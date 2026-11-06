package interstellarTikiTiks.apexx.client.module.implementation;

import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.ConfigKeybindListSetting;
import interstellarTikiTiks.apexx.client.module.Module;

/**
 * Holds the list of config-swap hotkeys. The hotkeys themselves are polled and
 * applied in {@code MinecraftClientMixin}, which iterates
 * {@code ConfigKeybindManager} directly and therefore fires regardless of this
 * module's enabled state — binding a config and pressing its key always swaps,
 * even when this module shows as "off". {@link #shouldTickWhenDisabled()} is
 * overridden so that intent is explicit and survives any future move of the
 * polling logic into the module's own tick.
 */
public class ConfigBind extends Module {
    private final ConfigKeybindListSetting configKeybinds = new ConfigKeybindListSetting("Config Keybinds");

    public ConfigBind() {
        super("ConfigBind", "Assign hotkeys to your saved configs so a single key press loads a whole setup mid-game (for example, one config per server or game mode).", Category.CLIENT);
        setHidden(true); // client/config utility — lives under the settings gear, not the module list
        addSetting(configKeybinds);
    }

    /**
     * Config-swap hotkeys are always live, independent of the toggle state, so
     * this module never needs to be enabled for its binds to work.
     */
    @Override
    public boolean shouldTickWhenDisabled() {
        return true;
    }
}
