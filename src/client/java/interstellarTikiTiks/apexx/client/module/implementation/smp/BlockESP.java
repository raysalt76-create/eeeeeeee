package interstellarTikiTiks.apexx.client.module.implementation.smp;

import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.ColorSetting;
import interstellarTikiTiks.apexx.client.module.ModeSetting;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import interstellarTikiTiks.apexx.client.module.StringSetting;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.BufferAllocator;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.ChunkStatus;

import java.awt.Color;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * BlockESP (SMP) - boxes chosen blocks through walls. Where LiquidBounce's BlockESP takes one list of
 * blocks, this one takes groups (chests, beds, spawners, ...) with a colour each, plus a free list.
 *
 * <p>It scans the loaded chunks around you on an interval and skips every chunk section that cannot
 * hold a wanted block, so the scan stays cheap.</p>
 */
public final class BlockESP extends Module {

    /** A named group of blocks with its own switch and colour. */
    private static final class Group {
        final BooleanSetting enabled;
        final ColorSetting color;
        final Predicate<BlockState> match;

        Group(String name, boolean on, Color color, Predicate<BlockState> match, String about) {
            this.enabled = new BooleanSetting(name, on).setDescription(about);
            this.color = new ColorSetting(name + " Color", color);
            this.match = match;
        }
    }

    private record Hit(BlockPos pos, Color color) {
    }

    private final List<Group> groups = new ArrayList<>();
    private final SliderSetting radius = new SliderSetting("Radius", 24.0, 8.0, 96.0, 0)
            .setDescription("Horizontal blocks around you that are searched.");
    private final SliderSetting vertical = new SliderSetting("Vertical Radius", 24.0, 4.0, 96.0, 0)
            .setDescription("Blocks above and below you that are searched.");
    private final SliderSetting interval = new SliderSetting("Scan Interval", 20.0, 5.0, 100.0, 0)
            .setDescription("Ticks between searches. Lower finds changes sooner and costs more.");
    private final SliderSetting maxBlocks = new SliderSetting("Max Blocks", 300.0, 20.0, 2000.0, 0)
            .setDescription("Most blocks drawn at once; the nearest are kept.");
    private final ModeSetting style = new ModeSetting("Style", SmpRender.BOTH, SmpRender.OUTLINE, SmpRender.FILLED)
            .setDescription("How each highlight is drawn: Both = outline plus translucent fill, Outline = edges only, Filled = solid translucent box.");
    private final SliderSetting fillOpacity = new SliderSetting("Fill Opacity", 0.2, 0.05, 0.8, 2)
            .setDescription("How solid the filled part of the box is.");
    private final StringSetting custom = new StringSetting("Custom Blocks", "")
            .setDescription("Comma separated block names to also show, e.g. diamond_ore,gilded_blackstone.");
    private final ColorSetting customColor = new ColorSetting("Custom Color", new Color(255, 255, 255));
    private final BooleanSetting tracers = new BooleanSetting("Tracers", false)
            .setDescription("Draws a line from your view to each block.");

    private final List<Hit> hits = new ArrayList<>();
    private Set<Block> customBlocks = Set.of();
    private String customSource = "";
    private int sinceScan;

