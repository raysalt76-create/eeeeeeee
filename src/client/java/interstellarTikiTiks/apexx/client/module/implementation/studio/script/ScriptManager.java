package interstellarTikiTiks.apexx.client.module.implementation.studio.script;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import interstellarTikiTiks.apexx.client.Apexx;
import interstellarTikiTiks.apexx.client.manager.ModuleManager;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Runtime for Apexx scripts. Scripts live in memory for the session and persist through the
 * user's own cloud config blob ({@link #localScriptsJson()} / {@link #replaceLocalFromJson}).
 * There is no network or local-server sync of any kind.
 *
 * <p>Sync is fail-soft and hot-reloading:</p>
 * <ul>
 *   <li>A script whose source is unchanged keeps its live module (and its
 *       {@code var} state).</li>
 *   <li>A changed script is removed and re-registered from the fresh AST.</li>
 *   <li>A script that fails to lex/parse/compile is quarantined — its error string
 *       is stored and surfaced; other scripts are unaffected and never crash.</li>
 *   <li>All {@code addModule}/{@code removeModule} mutations are deferred to the
 *       END of the tick, never run while {@code ModuleManager} is iterating.</li>
 * </ul>
 */
public final class ScriptManager {

    private static final Logger LOGGER = org.slf4j.helpers.NOPLogger.NOP_LOGGER;
    private static final ScriptManager INSTANCE = new ScriptManager();

    private boolean initialized = false;

    // Live script modules we own, keyed by script name → its source hash.
    private final Map<String, String> liveHashes = new HashMap<>();
    // Per-script parse/compile error, surfaced to the GUI (name → message).
    private final Map<String, String> errors = new LinkedHashMap<>();

    // Scripts written in the in-game IDE. They live in memory for the session.
    private final Map<String, LocalScript> localScripts = new LinkedHashMap<>();

    /** A script authored in the IDE. {@code label} is the file-style name the player chose. */
    public record LocalScript(String label, String source, boolean enabled) { }

    /** A compile problem: 1-based line (or -1 when unknown) and a readable message. */
    public record Problem(int line, String message) { }

    private ScriptManager() {}

    public static ScriptManager getInstance() {
        return INSTANCE;
    }

