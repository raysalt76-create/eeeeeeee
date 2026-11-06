package interstellarTikiTiks.apexx.client.module.implementation;

import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.util.ClientWiper;

/**
 * Removes the client's on-disk footprint on demand.
 *
 * <p>Has no options of its own: it runs with the Self-Destruct tab's options (ClickGUI's Ocean Safe, Wipe Config,
 * Delete Jar, Replace Jar, Decoy URL), so a key-bound trigger and the tab's button always do the same thing.</p>
 */
public class SelfDestruct extends Module {

    public SelfDestruct() {
        super("SelfDestruct", "Deactivate or wipe the client on demand.", Category.CLIENT);
        setHidden(true); // client/config utility: lives in the Self-Destruct tab, not the module list
    }

    @Override
    public boolean isOneShot() {
        return true;
    }

    @Override
    public void onEnable() {
        runConfigured();
    }

    private static boolean on(BooleanSetting setting, boolean fallback) {
        return setting == null ? fallback : setting.isEnabled();
    }

    /**
     * Runs the self-destruct with the Self-Destruct tab's options. Ocean Safe only deactivates in memory (no file is
     * touched); otherwise the wipe runs off the client thread, since it does blocking IO and post-exit scheduling.
     */
    public static void runConfigured() {
        if (on(ClickGUIModule.sdOceanSafe, false)) {
            ClientWiper.inMemoryDeactivate();
            return;
        }
        boolean del = on(ClickGUIModule.sdDeleteJar, false);
        boolean rep = on(ClickGUIModule.sdReplaceJar, true) && !del;
        boolean wipe = on(ClickGUIModule.sdWipeConfig, true);
        String url = ClickGUIModule.sdDecoyUrl != null ? ClickGUIModule.sdDecoyUrl.getValue() : null;
        Thread t = new Thread(() -> ClientWiper.selfDestruct(wipe, del, rep, url), "SelfDestruct");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Programmatic self-destruct trigger.
     *
     * @param wipeConfig whether to wipe config directories and tokens.
     * @param deleteJar  whether to delete the jar on process exit.
     */
    public static void trigger(boolean wipeConfig, boolean deleteJar) {
        ClientWiper.selfDestruct(wipeConfig, deleteJar);
    }

    /**
     * Programmatic in-memory safe trigger: nothing on disk is touched.
     */
    public static void triggerInMemory() {
        ClientWiper.inMemoryDeactivate();
    }

    /**
     * Fatal security trigger: wipes all credentials and files, schedules jar deletion,
     * and exits immediately via System.exit(0).
     */
    public static void triggerAndExit() {
        ClientWiper.selfDestructAndExit();
    }
}
