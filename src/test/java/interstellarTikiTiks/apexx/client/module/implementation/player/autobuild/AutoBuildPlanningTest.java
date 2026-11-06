package interstellarTikiTiks.apexx.client.module.implementation.player.autobuild;

import interstellarTikiTiks.apexx.client.module.implementation.player.autobuild.SchematicTransform.Mirror;
import interstellarTikiTiks.apexx.client.module.implementation.player.autobuild.SchematicTransform.Rotation;
import net.minecraft.nbt.NbtCompound;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Transforms, placement rules, build ordering, the schematic library and MCEdit parsing. */
class AutoBuildPlanningTest {

    // ------------------------------------------------------------------ transform

    @Test
    void rotationMapsCornersAndSwapsSize() {
        SchematicTransform t = new SchematicTransform(3, 2, Rotation.CW_90, Mirror.NONE);
        assertEquals(2, t.sizeX());
        assertEquals(3, t.sizeZ());
        // North-west corner (0,0) goes to the north-east corner after a clockwise quarter turn.
        assertArrayEquals(new int[]{1, 0}, t.apply(0, 0));
        assertArrayEquals(new int[]{0, 2}, t.apply(2, 1));
    }

    @Test
    void everyRotationAndMirrorIsABijection() {
        for (Rotation r : Rotation.values()) {
            for (Mirror m : Mirror.values()) {
                SchematicTransform t = new SchematicTransform(4, 3, r, m);
                Set<Integer> seen = new HashSet<>();
                for (int x = 0; x < 4; x++) {
                    for (int z = 0; z < 3; z++) {
                        int[] p = t.apply(x, z);
                        assertTrue(p[0] >= 0 && p[0] < t.sizeX() && p[1] >= 0 && p[1] < t.sizeZ(), r + "/" + m);
                        assertTrue(seen.add(p[0] * 100 + p[1]), "collision for " + r + "/" + m);
                    }
                }
            }
        }
    }

    @Test
    void stateTransformRotatesFacingAxisRotationAndConnections() {
        SchematicTransform cw = new SchematicTransform(1, 1, Rotation.CW_90, Mirror.NONE);
        assertEquals("minecraft:oak_stairs[facing=east,half=top,shape=straight]",
                cw.transformState("minecraft:oak_stairs[facing=north,half=top,shape=straight]"));
        assertEquals("minecraft:oak_log[axis=z]", cw.transformState("minecraft:oak_log[axis=x]"));
        assertEquals("minecraft:oak_log[axis=y]", cw.transformState("minecraft:oak_log[axis=y]"));
        assertEquals("minecraft:oak_sign[rotation=4]", cw.transformState("minecraft:oak_sign[rotation=0]"));
        assertEquals("minecraft:oak_fence[east=true,north=false,south=false,west=false]",
                cw.transformState("minecraft:oak_fence[east=false,north=true,south=false,west=false]"));
        assertEquals("minecraft:rail[shape=east_west]", cw.transformState("minecraft:rail[shape=north_south]"));
        assertEquals("minecraft:rail[shape=ascending_south]", cw.transformState("minecraft:rail[shape=ascending_east]"));
        assertEquals("minecraft:stone", cw.transformState("minecraft:stone"));
    }

    @Test
    void mirrorFlipsTheRightAxisAndHinge() {
        SchematicTransform fb = new SchematicTransform(1, 1, Rotation.NONE, Mirror.FRONT_BACK);
        assertEquals("minecraft:furnace[facing=west]", fb.transformState("minecraft:furnace[facing=east]"));
        assertEquals("minecraft:furnace[facing=north]", fb.transformState("minecraft:furnace[facing=north]"));
        assertEquals("minecraft:oak_door[facing=north,half=lower,hinge=right]",
                fb.transformState("minecraft:oak_door[facing=north,half=lower,hinge=left]"));
        SchematicTransform lr = new SchematicTransform(1, 1, Rotation.NONE, Mirror.LEFT_RIGHT);
        assertEquals("minecraft:furnace[facing=south]", lr.transformState("minecraft:furnace[facing=north]"));
        assertEquals("minecraft:oak_sign[rotation=8]", lr.transformState("minecraft:oak_sign[rotation=0]"));
    }

    // ------------------------------------------------------------------ rules

