package interstellarTikiTiks.apexx.client.module.implementation.combat;

import interstellarTikiTiks.apexx.client.event.EventBus;
import interstellarTikiTiks.apexx.client.event.impl.CombatSwapLockEvent;
import interstellarTikiTiks.apexx.client.event.impl.TriggerBotCooldownEvent;
import interstellarTikiTiks.apexx.client.event.impl.TriggerBotReadyEvent;
import interstellarTikiTiks.apexx.client.friend.FriendManager;
import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.ModeSetting;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import interstellarTikiTiks.apexx.client.module.SubCategory;
import interstellarTikiTiks.apexx.client.module.hooks.AttackGatingProvider;
import interstellarTikiTiks.apexx.client.module.hooks.SpearReachProvider;
import interstellarTikiTiks.apexx.client.module.hooks.ItemUseBlockProvider;
import interstellarTikiTiks.apexx.client.module.hooks.MovementInputModifier;
import interstellarTikiTiks.apexx.client.module.hooks.RenderFrameAware;
import interstellarTikiTiks.apexx.client.util.MonitorRefreshRate;
import interstellarTikiTiks.apexx.client.manager.TargetManager;
import interstellarTikiTiks.apexx.client.module.implementation.player.BotFilterProvider;
import interstellarTikiTiks.apexx.client.mixin.MinecraftClientAccessor;
import interstellarTikiTiks.apexx.client.util.ExecutionMode;
import interstellarTikiTiks.apexx.client.util.ModuleAccess;
import interstellarTikiTiks.apexx.client.util.AttackValidator;
import interstellarTikiTiks.apexx.client.util.RotationManager;
import interstellarTikiTiks.apexx.client.util.SystemInputSimulator;
import interstellarTikiTiks.apexx.client.util.InputTracker;
import interstellarTikiTiks.apexx.client.util.combat.CombatItemUtil;
import interstellarTikiTiks.apexx.client.util.combat.MeleeMechanics;
import interstellarTikiTiks.apexx.client.util.combat.SprintResetPlan;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.MaceItem;
import net.minecraft.util.Hand;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;


/**
 * TriggerBot — attacks the opponent the live ray is on, in sync with vanilla's melee rules.
 *
 * <ul>
 *   <li><b>Crits: Sync</b> (default). A ready swing is held through the whole jump (or knockback
 *       lift) and lands on the second falling tick of every fall, so spam-jumping crits at the same
 *       point each time. A sword's full charge (12.5 ticks) is slower than a jump cycle (12 ticks):
 *       waiting for it would slip each crit a tick later until one jump had no hit at all. A crit only
 *       needs a strong swing (over 0.90 charge), so when full charge would miss the sync tick the
 *       swing goes out on time at strong charge and the rhythm never drifts. The one-tick sprint
 *       release before a crit (a hit the server counts as sprinting is never a crit) is built in.</li>
 *   <li><b>Crits: Only Crits</b> never swings outside a crit; <b>Off</b> swings on charge.</li>
 *   <li><b>Sword Cooldown</b> (default 0.88, range 0.85-1.00): the charge a non-crit sword swing
 *       waits for, varied per swing (log-normal, never below 0.85). Requested by the owner as a
 *       deliberate exception to the full-charge rule; below 0.90 vanilla gives no sprint knockback.
 *       Other weapons wait for full charge.</li>
 *   <li>Never swings into the target's post-hit invulnerability, at a shield that would block it, or
 *       while AutoMace or AutoShieldBreak owns the attack.</li>
 *   <li><b>Auto Unshield</b> lowers your own raised shield one tick before the swing is due, hits,
 *       and raises it again while use is still held.</li>
 *   <li>Vanilla reach (3 blocks), live-ray entity hit passed to the vanilla doAttack() invoker.</li>
 * </ul>
 */
public final class TriggerBot extends Module implements MovementInputModifier, RenderFrameAware, ItemUseBlockProvider, interstellarTikiTiks.apexx.client.module.hooks.ClickSimulating {
    // ---- Delays ----
    private final SliderSetting profileDelay = new SliderSetting("Acquire Delay (ms)", 30, 0, 1000, 0)
            .withJitter(10)
            .setDescription("Wait between a new target being acquired and the first strike on it. 0 = strike on the first frame it is valid.")
            .setSection("Delays");
    private final SliderSetting hitGap = new SliderSetting("Extra Hit Gap (ms)", 15, 0, 500, 0)
            .withJitter(5)
            .setDescription("Extra wait after each strike, on top of the weapon's charge, before the next strike may go out. 0 = only the charge limits it.")
            .setSection("Delays");

    private final BooleanSetting sprintHitsOnly = new BooleanSetting("Sprint Hits Only", true)
            .setDescription("Anti-sweep, swords only: no grounded sword strike while you are not sprinting (that hit would sweep). Other weapons never sweep and are not held back. Airborne crits still go out, and when sprinting is impossible (Slowness, Blindness, hunger 6 or less, in water, using an item) strikes go out normally.");
    private final BooleanSetting weaponOnly = new BooleanSetting("Weapon Only", true)
            .setDescription("Only strikes while holding a sword, axe, mace, spear or trident.");


    private static final long UNSHIELD_TIMEOUT_MS = 325L;
    /** Vanilla survival melee reach. */
    private static final double RANGE = 3.0;

    // ── Settings ──────────────────────────────────────────────────────────────
    private final ModeSetting targetMode = new ModeSetting("Selection", "Aimed", "Aimed", "Nearest", "Chased")
            .setDescription("Aimed: strikes whatever the crosshair is on. Nearest: strikes only if the crosshair target is also the closest valid one. Chased: sticks to the entity you struck most recently; the first aimed target becomes the chased one.");

