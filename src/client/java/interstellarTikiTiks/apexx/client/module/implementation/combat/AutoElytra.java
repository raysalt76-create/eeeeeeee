package interstellarTikiTiks.apexx.client.module.implementation.combat;

import interstellarTikiTiks.apexx.client.event.EventBus;
import interstellarTikiTiks.apexx.client.event.EventTarget;
import interstellarTikiTiks.apexx.client.event.impl.CombatSwapLockEvent;
import interstellarTikiTiks.apexx.client.event.impl.MaceHitEvent;
import interstellarTikiTiks.apexx.client.friend.FriendManager;
import interstellarTikiTiks.apexx.client.mixin.MinecraftClientAccessor;
import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import interstellarTikiTiks.apexx.client.module.SubCategory;
import interstellarTikiTiks.apexx.client.util.ClickSimController;
import interstellarTikiTiks.apexx.client.util.ClientInternalLog;
import interstellarTikiTiks.apexx.client.util.ModuleAccess;
import interstellarTikiTiks.apexx.client.util.Notifier;
import interstellarTikiTiks.apexx.client.util.combat.ElytraDropPlanner;
import interstellarTikiTiks.apexx.client.util.combat.InteractSim;
import interstellarTikiTiks.apexx.client.util.combat.ModuleDelays;
import interstellarTikiTiks.apexx.client.util.combat.PlayerMotionSimulation;
import interstellarTikiTiks.apexx.client.util.combat.PlayerTrajectoryEngine;
import interstellarTikiTiks.apexx.client.util.combat.SlotSync;
import interstellarTikiTiks.apexx.client.util.combat.SubTickSequencer;
import interstellarTikiTiks.apexx.client.util.combat.TickActionOrder;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.EquippableComponent;
import net.minecraft.entity.EntityPose;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.List;
import java.util.UUID;

/**
 * AutoElytra takes the elytra off right before an AutoMace hit lands. Nothing else: it never puts the
 * wing on, never opens it and never steers.
 *
 * <p>Each tick the local player is flown forward on the exact elytra/free-fall physics
 * ({@link ElytraDropPlanner}) against the forecast of AutoMace's target. The smash needs the gliding
 * flag gone (a server round trip) and a fall of 1.5 blocks, so the chestplate goes on on the last tick
 * that still lets the hit happen the moment the target is in reach: one tick later would delay it, one
 * tick earlier only loses glide.</p>
 *
 * <p>Order, in the tick's action window: select the chestplate slot through {@link SlotSync}, then use
 * it in the same tick through the shared use seam. The use is verified (the worn elytra is gone) and a
 * dropped use is retried on a later tick, never doubled within one.</p>
 */
public class AutoElytra extends Module implements interstellarTikiTiks.apexx.client.module.hooks.ClickSimulating {

    private final BooleanSetting swapBack = new BooleanSetting("Swap Back", false)
            .setDescription("After the elytra is off and no AutoMace hit is pending, reselects the hotbar slot you were holding. Off: the chestplate slot stays selected and AutoMace swaps away from it itself.");
    private final SliderSetting extraLead = new SliderSetting("Extra Lead (ms)", 20, 0, 150, 0)
            .withJitter(10)
            .setSection("Delays")
            .setDescription("Extra time, on top of your ping, that the elytra is taken off ahead of the hit. Raise it if smashes still land while gliding on a jittery connection; too high and you start falling early. Re-drawn about once a second.");
    private final SliderSetting swapBackDelay = new SliderSetting("Swap Back Delay (ms)", 150, 0, 600, 0)
            .withJitter(40)
            .setDependsOn("Swap Back")
            .setSection("Delays")
            .setDescription("Shortest wait after the elytra comes off before your original slot is reselected (only once no AutoMace hit is pending).");
    private final SliderSetting postHitSwapBack = new SliderSetting("Post-Hit Swap Back Delay (ms)", 100, 0, 1000, 0)
            .withJitter(25)
            .setDependsOn("Swap Back")
            .setSection("Delays")
            .setDescription("After an AutoMace hit lands, how long the chestplate slot is still held before your original slot is reselected.");
    private final SliderSetting holdCap = new SliderSetting("Hold Cap (ms)", 600, 0, 1000, 0)
            .withJitter(60)
            .setDependsOn("Swap Back")
            .setSection("Delays")
            .setDescription("Longest the chestplate slot is held after the elytra comes off before your original slot is reselected, even if a hit is still pending.");
    private final SliderSetting retryDelay = new SliderSetting("Retry Delay (ms)", 500, 0, 1000, 0)
            .withJitter(60)
            .setSection("Delays")
            .setDescription("Wait before trying again after a chestplate use did not take the elytra off.");
    private final SliderSetting missingRetryDelay = new SliderSetting("No Chestplate Retry (ms)", 1000, 0, 1000, 0)
            .withJitter(100)
            .setSection("Delays")
            .setDescription("Wait before checking again when no chestplate was found in the hotbar.");
    private final SliderSetting earlyShedChance = new SliderSetting("Early Shed Chance %", 15, 0, 50, 0)
            .setSection("Humanization")
            .setDescription("Chance per dive to take the wing off one tick earlier than the last possible moment, so the timing is not perfect every single dive. Costs a little glide, never the hit.");
    private final BooleanSetting forceCooldownReset = new BooleanSetting("Force Use Cooldown Reset", false)
            .setBlatant(true)
            .setDescription("BLATANT: clears the vanilla item-use cooldown before every chestplate use, including retries. Off: the use waits for the vanilla use cooldown like a real click.");

