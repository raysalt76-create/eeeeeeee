package interstellarTikiTiks.apexx.client.gui.gpuui.kit;

/**
 * Geometry for dragging a module card to a new place in the catalog grid. Pure maths, so it is unit tested.
 *
 * <p>A drop position is an insertion index 0..n (n = after the last card): the gap the pointer is nearest to.
 * In a one-column list that is the nearest horizontal gap; in a grid it is the nearest vertical gap inside the
 * row under the pointer, so the indicator always sits between two cards of the same row (or at a row's end).
 */
public final class CatalogDrag {
    private CatalogDrag() { }

    /** Where a drop would land and where its indicator goes (grid-relative row and boundary column). */
    public record Drop(int index, int row, int column) { }

    /**
     * The drop under ({@code px}, {@code py}) for {@code n} cards laid out row-major in {@code cols} columns of
     * {@code cardW} x {@code cardH}, separated by {@code colGap} / {@code rowGap}, starting at
     * ({@code left}, {@code top}).
     */
    public static Drop drop(float px, float py, float left, float top, float cardW, float cardH,
                            float colGap, float rowGap, int cols, int n) {
        cols = Math.max(1, cols);
        if (n <= 0) return new Drop(0, 0, 0);
        float pitchY = Math.max(1f, cardH + rowGap);
        float pitchX = Math.max(1f, cardW + colGap);
        int rows = (n + cols - 1) / cols;
        if (cols == 1) {
            int k = Math.round((py - top + rowGap * 0.5f) / pitchY);
            k = Math.max(0, Math.min(n, k));
            return new Drop(k, k, 0);
        }
        int row = (int) Math.floor((py - top + rowGap * 0.5f) / pitchY);
        row = Math.max(0, Math.min(rows - 1, row));
        int inRow = Math.min(cols, n - row * cols);
        int col = Math.round((px - left + colGap * 0.5f) / pitchX);
        col = Math.max(0, Math.min(inRow, col));
        return new Drop(row * cols + col, row, col);
    }

    /**
     * Clamps an insertion index so a card stays inside its group: favourites are always listed first, so a
     * favourite drops among favourites ({@code 0..favCount}) and any other card among the rest
     * ({@code favCount..n}).
     */
    public static int clampToGroup(int index, boolean favourite, int favCount, int n) {
        int lo = favourite ? 0 : favCount;
        int hi = favourite ? favCount : n;
        return Math.max(lo, Math.min(hi, index));
    }
}