    /** Call once from the client ModInitializer. Registers the event tick. */
    public void init() {
        if (initialized) return;
        initialized = true;
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            try {
                if (!interstellarTikiTiks.apexx.client.Apexx.isTornDown()) ScriptEvents.tick(client);
            } catch (Throwable t) {
                LOGGER.debug("[ScriptManager] event error: {}", t.toString());
            }
        });
        LOGGER.info("[ScriptManager] Apexx scripting runtime initialized");
    }

    // ── IDE support ──────────────────────────────────────────────────────────

    /** Scripts written in the IDE this session, in creation order. */
    public List<LocalScript> localScripts() {
        synchronized (localScripts) {
            return new ArrayList<>(localScripts.values());
        }
    }

    /** Adds or replaces an IDE script and applies it now. Must be called on the client thread. */
    public void saveLocal(String label, String source, boolean enabled) {
        if (label == null || label.isBlank() || source == null) return;
        syncFromLive(label);
        synchronized (localScripts) {
            localScripts.put(label, new LocalScript(label, source, enabled));
        }
        applyEntries(effectiveEntries());
        interstellarTikiTiks.apexx.client.config.ConfigManager.getInstance().onUserContentChanged();
    }

    /** IDE scripts as {name, enabled, source} objects, for the cloud config blob. */
    public JsonArray localScriptsJson() {
        try { syncFromLive(null); } catch (Throwable ignored) { }
        JsonArray array = new JsonArray();
        for (LocalScript local : localScripts()) {
            JsonObject o = new JsonObject();
            o.addProperty("name", local.label());
            o.addProperty("enabled", local.enabled());
            o.addProperty("source", local.source());
            JsonObject state = savedState.get(local.label());
            if (state != null) o.add("state", state.deepCopy());
            array.add(o);
        }
        return array;
    }

    /** Replace the IDE scripts with those from the user's own cloud blob. Client thread only. */
    public void replaceLocalFromJson(JsonArray array) {
        if (array == null) return;
        try { syncFromLive(null); } catch (Throwable ignored) { }
        Map<String, LocalScript> next = new LinkedHashMap<>();
        for (JsonElement el : array) {
            if (el == null || !el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            String name = o.has("name") && o.get("name").isJsonPrimitive() ? o.get("name").getAsString() : null;
            String source = o.has("source") && o.get("source").isJsonPrimitive() ? o.get("source").getAsString() : null;
            if (name == null || name.isBlank() || source == null) continue;
            boolean enabled = !o.has("enabled") || !o.get("enabled").isJsonPrimitive() || o.get("enabled").getAsBoolean();
            next.put(name, new LocalScript(name, source, enabled));
            if (o.has("state") && o.get("state").isJsonObject()) savedState.put(name, o.getAsJsonObject("state").deepCopy());
        }
        synchronized (localScripts) {
            localScripts.clear();
            localScripts.putAll(next);
        }
        // Force re-registration so the restored keybinds/settings are applied to fresh modules.
        for (String label : next.keySet()) {
            String module = labelToModule.getOrDefault(label, label);
            liveHashes.remove(module);
            removeLive(module);
        }
        applyEntries(effectiveEntries());
    }

    /** Renames an IDE script, keeping its live module, keybinds and settings. Client thread only. */
    public void renameLocal(String old, String wanted, String source, boolean enabled) {
        if (old == null || wanted == null || wanted.isBlank() || source == null) return;
        try { syncFromLive(null); } catch (Throwable ignored) { }
        JsonObject state = savedState.remove(old);
        if (state != null) savedState.put(wanted, state);
        String module = labelToModule.remove(old);
        if (module != null) labelToModule.put(wanted, module);
        synchronized (localScripts) {
            Map<String, LocalScript> next = new LinkedHashMap<>();
            boolean placed = false;
            for (Map.Entry<String, LocalScript> e : localScripts.entrySet()) {
                if (e.getKey().equals(old)) {
                    next.put(wanted, new LocalScript(wanted, source, enabled));
                    placed = true;
                } else if (!e.getKey().equals(wanted)) {
                    next.put(e.getKey(), e.getValue());
                }
            }
            if (!placed) next.put(wanted, new LocalScript(wanted, source, enabled));
            localScripts.clear();
            localScripts.putAll(next);
        }
        applyEntries(effectiveEntries());
        interstellarTikiTiks.apexx.client.config.ConfigManager.getInstance().onUserContentChanged();
    }

    /** The live module's on/off state of a compiled, non-oneshot script, or null when there is none. */
    public Boolean liveEnabledOrNull(String label) {
        try {
            ModuleManager mm = Apexx.getInstance() == null ? null : Apexx.getInstance().getModuleManager();
            var m = mm == null ? null : mm.getModule(labelToModule.getOrDefault(label, label));
            if (!(m instanceof ScriptModule sm) || sm.isOneShot()) return null;
            return sm.isEnabled();
        } catch (Throwable t) {
            return null;
        }
    }

    /** Removes an IDE script (and unloads its module). Must be called on the client thread. */
    public boolean deleteLocal(String label) {
        try { syncFromLive(null); } catch (Throwable ignored) { }
        boolean removed;
        synchronized (localScripts) {
            removed = localScripts.remove(label) != null;
        }
        savedState.remove(label);
        String module = labelToModule.remove(label);
        if (module != null) runtimeErrors.remove(module);
        if (removed) {
            applyEntries(effectiveEntries());
                interstellarTikiTiks.apexx.client.config.ConfigManager.getInstance().onUserContentChanged();
        }
        return removed;
    }

    /** Drops every in-memory IDE script (sign-out / self-destruct). */
    public void clearLocal() {
        synchronized (localScripts) {
            localScripts.clear();
        }
        savedState.clear();
        labelToModule.clear();
        runtimeErrors.clear();
        ScriptDebugger.clearAll();
    }

    private void removeLive(String moduleName) {
        try {
            ModuleManager mm = Apexx.getInstance() == null ? null : Apexx.getInstance().getModuleManager();
            if (mm != null && mm.getModule(moduleName) instanceof ScriptModule) mm.removeModule(moduleName);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Parses and resolves {@code source} without registering anything, so the IDE can show problems as
     * the player types. Returns null when the script is valid.
     */
    public static Problem check(String source) {
        try {
            ScriptCompiler.parseAndResolve(source);
            return null;
        } catch (ScriptException se) {
            return new Problem(se.line, se.getMessage());
        } catch (Throwable t) {
            return new Problem(-1, "cannot check this script: " + t.getClass().getSimpleName());
        }
    }

    /** Most recent per-script compile errors (name → message). */
    public Map<String, String> getErrors() {
        return new LinkedHashMap<>(errors);
    }

    // ── runtime errors (surfaced in the IDE) ─────────────────────────────────

    private final Map<String, Problem> runtimeErrors = new java.util.concurrent.ConcurrentHashMap<>();
    // IDE label → module name declared in the source.
    private final Map<String, String> labelToModule = new java.util.concurrent.ConcurrentHashMap<>();

    /** A script hit a runtime fault; the latest one per script is kept for the editor. */
    public void reportRuntimeError(String moduleName, int line, String message) {
        if (moduleName == null || message == null) return;
        runtimeErrors.put(moduleName, new Problem(line, message));
    }

    /** The module name the script saved under {@code label} declares (the label itself until it is compiled). */
    public String moduleNameFor(String label) {
        return label == null ? null : labelToModule.getOrDefault(label, label);
    }

    /** The latest runtime error of the script saved under {@code label} (IDE name), or null. */
    public Problem runtimeErrorFor(String label) {
        String module = labelToModule.getOrDefault(label, label);
        return runtimeErrors.get(module);
    }

    public void clearRuntimeError(String label) {
        runtimeErrors.remove(labelToModule.getOrDefault(label, label));
    }

    /** Whether the live module of the script saved under {@code label} is currently on. */
    public boolean isLiveEnabled(String label) {
        try {
            ModuleManager mm = Apexx.getInstance() == null ? null : Apexx.getInstance().getModuleManager();
            var m = mm == null ? null : mm.getModule(labelToModule.getOrDefault(label, label));
            return m != null && m.isEnabled();
        } catch (Throwable t) {
            return false;
        }
    }

    // ── keybind/setting state that survives recompiles and restarts ──────────

    /** Saved keybinds and setting values per IDE label (from the cloud blob or a previous module). */
    private final Map<String, JsonObject> savedState = new LinkedHashMap<>();

    private static JsonObject captureState(interstellarTikiTiks.apexx.client.module.Module m) {
        JsonObject o = new JsonObject();
        o.addProperty("key", m.getKeyCode());
        o.addProperty("key2", m.getKeyCode2());
        o.addProperty("hold", m.isHoldMode());
        JsonObject settings = new JsonObject();
        JsonObject binds = new JsonObject();
        for (var s : m.getSettings()) {
            if (s instanceof interstellarTikiTiks.apexx.client.module.SliderSetting sl) settings.addProperty(s.getName(), sl.getValue());
            else if (s instanceof interstellarTikiTiks.apexx.client.module.BooleanSetting b) {
                settings.addProperty(s.getName(), b.isEnabled());
                if (b.isBindable() && b.getKeyCode() != -1) binds.addProperty(s.getName(), b.getKeyCode());
            }
            else if (s instanceof interstellarTikiTiks.apexx.client.module.ModeSetting md) settings.addProperty(s.getName(), md.getCurrentMode());
            else if (s instanceof interstellarTikiTiks.apexx.client.module.ColorSetting c) settings.addProperty(s.getName(), c.getValue().getRGB());
        }
        o.add("settings", settings);
        o.add("binds", binds);
        return o;
    }

    private static void applyState(interstellarTikiTiks.apexx.client.module.Module m, JsonObject o) {
        if (o == null) return;
        try {
            if (o.has("key")) m.setKeyCode(o.get("key").getAsInt());
            if (o.has("key2")) m.setKeyCode2(o.get("key2").getAsInt());
            if (o.has("hold")) m.setHoldMode(o.get("hold").getAsBoolean());
            JsonObject settings = o.has("settings") && o.get("settings").isJsonObject() ? o.getAsJsonObject("settings") : new JsonObject();
            JsonObject binds = o.has("binds") && o.get("binds").isJsonObject() ? o.getAsJsonObject("binds") : new JsonObject();
            for (var s : m.getSettings()) {
                JsonElement v = settings.get(s.getName());
                try {
                    if (v != null && v.isJsonPrimitive()) {
                        if (s instanceof interstellarTikiTiks.apexx.client.module.SliderSetting sl) sl.setValue(v.getAsDouble());
                        else if (s instanceof interstellarTikiTiks.apexx.client.module.BooleanSetting b) b.setEnabled(v.getAsBoolean());
                        else if (s instanceof interstellarTikiTiks.apexx.client.module.ModeSetting md
                                && md.getModes().contains(v.getAsString())) md.setCurrentMode(v.getAsString());
                        else if (s instanceof interstellarTikiTiks.apexx.client.module.ColorSetting c)
                            c.setValue(new java.awt.Color(v.getAsInt(), true));
                    }
                    JsonElement bind = binds.get(s.getName());
                    if (bind != null && s instanceof interstellarTikiTiks.apexx.client.module.BooleanSetting b && b.isBindable()) {
                        b.setKeyCode(bind.getAsInt());
                    }
                } catch (RuntimeException ignored) {
                    // A value of the wrong type (setting changed kind): keep the script's default.
                }
            }
        } catch (RuntimeException ignored) {
        }
    }

    /** Copies the live modules' enabled flag and state into the IDE list (keybind toggles, GUI edits). */
    private void syncFromLive(String exceptLabel) {
        ModuleManager mm = Apexx.getInstance() == null ? null : Apexx.getInstance().getModuleManager();
        if (mm == null) return;
        synchronized (localScripts) {
            for (Map.Entry<String, LocalScript> e : localScripts.entrySet()) {
                String label = e.getKey();
                var m = mm.getModule(labelToModule.getOrDefault(label, label));
                if (!(m instanceof ScriptModule sm)) continue;
                savedState.put(label, captureState(sm));
                if (label.equals(exceptLabel) || sm.isOneShot()) continue;
                LocalScript ls = e.getValue();
                if (ls.enabled() != sm.isEnabled()) e.setValue(new LocalScript(ls.label(), ls.source(), sm.isEnabled()));
            }
        }
    }

    /** The IDE scripts as entries for {@link #applyEntries}. */
    private List<ScriptEntry> effectiveEntries() {
        List<ScriptEntry> merged = new ArrayList<>();
        synchronized (localScripts) {
            for (LocalScript local : localScripts.values()) {
                ScriptEntry e = new ScriptEntry();
                e.name = local.label();
                e.enabled = local.enabled();
                e.source = local.source();
                merged.add(e);
            }
        }
        return merged;
    }

    private void applyEntries(List<ScriptEntry> entries) {
        Apexx apexx = Apexx.getInstance();
        ModuleManager mm = apexx == null ? null : apexx.getModuleManager();
        if (mm == null) return;

        errors.clear();
        Set<String> seen = new HashSet<>();
        Map<String, String> claimed = new HashMap<>(); // module name -> label that owns it this pass

        for (ScriptEntry entry : entries) {
            String source = entry.source;
            // Compile first so we can name the module from the source itself.
            ScriptModule compiled;
            try {
                compiled = ScriptCompiler.compile(source);
            } catch (ScriptException se) {
                String label = entry.name != null ? entry.name : "<unnamed>";
                String liveKey = labelToModule.getOrDefault(label, label);
                if (liveHashes.containsKey(liveKey)) seen.add(liveKey);
                errors.put(label, se.getMessage());
                LOGGER.debug("[ScriptManager] quarantined script '{}': {}", label, se.getMessage());
                continue;
            } catch (Throwable t) {
                String label = entry.name != null ? entry.name : "<unnamed>";
                String liveKey = labelToModule.getOrDefault(label, label);
                if (liveHashes.containsKey(liveKey)) seen.add(liveKey);
                errors.put(label, "internal error: " + t);
                continue;
            }

            String name = compiled.getName();
            String label = entry.name != null ? entry.name : name;
            String owner = claimed.putIfAbsent(name, label);
            if (owner != null && !owner.equals(label)) {
                errors.put(label, "module name '" + name + "' is already used by script '" + owner + "'");
                continue;
            }
            seen.add(name);
            String previousModule = labelToModule.put(label, name);
            // Breakpoints follow the script when its module name changes (or is first learned).
            ScriptDebugger.renameScript(previousModule != null ? previousModule : label, name);
            if (previousModule != null && !previousModule.equals(name) && liveHashes.containsKey(previousModule)) {
                // The source renamed its module: carry the old one's state over, then drop it below.
                var old = mm.getModule(previousModule);
                if (old instanceof ScriptModule) savedState.put(label, captureState(old));
            }

            String newHash = compiled.getSourceHash();
            String oldHash = liveHashes.get(name);

            if (oldHash != null && oldHash.equals(newHash)) {
                // Unchanged source → keep the live module (preserves var state).
                // But still honor the enabled flag from the blob.
                applyEnabled(mm, name, entry.enabled);
                continue;
            }

            // New or changed → remove the old (if any) and register the fresh one, keeping its
            // keybinds and setting values.
            if (oldHash != null) {
                var old = mm.getModule(name);
                if (old instanceof ScriptModule) savedState.put(label, captureState(old));
                mm.removeModule(name);
            }
            runtimeErrors.remove(name);
            applyState(compiled, savedState.get(label));
            boolean added = mm.addModuleAfter(null, compiled);
            if (added) {
                liveHashes.put(name, newHash);
                if (entry.enabled && !compiled.isOneShot()) {
                    try {
                        compiled.setEnabled(true);
                    } catch (Throwable ignored) {
                        LOGGER.debug("[ScriptManager] failed to enable module '{}': {}", name, ignored.toString());
                    }
                }
            } else {
                // A native module already owns this name — quarantine to avoid clobbering.
                errors.put(name, "name '" + name + "' collides with an existing module");
                seen.remove(name);
            }
        }

        // Remove any live script modules no longer present in the blob.
        List<String> toRemove = new ArrayList<>();
        for (String name : liveHashes.keySet()) {
            if (!seen.contains(name)) toRemove.add(name);
        }
        for (String name : toRemove) {
            mm.removeModule(name);
            liveHashes.remove(name);
        }

        LOGGER.debug("[ScriptManager] synced {} script(s), {} error(s)", seen.size(), errors.size());
    }

    private void applyEnabled(ModuleManager mm, String name, boolean enabled) {
        try {
            var module = mm.getModule(name);
            if (module != null && module.isOneShot()) return; // a oneshot is only "on" while it runs
            if (module != null && module.isEnabled() != enabled) {
                module.setEnabled(enabled);
            }
        } catch (Throwable ignored) {
            LOGGER.debug("[ScriptManager] failed to apply enabled state to module '{}': {}", name, ignored.toString());
        }
    }

    private static final class ScriptEntry {
        String name;
        boolean enabled;
        String source;
    }
}
