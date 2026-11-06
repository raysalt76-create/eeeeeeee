package interstellarTikiTiks.apexx.client.module.implementation.studio;

import com.google.gson.JsonArray;
import interstellarTikiTiks.apexx.client.Apexx;
import interstellarTikiTiks.apexx.client.ApexxAuth;
import interstellarTikiTiks.apexx.client.config.ConfigManager;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.hooks.StudioProvider;
import interstellarTikiTiks.apexx.client.module.implementation.studio.macro.MacroManager;
import interstellarTikiTiks.apexx.client.module.implementation.studio.macro.MacroView;
import interstellarTikiTiks.apexx.client.module.implementation.studio.script.ScriptManager;
import interstellarTikiTiks.apexx.client.module.implementation.studio.script.ScriptView;
import net.minecraft.client.MinecraftClient;

/**
 * Premium macro and script studio: the Macros tab, the script IDE (editor, reference, debugger) and the macro and
 * script runtimes. A hidden module so all of it ships only in the premium payload; every class it uses lives under
 * {@code studio/} and is listed in premium-only-modules.txt. The loader reaches it through {@link StudioProvider}.
 */
public class MacroStudio extends Module implements StudioProvider {

    private volatile MacroView macroView;
    private volatile ScriptView scriptView;
    private volatile JsonArray heldMacros;
    private volatile JsonArray heldScripts;

    public MacroStudio() {
        super("Macro Studio", "Macros and the script IDE (Premium).", Category.CLIENT);
        setHidden(true);
        // Saved macros/scripts the loader parked before this payload loaded (or restored from the cloud while no
        // studio was present) come back now, then the runtimes start.
        try {
            ConfigManager.getInstance().adoptParkedUserContent(this);
        } catch (Throwable ignored) {
        }
        startRuntime();
    }

    @Override
    public boolean studioAvailable() {
        return "premium".equalsIgnoreCase(ApexxAuth.getSessionTier());
    }

    @Override
    public void startRuntime() {
        if (Apexx.isTornDown() || !studioAvailable()) return;
        if (!RUNTIME_STARTED.compareAndSet(false, true)) return;
        try {
            interstellarTikiTiks.apexx.client.module.implementation.studio.macro.chunk.MacroManager.getInstance().init();
        } catch (Throwable ignored) {
        }
        try {
            ScriptManager.getInstance().init();
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void shutdownRuntime() {
        try {
            interstellarTikiTiks.apexx.client.module.implementation.studio.macro.chunk.MacroManager.getInstance().shutdown();
        } catch (Throwable ignored) {
        }
        try {
            ScriptManager.getInstance().clearLocal();
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void tickMacros(MinecraftClient client, boolean allowInputTriggers) {
        if (!studioAvailable()) return;
        MacroManager.getInstance().tick(client, allowInputTriggers);
    }

    @Override
    public void notifyBlockInteract(String clickedBlockId, String usedBlockId) {
        MacroManager.getInstance().notifyBlockInteract(clickedBlockId, usedBlockId);
    }

    @Override
    public JsonArray macrosJson() {
        JsonArray held = heldMacros;
        return held != null ? held.deepCopy() : MacroManager.getInstance().toJsonArray();
    }

    @Override
    public JsonArray scriptsJson() {
        JsonArray held = heldScripts;
        return held != null ? held.deepCopy() : ScriptManager.getInstance().localScriptsJson();
    }

    // Without Premium the saved content is kept as-is and written back, but never loaded into the runtimes
    // (restoring scripts registers them as live modules).
    @Override
    public void restoreMacros(JsonArray macros) {
        if (macros == null) return;
        if (!studioAvailable()) {
            heldMacros = macros.deepCopy();
            return;
        }
        heldMacros = null;
        MacroManager.getInstance().replaceFromCloud(macros);
    }

    @Override
    public void restoreScripts(JsonArray scripts) {
        if (scripts == null) return;
        if (!studioAvailable()) {
            heldScripts = scripts.deepCopy();
            return;
        }
        heldScripts = null;
        ScriptManager.getInstance().replaceLocalFromJson(scripts);
    }

    @Override
    public boolean hasUserContent() {
        JsonArray m = heldMacros;
        JsonArray sc = heldScripts;
        return (m != null && !m.isEmpty()) || (sc != null && !sc.isEmpty())
                || MacroManager.getInstance().size() > 0 || !ScriptManager.getInstance().localScripts().isEmpty();
    }

    @Override
    public Panel macroPanel() {
        MacroView view = macroView;
        if (view == null) {
            synchronized (this) {
                if (macroView == null) macroView = new MacroView();
                view = macroView;
            }
        }
        return view;
    }

    @Override
    public Panel scriptPanel() {
        ScriptView view = scriptView;
        if (view == null) {
            synchronized (this) {
                if (scriptView == null) scriptView = new ScriptView();
                view = scriptView;
            }
        }
        return view;
    }
}
