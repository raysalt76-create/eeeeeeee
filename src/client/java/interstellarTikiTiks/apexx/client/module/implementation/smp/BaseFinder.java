package interstellarTikiTiks.apexx.client.module.implementation.smp;

import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.ColorSetting;
import interstellarTikiTiks.apexx.client.module.ModeSetting;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.Setting;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import interstellarTikiTiks.apexx.client.module.implementation.smp.BaseFinderCore.Candidate;
import interstellarTikiTiks.apexx.client.module.implementation.smp.BaseFinderCore.Filter;
import interstellarTikiTiks.apexx.client.module.implementation.smp.BaseFinderCore.Kind;
import interstellarTikiTiks.apexx.client.util.render.RenderUtils;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.BufferAllocator;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.WorldChunk;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

/**
 * Base Finder (SMP) - highlights storage (chests, trapped chests, ender chests, shulker boxes,
 * droppers) and spawners (spawners, trial spawners) around you, ported from Donut Client's finder.
 *
 * <p>It reads only block entities the server has already sent in loaded chunks and never asks for a
 * chunk; the scan reruns every {@value BaseFinderCore#SCAN_INTERVAL_TICKS} ticks, nearest first, capped at
 * {@value BaseFinderCore#MAX_TARGETS} blocks.</p>
 */
public final class BaseFinder extends Module {

    private static final String OUTLINE = "Outline";
    private static final String FILL = "Fill";
    private static final String BOTH = "Both";

    /** The rendering options one group (storage or spawners) carries. */
    private static final class Look {
        final BooleanSetting enabled;
        final SliderSetting range;
        final ModeSetting mode;
        final BooleanSetting throughWalls;
        final BooleanSetting distanceFade;
        final SliderSetting outlineOpacity;
        final SliderSetting fillOpacity;
        final SliderSetting lineWidth;

        Look(String prefix, String section, String about) {
            enabled = new BooleanSetting(prefix, true).setDescription(about).setSection(section);
            range = new SliderSetting(prefix + " Range", 96, 16, 256, 0)
                    .setDescription("Farthest a block is highlighted, in blocks.").setSection(section);
            mode = new ModeSetting(prefix + " Mode", BOTH, OUTLINE, FILL)
                    .setDescription("How each block is highlighted: Both = outline plus translucent fill, Outline = edges only, Fill = filled box only.")
                    .setSection(section);
            throughWalls = new BooleanSetting(prefix + " Through Walls", true)
                    .setDescription("Draws the highlight on top of terrain so hidden blocks show through walls. Off: only visible faces are highlighted.").setSection(section);
            distanceFade = new BooleanSetting(prefix + " Distance Fade", true)
                    .setDescription("Fades highlights out over the far half of the range so nearby finds stand out.").setSection(section);
            outlineOpacity = new SliderSetting(prefix + " Outline Opacity", 0.9, 0.0, 1.0, 2)
                    .setDescription("How solid the outline edges are (0 = invisible, 1 = fully opaque).").setSection(section);
            fillOpacity = new SliderSetting(prefix + " Fill Opacity", 0.16, 0.0, 1.0, 2)
                    .setDescription("How solid the filled box is (0 = invisible, 1 = fully opaque).").setSection(section);
            lineWidth = new SliderSetting(prefix + " Line Width", 1.5, 0.5, 5.0, 1)
                    .setDescription("Thickness of the outline edges.").setSection(section);
        }
    }

    private record Hit(BlockPos pos, Kind kind) {
    }

    private final Look storage = new Look("Storage", "Storage",
            "Highlights chests, ender chests, shulker boxes and droppers.");
    private final BooleanSetting chests = new BooleanSetting("Chests", true)
            .setDescription("Highlights normal chests (single and double).").setSection("Storage");
    private final BooleanSetting trappedChests = new BooleanSetting("Trapped Chests", true)
            .setDescription("Highlights trapped chests.").setSection("Storage");
    private final BooleanSetting enderChests = new BooleanSetting("Ender Chests", true)
            .setDescription("Highlights ender chests, a common sign of a player base.").setSection("Storage");
    private final BooleanSetting shulkers = new BooleanSetting("Shulkers", true)
            .setDescription("Highlights placed shulker boxes of every colour.").setSection("Storage");
    private final BooleanSetting droppers = new BooleanSetting("Droppers", true)
            .setDescription("Highlights droppers, often part of player-built farms and sorters.").setSection("Storage");
    private final BooleanSetting perTypeColors = new BooleanSetting("Per-Type Colors", true)
            .setDescription("Colours chests, shulkers and droppers separately; off uses Storage Color for all.")
            .setSection("Storage");
    private final ColorSetting storageColor = new ColorSetting("Storage Color", new Color(0x4C86FF)).setSection("Storage");
    private final ColorSetting chestColor = new ColorSetting("Chest Color", new Color(0xFFC66B)).setSection("Storage");
    private final ColorSetting shulkerColor = new ColorSetting("Shulker Color", new Color(0xBA8CFF)).setSection("Storage");
    private final ColorSetting dropperColor = new ColorSetting("Dropper Color", new Color(0x6DDAC5)).setSection("Storage");