    private final ModeSetting crits = new ModeSetting("Crit Timing", "Locked", "Locked", "Crit Only", "Free")
            .setDescription("Locked: while airborne, keeps a charged strike back and releases it on the same falling tick of each jump, dropping sprint for that tick. Crit Only: never strikes unless it will be a critical. Free: strikes the moment the weapon is ready.");

    private final SliderSetting swordCooldown = new SliderSetting("Sword Charge Floor", 0.88, 0.85, 1.0, 2)
            .setDescription("Charge a sword needs before a non-critical strike, nudged slightly per strike and never under 0.85. Under 0.90 the hit gives no sprint knockback. Critical strikes need over 0.90; other weapons wait for a full charge.");

    private final BooleanSetting unshield = new BooleanSetting("Lower Own Guard", false)
            .setDescription("If your own shield is raised, drops it a tick ahead of the strike, hits, then lifts it again for as long as use stays held.");


    private final ModeSetting engageFilter = new ModeSetting("Engage Filter", "Players", "Anyone", "Players", "Armed", "Armed Players")
            .setDescription("Who and what may be struck. Players: other players only. Armed: any target, but only while a weapon is held (sword, axe, mace, spear, trident or another tool with attack damage). Armed Players: both limits. Anyone: no limit.");

    private final BooleanSetting onlyOnClick = new BooleanSetting("While Attack Held", false)
            .setDescription("Strikes only fire while the attack button is physically down.");

    /** Last Hit remembers the last target this long (ticks), and drops it once it is this far away. */
    private static final int LAST_HIT_MEMORY_TICKS = 200;
    private static final double LAST_HIT_MAX_DISTANCE = 12.0;
    /** A target hit this recently is an ongoing fight: no fresh reaction delay before the next swing. */
    private static final int ENGAGED_TICKS = 40;
    /** A click inside this many ticks of losing the same target keeps its reaction timer. */
    private static final int REACTION_GRACE_TICKS = 2;

    /** Sync crits land this many falling ticks after the first one (the second falling tick). */
    private static final int SYNC_FALL_TICKS = 1;
    /**
     * Crit planning horizon: a full jump arc (12 ticks, more with Jump Boost) plus the fall delay, so a
     * swing ready on the way up waits for the fall instead of going out as a rising non-crit.
     */
    private static final int CRIT_HORIZON_TICKS = 18;
    /** Knockback this recent turns the lift it causes into crit airtime (no rising hits). */
    private static final int KNOCKBACK_LIFT_TICKS = 4;

    // ── Shield State Machine Enum ────────────────────────────────────────────────
    private enum ShieldState {
        IDLE,
        DROP_SHIELD,
        ATTACK,
        RESTORE_SHIELD
    }

    // ── Runtime State ───────────────────────────────────────────────────────────
    private ShieldState shieldState = ShieldState.IDLE;
    private int unshieldTargetId = Integer.MIN_VALUE;
    private long unshieldTimeout = 0L;
    private boolean wasHoldingUseKey = false;
    private boolean shieldInputSuppressed = false;

    private int lastAcquiredTargetId = Integer.MIN_VALUE;
    private long targetAcquiredTime = 0L;

    private int critHoldTicks = 0;
    private int lastCritHoldAge = -1;
    /** Ticks until the expected swing, from the last {@link #canAttackWithWeapon} (0 = now). */
    private int swingWait = MeleeMechanics.NONE;
    /** Player age at the first tick of the current fall (crit state), or MIN_VALUE when not falling. */
    private int fallStartAge = Integer.MIN_VALUE;

    /** One-tick forward release before a planned crit. */
    private final SprintResetPlan sprintRelease = new SprintResetPlan();

    private int lastAttackAge = -1;

    /** Lowest sword charge a non-crit swing may ever use, whatever the variation draws. */
    private static final float SWORD_COOLDOWN_FLOOR = 0.85f;
    /** Spread of the per-swing variation (log-normal: usually close, occasionally a little late). */
    private static final double COOLDOWN_SPREAD = 0.012;
    private static final double COOLDOWN_SKEW = 0.55;
    /** The sword threshold drawn for the next swing, or a negative value when none is drawn yet. */
    private float swingThreshold = -1.0f;

    /** Player age at which the current crosshair target was acquired, and the ticks to wait from then. */
    private int targetAcquiredAge = 0;
    private long reactionMs = 0L;
    private int lastSeenAge = Integer.MIN_VALUE;

    /** Last render-frame evaluation (System.nanoTime), for the monitor-refresh-rate cap. */
    private long lastPollNanos = 0L;
    /** Earliest System.currentTimeMillis() at which the next strike may go out (Extra Hit Gap). */
    private long nextHitAllowedAt = 0L;

    /** Smart-mode closest target, resolved once per tick (the entity scan is not repeated per call). */
    private LivingEntity smartClosest = null;
    private int smartClosestTick = Integer.MIN_VALUE;

    public TriggerBot() {
        super("TriggerBot", "Attacks for you only when your own aim is already on an enemy: once the crosshair (the live server-side ray) rests on a valid target within reach and your weapon is charged, it clicks after a short human-like delay. It never moves your aim. Checks every frame up to your monitor's refresh rate, can require sprint hits so you never sweep, and respects friends, shields and invulnerability frames.", Category.COMBAT, SubCategory.SMP);
        addSetting(profileDelay);
        addSetting(hitGap);
        addSetting(targetMode);
        addSetting(crits);
        addSetting(swordCooldown);
        addSetting(unshield);
        addSetting(engageFilter);
        addSetting(weaponOnly);
        addSetting(sprintHitsOnly);
        addSetting(onlyOnClick);
    }