    private double cachedLeadMs = 20.0;
    private long leadDrawnAtMs = 0L;
    private int diveEarlyTicks = 0;
    private long diveDrawnAtMs = 0L;
    private int holdMinTicks = 3;
    private int holdCapTicks = 12;
    /** Server tick the swap can wait for before the gliding flag changes, ms. */
    private static final double SERVER_TICK_MS = 50.0;
    /** Ticks of flight the planner looks ahead past the clear latency. */
    private static final int LOOKAHEAD_TICKS = 14;
    /** Target farther than this is never considered. */
    private static final double MAX_DISTANCE = 45.0;
    /** A forecast set must hold this much weight voting "drop now". */
    private static final double VOTE_WEIGHT = 0.4;
    /** An AutoMace drop request is honoured when the model puts the hit within this many ticks past ready. */
    private static final int REQUEST_TTL_TICKS = 2;
    /** Ticks of fall and tick slack added to the clear latency for the plain closing-speed trigger. */
    private static final double CLOSING_PAD_TICKS = 2.0;
    /** With AutoMace on and no target yet, the nearest player within this many blocks is the victim. */
    private static final double NEAREST_FALLBACK = 20.0;
    private static final int MAX_USE_ATTEMPTS = 3;
    private static final int SWAP_WATCHDOG_TICKS = 6;
    /** Other module shedding the wing: how long past its own hold this module stays out. */
    private static final int EXTERNAL_PAD_TICKS = 14;
    private static final int GROUND_RELEASE_TICKS = 10;

    private Object boundPlayer;
    private Object boundWorld;
    private int currentTick = 0;

    // swap in flight
    private int swapSlot = -1;
    private int swapAttempts = 0;
    private int swapStartTick = 0;
    private int nextAttemptTick = 0;
    private int externalUntilTick = 0;

    // slot hold after the wing came off
    private boolean holdActive = false;
    private int holdSlot = -1;
    private int holdOrigin = -1;
    private int holdStartTick = 0;
    private int holdReleaseTick = -1;
    private int groundTicks = 0;

    // AutoMace's "the dive is on" latch
    private int requestTick = -1000;
    private UUID requestVictim = null;

    public AutoElytra() {
        super("AutoElytra", "During an elytra dive onto an AutoMace target, swaps your chestplate on at the last moment so the wings come off just before the smash lands, keeping the glide as long as possible. It never equips or opens the wings itself.", Category.COMBAT);
        setSubCategory(SubCategory.MACE);
        addSetting(swapBack);
        addSetting(extraLead);
        addSetting(swapBackDelay);
        addSetting(postHitSwapBack);
        addSetting(holdCap);
        addSetting(retryDelay);
        addSetting(missingRetryDelay);
        addSetting(earlyShedChance);
        addSetting(forceCooldownReset);
    }

    // ====================================================================================
    // Lifecycle
    // ====================================================================================

    @Override
    public void onEnable() {
        EventBus.register(this);
        recover(false);
    }

    @Override
    public void onDisable() {
        EventBus.unregister(this);
        recover(false);
    }

    @Override
    protected void onRuntimeStateReleased() {
        recover(false);
        SlotSync.releaseOwner(this);
    }

