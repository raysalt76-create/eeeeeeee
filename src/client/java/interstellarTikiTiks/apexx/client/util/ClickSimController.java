package interstellarTikiTiks.apexx.client.util;

import interstellarTikiTiks.apexx.client.mixin.KeyBindingAccessor;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.mixin.MinecraftClientAccessor;
import interstellarTikiTiks.apexx.client.util.combat.SubTickSequencer;
import net.minecraft.client.MinecraftClient;

public final class ClickSimController {

    private ClickSimController() {
    }

    /**
     * True when the module running right now has its own "Click Sim" toggle on. That toggle is the
     * only opt-in for real input events (GLFW callbacks and visible holds), in either execution mode;
     * there is no client-wide switch. Work outside a module (vanilla input, no owner) never simulates.
     */
    public static boolean isEnabled() {
        return isEnabledFor(ModuleExecutionContext.currentOwner());
    }

    /** True when {@code owner} is a module whose own "Click Sim" toggle is on. */
    public static boolean isEnabledFor(Object owner) {
        return owner instanceof Module module && module.isClickSimEnabled();
    }

    private static boolean shouldDelayAfterSlotSwitch() {
        if (!SubTickSequencer.isVariationEnabled()) {
            return false;
        }
        long lastSwitch = SubTickSequencer.getLastSlotSwitchTimeMs();
        if (lastSwitch <= 0L) {
            return false;
        }
        long elapsed = System.currentTimeMillis() - lastSwitch;
        return elapsed < SubTickSequencer.getMinMicroDelayMs() && SubTickSequencer.remainingTickBudgetMs() > 2.0;
    }

    public static void attack(MinecraftClient mc) {
        attack(mc, false);
    }

    /**
     * True when a world attack/use must not be dispatched.
     */
    private static boolean screenBlocksWorldAction(MinecraftClient mc) {
        return mc == null || mc.currentScreen != null;
    }

    public static void attack(MinecraftClient mc, boolean forceSim) {
        if (shouldDelayAfterSlotSwitch()) {
            double delay = SubTickSequencer.sampleDefaultMicroDelayMs();
            SubTickSequencer.queueAction(() -> attackNow(mc, forceSim), delay);
            return;
        }
        attackNow(mc, forceSim);
    }

    public static void attackNow(MinecraftClient mc) {
        attackNow(mc, false);
    }

    public static void attackNow(MinecraftClient mc, boolean forceSim) {
        if (screenBlocksWorldAction(mc)) {
            return;
        }
        if (!AttackValidator.emitAttackClick(mc)) return;
        if (mc instanceof MinecraftClientAccessor accessor) {
            interstellarTikiTiks.apexx.client.util.ExecutionMode.relaxAttackCooldown(accessor);
            AttackValidator.invokeVanillaAttack(mc);
        }
        if (mc.options != null && mc.options.attackKey instanceof KeyBindingAccessor attackKey) {
            attackKey.setTimesPressed(0);
        }
    }

    public static void use(MinecraftClient mc) {
        use(mc, false);
    }

    public static void use(MinecraftClient mc, boolean forceSim) {
        if (shouldDelayAfterSlotSwitch()) {
            double delay = SubTickSequencer.sampleDefaultMicroDelayMs();
            SubTickSequencer.queueAction(() -> useNow(mc, forceSim), delay);
            return;
        }
        useNow(mc, forceSim);
    }

    public static void useNow(MinecraftClient mc) {
        useNow(mc, false);
    }

    public static void useNow(MinecraftClient mc, boolean forceSim) {
        if (screenBlocksWorldAction(mc) || mc.player == null || mc.world == null
                || !interstellarTikiTiks.apexx.client.util.combat.InteractSim.useReady(mc)) {
            return;
        }
        if (isEnabled() && !SystemInputSimulator.tapUse()) return;
        interstellarTikiTiks.apexx.client.util.combat.SlotSync.paceAfterSwap();
        if (mc instanceof MinecraftClientAccessor accessor) {
            accessor.useItem();
            RotationManager.markActionDispatched(ModuleExecutionContext.currentOwner());
        }
        if (mc.options != null && mc.options.useKey instanceof KeyBindingAccessor useKey) {
            useKey.setTimesPressed(0);
        }
    }

    public static void pulseAttack(MinecraftClient mc) {
        pulseAttack(mc, false);
    }

    public static void pulseAttack(MinecraftClient mc, boolean forceSim) {
        // A pulse is a key press for vanilla's input handling, which already runs after a slot
        // switch made at the tick head. It is never queued: a render frame would only reach the
        // input handling of the next tick, one tick after the switch.
        pulseAttackNow(mc, forceSim);
    }

    public static void pulseAttackNow(MinecraftClient mc) {
        pulseAttackNow(mc, false);
    }

    public static void pulseAttackNow(MinecraftClient mc, boolean forceSim) {
        if (screenBlocksWorldAction(mc) || mc.options == null) {
            return;
        }
        if (!AttackValidator.emitAttackClick(mc)) return;
        if (mc.options.attackKey instanceof KeyBindingAccessor attackKey) {
            if (attackKey.getTimesPressed() <= 0) {
                attackKey.setTimesPressed(1);
            }
        }
    }

    public static void pulseUse(MinecraftClient mc) {
        pulseUse(mc, false);
    }

    public static void pulseUse(MinecraftClient mc, boolean forceSim) {
        // Never queued, for the same reason as pulseAttack.
        pulseUseNow(mc, forceSim);
    }

    public static void pulseUseNow(MinecraftClient mc) {
        pulseUseNow(mc, false);
    }

    public static void pulseUseNow(MinecraftClient mc, boolean forceSim) {
        if (screenBlocksWorldAction(mc) || mc.options == null || mc.player == null
                || !interstellarTikiTiks.apexx.client.util.combat.InteractSim.useReady(mc)) {
            return;
        }
        if (isEnabled() && !SystemInputSimulator.tapUse()) return;
        if (mc.options.useKey instanceof KeyBindingAccessor useKey) {
            if (useKey.getTimesPressed() <= 0) {
                useKey.setTimesPressed(1);
            }
        }
    }
}
