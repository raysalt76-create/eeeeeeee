package interstellarTikiTiks.apexx.client.config.hub;

import interstellarTikiTiks.apexx.client.config.CloudConfigs;
import interstellarTikiTiks.apexx.client.config.ConfigManager;
import net.minecraft.client.MinecraftClient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * PrivateConfigManager — wired to CloudConfigs (/api/config).
 * Connects the in-game ConfigScreen to cloud-backed configs.
 */
public class PrivateConfigManager {

    public static final String AUTOSAVE_SLUG = "__autosave__";
    private static final PrivateConfigManager INSTANCE = new PrivateConfigManager();
    private static final ExecutorService POOL = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "apexx-private-config-hub");
        t.setDaemon(true);
        return t;
    });

    public static PrivateConfigManager getInstance() {
        return INSTANCE;
    }

    private static void runOnClient(Runnable r) {
        MinecraftClient mc;
        try {
            mc = MinecraftClient.getInstance();
        } catch (Throwable unavailable) {
            return;
        }
        if (mc == null) return;
        try {
            if (mc.isOnThread()) r.run();
            else mc.execute(r);
        } catch (Throwable rejected) {
            // The client is stopping; no UI callback can be delivered safely.
        }
    }

    public void autoSave(String localConfigName, Runnable onSuccess, Consumer<String> onError) {
        POOL.execute(() -> {
            try {
                String json = ConfigManager.getInstance().exportConfigJsonForCloud(localConfigName);
                if (json == null || json.isBlank()) {
                    runOnClient(() -> onError.accept("Local config is empty"));
                    return;
                }
                String id = CloudConfigs.saveNamed(localConfigName, json, false);
                if (id != null) {
                    runOnClient(onSuccess::run);
                } else {
                    String err = CloudConfigs.lastError();
                    runOnClient(() -> onError.accept(err != null ? err : "AutoSave failed"));
                }
            } catch (Throwable t) {
                runOnClient(() -> onError.accept(t.getMessage()));
            }
        });
    }

    public void autoLoad(boolean applyNow, Consumer<Boolean> onSuccess, Consumer<String> onError) {
        POOL.execute(() -> {
            try {
                String[] got = CloudConfigs.getOwnByName("default");
                if (got != null && got.length > 1) {
                    if (applyNow) {
                        boolean accepted = ConfigManager.getInstance().importConfigJsonForCloud(
                                "default", got[1], true, onSuccess);
                        if (!accepted) runOnClient(() -> onSuccess.accept(false));
                    } else {
                        boolean ok = ConfigManager.getInstance().importConfigJsonForCloud("default", got[1], false);
                        runOnClient(() -> onSuccess.accept(ok));
                    }
                } else {
                    runOnClient(() -> onSuccess.accept(false));
                }
            } catch (Throwable t) {
                runOnClient(() -> onSuccess.accept(false));
            }
        });
    }

    public void saveConfig(String slug, String configJson,
            Runnable onSuccess, Consumer<String> onError) {
        POOL.execute(() -> {
            try {
                String id = CloudConfigs.saveNamed(slug, configJson, false);
                if (id != null) {
                    runOnClient(onSuccess::run);
                } else {
                    String err = CloudConfigs.lastError();
                    runOnClient(() -> onError.accept(err != null ? err : "Failed to save cloud config"));
                }
            } catch (Throwable t) {
                runOnClient(() -> onError.accept(t.getMessage()));
            }
        });
    }

    /** Save the named config and make it the active cloud profile for future autosaves. */
    public void saveActiveConfig(String name, String configJson,
            Runnable onSuccess, Consumer<String> onError) {
        POOL.execute(() -> {
            try {
                String id = CloudConfigs.saveNamed(name, configJson, false);
                if (id == null) {
                    String err = CloudConfigs.lastError();
                    runOnClient(() -> onError.accept(err != null ? err : "Failed to save cloud config"));
                    return;
                }
                // The live state now matches this config: it is the Overwrite target and nothing is unsaved. Marked
                // before activation so the working-copy resync that activation triggers already names it.
                ConfigManager.getInstance().markSavedAs(name, configJson);
                if (!CloudConfigs.setActive(id, configJson)) {
                    String err = CloudConfigs.lastError();
                    runOnClient(() -> onError.accept(err != null ? err : "Config saved, but could not activate it"));
                    return;
                }
                runOnClient(onSuccess::run);
            } catch (Throwable t) {
                runOnClient(() -> onError.accept(t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage()));
            }
        });
    }

    /**
     * Overwrite the loaded named config with the current settings (the only way, besides Save, that a named config
     * changes: automatic saves go to the hidden working slot). Call on the client thread; callbacks run there too and
     * {@code onSuccess} gets the config's name.
     */
    public void overwriteLoaded(Consumer<String> onSuccess, Consumer<String> onError) {
        ConfigManager manager = ConfigManager.getInstance();
        String name = manager.getLoadedConfigName();
        if (name == null) {
            runOnClient(() -> onError.accept("No config is loaded. Save the settings as a new config instead."));
            return;
        }
        if (!CloudConfigs.signedIn()) {
            runOnClient(() -> onError.accept("Sign in to save configs."));
            return;
        }
        String json = manager.exportCurrentConfigJsonForCloud();
        if (json == null || json.isBlank()) {
            runOnClient(() -> onError.accept("The current settings could not be exported"));
            return;
        }
        POOL.execute(() -> {
            try {
                // By name: the active id can still be the previous profile while a load waits for the menu to close.
                boolean saved = CloudConfigs.saveNamed(name, json, false) != null;
                if (!saved) {
                    String err = CloudConfigs.lastError();
                    runOnClient(() -> onError.accept(err != null ? err : "Could not save the config"));
                    return;
                }
                runOnClient(() -> {
                    manager.markSavedAs(name, json);
                    // Record the now-clean state in the working copy too, so a restart shows no unsaved changes.
                    String working = manager.exportCurrentConfigJsonForCloud();
                    if (working != null) interstellarTikiTiks.apexx.client.config.CloudConfigSync.saveIfChangedNow(working);
                    onSuccess.accept(name);
                });
            } catch (Throwable t) {
                String message = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
                runOnClient(() -> onError.accept(message));
            }
        });
    }

    /** Activate a profile after the GUI closes without sending its config contents. */
    public void activateConfig(String name, Runnable onSuccess, Consumer<String> onError) {
        POOL.execute(() -> {
            try {
                if (CloudConfigs.setActiveByName(name)) {
                    runOnClient(onSuccess::run);
                } else {
                    String err = CloudConfigs.lastError();
                    runOnClient(() -> onError.accept(err != null ? err : "Could not activate cloud config"));
                }
            } catch (Throwable t) {
                String message = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
                runOnClient(() -> onError.accept(message));
            }
        });
    }

    public void listConfigs(Consumer<List<PrivateConfigInfo>> onSuccess,
            Consumer<String> onError) {
        POOL.execute(() -> {
            try {
                if (!CloudConfigs.signedIn()) {
                    runOnClient(() -> onError.accept("Not signed in"));
                    return;
                }
                List<CloudConfigs.Entry> entries = CloudConfigs.list();
                List<PrivateConfigInfo> list = new ArrayList<>();
                for (CloudConfigs.Entry e : entries) {
                    if (CloudConfigs.isWorkingSlot(e.name())) continue; // the hidden working copy is not a config
                    list.add(new PrivateConfigInfo(e.name(), e.gameMode(), e.active(), 0L, e.id(), e.isPublic(), e.tags()));
                }
                runOnClient(() -> onSuccess.accept(list));
            } catch (Throwable t) {
                runOnClient(() -> onError.accept(t.getMessage()));
            }
        });
    }

    public void fetchConfig(String slug, Consumer<String> onSuccess,
            Consumer<String> onError) {
        POOL.execute(() -> {
            try {
                String[] got = CloudConfigs.getOwnByName(slug);
                if (got != null && got.length > 1) {
                    runOnClient(() -> onSuccess.accept(got[1]));
                } else {
                    String err = CloudConfigs.lastError();
                    runOnClient(() -> onError.accept(err != null ? err : "Config not found"));
                }
            } catch (Throwable t) {
                runOnClient(() -> onError.accept(t.getMessage()));
            }
        });
    }

    public void loadConfig(String slug, boolean applyNow,
            Consumer<Boolean> onSuccess, Consumer<String> onError) {
        POOL.execute(() -> {
            try {
                String[] got = CloudConfigs.getOwnByName(slug);
                if (got != null && got.length > 1) {
                    if (applyNow) {
                        boolean accepted = ConfigManager.getInstance().importConfigJsonForCloud(
                                slug, got[1], true, imported -> {
                                    if (imported) onSuccess.accept(true);
                                    else onError.accept("Invalid config format");
                                });
                        if (!accepted) runOnClient(() -> onError.accept("Invalid config format"));
                    } else if (ConfigManager.getInstance().importConfigJsonForCloud(slug, got[1], false)) {
                        runOnClient(() -> onSuccess.accept(true));
                    } else {
                        runOnClient(() -> onError.accept("Invalid config format"));
                    }
                } else {
                    String err = CloudConfigs.lastError();
                    runOnClient(() -> onError.accept(err != null ? err : "Config not found"));
                }
            } catch (Throwable t) {
                runOnClient(() -> onError.accept(t.getMessage()));
            }
        });
    }

    public void deleteConfig(String slug, Runnable onSuccess,
            Consumer<String> onError) {
        POOL.execute(() -> {
            try {
                boolean ok = CloudConfigs.deleteNamed(slug);
                if (ok) {
                    runOnClient(onSuccess::run);
                } else {
                    String err = CloudConfigs.lastError();
                    runOnClient(() -> onError.accept(err != null ? err : "Failed to delete cloud config"));
                }
            } catch (Throwable t) {
                runOnClient(() -> onError.accept(t.getMessage()));
            }
        });
    }

    public void generateShareCode(String slug, Consumer<String> onSuccess,
            Consumer<String> onError) {
        POOL.execute(() -> {
            try {
                String id = CloudConfigs.idForName(slug);
                String[] stored = id == null ? null : CloudConfigs.getOwn(id);
                boolean hasPrivate = stored != null && stored.length > 1
                        && interstellarTikiTiks.apexx.client.config.UserContentBlob.hasContent(stored[1]);
                String activeId = interstellarTikiTiks.apexx.client.config.CloudConfigSync.getActiveConfigId();
                if (hasPrivate || (id != null && id.equals(activeId) && ConfigManager.getInstance().hasLiveUserContent())
                        || interstellarTikiTiks.apexx.client.config.UserContentBlob.hasContent(
                                id == null ? ConfigManager.getInstance().exportConfigJsonForCloud(slug) : null)) {
                    runOnClient(() -> onError.accept(ConfigHubManager.SHARE_BLOCKED_MESSAGE));
                    return;
                }
                if (id == null) {
                    String json = ConfigManager.getInstance().exportConfigJsonForCloud(slug);
                    if (json != null) {
                        id = CloudConfigs.saveNamed(slug, json, true);
                    }
                } else {
                    CloudConfigs.setPublic(slug, true);
                }
                if (id != null) {
                    final String shareId = id;
                    runOnClient(() -> onSuccess.accept(shareId));
                } else {
                    String err = CloudConfigs.lastError();
                    runOnClient(() -> onError.accept(err != null ? err : "Failed to share config"));
                }
            } catch (Throwable t) {
                runOnClient(() -> onError.accept(t.getMessage()));
            }
        });
    }

    public void fetchByShareCode(String code, Consumer<String> onSuccess,
            Consumer<String> onError) {
        POOL.execute(() -> {
            try {
                String[] got = CloudConfigs.getPublic(code);
                if (got != null && got.length > 1) {
                    runOnClient(() -> onSuccess.accept(got[1]));
                } else {
                    String err = CloudConfigs.lastError();
                    runOnClient(() -> onError.accept(err != null ? err : "Public config not found"));
                }
            } catch (Throwable t) {
                runOnClient(() -> onError.accept(t.getMessage()));
            }
        });
    }

    /** True for text that can be a share code (a cloud config id): 4-64 letters, digits, '-' or '_'. */
    public static boolean isShareCode(String code) {
        return code != null && code.trim().matches("[A-Za-z0-9_-]{4,64}");
    }

    /**
     * Import a config someone shared by its code: looks it up, copies it into this account as
     * "<name> (copy)", applies it and makes it the active profile (the same flow as "Download and use"
     * in the community hub, so the active profile is never overwritten). Their macros, scripts and
     * per-user prefs are scrubbed. Callbacks run on the client thread; {@code onSuccess} gets the new name.
     */
    public void importByShareCode(String code, boolean keepOriginalKeybinds, Consumer<String> onSuccess,
            Consumer<String> onError) {
        if (!isShareCode(code)) {
            runOnClient(() -> onError.accept("That is not a share code"));
            return;
        }
        String trimmed = code.trim();
        POOL.execute(() -> {
            try {
                String[] got = CloudConfigs.getPublic(trimmed);
                if (got == null || got.length < 2) {
                    String err = CloudConfigs.lastError();
                    runOnClient(() -> onError.accept(err != null && !err.isBlank()
                            ? "No shared config has that code (" + err + ")" : "No shared config has that code"));
                    return;
                }
                RemoteConfig config = new RemoteConfig();
                config.id = trimmed;
                config.name = got[0] == null || got[0].isBlank() ? "Shared config" : got[0];
                runOnClient(() -> new ConfigHubManager().downloadConfig(config, keepOriginalKeybinds, onSuccess, onError));
            } catch (Throwable t) {
                String message = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
                runOnClient(() -> onError.accept(message));
            }
        });
    }

    public void saveConfigBlocking(String slug, String configJson) {
        CloudConfigs.saveNamed(slug, configJson, false);
    }

    public static class PrivateConfigInfo {
        public final String slug;
        public final String gameMode;
        public final boolean active;
        public final long updatedAt;
        public final String id;
        public final boolean isPublic;
        public final java.util.List<String> tags;
        public PrivateConfigInfo(String slug, long updatedAt) {
            this(slug, "Other", false, updatedAt);
        }
        public PrivateConfigInfo(String slug, String gameMode, boolean active, long updatedAt) {
            this(slug, gameMode, active, updatedAt, "", false, java.util.List.of());
        }
        public PrivateConfigInfo(String slug, String gameMode, boolean active, long updatedAt,
                String id, boolean isPublic, java.util.List<String> tags) {
            this.slug = slug;
            this.gameMode = gameMode == null || gameMode.isBlank() ? "Other" : gameMode;
            this.active = active;
            this.updatedAt = updatedAt;
            this.id = id == null ? "" : id;
            this.isPublic = isPublic;
            this.tags = tags == null ? java.util.List.of() : java.util.List.copyOf(tags);
        }
    }
}
