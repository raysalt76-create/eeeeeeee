package interstellarTikiTiks.apexx.client.module.implementation.bedwars;

import interstellarTikiTiks.apexx.client.friend.FriendManager;
import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.ModeSetting;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import interstellarTikiTiks.apexx.client.module.hooks.AttackTargetAware;
import interstellarTikiTiks.apexx.client.module.hooks.ItemUseBlockProvider;
import interstellarTikiTiks.apexx.client.module.hooks.OutgoingPacketInterceptor;
import interstellarTikiTiks.apexx.client.module.implementation.player.BotFilterProvider;
import interstellarTikiTiks.apexx.client.util.ModuleAccess;
import interstellarTikiTiks.apexx.client.util.SystemInputSimulator;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.common.KeepAliveC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.util.math.MathHelper;

import java.util.ArrayDeque;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Block-hitting with four timing strategies. Every block is the use key held through
 * {@link SystemInputSimulator} (the same input a player gives by holding right click), so the item use goes
 * through vanilla {@code doItemUse}/{@code stopUsingItem} and honours the execution mode and the use cadence.
 *
 * <ul>
 *   <li><b>Manual</b>: after each of your hits on a target, a short block with a set chance (blocks per second is
 *       roughly your CPS times the chance).</li>
 *   <li><b>Auto</b>: a block after every Nth hit while you stay on a target, held for a sampled time and lowered
 *       before your next hit is ready.</li>
 *   <li><b>Predict</b>: reads your own hurt time and the rhythm of the hits you take, and raises the block just
 *       before you can be damaged again; lowers when the hit lands or a short hold runs out.</li>
 *   <li><b>Lag</b> (blatant): toggles the block every tick near a target and, each time the block is released,
 *       holds your outgoing packets for a short delay so the server keeps seeing the block. Packet holding is not
 *       something a vanilla client does.</li>
 * </ul>
 *
 * <p>"Sword (1.8)" relies on a legacy server (through ViaVersion) turning a held-sword use into a 1.8 block;
 * "Shield (1.9+)" uses a real blocking item. UNVERIFIED on a live server.
 */
public class AutoBlock extends Module implements AttackTargetAware, ItemUseBlockProvider, OutgoingPacketInterceptor, interstellarTikiTiks.apexx.client.module.hooks.ClickSimulating {

    private static final String MANUAL = "Manual";
    private static final String AUTO = "Auto";
    private static final String PREDICT = "Predict";
    private static final String LAG = "Lag";