    @Override
    public void onEnable() {
        resetState();
    }

    @Override
    public void onDisable() {
        resetUnshieldState(mc);
        resetState();
    }

    @Override
    protected void onRuntimeStateReleased() {
        sprintRelease.cancel();
        resetUnshieldState(mc);
        resetState();
    }

    private void resetState() {
        this.shieldState = ShieldState.IDLE;
        this.unshieldTargetId = Integer.MIN_VALUE;
        this.unshieldTimeout = 0L;
        this.wasHoldingUseKey = false;
        this.shieldInputSuppressed = false;
        this.lastAttackAge = -1;
        this.swingThreshold = -1.0f;
        this.critHoldTicks = 0;
        this.lastCritHoldAge = -1;
        this.swingWait = MeleeMechanics.NONE;
        this.fallStartAge = Integer.MIN_VALUE;
        this.sprintRelease.cancel();
        this.lastPollNanos = 0L;
        this.nextHitAllowedAt = 0L;

        clearReactionTimer();
    }

    /**
     * Loses the crit hold only. The reaction timer survives a short crosshair flicker on the same
     * target (see {@link #REACTION_GRACE_TICKS}); {@link #checkReactionDelay} restarts it after a
     * real loss or on a new target.
     */
    private void resetReactionTimer() {
        this.critHoldTicks = 0;
    }

    private void clearReactionTimer() {
        this.lastAcquiredTargetId = Integer.MIN_VALUE;
        this.targetAcquiredTime = 0L;
        this.lastSeenAge = Integer.MIN_VALUE;
        this.critHoldTicks = 0;
    }

    /** Tick work only: the fall clock and the once-per-tick Nearest scan cache. Strikes are frame-polled. */
    @Override
    public void onTick() {
        if (!isEnabled()) return;
        updateFallClock();
        smartClosestTick = Integer.MIN_VALUE;
    }

    /**
     * Polls on every rendered frame (client thread, just before Mouse.tick), capped at the refresh
     * rate of the monitor the window is on, so the reaction is frame-accurate instead of waiting for
     * the next 50 ms tick. The crosshair is re-resolved at the frame's tick progress first.
     */
    @Override
    public void onRenderFrame() {
        if (!isEnabled() || mc == null) return;
        long now = System.nanoTime();
        int hz = MonitorRefreshRate.get(mc);
        // 5% slack so vsync-paced frames landing a hair early are not skipped every other frame.
        long minGap = (long) (1_000_000_000L / (double) hz * 0.95);
        if (lastPollNanos != 0L && now - lastPollNanos < minGap) return;
        lastPollNanos = now;
        refreshCrosshair();
        evaluateTriggerBot();
    }

    /** Remembers the first tick of the current fall, so the sync tick counts from it. */
    private void updateFallClock() {
        ClientPlayerEntity player = mc != null ? mc.player : null;
        if (player == null || !MeleeMechanics.isCritState(player)) {
            fallStartAge = Integer.MIN_VALUE;
        } else if (fallStartAge == Integer.MIN_VALUE || fallStartAge > player.age) {
            fallStartAge = player.age;
        }
    }

    private void refreshCrosshair() {
        if (mc == null || mc.player == null || mc.world == null || mc.gameRenderer == null
                || mc.currentScreen != null) {
            return;
        }
        mc.gameRenderer.updateCrosshairTarget(mc.getRenderTickCounter().getTickProgress(true));
    }

