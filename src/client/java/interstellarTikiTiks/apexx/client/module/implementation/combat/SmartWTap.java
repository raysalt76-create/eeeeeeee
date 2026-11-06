package interstellarTikiTiks.apexx.client.module.implementation.combat;

import interstellarTikiTiks.apexx.client.mixin.KeyBindingAccessor;
import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.ModeSetting;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import interstellarTikiTiks.apexx.client.module.hooks.AttackTargetAware;
import interstellarTikiTiks.apexx.client.module.hooks.MovementInputModifier;
import interstellarTikiTiks.apexx.client.module.hooks.MovementInputOverrideProvider;
import interstellarTikiTiks.apexx.client.util.combat.MeleeMechanics;
import interstellarTikiTiks.apexx.client.util.combat.SprintResetPlan;
import interstellarTikiTiks.apexx.client.util.combat.ModuleDelays;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.Entity;
import net.minecraft.util.PlayerInput;

import java.util.concurrent.ThreadLocalRandom;

/**
 * SmartWTap - W-tap / S-tap / sprint-key sprint reset after a hit, picking the tap from spacing in Mixed mode.
 * The Sprint Key style (formerly the separate SprintReset module) withholds the sprint input for one
 * movement tick with forward kept, so vanilla sends STOP then START.
 *
 * <p>Runs on movement ticks through {@link MovementInputModifier} (durations are rounded to whole
 * ticks, minimum one) and only after a hit that cleared the server's sprint flag. It still reports
 * itself to SnapTap through {@link MovementInputOverrideProvider} so the two do not fight over the keys.
 */
public class SmartWTap extends Module implements AttackTargetAware, MovementInputOverrideProvider, MovementInputModifier {

    private final ModeSetting mode = new ModeSetting("Reset Style", "Release", "Release", "Back Tap", "Mixed", "Sprint Key")
            .setDescription("How sprint is broken after a hit. Release (W-tap): briefly lets go of forward. Back Tap (S-tap): briefly presses backward, which also stops you running into the target. Mixed: back-taps when you are close and picks randomly otherwise, so the pattern is less predictable. Sprint Key: withholds only the sprint key for exactly one tick while forward stays held, so you keep pressure (Reset Length is not used); if sprint already restarted, that tick releases forward instead.");
    private final SliderSetting resetLength = new SliderSetting("Reset Length (ms)", 50.0, 10.0, 200.0, 0)
            .withJitter(15)
            .setDescription("How long the release or back press is held, rounded to whole 50 ms ticks (always at least one). Longer resets are more reliable but slow your chase; one tick is enough on most servers.");
    private final BooleanSetting onlyOnGround = new BooleanSetting("Grounded Only", false)
            .setDescription("Skip the reset while you are in the air, e.g. during crit jumps, where breaking sprint would not help the next hit.");
    private final BooleanSetting disableInLiquids = new BooleanSetting("Disable In Liquids", false)
            .setDescription("Skip the reset while you are in water or lava, and cancel one still running when you enter them. Grounded Only is the matching air gate.");

    private final SliderSetting tapStartDelay = new SliderSetting("Start Lag (ms)", 30.0, 0.0, 200.0, 0)
            .withJitter(15)
            .setDescription("Gap between your hit landing and the start of the reset, rounded to whole ticks (a 30 ms value starts one tick later 60% of the time). 0 resets in the hit's own tick.")
            .setSection("Delays");

    private static final double MAX_REACH_DISTANCE = 3.0;
    private static final double STAP_SPACING_THRESHOLD = 2.7;

    private final SprintResetPlan plan = new SprintResetPlan();

    public SmartWTap() {
        super("SmartWTap", "After one of your sprint hits lands and clears your sprint on the server, SmartWTap briefly releases forward (W-tap) or presses backward (S-tap) so sprint restarts before your next hit and that hit deals full sprint knockback again. It only acts on hits that actually reset the server's sprint, and coordinates with SnapTap so they never fight over the movement keys.", Category.COMBAT);
        addSetting(mode);
        addSetting(resetLength);
        addSetting(onlyOnGround);
        addSetting(disableInLiquids);
        addSetting(tapStartDelay);
    }

