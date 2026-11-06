package interstellarTikiTiks.apexx.client.util;

import interstellarTikiTiks.apexx.client.util.combat.SubTickSequencer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.security.SecureRandom;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * RotationManager — the shared aim-assist API for the whole client.
 *
 * Every aiming module (AimAssist, AutoMace, AutoHeal, BowAimbot,
 * GrappleAimbot, AntiDivebomb, AutoAnchor, …) feeds a target {@link Supplier}
 * into this class and the engine performs ONE smooth, continuous turn toward it.
 *
 * The core is a critically-damped SmoothDamp (frame-rate independent, no
 * overshoot, O(1) per frame). It is driven by exactly two concepts:
 *
 *   • strength       — how aggressively the camera locks on (turn speed).
 *   • humanization    — how much human-like imperfection is mixed in
 *                       (eased micro-noise + reaction delay). 0 = robotic-smooth.
 *
 * Modules call {@link #aimAt} (or the legacy {@code setRotationSupplier}
 * overloads) and may contend for the single aim channel via owner + priority.
 * The update loop runs per-FRAME (WorldRenderEvents.END_MAIN) so the motion is
 * as smooth as the frame rate, never stepped at the 20Hz tick rate.
 */
public class RotationManager {
    private static final MinecraftClient mc = MinecraftClient.getInstance();
    private static final SecureRandom random = new SecureRandom();
    private static final Random controllerRng = new Random();
    private static final RotationController controller = new RotationController(controllerRng);
    private static RotationRequest activeRequest = null;
    private static ClientPlayerEntity silentBoundPlayer = null;
    private static Object lastOwner = null;

    // Step and camera easing
    private static boolean stepPending;
    private static float stepFromYaw;
    private static float stepFromPitch;
    private static long stepTick = Long.MIN_VALUE;
    private static float stepToYaw;
    private static float stepToPitch;
    private static long pendingCameraEaseMs;
    private static ClientPlayerEntity cameraEasePlayer;
    private static float cameraEaseYaw;
    private static float cameraEasePitch;
    private static long cameraEaseStart;
    private static long cameraEaseDuration;

    // ── Mouse-GCD quantization ──────────────────────────────────────────────────
    // Every rotation this class commits is snapped to a whole number of mouse steps
    // (see quantizeDelta). Toggleable so the quantized and raw paths can be compared
    // against a detector; on by default because raw float angles are unreachable by
    // hand and therefore trivially separable from human input.
    private static boolean gcdQuantization = true;

    // Largest error seen during the current LINEAR/SINE approach, used to normalise
    // the sine velocity envelope. Re-baselined on a new target, cleared on arrival.
    private static float sineSpanYaw = 0.0f;
    private static float sineSpanPitch = 0.0f;

    public enum Priority {
        LOWEST, LOW, NORMAL, HIGH, HIGHEST
    }

    public enum RotationMode {
        INSTANT, SMOOTH, SINE, LINEAR,
        // TIMED — a deadline-driven natural flick. Given a time window, the aim
        // performs a full human motion (accelerate, decelerate, a small
        // overshoot, then a correction) that lands EXACTLY on target at the end
        // of the window, then hands off to smooth tracking. See timedProfile().
        TIMED,
        // WIND — the SMOOTH core plus bounded human shaping: a wandering lateral
        // force that lets the path genuinely overshoot and correct, and per-axis
        // speed asymmetry so yaw/pitch velocity don't correlate near 1.
        //
        // It used to be a SEPARATE integrator carrying its own unit system, in
        // which the per-frame step was `speed * 0.5 * dt` — i.e. a ceiling of
        // `speed * 0.5` deg/SECOND. At the shipped defaults that was ~23 deg/s,
        // roughly a tenth of what is needed to hold a strafing target, and the
        // Strength slider did not enter the expression at all. WIND being the
        // default for AimAssist, AutoMace, SpearAssist and PearlGrapple is what
        // made aim feel broken. It is now shaping applied ON TOP of the one
        // properly-scaled core, so Strength always governs turn rate.
        WIND,
        // ASSIST — AimAssist's visible-camera controller (see AssistMotion). Aims at a
        // zone rather than a point, credits the player's own mouse motion so it only fills
        // the gap, feeds forward the target's angular motion, and detaches on a deliberate
        // pull away. Requested through setAssistSupplier.
        ASSIST;
        public static final RotationMode LEGIT = SMOOTH;
    }

    public enum AntiCheatProfile {
        STRICT("Strict", 28.0f, 22.0f, 5, true),
        BALANCED("Balanced", 45.0f, 35.0f, 4, true),
        FAST("Fast", 70.0f, 55.0f, 3, false),
        RAW("Raw (Uncapped)", 180.0f, 90.0f, 1, false);

        public final String displayName;
        public final float maxStepYaw;
        public final float maxStepPitch;
        public final int decayTicks;
        public final boolean sprintGuard;

        AntiCheatProfile(String displayName, float maxStepYaw, float maxStepPitch, int decayTicks, boolean sprintGuard) {
            this.displayName = displayName;
            this.maxStepYaw = maxStepYaw;
            this.maxStepPitch = maxStepPitch;
            this.decayTicks = decayTicks;
            this.sprintGuard = sprintGuard;
        }
    }

    private static AntiCheatProfile antiCheatProfile = AntiCheatProfile.STRICT;
    private static boolean sprintSafetyGuard = true;
    private static boolean smoothDecayEnabled = true;
    private static double gcdRemainderYaw = 0.0;
    private static double gcdRemainderPitch = 0.0;

    // Decay interpolation anchors for Hermite smoothstep return
    private static float decayStartYaw = 0.0f;
    private static float decayStartPitch = 0.0f;
    private static int totalDecayTicks = 5;

    // ── WIND shaping state ──────────────────────────────────────────────────────
    // Persistent between frames: the wander force is an autoregressive process, so it
    // carries. Reset on acquire and on forceStop alongside the velocities.
    /** Wander force as a fraction of the current approach speed. Higher = more wandering. */
    private static final double WIND_RATIO = 0.28;
    /** Above this angular error the wander is re-excited each step; below it only decays. */
    private static final double WIND_D_FAR = 6.0;

    /** Lateral wander offset applied to the aim point, in degrees. */
    private static double windYaw = 0.0;
    private static double windPitch = 0.0;
    private static double windYawVel = 0.0;
    private static double windPitchVel = 0.0;
    private static double windPhase = 0.0;

    /**
     * Per-axis speed asymmetry for WIND, as a MULTIPLIER on the strength-derived core
     * speed. Scoped to an owner so one module's profile can never leak into another's
     * aim — the previous version was a bare global that only AimAssist ever wrote, so
     * AutoMace, SpearAssist and PearlGrapple (all WIND users) silently ran on whatever
     * AimAssist had last set, including after AimAssist was switched off.
     */
    private static Object windSpeedOwner = null;
    private static double windYawBias = 1.0;
    private static double windPitchBias = 1.0;

    // ── ASSIST controller (AimAssist "Normal") ─────────────────────────────────
    private static final AssistMotion assistMotion = new AssistMotion();
    private static Supplier<net.minecraft.util.math.Box> assistZoneSupplier = null;
    private static AssistMotion.Config assistConfig = AssistMotion.Config.DEFAULT;

    /**
     * Visible-camera assist toward a target zone (see {@link AssistMotion}). The zone supplier
     * is sampled every render frame and should return the render-interpolated world box the
     * crosshair should settle inside, or null to release. Calling again with the same owner
     * only updates the zone supplier and config; the motion state carries on.
     */
    public static void setAssistSupplier(Object owner, Priority priority,
                                         Supplier<net.minecraft.util.math.Box> zone,
                                         AssistMotion.Config config) {
        if (rejectsAcquire(owner, priority)) return;
        predictionTicks = 0.0;
        Supplier<Vec3d> center = zone == null ? null : () -> {
            net.minecraft.util.math.Box box = zone.get();
            return box == null ? null : box.getCenter();
        };
        setRotationSupplier(owner, priority, center, 1.0, RotationMode.ASSIST, 0.0, 0L,
                false, false, true);
        if (currentOwner == owner) {
            assistZoneSupplier = zone;
            assistConfig = config == null ? AssistMotion.Config.DEFAULT : config;
        }
    }

    /** New target on {@code owner}'s ASSIST channel: restart engagement without a velocity jump. */
    public static void retargetAssist(Object owner) {
        if (owner != null && owner == currentOwner && mode == RotationMode.ASSIST) {
            assistMotion.retarget();
        }
    }

    /** Current ASSIST engagement (0..1) for {@code owner}, or 0 when it does not own the channel. */
    public static double getAssistEngagement(Object owner) {
        return (isActive && owner == currentOwner && mode == RotationMode.ASSIST) ? assistMotion.engagement() : 0.0;
    }

    /**
     * Converts a world box into an angular zone around the current interpolated eye position.
     * The horizontal half-size uses the box's footprint as seen along the line of sight.
     */
    public static AssistMotion.Zone assistZoneFor(net.minecraft.util.math.Box box) {
        if (box == null || mc.player == null) return null;
        Vec3d eye = getInterpolatedEyePos();
        Vec3d c = box.getCenter();
        double dx = c.x - eye.x;
        double dz = c.z - eye.z;
        double hz = Math.max(1.0e-3, Math.sqrt(dx * dx + dz * dz));
        double yaw = Math.toDegrees(Math.atan2(dz, dx)) - 90.0;
        double halfWidth = 0.5 * (box.getLengthX() * Math.abs(dz / hz) + box.getLengthZ() * Math.abs(dx / hz));
        double yawHalf = Math.toDegrees(Math.atan2(halfWidth, hz));
        double pitchTop = -Math.toDegrees(Math.atan2(box.maxY - eye.y, hz));
        double pitchBottom = -Math.toDegrees(Math.atan2(box.minY - eye.y, hz));
        return new AssistMotion.Zone(yaw, yawHalf,
                MathHelper.clamp(Math.min(pitchTop, pitchBottom), -90.0, 90.0),
                MathHelper.clamp(Math.max(pitchTop, pitchBottom), -90.0, 90.0));
    }

    /**
     * Sets {@code owner}'s per-axis WIND speed bias from two nominal deg/s figures.
     *
     * <p>Separate horizontal and vertical speeds are the point: driving both axes from
     * one strength value leaves yaw and pitch velocity almost perfectly proportional, so
     * their cross-correlation sits near 1. Real hands control the two axes with different
     * latencies and different corrective counts.
     *
     * <p>These are interpreted as a RATIO against {@link #WIND_NOMINAL_DEG_PER_SEC}, not
     * as an absolute ceiling, so Strength remains the single control over how fast the
     * aim actually turns and these only set how the two axes differ from each other.
     */
    public static void setWindSpeeds(Object owner, double yawDegPerSec, double pitchDegPerSec) {
        windSpeedOwner = owner;
        windYawBias = axisBias(yawDegPerSec);
        windPitchBias = axisBias(pitchDegPerSec);
    }

    /** Reference deg/s that maps to a 1.0x axis bias. */
    private static final double WIND_NOMINAL_DEG_PER_SEC = 60.0;

    private static double axisBias(double degPerSec) {
        if (degPerSec <= 0.0) return 0.0;
        return MathHelper.clamp(degPerSec / WIND_NOMINAL_DEG_PER_SEC, 0.15, 3.0);
    }

    /** This frame's yaw bias — 1.0 unless the active owner set one. */
    private static double activeYawBias() {
        return currentOwner != null && currentOwner == windSpeedOwner ? windYawBias : 1.0;
    }

    private static double activePitchBias() {
        return currentOwner != null && currentOwner == windSpeedOwner ? windPitchBias : 1.0;
    }

    private static Supplier<Vec3d> targetSupplier = null;
    private static boolean isActive       = false;
    private static boolean isSilent       = false;
    private static boolean isReleasing    = false;
    private static boolean isYawOnly      = false;

    // ── Post-Attack Hold & Smooth Silent Decay ──────────────────────────────────
    // Holds the silent look state for N ticks after an attack hit before releasing,
    // followed by a smooth GCD-quantized decay back to the player's view direction
    // so anti-cheats never observe a single-tick rotation snap or instant snap-back.
    private static int    postAttackHoldTicks = 0;
    private static float  holdYaw             = 0.0f;
    private static float  holdPitch           = 0.0f;
    private static Object holdOwner           = null;
    // Rule 4: owner whose action went out this tick, and a clear/stop it issued after that action,
    // applied at the next tick start so the action's rotation is not released mid-tick.
    private static Object actionOwner          = null;
    private static long   actionTick           = Long.MIN_VALUE;
    private static Object deferredReleaseOwner = null;
    private static boolean deferredReleaseHard = false;
    private static boolean isDecaying         = false;
    private static int    decayTicksRemaining = 0;

    private static double baseStrength    = 5.0;
    private static double humanization    = 0.0;
    /** Extra response applied only to stable smooth requests that explicitly opt in. */
    private static double silentSpeedMultiplier = 1.0;
    private static long   reactionDelayMs = 0;
    private static RotationMode mode      = RotationMode.SMOOTH;
    // Owners that already provide a filtered target point can opt out of the shared
    // humanization layer. Applying another moving offset or random sensor step here
    // only creates visible chatter in an otherwise continuous aim path.
    private static boolean stableSmoothAim = false;
    private static boolean sensorJitterEnabled = true;
    private static double trackingFactor  = 0.5;
    private static double predictionTicks = 0.0;

    // ── TIMED mode (RotationMode.TIMED) ───────────────────────────────────────
    // timedWindowMs = the full flick duration; the motion completes (dead-on the
    // target) at the end of it. timedZeta = the 2nd-order damping ratio that sets
    // how much human overshoot/correction the flick shows (lower = more). The
    // start pose/time are captured once when the channel engages.
    private static double timedWindowMs = 180.0;
    private static double timedZeta     = 0.62;
    private static long   timedStartTime = 0L;
    private static float  timedStartYaw  = 0.0f;
    private static float  timedStartPitch = 0.0f;