    private void evaluateTriggerBot() {
        if (mc == null || mc.player == null || mc.world == null || mc.interactionManager == null || mc.currentScreen != null) {
            resetUnshieldState(mc);
            return;
        }

        if (mc.player.isDead() || mc.player.isSpectator()) {
            resetUnshieldState(mc);
            return;
        }

        // AutoMace owns the swing only while a smash is armed, in flight, or a dive is falling at a
        // target in smash reach. A sword hit then would reset the attack cooldown the smash needs.
        // The reaction timer is kept so the swing is prompt once the dive is over.
        if (isAutoMaceOwningAttack()) {
            resetUnshieldState(mc);
            return;
        }

        // AutoShieldBreak runs the whole axe swap, hit and swap back; a sword swing in between would
        // reset the charge and fight its slot selection.
        AutoShieldBreak shieldBreak = ModuleAccess.getModule(AutoShieldBreak.class);
        if (shieldBreak != null && shieldBreak.isEnabled() && shieldBreak.isBusy()) {
            resetUnshieldState(mc);
            return;
        }

        // Process active unshield state machine
        if (processUnshieldSequence(mc)) {
            return;
        }

        boolean playerIsBlocking = mc.player.isBlocking() || mc.player.isUsingItem() || isUseKeyPressed(mc);
        // Auto Unshield only lowers a shield: eating, drinking, a bow or any other item use is left alone.
        boolean canUnshield = unshield.isEnabled() && isHoldingShield(mc.player)
                && (!mc.player.isUsingItem() || mc.player.getActiveItem().isOf(Items.SHIELD));

        if (playerIsBlocking && !canUnshield) {
            resetReactionTimer();
            return;
        }

        if (onlyOnClick.isEnabled() && !isAttackButtonHeld(mc)) {
            resetReactionTimer();
            return;
        }

        if (weaponOnly.isEnabled() && !isStrictWeapon(mc.player.getMainHandStack())) {
            resetReactionTimer();
            return;
        }

        double hitReach = currentReach();
        EntityHitResult entityHit = resolveTarget(mc, hitReach);
        if (entityHit == null) {
            resetReactionTimer();
            return;
        }

        Entity rawEntity = entityHit.getEntity();
        if (!(rawEntity instanceof LivingEntity target) || !isAllowedTarget(mc, target)) {
            resetReactionTimer();
            return;
        }

        if (!isTargetModeValid(target)) {
            resetReactionTimer();
            return;
        }

        if (!isWithinDistance(mc, entityHit, target, hitReach)) {
            resetReactionTimer();
            return;
        }

        // Reaction time delay window
        if (!checkReactionDelay(target)) {
            return;
        }

        // A raised shield: with AutoShieldBreak on "On Swing" this is the click that starts its axe
        // break (a sword swing into the shield would be wasted and reset the charge). The break has
        // its own timing, so it does not wait for the sword to charge.
        if (shieldBlocks(target) && shieldBreak != null && shieldBreak.wouldBreakOnSwing(target)) {
            shieldBreak.breakForSwing(target);
            return;
        }

        // Weapon cooldown gating & crit planning
        boolean ready = canAttackWithWeapon(mc, target);
        // Auto Unshield: vanilla cannot attack in the tick a use is released, so the shield goes
        // down one tick before the swing is due and the hit lands on time.
        boolean unshieldNow = canUnshield && playerIsBlocking && (ready || swingWait == 1);
        if (!ready && !unshieldNow) {
            return;
        }

        // Post-hit invulnerability: a swing into i-frames is wasted and resets the cooldown.
        // onTick runs at tick head before LivingEntity.baseTick decrements hurtTime, so hurtTime == 1
        // expires during this tick before the server processes the attack. A shield lowered for a
        // swing due next tick is judged against next tick's value.
        if (target.hurtTime > (unshieldNow && !ready ? 2 : 1)) {
            return;
        }

        // A shield that would block the hit: AutoShieldBreak's job, or a wasted swing.
        if (shieldBlocks(target)) {
            return;
        }

        // Anti-sweep: a grounded non-sprint sword hit sweeps. Airborne hits (crits) and non-swords cannot sweep.
        if (sprintHitsOnly.isEnabled() && CombatItemUtil.isSword(mc.player.getMainHandStack())
                && mc.player.isOnGround() && !mc.player.isSprinting() && canSprint(mc.player)) {
            return;
        }

        if (System.currentTimeMillis() < nextHitAllowedAt) {
            return;
        }

        // Self unshielding path
        if (unshieldNow) {
            startUnshieldSequence(mc, target);
            return;
        }

        // Deliver attack
        performAttack(mc, entityHit, target);
    }

    // ── Unshield State Machine ─────────────────────────────────────────────────

    private boolean processUnshieldSequence(MinecraftClient client) {
        if (this.shieldState == ShieldState.IDLE) {
            return false;
        }

        if (client.player == null || client.world == null || client.interactionManager == null || client.currentScreen != null) {
            resetUnshieldState(client);
            return true;
        }

        if (isAutoMaceOwningAttack()) {
            resetUnshieldState(client);
            return true;
        }

        long now = System.currentTimeMillis();
        if (now > this.unshieldTimeout) {
            resetUnshieldState(client);
            return true;
        }

        LivingEntity target = getUnshieldTarget(client);
        if (target == null) {
            resetUnshieldState(client);
            return true;
        }

        switch (this.shieldState) {
            case DROP_SHIELD -> {
                if (!isHoldingOrUsingShield(client.player) && !client.player.isUsingItem()) {
                    this.shieldState = ShieldState.ATTACK;
                    this.unshieldTimeout = now + UNSHIELD_TIMEOUT_MS;
                    return executeUnshieldAttack(client, target, now);
                }
                return true;
            }
            case ATTACK -> {
                return executeUnshieldAttack(client, target, now);
            }
            case RESTORE_SHIELD -> {
                restoreShieldState(client);
                return true;
            }
        }
        return true;
    }

    private boolean executeUnshieldAttack(MinecraftClient client, LivingEntity target, long now) {
        if (isHoldingOrUsingShield(client.player) || client.player.isUsingItem()) {
            return true;
        }

        double reach = RANGE;
        EntityHitResult entityHit = resolveTarget(client, reach);
        if (entityHit == null || entityHit.getEntity() != target) {
            resetUnshieldState(client);
            return true;
        }

        if (!isWithinDistance(client, entityHit, target, reach)) {
            resetUnshieldState(client);
            return true;
        }

        if (target.hurtTime > 1) {
            return true;
        }

        if (!canAttackWithWeapon(client, target)) {
            return true;
        }

        if (shieldBlocks(target)) {
            resetUnshieldState(client);
            return true;
        }

        performAttack(client, entityHit, target);

        if (this.wasHoldingUseKey && hasShieldInHand(client.player)) {
            this.shieldState = ShieldState.RESTORE_SHIELD;
            this.unshieldTimeout = now + UNSHIELD_TIMEOUT_MS;
        } else {
            resetUnshieldState(client);
        }
        return true;
    }

    private void startUnshieldSequence(MinecraftClient client, LivingEntity target) {
        if (client.player == null || client.interactionManager == null || target == null) return;
        this.wasHoldingUseKey = isUseKeyPressed(client) && hasShieldInHand(client.player);
        // Suppression goes through the shared ItemUseBlockProvider seam (doItemUse is cancelled while
        // shieldInputSuppressed), so the user's binding state is never rewritten. Lowering a shield that is
        // already up still needs the vanilla release interaction; the send seam records it in
        // TickActionOrder, so no attack shares its movement interval.
        this.shieldInputSuppressed = true;
        if (client.player.isUsingItem()) {
            client.interactionManager.stopUsingItem(client.player);
        }
        this.shieldState = ShieldState.DROP_SHIELD;
        this.unshieldTargetId = target.getId();
        this.unshieldTimeout = System.currentTimeMillis() + UNSHIELD_TIMEOUT_MS;
    }

