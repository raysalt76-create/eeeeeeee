package interstellarTikiTiks.apexx.client.config.hub;

import interstellarTikiTiks.apexx.client.config.CloudConfigSync;
import interstellarTikiTiks.apexx.client.config.CloudConfigs;
import interstellarTikiTiks.apexx.client.config.UserContentBlob;
import interstellarTikiTiks.apexx.client.config.ConfigManager;
import net.minecraft.client.MinecraftClient;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Community-facing operations backed by the shared {@code /api/config} endpoint. */
public class ConfigHubManager {
    private static final ExecutorService POOL = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "apexx-config-hub");
        thread.setDaemon(true);
        return thread;
    });

    private volatile List<RemoteConfig> cachedConfigs = List.of();
    private volatile ConfigHubProfile cachedProfile;

    private static void runOnClient(Runnable action) {
        MinecraftClient client;
        try {
            client = MinecraftClient.getInstance();
        } catch (Throwable unavailable) {
            return;
        }
        if (client == null) return;
        try {
            if (client.isOnThread()) action.run();
            else client.execute(action);
        } catch (Throwable rejected) {
            // The client is stopping; no UI callback can be delivered safely.
        }
    }

    public void fetchConfigs(Consumer<List<RemoteConfig>> onSuccess, Consumer<String> onError) {
        fetchConfigs(null, onSuccess, onError);
    }

    /**
     * As {@link #fetchConfigs(Consumer, Consumer)}, but with an optional username search run
     * server-side - the default browse list only ever sees the first page of public configs, which
     * can silently miss a specific user's configs once there are enough public configs to fill it.
     */
    public void fetchConfigs(String username, Consumer<List<RemoteConfig>> onSuccess, Consumer<String> onError) {
        fetchConfigs(username, null, onSuccess, onError);
    }

    /** As above, ordered by {@code sort} ({@link CloudConfigs#SORT_POPULAR}, {@link CloudConfigs#SORT_TOP}, newest). */
    public void fetchConfigs(String username, String sort, Consumer<List<RemoteConfig>> onSuccess, Consumer<String> onError) {
        POOL.execute(() -> {
            try {
                List<RemoteConfig> configs = new ArrayList<>();
                boolean searching = username != null && !username.isBlank();
                List<CloudConfigs.Entry> publicEntries = searching
                        ? CloudConfigs.listPublic(300, null, username, sort)
                        : CloudConfigs.listPublic(100, null, null, sort);
                if (CloudConfigs.lastError() != null) {
                    runOnClient(() -> onError.accept(CloudConfigs.lastError()));
                    return;
                }
                // Best-effort: which of these does the signed-in user own? (enables Delete / Make Private)
                java.util.Set<String> ownIds = new java.util.HashSet<>();
                try {
                    if (CloudConfigs.signedIn()) {
                        for (CloudConfigs.Entry own : CloudConfigs.list()) {
                            if (own.id() != null) ownIds.add(own.id());
                        }
                    }
                } catch (Throwable ignored) {
                    // Ownership marking is optional; a failure just hides the owner-only actions.
                }
                for (CloudConfigs.Entry entry : publicEntries) {
                    RemoteConfig remote = new RemoteConfig();
                    remote.id = entry.id();
                    remote.name = entry.name();
                    remote.author = entry.ownerName();
                    remote.gameMode = entry.gameMode();
                    remote.description = entry.description();
                    remote.fileName = entry.name();
                    remote.isPublic = true;
                    remote.owned = entry.id() != null && ownIds.contains(entry.id());
                    remote.tags = new ArrayList<>(entry.tags() == null ? List.of() : entry.tags());
                    remote.ratingAvg = entry.ratingAvg();
                    remote.ratingCount = entry.ratingCount();
                    remote.weeklyScore = entry.weeklyScore();
                    remote.myRating = entry.myRating();
                    configs.add(remote);
                }
                cachedConfigs = List.copyOf(configs);
                runOnClient(() -> onSuccess.accept(cachedConfigs));
            } catch (Throwable error) {
                runOnClient(() -> onError.accept(message(error)));
            }
        });
    }

    public void downloadConfig(RemoteConfig config, Consumer<String> onSuccess, Consumer<String> onError) {
        downloadConfig(config, false, onSuccess, onError);
    }

    /** As {@link #downloadConfig(RemoteConfig, Consumer, Consumer)}, optionally keeping this client's own module keybinds. */
    public void downloadConfig(RemoteConfig config, boolean keepOriginalKeybinds, Consumer<String> onSuccess, Consumer<String> onError) {
        if (config == null || config.id == null || config.id.isBlank()) {
            onError.accept("Choose a community config first");
            return;
        }
        POOL.execute(() -> {
            try {
                String localName = uniqueImportedName(config.name);
                String id = CloudConfigs.importPublic(config.id, localName);
                if (id == null || id.isBlank()) {
                    fail(onError, "Could not copy config: " + errorOf("Import failed"));
                    return;
                }
                String[] remote = CloudConfigs.getOwn(id);
                if (remote == null || remote.length < 2) {
                    CloudConfigs.deleteNamed(localName);
                    fail(onError, "The downloaded config could not be applied");
                    return;
                }
                // The server copy of someone else's public config may carry their macros/scripts and
                // per-user prefs (server map, keys). Scrub them so they can never be applied later as if
                // they were this user's own active blob.
                String scrubbed = interstellarTikiTiks.apexx.client.config.ClientPrefs.stripJson(
                        UserContentBlob.stripJson(remote[1]));
                if (scrubbed != null && !scrubbed.equals(remote[1])) {
                    remote[1] = scrubbed;
                    CloudConfigs.saveNamed(localName, remote[1], false);
                }
                boolean accepted = ConfigManager.getInstance().importConfigJsonForCloud(
                        localName, remote[1], true, keepOriginalKeybinds, imported -> {
                            if (!imported) {
                                POOL.execute(() -> CloudConfigs.deleteNamed(localName));
                                onError.accept("The downloaded config could not be applied");
                                return;
                            }
                            POOL.execute(() -> {
                                try {
                                    if (!CloudConfigs.setActive(id, remote[1])) {
                                        fail(onError, "Config was downloaded, but activation failed: "
                                                + errorOf("Activation failed"));
                                        return;
                                    }
                                    runOnClient(() -> onSuccess.accept(localName));
                                } catch (Throwable error) {
                                    fail(onError, message(error));
                                }
                            });
                        });
                if (!accepted) {
                    CloudConfigs.deleteNamed(localName);
                    fail(onError, "The downloaded config could not be applied");
                    return;
                }
            } catch (Throwable error) {
                fail(onError, message(error));
            }
        });
    }

    /** Delete one of the signed-in user's own cloud configs. The worker enforces ownership. */
    public void deleteConfig(RemoteConfig config, Consumer<String> onSuccess, Consumer<String> onError) {
        if (config == null || config.id == null || config.id.isBlank()) {
            onError.accept("Choose a config first");
            return;
        }
        if (!config.owned) {
            onError.accept("You can only delete your own configs");
            return;
        }
        POOL.execute(() -> {
            try {
                if (CloudConfigs.deleteById(config.id)) {
                    runOnClient(() -> onSuccess.accept("Deleted '" + config.name + "'"));
                } else {
                    fail(onError, "Delete failed: " + errorOf("Cloud rejected the delete"));
                }
            } catch (Throwable error) {
                fail(onError, message(error));
            }
        });
    }

    public static final String SHARE_BLOCKED_MESSAGE = "Remove your macros and scripts before sharing this config";

    /** True when the stored copy of this config, or the live state when it is the active config, has macros/scripts. */
    private static boolean blockedByUserContent(String configId) {
        String[] stored = CloudConfigs.getOwn(configId);
        if (stored != null && stored.length > 1 && UserContentBlob.hasContent(stored[1])) return true;
        String active = CloudConfigSync.getActiveConfigId();
        return active != null && active.equals(configId) && ConfigManager.getInstance().hasLiveUserContent();
    }

    /** Make one of the signed-in user's own configs public or private. The worker enforces ownership. */
    public void setVisibility(RemoteConfig config, boolean makePublic, Consumer<String> onSuccess, Consumer<String> onError) {
        if (config == null || config.id == null || config.id.isBlank()) {
            onError.accept("Choose a config first");
            return;
        }
        if (!config.owned) {
            onError.accept("You can only change your own configs");
            return;
        }
        POOL.execute(() -> {
            try {
                if (makePublic && blockedByUserContent(config.id)) {
                    fail(onError, SHARE_BLOCKED_MESSAGE);
                    return;
                }
                if (CloudConfigs.setPublicById(config.id, makePublic)) {
                    runOnClient(() -> onSuccess.accept("'" + config.name + "' is now " + (makePublic ? "public" : "private")));
                } else {
                    fail(onError, "Visibility change failed: " + errorOf("Cloud rejected the change"));
                }
            } catch (Throwable error) {
                fail(onError, message(error));
            }
        });
    }

    /**
     * Rate someone else's public config 1-5 stars (0 clears your rating). One rating per player per config; the
     * server keeps the average. On success {@code config}'s rating fields are updated before {@code onSuccess}.
     */
    public void rate(RemoteConfig config, int stars, Consumer<String> onSuccess, Consumer<String> onError) {
        if (config == null || config.id == null || config.id.isBlank()) { onError.accept("Choose a config first"); return; }
        if (config.owned) { onError.accept("You cannot rate your own config"); return; }
        if (stars < 0 || stars > 5) { onError.accept("Pick 1 to 5 stars"); return; }
        if (!CloudConfigs.signedIn()) { onError.accept("Sign in to rate configs"); return; }
        POOL.execute(() -> {
            try {
                double[] result = CloudConfigs.rate(config.id, stars);
                if (result == null) {
                    String err = errorOf("Cloud rejected the rating");
                    String reason = err.contains("RATE_LIMITED") ? "You are rating too fast, wait a minute"
                            : err.contains("OWN_CONFIG") ? "You cannot rate your own config" : "Rating failed: " + err;
                    fail(onError, reason);
                    return;
                }
                runOnClient(() -> {
                    config.myRating = stars;
                    config.ratingAvg = result[0];
                    config.ratingCount = (int) result[1];
                    onSuccess.accept(stars == 0 ? "Removed your rating of '" + config.name + "'"
                            : "Rated '" + config.name + "' " + stars + (stars == 1 ? " star" : " stars"));
                });
            } catch (Throwable error) {
                fail(onError, message(error));
            }
        });
    }

    /** Replace a config's filter tags. The worker enforces ownership. */
    public void setTags(RemoteConfig config, List<String> tags, Consumer<String> onSuccess, Consumer<String> onError) {
        if (config == null || config.id == null || config.id.isBlank()) { onError.accept("Choose a config first"); return; }
        if (!config.owned) { onError.accept("You can only change your own configs"); return; }
        POOL.execute(() -> {
            try {
                if (CloudConfigs.setTagsById(config.id, tags)) {
                    config.tags = new ArrayList<>(tags == null ? List.of() : tags);
                    runOnClient(() -> onSuccess.accept("Updated filters for '" + config.name + "'"));
                } else {
                    fail(onError, "Filter change failed: " + errorOf("Cloud rejected the change"));
                }
            } catch (Throwable error) {
                fail(onError, message(error));
            }
        });
    }

    /** Rename one of the signed-in user's own configs. The worker enforces ownership. */
    public void rename(RemoteConfig config, String newName, Consumer<String> onSuccess, Consumer<String> onError) {
        if (config == null || config.id == null || config.id.isBlank()) { onError.accept("Choose a config first"); return; }
        if (!config.owned) { onError.accept("You can only rename your own configs"); return; }
        if (newName == null || newName.isBlank()) { onError.accept("Type a new name first"); return; }
        POOL.execute(() -> {
            try {
                if (CloudConfigs.renameById(config.id, newName.trim())) {
                    config.name = newName.trim();
                    runOnClient(() -> onSuccess.accept("Renamed to '" + newName.trim() + "'"));
                } else {
                    fail(onError, "Rename failed: " + errorOf("Cloud rejected the change"));
                }
            } catch (Throwable error) {
                fail(onError, message(error));
            }
        });
    }

    public void fetchProfile(Consumer<ConfigHubProfile> onSuccess, Consumer<String> onError) {
        POOL.execute(() -> {
            try {
                if (!CloudConfigs.signedIn()) {
                    runOnClient(() -> onError.accept("Not signed in to Apexx"));
                    return;
                }
                CloudConfigs.list();
                if (CloudConfigs.lastError() != null) {
                    runOnClient(() -> onError.accept(CloudConfigs.lastError()));
                    return;
                }
                ConfigHubProfile profile = ConfigHubProfile.unknown();
                profile.authenticated = true;
                profile.uploadAllowed = true;
                profile.webUiOnly = false;
                profile.hasClaimedName = false;
                profile.licenseTag = "Apexx account";
                cachedProfile = profile;
                runOnClient(() -> onSuccess.accept(profile));
            } catch (Throwable error) {
                runOnClient(() -> onError.accept(message(error)));
            }
        });
    }

    public void fetchClientSync(Consumer<String> onSuccess, Consumer<String> onError) {
        POOL.execute(() -> {
            try {
                List<CloudConfigs.Entry> entries = CloudConfigs.list();
                if (CloudConfigs.lastError() != null) {
                    runOnClient(() -> onError.accept(CloudConfigs.lastError()));
                    return;
                }
                String active = entries.stream().filter(CloudConfigs.Entry::active)
                        .map(CloudConfigs.Entry::name).findFirst().orElse("No active config");
                runOnClient(() -> onSuccess.accept(active));
            } catch (Throwable error) {
                runOnClient(() -> onError.accept(message(error)));
            }
        });
    }

    public void claimDisplayName(String desiredName, Consumer<ConfigHubProfile> onSuccess, Consumer<String> onError) {
        runOnClient(() -> onError.accept("Display names are managed by your Apexx account"));
    }

    public void uploadConfig(String localConfigName, String remoteName, String description,
            Consumer<String> onSuccess, Consumer<String> onError) {
        POOL.execute(() -> {
            try {
                String json = ConfigManager.getInstance().exportConfigJsonForCloud(localConfigName);
                if (UserContentBlob.hasContent(json)) {
                    fail(onError, SHARE_BLOCKED_MESSAGE);
                    return;
                }
                String id = CloudConfigs.saveNamed(remoteName, json, true, "Other", description);
                if (id == null) {
                    fail(onError, "Upload failed: " + errorOf("Cloud rejected the config"));
                    return;
                }
                runOnClient(() -> onSuccess.accept("Uploaded “" + remoteName + "” to the community hub"));
            } catch (Throwable error) {
                fail(onError, message(error));
            }
        });
    }

    public void registerLinkKey(String linkKey, Runnable onSuccess, Consumer<String> onError) {
        runOnClient(() -> onError.accept("Account linking is not required for cloud configs"));
    }

    public List<RemoteConfig> getCachedConfigs() {
        return cachedConfigs;
    }

    public ConfigHubProfile getCachedProfile() {
        return cachedProfile;
    }

    private static String uniqueImportedName(String name) {
        String base = (name == null || name.isBlank() ? "Community config" : name.trim()) + " (copy)";
        String candidate = base;
        int suffix = 2;
        while (CloudConfigs.idForName(candidate) != null) candidate = base + " " + suffix++;
        return candidate;
    }

    private static void fail(Consumer<String> callback, String error) {
        runOnClient(() -> callback.accept(error));
    }

    private static String errorOf(String fallback) {
        String error = CloudConfigs.lastError();
        return error == null || error.isBlank() ? fallback : error;
    }

    private static String message(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }
}
