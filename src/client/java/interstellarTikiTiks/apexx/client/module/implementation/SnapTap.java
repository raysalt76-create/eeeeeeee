package interstellarTikiTiks.apexx.client.module.implementation;

import interstellarTikiTiks.apexx.client.mixin.KeyBindingAccessor;
import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.hooks.MovementInputOverrideProvider;
import interstellarTikiTiks.apexx.client.util.ModuleAccess;
import interstellarTikiTiks.apexx.client.util.InputTracker;
import interstellarTikiTiks.apexx.client.util.SystemInputSimulator;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;

public class SnapTap extends Module {

    private final BooleanSetting lateralEnabled = new BooleanSetting("A&D", true)
            .setDescription("When A and D are both held, only the most recently pressed one moves you (vanilla cancels them out and you stop). Makes strafe switches instant.");
    private final BooleanSetting forwardBackEnabled = new BooleanSetting("S&W", true)
            .setDescription("When W and S are both held, only the most recently pressed one moves you instead of the two cancelling out.");

    private final PairState leftRightState = new PairState();
    private final PairState forwardBackState = new PairState();

    public SnapTap() {
        super("SnapTap", "Last-key-wins movement (like Razer Snap Tap): when you hold two opposite movement keys, the one you pressed last takes over instead of both cancelling, so strafe direction changes are instant. Works on the movement input only.", Category.MOVEMENT);
        addSetting(lateralEnabled);
        addSetting(forwardBackEnabled);
    }

    @Override
    public void onTick() {
        // Null during early load / between worlds — nothing to clean, and touching
        // mc.options here would throw and get the module permanently disabled.
        if (mc.options == null) return;
        if (mc.currentScreen != null) {
            mc.options.leftKey.setPressed(false);
            mc.options.rightKey.setPressed(false);
            mc.options.backKey.setPressed(false);
            mc.options.forwardKey.setPressed(false);
            leftRightState.reset();
            forwardBackState.reset();
            return;
        }
        handlePair(mc.options.leftKey, mc.options.rightKey, leftRightState, lateralEnabled.isEnabled());

        MovementInputOverrideProvider override = ModuleAccess.getEnabledHook(MovementInputOverrideProvider.class);
        if (override != null && override.isOverridingSnapTap()) {
            if (override.isSuppressingForward()) {
                mc.options.forwardKey.setPressed(false);
            }
            mc.options.backKey.setPressed(override.isStapMode());
            forwardBackState.reset();
        } else {
            handlePair(mc.options.backKey, mc.options.forwardKey, forwardBackState, forwardBackEnabled.isEnabled());
        }
    }

    @Override
    public void onDisable() {
        if (mc.options == null) return;
        restorePhysical(mc.options.leftKey);
        restorePhysical(mc.options.rightKey);
        restorePhysical(mc.options.backKey);
        restorePhysical(mc.options.forwardKey);
        leftRightState.reset();
        forwardBackState.reset();
    }

    @Override
    protected void onRuntimeStateReleased() {
        if (mc.options == null) return;
        restorePhysical(mc.options.leftKey);
        restorePhysical(mc.options.rightKey);
        restorePhysical(mc.options.backKey);
        restorePhysical(mc.options.forwardKey);
        leftRightState.reset();
        forwardBackState.reset();
    }

    private void handlePair(KeyBinding firstKey, KeyBinding secondKey, PairState state, boolean enabled) {
        if (!enabled) {
            restorePhysical(firstKey);
            restorePhysical(secondKey);
            state.reset();
            return;
        }

        boolean firstDown = isPhysicallyPressed(firstKey);
        boolean secondDown = isPhysicallyPressed(secondKey);

        state.lastFirstPress = pressOrder(firstKey);
        state.lastSecondPress = pressOrder(secondKey);

        if (firstDown && secondDown) {
            if (state.lastFirstPress > state.lastSecondPress) {
                state.active = Active.FIRST;
            } else if (state.lastSecondPress > state.lastFirstPress) {
                state.active = Active.SECOND;
            } else {
                state.active = Active.FIRST;
            }
        } else if (firstDown) {
            state.active = Active.FIRST;
        } else if (secondDown) {
            state.active = Active.SECOND;
        } else {
            state.active = Active.NONE;
        }

        firstKey.setPressed(state.active == Active.FIRST);
        secondKey.setPressed(state.active == Active.SECOND);

        state.prevFirstDown = firstDown;
        state.prevSecondDown = secondDown;
    }

    private void restorePhysical(KeyBinding key) {
        key.setPressed(isPhysicallyPressed(key));
    }

    private boolean isPhysicallyPressed(KeyBinding key) {
        return SystemInputSimulator.isPhysicallyPressed(key);
    }

    private long pressOrder(KeyBinding binding) {
        InputUtil.Key key = ((KeyBindingAccessor) binding).getBoundKey();
        if (key.getCategory() == InputUtil.Type.MOUSE) return InputTracker.getMousePressOrder(key.getCode());
        return key.getCategory() == InputUtil.Type.KEYSYM ? InputTracker.getKeyPressOrder(key.getCode()) : 0L;
    }

    private enum Active {
        NONE,
        FIRST,
        SECOND
    }

    private static class PairState {
        private boolean prevFirstDown = false;
        private boolean prevSecondDown = false;
        private long lastFirstPress = 0L;
        private long lastSecondPress = 0L;
        private Active active = Active.NONE;

        private void reset() {
            prevFirstDown = false;
            prevSecondDown = false;
            lastFirstPress = 0L;
            lastSecondPress = 0L;
            active = Active.NONE;
        }
    }
}
