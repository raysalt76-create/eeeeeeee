package interstellarTikiTiks.apexx.client.module.implementation.combat;

import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.ModeSetting;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import interstellarTikiTiks.apexx.client.module.hooks.AttackGatingProvider;
import interstellarTikiTiks.apexx.client.module.hooks.AttackHandleAware;
import interstellarTikiTiks.apexx.client.module.hooks.VelocityPacketAware;
import interstellarTikiTiks.apexx.client.util.ModuleExecutionContext;
import interstellarTikiTiks.apexx.client.util.SystemInputSimulator;
import interstellarTikiTiks.apexx.client.util.combat.MeleeMechanics;
import interstellarTikiTiks.apexx.client.util.combat.ModuleDelays;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.hit.EntityHitResult;

import java.util.concurrent.ThreadLocalRandom;

/**
 * HitSelect - holds the swing until it will count, and takes it right after the opponent's hit lands.
 *
 * <p>Manual hit-select: do not panic-click, let the opponent's hit connect, then swing immediately
 * while the target is still in reach. The swing lands while the knockback is being applied, which
 * cuts the horizontal displacement. This module does that by withholding the player's own click:
 *
 * <ul>
 *   <li><b>Never spend a hit on i-frames.</b> While the target's invulnerability ticks
 *       ({@code timeUntilRegen}) are above the threshold the click is held, because a swing that
 *       deals no damage also gives no sprint reset.</li>
 *   <li><b>Trade window.</b> Opens when knockback from a hit reaches the local player (a velocity
 *       packet preceded by the damage flash) and stays open for Velocity Window ticks. KB Reduction
 *       swings at once: a strong sprint hit multiplies your own velocity by 0.6, so the earlier it
 *       lands after the knockback, the more of it is removed. Criticals waits for the fall, but only
 *       while a crit can actually happen before the hold times out (a hit the server still counts as
 *       sprinting is a sprint hit, never a crit).</li>
 *   <li><b>Damage Tick</b> additionally holds until the trade window opens, but only for a bounded
 *       time so a passive opponent cannot lock the click forever. <b>Cancel Click</b> has no timeout.</li>
 * </ul>
 *
 * <p>Only the player's own clicks are held. A swing dispatched by another module (AutoMace,
 * crystal modules, ...) is never withheld; TriggerBot queries this module explicitly.
 * A held click is skipped, not deferred, so a held mouse button simply tries again next tick.
 */
public class HitSelect extends Module implements AttackGatingProvider, AttackHandleAware, VelocityPacketAware {

    private final ModeSetting mode = new ModeSetting("Hold Rule", "I-Frames", "I-Frames", "Trade Sync", "Trade Strict")
            .setDescription("I-Frames: only withholds a click that would land while the enemy is still invulnerable. Trade Sync: additionally waits for their hit to land on you, up to Max Hold. Trade Strict: waits for it indefinitely.");

    private final ModeSetting preference = new ModeSetting("Goal", "Less Knockback", "Less Knockback", "Crit On Fall")
            .setDescription("Less Knockback: clicks as soon as you have been knocked back. Crit On Fall: waits until you are dropping so the click can crit, as long as that fits within Max Hold.");

    private final SliderSetting chance = new SliderSetting("Engage Odds %", 90, 0, 100, 0)
            .setDescription("Chance that HitSelect manages a given exchange at all. Decided once per exchange, so below 100% some trades are played as plain clicks, which keeps your pattern less predictable.");

    private final SliderSetting velocityWindowTicks = new SliderSetting("Trade Window (ticks)", 6, 1, 12, 0).withJitter(1)
            .setDescription("How many ticks after you take knockback the exchange stays open for a released counter-hit.");

    private final SliderSetting targetHurtThreshold = new SliderSetting("Enemy I-Frame Limit", 10, 0, 20, 0)
            .setDescription("Clicks stay withheld while the enemy's remaining invulnerability ticks exceed this. 10 releases the click once it can deal damage.");