    private final Look spawners = new Look("Spawners", "Spawners", "Highlights monster spawners.");
    private final BooleanSetting trialSpawners = new BooleanSetting("Trial Spawners", true)
            .setDescription("Also highlights trial spawners from trial chambers.").setSection("Spawners");
    private final ColorSetting spawnerColor = new ColorSetting("Spawner Color", new Color(0xFF779C)).setSection("Spawners");

    private List<Hit> targets = List.of();
    private Object lastWorld;
    private int ticks;

    public BaseFinder() {
        super("BaseFinder", "Finds player bases and dungeons by highlighting storage (chests, ender chests, shulkers, droppers) and spawners in the chunks the server has already sent you, through walls if you like. Rescans every 10 ticks, nearest first, with separate range, style and colours for storage and spawners. Never requests extra chunks; visual only.", Category.SMP);
        addLook(storage);
        for (BooleanSetting s : List.of(chests, trappedChests, enderChests, shulkers, droppers, perTypeColors)) {
            addSetting(s);
            s.setVisibility(storage.enabled::isEnabled);
        }
        addSetting(storageColor);
        storageColor.setVisibility(() -> storage.enabled.isEnabled() && !perTypeColors.isEnabled());
        for (ColorSetting c : List.of(chestColor, shulkerColor, dropperColor)) {
            addSetting(c);
            c.setVisibility(() -> storage.enabled.isEnabled() && perTypeColors.isEnabled());
        }
        addLook(spawners);
        addSetting(trialSpawners);
        trialSpawners.setVisibility(spawners.enabled::isEnabled);
        addSetting(spawnerColor);
        spawnerColor.setVisibility(spawners.enabled::isEnabled);
    }

    private void addLook(Look look) {
        addSetting(look.enabled);
        for (Setting s : List.<Setting>of(look.range, look.mode, look.throughWalls, look.distanceFade)) {
            addSetting(s);
            s.setVisibility(look.enabled::isEnabled);
        }
        addSetting(look.outlineOpacity);
        addSetting(look.fillOpacity);
        addSetting(look.lineWidth);
        look.outlineOpacity.setVisibility(() -> look.enabled.isEnabled() && !look.mode.is(FILL));
        look.lineWidth.setVisibility(() -> look.enabled.isEnabled() && !look.mode.is(FILL));
        look.fillOpacity.setVisibility(() -> look.enabled.isEnabled() && !look.mode.is(OUTLINE));
    }

    @Override
    public boolean rendersInWorld() {
        return true;
    }

    @Override
    public void onEnable() {
        clear();
    }

    @Override
    public void onDisable() {
        clear();
    }

    private void clear() {
        targets = List.of();
        lastWorld = null;
        ticks = 0;
    }

    private Filter storageFilter() {
        return new Filter(storage.enabled.isEnabled(), storage.range.getValue(), chests.isEnabled(),
                trappedChests.isEnabled(), enderChests.isEnabled(), shulkers.isEnabled(), droppers.isEnabled(), false);
    }

    private Filter spawnerFilter() {
        return new Filter(spawners.enabled.isEnabled(), spawners.range.getValue(), false, false, false, false, false,
                trialSpawners.isEnabled());
    }

