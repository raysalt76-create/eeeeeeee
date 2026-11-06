package interstellarTikiTiks.apexx.client.module.implementation.player.autobuild;

import java.util.Arrays;

/**
 * A schematic after mirror/rotation, laid out for building: a dense grid for "what belongs at this
 * cell" lookups and the placeable cells in build order.
 *
 * <p>Build order is bottom-up by layer. Inside a layer, full blocks come before attachments
 * (torches, ladders, buttons, carpets, rails...) so the faces those hang on already exist, and cells
 * are visited in a serpentine (boustrophedon) sweep so consecutive targets stay close together.
 * Cells whose rule is {@code skip} (upper door halves, bed heads...) are left out of the order but
 * kept in the grid so the preview still draws them.
 */
public final class BuildModel {

    private final int sizeX;
    private final int sizeY;
    private final int sizeZ;
    private final String[] palette;
    private final int[] grid;        // transformed (y*sizeZ+z)*sizeX+x -> palette index or EMPTY
    private final int[] order;       // packed positions in build order
    private final int[] layerStart;  // order index of the first cell at each y; length sizeY+1

    private BuildModel(int sizeX, int sizeY, int sizeZ, String[] palette, int[] grid, int[] order, int[] layerStart) {
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.palette = palette;
        this.grid = grid;
        this.order = order;
        this.layerStart = layerStart;
    }

    /**
     * @param skip       palette index (of the transformed palette) -> leave out of the build order
     * @param attachment palette index -> place after the layer's full blocks
     */
    public static BuildModel of(Schematic s, SchematicTransform.Rotation rotation, SchematicTransform.Mirror mirror,
                                java.util.function.IntPredicate skip, java.util.function.IntPredicate attachment) {
        return of(s, rotation, mirror, skip, i -> attachment.test(i) ? 1 : 0, 2);
    }

    /**
     * Like the attachment overload, but each layer is swept {@code passes} times and a cell joins the
     * sweep {@code pass} returns for its palette index (0 first): e.g. plain blocks, then gravity
     * blocks, then attachments, then fluid-sensitive ones.
     */
    public static BuildModel of(Schematic s, SchematicTransform.Rotation rotation, SchematicTransform.Mirror mirror,
                                java.util.function.IntPredicate skip, java.util.function.IntUnaryOperator pass,
                                int passes) {
        SchematicTransform t = new SchematicTransform(s.sizeX(), s.sizeZ(), rotation, mirror);
        int tx = t.sizeX();
        int tz = t.sizeZ();
        int sy = s.sizeY();
        String[] palette = new String[s.paletteSize()];
        for (int i = 0; i < palette.length; i++) {
            palette[i] = s.paletteIsAir(i) ? s.paletteState(i) : t.transformState(s.paletteState(i));
        }

        int[] grid = new int[tx * sy * tz];
        Arrays.fill(grid, Schematic.EMPTY);
        for (int y = 0; y < sy; y++) {
            for (int z = 0; z < s.sizeZ(); z++) {
                for (int x = 0; x < s.sizeX(); x++) {
                    int p = s.paletteIndexAt(x, y, z);
                    if (p == Schematic.EMPTY) continue;
                    int[] xz = t.apply(x, z);
                    grid[(y * tz + xz[1]) * tx + xz[0]] = p;
                }
            }
        }

        int count = 0;
        for (int p : grid) {
            if (p != Schematic.EMPTY && !s.paletteIsAir(p) && !skip.test(p)) count++;
        }
        int[] order = new int[count];
        int[] layerStart = new int[sy + 1];
        int n = 0;
        for (int y = 0; y < sy; y++) {
            layerStart[y] = n;
            for (int sweep = 0; sweep < passes; sweep++) {
                for (int z = 0; z < tz; z++) {
                    boolean reverse = (z & 1) == 1;
                    for (int i = 0; i < tx; i++) {
                        int x = reverse ? tx - 1 - i : i;
                        int p = grid[(y * tz + z) * tx + x];
                        if (p == Schematic.EMPTY || s.paletteIsAir(p) || skip.test(p)) continue;
                        if (Math.max(0, Math.min(passes - 1, pass.applyAsInt(p))) != sweep) continue;
                        order[n++] = pack(x, y, z);
                    }
                }
            }
        }
        layerStart[sy] = n;
        return new BuildModel(tx, sy, tz, palette, grid, order, layerStart);
    }

    public static int pack(int x, int y, int z) {
        return (x & 0x3FF) | (z & 0x3FF) << 10 | (y & 0x3FF) << 20;
    }

    public static int unpackX(int packed) {
        return packed & 0x3FF;
    }

    public static int unpackZ(int packed) {
        return (packed >>> 10) & 0x3FF;
    }

    public static int unpackY(int packed) {
        return (packed >>> 20) & 0x3FF;
    }

    public int sizeX() {
        return sizeX;
    }

    public int sizeY() {
        return sizeY;
    }

    public int sizeZ() {
        return sizeZ;
    }

    public int paletteSize() {
        return palette.length;
    }

    /** Transformed state string for a palette index. */
    public String state(int paletteIndex) {
        return palette[paletteIndex];
    }

    public boolean inBounds(int x, int y, int z) {
        return x >= 0 && y >= 0 && z >= 0 && x < sizeX && y < sizeY && z < sizeZ;
    }

    /** Palette index at a transformed cell, or {@link Schematic#EMPTY}. */
    public int paletteAt(int x, int y, int z) {
        return inBounds(x, y, z) ? grid[(y * sizeZ + z) * sizeX + x] : Schematic.EMPTY;
    }

    public int orderSize() {
        return order.length;
    }

    public int orderAt(int i) {
        return order[i];
    }

    /** First order index at layer {@code y} (clamped), so a layer range can be scanned directly. */
    public int layerStart(int y) {
        if (y <= 0) return 0;
        if (y >= sizeY) return order.length;
        return layerStart[y];
    }
}