    @Test
    void stairsNeedPlayerFacingAndHalf() {
        PlacementRules.Rule rule = PlacementRules.forState("minecraft:oak_stairs[facing=east,half=top]");
        PlacementRules.Requirement r = rule.options().get(0);
        assertEquals("east", r.playerFacing());
        assertEquals(PlacementRules.Half.UPPER, r.half());
        assertTrue(r.allowsSide("down"));
        assertFalse(r.allowsSide("up"));
    }

    @Test
    void logsClickTheirAxisAndWallTorchesTheirFacing() {
        PlacementRules.Requirement log = PlacementRules.forState("minecraft:oak_log[axis=x]").options().get(0);
        assertEquals(Set.of("east", "west"), log.sides());
        PlacementRules.Rule torch = PlacementRules.forState("minecraft:wall_torch[facing=north]");
        assertTrue(torch.attachment());
        assertEquals(Set.of("north"), torch.options().get(0).sides());
    }

    @Test
    void furnacesFaceThePlayerAndDispensersUseLookDirection() {
        assertEquals("south", PlacementRules.forState("minecraft:furnace[facing=north]").options().get(0).playerFacing());
        assertEquals("up", PlacementRules.forState("minecraft:dispenser[facing=down]").options().get(0).lookDirection());
        assertEquals("down", PlacementRules.forState("minecraft:observer[facing=down]").options().get(0).lookDirection());
    }

    @Test
    void slabsAndSecondHalves() {
        assertTrue(PlacementRules.forState("minecraft:oak_slab[type=double]").doubleSlab());
        assertEquals(PlacementRules.Half.UPPER,
                PlacementRules.forState("minecraft:oak_slab[type=top]").options().get(0).half());
        assertTrue(PlacementRules.forState("minecraft:oak_door[facing=north,half=upper]").skip());
        assertTrue(PlacementRules.forState("minecraft:red_bed[facing=north,part=head]").skip());
        assertFalse(PlacementRules.forState("minecraft:oak_door[facing=north,half=lower]").skip());
        assertEquals(2, PlacementRules.forState("minecraft:oak_trapdoor[facing=north,half=top]").options().size());
    }

    @Test
    void orientationMathMatchesVanilla() {
        assertEquals("south", PlacementRules.horizontalFacing(0f));
        assertEquals("west", PlacementRules.horizontalFacing(90f));
        assertEquals("north", PlacementRules.horizontalFacing(180f));
        assertEquals("east", PlacementRules.horizontalFacing(-90f));
        assertEquals(45.0, PlacementRules.facingMargin(0f), 1e-6);
        assertEquals(0.0, PlacementRules.facingMargin(45f), 1e-6);
        assertEquals("down", PlacementRules.lookDirection(0f, 80f));
        assertEquals("up", PlacementRules.lookDirection(0f, -80f));
        assertEquals("south", PlacementRules.lookDirection(0f, 0f));
        assertTrue(PlacementRules.halfOk(PlacementRules.Half.UPPER, "north", 0.75));
        assertFalse(PlacementRules.halfOk(PlacementRules.Half.UPPER, "north", 0.25));
        assertTrue(PlacementRules.halfOk(PlacementRules.Half.UPPER, "down", 0.0));
    }

    @Test
    void propertyMatchOnlyLooksAtPlacementControlledKeys() {
        Map<String, String> want = Map.of("facing", "north", "half", "bottom");
        assertTrue(PlacementRules.propertiesMatch(want, Map.of("facing", "north", "half", "bottom", "waterlogged", "true")));
        assertFalse(PlacementRules.propertiesMatch(want, Map.of("facing", "east", "half", "bottom")));
        assertTrue(PlacementRules.propertiesMatch(Map.of("type", "left"), Map.of("type", "single")));
    }

    // ------------------------------------------------------------------ build model

    @Test
    void buildOrderIsBottomUpWithAttachmentsLastAndSkipsLeftOut() {
        // 2x2x1 grid: y0 = [torch(attachment), stone]; y1 = [stone, upper door (skip)]
        String[] palette = {"minecraft:torch", "minecraft:stone", "minecraft:oak_door[facing=north,half=upper]"};
        int[] cells = {0, 1, 1, 2};
        Schematic s = new Schematic("t", 2, 2, 1, palette, cells);
        BuildModel m = BuildModel.of(s, Rotation.NONE, Mirror.NONE,
                i -> PlacementRules.forState(palette[i]).skip(),
                i -> PlacementRules.forState(palette[i]).attachment());
        assertEquals(3, m.orderSize());
        assertEquals(1, BuildModel.unpackX(m.orderAt(0)), "stone before torch on layer 0");
        assertEquals(0, BuildModel.unpackY(m.orderAt(0)));
        assertEquals(0, BuildModel.unpackX(m.orderAt(1)));
        assertEquals(1, BuildModel.unpackY(m.orderAt(2)));
        assertEquals(2, m.layerStart(1));
        assertEquals(2, m.paletteAt(1, 1, 0), "skipped cells stay in the grid for the preview");
    }

