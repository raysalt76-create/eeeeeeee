package interstellarTikiTiks.apexx.client.config;

import com.google.gson.*;
import interstellarTikiTiks.apexx.client.Apexx;
import interstellarTikiTiks.apexx.client.ApexxAuth;
import interstellarTikiTiks.apexx.client.config.ConfigKeybindManager;
import interstellarTikiTiks.apexx.client.hud.HUDElement;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.*;
import interstellarTikiTiks.apexx.client.module.implementation.ClickGUIModule;
import interstellarTikiTiks.apexx.client.theme.ThemeManager;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Color;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import com.google.gson.JsonArray;
import interstellarTikiTiks.apexx.client.module.hooks.StudioProvider;
import interstellarTikiTiks.apexx.client.util.ModuleAccess;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class ConfigManager {
    public enum LoadResult { LOADED, FAILED }

    private static final Logger LOGGER = org.slf4j.helpers.NOPLogger.NOP_LOGGER;
    private static final int CONFIG_AUTH_GATE_SALT = ((0x6C ^ 0x2D) << 24)
            | ((0x81 ^ 0xC3) << 16)
            | ((0x4A ^ 0x1F) << 8)
            | (0xE2 ^ 0xB7);
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private static final Set<String> CLOUD_RESTRICTED_MODULE_KEYS = new HashSet<>(Arrays.asList(
            "autosword"
    ));
    // Standalone build: configs live on disk (~/.apexx/configs); there is no cloud copy.
    private static volatile boolean saveLocalConfig = true;
    private static volatile boolean startupConfigApplied = false;
    private static final Object LOCAL_FILE_LOCK = new Object();
    private static volatile boolean legacyMigrationDone = false;
    private static final java.util.concurrent.ScheduledExecutorService USER_CONTENT_TIMER =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "apexx-user-content-save");
                thread.setDaemon(true);
                return thread;
            });
    private final Object userContentLock = new Object();
    private java.util.concurrent.ScheduledFuture<?> userContentFlush;
    /** True once this session restored or edited macros/scripts; until then saved keys are carried over. */
    private volatile boolean userContentOwned = false;
    /** Saved macros/scripts held while the Premium studio is absent (free payload); written back unchanged. */
    private volatile JsonArray parkedMacros;
    private volatile JsonArray parkedScripts;
    private static final ConfigManager INSTANCE = new ConfigManager();
    private final ExecutorService namedConfigExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "apexx-named-config-load");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicLong configLoadGeneration = new AtomicLong();
    private volatile Future<?> pendingNamedConfigLoad;
    private volatile String lastLoadedConfigName = "default";
    private volatile long lastSaveMs = 0;
    private volatile String cachedConfigHash = "";
    private volatile String cachedConfigJson = null;
    private volatile String shutdownSnapshotJson;

    // ── Secure Isolated Storage (completely outside .minecraft across all OS's) ──
    // These RESOLVE a path only — they no longer create the directory. Configs are cloud-only, so a
    // cloud-only session must leave nothing under ~/.apexx: creating the dir here (which happened just
    // by constructing ConfigManager) was itself a forensic trace. The only thing that legitimately
    // lives in AuthCredentialStore's per-user local-data area, and that store
    // creates the directory itself when (and only when) the user is actually remembered. The gated
    // local-config writer (persistLocalConfig) creates the configs dir on demand if local mode is on.
    public static Path resolveSecureBaseDir() {
        return java.nio.file.Paths.get(System.getProperty("user.home"), ".apexx");
    }

    public static Path resolveConfigDir() {
        return resolveSecureBaseDir().resolve("configs");
    }

    public static Path resolveLastConfigFile() {
        return resolveSecureBaseDir().resolve(".last_config");
    }

    public static boolean isValidConfigJson(String json) {
        if (json == null || json.isBlank() || json.trim().length() < 10) return false;
        try {
            JsonElement el = JsonParser.parseString(json);
            if (el == null || !el.isJsonObject()) return false;
            JsonObject obj = el.getAsJsonObject();
            return obj.has("modules") || obj.has("theme") || obj.has("hudElements")
                    || obj.has("IIllIlIl30") /* legacy obfuscated root key */
                    || obj.has("IllIIllI30");
        } catch (Throwable t) {
            return false;
        }
    }

    private static void cleanupLegacyMinecraftConfigs() {
        // Migration is opt-in with local storage. Never delete the user's source or overwrite
        // an existing destination: cloud availability does not prove an old config was saved.
        if (!saveLocalConfig || legacyMigrationDone) return;
        legacyMigrationDone = true;
        try {
            Path secureDir = resolveConfigDir();
            Path legacyDir = FabricLoader.getInstance().getConfigDir().resolve("apexx");
            Path macConfigs = java.nio.file.Paths.get(System.getProperty("user.home"),
                    "Library", "Application Support", ".apexx", "configs");
            for (Path directory : List.of(legacyDir, macConfigs)) {
                if (!Files.isDirectory(directory)) continue;
                try (var files = Files.list(directory)) {
                    for (Path source : files.filter(p -> p.toString().endsWith(".json")).toList()) {
                        copyLegacyConfig(source, secureDir.resolve(source.getFileName()));
                    }
                }
            }
            copyLegacyConfig(FabricLoader.getInstance().getConfigDir().resolve("apexx-last-config.json"),
                    secureDir.resolve("default.json"));
            copyLegacyConfig(resolveSecureBaseDir().resolve("config.json"), secureDir.resolve("default.json"));
        } catch (Throwable failure) {
            LOGGER.debug("Legacy config migration failed; originals preserved", failure);
        }
    }

    static boolean copyLegacyConfig(Path source, Path target) {
        try {
            if (!Files.isRegularFile(source) || Files.exists(target)) return false;
            String content = Files.readString(source, StandardCharsets.UTF_8);
            if (!isValidConfigJson(content)) return false;
            Files.createDirectories(target.getParent());
            Files.writeString(target, content, StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE_NEW);
            return content.equals(Files.readString(target, StandardCharsets.UTF_8));
        } catch (Exception failure) {
            LOGGER.debug("Legacy config copy failed; original preserved", failure);
            return false;
        }
    }

    // Config persistence.
    //
    // Every save() writes the current config to ~/.apexx/configs/<name>.json, outside .minecraft, so the
    // config carries across versions.
    // saveLocalConfig and LOCAL_FILE_LOCK are declared above INSTANCE: the constructor reads them.

    public static boolean isSaveLocalConfig() {
        return saveLocalConfig;
    }

    public static void setSaveLocalConfig(boolean enabled) {
        saveLocalConfig = enabled;
    }

    public static ConfigManager getInstance() {
        return INSTANCE;
    }

    public ConfigManager() {
        // Clean up any legacy .minecraft files on first instantiation
        cleanupLegacyMinecraftConfigs();
        // Payload modules can register (and autosave) before loadStartupConfig runs; they must
        // already target the config the user had active, not overwrite the pointer with "default".
        lastLoadedConfigName = resolveStartupConfigName();
    }

    private void persistLocalConfig(String name, String json) {
        if (!saveLocalConfig || json == null || json.isEmpty() || !isValidConfigJson(json)) return;
        synchronized (LOCAL_FILE_LOCK) {
            try {
                cleanupLegacyMinecraftConfigs();

                Path cfgDir = resolveConfigDir();
                // resolveConfigDir() does not create the dir — create it here, on the write path.
                Files.createDirectories(cfgDir);
                try {
                    if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
                        Files.setAttribute(cfgDir.getParent(), "dos:hidden", true);
                        Files.setAttribute(cfgDir, "dos:hidden", true);
                    }
                } catch (Throwable ignored) {}
                String currentName = (name != null && !name.isBlank()) ? name : lastLoadedConfigName;
                if (currentName == null || currentName.isBlank()) currentName = "default";
                String safeName = sanitizeConfigName(currentName);
                writeAtomically(cfgDir.resolve(safeName + ".json"), json);

                persistLastConfigName(safeName);
            } catch (Exception e) {
                LOGGER.debug("[ConfigManager] Failed to persist local config: {}", e.getMessage());
            }
        }
    }

    /**
     * Write through a sibling temp file so a crash or a full disk mid-write leaves the previous
     * config intact instead of a truncated file that fails validation and reads as "no config".
     */
    static void writeAtomically(Path target, String content) throws IOException {
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(temp, content, StandardCharsets.UTF_8);
        try {
            Files.move(temp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicMoveFailed) {
            try {
                Files.move(temp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException moveFailed) {
                // Some filesystems refuse to replace the file (e.g. an older hidden copy on Windows).
                Files.writeString(target, content, StandardCharsets.UTF_8);
                Files.deleteIfExists(temp);
            }
        }
    }

    /** The last active config name if its file still exists, otherwise "default". */
    private String resolveStartupConfigName() {
        String name = readLastConfigName();
        return "default".equals(name) || readLocalConfig(name) != null ? name : "default";
    }

    /** The config name that was active when the game last saved, or "default". */
    String readLastConfigName() {
        if (!saveLocalConfig) return "default";
        try {
            Path lastFile = resolveLastConfigFile();
            if (Files.isRegularFile(lastFile)) {
                String name = Files.readString(lastFile, StandardCharsets.UTF_8).trim();
                if (!name.isEmpty() && !isValidConfigJson(name)) return sanitizeConfigName(name);
            }
        } catch (Exception e) {
            LOGGER.debug("[ConfigManager] Failed to read last config name: {}", e.getMessage());
        }
        return "default";
    }

    // ── Cloud baseline ──────────────────────────────────────────────────────────
    //
    // The last config this machine confirmed equal to the active cloud profile. At sign-in it tells
    // apart "only the local file changed" (push it) from "only the cloud changed" (apply it), so
    // neither side silently overwrites newer edits on the other.

    private static Path resolveCloudBaselineFile() {
        return resolveSecureBaseDir().resolve(".cloud_baseline");
    }

    void writeCloudBaseline(String cloudId, String json) {
        if (!saveLocalConfig || json == null || !isValidConfigJson(json)) return;
        synchronized (LOCAL_FILE_LOCK) {
            try {
                JsonObject baseline = new JsonObject();
                if (cloudId != null) baseline.addProperty("id", cloudId);
                baseline.addProperty("name", sanitizeConfigName(lastLoadedConfigName));
                baseline.add("data", JsonParser.parseString(json));
                Path file = resolveCloudBaselineFile();
                Files.createDirectories(file.getParent());
                writeAtomically(file, gson.toJson(baseline));
            } catch (Exception e) {
                LOGGER.debug("[ConfigManager] Failed to persist cloud baseline: {}", e.getMessage());
            }
        }
    }

    /** The baseline recorded for this cloud profile and local config name, or null if unknown. */
    String readCloudBaseline(String cloudId, String configName) {
        if (!saveLocalConfig) return null;
        try {
            Path file = resolveCloudBaselineFile();
            if (!Files.isRegularFile(file)) return null;
            JsonObject baseline = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            String storedId = baseline.has("id") && !baseline.get("id").isJsonNull()
                    ? baseline.get("id").getAsString() : null;
            String storedName = baseline.has("name") ? baseline.get("name").getAsString() : null;
            if (cloudId != null && storedId != null && !cloudId.equals(storedId)) return null;
            if (!sanitizeConfigName(configName).equals(storedName)) return null;
            if (!baseline.has("data") || !baseline.get("data").isJsonObject()) return null;
            String data = baseline.get("data").toString();
            return isValidConfigJson(data) ? data : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** What to do with the local and cloud copies of the active config at sign-in. */
    enum LaunchSync { APPLY_CLOUD, PUSH_LOCAL, IN_SYNC, NOTHING }

    /**
     * Three-way decision between the local file, the cloud profile, and the baseline both last
     * agreed on. Configs are compared as JSON trees, so key order and formatting do not matter.
     */
    static LaunchSync decideLaunchSync(String cloud, String local, String baseline, boolean localEditedThisSession) {
        if (cloud == null) return local != null ? LaunchSync.PUSH_LOCAL : LaunchSync.NOTHING;
        if (local == null) return LaunchSync.APPLY_CLOUD;
        if (sameConfigJson(local, cloud)) return LaunchSync.IN_SYNC;
        // The user saved or switched configs while the request was in flight: never revert that.
        if (localEditedThisSession) return LaunchSync.PUSH_LOCAL;
        // No shared history (first sign-in on this machine or after this update): the cloud has
        // been the source of truth until now.
        if (baseline == null) return LaunchSync.APPLY_CLOUD;
        if (sameConfigJson(baseline, cloud)) return LaunchSync.PUSH_LOCAL;   // only local changed
        if (sameConfigJson(baseline, local)) return LaunchSync.APPLY_CLOUD;  // only cloud changed
        return LaunchSync.PUSH_LOCAL; // both changed: keep this machine's current config
    }

    static boolean sameConfigJson(String first, String second) {
        if (first == null || second == null) return first == second;
        try {
            return JsonParser.parseString(first).equals(JsonParser.parseString(second));
        } catch (Throwable ignored) {
            return first.equals(second);
        }
    }

    /**
     * Reconcile the local file with the active cloud profile pulled at sign-in. Runs on the client
     * thread. {@code pendingLocalChanges} means a save this session is still waiting to be pushed
     * (the pull may be a retry started after it). {@code onDone} receives the cloud snapshot both sides now agree on (null when the local
     * config still has to be pushed) and whether the local config should be pushed.
     */
    void reconcileWithCloud(String cloudBlob, String cloudId, long launchGeneration,
            boolean pendingLocalChanges, java.util.function.BiConsumer<String, Boolean> onDone) {
        runOnClientThread(() -> {
            if (Apexx.isTornDown()) return;
            String name = lastLoadedConfigName;
            String local = readLocalConfig(name);
            String localForCloud = local == null ? null : sanitizeForCloud(local);
            boolean editedThisSession = pendingLocalChanges || launchGeneration != configLoadGeneration.get();
            LaunchSync decision = decideLaunchSync(cloudBlob, localForCloud,
                    readCloudBaseline(cloudId, name), editedThisSession);
            switch (decision) {
                case APPLY_CLOUD -> applyCloudBlob(cloudBlob, -1L, applied -> {
                    if (applied) writeCloudBaseline(cloudId, cloudBlob);
                    onDone.accept(cloudBlob, !applied && localForCloud != null);
                });
                case IN_SYNC -> {
                    writeCloudBaseline(cloudId, cloudBlob);
                    onDone.accept(cloudBlob, true); // dedup makes this a no-op unless new keys appeared
                }
                case PUSH_LOCAL -> onDone.accept(cloudBlob, true);
                case NOTHING -> onDone.accept(cloudBlob, false);
            }
        });
    }

    /** Run on the client thread after the current task finishes, even when already on it. */
    static void queueOnClientThread(Runnable action) {
        try {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client != null) client.execute(action);
        } catch (Throwable rejected) {
            LOGGER.debug("[ConfigManager] Dropped config task because the client is stopping");
        }
    }

    private String sanitizeForCloud(String json) {
        try {
            JsonObject sanitized = sanitizeCloudConfig(JsonParser.parseString(json));
            return sanitized == null ? null : gson.toJson(sanitized);
        } catch (Exception malformed) {
            return null;
        }
    }

    private static void runOnClientThread(Runnable action) {
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
            LOGGER.debug("[ConfigManager] Dropped config task because the client is stopping");
        }
    }

    private void persistLastConfigName(String name) {
        // Remembers which named config to load on the next launch (see loadStartupConfig).
        if (!saveLocalConfig) return;
        try {
            Path lastFile = resolveLastConfigFile();
            if (lastFile.getParent() != null) {
                Files.createDirectories(lastFile.getParent());
            }
            Files.writeString(lastFile, sanitizeConfigName(name), StandardCharsets.UTF_8);
            try {
                if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
                    Files.setAttribute(lastFile, "dos:hidden", true);
                }
            } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
    }

    /** The last config we wrote to disk, or null if there is none. */
    public String readLocalConfig() {
        return readLocalConfig(lastLoadedConfigName);
    }

    public String readLocalConfig(String name) {
        if (!saveLocalConfig) return null;
        try {
            cleanupLegacyMinecraftConfigs();

            Path cfgDir = resolveConfigDir();
            if (name != null && !name.isBlank()) {
                String safe = sanitizeConfigName(name);
                Path namedFile = cfgDir.resolve(safe + ".json");
                if (Files.isRegularFile(namedFile)) {
                    String content = Files.readString(namedFile, StandardCharsets.UTF_8);
                    if (isValidConfigJson(content)) {
                        return content;
                    }
                }
            }

            // If loading "default", check root config.json or .last_config fallback
            if (name == null || name.isBlank() || "default".equalsIgnoreCase(name)) {
                Path rootConfig = resolveSecureBaseDir().resolve("config.json");
                if (Files.isRegularFile(rootConfig)) {
                    String content = Files.readString(rootConfig, StandardCharsets.UTF_8);
                    if (isValidConfigJson(content)) {
                        return content;
                    }
                }
                Path lastFile = resolveLastConfigFile();
                if (Files.isRegularFile(lastFile)) {
                    String lastContent = Files.readString(lastFile, StandardCharsets.UTF_8).trim();
                    if (isValidConfigJson(lastContent)) {
                        return lastContent;
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.debug("[ConfigManager] Failed to read local config: {}", e.getMessage());
        }
        return null;
    }

    public void checkForExternalChanges() {
        // No-op (companion HTTP polling removed; in-game UI directly dispatches to ConfigManager)
    }

    public void load(String name) {
        load(name, null);
    }

    /**
     * Load the config that was active when the game last saved, from disk only. Runs at launch and
     * after sign-in, before CloudConfigSync reconciles it with the cloud; it never starts a cloud
     * request of its own so it cannot race that reconciliation.
     */
    public void loadStartupConfig() {
        try {
            String name = resolveStartupConfigName();
            String local = readLocalConfig(name);
            configLoadGeneration.incrementAndGet();
            cancelPendingNamedConfigLoad();
            lastLoadedConfigName = name;
            if (local != null && loadFromJson(local)) {
                cachedConfigJson = local;
                cachedConfigHash = Integer.toHexString(local.hashCode());
            } else if (cachedConfigJson == null) {
                ThemeManager.getInstance().setCurrentTheme("dark");
            }
        } finally {
            startupConfigApplied = true;
        }
    }

    /** Load a named config and report only after its source has actually been applied. */
    public void load(String name, Consumer<LoadResult> completion) {
        MinecraftClient client;
        try {
            client = MinecraftClient.getInstance();
        } catch (Throwable unavailable) {
            return;
        }
        if (client == null) return;
        if (!client.isOnThread()) {
            try {
                client.execute(() -> load(name, completion));
            } catch (Throwable rejected) {
                LOGGER.debug("[ConfigManager] Client stopped before config load could be queued");
            }
            return;
        }

        String safeName = sanitizeConfigName(name);
        long requestGeneration = configLoadGeneration.incrementAndGet();
        cancelPendingNamedConfigLoad();

        // The local file for this name wins; it is the last state the user saved on this machine.
        String local = readLocalConfig(safeName);
        if (local != null) {
            if (loadFromJson(local)) {
                cachedConfigJson = local;
                cachedConfigHash = Integer.toHexString(local.hashCode());
                this.lastLoadedConfigName = safeName;
                persistLastConfigName(safeName);
                notifyLoadCompletion(completion, LoadResult.LOADED);
            } else {
                notifyLoadCompletion(completion, LoadResult.FAILED);
            }
            return;
        }

        // Not on disk (a cloud-only named profile). Cloud HTTP can take tens of seconds, so keep
        // the game thread responsive with the in-memory fallback and replace it on success.
        if (cachedConfigJson != null && isValidConfigJson(cachedConfigJson)) {
            loadFromJson(cachedConfigJson);
        } else if ("default".equals(safeName)) {
            ThemeManager.getInstance().setCurrentTheme("dark");
        }
        // Cloud configs are looked up by their real name ("My PvP"), not the file-safe form ("My_PvP").
        loadNamedConfigFromCloudAsync(name == null || name.isBlank() ? safeName : name.trim(), requestGeneration, completion);
    }

    private static void notifyLoadCompletion(Consumer<LoadResult> completion, LoadResult result) {
        if (completion == null) return;
        try {
            completion.accept(result);
        } catch (Throwable failure) {
            LOGGER.debug("[ConfigManager] Config load completion failed: {}", failure.getMessage());
        }
    }

    /** Cancel any outstanding cloud config lookup and invalidate its eventual completion. */
    public void cancelPendingLoads() {
        configLoadGeneration.incrementAndGet();
        cancelPendingNamedConfigLoad();
    }

    private void cancelPendingNamedConfigLoad() {
        Future<?> pending = pendingNamedConfigLoad;
        pendingNamedConfigLoad = null;
        if (pending != null) pending.cancel(true);
    }

    private void loadNamedConfigFromCloudAsync(String name, long requestGeneration,
            Consumer<LoadResult> completion) {
        try {
            pendingNamedConfigLoad = namedConfigExecutor.submit(() -> {
                String json = null;
                String cloudName = name;
                try {
                    String[] result = CloudConfigs.getOwnByName(name);
                    if (result != null && result.length >= 2 && isValidConfigJson(result[1])) {
                        json = result[1];
                        if (result[0] != null && !result[0].isBlank()) cloudName = result[0];
                    }
                } catch (Throwable failure) {
                    LOGGER.debug("[ConfigManager] Named cloud load failed: {}", failure.getMessage());
                }
                MinecraftClient client;
                try {
                    client = MinecraftClient.getInstance();
                } catch (Throwable unavailable) {
                    return;
                }
                if (client == null) return;
                String resolvedJson = json;
                String resolvedName = cloudName;
                try {
                    client.execute(() -> {
                        if (requestGeneration != configLoadGeneration.get() || Apexx.isTornDown()) return;
                        if (resolvedJson != null) {
                            if (loadFromJson(resolvedJson)) {
                                cachedConfigJson = resolvedJson;
                                cachedConfigHash = Integer.toHexString(resolvedJson.hashCode());
                                lastLoadedConfigName = sanitizeConfigName(name);
                                persistLocalConfig(lastLoadedConfigName, resolvedJson);
                                markLoaded(resolvedName);
                                notifyLoadCompletion(completion, LoadResult.LOADED);
                            } else {
                                notifyLoadCompletion(completion, LoadResult.FAILED);
                            }
                        } else {
                            notifyLoadCompletion(completion, LoadResult.FAILED);
                        }
                    });
                } catch (Throwable ignored) {
                    // The game may already be stopping; a failed late apply is safe to drop.
                }
            });
        } catch (RuntimeException rejected) {
            LOGGER.debug("[ConfigManager] Cloud config worker unavailable: {}", rejected.getMessage());
        }
    }

    private boolean loadFromJson(String jsonString) {
        return loadFromJson(jsonString, false);
    }

    /**
     * {@code trustedOwnBlob} is true only for the user's own active cloud blob. Macros and scripts are
     * restored from it and never from anything else (imports, community configs, named profiles).
     */
    private boolean loadFromJson(String jsonString, boolean trustedOwnBlob) {
        try {
            JsonElement parsed = JsonParser.parseString(jsonString);
            if (parsed == null || !parsed.isJsonObject()) return false;
            JsonObject root = parsed.getAsJsonObject();
            migrateObfuscatedConfig(root);
            ConfigMigrations.migrate(root);
            applyConfigFromJson(root);
            if (trustedOwnBlob) {
                applyUserContent(root);
                // Only the user's own blob restores per-user prefs; named swaps and imports keep the live ones.
                ClientPrefs.get().applyFromBlob(root.get(ClientPrefs.KEY));
            }
            return true;
        } catch (Exception e) {
            LOGGER.debug("Failed to parse config JSON: {}", e.getMessage());
            return false;
        }
    }

    




    public String getLastLoadedConfigName() {
        return lastLoadedConfigName;
    }

    long getConfigLoadGeneration() {
        return configLoadGeneration.get();
    }

    void runIfConfigLoadGenerationCurrent(long expectedGeneration, Runnable action) {
        MinecraftClient client;
        try {
            client = MinecraftClient.getInstance();
        } catch (Throwable unavailable) {
            return;
        }
        if (client == null) return;
        Runnable guarded = () -> {
            if (expectedGeneration == configLoadGeneration.get() && !Apexx.isTornDown()) {
                action.run();
            }
        };
        try {
            if (client.isOnThread()) guarded.run();
            else client.execute(guarded);
        } catch (Throwable rejected) {
            LOGGER.debug("[ConfigManager] Dropped stale config metadata because the client is stopping");
        }
    }

    public void loadModule(String configName, Module module) {
        if (configName == null || configName.isEmpty() || module == null) {
            return;
        }

        if ("Booster".equalsIgnoreCase(module.getName())) {
            return;
        }

        String jsonString = cachedConfigJson;
        if (jsonString == null || !isValidConfigJson(jsonString)) {
            jsonString = readLocalConfig(configName);
            if (jsonString != null) {
                cachedConfigJson = jsonString;
            }
        }
        if (jsonString == null) return;

        try {
            JsonElement parsed = JsonParser.parseString(jsonString);
            if (parsed == null || !parsed.isJsonObject()) return;
            JsonObject root = parsed.getAsJsonObject();
            migrateObfuscatedConfig(root);
            ConfigMigrations.migrate(root);
            if (!root.has("modules") || !root.get("modules").isJsonObject()) return;

            Map<String, ModuleData> modules = parseModules(root.getAsJsonObject("modules"));
            Map<String, ModuleData> moduleLookup = buildModuleLookup(modules);
            ModuleData moduleData = resolveModuleData(modules, moduleLookup, module.getName());
            if (moduleData == null) {
                return;
            }

            module.setEnabled(false);
            module.setKeyCode(moduleData.keyCode);
            module.setKeyCode2(moduleData.keyCode2);
            module.setHoldMode(moduleData.holdMode);
            module.setFavorite(moduleData.favorite);
            if (moduleData.settings != null) {
                for (Setting setting : module.getSettings()) {
                    loadSetting(moduleData.settings, setting);
                }
            }
            module.setEnabled(moduleData.enabled && canEnableModules());
        } catch (Exception e) {
            LOGGER.debug("Failed to load module config for {}: {}", module.getName(), e.getMessage());
        }
    }

    ConfigData buildCurrentConfigData() {
        ConfigData data = new ConfigData();

        if (ClickGUIModule.theme != null) {
            data.theme = ClickGUIModule.theme.getCurrentMode();
        } else {
            data.theme = ThemeManager.getInstance().getCurrentTheme().name;
        }
        if (ClickGUIModule.backgroundOverlay != null) {
            data.atmosphere = ClickGUIModule.backgroundOverlay.getCurrentMode();
        }

        data.hudElements = new HashMap<>();
        if (Apexx.INSTANCE != null && Apexx.INSTANCE.getHudManager() != null) {
            for (HUDElement element : Apexx.INSTANCE.getHudManager().getElements()) {
                HUDElementData elementData = new HUDElementData(element.getX(), element.getY(), element.isEnabled());

                elementData.settings = new JsonObject();
                List<Setting> settings = element.getSettings();
                if (settings != null) {
                    for (Setting setting : settings) {
                        saveSetting(elementData.settings, setting);
                    }
                }

                data.hudElements.put(element.getName(), elementData);
            }
        }

        data.modules = new HashMap<>();
        if (Apexx.INSTANCE != null && Apexx.INSTANCE.getModuleManager() != null) {
            for (Module module : new ArrayList<>(Apexx.INSTANCE.getModuleManager().getModules())) {
                if ("Booster".equalsIgnoreCase(module.getName())) {
                    continue;
                }
                ModuleData moduleData = new ModuleData();
                moduleData.enabled = module.isEnabledRaw();
                moduleData.keyCode = module.getKeyCode();
                moduleData.keyCode2 = module.getKeyCode2();
                moduleData.holdMode = module.isHoldMode();
                moduleData.favorite = module.isFavorite();
                moduleData.settings = new JsonObject();
                for (Setting setting : module.getSettings()) {
                    saveSetting(moduleData.settings, setting);
                }
                data.modules.put(module.getName(), moduleData);
            }
        }

        List<ConfigKeybindManager.ConfigKeybind> cfgKbs = ConfigKeybindManager.getInstance().getKeybinds();
        if (!cfgKbs.isEmpty()) {
            data.configKeybinds = new ArrayList<>();
            for (ConfigKeybindManager.ConfigKeybind ckb : cfgKbs) {
                if (ckb.key1 == -1) continue;
                ConfigKeybindEntry entry = new ConfigKeybindEntry();
                entry.configName = ckb.configName;
                entry.key1 = ckb.key1;
                entry.key2 = ckb.key2;
                data.configKeybinds.add(entry);
            }
        }

        return data;
    }

    /**
     * The live state merged over the last loaded/saved config. Entries for modules and HUD elements
     * that are not registered right now (the payload has not finished loading, a module failed to
     * construct, or a new payload version dropped or renamed it) are carried over unchanged, so a
     * save never erases them. While modules cannot be enabled (signed out), the saved enabled flags
     * are kept instead of the forced-off live ones.
     */
    private JsonObject buildMergedConfigTree() {
        JsonObject tree = buildModuleAndHudTree();
        attachClientPrefs(tree);
        attachUserContent(tree);
        return tree;
    }

    /**
     * Per-user prefs (server auto-load map, tour flag, HUD layouts, friend/enemy keys) go into the blob
     * once this session restored or changed them; before that the previously saved key is carried over,
     * so an early autosave can never replace the user's saved prefs with defaults.
     */
    private void attachClientPrefs(JsonObject tree) {
        try {
            ClientPrefs prefs = ClientPrefs.get();
            if (prefs.isOwned()) {
                tree.add(ClientPrefs.KEY, prefs.toJson());
                return;
            }
            String previousJson = cachedConfigJson;
            if (previousJson != null && previousJson.contains("\"" + ClientPrefs.KEY + "\"")) {
                JsonElement previous = JsonParser.parseString(previousJson);
                JsonElement saved = previous.isJsonObject() ? previous.getAsJsonObject().get(ClientPrefs.KEY) : null;
                if (saved != null && saved.isJsonObject()) tree.add(ClientPrefs.KEY, saved.deepCopy());
            }
        } catch (RuntimeException e) {
            LOGGER.debug("[ConfigManager] Could not attach client prefs: {}", e.getMessage());
        }
    }

    /**
     * Macros and IDE scripts go into the blob only once this session has restored or edited them.
     * Before that the live managers hold defaults, so the previously saved keys are carried over
     * instead; otherwise an early autosave would erase the user's cloud macros.
     */
    private void attachUserContent(JsonObject tree) {
        try {
            JsonArray macros = null;
            JsonArray scripts = null;
            if (userContentOwned) {
                // Parked arrays (no studio in this payload, or not handed over yet) are written back unchanged.
                StudioProvider studio = studio();
                JsonArray parkedM = parkedMacros;
                JsonArray parkedS = parkedScripts;
                macros = parkedM != null ? parkedM.deepCopy() : studio != null ? studio.macrosJson() : null;
                scripts = parkedS != null ? parkedS.deepCopy() : studio != null ? studio.scriptsJson() : null;
            } else {
                String previousJson = cachedConfigJson;
                if (previousJson != null && previousJson.contains("\"" + UserContentBlob.MACROS + "\"")
                        || previousJson != null && previousJson.contains("\"" + UserContentBlob.SCRIPTS + "\"")) {
                    JsonObject previous = JsonParser.parseString(previousJson).getAsJsonObject();
                    JsonElement m = previous.get(UserContentBlob.MACROS);
                    JsonElement sc = previous.get(UserContentBlob.SCRIPTS);
                    if (m != null && m.isJsonArray()) macros = m.getAsJsonArray().deepCopy();
                    if (sc != null && sc.isJsonArray()) scripts = sc.getAsJsonArray().deepCopy();
                }
            }
            UserContentBlob.attach(tree, macros, scripts, UserContentBlob.MAX_BLOB_BYTES);
        } catch (RuntimeException e) {
            LOGGER.debug("[ConfigManager] Could not attach macros/scripts: {}", e.getMessage());
        }
    }

    private JsonObject buildModuleAndHudTree() {
        JsonObject current = gson.toJsonTree(buildCurrentConfigData()).getAsJsonObject();
        ConfigMigrations.stamp(current);
        String previousJson = cachedConfigJson;
        if (previousJson == null || !isValidConfigJson(previousJson)) return current;
        try {
            JsonObject previous = JsonParser.parseString(previousJson).getAsJsonObject();
            migrateObfuscatedConfig(previous);
            ConfigMigrations.migrate(previous);
            carryOverMissingEntries(previous, current, "hudElements", false);
            carryOverMissingEntries(previous, current, "modules", true);
            for (String rootKey : List.of("theme", "guiType", "atmosphere")) {
                if (!current.has(rootKey) && previous.has(rootKey)) {
                    current.add(rootKey, previous.get(rootKey).deepCopy());
                }
            }
            if (!canEnableModules()) keepSavedEnabledFlags(previous, current);
        } catch (RuntimeException malformedPrevious) {
            LOGGER.debug("[ConfigManager] Previous config could not be merged: {}", malformedPrevious.getMessage());
        }
        return current;
    }

    private void carryOverMissingEntries(JsonObject previous, JsonObject current, String section, boolean moduleAliases) {
        if (!previous.has(section) || !previous.get(section).isJsonObject()) return;
        JsonObject target = current.has(section) && current.get(section).isJsonObject()
                ? current.getAsJsonObject(section) : new JsonObject();
        Set<String> present = new HashSet<>();
        for (String key : target.keySet()) {
            present.add(moduleAliases ? normalizeAliasLookupKey(key) : normalizeLookupKey(key));
        }
        for (Map.Entry<String, JsonElement> entry : previous.getAsJsonObject(section).entrySet()) {
            String key = entry.getKey();
            String normalized = moduleAliases ? normalizeAliasLookupKey(key) : normalizeLookupKey(key);
            if (target.has(key) || present.contains(normalized)) {
                if (moduleAliases && entry.getValue().isJsonObject()) {
                    JsonObject prevMod = entry.getValue().getAsJsonObject();
                    JsonObject targetMod = target.has(key) && target.get(key).isJsonObject()
                            ? target.getAsJsonObject(key) : null;
                    if (targetMod == null) {
                        for (String tKey : target.keySet()) {
                            if (normalizeAliasLookupKey(tKey).equals(normalized) && target.get(tKey).isJsonObject()) {
                                targetMod = target.getAsJsonObject(tKey);
                                break;
                            }
                        }
                    }
                    if (targetMod != null && prevMod.has("settings") && prevMod.get("settings").isJsonObject()
                            && targetMod.has("settings") && targetMod.get("settings").isJsonObject()) {
                        JsonObject prevSettings = prevMod.getAsJsonObject("settings");
                        JsonObject targetSettings = targetMod.getAsJsonObject("settings");
                        for (Map.Entry<String, JsonElement> sEntry : prevSettings.entrySet()) {
                            if (!targetSettings.has(sEntry.getKey())) {
                                targetSettings.add(sEntry.getKey(), sEntry.getValue().deepCopy());
                            }
                        }
                    }
                }
                continue;
            }
            target.add(key, entry.getValue().deepCopy());
        }
        current.add(section, target);
    }

    private static void keepSavedEnabledFlags(JsonObject previous, JsonObject current) {
        if (!previous.has("modules") || !previous.get("modules").isJsonObject()
                || !current.has("modules") || !current.get("modules").isJsonObject()) return;
        JsonObject previousModules = previous.getAsJsonObject("modules");
        for (Map.Entry<String, JsonElement> entry : current.getAsJsonObject("modules").entrySet()) {
            JsonElement saved = previousModules.get(entry.getKey());
            if (saved == null || !saved.isJsonObject() || !entry.getValue().isJsonObject()) continue;
            JsonElement enabled = saved.getAsJsonObject().get("enabled");
            if (enabled != null) entry.getValue().getAsJsonObject().add("enabled", enabled.deepCopy());
        }
    }

    /** Snapshot the current live state for an explicit named cloud save. */
    public String exportCurrentConfigJsonForCloud() {
        try {
            JsonObject sanitized = sanitizeCloudConfig(buildMergedConfigTree());
            return sanitized == null ? null : gson.toJson(sanitized);
        } catch (Exception e) {
            LOGGER.debug("[ConfigManager] Failed to export current config: {}", e.getMessage());
            return null;
        }
    }

    /** User-initiated save: also supersedes any config load still in flight. */
    public void save(String name) {
        configLoadGeneration.incrementAndGet();
        cancelPendingNamedConfigLoad();
        persistCurrentState(name);
    }

    /**
     * Internal save (e.g. after payload modules register). Persists the same snapshot as
     * {@link #save(String)} but does not cancel an in-flight config load or the sign-in cloud
     * reconciliation: modules registering late used to discard the user's cloud config that way.
     */
    public void autosave(String name) {
        // Core modules (ClickGUI: theme, atmosphere, ...) register before loadStartupConfig applies
        // the saved file, so their live state is still the defaults. Writing that snapshot now would
        // overwrite the user's saved values on disk before they are ever read.
        if (!startupConfigApplied) return;
        persistCurrentState(name);
    }

    private void persistCurrentState(String name) {
        String safeName = sanitizeConfigName(name);
        this.lastLoadedConfigName = safeName;
        try {
            String json = gson.toJson(buildMergedConfigTree());
            cachedConfigJson = json;
            cachedConfigHash = Integer.toHexString(json.hashCode());
            lastSaveMs = System.currentTimeMillis();
            persistLocalConfig(safeName, json);
        } catch (Exception e) {
            LOGGER.debug("[ConfigManager] Failed to save config {}: {}", safeName, e.getMessage());
        }
    }

    /** Capture mutable module/HUD state before Minecraft stops; the JVM hook only writes this snapshot. */
    public void prepareShutdownSnapshot() {
        try {
            shutdownSnapshotJson = gson.toJson(buildMergedConfigTree());
        } catch (Throwable failure) {
            LOGGER.debug("[ConfigManager] Failed to capture shutdown snapshot: {}", failure.getMessage());
        }
    }

    /** Persist the immutable snapshot captured on CLIENT_STOPPING, without reading live game state. */
    public void saveShutdownSnapshot() {
        String snapshot = shutdownSnapshotJson;
        if (snapshot == null || snapshot.isBlank()) return;
        persistLocalConfig(lastLoadedConfigName, snapshot);
    }

    /** The shutdown snapshot in the shape the cloud accepts, or null if none was captured. */
    public String getShutdownSnapshotForCloud() {
        String snapshot = shutdownSnapshotJson;
        return snapshot == null || snapshot.isBlank() ? null : sanitizeForCloud(snapshot);
    }

    /**
     * Apply a config blob pulled from the cloud (/api/config). Runs on the client thread (it touches
     * modules/HUD/theme) and sets the {@link CloudConfigSync} "applying" guard so the resulting local
     * save doesn't immediately echo the same config straight back to the server.
     */
    public void applyCloudBlob(String blobJson) {
        applyCloudBlob(blobJson, -1L, null);
    }

    void applyCloudBlob(String blobJson, long expectedGeneration) {
        applyCloudBlob(blobJson, expectedGeneration, null);
    }

    void applyCloudBlob(String blobJson, long expectedGeneration, Consumer<Boolean> completion) {
        applyCloudBlob(blobJson, expectedGeneration, completion, () -> true);
    }

    void applyCloudBlob(String blobJson, long expectedGeneration, Consumer<Boolean> completion,
            java.util.function.BooleanSupplier stillCurrent) {
        if (blobJson == null || blobJson.isBlank() || !isValidConfigJson(blobJson)) return;
        if (expectedGeneration >= 0 && expectedGeneration != configLoadGeneration.get()) return;
        // Whether the cloud copy may replace the local file is decided by reconcileWithCloud.
        Runnable apply = () -> {
            if ((expectedGeneration >= 0 && expectedGeneration != configLoadGeneration.get())
                    || Apexx.isTornDown() || !stillCurrent.getAsBoolean()) {
                if (completion != null) notifyImportCompletion(completion, false);
                return;
            }
            configLoadGeneration.incrementAndGet();
            cancelPendingNamedConfigLoad();
            try {
                CloudConfigSync.setApplying(true);
                boolean applied = loadFromJson(blobJson, true);
                if (applied) {
                    // Keep entries for modules that are not registered yet, then mirror to disk.
                    cachedConfigJson = blobJson;
                    persistCurrentState(lastLoadedConfigName);
                } else {
                    LOGGER.debug("[ConfigManager] Cloud config could not be applied");
                }
                if (completion != null) notifyImportCompletion(completion, applied);
            } catch (Throwable t) {
                LOGGER.debug("[ConfigManager] applyCloudBlob failed: {}", t.getMessage());
                if (completion != null) notifyImportCompletion(completion, false);
            } finally {
                CloudConfigSync.setApplying(false);
            }
        };
        try {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client == null) return;
            if (client.isOnThread()) apply.run();
            else client.execute(apply);
        } catch (Throwable rejected) {
            LOGGER.debug("[ConfigManager] Dropped cloud apply because the client is stopping");
        }
    }

    private void saveSetting(JsonObject jsonObject, Setting setting) {
        if (setting instanceof ModeSetting s) {
            jsonObject.addProperty(s.getName(), s.getCurrentMode());
        } else if (setting instanceof SliderSetting s) {
            jsonObject.addProperty(s.getName(), s.getPreciseValue());
        } else if (setting instanceof ColorSetting s) {
            jsonObject.addProperty(s.getName(), s.getBaseValue().getRGB());
            // Always written (also when off) so a merge with the previous blob can never resurrect an old "on".
            jsonObject.addProperty(s.getName() + "#rainbow", s.isRainbow());
            jsonObject.addProperty(s.getName() + "#rainbowSpeed", s.getRainbowSpeed());
        } else if (setting instanceof BooleanSetting s) {
            jsonObject.addProperty(s.getName(), s.isEnabled());
            // Bindable booleans (e.g. TriggerBot's "Hit Select") persist their own key +
            // hold/toggle alongside the value under "<name>#key" / "<name>#hold".
            if (s.isBindable()) {
                jsonObject.addProperty(s.getName() + "#key", s.getKeyCode());
                jsonObject.addProperty(s.getName() + "#hold", s.isKeyHoldMode());
            }
        } else if (setting instanceof StringSetting s) {
            jsonObject.addProperty(s.getName(), s.getValue());
        }
    }

    void loadSetting(JsonObject jsonObject, Setting setting) {
        String settingKey = resolveSettingKey(jsonObject, setting.getName());
        if (settingKey == null) {
            return;
        }

        try {
            JsonElement elem = jsonObject.get(settingKey);
            if (elem == null || elem.isJsonNull()) return;

            if (setting instanceof ModeSetting s) {
                if (elem.isJsonPrimitive() && elem.getAsJsonPrimitive().isBoolean()) {
                    boolean boolVal = elem.getAsBoolean();
                    if ("Atmosphere".equalsIgnoreCase(s.getName()) || "Mist".equalsIgnoreCase(s.getName())) {
                        s.setCurrentMode(boolVal ? "Mist" : "None");
                    } else if (s.getModes().size() > 1) {
                        s.setCurrentMode(boolVal ? s.getModes().get(1) : s.getModes().get(0));
                    }
                } else if (elem.isJsonPrimitive()) {
                    String strVal = elem.getAsString();
                    if ("Atmosphere".equalsIgnoreCase(s.getName()) || "Mist".equalsIgnoreCase(s.getName())) {
                        if ("true".equalsIgnoreCase(strVal) || "on".equalsIgnoreCase(strVal) || "enabled".equalsIgnoreCase(strVal)) {
                            s.setCurrentMode("Mist");
                        } else if ("false".equalsIgnoreCase(strVal) || "off".equalsIgnoreCase(strVal) || "disabled".equalsIgnoreCase(strVal)) {
                            s.setCurrentMode("None");
                        } else {
                            s.setCurrentMode(strVal);
                        }
                    } else if ("Theme".equalsIgnoreCase(s.getName())) {
                        s.setCurrentMode(ClickGUIModule.normalizeThemeName(strVal));
                    } else {
                        s.setCurrentMode(strVal);
                        // A choice that no longer exists (renamed/removed in a newer client) falls back to
                        // the default instead of silently keeping whatever an earlier config left behind.
                        String wanted = strVal.replaceAll("[^a-zA-Z0-9]", "");
                        if (!s.getCurrentMode().replaceAll("[^a-zA-Z0-9]", "").equalsIgnoreCase(wanted)) {
                            s.resetToDefault();
                        }
                    }
                }
            } else if (setting instanceof SliderSetting s) {
                if (elem.isJsonPrimitive()) {
                    s.setValue(elem.getAsDouble());
                }
            } else if (setting instanceof ColorSetting s) {
                if (elem.isJsonPrimitive()) {
                    if (elem.getAsJsonPrimitive().isNumber()) {
                        s.setValue(new Color(elem.getAsInt(), true));
                    } else {
                        String str = elem.getAsString().trim();
                        try {
                            if (str.startsWith("#")) {
                                long val = Long.parseLong(str.substring(1), 16);
                                if (str.length() <= 7) val |= 0xFF000000L;
                                s.setValue(new Color((int) val, true));
                            } else if (str.startsWith("0x") || str.startsWith("0X")) {
                                long val = Long.parseLong(str.substring(2), 16);
                                s.setValue(new Color((int) val, true));
                            } else {
                                s.setValue(new Color((int) Long.parseLong(str), true));
                            }
                        } catch (Exception ignored) {}
                    }
                }
                // Rainbow keys are newer than the colour itself; a config without them keeps the defaults.
                JsonElement rainbow = jsonObject.get(s.getName() + "#rainbow");
                s.setRainbow(rainbow != null && rainbow.isJsonPrimitive() && rainbow.getAsBoolean());
                JsonElement speed = jsonObject.get(s.getName() + "#rainbowSpeed");
                if (speed != null && speed.isJsonPrimitive()) {
                    try { s.setRainbowSpeed(speed.getAsDouble()); } catch (Exception ignored) {}
                }
            } else if (setting instanceof BooleanSetting s) {
                if (elem.isJsonPrimitive()) {
                    if (elem.getAsJsonPrimitive().isBoolean()) {
                        s.setEnabled(elem.getAsBoolean());
                    } else if (elem.getAsJsonPrimitive().isNumber()) {
                        s.setEnabled(elem.getAsInt() != 0);
                    } else {
                        String str = elem.getAsString();
                        s.setEnabled("true".equalsIgnoreCase(str) || "1".equals(str) || "on".equalsIgnoreCase(str));
                    }
                }
                if (s.isBindable()) {
                    if (jsonObject.has(s.getName() + "#key")) {
                        try { s.setKeyCode(jsonObject.get(s.getName() + "#key").getAsInt()); } catch (Exception ignored) {}
                    }
                    if (jsonObject.has(s.getName() + "#hold")) {
                        try { s.setKeyHoldMode(jsonObject.get(s.getName() + "#hold").getAsBoolean()); } catch (Exception ignored) {}
                    }
                }
            } else if (setting instanceof StringSetting s) {
                if (elem.isJsonPrimitive()) {
                    s.setValue(elem.getAsString());
                }
            }
        } catch (Exception e) {
            ;
        }
    }

    

    

    


    private static final Map<String, String> LEGACY_ROOT_KEYS = Map.ofEntries(
            Map.entry("IllIIllI30", "theme"),
            Map.entry("lIIIIlIl30", "hudElements"),
            Map.entry("IIllIlIl30", "modules"),
            Map.entry("IlIllIlI31", "clientSyncKeyCode"),
            Map.entry("lIIllIlI31", "clientSyncKeyEnabled"),
            Map.entry("lIlIIlIl31", "configKeybinds")
    );

    private static final Map<String, String> LEGACY_MODULE_KEYS = Map.of(
            "IlIllIlI32", "enabled",
            "lIIllIlI32", "keyCode",
            "lIlIIlIl32", "keyCode2",
            "IllIIllI32", "settings"
    );

    private static final Map<String, String> LEGACY_HUD_KEYS = Map.of(
            "lIlllIIl31", "x",
            "IlIIlIII31", "y",
            "lIlIlIll31", "enabled",
            "IIlIlllI31", "settings"
    );

    private static final Map<String, String> LEGACY_KEYBIND_KEYS = Map.of(
            "IllIIllI31", "configName",
            "lIIIIlIl31", "key1",
            "IIllIlIl31", "key2"
    );

    



    private static void migrateObfuscatedConfig(JsonObject root) {
        if (root == null || root.has("modules") || root.has("theme")) {
            return; 

        }

        

        boolean hasLegacy = false;
        for (String legacyKey : LEGACY_ROOT_KEYS.keySet()) {
            if (root.has(legacyKey)) { hasLegacy = true; break; }
        }
        if (!hasLegacy) return;

        

        remapKeys(root, LEGACY_ROOT_KEYS);

        

        if (root.has("modules") && root.get("modules").isJsonObject()) {
            JsonObject modulesObj = root.getAsJsonObject("modules");
            for (Map.Entry<String, JsonElement> entry : modulesObj.entrySet()) {
                if (entry.getValue().isJsonObject()) {
                    remapKeys(entry.getValue().getAsJsonObject(), LEGACY_MODULE_KEYS);
                }
            }
        }

        if (root.has("hudElements") && root.get("hudElements").isJsonObject()) {
            JsonObject hudObj = root.getAsJsonObject("hudElements");
            for (Map.Entry<String, JsonElement> entry : hudObj.entrySet()) {
                if (entry.getValue().isJsonObject()) {
                    remapKeys(entry.getValue().getAsJsonObject(), LEGACY_HUD_KEYS);
                }
            }
        }

        if (root.has("configKeybinds") && root.get("configKeybinds").isJsonArray()) {
            for (JsonElement el : root.getAsJsonArray("configKeybinds")) {
                if (el.isJsonObject()) {
                    remapKeys(el.getAsJsonObject(), LEGACY_KEYBIND_KEYS);
                }
            }
        }
    }

    private static void remapKeys(JsonObject obj, Map<String, String> mapping) {
        for (Map.Entry<String, String> remap : mapping.entrySet()) {
            String oldKey = remap.getKey();
            String newKey = remap.getValue();
            if (obj.has(oldKey) && !obj.has(newKey)) {
                obj.add(newKey, obj.remove(oldKey));
            }
        }
    }

    /** Set just before a single {@link #applyConfigFromJson} call to skip its two keybind fields. */
    private volatile boolean keepOriginalKeybindsOnNextApply = false;

    void applyConfigFromJson(JsonObject root) {
        boolean keepOriginalKeybinds = keepOriginalKeybindsOnNextApply;
        keepOriginalKeybindsOnNextApply = false;
        String rawTheme = null;
        if (root.has("theme") && root.get("theme").isJsonPrimitive()) {
            rawTheme = root.get("theme").getAsString();
        } else if (root.has("guiTheme") && root.get("guiTheme").isJsonPrimitive()) {
            rawTheme = root.get("guiTheme").getAsString();
        }
        if (rawTheme != null && !rawTheme.isBlank()) {
            String norm = ClickGUIModule.normalizeThemeName(rawTheme);
            ThemeManager.getInstance().setCurrentTheme(norm);
            if (ClickGUIModule.theme != null) {
                ClickGUIModule.theme.setCurrentMode(norm);
            }
        }

        if (ClickGUIModule.backgroundOverlay != null) {
            JsonElement atmo = root.has("atmosphere") ? root.get("atmosphere")
                    : root.has("mist") ? root.get("mist") : null;
            if (atmo != null && atmo.isJsonPrimitive()) {
                if (atmo.getAsJsonPrimitive().isBoolean()) {
                    ClickGUIModule.backgroundOverlay.setCurrentMode(atmo.getAsBoolean() ? "Mist" : "None");
                } else {
                    String s = atmo.getAsString();
                    if ("true".equalsIgnoreCase(s) || "on".equalsIgnoreCase(s)) {
                        ClickGUIModule.backgroundOverlay.setCurrentMode("Mist");
                    } else if ("false".equalsIgnoreCase(s) || "off".equalsIgnoreCase(s)) {
                        ClickGUIModule.backgroundOverlay.setCurrentMode("None");
                    } else {
                        ClickGUIModule.backgroundOverlay.setCurrentMode(s);
                    }
                }
            }
        }

        if (root.has("hudElements") && root.get("hudElements").isJsonObject()
                && Apexx.INSTANCE != null && Apexx.INSTANCE.getHudManager() != null) {
            JsonObject hudObj = root.getAsJsonObject("hudElements");
            for (Map.Entry<String, JsonElement> entry : hudObj.entrySet()) {
                if (!entry.getValue().isJsonObject()) continue;
                HUDElement element = Apexx.INSTANCE.getHudManager().getElement(entry.getKey());
                if (element == null) continue;

                JsonObject elemObj = entry.getValue().getAsJsonObject();
                // A malformed position/flag in an old or hand-edited config skips that field, not the whole config.
                try { if (elemObj.has("x")) element.setX(elemObj.get("x").getAsDouble()); } catch (RuntimeException ignored) {}
                try { if (elemObj.has("y")) element.setY(elemObj.get("y").getAsDouble()); } catch (RuntimeException ignored) {}
                try { if (elemObj.has("enabled")) element.setEnabled(elemObj.get("enabled").getAsBoolean()); } catch (RuntimeException ignored) {}

                if (elemObj.has("settings") && elemObj.get("settings").isJsonObject()) {
                    JsonObject settingsObj = elemObj.getAsJsonObject("settings");
                    List<Setting> settings = element.getSettings();
                    if (settings != null) {
                        for (Setting setting : settings) {
                            loadSetting(settingsObj, setting);
                        }
                    }
                }
            }
        }

        if (root.has("modules") && root.get("modules").isJsonObject()) {
            Map<String, ModuleData> modules = parseModules(root.getAsJsonObject("modules"));
            Map<String, ModuleData> moduleLookup = buildModuleLookup(modules);
            List<Module> configured = new ArrayList<>(Apexx.INSTANCE.getModuleManager().getModules());
            for (Module module : configured) {
                if (!"Booster".equalsIgnoreCase(module.getName())
                        && resolveModuleData(modules, moduleLookup, module.getName()) != null) {
                    module.setEnabled(false);
                }
            }
            for (Module module : configured) {
                if ("Booster".equalsIgnoreCase(module.getName())) continue;
                ModuleData moduleData = resolveModuleData(modules, moduleLookup, module.getName());
                if (moduleData != null) {
                    if (!keepOriginalKeybinds) {
                        module.setKeyCode(moduleData.keyCode);
                        module.setKeyCode2(moduleData.keyCode2);
                    }
                    module.setHoldMode(moduleData.holdMode);
                    module.setFavorite(moduleData.favorite);
                    if (moduleData.settings != null) {
                        for (Setting setting : module.getSettings()) {
                            loadSetting(moduleData.settings, setting);
                        }
                    }
                }
            }
        }

        if (root.has("configKeybinds") && root.get("configKeybinds").isJsonArray()) {
            ConfigKeybindManager.getInstance().clear();
            for (JsonElement el : root.getAsJsonArray("configKeybinds")) {
                if (!el.isJsonObject()) continue;
                JsonObject obj = el.getAsJsonObject();
                String cfgName = obj.has("configName") ? obj.get("configName").getAsString() : null;
                if (cfgName == null || cfgName.isBlank()) continue;
                int k1 = obj.has("key1") ? obj.get("key1").getAsInt() : -1;
                int k2 = obj.has("key2") ? obj.get("key2").getAsInt() : -1;
                if (k1 != -1) ConfigKeybindManager.getInstance().setKeybind(cfgName, k1, k2);
            }
        }

        // Install settings and config keybinds before any onEnable callback can run.
        if (root.has("modules") && root.get("modules").isJsonObject()) {
            Map<String, ModuleData> modules = parseModules(root.getAsJsonObject("modules"));
            Map<String, ModuleData> lookup = buildModuleLookup(modules);
            for (Module module : new ArrayList<>(Apexx.INSTANCE.getModuleManager().getModules())) {
                if ("Booster".equalsIgnoreCase(module.getName())) continue;
                ModuleData data = resolveModuleData(modules, lookup, module.getName());
                if (data != null) module.setEnabled(data.enabled && canEnableModules());
            }
        }
    }

    private Map<String, ModuleData> parseModules(JsonObject modulesObj) {
        Map<String, ModuleData> result = new HashMap<>();
        for (Map.Entry<String, JsonElement> entry : modulesObj.entrySet()) {
            if (!entry.getValue().isJsonObject()) continue;
            JsonObject obj = entry.getValue().getAsJsonObject();
            ModuleData md = new ModuleData();
            md.enabled = configBoolean(obj, "enabled", false);
            md.keyCode = configInteger(obj, "keyCode", -1);
            md.keyCode2 = configInteger(obj, "keyCode2", -1);
            md.holdMode = configBoolean(obj, "holdMode", false);
            md.favorite = configBoolean(obj, "favorite", false);
            md.settings = obj.has("settings") && obj.get("settings").isJsonObject()
                    ? obj.getAsJsonObject("settings") : new JsonObject();
            result.put(entry.getKey(), md);
        }
        return result;
    }

    private static boolean configBoolean(JsonObject object, String key, boolean fallback) {
        try {
            return object.has(key) && !object.get(key).isJsonNull()
                    ? object.get(key).getAsBoolean() : fallback;
        } catch (RuntimeException malformedValue) {
            return fallback;
        }
    }

    private static int configInteger(JsonObject object, String key, int fallback) {
        try {
            return object.has(key) && !object.get(key).isJsonNull()
                    ? object.get(key).getAsInt() : fallback;
        } catch (RuntimeException malformedValue) {
            return fallback;
        }
    }

    private boolean canEnableModules() {
        try {
            // Config-paste choke point: enabling modules from ANY config (live poll, offline
            // mirror, or GUI import) requires a live verified session grant. The old
            // isFreeVersion() shortcut is removed so freeVersion can never re-open this.
            return ApexxAuth.isAuthenticated() && ApexxAuth.authGateToken(CONFIG_AUTH_GATE_SALT) != 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private Map<String, ModuleData> buildModuleLookup(Map<String, ModuleData> modules) {
        Map<String, ModuleData> lookup = new HashMap<>();
        if (modules == null) {
            return lookup;
        }

        for (Map.Entry<String, ModuleData> entry : modules.entrySet()) {
            String key = entry.getKey();
            ModuleData value = entry.getValue();
            if (key == null || key.isBlank() || value == null) {
                continue;
            }

            putIfAbsent(lookup, normalizeLookupKey(key), value);
            putIfAbsent(lookup, normalizeAliasLookupKey(key), value);
        }
        return lookup;
    }

    private ModuleData resolveModuleData(Map<String, ModuleData> modules, Map<String, ModuleData> lookup, String moduleName) {
        if (modules == null || moduleName == null || moduleName.isBlank()) {
            return null;
        }

        ModuleData exact = modules.get(moduleName);
        if (exact != null) {
            return exact;
        }

        if (lookup == null || lookup.isEmpty()) {
            return null;
        }

        ModuleData normalized = lookup.get(normalizeLookupKey(moduleName));
        if (normalized != null) {
            return normalized;
        }

        ModuleData aliased = lookup.get(normalizeAliasLookupKey(moduleName));
        if (aliased != null) {
            return aliased;
        }
        return "AutoAnchor".equalsIgnoreCase(moduleName) ? legacyAnchorData(modules) : null;
    }

    /**
     * Configs saved before KeyAnchor and SilentSafeAnchor were merged into AutoAnchor: their settings
     * carry over by name, and the mode is the one the module that was in use had. When both were set up,
     * the one that was enabled (else the one with a key) wins, SilentSafeAnchor first.
     */
    static ModuleData legacyAnchorData(Map<String, ModuleData> modules) {
        ModuleData safe = modules.get("SilentSafeAnchor");
        ModuleData macro = modules.get("KeyAnchor");
        ModuleData source;
        String mode;
        if (safe != null && (macro == null || safe.enabled || (!macro.enabled && safe.keyCode != -1))) {
            source = safe;
            mode = "Safe Anchor";
        } else if (macro != null) {
            source = macro;
            mode = "Anchor";
        } else {
            return null;
        }
        ModuleData copy = new ModuleData();
        copy.enabled = source.enabled;
        copy.keyCode = source.keyCode;
        copy.keyCode2 = source.keyCode2;
        copy.holdMode = source.holdMode;
        copy.favorite = source.favorite;
        copy.settings = source.settings == null ? new JsonObject() : source.settings.deepCopy();
        copy.settings.addProperty("Mode", mode);
        return copy;
    }

    // Current setting name -> name it was saved under before a rename, so saved configs keep their value.
    private static final Map<String, String> RENAMED_SETTINGS = Map.of(
            "Through Walls", "Wall Bypass",
            "Urgent Reequip", "Reequip Dwell Bypass",
            "Sword Cooldown", "Sword Cooldown Min",
            "Axe Cooldown", "Axe Cooldown Min");

    private String resolveSettingKey(JsonObject jsonObject, String settingName) {
        if (jsonObject == null || settingName == null || settingName.isBlank()) {
            return null;
        }

        if (jsonObject.has(settingName)) {
            return settingName;
        }

        if ("Separator".equals(settingName) && jsonObject.has("Seperator")) {
            return "Seperator";
        }

        String legacyName = RENAMED_SETTINGS.get(settingName);
        if (legacyName != null && jsonObject.has(legacyName)) {
            return legacyName;
        }

        List<String> aliasCandidates = switch (settingName.toLowerCase()) {
            case "atmosphere", "mist", "background overlay" -> List.of("Atmosphere", "atmosphere", "Mist", "mist", "Background Overlay", "backgroundOverlay", "background_overlay");
            case "theme", "gui theme" -> List.of("Theme", "theme", "guiTheme", "gui_theme", "uiTheme", "ui_theme");
            case "backdrop blur", "blur bg", "blur", "blur background" -> List.of("Blur BG", "blurBg", "blur_bg", "Blur", "blur", "Blur Background", "blurBackground", "blur_background");
            default -> List.of();
        };
        for (String candidate : aliasCandidates) {
            if (jsonObject.has(candidate)) {
                return candidate;
            }
        }

        String normalizedTarget = normalizeLookupKey(settingName);
        String aliasTarget = normalizeAliasLookupKey(settingName);
        for (Map.Entry<String, JsonElement> entry : jsonObject.entrySet()) {
            String key = entry.getKey();
            if (key == null || key.isBlank()) {
                continue;
            }
            if (normalizeLookupKey(key).equals(normalizedTarget)
                    || normalizeAliasLookupKey(key).equals(aliasTarget)) {
                return key;
            }
        }
        return null;
    }

    private void putIfAbsent(Map<String, ModuleData> map, String key, ModuleData value) {
        if (key == null || key.isBlank() || value == null) {
            return;
        }
        map.putIfAbsent(key, value);
    }

    private String normalizeLookupKey(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toLowerCase().replaceAll("[^a-z0-9]", "");
    }

    private String normalizeAliasLookupKey(String value) {
        String normalized = normalizeLookupKey(value);
        if (normalized.startsWith("auto") && normalized.length() > 4) {
            return normalized.substring(4);
        }
        return normalized;
    }

    public synchronized String exportConfigJson(String name) {
        if (name == null || name.isBlank() || name.equalsIgnoreCase(lastLoadedConfigName)) {
            if (cachedConfigJson != null && isValidConfigJson(cachedConfigJson)) {
                return cachedConfigJson;
            }
            save(lastLoadedConfigName != null ? lastLoadedConfigName : "default");
            return cachedConfigJson;
        }
        return readLocalConfig(name);
    }

    public synchronized String exportConfigJsonForCloud(String name) {
        String rawJson = exportConfigJson(name);
        if (rawJson == null || rawJson.isBlank()) {
            return null;
        }
        try {
            JsonElement parsed = JsonParser.parseString(rawJson);
            JsonObject sanitized = sanitizeCloudConfig(parsed);
            if (sanitized == null) {
                return null;
            }
            return gson.toJson(sanitized);
        } catch (Exception ignored) {
            return null;
        }
    }

    public synchronized boolean importConfigJson(String name, String rawJson, boolean applyNow) {
        return importConfigJson(name, rawJson, applyNow, null);
    }

    /** Import a config, applying it on the client thread before reporting completion. */
    public boolean importConfigJson(String name, String rawJson, boolean applyNow,
            Consumer<Boolean> completion) {
        if (rawJson == null || rawJson.isBlank() || !isValidConfigJson(rawJson)) {
            return false;
        }
        // Never import another user's macros or scripts, or their per-user prefs (server map, keys, ...).
        rawJson = ClientPrefs.stripJson(UserContentBlob.stripJson(rawJson));
        final String importedJson = rawJson;

        String safeName = sanitizeConfigName(name);
        if (!applyNow) {
            persistLocalConfig(safeName, importedJson);
            return true;
        }

        MinecraftClient client;
        try {
            client = MinecraftClient.getInstance();
        } catch (Throwable unavailable) {
            return false;
        }
        if (client == null) return false;

        Runnable apply = () -> {
            configLoadGeneration.incrementAndGet();
            cancelPendingNamedConfigLoad();
            if (!loadFromJson(importedJson)) {
                if (completion != null) notifyImportCompletion(completion, false);
                return;
            }
            persistLocalConfig(safeName, importedJson);
            cachedConfigJson = importedJson;
            userContentOwned = true;
            cachedConfigHash = Integer.toHexString(importedJson.hashCode());
            lastSaveMs = System.currentTimeMillis();
            this.lastLoadedConfigName = safeName;
            persistLastConfigName(safeName);
            markLoaded(name);
            if (completion != null) notifyImportCompletion(completion, true);
        };
        try {
            if (client.isOnThread()) apply.run();
            else client.execute(apply);
        } catch (Throwable rejected) {
            LOGGER.debug("[ConfigManager] Client stopped before imported config could be applied");
            return false;
        }
        return true;
    }

    public synchronized boolean importConfigJsonForCloud(String name, String rawJson, boolean applyNow) {
        return importConfigJsonForCloud(name, rawJson, applyNow, null);
    }

    public boolean importConfigJsonForCloud(String name, String rawJson, boolean applyNow,
            Consumer<Boolean> completion) {
        return importConfigJsonForCloud(name, rawJson, applyNow, false, completion);
    }

    /**
     * As {@link #importConfigJsonForCloud(String, String, boolean, Consumer)}, but with the option to
     * keep this client's own module keybinds instead of adopting the imported config's bindings - the
     * rest of the config (modules, settings, theme, HUD layout, ...) is still applied normally.
     */
    public boolean importConfigJsonForCloud(String name, String rawJson, boolean applyNow,
            boolean keepOriginalKeybinds, Consumer<Boolean> completion) {
        if (rawJson == null || rawJson.isBlank()) {
            return false;
        }
        keepOriginalKeybindsOnNextApply = keepOriginalKeybinds;

        JsonObject sanitized;
        try {
            JsonElement parsed = JsonParser.parseString(rawJson);
            sanitized = sanitizeCloudConfig(parsed);
        } catch (Exception e) {
            LOGGER.debug("Failed to sanitize cloud config '{}': {}", name, e.getMessage());
            return false;
        }

        if (sanitized == null) {
            return false;
        }

        return importConfigJson(name, gson.toJson(sanitized), applyNow, completion);
    }

    private static void notifyImportCompletion(Consumer<Boolean> completion, boolean success) {
        try {
            completion.accept(success);
        } catch (Throwable failure) {
            LOGGER.debug("[ConfigManager] Config import completion failed: {}", failure.getMessage());
        }
    }

    /** The Premium macro/script studio, or null in the free payload (or before the payload has loaded). */
    private StudioProvider studio() {
        StudioProvider studio;
        try {
            studio = ModuleAccess.getHook(StudioProvider.class);
        } catch (Throwable t) {
            return null;
        }
        // Content parked before the studio registered is handed over at the first chance.
        if (studio != null && (parkedMacros != null || parkedScripts != null)) adoptParkedUserContent(studio);
        return studio;
    }

    /**
     * Replace the in-memory macros/scripts with those in the blob; keys that are absent leave them alone. Without the
     * studio (free payload, or the payload is not loaded yet) the arrays are parked untouched and written back as they
     * are, so a free account never loses the macros and scripts saved in its cloud config.
     */
    void applyUserContent(JsonObject root) {
        try {
            StudioProvider studio = studio();
            JsonElement m = root.get(UserContentBlob.MACROS);
            if (m != null && m.isJsonArray()) {
                if (studio != null) studio.restoreMacros(m.getAsJsonArray());
                else parkedMacros = m.getAsJsonArray().deepCopy();
                userContentOwned = true;
            }
            JsonElement sc = root.get(UserContentBlob.SCRIPTS);
            if (sc != null && sc.isJsonArray()) {
                if (studio != null) studio.restoreScripts(sc.getAsJsonArray());
                else parkedScripts = sc.getAsJsonArray().deepCopy();
                userContentOwned = true;
            }
        } catch (RuntimeException e) {
            LOGGER.debug("[ConfigManager] Could not restore macros/scripts: {}", e.getMessage());
        }
    }

    /**
     * The studio module was just built (premium payload loaded): hand it the macros/scripts parked while it was
     * absent. Restoring scripts registers modules, so it runs on the client thread.
     */
    public void adoptParkedUserContent(StudioProvider studio) {
        if (studio == null) return;
        JsonArray macros = parkedMacros;
        JsonArray scripts = parkedScripts;
        if (macros == null && scripts == null) return;
        runOnClientThread(() -> {
            try {
                if (macros != null && parkedMacros == macros) {
                    studio.restoreMacros(macros);
                    parkedMacros = null;
                }
                if (scripts != null && parkedScripts == scripts) {
                    studio.restoreScripts(scripts);
                    parkedScripts = null;
                }
            } catch (RuntimeException e) {
                LOGGER.debug("[ConfigManager] Could not hand parked macros/scripts to the studio: {}", e.getMessage());
            }
        });
    }

    /** True when the live macro/script managers hold anything (privacy guard for sharing). */
    public boolean hasLiveUserContent() {
        try {
            StudioProvider studio = studio();
            if (studio != null) return studio.hasUserContent();
            JsonArray macros = parkedMacros;
            JsonArray scripts = parkedScripts;
            return (macros != null && !macros.isEmpty()) || (scripts != null && !scripts.isEmpty());
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Macros or IDE scripts changed. Pushes the active cloud blob after a short quiet period so a burst
     * of IDE saves becomes one request. Safe to call from any thread; never writes to disk.
     */
    public void onUserContentChanged() {
        if (!startupConfigApplied) return;
        userContentOwned = true;
        synchronized (userContentLock) {
            if (userContentFlush != null) userContentFlush.cancel(false);
            try {
                userContentFlush = USER_CONTENT_TIMER.schedule(
                        () -> runOnClientThread(this::flushUserContent), 1500L, TimeUnit.MILLISECONDS);
            } catch (RuntimeException rejected) {
                userContentFlush = null;
            }
        }
    }

    private void flushUserContent() {
        if (Apexx.isTornDown() || CloudConfigSync.isApplying()) return;
        autosave(lastLoadedConfigName);
        CloudConfigSync.saveIfChangedNow(exportCurrentConfigJsonForCloud());
    }

    // ---- Loaded named config + unsaved changes ---------------------------------------------------
    //
    // Automatic saves go to the hidden working slot (see CloudConfigSync), so the named config the user loaded only
    // changes on Overwrite/Save. These fields remember which named config the live state came from and the state it
    // had then (module/HUD/theme only: per-user prefs, macros and scripts are account data, not part of a config).

    private volatile String loadedConfigName;
    private volatile JsonObject loadedBaseline; // null while a config is loaded = changed against an unknown baseline
    private volatile boolean unsavedCache;
    private volatile long unsavedCheckedAtMs;
    private volatile String unsavedNoticeFor;
    private static final long UNSAVED_RECHECK_MS = 750L;

    /** Display name of the named config the live settings came from, or null. */
    public String getLoadedConfigName() {
        return loadedConfigName;
    }

    /** A named config was just applied (client thread): it is the Overwrite target and the clean baseline. */
    public void markLoaded(String name) {
        setLoaded(name, currentComparableTree());
    }

    /** {@code savedJson} was just written to the named config {@code name}; the live state matches it. */
    public void markSavedAs(String name, String savedJson) {
        JsonObject baseline = null;
        try {
            if (savedJson != null) baseline = comparable(sanitizeCloudConfig(JsonParser.parseString(savedJson)));
        } catch (RuntimeException ignored) {
            baseline = null;
        }
        setLoaded(name, baseline != null ? baseline : currentComparableTree());
    }

    /** The working copy was restored at launch; {@code dirty} is whether it differed from {@code name} back then. */
    public void restoreLoadedConfig(String name, boolean dirty) {
        setLoaded(name, dirty ? null : currentComparableTree());
    }

    /** A cloud config was renamed ({@code newName} null = deleted); keep the Overwrite target in step. */
    public void onConfigRenamed(String oldName, String newName) {
        String loaded = loadedConfigName;
        if (loaded == null || oldName == null || !loaded.equalsIgnoreCase(oldName.trim())) return;
        if (newName == null || newName.isBlank()) {
            loadedConfigName = null;
            loadedBaseline = null;
        } else {
            loadedConfigName = newName.trim();
        }
        unsavedCheckedAtMs = 0L;
    }

    private void setLoaded(String name, JsonObject baseline) {
        String clean = name == null || name.isBlank() || CloudConfigs.isWorkingSlot(name.trim()) ? null : name.trim();
        loadedConfigName = clean;
        loadedBaseline = clean == null ? null : baseline;
        unsavedCache = clean != null && baseline == null;
        unsavedCheckedAtMs = System.currentTimeMillis();
        unsavedNoticeFor = null;
    }

    /**
     * True when the live settings differ from the loaded named config. Rebuilds the comparison at most every
     * {@value #UNSAVED_RECHECK_MS} ms and only on the client thread (it reads module state); elsewhere it returns
     * the last answer.
     */
    public boolean hasUnsavedChanges() {
        return hasUnsavedChanges(false);
    }

    private boolean hasUnsavedChanges(boolean force) {
        if (loadedConfigName == null) return false;
        JsonObject baseline = loadedBaseline;
        if (baseline == null) return true;
        long now = System.currentTimeMillis();
        if ((force || now - unsavedCheckedAtMs >= UNSAVED_RECHECK_MS) && onClientThread()) {
            JsonObject current = currentComparableTree();
            unsavedCache = current != null && !current.equals(baseline);
            unsavedCheckedAtMs = now;
        }
        return unsavedCache;
    }

    /** The marker the working slot carries ({@code from}, {@code id}, {@code dirty}), or null with no loaded config. */
    JsonObject workingMarker(String loadedId) {
        String name = loadedConfigName;
        if (name == null) return null;
        JsonObject marker = new JsonObject();
        marker.addProperty("from", name);
        if (loadedId != null && !loadedId.isBlank()) marker.addProperty("id", loadedId);
        marker.addProperty("dirty", hasUnsavedChanges(true));
        return marker;
    }

    /** One notice per loaded config the first time its changes are saved only to the working copy. */
    void noticeUnsavedChangesOnce() {
        String name = loadedConfigName;
        if (name == null || name.equals(unsavedNoticeFor) || !onClientThread() || !hasUnsavedChanges(true)) return;
        unsavedNoticeFor = name;
        try {
            interstellarTikiTiks.apexx.client.util.Notifier.info("Configs",
                    "Unsaved changes — Overwrite to keep them in \"" + name + "\"");
        } catch (Throwable ignored) {
            // A notice is cosmetic; never let it break a save.
        }
    }

    private static boolean onClientThread() {
        try {
            MinecraftClient client = MinecraftClient.getInstance();
            return client != null && client.isOnThread();
        } catch (Throwable unavailable) {
            return false;
        }
    }

    /** The live config in the shape a named config is compared in, or null if it cannot be built. */
    private JsonObject currentComparableTree() {
        try {
            return comparable(sanitizeCloudConfig(buildMergedConfigTree()));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Drops the account-level keys (prefs, macros, scripts, working marker) so only the config itself is compared. */
    static JsonObject comparable(JsonObject sanitized) {
        if (sanitized == null) return null;
        // Round-trip through text so a live tree (Float/Integer values) and a parsed blob compare by printed value.
        JsonObject copy = JsonParser.parseString(sanitized.toString()).getAsJsonObject();
        copy.remove(ClientPrefs.KEY);
        copy.remove(UserContentBlob.MACROS);
        copy.remove(UserContentBlob.SCRIPTS);
        copy.remove(CloudConfigSync.WORKING_MARKER);
        return copy;
    }

    private final Object clientPrefsLock = new Object();
    private java.util.concurrent.ScheduledFuture<?> clientPrefsFlush;

    /**
     * A per-user pref changed (see {@link ClientPrefs}). Pushes the active cloud blob after a short quiet
     * period, like macros. Safe to call from any thread; never writes to disk.
     */
    public void onClientPrefsChanged() {
        if (!startupConfigApplied) return; // the next save after startup carries it
        synchronized (clientPrefsLock) {
            if (clientPrefsFlush != null) clientPrefsFlush.cancel(false);
            try {
                clientPrefsFlush = USER_CONTENT_TIMER.schedule(
                        () -> runOnClientThread(this::flushUserContent), 1500L, TimeUnit.MILLISECONDS);
            } catch (RuntimeException rejected) {
                clientPrefsFlush = null;
            }
        }
    }

    private JsonObject sanitizeCloudConfig(JsonElement parsed) {
        if (parsed == null || !parsed.isJsonObject()) {
            return null;
        }

        JsonObject root = parsed.getAsJsonObject().deepCopy();
        migrateObfuscatedConfig(root);
        ConfigMigrations.migrate(root);
        root.remove("webSyncCode");
        root.remove("webSyncAutoLoad");
        if (!root.has("modules") || !root.get("modules").isJsonObject()) {
            // The server's /api/config "save" REQUIRES a top-level `modules` OBJECT and answers
            // BAD_REQUEST without one -- it is the whole of its isBlob test. This branch used to
            // return the blob untouched, so any config that legitimately had no modules map was
            // uploaded in a shape the server always rejected, and the user just saw "BAD_REQUEST".
            //
            // That is reachable more easily than it looks: isValidConfigJson() deliberately accepts
            // theme-only and hudElements-only configs, and migrateObfuscatedConfig() bails early on
            // a partially-migrated config (plain `theme`, still-obfuscated module key), leaving no
            // `modules` behind either.
            //
            // An empty map is a valid config and round-trips to exactly the same client state, so
            // filling it in costs nothing and satisfies the contract.
            root.add("modules", new JsonObject());
            return root;
        }

        JsonObject modules = root.getAsJsonObject("modules");
        List<String> toRemove = new ArrayList<>();
        for (Map.Entry<String, JsonElement> entry : modules.entrySet()) {
            String key = entry.getKey();
            if (key != null && isCloudRestrictedModule(key)) {
                toRemove.add(key);
            }
        }

        for (String key : toRemove) {
            modules.remove(key);
        }
        return root;
    }

    private boolean isCloudRestrictedModule(String moduleName) {
        String normalized = normalizeLookupKey(moduleName);
        return CLOUD_RESTRICTED_MODULE_KEYS.contains(normalized);
    }

    private String sanitizeConfigName(String name) {
        if (name == null || name.isBlank()) {
            return "default";
        }
        String normalized = name.trim().replaceAll("[^A-Za-z0-9_.-]", "_");
        if (normalized.isBlank()) {
            return "default";
        }
        if (normalized.length() > 64) {
            normalized = normalized.substring(0, 64);
        }
        return normalized;
    }

    public boolean rename(String oldName, String newName) {
        if (!saveLocalConfig || oldName == null || newName == null) return false;
        String safeOld = sanitizeConfigName(oldName);
        String safeNew = sanitizeConfigName(newName);
        if (safeOld.equalsIgnoreCase(safeNew)) return true;
        if ("default".equalsIgnoreCase(safeOld)) return false; // cannot rename default
        try {
            Path cfgDir = resolveConfigDir();
            Path oldFile = cfgDir.resolve(safeOld + ".json");
            Path newFile = cfgDir.resolve(safeNew + ".json");
            if (!Files.exists(oldFile)) return false;
            Files.move(oldFile, newFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            if (safeOld.equals(lastLoadedConfigName)) {
                lastLoadedConfigName = safeNew;
                persistLastConfigName(safeNew);
            }
            return true;
        } catch (Exception e) {
            LOGGER.debug("[ConfigManager] Failed to rename config: {}", e.getMessage());
            return false;
        }
    }

    public boolean delete(String name) {
        if (!saveLocalConfig || name == null || "default".equalsIgnoreCase(name)) return false;
        try {
            String safe = sanitizeConfigName(name);
            Path namedFile = resolveConfigDir().resolve(safe + ".json");
            boolean deleted = Files.deleteIfExists(namedFile);
            if (deleted && safe.equals(lastLoadedConfigName)) {
                load("default");
            }
            return deleted;
        } catch (Exception e) {
            return false;
        }
    }

    public List<String> getAvailableConfigs() {
        List<String> configs = new ArrayList<>();
        configs.add("default");
        Path cfgDir = resolveConfigDir();
        if (saveLocalConfig && Files.isDirectory(cfgDir)) {
            try (var stream = Files.list(cfgDir)) {
                stream.filter(p -> p.getFileName().toString().endsWith(".json"))
                      .map(p -> {
                          String fn = p.getFileName().toString();
                          return fn.substring(0, fn.length() - 5);
                      })
                      .filter(n -> !configs.contains(n))
                      .forEach(configs::add);
            } catch (Exception ignored) {}
        }
        return configs;
    }

    static class HUDElementData {
        public double x, y;
        public boolean enabled;
        public JsonObject settings;

        public HUDElementData() {}

        public HUDElementData(double x, double y, boolean enabled) {
            this.x = x;
            this.y = y;
            this.enabled = enabled;
        }
    }

    static class ConfigData {
        public String theme;
        public String atmosphere;

        public Map<String, HUDElementData> hudElements = new HashMap<>();
        public Map<String, ModuleData> modules = new HashMap<>();
        public Integer clientSyncKeyCode;
        public Boolean clientSyncKeyEnabled;
        public String webSyncCode;
        public Boolean webSyncAutoLoad;
        public List<ConfigKeybindEntry> configKeybinds;
    }

    static class ConfigKeybindEntry {
        public String configName;
        public int key1 = -1;
        public int key2 = -1;
    }

    static class ModuleData {
        public boolean enabled;
        public int keyCode;
        public int keyCode2 = -1;
        public boolean holdMode = false;
        public boolean favorite = false;
        public JsonObject settings = new JsonObject();
    }
}
