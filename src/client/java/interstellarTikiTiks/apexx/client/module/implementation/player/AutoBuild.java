package interstellarTikiTiks.apexx.client.module.implementation.player;

import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.ColorSetting;
import interstellarTikiTiks.apexx.client.module.ModeSetting;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import interstellarTikiTiks.apexx.client.module.StringSetting;
import interstellarTikiTiks.apexx.client.module.hooks.FileDropHandler;
import interstellarTikiTiks.apexx.client.module.hooks.ItemUseBlockProvider;
import interstellarTikiTiks.apexx.client.module.hooks.SchematicHudProvider;
import interstellarTikiTiks.apexx.client.module.implementation.player.autobuild.BuildModel;
import interstellarTikiTiks.apexx.client.module.implementation.player.autobuild.BuildProgressStore;
import interstellarTikiTiks.apexx.client.module.implementation.player.autobuild.LibraryIndex;
import interstellarTikiTiks.apexx.client.module.implementation.player.autobuild.PlacementRules;
import interstellarTikiTiks.apexx.client.module.implementation.player.autobuild.RestockController;
import interstellarTikiTiks.apexx.client.module.implementation.player.autobuild.Schematic;
import interstellarTikiTiks.apexx.client.module.implementation.player.autobuild.SchematicException;
import interstellarTikiTiks.apexx.client.module.implementation.player.autobuild.SchematicFilePicker;
import interstellarTikiTiks.apexx.client.module.implementation.player.autobuild.SchematicIo;
import interstellarTikiTiks.apexx.client.module.implementation.player.autobuild.SchematicLibrary;
import interstellarTikiTiks.apexx.client.module.implementation.player.autobuild.SchematicTransform;
import interstellarTikiTiks.apexx.client.module.implementation.player.autobuild.SmartOrder;
import interstellarTikiTiks.apexx.client.module.implementation.player.autobuild.StateString;
import interstellarTikiTiks.apexx.client.module.implementation.support.BaritoneBridge;
import interstellarTikiTiks.apexx.client.util.ClickSimController;
import interstellarTikiTiks.apexx.client.util.ExecutionMode;
import interstellarTikiTiks.apexx.client.util.Notifier;
import interstellarTikiTiks.apexx.client.util.PlaceResolver;
import interstellarTikiTiks.apexx.client.util.PlaceSolution;
import interstellarTikiTiks.apexx.client.util.PlaceValidator;
import interstellarTikiTiks.apexx.client.util.RotationManager;
import interstellarTikiTiks.apexx.client.util.SystemInputSimulator;
import interstellarTikiTiks.apexx.client.util.combat.BlockPlacementValidator;
import interstellarTikiTiks.apexx.client.util.combat.InteractSim;
import interstellarTikiTiks.apexx.client.util.combat.ModuleDelays;
import interstellarTikiTiks.apexx.client.util.combat.SlotSync;
import interstellarTikiTiks.apexx.client.util.combat.SubTickSequencer;
import interstellarTikiTiks.apexx.client.util.combat.TickActionOrder;
import interstellarTikiTiks.apexx.client.util.render.RenderUtils;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.FluidBlock;
import net.minecraft.block.MapColor;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.BufferAllocator;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.state.property.Property;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.awt.Color;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * AutoBuild - loads a Litematica / Sponge / MCEdit / structure schematic, previews it in the world
 * (colour-coded correct / missing / wrong) with a layer diagram, a materials HUD and checklist, and
 * builds it block by block at vanilla cadence through the shared placement seams.
 *
 * <p>Schematics come from a native file picker, drag-and-drop onto the ClickGUI, a typed path or the
 * in-GUI library (recent files plus the usual schematic folders). Where a build stood (origin,
 * transform, layer) is remembered per schematic, world and dimension so it resumes after a relog.
 *
 * <p>The schematic can be mirrored, rotated and offset from its anchor. Directional block states
 * (stairs, slabs, logs, trapdoors, furnaces, torches, buttons...) are reproduced by choosing the
 * support face, hit height and player facing vanilla derives them from ({@link PlacementRules}); a
 * placed block whose controlled properties differ counts as wrong. The Smart order places plain
 * blocks, then gravity blocks, then attached blocks, then fluid-sensitive ones in each layer, and
 * never walls the player in. Optionally the builder mines blocks that occupy or obstruct a target,
 * places temporary scaffold where a block has no face to click, jump-places a pillar to reach layers
 * above reach, restocks from nearby containers, walks with Baritone (when installed) and removes its
 * scaffold afterward. Easy Place turns a right-click on a ghost block into placing the right block.
 *
 * <p>Every place is a visible SlotSync selection followed later in the same tick by a vanilla use,
 * gated on the live ray reaching the exact support face; the rotation is retained for the rest of
 * the tick. Mining holds the vanilla attack key while the camera crosshair is on the block, so it
 * always uses visible aim and breaks at vanilla speed. All waits are this module's own sliders.
 */
public class AutoBuild extends Module implements ItemUseBlockProvider, FileDropHandler, SchematicHudProvider, interstellarTikiTiks.apexx.client.module.hooks.ClickSimulating {

    private static final int SCAN_WINDOW = 4096;    // cells examined per tick when seeking the next target
    private static final int GHOST_CELL_CAP = 6000; // max cubes drawn per frame
    private static final int RECOUNT_BUDGET = 40_000;
    private static final long MINE_TIMEOUT_MS = 15_000L;
    private static final long TOWER_TIMEOUT_MS = 1_500L;
    private static final long EASY_TTL_MS = 1_500L;
    private static final long ETA_WINDOW_MS = 120_000L;
    private static final double FACING_MARGIN_DEG = 8.0;
    private static final int LIBRARY_MAX = 24;
    private static final int RECENT_MAX = 20;
    private static final String LIBRARY_PLACEHOLDER = "(choose a schematic)";
    private static final String LIBRARY_EMPTY = "(press Refresh Library)";

    private final MinecraftClient mc = MinecraftClient.getInstance();

    // --- Library ---
    private final StringSetting pathSetting = new StringSetting("Schematic Path", "");
    private final BooleanSetting browse = new BooleanSetting("Browse File", false);
    private final ModeSetting libraryPick = new ModeSetting("Library", LIBRARY_EMPTY);
    private final StringSetting librarySearch = new StringSetting("Library Search", "");
    private final BooleanSetting nextFile = new BooleanSetting("Next Schematic", false);
    private final BooleanSetting prevFile = new BooleanSetting("Previous Schematic", false);
    private final BooleanSetting refreshLibrary = new BooleanSetting("Refresh Library", false);
    private final StringSetting recentSetting = new StringSetting("Recent Schematics", "");

    // --- Placement / transform ---
    private final ModeSetting originMode =
            new ModeSetting("Origin", "Player Feet", "Looked-at Block", "Captured Corner");
    private final BooleanSetting capture = new BooleanSetting("Capture Corner", false);
    private final SliderSetting offsetX = new SliderSetting("Offset X", 0, -64, 64, 0);
    private final SliderSetting offsetY = new SliderSetting("Offset Y", 0, -64, 64, 0);
    private final SliderSetting offsetZ = new SliderSetting("Offset Z", 0, -64, 64, 0);
    private final ModeSetting rotationMode = new ModeSetting("Rotation", "0", "90", "180", "270");
    private final ModeSetting mirrorMode = new ModeSetting("Mirror", "None", "Front-Back", "Left-Right");
    private final BooleanSetting resetProgress = new BooleanSetting("Reset Progress", false);
    private final StringSetting progressSetting = new StringSetting("Build Progress", "");

    // --- Build ---
    private final BooleanSetting autoPlace = new BooleanSetting("Auto Place", false);
    private final ModeSetting buildOrder = new ModeSetting("Build Order", "Smart", "Layer (bottom-up)", "Nearest First");
    private final BooleanSetting easyPlace = new BooleanSetting("Easy Place", false);
    private final BooleanSetting matchStates = new BooleanSetting("Match States", true);
    private final BooleanSetting silentAim = new BooleanSetting("Silent Aim", false);
    private final SliderSetting rotationSpeed = new SliderSetting("Rotation Speed", 60, 1, 100, 0);
    private final SliderSetting maxReach = new SliderSetting("Max Reach", 4.5, 1.0, 4.5, 1);
    private final BooleanSetting onlySafe = new BooleanSetting("Only Safe Placements", true);

    // --- Reach & obstacles ---
    private final BooleanSetting clearObstructions = new BooleanSetting("Clear Obstructions", false);
    private final BooleanSetting scaffold = new BooleanSetting("Scaffold", false);
    private final BooleanSetting towerUp = new BooleanSetting("Tower Up", false);
    private final BooleanSetting removeScaffold = new BooleanSetting("Remove Scaffold", true);
    private final StringSetting scaffoldBlocks = new StringSetting("Scaffold Blocks",
            "minecraft:cobblestone,minecraft:dirt,minecraft:netherrack,minecraft:cobbled_deepslate,minecraft:stone");

    // --- Restock / pathing ---
    private final BooleanSetting autoRestock = new BooleanSetting("Auto Restock", false);
    private final SliderSetting restockRange = new SliderSetting("Restock Range", 16, 4, 64, 0);
    private final BooleanSetting walkToBlocks = new BooleanSetting("Walk To Blocks", false);

    // --- HUD ---
    private final BooleanSetting materialsHud = new BooleanSetting("Materials HUD", true);
    private final SliderSetting hudRowCount = new SliderSetting("HUD Rows", 8, 1, 20, 0);
    private final BooleanSetting countShulkers = new BooleanSetting("Count Shulkers", true);

    // --- Visuals ---
    private final BooleanSetting diagram = new BooleanSetting("Diagram Overlay", true);
    private final SliderSetting diagramLayer = new SliderSetting("Diagram Layer", 0, 0, 384, 0);
    private final SliderSetting diagramScale = new SliderSetting("Diagram Scale", 6, 2, 16, 0);
    private final BooleanSetting ghost = new BooleanSetting("Ghost Preview", true);
    private final SliderSetting ghostRange = new SliderSetting("Ghost Range", 48, 8, 160, 0);
    private final SliderSetting ghostLayers = new SliderSetting("Ghost Layers Above", 0, 0, 64, 0);
    private final BooleanSetting showCorrect = new BooleanSetting("Show Correct", true);
    private final BooleanSetting showMissing = new BooleanSetting("Show Missing", true);
    private final BooleanSetting showWrong = new BooleanSetting("Show Wrong", true);
    private final BooleanSetting ghostOutline = new BooleanSetting("Ghost Outline", true);
    private final ColorSetting remainingColor = new ColorSetting("Remaining Color", new Color(90, 170, 255, 110));
    private final ColorSetting placedColor = new ColorSetting("Placed Color", new Color(95, 230, 130, 70));
    private final ColorSetting wrongColor = new ColorSetting("Wrong Color", new Color(240, 80, 80, 120));
    private final ColorSetting scaffoldColor = new ColorSetting("Scaffold Color", new Color(240, 200, 70, 90));

    // --- Delays ---
    private final SliderSetting placeDelay = new SliderSetting("Place Delay (ms)", 150, 0, 1000, 0).withJitter(30);
    private final SliderSetting swapDelay = new SliderSetting("Swap Delay (ms)", 30, 0, 45, 0).withJitter(4);
    private final SliderSetting breakDelay = new SliderSetting("Break Delay (ms)", 120, 0, 1000, 0).withJitter(25);
    private final SliderSetting swapBackDelay = new SliderSetting("Swap Back Delay (ms)", 200, 0, 2000, 0).withJitter(40);
    private final SliderSetting towerDelay = new SliderSetting("Tower Delay (ms)", 150, 0, 1000, 0).withJitter(30);
    private final SliderSetting restockOpenDelay = new SliderSetting("Restock Open Delay (ms)", 250, 0, 1000, 0).withJitter(50);
    private final SliderSetting restockClickDelay = new SliderSetting("Restock Click Delay (ms)", 120, 0, 1000, 0).withJitter(25);
    private final SliderSetting restockCloseDelay = new SliderSetting("Restock Close Delay (ms)", 200, 0, 1000, 0).withJitter(40);
    private final SliderSetting pathDelay = new SliderSetting("Path Delay (ms)", 800, 0, 1000, 0).withJitter(150);

    // --- Runtime (client thread unless noted) ---
    private enum Kind { PLACE, SCAFFOLD, MINE, DOUBLE_SLAB }
    private enum CellStatus { CORRECT, MISSING, WRONG }

    private record LoadResult(long generation, Schematic schematic, String error, Path path, long size) { }
    private record Candidate(Kind kind, BlockPos pos, int slot, Item item, PlaceSolution solution,
                             BlockPos expectCell, PlacementRules.Requirement requirement) { }

    private final AtomicReference<LoadResult> pendingLoad = new AtomicReference<>();
    private volatile boolean loading;
    private String status = "No schematic loaded.";
    private Schematic schematic;
    private Path schematicPath;
    private long schematicSize = -1;
    private BuildModel model;
    private boolean modelDirty;
    private Block[] blockCache;   // palette index -> resolved block
    private Item[] itemCache;     // palette index -> resolved item
    private int[] colorCache;     // palette index -> ARGB diagram colour
    private PlacementRules.Rule[] ruleCache;
    private Map<String, String>[] targetProps;
    private boolean[] unplaceable;
    private boolean[] gravityCache;   // falls without a block below
    private boolean[] floorCache;     // must stand on the block below
    private boolean[] dependentCache; // hangs on or stands on another block; never scaffolded for
    private int lowDone;          // watermark: order entries below this are already correct