    /** Shared use-suppression seam: vanilla right-click use is cancelled while the unshield sequence runs. */
    @Override
    public boolean shouldBlockItemUse() {
        return this.shieldInputSuppressed;
    }

    private void restoreShieldState(MinecraftClient client) {
        if (this.shieldInputSuppressed && client != null && client.options != null && client.options.useKey != null) {
            // Re-sync the binding to the hardware through the shared input seam.
            SystemInputSimulator.restorePhysicalState(client.options.useKey);
        }
        this.shieldState = ShieldState.IDLE;
        this.unshieldTargetId = Integer.MIN_VALUE;
        this.unshieldTimeout = 0L;
        this.wasHoldingUseKey = false;
        this.shieldInputSuppressed = false;
    }

    private void resetUnshieldState(MinecraftClient client) {
        restoreShieldState(client);
    }

    private LivingEntity getUnshieldTarget(MinecraftClient client) {
        if (client.world == null || this.unshieldTargetId == Integer.MIN_VALUE) return null;
        Entity entity = client.world.getEntityById(this.unshieldTargetId);
        if (entity instanceof LivingEntity living && isAllowedTarget(client, living)) {
            return living;
        }
        return null;
    }

    // ── Target Resolution & Reach ──────────────────────────────────────────────

    /** The entity the live ray is on: the visible crosshair, or the silent angles while a silent rotation is active. */
    private EntityHitResult resolveTarget(MinecraftClient client, double reach) {
        if (reach > RANGE && client.player != null) {
            // Spear reach: the vanilla pick only reaches the held sword's 3.0, so cast the live ray further.
            boolean silent = RotationManager.isSilentRotationActive();
            HitResult far = RotationManager.getCrosshairTarget(
                    silent ? RotationManager.getFinalYaw() : client.player.getYaw(),
                    silent ? RotationManager.getFinalPitch() : client.player.getPitch(), reach);
            return far instanceof EntityHitResult ehr ? ehr : null;
        }
        HitResult crosshair = RotationManager.isSilentRotationActive() && client.player != null
                ? RotationManager.getCrosshairTarget(RotationManager.getFinalYaw(), RotationManager.getFinalPitch(),
                        Math.min(reach, client.player.getEntityInteractionRange()))
                : client.crosshairTarget;
        return crosshair instanceof EntityHitResult ehr ? ehr : null;
    }

    private boolean isWithinDistance(MinecraftClient client, EntityHitResult hitResult, LivingEntity target, double reach) {
        if (client.player == null || hitResult == null || target == null) return false;
        double distSq = client.player.squaredDistanceTo(target);
        if (distSq < 0.04) return false;

        double eyeDistance = client.player.getEyePos().distanceTo(hitResult.getPos());
        return eyeDistance <= reach + 1.0E-4;
    }

    private boolean isAllowedTarget(MinecraftClient client, LivingEntity target) {
        if (target == null || target == client.player || !target.isAlive() || target.isRemoved() || target.getHealth() <= 0.0f) {
            return false;
        }
        if (target.isInvisible() && !(target instanceof PlayerEntity)) {
            return false;
        }
        if ((engageFilter.is("Players") || engageFilter.is("Armed Players")) && !(target instanceof PlayerEntity)) {
            return false;
        }
        if (target instanceof PlayerEntity player) {
            if (player.isSpectator() || player.isCreative()) return false;
            if (FriendManager.getInstance().isFriend(player.getUuid())) return false;
            if (!interstellarTikiTiks.apexx.client.util.combat.StickyTargets.allows(this, player)) return false;
        }
        for (BotFilterProvider botFilter : ModuleAccess.getEnabledHooks(BotFilterProvider.class)) {
            if (botFilter.isBot(target)) return false;
        }
        if ((engageFilter.is("Armed") || engageFilter.is("Armed Players")) && !isWeapon(client.player.getMainHandStack())) {
            return false;
        }
        return true;
    }

    private boolean isTargetModeValid(LivingEntity target) {
        if (targetMode.is("Aimed")) {
            return true;
        }
        if (targetMode.is("Chased")) {
            // Only the player you last hit; with none remembered yet, the crosshair target is accepted
            // and becomes the last hit once the swing lands.
            LivingEntity last = resolveLastHitTarget();
            return last == null || last == target;
        }
        // In Smart mode, only attack if this is the closest/locked living entity
        if (mc.world == null || mc.player == null) {
            return true;
        }
        if (smartClosestTick != mc.player.age) {
            LivingEntity closest = null;
            double closestDist = Double.MAX_VALUE;
            for (Entity entity : mc.world.getEntities()) {
                if (entity instanceof LivingEntity living && isAllowedTarget(mc, living)) {
                    double d = mc.player.squaredDistanceTo(living);
                    if (d < closestDist) {
                        closestDist = d;
                        closest = living;
                    }
                }
            }
            smartClosest = closest;
            smartClosestTick = mc.player.age;
        }
        LivingEntity closest = smartClosest;
        return closest == null || closest == target || closest.getUuid().equals(target.getUuid());
    }

    /**
     * The player Last Hit holds on: a ClickGUI/manual lock first, then whoever the local player last
     * attacked (a manual click or this bot's own swing, both seen by {@link MeleeMechanics}), then the
     * last manual click. Null once that target is dead, gone, far away or forgotten.
     */
    private LivingEntity resolveLastHitTarget() {
        if (mc.world == null || mc.player == null) return null;
        LivingEntity locked = TargetManager.getLockedTarget();
        if (isLastHitCandidate(locked)) return locked;

        MeleeMechanics.AttackRecord last = MeleeMechanics.lastAttack();
        if (last != null && mc.player.age - last.age() <= LAST_HIT_MEMORY_TICKS
                && mc.world.getEntityById(last.targetId()) instanceof LivingEntity living
                && isLastHitCandidate(living)) {
            return living;
        }

        LivingEntity manual = TargetManager.getLastManualHitTarget();
        if (TargetManager.msSinceLastHit() <= LAST_HIT_MEMORY_TICKS * 50L && isLastHitCandidate(manual)) {
            return manual;
        }
        return null;
    }

