package interstellarTikiTiks.apexx.client.module.implementation.player.autobuild;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtInt;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Parsing of hand-built Litematica / Sponge / structure NBT, material counting, and caps. */
class SchematicIoTest {

    // ------------------------------------------------------------------
    // Litematica
    // ------------------------------------------------------------------

    @Test
    void litematicSingleRegionParsesBlocksAndCounts() throws Exception {
        // palette [air, stone, dirt] -> bits = 2; region 2x2x1; layout index = y*2 + x
        int[] values = {1, 2, 0, 1}; // (0,0,0)=stone (1,0,0)=dirt (0,1,0)=air (1,1,0)=stone
        NbtCompound root = litematic(2, 2, 1,
                new String[]{"minecraft:air", "minecraft:stone", "minecraft:dirt"}, values);

        Schematic s = SchematicIo.parseLitematic(root, "house");

        assertEquals(2, s.sizeX());
        assertEquals(2, s.sizeY());
        assertEquals(1, s.sizeZ());
        assertEquals("minecraft:stone", s.baseNameAt(0, 0, 0));
        assertEquals("minecraft:dirt", s.baseNameAt(1, 0, 0));
        assertNull(s.stateAt(0, 1, 0), "air cell is empty");
        assertFalse(s.isSolid(0, 1, 0));
        assertEquals("minecraft:stone", s.baseNameAt(1, 1, 0));
        assertEquals(3, s.totalSolidBlocks());

        Map<String, Integer> counts = s.materialCounts();
        assertEquals(2, counts.get("minecraft:stone"));
        assertEquals(1, counts.get("minecraft:dirt"));
        // highest count first
        assertEquals("minecraft:stone", counts.keySet().iterator().next());
    }

    @Test
    void litematicPropertiesBecomeStateString() throws Exception {
        NbtCompound stairs = new NbtCompound();
        stairs.putString("Name", "minecraft:oak_stairs");
        NbtCompound props = new NbtCompound();
        props.putString("facing", "east");
        props.putString("half", "bottom");
        stairs.put("Properties", props);
        // base-name counting ignores properties but the full state string is retained
        NbtList palette = new NbtList();
        palette.add(palette.size(), air());
        palette.add(palette.size(), stairs);
        NbtCompound root = litematicRaw(1, 1, 1, palette, new int[]{1});

        Schematic s = SchematicIo.parseLitematic(root, "stairs");
        assertEquals("minecraft:oak_stairs", s.baseNameAt(0, 0, 0));
        assertEquals("minecraft:oak_stairs[facing=east,half=bottom]", s.stateAt(0, 0, 0));
    }

    @Test
    void litematicUnpacksEntriesThatStraddleLongBoundaries() throws Exception {
        // palette size 20 -> bits = 5; 13 blocks in a 13x1x1 row; entry 12 straddles long0/long1.
        String[] names = new String[20];
        names[0] = "minecraft:air";
        for (int i = 1; i < 20; i++) {
            names[i] = "minecraft:stone_" + i;
        }
        int[] values = new int[13];
        for (int x = 0; x < 13; x++) {
            values[x] = x; // cell (x,0,0) -> palette index x
        }
        NbtCompound root = litematic(13, 1, 1, names, values);

        Schematic s = SchematicIo.parseLitematic(root, "row");
        assertEquals(13, s.sizeX());
        for (int x = 0; x < 13; x++) {
            if (x == 0) {
                assertNull(s.stateAt(0, 0, 0));
            } else {
                assertEquals("minecraft:stone_" + x, s.baseNameAt(x, 0, 0), "cell x=" + x);
            }
        }
        assertEquals(12, s.totalSolidBlocks());
    }

    @Test
    void litematicWithoutRegionsThrows() {
        NbtCompound root = new NbtCompound();
        SchematicException ex = assertThrows(SchematicException.class,
                () -> SchematicIo.parseLitematic(root, "bad"));
        assertTrue(ex.getMessage().toLowerCase().contains("region"));
    }

    @Test
    void litematicTruncatedBlockStatesThrows() throws Exception {
        NbtList palette = new NbtList();
        palette.add(palette.size(), air());
        palette.add(palette.size(), named("minecraft:stone"));
        // claim 4x4x4 (needs blockstates) but hand it an empty long array
        NbtCompound root = litematicRaw(4, 4, 4, palette, new int[0]);
        assertThrows(SchematicException.class, () -> SchematicIo.parseLitematic(root, "trunc"));
    }

    // ------------------------------------------------------------------
    // Sponge
    // ------------------------------------------------------------------