    private BlockPos buildOrigin;        // latched while building
    private BlockPos capturedCorner;
    private net.minecraft.client.network.ClientPlayerEntity lastPlayer;
    private net.minecraft.client.world.ClientWorld lastWorld;

    // Library
    private final LibraryIndex library = new LibraryIndex();
    private final Map<String, Path> libraryLabels = new HashMap<>();
    private int libraryCursor = -1;
    private int pendingLibraryStep;
    private boolean announceLibrary;

    // Progress
    private final BuildProgressStore progressStore = new BuildProgressStore();
    private boolean pendingRestore;
    private int resumeLayer = -1;
    private boolean fullVerifyPending;
    private int lastSavedLayer = -1;

    private long nextPlaceAllowedMs;
    private long nextBreakAllowedMs;
    private long swapBackAtMs;
    private int originalSlot = -1;
    private int expectedSlot = -1;
    private net.minecraft.client.network.ClientPlayerEntity slotPlayer;
    private net.minecraft.client.world.ClientWorld slotWorld;
    private long loadGeneration;
    private Map<String, Integer> materials = Map.of();
    private Map<String, Integer> remainingMaterials = Map.of();
    private final Map<String, Integer> recountAcc = new HashMap<>();
    private int recountCursor;
    private int recountTotalAcc, recountMissingAcc, recountWrongAcc;
    private int cellsTotal = -1, cellsMissing, cellsWrong;
    private final ArrayDeque<long[]> etaSamples = new ArrayDeque<>();
    private long placedCount;
    private volatile Vec3d aimPoint;

    private BlockPos miningPos;
    private long miningSince;
    private boolean attackHeld;
    private boolean miningScaffold;

    private boolean towering;
    private BlockPos towerGround;
    private long towerStartMs;
    private long nextTowerAllowedMs;
    private boolean jumpHeld;

    // Easy Place
    private boolean selfUse;
    private BlockPos easyTarget;
    private long easyTargetAt;

    // Walking (Baritone)
    private BlockPos walkGoal;
    private long nextPathMs;

    // HUD (built on the client tick, read on the render thread)
    private volatile List<Row> hudRows = List.of();
    private int hudTick;

    private final Set<BlockPos> scaffoldPositions = new LinkedHashSet<>();

    private int statOutOfReach, statNoSupport, statNoMaterial, statNotInHotbar, statBlocked, statWrong, statTrap;
    private BlockPos nearestUnreachable;

    private final RestockController restock = new RestockController(new RestockController.Host() {
        @Override public Object owner() { return AutoBuild.this; }
        @Override public boolean silentAim() { return silentAim.isEnabled(); }
        @Override public double rotationStrength() {
            return RotationManager.engineStrength(rotationSpeed.getValue(), RotationManager.STRENGTH_SCALE_TRACK);
        }
        @Override public double reach() { return currentReach(); }
        @Override public double range() { return restockRange.getValue(); }
        @Override public Map<Item, Integer> shortfall() { return materialShortfall(); }
        @Override public boolean isMaterial(Item item) { return isBuildMaterial(item); }
        @Override public long openDelayMs() { return ModuleDelays.clampMs(restockOpenDelay.sample()); }
        @Override public long clickDelayMs() { return ModuleDelays.clampMs(restockClickDelay.sample()); }
        @Override public long closeDelayMs() { return ModuleDelays.clampMs(restockCloseDelay.sample()); }
        @Override public long pathDelayMs() { return ModuleDelays.clampMs(pathDelay.sample()); }
        @Override public boolean walkAllowed() { return walkToBlocks.isEnabled(); }
        @Override public void runSelfUse(Runnable click) { runAsSelfUse(click); }
        @Override public void releaseAim() { RotationManager.stop(AutoBuild.this); }
        @Override public void notice(String key, String message) { warnMissing(key, message); }
    });

    public AutoBuild() {
        super("AutoBuild", "Builds a loaded schematic: file picker, drag-and-drop, library, resume, smart order, "
                + "easy place, restock, ghost preview and materials HUD.", Category.SMP);

        pathSetting.setDescription("Absolute path to a .litematic, .schem, .schematic or .nbt file.").setSection("Library");
        pathSetting.onChange(() -> {
            String p = pathSetting.getValue();
            if (p != null && !p.isBlank()) {
                Path path = toPath(p);
                if (path != null && Files.isRegularFile(path)) {
                    requestLoad(path);
                }
            }
        });
        browse.setDescription("Open your system's file picker to choose schematics (several at once is fine). "
                + "You can also drag files onto the ClickGUI. Resets itself.").setSection("Library");
        browse.onChange(() -> {
            if (browse.isEnabled()) {
                browse.setValue(false);
                runOnClient(this::openFilePicker);
            }
        });
        libraryPick.setDescription("Recent files and the schematics folders. Picking one loads it.").setSection("Library");
        libraryPick.onChange(() -> {
            Path picked = libraryLabels.get(libraryPick.getCurrentMode());
            if (picked == null) return;
            if (picked.toString().equals(pathSetting.getValue())) {
                if (schematic == null || !picked.equals(schematicPath)) requestLoad(picked);
            } else {
                pathSetting.setValue(picked.toString());
            }
        });
        librarySearch.setDescription("Only list library files whose name contains this text.").setSection("Library");
        librarySearch.onChange(() -> runOnClient(() -> {
            if (library.files().isEmpty()) refreshLibraryList(false);
            else rebuildLibraryLabels();
        }));
        nextFile.setDescription("Load the next file from the library. Resets itself.").setSection("Library");
        nextFile.onChange(() -> {
            if (nextFile.isEnabled()) {
                nextFile.setValue(false);
                runOnClient(() -> stepLibrary(1));
            }
        });
        prevFile.setDescription("Load the previous file from the library. Resets itself.").setSection("Library");
        prevFile.onChange(() -> {
            if (prevFile.isEnabled()) {
                prevFile.setValue(false);
                runOnClient(() -> stepLibrary(-1));
            }
        });
        refreshLibrary.setDescription("Rescan .minecraft/schematics and config/schematics. Resets itself.").setSection("Library");
        refreshLibrary.onChange(() -> {
            if (refreshLibrary.isEnabled()) {
                refreshLibrary.setValue(false);
                runOnClient(() -> refreshLibraryList(true));
            }
        });
        recentSetting.setDescription("Recently loaded schematic files (internal).").setSection("Library");
        recentSetting.setVisibility(() -> false);

        originMode.setDescription("Where the schematic's minimum corner is anchored (before the offsets).").setSection("Placement");
        capture.setDescription("Snapshot the block you are looking at as the build origin corner.").setSection("Placement");
        capture.onChange(() -> {
            if (capture.isEnabled()) {
                capture.setValue(false);
                runOnClient(this::captureCorner);
            }
        });
        Runnable reanchor = () -> {
            buildOrigin = null;
            lowDone = 0;
        };
        offsetX.setDescription("Shift the build along X, in blocks.").setSection("Placement");
        offsetY.setDescription("Shift the build up or down, in blocks.").setSection("Placement");
        offsetZ.setDescription("Shift the build along Z, in blocks.").setSection("Placement");
        offsetX.onChange(reanchor);
        offsetY.onChange(reanchor);
        offsetZ.onChange(reanchor);
        rotationMode.setDescription("Rotate the schematic clockwise (seen from above), in degrees. Facing states rotate with it.").setSection("Placement");
        mirrorMode.setDescription("Mirror the schematic before rotating: Front-Back flips east/west, Left-Right flips north/south.").setSection("Placement");
        rotationMode.onChange(() -> modelDirty = true);
        mirrorMode.onChange(() -> modelDirty = true);
        resetProgress.setDescription("Forget where this schematic was being built in this world, so it starts fresh. "
                + "Resets itself.").setSection("Placement");
        resetProgress.onChange(() -> {
            if (resetProgress.isEnabled()) {
                resetProgress.setValue(false);
                runOnClient(this::resetBuildProgress);
            }
        });
        progressSetting.setDescription("Saved build positions per schematic and world (internal).").setSection("Placement");
        progressSetting.setVisibility(() -> false);

        autoPlace.setDescription("Build automatically. Off = preview only (ghost, diagram and checklist).").setSection("Build");
        autoPlace.onChange(() -> {
            buildOrigin = null;
            lowDone = 0;
            releasePlacementState();
        });
        buildOrder.setDescription("Smart builds each layer bottom-up as plain, gravity, attached, then water-sensitive blocks, "
                + "checks support and never walls you in. Layer is a plain bottom-up sweep; Nearest First prefers the "
                + "closest reachable block.").setSection("Build");
        buildOrder.onChange(() -> modelDirty = true);
        easyPlace.setDescription("Right-click a ghost block to place the right block there (correct facing included), "
                + "picked from your hotbar. Also stops you placing a different block into a schematic cell.")
                .setSection("Build");
        easyPlace.onChange(() -> easyTarget = null);
        matchStates.setDescription("Require facing/axis/half/slab type to match too, not just the block. Wrong ones count as wrong.").setSection("Build");
        silentAim.setDescription("Aim placements without moving the camera. Mining always uses visible aim.").setSection("Build");
        rotationSpeed.setDescription("Rotation convergence speed (1 slow - 100 instant).").setSection("Build");
        maxReach.setDescription("Maximum interaction reach in blocks (clamped to vanilla).").setSection("Build");
        onlySafe.setDescription("Never place inside the blocks you occupy.").setSection("Build");

        clearObstructions.setDescription("Mine wrong blocks in target cells and blocks that hide the face a placement needs.")
                .setSection("Reach & Obstacles");
        scaffold.setDescription("Place a temporary support block when a target has no face to click against.")
                .setSection("Reach & Obstacles");
        towerUp.setDescription("Jump-place a pillar under yourself when the next blocks are above your reach.")
                .setSection("Reach & Obstacles");
        removeScaffold.setDescription("Mine the scaffold back out once nothing else is left to build.")
                .setSection("Reach & Obstacles");
        scaffoldBlocks.setDescription("Comma-separated block ids used for scaffold, in order of preference (must be in the hotbar).")
                .setSection("Reach & Obstacles");

        autoRestock.setDescription("When the next blocks need materials you don't carry, open the nearest chest, barrel "
                + "or shulker box in range, take what the build needs and close it. Stops if you take damage.")
                .setSection("Restock");
        restockRange.setDescription("How far (blocks) to look for containers to restock from.").setSection("Restock");
        walkToBlocks.setDescription("Use Baritone (if installed) to walk to blocks out of reach and to restock containers.")
                .setSection("Pathing");
        walkToBlocks.onChange(() -> {
            if (!walkToBlocks.isEnabled()) runOnClient(this::cancelWalk);
        });

        materialsHud.setDescription("Feed the \"Schematic Materials\" HUD element: progress, ETA and what you still need.")
                .setSection("HUD");
        hudRowCount.setDescription("How many material rows the HUD lists.").setSection("HUD");
        countShulkers.setDescription("Count materials inside shulker boxes you carry in the HUD's \"have\" column.")
                .setSection("HUD");

        diagram.setDescription("Draw the material checklist and the layer diagram on screen.").setSection("Visuals");
        diagramLayer.setDescription("Which Y layer the diagram shows.").setSection("Visuals");
        diagramScale.setDescription("Diagram cell size in pixels.").setSection("Visuals");
        ghost.setDescription("Draw translucent ghost blocks in the world where the schematic builds.").setSection("Visuals");
        ghostRange.setDescription("Only draw ghost blocks within this distance of the player.").setSection("Visuals");
        ghostLayers.setDescription("Only draw this many layers above the current build layer. 0 = every layer.").setSection("Visuals");
        showCorrect.setDescription("Draw cells that already hold the right block.").setSection("Visuals");
        showMissing.setDescription("Draw cells that still need a block (drawn slightly smaller).").setSection("Visuals");
        showWrong.setDescription("Draw cells holding a different block or the wrong state (full size, outlined).").setSection("Visuals");
        ghostOutline.setDescription("Outline missing cells (wrong cells are always outlined).").setSection("Visuals");
        remainingColor.setDescription("Ghost colour for blocks still to place.").setSection("Visuals");
        placedColor.setDescription("Ghost colour for blocks already correct.").setSection("Visuals");
        wrongColor.setDescription("Ghost colour for wrong blocks.").setSection("Visuals");
        scaffoldColor.setDescription("Colour for scaffold this module placed.").setSection("Visuals");

        placeDelay.setDescription("Pause between two placements. Item use also waits for vanilla's use cooldown.").setSection("Delays");
        swapDelay.setDescription("Gap between selecting the block and using it, inside the same tick.").setSection("Delays");
        breakDelay.setDescription("Pause after a block finishes breaking before the next action.").setSection("Delays");
        swapBackDelay.setDescription("Idle time before reselecting the item you held.").setSection("Delays");
        towerDelay.setDescription("Pause between two pillar jumps.").setSection("Delays");
        restockOpenDelay.setDescription("Wait after a container opens before the first click.").setSection("Delays");
        restockClickDelay.setDescription("Pause between two clicks inside a container.").setSection("Delays");
        restockCloseDelay.setDescription("Wait after the last click before closing the container.").setSection("Delays");
        pathDelay.setDescription("Minimum time between two Baritone path requests.").setSection("Delays");

        addSetting(pathSetting);
        addSetting(browse);
        addSetting(libraryPick);
        addSetting(librarySearch);
        addSetting(nextFile);
        addSetting(prevFile);
        addSetting(refreshLibrary);
        addSetting(recentSetting);
        addSetting(originMode);
        addSetting(capture);
        addSetting(offsetX);
        addSetting(offsetY);
        addSetting(offsetZ);
        addSetting(rotationMode);
        addSetting(mirrorMode);
        addSetting(resetProgress);
        addSetting(progressSetting);
        addSetting(autoPlace);
        addSetting(buildOrder);
        addSetting(easyPlace);
        addSetting(matchStates);
        addSetting(silentAim);
        addSetting(rotationSpeed);
        addSetting(maxReach);
        addSetting(onlySafe);
        addSetting(clearObstructions);
        addSetting(scaffold);
        addSetting(towerUp);
        addSetting(removeScaffold);
        addSetting(scaffoldBlocks);
        addSetting(autoRestock);
        addSetting(restockRange);
        addSetting(walkToBlocks);
        addSetting(materialsHud);
        addSetting(hudRowCount);
        addSetting(countShulkers);
        addSetting(diagram);
        addSetting(diagramLayer);
        addSetting(diagramScale);
        addSetting(ghost);
        addSetting(ghostRange);
        addSetting(ghostLayers);
        addSetting(showCorrect);
        addSetting(showMissing);
        addSetting(showWrong);
        addSetting(ghostOutline);
        addSetting(remainingColor);
        addSetting(placedColor);
        addSetting(wrongColor);
        addSetting(scaffoldColor);
        addSetting(placeDelay);
        addSetting(swapDelay);
        addSetting(breakDelay);
        addSetting(swapBackDelay);
        addSetting(towerDelay);
        addSetting(restockOpenDelay);
        addSetting(restockClickDelay);
        addSetting(restockCloseDelay);
        addSetting(pathDelay);
    }

