package interstellarTikiTiks.apexx.client.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class LegacyConfigMigrationTest {
    private static final String VALID = "{\"modules\":{\"Sprint\":{\"enabled\":true}}}";

    @Test
    void validConfigIsCopiedAndTheOriginalIsKept(@TempDir Path dir) throws Exception {
        Path source = dir.resolve("old/default.json");
        Path target = dir.resolve("new/default.json");
        Files.createDirectories(source.getParent());
        Files.writeString(source, VALID, StandardCharsets.UTF_8);

        assertTrue(ConfigManager.copyLegacyConfig(source, target));
        assertEquals(VALID, Files.readString(target, StandardCharsets.UTF_8));
        assertTrue(Files.exists(source), "Migration must never delete the source");
    }

    @Test
    void existingDestinationIsNeverOverwritten(@TempDir Path dir) throws Exception {
        Path source = dir.resolve("old.json");
        Path target = dir.resolve("default.json");
        Files.writeString(source, VALID, StandardCharsets.UTF_8);
        Files.writeString(target, "{\"theme\":\"keep-me\"}", StandardCharsets.UTF_8);

        assertFalse(ConfigManager.copyLegacyConfig(source, target));
        assertEquals("{\"theme\":\"keep-me\"}", Files.readString(target, StandardCharsets.UTF_8));
        assertTrue(Files.exists(source));
    }

    @Test
    void invalidOrMissingSourceCopiesNothing(@TempDir Path dir) throws Exception {
        Path invalid = dir.resolve("invalid.json");
        Files.writeString(invalid, "not json at all, just text", StandardCharsets.UTF_8);
        Path target = dir.resolve("out/default.json");

        assertFalse(ConfigManager.copyLegacyConfig(invalid, target));
        assertFalse(ConfigManager.copyLegacyConfig(dir.resolve("missing.json"), target));
        assertFalse(Files.exists(target));
        assertTrue(Files.exists(invalid), "An unreadable legacy file is preserved");
    }
}