    // ── ENGAGE EASE-IN ────────────────────────────────────────────────────────
    // The moment a module (re)acquires the channel — AimAssist locking a fresh
    // target, or AutoMace snapping on for a smash — the very first turn used to
    // pull at FULL strength/turn-rate from a standstill, which read as an
    // instant snap ("if I don't move my mouse, there's a noticeable snap when it
    // engages"). engageStartTime stamps the acquire; applyRotationStep ramps the
    // effective strength AND max turn-rate up from ENGAGE_MIN_FACTOR to full over
    // ENGAGE_EASE_MS (smoothstepped) so every engagement *accelerates* into the
    // lock like a hand does, instead of teleporting onto it. Set only on a GENUINE
    // (re)acquire so a transient supplier-null blip doesn't re-trigger the ramp.
    private static long   engageStartTime = 0L;
    private static final double ENGAGE_EASE_MS    = 600.0;
    private static final double ENGAGE_MIN_FACTOR = 0.12;

    private static Object   currentOwner    = null;
    private static Priority currentPriority = Priority.LOWEST;

    private static float visualYaw, visualPitch;
    private static float finalYaw,  finalPitch;
    private static float prevFinalYaw, prevFinalPitch;
    private static float lastSetYaw, lastSetPitch;

    private static long lastUpdateTime = -1;
    private static long lastUpdateNanos = -1L;
    private static final double TARGET_MS_PER_FRAME = 1000.0 / 60.0;

    // ── Release ──────────────────────────────────────────────────────────────
    // A fast flick AWAY from the target hands control straight back to the player,
    // even at high Strength where the soft mouse-resistance can't fully let go.
    // releaseThreshold = the per-frame away-flick (degrees) that triggers it;
    // releaseCooldownUntil holds the assist off for a beat so you fully pull away.
    private static boolean releaseEnabled = false;
    private static float   releaseThreshold = 9.0f;
    private static long    releaseCooldownUntil = 0L;

    
    // ── Exclusive channel lock (PearlCatch total control safeguard) ───────────
    private static Object exclusiveOwner = null;
    /** Monotonic time (ms) the current exclusive owner took the lock; read-only diagnostics for callers. */
    private static long exclusiveSinceMs = 0L;
    /**
     * Hard ceiling on one exclusive hold. The longest PearlCatch press (first-throw wait, the widest throw
     * gap plus its grace, the fallback retries and the swap-back delay) stays well inside it; a lock older
     * than this was left behind and is dropped so it can never wedge every other aim. Matches AutoMace's
     * stale-lock window.
     */
    private static final long EXCLUSIVE_LOCK_MAX_MS = 8000L;

    /**
     * Takes the rotation channel exclusively for {@code owner}: until it is released (or expires after
     * {@link #EXCLUSIVE_LOCK_MAX_MS}, or its module is disabled), every rotation request from any other
     * owner is rejected regardless of priority, and the holder wins the channel regardless of priority.
     * An aim another owner already holds is released (owner-scoped, through {@link #clearTarget(Object)}),
     * so it cannot keep steering the look while the lock is held.
     */
    public static void acquireExclusiveLock(Object owner) {
        if (owner == null) return;
        if (exclusiveOwner != owner) {
            exclusiveSinceMs = System.nanoTime() / 1_000_000L;
        }
        exclusiveOwner = owner;
        if (isActive && currentOwner != null && currentOwner != owner) {
            clearTarget(currentOwner);
        }
    }

    /**
     * True while an exclusive lock is in force. A lock past {@link #EXCLUSIVE_LOCK_MAX_MS}, or held by a
     * (non one-shot) module that has since been disabled, is dropped here.
     */
    private static boolean exclusiveHeld() {
        Object owner = exclusiveOwner;
        if (owner == null) return false;
        boolean expired = System.nanoTime() / 1_000_000L - exclusiveSinceMs > EXCLUSIVE_LOCK_MAX_MS;
        boolean orphaned = owner instanceof interstellarTikiTiks.apexx.client.module.Module module
                && !module.isEnabled() && !module.isOneShot();
        if (!expired && !orphaned) return true;
        exclusiveOwner = null;
        ClientInternalLog.warn("RotationManager", "dropped a " + (expired ? "timed-out" : "orphaned")
                + " exclusive rotation lock held by " + owner.getClass().getSimpleName());
        return false;
    }

    /** True when an exclusive lock held by someone other than {@code owner} is in force. */
    private static boolean exclusiveBlocks(Object owner) {
        return exclusiveHeld() && exclusiveOwner != owner;
    }

    /** Read-only: the current exclusive-lock owner, or null when unlocked. */
    public static Object getExclusiveOwner() {
        return exclusiveHeld() ? exclusiveOwner : null;
    }

    /** Read-only: how long (ms, monotonic) the current exclusive lock has been held; 0 when unlocked. */
    public static long exclusiveLockHeldMs() {
        return !exclusiveHeld() ? 0L : Math.max(0L, System.nanoTime() / 1_000_000L - exclusiveSinceMs);
    }

    public static void releaseExclusiveLock(Object owner) {
        if (exclusiveOwner == owner) {
            exclusiveOwner = null;
        }
    }

    public static boolean isExclusiveLocked() {
        return exclusiveHeld();
    }

    public static boolean isExclusiveOwner(Object owner) {
        return !exclusiveHeld() || exclusiveOwner == owner;
    }

    public static boolean isMouseLocked() {
        return exclusiveHeld();
    }

    public static void setReleaseConfig(boolean enabled, float thresholdDeg) {
        releaseEnabled = enabled;
        releaseThreshold = Math.max(1.0f, thresholdDeg);
    }

    // ── Max turn-speed cap (anti-flick) ──────────────────────────────────────────
    // Hard ceiling on how fast the assist may turn, so it can never do an inhuman instant flick even at
    // high Strength. Scoped to a single owner (set by AimAssist) so it never slows another module's aim.
    private static Object maxSpeedCapOwner    = null;
    private static double maxSpeedCapDegPerSec = 0.0;  // 0 = uncapped

    /** Cap {@code owner}'s turn rate to {@code degPerTick} °/tick (×20 → °/s). 0 disables the cap. */
    public static void setMaxSpeedCap(Object owner, double degPerTick) {
        maxSpeedCapOwner     = owner;
        maxSpeedCapDegPerSec = degPerTick <= 0.0 ? 0.0 : degPerTick * 20.0;
    }

    // ── CONTINUOUS PROJECTION YIELD (AimAssist "work-with-you" model) ──────────
    // Replaces the old hard Release + binary mouseResistance for the AimAssist channel ONLY.
    // Instead of fighting a player who drags away (or snapping fully off), the assist reads the SIGN
    // of the player's mouse motion projected onto the aim-error axis: pulling AWAY smoothly and
    // continuously lowers assist strength (and lightly co-assists their motion) so it works WITH the
    // hand; moving toward/perpendicular (imprecise aim) is NOT treated as opposition, so bad aim is
    // still assisted hard. Gated to yieldOwner so AutoMace/AutoHeal/etc. keep full authority.
    private static boolean yieldEnabled       = false;
    private static Object  yieldOwner         = null;   // only THIS owner's aim yields to the player
    private static float   yieldSensitivity   = 1.0f;   // 0..1 (from Strength): higher = holds harder
    private static float   yieldLevel         = 0.0f;   // smoothed 0..1 yield state (no-snap integrator)
    private static float   assistStrengthScale = 1.0f;  // 1 - yieldLevel for yieldOwner, else 1.0

    private static final float AWAY_ALIGN_MIN   = 0.30f;   // min opposite-alignment to count as a deliberate pull-away
    private static final float YIELD_RISE_TAU   = 0.08f;   // s — yields quickly but continuously
    private static final float YIELD_FALL_TAU   = 0.35f;   // s — assist ramps back up gracefully (the hand-off)
    private static final float YIELD_REF_LOW    = 70.0f;   // deg/s away-pull that ~fully yields at Strength 0
    private static final float YIELD_REF_HIGH   = 260.0f;  // deg/s needed to ~fully yield at Strength 1
    private static final float YIELD_ASSIST_GAIN = 0.15f;  // co-assist along the player's own motion while yielding

    // Strength-scaled low-pass on the target point — removes entity-lerp micro-jitter (a stutter
    // source) before it reaches smoothDamp, for the "lucid watery" feel. Reset on every (re)acquire.
    private static float   filtTargetYaw   = 0.0f;
    private static float   filtTargetPitch = 0.0f;
    private static boolean filtInit        = false;

    /** Enable the continuous projection-yield for {@code owner}'s aim only (AimAssist). {@code
     *  sensitivity} (0..1, from Strength) sets how hard you must pull to disengage — higher holds
     *  harder while you aim. Any other owner acquiring the channel clears this (see the acquire block). */
    public static void setYieldConfig(Object owner, boolean enabled, float sensitivity) {
        yieldEnabled = enabled;
        if (enabled) yieldOwner = owner;
        yieldSensitivity = MathHelper.clamp(sensitivity, 0.0f, 1.0f);
    }

    // SmoothDamp velocity state (degrees / second).
    private static float velocityYaw   = 0.0f;
    private static float velocityPitch = 0.0f;

    // Humanization: eased Gaussian noise that drifts the aim point slightly.
    private static float noiseYaw         = 0.0f;
    private static float noisePitch       = 0.0f;
    private static float noiseTargetYaw   = 0.0f;
    private static float noiseTargetPitch = 0.0f;
    private static long  lastNoiseRetarget = 0;

    private static double mouseResistance = 0.0;

    // The player's own mouse movement folded in this frame (degrees), for the ASSIST controller.
    private static float frameMouseYawDelta = 0.0f;
    private static float frameMousePitchDelta = 0.0f;

    private static long    nextReactionTime = 0;
    private static boolean hasReacted       = false;

    private static Vec3d lastPredictSample     = null;
    private static long  lastPredictSampleTime = 0;

    // ──────────────────────────────────────────────────────────────────────
    //  Public aim API
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Smoothly aim toward a (live) world position — the canonical entry point.
     *
     * @param owner         the requesting module (for priority arbitration / release)
     * @param priority      higher priority wins the single aim channel
     * @param target        supplier evaluated every frame; return null to release
     * @param strength      lock speed (≈ 1–25; higher = snappier)
     * @param humanizationP human-like imperfection 0..1 (0 = perfectly smooth)
     * @param silent        true = rotate the hit/look direction without moving the visible camera
     */
    public static void aimAt(Object owner, Priority priority, Supplier<Vec3d> target,
                             double strength, double humanizationP, boolean silent) {
        setRotationSupplier(owner, priority, target, strength, RotationMode.SMOOTH,
                humanizationP, 0L, silent, false);
    }

    /**
     * Configure the TIMED-flick envelope. {@code windowMs} is the full flick
     * duration; {@code overshoot01} (0..1) sets how much human overshoot the
     * motion shows (0 ≈ critically damped, 1 ≈ ~15% overshoot). Call this before
     * (or alongside) engaging a TIMED aim — it just stores the envelope params.
     */
    public static void setTimedConfig(double windowMs, double overshoot01) {
        timedWindowMs = Math.max(1.0, windowMs);
        // More overshoot → lower damping ratio. 0 → 0.82 (barely), 1 → 0.45 (lots).
        timedZeta = 0.82 - 0.37 * MathHelper.clamp(overshoot01, 0.0, 1.0);
    }

    /**
     * Aim toward a (live) world position as a single, deadline-bounded HUMAN
     * flick: the camera/look accelerates, decelerates, slightly overshoots, then
     * corrects, landing dead-on the target at {@code windowMs}. After the window
     * it hands off to smooth tracking at {@code trackStrength}. This is the
     * "not-a-snap" path — every turn looks human and finishes on time.
     *
     * @param windowMs       how long the flick takes start→on-target
     * @param overshoot01    human overshoot amount 0..1
     * @param trackStrength  lock speed for the post-flick tracking phase (≈1..25)
     */
    public static void aimWithin(Object owner, Priority priority, Supplier<Vec3d> target,
                                 double windowMs, double overshoot01, double trackStrength,
                                 double humanizationP, boolean silent, boolean yawOnly) {
        setRotationSupplier(owner, priority, target, trackStrength, RotationMode.TIMED,
                humanizationP, 0L, silent, yawOnly);
        // Only stamp the flick envelope if we actually hold the channel — a lower
        // priority caller that lost arbitration must NOT clobber the active flick's
        // window/overshoot (setRotationSupplier returns without taking the channel).
        if (currentOwner == owner) {
            setTimedConfig(windowMs, overshoot01);
        }
    }

    /** True when a setRotationSupplier call would be REJECTED (no player, or a lower-priority DIFFERENT
     *  owner while the channel is held). The pre-guard convenience overloads check this so a rejected
     *  call is fully side-effect-free — it must NOT clobber the active owner's trackingFactor /
     *  predictionTicks (a rejected per-frame NORMAL aim was wiping an active prediction owner's lead). */
    private static boolean rejectsAcquire(Object owner, Priority priority) {
        if (mc.player == null) return true;
        // An exclusive lock (PearlCatch between its throws, PearlGrapple, WebEscape...) decides the
        // channel outright: everyone else is refused, and the holder is never refused on priority.
        if (exclusiveHeld()) return exclusiveOwner != owner;
        return isActive && currentOwner != owner && priority.ordinal() <= currentPriority.ordinal();
    }

