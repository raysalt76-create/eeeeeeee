package interstellarTikiTiks.apexx.client.util.combat;

import interstellarTikiTiks.apexx.client.util.ClickSimController;
import interstellarTikiTiks.apexx.client.util.RotationManager;
import interstellarTikiTiks.apexx.client.util.SystemInputSimulator;
import net.minecraft.client.MinecraftClient;

/**
 * One AlienWC dive-boost run, shared by AlienHighCharge's Ground Launch and its Auto activation:
 * hold the wind-charge slot, look down, throw at the tick the mode picks, then hand over to
 * {@link AlienWCSupport.Recovery}. The owning module calls {@link #tick()} once per client tick and
 * {@link #finish()} when the run ends or the module is released.
 *
 * <p>Manual mode starts the look-down as soon as the glide is open (or, when not
 * {@link Config#eagerLookDown}, once the floor is close enough to need it) and throws inside the
 * ground window with the aim locked. Dynamic mode asks {@link AlienWCSolver} for both decisions.
 * The throw goes through {@code ClickSimController} after a sub-tick micro-delay with the charge
 * slot already selected, so the slot change never trails the use.
 */
public final class AlienWCController {
    private static final MinecraftClient mc = MinecraftClient.getInstance();

    /**
     * Run settings, sampled when the run starts. {@code slotHoldTicks} is the owning module's Swap Back delay
     * (ticks the charge slot stays held after the throw); {@code reopenDelayMs} / {@code reopenIntervalMs} are
     * its Re-open sliders (wait after the throw before re-opening closed wings, and between two re-open presses).
     */
    public record Config(boolean dynamic, double aimSpeed, double groundThreshold, boolean silent,
                         float lockPitch, double clearance, boolean eagerLookDown, boolean reopenGlide,
                         int slotHoldTicks, long reopenDelayMs, long reopenIntervalMs) {}

    private enum Stage { CHECK, RECOVERY, DONE }

    private static Object activeOwner;

    private final Object owner;
    private final Config cfg;
    private final int chargeSlot;
    /** Slot to swap back to: the one held when this run selected the charge slot in the throw tick. */
    private int restoreSlot;

    private Stage stage = Stage.CHECK;
    private boolean lookingDown = false;
    private boolean throwQueued = false;
    private long throwQueuedTick = Long.MIN_VALUE;
    private AlienWCSupport.Recovery recovery;
    private Object boundPlayer;
    private Object boundWorld;

    public AlienWCController(Object owner, Config cfg, int chargeSlot, int originalSlot) {
        this.owner = owner;
        this.cfg = cfg;
        this.chargeSlot = chargeSlot;
        this.restoreSlot = originalSlot;
        this.boundPlayer = mc.player;
        this.boundWorld = mc.world;
        activeOwner = owner;
    }

    /** True while any controller owns the dive-boost; the other AlienWC module stays out. */
    public static boolean isBusy() {
        return activeOwner != null;
    }

    /** The slot this run swaps back to after the throw (-1 when none). */
    public int restoreSlot() {
        return restoreSlot != chargeSlot ? restoreSlot : -1;
    }

    /** @return true once the run is over (success, failure or timeout); call {@link #finish()}. */
    public boolean tick() {
        if (mc.player == null || mc.world == null || mc.player != boundPlayer || mc.world != boundWorld) {
            return true;
        }
        return switch (stage) {
            case CHECK -> tickCheck();
            case RECOVERY -> tickRecovery();
            case DONE -> true;
        };
    }

