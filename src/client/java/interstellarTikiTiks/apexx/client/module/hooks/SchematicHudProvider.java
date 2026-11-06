package interstellarTikiTiks.apexx.client.module.hooks;

import java.util.List;

/**
 * Supplies the rows of the schematic materials / progress HUD widget. The widget lives in the
 * loader and the builder module in the payload, so the widget only reads plain rows through this.
 */
public interface SchematicHudProvider {

    /** One text row and its ARGB colour. */
    record Row(String text, int color) { }

    /** Rows to draw this frame; empty when nothing should be shown. Called on the render thread. */
    List<Row> schematicHudRows();
}