    private final ModeSetting mode = new ModeSetting("Mode", MANUAL, MANUAL, AUTO, PREDICT, LAG)
            .setDescription("Manual: a short block after your hits, by chance (blocks/s = CPS x chance). "
                    + "Auto: blocks after every Nth hit and lowers before your next hit. "
                    + "Predict: blocks just before you can be hit again. "
                    + "Lag (blatant): holds your packets after each block so the server sees a longer block.");
    private final ModeSetting blockType = new ModeSetting("Block Type", "Sword (1.8)", "Sword (1.8)", "Shield (1.9+)")
            .setDescription("Sword (1.8): right-click sword block for legacy (Bedwars) servers via ViaVersion. Shield (1.9+): raise a shield (offhand or main hand) for modern combat.");
    private final BooleanSetting requireMouseDown = new BooleanSetting("Require Mouse Down", true)
            .setDescription("Only block-hits while you hold the use (right) button.");
    private final BooleanSetting ignoreManualBlock = new BooleanSetting("Ignore Manual Block", false)
            .setDependsOn("Require Mouse Down")
            .setDescription("While a target is in range, holding right click only arms the module: your own right click does not block by itself. Away from targets your right click always works (shops, chests, placing).");
    /** A target was in range on the last tick: the only time Ignore Manual Block may swallow the right click. */
    private boolean targetNear;
    private final SliderSetting angle = new SliderSetting("Angle", 90, 0, 360, 0)
            .setDescription("A target must be within this view angle (degrees, centred on your crosshair).");
    private final SliderSetting minDistance = new SliderSetting("Min Distance", 0.0, 0.0, 6.0, 2)
            .setDescription("The target must be at least this far away (blocks).");
    private final SliderSetting maxDistance = new SliderSetting("Max Distance", 3.0, 0.0, 6.0, 2)
            .setDescription("The target must be within this distance (blocks).");
    private final BooleanSetting playersOnly = new BooleanSetting("Players Only", true)
            .setDescription("Only players count as targets (never armor stands or mobs). Friends and filtered bots are always skipped.");
    private final BooleanSetting onlyWhileAttacking = new BooleanSetting("Only While Attacking", true)
            .setDependsOn("Mode:Manual|Auto")
            .setDescription("Only blocks while you physically hold the attack button, and lowers the moment you let go.");
    private final SliderSetting chance = new SliderSetting("Chance %", 100, 0, 100, 0)
            .setDependsOn("Mode:Manual|Auto")
            .setDescription("Odds that a hit is followed by a block.");
    private final SliderSetting blockEvery = new SliderSetting("Block Every (hits)", 3, 1, 10, 0)
            .setDependsOn("Mode:Auto")
            .setDescription("Auto: block after the first of every N hits in a row on a target.");
    private final SliderSetting maxHurtTime = new SliderSetting("Max Hurt Time (ms)", 50, 0, 500, 0)
            .setDependsOn("Mode:Predict")
            .setDescription("Predict: start blocking this long before you can take damage again.");
    private final BooleanSetting includePing = new BooleanSetting("Include Ping", true)
            .setDependsOn("Mode:Predict")
            .setDescription("Predict: add your ping to Max Hurt Time.");

    private final SliderSetting raiseDelay = new SliderSetting("Raise Delay (ms)", 40, 0, 250, 0).withJitter(15)
            .setSection("Delays")
            .setDependsOn("Mode:Manual|Auto")
            .setDescription("Wait after your hit before the block is raised.");
    private final SliderSetting manualHold = new SliderSetting("Manual Hold (ms)", 50, 0, 500, 0).withJitter(10)
            .setSection("Delays")
            .setDependsOn("Mode:Manual")
            .setDescription("Manual: how long each block is held.");
    private final SliderSetting holdTime = new SliderSetting("Block Hold (ms)", 250, 50, 1000, 0).withJitter(70)
            .setSection("Delays")
            .setDependsOn("Mode:Auto")
            .setDescription("Auto: how long the block is held (it is lowered earlier if your next attack is about to be ready).");
    private final SliderSetting lowerMargin = new SliderSetting("Lower Margin (ms)", 150, 50, 500, 0).withJitter(30)
            .setSection("Delays")
            .setDependsOn("Mode:Manual|Auto")
            .setDescription("Shield: the block is lowered at least this long before your weapon is fully charged again, so the next hit is never swallowed.");
    private final SliderSetting holdAfter = new SliderSetting("Hold After (ticks)", 2, 0, 10, 0)
            .setSection("Delays")
            .setDependsOn("Mode:Predict")
            .setDescription("Predict: keep blocking this many ticks after you become hittable if no hit arrives.");
    private final SliderSetting lagDelay = new SliderSetting("Lag Delay (ms)", 100, 0, 500, 0).withJitter(25)
            .setSection("Delays")
            .setDependsOn("Mode:Lag")
            .setBlatant(true)
            .setDescription("Lag (blatant): how long your outgoing packets are held after each block release.");

    /** Vanilla shield raise time before it actually blocks (5 ticks). */
    private static final long SHIELD_RAISE_MS = 250L;
    private static final long TICK_MS = 50L;
    /** Lag: re-raise the block this long before the held packets go out. */
    private static final long LAG_REBLOCK_LEAD_MS = 50L;
    private static final int LAG_MAX_QUEUED = 300;

    private boolean blocking;
    /** True while this module decides the use key (valid item, no screen, armed). */
    private boolean managingUse;