    /** Owner-scoped, idempotent cleanup. Safe with {@code mc.player == null}. */
    private void recover(boolean restoreSlot) {
        SubTickSequencer.cancelOwner(this);
        if (restoreSlot && swapBack.isEnabled() && holdActive && mc.player != null && holdOrigin >= 0
                && mc.player.getInventory().getSelectedSlot() == holdSlot) {
            SlotSync.restore(mc, holdOrigin);
        }
        clearHold();
        swapSlot = -1;
        swapAttempts = 0;
        nextAttemptTick = 0;
        externalUntilTick = 0;
        requestTick = -1000;
        requestVictim = null;
        groundTicks = 0;
        boundPlayer = mc.player;
        boundWorld = mc.world;
    }

    // ====================================================================================
    // Public API (AutoMace, MaceDoubleTap)
    // ====================================================================================

    public void requestDivebombDrop() {
        requestDivebombDrop(null);
    }

    /**
     * AutoMace says the dive is on and names its victim. Only latches a request: the next tick runs it
     * through the same model as everything else, so nothing is dispatched from AutoMace's call site.
     */
    public void requestDivebombDrop(PlayerEntity victim) {
        if (!isEnabled() || mc.player == null) {
            return;
        }
        requestTick = currentTick;
        requestVictim = victim != null ? victim.getUuid() : null;
    }

    /** AutoMace landed a hit: a held slot is released a couple of ticks later when Swap Back is on. */
    public void notifyMaceHit() {
        if (!isEnabled() || mc.player == null) {
            return;
        }
        if (holdActive) {
            holdReleaseTick = currentTick + ModuleDelays.msToTicks(postHitSwapBack.sample());
        }
    }

    /** Another module takes the wing off itself: this one stays out of it for that long. */
    public void notifyExternalDrop(int holdTicks) {
        if (!isEnabled() || mc.player == null) {
            return;
        }
        externalUntilTick = Math.max(externalUntilTick, currentTick + Math.max(1, holdTicks) + EXTERNAL_PAD_TICKS);
        SubTickSequencer.cancelOwner(this);
        swapSlot = -1;
    }

    /** The combo was abandoned: a held slot goes back now (Swap Back on) and no drop is pending. */
    public void notifyComboAborted() {
        if (!isEnabled() || mc.player == null) {
            return;
        }
        requestTick = -1000;
        if (holdActive) {
            holdReleaseTick = currentTick;
        }
    }

    public boolean isSwapping() {
        return swapSlot >= 0;
    }

    /**
     * While this module keeps the chestplate slot selected, the hotbar slot the player really had
     * before it, else -1. AutoMace uses this as "the slot to return to" so a smash never "returns"
     * to the chestplate slot.
     */
    public int getTransientOriginSlot() {
        if (holdActive && holdOrigin >= 0 && mc.player != null
                && mc.player.getInventory().getSelectedSlot() == holdSlot) {
            return holdOrigin;
        }
        return -1;
    }

    @EventTarget
    public void onMaceHit(MaceHitEvent event) {
        notifyMaceHit();
    }

    // ====================================================================================
    // Tick
    // ====================================================================================

    @Override
    public void onTick() {
        if (!isEnabled() || mc.player == null || mc.world == null) {
            return;
        }
        if (mc.player != boundPlayer || mc.world != boundWorld) {
            recover(false);
        }
        currentTick++;
        groundTicks = mc.player.isOnGround() ? groundTicks + 1 : 0;

        if (swapSlot >= 0) {
            driveSwap();
        }
        updateHold();
        if (swapSlot < 0) {
            maybeDrop();
        }
    }

    // ====================================================================================
    // Decision
    // ====================================================================================

    private void maybeDrop() {
        if (mc.currentScreen != null || currentTick < nextAttemptTick || currentTick < externalUntilTick) {
            return;
        }
        if (!wearingElytra() || !mc.player.isGliding() || mc.player.isOnGround() || mc.player.isSpectator()
                || mc.player.getAbilities().flying || inFluidOrClimbing() || hotbarLocked()) {
            return;
        }
        boolean requested = currentTick - requestTick <= REQUEST_TTL_TICKS;
        PlayerEntity victim = resolveVictim(requested);
        if (victim == null || mc.player.squaredDistanceTo(victim) > MAX_DISTANCE * MAX_DISTANCE) {
            return;
        }
        Decision decision = decide(victim);
        // Three independent triggers, so a model that sees no contact can never leave the wing on:
        // the physics planner, AutoMace's own swept-reach request, and plain closing-speed geometry.
        boolean drop = decision.drop || requested || closingWithin(victim, decision.clearTicks + CLOSING_PAD_TICKS);
        if (!drop) {
            return;
        }
        if (findChestplateSlot() < 0) {
            nextAttemptTick = currentTick + ModuleDelays.msToTicks(missingRetryDelay.sample());
            Notifier.warn(getName(), "No chestplate in the hotbar to swap the elytra off with");
            return;
        }
        if (!startSwap()) {
            return; // the packet interval is closed; the decision is re-made next tick
        }
        ClientInternalLog.info(getName(), String.format(
                "Wing off: hit in %.2f ticks (%.2f if one tick later), clear %d ticks, ready %d",
                decision.hitNow, decision.hitIfLater, decision.clearTicks, (int) decision.readyTicks));
    }