    public BlockESP() {
        super("BlockESP", "Searches the area around you on an interval and draws boxes, visible through walls, around the block types you enable (plus any custom block names), nearest first and capped by Max Blocks. Visual only: nothing is sent to the server.", Category.RENDER);
        groups.add(new Group("Chests", true, new Color(255, 170, 40),
                s -> s.isOf(Blocks.CHEST) || s.isOf(Blocks.TRAPPED_CHEST), "Chests and trapped chests."));
        groups.add(new Group("Ender Chests", true, new Color(170, 80, 255),
                s -> s.isOf(Blocks.ENDER_CHEST), "Ender chests."));
        groups.add(new Group("Shulker Boxes", true, new Color(230, 120, 230),
                s -> s.isIn(BlockTags.SHULKER_BOXES), "Shulker boxes of every colour."));
        groups.add(new Group("Barrels", false, new Color(200, 140, 80),
                s -> s.isOf(Blocks.BARREL), "Barrels."));
        groups.add(new Group("Beds", false, new Color(255, 90, 90),
                s -> s.isIn(BlockTags.BEDS), "Beds."));
        groups.add(new Group("Spawners", true, new Color(80, 120, 255),
                s -> s.isOf(Blocks.SPAWNER) || s.isOf(Blocks.TRIAL_SPAWNER), "Monster spawners and trial spawners."));
        groups.add(new Group("Furnaces", false, new Color(170, 170, 170),
                s -> s.isOf(Blocks.FURNACE) || s.isOf(Blocks.BLAST_FURNACE) || s.isOf(Blocks.SMOKER),
                "Furnaces, blast furnaces and smokers."));
        groups.add(new Group("Hoppers & Droppers", false, new Color(150, 150, 190),
                s -> s.isOf(Blocks.HOPPER) || s.isOf(Blocks.DROPPER) || s.isOf(Blocks.DISPENSER),
                "Hoppers, droppers and dispensers."));
        groups.add(new Group("Portals", false, new Color(160, 60, 220),
                s -> s.isOf(Blocks.NETHER_PORTAL) || s.isOf(Blocks.END_PORTAL) || s.isOf(Blocks.END_PORTAL_FRAME)
                        || s.isOf(Blocks.END_GATEWAY), "Nether and end portals, portal frames and gateways."));
        groups.add(new Group("Command Blocks", false, new Color(255, 120, 60),
                s -> s.isOf(Blocks.COMMAND_BLOCK) || s.isOf(Blocks.CHAIN_COMMAND_BLOCK)
                        || s.isOf(Blocks.REPEATING_COMMAND_BLOCK), "Command blocks of every kind."));
        groups.add(new Group("Ancient Debris", false, new Color(130, 80, 60),
                s -> s.isOf(Blocks.ANCIENT_DEBRIS), "Ancient debris."));
        groups.add(new Group("Dragon Egg", false, new Color(120, 40, 200),
                s -> s.isOf(Blocks.DRAGON_EGG), "A dragon egg."));

        addSetting(radius);
        addSetting(vertical);
        addSetting(interval);
        addSetting(maxBlocks);
        addSetting(style);
        addSetting(fillOpacity);
        fillOpacity.setVisibility(() -> !style.is(SmpRender.OUTLINE));
        for (Group group : groups) {
            addSetting(group.enabled);
            addSetting(group.color);
            group.color.setVisibility(group.enabled::isEnabled);
        }
        addSetting(custom);
        addSetting(customColor);
        addSetting(tracers);
    }

    @Override
    public boolean rendersInWorld() {
        return true;
    }

    @Override
    public void onEnable() {
        sinceScan = Integer.MAX_VALUE;
        hits.clear();
    }

    @Override
    public void onDisable() {
        hits.clear();
    }

    @Override
    public void onTick() {
        if (mc.player == null || mc.world == null) {
            hits.clear();
            return;
        }
        if (++sinceScan >= (int) interval.getValue()) {
            sinceScan = 0;
            scan();
        }
    }

