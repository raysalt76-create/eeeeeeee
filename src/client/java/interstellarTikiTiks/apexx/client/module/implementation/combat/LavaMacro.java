package interstellarTikiTiks.apexx.client.module.implementation.combat;

import interstellarTikiTiks.apexx.client.util.combat.ModuleDelays;
import interstellarTikiTiks.apexx.client.util.combat.TickActionOrder;

import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.SubCategory;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.ModeSetting;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import interstellarTikiTiks.apexx.client.util.RotationManager;
import interstellarTikiTiks.apexx.client.util.ClickSimController;
import net.minecraft.block.BlockState;
import net.minecraft.fluid.Fluids;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

public class LavaMacro extends Module implements interstellarTikiTiks.apexx.client.module.hooks.ClickSimulating {
    // ---- Delays ----
    private final SliderSetting profileDelay = new SliderSetting("Pickup Delay (ms)", 30, 0, 1000, 0)
            .withJitter(10)
            .setDescription("How long the lava stays down before it is scooped back up. Longer gives it more time to set the target on fire; 0 scoops it on the next tick.")
            .setSection("Delays");
    private final SliderSetting pickupRetry = new SliderSetting("Pickup Retry (ms)", 75, 0, 250, 0)
            .withJitter(15)
            .setDescription("Minimum gap between scoop attempts while the previous one is unconfirmed. Attempts never come faster than vanilla's 4-tick item-use cadence, whatever this is set to.")
            .setSection("Delays");


    // One-shot: each press fires once (Tap) or repeats while held (Repeat);
    // a second press never cancels the in-flight sequence.
    @Override
    public boolean isOneShot() {
        return true;
    }


    private enum State {
        IDLE,
        PLACE,
        WAIT,
        PICKUP,
        VERIFY_PICKUP
    }

    private static final double REACH = 4.5;
    private static final float AIM_PITCH_TOLERANCE = 2.0f;
    private static final int MAX_AIM_TICKS = 20;

    private final ModeSetting aim = new ModeSetting("Aim", "Crosshair", "Crosshair", "Down", "Custom Pitch")
            .setDescription("Where the lava is poured. Crosshair: the block you are looking at. Down: straight below your feet (it turns for you). Custom Pitch: keeps your yaw and looks down at the pitch below, handy for pouring just in front of you.");
    private final SliderSetting aimPitch = new SliderSetting("Aim Pitch", 60.0, 0.0, 90.0, 0)
            .setDescription("Pitch in degrees used by Custom Pitch: 90 is straight down, lower values pour further in front of you.")
            .setDependsOn("Aim:Custom Pitch");
    private final BooleanSetting silentAim = new BooleanSetting("Silent Aim", false)
            .setDescription("For Down and Custom Pitch: turn only the rotation the server sees so your screen stays still. Off: your camera visibly turns down and back.");

    private int aimWaitTicks;
    private float aimYaw;

    private State state = State.IDLE;
    private int originalSlot = -1;
    /** The lava bucket slot this module selected; the swap-back only applies while it is still held. */
    private int ownedSlot = -1;
    private long placedAtMs = 0L;
    private BlockPos placedLavaPos = null;
    private long pickupStartedAtMs = 0L;
    private long lastPickupAttemptMs = 0L;

    public LavaMacro() {
        super("LavaMacro", "One key press pours a lava bucket from your hotbar, then scoops the lava back up after the Pickup Delay, so you burn an opponent without losing the bucket or leaving lava behind. It keeps retrying the scoop for up to 8 seconds until the bucket is confirmed full, then reselects your previous item.", Category.AUTOMATIONS);
        addSetting(profileDelay);
        addSetting(pickupRetry);
        setSubCategory(SubCategory.SMP);
        addSetting(aim);
        addSetting(aimPitch);
        addSetting(silentAim);
    }

    private boolean aimsManually() {
        return !"Crosshair".equals(aim.getCurrentMode());
    }