    private boolean tickCheck() {
        // The queued use is bound to the tick that queued it. If the sequencer dropped it (cancel, stale
        // ticket, no player) the flag would stay set forever, so a new tick clears it.
        if (throwQueued && SubTickSequencer.currentTick() != throwQueuedTick) {
            throwQueued = false;
        }
        if (mc.player.isOnGround() || mc.player.horizontalCollision) {
            return true;
        }

        boolean gliding = mc.player.isGliding();
        if (!gliding && cfg.reopenGlide()) {
            // Press jump again when the glide was cancelled or has not started yet.
            SystemInputSimulator.tapJump();
        }

        // The wind-charge slot is selected in the throw tick (queueThrow), never ticks ahead of it.
        if (!gliding) {
            // The solver and the ground window both assume an open glide.
            return !cfg.reopenGlide();
        }

        boolean throwNow;
        if (cfg.dynamic()) {
            if (!lookingDown) {
                if (!AlienWCSolver.shouldStartLookDown(cfg.aimSpeed(), cfg.lockPitch(), cfg.clearance(),
                        cfg.groundThreshold())) {
                    return false;
                }
                lookingDown = true;
                AlienWCSupport.aimDown(owner, cfg.aimSpeed(), cfg.silent());
            }
            if (RotationManager.getFinalPitch() < cfg.lockPitch()) return false;
            throwNow = switch (AlienWCSolver.throwCall(cfg.aimSpeed(), cfg.lockPitch(), cfg.clearance())) {
                case WAIT -> false;
                case THROW -> true;
                case FALLBACK -> AlienWCSupport.isInThrowWindow(AlienWCSupport.floorDistance(),
                        cfg.groundThreshold());
            };
        } else {
            double floorDist = AlienWCSupport.floorDistance();
            if (!lookingDown) {
                if (!cfg.eagerLookDown() && floorDist > lookDownDistance()) return false;
                lookingDown = true;
                AlienWCSupport.aimDown(owner, cfg.aimSpeed(), cfg.silent());
            }
            boolean aimLocked = RotationManager.getFinalPitch() >= cfg.lockPitch();
            throwNow = aimLocked && AlienWCSupport.isInThrowWindow(floorDist, cfg.groundThreshold());
        }

        if (throwNow) queueThrow();
        return false;
    }

    /**
     * Floor distance at which a manual run must start looking down so the pitch reaches the lock
     * before the ground window: the ticks the turn needs times the (growing) fall per tick.
     */
    private double lookDownDistance() {
        float pitchStep = (float) Math.max(0.5, cfg.aimSpeed() * 100.0 * 0.05);
        float remaining = Math.max(0.0f, cfg.lockPitch() - RotationManager.getFinalPitch());
        int ticks = (int) Math.ceil(remaining / pitchStep) + 1;
        return cfg.groundThreshold() + ticks * Math.max(AlienWCSupport.descentPerTick(), 0.3) * 1.5;
    }

    private void queueThrow() {
        if (throwQueued) return;
        if (!AlienWCSupport.isChargeReady(chargeSlot) || mc.currentScreen != null) return;
        // Swap first in this tick; the use follows a sub-tick micro-delay later (paced by the use seam).
        int held = SlotSync.current(mc);
        if (held != chargeSlot) {
            if (!TickActionOrder.canSwitchSlot()) return;
            if (!SlotSync.select(mc, chargeSlot) || SlotSync.current(mc) != chargeSlot) return;
            restoreSlot = held;
        }
        throwQueued = true;
        throwQueuedTick = SubTickSequencer.currentTick();
        SubTickSequencer.queueAction(owner, () -> {
            if (mc.player == null || stage != Stage.CHECK
                    || mc.currentScreen != null
                    || SlotSync.current(mc) != chargeSlot
                    || !AlienWCSupport.isChargeReady(chargeSlot)) {
                throwQueued = false;
                return;
            }
            ClickSimController.useNow(mc, false);
            TickActionOrder.recordUse();
            recovery = new AlienWCSupport.Recovery(owner, cfg.aimSpeed(), cfg.silent(), chargeSlot,
                    restoreSlot(), cfg.slotHoldTicks(), cfg.reopenDelayMs(), cfg.reopenIntervalMs());
            stage = Stage.RECOVERY;
        }, SubTickSequencer.sampleMicroDelayMs());
    }

    private boolean tickRecovery() {
        recovery.tick();

        long elapsed = recovery.elapsedMs();
        boolean timeout = elapsed >= 3000L;
        boolean stableGlide = mc.player.isGliding() && elapsed >= 600L && recovery.isPitchDone();
        boolean hitGround = mc.player.isOnGround() && elapsed > 300L;
        return hitGround || stableGlide || timeout;
    }

    /** Ends the run: swaps back, drops the aim request and frees the dive-boost. Idempotent. */
    public void finish() {
        stage = Stage.DONE;
        throwQueued = false;
        SubTickSequencer.cancelOwner(owner);
        if (recovery != null) {
            recovery.finish();
        } else {
            if (mc.player != null && restoreSlot() >= 0
                    && mc.player == boundPlayer && SlotSync.current(mc) == chargeSlot) {
                SlotSync.restore(mc, restoreSlot());
            }
            RotationManager.clearTarget(owner);
        }
        if (activeOwner == owner) activeOwner = null;
    }
}
