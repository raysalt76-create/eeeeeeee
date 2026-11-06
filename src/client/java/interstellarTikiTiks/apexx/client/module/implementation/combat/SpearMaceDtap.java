package interstellarTikiTiks.apexx.client.module.implementation.combat;

import interstellarTikiTiks.apexx.client.event.EventBus;
import interstellarTikiTiks.apexx.client.event.impl.CombatSwapLockEvent;
import interstellarTikiTiks.apexx.client.friend.FriendManager;
import interstellarTikiTiks.apexx.client.mixin.ClientPlayerEntityAccessor;
import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import interstellarTikiTiks.apexx.client.util.combat.ModuleDelays;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.SubCategory;
import interstellarTikiTiks.apexx.client.module.hooks.AttackExecutionAware;
import interstellarTikiTiks.apexx.client.module.hooks.IncomingPacketInterceptor;
import interstellarTikiTiks.apexx.client.module.hooks.RenderFrameAware;
import interstellarTikiTiks.apexx.client.module.implementation.player.BotFilterProvider;
import interstellarTikiTiks.apexx.client.util.AttackValidator;
import interstellarTikiTiks.apexx.client.util.ClientInternalLog;
import interstellarTikiTiks.apexx.client.util.ModuleAccess;
import interstellarTikiTiks.apexx.client.util.ModuleExecutionContext;
import interstellarTikiTiks.apexx.client.util.RotationManager;
import interstellarTikiTiks.apexx.client.util.SystemInputSimulator;
import interstellarTikiTiks.apexx.client.util.combat.CombatPrediction;
import interstellarTikiTiks.apexx.client.util.combat.InterceptPredictor;
import interstellarTikiTiks.apexx.client.util.combat.SlotSync;
import interstellarTikiTiks.apexx.client.util.combat.SpearKineticModel;
import interstellarTikiTiks.apexx.client.util.combat.SpearRegistrationModel;
import interstellarTikiTiks.apexx.client.util.combat.TickActionOrder;
import interstellarTikiTiks.apexx.client.util.combat.WireRay;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.AttackRangeComponent;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.component.type.KineticWeaponComponent;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityStatuses;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.MaceItem;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.EntityDamageS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityStatusS2CPacket;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * SpearMaceDtap - charged spear pierce, then a mace hit on the same target, spear always first.
 *
 * <p><b>Why the swap waits for the server.</b> A slot change the server processes before the tick that
 * evaluates the spear ends the charge, and the spear never deals damage: one pop, the mace's. Which server
 * tick handles which client packet is invisible to the client: jitter, a retransmit or a slow server tick
 * can deliver the contact movement and a swap sent ticks later in one batch, so every fixed or predicted
 * hold can lose the spear. The server's damage packet for the pierce is the only proof it registered, so
 * the mace swap is released by that packet and nothing else (or by the target's totem pop while a
 * modelled contact on that target is open: inside the target's damage invulnerability the server sends
 * no damage packet, only the pop).
 *
 * <p><b>Prediction model.</b>
 * <ol>
 *   <li><b>Contact:</b> {@link SpearKineticModel} runs the server's charged-spear test on the state the last
 *       movement packet carried (sent position and rotation, head yaw one packet behind, the
 *       {@code 2.0..4.5 + movement.look} ray, damage window and speed test, the 10 tick per-target contact
 *       cooldown) against the target as the server holds it. A predicted contact opens an expectation.</li>
 *   <li><b>Registration:</b> {@link SpearRegistrationModel} learns, per session, the time from a predicted
 *       contact to its server confirmation (seeded from the tab-list ping), and gives the window in which
 *       a registration can still arrive. An expectation that outlives it was not registered: the module logs
 *       it and sends nothing, so the spear charge is never cancelled on a guess.</li>
 *   <li><b>Charge guard:</b> while a target is inside the strike envelope, an expectation is open or a
 *       registered hit waits for its swap, the use key is kept pressed at the tick head (before vanilla's
 *       input handling) against a physical release, for at most the envelope + registration window +
 *       follow-up window. A release there would cancel the charge before the server evaluates it.</li>
 *   <li><b>Follow-up:</b> at the first tick head after the confirmation, inside {@link TickActionOrder}'s
 *       action window: real hotbar swap to the mace through {@link SlotSync}, then the attack through
 *       {@link AttackValidator} on the vanilla crosshair (spear range 4.5, the spear is still the active
 *       item until this tick's player update), same tick. Gates that fail (crosshair, charge, fall) are
 *       retried for {@link #FOLLOW_UP_TICKS} ticks; the dive usually closes the distance meanwhile.</li>
 * </ol>
 * Every refusal, expired expectation, latency sample and the totem pops of each combo are logged.
 *
 * <p><b>Aim.</b> While charging or while a registered hit waits, AutoMace's aim pipeline (target, pre-aim,
 * tracking, Silent Aim) runs in an aim-only mode ({@link #wantsAim()}); it swaps and clicks nothing.
 *
 * <p>Settings: Auto Backstab, plus a "Delays" section (Follow-Up Delay, Combo Cooldown). The swap moment
 * itself comes from the server, the windows from the model. The use-key guard goes through the shared
 * {@link SystemInputSimulator} hold, and the follow-up is gated on {@link WireRay} (the rotation on the
 * wire must reach the target's hitbox). Rule 4 full attack charge stays required (AGENTS.md lists this
 * module with the plain melee modules).
 *
 * <p>COMPILED and unit-tested model only; not RUNTIME VERIFIED.
 */
public class SpearMaceDtap extends Module implements AttackExecutionAware, IncomingPacketInterceptor, RenderFrameAware, interstellarTikiTiks.apexx.client.module.hooks.ClickSimulating {

    private static final double SMASH_MIN_FALL = 1.5;
    /** Ticks a registered spear hit may wait for the dispatch gates (crosshair ray, attack charge, action window). */
    private static final int FOLLOW_UP_TICKS = 8;
    /** Ticks of strike envelope ahead of a modelled contact counted into the use-key guard's cap. */
    private static final int LEAD_TICKS = 4;
    /** Ticks after the follow-up in which the target's totem pops are counted for the log. */
    private static final int OUTCOME_WATCH_TICKS = 30;
    /** A server event older than this at the tick head that reads it is stale (the invulnerability window is over). */
    private static final long EVENT_MAX_AGE_MS = 500L;
    /** Bound on unread server events; the tick head drains them every tick. */
    private static final int MAX_QUEUED_EVENTS = 64;
    private static final double SPEAR_REACH = 4.5;
    /** Players further than this (squared) from the player are not tested. */
    private static final double TEST_RANGE_SQ = 12.0 * 12.0;

    /** A modelled contact waiting for the server's answer. */
    private record Expectation(long contactMs, long tick, double spearDamage, String note) {
    }

    /** A server packet about a hit: damage that names this player as the cause, or a totem pop. */
    private record NetEvent(int victimId, boolean totemPop, long atMs) {
    }

    private final SpearRegistrationModel registration = new SpearRegistrationModel();
    private final ConcurrentLinkedQueue<NetEvent> events = new ConcurrentLinkedQueue<>();

    private long tickCounter = 0L;
    private long tickHeadMs = 0L;
    private long prevTickHeadMs = 0L;

    // ── sent state: what the server holds, one movement packet per tick ─────────
    private Vec3d sentPos = null;
    private Vec3d prevSentPos = null;
    private float sentYaw;
    private float sentPitch;
    private float headYaw;
    private long sentTick = -1L;
    private long prevSentTick = -1L;

    // ── charge bookkeeping ──────────────────────────────────────────────────────
    private int lastUsedTicks = Integer.MAX_VALUE;
    private long lastChargeTick = Long.MIN_VALUE / 2;
    /** Mirror of the server's per-charge contact cooldown: entity id -> world time of first contact. */
    private final Map<Integer, Long> contactAt = new HashMap<>();
    /** Modelled contacts awaiting registration, by entity id. */
    private final Map<Integer, Expectation> expectations = new HashMap<>();

    // ── registered hit awaiting dispatch ────────────────────────────────────────
    private PlayerEntity armed = null;
    private long armedTick = 0L;
    /** Follow-Up Delay drawn for the current registration, in ticks. */
    private int armedDelayTicks = 0;
    private int armedPops = 0;
    private double armedSpearDamage = -1.0;
    private String armedNote = "";
    private String lastRefusal = "";
    private String lastLoggedRefusal = "";

    // ── use-key guard ───────────────────────────────────────────────────────────
    /** True while this module, not the player's finger, is what keeps the use key pressed. */
    private boolean useOverridden = false;
    private int guardTicks = 0;

    // ── outcome log ─────────────────────────────────────────────────────────────
    private int outcomeVictimId = -1;
    private long outcomeUntilTick = -1L;
    private long outcomeFireTick = -1L;
    private long outcomeFireMs = 0L;
    private int outcomePopsBefore = 0;
    private int outcomePopsAfter = 0;

    private boolean attackConfirmed = false;
    private boolean reentrantAttack = false;
    private PlayerEntity comboTarget = null;

    private long nextAllowedMs = 0L;
    private static volatile long lastFireWorldTime = Long.MIN_VALUE;
    private Object boundPlayer;
    private Object boundWorld;

    private final BooleanSetting autoBackstab = new BooleanSetting("Auto Backstab", true)
            .setDescription("Aims at the unshielded back of a blocking target during the spear charge, using AutoMace's backstab aim rotations.");
    private final SliderSetting followUpDelay = new SliderSetting("Follow-Up Delay (ms)", 10, 0, 250, 0)
            .withJitter(5)
            .setDescription("Wait after the server confirms the spear hit before the mace swap and hit (rounded to tick heads). 0 = the first tick head after the confirmation.")
            .setSection("Delays");
    private final SliderSetting comboCooldown = new SliderSetting("Combo Cooldown (ms)", 200, 0, 2000, 0)
            .withJitter(25)
            .setDescription("Gap after a combo before the next one may start; AutoMace stays paused for it.")
            .setSection("Delays");

    public SpearMaceDtap() {
        super("SpearMaceDoubleTap", "Spear-to-mace double tap: lands a charged spear hit, waits until the server confirms the spear hit registered, then immediately swaps to your mace and lands a follow-up mace hit, combining the spear's reach and knockback with the mace's damage in one exchange.", Category.COMBAT);
        setSubCategory(SubCategory.MACE);
        addSetting(autoBackstab);
        addSetting(followUpDelay);
        addSetting(comboCooldown);
    }

    public boolean isAutoBackstabEnabled() {
        return autoBackstab.isEnabled();
    }

    /**
     * True while Auto Backstab is active: only during a main-hand spear charge.
     * When active against a blocking target, AutoMace's backstab aim rotations (StrikePlanner.Need.BACKSTAB)
     * are used to steer toward the unshielded side of the target.
     */
    public static boolean isAutoBackstabActive() {
        SpearMaceDtap dtap = ModuleAccess.getModule(SpearMaceDtap.class);
        return dtap != null && dtap.isEnabled() && dtap.autoBackstab.isEnabled()
                && dtap.mc.player != null && dtap.mc.world != null
                && System.currentTimeMillis() >= dtap.nextAllowedMs
                && dtap.isChargingMainHandSpear();
    }

    /** True during the short gap after a combo; AutoMace stays paused for it. */
    public static boolean isComboActive() {
        SpearMaceDtap dtap = ModuleAccess.getModule(SpearMaceDtap.class);
        return dtap != null && dtap.isEnabled() && System.currentTimeMillis() < dtap.nextAllowedMs;
    }

    /** True in the world tick in which the follow-up was dispatched; AutoMace keeps its aim until the movement packet. */
    public static boolean firedThisTick() {
        net.minecraft.client.MinecraftClient client = net.minecraft.client.MinecraftClient.getInstance();
        return client != null && client.world != null && client.world.getTime() == lastFireWorldTime;
    }

    /** True while AutoMace should run its aim (and nothing else): a spear charge, or a registered hit waiting for its mace. */
    public static boolean wantsAim() {
        SpearMaceDtap dtap = ModuleAccess.getModule(SpearMaceDtap.class);
        return dtap != null && dtap.isEnabled()
                && dtap.mc.player != null && dtap.mc.world != null
                && System.currentTimeMillis() >= dtap.nextAllowedMs
                && (dtap.isChargingMainHandSpear() || dtap.armed != null);
    }

    @Override
    public void onEnable() {
        registration.reset();
        resetState();
    }

    @Override
    public void onDisable() {
        // Releases the use-key hold and AutoMace's aim-only mode this module may still own.
        resetState();
    }

    /** Everything this module dispatches finishes inside one call; a deferred spear restore stays queued. */
    @Override
    protected void onRuntimeStateReleased() {
        resetState();
    }

    private void resetState() {
        restoreUse();
        events.clear();
        expectations.clear();
        contactAt.clear();
        armed = null;
        armedPops = 0;
        attackConfirmed = false;
        reentrantAttack = false;
        comboTarget = null;
        sentPos = null;
        prevSentPos = null;
        sentTick = -1L;
        prevSentTick = -1L;
        lastUsedTicks = Integer.MAX_VALUE;
        lastChargeTick = Long.MIN_VALUE / 2;
        guardTicks = 0;
        outcomeUntilTick = -1L;
        outcomeVictimId = -1;
        lastRefusal = "";
        lastLoggedRefusal = "";
        boundPlayer = mc.player;
        boundWorld = mc.world;
        AutoMace autoMace = ModuleAccess.getModule(AutoMace.class);
        if (autoMace != null && !autoMace.isEnabled()) {
            autoMace.stopAiming();
        }
    }

    // ── tick head: model, server events, guard, dispatch ────────────────────────

    @Override
    public void onTick() {
        tickCounter++;
        if (!hasWorld()) {
            events.clear();
            return;
        }
        long now = System.currentTimeMillis();
        prevTickHeadMs = tickHeadMs == 0L ? now - (long) SpearRegistrationModel.TICK_MS : tickHeadMs;
        tickHeadMs = now;
        registration.seed(pingMs());
        sampleSentState();
        if (mc.gameRenderer != null) {
            mc.gameRenderer.updateCrosshairTarget(1.0f);
        }

        boolean charging = isChargingMainHandSpear();
        ItemStack active = charging ? mc.player.getActiveItem() : ItemStack.EMPTY;
        int used = 0;
        if (charging) {
            used = active.getMaxUseTime(mc.player) - mc.player.getItemUseTimeLeft();
            if (used < lastUsedTicks) {
                // A new charge: the server starts a fresh contact-cooldown map with it.
                contactAt.clear();
                guardTicks = 0;
            }
            lastUsedTicks = used;
            lastChargeTick = tickCounter;
            if (outcomeUntilTick >= 0L && tickCounter > outcomeFireTick + 1) {
                // A new charge: its registrations must not be read as the last combo's outcome.
                finishOutcome(true);
            }
            if (armed == null && now >= nextAllowedMs) {
                predictContacts(active, used);
            }
        } else {
            lastUsedTicks = Integer.MAX_VALUE;
            contactAt.clear();
        }

        readServerEvents(now, charging);
        expireExpectations(now);
        finishOutcome(false);
        holdUseKey(charging, active, used);

        if (armed != null) {
            followUp();
        }

        AutoMace autoMace = ModuleAccess.getModule(AutoMace.class);
        if (autoMace != null && !autoMace.isEnabled()) {
            if (wantsAim()) {
                autoMace.runSpearAimOnly();
            } else {
                autoMace.stopAiming();
            }
        }
    }

    @Override
    public void onRenderFrame() {
        AutoMace autoMace = ModuleAccess.getModule(AutoMace.class);
        if (autoMace != null && !autoMace.isEnabled()) {
            if (wantsAim()) {
                autoMace.runSpearAimOnly();
            } else {
                autoMace.stopAiming();
            }
        }
    }

    private void followUp() {
        PlayerEntity target = armed;
        if (!isValidTarget(target)) {
            drop("target invalid");
            return;
        }
        long sinceArmed = tickCounter - armedTick;
        if (sinceArmed > FOLLOW_UP_TICKS + armedDelayTicks) {
            drop(lastRefusal.isEmpty() ? "timed out" : lastRefusal);
            return;
        }
        if (sinceArmed < armedDelayTicks) {
            refuse("follow-up delay (" + sinceArmed + "/" + armedDelayTicks + " ticks)");
            return;
        }
        if (fire(target)) {
            armed = null;
        }
    }

    // ── server events: the only thing that releases the swap ────────────────────

    /** Network thread: records hits the server reports. Nothing is dispatched from here. */
    @Override
    public boolean handleIncomingPacket(Packet<?> packet) {
        try {
            if (!isEnabled() || mc.player == null || mc.world == null) {
                return false;
            }
            int me = mc.player.getId();
            if (packet instanceof EntityDamageS2CPacket damage) {
                // On the wire the source ids are sent as id + 1 (0 = none), but the packet codec
                // (readOffsetVarInt) already subtracts the 1: the decoded record holds the real entity id,
                // -1 for none. Comparing against id + 1 never matched, so no spear hit was ever registered.
                if (damage.entityId() != me && (damage.sourceCauseId() == me || damage.sourceDirectId() == me)) {
                    queue(new NetEvent(damage.entityId(), false, System.currentTimeMillis()));
                }
            } else if (packet instanceof EntityStatusS2CPacket status
                    && status.getStatus() == EntityStatuses.USE_TOTEM_OF_UNDYING) {
                Entity popped = status.getEntity(mc.world);
                if (popped != null && popped != mc.player) {
                    queue(new NetEvent(popped.getId(), true, System.currentTimeMillis()));
                }
            }
        } catch (RuntimeException ignored) {
            // A packet observer must never disturb packet handling.
        }
        return false;
    }

    private void queue(NetEvent event) {
        while (events.size() >= MAX_QUEUED_EVENTS) {
            events.poll();
        }
        events.add(event);
    }

    /**
     * Turns this tick's server events into combo state: a registration arms the follow-up (and is a latency
     * sample when the model predicted it); a pop on an armed or just-hit target is counted for the log.
     */
    private void readServerEvents(long now, boolean charging) {
        NetEvent ev;
        while ((ev = events.poll()) != null) {
            if (now - ev.atMs() > EVENT_MAX_AGE_MS) continue;
            int id = ev.victimId();
            if (outcomeUntilTick >= 0L && id == outcomeVictimId) {
                // The mace's answer needs a round trip; a pop well before that is the spear's, read a tick late.
                if (ev.totemPop() && ev.atMs() - outcomeFireMs < registration.expectedMs() / 2.0) {
                    outcomePopsBefore++;
                } else if (ev.totemPop()) {
                    outcomePopsAfter++;
                    finishOutcome(true);
                }
                continue;
            }
            if (armed != null) {
                if (ev.totemPop() && armed.getId() == id) armedPops++;
                continue;
            }
            if (now < nextAllowedMs) continue;
            Expectation exp = expectations.get(id);
            // Damage this player caused outside a spear charge (or its registration window) is not a pierce.
            if (!charging && exp == null && tickCounter - lastChargeTick > registration.deadlineTicks()) continue;
            // A pop names no attacker: it counts only on a target the model just predicted a contact on.
            if (ev.totemPop() && exp == null) continue;
            if (!(mc.world.getEntityById(id) instanceof PlayerEntity p) || !isValidTarget(p)) continue;
            if (exp != null) {
                expectations.remove(id);
                long sample = ev.atMs() - exp.contactMs();
                registration.addSample(sample);
                armedNote = String.format("%s, registered %d ms after contact, model %.0f+-%.0f ms n=%d",
                        exp.note(), sample, registration.expectedMs(), registration.deviationMs(), registration.samples());
                armedSpearDamage = exp.spearDamage();
            } else {
                armedNote = "registered without a modelled contact";
                armedSpearDamage = -1.0;
            }
            armed = p;
            armedTick = tickCounter;
            armedDelayTicks = ModuleDelays.msToTicks(followUpDelay.sample());
            armedPops = ev.totemPop() ? 1 : 0;
            lastRefusal = "";
            lastLoggedRefusal = "";
        }
    }

    /** A modelled contact the server never confirmed in time was not registered: the spear charge is left alone. */
    private void expireExpectations(long now) {
        Iterator<Map.Entry<Integer, Expectation>> it = expectations.entrySet().iterator();
        while (it.hasNext()) {
            Expectation exp = it.next().getValue();
            if (!registration.awaiting(now - exp.contactMs())) {
                ClientInternalLog.info("SpearMaceDtap", "no registration: " + exp.note() + " got no server confirmation within "
                        + Math.round(registration.deadlineMs()) + " ms - mace held back, the charge was not cancelled");
                it.remove();
            }
        }
    }

    // ── contact model ───────────────────────────────────────────────────────────

    /**
     * Opens an expectation for every player the server's charged-spear test hits on the state the last
     * movement packet carried. Only predicts; the swap waits for the server either way.
     */
    private void predictContacts(ItemStack active, int used) {
        if (findMaceSlot() < 0 || !hasSmashFall()) {
            // Nothing to follow up with: an expectation would only hold the button for no reason.
            return;
        }
        KineticWeaponComponent kinetic = active.get(DataComponentTypes.KINETIC_WEAPON);
        AttackRangeComponent range = active.get(DataComponentTypes.ATTACK_RANGE);
        if (kinetic == null || range == null || kinetic.damageConditions().isEmpty()
                || sentPos == null || prevSentPos == null || prevSentTick != tickCounter - 1) {
            return;
        }
        KineticWeaponComponent.Condition damage = kinetic.damageConditions().get();
        int u = used - kinetic.delayTicks();
        if (u < 1) {
            // Before the delay the server returns before touching the cooldown; one tick of slack for count skew.
            return;
        }
        Vec3d eye = sentPos.add(0.0, mc.player.getEyeHeight(mc.player.getPose()), 0.0);
        Vec3d look = mc.player.getRotationVector(sentPitch, sentYaw);
        Vec3d headLook = mc.player.getRotationVector(sentPitch, headYaw);
        Vec3d movement = sentPos.subtract(prevSentPos);
        double ownSpeed = SpearKineticModel.speedAlong(look, movement);
        double minR = range.getEffectiveMinRange(mc.player);
        double maxR = range.getEffectiveMaxRange(mc.player);
        long worldTime = mc.world.getTime();

        for (PlayerEntity p : mc.world.getPlayers()) {
            if (p == mc.player || !isValidTarget(p) || p.squaredDistanceTo(mc.player) > TEST_RANGE_SQ) continue;
            if (isAutoBackstabActive() && p.isBlocking() && !AutoMace.isPlayerBehindTarget(p, mc)) {
                // Shield blocks the spear from the front: wait until backstab aim brings us behind them.
                continue;
            }
            Box box = targetBox(p);
            double margin = range.hitboxMargin() + p.getTargetingMargin();
            Vec3d contact = SpearKineticModel.contactPoint(eye, headLook, movement, minR, maxR, margin, box);
            if (contact == null) continue;
            // Both the head ray and the plain look must touch: a one-yaw difference at the box edge is not a hit.
            if (!SpearKineticModel.touches(eye, look, movement, minR, maxR, margin, box)) continue;
            if (mc.world.raycast(new RaycastContext(eye, contact, RaycastContext.ShapeType.COLLIDER,
                    RaycastContext.FluidHandling.NONE, mc.player)).getType() != HitResult.Type.MISS) continue;
            Long first = contactAt.get(p.getId());
            if (first != null && worldTime - first < SpearKineticModel.CONTACT_COOLDOWN_TICKS) continue;
            // The server starts the cooldown on contact, damage or not.
            contactAt.put(p.getId(), worldTime);
            double rel = SpearKineticModel.relativeSpeed(ownSpeed,
                    SpearKineticModel.speedAlong(look, targetMovement(p)));
            if (!damage.isSatisfied(u, ownSpeed, rel, 1.0)) continue;
            double spearDamage = SpearKineticModel.spearDamage(rel, kinetic.damageMultiplier());
            // The movement packet carrying this state left during the previous tick.
            expectations.putIfAbsent(p.getId(), new Expectation(prevTickHeadMs, tickCounter, spearDamage,
                    String.format("contact %s u=%d own=%.1f dmg=%.0f", p.getName().getString(), u, ownSpeed, spearDamage)));
        }
    }

    // ── use-key guard ───────────────────────────────────────────────────────────

    /**
     * Keeps the use key pressed against a physical release while a target is about to be touched, a contact
     * waits for its registration, or a registered hit waits for its swap. Runs at the tick head, ahead of
     * vanilla's input handling, which is what would otherwise stop the item and send the release. Only while
     * the spear is still charging: holding the key with nothing in use would start a new use.
     */
    private void holdUseKey(boolean charging, ItemStack active, int used) {
        if (!charging || mc.options == null || mc.options.useKey == null || mc.currentScreen != null) {
            restoreUse();
            return;
        }
        boolean wanted = armed != null || !expectations.isEmpty() || strikeImminent(active, used);
        if (!wanted || guardTicks >= registration.guardCapTicks(LEAD_TICKS, FOLLOW_UP_TICKS)) {
            restoreUse();
            return;
        }
        if (!mc.options.useKey.isPressed()) {
            // Shared, owner-scoped hold: released only by this module (or its runtime release).
            SystemInputSimulator.pressUse();
            useOverridden = mc.options.useKey.isPressed();
        }
        if (useOverridden) {
            guardTicks++;
        }
    }

    /** True while a charge past its delay has a valid target inside the server ray's reach envelope. */
    private boolean strikeImminent(ItemStack active, int used) {
        KineticWeaponComponent kinetic = active.get(DataComponentTypes.KINETIC_WEAPON);
        AttackRangeComponent range = active.get(DataComponentTypes.ATTACK_RANGE);
        if (kinetic == null || range == null || used < kinetic.delayTicks() || sentPos == null
                || findMaceSlot() < 0 || !hasSmashFall()) {
            return false;
        }
        Vec3d eye = sentPos.add(0.0, mc.player.getEyeHeight(mc.player.getPose()), 0.0);
        double speed = prevSentPos != null ? sentPos.subtract(prevSentPos).length() : 0.0;
        double maxR = range.getEffectiveMaxRange(mc.player);
        for (PlayerEntity p : mc.world.getPlayers()) {
            if (p == mc.player || !isValidTarget(p) || p.squaredDistanceTo(mc.player) > TEST_RANGE_SQ) continue;
            double d = Math.sqrt(p.getBoundingBox().squaredMagnitude(eye));
            if (SpearKineticModel.inStrikeEnvelope(d, maxR, range.hitboxMargin() + p.getTargetingMargin(), speed)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Releases this module's own use-key hold through the shared simulator; another owner's hold (or the
     * player's finger) is left alone. Idempotent.
     */
    private void restoreUse() {
        if (!useOverridden) {
            return;
        }
        useOverridden = false;
        ModuleExecutionContext.run(this, SystemInputSimulator::releaseUse);
    }

    // ── outcome log ─────────────────────────────────────────────────────────────

    /** Logs the totem pops of the last combo: one before the mace (spear) and one after it is the double pop. */
    private void finishOutcome(boolean now) {
        if (outcomeUntilTick < 0L || (!now && tickCounter < outcomeUntilTick)) {
            return;
        }
        ClientInternalLog.info("SpearMaceDtap", "outcome: totem pops spear=" + outcomePopsBefore + " mace=" + outcomePopsAfter
                + " (" + (tickCounter - outcomeFireTick) + " ticks after the swap)"
                + (outcomePopsAfter == 0 ? " - a mace hit inside the spear's invulnerability only deals its excess over the spear" : ""));
        outcomeUntilTick = -1L;
        outcomeVictimId = -1;
    }

    private void drop(String reason) {
        ClientInternalLog.info("SpearMaceDtap", "follow-up dropped: " + reason + " (" + armedNote + ", waited "
                + (tickCounter - armedTick) + " ticks)");
        armed = null;
        lastRefusal = "";
        lastLoggedRefusal = "";
    }

    /** Records the position/rotation the last movement packet carried; consecutive ticks give the server's movement. */
    private void sampleSentState() {
        if (!(mc.player instanceof ClientPlayerEntityAccessor sent)) {
            return;
        }
        Vec3d now = new Vec3d(sent.getLastXClient(), sent.getLastYClient(), sent.getLastZClient());
        prevSentPos = sentPos;
        prevSentTick = sentTick;
        // The server's head yaw is the yaw of the player update before this packet.
        headYaw = sentTick == tickCounter - 1 ? sentYaw : sent.getLastYawClient();
        sentPos = now;
        sentTick = tickCounter;
        sentYaw = sent.getLastYawClient();
        sentPitch = sent.getLastPitchClient();
    }

    private boolean hasWorld() {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) {
            return false;
        }
        if (boundPlayer != mc.player || boundWorld != mc.world) {
            // A new player/world invalidates every entity, slot and sent state this module remembered.
            resetState();
        }
        return true;
    }

    private double pingMs() {
        if (mc.getNetworkHandler() == null) {
            return -1.0;
        }
        PlayerListEntry entry = mc.getNetworkHandler().getPlayerListEntry(mc.player.getUuid());
        return entry == null ? -1.0 : entry.getLatency();
    }

    // ── dispatch ────────────────────────────────────────────────────────────────

    /**
     * Same-tick contract: real slot swap first, then the attack, both before this tick's movement
     * packet. Every check runs before the swap, so a refused combo leaves the spear untouched.
     */
    private boolean fire(PlayerEntity target) {
        if (!TickActionOrder.isWindowOpen()) return refuse("action window closed");
        if (!TickActionOrder.canSwitchSlot()) return refuse("another use/attack already left this interval");
        if (mc.currentScreen != null) return refuse("screen open");
        if (System.currentTimeMillis() < nextAllowedMs) return refuse("cooldown");
        if (!hasSmashFall()) return refuse("fall distance too short for a smash");

        int spearSlot = mc.player.getInventory().getSelectedSlot();
        int maceSlot = findMaceSlot();
        if (maceSlot < 0 || maceSlot == spearSlot) return refuse("no mace in the hotbar");
        // Rule 4: a full attack charge; the slot change does not reset it before this tick's player update.
        if (mc.player.getAttackCooldownProgress(0.5f) < 1.0f) {
            return refuse("attack charge not full (" + SpearKineticModel.ticksToFullCharge(
                    mc.player.getAttackCooldownProgress(0.5f), mc.player.getAttackCooldownProgressPerTick())
                    + " ticks to go)");
        }
        // Another owner's silent rotation would reroute vanilla's attack to its own target; AutoMace's is the aim we asked for.
        boolean silent = RotationManager.isSilentRotationActive();
        if (silent && !(RotationManager.getCurrentOwner() instanceof AutoMace)) return refuse("foreign silent rotation");
        if (!isLiveRayOn(target, silent)) return refuse("crosshair is not on the target (out of 4.5 range or aim off)");
        // Rule 3: the rotation that goes on the wire must itself reach the hitbox, not just the camera ray.
        if (!WireRay.onEntity(mc, target, SPEAR_REACH)) return refuse("wire rotation does not reach the target hitbox");

        CombatSwapLockEvent lock = new CombatSwapLockEvent(this);
        EventBus.post(lock);
        if (lock.isItemSwapLocked() || lock.isAutoAttackLocked()) return refuse("swap or attack locked by another module");

        String forecast = maceForecast(maceSlot);
        if (!SlotSync.select(mc, maceSlot) || mc.player.getInventory().getSelectedSlot() != maceSlot
                || !(mc.player.getMainHandStack().getItem() instanceof MaceItem)) {
            int held = mc.player.getInventory().getSelectedSlot();
            if (held != spearSlot) {
                // The slot changed without the mace in hand: put the spear back.
                endCombo(spearSlot, held, true);
                return true;
            }
            return refuse("slot select refused");
        }
        comboTarget = target;
        attackConfirmed = false;
        reentrantAttack = true;
        try {
            if (silent) {
                RotationManager.withSilentReach(SPEAR_REACH, () -> AttackValidator.dispatchImmediateClickAttack(mc));
            } else {
                AttackValidator.dispatchImmediateClickAttack(mc);
            }
        } finally {
            reentrantAttack = false;
        }
        releaseAttackKey();
        lastFireWorldTime = mc.world.getTime();
        ClientInternalLog.info("SpearMaceDtap", "follow-up (" + armedNote + ") " + (tickCounter - armedTick)
                + " ticks after registration, spear pops=" + armedPops + ", " + forecast
                + ", guardTicks=" + guardTicks + " attackSent=" + attackConfirmed + " silent=" + silent);
        outcomeVictimId = target.getId();
        outcomeFireTick = tickCounter;
        outcomeFireMs = System.currentTimeMillis();
        outcomeUntilTick = tickCounter + OUTCOME_WATCH_TICKS;
        outcomePopsBefore = armedPops;
        outcomePopsAfter = 0;
        lastRefusal = "";
        lastLoggedRefusal = "";
        // No attack: the swap still happened, so the spear comes back on the next tick head.
        endCombo(spearSlot, maceSlot, !attackConfirmed);
        return true;
    }

    /** Records why this tick's dispatch was refused; logged once per distinct reason so a silent skip is visible. */
    private boolean refuse(String reason) {
        lastRefusal = reason;
        if (!reason.equals(lastLoggedRefusal)) {
            lastLoggedRefusal = reason;
            ClientInternalLog.info("SpearMaceDtap", "follow-up waiting: " + reason + " (" + armedNote + ", "
                    + (tickCounter - armedTick) + " ticks since registration)");
        }
        return false;
    }

    @Override
    public void onVanillaEntityAttack(Entity target) {
        if (reentrantAttack && target != null && target == comboTarget) {
            attackConfirmed = true;
        }
    }

    /** A spear restore waits for the next tick head: a slot change may not follow this tick's attack. */
    private void endCombo(int spearSlot, int maceSlot, boolean restoreSpear) {
        if (restoreSpear && spearSlot >= 0 && spearSlot <= 8 && mc.player.getInventory().getSelectedSlot() == maceSlot) {
            TickActionOrder.deferSlot(spearSlot, maceSlot);
        }
        nextAllowedMs = System.currentTimeMillis() + Math.round(comboCooldown.sample());
        comboTarget = null;
    }

    /**
     * Releases only an attack-key press this module created (owner-scoped in the shared simulator); the
     * emitted click's queued count is already consumed by the dispatch. The player's own held key is untouched.
     */
    private void releaseAttackKey() {
        ModuleExecutionContext.run(this, SystemInputSimulator::releaseAttack);
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    /** The target as the server holds it when it evaluates the spear: tracked position carried forward by the latency. */
    private Box targetBox(PlayerEntity p) {
        Box server = InterceptPredictor.getInstance().serverBox(p, 0.0);
        return server != null ? server : p.getBoundingBox();
    }

    private static Vec3d targetMovement(PlayerEntity p) {
        Vec3d step = InterceptPredictor.getInstance().velocity(p.getUuid());
        return step != null ? step : CombatPrediction.perTickVelocityVec(p);
    }

    /**
     * Vanilla's tick-start crosshair (the spear is still the main-hand item, so it picks with the spear's
     * 4.5 block range) must be on the target. The mace attack reuses that crosshair. Under AutoMace's
     * Silent Aim the ray is cast from the rotation the server will receive, which is what the rerouted click uses.
     */
    private boolean isLiveRayOn(PlayerEntity target, boolean silent) {
        if (silent) {
            HitResult hit = RotationManager.getCrosshairTarget(RotationManager.getFinalYaw(),
                    RotationManager.getFinalPitch(), SPEAR_REACH);
            return hit instanceof EntityHitResult ehr && ehr.getEntity() == target;
        }
        if (mc.gameRenderer == null) {
            return false;
        }
        mc.gameRenderer.updateCrosshairTarget(1.0f);
        return mc.crosshairTarget instanceof EntityHitResult hit && hit.getEntity() == target;
    }

    /**
     * Log only: inside the target's damage invulnerability the mace deals its excess over the spear hit
     * (pre-armor), which decides whether it pops a second totem. A hit with no modelled spear damage shows the mace alone.
     */
    private String maceForecast(int maceSlot) {
        ItemStack mace = mc.player.getInventory().getStack(maceSlot);
        double maceDamage = SpearKineticModel.swappedMaceDamage(mc.player.getAttributeValue(EntityAttributes.ATTACK_DAMAGE),
                mc.player.getAttackCooldownProgress(0.5f), mc.player.fallDistance, enchantLevel(mace, "density"));
        if (armedSpearDamage < 0.0) {
            return String.format("mace~%.0f", maceDamage);
        }
        return String.format("mace~%.0f spear~%.0f excess~%.0f", maceDamage, armedSpearDamage,
                SpearKineticModel.netGainInsideInvulnerability(maceDamage, armedSpearDamage));
    }

    private boolean isChargingMainHandSpear() {
        return mc.player.isUsingItem() && mc.player.getActiveHand() == Hand.MAIN_HAND
                && isSpear(mc.player.getActiveItem()) && isSpear(mc.player.getMainHandStack());
    }

    private static boolean isSpear(ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.contains(DataComponentTypes.KINETIC_WEAPON);
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

    /** The server's smash test ({@code MaceItem.shouldDealAdditionalDamage}) on the client's own fall. */
    private boolean hasSmashFall() {
        if (mc.player.isOnGround() || mc.player.isGliding() || mc.player.hasVehicle()
                || mc.player.isTouchingWater() || mc.player.isInLava() || mc.player.isClimbing()) {
            return false;
        }
        return mc.player.fallDistance > SMASH_MIN_FALL;
    }

    /** Hotbar mace with the most Density, then Breach (the two smash damage enchantments). */
    private int findMaceSlot() {
        int best = -1;
        int bestScore = -1;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (!(stack.getItem() instanceof MaceItem)) continue;
            int score = enchantLevel(stack, "density") * 16 + enchantLevel(stack, "breach");
            if (score > bestScore) {
                bestScore = score;
                best = i;
            }
        }
        return best;
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
