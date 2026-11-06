package interstellarTikiTiks.apexx.client.module.hooks;

import com.google.gson.JsonArray;
import interstellarTikiTiks.apexx.client.gui.gpuui.framework.UiDisplayList;
import interstellarTikiTiks.apexx.client.gui.gpuui.kit.UiHost;
import net.minecraft.client.MinecraftClient;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The Premium macro and script studio (Macros tab, script IDE, macro and script runtimes). Implemented by a hidden
 * payload module that ships only in the premium payload, so the free payload carries none of it; the loader's
 * Macros/Scripts tabs, tick dispatch and cloud-blob code reach it only through this interface and treat a missing
 * implementation as "locked".
 */
public interface StudioProvider {

    /**
     * Loader-side guard so the runtimes (which register Fabric tick callbacks that cannot be removed) start once per
     * JVM even if the payload is loaded again into a fresh class loader.
     */
    AtomicBoolean RUNTIME_STARTED = new AtomicBoolean();

    /** True when this account may use the studio (Premium). */
    boolean studioAvailable();

    /** Starts the chunk-macro and script runtimes. Idempotent. */
    void startRuntime();

    /** Sign-out / self-destruct: stops the macro runtime, releases held inputs and drops IDE scripts. */
    void shutdownRuntime();

    /** Runs the step-macro runtime once per client tick. */
    void tickMacros(MinecraftClient client, boolean allowInputTriggers);

    /** A block interaction happened (block-interact macro trigger). */
    void notifyBlockInteract(String clickedBlockId, String usedBlockId);

    /** Macros in their cloud-blob JSON shape. */
    JsonArray macrosJson();

    /** IDE scripts in their cloud-blob JSON shape. */
    JsonArray scriptsJson();

    /** Replaces the macros with the user's own cloud copy. */
    void restoreMacros(JsonArray macros);

    /** Replaces the IDE scripts with the user's own cloud copy. */
    void restoreScripts(JsonArray scripts);

    /** True when any macro or IDE script is held. */
    boolean hasUserContent();

    /** The Macros tab. */
    Panel macroPanel();

    /** The Scripts (IDE) tab. */
    Panel scriptPanel();

    /** A ClickGUI page drawn by the payload. */
    interface Panel {
        void refresh();

        /** Saves pending edits (menu closing). */
        default void flush() {
        }

        boolean scroll(double mx, double my, double amount, long now);

        void draw(UiDisplayList.Builder b, UiHost host, float x, float y, float w, float h, float s, float mx, float my);
    }
}
