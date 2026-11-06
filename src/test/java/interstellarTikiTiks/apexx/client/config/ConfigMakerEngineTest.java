package interstellarTikiTiks.apexx.client.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import interstellarTikiTiks.apexx.client.config.maker.ConfigMakerEngine;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The client engine against the golden plans shared byte-for-byte with the website's
 * functions/_shared/config-maker.test.ts, so both build the same config for the same answers.
 */
class ConfigMakerEngineTest {

    private static JsonObject read(String classpath, String relative) throws IOException {
        try (InputStream in = ConfigMakerEngineTest.class.getClassLoader().getResourceAsStream(classpath)) {
            if (in != null) return JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        }
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++, dir = dir.getParent()) {
            Path candidate = dir.resolve(relative);
            if (Files.isRegularFile(candidate)) {
                return JsonParser.parseString(Files.readString(candidate, StandardCharsets.UTF_8)).getAsJsonObject();
            }
        }
        throw new IOException("not found: " + relative);
    }

    private static JsonObject spec() throws IOException {
        return read("assets/apexx/config_maker.json", "src/client/resources/assets/apexx/config_maker.json");
    }

    private static JsonObject golden() throws IOException {
        return read("config_maker_golden.json", "src/test/resources/config_maker_golden.json");
    }

    private static Map<String, String> answers(JsonObject o) {
        Map<String, String> map = new LinkedHashMap<>();
        o.entrySet().forEach(e -> map.put(e.getKey(), e.getValue().getAsString()));
        return map;
    }

    private static List<Map<String, String>> allAnswers(JsonObject spec) {
        List<List<String>> ids = new java.util.ArrayList<>();
        for (JsonElement q : spec.getAsJsonArray("questions")) {
            List<String> opts = new java.util.ArrayList<>();
            for (JsonElement o : q.getAsJsonObject().getAsJsonArray("options")) opts.add(o.getAsJsonObject().get("id").getAsString());
            ids.add(opts);
        }
        List<String> qids = new java.util.ArrayList<>();
        for (JsonElement q : spec.getAsJsonArray("questions")) qids.add(q.getAsJsonObject().get("id").getAsString());
        List<Map<String, String>> out = new java.util.ArrayList<>();
        out.add(new LinkedHashMap<>());
        for (int i = 0; i < qids.size(); i++) {
            List<Map<String, String>> next = new java.util.ArrayList<>();
            for (Map<String, String> partial : out) {
                for (String option : ids.get(i)) {
                    Map<String, String> m = new LinkedHashMap<>(partial);
                    m.put(qids.get(i), option);
                    next.add(m);
                }
            }
            out = next;
        }
        return out;
    }

    /** Structural equality with numbers compared by value (Gson writes 65.0 where JS writes 65). */
    private static void assertSameJson(JsonElement expected, JsonElement actual, String path) {
        if (expected.isJsonObject()) {
            assertTrue(actual.isJsonObject(), path);
            JsonObject e = expected.getAsJsonObject(), a = actual.getAsJsonObject();
            assertEquals(e.keySet(), a.keySet(), path);
            for (String key : e.keySet()) assertSameJson(e.get(key), a.get(key), path + "." + key);
        } else if (expected.isJsonArray()) {
            JsonArray e = expected.getAsJsonArray(), a = actual.getAsJsonArray();
            assertEquals(e.size(), a.size(), path);
            for (int i = 0; i < e.size(); i++) assertSameJson(e.get(i), a.get(i), path + "[" + i + "]");
        } else {
            JsonPrimitive e = expected.getAsJsonPrimitive(), a = actual.getAsJsonPrimitive();
            if (e.isNumber()) assertEquals(e.getAsDouble(), a.getAsDouble(), 1e-9, path);
            else assertEquals(e, a, path);
        }
    }

    @Test
    void matchesSharedGoldenPlans() throws IOException {
        JsonObject spec = spec();
        JsonArray cases = golden().getAsJsonArray("cases");
        assertTrue(cases.size() >= 5);
        for (JsonElement c : cases) {
            JsonObject entry = c.getAsJsonObject();
            ConfigMakerEngine.Plan plan = ConfigMakerEngine.generate(spec, answers(entry.getAsJsonObject("answers")));
            assertSameJson(entry.get("plan"), ConfigMakerEngine.toJson(plan), entry.get("answers").toString());
        }
    }

    @Test
    void distinctConfigCountMatchesGolden() throws IOException {
        JsonObject spec = spec();
        Set<String> seen = new HashSet<>();
        for (Map<String, String> a : allAnswers(spec)) {
            JsonObject json = ConfigMakerEngine.toJson(ConfigMakerEngine.generate(spec, a));
            json.remove("name");
            seen.add(json.toString());
        }
        assertEquals(golden().get("distinctConfigs").getAsInt(), seen.size());
    }

    @Test
    void safetyRulesHoldForEveryAnswerSet() throws IOException {
        JsonObject spec = spec();
        JsonObject safety = spec.getAsJsonObject("safety");
        for (Map<String, String> a : allAnswers(spec)) {
            ConfigMakerEngine.Plan plan = ConfigMakerEngine.generate(spec, a);
            boolean blatantOk = "aggressive".equals(a.get("playstyle")) && "lenient".equals(a.get("anticheat"));
            for (JsonElement n : safety.getAsJsonArray("blatant")) {
                ConfigMakerEngine.PlanModule m = plan.modules.get(n.getAsString());
                if (!blatantOk && m != null) assertFalse(m.enabled, n + " " + a);
            }
            for (JsonElement n : safety.getAsJsonArray("neverEnable")) {
                ConfigMakerEngine.PlanModule m = plan.modules.get(n.getAsString());
                if (m != null) assertFalse(m.enabled, n + " " + a);
            }
            boolean strict = "strict".equals(a.get("anticheat"));
            int expectedWarnings = (strict && "silent".equals(a.get("aimMode")) ? 1 : 0) + (strict && "silent".equals(a.get("placement")) ? 1 : 0);
            assertEquals(expectedWarnings, plan.warnings.size(), a.toString());
            JsonObject aimPrefs = spec.getAsJsonObject("aimMode").getAsJsonArray("normal").get(0).getAsJsonObject().getAsJsonObject("prefer");
            plan.modules.forEach((name, m) -> m.settings.forEach((key, value) -> {
                if ("Silent Aim".equals(key) && aimPrefs.has(name)) assertEquals("silent".equals(a.get("aimMode")), value.getAsBoolean(), name);
                if (value.isNumber() && key.contains("(ms)")) assertTrue(value.getAsDouble() >= 1.0, name + "." + key);
            }));
        }
    }

    @Test
    void rejectsUnknownAnswers() throws IOException {
        JsonObject spec = spec();
        assertThrows(IllegalArgumentException.class, () -> ConfigMakerEngine.generate(spec,
                Map.of("gamemode", "bedwars", "playstyle", "legit", "anticheat", "strict", "aim", "none", "ping", "low",
                        "aimMode", "normal", "placement", "normal")));
    }
}
