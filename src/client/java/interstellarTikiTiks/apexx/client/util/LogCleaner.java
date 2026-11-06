package interstellarTikiTiks.apexx.client.util;

import net.fabricmc.loader.api.FabricLoader;
import java.io.*;
import java.nio.file.Path;
import java.util.*;

public class LogCleaner {
    public static void clean() {
        Path logPath = FabricLoader.getInstance().getGameDir().resolve("logs").resolve("latest.log");
        File file = logPath.toFile();

        if (!file.exists()) {
            return;
        }

        // Strip every line that names the client or its bundled rendering libraries from latest.log.
        // "combat-hitboxes" (formerly "clumps") is deliberately NOT here — that is the cover identity (a real, innocuous mod), so
        // its mod-list line should remain and look legitimate. These are the strings that identify
        // the client itself or its renderer footprint.
        final String[] remove = {"apexx", "nanovg", "interstellar", "msdf"};

        try {
            List<String> lines = new ArrayList<>();

            try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    boolean skip = false;

                    for (String keyword : remove) {
                        if (line.toLowerCase().contains(keyword.toLowerCase())) {
                            skip = true;
                            break;
                        }
                    }

                    if (!skip) {
                        lines.add(line);
                    }
                }
            }

            try (BufferedWriter writer = new BufferedWriter(new FileWriter(file))) {
                for (String l : lines) {
                    writer.write(l);
                    writer.newLine();
                }
            }

        } catch (IOException e) {

        }
    }
}