    @Test
    void spongeV2ParsesBlocksAndCounts() throws Exception {
        NbtCompound root = new NbtCompound();
        root.putShort("Width", (short) 2);
        root.putShort("Height", (short) 2);
        root.putShort("Length", (short) 1);
        NbtCompound palette = new NbtCompound();
        palette.putInt("minecraft:air", 0);
        palette.putInt("minecraft:stone", 1);
        palette.putInt("minecraft:oak_planks", 2);
        root.put("Palette", palette);
        root.putByteArray("BlockData", new byte[]{1, 2, 0, 1}); // index = y*2 + x

        Schematic s = SchematicIo.parseSponge(root, "sponge");
        assertEquals(2, s.sizeX());
        assertEquals(2, s.sizeY());
        assertEquals(1, s.sizeZ());
        assertEquals("minecraft:stone", s.baseNameAt(0, 0, 0));
        assertEquals("minecraft:oak_planks", s.baseNameAt(1, 0, 0));
        assertNull(s.stateAt(0, 1, 0));
        assertEquals("minecraft:stone", s.baseNameAt(1, 1, 0));
        assertEquals(2, s.materialCounts().get("minecraft:stone"));
        assertEquals(1, s.materialCounts().get("minecraft:oak_planks"));
    }

    @Test
    void spongeDecodesMultiByteVarintAndSparsePalette() throws Exception {
        NbtCompound root = new NbtCompound();
        root.putShort("Width", (short) 1);
        root.putShort("Height", (short) 1);
        root.putShort("Length", (short) 1);
        NbtCompound palette = new NbtCompound();
        palette.putInt("minecraft:air", 0);
        palette.putInt("minecraft:bedrock", 130); // id needs a 2-byte varint
        root.put("Palette", palette);
        root.putByteArray("BlockData", new byte[]{(byte) 0x82, 0x01}); // varint 130

        Schematic s = SchematicIo.parseSponge(root, "bedrock");
        assertEquals("minecraft:bedrock", s.baseNameAt(0, 0, 0));
    }

    @Test
    void spongeV3UnderBlocksCompoundParses() throws Exception {
        NbtCompound blocks = new NbtCompound();
        NbtCompound palette = new NbtCompound();
        palette.putInt("minecraft:air", 0);
        palette.putInt("minecraft:glass", 1);
        blocks.put("Palette", palette);
        blocks.putByteArray("Data", new byte[]{1});
        NbtCompound schematic = new NbtCompound();
        schematic.putShort("Width", (short) 1);
        schematic.putShort("Height", (short) 1);
        schematic.putShort("Length", (short) 1);
        schematic.put("Blocks", blocks);
        NbtCompound root = new NbtCompound();
        root.put("Schematic", schematic);

        Schematic s = SchematicIo.parseSponge(root, "glass");
        assertEquals("minecraft:glass", s.baseNameAt(0, 0, 0));
    }

    @Test
    void spongeRejectsHugeSparsePaletteBeforeAllocating() {
        NbtCompound root = new NbtCompound();
        root.putShort("Width", (short) 1);
        root.putShort("Height", (short) 1);
        root.putShort("Length", (short) 1);
        NbtCompound palette = new NbtCompound();
        palette.putInt("minecraft:stone", Integer.MAX_VALUE - 1);
        root.put("Palette", palette);
        root.putByteArray("BlockData", new byte[]{0});
        assertThrows(SchematicException.class, () -> SchematicIo.parseSponge(root, "sparse"));
    }

    @Test
    void spongeTooLargeThrows() {
        NbtCompound root = new NbtCompound();
        root.putShort("Width", (short) 2000); // > MAX_AXIS
        root.putShort("Height", (short) 1);
        root.putShort("Length", (short) 1);
        NbtCompound palette = new NbtCompound();
        palette.putInt("minecraft:air", 0);
        root.put("Palette", palette);
        root.putByteArray("BlockData", new byte[]{0});
        SchematicException ex = assertThrows(SchematicException.class, () -> SchematicIo.parseSponge(root, "big"));
        assertTrue(ex.getMessage().toLowerCase().contains("too large"));
    }

    // ------------------------------------------------------------------
    // Structure .nbt
    // ------------------------------------------------------------------

    @Test
    void structureNbtParses() throws Exception {
        NbtCompound root = new NbtCompound();
        NbtList size = new NbtList();
        size.add(size.size(), NbtInt.of(2));
        size.add(size.size(), NbtInt.of(1));
        size.add(size.size(), NbtInt.of(1));
        root.put("size", size);

        NbtList palette = new NbtList();
        palette.add(palette.size(), named("minecraft:stone"));
        palette.add(palette.size(), named("minecraft:dirt"));
        root.put("palette", palette);

        NbtList blocks = new NbtList();
        blocks.add(blocks.size(), structureBlock(0, 0, 0, 0));
        blocks.add(blocks.size(), structureBlock(1, 1, 0, 0));
        root.put("blocks", blocks);

        Schematic s = SchematicIo.parseStructure(root, "struct");
        assertEquals(2, s.sizeX());
        assertEquals("minecraft:stone", s.baseNameAt(0, 0, 0));
        assertEquals("minecraft:dirt", s.baseNameAt(1, 0, 0));
        assertEquals(2, s.totalSolidBlocks());
    }