    private final SliderSetting holdTimeoutTicks = new SliderSetting("Max Hold (ticks)", 12, 2, 40, 0).withJitter(2)
            .setDescription("Longest a click is withheld: while waiting for the enemy's hit in Trade Sync, or for the fall in Crit On Fall.");

    private final ModeSetting stance = new ModeSetting("Stance", "Closing In", "Any", "Closing In", "Grounded", "Closing In + Grounded")
            .setDescription("When clicks may be withheld. Closing In: only while you advance on the enemy (forward key down or the gap shrinking). Grounded: only while on the ground. Any: always.");
    private final BooleanSetting disableInLiquids = new BooleanSetting("Disable In Liquids", false)
            .setDescription("Never withhold your clicks while you are in water or lava; they go through as plain clicks.");
    private final BooleanSetting disableInAir = new BooleanSetting("Disable In Air", false)
            .setDescription("Never withhold your clicks while you are off the ground, e.g. during crit jumps. A plain switch with the same effect as the Grounded stances.");


    private final SliderSetting releaseDelay = new SliderSetting("Release Delay (ms)", 30, 0, 200, 0).withJitter(10)
            .setSection("Delays")
            .setDescription("After their hit knocks you back, keep your counter-click held this long before releasing it, in milliseconds (a human reaction time). 0 = release at once.");

    private int velocityTicks = 0;
    private long releaseAtMs = 0L;
    private int holdLimitTicks = -1;
    private int tradeStartAge = -1;
    private boolean engageRolled = false;
    private boolean engaged = false;
    private int holdStartAge = -1;

    public HitSelect() {
        super("HitSelect", "Makes your clicks count in trades: it withholds clicks that would land while the enemy is still invulnerable, and can wait for their hit to land on you before releasing yours right after it. That counter-hit lands while you are taking knockback, which reduces how far you get pushed (hit-selecting), or can be held for a falling crit. It never adds clicks, it only times the ones you make.", Category.COMBAT);
        addSetting(mode);
        addSetting(preference);
        addSetting(chance);
        addSetting(velocityWindowTicks);
        addSetting(targetHurtThreshold);
        addSetting(holdTimeoutTicks);
        addSetting(stance);
        addSetting(disableInLiquids);
        addSetting(disableInAir);
        addSetting(releaseDelay);
    }

    @Override
    public void onEnable() {
        resetState();
    }

    @Override
    public void onDisable() {
        resetState();
    }

    @Override
    protected void onRuntimeStateReleased() {
        resetState();
    }

    private void resetState() {
        velocityTicks = 0;
        tradeStartAge = -1;
        endTrade();
    }

    private void endTrade() {
        tradeStartAge = -1;
        engageRolled = false;
        engaged = false;
        holdStartAge = -1;
        holdLimitTicks = -1;
        releaseAtMs = 0L;
    }

    @Override
    public void onTick() {
        if (velocityTicks > 0) {
            velocityTicks--;
        }
    }

    @Override
    public void onVelocityPacket() {
        if (!isEnabled() || mc.player == null) return;
        // Knockback from a hit only; wind charges, rods and teleports also send velocity.
        if (MeleeMechanics.ticksSinceSelfKnockback(mc.player) != 0) return;
        velocityTicks = Math.max(1, (int) Math.round(velocityWindowTicks.sample()));
        tradeStartAge = mc.player.age;
        releaseAtMs = System.currentTimeMillis() + ModuleDelays.clampMs(releaseDelay.sample());
    }

    public boolean isVelocityActive() {
        return isEnabled() && velocityTicks > 0;
    }

