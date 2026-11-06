package interstellarTikiTiks.apexx.client.gui.gpuui.kit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Drag-to-reorder drop maths and the Auto menu-scale correction. */
class CatalogLayoutMathTest {

    // Two columns of 100x50 cards, gaps 10, grid at (0, 0); 5 cards = rows of 2, 2, 1.
    private static CatalogDrag.Drop grid(float x, float y) {
        return CatalogDrag.drop(x, y, 0f, 0f, 100f, 50f, 10f, 10f, 2, 5);
    }

    @Test
    void gridDropsOnTheNearestGapOfTheRowUnderThePointer() {
        assertEquals(0, grid(20f, 20f).index());          // left half of card 0
        assertEquals(1, grid(80f, 20f).index());          // right half of card 0
        assertEquals(2, grid(200f, 20f).index());         // end of row 0
        assertEquals(2, grid(200f, 20f).column());
        assertEquals(3, grid(130f, 80f).index());         // between cards 2 and 3
        assertEquals(5, grid(150f, 130f).index());        // last row holds one card: clamps to its end
        assertEquals(0, grid(-50f, -50f).index());
        assertEquals(5, grid(500f, 999f).index());
    }

    @Test
    void singleColumnUsesHorizontalGaps() {
        CatalogDrag.Drop d = CatalogDrag.drop(10f, 70f, 0f, 0f, 100f, 50f, 10f, 10f, 1, 3);
        assertEquals(1, d.index());
        assertEquals(0, CatalogDrag.drop(10f, 10f, 0f, 0f, 100f, 50f, 10f, 10f, 1, 3).index());
        assertEquals(3, CatalogDrag.drop(10f, 900f, 0f, 0f, 100f, 50f, 10f, 10f, 1, 3).index());
        assertEquals(0, CatalogDrag.drop(10f, 10f, 0f, 0f, 100f, 50f, 10f, 10f, 1, 0).index());
    }

    @Test
    void cardsStayInsideTheirFavouriteGroup() {
        assertEquals(2, CatalogDrag.clampToGroup(5, true, 2, 6));
        assertEquals(2, CatalogDrag.clampToGroup(0, false, 2, 6));
        assertEquals(4, CatalogDrag.clampToGroup(4, false, 2, 6));
        assertEquals(1, CatalogDrag.clampToGroup(1, true, 2, 6));
    }

    @Test
    void autoScaleIsNeutralOnTheReferenceScreenAndBounded() {
        // A 24" 16:9 1080p monitor: 1080 px over ~299 mm.
        float ref = AutoMenuScale.pxPerMm(1080, 299, 1f, 1f);
        assertEquals(1f, AutoMenuScale.correction(1080f, ref), 0.01f);
        // Same panel size at 4K: same physical size, so the same correction.
        assertEquals(1f, AutoMenuScale.correction(2160f, AutoMenuScale.pxPerMm(2160, 299, 1f, 1f)), 0.01f);
        // A 14" laptop (~174 mm tall) gets a larger share, a 32" monitor (~398 mm) a smaller one.
        assertTrue(AutoMenuScale.correction(1080f, AutoMenuScale.pxPerMm(1080, 174, 1f, 1f)) > 1.05f);
        assertTrue(AutoMenuScale.correction(1440f, AutoMenuScale.pxPerMm(1440, 398, 1f, 1f)) < 0.95f);
        // Always inside the clamp, and neutral when nothing is known.
        float tiny = AutoMenuScale.correction(1080f, AutoMenuScale.pxPerMm(1080, 60, 1f, 1f));
        assertTrue(tiny <= AutoMenuScale.MAX_CORRECTION && tiny >= AutoMenuScale.MIN_CORRECTION);
        assertEquals(1f, AutoMenuScale.correction(1080f, Float.NaN));
        assertEquals(1f, AutoMenuScale.correction(0f, ref));
    }

    @Test
    void implausibleMonitorSizesFallBackToContentScale() {
        assertTrue(Float.isNaN(AutoMenuScale.pxPerMm(1080, 0, 1f, 0f)));
        float fromScale = AutoMenuScale.pxPerMm(1080, 0, 1f, 1.5f);
        assertEquals(96f / 25.4f * 1.5f, fromScale, 1e-4f);
        // A Retina framebuffer is twice the screen coordinates.
        assertEquals(2f * AutoMenuScale.pxPerMm(900, 200, 1f, 1f), AutoMenuScale.pxPerMm(900, 200, 2f, 1f), 1e-4f);
    }
}
