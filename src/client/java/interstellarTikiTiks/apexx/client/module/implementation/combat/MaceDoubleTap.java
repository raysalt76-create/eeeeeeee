package interstellarTikiTiks.apexx.client.module.implementation.combat;

import interstellarTikiTiks.apexx.client.event.EventBus;
import interstellarTikiTiks.apexx.client.event.EventTarget;
import interstellarTikiTiks.apexx.client.event.impl.CombatSwapLockEvent;
import interstellarTikiTiks.apexx.client.friend.FriendManager;
import interstellarTikiTiks.apexx.client.mixin.MinecraftClientAccessor;
import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import interstellarTikiTiks.apexx.client.module.SubCategory;
import interstellarTikiTiks.apexx.client.module.hooks.AttackExecutionAware;
import interstellarTikiTiks.apexx.client.module.hooks.AttackHandleAware;
import interstellarTikiTiks.apexx.client.module.hooks.RenderFrameAware;
import interstellarTikiTiks.apexx.client.module.implementation.player.BotFilterProvider;
import interstellarTikiTiks.apexx.client.util.AttackValidator;
import interstellarTikiTiks.apexx.client.util.ClickSimController;
import interstellarTikiTiks.apexx.client.util.ClientInternalLog;
import interstellarTikiTiks.apexx.client.util.ModuleAccess;
import interstellarTikiTiks.apexx.client.util.RotationManager;
import interstellarTikiTiks.apexx.client.util.RotationRequest;
import interstellarTikiTiks.apexx.client.util.combat.ModuleDelays;
import interstellarTikiTiks.apexx.client.util.combat.TargetPriority;
import interstellarTikiTiks.apexx.client.util.combat.SlotSync;
import interstellarTikiTiks.apexx.client.util.combat.TickActionOrder;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.EnchantmentEffectComponentTypes;
import net.minecraft.component.type.EquippableComponent;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.MaceItem;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * MaceDoubleTap - breach hit, elytra drop, then a mace smash as soon as the server has ended the
 * glide, never swapping back between them.
 *
 * <pre>
 *   Tick N    gliding, weapon in hand, target on the crosshair in reach:
 *               swap to the Breach mace, hit.                      (stays on the mace slot)
 *   Tick N+1  swap to the chestplate slot, right-click: the chestplate replaces the elytra.
 *   Tick N+k  (k >= 2) the server has ended the glide and has seen enough fall for a smash:
 *               swap to the mace, hit.
 *   (Optional Swap Back to the slot the chain started on, at the first tick head after the
 *    Swap Back Delay, never in the hit's own movement interval.)
 * </pre>
 *
 * <p>Why the smash waits: vanilla only adds the mace smash bonus when the attacker has fallen more
 * than 1.5 blocks and is not gliding ({@code MaceItem.shouldDealAdditionalDamage}). The server keeps
 * a glider's fall distance at 1.0 or less and clears the glide flag only in its own player tick
 * ({@code LivingEntity.tickGliding}), never when the right-click arrives. A hit sent on a fixed N+2
 * reached the server in the same tick as the right-click whenever the two bunched up (server lag,
 * network jitter, or a slow frame that runs both client ticks back to back, such as the particle
 * burst of the opener popping a totem). The server then still counted the glide, the hit was a weak
 * plain hit, and the target's hurt cooldown absorbed all of it. The client's own {@code isGliding()}
 * only turns false when the server's flag update arrives, so the smash waits for that, then for a
 * fall the server has certainly processed ({@link #serverFallLowerBound}), up to
 * {@link #MAX_SMASH_WAIT_TICKS}. While it waits the chain holds the chestplate slot and keeps aiming.
 *
 * <p>Render frames run the start test (vanilla's per-frame crosshair, the glide and the hotbar plan).
 * Every step dispatches at a tick head, inside {@link TickActionOrder}'s action window before that
 * tick's movement packet: a real {@link SlotSync} selection first, then the action in the same tick,
 * with the attack through {@link AttackValidator} and the right-click through
 * {@link ClickSimController}. The opener and the drop each run in their own tick or end the chain;
 * the smash may wait, but its swap and hit always share the tick they fire in.
 *
 * <p>Aim: like AutoMace, a diving glider acquires a target inside the Pre-Aim Distance and steers
 * toward it through {@link RotationManager} (optionally Silent Aim), aiming at the rear of the hitbox
 * when the player is behind the target's facing (backstab). Once the chain starts the target is
 * locked and the SAME request stays alive through all three ticks, so the aim keeps following the
 * target while the glider passes over it. The opening hit fires only when the live crosshair ray
 * is on the target.
 *
 * <p>The opening hit is made with the weapon in hand (the swap has not been ticked yet), so it
 * needs {@link #MIN_OPEN_CHARGE} of attack charge, not a full one. The smash comes a few ticks after
 * the breach hit, so it can never have a full attack charge; the smash bonus does not scale with
 * charge, and that is the combo as specified. COMPILED only; not RUNTIME VERIFIED.
 */
public class MaceDoubleTap extends Module implements AttackHandleAware, AttackExecutionAware, RenderFrameAware, interstellarTikiTiks.apexx.client.module.hooks.ClickSimulating {

    /** Ticks after the chestplate right-click the smash may wait for the server to end the glide. */
    static final int MAX_SMASH_WAIT_TICKS = 12;
    /** The server holds a glider's fall distance at or below this ({@code LivingEntity.limitFallDistance}). */
    static final double GLIDE_FALL_CAP = 1.0;
    /**
     * Server fall distance the smash waits for. Above 1.5 it is a smash at all; at 2.0 a near-zero
     * charge smash (6 x 0.2 + 4 per block = 9.2) still beats a fully charged critical opener
     * (6 x 1.5 = 9.0). Inside the target's hurt cooldown only the excess over the opener is dealt.
     */
    static final double SMASH_SERVER_FALL = 2.0;
    /** AutoElytra keeps the wing off for this long; the mace hit event releases it earlier. */
    private static final int ELYTRA_HOLD_TICKS = MAX_SMASH_WAIT_TICKS + 1;
    /** Hardcoded: the opening hit may start from this attack charge up (not only a full 1.0). */
    private static final float MIN_OPEN_CHARGE = 0.9f;
    private static final double ATTACK_RANGE = 3.0;
    private static final double HITBOX_ACCURACY = 0.20;
    /** Backstab = the player is at least this far behind the target's facing direction (dot product). */
    private static final double BACKSTAB_DOT = -0.35;
    private static final double AIM_RESPONSE = 0.45;

    private final BooleanSetting weaponOnly = new BooleanSetting("Weapon Only", true)
            .setDescription("Only start a chain from a hit you begin with a sword or axe in hand, so other items never trigger it.");
    private final BooleanSetting ignoreShields = new BooleanSetting("Ignore Shields", true)
            .setDescription("Skip targets holding up a shield toward you (the hits would be blocked). Targets you are behind are still attacked, since their shield cannot cover their back.");
    private final BooleanSetting swapBack = new BooleanSetting("Swap Back", true)
            .setDescription("After the final mace smash, reselect the hotbar slot you started the chain on (after Swap Back Delay, never in the hit's own tick).");
    private final BooleanSetting silentAim = new BooleanSetting("Silent Aim", false)
            .setDescription("Aim using only the rotation the server sees, so your camera never moves during the combo. Off: your view visibly tracks the target.");
    private final SliderSetting aimStrength = new SliderSetting("Aim Strength", 60.0, 1.0, 100.0, 0)
            .setDescription("1 = slow, lazy turns; 100 = fast, firm tracking. Once the combo has started the aim runs at full authority and stays on the target.");
    private final SliderSetting preAimDistance = new SliderSetting("Pre-Aim Distance", 3.0, 0.5, 8.0, 1)
            .setDescription("Blocks beyond the 3.0-block hit range at which a diving glider acquires the target and starts turning toward it. Authority rises as the target closes.");
    private final SliderSetting cooldownMs = new SliderSetting("Cooldown (ms)", 500, 0, 3000, 0)
            .withJitter(100)
            .setDescription("Minimum time after a chain ends or aborts before a new one may start.");
    private final SliderSetting swapBackDelayMs = new SliderSetting("Swap Back Delay (ms)", 60, 0, 1000, 0)
            .withJitter(15)
            .setDescription("Wait after the final smash before Swap Back reselects your starting slot. Applied at the first tick head after it elapses, so 0 means the next tick.")
            .setSection("Delays")
            .setDependsOn("Swap Back");

    private enum Stage { IDLE, BREACH_HIT, ELYTRA_OFF }

    /** The slots one chain will use, read from the hotbar before anything is sent. */
    private record Plan(PlayerEntity target, int breachSlot, int chestSlot, int smashSlot) {}

    private Stage stage = Stage.IDLE;
    private long tickId = 0L;
    private long stepTickId = -1L;
    /** Tick of this module's last slot selection; a second change in the same tick is never sent. */
    private long selectTickId = -1L;

    private Plan renderPlan = null;
    /** The player the rotation supplier currently steers toward; the chain target once started. */
    private PlayerEntity aimTarget = null;
    private boolean aimRequested = false;
    private PlayerEntity target = null;
    private int originalSlot = -1;
    private int chestSlot = -1;
    /** The slot this chain last selected; restores and actions only run while it is still held. */
    private int expectedSlot = -1;
    /** Tick of the chestplate right-click; the smash wait is counted from it. */
    private long dropTickId = -1L;
    /** Player Y when the chain started, and at the first tick head the client saw the glide end (NaN before). */
    private double chainStartY = Double.NaN;
    private double glideEndY = Double.NaN;
    /** Why the smash is still waiting, for the abort log when the wait runs out. */
    private String waitReason = "";

    private boolean attackConfirmed = false;
    private boolean reentrantAttack = false;
    private boolean dropNotified = false;
    private long nextAllowedMs = 0L;
    /** Swap Back queued by a finished chain: slot to restore, slot that must still be held, due time. */
    private int pendingRestoreSlot = -1;
    private int pendingRestoreFrom = -1;
    private long pendingRestoreAtMs = 0L;
    private Object boundPlayer;
    private Object boundWorld;

    public MaceDoubleTap() {
        super("MaceDoubleTap", "Elytra mace combo for diving at a target: while you glide in, it locks aim onto them (preferring their back), lands a Breach mace hit, drops your elytra so the glide ends, and then lands a full fall-damage mace smash as soon as the server confirms you are falling, all without swapping back in between. Starts once your weapon is at least 90% charged.", Category.COMBAT);
        setSubCategory(SubCategory.MACE);
        addSetting(weaponOnly);
        addSetting(ignoreShields);
        addSetting(swapBack);
        addSetting(silentAim);
        addSetting(aimStrength);
        addSetting(preAimDistance);
        addSetting(cooldownMs);
        addSetting(swapBackDelayMs);
    }

    @Override
    public void onEnable() {
        EventBus.register(this);
        clearChain();
        boundPlayer = mc.player;
        boundWorld = mc.world;
    }

    @Override
    public void onDisable() {
        EventBus.unregister(this);
        flushPendingRestore();
        abortChain();
        abortAim();
    }

    @Override
    protected void onRuntimeStateReleased() {
        flushPendingRestore();
        abortChain();
        abortAim();
    }

    public boolean isBusy() {
        return stage != Stage.IDLE;
    }

    @EventTarget
    public void onCombatSwapLock(CombatSwapLockEvent event) {
        if (!event.isRequestedBy(this) && isEnabled() && stage != Stage.IDLE) {
            event.setItemSwapLocked(true);
            event.setAutoAttackLocked(true);
        }
    }

    /** Our own dispatched attacks pass; a manual click mid-chain would hit with the wrong item. */
    @Override
    public boolean handleAttack() {
        if (reentrantAttack || stage == Stage.IDLE) {
            return false;
        }
        drainQueuedAttackClicks();
        return true;
    }

    /** Vanilla's attack callback: the client-side proof that a chain hit was dispatched. */
    @Override
    public void onVanillaEntityAttack(Entity hit) {
        if (reentrantAttack && target != null && hit == target) {
            attackConfirmed = true;
        }
    }

    // ── render frame: the start test ──────────────────────────────────────────

    @Override
    public void onRenderFrame() {
        if (!hasContext()) {
            renderPlan = null;
            releaseAim();
            return;
        }
        updateAim();
        renderPlan = stage == Stage.IDLE && canStart()
                ? planFor(targetUnder(mc.crosshairTarget)) : null;
    }

    // ── aim ───────────────────────────────────────────────────────────────────

    /**
     * Steers toward the chain target (locked from the start until the last hit) or, while idle and a
     * dive is possible, the best player inside the pre-aim window. Released when there is none.
     */
    private void updateAim() {
        PlayerEntity victim;
        if (stage != Stage.IDLE) {
            victim = isValidTarget(target) ? target : null;
        } else {
            victim = divePossible() ? pickAimTarget() : null;
        }
        if (victim == null) {
            aimTarget = null;
            releaseAim();
            return;
        }
        aimTarget = victim;
        boolean silent = silentAim.isEnabled();
        double speed = aimAuthority(victim) * 60.0;
        double response = AIM_RESPONSE;
        if (silent) {
            speed *= 1.5;
            response = Math.min(1.0, response + 0.30);
        }
        RotationRequest req = RotationRequest.create()
                .speed(speed)
                .response(response)
                .prediction(0.3)
                .silent(silent)
                .yawOnly(false);
        aimRequested = true;
        RotationManager.setRotationSupplier(this, RotationManager.Priority.HIGHEST,
                () -> aimTarget != null ? aimPoint(aimTarget) : null, req);
    }

    /** Full authority once the combo runs; before that it ramps up as the target closes in. */
    private double aimAuthority(PlayerEntity victim) {
        double base = RotationManager.engineStrength(aimStrength.getValue(), RotationManager.STRENGTH_SCALE_TRACK);
        if (stage != Stage.IDLE) {
            return MathHelper.clamp(base, 1.0, 24.0);
        }
        double engage = ATTACK_RANGE + preAimDistance.getValue();
        double span = Math.max(0.25, engage - ATTACK_RANGE);
        double progress = MathHelper.clamp((engage - mc.player.distanceTo(victim)) / span, 0.0, 1.0);
        return MathHelper.clamp(base * (0.08 + 0.92 * progress * progress), 1.0, 24.0);
    }

    /** Rear of the hitbox when the player is behind the target's facing, else the nearest hitbox point. */
    private Vec3d aimPoint(PlayerEntity victim) {
        float t = mc.getRenderTickCounter().getTickProgress(false);
        double visX = MathHelper.lerp(t, victim.lastRenderX, victim.getX());
        double visY = MathHelper.lerp(t, victim.lastRenderY, victim.getY());
        double visZ = MathHelper.lerp(t, victim.lastRenderZ, victim.getZ());
        if (isPlayerBehindTarget(victim)) {
            Vec3d look = victim.getRotationVec(1.0f);
            return new Vec3d(visX - look.x * (victim.getWidth() * 0.45),
                    visY + victim.getHeight() * 0.65,
                    visZ - look.z * (victim.getWidth() * 0.45));
        }
        Box box = victim.getBoundingBox().offset(visX - victim.getX(), visY - victim.getY(), visZ - victim.getZ());
        return RotationManager.calculateHitboxPoint(box, mc.player.getEyePos(), HITBOX_ACCURACY, true);
    }

    /** True when the player's horizontal position is behind the target's facing direction. */
    private boolean isPlayerBehindTarget(PlayerEntity victim) {
        Vec3d look = victim.getRotationVec(1.0f);
        Vec3d toPlayer = mc.player.getEyePos().subtract(victim.getEyePos());
        double horizontal = Math.sqrt(toPlayer.x * toPlayer.x + toPlayer.z * toPlayer.z);
        double lookHorizontal = Math.sqrt(look.x * look.x + look.z * look.z);
        if (horizontal <= 1.0e-4 || lookHorizontal <= 1.0e-4) return false;
        return (toPlayer.x * look.x + toPlayer.z * look.z) / (horizontal * lookHorizontal) <= BACKSTAB_DOT;
    }

    /** Sticky: the current aim target is kept while valid and in range, otherwise the best-scoring player. */
    private PlayerEntity pickAimTarget() {
        double range = ATTACK_RANGE + preAimDistance.getValue();
        if (aimTarget != null && isAimable(aimTarget) && mc.player.squaredDistanceTo(aimTarget) <= range * range) {
            return aimTarget;
        }
        PlayerEntity best = null;
        double bestScore = -Double.MAX_VALUE;
        for (PlayerEntity p : mc.world.getPlayers()) {
            if (!isAimable(p) || mc.player.squaredDistanceTo(p) > range * range) continue;
            double score = TargetPriority.score(p, mc.player, range);
            if (score > bestScore) {
                bestScore = score;
                best = p;
            }
        }
        return best;
    }

    /** A valid target that is not far above the player and is in line of sight. */
    private boolean isAimable(PlayerEntity p) {
        return isValidTarget(p) && p.getY() - mc.player.getY() <= 3.5 && mc.player.canSee(p);
    }

    /** A gliding, descending player with the hardware for the combo; the charge and crosshair are checked at the start. */
    private boolean divePossible() {
        if (System.currentTimeMillis() < nextAllowedMs) return false;
        if (!mc.player.isGliding() || !isWearingGlider() || mc.player.getVelocity().y >= 0.0) return false;
        if (mc.player.isUsingItem() || mc.player.isSpectator() || mc.player.isCreative() || mc.player.hasVehicle()) return false;
        if (weaponOnly.isEnabled() && !isWeapon(mc.player.getMainHandStack())) return false;
        return findBreachMace() >= 0 && findChestplateSlot() >= 0 && findSmashMace() >= 0;
    }

    /** Soft release: the rotation decays back to the camera and a post-attack hold is honoured. */
    private void releaseAim() {
        if (aimRequested) {
            aimRequested = false;
            RotationManager.clearTarget(this);
        }
    }

    /** Immediate release for disable, world change and runtime release. */
    private void abortAim() {
        aimRequested = false;
        aimTarget = null;
        RotationManager.abortOwner(this);
    }

    /** Another owner's silent rotation would reroute vanilla's attack to its own target. */
    private boolean foreignSilentRotation() {
        return RotationManager.isSilentRotationActive() && RotationManager.getCurrentOwner() != this;
    }

    // ── tick head: the chain ──────────────────────────────────────────────────

    @Override
    public void onTick() {
        tickId++;
        if (mc.player == null || mc.world == null || mc.interactionManager == null
                || boundPlayer != mc.player || boundWorld != mc.world) {
            // A new player/world: nothing remembered from the old one may act.
            clearChain();
            abortAim();
            clearPendingRestore();
            boundPlayer = mc.player;
            boundWorld = mc.world;
            return;
        }
        if (stage != Stage.IDLE && (mc.currentScreen != null || tickId != stepTickId + 1)) {
            abortChain();
            return;
        }
        servicePendingRestore();
        switch (stage) {
            case IDLE -> tryStart();
            case BREACH_HIT -> stepUnequip();
            case ELYTRA_OFF -> stepSmash();
        }
    }

    /** Tick N: everything the next two ticks need is checked first, then breach swap + hit. */
    private void tryStart() {
        Plan cached = renderPlan;
        renderPlan = null;
        if (!hasContext() || !canStart() || !actionWindowOpen() || mc.gameRenderer == null) return;
        // Tick-start crosshair, exactly what vanilla computes next in this tick.
        mc.gameRenderer.updateCrosshairTarget(1.0f);
        PlayerEntity hit = targetUnder(mc.crosshairTarget);
        if (hit == null) return;
        Plan plan = cached != null && cached.target() == hit && planStillHeld(cached) ? cached : planFor(hit);
        if (plan == null) return;

        CombatSwapLockEvent lock = new CombatSwapLockEvent(this);
        EventBus.post(lock);
        if (lock.isItemSwapLocked() || lock.isAutoAttackLocked()) return;

        // A new chain owns the hotbar now; a queued Swap Back from the last one no longer applies.
        clearPendingRestore();
        target = plan.target();
        originalSlot = mc.player.getInventory().getSelectedSlot();
        chestSlot = plan.chestSlot();
        expectedSlot = originalSlot;
        chainStartY = mc.player.getY();
        stage = Stage.BREACH_HIT;
        stepTickId = tickId;
        if (!select(plan.breachSlot()) || !attackTarget()) {
            abortChain();
        }
    }

    /** Tick N+1: chestplate slot + right-click. Stays on that slot; nothing is swapped back. */
    private void stepUnequip() {
        if (!actionWindowOpen() || !holdsExpectedSlot() || !isWearingGlider() || !isValidTarget(target)
                || !isChestplate(mc.player.getInventory().getStack(chestSlot))
                || !useReadyNow() || mc.interactionManager.isBreakingBlock()) {
            logSmashAbort("N+1 elytra drop preconditions: window=" + actionWindowOpen() + " heldSlot=" + holdsExpectedSlot()
                    + " glider=" + isWearingGlider() + " targetValid=" + isValidTarget(target)
                    + " targetHp=" + (target != null ? target.getHealth() : -1f)
                    + " chestSlotOk=" + isChestplate(mc.player.getInventory().getStack(chestSlot))
                    + " useReady=" + useReadyNow() + " breaking=" + mc.interactionManager.isBreakingBlock());
            abortChain();
            return;
        }
        stepTickId = tickId;
        AutoElytra autoElytra = ModuleAccess.getModule(AutoElytra.class);
        if (autoElytra != null && autoElytra.isEnabled()) {
            autoElytra.notifyExternalDrop(ELYTRA_HOLD_TICKS);
            dropNotified = true;
        }
        if (!select(chestSlot)) {
            abortChain();
            return;
        }
        ClickSimController.useNow(mc);
        // An item use is an action in this interval: no slot change may follow it before the movement packet.
        TickActionOrder.recordUse();
        if (isWearingGlider()) {
            // The client-side equip did not happen (the right-click went elsewhere or was refused).
            abortChain();
            return;
        }
        dropTickId = tickId;
        glideEndY = Double.NaN;
        waitReason = "";
        stage = Stage.ELYTRA_OFF;
    }

    /**
     * Tick N+2 onward: mace swap + hit once the server has ended the glide and the fall it has seen
     * since makes a smash that beats the opener. Until then the chain holds the chestplate slot and
     * keeps aiming; it ends when the wait runs out or the fall is lost.
     */
    private void stepSmash() {
        int smash = findSmashMace();
        boolean timedOut = tickId - dropTickId > MAX_SMASH_WAIT_TICKS;
        if (timedOut || !holdsExpectedSlot() || isWearingGlider() || smash < 0 || !isValidTarget(target)
                || fallInterrupted() || mc.gameRenderer == null) {
            logSmashAbort((timedOut ? "smash wait ran out (" + waitReason + ")" : "smash preconditions")
                    + ": heldSlot=" + holdsExpectedSlot() + " glider=" + isWearingGlider() + " smashSlot=" + smash
                    + " targetValid=" + isValidTarget(target) + " fallLost=" + fallInterrupted());
            abortChain();
            return;
        }
        // The chain is alive this tick whether it fires or keeps waiting.
        stepTickId = tickId;
        if (mc.player.isGliding()) {
            // The server has not ended the glide yet: a hit now could still reach it as a glider's plain hit.
            waitReason = "server still gliding";
            return;
        }
        if (Double.isNaN(glideEndY)) {
            glideEndY = mc.player.getY();
        }
        double serverFall = serverFallLowerBound(chainStartY, glideEndY, mc.player.getY());
        if (serverFall < SMASH_SERVER_FALL) {
            waitReason = String.format("server fall >= %.2f", serverFall);
            return;
        }
        if (!actionWindowOpen() || foreignSilentRotation()) {
            waitReason = "action window closed or foreign silent rotation";
            return;
        }
        mc.gameRenderer.updateCrosshairTarget(1.0f);
        if (targetUnder(mc.crosshairTarget) != target) {
            // The aim keeps steering at the locked target; the live ray decides on a later tick.
            waitReason = "crosshair off target";
            return;
        }
        if (!select(smash) || !attackTarget()) {
            logSmashAbort("dispatch: attackConfirmed=" + attackConfirmed + " mace="
                    + (mc.player.getMainHandStack().getItem() instanceof MaceItem) + " attackCooldown="
                    + (mc instanceof MinecraftClientAccessor a ? a.getAttackCooldown() : -1));
            abortChain();
            return;
        }
        ClientInternalLog.info("MaceDoubleTap", String.format("Smash sent %d ticks after the drop, server fall >= %.2f",
                tickId - dropTickId, serverFall));
        finishChain();
    }

    /**
     * A lower bound on the server's fall distance. While gliding the server held it at no more than
     * {@link #GLIDE_FALL_CAP}, and it had at least the descent since the chain started, up to that cap.
     * After the glide ended it adds every descent it processes, which certainly includes the movement
     * sent after the client saw the glide end. Ascents never lower it.
     */
    static double serverFallLowerBound(double chainStartY, double glideEndY, double currentY) {
        double glideFall = Math.min(GLIDE_FALL_CAP, Math.max(0.0, chainStartY - glideEndY));
        return glideFall + Math.max(0.0, glideEndY - currentY);
    }

    /** Landing, water, lava or a ladder resets the fall distance: there is no smash left to wait for. */
    private boolean fallInterrupted() {
        return mc.player.isOnGround() || mc.player.isTouchingWater() || mc.player.isInLava() || mc.player.isClimbing();
    }

    private void logSmashAbort(String why) {
        ClientInternalLog.warn("MaceDoubleTap", "Chain aborted - " + why);
    }

    /** The final hit landed: optional Swap Back after its delay (at a later tick head), then the chain is over. */
    private void finishChain() {
        if (swapBack.isEnabled() && originalSlot >= 0 && originalSlot <= 8 && expectedSlot >= 0
                && expectedSlot != originalSlot && mc.player.getInventory().getSelectedSlot() == expectedSlot) {
            pendingRestoreSlot = originalSlot;
            pendingRestoreFrom = expectedSlot;
            pendingRestoreAtMs = System.currentTimeMillis() + ModuleDelays.clampMs(swapBackDelayMs.sample());
        }
        nextAllowedMs = System.currentTimeMillis() + Math.round(cooldownMs.sample());
        clearChain();
    }

    // ── actions ───────────────────────────────────────────────────────────────

    /** Real slot selection in the action window, at most one per tick, never after an action. */
    private boolean select(int slot) {
        if (mc.player.getInventory().getSelectedSlot() == slot) {
            expectedSlot = slot;
            return true;
        }
        if (!actionWindowOpen() || selectTickId == tickId || !SlotSync.select(mc, slot)
                || mc.player.getInventory().getSelectedSlot() != slot) {
            return false;
        }
        selectTickId = tickId;
        expectedSlot = slot;
        return true;
    }

    /** Vanilla attack on the live crosshair; true only when vanilla dispatched it at the chain target. */
    private boolean attackTarget() {
        if (!holdsExpectedSlot() || !(mc.player.getMainHandStack().getItem() instanceof MaceItem)
                || targetUnder(mc.crosshairTarget) != target) {
            return false;
        }
        attackConfirmed = false;
        reentrantAttack = true;
        try {
            AttackValidator.dispatchImmediateClickAttack(mc);
        } finally {
            reentrantAttack = false;
        }
        if (attackConfirmed) {
            // Rule 4: every chain hit rides on its angles for the rest of this tick, silent aim or not.
            RotationManager.setPostAttackHold(this, 1, RotationManager.getFinalYaw(), RotationManager.getFinalPitch());
        }
        return attackConfirmed;
    }

    /**
     * Back to the slot the chain started on, only while this chain's selection is still held. A
     * restore that may not be sent now (after an action, outside the window, or a second change this
     * tick) is queued for the next tick head instead of dropped.
     */
    private void restoreSlot() {
        if (mc.player == null || !swapBack.isEnabled() || originalSlot < 0 || originalSlot > 8 || expectedSlot < 0
                || expectedSlot == originalSlot || mc.player.getInventory().getSelectedSlot() != expectedSlot) {
            return;
        }
        if (actionWindowOpen() && selectTickId != tickId && mc.isOnThread()) {
            SlotSync.select(mc, originalSlot);
            selectTickId = tickId;
        } else {
            TickActionOrder.deferSlot(originalSlot, expectedSlot);
        }
    }

    /**
     * Applies a queued Swap Back at a tick head once its delay has passed, inside the action window
     * and only while the slot the chain left selected is still held (a newer user choice wins).
     */
    private void servicePendingRestore() {
        if (pendingRestoreSlot < 0) return;
        if (mc.player.getInventory().getSelectedSlot() != pendingRestoreFrom) {
            clearPendingRestore();
            return;
        }
        if (System.currentTimeMillis() < pendingRestoreAtMs || !actionWindowOpen() || selectTickId == tickId) return;
        SlotSync.select(mc, pendingRestoreSlot);
        selectTickId = tickId;
        clearPendingRestore();
    }

    /** Disable/release: a queued Swap Back is handed to the next tick head instead of being lost. */
    private void flushPendingRestore() {
        if (pendingRestoreSlot >= 0) {
            TickActionOrder.deferSlot(pendingRestoreSlot, pendingRestoreFrom);
        }
        clearPendingRestore();
    }

    private void clearPendingRestore() {
        pendingRestoreSlot = -1;
        pendingRestoreFrom = -1;
        pendingRestoreAtMs = 0L;
    }

    /** Idempotent, owner-scoped unwind for failure, disable, world change and runtime release. */
    private void abortChain() {
        if (stage != Stage.IDLE) {
            restoreSlot();
            if (dropNotified) {
                AutoElytra autoElytra = ModuleAccess.getModule(AutoElytra.class);
                if (autoElytra != null && autoElytra.isEnabled()) {
                    autoElytra.notifyComboAborted();
                }
            }
            nextAllowedMs = System.currentTimeMillis() + Math.round(cooldownMs.sample());
        }
        clearChain();
    }

    private void clearChain() {
        stage = Stage.IDLE;
        stepTickId = -1L;
        renderPlan = null;
        target = null;
        originalSlot = -1;
        chestSlot = -1;
        expectedSlot = -1;
        dropTickId = -1L;
        chainStartY = Double.NaN;
        glideEndY = Double.NaN;
        waitReason = "";
        attackConfirmed = false;
        reentrantAttack = false;
        dropNotified = false;
        SlotSync.releaseOwner(this);
    }

    /**
     * Swallows queued manual clicks a mid-chain attack would send with the wrong item. This module
     * never presses the attack key itself, so the key's held state (the user's own press) is left
     * alone; only the already-queued click count is consumed.
     */
    private void drainQueuedAttackClicks() {
        if (mc.options != null && mc.options.attackKey != null) {
            while (mc.options.attackKey.wasPressed()) {}
        }
    }

    // ── queries ───────────────────────────────────────────────────────────────

    private boolean hasContext() {
        return mc.player != null && mc.world != null && mc.interactionManager != null && mc.currentScreen == null;
    }

    /** Tick head, before the movement packet, with no attack or use sent yet in this interval. */
    private boolean actionWindowOpen() {
        return TickActionOrder.isWindowOpen() && TickActionOrder.canSwitchSlot();
    }

    private boolean holdsExpectedSlot() {
        return expectedSlot >= 0 && mc.player.getInventory().getSelectedSlot() == expectedSlot;
    }

    /** Player-side start conditions, including the ones the N+1 and N+2 steps will depend on. */
    private boolean canStart() {
        if (System.currentTimeMillis() < nextAllowedMs) return false;
        if (!mc.player.isGliding() || !isWearingGlider() || mc.player.getVelocity().y >= 0.0) return false;
        if (mc.player.isUsingItem() || mc.player.isSpectator() || mc.player.isCreative() || mc.player.hasVehicle()) return false;
        if (weaponOnly.isEnabled() && !isWeapon(mc.player.getMainHandStack())) return false;
        // The wing must be removable by the chestplate (no Curse of Binding).
        if (EnchantmentHelper.hasAnyEnchantmentsWith(mc.player.getEquippedStack(EquipmentSlot.CHEST),
                EnchantmentEffectComponentTypes.PREVENT_ARMOR_CHANGE)) return false;
        // The right-click on N+1 needs vanilla's use cooldown to have run out by then.
        if (!(mc instanceof MinecraftClientAccessor accessor) || accessor.getItemUseCooldown() > 1) return false;
        // The opening hit is made with the weapon in hand, so it only needs 90% of its charge.
        if (mc.player.getAttackCooldownProgress(0.5f) < MIN_OPEN_CHARGE) return false;
        return !foreignSilentRotation();
    }

    private boolean useReadyNow() {
        return mc instanceof MinecraftClientAccessor accessor && accessor.getItemUseCooldown() <= 0;
    }

    private Plan planFor(PlayerEntity hit) {
        if (hit == null) return null;
        int held = mc.player.getInventory().getSelectedSlot();
        int breach = findBreachMace();
        int chest = findChestplateSlot();
        int smash = findSmashMace();
        if (breach < 0 || chest < 0 || smash < 0 || breach == held) return null;
        return new Plan(hit, breach, chest, smash);
    }

    /** A plan from a render frame still describes the hotbar at the tick head. */
    private boolean planStillHeld(Plan plan) {
        return plan.breachSlot() != mc.player.getInventory().getSelectedSlot()
                && plan.breachSlot() == findBreachMace() && plan.smashSlot() == findSmashMace()
                && isChestplate(mc.player.getInventory().getStack(plan.chestSlot()));
    }

    /**
     * The player under a vanilla crosshair pick, valid and with the eye ray reaching its hitbox
     * inside vanilla reach.
     */
    private PlayerEntity targetUnder(HitResult hit) {
        if (!(hit instanceof EntityHitResult ehr) || !(ehr.getEntity() instanceof PlayerEntity p) || !isValidTarget(p)) {
            return null;
        }
        // A shield cannot soak a hit from behind.
        if (ignoreShields.isEnabled() && p.isBlocking() && !isPlayerBehindTarget(p)) {
            return null;
        }
        Vec3d eye = mc.player.getCameraPosVec(1.0f);
        Vec3d end = eye.add(mc.player.getRotationVec(1.0f).multiply(mc.player.getEntityInteractionRange()));
        Box box = p.getBoundingBox();
        return box.contains(eye) || box.raycast(eye, end).isPresent() ? p : null;
    }

    private boolean isValidTarget(PlayerEntity p) {
        if (p == null || p == mc.player || !p.isAlive() || p.isRemoved()) return false;
        if (p.isSpectator() || p.isCreative()) return false;
        if (FriendManager.getInstance().isFriend(p.getUuid())) return false;
        if (!interstellarTikiTiks.apexx.client.util.combat.StickyTargets.allows(this, p)) return false;
        for (BotFilterProvider botFilter : ModuleAccess.getEnabledHooks(BotFilterProvider.class)) {
            if (botFilter.isBot(p)) return false;
        }
        return true;
    }

    private boolean isWearingGlider() {
        return LivingEntity.canGlideWith(mc.player.getEquippedStack(EquipmentSlot.CHEST), EquipmentSlot.CHEST);
    }

    private static boolean isWeapon(ItemStack stack) {
        return stack != null && !stack.isEmpty() && (stack.isIn(ItemTags.SWORDS) || stack.isIn(ItemTags.AXES));
    }

    /** A chest item that vanilla's right-click swaps into the chest slot and that does not glide. */
    private boolean isChestplate(ItemStack stack) {
        if (stack == null || stack.isEmpty() || LivingEntity.canGlideWith(stack, EquipmentSlot.CHEST)) return false;
        EquippableComponent equippable = stack.get(DataComponentTypes.EQUIPPABLE);
        return equippable != null && equippable.slot() == EquipmentSlot.CHEST && equippable.swappable()
                && equippable.allows(mc.player.getType());
    }

    private int findChestplateSlot() {
        for (int i = 0; i < 9; i++) {
            if (isChestplate(mc.player.getInventory().getStack(i))) return i;
        }
        return -1;
    }

    /** Highest Breach level among hotbar maces; a mace without Breach is not a breach swap. */
    private int findBreachMace() {
        int best = -1;
        int bestLevel = 0;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (!(stack.getItem() instanceof MaceItem)) continue;
            int level = enchantLevel(stack, "breach");
            if (level > bestLevel) {
                bestLevel = level;
                best = i;
            }
        }
        return best;
    }

    /** Wind Burst, then Density, then Breach - the same ranking AutoMace uses for its smash mace. */
    private int findSmashMace() {
        int best = -1;
        ItemStack bestStack = null;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (!(stack.getItem() instanceof MaceItem)) continue;
            if (bestStack == null || isBetterSmashMace(stack, bestStack)) {
                best = i;
                bestStack = stack;
            }
        }
        return best;
    }

    private static boolean isBetterSmashMace(ItemStack candidate, ItemStack best) {
        int wbCand = enchantLevel(candidate, "wind_burst");
        int wbBest = enchantLevel(best, "wind_burst");
        if (wbCand != wbBest) return wbCand > wbBest;
        int densCand = enchantLevel(candidate, "density");
        int densBest = enchantLevel(best, "density");
        if (densCand != densBest) return densCand > densBest;
        return enchantLevel(candidate, "breach") > enchantLevel(best, "breach");
    }

    private static int enchantLevel(ItemStack stack, String path) {
        ItemEnchantmentsComponent enchants = stack.getOrDefault(DataComponentTypes.ENCHANTMENTS, ItemEnchantmentsComponent.DEFAULT);
        for (RegistryEntry<Enchantment> entry : enchants.getEnchantments()) {
            String id = entry.getKey().map(k -> k.getValue().getPath()).orElse("");
            if (path.equals(id)) return enchants.getLevel(entry);
        }
        return 0;
    }
}