    private float targetPitch() {
        return "Down".equals(aim.getCurrentMode()) ? 89.9f : (float) aimPitch.getValue();
    }

    /**
     * Block under the ray the action will actually use: the wire ray from the final (sent) angles, in
     * every aim mode. Without an active rotation those are the camera angles.
     */
    private BlockHitResult liveHit() {
        net.minecraft.util.hit.HitResult hit =
                RotationManager.getCrosshairTarget(RotationManager.getFinalYaw(), RotationManager.getFinalPitch(), REACH);
        return hit instanceof BlockHitResult b && b.getType() == net.minecraft.util.hit.HitResult.Type.BLOCK ? b : null;
    }

    private void releaseAim() {
        RotationManager.clearTarget(this);
        RotationManager.stop(this);
    }

    @Override
    public void onEnable() {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) {
            toggle();
            return;
        }

        if (!aimsManually() && liveHit() == null) {
            toggle();
            return;
        }

        int lavaBucketSlot = findHotbarSlot(Items.LAVA_BUCKET);
        if (lavaBucketSlot == -1) {
            toggle();
            return;
        }

        originalSlot = mc.player.getInventory().getSelectedSlot();

        placedLavaPos = null;
        aimWaitTicks = 0;
        aimYaw = mc.player.getYaw();
        placedAtMs = 0L;
        pickupStartedAtMs = 0L;
        lastPickupAttemptMs = 0L;
        state = State.PLACE;
    }

    @Override
    public void onDisable() {
        releaseAim();
        restoreOwnedSlot();
        resetState();
    }

    @Override
    protected void onRuntimeStateReleased() {
        releaseAim();
        resetState();
        interstellarTikiTiks.apexx.client.util.combat.SlotSync.releaseOwner(this);
    }

    @Override
    public void onTick() {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) {
            toggle();
            return;
        }

        switch (state) {
            case PLACE -> {
                if (aimsManually()) {
                    // Step the owner-scoped rotation toward the chosen pitch; the lava goes once the live
                    // ray reaches a block, in the same tick as the bucket selection.
                    final float yaw = aimYaw, pitch = targetPitch();
                    RotationManager.aimAt(this, RotationManager.Priority.HIGHEST,
                            () -> mc.player.getEyePos().add(Vec3d.fromPolar(MathHelper.clamp(pitch, -89.9f, 89.9f), yaw).multiply(100.0)),
                            35.0, 0.0, silentAim.isEnabled());
                    if (Math.abs(RotationManager.getFinalPitch() - pitch) > AIM_PITCH_TOLERANCE || liveHit() == null) {
                        if (++aimWaitTicks > MAX_AIM_TICKS) finish();
                        return;
                    }
                } else if (liveHit() == null) {
                    finish();
                    return;
                }
                // Select the bucket and use it in the same call (same tick). If the hotbar cannot
                // change yet this packet interval, stay in PLACE and retry next tick.
                int lavaBucketSlot = findHotbarSlot(Items.LAVA_BUCKET);
                if (lavaBucketSlot == -1) {
                    finish();
                    return;
                }
                if (mc.player.getInventory().getSelectedSlot() != lavaBucketSlot
                        && (!TickActionOrder.canSwitchSlot()
                        || !interstellarTikiTiks.apexx.client.util.combat.SlotSync.select(mc, lavaBucketSlot))) {
                    return;
                }
                ownedSlot = lavaBucketSlot;
                BlockHitResult placeHit = liveHit();
                if (placeHit != null) placedLavaPos = placeHit.getBlockPos().offset(placeHit.getSide());
                ClickSimController.use(mc);
                TickActionOrder.recordUse();
                placedAtMs = System.currentTimeMillis() + ModuleDelays.clampMs(profileDelay.sample());
                state = State.WAIT;
            }
            case WAIT -> {
                if (System.currentTimeMillis() >= placedAtMs) {
                    state = State.PICKUP;
                }
            }
            case PICKUP -> {
                if (pickupStartedAtMs == 0L) {
                    pickupStartedAtMs = System.currentTimeMillis();
                }
                // Keep retrying for a while instead of finishing after one click.
                if (System.currentTimeMillis() - pickupStartedAtMs > 8000L) {
                    finish();
                    return;
                }
                if (aimsManually()) {
                    // Keep the same aim while scooping so the bucket reaches the lava just placed.
                    final float yaw = aimYaw, pitch = targetPitch();
                    RotationManager.aimAt(this, RotationManager.Priority.HIGHEST,
                            () -> mc.player.getEyePos().add(Vec3d.fromPolar(MathHelper.clamp(pitch, -89.9f, 89.9f), yaw).multiply(100.0)),
                            35.0, 0.0, silentAim.isEnabled());
                }
                if (!isLookingAtLava()) {
                    return;
                }
                // Pickup Retry, and never faster than vanilla's own 4-tick item-use cadence.
                if (System.currentTimeMillis() < lastPickupAttemptMs
                        || !interstellarTikiTiks.apexx.client.util.combat.InteractSim.useReady(mc)) {
                    return;
                }
                ClickSimController.use(mc);
                TickActionOrder.recordUse();
                lastPickupAttemptMs = System.currentTimeMillis() + ModuleDelays.clampMs(pickupRetry.sample());
                state = State.VERIFY_PICKUP;
            }
            case VERIFY_PICKUP -> {
                if (mc.player.getMainHandStack().isOf(Items.LAVA_BUCKET)) {
                    finish();
                    return;
                }
                // If pickup didn't happen, try again.
                state = State.PICKUP;
            }
            case IDLE -> {
            }
        }
    }

    /**
     * A lava source is on the live ray. The vanilla crosshair ignores fluids (the ray passes through
     * the source into the block below), so the bucket's own fluid-aware ray is used, from the same
     * angles the use will be dispatched with.
     */
    private boolean isLookingAtLava() {
        float yaw = RotationManager.getFinalYaw();
        float pitch = RotationManager.getFinalPitch();
        Vec3d eye = mc.player.getEyePos();
        Vec3d end = eye.add(Vec3d.fromPolar(pitch, yaw).multiply(REACH));
        BlockHitResult hit = mc.world.raycast(new net.minecraft.world.RaycastContext(eye, end,
                net.minecraft.world.RaycastContext.ShapeType.OUTLINE,
                net.minecraft.world.RaycastContext.FluidHandling.SOURCE_ONLY, mc.player));
        if (hit == null || hit.getType() != net.minecraft.util.hit.HitResult.Type.BLOCK) {
            return false;
        }
        return mc.world.getBlockState(hit.getBlockPos()).getFluidState().isOf(Fluids.LAVA);
    }

    private int findHotbarSlot(net.minecraft.item.Item item) {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (!stack.isEmpty() && stack.getItem() == item) {
                return i;
            }
        }
        return -1;
    }

    private void finish() {
        releaseAim();
        restoreOwnedSlot();
        toggle();
    }

    /** Back to the starting slot, only while the bucket slot this module selected is still held; deferred past a use. */
    private void restoreOwnedSlot() {
        if (originalSlot >= 0 && originalSlot < 9 && ownedSlot >= 0 && mc.player != null
                && mc.player.getInventory().getSelectedSlot() == ownedSlot) {
            interstellarTikiTiks.apexx.client.util.combat.SlotSync.restore(mc, originalSlot);
        }
        ownedSlot = -1;
    }

    private void resetState() {
        state = State.IDLE;
        originalSlot = -1;
        ownedSlot = -1;
        placedAtMs = 0L;
        placedLavaPos = null;
        pickupStartedAtMs = 0L;
        lastPickupAttemptMs = 0L;
    }
}
