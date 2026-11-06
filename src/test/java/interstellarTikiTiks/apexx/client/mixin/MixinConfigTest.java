package interstellarTikiTiks.apexx.client.mixin;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A class in the mixin package that is missing from the mixin config is never applied, and code that
 * casts to it (an accessor) then fails at run time on every machine. This pins the config to the
 * sources: every mixin class is registered, or is named here as deliberately unregistered.
 */
public class MixinConfigTest {
    /** Mixins that are intentionally not registered. Adding one here is a decision, not a default. */
    private static final Set<String> INTENTIONALLY_UNREGISTERED = Set.of(
            "DrawContextMixin",
            "ReloadableResourceManagerImplMixin",
            "ShaderLoaderMixin");

    private static JsonObject config() throws IOException {
        try (InputStream in = MixinConfigTest.class.getClassLoader().getResourceAsStream("clumps.client.mixins.json")) {
            assertNotNull(in, "clumps.client.mixins.json must be on the classpath");
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    private static Set<String> registered(JsonObject config) {
        Set<String> names = new TreeSet<>();
        for (String section : new String[] {"mixins", "client", "server"}) {
            if (!config.has(section)) continue;
            JsonArray array = config.getAsJsonArray(section);
            array.forEach(entry -> names.add(entry.getAsString()));
        }
        return names;
    }

    private static Path mixinSources() {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (int up = 0; up < 5 && dir != null; up++, dir = dir.getParent()) {
            Path candidate = dir.resolve("src/client/java/interstellarTikiTiks/apexx/client/mixin");
            if (Files.isDirectory(candidate)) return candidate;
        }
        return null;
    }

    @Test
    public void everyMixinClassIsRegisteredOrDeliberatelyNot() throws IOException {
        Path sources = mixinSources();
        assertNotNull(sources, "mixin sources not found from " + System.getProperty("user.dir"));
        Set<String> registered = registered(config());
        Set<String> topLevelRegistered = new TreeSet<>();
        registered.forEach(name -> topLevelRegistered.add(name.split("\\$")[0]));

        Set<String> missing = new TreeSet<>();
        try (Stream<Path> files = Files.list(sources)) {
            files.map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".java"))
                    .map(name -> name.substring(0, name.length() - 5))
                    .filter(name -> !topLevelRegistered.contains(name))
                    .filter(name -> !INTENTIONALLY_UNREGISTERED.contains(name))
                    .forEach(missing::add);
        }
        assertTrue(missing.isEmpty(), "mixin classes missing from clumps.client.mixins.json: " + missing);
    }

    @Test
    public void everyRegisteredEntryHasASource() throws IOException {
        Path sources = mixinSources();
        assertNotNull(sources);
        Set<String> unknown = new TreeSet<>();
        for (String name : registered(config())) {
            if (!Files.exists(sources.resolve(name.split("\\$")[0] + ".java"))) unknown.add(name);
        }
        assertTrue(unknown.isEmpty(), "registered mixins without a source file: " + unknown);
    }

    @Test
    public void theUnregisteredAllowlistHasNoStaleEntries() throws IOException {
        Set<String> registered = registered(config());
        for (String name : INTENTIONALLY_UNREGISTERED) {
            assertFalse(registered.contains(name), name + " is registered; remove it from the allowlist");
        }
    }

    @Test
    public void packetAccessorsUsedByModulesAreRegistered() throws IOException {
        Set<String> registered = registered(config());
        assertTrue(registered.contains("EntityS2CPacketAccessor"));
        assertTrue(registered.contains("EntityStatusS2CPacketAccessor"));
    }
}