    // Manual / Auto
    private long raiseAtMs = -1L;
    private long lowerAtMs = -1L;
    private boolean pendingShortHold;
    private int hitStreak;
    private long lastHitMs;

    // Predict
    private final DamageRhythm rhythm = new DamageRhythm();
    private int lastHurtTime;
    private long holdUntilMs = -1L;

    // Lag
    private final ArrayDeque<Packet<?>> lagQueue = new ArrayDeque<>();
    private volatile boolean lagEngaged;
    private boolean lagBuffering;
    private long lagStartMs;
    private long lagDelayMs;
    private ClientPlayNetworkHandler lagHandler;
    private volatile boolean lagFlushing;

    public AutoBlock() {
        super("AutoBlock", "Block-hitting with four modes. Manual: a short block after your hits by chance. Auto: blocks after every Nth hit and lowers before your next one. Predict: blocks just before you can be hit again. Lag (blatant): holds your outgoing packets after each block so the server sees it for longer; packet holding is not vanilla behaviour and servers with lag checks can flag it. Sword (1.8) is the Bedwars default (legacy sword block via ViaVersion); Shield (1.9+) uses a real shield.", Category.BEDWARS);
        addSetting(mode);
        addSetting(blockType);
        addSetting(requireMouseDown);
        addSetting(ignoreManualBlock);
        addSetting(angle);
        addSetting(minDistance);
        addSetting(maxDistance);
        addSetting(playersOnly);
        addSetting(onlyWhileAttacking);
        addSetting(chance);
        addSetting(blockEvery);
        addSetting(maxHurtTime);
        addSetting(includePing);
        addSetting(raiseDelay);
        addSetting(manualHold);
        addSetting(holdTime);
        addSetting(lowerMargin);
        addSetting(holdAfter);
        addSetting(lagDelay);
    }

    @Override
    public boolean isBlatantFlagged() {
        return mode.is(LAG);
    }

    @Override
    public void onEnable() {
        resetState();
    }

    @Override
    public void onDisable() {
        releaseAll();
    }

    @Override
    protected void onRuntimeStateReleased() {
        releaseAll();
    }

    private void releaseAll() {
        flushLag();
        setBlocking(false);
        if (managingUse && mc.options != null) SystemInputSimulator.restorePhysicalState(mc.options.useKey);
        resetState();
    }

    private void resetState() {
        blocking = false;
        managingUse = false;
        raiseAtMs = -1L;
        lowerAtMs = -1L;
        pendingShortHold = false;
        hitStreak = 0;
        lastHitMs = 0L;
        rhythm.reset();
        lastHurtTime = mc.player != null ? mc.player.hurtTime : 0;
        holdUntilMs = -1L;
        lagEngaged = false;
        lagBuffering = false;
    }

    // ------------------------------------------------------------------ tick

    @Override
    public void onTick() {
        if (!isEnabled() || mc.player == null || mc.world == null || mc.options == null) return;
        ClientPlayerEntity p = mc.player;
        long now = System.currentTimeMillis();

        boolean usable = mc.currentScreen == null && p.isAlive() && hasBlockItem(p);
        boolean armed = !requireMouseDown.isEnabled() || SystemInputSimulator.isPhysicallyPressed(mc.options.useKey);
        if (!usable || !armed) {
            disengage();
            return;
        }
        managingUse = true;
        targetNear = findTarget(p) != null;

        switch (mode.getCurrentMode()) {
            case PREDICT -> tickPredict(p, now);
            case LAG -> tickLag(p, now);
            default -> tickHitFollow(p, now);
        }
        if (!LAG.equals(mode.getCurrentMode()) && lagBuffering) flushLag();

        // Ignore Manual Block: the held right button only arms the module, it does not block by itself.
        if (!blocking && targetNear && requireMouseDown.isEnabled() && ignoreManualBlock.isEnabled()) {
            mc.options.useKey.setPressed(false);
        }
    }