    @Override
    public boolean rendersInWorld() {
        return true; // keep the world-render hook alive even though this is a PLAYER module
    }

    @Override
    public void onEnable() {
        // Pick up a path typed while disabled.
        String p = pathSetting.getValue();
        if (schematic == null && p != null && !p.isBlank()) {
            Path path = toPath(p);
            if (path != null && Files.isRegularFile(path)) {
                requestLoad(path);
            }
        }
        if (library.files().isEmpty()) refreshLibraryList(false);
    }

    @Override
    public void onTick() {
        if (mc.player == null || mc.world == null) {
            return;
        }
        if (lastPlayer != mc.player || lastWorld != mc.world) {
            boolean firstSight = lastWorld == null;
            lastPlayer = mc.player;
            lastWorld = mc.world;
            if (!firstSight) onWorldChanged();
        }
        if (slotPlayer != null && (slotPlayer != mc.player || slotWorld != mc.world)) {
            releasePlacementState();
            buildOrigin = null;
            scaffoldPositions.clear();
        }
        if (originalSlot != -1 && mc.player.getInventory().getSelectedSlot() != expectedSlot) {
            originalSlot = expectedSlot = -1; // a user or another module displaced our selection
            slotPlayer = null;
            slotWorld = null;
        }
        consumePendingLoad();
        if (modelDirty && schematic != null) {
            modelDirty = false;
            releasePlacementState();
            rebuildModel();
        }
        if (pendingRestore && model != null) {
            pendingRestore = false;
            restoreProgress();
        }
        if (model != null) {
            recountStep();
        }
        if (++hudTick >= 5) {
            hudTick = 0;
            rebuildHud();
        }

        if (restock.isActive()) {
            if (!autoRestock.isEnabled() || !autoPlace.isEnabled() || model == null) {
                restock.abort(mc);
                stopActions();
            } else {
                restock.tick(mc);
                return;
            }
        }

        if (easyTarget != null && (!easyPlace.isEnabled() || now() - easyTargetAt > EASY_TTL_MS)) {
            easyTarget = null;
        }
        boolean active = autoPlace.isEnabled() || easyTarget != null;
        if (model == null || !active || mc.currentScreen != null || mc.player.isUsingItem()) {
            cancelWalk();
            if (model != null && originalSlot != -1 && mc.currentScreen == null && !mc.player.isUsingItem()) {
                idle(); // a finished Easy Place keeps the vanilla-paced swap back
            } else {
                releasePlacementState();
            }
            return;
        }
        if (buildOrigin == null) {
            BlockPos o = effectiveOrigin();
            if (o == null) {
                warnMissing("origin", "Look at a block to set the build origin.");
                return;
            }
            buildOrigin = o;
            lowDone = 0;
            if (resumeLayer >= 0) {
                lowDone = model.layerStart(resumeLayer);
                fullVerifyPending = lowDone > 0;
            }
            resumeLayer = -1;
            lastSavedLayer = -1;
            etaSamples.clear();
            saveProgress();
        }
        if (miningPos != null) {
            continueMining();
            return;
        }
        if (towering) {
            continueTower();
            return;
        }
        if (!TickActionOrder.isWindowOpen() || !TickActionOrder.canSwitchSlot()) return;
        if (easyTarget != null && runEasyPlace()) return;
        if (autoPlace.isEnabled()) {
            runBuild();
        } else {
            idle();
        }
    }

    private void onWorldChanged() {
        restock.abort(mc);
        restock.clearMemory();
        cancelWalk();
        releasePlacementState();
        buildOrigin = null;
        capturedCorner = null;
        scaffoldPositions.clear();
        easyTarget = null;
        etaSamples.clear();
        if (schematic != null) pendingRestore = true;
    }

    // ------------------------------------------------------------------
    // Build loop
    // ------------------------------------------------------------------

    private void runBuild() {
        BlockPos origin = buildOrigin;
        BuildModel m = model;
        int n = m.orderSize();

        while (lowDone < n && isSatisfied(origin, m, m.orderAt(lowDone))) {
            lowDone++;
        }
        if (lowDone >= n) {
            if (fullVerifyPending) {
                // Resumed above layer 0: check everything below once before calling it done.
                fullVerifyPending = false;
                lowDone = 0;
                return;
            }
            if (tryRemoveScaffold()) return;
            status = "Build complete (" + n + " blocks).";
            Notifier.success("AutoBuild", (schematic != null ? schematic.name() : "Schematic") + " complete.");
            forgetProgress();
            autoPlace.setValue(false);
            releasePlacementState();
            return;
        }
        int layer = BuildModel.unpackY(m.orderAt(lowDone));
        if (layer != lastSavedLayer) saveProgress();

        double reach = currentReach();
        boolean nearest = buildOrder.is("Nearest First");
        boolean smart = buildOrder.is("Smart");
        Vec3d eyes = mc.player.getEyePos();

        statOutOfReach = statNoSupport = statNoMaterial = statNotInHotbar = statBlocked = statWrong = statTrap = 0;
        nearestUnreachable = null;
        double nearestUnreachableDist = Double.MAX_VALUE;

        Candidate chosen = null;
        Candidate fallback = null; // Smart: a mine/scaffold step, used only if no plain placement is ready
        int fallbackY = Integer.MAX_VALUE;
        double bestDist = Double.MAX_VALUE;
        int end = Math.min(n, lowDone + SCAN_WINDOW);
        for (int i = lowDone; i < end; i++) {
            int packed = m.orderAt(i);
            int y = BuildModel.unpackY(packed);
            if (fallback != null && y > fallbackY) break;
            int pal = m.paletteAt(BuildModel.unpackX(packed), y, BuildModel.unpackZ(packed));
            if (pal == Schematic.EMPTY || unplaceable[pal] || ruleCache[pal].skip()) continue;
            BlockPos world = origin.add(BuildModel.unpackX(packed), y, BuildModel.unpackZ(packed));
            double dist = eyes.distanceTo(Vec3d.ofCenter(world));
            if (dist > reach + 0.9) {
                statOutOfReach++;
                if (dist < nearestUnreachableDist && !isSatisfied(origin, m, packed)) {
                    nearestUnreachableDist = dist;
                    nearestUnreachable = world;
                }
                continue;
            }
            Candidate c = candidateFor(world, pal, reach);
            if (c == null) continue;
            if (nearest) {
                if (dist < bestDist) {
                    bestDist = dist;
                    chosen = c;
                }
                continue;
            }
            if (smart && (c.kind() == Kind.MINE || c.kind() == Kind.SCAFFOLD)) {
                if (fallback == null) {
                    fallback = c;
                    fallbackY = y;
                }
                continue;
            }
            chosen = c;
            break;
        }
        if (chosen == null) chosen = fallback;

        if (chosen == null) {
            idle();
            if (autoRestock.isEnabled() && statNoMaterial > 0 && restock.begin(mc)) return;
            if (towerUp.isEnabled() && nearestUnreachable != null && startTower(reach)) return;
            if (walkToBlocks.isEnabled() && nearestUnreachable != null && walkTowards(nearestUnreachable, reach)) return;
            reportWaiting();
            return;
        }
        cancelWalk();
        swapBackAtMs = 0L;

        if (chosen.kind() == Kind.MINE) {
            startMining(chosen.pos());
            return;
        }
        aimAndPlace(chosen);
    }

    /** Keeps an owner-scoped aim on the target's face and places once the live ray reaches it. */
    private boolean aimAndPlace(Candidate chosen) {
        aimPoint = chosen.solution().hitVec();
        boolean silent = silentAim.isEnabled();
        RotationManager.aimAt(this, RotationManager.Priority.HIGH, () -> aimPoint, strength(), 0.0, silent);
        if (RotationManager.getCurrentOwner() != this) return false;
        boolean placed = executePlace(chosen, false);
        if (placed && chosen.kind() == Kind.SCAFFOLD) {
            scaffoldPositions.add(chosen.pos().toImmutable());
        }
        return placed;
    }

    /** What to do about one target cell, or null when it is done or cannot be acted on now. */
    private Candidate candidateFor(BlockPos world, int pal, double reach) {
        BlockState cur = mc.world.getBlockState(world);
        CellStatus st = status(cur, pal);
        if (st == CellStatus.CORRECT) return null;
        PlacementRules.Rule rule = ruleCache[pal];
        boolean smart = buildOrder.is("Smart");

        if (st == CellStatus.WRONG) {
            statWrong++;
            if (rule.doubleSlab() && cur.getBlock() == blockCache[pal]) {
                Candidate upgrade = doubleSlabCandidate(world, cur, pal, reach);
                if (upgrade != null) return upgrade;
            }
            if (clearObstructions.isEnabled() && canMine(world, cur, reach)) {
                return new Candidate(Kind.MINE, world, -1, null, null, world, null);
            }
            statBlocked++;
            return null;
        }

        // MISSING
        if ((onlySafe.isEnabled() || smart) && occupiesPlayer(world)) return null;
        if (smart && wouldTrap(world)) {
            statTrap++;
            return null;
        }
        if (smart && floorCache[pal] && !isSolid(world.down())) {
            // Gravity blocks fall and floor-standing ones break without the block below.
            if (gravityCache[pal] && !dependentCache[pal] && scaffold.isEnabled()) {
                Candidate sc = scaffoldCandidate(world, reach, true);
                if (sc != null) return sc;
            }
            statNoSupport++;
            return null;
        }
        Item item = itemCache[pal];
        int slot = findHotbarSlot(item);
        if (slot < 0) {
            if (countInInventory(item) > 0) statNotInHotbar++;
            else statNoMaterial++;
            return null;
        }
        for (PlacementRules.Requirement req : rule.options()) {
            PlaceSolution s = resolveFor(world, req, reach);
            if (s != null) return new Candidate(Kind.PLACE, world, slot, item, s, world, req);
        }

        boolean hasSupport = false;
        for (Direction dir : Direction.values()) {
            BlockState n = mc.world.getBlockState(world.offset(dir));
            if (!n.isAir() && !n.isReplaceable()) {
                hasSupport = true;
                break;
            }
        }
        if (!hasSupport) {
            if (scaffold.isEnabled() && !(smart && dependentCache[pal])) {
                Candidate sc = scaffoldCandidate(world, reach, false);
                if (sc != null) return sc;
            }
            statNoSupport++;
            return null;
        }
        if (clearObstructions.isEnabled()) {
            BlockPos blocker = findBlocker(world, reach);
            if (blocker != null) return new Candidate(Kind.MINE, blocker, -1, null, null, blocker, null);
        }
        statBlocked++;
        return null;
    }

    private CellStatus status(BlockState cur, int pal) {
        Block want = blockCache[pal];
        if (cur.getBlock() == want) {
            if (!matchStates.isEnabled() || targetProps[pal].isEmpty()) return CellStatus.CORRECT;
            return PlacementRules.propertiesMatch(targetProps[pal], propsOf(cur)) ? CellStatus.CORRECT : CellStatus.WRONG;
        }
        if (cur.isAir() || cur.isReplaceable()) return CellStatus.MISSING;
        return CellStatus.WRONG;
    }

    private boolean isSatisfied(BlockPos origin, BuildModel m, int packed) {
        int pal = m.paletteAt(BuildModel.unpackX(packed), BuildModel.unpackY(packed), BuildModel.unpackZ(packed));
        if (pal == Schematic.EMPTY || unplaceable[pal] || ruleCache[pal].skip()) return true;
        BlockPos pos = origin.add(BuildModel.unpackX(packed), BuildModel.unpackY(packed), BuildModel.unpackZ(packed));
        return status(mc.world.getBlockState(pos), pal) == CellStatus.CORRECT;
    }