    /**
     * Submit (or refresh) this module's rotation using the continuous rotation controller.
     * Speed, response, humanization, prediction, reaction, silent, and yaw-only are fully defined by the request.
     */
    public static void setRotationSupplier(Object owner, Priority priority, Supplier<Vec3d> supplier, RotationRequest request) {
        if (rejectsAcquire(owner, priority)) {
            return;
        }
        long currentTime = System.currentTimeMillis();
        if (!isActive || isReleasing || currentOwner != owner) {
            boolean ownerChanged = isActive && currentOwner != null && currentOwner != owner;
            boolean resume = isReleasing && currentOwner == owner;
            boolean fresh = ownerChanged || (!resume && lastOwner != owner);
            boolean wasSilent = isActive && isSilent && mc.player != null;
            isActive = true;
            isReleasing = false;
            currentOwner = owner;
            currentPriority = priority;
            silentBoundPlayer = mc.player;
            float currentYaw = mc.player.getYaw();
            float currentPitch = mc.player.getPitch();
            if (!wasSilent) {
                visualYaw = currentYaw;
                visualPitch = currentPitch;
            } else if (!request.silent()) {
                currentYaw = visualYaw;
                currentPitch = visualPitch;
                mc.player.setYaw(currentYaw);
                mc.player.lastYaw = currentYaw;
                mc.player.setPitch(currentPitch);
                mc.player.lastPitch = currentPitch;
            }
            finalYaw = currentYaw;
            finalPitch = currentPitch;
            lastSetYaw = currentYaw;
            lastSetPitch = currentPitch;
            lastUpdateTime = currentTime;
            lastUpdateNanos = System.nanoTime();
            if (fresh) {
                controller.reset(true);
                assistMotion.reset();
            }
            long reactionMs = request.reactionMs();
            hasReacted = reactionMs <= 0L;
            nextReactionTime = currentTime + reactionMs;
        }
        targetSupplier = supplier;
        // Aiming again cancels a release this owner had held over: it is retargeting, not finishing.
        if (deferredReleaseOwner == owner) deferredReleaseOwner = null;
        activeRequest = request;
        mode = RotationMode.SMOOTH;
        isSilent = request.silent();
        isYawOnly = request.yawOnly();
        controller.apply(request);
    }

    public static void setRotationSupplier(Object owner, Priority priority, Supplier<Vec3d> supplier,
            double str, RotationMode rotMode, double rand, boolean silent, boolean yawOnly,
            double tracking, double predTicks) {
        if (rejectsAcquire(owner, priority)) return;
        trackingFactor  = tracking;
        predictionTicks = predTicks;
        setRotationSupplier(owner, priority, supplier, str, rotMode, rand, 0L, silent, yawOnly);
    }

    public static void setRotationSupplier(Object owner, Priority priority, Supplier<Vec3d> supplier,
            double str, RotationMode rotMode, double rand, boolean silent, boolean yawOnly, double tracking) {
        if (rejectsAcquire(owner, priority)) return;
        trackingFactor  = tracking;
        predictionTicks = 0.0;
        setRotationSupplier(owner, priority, supplier, str, rotMode, rand, 0L, silent, yawOnly);
    }

    public static void setRotationSupplier(Object owner, Priority priority, Supplier<Vec3d> supplier,
            double str, RotationMode rotMode, double rand, boolean silent, boolean yawOnly) {
        if (rejectsAcquire(owner, priority)) return;
        predictionTicks = 0.0;
        setRotationSupplier(owner, priority, supplier, str, rotMode, rand, 0L, silent, yawOnly);
    }

    public static void setRotationSupplier(Object owner, Priority priority, Supplier<Vec3d> supplier,
            double str, RotationMode rotMode, double rand, long reactionTime,
            boolean silent, boolean yawOnly) {
        setRotationSupplier(owner, priority, supplier, str, rotMode, rand, reactionTime, silent, yawOnly, false);
    }

    /**
     * Stable smooth aim for modules that provide their own continuous target filtering.
     * It preserves the shared frame-rate-independent spring and GCD quantization, while
     * disabling global random aim spread, sway, reaction sampling, and sensor-step jitter.
     */
    public static void setStableSmoothSupplier(Object owner, Priority priority, Supplier<Vec3d> supplier,
            double str, boolean silent, boolean yawOnly) {
        setStableSmoothSupplier(owner, priority, supplier, str, silent, yawOnly, 1.0, 0.0);
    }

    /**
     * Stable smooth aim with optional silent-only response tuning. The target remains
     * unrandomized and the engine keeps the same critically damped path; the optional
     * sway is slow and continuous, and the speed scale changes response without a snap.
     * {@code silentHumanization} is the calling module's own sway amount (0..1) for silent aim;
     * 0 keeps the aim rigid.
     */
    public static void setStableSmoothSupplier(Object owner, Priority priority, Supplier<Vec3d> supplier,
            double str, boolean silent, boolean yawOnly,
            double silentSpeedMultiplier, double silentHumanization) {
        if (rejectsAcquire(owner, priority)) return;
        predictionTicks = 0.0;
        setRotationSupplier(owner, priority, supplier, str, RotationMode.SMOOTH, 0.0, 0L,
                silent, yawOnly, true);
        if (currentOwner == owner) {
            RotationManager.silentSpeedMultiplier = silent
                    ? MathHelper.clamp(silentSpeedMultiplier, 1.0, 2.5)
                    : 1.0;
            if (silent && silentHumanization > 0.0) {
                humanization = MathHelper.clamp(silentHumanization, 0.0, 1.0);
            }
        }
    }

    /**
     * Aim only in pitch while leaving yaw under the player's normal camera control. The supplier
     * is sampled by the shared rotation update, and its target yaw follows the live camera yaw so
     * the common controller does not pull the horizontal axis back toward a captured angle.
     *
     * @param maxPitchSpeedDegPerSecond pitch speed ceiling; zero leaves the shared profile uncapped
     */
    public static void aimPitch(Object owner, Priority priority, Supplier<Float> pitchSupplier,
            double str, double maxPitchSpeedDegPerSecond) {
        if (pitchSupplier == null || rejectsAcquire(owner, priority)) return;

        setMaxSpeedCap(owner, maxPitchSpeedDegPerSecond <= 0.0
                ? 0.0
                : maxPitchSpeedDegPerSecond / 20.0);
        setStableSmoothSupplier(owner, priority, () -> {
            if (mc.player == null) return null;
            Float pitch = pitchSupplier.get();
            return pitch == null ? null : pointAlongRotation(mc.player.getYaw(), pitch);
        }, str, false, false);
    }

    private static void setRotationSupplier(Object owner, Priority priority, Supplier<Vec3d> supplier,
            double str, RotationMode rotMode, double rand, long reactionTime,
            boolean silent, boolean yawOnly, boolean stableSmooth) {
        if (rejectsAcquire(owner, priority)) return;
        RotationMode prevMode = mode;
        double speed = rotMode == RotationMode.INSTANT ? 7200.0 : str * 60.0;
        double response = rotMode == RotationMode.INSTANT ? 1.0 : (stableSmooth ? 0.85 : 0.65);
        if (silent) {
            speed *= 1.5;
            response = Math.min(1.0, response + 0.30);
        }
        RotationRequest req = RotationRequest.create()
                .speed(speed)
                .response(response)
                .humanization(rand)
                .prediction(predictionTicks > 0.0 ? Math.min(1.0, predictionTicks * 0.1) : 0.0)
                .reactionMs(reactionTime)
                .silent(silent)
                .yawOnly(yawOnly)
                .targetSmoothing(!stableSmooth);
        setRotationSupplier(owner, priority, supplier, req);
        if (currentOwner == owner) {
            if (prevMode != rotMode && rotMode == RotationMode.ASSIST) {
                assistMotion.reset();
            }
            mode = rotMode;
        }
    }

    public static void setRotationSupplier(Object owner, Priority priority, Supplier<Vec3d> supplier,
            double str, boolean silent) {
        if (rejectsAcquire(owner, priority)) return;
        predictionTicks = 0.0;
        setRotationSupplier(owner, priority, supplier, str, RotationMode.SMOOTH, 0.0, 0L, silent, false);
    }

    private static final Object LEGACY_OWNER = new Object();

    public static void clearTarget() {
        clearTarget(LEGACY_OWNER);
    }

    public static void stop() {
        forceStop();
    }

    public static void clearTarget(Object owner) {
        if (!isActive || isReleasing) return;
        if (currentOwner == owner || currentOwner == null) {
            if (actionThisTick(owner)) {
                // An action went out this tick: the rotation it was sent on stays put until the tick ends.
                deferredReleaseOwner = owner;
                deferredReleaseHard = false;
                return;
            }
            if (!isSilent && mode == RotationMode.ASSIST) {
                forceStop();
                return;
            }
            targetSupplier = null;
            isReleasing = true;
        }
    }

    public static void stop(Object owner) {
        if (!isActive || (currentOwner != null && currentOwner != owner)) return;
        if (actionThisTick(owner)) {
            deferredReleaseOwner = owner;
            deferredReleaseHard = true;
            return;
        }
        forceStop();
    }

    private static boolean actionThisTick(Object owner) {
        return owner != null && actionOwner == owner && actionTick == SubTickSequencer.currentTick();
    }

    /**
     * Rule 4: a module just sent an attack, use or place on its current rotation. Hold that rotation
     * through the rest of this tick via {@link #setPostAttackHold}; a clear or stop the module issues in
     * the same tick is applied at the start of the next one, so the angle the action went out on is not
     * changed or released mid-tick. No-op unless {@code owner} holds the rotation channel.
     */
    public static void markActionDispatched(Object owner) {
        if (owner == null || !isActive || currentOwner != owner) return;
        actionOwner = owner;
        actionTick = SubTickSequencer.currentTick();
        setPostAttackHold(owner, 1, finalYaw, finalPitch);
    }

    /** Tick HEAD (from SubTickSequencer.onTickStart): expire the hold, then apply a held-over release. */
    public static void onTickStart() {
        if (postAttackHoldTicks > 0 && --postAttackHoldTicks == 0) {
            holdOwner = null;
        }
        Object owner = deferredReleaseOwner;
        boolean hard = deferredReleaseHard;
        deferredReleaseOwner = null;
        deferredReleaseHard = false;
        if (owner != null) {
            if (hard) stop(owner);
            else clearTarget(owner);
        }
    }

    /**
     * Immediately releases every rotation resource associated with an owner. This is the fault and
     * lifecycle path: unlike {@link #stop(Object)}, it deliberately skips silent decay/hold so a
     * crashed or disabled high-priority module cannot keep blocking another aiming module.
     */
    public static void abortOwner(Object owner) {
        if (owner == null) return;

        if (exclusiveOwner == owner) exclusiveOwner = null;
        if (deferredReleaseOwner == owner) deferredReleaseOwner = null;
        if (actionOwner == owner) actionOwner = null;
        if (holdOwner == owner) {
            postAttackHoldTicks = 0;
            holdOwner = null;
        }
        if (windSpeedOwner == owner) {
            windSpeedOwner = null;
            windYawBias = 1.0;
            windPitchBias = 1.0;
        }
        if (maxSpeedCapOwner == owner) {
            maxSpeedCapOwner = null;
            maxSpeedCapDegPerSec = 0.0;
        }
        if (yieldOwner == owner) {
            yieldOwner = null;
            yieldEnabled = false;
            yieldLevel = 0.0f;
            assistStrengthScale = 1.0f;
        }

        if (currentOwner == owner) {
            postAttackHoldTicks = 0;
            holdOwner = null;
            forceStop();
            isSilent = false;
            isYawOnly = false;
        }
    }

    /** Hard reset for world/session teardown. No owner, hold, decay, or exclusive lock survives it. */
    public static void abortAll() {
        exclusiveOwner = null;
        deferredReleaseOwner = null;
        deferredReleaseHard = false;
        actionOwner = null;
        postAttackHoldTicks = 0;
        holdOwner = null;
        windSpeedOwner = null;
        windYawBias = 1.0;
        windPitchBias = 1.0;
        maxSpeedCapOwner = null;
        maxSpeedCapDegPerSec = 0.0;
        yieldOwner = null;
        yieldEnabled = false;
        yieldLevel = 0.0f;
        assistStrengthScale = 1.0f;
        forceStop();
        isSilent = false;
        isYawOnly = false;
    }

    public static void setPostAttackHold(Object owner, int ticks, float yaw, float pitch) {
        if (ticks <= 0) return;
        if (owner == null || currentOwner != owner || !isActive) return;
        // A hold preserves the reached rotation; it must never become another aim setter.
        yaw = finalYaw;
        pitch = finalPitch;
        postAttackHoldTicks = ticks;
        holdYaw = yaw;
        holdPitch = pitch;
        holdOwner = owner;
        finalYaw = yaw;
        finalPitch = pitch;
    }

    /**
     * Instantly snaps the silent rotation angles to the exact specified yaw and pitch,
     * activating silent mode and quantizing to mouse GCD without anti-cheat per-tick step clamping.
     * Ideal for projectile throws (e.g. PearlCatch) requiring exact un-clamped trajectory aim.
     */
    public static void snapSilentRotation(Object owner, Priority priority, float yaw, float pitch) {
        if (rejectsAcquire(owner, priority)) return;
        Supplier<Vec3d> supplier = () -> pointAlongRotation(yaw, pitch, 64.0);
        RotationRequest req = RotationRequest.create()
                .speed(7200.0)
                .response(1.0)
                .silent(true)
                .yawOnly(false);
        setRotationSupplier(owner, priority, supplier, req);
        finalYaw = yaw;
        finalPitch = pitch;
        if (mc.player != null) {
            mc.player.setYaw(finalYaw);
            mc.player.setPitch(finalPitch);
        }
        lastSetYaw = finalYaw;
        lastSetPitch = finalPitch;
    }

