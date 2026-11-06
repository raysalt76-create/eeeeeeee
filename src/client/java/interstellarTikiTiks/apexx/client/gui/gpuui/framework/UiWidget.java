package interstellarTikiTiks.apexx.client.gui.gpuui.framework;

/** A retained input element that can also emit commands into the frame display list. */
public interface UiWidget {
    void paint(UiDisplayList.Builder displayList);
}
