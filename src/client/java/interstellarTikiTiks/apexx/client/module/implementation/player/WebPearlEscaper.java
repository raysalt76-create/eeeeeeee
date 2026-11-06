package interstellarTikiTiks.apexx.client.module.implementation.player;

import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import interstellarTikiTiks.apexx.client.util.Notifier;
import interstellarTikiTiks.apexx.client.util.RotationManager;
import interstellarTikiTiks.apexx.client.util.combat.PearlLimit;
import interstellarTikiTiks.apexx.client.util.combat.PearlSimulation;
import interstellarTikiTiks.apexx.client.util.combat.PearlThrow;
import interstellarTikiTiks.apexx.client.util.combat.SlotSync;
import net.minecraft.block.Blocks;
import net.minecraft.item.Items;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * The pearl half of {@link WebEscape}: throws an ender pearl out of a cobweb you are stuck in.
 *
 * <p><b>Trigger.</b> Your box has been inside a cobweb for "Stuck Time" ms. WebEscape decides whether the
 * pearl or the water goes first.</p>
 *
 * <p><b>Safety.</b> A pearl landing deals 5 damage in vanilla. With "Health Check" on (default) no pearl
 * is thrown while health plus absorption is below "Min Health", and the global Pearl Limit
 * ({@link PearlLimit}) is respected, so the last pearls stay with you.</p>
 *
 * <p><b>Aim.</b> The throw direction is chosen by simulating the pearl's flight
 * ({@link PearlSimulation}) from the live tick state: a candidate counts only when the pearl ends on a
 * block with room to stand at least "Distance" blocks away. "Auto Aim" turns toward the best candidate
 * through the shared {@link RotationManager} (optionally silent); with it off the pearl goes where you
 * are already looking, and only if that landing is safe. The pearl is thrown once the live angles have
 * reached the planned ones and the re-simulated landing is still safe, with the slot selected first in
 * the same tick; the slot is restored on a later tick.</p>
 */
final class WebPearlEscaper implements interstellarTikiTiks.apexx.client.module.hooks.ClickSimulating {
    private final Module owner;
    private final net.minecraft.client.MinecraftClient mc = net.minecraft.client.MinecraftClient.getInstance();

    private final BooleanSetting healthCheck = new BooleanSetting("Health Check", true)
            .setDescription("Never throws the escape pearl while your health is below Min Health, since a pearl landing costs 5 health (2.5 hearts).");
    private final SliderSetting minHealth = new SliderSetting("Min Health", 6.0, 1.0, 20.0, 1)
            .setDescription("Health points (2 = one heart), absorption included, you must have for the pearl to be thrown.");
    private final SliderSetting distance = new SliderSetting("Distance", 10.0, 4.0, 30.0, 0)
            .setDescription("Minimum distance in blocks from the web where the pearl has to land; landing spots closer than this are ignored.");
    private final SliderSetting stuckTime = new SliderSetting("Stuck Time", 150, 0, 1000, 0)
            .withJitter(40)
            .setDescription("How long in ms you must be inside the web before the pearl is thrown, so brief web touches don't waste pearls.");
    private final BooleanSetting autoAim = new BooleanSetting("Auto Aim", true)
            .setDescription("Turns toward the safest reachable landing spot before throwing. Off throws along your current look, and only if that lands safely.");
    private final BooleanSetting silentAim = new BooleanSetting("Pearl Silent Aim", false)
            .setDescription("Turns only the angles sent to the server for the throw; your camera keeps its view.")
            .setDependsOn("Auto Aim");
    private final SliderSetting cooldownMs = new SliderSetting("Pearl Cooldown", 1500, 500, 5000, 0)
            .withJitter(250)
            .setDescription("Rest time in ms after a pearl attempt (thrown or abandoned) before another can start. The 500 ms minimum covers vanilla's pearl cooldown.");

    private static final double REACH_TOLERANCE = 1.5;
    private static final long AIM_TIMEOUT_MS = 1000L;
    private static final double AIM_STRENGTH = 60.0;
    private static final int SIM_TICKS = 120;
    private static final int YAW_STEPS = 24;
    private static final float[] PITCHES = {-5f, -15f, -25f, -35f, -45f, -55f, -65f};

    private enum Phase { IDLE, AIMING, RESTORE }

    private Phase phase = Phase.IDLE;
    private long stuckSinceMs;
    private long stuckWaitMs;
    private long engagedMs;
    private long cooldownUntilMs;
    private int originalSlot = -1;
    private int pearlSlot = -1;
    private float planYaw;
    private float planPitch;
    private Object boundPlayer;
    private Object boundWorld;