    private void disengage() {
        flushLag();
        targetNear = false;
        lagEngaged = false;
        setBlocking(false);
        if (managingUse && mc.options != null) SystemInputSimulator.restorePhysicalState(mc.options.useKey);
        managingUse = false;
        raiseAtMs = -1L;
        holdUntilMs = -1L;
        if (mc.player != null) lastHurtTime = mc.player.hurtTime;
    }

    // ------------------------------------------------------------------ Manual / Auto

    @Override
    public void onAttack(Entity target) {
        if (!isEnabled() || !managingUse || mc.player == null) return;
        String m = mode.getCurrentMode();
        if (!MANUAL.equals(m) && !AUTO.equals(m)) return;
        if (!isValidTarget(mc.player, target, false)) return;
        long now = System.currentTimeMillis();

        boolean block;
        if (AUTO.equals(m)) {
            // A hit more than ~1.5 s after the last one starts a new streak.
            if (now - lastHitMs > 1500L) hitStreak = 0;
            int every = Math.max(1, (int) blockEvery.getValue());
            block = hitStreak % every == 0;
            hitStreak++;
        } else {
            block = true;
        }
        lastHitMs = now;
        if (block && !blocking && raiseAtMs < 0L
                && ThreadLocalRandom.current().nextDouble(100.0) < chance.getValue()) {
            raiseAtMs = now + (long) raiseDelay.sample();
            pendingShortHold = MANUAL.equals(m);
        }
    }

    private void tickHitFollow(ClientPlayerEntity p, long now) {
        if (onlyWhileAttacking.isEnabled() && !SystemInputSimulator.isPhysicallyPressed(mc.options.attackKey)) {
            setBlocking(false);
            raiseAtMs = -1L;
            return;
        }
        boolean shield = shieldType();
        long remainingMs = remainingCooldownMs(p);
        long margin = (long) lowerMargin.sample();

        if (raiseAtMs >= 0L && now >= raiseAtMs) {
            raiseAtMs = -1L;
            // A shield needs its 0.25 s raise to beat the next attack to be worth it; a 1.8 block is instant.
            if (!blocking && (!shield || remainingMs > SHIELD_RAISE_MS + margin)) {
                long hold = pendingShortHold ? (long) manualHold.sample() : (long) holdTime.sample();
                lowerAtMs = now + Math.max(TICK_MS, hold);
                setBlocking(true);
            }
        }
        if (blocking && (now >= lowerAtMs || (shield && remainingMs <= margin))) {
            setBlocking(false);
        }
    }

    // ------------------------------------------------------------------ Predict

    private void tickPredict(ClientPlayerEntity p, long now) {
        int hurt = p.hurtTime;
        boolean damaged = hurt > lastHurtTime && hurt >= 9;
        lastHurtTime = hurt;

        if (findTarget(p) == null) {
            rhythm.reset();
            holdUntilMs = -1L;
            setBlocking(false);
            return;
        }
        if (damaged) {
            // The hit landed: lower now and learn the attacker's rhythm.
            rhythm.record(now);
            holdUntilMs = -1L;
            setBlocking(false);
            return;
        }

        long early = maxHurtTime.getValue() > 0 ? (long) maxHurtTime.getValue() : 0L;
        if (includePing.isEnabled()) early += pingMs();
        early += TICK_MS;
        long untilHittable = hurt * TICK_MS;
        int holdTicks = (int) holdAfter.getValue();

        if (rhythm.isStable()) {
            long expected = rhythm.expectedNextHit();
            boolean inWindow = now >= expected - early && now <= expected + holdTicks * TICK_MS;
            setBlocking(inWindow && untilHittable <= early);
            return;
        }
        if (hurt > 0) {
            holdUntilMs = -1L;
            setBlocking(untilHittable <= early);
        } else if (blocking) {
            if (holdTicks <= 0) {
                setBlocking(false);
            } else {
                if (holdUntilMs < 0L) holdUntilMs = now + holdTicks * TICK_MS;
                if (now >= holdUntilMs) setBlocking(false);
            }
        }
    }

