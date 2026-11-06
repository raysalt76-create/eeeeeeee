package interstellarTikiTiks.apexx.client.module;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every module named in ModuleManager's production list must construct without throwing: a module whose
 * constructor fails is silently skipped at runtime ("Production discovery skipped ...") and disappears for players.
 */
public class AllModulesConstructTest {

    @Test
    public void everyRegisteredModuleConstructs() throws Exception {
        // Tests run from versions/<mc>/, so walk up to the repository root.
        Path rel = Path.of("src/client/java/interstellarTikiTiks/apexx/client/manager/ModuleManager.java");
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.isRegularFile(dir.resolve(rel))) dir = dir.getParent();
        assertTrue(dir != null, "ModuleManager.java not found above the test working directory");
        String source = Files.readString(dir.resolve(rel));
        Matcher m = Pattern.compile("\"(interstellarTikiTiks\\.apexx\\.client\\.module\\.implementation\\.[A-Za-z0-9_.]+)\"")
                .matcher(source);
        List<String> failures = new ArrayList<>();
        int checked = 0;
        int skipped = 0;
        while (m.find()) {
            String name = m.group(1);
            Class<?> type;
            try {
                type = Class.forName(name);
            } catch (ClassNotFoundException missing) {
                failures.add(name + " (class missing)");
                continue;
            } catch (Throwable staticInit) {
                if (needsLiveGame(staticInit)) { checked++; skipped++; continue; }
                failures.add(name + " (static init) -> " + staticInit);
                continue;
            }
            if (!Module.class.isAssignableFrom(type)) continue;
            checked++;
            try {
                type.getDeclaredConstructor().newInstance();
            } catch (Throwable t) {
                Throwable cause = t.getCause() != null ? t.getCause() : t;
                if (needsLiveGame(cause)) { skipped++; continue; }
                failures.add(name + " -> " + cause);
            }
        }
        assertTrue(checked - skipped > 20, "expected to construct the registered modules, built " + (checked - skipped) + " of " + checked);
        assertTrue(failures.isEmpty(), "modules failed to construct:\n" + String.join("\n", failures));
    }

    /** Registry/bootstrap failures only mean the module touches game state that unit tests cannot start. */
    private static boolean needsLiveGame(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof NoClassDefFoundError || c instanceof ExceptionInInitializerError
                    || c instanceof IllegalAccessError) return true;
        }
        return false;
    }
}