    @Test
    void buildModelAppliesTheTransformToCellsAndPalette() {
        String[] palette = {"minecraft:furnace[facing=north]"};
        Schematic s = new Schematic("t", 2, 1, 1, palette, new int[]{0, Schematic.EMPTY});
        BuildModel m = BuildModel.of(s, Rotation.CW_90, Mirror.NONE, i -> false, i -> false);
        assertEquals(1, m.sizeX());
        assertEquals(2, m.sizeZ());
        assertEquals(0, m.paletteAt(0, 0, 0));
        assertEquals(Schematic.EMPTY, m.paletteAt(0, 0, 1));
        assertEquals("minecraft:furnace[facing=east]", m.state(0));
    }

    @Test
    void packRoundTrips() {
        int p = BuildModel.pack(1023, 512, 7);
        assertEquals(1023, BuildModel.unpackX(p));
        assertEquals(512, BuildModel.unpackY(p));
        assertEquals(7, BuildModel.unpackZ(p));
    }

    // ------------------------------------------------------------------ library

    @Test
    void libraryListsOnlySchematicsSortedByName(@TempDir Path dir) throws Exception {
        Path schematics = Files.createDirectories(dir.resolve("schematics").resolve("sub"));
        Files.writeString(schematics.resolve("b.litematic"), "x");
        Files.writeString(dir.resolve("schematics").resolve("A.schem"), "x");
        Files.writeString(dir.resolve("schematics").resolve("notes.txt"), "x");
        List<Path> found = SchematicLibrary.list(SchematicLibrary.defaultFolders(dir));
        assertEquals(2, found.size());
        assertEquals("A.schem", found.get(0).getFileName().toString());
        assertEquals("b.litematic", found.get(1).getFileName().toString());
        assertTrue(SchematicLibrary.list(List.of(dir.resolve("missing"))).isEmpty());
    }

    // ------------------------------------------------------------------ MCEdit

    @Test
    void mcEditLegacyIdsBecomeModernStates() throws Exception {
        NbtCompound root = new NbtCompound();
        root.putShort("Width", (short) 3);
        root.putShort("Height", (short) 1);
        root.putShort("Length", (short) 1);
        root.putString("Materials", "Alpha");
        // stone, oak stairs (facing north, top), white->orange wool
        root.putByteArray("Blocks", new byte[]{1, 53, 35});
        root.putByteArray("Data", new byte[]{0, 3 | 4, 1});
        assertTrue(SchematicIo.isMcEdit(root));
        Schematic s = SchematicIo.parseMcEdit(root, "legacy");
        assertEquals("minecraft:stone", s.stateAt(0, 0, 0));
        assertEquals("minecraft:oak_stairs[facing=north,half=top]", s.stateAt(1, 0, 0));
        assertEquals("minecraft:orange_wool", s.stateAt(2, 0, 0));
    }

    @Test
    void legacyUnknownIdsAreAirAndLogsKeepTheirAxis() {
        assertEquals("minecraft:air", LegacySchematicIds.state(254, 0));
        assertEquals("minecraft:spruce_log[axis=x]", LegacySchematicIds.state(17, 1 | 4));
        assertEquals("minecraft:wall_torch[facing=west]", LegacySchematicIds.state(50, 2));
        assertEquals("minecraft:smooth_stone_slab[type=top]", LegacySchematicIds.state(44, 8));
    }

    @Test
    void mcEditTruncatedThrows() {
        NbtCompound root = new NbtCompound();
        root.putShort("Width", (short) 2);
        root.putShort("Height", (short) 2);
        root.putShort("Length", (short) 2);
        root.putByteArray("Blocks", new byte[]{1});
        assertThrows(SchematicException.class, () -> SchematicIo.parseMcEdit(root, "short"));
    }
}