    @Override
    public void onTick() {
        if (mc.world == null || mc.player == null) {
            clear();
            return;
        }
        if (mc.world != lastWorld) {
            clear();
            lastWorld = mc.world;
        }
        Filter storageFilter = storageFilter();
        Filter spawnerFilter = spawnerFilter();
        double range = BaseFinderCore.scanRange(storageFilter, spawnerFilter);
        if (range <= 0.0) {
            targets = List.of();
            ticks = 0;
            return;
        }
        if (ticks++ % BaseFinderCore.SCAN_INTERVAL_TICKS != 0) return;

        int radius = (int) Math.ceil(range / 16.0);
        int centerX = mc.player.getChunkPos().x;
        int centerZ = mc.player.getChunkPos().z;
        List<Candidate> candidates = new ArrayList<>();
        for (int x = centerX - radius; x <= centerX + radius; x++) {
            for (int z = centerZ - radius; z <= centerZ + radius; z++) {
                Chunk chunk = mc.world.getChunk(x, z, ChunkStatus.FULL, false);
                if (!(chunk instanceof WorldChunk loaded)) continue;
                for (BlockEntity entity : loaded.getBlockEntities().values()) {
                    if (entity.isRemoved()) continue;
                    Kind kind = kindOf(entity.getCachedState());
                    if (kind == null) continue;
                    BlockPos pos = entity.getPos();
                    candidates.add(new Candidate(pos.getX(), pos.getY(), pos.getZ(), kind));
                }
            }
        }
        List<Candidate> chosen = BaseFinderCore.select(candidates, mc.player.getX(), mc.player.getY(),
                mc.player.getZ(), storageFilter, spawnerFilter);
        List<Hit> hits = new ArrayList<>(chosen.size());
        for (Candidate c : chosen) hits.add(new Hit(new BlockPos(c.x(), c.y(), c.z()), c.kind()));
        targets = List.copyOf(hits);
    }

    static Kind kindOf(BlockState state) {
        if (state.isOf(Blocks.CHEST)) return Kind.CHEST;
        if (state.isOf(Blocks.TRAPPED_CHEST)) return Kind.TRAPPED_CHEST;
        if (state.isOf(Blocks.ENDER_CHEST)) return Kind.ENDER_CHEST;
        if (state.getBlock() instanceof ShulkerBoxBlock) return Kind.SHULKER;
        if (state.isOf(Blocks.DROPPER)) return Kind.DROPPER;
        if (state.isOf(Blocks.SPAWNER)) return Kind.SPAWNER;
        if (state.isOf(Blocks.TRIAL_SPAWNER)) return Kind.TRIAL_SPAWNER;
        return null;
    }

    private Color colorOf(Kind kind) {
        if (kind.isSpawner()) return spawnerColor.getValue();
        if (!perTypeColors.isEnabled()) return storageColor.getValue();
        return switch (kind) {
            case CHEST, TRAPPED_CHEST, ENDER_CHEST -> chestColor.getValue();
            case SHULKER -> shulkerColor.getValue();
            case DROPPER -> dropperColor.getValue();
            default -> storageColor.getValue();
        };
    }

    @Override
    public void onWorldRender(MatrixStack matrices, float tickDelta) {
        if (mc.world == null || mc.player == null || targets.isEmpty()) return;
        Vec3d camera = mc.gameRenderer.getCamera().getCameraPos();
        Filter storageFilter = storageFilter();
        Filter spawnerFilter = spawnerFilter();
        try (BufferAllocator allocator = interstellarTikiTiks.apexx.client.util.render.PooledBufferAllocator.acquire(16384)) {
            VertexConsumerProvider.Immediate immediate = VertexConsumerProvider.immediate(allocator);
            for (Hit hit : targets) {
                Look look = hit.kind().isSpawner() ? spawners : storage;
                Filter filter = hit.kind().isSpawner() ? spawnerFilter : storageFilter;
                if (!filter.enabled() || !filter.includes(hit.kind())) continue;
                Vec3d center = Vec3d.ofCenter(hit.pos());
                double distance = center.distanceTo(camera);
                if (distance > filter.range()) continue;

                // The block may have changed since the last scan.
                BlockState state = mc.world.getBlockState(hit.pos());
                if (kindOf(state) != hit.kind()) continue;
                VoxelShape shape = state.getOutlineShape(mc.world, hit.pos());
                if (shape.isEmpty()) continue;
                Box local = shape.getBoundingBox().offset(hit.pos()).expand(0.003)
                        .offset(-camera.x, -camera.y, -camera.z);

                double fade = look.distanceFade.isEnabled() ? BaseFinderCore.fade(distance, filter.range()) : 1.0;
                Color color = colorOf(hit.kind());
                boolean through = look.throughWalls.isEnabled();
                float fill = look.mode.is(OUTLINE) ? 0f : (float) (look.fillOpacity.getValue() * fade);
                float outline = look.mode.is(FILL) ? 0f : (float) (look.outlineOpacity.getValue() * fade);
                if (fill > 0.004f) RenderUtils.drawBoxEsp(matrices, immediate, local, color, fill, through);
                if (outline > 0.004f) {
                    // The shared line layer has a fixed width; thicker lines are drawn as nested passes.
                    int passes = Math.max(1, (int) Math.round(look.lineWidth.getValue()));
                    for (int i = 0; i < passes; i++) {
                        RenderUtils.drawBoxOutlineEsp(matrices, immediate, local.expand(i * 0.004), color,
                                outline, through);
                    }
                }
            }
            immediate.draw();
        }
    }
}