    private boolean isSolid(BlockPos pos) {
        BlockState st = mc.world.getBlockState(pos);
        return !st.isAir() && !st.isReplaceable();
    }

    private boolean blocksMovement(BlockPos pos) {
        return !mc.world.getBlockState(pos).getCollisionShape(mc.world, pos).isEmpty();
    }

    /** Placing at {@code cell} would leave the player with a roof and no free horizontal exit. */
    private boolean wouldTrap(BlockPos cell) {
        BlockPos feet = mc.player.getBlockPos();
        BlockPos head = feet.up();
        BlockPos roofPos = head.up();
        boolean relevant = cell.equals(roofPos);
        for (Direction d : Direction.Type.HORIZONTAL) {
            if (cell.equals(feet.offset(d)) || cell.equals(head.offset(d))) {
                relevant = true;
                break;
            }
        }
        if (!relevant) return false;
        if (!cell.equals(roofPos) && !blocksMovement(roofPos)) return false;
        for (Direction d : Direction.Type.HORIZONTAL) {
            BlockPos f = feet.offset(d);
            BlockPos h = head.offset(d);
            if (!f.equals(cell) && !h.equals(cell) && !blocksMovement(f) && !blocksMovement(h)) return false;
        }
        return true;
    }

    /**
     * Finds a support face whose click yields the required state: allowed hit side, hit height and a
     * player orientation that the aim to that point actually produces (with slack from the edges).
     */
    private PlaceSolution resolveFor(BlockPos target, PlacementRules.Requirement req, double reach) {
        if (!mc.world.getBlockState(target).isReplaceable()) return null;
        Vec3d eye = mc.player.getEyePos();
        float curYaw = mc.player.getYaw();
        float curPitch = mc.player.getPitch();
        double effectiveReach = Math.min(reach, 4.25);
        PlaceSolution best = null;
        double bestTurn = Double.MAX_VALUE;
        for (Direction dir : Direction.values()) {
            BlockPos against = target.offset(dir);
            BlockState againstState = mc.world.getBlockState(against);
            if (againstState.isAir() || againstState.isReplaceable()) continue;
            Direction side = dir.getOpposite();
            if (!req.allowsSide(side.asString())) continue;
            double[] heights = side.getAxis().isHorizontal()
                    ? switch (req.half()) {
                        case UPPER -> new double[]{0.25, 0.4};
                        case LOWER -> new double[]{-0.25, -0.4};
                        default -> new double[]{0.0, 0.25, -0.25};
                    }
                    : new double[]{0.0};
            for (double dy : heights) {
                Vec3d hit = Vec3d.ofCenter(against).add(side.getOffsetX() * 0.46, side.getOffsetY() * 0.46 + dy,
                        side.getOffsetZ() * 0.46);
                if (eye.squaredDistanceTo(hit) > effectiveReach * effectiveReach) continue;
                if (!PlaceResolver.isVisible(mc, against, hit)) continue;
                float[] rot = RotationManager.calculateRotationsToPos(hit, curYaw);
                if (!PlacementRules.orientationOk(req, rot[0], rot[1], FACING_MARGIN_DEG)) continue;
                double turn = Math.abs(MathHelper.wrapDegrees(rot[0] - curYaw)) + Math.abs(rot[1] - curPitch);
                if (turn < bestTurn) {
                    bestTurn = turn;
                    best = new PlaceSolution(against, side, hit);
                }
            }
        }
        return best;
    }

    private Candidate doubleSlabCandidate(BlockPos pos, BlockState cur, int pal, double reach) {
        Map<String, String> props = propsOf(cur);
        String type = props.get("type");
        if (type == null || type.equals("double")) return null;
        Item item = itemCache[pal];
        int slot = findHotbarSlot(item);
        if (slot < 0) {
            statNoMaterial++;
            return null;
        }
        Direction side = type.equals("bottom") ? Direction.UP : Direction.DOWN;
        double y = type.equals("bottom") ? 0.48 : 0.52;
        Vec3d hit = new Vec3d(pos.getX() + 0.5, pos.getY() + y, pos.getZ() + 0.5);
        if (mc.player.getEyePos().squaredDistanceTo(hit) > Math.min(reach, 4.25) * Math.min(reach, 4.25)) return null;
        if (!PlaceResolver.isVisible(mc, pos, hit)) return null;
        BlockHitResult intended = new BlockHitResult(hit, side, pos, false);
        BlockPos expect = BlockPlacementValidator.placementCell(mc, intended);
        return new Candidate(Kind.DOUBLE_SLAB, pos, slot, item, new PlaceSolution(pos, side, hit),
                expect == null ? pos : expect, PlacementRules.Requirement.ANY);
    }

    /** A temporary block next to {@code target} (below first) that itself has a face to click. */
    private Candidate scaffoldCandidate(BlockPos target, double reach, boolean belowOnly) {
        int slot = scaffoldSlot();
        if (slot < 0) {
            warnMissing("scaffold", "No scaffold block in the hotbar (see Scaffold Blocks).");
            return null;
        }
        Item item = mc.player.getInventory().getStack(slot).getItem();
        Direction[] order = belowOnly ? new Direction[]{Direction.DOWN}
                : new Direction[]{Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP};
        for (Direction d : order) {
            BlockPos n = target.offset(d);
            if (!mc.world.getBlockState(n).isReplaceable()) continue;
            if (wantsSolid(n)) continue; // would be a schematic cell; it gets its own block
            if ((onlySafe.isEnabled() || buildOrder.is("Smart")) && occupiesPlayer(n)) continue;
            if (buildOrder.is("Smart") && wouldTrap(n)) continue;
            PlaceSolution s = resolveFor(n, PlacementRules.Requirement.ANY, reach);
            if (s != null) return new Candidate(Kind.SCAFFOLD, n, slot, item, s, n, PlacementRules.Requirement.ANY);
        }
        return null;
    }

    /** The first block on the eye-to-face ray that is not the intended support, if it may be mined. */
    private BlockPos findBlocker(BlockPos target, double reach) {
        Vec3d eye = mc.player.getEyePos();
        for (Direction dir : Direction.values()) {
            BlockPos against = target.offset(dir);
            BlockState st = mc.world.getBlockState(against);
            if (st.isAir() || st.isReplaceable()) continue;
            Direction side = dir.getOpposite();
            Vec3d hit = Vec3d.ofCenter(against).add(side.getOffsetX() * 0.46, side.getOffsetY() * 0.46, side.getOffsetZ() * 0.46);
            if (eye.squaredDistanceTo(hit) > reach * reach) continue;
            BlockHitResult r = mc.world.raycast(new RaycastContext(eye, hit, RaycastContext.ShapeType.COLLIDER,
                    RaycastContext.FluidHandling.NONE, mc.player));
            if (r.getType() != HitResult.Type.BLOCK || r.getBlockPos().equals(against)) continue;
            BlockPos blocker = r.getBlockPos();
            if (isCorrectSchematicBlock(blocker)) continue;
            BlockState bs = mc.world.getBlockState(blocker);
            if (canMine(blocker, bs, reach)) return blocker.toImmutable();
        }
        return null;
    }

    private boolean canMine(BlockPos pos, BlockState st, double reach) {
        if (st.isAir() || st.getBlock() instanceof FluidBlock) return false;
        if (st.getHardness(mc.world, pos) < 0) return false;
        if (pos.equals(mc.player.getBlockPos().down())) return false; // never dig out our own footing
        return mc.player.getEyePos().squaredDistanceTo(Vec3d.ofCenter(pos)) <= reach * reach;
    }

    // ------------------------------------------------------------------
    // Easy Place
    // ------------------------------------------------------------------

    /**
     * Vanilla right-click hook (client thread, {@code doItemUse} HEAD). Turns a right-click on a
     * missing ghost cell into an Easy Place target, and refuses a vanilla placement of a different
     * block into a schematic cell. Our own clicks pass through untouched.
     */
    @Override
    public boolean shouldBlockItemUse() {
        if (selfUse || !easyPlace.isEnabled() || model == null || mc.player == null || mc.world == null
                || mc.currentScreen != null || restock.isActive() || mc.player.isUsingItem()) return false;
        // A ghost that follows the crosshair (unlatched Looked-at Block origin) has no fixed cells to fill.
        if (buildOrigin == null && originMode.is("Looked-at Block")) return false;
        ItemStack held = mc.player.getMainHandStack();
        if (!held.isEmpty() && !(held.getItem() instanceof BlockItem)) return false;
        BlockPos origin = currentOrigin();
        if (origin == null) return false;
        BlockPos cell = ghostCellOnRay(origin);
        if (cell != null) {
            easyTarget = cell.toImmutable();
            easyTargetAt = now();
            return true;
        }
        // No ghost on the ray: still keep a wrong block out of a schematic cell.
        if (held.getItem() instanceof BlockItem blockItem
                && mc.crosshairTarget instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
            BlockPos into = BlockPlacementValidator.placementCell(mc, hit);
            int pal = into == null ? Schematic.EMPTY : paletteAtWorld(into);
            if (pal != Schematic.EMPTY && !isAirPalette(pal) && !unplaceable[pal]
                    && blockItem.getBlock() != blockCache[pal]) {
                warnMissing("easy-wrong", "Easy Place: that cell needs " + shortName(StateString.base(model.state(pal))) + ".");
                return true;
            }
        }
        return false;
    }

    /** First missing schematic cell the look ray passes through before it hits the world. */
    private BlockPos ghostCellOnRay(BlockPos origin) {
        Vec3d eye = mc.player.getEyePos();
        Vec3d dir = mc.player.getRotationVec(1.0f);
        double limit = currentReach();
        if (mc.crosshairTarget instanceof BlockHitResult b && b.getType() == HitResult.Type.BLOCK) {
            limit = Math.min(limit, eye.distanceTo(b.getPos()) + 1.0e-3);
        }
        int x = MathHelper.floor(eye.x);
        int y = MathHelper.floor(eye.y);
        int z = MathHelper.floor(eye.z);
        int stepX = dir.x > 0 ? 1 : dir.x < 0 ? -1 : 0;
        int stepY = dir.y > 0 ? 1 : dir.y < 0 ? -1 : 0;
        int stepZ = dir.z > 0 ? 1 : dir.z < 0 ? -1 : 0;
        double tDeltaX = stepX == 0 ? Double.MAX_VALUE : Math.abs(1.0 / dir.x);
        double tDeltaY = stepY == 0 ? Double.MAX_VALUE : Math.abs(1.0 / dir.y);
        double tDeltaZ = stepZ == 0 ? Double.MAX_VALUE : Math.abs(1.0 / dir.z);
        double tMaxX = stepX == 0 ? Double.MAX_VALUE : (stepX > 0 ? (x + 1 - eye.x) : (eye.x - x)) * tDeltaX;
        double tMaxY = stepY == 0 ? Double.MAX_VALUE : (stepY > 0 ? (y + 1 - eye.y) : (eye.y - y)) * tDeltaY;
        double tMaxZ = stepZ == 0 ? Double.MAX_VALUE : (stepZ > 0 ? (z + 1 - eye.z) : (eye.z - z)) * tDeltaZ;
        double t = 0.0;
        BlockPos.Mutable cursor = new BlockPos.Mutable();
        for (int guard = 0; guard < 64 && t <= limit; guard++) {
            int pal = model.paletteAt(x - origin.getX(), y - origin.getY(), z - origin.getZ());
            if (pal != Schematic.EMPTY && !unplaceable[pal] && !ruleCache[pal].skip()) {
                cursor.set(x, y, z);
                if (!occupiesPlayer(cursor) && status(mc.world.getBlockState(cursor), pal) == CellStatus.MISSING) {
                    return cursor.toImmutable();
                }
            }
            if (tMaxX < tMaxY && tMaxX < tMaxZ) {
                x += stepX;
                t = tMaxX;
                tMaxX += tDeltaX;
            } else if (tMaxY < tMaxZ) {
                y += stepY;
                t = tMaxY;
                tMaxY += tDeltaY;
            } else {
                z += stepZ;
                t = tMaxZ;
                tMaxZ += tDeltaZ;
            }
        }
        return null;
    }

    /** One Easy Place step. True when it consumed the tick. */
    private boolean runEasyPlace() {
        BlockPos target = easyTarget;
        int pal = paletteAtWorld(target);
        if (pal == Schematic.EMPTY || unplaceable[pal] || ruleCache[pal].skip()
                || status(mc.world.getBlockState(target), pal) != CellStatus.MISSING) {
            easyTarget = null;
            return false;
        }
        statOutOfReach = statNoSupport = statNoMaterial = statNotInHotbar = statBlocked = statWrong = statTrap = 0;
        Candidate c = candidateFor(target, pal, currentReach());
        if (c == null || (c.kind() != Kind.PLACE && c.kind() != Kind.DOUBLE_SLAB)) {
            if (c == null && statNoMaterial + statNotInHotbar > 0) {
                warnMissing("easy-material", "Easy Place: put " + shortName(StateString.base(model.state(pal)))
                        + " in your hotbar.");
                easyTarget = null;
            }
            return false;
        }
        swapBackAtMs = 0L;
        if (aimAndPlace(c)) easyTarget = null;
        return true;
    }