    private boolean isLastHitCandidate(LivingEntity entity) {
        return entity != null && entity != mc.player && entity.isAlive() && !entity.isRemoved()
                && entity.getHealth() > 0.0f && isAllowedTarget(mc, entity)
                && mc.player.distanceTo(entity) <= LAST_HIT_MAX_DISTANCE;
    }

    // ── Reaction Timing ───────────────────────────────────────────────────────

    /**
     * Reaction window for a newly acquired crosshair target: Acquire Delay (Gaussian around the
     * slider), measured in ms against frame-polled evaluation, so 0 strikes on the first valid frame.
     */
    private boolean checkReactionDelay(LivingEntity target) {
        if (target == null || mc.player == null) {
            clearReactionTimer();
            return false;
        }
        int age = mc.player.age;
        boolean lostTooLong = this.lastSeenAge == Integer.MIN_VALUE || age - this.lastSeenAge > REACTION_GRACE_TICKS;
        this.lastSeenAge = age;
        if (this.lastAcquiredTargetId != target.getId() || lostTooLong) {
            this.lastAcquiredTargetId = target.getId();
            this.targetAcquiredTime = System.currentTimeMillis();
            this.targetAcquiredAge = age;
            this.reactionMs = Math.max(0L, Math.round(profileDelay.sample()));
        }
        // Mid-fight (this target was hit a moment ago) the next swing is not a new reaction.
        MeleeMechanics.AttackRecord last = MeleeMechanics.lastAttack();
        if (last != null && last.targetId() == target.getId() && age - last.age() <= ENGAGED_TICKS) {
            return true;
        }
        return System.currentTimeMillis() - this.targetAcquiredTime >= this.reactionMs;
    }

    // ── Weapon Cooldown & Crit Sync ────────────────────────────────────────────

    /**
     * Charge a non-crit swing needs. A sword uses Sword Cooldown with a per-swing variation drawn
     * once and kept until that swing goes out (so the threshold does not flicker tick to tick); the
     * draw is log-normal around the setting and never below {@link #SWORD_COOLDOWN_FLOOR}. Every
     * other weapon waits for full charge.
     */
    private float resolveRequiredCooldown(ItemStack hand, LivingEntity target) {
        double base = 1.0;
        if (CombatItemUtil.isSword(hand)) {
            if (swingThreshold < 0.0f) {
                double z = java.util.concurrent.ThreadLocalRandom.current().nextGaussian();
                double draw = swordCooldown.getValue() + COOLDOWN_SPREAD * (Math.exp(COOLDOWN_SKEW * z) - 1.0);
                swingThreshold = MathHelper.clamp((float) draw, SWORD_COOLDOWN_FLOOR, 1.0f);
            }
            base = swingThreshold;
        }
        TriggerBotCooldownEvent event = new TriggerBotCooldownEvent(base, target);
        EventBus.post(event);
        return MathHelper.clamp((float) event.getThreshold(), 0.1f, 1.0f);
    }

    /**
     * Ticks until {@code player.getAttackCooldownProgress(0.5f)} reaches {@code targetProgress}
     * ({@code >} when {@code strictGreater} is true, {@code >=} otherwise).
     */
    private static int ticksUntilCooldown(ClientPlayerEntity player, float targetProgress, boolean strictGreater) {
        float now = player.getAttackCooldownProgress(0.5f);
        if (strictGreater ? now > targetProgress : now >= targetProgress) {
            return 0;
        }
        float perTick = player.getAttackCooldownProgressPerTick();
        if (perTick <= 0.0f) {
            return 0;
        }
        float currentTicks = now * perTick;
        float neededTicks = targetProgress * perTick;
        int t = Math.max(1, (int) Math.ceil(neededTicks - currentTicks));
        if (strictGreater && (currentTicks + t) / perTick <= targetProgress) {
            t++;
        }
        return t;
    }

    /**
     * Whether the swing goes out this tick. Also leaves {@link #swingWait} at the ticks until the
     * swing is expected (0 = now, {@link MeleeMechanics#NONE} = unknown), which Auto Unshield uses to
     * lower the shield exactly one tick ahead.
     */
    private boolean canAttackWithWeapon(MinecraftClient client, LivingEntity target) {
        swingWait = MeleeMechanics.NONE;
        if (client.player == null) return false;
        ClientPlayerEntity player = client.player;
        ItemStack hand = player.getMainHandStack();
        float progress = player.getAttackCooldownProgress(0.5f);
        float required = resolveRequiredCooldown(hand, target);
        int chargeTicks = ticksUntilCooldown(player, required, false);

        if (isMace(hand)) {
            if (progress < required) {
                swingWait = chargeTicks;
                return false;
            }
            boolean gated = shouldGateMaceCrits(client);
            swingWait = gated ? MeleeMechanics.NONE : 0;
            return !gated;
        }

        if (!crits.is("Free")) {
            int critTicks = planCrit(player, target);
            if (critTicks == 0) {
                critHoldTicks = 0;
                swingWait = 0;
                return true;
            }
            if (critTicks > 0 && countCritHold(player)) {
                swingWait = critTicks;
                return false;
            }
            if (crits.is("Crit Only")) {
                return false;
            }
        }
        critHoldTicks = 0;

        if (progress < required) {
            swingWait = chargeTicks;
            return false;
        }
        swingWait = 0;
        return true;
    }

