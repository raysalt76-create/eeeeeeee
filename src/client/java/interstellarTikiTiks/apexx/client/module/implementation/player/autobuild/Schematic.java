package interstellarTikiTiks.apexx.client.module.implementation.player.autobuild;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable, Minecraft-registry-free representation of a parsed schematic.
 *
 * <p>Coordinates use the schematic's own local frame with {@code (0,0,0)} at the minimum
 * corner, {@code y} up. The backing cell array is laid out in the canonical Litematica /
 * Sponge order {@code index = (y * sizeZ + z) * sizeX + x} so both formats fill it directly.
 *
 * <p>Each cell holds a palette index (or {@link #EMPTY}). The palette stores full block-state
 * strings such as {@code "minecraft:oak_stairs[facing=east,half=bottom]"}; resolving those to
 * {@code BlockState}/{@code Item} is deferred to the client thread (it needs the block registry)
 * and is deliberately kept out of this class so it can be unit-tested without bootstrapping MC.
 */
public final class Schematic {

    /** Cell value meaning "no block here" (outside any region of a merged schematic). */
    public static final int EMPTY = -1;

    private final String name;
    private final int sizeX;
    private final int sizeY;
    private final int sizeZ;
    private final String[] palette;      // palette index -> full block-state string
    private final String[] paletteBase;  // palette index -> base block id ("minecraft:stone")
    private final boolean[] paletteAir;  // palette index -> is an air variant
    private final int[] cells;           // size sizeX*sizeY*sizeZ, palette index or EMPTY

    Schematic(String name, int sizeX, int sizeY, int sizeZ, String[] palette, int[] cells) {
        this.name = name == null || name.isBlank() ? "schematic" : name;
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.palette = palette;
        this.cells = cells;
        this.paletteBase = new String[palette.length];
        this.paletteAir = new boolean[palette.length];
        for (int i = 0; i < palette.length; i++) {
            String base = baseName(palette[i]);
            this.paletteBase[i] = base;
            this.paletteAir[i] = isAirName(base);
        }
    }

    public String name() {
        return name;
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

    public long volume() {
        return (long) sizeX * sizeY * sizeZ;
    }

    public boolean inBounds(int x, int y, int z) {
        return x >= 0 && y >= 0 && z >= 0 && x < sizeX && y < sizeY && z < sizeZ;
    }

    public int index(int x, int y, int z) {
        return (y * sizeZ + z) * sizeX + x;
    }

    /** Palette index at a cell, or {@link #EMPTY}. */
    public int paletteIndexAt(int x, int y, int z) {
        if (!inBounds(x, y, z)) {
            return EMPTY;
        }
        return cells[index(x, y, z)];
    }

    /** True when the cell holds a block that should be placed (not empty and not an air variant). */
    public boolean isSolid(int x, int y, int z) {
        int p = paletteIndexAt(x, y, z);
        return p != EMPTY && !paletteAir[p];
    }

    /** Full block-state string for a cell, or {@code null} when empty/air. */
    public String stateAt(int x, int y, int z) {
        int p = paletteIndexAt(x, y, z);
        if (p == EMPTY || paletteAir[p]) {
            return null;
        }
        return palette[p];
    }

    /** Base block id (no properties) for a cell, or {@code null} when empty/air. */
    public String baseNameAt(int x, int y, int z) {
        int p = paletteIndexAt(x, y, z);
        if (p == EMPTY || paletteAir[p]) {
            return null;
        }
        return paletteBase[p];
    }

    public int paletteSize() {
        return palette.length;
    }

    public String paletteState(int paletteIndex) {
        return palette[paletteIndex];
    }

    public String paletteBaseName(int paletteIndex) {
        return paletteBase[paletteIndex];
    }

    public boolean paletteIsAir(int paletteIndex) {
        return paletteAir[paletteIndex];
    }

    /** Total number of solid (placeable) blocks across the whole schematic. */
    public long totalSolidBlocks() {
        long total = 0;
        for (int cell : cells) {
            if (cell != EMPTY && !paletteAir[cell]) {
                total++;
            }
        }
        return total;
    }

    /**
     * Required material counts keyed by base block id, highest count first.
     * Air variants and empty cells are excluded.
     */
    public Map<String, Integer> materialCounts() {
        Map<String, Integer> raw = new LinkedHashMap<>();
        for (int cell : cells) {
            if (cell == EMPTY || paletteAir[cell]) {
                continue;
            }
            raw.merge(paletteBase[cell], 1, Integer::sum);
        }
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(raw.entrySet());
        entries.sort((a, b) -> {
            int cmp = Integer.compare(b.getValue(), a.getValue());
            return cmp != 0 ? cmp : a.getKey().compareTo(b.getKey());
        });
        Map<String, Integer> sorted = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : entries) {
            sorted.put(e.getKey(), e.getValue());
        }
        return sorted;
    }

    static String baseName(String state) {
        if (state == null) {
            return "minecraft:air";
        }
        int bracket = state.indexOf('[');
        String base = bracket >= 0 ? state.substring(0, bracket) : state;
        base = base.trim();
        if (base.isEmpty()) {
            return "minecraft:air";
        }
        if (base.indexOf(':') < 0) {
            base = "minecraft:" + base;
        }
        return base;
    }

    static boolean isAirName(String base) {
        return base.equals("minecraft:air")
                || base.equals("minecraft:cave_air")
                || base.equals("minecraft:void_air");
    }
}