    private record Decision(boolean drop, double hitNow, double hitIfLater, int clearTicks, double readyTicks) {}

    /** Votes the forecasts of the victim's movement; any hypothesis weight at or above the threshold decides. */
    private Decision decide(PlayerEntity victim) {
        double rtt = roundTripMs();
        boolean gliding = mc.player.isGliding();
        long nowMs = System.currentTimeMillis();
        if (nowMs - leadDrawnAtMs >= 1000L) {
            cachedLeadMs = extraLead.sample();
            leadDrawnAtMs = nowMs;
        }
        if (nowMs - diveDrawnAtMs >= 1500L) {
            diveEarlyTicks = java.util.concurrent.ThreadLocalRandom.current().nextDouble() * 100.0 < earlyShedChance.getValue() ? 1 : 0;
            diveDrawnAtMs = nowMs;
        }
        int clear = gliding ? (int) Math.ceil((rtt + SERVER_TICK_MS + cachedLeadMs) / 50.0) + diveEarlyTicks : 0;
        int horizon = clear + LOOKAHEAD_TICKS;
        PlayerMotionSimulation.Prediction prediction = PlayerMotionSimulation.predict(mc.world, victim, horizon + 12);
        int since = (int) Math.max(0L, PlayerTrajectoryEngine.getInstance().currentTick() - prediction.observedTick());
        ElytraDropPlanner.Self me = new ElytraDropPlanner.Self(
                mc.player.getEntityPos(), mc.player.getVelocity(), gliding, mc.player.isOnGround(),
                mc.player.getYaw(), mc.player.getPitch(), mc.player.fallDistance,
                mc.player.getEyeHeight(EntityPose.GLIDING), mc.player.getEyeHeight(EntityPose.STANDING));
        PlayerMotionSimulation sim = PlayerMotionSimulation.of(mc.world, mc.player);

        double vote = 0.0;
        double bestNow = ElytraDropPlanner.NEVER;
        double bestLater = ElytraDropPlanner.NEVER;
        List<PlayerMotionSimulation.Forecast> forecasts = prediction.forecasts();
        for (PlayerMotionSimulation.Forecast forecast : forecasts) {
            Box[] boxes = ElytraDropPlanner.fromNow(forecast.boxes(), since, horizon + 2);
            ElytraDropPlanner.Verdict verdict = ElytraDropPlanner.judge(sim, me, boxes, clear, horizon);
            if (verdict.dropNow()) {
                vote += forecast.weight();
            }
            if (verdict.hitNow() < bestNow) {
                bestNow = verdict.hitNow();
                bestLater = verdict.hitIfLater();
            }
        }
        boolean drop = !forecasts.isEmpty() && vote >= VOTE_WEIGHT - 1.0E-9;
        return new Decision(drop, bestNow, bestLater, clear, clear + 1.0);
    }

    private PlayerEntity resolveVictim(boolean requested) {
        AutoMace mace = ModuleAccess.getModule(AutoMace.class);
        boolean maceOn = mace != null && mace.isEnabled();
        PlayerEntity victim = maceOn ? mace.getCurrentTarget() : null;
        if (!targetable(victim) && requested && requestVictim != null) {
            victim = lookupById(requestVictim);
        }
        if (!targetable(victim) && maceOn) {
            // AutoMace has no target yet: the nearest valid player is the one it will dive at.
            victim = null;
            double best = NEAREST_FALLBACK * NEAREST_FALLBACK;
            for (PlayerEntity p : mc.world.getPlayers()) {
                double d = mc.player.squaredDistanceTo(p);
                if (targetable(p) && d < best) {
                    best = d;
                    victim = p;
                }
            }
        }
        return targetable(victim) ? victim : null;
    }