    /**
     * Counts one held tick of a planned crit. A plan that keeps slipping later (a ceiling, a ladder,
     * a server correction) is given up after {@link #CRIT_HORIZON_TICKS} plus a small margin, so a
     * charged swing is never withheld indefinitely.
     */
    private boolean countCritHold(ClientPlayerEntity player) {
        if (player.getAttackCooldownProgress(0.5f) <= MeleeMechanics.STRONG_ATTACK_THRESHOLD) {
            // Still charging: nothing is being withheld yet.
            critHoldTicks = 0;
            return true;
        }
        if (critHoldTicks > CRIT_HORIZON_TICKS + 2) {
            return false;
        }
        if (lastCritHoldAge != player.age) {
            lastCritHoldAge = player.age;
            critHoldTicks++;
        }
        return true;
    }

    /**
     * Crit Sync: the ticks until the planned crit swing of this airtime, 0 to swing now, or
     * {@link MeleeMechanics#NONE} when no crit is planned (on the ground, impossible, or the target
     * will be out of reach by then).
     *
     * <p>The sync tick is the {@link #SYNC_FALL_TICKS}-th falling tick after the first one, however
     * long the jump has left. The swing takes full charge (the Cooldown setting) when that is ready
     * by the sync tick; otherwise it goes on the sync tick at strong charge (over 0.90, still a crit),
     * so a weapon whose full charge is slower than the jump cycle (a sword: 12.5 ticks against 12)
     * never drifts a tick per jump until a jump goes by with no hit.
     */
    private int planCrit(ClientPlayerEntity player, LivingEntity target) {
        if (!MeleeMechanics.canEverCrit(player)) {
            return MeleeMechanics.NONE;
        }
        boolean airborne = !player.isOnGround();
        // Knockback on the ground leaves the player on the ground for one more tick with an upward
        // velocity; after that the lift is ordinary airtime.
        boolean launched = player.getVelocity().y > 0.0 && player.isOnGround()
                && MeleeMechanics.ticksSinceSelfKnockback(player) <= KNOCKBACK_LIFT_TICKS;
        boolean jumping = player.isOnGround() && !launched && mc.options != null && mc.options.jumpKey.isPressed();
        if (!airborne && !launched && !jumping) {
            return MeleeMechanics.NONE;
        }

        // With the server still sprinting, one movement tick must pass first to send the STOP.
        boolean serverSprinting = MeleeMechanics.isServerSprinting(player);
        int sprintTicks = serverSprinting ? 1 : 0;
        int strongTicks = ticksUntilCooldown(player, MeleeMechanics.STRONG_ATTACK_THRESHOLD, true);
        boolean critStateNow = MeleeMechanics.isCritState(player);
        int fallen = fallStartAge == Integer.MIN_VALUE ? 0 : player.age - fallStartAge;
        double startVy = jumping ? MeleeMechanics.jumpVelocity(player) : player.getVelocity().y;

        // The earliest tick with a strong swing that is also on the sync tick. When the Cooldown
        // charge is ready by then it is taken there too; when it is not, waiting for it would be a drift.
        int ticks = MeleeMechanics.ticksUntilCritSwing(player, startVy, critStateNow, fallen, SYNC_FALL_TICKS,
                Math.max(sprintTicks, strongTicks), CRIT_HORIZON_TICKS);
        if (ticks == MeleeMechanics.NONE) {
            return MeleeMechanics.NONE;
        }
        if (ticks == 0) {
            // The server-side sprint model can disagree for a tick; Only Crits never swings a non-crit.
            return MeleeMechanics.wouldCrit(player, target) || !crits.is("Crit Only") ? 0 : MeleeMechanics.NONE;
        }
        if (!MeleeMechanics.inReachAfter(player, target, ticks, RANGE, startVy)) {
            return MeleeMechanics.NONE;
        }
        // The STOP goes out at the end of the movement tick just before the crit swing.
        if (serverSprinting && ticks == 1 && !sprintRelease.isActive() && player.isSprinting()) {
            sprintRelease.start(SprintResetPlan.Kind.W_TAP, 0, 1, false);
        }
        return ticks;
    }

    @Override
    public PlayerInput modifyMovementInput(MinecraftClient client, PlayerInput input) {
        if (!isEnabled() || !sprintRelease.isActive() || client.player == null) {
            return input;
        }
        return SprintResetPlan.apply(sprintRelease.next(client.player.isSprinting()), input);
    }

    private boolean shouldGateMaceCrits(MinecraftClient client) {
        if (crits.is("Free") || client == null || client.player == null) {
            return false;
        }
        ClientPlayerEntity player = client.player;
        if (player.isOnGround() || player.isGliding()) {
            return false;
        }
        if (MaceItem.shouldDealAdditionalDamage(player)) {
            return false;
        }
        return !(player.fallDistance >= 1.5 && player.getVelocity().y < 0.0);
    }

    // ── Attack Execution ───────────────────────────────────────────────────────