    private long pingMs() {
        if (mc.getNetworkHandler() == null || mc.player == null) return 0L;
        PlayerListEntry entry = mc.getNetworkHandler().getPlayerListEntry(mc.player.getUuid());
        return entry == null ? 0L : Math.max(0, Math.min(1000, entry.getLatency()));
    }

    // ------------------------------------------------------------------ Lag (blatant)

    private void tickLag(ClientPlayerEntity p, long now) {
        lagEngaged = findTarget(p) != null;
        if (lagBuffering) {
            long elapsed = now - lagStartMs;
            if (!lagEngaged || elapsed >= lagDelayMs) {
                flushLag();
            } else if (elapsed >= lagDelayMs - LAG_REBLOCK_LEAD_MS && !blocking) {
                setBlocking(true);
            }
            return;
        }
        if (!lagEngaged) {
            setBlocking(false);
            return;
        }
        // One tick blocked, one released; the release packet starts the hold (see handleOutgoingPacket).
        if (blocking) {
            setBlocking(false);
        } else if (!p.isUsingItem()) {
            setBlocking(true);
        }
    }

    @Override
    public boolean handleOutgoingPacket(Packet<?> packet) {
        if (lagFlushing || !isEnabled() || !mode.is(LAG) || !mc.isOnThread()) return false;
        synchronized (lagQueue) {
            if (lagBuffering) {
                if (packet instanceof KeepAliveC2SPacket) return false;
                long elapsed = System.currentTimeMillis() - lagStartMs;
                if (!lagEngaged || elapsed >= lagDelayMs || lagQueue.size() >= LAG_MAX_QUEUED) {
                    flushLag();
                    return false;
                }
                lagQueue.addLast(packet);
                return true;
            }
            if (lagEngaged && packet instanceof PlayerActionC2SPacket action
                    && action.getAction() == PlayerActionC2SPacket.Action.RELEASE_USE_ITEM) {
                lagBuffering = true;
                lagStartMs = System.currentTimeMillis();
                lagDelayMs = (long) lagDelay.sample();
                lagHandler = mc.getNetworkHandler();
                lagQueue.addLast(packet);
                return true;
            }
        }
        return false;
    }

    /** Sends every held packet in order (or drops them if the connection changed). Idempotent. */
    private void flushLag() {
        Packet<?>[] pending;
        ClientPlayNetworkHandler handler;
        synchronized (lagQueue) {
            lagBuffering = false;
            if (lagQueue.isEmpty()) {
                lagHandler = null;
                return;
            }
            pending = lagQueue.toArray(new Packet<?>[0]);
            lagQueue.clear();
            handler = lagHandler;
            lagHandler = null;
        }
        if (handler == null || handler != mc.getNetworkHandler()) return;
        lagFlushing = true;
        try {
            for (Packet<?> packet : pending) handler.sendPacket(packet);
        } catch (Throwable ignored) {
            // A failed re-send must not take the game down; the queue is already cleared.
        } finally {
            lagFlushing = false;
        }
    }

    // ------------------------------------------------------------------ use key

    @Override
    public boolean shouldBlockItemUse() {
        return isEnabled() && managingUse && !blocking && targetNear
                && requireMouseDown.isEnabled() && ignoreManualBlock.isEnabled();
    }

    private void setBlocking(boolean block) {
        if (blocking == block) return;
        blocking = block;
        if (block) {
            SystemInputSimulator.pressUse();
        } else {
            SystemInputSimulator.releaseUse();
            // The release restores the physical state; when the held right button only arms the module, the
            // block must still come down.
            if (requireMouseDown.isEnabled() && mc.options != null) mc.options.useKey.setPressed(false);
            lowerAtMs = -1L;
        }
    }

    // ------------------------------------------------------------------ helpers

    private boolean shieldType() {
        return blockType.is("Shield (1.9+)");
    }

    private boolean hasBlockItem(ClientPlayerEntity p) {
        return shieldType() ? hasBlockingItem(p) : hasWeapon(p);
    }

    /** A held sword/axe/weapon to block with (1.8): any main-hand item that is not a consumable, block or bow. */
    private static boolean hasWeapon(ClientPlayerEntity p) {
        ItemStack main = p.getMainHandStack();
        return !main.isEmpty() && !hasOwnUse(main);
    }

