package interstellarTikiTiks.apexx.client.module.hooks;

import java.nio.file.Path;
import java.util.List;

/**
 * Receives files dropped onto the game window while the ClickGUI is open. The GUI lives in the
 * loader and cannot reference payload module classes, so it hands the paths to this hook. Called on
 * the client thread; the files are read where they are and never copied.
 */
public interface FileDropHandler {

    /** @return true when at least one dropped file was accepted. */
    boolean onFilesDropped(List<Path> paths);
}