    private void scan() {
        refreshCustom();
        List<Group> active = new ArrayList<>();
        for (Group group : groups) {
            if (group.enabled.isEnabled()) active.add(group);
        }
        boolean any = !customBlocks.isEmpty();
        if (active.isEmpty() && !any) {
            hits.clear();
            return;
        }
        Predicate<BlockState> wanted = state -> {
            if (state.isAir()) return false;
            for (Group group : active) {
                if (group.match.test(state)) return true;
            }
            return customBlocks.contains(state.getBlock());
        };

        BlockPos center = mc.player.getBlockPos();
        int r = (int) radius.getValue();
        int v = (int) vertical.getValue();
        int minY = center.getY() - v;
        int maxY = center.getY() + v;
        int cx0 = (center.getX() - r) >> 4;
        int cx1 = (center.getX() + r) >> 4;
        int cz0 = (center.getZ() - r) >> 4;
        int cz1 = (center.getZ() + r) >> 4;
        Map<BlockPos, Color> found = new HashMap<>();
        double radiusSq = (double) r * r;

        for (int cx = cx0; cx <= cx1; cx++) {
            for (int cz = cz0; cz <= cz1; cz++) {
                Chunk chunk = mc.world.getChunk(cx, cz, ChunkStatus.FULL, false);
                if (chunk == null) continue;
                ChunkSection[] sections = chunk.getSectionArray();
                int bottom = chunk.getBottomY();
                for (int i = 0; i < sections.length; i++) {
                    int sectionY = bottom + i * 16;
                    if (sectionY + 15 < minY || sectionY > maxY) continue;
                    ChunkSection section = sections[i];
                    if (section == null || section.isEmpty() || !section.hasAny(wanted)) continue;
                    for (int x = 0; x < 16; x++) {
                        for (int y = 0; y < 16; y++) {
                            for (int z = 0; z < 16; z++) {
                                BlockState state = section.getBlockState(x, y, z);
                                if (!wanted.test(state)) continue;
                                BlockPos pos = new BlockPos((cx << 4) + x, sectionY + y, (cz << 4) + z);
                                if (pos.getY() < minY || pos.getY() > maxY) continue;
                                double dx = pos.getX() + 0.5 - mc.player.getX();
                                double dz = pos.getZ() + 0.5 - mc.player.getZ();
                                if (dx * dx + dz * dz > radiusSq) continue;
                                found.put(pos, colorFor(state, active));
                            }
                        }
                    }
                }
            }
        }

        hits.clear();
        List<Map.Entry<BlockPos, Color>> ordered = new ArrayList<>(found.entrySet());
        Vec3d eye = mc.player.getEyePos();
        ordered.sort((a, b) -> Double.compare(a.getKey().getSquaredDistance(eye), b.getKey().getSquaredDistance(eye)));
        int limit = (int) maxBlocks.getValue();
        for (int i = 0; i < ordered.size() && i < limit; i++) {
            hits.add(new Hit(ordered.get(i).getKey(), ordered.get(i).getValue()));
        }
    }

    private Color colorFor(BlockState state, List<Group> active) {
        for (Group group : active) {
            if (group.match.test(state)) return group.color.getValue();
        }
        return customColor.getValue();
    }

    private void refreshCustom() {
        String source = custom.getValue() == null ? "" : custom.getValue();
        if (source.equals(customSource)) return;
        customSource = source;
        Set<Block> blocks = new HashSet<>();
        for (String part : source.split(",")) {
            String name = part.trim().toLowerCase(Locale.ROOT);
            if (name.isEmpty()) continue;
            Identifier id = Identifier.tryParse(name.contains(":") ? name : "minecraft:" + name);
            if (id != null && Registries.BLOCK.containsId(id)) blocks.add(Registries.BLOCK.get(id));
        }
        customBlocks = blocks;
    }

    @Override
    public void onWorldRender(MatrixStack matrices, float tickDelta) {
        if (mc.player == null || mc.world == null || hits.isEmpty()) return;
        Vec3d camera = mc.gameRenderer.getCamera().getCameraPos();
        try (BufferAllocator allocator = interstellarTikiTiks.apexx.client.util.render.PooledBufferAllocator.acquire(8192)) {
            VertexConsumerProvider.Immediate immediate = VertexConsumerProvider.immediate(allocator);
            for (Hit hit : hits) {
                Box box = new Box(hit.pos());
                SmpRender.box(matrices, immediate, box, camera, hit.color(), style.getCurrentMode(),
                        (float) fillOpacity.getValue());
                if (tracers.isEnabled()) {
                    SmpRender.tracer(matrices, immediate, camera, box.getCenter(), hit.color(), tickDelta);
                }
            }
            immediate.draw();
        }
    }
}