    private void runAsSelfUse(Runnable click) {
        boolean previous = selfUse;
        selfUse = true;
        try {
            click.run();
        } finally {
            selfUse = previous;
        }
    }

    // ------------------------------------------------------------------
    // Placement execution
    // ------------------------------------------------------------------

    /** Selects the item, waits the swap gap inside this tick, then uses it on the live face. */
    private boolean executePlace(Candidate chosen, boolean ignorePlaceDelay) {
        if (!ignorePlaceDelay && now() < nextPlaceAllowedMs) return false;
        if (!InteractSim.useReady(mc)) return false;
        boolean silent = silentAim.isEnabled();
        BlockHitResult intended = new BlockHitResult(chosen.solution().hitVec(), chosen.solution().side(),
                chosen.solution().against(), false);

        // Gate strictly on the live authoritative ray reaching the exact support face.
        BlockHitResult live = BlockPlacementValidator.validateHit(mc, intended, silent);
        if (live == null) return false;
        BlockPos cell = BlockPlacementValidator.placementCell(mc, live);
        if (cell == null || !cell.equals(chosen.expectCell())) return false;
        if (!requirementMet(chosen, live)) return false;

        double delay = swapDelay.sample();
        if (ExecutionMode.isDirect()) delay = Math.min(3.0, delay);
        if (SubTickSequencer.remainingTickBudgetMs() <= delay + 1.0) return false;
        long tick = SubTickSequencer.currentTick();
        var player = mc.player;
        var world = mc.world;
        int held = player.getInventory().getSelectedSlot();
        if (!SlotSync.select(mc, chosen.slot())) return false;
        if (held != chosen.slot() && originalSlot == -1) {
            originalSlot = held;
            slotPlayer = player;
            slotWorld = world;
        }
        expectedSlot = chosen.slot();
        long deadline = System.nanoTime() + (long) (delay * 1_000_000L);
        if (!SubTickSequencer.awaitActionDeadline(mc, deadline)
                || tick != SubTickSequencer.currentTick() || mc.player != player || mc.world != world) {
            stopActions();
            return false;
        }
        if (!isEnabled() || !(autoPlace.isEnabled() || easyTarget != null) || mc.currentScreen != null
                || !TickActionOrder.isWindowOpen() || !TickActionOrder.canSwitchSlot()
                || player.getInventory().getSelectedSlot() != chosen.slot()
                || !player.getMainHandStack().isOf(chosen.item())
                || ((onlySafe.isEnabled() || buildOrder.is("Smart")) && occupiesPlayer(chosen.expectCell()))) return false;
        if (chosen.kind() != Kind.DOUBLE_SLAB) {
            BlockState current = world.getBlockState(chosen.pos());
            if (!current.isAir() && !current.isReplaceable()) return false;
        }
        BlockHitResult finalHit = BlockPlacementValidator.validateHit(mc, intended, silent);
        if (finalHit == null || !chosen.expectCell().equals(BlockPlacementValidator.placementCell(mc, finalHit))
                || !requirementMet(chosen, finalHit)) return false;
        HitResult clickTarget = RotationManager.isSilentRotationActive()
                ? RotationManager.getSilentCrosshairTarget() : mc.crosshairTarget;
        if (!(clickTarget instanceof BlockHitResult clickHit)
                || !clickHit.getBlockPos().equals(finalHit.getBlockPos())
                || clickHit.getSide() != finalHit.getSide()
                || !PlaceValidator.canPlace(mc) || !InteractSim.useReady(mc)) return false;
        runAsSelfUse(() -> ClickSimController.useNow(mc));
        RotationManager.setPostAttackHold(this, 1, RotationManager.getFinalYaw(), RotationManager.getFinalPitch());
        placedCount++; // attempted vanilla uses; world state alone decides completion
        nextPlaceAllowedMs = now() + (long) placeDelay.sample();
        return true;
    }

    /** The click the server will see produces the wanted state: hit height and wire orientation. */
    private boolean requirementMet(Candidate c, BlockHitResult hit) {
        PlacementRules.Requirement req = c.requirement();
        if (req == null || req == PlacementRules.Requirement.ANY) return true;
        String side = hit.getSide().asString();
        if (!req.allowsSide(side)) return false;
        double frac = hit.getPos().y - c.expectCell().getY();
        if (!PlacementRules.halfOk(req.half(), side, frac)) return false;
        float yaw = RotationManager.isSilentRotationActive() ? RotationManager.getFinalYaw() : mc.player.getYaw();
        float pitch = RotationManager.isSilentRotationActive() ? RotationManager.getFinalPitch() : mc.player.getPitch();
        return PlacementRules.orientationOk(req, yaw, pitch, 2.0);
    }

    // ------------------------------------------------------------------
    // Mining (vanilla held attack on the camera crosshair)
    // ------------------------------------------------------------------

    private void startMining(BlockPos pos) {
        if (now() < nextBreakAllowedMs) return;
        double reach = currentReach();
        BlockHitResult face = PlaceResolver.resolveExistingBlockHit(mc, pos, reach);
        if (face == null) return;
        BlockState st = mc.world.getBlockState(pos);
        int tool = bestToolSlot(st);
        int held = mc.player.getInventory().getSelectedSlot();
        if (tool >= 0 && tool != held) {
            if (!SlotSync.select(mc, tool)) return;
            if (originalSlot == -1) {
                originalSlot = held;
                slotPlayer = mc.player;
                slotWorld = mc.world;
            }
            expectedSlot = tool;
        }
        miningPos = pos.toImmutable();
        miningScaffold = scaffoldPositions.contains(miningPos);
        miningSince = now();
        aimPoint = face.getPos();
        RotationManager.aimAt(this, RotationManager.Priority.HIGH, () -> aimPoint, strength(), 0.0, false);
    }

    private void continueMining() {
        BlockState st = mc.world.getBlockState(miningPos);
        if (st.isAir() || (st.isReplaceable() && !st.isOf(Blocks.SNOW))) {
            finishMining();
            return;
        }
        double reach = currentReach();
        if (now() - miningSince > MINE_TIMEOUT_MS || !canMine(miningPos, st, reach)) {
            finishMining();
            return;
        }
        RotationManager.aimAt(this, RotationManager.Priority.HIGH, () -> aimPoint, strength(), 0.0, false);
        boolean onBlock = RotationManager.getCurrentOwner() == this
                && mc.crosshairTarget instanceof BlockHitResult b && b.getType() == HitResult.Type.BLOCK
                && b.getBlockPos().equals(miningPos);
        if (onBlock && !attackHeld) {
            SystemInputSimulator.pressAttack();
            attackHeld = true;
        } else if (!onBlock && attackHeld) {
            SystemInputSimulator.releaseAttack();
            attackHeld = false;
        }
    }

    private void finishMining() {
        if (attackHeld) {
            SystemInputSimulator.releaseAttack();
            attackHeld = false;
        }
        if (miningPos != null && miningScaffold && mc.world != null && mc.world.getBlockState(miningPos).isAir()) {
            scaffoldPositions.remove(miningPos);
        }
        miningPos = null;
        miningScaffold = false;
        nextBreakAllowedMs = now() + (long) breakDelay.sample();
        RotationManager.stop(this);
    }

    private boolean tryRemoveScaffold() {
        if (!removeScaffold.isEnabled() || scaffoldPositions.isEmpty()) return false;
        double reach = currentReach();
        var it = scaffoldPositions.iterator();
        while (it.hasNext()) {
            BlockPos p = it.next();
            BlockState st = mc.world.getBlockState(p);
            if (st.isAir() || wantsSolid(p)) {
                it.remove();
                continue;
            }
            if (canMine(p, st, reach)) {
                startMining(p);
                return true;
            }
        }
        if (!scaffoldPositions.isEmpty()) {
            warnMissing("scaffold-left", scaffoldPositions.size() + " scaffold block(s) left out of reach.");
        }
        return false;
    }