    /** Whether the player's click should be held right now. Safe to call several times per tick. */
    public boolean shouldGate() {
        if (!isEnabled() || mc.player == null || mc.world == null) {
            return false;
        }
        if (isOtherModuleAttack()) {
            return false;
        }

        LivingEntity target = rayTarget();
        if (target == null) {
            endTrade();
            return false;
        }
        if ((stance.is("Grounded") || stance.is("Closing In + Grounded")) && !mc.player.isOnGround()) {
            return false;
        }
        // Read per click, so a gate that turns on mid-trade releases the hold on the very next click.
        if (disableInLiquids.isEnabled() && MeleeMechanics.isInLiquid(mc.player)) {
            return false;
        }
        if (disableInAir.isEnabled() && MeleeMechanics.isInAir(mc.player)) {
            return false;
        }
        if ((stance.is("Closing In") || stance.is("Closing In + Grounded")) && !mc.options.forwardKey.isPressed() && !isClosingIn(target)) {
            return false;
        }

        // One chance roll per trade: re-rolling per click would leak a swing through the hold.
        if (!engageRolled) {
            engageRolled = true;
            engaged = chance.getValue() >= 100.0
                    || ThreadLocalRandom.current().nextDouble() * 100.0 < chance.getValue();
        }
        if (!engaged) {
            return false;
        }

        // A swing into i-frames deals no damage and gives no sprint reset.
        if (target.timeUntilRegen > (int) targetHurtThreshold.getValue()) {
            return true;
        }

        // Any damage (fire, falls) raises hurtTime, so only knockback from a hit opens the window.
        boolean tradeWindow = velocityTicks > 0;
        // Criticals keeps holding past the velocity window while the fall is still coming.
        boolean critHold = preference.is("Crit On Fall") && critReachable();
        if (tradeWindow || critHold) {
            if (critHold) {
                return true;
            }
            // Their hit landed: hold the counter-click for the reaction delay, then release it.
            if (System.currentTimeMillis() < releaseAtMs) {
                return true;
            }
            endTrade();
            return false;
        }

        if (mode.is("Trade Strict")) {
            return true;
        }
        if (mode.is("Trade Sync")) {
            int age = mc.player.age;
            if (holdStartAge < 0) {
                holdStartAge = age;
                holdLimitTicks = Math.max(1, (int) Math.round(holdTimeoutTicks.sample()));
            }
            if (age - holdStartAge <= holdLimitTicks) {
                return true;
            }
            endTrade();
        }
        return false;
    }

    /**
     * True while the crit window is still ahead: not yet in the falling state, the server does not
     * count the player as sprinting, and the fall starts before the hold has run for Hold Timeout.
     */
    private boolean critReachable() {
        if (tradeStartAge < 0 || MeleeMechanics.isCritState(mc.player)
                || MeleeMechanics.isServerSprinting(mc.player)) {
            return false;
        }
        int budget = (int) holdTimeoutTicks.getValue() - Math.max(0, mc.player.age - tradeStartAge);
        return budget > 0 && MeleeMechanics.ticksUntilCritWindow(mc.player, budget) > 0;
    }

    private LivingEntity rayTarget() {
        if (mc.crosshairTarget instanceof EntityHitResult ehr && ehr.getEntity() instanceof LivingEntity target
                && target != mc.player && target.isAlive()) {
            return target;
        }
        return null;
    }

    private boolean isClosingIn(LivingEntity target) {
        double dx = target.getX() - mc.player.getX();
        double dz = target.getZ() - mc.player.getZ();
        net.minecraft.util.math.Vec3d motion = mc.player.getVelocity();
        return motion.x * dx + motion.z * dz > 0.0;
    }

    /**
     * True for a swing another module dispatches (it must never be withheld). TriggerBot runs
     * inside its own context but asks this module directly, so it is held.
     */
    private boolean isOtherModuleAttack() {
        Object owner = ModuleExecutionContext.currentOwner();
        if (owner != null && !(owner instanceof TriggerBot)) {
            return true;
        }
        return mc.options != null && SystemInputSimulator.isSimulatedPressed(mc.options.attackKey)
                && !(owner instanceof TriggerBot);
    }

    @Override
    public boolean shouldGateAttackInput() {
        return shouldGate();
    }

    @Override
    public boolean handleAttack() {
        return shouldGate();
    }
}