    private boolean isLiquidGated(Entity player) {
        return disableInLiquids.isEnabled() && MeleeMechanics.isInLiquid(player);
    }

    @Override
    public void onAttack(Entity target) {
        if (target == null || mc.player == null || mc.options == null) return;
        if (!isEnabled() || plan.isActive()) return;
        if (onlyOnGround.isEnabled() && !mc.player.isOnGround()) return;
        if (isLiquidGated(mc.player)) return;

        if (mode.is("Sprint Key")) {
            // Read after MeleeMechanics recorded this hit (attackEntity HEAD, before the hooks).
            MeleeMechanics.AttackRecord sprintHit = MeleeMechanics.lastAttack();
            if (sprintHit == null || sprintHit.age() != mc.player.age || !sprintHit.clientSprintingBefore()
                    || !MeleeMechanics.needsSprintReset(mc.player)) return;
            plan.start(SprintResetPlan.Kind.SPRINT_KEY, ModuleDelays.msToTicks(tapStartDelay.sample()), 1, true);
            return;
        }

        double dist = mc.player.distanceTo(target);
        if (dist > MAX_REACH_DISTANCE) return;

        // Player must be actively pressing forward to trigger sprint reset
        if (!isForwardKeyDown()) return;

        // Only a hit that cleared the server's sprint needs a reset.
        MeleeMechanics.AttackRecord hit = MeleeMechanics.lastAttack();
        if (hit == null || hit.age() != mc.player.age || !MeleeMechanics.needsSprintReset(mc.player)) return;

        boolean isStap;
        if (mode.is("Mixed")) {
            if (dist < STAP_SPACING_THRESHOLD) {
                isStap = true;
            } else {
                isStap = ThreadLocalRandom.current().nextBoolean();
            }
        } else if (mode.is("Back Tap")) {
            isStap = true;
        } else {
            isStap = false;
        }

        double durationMs = resetLength.sample();
        plan.start(isStap ? SprintResetPlan.Kind.S_TAP : SprintResetPlan.Kind.W_TAP,
                ModuleDelays.msToTicks(tapStartDelay.sample()),
                Math.max(1, (int) Math.round(durationMs / 50.0)),
                hit.clientSprintingBefore());
    }

    @Override
    public void onTick() {
        if (plan.isActive() && (mc.player == null || mc.currentScreen != null || !mc.player.isAlive()
                || isLiquidGated(mc.player))) {
            plan.cancel();
        }
    }

    @Override
    public void onEnable() {
        plan.cancel();
    }

    @Override
    public PlayerInput modifyMovementInput(MinecraftClient client, PlayerInput input) {
        if (!isEnabled() || !plan.isActive() || client.player == null) {
            return input;
        }
        // Read on the movement tick itself: entering liquid mid-reset cancels the plan and leaves
        // the player's own input untouched (the plan never holds a key, so nothing stays pressed).
        if (isLiquidGated(client.player)) {
            plan.cancel();
            return input;
        }
        return SprintResetPlan.apply(plan.next(client.player.isSprinting()), input);
    }

    @Override
    public void onDisable() {
        plan.cancel();
    }

    @Override
    protected void onRuntimeStateReleased() {
        plan.cancel();
    }

    @Override
    public boolean isOverridingSnapTap() {
        return isEnabled() && plan.isHolding();
    }

    /** A post-hit sprint reset is planned or running; AutoSprint holds off its own sprint taps meanwhile. */
    public boolean isResetActive() {
        return isEnabled() && plan.isActive();
    }

    @Override
    public boolean isSuppressingForward() {
        return isEnabled() && plan.isHolding();
    }

    @Override
    public boolean isStapMode() {
        return plan.isHolding() && plan.kind() == SprintResetPlan.Kind.S_TAP;
    }

    private boolean isForwardKeyDown() {
        if (mc.options == null || mc.getWindow() == null) return false;
        int code = ((KeyBindingAccessor) mc.options.forwardKey).getBoundKey().getCode();
        if (code < 0) return false;
        return InputUtil.isKeyPressed(mc.getWindow(), code);
    }
}