    WebPearlEscaper(Module owner) {
        this.owner = owner;
        minHealth.setVisibility(healthCheck::isEnabled);
        for (var setting : new interstellarTikiTiks.apexx.client.module.Setting[]{
                healthCheck, minHealth, distance, stuckTime, autoAim, silentAim, cooldownMs}) {
            setting.setSection("Pearl");
        }
    }

    /** The settings WebEscape adds to its own list. */
    java.util.List<interstellarTikiTiks.apexx.client.module.Setting> settings() {
        return java.util.List.of(healthCheck, minHealth, distance, stuckTime, autoAim, silentAim, cooldownMs);
    }

    boolean isBusy() {
        return phase != Phase.IDLE;
    }

    /** Ends any engagement and releases the rotation, slot and input state it owns. Idempotent. */
    void release() {
        RotationManager.clearTarget(owner);
        RotationManager.stop(owner);
        if (phase == Phase.RESTORE && mc.player != null && mc.player == boundPlayer && originalSlot >= 0
                && SlotSync.current(mc) == pearlSlot) {
            SlotSync.restore(mc, originalSlot);
        }
        SlotSync.releaseOwner(owner);
        reset();
    }

    /** One tick: continues a running escape, or starts one when stuck. */
    void tick() {
        if (mc.player == null || mc.world == null) {
            if (phase != Phase.IDLE) release();
            return;
        }
        if (phase != Phase.IDLE && (mc.player != boundPlayer || mc.world != boundWorld)) {
            release();
            return;
        }
        switch (phase) {
            case IDLE -> tryEngage();
            case AIMING -> tickAim();
            case RESTORE -> finish();
        }
    }

    private void tryEngage() {
        long now = System.currentTimeMillis();
        if (!inWeb()) {
            stuckSinceMs = 0L;
            return;
        }
        if (stuckSinceMs == 0L) {
            stuckSinceMs = now;
            stuckWaitMs = (long) Math.max(0.0, stuckTime.sample());
        }
        if (now - stuckSinceMs < stuckWaitMs || now < cooldownUntilMs
                || mc.currentScreen != null || mc.player.isSpectator()) {
            return;
        }
        if (healthCheck.isEnabled()
                && mc.player.getHealth() + mc.player.getAbsorptionAmount() < (float) minHealth.getValue()) {
            return;
        }
        if (!PearlLimit.canThrow(mc) || PearlThrow.cooling(mc)) return;
        int slot = findPearl();
        if (slot < 0) return;
        if (!plan()) {
            cooldownUntilMs = now + (long) cooldownMs.sample();
            Notifier.warn(owner.getName(), "No safe pearl landing found");
            return;
        }
        boundPlayer = mc.player;
        boundWorld = mc.world;
        originalSlot = SlotSync.current(mc);
        pearlSlot = slot;
        engagedMs = now;
        phase = Phase.AIMING;
        tickAim();
    }

    private void tickAim() {
        long now = System.currentTimeMillis();
        if (now - engagedMs > AIM_TIMEOUT_MS || !inWeb() || mc.currentScreen != null
                || !mc.player.getInventory().getStack(pearlSlot).isOf(Items.ENDER_PEARL)
                || !PearlLimit.canThrow(mc)) {
            end(now);
            return;
        }
        float yaw;
        float pitch;
        if (autoAim.isEnabled()) {
            if (!plan()) {
                end(now);
                return;
            }
            final Vec3d point = mc.player.getEyePos().add(Vec3d.fromPolar(planPitch, planYaw).multiply(60.0));
            RotationManager.aimAt(owner, RotationManager.Priority.HIGHEST, () -> point, AIM_STRENGTH, 0.3,
                    silentAim.isEnabled());
            yaw = RotationManager.getFinalYaw();
            pitch = RotationManager.getFinalPitch();
            if (Math.abs(MathHelper.wrapDegrees(yaw - planYaw)) > REACH_TOLERANCE
                    || Math.abs(pitch - planPitch) > REACH_TOLERANCE) {
                return;
            }
        } else {
            yaw = mc.player.getYaw();
            pitch = mc.player.getPitch();
        }
        // The throw is eligible only when this exact look still lands somewhere safe.
        if (!landingSafe(yaw, pitch)) {
            if (!autoAim.isEnabled()) end(now);
            return;
        }
        if (SlotSync.current(mc) != pearlSlot && !SlotSync.select(mc, pearlSlot)) return;
        if (PearlThrow.use(mc, yaw, pitch)) {
            RotationManager.setPostAttackHold(owner, 1, yaw, pitch);
            phase = Phase.RESTORE;
        } else {
            end(now);
        }
    }

