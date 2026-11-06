package interstellarTikiTiks.apexx.client.config.maker;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The Config Maker rules engine: turns five multiple-choice answers into a plan (which modules to enable and
 * which settings to set) using the shared spec in {@code assets/apexx/config_maker.json}. Pure and offline:
 * no Minecraft types, no network. The website runs a line-for-line TypeScript port of this class
 * ({@code src/lib/configMaker.ts}) on the same spec, and both are checked against one golden fixture.
 *
 * <p>Order: common, gamemode, playstyle, anticheat, aim, ping, aimMode, placement steps (the player's Silent/Normal
 * choices come last so they win), then warnings, the safety pass (never-enable list,
 * Blatant modules only for Aggressive + Lenient), then every numeric setting is clamped to its slider range
 * (delays never below 1 ms) and rounded to the slider's decimals.</p>
 */
public final class ConfigMakerEngine {
    public static final String[] QUESTION_IDS = {"gamemode", "playstyle", "anticheat", "aim", "ping", "aimMode", "placement"};

    private ConfigMakerEngine() { }

    /** One module in the plan. Settings keep insertion order. */
    public static final class PlanModule {
        public boolean enabled;
        /** False while only {@code prefer} steps named this module: it is then kept out of the preview unless enabled. */
        public boolean listed = true;
        public final LinkedHashMap<String, JsonPrimitive> settings = new LinkedHashMap<>();
    }

    public static final class Plan {
        public String name;
        public String gameMode;
        public final List<String> tags = new ArrayList<>();
        public boolean blatantAllowed;
        public final List<String> warnings = new ArrayList<>();
        public final LinkedHashMap<String, PlanModule> modules = new LinkedHashMap<>();
    }

    /** Run the rules. {@code answers} maps each question id to one of its option ids. */
    public static Plan generate(JsonObject spec, Map<String, String> answers) {
        validate(spec, answers);
        Plan plan = new Plan();
        applySteps(spec.getAsJsonArray("common"), plan, answers);
        applySteps(obj(spec, "gamemodes").getAsJsonArray(answers.get("gamemode")), plan, answers);
        for (String q : new String[]{"playstyle", "anticheat", "aim", "ping", "aimMode", "placement"}) {
            applySteps(obj(spec, q).getAsJsonArray(answers.get(q)), plan, answers);
        }

        JsonArray warnings = spec.getAsJsonArray("warnings");
        if (warnings != null) {
            for (JsonElement w : warnings) {
                JsonObject warning = w.getAsJsonObject();
                if (matches(warning.getAsJsonObject("when"), answers)) plan.warnings.add(warning.get("text").getAsString());
            }
        }

        JsonObject safety = obj(spec, "safety");
        for (String name : strings(safety.getAsJsonArray("neverEnable"))) {
            PlanModule m = plan.modules.get(name);
            if (m != null) m.enabled = false;
        }
        plan.blatantAllowed = matches(safety.getAsJsonObject("blatantAllowedWhen"), answers);
        boolean anyBlatant = false;
        for (String name : strings(safety.getAsJsonArray("blatant"))) {
            PlanModule m = plan.modules.get(name);
            if (m == null) continue;
            if (!plan.blatantAllowed) m.enabled = false;
            anyBlatant |= m.enabled;
        }

        JsonObject ranges = obj(spec, "ranges");
        for (Map.Entry<String, PlanModule> e : plan.modules.entrySet()) {
            JsonObject moduleRanges = ranges.has(e.getKey()) ? ranges.getAsJsonObject(e.getKey()) : null;
            for (Map.Entry<String, JsonPrimitive> s : e.getValue().settings.entrySet()) {
                if (!s.getValue().isNumber()) continue;
                JsonArray range = moduleRanges != null && moduleRanges.has(s.getKey())
                        ? moduleRanges.getAsJsonArray(s.getKey()) : null;
                s.setValue(new JsonPrimitive(clamp(s.getKey(), s.getValue().getAsDouble(), range)));
            }
        }

        JsonObject naming = obj(spec, "naming");
        plan.name = str(naming, "gamemode", answers.get("gamemode")) + " - "
                + str(naming, "playstyle", answers.get("playstyle")) + " - "
                + str(naming, "anticheat", answers.get("anticheat"));
        plan.gameMode = obj(spec, "hubMode").get(answers.get("gamemode")).getAsString();
        Set<String> tags = new LinkedHashSet<>(strings(obj(spec, "tags").getAsJsonArray(answers.get("gamemode"))));
        tags.add(anyBlatant ? "Blatant" : "Legit");
        plan.tags.addAll(tags);
        return plan;
    }

    /** Clamp to the slider range (delays: at least 1 ms, never 0) and round to the slider's decimals. */
    static double clamp(String setting, double value, JsonArray range) {
        boolean delay = setting.contains("(ms)");
        if (range == null) return delay ? Math.max(1.0, value) : value;
        double lo = range.get(0).getAsDouble(), hi = range.get(1).getAsDouble();
        int decimals = range.size() > 2 ? range.get(2).getAsInt() : 0;
        if (delay) lo = Math.max(lo, 1.0);
        double v = Math.min(hi, Math.max(lo, value));
        double p = Math.pow(10, decimals);
        return Math.round(v * p) / p;
    }

