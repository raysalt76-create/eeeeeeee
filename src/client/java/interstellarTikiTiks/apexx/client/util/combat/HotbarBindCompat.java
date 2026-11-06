package interstellarTikiTiks.apexx.client.util.combat;

import interstellarTikiTiks.apexx.client.module.Module;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;

/**
 * Detects a module keybind that is also one of the vanilla hotbar keys. A module bound that way must
 * not select the slot itself: vanilla's own input handling selects it (hotbar keys run before use
 * keys inside one tick), so the module only has to act afterwards and remember where to swap back.
 */
public final class HotbarBindCompat {

    private HotbarBindCompat() {
    }

    /**
     * @return the hotbar index (0-8) whose vanilla key is this module's single bound key, or -1 when
     *         the module is unbound, uses a two-key bind, or the key is not a hotbar key
     */
    public static int hotbarIndexOfBind(MinecraftClient mc, Module module) {
        if (mc == null || mc.options == null || module == null) return -1;
        int key = module.getKeyCode();
        int key2 = module.getKeyCode2();
        if (key <= 0 || (key2 != -1 && key2 != 0)) return -1;
        KeyBinding[] hotbar = mc.options.hotbarKeys;
        for (int i = 0; i < hotbar.length && i < 9; i++) {
            try {
                InputUtil.Key bound = InputUtil.fromTranslationKey(hotbar[i].getBoundKeyTranslationKey());
                if (bound.getCategory() == InputUtil.Type.KEYSYM && bound.getCode() == key) return i;
            } catch (IllegalArgumentException ignored) {
                // Unparseable binding: not a usable hotbar key.
            }
        }
        return -1;
    }
}