    /** Next tick: slot back (only while we still hold the pearl slot), rotation released. */
    private void finish() {
        long now = System.currentTimeMillis();
        end(now);
    }

    private void end(long now) {
        RotationManager.clearTarget(owner);
        RotationManager.stop(owner);
        if (mc.player != null && mc.player == boundPlayer && originalSlot >= 0 && originalSlot != pearlSlot
                && SlotSync.current(mc) == pearlSlot) {
            SlotSync.restore(mc, originalSlot);
        }
        SlotSync.releaseOwner(owner);
        cooldownUntilMs = now + (long) cooldownMs.sample();
        reset();
    }

    private void reset() {
        phase = Phase.IDLE;
        originalSlot = -1;
        pearlSlot = -1;
        boundPlayer = null;
        boundWorld = null;
        stuckSinceMs = 0L;
    }

    // ── Planning ─────────────────────────────────────────────────────────

    /** Picks the safe landing closest to the wanted distance; false when none exists. */
    private boolean plan() {
        if (!autoAim.isEnabled()) {
            planYaw = mc.player.getYaw();
            planPitch = mc.player.getPitch();
            return landingSafe(planYaw, planPitch);
        }
        double best = Double.MAX_VALUE;
        boolean found = false;
        float baseYaw = mc.player.getYaw();
        for (int i = 0; i < YAW_STEPS; i++) {
            float yaw = MathHelper.wrapDegrees(baseYaw + i * (360f / YAW_STEPS));
            for (float pitch : PITCHES) {
                Vec3d land = landing(yaw, pitch);
                if (land == null) continue;
                double dist = horizontalDistance(land);
                if (dist < distance.getValue()) continue;
                // Prefer the nearest acceptable landing, then the smallest turn from where we look.
                double cost = dist + Math.abs(MathHelper.wrapDegrees(yaw - baseYaw)) * 0.05;
                if (cost < best) {
                    best = cost;
                    planYaw = yaw;
                    planPitch = pitch;
                    found = true;
                }
            }
        }
        return found;
    }

    private boolean landingSafe(float yaw, float pitch) {
        Vec3d land = landing(yaw, pitch);
        return land != null && horizontalDistance(land) >= distance.getValue();
    }

    private double horizontalDistance(Vec3d p) {
        double dx = p.x - mc.player.getX();
        double dz = p.z - mc.player.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** Where a pearl thrown with this look ends, or null when it ends in unsafe ground. */
    private Vec3d landing(float yaw, float pitch) {
        PearlSimulation.Path path = PearlSimulation.thrown(mc.world, mc.player, yaw, pitch,
                mc.player.getVelocity(), Vec3d.ZERO).trace(SIM_TICKS);
        HitResult end = path.end();
        if (!(end instanceof BlockHitResult block) || end.getType() != HitResult.Type.BLOCK) return null;
        BlockPos at = block.getBlockPos().offset(block.getSide());
        // Room to stand: the landing cell and the one above must be free, not web, no fluid or fire.
        for (int dy = 0; dy < 2; dy++) {
            BlockPos cell = at.up(dy);
            var state = mc.world.getBlockState(cell);
            if (state.isOf(Blocks.COBWEB) || !state.getFluidState().isEmpty() || state.isOf(Blocks.FIRE)
                    || state.isOf(Blocks.LAVA) || !state.getCollisionShape(mc.world, cell).isEmpty()) {
                return null;
            }
        }
        return end.getPos();
    }

    // ── World queries ────────────────────────────────────────────────────

    private boolean inWeb() {
        var box = mc.player.getBoundingBox().contract(1.0e-4);
        int minX = MathHelper.floor(box.minX), maxX = MathHelper.floor(box.maxX);
        int minY = MathHelper.floor(box.minY), maxY = MathHelper.floor(box.maxY);
        int minZ = MathHelper.floor(box.minZ), maxZ = MathHelper.floor(box.maxZ);
        BlockPos.Mutable cursor = new BlockPos.Mutable();
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    cursor.set(x, y, z);
                    if (mc.world.getBlockState(cursor).isOf(Blocks.COBWEB)) return true;
                }
            }
        }
        return false;
    }

    private int findPearl() {
        int held = SlotSync.current(mc);
        if (mc.player.getInventory().getStack(held).isOf(Items.ENDER_PEARL)) return held;
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).isOf(Items.ENDER_PEARL)) return i;
        }
        return -1;
    }
}