    /**
     * Largest look change (degrees, per axis) a single aim step can commit while the active
     * anti-cheat profile's step ceiling applies. Callers that plan a multi-tick turn use it to
     * keep the plan inside what {@link #stepAim} can actually deliver.
     */
    public static float aimStepCeiling() {
        return antiCheatProfile == AntiCheatProfile.RAW
                ? 90.0f
                : Math.min(antiCheatProfile.maxStepYaw, antiCheatProfile.maxStepPitch);
    }

    /**
     * Turn-rate ceiling (deg/s) the live step path applies to every aim request: the owner's own
     * {@link #setMaxSpeedCap} (when it holds the channel) and the active anti-cheat profile's step ceiling
     * (none for RAW). 0 means uncapped.
     */
    private static double effectiveTurnCapDegPerSec() {
        double cap = 0.0;
        if (maxSpeedCapDegPerSec > 0.0 && currentOwner == maxSpeedCapOwner) {
            cap = maxSpeedCapDegPerSec;
        }
        if (antiCheatProfile != AntiCheatProfile.RAW) {
            double profileCap = aimStepCeiling() * 20.0;
            cap = cap > 0.0 ? Math.min(cap, profileCap) : profileCap;
        }
        return cap;
    }

    /** Pitch the aim point of {@link #stepAim} stops short of vertical at. */
    private static final float VERTICAL_AIM_LIMIT = 89.9f;

    /**
     * One owner-scoped aim step: moves the authoritative look at most {@code maxStepDeg} degrees
     * along the straight turn toward {@code yaw}/{@code pitch} and holds it until the next call.
     * The step lands on the mouse-sensitivity lattice, carries no humanization or aim offset, and
     * is capped per axis by the active profile's step ceiling, so a timed pursuit (such as
     * PearlCatch's flick) is spread over the ticks available instead of snapping. The held look is
     * what the next item use and movement packet carry; the owner releases it with
     * {@link #clearTarget(Object)}. Returns false when another owner holds the channel.
     */
    public static boolean stepAim(Object owner, Priority priority, float yaw, float pitch,
                                  float maxStepDeg, boolean silent) {
        if (rejectsAcquire(owner, priority)) return false;
        float baseYaw = getFinalYaw();
        float basePitch = getFinalPitch();
        float dy = MathHelper.wrapDegrees(yaw - baseYaw);
        float dp = MathHelper.clamp(pitch, -90.0f, 90.0f) - basePitch;
        float distance = (float) Math.sqrt(dy * dy + dp * dp);
        float scale = distance > maxStepDeg && distance > 1.0e-6f ? maxStepDeg / distance : 1.0f;
        float ceiling = aimStepCeiling();
        float stepYaw = MathHelper.clamp(dy * scale, -ceiling, ceiling);
        float stepPitch = MathHelper.clamp(dp * scale, -ceiling, ceiling);
        float goalYaw = baseYaw + stepYaw;
        float goalPitch = MathHelper.clamp(basePitch + stepPitch, -90.0f, 90.0f);
        // Far point, evaluated live from the tick eye the aim math measures from (updateInternal
        // uses getEyePos(), not the frame-interpolated eye), so the committed step is the requested
        // angle exactly and does not drift with eye interpolation between frames. A point from the
        // interpolated eye was off by the eye's lag over 64 blocks, up to a degree while falling.
        predictionTicks = 0.0;
        // Straight up or down the direction's horizontal part is float noise (cos of the float
        // radians for 90 degrees is slightly negative), which flips the yaw the aim point implies by
        // 180 degrees. Keep the aim point a hair off vertical so the yaw stays the one requested.
        float aimPitch = MathHelper.clamp(goalPitch, -VERTICAL_AIM_LIMIT, VERTICAL_AIM_LIMIT);
        Vec3d direction = RotationMath.direction(goalYaw, aimPitch).multiply(64.0);
        setRotationSupplier(owner, priority, () -> mc.player == null ? null : mc.player.getEyePos().add(direction),
                RotationRequest.create().speed(7200.0).response(1.0).humanization(0.0).prediction(0.0)
                        .reactionMs(0L).silent(silent).placement(true));
        if (currentOwner != owner) return false;
        // Commit the step now: the item use that follows in this tick, and the movement packet,
        // carry this look rather than the previous frame's.
        updateBeforeMovement(owner, baseYaw, basePitch);
        return currentOwner == owner;
    }

    public static int getPostAttackHoldTicks() {
        return postAttackHoldTicks;
    }

    /**
     * Resets the initial acceleration onset ramp, ensuring the next aim engagement
     * or retarget accelerates smoothly from rest instead of snapping at full speed.
     */
    public static void resetEngageRamp() {
        engageStartTime = System.currentTimeMillis();
    }

    public static void cancelHold(Object owner) {
        if (holdOwner == null || holdOwner == owner) {
            postAttackHoldTicks = 0;
            holdOwner = null;
            if (!isActive || targetSupplier == null) {
                forceStop();
            }
        }
    }

    // Yaw-only (vertical rotation disabled) wire-pitch shaping state.
    private static boolean yawOnlyPitchJitterSeeded;
    private static float yawOnlyPitchOffset;
    private static int yawOnlyPitchPixels;
    private static int yawOnlyPitchAge = Integer.MIN_VALUE;
    private static final float YAW_ONLY_ACTIVE_YAW = 0.35f;
    private static final float YAW_ONLY_PITCH_BAND = 0.35f;
    private static final float YAW_ONLY_PITCH_MARGIN = 88.0f;

    public static void onClientTick() {
        // Continuous controller glides at frame rate via RotationController; 20Hz tick decay is disabled.
    }

    private static void resetGcdRemainders() {
        gcdRemainderYaw = 0.0;
        gcdRemainderPitch = 0.0;
    }