    /**
     * Plain geometry: the eye reaches the victim's box within {@code ticks} ticks at the current closing
     * speed (own velocity minus the victim's per-tick motion), or is already about to.
     */
    private boolean closingWithin(PlayerEntity victim, double ticks) {
        Box box = victim.getBoundingBox();
        Vec3d eye = mc.player.getEyePos();
        double dx = eye.x - MathHelper.clamp(eye.x, box.minX, box.maxX);
        double dy = eye.y - MathHelper.clamp(eye.y, box.minY, box.maxY);
        double dz = eye.z - MathHelper.clamp(eye.z, box.minZ, box.maxZ);
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double gap = distance - (ElytraDropPlanner.REACH + ElytraDropPlanner.REACH_PAD);
        if (gap <= 0.0) {
            return true;
        }
        Vec3d relative = mc.player.getVelocity().subtract(
                victim.getX() - victim.lastX, victim.getY() - victim.lastY, victim.getZ() - victim.lastZ);
        // Closing speed along the line to the box (blocks per tick).
        double closing = relative.dotProduct(new Vec3d(-dx, -dy, -dz).multiply(1.0 / Math.max(distance, 1.0E-6)));
        return closing > 0.05 && gap / closing <= ticks;
    }

    private boolean targetable(PlayerEntity p) {
        if (p == null || p == mc.player || !p.isAlive() || p.isCreative() || p.isSpectator()) {
            return false;
        }
        return !FriendManager.getInstance().isFriend(p.getUuid());
    }

    private PlayerEntity lookupById(UUID id) {
        if (id == null || mc.world == null) {
            return null;
        }
        for (PlayerEntity p : mc.world.getPlayers()) {
            if (p != mc.player && id.equals(p.getUuid())) {
                return p;
            }
        }
        return null;
    }

    private double roundTripMs() {
        if (mc.getNetworkHandler() == null || mc.player == null) {
            return 0.0;
        }
        PlayerListEntry entry = mc.getNetworkHandler().getPlayerListEntry(mc.player.getUuid());
        return entry == null ? 0.0 : Math.max(0, entry.getLatency());
    }

    // ====================================================================================
    // Swap: select the chestplate slot, then use it, in this tick
    // ====================================================================================

    /** @return false when nothing was started (no slot change is allowed in this packet interval) */
    private boolean startSwap() {
        int slot = findChestplateSlot();
        if (slot < 0 || swapSlot >= 0 || !TickActionOrder.canSwitchSlot()) {
            return false;
        }
        int origin = holdActive ? holdOrigin : mc.player.getInventory().getSelectedSlot();
        if (!SlotSync.select(mc, slot)) {
            return false;
        }
        swapSlot = slot;
        swapAttempts = 0;
        swapStartTick = currentTick;
        holdActive = true;
        holdSlot = slot;
        holdOrigin = origin;
        holdStartTick = currentTick;
        holdReleaseTick = -1;
        holdMinTicks = ModuleDelays.msToTicks(swapBackDelay.sample());
        holdCapTicks = ModuleDelays.msToTicks(holdCap.sample());
        fireUse();
        return true;
    }

    /** Queues the use inside the action window (collapses to "now", after the slot select, in the same tick). */
    private void fireUse() {
        final int slot = swapSlot;
        SubTickSequencer.queueAction(this, () -> {
            if (swapSlot != slot || mc.player == null || mc.player.getInventory().getSelectedSlot() != slot
                    || !wearingElytra()) {
                return;
            }
            swapAttempts++;
            useChestplate();
            if (!wearingElytra()) {
                finishSwap(true);
            }
        }, SubTickSequencer.sampleMicroDelayMs());
    }

    /**
     * One right-click on the selected chestplate, through the shared use seam only. While the vanilla
     * item-use cooldown is running the attempt does not count and the use is retried on a later tick;
     * there is no direct fallback, so at most one use leaves per tick. The client applies the equipment
     * swap synchronously, so the worn elytra being gone is the verdict.
     */
    private void useChestplate() {
        if (forceCooldownReset.isEnabled()) {
            // BLATANT only: a real click never skips the use cooldown.
            if (mc instanceof MinecraftClientAccessor accessor) {
                accessor.setItemUseCooldown(0);
            }
        } else if (!InteractSim.useReady(mc)) {
            swapAttempts--; // not an attempt: the vanilla interval has not elapsed
            return;
        }
        mc.gameRenderer.updateCrosshairTarget(1.0f);
        ClickSimController.useNow(mc, true);
    }

    /** Another module (shield break, stun slam, a pearl throw...) owns the hotbar this tick. */
    private boolean hotbarLocked() {
        CombatSwapLockEvent lock = new CombatSwapLockEvent(this);
        EventBus.post(lock);
        return lock.isItemSwapLocked();
    }