    /** A shield (or anything with the blocking component), with no main-hand item that would be used first. */
    private static boolean hasBlockingItem(ClientPlayerEntity p) {
        ItemStack main = p.getMainHandStack();
        if (main.contains(DataComponentTypes.BLOCKS_ATTACKS)) return true;
        return p.getOffHandStack().contains(DataComponentTypes.BLOCKS_ATTACKS) && !hasOwnUse(main);
    }

    private static boolean hasOwnUse(ItemStack stack) {
        return stack.contains(DataComponentTypes.CONSUMABLE) || stack.contains(DataComponentTypes.FOOD)
                || stack.getItem() instanceof net.minecraft.item.BlockItem
                || stack.getItem() instanceof net.minecraft.item.RangedWeaponItem;
    }

    private static long remainingCooldownMs(ClientPlayerEntity p) {
        float progress = p.getAttackCooldownProgress(0.0f);
        return (long) Math.max(0.0, (1.0 - progress) * p.getAttackCooldownProgressPerTick() * TICK_MS);
    }

    /** The closest valid target inside the Angle / distance window, or null. */
    private LivingEntity findTarget(ClientPlayerEntity p) {
        LivingEntity best = null;
        double bestDist = Double.MAX_VALUE;
        for (Entity e : mc.world.getEntities()) {
            if (!isValidTarget(p, e, true)) continue;
            double d = p.distanceTo(e);
            if (d < bestDist) {
                bestDist = d;
                best = (LivingEntity) e;
            }
        }
        return best;
    }

    private boolean isValidTarget(ClientPlayerEntity p, Entity e, boolean checkAngle) {
        if (e == p || !(e instanceof LivingEntity living) || !living.isAlive() || e instanceof ArmorStandEntity) return false;
        if (playersOnly.isEnabled() && !(e instanceof PlayerEntity)) return false;
        if (e instanceof PlayerEntity && FriendManager.getInstance().isFriend(e.getUuid())) return false;
        for (BotFilterProvider bots : ModuleAccess.getEnabledHooks(BotFilterProvider.class)) {
            if (bots.isBot(e)) return false;
        }
        double d = p.distanceTo(e);
        if (d < minDistance.getValue() || d > maxDistance.getValue()) return false;
        if (!checkAngle) return true;
        double dx = e.getX() - p.getX();
        double dz = e.getZ() - p.getZ();
        float yawTo = (float) (MathHelper.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0f;
        return Math.abs(MathHelper.wrapDegrees(yawTo - p.getYaw())) <= angle.getValue() / 2.0;
    }

    /**
     * Rhythm of the hits you take: once three consecutive gaps fall inside a plausible melee interval, the next hit
     * is expected one average gap after the last. Pure timing, no game state, so it is unit-tested directly.
     */
    static final class DamageRhythm {
        static final int SAMPLES = 3;
        static final long MIN_GAP_MS = 250L;
        static final long MAX_GAP_MS = 1500L;

        private final long[] gaps = new long[SAMPLES];
        private int count;
        private int next;
        private long lastHitMs;

        void record(long nowMs) {
            if (lastHitMs > 0L) {
                long gap = nowMs - lastHitMs;
                if (gap >= MIN_GAP_MS && gap <= MAX_GAP_MS) {
                    gaps[next] = gap;
                    next = (next + 1) % SAMPLES;
                    if (count < SAMPLES) count++;
                } else {
                    count = 0;
                    next = 0;
                }
            }
            lastHitMs = nowMs;
        }

        boolean isStable() {
            return count >= SAMPLES && lastHitMs > 0L;
        }

        long expectedNextHit() {
            if (count == 0) return lastHitMs;
            long total = 0L;
            for (int i = 0; i < count; i++) total += gaps[i];
            return lastHitMs + total / count;
        }

        void reset() {
            count = 0;
            next = 0;
            lastHitMs = 0L;
        }
    }
}