    // ------------------------------------------------------------------
    // Direct unit of the bit reader
    // ------------------------------------------------------------------

    @Test
    void packedEntryRoundTripsForEveryBitWidth() {
        for (int bits = 2; bits <= 12; bits++) {
            int count = 40;
            int[] values = new int[count];
            int max = (1 << bits) - 1;
            for (int i = 0; i < count; i++) {
                values[i] = (i * 7 + 3) & max;
            }
            long[] packed = pack(values, bits);
            for (int i = 0; i < count; i++) {
                assertEquals(values[i], (int) SchematicIo.readPackedEntry(packed, i, bits),
                        "bits=" + bits + " i=" + i);
            }
        }
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private static NbtCompound air() {
        return named("minecraft:air");
    }

    private static NbtCompound named(String name) {
        NbtCompound c = new NbtCompound();
        c.putString("Name", name);
        return c;
    }

    private static NbtCompound structureBlock(int state, int x, int y, int z) {
        NbtCompound c = new NbtCompound();
        c.putInt("state", state);
        NbtList pos = new NbtList();
        pos.add(pos.size(), NbtInt.of(x));
        pos.add(pos.size(), NbtInt.of(y));
        pos.add(pos.size(), NbtInt.of(z));
        c.put("pos", pos);
        return c;
    }

    /** Build a single-region litematic from a flat palette of names and canonical-order values. */
    private static NbtCompound litematic(int sx, int sy, int sz, String[] names, int[] values) {
        NbtList palette = new NbtList();
        for (String n : names) {
            palette.add(palette.size(), named(n));
        }
        int bits = Math.max(2, 32 - Integer.numberOfLeadingZeros(Math.max(1, names.length - 1)));
        return litematicRaw(sx, sy, sz, palette, values, bits);
    }

    private static NbtCompound litematicRaw(int sx, int sy, int sz, NbtList palette, int[] values) {
        int bits = Math.max(2, 32 - Integer.numberOfLeadingZeros(Math.max(1, palette.size() - 1)));
        return litematicRaw(sx, sy, sz, palette, values, bits);
    }

    private static NbtCompound litematicRaw(int sx, int sy, int sz, NbtList palette, int[] values, int bits) {
        NbtCompound region = new NbtCompound();
        NbtCompound size = new NbtCompound();
        size.putInt("x", sx);
        size.putInt("y", sy);
        size.putInt("z", sz);
        region.put("Size", size);
        NbtCompound position = new NbtCompound();
        position.putInt("x", 0);
        position.putInt("y", 0);
        position.putInt("z", 0);
        region.put("Position", position);
        region.put("BlockStatePalette", palette);
        region.putLongArray("BlockStates", pack(values, bits));

        NbtCompound regions = new NbtCompound();
        regions.put("main", region);
        NbtCompound root = new NbtCompound();
        root.put("Regions", regions);
        return root;
    }

    /** Inverse of {@link SchematicIo#readPackedEntry} - the writer used only by the tests. */
    private static long[] pack(int[] values, int bits) {
        long totalBits = (long) values.length * bits;
        int longs = (int) ((totalBits + 63L) / 64L);
        long[] data = new long[Math.max(longs, 0)];
        long mask = bits >= 64 ? -1L : (1L << bits) - 1L;
        for (int i = 0; i < values.length; i++) {
            long v = values[i] & mask;
            long startOffset = (long) i * bits;
            int startLong = (int) (startOffset >>> 6);
            int startBit = (int) (startOffset & 63L);
            data[startLong] |= v << startBit;
            int endLong = (int) (((long) (i + 1) * bits - 1L) >>> 6);
            if (endLong != startLong) {
                data[endLong] |= v >>> (64 - startBit);
            }
        }
        return data;
    }

    // Retained to show the byte layout intent for Sponge varints; not all paths use it.
    @SuppressWarnings("unused")
    private static byte[] varints(int... values) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int value : values) {
            int v = value;
            while ((v & ~0x7F) != 0) {
                out.write((v & 0x7F) | 0x80);
                v >>>= 7;
            }
            out.write(v);
        }
        return out.toByteArray();
    }
}