    private static void applySteps(JsonArray steps, Plan plan, Map<String, String> answers) {
        if (steps == null) return;
        for (JsonElement el : steps) {
            JsonObject step = el.getAsJsonObject();
            if (step.has("when") && !matches(step.getAsJsonObject("when"), answers)) continue;
            if (step.has("enable")) {
                for (String name : strings(step.getAsJsonArray("enable"))) {
                    PlanModule m = entry(plan, name);
                    m.enabled = true;
                    m.listed = true;
                }
            }
            if (step.has("disable")) {
                for (String name : strings(step.getAsJsonArray("disable"))) {
                    PlanModule m = plan.modules.get(name);
                    if (m != null) m.enabled = false;
                }
            }
            if (step.has("set")) {
                for (Map.Entry<String, JsonElement> mod : step.getAsJsonObject("set").entrySet()) {
                    PlanModule m = entry(plan, mod.getKey());
                    m.listed = true;
                    for (Map.Entry<String, JsonElement> s : mod.getValue().getAsJsonObject().entrySet()) {
                        m.settings.put(s.getKey(), s.getValue().getAsJsonPrimitive());
                    }
                }
            }
            if (step.has("prefer")) {
                for (Map.Entry<String, JsonElement> mod : step.getAsJsonObject("prefer").entrySet()) {
                    PlanModule m = plan.modules.get(mod.getKey());
                    if (m == null) {
                        m = entry(plan, mod.getKey());
                        m.listed = false;
                    }
                    for (Map.Entry<String, JsonElement> s : mod.getValue().getAsJsonObject().entrySet()) {
                        m.settings.put(s.getKey(), s.getValue().getAsJsonPrimitive());
                    }
                }
            }
            if (step.has("scale")) {
                JsonObject scale = step.getAsJsonObject("scale");
                String match = scale.get("match").getAsString();
                double factor = scale.get("factor").getAsDouble();
                Set<String> only = scale.has("modules") ? new LinkedHashSet<>(strings(scale.getAsJsonArray("modules"))) : null;
                for (Map.Entry<String, PlanModule> e : plan.modules.entrySet()) {
                    if (only != null && !only.contains(e.getKey())) continue;
                    for (Map.Entry<String, JsonPrimitive> s : e.getValue().settings.entrySet()) {
                        if (s.getKey().contains(match) && s.getValue().isNumber()) {
                            s.setValue(new JsonPrimitive(s.getValue().getAsDouble() * factor));
                        }
                    }
                }
            }
            if (step.has("assign")) {
                JsonObject assign = step.getAsJsonObject("assign");
                String match = assign.get("match").getAsString();
                JsonPrimitive value = assign.get("value").getAsJsonPrimitive();
                for (PlanModule m : plan.modules.values()) {
                    if (m.settings.containsKey(match)) m.settings.put(match, value);
                }
            }
        }
    }

    private static PlanModule entry(Plan plan, String name) {
        return plan.modules.computeIfAbsent(name, k -> new PlanModule());
    }

    /** True when every question listed in {@code when} has its answer among the listed options. */
    private static boolean matches(JsonObject when, Map<String, String> answers) {
        if (when == null) return true;
        for (Map.Entry<String, JsonElement> e : when.entrySet()) {
            if (!strings(e.getValue().getAsJsonArray()).contains(answers.get(e.getKey()))) return false;
        }
        return true;
    }

    private static void validate(JsonObject spec, Map<String, String> answers) {
        if (spec == null || answers == null) throw new IllegalArgumentException("missing spec or answers");
        for (JsonElement q : spec.getAsJsonArray("questions")) {
            JsonObject question = q.getAsJsonObject();
            String id = question.get("id").getAsString();
            String answer = answers.get(id);
            boolean known = false;
            for (JsonElement o : question.getAsJsonArray("options")) {
                if (o.getAsJsonObject().get("id").getAsString().equals(answer)) known = true;
            }
            if (!known) throw new IllegalArgumentException("no valid answer for " + id);
        }
    }

    /**
     * The plan as JSON ({name, gameMode, tags, warnings, modules{name:{enabled, [listed: false,] settings}}}): used by
     * the golden tests. {@code listed} is written only when false.
     */
    public static JsonObject toJson(Plan plan) {
        JsonObject out = new JsonObject();
        out.addProperty("name", plan.name);
        out.addProperty("gameMode", plan.gameMode);
        JsonArray tags = new JsonArray();
        plan.tags.forEach(tags::add);
        out.add("tags", tags);
        JsonArray warnings = new JsonArray();
        plan.warnings.forEach(warnings::add);
        out.add("warnings", warnings);
        JsonObject modules = new JsonObject();
        for (Map.Entry<String, PlanModule> e : plan.modules.entrySet()) {
            JsonObject m = new JsonObject();
            m.addProperty("enabled", e.getValue().enabled);
            if (!e.getValue().listed) m.addProperty("listed", false);
            JsonObject settings = new JsonObject();
            e.getValue().settings.forEach(settings::add);
            m.add("settings", settings);
            modules.add(e.getKey(), m);
        }
        out.add("modules", modules);
        return out;
    }

    private static JsonObject obj(JsonObject o, String key) {
        return o.getAsJsonObject(key);
    }

    private static String str(JsonObject o, String group, String key) {
        return o.getAsJsonObject(group).get(key).getAsString();
    }

    private static List<String> strings(JsonArray array) {
        List<String> out = new ArrayList<>();
        if (array != null) for (JsonElement e : array) out.add(e.getAsString());
        return out;
    }
}
