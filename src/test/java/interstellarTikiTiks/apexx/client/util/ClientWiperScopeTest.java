package interstellarTikiTiks.apexx.client.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class ClientWiperScopeTest {
    @TempDir Path root;
    @Test void ownedTreeCleanupDoesNotTraverseSymlinks() throws Exception {
        Path external = Files.createDirectories(root.resolve("world"));
        Path valuable = external.resolve("level.dat");
        Files.writeString(valuable, "world fixture");
        Path owned = Files.createDirectories(root.resolve("apexx"));
        Files.writeString(owned.resolve("config.json"), "client fixture");
        try {
            Files.createSymbolicLink(owned.resolve("linked-world"), external);
        } catch (java.nio.file.FileSystemException | UnsupportedOperationException e) {
            org.junit.jupiter.api.Assumptions.abort("symbolic links are not creatable here: " + e.getMessage());
        }
        ClientWiper.deleteOwnedTree(owned);
        assertFalse(Files.exists(owned));
        assertEquals("world fixture", Files.readString(valuable));
    }
}