    private void performAttack(MinecraftClient client, EntityHitResult entityHit, LivingEntity target) {
        if (client.player == null || client.interactionManager == null) return;
        if (this.lastAttackAge == client.player.age) return;

        // Attack gating hooks (e.g. HitSelect)
        for (AttackGatingProvider gating : ModuleAccess.getEnabledHooks(AttackGatingProvider.class)) {
            if (gating.shouldGateAttackInput()) {
                return;
            }
        }

        CombatSwapLockEvent lock = new CombatSwapLockEvent();
        EventBus.post(lock);
        if (lock.isAutoAttackLocked()) return;

        TriggerBotReadyEvent readyEvent = new TriggerBotReadyEvent(target);
        EventBus.post(readyEvent);
        if (readyEvent.isCancelled()) return;

        // Vanilla's own doAttack acts on the live ray (the mixin reroutes it along the silent angles
        // while a silent rotation is active); the crosshair is never rewritten to make a swing possible.
        // Legit mode clears vanilla's miss-penalty lockout (a stray click otherwise silences the bot for
        // half a second); Direct mode leaves vanilla's own gate intact. Same seam as ClickSimController.
        if (client instanceof MinecraftClientAccessor accessor) {
            ExecutionMode.relaxAttackCooldown(accessor);
        }
        boolean attacked;
        if (client.player.getEyePos().distanceTo(entityHit.getPos()) > RANGE + 0.05) {
            // Past the sword's reach: only the spear swap can land this hit, a sword swing would just miss.
            attacked = false;
            for (SpearReachProvider spear : ModuleAccess.getEnabledHooks(SpearReachProvider.class)) {
                if (spear.attackFromSpearReach(entityHit)) {
                    attacked = true;
                    break;
                }
            }
        } else {
            attacked = AttackValidator.invokeVanillaAttack(client);
        }

        // A refused swing (handler, gate, cooldown) is retried next evaluation, not recorded as done.
        if (attacked) {
            this.lastAttackAge = client.player.age;
            this.swingThreshold = -1.0f;
            this.critHoldTicks = 0;
            this.nextHitAllowedAt = System.currentTimeMillis() + Math.max(0L, Math.round(hitGap.sample()));
        }
    }

    // ── Helper Utilities ────────────────────────────────────────────────────────

    /** The melee reach right now: 3.0, or the spear's when SpearReach can swap one in for the hit. */
    private double currentReach() {
        double reach = RANGE;
        for (SpearReachProvider spear : ModuleAccess.getEnabledHooks(SpearReachProvider.class)) {
            reach = Math.max(reach, spear.spearReach());
        }
        return reach;
    }

    private double getEffectiveRange() {
        return RANGE;
    }

    /** Engage filter "Armed": any weapon, including other tools that carry attack attributes. */
    private boolean isWeapon(ItemStack stack) {
        return CombatItemUtil.isWeapon(stack);
    }

    /** "Weapon Only": sword, axe, mace, spear or trident only. */
    private static boolean isStrictWeapon(ItemStack stack) {
        return CombatItemUtil.isStrictWeapon(stack);
    }

    /** Whether sprinting is possible at all right now; when it is not, Sprint Hits Only steps aside. */
    private static boolean canSprint(ClientPlayerEntity player) {
        if (player.hasStatusEffect(StatusEffects.SLOWNESS) || player.hasStatusEffect(StatusEffects.BLINDNESS)) {
            return false;
        }
        if (!player.getAbilities().allowFlying && player.getHungerManager().getFoodLevel() <= 6) {
            return false;
        }
        if (player.isTouchingWater() && !player.isSwimming()) return false;
        return !player.isUsingItem();
    }

    private boolean isMace(ItemStack stack) {
        return CombatItemUtil.isMace(stack);
    }

    private boolean isHoldingShield(LivingEntity entity) {
        if (entity == null) return false;
        return entity.getMainHandStack().isOf(Items.SHIELD) || entity.getOffHandStack().isOf(Items.SHIELD);
    }

    private boolean isHoldingOrUsingShield(LivingEntity entity) {
        if (entity == null) return false;
        if (entity.isBlocking()) return true;
        return entity.isUsingItem() && entity.getActiveItem().isOf(Items.SHIELD);
    }

    /**
     * Whether the target's shield would block a hit from us: blocking (past its raise delay) and
     * facing us within the shield's 90 degrees of head yaw (LivingEntity.getDamageBlockedAmount).
     */
    private boolean shieldBlocks(LivingEntity target) {
        if (target == null || mc.player == null || !target.isBlocking()) return false;
        Vec3d look = Vec3d.fromPolar(0.0f, target.getHeadYaw());
        Vec3d toUs = new Vec3d(mc.player.getX() - target.getX(), 0.0, mc.player.getZ() - target.getZ());
        return toUs.lengthSquared() < 1.0e-6 || toUs.normalize().dotProduct(look) >= 0.0;
    }

    private boolean hasShieldInHand(ClientPlayerEntity player) {
        if (player == null) return false;
        for (Hand hand : Hand.values()) {
            ItemStack stack = player.getStackInHand(hand);
            if (!stack.isEmpty() && stack.isOf(Items.SHIELD)) return true;
        }
        return false;
    }

    private boolean isUseKeyPressed(MinecraftClient client) {
        return client != null && client.options != null && client.options.useKey != null
                && (client.options.useKey.isPressed() || isPhysicalUseKeyPressed(client));
    }

    /** The bound use key (a mouse button by default) as the hardware reports it. */
    private boolean isPhysicalUseKeyPressed(MinecraftClient client) {
        if (client == null || client.options == null || client.options.useKey == null) {
            return false;
        }
        return SystemInputSimulator.isPhysicallyPressed(client.options.useKey);
    }

    private boolean isAutoMaceOwningAttack() {
        AutoMace autoMace = ModuleAccess.getModule(AutoMace.class);
        return autoMace != null && autoMace.isEnabled() && autoMace.isSmashImminent();
    }

    private boolean isAttackButtonHeld(MinecraftClient client) {
        if (client == null) return false;
        // Physical button state as recorded by the mouse seam; synthetic presses are not tracked there.
        return InputTracker.isMouseButtonPressed(GLFW.GLFW_MOUSE_BUTTON_LEFT);
    }
}
