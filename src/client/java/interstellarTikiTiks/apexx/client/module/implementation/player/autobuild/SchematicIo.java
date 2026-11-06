package interstellarTikiTiks.apexx.client.module.implementation.player.autobuild;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtSizeTracker;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Reads Litematica ({@code .litematic}), Sponge ({@code .schem}) and vanilla structure
 * ({@code .nbt}) schematics into a registry-free {@link Schematic}.
 *
 * <p>The {@code parseXxx(NbtCompound, ...)} entry points are pure functions (no file or MC
 * registry access) so they can be exercised by unit tests from hand-built NBT. {@link #load(Path)}
 * adds file reading and format detection on top.
 */
public final class SchematicIo {

    /** Hard caps so a malformed or oversized file cannot exhaust memory. */
    public static final int MAX_AXIS = 1024;
    public static final long MAX_VOLUME = 4_000_000L;
    private static final long MAX_FILE_BYTES = 64L * 1024L * 1024L;

    private SchematicIo() {
    }

    /** Read + parse a schematic file, choosing the parser from the extension (with a content fallback). */
    public static Schematic load(Path file) throws SchematicException {
        if (file == null) {
            throw new SchematicException("No schematic file selected.");
        }
        String fileName = file.getFileName() == null ? "" : file.getFileName().toString();
        String lower = fileName.toLowerCase(Locale.ROOT);
        String name = stripExtension(fileName);

        NbtCompound root;
        try {
            root = NbtIo.readCompressed(file, NbtSizeTracker.of(MAX_FILE_BYTES));
        } catch (IOException e) {
            throw new SchematicException("Could not read '" + fileName + "': " + e.getMessage(), e);
        } catch (RuntimeException e) {
            throw new SchematicException("'" + fileName + "' is not a valid (gzipped NBT) schematic.", e);
        }
        if (root == null) {
            throw new SchematicException("'" + fileName + "' contained no NBT data.");
        }

        if (lower.endsWith(".litematic")) {
            return parseLitematic(root, name);
        }
        if (lower.endsWith(".schem") || lower.endsWith(".schematic")) {
            return isMcEdit(root) ? parseMcEdit(root, name) : parseSponge(root, name);
        }
        if (lower.endsWith(".nbt")) {
            return parseStructure(root, name);
        }
        // Unknown extension: detect from content.
        if (root.getCompound("Regions").isPresent()) {
            return parseLitematic(root, name);
        }
        if (isMcEdit(root)) {
            return parseMcEdit(root, name);
        }
        if (root.getCompound("Schematic").isPresent() || root.contains("BlockData") || root.contains("Blocks")) {
            return parseSponge(root, name);
        }
        if (root.contains("blocks") && root.contains("palette") && root.contains("size")) {
            return parseStructure(root, name);
        }
        throw new SchematicException("Unsupported schematic format: '" + fileName
                + "'. Supported: .litematic, .schem, .schematic, .nbt");
    }

    // ------------------------------------------------------------------
    // Litematica
    // ------------------------------------------------------------------

    public static Schematic parseLitematic(NbtCompound root, String name) throws SchematicException {
        NbtCompound regions = root.getCompound("Regions")
                .orElseThrow(() -> new SchematicException("Litematic has no 'Regions'."));
        Set<String> regionNames = new TreeSet<>(regions.getKeys());
        if (regionNames.isEmpty()) {
            throw new SchematicException("Litematic has no regions.");
        }

        // Pass 1 - global bounding box across every region.
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        List<RegionBox> boxes = new ArrayList<>();
        for (String regionName : regionNames) {
            NbtCompound region = regions.getCompoundOrEmpty(regionName);
            NbtCompound size = region.getCompoundOrEmpty("Size");
            int sx = size.getInt("x", 0);
            int sy = size.getInt("y", 0);
            int sz = size.getInt("z", 0);
            if (sx == 0 || sy == 0 || sz == 0) {
                continue;
            }
            if (Math.abs((long) sx) > MAX_AXIS || Math.abs((long) sy) > MAX_AXIS
                    || Math.abs((long) sz) > MAX_AXIS) {
                throw new SchematicException("Litematic region is too large: " + regionName + ".");
            }
            NbtCompound pos = region.getCompoundOrEmpty("Position");
            int px = pos.getInt("x", 0);
            int py = pos.getInt("y", 0);
            int pz = pos.getInt("z", 0);
            int ex = px + (sx > 0 ? sx - 1 : sx + 1);
            int ey = py + (sy > 0 ? sy - 1 : sy + 1);
            int ez = pz + (sz > 0 ? sz - 1 : sz + 1);
            RegionBox box = new RegionBox(regionName,
                    Math.min(px, ex), Math.min(py, ey), Math.min(pz, ez),
                    Math.abs(sx), Math.abs(sy), Math.abs(sz));
            boxes.add(box);
            minX = Math.min(minX, box.minX);
            minY = Math.min(minY, box.minY);
            minZ = Math.min(minZ, box.minZ);
            maxX = Math.max(maxX, box.minX + box.absX - 1);
            maxY = Math.max(maxY, box.minY + box.absY - 1);
            maxZ = Math.max(maxZ, box.minZ + box.absZ - 1);
        }
        if (boxes.isEmpty()) {
            throw new SchematicException("Litematic regions all have zero size.");
        }

        int sizeX = maxX - minX + 1;
        int sizeY = maxY - minY + 1;
        int sizeZ = maxZ - minZ + 1;
        checkDimensions(sizeX, sizeY, sizeZ);

        int[] cells = newEmptyCells(sizeX, sizeY, sizeZ);
        List<String> palette = new ArrayList<>();
        Map<String, Integer> interner = new HashMap<>();

        // Pass 2 - merge every region's blocks into the global grid.
        for (RegionBox box : boxes) {
            NbtCompound region = regions.getCompoundOrEmpty(box.name);
            NbtList paletteList = region.getListOrEmpty("BlockStatePalette");
            int localPaletteSize = paletteList.size();
            if (localPaletteSize == 0) {
                continue;
            }
            int[] remap = new int[localPaletteSize];
            for (int i = 0; i < localPaletteSize; i++) {
                remap[i] = intern(palette, interner, blockStateString(paletteList.getCompoundOrEmpty(i)));
            }

            long[] data = region.getLongArray("BlockStates").orElse(new long[0]);
            int bits = Math.max(2, 32 - Integer.numberOfLeadingZeros(Math.max(1, localPaletteSize - 1)));
            long entries = (long) box.absX * box.absY * box.absZ;
            long requiredLongs = (entries * bits + 63L) / 64L;
            if (data.length < requiredLongs) {
                throw new SchematicException("Litematic region '" + box.name
                        + "' is truncated (expected " + requiredLongs + " longs, found " + data.length + ").");
            }

            int offX = box.minX - minX;
            int offY = box.minY - minY;
            int offZ = box.minZ - minZ;
            long li = 0;
            for (int y = 0; y < box.absY; y++) {
                for (int z = 0; z < box.absZ; z++) {
                    for (int x = 0; x < box.absX; x++, li++) {
                        int local = (int) readPackedEntry(data, li, bits);
                        if (local < 0 || local >= localPaletteSize) {
                            continue;
                        }
                        int global = remap[local];
                        cells[(((y + offY) * sizeZ) + (z + offZ)) * sizeX + (x + offX)] = global;
                    }
                }
            }
        }

        return new Schematic(name, sizeX, sizeY, sizeZ, palette.toArray(new String[0]), cells);
    }

    /** Unpack a value from Litematica's tightly-packed long array (entries may straddle longs). */
    static long readPackedEntry(long[] data, long index, int bits) {
        long startOffset = index * bits;
        int startLong = (int) (startOffset >>> 6);
        int endLong = (int) (((index + 1L) * bits - 1L) >>> 6);
        int startBit = (int) (startOffset & 63L);
        long mask = bits >= 64 ? -1L : (1L << bits) - 1L;
        if (startLong == endLong) {
            return (data[startLong] >>> startBit) & mask;
        }
        int bitsInFirst = 64 - startBit;
        return ((data[startLong] >>> startBit) | (data[endLong] << bitsInFirst)) & mask;
    }

    // ------------------------------------------------------------------
    // Sponge (.schem) - v2 at root, v3 under "Schematic"/"Blocks"
    // ------------------------------------------------------------------

    public static Schematic parseSponge(NbtCompound root, String name) throws SchematicException {
        NbtCompound s = root.getCompound("Schematic").orElse(root);
        int width = s.getInt("Width", 0);
        int height = s.getInt("Height", 0);
        int length = s.getInt("Length", 0);
        if (width <= 0 || height <= 0 || length <= 0) {
            throw new SchematicException("Sponge schematic has invalid dimensions "
                    + width + "x" + height + "x" + length + ".");
        }
        checkDimensions(width, height, length);

        NbtCompound blocks = s.getCompound("Blocks").orElse(null);
        NbtCompound paletteComp;
        byte[] data;
        if (blocks != null) { // v3
            paletteComp = blocks.getCompoundOrEmpty("Palette");
            data = blocks.getByteArray("Data").orElse(null);
        } else { // v1/v2
            paletteComp = s.getCompoundOrEmpty("Palette");
            data = s.getByteArray("BlockData").orElse(null);
        }
        if (paletteComp.getKeys().isEmpty()) {
            throw new SchematicException("Sponge schematic has an empty block palette.");
        }
        if (data == null) {
            throw new SchematicException("Sponge schematic has no block data.");
        }

        int maxId = -1;
        Map<Integer, String> byId = new HashMap<>();
        for (String key : paletteComp.getKeys()) {
            int id = paletteComp.getInt(key, -1);
            if (id < 0) {
                continue;
            }
            if (id >= MAX_VOLUME) {
                throw new SchematicException("Sponge schematic palette id is too large: " + id + ".");
            }
            byId.put(id, key);
            maxId = Math.max(maxId, id);
        }
        if (maxId < 0) {
            throw new SchematicException("Sponge schematic palette has no valid ids.");
        }
        String[] palette = new String[maxId + 1];
        for (int i = 0; i <= maxId; i++) {
            palette[i] = byId.getOrDefault(i, "minecraft:air");
        }

        int total = width * height * length; // bounded by checkDimensions
        int[] cells = new int[total];
        // Sponge block order is x + z*W + y*W*L == canonical (y*sizeZ+z)*sizeX+x, so fill directly.
        int cursor = 0;
        for (int i = 0; i < total; i++) {
            int value = 0;
            int shift = 0;
            while (true) {
                if (cursor >= data.length) {
                    throw new SchematicException("Sponge block data ended early (at block " + i + " of " + total + ").");
                }
                int b = data[cursor++] & 0xFF;
                value |= (b & 0x7F) << shift;
                if ((b & 0x80) == 0) {
                    break;
                }
                shift += 7;
                if (shift >= 35) {
                    throw new SchematicException("Sponge block data has a malformed varint at block " + i + ".");
                }
            }
            cells[i] = (value >= 0 && value < palette.length) ? value : Schematic.EMPTY;
        }

        return new Schematic(name, width, height, length, palette, cells);
    }

    // ------------------------------------------------------------------
    // Legacy MCEdit / WorldEdit (.schematic, numeric ids)
    // ------------------------------------------------------------------

    /** MCEdit files keep numeric ids in a root "Blocks" byte array and have no palette. */
    static boolean isMcEdit(NbtCompound root) {
        return root.getByteArray("Blocks").isPresent() && root.getCompound("Palette").isEmpty()
                && root.getCompound("Schematic").isEmpty();
    }

    public static Schematic parseMcEdit(NbtCompound root, String name) throws SchematicException {
        int width = root.getInt("Width", 0);
        int height = root.getInt("Height", 0);
        int length = root.getInt("Length", 0);
        if (width <= 0 || height <= 0 || length <= 0) {
            throw new SchematicException("MCEdit schematic has invalid dimensions "
                    + width + "x" + height + "x" + length + ".");
        }
        checkDimensions(width, height, length);
        byte[] blocks = root.getByteArray("Blocks").orElse(new byte[0]);
        byte[] data = root.getByteArray("Data").orElse(new byte[0]);
        byte[] add = root.getByteArray("AddBlocks").orElse(null);
        int total = width * height * length;
        if (blocks.length < total) {
            throw new SchematicException("MCEdit schematic is truncated (expected " + total
                    + " blocks, found " + blocks.length + ").");
        }

        List<String> palette = new ArrayList<>();
        Map<String, Integer> interner = new HashMap<>();
        int[] cells = new int[total];
        // MCEdit order is (y * Length + z) * Width + x == canonical order.
        for (int i = 0; i < total; i++) {
            int id = blocks[i] & 0xFF;
            if (add != null && (i >> 1) < add.length) {
                int nibble = (i & 1) == 0 ? (add[i >> 1] >> 4) & 0xF : add[i >> 1] & 0xF;
                id |= nibble << 8;
            }
            int meta = i < data.length ? data[i] & 0xF : 0;
            String state = LegacySchematicIds.state(id, meta);
            cells[i] = intern(palette, interner, state);
        }
        return new Schematic(name, width, height, length, palette.toArray(new String[0]), cells);
    }

    // ------------------------------------------------------------------
    // Vanilla structure block (.nbt)
    // ------------------------------------------------------------------

    public static Schematic parseStructure(NbtCompound root, String name) throws SchematicException {
        NbtList sizeList = root.getListOrEmpty("size");
        if (sizeList.size() < 3) {
            throw new SchematicException("Structure NBT has no 'size'.");
        }
        int sizeX = sizeList.getInt(0, 0);
        int sizeY = sizeList.getInt(1, 0);
        int sizeZ = sizeList.getInt(2, 0);
        if (sizeX <= 0 || sizeY <= 0 || sizeZ <= 0) {
            throw new SchematicException("Structure NBT has invalid size "
                    + sizeX + "x" + sizeY + "x" + sizeZ + ".");
        }
        checkDimensions(sizeX, sizeY, sizeZ);

        NbtList paletteList = root.getListOrEmpty("palette");
        if (paletteList.size() == 0) {
            throw new SchematicException("Structure NBT has no palette.");
        }
        String[] palette = new String[paletteList.size()];
        for (int i = 0; i < paletteList.size(); i++) {
            palette[i] = blockStateString(paletteList.getCompoundOrEmpty(i));
        }

        int[] cells = newEmptyCells(sizeX, sizeY, sizeZ);
        NbtList blocksList = root.getListOrEmpty("blocks");
        for (int i = 0; i < blocksList.size(); i++) {
            NbtCompound entry = blocksList.getCompoundOrEmpty(i);
            int state = entry.getInt("state", -1);
            if (state < 0 || state >= palette.length) {
                continue;
            }
            NbtList pos = entry.getListOrEmpty("pos");
            if (pos.size() < 3) {
                continue;
            }
            int x = pos.getInt(0, -1);
            int y = pos.getInt(1, -1);
            int z = pos.getInt(2, -1);
            if (x < 0 || y < 0 || z < 0 || x >= sizeX || y >= sizeY || z >= sizeZ) {
                continue;
            }
            cells[(y * sizeZ + z) * sizeX + x] = state;
        }

        return new Schematic(name, sizeX, sizeY, sizeZ, palette, cells);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Build {@code "namespace:path[k1=v1,k2=v2]"} from a {Name, Properties} palette compound. */
    static String blockStateString(NbtCompound entry) {
        String blockName = entry.getString("Name", "minecraft:air");
        NbtCompound properties = entry.getCompoundOrEmpty("Properties");
        Set<String> keys = properties.getKeys();
        if (keys.isEmpty()) {
            return blockName;
        }
        StringBuilder sb = new StringBuilder(blockName).append('[');
        boolean first = true;
        for (String key : new TreeSet<>(keys)) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append(key).append('=').append(properties.getString(key, ""));
        }
        return sb.append(']').toString();
    }

    private static int intern(List<String> palette, Map<String, Integer> interner, String state) {
        Integer existing = interner.get(state);
        if (existing != null) {
            return existing;
        }
        int idx = palette.size();
        palette.add(state);
        interner.put(state, idx);
        return idx;
    }

    private static int[] newEmptyCells(int sizeX, int sizeY, int sizeZ) {
        int[] cells = new int[sizeX * sizeY * sizeZ];
        java.util.Arrays.fill(cells, Schematic.EMPTY);
        return cells;
    }

    private static void checkDimensions(int sizeX, int sizeY, int sizeZ) throws SchematicException {
        if (sizeX > MAX_AXIS || sizeY > MAX_AXIS || sizeZ > MAX_AXIS) {
            throw new SchematicException("Schematic is too large: " + sizeX + "x" + sizeY + "x" + sizeZ
                    + " (max " + MAX_AXIS + " per axis).");
        }
        long volume = (long) sizeX * sizeY * sizeZ;
        if (volume > MAX_VOLUME) {
            throw new SchematicException("Schematic is too large: " + volume + " blocks (max " + MAX_VOLUME + ").");
        }
    }

    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    private record RegionBox(String name, int minX, int minY, int minZ, int absX, int absY, int absZ) {
    }
}