    private void driveSwap() {
        if (!wearingElytra()) {
            finishSwap(true);
            return;
        }
        if (hotbarLocked()) {
            return; // the watchdog still bounds the wait
        }
        if (swapAttempts >= MAX_USE_ATTEMPTS || currentTick - swapStartTick > SWAP_WATCHDOG_TICKS) {
            finishSwap(false);
            return;
        }
        if (mc.player.getInventory().getSelectedSlot() != swapSlot
                && (!TickActionOrder.canSwitchSlot() || !SlotSync.select(mc, swapSlot))) {
            return;
        }
        fireUse();
    }

    private void finishSwap(boolean success) {
        swapSlot = -1;
        swapAttempts = 0;
        if (success) {
            nextAttemptTick = currentTick;
            return;
        }
        // The wing is still on: give the slot back and try again shortly (the use is retried from scratch).
        if (holdActive && holdOrigin >= 0 && mc.player != null
                && mc.player.getInventory().getSelectedSlot() == holdSlot) {
            SlotSync.restore(mc, holdOrigin);
        }
        clearHold();
        nextAttemptTick = currentTick + ModuleDelays.msToTicks(retryDelay.sample());
        ClientInternalLog.info(getName(), "Chestplate use was not applied; retrying");
    }

    // ====================================================================================
    // Slot hold
    // ====================================================================================

    /**
     * After the wing is off the chestplate slot stays selected for AutoMace's swap. Whatever moved the
     * hotbar off it (AutoMace's mace swap, the player) owns it: the hold is dropped without touching it.
     * With Swap Back on the original slot returns once no hit is pending.
     */
    private void updateHold() {
        if (!holdActive || swapSlot >= 0) {
            return;
        }
        if (mc.player.getInventory().getSelectedSlot() != holdSlot) {
            clearHold();
            return;
        }
        if (groundTicks >= GROUND_RELEASE_TICKS) {
            clearHold();
            return;
        }
        if (!swapBack.isEnabled() || holdOrigin < 0 || holdOrigin == holdSlot) {
            return;
        }
        int age = currentTick - holdStartTick;
        boolean forced = (holdReleaseTick >= 0 && currentTick >= holdReleaseTick) || age >= holdCapTicks;
        if (!forced) {
            if (age < holdMinTicks) {
                return;
            }
            AutoMace mace = ModuleAccess.getModule(AutoMace.class);
            if (mace != null && mace.isEnabled() && (mace.isSmashArmed() || mace.hasCommittedSmashTarget())) {
                return;
            }
        }
        SlotSync.restore(mc, holdOrigin);
        clearHold();
    }

    private void clearHold() {
        holdActive = false;
        holdSlot = -1;
        holdOrigin = -1;
        holdReleaseTick = -1;
    }

    // ====================================================================================
    // World / item helpers
    // ====================================================================================

    private boolean inFluidOrClimbing() {
        return mc.player.isTouchingWater() || mc.player.isInLava() || mc.player.isClimbing();
    }

    private boolean wearingElytra() {
        return mc.player != null && mc.player.getEquippedStack(EquipmentSlot.CHEST).isOf(Items.ELYTRA);
    }

    /** The best hotbar chestplate: the selected slot wins ties, then netherite down to leather. */
    private int findChestplateSlot() {
        int best = -1;
        int bestRank = -1;
        int selected = mc.player.getInventory().getSelectedSlot();
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (!isChestplate(stack)) {
                continue;
            }
            int rank = rank(stack) * 2 + (i == selected ? 1 : 0);
            if (rank > bestRank) {
                bestRank = rank;
                best = i;
            }
        }
        return best;
    }

    private static int rank(ItemStack stack) {
        if (stack.isOf(Items.NETHERITE_CHESTPLATE)) return 5;
        if (stack.isOf(Items.DIAMOND_CHESTPLATE)) return 4;
        if (stack.isOf(Items.IRON_CHESTPLATE)) return 3;
        if (stack.isOf(Items.CHAINMAIL_CHESTPLATE)) return 2;
        if (stack.isOf(Items.GOLDEN_CHESTPLATE)) return 1;
        return 0;
    }

    private static boolean isChestplate(ItemStack stack) {
        if (stack.isEmpty() || stack.isOf(Items.ELYTRA)) {
            return false;
        }
        EquippableComponent equippable = stack.get(DataComponentTypes.EQUIPPABLE);
        return equippable != null && equippable.slot() == EquipmentSlot.CHEST;
    }
}