    private static void forceStop() {
        stepPending = false;
        isDecaying = false;
        decayTicksRemaining = 0;
        resetGcdRemainders();
        if (isSilent && mc.player != null && mc.player == silentBoundPlayer) {
            float wireYaw = mc.player.getYaw();
            float continuousYaw = visualYaw + 360.0f * Math.round((wireYaw - visualYaw) / 360.0f);
            mc.player.setYaw(continuousYaw);
            mc.player.lastRenderYaw = continuousYaw;
            mc.player.lastYaw = continuousYaw;
            if (!isYawOnly) {
                mc.player.setPitch(visualPitch);
                mc.player.lastRenderPitch = visualPitch;
                mc.player.lastPitch = visualPitch;
            }
        }
        if (isYawOnly && yawOnlyPitchJitterSeeded && mc.player != null
                && mc.player == silentBoundPlayer) {
            mc.player.setPitch(RotationMath.clampPitch(mc.player.getPitch() - yawOnlyPitchOffset));
            mc.player.lastPitch = mc.player.getPitch();
        }
        isActive        = false;
        isSilent        = false;
        isYawOnly       = false;
        targetSupplier  = null;
        isReleasing     = false;
        lastOwner       = currentOwner;
        currentOwner    = null;
        currentPriority = Priority.LOWEST;
        activeRequest   = null;
        mode            = RotationMode.SMOOTH;
        lastUpdateNanos = -1L;
        controller.reset(false);
        yawOnlyPitchJitterSeeded = false;
        yawOnlyPitchPixels = 0;
        yawOnlyPitchAge = Integer.MIN_VALUE;
        silentBoundPlayer = null;
        stableSmoothAim = false;
        sensorJitterEnabled = true;
        // Clear the away-flick release gag so it can't carry over and silence the NEXT owner's aim
        releaseCooldownUntil = 0L;

        velocityYaw     = 0.0f;
        velocityPitch   = 0.0f;
        frameMouseYawDelta = 0.0f;
        frameMousePitchDelta = 0.0f;
        noiseYaw        = 0.0f;
        noisePitch      = 0.0f;
        mouseResistance = 0.0;
        windYaw         = 0.0;
        windPitch       = 0.0;
        windYawVel      = 0.0;
        windPitchVel    = 0.0;
        windPhase       = 0.0;
        sineSpanYaw     = 0.0f;
        sineSpanPitch   = 0.0f;
        assistMotion.reset();
        assistZoneSupplier = null;

        lastPredictSample     = null;
        lastPredictSampleTime = 0;
        predictionTicks       = 0.0;
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Per-frame update (smooth engine)
    // ──────────────────────────────────────────────────────────────────────

    private static double lastFrameSeconds = 1.0 / 60.0;

    /**
     * Per-frame rotation update. Visible and silent owners share this exact path so both deliver
     * identical rotation speed, cadence and wire deltas — silent only differs in that the user's
     * mouse look is captured into the visual camera state ({@link #handleMouseLook}) and the camera
     * renders from the visual angles, while the authoritative player rotation follows the assist.
     */
    public static void update(float tickDelta) {
        updateInternal(false, 0.0f, 0.0f);
    }

    /** Owner-scoped read-only pursuit forecast; normal render updates still own all angle writes. */
    public static float[] aimForArrival(Object owner, float yaw, float pitch, double seconds) {
        if (!isActive || isReleasing || currentOwner != owner) return null;
        double pendingFrameSeconds = Math.max(lastFrameSeconds, (System.currentTimeMillis() - lastUpdateTime) / 1000.0);
        return controller.arrivalAim(finalYaw, finalPitch, yaw, pitch, seconds, pendingFrameSeconds);
    }

    /**
     * Owner-scoped, read-only: offsets {yawLow, yawHigh, pitchLow, pitchHigh} (degrees from the current
     * angles) the controller can reach by {@code seconds} from now, counting the frame about to step. Null
     * when the caller does not own the channel.
     */
    public static double[] reachWithin(Object owner, double seconds) {
        if (!isActive || isReleasing || currentOwner != owner) return null;
        double pendingFrameSeconds = Math.max(lastFrameSeconds, (System.currentTimeMillis() - lastUpdateTime) / 1000.0);
        return controller.reachable(Math.max(0.0, seconds) + pendingFrameSeconds, pendingFrameSeconds);
    }

    /**
     * Owner-scoped, read-only: the command that lands the controller on (yaw, pitch) {@code seconds} from now,
     * counting the frame about to step (call from inside the rotation supplier). Null when not the owner.
     */
    public static float[] aimForDeadline(Object owner, float yaw, float pitch, double seconds) {
        if (!isActive || isReleasing || currentOwner != owner) return null;
        double pendingFrameSeconds = Math.max(lastFrameSeconds, (System.currentTimeMillis() - lastUpdateTime) / 1000.0);
        return controller.arrivalAim(finalYaw, finalPitch, yaw, pitch,
                Math.max(0.0, seconds) + pendingFrameSeconds, pendingFrameSeconds);
    }

    /** Owner-checked placement progression immediately before vanilla serializes movement. */
    public static void updateBeforeMovement(Object owner, float sentYaw, float sentPitch) {
        if (currentOwner == owner) updateInternal(true, sentYaw, sentPitch);
    }

    private static void updateInternal(boolean placementBoundary, float sentYaw, float sentPitch) {
        if (!isActive || mc.player == null) {
            if (isActive) forceStop();
            return;
        }
        if (silentBoundPlayer != null && mc.player != silentBoundPlayer) {
            forceStop();
            return;
        }

        long currentTime = System.currentTimeMillis();
        long currentNanos = System.nanoTime();

        double deltaTime = 1.0;
        if (lastUpdateNanos > 0L) {
            double elapsedMs = (currentNanos - lastUpdateNanos) * 1.0e-6;
            deltaTime = elapsedMs / TARGET_MS_PER_FRAME;
            deltaTime = MathHelper.clamp(deltaTime, 0.01, 3.0);
        } else if (lastUpdateTime > 0L) {
            double elapsed = currentTime - lastUpdateTime;
            deltaTime = elapsed / 16.666666666666668;
            deltaTime = MathHelper.clamp(deltaTime, 0.01, 3.0);
        }
        if (!placementBoundary) {
            lastUpdateTime = currentTime;
            lastUpdateNanos = currentNanos;
            lastFrameSeconds = deltaTime / 60.0;
        }

        // Rule 4: after an action went out this tick, the same yaw/pitch must be what the movement packet
        // carries. Freeze every step (render-frame and pre-movement) until onTickStart() expires the hold
        // at the next tick head, and pin the player's angles to it.
        if (postAttackHoldTicks > 0 && holdOwner != null && holdOwner == currentOwner
                && Float.isFinite(holdYaw) && Float.isFinite(holdPitch)) {
            finalYaw = holdYaw;
            finalPitch = holdPitch;
            if (mc.player.getYaw() != holdYaw) mc.player.setYaw(holdYaw);
            if (!isYawOnly && mc.player.getPitch() != holdPitch) mc.player.setPitch(holdPitch);
            lastSetYaw = holdYaw;
            if (!isYawOnly) lastSetPitch = holdPitch;
            return;
        }

        prevFinalYaw = finalYaw;
        prevFinalPitch = finalPitch;

        if (!isSilent) {
            visualYaw   = mc.player.getYaw();
            visualPitch = mc.player.getPitch();
        }

        boolean isAiming = targetSupplier != null && !isReleasing && currentTime >= releaseCooldownUntil;

        if (isAiming && !hasReacted) {
            if (currentTime < nextReactionTime) {
                return;
            }
            hasReacted = true;
        }

        float mouseYawDelta   = MathHelper.wrapDegrees(mc.player.getYaw() - lastSetYaw);
        float mousePitchDelta = mc.player.getPitch() - lastSetPitch;
        frameMouseYawDelta = mouseYawDelta;
        frameMousePitchDelta = mousePitchDelta;

        finalYaw += mouseYawDelta;
        if (!isYawOnly) {
            finalPitch = RotationMath.clampPitch(finalPitch + mousePitchDelta);
        }

        Vec3d targetPosition = null;
        if (isAiming) {
            targetPosition = targetSupplier.get();
            if (targetPosition == null) {
                if (!isSilent && mode == RotationMode.ASSIST) {
                    forceStop();
                    return;
                }
                isReleasing = true;
            }
        } else if (!isReleasing) {
            forceStop();
            return;
        }

        if (isReleasing) {
            if (Math.abs(RotationMath.yawDelta(finalYaw, visualYaw)) < 0.5f
                    && (isYawOnly || Math.abs(visualPitch - finalPitch) < 0.5f)) {
                forceStop();
                return;
            }
        }

        if (!placementBoundary && !isReleasing && mode == RotationMode.ASSIST) {
            applyAssistStep(deltaTime);
            if (isReleasing) {
                forceStop();
                return;
            }
            float oldYaw = mc.player.getYaw();
            float oldPitch = mc.player.getPitch();
            float appliedYaw = quantizeDelta(MathHelper.wrapDegrees(finalYaw - oldYaw), false);
            float newYaw = oldYaw + appliedYaw;
            mc.player.setYaw(newYaw);
            if (!isSilent) {
                mc.player.lastYaw += appliedYaw;
                mc.player.lastRenderYaw += appliedYaw;
            }
            lastSetYaw = newYaw;
            if (!isYawOnly) {
                float rawPitch = RotationMath.clampPitch(finalPitch) - oldPitch;
                float appliedPitch = quantizeDelta(rawPitch, false);
                float newPitch = RotationMath.clampPitch(oldPitch + appliedPitch);
                float committedPitch = newPitch - oldPitch;
                mc.player.setPitch(newPitch);
                if (!isSilent) {
                    mc.player.lastPitch = RotationMath.clampPitch(mc.player.lastPitch + committedPitch);
                    mc.player.lastRenderPitch = RotationMath.clampPitch(mc.player.lastRenderPitch + committedPitch);
                }
                lastSetPitch = newPitch;
            } else {
                lastSetPitch = oldPitch;
            }
            return;
        }

        Vec3d eyePos = (placementBoundary || isSilent) ? mc.player.getEyePos() : getInterpolatedEyePos();
        float gcd = RotationMath.mousePixelStep(((Double) mc.options.getMouseSensitivity().getValue()));
        if (placementBoundary && !isReleasing) {
            stepFromYaw = finalYaw;
            stepFromPitch = finalPitch;
            stepPending = true;
            stepTick = SubTickSequencer.currentTick();
        }
        controller.setTurnRateCap(effectiveTurnCapDegPerSec());
        double[] move = placementBoundary && !isReleasing
                ? controller.stepPlacement(finalYaw, finalPitch, eyePos, targetPosition, sentYaw, sentPitch, gcd)
                : controller.step(finalYaw, finalPitch, eyePos.x, eyePos.y, eyePos.z,
                        targetPosition, visualYaw, visualPitch, isReleasing, gcd, currentTime, deltaTime);
        float oldPitch = mc.player.getPitch();
        finalYaw += (float) move[0];
        if (!isYawOnly) {
            finalPitch = RotationMath.clampPitch(finalPitch + (float) move[1]);
        }
        if (placementBoundary && !isReleasing) {
            stepToYaw = finalYaw;
            stepToPitch = finalPitch;
            if (pendingCameraEaseMs > 0L && !isSilent) {
                cameraEasePlayer = mc.player;
                cameraEaseYaw = stepFromYaw - stepToYaw;
                cameraEasePitch = stepFromPitch - stepToPitch;
                cameraEaseStart = currentTime;
                cameraEaseDuration = pendingCameraEaseMs;
            }
            pendingCameraEaseMs = 0L;
        }
        mc.player.setYaw(finalYaw);
        if (!isYawOnly) {
            mc.player.setPitch(finalPitch);
        } else {
            applyYawOnlyPitchJitter(gcd, (float) move[0]);
        }
        if (!placementBoundary && !isSilent && !stepShownThisTick(mc.player)) {
            mc.player.lastYaw += (float) move[0];
            mc.player.lastRenderYaw += (float) move[0];
            if (!isYawOnly) {
                float pitchDelta = finalPitch - oldPitch;
                mc.player.lastPitch = RotationMath.clampPitch(mc.player.lastPitch + pitchDelta);
                mc.player.lastRenderPitch = RotationMath.clampPitch(mc.player.lastRenderPitch + pitchDelta);
            }
        }
        lastSetYaw = finalYaw;
        lastSetPitch = finalPitch;
    }

    private static void applyYawOnlyPitchJitter(float pixelStep, float yawMove) {
        if (mc.player == null) {
            return;
        }
        if (!yawOnlyPitchJitterSeeded) {
            yawOnlyPitchOffset = 0.0f;
            yawOnlyPitchPixels = 0;
            yawOnlyPitchAge = mc.player.age;
            yawOnlyPitchJitterSeeded = true;
        }
        float userPitch = mc.player.getPitch() - yawOnlyPitchOffset;
        int age = mc.player.age;
        boolean active = pixelStep > 0.0f
                && Math.abs(yawMove) >= YAW_ONLY_ACTIVE_YAW
                && Math.abs(userPitch) < YAW_ONLY_PITCH_MARGIN;
        if (active) {
            if (age != yawOnlyPitchAge) {
                yawOnlyPitchAge = age;
                yawOnlyPitchPixels = nextYawOnlyPitchPixels(pixelStep);
            }
        } else {
            yawOnlyPitchPixels = 0;
            yawOnlyPitchAge = age;
        }
        float target = userPitch + yawOnlyPitchPixels * pixelStep;
        mc.player.setPitch(RotationMath.clampPitch(target));
        yawOnlyPitchOffset = mc.player.getPitch() - userPitch;
    }

    private static int nextYawOnlyPitchPixels(float pixelStep) {
        int band = MathHelper.clamp((int) (YAW_ONLY_PITCH_BAND / pixelStep), 1, 2);
        int step = controllerRng.nextBoolean() ? 1 : -1;
        if (yawOnlyPitchPixels >= band) {
            step = -1;
        } else if (yawOnlyPitchPixels <= -band) {
            step = 1;
        }
        return MathHelper.clamp(yawOnlyPitchPixels + step, -band, band);
    }


    private static void resetWindState() {
        windYaw = 0.0;
        windPitch = 0.0;
    }

    /**
     * Rounds an angular delta to a whole number of mouse steps.
     *
     * <p>Without this the client emitted rotations at arbitrary float precision. A
     * hand on a mouse physically cannot do that -- every reachable angle is a multiple
     * of {@link MouseAimHelper#getGcd()} -- so an off-lattice angle is one of the
     * cheapest and most reliable signals a detector has.
     *
     * <p><b>Deliberately stateless.</b> {@code delta} is the gap between the internal
     * easing curve and the angle last <em>written</em> to the player, so last frame's
     * rounding leftover is already contained in it and carries itself forward. An
     * additional residue accumulator (the pattern {@link MouseAimHelper} uses, which
     * is correct there because it integrates a rate rather than a position error)
     * integrates that leftover a second time. Simulating the real path showed this
     * wound the accumulator into a limit cycle: once settled, the aim emitted a
     * perfectly periodic +1/-1 step oscillation -- 41 emissions per 120 stationary
     * frames at sensitivity 1.0 -- which is a far stronger machine signature than the
     * off-lattice angles being removed. Stateless also settles closer to target
     * (0.17 steps vs 0.83) and tracks a moving target within a quarter step.
     *
     * <p>Deltas already on the lattice (the player's own mouse, folded in during
     * silent mode) round to themselves exactly, so this is a no-op for them.
     */
    public static float quantizeDelta(float delta) {
        return quantizeDelta(delta, false);
    }

    public static float quantizeDelta(float delta, boolean withSensorJitter) {
        if (!gcdQuantization) {
            return delta;
        }
        double gcd = MouseAimHelper.getGcdDouble();
        if (gcd <= 1.0e-7 || !Float.isFinite(delta)) {
            return delta;
        }
        long steps = Math.round((double) delta / gcd);
        if (withSensorJitter && steps != 0L && ThreadLocalRandom.current().nextDouble() < 0.15) {
            steps += ThreadLocalRandom.current().nextBoolean() ? 1L : -1L;
        }
        return (float) ((double) steps * gcd);
    }

    public static float quantizeAngle(float current, float target) {
        float delta = net.minecraft.util.math.MathHelper.wrapDegrees(target - current);
        return current + quantizeDelta(delta);
    }

    public static float quantizePitch(float current, float target) {
        float delta = target - current;
        return current + quantizeDelta(delta);
    }

    public static void setGcdQuantization(boolean enabled) {
        gcdQuantization = enabled;
    }

    /** Ceiling of the engine-strength curve used by the tracking modules (AimAssist). */
    public static final double STRENGTH_SCALE_TRACK = 24.0;
    /** Ceiling used by committed and flick-based aim requests. */
    public static final double STRENGTH_SCALE_FLICK = 99.0;

    /**
     * Maps an operator-facing 0-100 "Strength" slider onto the engine strength the
     * rotation core expects.
     *
     * <p>The expression {@code 1 + pow(s, 0.72) * scale} was copy-pasted at seven call
     * sites across five modules with two different scales, so tuning the response curve
     * meant finding and editing all seven identically -- and any one of them drifting
     * would silently give that module a different feel from the rest. The exponent
     * below 1 front-loads the slider: the low half of the range spreads over more of
     * the useful turn-rate band, where the difference is actually perceptible.
     *
     * @param slider0to100 raw slider value; clamped, so callers need not pre-clamp
     * @param scale        {@link #STRENGTH_SCALE_TRACK} or {@link #STRENGTH_SCALE_FLICK}
     */
    public static double engineStrength(double slider0to100, double scale) {
        double s = MathHelper.clamp(slider0to100 / 100.0, 0.0, 1.0);
        return 1.0 + Math.pow(s, 0.72) * scale;
    }

    public static boolean isGcdQuantization() {
        return gcdQuantization;
    }

    /**
     * The smooth core: one critically-damped turn toward (targetYaw, targetPitch).
     * No random stutter, no frame skips — just a single continuous easing arc whose
     * speed/snappiness comes from {@code strength} and whose human imperfection comes
     * from {@code humanization}.
     *
     * <p>This computes the arc at full float precision on purpose. Snapping to the
     * mouse GCD happens once, at the commit point in {@link #writeRotation}, so the
     * easing curve itself stays exact and only the emitted angle is quantized.
     */
    private static void applyRotationStep(float targetYaw, float targetPitch,
                                          double deltaTime, long currentTime) {
        // Convert the normalized frame delta back into seconds for SmoothDamp.
        float dt = (float) (deltaTime * (TARGET_MS_PER_FRAME / 1000.0));
        dt = MathHelper.clamp(dt, 1.0f / 1000.0f, 0.1f);

        // Smooth C2 acceleration onset envelope (minimum-jerk polynomial: 10u^3 - 15u^4 + 6u^5).
        // Real human flicks accelerate from rest with neuromuscular inertia, satisfying P'(0) = 0, P''(0) = 0.
        // For high priority / flick aims (AutoMace / fast dives): 32-45ms smooth acceleration onset
        // starting from a firm 0.30-0.38 authority tug that accelerates continuously
        // into full tracking authority (3200 deg/s, smoothTime down to 0.016s), locking on target in <1 tick
        // while preventing instant single-frame snaps.
        // For standard tracking (AimAssist): 90ms ease-in starting from 0.18 authority.
        boolean isHighPriorityFlick = (currentPriority == Priority.HIGHEST);
        double accelDurationMs = isHighPriorityFlick
                ? (stableSmoothAim ? 32.0 : 45.0)
                : ((baseStrength >= 20.0) ? 85.0 : 90.0);
        double onsetProgress = MathHelper.clamp((currentTime - engageStartTime) / accelDurationMs, 0.0, 1.0);
        double onsetP2 = onsetProgress * onsetProgress;
        double onsetP3 = onsetP2 * onsetProgress;
        double minJerkRamp = 10.0 * onsetP3 - 15.0 * onsetP3 * onsetProgress + 6.0 * onsetP3 * onsetP2;

        double startFactor = isHighPriorityFlick
                ? (stableSmoothAim ? 0.38 : 0.30)
                : ((baseStrength >= 20.0) ? 0.12 : 0.18);
        double engageFactor = startFactor + (1.0 - startFactor) * minJerkRamp;

        // Effective strength backs off while the player deliberately pulls away (assistStrengthScale
        // is driven by the continuous projection-yield above; it is a constant 1.0 for every non-
        // AimAssist owner, so their motion is unaffected).
        double effStr = baseStrength * assistStrengthScale * engageFactor;
        effStr = MathHelper.clamp(effStr, 0.05, 300.0);

        // Normalize engine strength to compute damping parameters. For standard strength (1..25), LERP across
        // the 0.80s -> 0.07s and 70°/s -> 900°/s bands. For high strength (> 25, such as AutoMace or SpearAssist),
        // maintain a human flick ceiling: smoothTime floor at 0.040s (~40ms damping window)
        // and maxSpeed capped at 1150°/s (elite human flick ceiling).
        double n = MathHelper.clamp((Math.min(effStr, 25.0) - 1.0) / 24.0, 0.0, 1.0);
        float smoothTime;
        float maxSpeed;
        if (isHighPriorityFlick) {
            double extra = Math.max(0.0, effStr - 25.0);
            smoothTime = (float) Math.max(0.016, 0.045 - (extra * 0.00012));
            maxSpeed = (float) (600.0 + (3200.0 - 600.0) * minJerkRamp);
        } else if (effStr > 25.0) {
            double extra = effStr - 25.0;
            float minSmooth = 0.045f;
            smoothTime = (float) Math.max(minSmooth, 0.07 - (extra * 0.00018));
            maxSpeed = (float) Math.min(1150.0, 900.0 + (extra * 2.5));
            maxSpeed *= (float) (startFactor + (1.0 - startFactor) * minJerkRamp);
        } else {
            smoothTime = (float) (0.80 - n * (0.80 - 0.07));   // 0.80s (syrupy) → 0.07s (snappy)
            maxSpeed   = (float) (70.0 + n * (900.0 - 70.0));  // 70°/s (weak) → 900°/s (human flick ceiling)
            maxSpeed  *= (float) (startFactor + (1.0 - startFactor) * minJerkRamp);
        }

        // User anti-flick cap (AimAssist only): hold the turn rate below a human ceiling so it can never
        // whip 180° instantly. Only binds for this owner and only when the cap is below the strength-
        // derived maxSpeed, so normal/low Strength and other modules are unaffected.
        if (maxSpeedCapDegPerSec > 0.0 && currentOwner == maxSpeedCapOwner) {
            maxSpeed = (float) Math.min(maxSpeed, maxSpeedCapDegPerSec);
        }

        // Faster silent tracking still follows the same damped curve. Keep a minimum
        // damping window so a high multiplier cannot turn this into a one-frame snap.
        if (isSilent && silentSpeedMultiplier > 1.0) {
            smoothTime = Math.max(0.020f, (float) (smoothTime / silentSpeedMultiplier));
            maxSpeed *= (float) silentSpeedMultiplier;
        }

        // ── Humanization: a slow, SMOOTH postural sway ONLY ──
        // Real hands don't micro-jitter — they drift slightly. The old random Gaussian "micro-correction"
        // (retargeted a few times/sec off random.nextGaussian) read as jitter, so it's GONE. What remains
        // is a single low-frequency sinusoidal sway ("breathing"): a continuous, deterministic, smooth
        // drift scaled by Humanization — slight variation, never a jitter.
        float swayYaw = 0.0f, swayPitch = 0.0f;
        noiseYaw = 0.0f;
        noisePitch = 0.0f;
        if (humanization > 0.0) {
            double t = currentTime / 1000.0;
            swayYaw   = (float) (Math.sin(t * 1.6)       * humanization * 0.65);
            swayPitch = (float) (Math.sin(t * 1.1 + 1.3) * humanization * 0.42);
        }

        // WIND's own lateral wander is applied later, in updateWindWander(), on the aim point
        // itself rather than as an extra noise term here — one wander source, not two stacked.

        // Lucid low-pass: for the SMOOTH tracking path only, ease the target point through a
        // strength-scaled first-order filter to strip entity-lerp micro-jitter before it reaches
        // smoothDamp (a stutter source). Alpha stays >= 0.30 so it removes jitter without adding
        // perceptible lag; higher Strength filters less (stays responsive). TIMED/INSTANT bypass it
        // so a deadline flick / snap is never softened.
        float tgtYaw = targetYaw, tgtPitch = targetPitch;
        if (mode == RotationMode.SMOOTH || mode == RotationMode.WIND) {
            float lp = (currentPriority == Priority.HIGHEST)
                    ? (stableSmoothAim ? 0.92f : 0.85f)
                    : (float) (0.30 + 0.45 * n);
            if (!filtInit) { filtTargetYaw = targetYaw; filtTargetPitch = targetPitch; filtInit = true; }
            filtTargetYaw   += MathHelper.wrapDegrees(targetYaw - filtTargetYaw) * lp;
            filtTargetPitch += (targetPitch - filtTargetPitch) * lp;
            tgtYaw = filtTargetYaw; tgtPitch = filtTargetPitch;
        }

        float aimYaw   = tgtYaw   + noiseYaw   + swayYaw;
        float aimPitch = tgtPitch + noisePitch + swayPitch;

        // ── TIMED: a deadline-bounded human flick. While the window is open we
        // drive the rotation analytically along an under-damped 2nd-order step
        // response (accelerate → overshoot → correct), landing EXACTLY on the
        // (live) target at u=1. Because the position is a function of wall-clock
        // elapsed, it is inherently frame-rate independent. After the window we
        // fall through to the SMOOTH path so it keeps tracking a moving target.
        if (mode == RotationMode.TIMED && timedWindowMs > 0.0) {
            double u = (currentTime - timedStartTime) / timedWindowMs;
            if (u < 1.0) {
                double frac = timedProfile(u, timedZeta);

                float yawSpan  = MathHelper.wrapDegrees(aimYaw - timedStartYaw);
                float newYaw   = timedStartYaw + (float) (frac * yawSpan);
                velocityYaw    = (newYaw - finalYaw) / Math.max(dt, 1.0f / 240.0f);
                finalYaw       = finalYaw + MathHelper.wrapDegrees(newYaw - finalYaw);

                if (!isYawOnly) {
                    float pitchSpan = MathHelper.clamp(aimPitch, -90.0f, 90.0f) - timedStartPitch;
                    float newPitch  = timedStartPitch + (float) (frac * pitchSpan);
                    velocityPitch   = (newPitch - finalPitch) / Math.max(dt, 1.0f / 240.0f);
                    finalPitch      = MathHelper.clamp(newPitch, -90.0f, 90.0f);
                }
                return;
            }
            // u >= 1: window done — snap the residual to dead-on, then let the
            // SMOOTH integration below take over for continued tracking.
        }

        // Tiny deadzone: once locked (and not humanizing), stop doing work.
        float baseYawErr   = MathHelper.wrapDegrees(targetYaw - finalYaw);
        float basePitchErr = targetPitch - finalPitch;
        if (humanization <= 0.0 && Math.abs(baseYawErr) < 0.03f
                && (isYawOnly || Math.abs(basePitchErr) < 0.03f)) {
            velocityYaw   = 0.0f;
            velocityPitch = 0.0f;
            return;
        }

        // INSTANT: snap directly (used by some macro-style aims).
        if (mode == RotationMode.INSTANT) {
            finalYaw = finalYaw + MathHelper.wrapDegrees(aimYaw - finalYaw);
            if (!isYawOnly) finalPitch = MathHelper.clamp(aimPitch, -90.0f, 90.0f);
            velocityYaw   = 0.0f;
            velocityPitch = 0.0f;
            return;
        }

        // ── LINEAR / SINE ─────────────────────────────────────────────────────
        // Both were declared in RotationMode and offered as user-facing choices by
        // BowAimbot and AutoHeal, but neither had an implementation:
        // every non-TIMED, non-INSTANT mode fell through to SMOOTH, so picking them
        // changed nothing. They are real profiles now, and they are deliberately
        // distinguishable from each other -- having several genuinely different aim
        // signatures is the point when the client is being used to probe a detector.
        if (mode == RotationMode.LINEAR || mode == RotationMode.SINE) {
            float yawErr   = MathHelper.wrapDegrees(aimYaw - finalYaw);
            float pitchErr = MathHelper.clamp(aimPitch, -90.0f, 90.0f) - finalPitch;

            // Track the largest error seen this approach so progress can be
            // normalised. A materially larger error means a new target, not progress,
            // so the span re-baselines rather than treating the jump as regression.
            if (Math.abs(yawErr) > sineSpanYaw + 0.5f) sineSpanYaw = Math.abs(yawErr);
            if (Math.abs(pitchErr) > sineSpanPitch + 0.5f) sineSpanPitch = Math.abs(pitchErr);

            float rate = maxSpeed;
            if (mode == RotationMode.SINE) {
                // Half-sine velocity envelope: ease out of rest, peak mid-flight,
                // ease into the target. The 0.25 floor keeps it from stalling at the
                // endpoints where sin() goes to zero.
                double span = Math.max(1.0e-3, Math.hypot(sineSpanYaw, sineSpanPitch));
                double remaining = Math.hypot(yawErr, pitchErr);
                double progress = MathHelper.clamp(1.0 - remaining / span, 0.0, 1.0);
                rate *= (float) (0.25 + 0.75 * Math.sin(progress * Math.PI));
            }

            float maxStep = (float) (rate * dt);
            float totalErr = isYawOnly ? Math.abs(yawErr) : (float) Math.hypot(yawErr, pitchErr);
            float appliedYaw;
            float appliedPitch = 0.0f;
            if (totalErr <= maxStep || totalErr < 1.0e-5f) {
                appliedYaw = yawErr;
                if (!isYawOnly) appliedPitch = pitchErr;
            } else {
                float stepScale = maxStep / totalErr;
                appliedYaw = yawErr * stepScale;
                if (!isYawOnly) appliedPitch = pitchErr * stepScale;
            }

            finalYaw += appliedYaw;
            velocityYaw = appliedYaw / Math.max(dt, 1.0f / 240.0f);

            if (!isYawOnly) {
                finalPitch = MathHelper.clamp(finalPitch + appliedPitch, -90.0f, 90.0f);
                velocityPitch = appliedPitch / Math.max(dt, 1.0f / 240.0f);
            }

            // Arrived: drop the span so the next engagement re-measures its own.
            if (totalErr <= maxStep) {
                sineSpanYaw = 0.0f;
                sineSpanPitch = 0.0f;
            }
            return;
        }

        // ── Fixed-timestep integration (frame-rate / spec independent) ──────
        // SmoothDamp assumes dt << smoothTime. On a weak PC a single 50ms frame
        // would otherwise produce a big discrete jump (chunky aim). Integrate
        // the SAME curve in fixed ~8ms sub-steps so the motion is identical at
        // 20 FPS and 144 FPS — only the number of sub-steps changes.
        final float SUBSTEP = 1.0f / 120.0f; // 8.33ms fixed step
        int substeps = Math.max(1, (int) Math.ceil(dt / SUBSTEP));
        float subDt = dt / substeps;

        // ── WIND shaping ───────────────────────────────────────────────────────
        // WIND is no longer a separate integrator. It is the same core below, with
        // two bounded modifications:
        //
        //   * a wandering LATERAL offset on the aim point, so the path curves and can
        //     genuinely overshoot then correct instead of converging monotonically;
        //   * per-axis speed asymmetry, so yaw and pitch velocity stop correlating
        //     near 1.0.
        //
        // Both are driven by the SAME strength-derived maxSpeed as SMOOTH, which is
        // what makes the Strength slider finally govern WIND's turn rate.
        float yawSpeedCap = maxSpeed;
        float pitchSpeedCap = maxSpeed;
        if (mode == RotationMode.WIND) {
            yawSpeedCap = (float) (maxSpeed * activeYawBias());
            pitchSpeedCap = (float) (maxSpeed * activePitchBias());
            // The axis bias may exceed 1.0, so re-apply the operator's Max Turn ceiling
            // AFTER biasing — otherwise a >1 bias would let one axis turn faster than the
            // explicit anti-flick limit the user set.
            if (maxSpeedCapDegPerSec > 0.0 && currentOwner == maxSpeedCapOwner) {
                yawSpeedCap = (float) Math.min(yawSpeedCap, maxSpeedCapDegPerSec);
                pitchSpeedCap = (float) Math.min(pitchSpeedCap, maxSpeedCapDegPerSec);
            }
            updateWindWander(targetYaw, targetPitch, maxSpeed, dt);
            aimYaw += (float) windYaw;
            aimPitch += (float) windPitch;
        } else {
            windYaw = 0.0;
            windPitch = 0.0;
        }

        for (int i = 0; i < substeps; i++) {
            // Re-unwrap each sub-step so the shortest-path turn stays correct.
            float yawTarget = finalYaw + MathHelper.wrapDegrees(aimYaw - finalYaw);

            float[] ry = smoothDamp(finalYaw, yawTarget, velocityYaw, smoothTime, yawSpeedCap, subDt);
            finalYaw    = ry[0];
            velocityYaw = ry[1];

            if (!isYawOnly && pitchSpeedCap > 0.0f) {
                float pitchTarget = MathHelper.clamp(aimPitch, -90.0f, 90.0f);
                float pSmooth = (currentPriority == Priority.HIGHEST) ? smoothTime : smoothTime * 1.1f;
                float[] rp = smoothDamp(finalPitch, pitchTarget, velocityPitch, pSmooth, pitchSpeedCap, subDt);
                finalPitch    = MathHelper.clamp(rp[0], -90.0f, 90.0f);
                velocityPitch = rp[1];
            }
        }
    }

    /**
     * One ASSIST frame. The zone is re-sampled here (the generic path above only resolved its
     * centre), converted to camera angles, and the controller's delta is added to the tracked
     * rotation; writeRotation() then commits it like every other mode.
     */
    private static void applyAssistStep(double deltaTime) {
        net.minecraft.util.math.Box box = assistZoneSupplier != null ? assistZoneSupplier.get() : null;
        AssistMotion.Zone zone = assistZoneFor(box);
        if (zone == null) {
            isReleasing = true;
            return;
        }
        double dt = deltaTime * (TARGET_MS_PER_FRAME / 1000.0);
        AssistMotion.Output out = assistMotion.update(finalYaw, finalPitch, zone,
                frameMouseYawDelta, isYawOnly ? 0.0 : frameMousePitchDelta, dt, assistConfig);
        double stepCap = effectiveTurnCapDegPerSec() * dt;
        double outYaw = stepCap > 0.0 ? MathHelper.clamp(out.yaw(), -stepCap, stepCap) : out.yaw();
        double outPitch = stepCap > 0.0 ? MathHelper.clamp(out.pitch(), -stepCap, stepCap) : out.pitch();
        finalYaw += (float) outYaw;
        if (!isYawOnly) {
            finalPitch = MathHelper.clamp(finalPitch + (float) outPitch, -90.0f, 90.0f);
        }
        float invDt = (float) (1.0 / Math.max(dt, 1.0 / 240.0));
        velocityYaw = (float) outYaw * invDt;
        velocityPitch = (float) outPitch * invDt;
    }

    /**
     * Advances the WIND wander offset one frame using a continuous 2nd-order physical wind model.
     *
     * <p>Real human motor control exhibits low-frequency organic curvature and postural drift,
     * not frame-to-frame white noise. Instead of injecting discrete random bumps every millisecond
     * (which creates microstutters and velocity derivative spikes at high framerates), this:
     *   1. Computes an orthogonal curving force perpendicular to the target bearing, creating natural
     *      arcs and human-like overshoot/correction.
     *   2. Synthesizes smooth, continuous multi-harmonic drift waves (~1.5 Hz neuromuscular tremor band).
     *   3. Integrates continuous acceleration into wind velocity with viscous damping, ensuring
     *      C1/C2 continuity with ZERO microstutter.
     *   4. Applies a smooth cubic Hermite distance envelope that gently dissolves wind to zero as the
     *      crosshair arrives on target, settling silky-smoothly without dithering.
     */
    private static void updateWindWander(float targetYaw, float targetPitch, float maxSpeed, float dt) {
        double errYaw = MathHelper.wrapDegrees(targetYaw - finalYaw);
        double errPitch = MathHelper.clamp(targetPitch, -90.0f, 90.0f) - finalPitch;
        double dist = Math.hypot(errYaw, errPitch);

        if (dist < 0.25) {
            // Close settling: smoothly damp remaining wind velocity and offset to zero
            double settleDecay = Math.exp(-dt / 0.05);
            windYaw *= settleDecay;
            windPitch *= settleDecay;
            windYawVel *= settleDecay;
            windPitchVel *= settleDecay;
            return;
        }

        // Smooth cubic Hermite arrival envelope: 0 at <0.3° to 1.0 at >=5.5°
        double tDist = MathHelper.clamp((dist - 0.3) / 5.2, 0.0, 1.0);
        double envelope = tDist * tDist * (3.0 - 2.0 * tDist);

        // Advance continuous phase for organic, low-frequency hand wave
        windPhase += dt * (2.1 + 0.9 * Math.max(humanization, 0.2));

        // Multi-frequency harmonic continuous wave (smooth derivatives, zero white noise)
        double wave1 = Math.sin(windPhase * 1.65) * Math.cos(windPhase * 0.72);
        double wave2 = Math.cos(windPhase * 1.32 + 0.85) * Math.sin(windPhase * 0.88);

        // Orthogonal unit vector perpendicular to the approach direction (creates natural curved arcs)
        double normX = -errPitch / dist;
        double normY = errYaw / dist;

        // Strength-scaled force magnitude
        double forceMag = Math.min(dist * 0.40, maxSpeed * WIND_RATIO) * envelope * Math.max(humanization, 0.40);

        // Continuous accelerations: lateral curving arc + smooth harmonic undulation
        double accelYaw = (normX * 0.65 + wave1 * 0.35) * forceMag * 14.0;
        double accelPitch = (normY * 0.55 + wave2 * 0.30) * forceMag * 11.0;

        // Viscous damping factor (decay constant ~0.085s)
        double damping = Math.exp(-dt / 0.085);

        // 2nd-order integration: acceleration -> velocity -> position
        windYawVel = windYawVel * damping + accelYaw * dt;
        windPitchVel = windPitchVel * damping + accelPitch * dt;

        windYaw += windYawVel * dt;
        windPitch += windPitchVel * dt;

        // Soft-bounded perturbation: ensures wind cannot pull crosshair off the intended path
        double maxBound = Math.max(0.0, Math.min(2.5, dist * 0.5));
        windYaw = MathHelper.clamp(windYaw, -maxBound, maxBound);
        windPitch = MathHelper.clamp(windPitch, -maxBound, maxBound);
    }

    /**
     * Normalized TIMED flick envelope: fraction of the start→target distance
     * completed at normalized time {@code u} ∈ [0,1]. It is the unit step
     * response of an under-damped 2nd-order system (so it accelerates, then
     * overshoots, then corrects — the human shape), with the remaining error
     * windowed by {@code (1 - u³)} so the flick lands EXACTLY on target at u=1
     * (profile(1) == 1) while preserving the mid-flight overshoot. Endpoints are
     * exact: profile(0)=0, profile(1)=1.
     *
     * @param zeta damping ratio — lower = more overshoot/correction.
     */
    private static double timedProfile(double u, double zeta) {
        if (u <= 0.0) return 0.0;
        if (u >= 1.0) return 1.0;
        zeta = MathHelper.clamp(zeta, 0.40, 0.97);

        double a  = 4.0;                 // envelope decay: e^-a ≈ 1.8% left at u=1
        double wn = a / zeta;            // natural frequency (normalized time)
        double wd = wn * Math.sqrt(1.0 - zeta * zeta);

        // Remaining-error term of the under-damped step response (1 at u=0).
        double err = Math.exp(-a * u) * (Math.cos(wd * u) + (a / wd) * Math.sin(wd * u));
        // Window it to 0 at u=1 so the landing is dead-on, keeping the overshoot.
        err *= (1.0 - u * u * u);
        return 1.0 - err;
    }

    /**
     * Unity-style critically-damped SmoothDamp. Frame-rate independent, never
     * overshoots → produces one smooth, continuous turn. Returns {output, vel}.
     */
    private static float[] smoothDamp(float current, float target, float vel,
                                      float smoothTime, float maxSpeed, float dt) {
        smoothTime = Math.max(0.0001f, smoothTime);
        float omega = 2.0f / smoothTime;

        float x = omega * dt;
        float exp = 1.0f / (1.0f + x + 0.48f * x * x + 0.235f * x * x * x);

        float change    = current - target;
        float original  = target;
        float maxChange = maxSpeed * smoothTime;
        change = MathHelper.clamp(change, -maxChange, maxChange);
        target = current - change;

        float temp = (vel + omega * change) * dt;
        vel = (vel - omega * temp) * exp;
        float output = target + (change + temp) * exp;

        // Prevent overshooting the original target.
        if ((original - current > 0.0f) == (output > original)) {
            output = original;
            vel = (output - original) / dt;
        }
        return new float[]{ output, vel };
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Queries / helpers (unchanged public surface)
    // ──────────────────────────────────────────────────────────────────────

    public static Vec3d getCurrentRotation(Object owner) {
        if (!isActive || currentOwner != owner || mc.player == null) return null;

        Vec3d eyePos   = mc.player.getEyePos();
        float yawRad   = (float) Math.toRadians(finalYaw);
        float pitchRad = (float) Math.toRadians(finalPitch);

        double xDir = -Math.sin(yawRad) * Math.cos(pitchRad);
        double yDir = -Math.sin(pitchRad);
        double zDir =  Math.cos(yawRad) * Math.cos(pitchRad);

        return eyePos.add(new Vec3d(xDir, yDir, zDir).normalize().multiply(100));
    }

    // ── Vertical-pole yaw guard (engine-only) ────────────────────────────────
    // Straight down/up, yaw is undefined: atan2(dz,dx) over a sub-block
    // horizontal offset flips wildly with the target's lerp/lead jitter, and the
    // engine chasing those flips is what SPUN the camera in circles while
    // standing on top of an opponent (crosshair never on the hitbox → missed
    // stun slams). update() eases toward poleGuardedYaw() instead of the raw
    // bearing: inside POLE_HOLD_HZ — kept BELOW the ~0.174-block radius where a
    // correct-pitch ray still lands on the 0.6-wide hitbox at ANY yaw, so
    // freezing is geometrically safe even entering with a wrong yaw — authority
    // is 0 (hold yaw); it fades smoothly UP to full by POLE_BLEND_HZ so a
    // badly-wrong yaw still converges (never a permanent hold, the bug an earlier
    // 0.35 radius introduced). BOTH gates must agree: the hold also requires the
    // look to be near-vertical (POLE_PITCH_*), so a horizontally-close but
    // eye-level aim (CrystalAura / AutoDrain one block away) keeps full yaw
    // tracking. Applied ONLY in the engine's live aim — calculateRotationsToPos
    // stays a PURE bearing helper so geometry queries (HostApi.yawTo, AutoMace
    // stop-aim, FOV/hitbox checks) are unaffected.
    private static final double POLE_HOLD_HZ    = 0.22; // < 0.30 hitbox half-width: directly on head/feet
    private static final double POLE_BLEND_HZ   = 0.40; // >= 0.40 blocks: full horizontal separation
    private static final double POLE_PITCH_MIN  = 60.0; // deg — guard starts engaging
    private static final double POLE_PITCH_FULL = 75.0; // deg — guard fully engaged

    /** The yaw target the engine eases toward, with yaw authority faded out near the vertical pole so
     *  the camera holds yaw (no spin) instead of chasing an undefined bearing. {@code rawTargetYaw} is
     *  the pure bearing from {@link #calculateRotationsToPos} (already unwrapped relative to
     *  {@code curYaw}); {@code pitch} is its pitch. Returns the raw bearing untouched away from the pole. */
    private static float poleGuardedYaw(Vec3d targetPos, float rawTargetYaw, float pitch, float curYaw) {
        if (mc.player == null) return rawTargetYaw;

        Vec3d eye = getInterpolatedEyePos();
        double dx = targetPos.x - eye.x;
        double dy = targetPos.y - eye.y;
        double dz = targetPos.z - eye.z;
        double hz = Math.sqrt(dx * dx + dz * dz);

        // If target is horizontally separated (>= 0.40 blocks), horizontal bearing is completely stable.
        // Never suppress turning when target is horizontally away from the pole (e.g. 180 backstab flick)!
        if (hz >= POLE_BLEND_HZ) {
            return rawTargetYaw;
        }

        // Check if looking near-vertical (either target direction or current player camera)
        float viewPitch = Math.max(Math.abs(pitch), Math.abs(mc.player.getPitch()));
        if (viewPitch < POLE_PITCH_MIN) {
            return rawTargetYaw;
        }

        double hzT = MathHelper.clamp((hz - POLE_HOLD_HZ) / (POLE_BLEND_HZ - POLE_HOLD_HZ), 0.0, 1.0);
        hzT = hzT * hzT * (3.0 - 2.0 * hzT); // smoothstep — C1-continuous fade

        double steep = MathHelper.clamp((viewPitch - POLE_PITCH_MIN) / (POLE_PITCH_FULL - POLE_PITCH_MIN), 0.0, 1.0);
        steep = steep * steep * (3.0 - 2.0 * steep);

        // When hz <= 0.22 and viewPitch >= 75 deg: authority is 0.0 (exact yaw freeze, ZERO spin).
        // Away from pole: fades smoothly up to full 1.0 authority.
        double authority = 1.0 - (1.0 - hzT) * steep;
        return curYaw + (float) ((rawTargetYaw - curYaw) * authority);
    }

    public static float[] calculateRotationsToPos(Vec3d targetPos, float currentYaw) {
        if (mc.player == null) return new float[]{0, 0};
        Vec3d playerPos = getInterpolatedEyePos();
        double dx = targetPos.x - playerPos.x;
        double dy = targetPos.y - playerPos.y;
        double dz = targetPos.z - playerPos.z;
        double hz = Math.sqrt(dx * dx + dz * dz);

        float baseYaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0f;
        float pitch   = (float) -Math.toDegrees(Math.atan2(dy, hz));

        float unwrappedYaw = currentYaw + MathHelper.wrapDegrees(baseYaw - currentYaw);
        return new float[]{unwrappedYaw, MathHelper.clamp(pitch, -90.0f, 90.0f)};
    }

    /** Builds a world-space point on the look ray for an exact yaw/pitch request. */
    public static Vec3d pointAlongRotation(float yaw, float pitch) {
        return pointAlongRotation(yaw, pitch, 3.5);
    }

    /**
     * Same as {@link #pointAlongRotation(float, float)} with an explicit distance. A far point
     * keeps any world-space aim offset (humanization spread) negligible in angular terms, which
     * matters for exact-angle projectile throws.
     */
    public static Vec3d pointAlongRotation(float yaw, float pitch, double distance) {
        if (mc.player == null) return null;
        float f = pitch * 0.017453292F;
        float g = -yaw * 0.017453292F;
        float h = MathHelper.cos(g);
        float i = MathHelper.sin(g);
        float j = MathHelper.cos(f);
        float k = MathHelper.sin(f);
        return getInterpolatedEyePos().add(new Vec3d(i * j, -k, h * j).multiply(distance));
    }

    public static void setVerticalAim(boolean enabled) {
        isYawOnly = !enabled;
    }

    public static boolean isVerticalAimEnabled() {
        return !isYawOnly;
    }

    public static boolean isActive()               { return isActive; }
    public static boolean isAimActive()            { return isActive; }
    public static Object getCurrentOwner()         { return currentOwner; }
    public static boolean isReleasing()            { return isActive && isReleasing; }
    public static boolean isSilentRotationActive() { return isActive && isSilent; }
    public static boolean isSilentCameraActive(net.minecraft.entity.Entity entity) {
        return isActive && isSilent && mc.player != null && entity == mc.player && entity == silentBoundPlayer;
    }
    public static float getPrevFinalYaw()   { return isActive ? prevFinalYaw   : (mc.player != null ? mc.player.lastRenderYaw   : 0); }
    public static float getPrevFinalPitch() { return isActive ? prevFinalPitch : (mc.player != null ? mc.player.lastRenderPitch : 0); }
    public static float getVisualYaw()      { return isActive ? visualYaw      : (mc.player != null ? mc.player.getYaw() : 0.0f); }
    public static float getVisualPitch()    { return isActive ? visualPitch    : (mc.player != null ? mc.player.getPitch() : 0.0f); }

    public static float visibleCameraYaw(Entity entity, float tickDelta) {
        ClientPlayerEntity player = mc.player;
        if (player == null || entity != player || player.hasVehicle()) return Float.NaN;
        if (cameraEasing(player)) return player.getYaw() + cameraEaseYaw * cameraEaseLeft();
        if (stepShownThisTick(player)) {
            return MathHelper.lerpAngleDegrees(tickDelta, stepFromYaw, stepToYaw) + (player.getYaw() - stepToYaw);
        }
        return Float.NaN;
    }

    public static float visibleCameraPitch(Entity entity, float tickDelta) {
        ClientPlayerEntity player = mc.player;
        if (player == null || entity != player || player.hasVehicle()) return Float.NaN;
        if (cameraEasing(player)) return RotationMath.clampPitch(player.getPitch() + cameraEasePitch * cameraEaseLeft());
        if (stepShownThisTick(player)) {
            return RotationMath.clampPitch(MathHelper.lerp(tickDelta, stepFromPitch, stepToPitch)
                    + (player.getPitch() - stepToPitch));
        }
        return Float.NaN;
    }

    private static boolean stepShownThisTick(ClientPlayerEntity player) {
        return isActive && !isSilent && player == silentBoundPlayer && stepTick == SubTickSequencer.currentTick();
    }

    private static boolean cameraEasing(ClientPlayerEntity player) {
        if (cameraEaseDuration <= 0L) return false;
        if (player != cameraEasePlayer || System.currentTimeMillis() - cameraEaseStart >= cameraEaseDuration) {
            cameraEaseDuration = 0L;
            cameraEasePlayer = null;
            return false;
        }
        return true;
    }

    private static float cameraEaseLeft() {
        double t = (System.currentTimeMillis() - cameraEaseStart) / (double) cameraEaseDuration;
        double x = MathHelper.clamp(t, 0.0, 1.0);
        double eased = x * x * x * (x * (x * 6.0 - 15.0) + 10.0);
        return (float) (1.0 - eased);
    }

    public static void restoreStepOrigin(ClientPlayerEntity player) {
        if (!stepPending) return;
        stepPending = false;
        if (!isActive || player != mc.player || player != silentBoundPlayer
                || stepTick != SubTickSequencer.currentTick()) return;
        player.lastYaw = stepFromYaw;
        if (!isYawOnly) player.lastPitch = stepFromPitch;
    }

    public static void easeCameraOnNextStep(Object owner, long durationMs) {
        if (isActive && currentOwner == owner && !isSilent && durationMs > 0L) pendingCameraEaseMs = durationMs;
    }

    public static float getFinalYaw() {
        if (!isActive) return mc.player != null ? mc.player.getYaw() : 0.0f;
        return finalYaw;
    }

    public static float getFinalPitch() {
        if (!isActive) return mc.player != null ? mc.player.getPitch() : 0.0f;
        return finalPitch;
    }

    public static boolean isSprintSafetyGuardActive() {
        return sprintSafetyGuard && antiCheatProfile.sprintGuard;
    }

    public static void setSprintSafetyGuard(boolean enabled) {
        sprintSafetyGuard = enabled;
    }

    public static boolean isSprintSafetyGuard() {
        return sprintSafetyGuard;
    }

    public static AntiCheatProfile getAntiCheatProfile() {
        return antiCheatProfile;
    }

    public static void setAntiCheatProfile(AntiCheatProfile profile) {
        if (profile != null) {
            antiCheatProfile = profile;
        }
    }

    public static boolean isSmoothDecayEnabled() {
        return smoothDecayEnabled;
    }

    public static void setSmoothDecayEnabled(boolean enabled) {
        smoothDecayEnabled = enabled;
    }

    public static boolean isSilentAimFacing(net.minecraft.entity.Entity target, double reach) {
        if (mc.player == null || target == null) return false;
        float yaw = getFinalYaw();
        float pitch = getFinalPitch();
        Vec3d eyePos = mc.player.getEyePos();
        float f = pitch * 0.017453292F;
        float g = -yaw * 0.017453292F;
        float h = MathHelper.cos(g);
        float i = MathHelper.sin(g);
        float j = MathHelper.cos(f);
        float k = MathHelper.sin(f);
        Vec3d lookVec = new Vec3d(i * j, -k, h * j);
        Vec3d endVec = eyePos.add(lookVec.multiply(reach));
        net.minecraft.util.math.Box box = target.getBoundingBox().expand(0.15);
        return box.raycast(eyePos, endVec).isPresent();
    }

    public static float[] getTranslatedMovementInput(float sideways, float forward) {
        // Model B: Body-Relative ("Freecam Camera / Real Body Aim")
        // Movement is naturally oriented to the player's body in the world.
        // No synthetic translation into sideways strafes is performed.
        return new float[]{sideways, forward};
    }

    public static boolean isRotationComplete(float threshold) {
        if (!isActive || targetSupplier == null || isReleasing) return false;
        Vec3d targetPos = targetSupplier.get();
        if (targetPos == null) return false;

        float[] targetRots = calculateRotationsToPos(targetPos, finalYaw);
        float yawDiff   = Math.abs(MathHelper.wrapDegrees(finalYaw   - targetRots[0]));
        float pitchDiff = Math.abs(MathHelper.wrapDegrees(finalPitch - targetRots[1]));

        return yawDiff < threshold && (isYawOnly || pitchDiff < threshold);
    }

    private static Vec3d getInterpolatedEyePos() {
        if (mc.player == null) return Vec3d.ZERO;
        float t = mc.getRenderTickCounter().getTickProgress(false);
        // Eye offset for the CURRENT pose, not getStandingEyeHeight(). That method always
        // reports the standing 1.62 even while sneaking (~1.27), swimming/crawling (~0.4) or
        // elytra-flying (~0.4), so every pitch the engine solved in those poses was computed
        // from an eye up to 1.2 blocks above the real camera — the aim sat consistently low
        // and the crosshair missed the hitbox outright in a crawl or a dive.
        double eyeOffset = mc.player.getEyeY() - mc.player.getY();
        double x = net.minecraft.util.math.MathHelper.lerp(t, mc.player.lastRenderX, mc.player.getX());
        double y = net.minecraft.util.math.MathHelper.lerp(t, mc.player.lastRenderY, mc.player.getY()) + eyeOffset;
        double z = net.minecraft.util.math.MathHelper.lerp(t, mc.player.lastRenderZ, mc.player.getZ());
        return new Vec3d(x, y, z);
    }

    public static void forceServerLookResync() {
        if (mc.player instanceof interstellarTikiTiks.apexx.client.mixin.ClientPlayerEntityAccessor accessor) {
            accessor.setLastYawClient(Float.NaN);
            accessor.setLastPitchClient(Float.NaN);
        }
    }

    /**
     * Virtual mouse-look decoupling:
     * When silent aim is active, physical mouse movements do not fight the camera or drag sensitivity.
     * The look deltas are folded into the visual camera angles, keeping player look 100% fluid.
     */
    public static void handleMouseLook(net.minecraft.client.network.ClientPlayerEntity player, double d, double d2) {
        if (!isSilentRotationActive() || player != mc.player || player != silentBoundPlayer) {
            player.changeLookDirection(d, d2);
            return;
        }
        float realYaw = player.getYaw();
        float realPitch = player.getPitch();
        float realLastYaw = player.lastYaw;
        float realLastPitch = player.lastPitch;

        player.setYaw(visualYaw);
        player.setPitch(visualPitch);
        player.changeLookDirection(d, d2);
        visualYaw = player.getYaw();
        visualPitch = player.getPitch();

        player.setYaw(realYaw);
        player.setPitch(realPitch);
        player.lastYaw = realLastYaw;
        player.lastPitch = realLastPitch;
    }

    /**
     * Obtains the current unit rotation vector along the silent server angles.
     */
    public static Vec3d getSilentRotationVec() {
        float f = getFinalPitch() * ((float)Math.PI / 180.0f);
        float g = -getFinalYaw() * ((float)Math.PI / 180.0f);
        float h = MathHelper.cos(g);
        float i = MathHelper.sin(g);
        float j = MathHelper.cos(f);
        float k = MathHelper.sin(f);
        return new Vec3d(i * j, -k, h * j);
    }

    /**
     * Obtains the current crosshair hit result pointing along the silent server angles.
     */
    public static net.minecraft.util.hit.HitResult getSilentCrosshairTarget() {
        if (mc.player == null || mc.world == null) return null;
        return getCrosshairTarget(getFinalYaw(), getFinalPitch(),
                Math.max(DEFAULT_SILENT_REACH, silentReachExtension));
    }

    private static final double DEFAULT_SILENT_REACH = 3.5;
    /** Vanilla block interaction range, the reach a silent block/item-use cast must cover. */
    private static final double SILENT_BLOCK_REACH = 4.5;

    /**
     * Silent-angle hit result for block/item use. Blocks are resolved out to the block interaction range
     * (the attack-reach {@link #getSilentCrosshairTarget()} would re-cast a placement at 3.6-4.5 blocks to a
     * miss); an entity is still only taken inside the attack reach.
     */
    public static net.minecraft.util.hit.HitResult getSilentBlockTarget() {
        if (mc.player == null || mc.world == null) return null;
        double entityReach = Math.max(DEFAULT_SILENT_REACH, silentReachExtension);
        double blockReach = Math.max(SILENT_BLOCK_REACH, silentReachExtension);
        float yaw = getFinalYaw();
        float pitch = getFinalPitch();
        net.minecraft.util.hit.HitResult hit = getCrosshairTarget(yaw, pitch, blockReach);
        if (hit instanceof net.minecraft.util.hit.EntityHitResult entityHit
                && mc.player.getEyePos().distanceTo(entityHit.getPos()) > entityReach) {
            Vec3d eye = mc.player.getEyePos();
            Vec3d end = eye.add(RotationMath.direction(yaw, pitch).multiply(blockReach));
            return mc.world.raycast(new net.minecraft.world.RaycastContext(eye, end,
                    net.minecraft.world.RaycastContext.ShapeType.OUTLINE,
                    net.minecraft.world.RaycastContext.FluidHandling.NONE, mc.player));
        }
        return hit;
    }
    // Extra reach for the silent attack/use reroute, live only inside withSilentReach so one
    // module's extended reach never leaks into another dispatch.
    private static double silentReachExtension = 0.0;

    /** Attack reach of the silent reroute: {@code base}, extended by an enclosing {@link #withSilentReach}. */
    public static double silentAttackReach(double base) {
        return Math.max(base, silentReachExtension);
    }

    /**
     * Runs {@code action} with the silent crosshair reroute extended to {@code reach}. It only
     * extends: a value below the default reach leaves the reroute unchanged. Client thread only.
     */
    public static boolean withSilentReach(double reach, java.util.function.BooleanSupplier action) {
        double previous = silentReachExtension;
        silentReachExtension = reach;
        try {
            return action.getAsBoolean();
        } finally {
            silentReachExtension = previous;
        }
    }

    /**
     * Raycasts block and entity targets along the given yaw and pitch angles.
     */
    public static net.minecraft.util.hit.HitResult getCrosshairTarget(float yaw, float pitch, double reach) {
        if (mc.player == null || mc.world == null) return null;
        // The tick eye, not the frame-interpolated camera: the server only ever sees the position
        // the last movement packet carried, and the aim solver measures from this same eye, so the
        // gate, the dispatch and the aim all agree on one ray.
        Vec3d eyePos = mc.player.getEyePos();
        float f = pitch * ((float)Math.PI / 180.0f);
        float g = -yaw * ((float)Math.PI / 180.0f);
        float h = MathHelper.cos(g);
        float i = MathHelper.sin(g);
        float j = MathHelper.cos(f);
        float k = MathHelper.sin(f);
        Vec3d rotVec = new Vec3d(i * j, -k, h * j);
        Vec3d endVec = eyePos.add(rotVec.x * reach, rotVec.y * reach, rotVec.z * reach);

        net.minecraft.util.hit.BlockHitResult blockHit = mc.world.raycast(new net.minecraft.world.RaycastContext(
                eyePos, endVec, net.minecraft.world.RaycastContext.ShapeType.OUTLINE,
                net.minecraft.world.RaycastContext.FluidHandling.NONE, mc.player));

        double entityDist = reach;
        if (blockHit != null && blockHit.getType() != net.minecraft.util.hit.HitResult.Type.MISS) {
            entityDist = eyePos.distanceTo(blockHit.getPos());
        }

        Vec3d entityEnd = eyePos.add(rotVec.x * entityDist, rotVec.y * entityDist, rotVec.z * entityDist);
        net.minecraft.util.math.Box searchBox = mc.player.getBoundingBox().stretch(rotVec.multiply(entityDist)).expand(1.0, 1.0, 1.0);
        net.minecraft.util.hit.EntityHitResult entityHit = net.minecraft.entity.projectile.ProjectileUtil.raycast(
                mc.player, eyePos, entityEnd, searchBox,
                entity -> !entity.isSpectator() && entity.canHit(),
                entityDist * entityDist);

        if (entityHit != null) {
            return entityHit;
        }
        return blockHit;
    }

    /**
     * Hitbox edge snapping calculation:
     * Calculates nearest point on the target's bounding box face to player eye position,
     * interpolating between closest boundary edge and chest center (0.65 height) based on accuracy.
     */
    public static Vec3d calculateHitboxPoint(net.minecraft.util.math.Box box, Vec3d eyePos, double accuracy, boolean verticalAim) {
        double clampedX = MathHelper.clamp(eyePos.x, box.minX, box.maxX);
        double clampedY = MathHelper.clamp(eyePos.y, box.minY, box.maxY);
        double clampedZ = MathHelper.clamp(eyePos.z, box.minZ, box.maxZ);
        Vec3d nearestEdge = new Vec3d(clampedX, clampedY, clampedZ);

        Vec3d center = box.getCenter();
        double chestY = box.minY + (box.getLengthY() * 0.65);
        Vec3d chestCenter = new Vec3d(center.x, chestY, center.z);

        double acc = MathHelper.clamp(accuracy, 0.0, 1.0);
        Vec3d targetPoint = nearestEdge.lerp(chestCenter, acc);

        if (!verticalAim) {
            targetPoint = new Vec3d(targetPoint.x, eyePos.y, targetPoint.z);
        }
        return targetPoint;
    }
}