    private int bestToolSlot(BlockState st) {
        int best = -1;
        float bestSpeed = 1.0f;
        for (int i = 0; i < 9; i++) {
            ItemStack s = mc.player.getInventory().getStack(i);
            if (s.isEmpty()) continue;
            float speed = s.getMiningSpeedMultiplier(st);
            if (speed > bestSpeed) {
                bestSpeed = speed;
                best = i;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------
    // Tower up (jump and place under the feet)
    // ------------------------------------------------------------------

    private boolean startTower(double reach) {
        if (now() < nextTowerAllowedMs) return true;
        BlockPos target = nearestUnreachable;
        Vec3d eye = mc.player.getEyePos();
        if (target.getY() + 0.5 <= eye.y) return false;
        double hx = target.getX() + 0.5 - mc.player.getX();
        double hz = target.getZ() + 0.5 - mc.player.getZ();
        if (Math.sqrt(hx * hx + hz * hz) > reach - 0.5) return false;
        if (!mc.player.isOnGround()) return false;
        BlockPos feet = mc.player.getBlockPos();
        BlockPos ground = feet.down();
        BlockState gs = mc.world.getBlockState(ground);
        if (gs.isAir() || gs.isReplaceable()) return false;
        if (!mc.world.getBlockState(feet.up(2)).getCollisionShape(mc.world, feet.up(2)).isEmpty()) {
            warnMissing("tower-ceiling", "Something above you blocks the pillar.");
            return false;
        }
        if (towerItemSlot(feet) < 0) return false;
        towerGround = ground.toImmutable();
        towering = true;
        towerStartMs = now();
        aimPoint = Vec3d.ofCenter(towerGround).add(0, 0.46, 0);
        RotationManager.aimAt(this, RotationManager.Priority.HIGH, () -> aimPoint, strength(), 0.0, silentAim.isEnabled());
        SystemInputSimulator.pressJump();
        jumpHeld = true;
        return true;
    }

    private void continueTower() {
        BlockPos cell = towerGround.up();
        RotationManager.aimAt(this, RotationManager.Priority.HIGH, () -> aimPoint, strength(), 0.0, silentAim.isEnabled());
        long elapsed = now() - towerStartMs;
        if (elapsed > TOWER_TIMEOUT_MS || (mc.player.isOnGround() && elapsed > 200L && mc.player.getY() < cell.getY() + 0.5)) {
            endTower();
            return;
        }
        if (mc.player.getY() < cell.getY() + 1.02) return; // feet not above the cell yet
        if (!TickActionOrder.isWindowOpen() || !TickActionOrder.canSwitchSlot()) return;
        int slot = towerItemSlot(cell);
        if (slot < 0) {
            endTower();
            return;
        }
        Item item = mc.player.getInventory().getStack(slot).getItem();
        boolean isScaffold = !wantsSolid(cell);
        Candidate c = new Candidate(isScaffold ? Kind.SCAFFOLD : Kind.PLACE, cell, slot, item,
                new PlaceSolution(towerGround, Direction.UP, aimPoint), cell, PlacementRules.Requirement.ANY);
        if (RotationManager.getCurrentOwner() == this && executePlace(c, true)) {
            if (isScaffold) scaffoldPositions.add(cell.toImmutable());
            endTower();
        }
    }

    private void endTower() {
        if (jumpHeld) {
            SystemInputSimulator.releaseJump();
            jumpHeld = false;
        }
        towering = false;
        towerGround = null;
        nextTowerAllowedMs = now() + (long) towerDelay.sample();
    }

    /** The schematic's own block for that cell when it needs no orientation, else scaffold. */
    private int towerItemSlot(BlockPos cell) {
        int pal = paletteAtWorld(cell);
        if (pal != Schematic.EMPTY && !isAirPalette(pal)) {
            if (unplaceable[pal] || ruleCache[pal].skip()) return -1;
            List<PlacementRules.Requirement> opts = ruleCache[pal].options();
            if (opts.size() != 1 || opts.get(0) != PlacementRules.Requirement.ANY) {
                warnMissing("tower-cell", "Can't pillar through a block that needs a facing; stand elsewhere.");
                return -1;
            }
            return findHotbarSlot(itemCache[pal]);
        }
        return scaffoldSlot();
    }

    // ------------------------------------------------------------------
    // Walking (Baritone, optional)
    // ------------------------------------------------------------------

    /** Asks Baritone to walk near {@code target}. True when a walk is (still) under way. */
    private boolean walkTowards(BlockPos target, double reach) {
        if (!BaritoneBridge.isAvailable()) {
            warnMissing("baritone", "Walk To Blocks needs Baritone installed; walk closer yourself.");
            return false;
        }
        long now = now();
        boolean ours = BaritoneBridge.isOwnedBy(this);
        if (ours && target.equals(walkGoal)) return true;
        if (now < nextPathMs) return ours;
        int range = Math.max(1, (int) Math.floor(reach) - 1);
        if (BaritoneBridge.goNear(this, target, range)) {
            walkGoal = target.toImmutable();
            nextPathMs = now + ModuleDelays.clampMs(pathDelay.sample());
            return true;
        }
        return false;
    }

    private void cancelWalk() {
        if (walkGoal != null) {
            walkGoal = null;
            BaritoneBridge.cancel(this);
        }
    }

    // ------------------------------------------------------------------
    // Idle / reporting
    // ------------------------------------------------------------------

    private void idle() {
        stopActions();
        if (originalSlot == -1) return;
        if (swapBackAtMs == 0L) {
            swapBackAtMs = now() + (long) swapBackDelay.sample();
        } else if (now() >= swapBackAtMs) {
            restoreSlot();
        }
    }

    private void reportWaiting() {
        if (statNotInHotbar > 0) {
            warnMissing("hotbar", "Next block(s) are in your inventory but not the hotbar.");
        } else if (statNoMaterial > 0) {
            warnMissing("materials", "Out of materials for the next block(s)"
                    + (autoRestock.isEnabled() ? "." : " (Auto Restock can fetch them from a nearby chest)."));
        } else if (statTrap > 0) {
            warnMissing("trap", "The next block would wall you in; step out of the build.");
        } else if (statWrong > 0 && !clearObstructions.isEnabled()) {
            warnMissing("wrong", statWrong + " wrong block(s) in the way; enable Clear Obstructions.");
        } else if (statNoSupport > 0 && !scaffold.isEnabled()) {
            warnMissing("support", statNoSupport + " block(s) have nothing to place against; enable Scaffold.");
        } else if (nearestUnreachable != null) {
            warnMissing("reach", "Move closer: next block at " + nearestUnreachable.toShortString()
                    + (towerUp.isEnabled() ? "" : " (Tower Up can pillar to high layers)")
                    + (walkToBlocks.isEnabled() ? "" : " (Walk To Blocks can walk there with Baritone)") + ".");
        }
    }

    // ------------------------------------------------------------------
    // Loading / library / model
    // ------------------------------------------------------------------

    private void requestLoad(Path path) {
        long generation = ++loadGeneration;
        pendingLoad.set(null);
        loading = true;
        status = "Loading " + path.getFileName() + "...";
        Thread worker = new Thread(() -> {
            long size = -1;
            try {
                size = Files.size(path);
            } catch (Throwable ignored) {
                // keyed without a size
            }
            try {
                Schematic s = SchematicIo.load(path);
                publishLoad(new LoadResult(generation, s, null, path, size));
            } catch (SchematicException e) {
                publishLoad(new LoadResult(generation, null, e.getMessage(), path, size));
            } catch (Throwable e) {
                publishLoad(new LoadResult(generation, null, "Failed to load: " + e, path, size));
            }
        }, "Apexx-AutoBuild-Loader");
        worker.setDaemon(true);
        worker.start();
    }

    /** Hands a finished load to the client thread and applies it there, even while the module is off. */
    private void publishLoad(LoadResult result) {
        mc.execute(() -> {
            if (result.generation() != loadGeneration) return;
            pendingLoad.set(result);
            consumePendingLoad();
        });
    }

    private void consumePendingLoad() {
        LoadResult result = pendingLoad.getAndSet(null);
        if (result == null || result.generation() != loadGeneration) {
            return;
        }
        loading = false;
        if (result.schematic() != null) {
            applyLoaded(result.schematic(), result.path(), result.size());
        } else {
            status = result.error();
            Notifier.error("AutoBuild", status);
        }
    }

    private void applyLoaded(Schematic s, Path path, long size) {
        releasePlacementState();
        cancelWalk();
        restock.abort(mc);
        this.schematic = s;
        this.schematicPath = path == null ? null : path.toAbsolutePath().normalize();
        this.schematicSize = size;
        this.placedCount = 0;
        this.materials = s.materialCounts();
        this.remainingMaterials = Map.of();
        this.buildOrigin = null;
        this.lowDone = 0;
        this.resumeLayer = -1;
        this.fullVerifyPending = false;
        this.easyTarget = null;
        this.scaffoldPositions.clear();
        rebuildModel();
        pendingRestore = true; // resume where this schematic was being built, once a world is there
        status = s.name() + "  " + s.sizeX() + "x" + s.sizeY() + "x" + s.sizeZ()
                + "  (" + s.totalSolidBlocks() + " blocks)";
        Notifier.success("AutoBuild", "Loaded " + status);
        if (schematicPath != null) {
            addRecents(List.of(schematicPath));
            if (!library.files().contains(schematicPath)) refreshLibraryList(false);
            else rebuildLibraryLabels();
        }
    }

    @SuppressWarnings("unchecked")
    private void rebuildModel() {
        Schematic s = schematic;
        if (s == null) return;
        SchematicTransform.Rotation rot = SchematicTransform.Rotation.fromLabel(rotationMode.getCurrentMode());
        SchematicTransform.Mirror mir = SchematicTransform.Mirror.fromLabel(mirrorMode.getCurrentMode());
        // Rules depend on the transformed state; compute them from a transformed palette first.
        SchematicTransform t = new SchematicTransform(s.sizeX(), s.sizeZ(), rot, mir);
        int n = s.paletteSize();
        PlacementRules.Rule[] rules = new PlacementRules.Rule[n];
        String[] states = new String[n];
        for (int i = 0; i < n; i++) {
            states[i] = s.paletteIsAir(i) ? s.paletteState(i) : t.transformState(s.paletteState(i));
            rules[i] = s.paletteIsAir(i) ? new PlacementRules.Rule(List.of(), true, false, false)
                    : PlacementRules.forState(states[i]);
        }
        BuildModel m = buildOrder.is("Smart")
                ? BuildModel.of(s, rot, mir, i -> rules[i].skip(),
                        i -> SmartOrder.passOf(states[i], rules[i].attachment()), SmartOrder.PASSES)
                : BuildModel.of(s, rot, mir, i -> rules[i].skip(), i -> rules[i].attachment());
        ruleCache = rules;
        blockCache = new Block[n];
        itemCache = new Item[n];
        colorCache = new int[n];
        targetProps = new Map[n];
        unplaceable = new boolean[n];
        gravityCache = new boolean[n];
        floorCache = new boolean[n];
        dependentCache = new boolean[n];
        for (int i = 0; i < n; i++) {
            targetProps[i] = Map.of();
            if (s.paletteIsAir(i)) {
                blockCache[i] = Blocks.AIR;
                itemCache[i] = Items.AIR;
                unplaceable[i] = true;
                continue;
            }
            String state = m.state(i);
            Identifier id = Identifier.tryParse(StateString.base(state));
            Block block = id == null ? Blocks.AIR : Registries.BLOCK.get(id);
            blockCache[i] = block;
            itemCache[i] = block.asItem();
            unplaceable[i] = block == Blocks.AIR || itemCache[i] == Items.AIR;
            colorCache[i] = diagramColour(block, StateString.base(state));
            String path = StateString.path(state);
            gravityCache[i] = SmartOrder.isGravity(path);
            floorCache[i] = SmartOrder.needsFloor(path) && !path.equals("lily_pad"); // lily pads sit on water
            dependentCache[i] = SmartOrder.isDependent(path) || rules[i].attachment();
            Map<String, String> all = StateString.props(state);
            Map<String, String> relevant = new HashMap<>();
            for (String key : PlacementRules.MATCHED_PROPERTIES) {
                if (all.containsKey(key)) relevant.put(key, all.get(key));
            }
            targetProps[i] = relevant;
        }
        model = m;
        lowDone = 0;
        recountCursor = 0;
        recountAcc.clear();
        recountTotalAcc = recountMissingAcc = recountWrongAcc = 0;
        cellsTotal = -1;
        etaSamples.clear();
    }

    private boolean isAirPalette(int pal) {
        return blockCache[pal] == Blocks.AIR;
    }

    private void refreshLibraryList(boolean announce) {
        Path runDir = mc.runDirectory == null ? null : mc.runDirectory.toPath();
        announceLibrary |= announce;
        library.refresh(SchematicLibrary.defaultFolders(runDir), recents(), mc, this::onLibraryUpdated);
    }

    /** Client thread: the library listing or its metadata changed. */
    private void onLibraryUpdated() {
        List<Path> files = library.files();
        if (announceLibrary) {
            announceLibrary = false;
            Notifier.info("AutoBuild", files.isEmpty()
                    ? "No schematics in .minecraft/schematics or config/schematics (drop files onto the ClickGUI)."
                    : files.size() + " schematic(s) in the library.");
        }
        rebuildLibraryLabels();
        if (pendingLibraryStep != 0) {
            int step = pendingLibraryStep;
            pendingLibraryStep = 0;
            if (!files.isEmpty()) stepLibrary(step);
        }
    }

    private void rebuildLibraryLabels() {
        String search = librarySearch.getValue() == null ? "" : librarySearch.getValue().trim().toLowerCase(Locale.ROOT);
        List<String> labels = new ArrayList<>();
        labels.add(LIBRARY_PLACEHOLDER);
        libraryLabels.clear();
        String selected = LIBRARY_PLACEHOLDER;
        for (Path p : library.files()) {
            if (labels.size() > LIBRARY_MAX) break;
            String fileName = p.getFileName() == null ? "" : p.getFileName().toString().toLowerCase(Locale.ROOT);
            if (!search.isEmpty() && !fileName.contains(search)) continue;
            String label = library.labelFor(p);
            String unique = label;
            for (int k = 2; libraryLabels.containsKey(unique); k++) unique = label + " (" + k + ")";
            labels.add(unique);
            libraryLabels.put(unique, p);
            if (p.equals(schematicPath)) selected = unique;
        }
        if (labels.size() == 1) {
            labels.set(0, library.files().isEmpty() ? LIBRARY_EMPTY : "(no match)");
            selected = labels.get(0);
        }
        libraryPick.setModes(labels, selected);
    }

    private void stepLibrary(int step) {
        List<Path> files = library.files();
        if (files.isEmpty()) {
            pendingLibraryStep = step;
            refreshLibraryList(false);
            return;
        }
        int current = schematicPath == null ? -1 : files.indexOf(schematicPath);
        libraryCursor = Math.floorMod((current >= 0 ? current : libraryCursor) + step, files.size());
        Path next = files.get(libraryCursor);
        if (next.toString().equals(pathSetting.getValue())) requestLoad(next);
        else pathSetting.setValue(next.toString());
    }

    private List<Path> recents() {
        List<Path> out = new ArrayList<>();
        String raw = recentSetting.getValue();
        if (raw == null || raw.isBlank()) return out;
        for (String line : raw.split("\n")) {
            Path p = toPath(line);
            if (p != null && !line.isBlank() && !out.contains(p)) out.add(p);
        }
        return out;
    }

    private void addRecents(List<Path> added) {
        List<Path> list = recents();
        for (int i = added.size() - 1; i >= 0; i--) {
            Path p = added.get(i).toAbsolutePath().normalize();
            list.remove(p);
            list.add(0, p);
        }
        while (list.size() > RECENT_MAX) list.remove(list.size() - 1);
        StringBuilder sb = new StringBuilder();
        for (Path p : list) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(p);
        }
        recentSetting.setValue(sb.toString());
    }

    /** Loads the first schematic and adds all of them to the library. Client thread. */
    private boolean acceptFiles(List<Path> paths, String how) {
        List<Path> accepted = new ArrayList<>();
        if (paths != null) {
            for (Path p : paths) {
                if (p == null) continue;
                try {
                    Path abs = p.toAbsolutePath().normalize();
                    if (Files.isRegularFile(abs) && SchematicLibrary.isSchematic(abs) && !accepted.contains(abs)) {
                        accepted.add(abs);
                    }
                } catch (RuntimeException ignored) {
                    // unusable path
                }
            }
        }
        if (accepted.isEmpty()) return false;
        addRecents(accepted);
        Path first = accepted.get(0);
        if (first.toString().equals(pathSetting.getValue())) requestLoad(first);
        else pathSetting.setValue(first.toString());
        if (accepted.size() > 1) {
            Notifier.info("AutoBuild", how + ": loading " + first.getFileName() + "; "
                    + (accepted.size() - 1) + " more added to the library.");
        }
        refreshLibraryList(false);
        return true;
    }

    /** Files dropped onto the ClickGUI (client thread); works while the module is off. */
    @Override
    public boolean onFilesDropped(List<Path> paths) {
        boolean ok = acceptFiles(paths, "Dropped");
        if (!ok && paths != null && !paths.isEmpty()) {
            Notifier.warn("AutoBuild", "Only .litematic, .schem, .schematic and .nbt files can be loaded.");
        }
        return ok;
    }

    private int diagramColour(Block block, String base) {
        int rgb = 0;
        try {
            BlockState state = block.getDefaultState();
            if (mc.world != null) {
                MapColor col = state.getMapColor(mc.world, BlockPos.ORIGIN);
                if (col != null) {
                    rgb = col.color;
                }
            }
        } catch (Throwable ignored) {
            // fall back to a hashed colour
        }
        if (rgb == 0) {
            int h = base.hashCode();
            rgb = ((h & 0x7F) + 64) << 16 | (((h >> 7) & 0x7F) + 64) << 8 | (((h >> 14) & 0x7F) + 64);
        }
        return 0xFF000000 | rgb;
    }

    /** Rolling pass over the build order that tallies what is still missing or wrong. */
    private void recountStep() {
        BlockPos origin = currentOrigin();
        if (origin == null || mc.world == null) return;
        BuildModel m = model;
        int n = m.orderSize();
        int end = Math.min(n, recountCursor + RECOUNT_BUDGET);
        for (int i = recountCursor; i < end; i++) {
            int packed = m.orderAt(i);
            int pal = m.paletteAt(BuildModel.unpackX(packed), BuildModel.unpackY(packed), BuildModel.unpackZ(packed));
            if (pal == Schematic.EMPTY || unplaceable[pal] || ruleCache[pal].skip()) continue;
            recountTotalAcc++;
            BlockPos pos = origin.add(BuildModel.unpackX(packed), BuildModel.unpackY(packed), BuildModel.unpackZ(packed));
            CellStatus st = status(mc.world.getBlockState(pos), pal);
            if (st != CellStatus.CORRECT) {
                recountAcc.merge(StateString.base(m.state(pal)), 1, Integer::sum);
                if (st == CellStatus.MISSING) recountMissingAcc++;
                else recountWrongAcc++;
            }
        }
        recountCursor = end;
        if (recountCursor >= n) {
            remainingMaterials = new LinkedHashMap<>(recountAcc);
            cellsTotal = recountTotalAcc;
            cellsMissing = recountMissingAcc;
            cellsWrong = recountWrongAcc;
            recountAcc.clear();
            recountTotalAcc = recountMissingAcc = recountWrongAcc = 0;
            recountCursor = 0;
            if (buildOrigin != null) { // an unlatched origin moves with the player: no meaningful rate
                long now = now();
                etaSamples.addLast(new long[]{now, cellsTotal - cellsMissing - cellsWrong});
                while (etaSamples.size() > 2 && now - etaSamples.peekFirst()[0] > ETA_WINDOW_MS) etaSamples.removeFirst();
            } else {
                etaSamples.clear();
            }
        }
    }

    // ------------------------------------------------------------------
    // Build progress (resume)
    // ------------------------------------------------------------------

    private String progressKey() {
        if (schematicPath == null || mc.world == null) return null;
        String world = "unknown";
        try {
            ServerInfo entry = mc.getCurrentServerEntry();
            if (entry != null && entry.address != null) {
                world = entry.address.toLowerCase(Locale.ROOT);
            } else if (mc.getServer() != null) {
                world = "sp:" + mc.getServer().getSaveProperties().getLevelName();
            }
        } catch (Throwable ignored) {
            // keep "unknown"
        }
        String dimension;
        try {
            dimension = mc.world.getRegistryKey().getValue().toString();
        } catch (Throwable t) {
            dimension = "";
        }
        String fileName = schematicPath.getFileName() == null ? schematicPath.toString() : schematicPath.getFileName().toString();
        return BuildProgressStore.key(fileName, schematicSize, world, dimension);
    }

    private void saveProgress() {
        String key = progressKey();
        if (key == null || buildOrigin == null || model == null) return;
        int layer = lowDone < model.orderSize() ? BuildModel.unpackY(model.orderAt(lowDone)) : model.sizeY();
        lastSavedLayer = layer;
        progressStore.load(progressSetting.getValue());
        progressStore.put(key, new BuildProgressStore.Progress(buildOrigin.getX(), buildOrigin.getY(), buildOrigin.getZ(),
                rotationMode.getCurrentMode(), mirrorMode.getCurrentMode(),
                (int) offsetX.getValue(), (int) offsetY.getValue(), (int) offsetZ.getValue(), layer));
        progressSetting.setValue(progressStore.toJson());
    }

    private void forgetProgress() {
        String key = progressKey();
        progressStore.load(progressSetting.getValue());
        if (progressStore.remove(key)) progressSetting.setValue(progressStore.toJson());
    }

    private void restoreProgress() {
        String key = progressKey();
        if (key == null) {
            pendingRestore = mc.world == null; // retry once a world exists
            return;
        }
        progressStore.load(progressSetting.getValue());
        BuildProgressStore.Progress p = progressStore.get(key);
        if (p == null) return;
        releasePlacementState();
        rotationMode.setCurrentMode(p.rotation());
        mirrorMode.setCurrentMode(p.mirror());
        offsetX.setValue(p.offsetX());
        offsetY.setValue(p.offsetY());
        offsetZ.setValue(p.offsetZ());
        capturedCorner = new BlockPos(p.x() - p.offsetX(), p.y() - p.offsetY(), p.z() - p.offsetZ());
        originMode.setCurrentMode("Captured Corner");
        buildOrigin = null;
        lowDone = 0;
        resumeLayer = p.layer();
        Notifier.info("AutoBuild", "Resuming " + (schematic != null ? schematic.name() : "build") + " at "
                + new BlockPos(p.x(), p.y(), p.z()).toShortString() + " (layer " + p.layer()
                + "). Reset Progress starts over.");
    }

    private void resetBuildProgress() {
        forgetProgress();
        releasePlacementState();
        buildOrigin = null;
        lowDone = 0;
        resumeLayer = -1;
        fullVerifyPending = false;
        lastSavedLayer = -1;
        etaSamples.clear();
        Notifier.info("AutoBuild", schematicPath == null ? "Build progress cleared."
                : "Progress for " + schematicPath.getFileName() + " cleared.");
    }

    // ------------------------------------------------------------------
    // Materials HUD
    // ------------------------------------------------------------------

    @Override
    public List<Row> schematicHudRows() {
        return hudRows;
    }

    private void rebuildHud() {
        if (!materialsHud.isEnabled() || model == null || mc.player == null) {
            hudRows = List.of();
            return;
        }
        List<Row> rows = new ArrayList<>();
        String name = schematic != null ? schematic.name() : "schematic";
        if (cellsTotal > 0) {
            int correct = cellsTotal - cellsMissing - cellsWrong;
            double pct = 100.0 * correct / cellsTotal;
            rows.add(new Row(name + "  " + String.format(Locale.ROOT, "%.1f%%", pct), 0xFFE6EEF5));
            rows.add(new Row(correct + " / " + cellsTotal + " placed   " + cellsMissing + " missing   "
                    + cellsWrong + " wrong", 0xFFB8C4D0));
            String eta = etaText(cellsMissing + cellsWrong);
            if (eta != null) rows.add(new Row("ETA " + eta, 0xFFB8C4D0));
        } else {
            rows.add(new Row(name + "  (counting...)", 0xFFE6EEF5));
        }
        String restockLine = restock.describe();
        if (restockLine != null) rows.add(new Row(restockLine, 0xFFF0C850));
        Map<Item, Integer> have = inventoryCounts(countShulkers.isEnabled());
        Map<String, Integer> need = remainingMaterials.isEmpty() && cellsTotal < 0 ? materials : remainingMaterials;
        int limit = (int) hudRowCount.getValue();
        int shown = 0;
        List<Map.Entry<String, Integer>> sorted = sortedByCount(need);
        for (Map.Entry<String, Integer> e : sorted) {
            if (shown >= limit) {
                rows.add(new Row("+" + (sorted.size() - shown) + " more", 0xFF8A96A3));
                break;
            }
            int count = have.getOrDefault(itemFor(e.getKey()), 0);
            boolean enough = count >= e.getValue();
            rows.add(new Row((enough ? "  " : "! ") + shortName(e.getKey()) + "  " + count + " / " + e.getValue(),
                    enough ? 0xFF7FE0A0 : 0xFFF07070));
            shown++;
        }
        hudRows = List.copyOf(rows);
    }

    private String etaText(int remaining) {
        if (remaining <= 0 || etaSamples.size() < 2) return null;
        long[] first = etaSamples.peekFirst();
        long[] last = etaSamples.peekLast();
        long dt = last[0] - first[0];
        long done = last[1] - first[1];
        if (dt < 10_000L || done <= 0) return null;
        long ms = (long) (remaining * (double) dt / done);
        long s = ms / 1000;
        return s >= 3600 ? String.format(Locale.ROOT, "%dh %02dm", s / 3600, (s / 60) % 60)
                : String.format(Locale.ROOT, "%dm %02ds", s / 60, s % 60);
    }

    /** Item counts across the whole inventory (offhand and armor included), optionally inside shulker boxes. */
    private Map<Item, Integer> inventoryCounts(boolean shulkers) {
        Map<Item, Integer> out = new HashMap<>();
        if (mc.player == null) return out;
        var inv = mc.player.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (stack.isEmpty()) continue;
            out.merge(stack.getItem(), stack.getCount(), Integer::sum);
            if (shulkers && stack.getItem() instanceof BlockItem bi && bi.getBlock() instanceof ShulkerBoxBlock) {
                ContainerComponent contents = stack.get(DataComponentTypes.CONTAINER);
                if (contents == null) continue;
                for (ItemStack inner : contents.iterateNonEmpty()) {
                    out.merge(inner.getItem(), inner.getCount(), Integer::sum);
                }
            }
        }
        return out;
    }

    /** Items the remaining build needs beyond what the player carries (shulker contents not counted). */
    private Map<Item, Integer> materialShortfall() {
        Map<Item, Integer> out = new HashMap<>();
        if (mc.player == null) return out;
        Map<String, Integer> need = remainingMaterials.isEmpty() && cellsTotal < 0 ? materials : remainingMaterials;
        Map<Item, Integer> have = inventoryCounts(false);
        for (Map.Entry<String, Integer> e : need.entrySet()) {
            Item item = itemFor(e.getKey());
            if (item == Items.AIR) continue;
            int missing = e.getValue() - have.getOrDefault(item, 0);
            if (missing > 0) out.merge(item, missing, Integer::sum);
        }
        return out;
    }

    private boolean isBuildMaterial(Item item) {
        if (!(item instanceof BlockItem bi)) return false;
        Map<String, Integer> need = remainingMaterials.isEmpty() && cellsTotal < 0 ? materials : remainingMaterials;
        return need.containsKey(Registries.BLOCK.getId(bi.getBlock()).toString());
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static Map<String, String> propsOf(BlockState st) {
        Map<String, String> out = new HashMap<>();
        for (Property<?> p : st.getProperties()) {
            out.put(p.getName(), valueName(st, p));
        }
        return out;
    }

    private static <T extends Comparable<T>> String valueName(BlockState st, Property<T> p) {
        return p.name(st.get(p));
    }

    private double currentReach() {
        double vanilla = mc.player == null ? 4.5 : mc.player.getBlockInteractionRange();
        return Math.min(maxReach.getValue(), vanilla);
    }

    private double strength() {
        return RotationManager.engineStrength(rotationSpeed.getValue(), RotationManager.STRENGTH_SCALE_TRACK);
    }

    private void runOnClient(Runnable task) {
        try {
            if (mc.isOnThread()) task.run();
            else mc.execute(task);
        } catch (Throwable ignored) {
            // client shutting down
        }
    }

    private int paletteAtWorld(BlockPos pos) {
        BlockPos origin = buildOrigin != null ? buildOrigin : currentOrigin();
        if (origin == null || model == null || pos == null) return Schematic.EMPTY;
        return model.paletteAt(pos.getX() - origin.getX(), pos.getY() - origin.getY(), pos.getZ() - origin.getZ());
    }

    private boolean wantsSolid(BlockPos pos) {
        int pal = paletteAtWorld(pos);
        return pal != Schematic.EMPTY && !isAirPalette(pal);
    }

    private boolean isCorrectSchematicBlock(BlockPos pos) {
        int pal = paletteAtWorld(pos);
        if (pal == Schematic.EMPTY || isAirPalette(pal)) return false;
        return status(mc.world.getBlockState(pos), pal) == CellStatus.CORRECT;
    }

    private boolean occupiesPlayer(BlockPos pos) {
        return mc.player.getBoundingBox().intersects(new Box(pos));
    }

    private int findHotbarSlot(Item item) {
        if (item == null || item == Items.AIR) {
            return -1;
        }
        int held = mc.player.getInventory().getSelectedSlot();
        if (mc.player.getInventory().getStack(held).isOf(item)) {
            return held;
        }
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).isOf(item)) {
                return i;
            }
        }
        return -1;
    }

    private int scaffoldSlot() {
        String raw = scaffoldBlocks.getValue();
        if (raw == null) return -1;
        for (String part : raw.split(",")) {
            Identifier id = Identifier.tryParse(part.trim());
            if (id == null) continue;
            Item item = Registries.ITEM.get(id);
            if (item == Items.AIR || !(item instanceof BlockItem)) continue;
            int slot = findHotbarSlot(item);
            if (slot >= 0) return slot;
        }
        return -1;
    }

    private int countInInventory(Item item) {
        if (mc.player == null || item == null || item == Items.AIR) {
            return 0;
        }
        int count = 0;
        var inv = mc.player.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (stack.isOf(item)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private int countInHotbar(Item item) {
        if (mc.player == null || item == null || item == Items.AIR) return 0;
        int count = 0;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.isOf(item)) count += stack.getCount();
        }
        return count;
    }

    // ------------------------------------------------------------------
    // File picker / origin
    // ------------------------------------------------------------------

    private void openFilePicker() {
        SchematicFilePicker.open(mc, defaultDir(), picked -> acceptFiles(picked, "Picked"), reason -> {
            status = reason;
            Notifier.info("AutoBuild", reason);
        });
    }

    private Path defaultDir() {
        try {
            if (mc.runDirectory != null) {
                Path schematics = mc.runDirectory.toPath().resolve("schematics");
                if (Files.isDirectory(schematics)) return schematics;
            }
            Path home = Path.of(System.getProperty("user.home", "."));
            Path downloads = home.resolve("Downloads");
            return Files.isDirectory(downloads) ? downloads : home;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void captureCorner() {
        if (mc.player != null && mc.crosshairTarget instanceof BlockHitResult bhr && bhr.getType() == HitResult.Type.BLOCK) {
            releasePlacementState();
            capturedCorner = bhr.getBlockPos().offset(bhr.getSide());
            buildOrigin = null;
            lowDone = 0;
            resumeLayer = -1;
            fullVerifyPending = false;
            originMode.setCurrentMode("Captured Corner");
            Notifier.info("AutoBuild", "Captured corner " + capturedCorner.toShortString());
        } else {
            warnMissing("capture", "Look at a block to capture a corner.");
        }
    }

    private BlockPos effectiveOrigin() {
        if (mc.player == null) {
            return null;
        }
        BlockPos base;
        if (originMode.is("Captured Corner")) {
            base = capturedCorner;
        } else if (originMode.is("Looked-at Block")) {
            if (mc.crosshairTarget instanceof BlockHitResult bhr && bhr.getType() == HitResult.Type.BLOCK) {
                base = bhr.getBlockPos().offset(bhr.getSide());
            } else {
                base = null;
            }
        } else {
            base = BlockPos.ofFloored(mc.player.getX(), mc.player.getY(), mc.player.getZ());
        }
        if (base == null) return null;
        return base.add((int) offsetX.getValue(), (int) offsetY.getValue(), (int) offsetZ.getValue());
    }

    private BlockPos currentOrigin() {
        return buildOrigin != null ? buildOrigin : effectiveOrigin();
    }

    // ------------------------------------------------------------------
    // Ghost world preview
    // ------------------------------------------------------------------

    @Override
    public void onWorldRender(MatrixStack matrices, float tickDelta) {
        if (!ghost.isEnabled() || model == null || mc.player == null || mc.world == null) {
            return;
        }
        BlockPos origin = currentOrigin();
        if (origin == null) {
            return;
        }
        BuildModel m = model;
        double range = ghostRange.getValue();
        double rangeSq = range * range;
        Vec3d camera = mc.gameRenderer.getCamera().getCameraPos();
        Vec3d player = new Vec3d(mc.player.getX(), mc.player.getY(), mc.player.getZ());
        Color remaining = remainingColor.getValue();
        Color placed = placedColor.getValue();
        Color wrong = wrongColor.getValue();
        Color scaff = scaffoldColor.getValue();
        Box unit = new Box(0, 0, 0, 1, 1, 1);
        Box inset = new Box(0.12, 0.12, 0.12, 0.88, 0.88, 0.88);
        int start = 0;
        int end = m.orderSize();
        int layersAbove = (int) ghostLayers.getValue();
        if (layersAbove > 0) {
            int buildLayer = currentBuildLayer();
            start = m.layerStart(buildLayer);
            end = m.layerStart(buildLayer + layersAbove + 1);
        }

        try (BufferAllocator allocator = interstellarTikiTiks.apexx.client.util.render.PooledBufferAllocator.acquire(2097152)) {
            VertexConsumerProvider.Immediate immediate = VertexConsumerProvider.immediate(allocator);
            int drawn = 0;
            for (int i = start; i < end && drawn < GHOST_CELL_CAP; i++) {
                int packed = m.orderAt(i);
                int x = BuildModel.unpackX(packed);
                int y = BuildModel.unpackY(packed);
                int z = BuildModel.unpackZ(packed);
                int pal = m.paletteAt(x, y, z);
                if (pal == Schematic.EMPTY || unplaceable[pal]) {
                    continue;
                }
                BlockPos world = origin.add(x, y, z);
                double dx = world.getX() + 0.5 - player.x;
                double dy = world.getY() + 0.5 - player.y;
                double dz = world.getZ() + 0.5 - player.z;
                if (dx * dx + dy * dy + dz * dz > rangeSq) {
                    continue;
                }
                CellStatus st = status(mc.world.getBlockState(world), pal);
                switch (st) {
                    case CORRECT -> {
                        if (!showCorrect.isEnabled()) continue;
                        drawCube(matrices, immediate, unit, world, camera, placed, false);
                    }
                    case MISSING -> {
                        if (!showMissing.isEnabled()) continue;
                        drawCube(matrices, immediate, inset, world, camera, remaining, ghostOutline.isEnabled());
                    }
                    default -> {
                        if (!showWrong.isEnabled()) continue;
                        drawCube(matrices, immediate, unit, world, camera, wrong, true);
                    }
                }
                drawn++;
            }
            for (BlockPos p : scaffoldPositions) {
                if (drawn++ >= GHOST_CELL_CAP) break;
                drawCube(matrices, immediate, unit, p, camera, scaff, true);
            }
            immediate.draw();
        } catch (Throwable ignored) {
            // never let a render fault disable the module's logic
        }
    }

    private static void drawCube(MatrixStack matrices, VertexConsumerProvider.Immediate immediate, Box box,
                                 BlockPos world, Vec3d camera, Color color, boolean outline) {
        matrices.push();
        matrices.translate(world.getX() - camera.x, world.getY() - camera.y, world.getZ() - camera.z);
        RenderUtils.drawFilledBox(matrices, immediate, box, color, color.getAlpha() / 255f);
        if (outline) {
            RenderUtils.drawBoxOutlineEsp(matrices, immediate, box, color, 0.8f, false);
        }
        matrices.pop();
    }

    // ------------------------------------------------------------------
    // On-screen diagram + material checklist
    // ------------------------------------------------------------------

    @Override
    public void onRender(DrawContext context, float tickDelta) {
        if (!diagram.isEnabled() || model == null || mc.textRenderer == null) {
            return;
        }
        BuildModel m = model;
        int cell = (int) diagramScale.getValue();
        int sizeX = m.sizeX();
        int sizeZ = m.sizeZ();
        int maxGrid = 320;
        if (sizeX * cell > maxGrid) {
            cell = Math.max(1, maxGrid / Math.max(1, sizeX));
        }
        if (sizeZ * cell > maxGrid) {
            cell = Math.max(1, maxGrid / Math.max(1, sizeZ));
        }

        int x0 = 6;
        int y0 = 6;
        int pad = 6;
        int gridW = sizeX * cell;
        int layer = Math.max(0, Math.min(m.sizeY() - 1, (int) diagramLayer.getValue()));
        int buildLayer = currentBuildLayer();

        List<String> lines = headerLines(layer, buildLayer);
        int textW = 0;
        for (String line : lines) {
            textW = Math.max(textW, mc.textRenderer.getWidth(line));
        }
        int panelW = Math.max(textW, gridW) + pad * 2;
        int gridH = sizeZ * cell;
        int panelH = pad + lines.size() * 10 + pad + gridH + pad;

        context.fill(x0, y0, x0 + panelW, y0 + panelH, 0xB0101216);
        context.fill(x0, y0, x0 + panelW, y0 + 1, 0xFF3A6EA5);

        int ty = y0 + pad;
        for (String line : lines) {
            context.drawTextWithShadow(mc.textRenderer, line, x0 + pad, ty, 0xFFE6EEF5);
            ty += 10;
        }

        int gx = x0 + pad;
        int gy = ty + pad;
        context.fill(gx - 1, gy - 1, gx + gridW + 1, gy + gridH + 1,
                layer == buildLayer ? 0xFF4CD08A : 0x55000000);
        for (int z = 0; z < sizeZ; z++) {
            for (int xx = 0; xx < sizeX; xx++) {
                int p = m.paletteAt(xx, layer, z);
                if (p == Schematic.EMPTY || isAirPalette(p)) {
                    continue;
                }
                int argb = colorCache != null && p < colorCache.length ? colorCache[p] : 0xFF808080;
                int cx = gx + xx * cell;
                int cz = gy + z * cell;
                context.fill(cx, cz, cx + cell, cz + cell, argb);
            }
        }
    }

    private int currentBuildLayer() {
        BlockPos origin = currentOrigin();
        if (origin == null || model == null || mc.world == null) {
            return 0;
        }
        BuildModel m = model;
        for (int i = Math.max(0, Math.min(lowDone, m.orderSize())); i < m.orderSize(); i++) {
            int packed = m.orderAt(i);
            if (!isSatisfied(origin, m, packed)) return BuildModel.unpackY(packed);
        }
        return Math.max(0, m.sizeY() - 1);
    }

    private List<String> headerLines(int layer, int buildLayer) {
        List<String> lines = new ArrayList<>();
        BuildModel m = model;
        String name = schematic != null ? schematic.name() : "schematic";
        List<Path> files = library.files();
        int libIndex = schematicPath == null ? -1 : files.indexOf(schematicPath);
        String lib = libIndex < 0 ? "" : "  [" + (libIndex + 1) + "/" + files.size() + "]";
        lines.add("AutoBuild: " + name + "  " + m.sizeX() + "x" + m.sizeY() + "x" + m.sizeZ() + lib);
        lines.add("Rotation " + rotationMode.getCurrentMode() + "°, mirror " + mirrorMode.getCurrentMode()
                + "   layer " + layer + " (building " + buildLayer + ")   order " + buildOrder.getCurrentMode());
        String progress = cellsTotal > 0
                ? "   correct " + (cellsTotal - cellsMissing - cellsWrong) + " / " + cellsTotal : "";
        lines.add("Uses requested: " + placedCount + progress
                + (scaffoldPositions.isEmpty() ? "" : "   scaffold: " + scaffoldPositions.size()));
        if (loading) {
            lines.add("Loading...");
        }
        String restockLine = restock.describe();
        if (restockLine != null) lines.add(restockLine);
        if (autoPlace.isEnabled()) {
            String waiting = waitingSummary();
            if (!waiting.isEmpty()) lines.add(waiting);
            if (miningPos != null) lines.add("Mining " + miningPos.toShortString());
            if (towering) lines.add("Towering...");
            if (walkGoal != null) lines.add("Walking to " + walkGoal.toShortString());
        }
        lines.add("Materials (hotbar / inventory / still needed):");
        Map<String, Integer> need = remainingMaterials.isEmpty() && cellsTotal < 0 ? materials : remainingMaterials;
        int shown = 0;
        for (Map.Entry<String, Integer> e : sortedByCount(need)) {
            if (shown >= 10) {
                lines.add("  +" + (need.size() - shown) + " more types");
                break;
            }
            Item item = itemFor(e.getKey());
            int hotbar = countInHotbar(item);
            int inv = countInInventory(item);
            String mark = inv >= e.getValue() ? "  " : "! ";
            lines.add(mark + shortName(e.getKey()) + "  " + hotbar + " / " + inv + " / " + e.getValue());
            shown++;
        }
        return lines;
    }

    private String waitingSummary() {
        StringBuilder sb = new StringBuilder();
        append(sb, statOutOfReach, "out of reach");
        append(sb, statNoSupport, "no support");
        append(sb, statNoMaterial, "no material");
        append(sb, statNotInHotbar, "not in hotbar");
        append(sb, statWrong, "wrong");
        append(sb, statBlocked, "blocked");
        append(sb, statTrap, "would trap you");
        return sb.length() == 0 ? "" : "Waiting: " + sb;
    }

    private static void append(StringBuilder sb, int count, String label) {
        if (count <= 0) return;
        if (sb.length() > 0) sb.append(", ");
        sb.append(count).append(' ').append(label);
    }

    private static List<Map.Entry<String, Integer>> sortedByCount(Map<String, Integer> map) {
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(map.entrySet());
        entries.sort((a, b) -> {
            int cmp = Integer.compare(b.getValue(), a.getValue());
            return cmp != 0 ? cmp : a.getKey().compareTo(b.getKey());
        });
        return entries;
    }

    private static Item itemFor(String base) {
        Identifier id = Identifier.tryParse(base);
        if (id == null) return Items.AIR;
        return Registries.BLOCK.get(id).asItem();
    }

    private static String shortName(String base) {
        int colon = base.indexOf(':');
        return colon >= 0 ? base.substring(colon + 1) : base;
    }

    // ------------------------------------------------------------------
    // Cleanup
    // ------------------------------------------------------------------

    @Override
    protected void onRuntimeStateReleased() {
        restock.abort(mc);       // closes only a container it opened; never touches rotation
        walkGoal = null;
        BaritoneBridge.cancel(this); // only a path this module started
        easyTarget = null;
        selfUse = false;
        hudRows = List.of();
        releasePlacementState(); // the single owner-scoped rotation release
        buildOrigin = null;
        capturedCorner = null;
    }

    /** Stops rotation, held keys and queued work without touching the hotbar. */
    private void stopActions() {
        SubTickSequencer.cancelOwner(this);
        RotationManager.stop(this);
        if (attackHeld) {
            SystemInputSimulator.releaseAttack();
            attackHeld = false;
        }
        if (jumpHeld) {
            SystemInputSimulator.releaseJump();
            jumpHeld = false;
        }
        miningPos = null;
        miningScaffold = false;
        towering = false;
        towerGround = null;
        aimPoint = null;
    }

    private void restoreSlot() {
        if (originalSlot != -1 && mc.player != null && mc.player == slotPlayer && mc.world == slotWorld
                && mc.player.getInventory().getSelectedSlot() == expectedSlot) {
            SlotSync.restore(mc, originalSlot);
        }
        SlotSync.releaseOwner(this);
        originalSlot = expectedSlot = -1;
        slotPlayer = null;
        slotWorld = null;
        swapBackAtMs = 0L;
    }

    private void releasePlacementState() {
        stopActions();
        restoreSlot();
        nextPlaceAllowedMs = 0L;
        nextBreakAllowedMs = 0L;
    }

    private static long now() {
        return System.currentTimeMillis();
    }

    private static Path toPath(String raw) {
        try {
            return raw == null || raw.isBlank() ? null : Path.of(raw.trim()).toAbsolutePath().normalize();
        } catch (Throwable e) {
            return null;
        }
    }
}
