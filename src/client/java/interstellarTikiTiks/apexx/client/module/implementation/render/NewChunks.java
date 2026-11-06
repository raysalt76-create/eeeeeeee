package interstellarTikiTiks.apexx.client.module.implementation.render;

import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.ColorSetting;
import interstellarTikiTiks.apexx.client.module.ModeSetting;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import interstellarTikiTiks.apexx.client.module.hooks.IncomingPacketInterceptor;
import interstellarTikiTiks.apexx.client.util.render.RenderUtils;
import it.unimi.dsi.fastutil.longs.Long2IntLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.block.BlockState;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.BufferAllocator;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.fluid.FluidState;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDeltaUpdateS2CPacket;
import net.minecraft.registry.RegistryKey;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.WorldChunk;

import java.awt.Color;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Highlights chunks that were most likely generated just now (new) versus chunks that existed before
 * (old), using the liquid-flow heuristic: a freshly generated chunk starts flowing its fluids right after
 * it is sent, while an old chunk arrives with its fluids already flowing. Heuristic only; memory only.
 */
public final class NewChunks extends Module implements IncomingPacketInterceptor {
    private static final String DISPLAY = "Display";
    private static final Direction[] FLOW_NEIGHBOURS = {
            Direction.EAST, Direction.NORTH, Direction.WEST, Direction.SOUTH, Direction.UP};
    private static final int SCANS_PER_TICK = 48;
    private static final int PENDING_RETRY_TICKS = 40;
    private static final int MAX_CHUNKS_PER_SET = 60_000;

    private final ColorSetting newColor = new ColorSetting("New Color", new Color(255, 60, 60))
            .setDescription("Colour of chunks that look freshly generated.")
            .setSection(DISPLAY);
    private final BooleanSetting showOld = new BooleanSetting("Show Old", false)
            .setDescription("Also highlights chunks that look previously generated.")
            .setSection(DISPLAY);
    private final ColorSetting oldColor = new ColorSetting("Old Color", new Color(60, 200, 90))
            .setDescription("Colour of chunks that look previously generated.")
            .setDependsOn("Show Old")
            .setSection(DISPLAY);
    private final ModeSetting style = new ModeSetting("Style", "Filled", "Filled", "Outline", "Both")
            .setDescription("Draw each chunk as a filled flat square, its outline, or both.")
            .setSection(DISPLAY);
    private final SliderSetting opacity = new SliderSetting("Opacity", 0.25, 0.05, 1.0, 2)
            .setDescription("Opacity of the filled squares.")
            .setDependsOn("Style:Filled|Both")
            .setSection(DISPLAY);
    private final SliderSetting renderY = new SliderSetting("Render Y", 0, -64, 320, 0)
            .setDescription("Height (Y level) at which the flat chunk squares are drawn.")
            .setSection(DISPLAY);

    /** Per dimension, per connection. Client thread only. */
    private final Map<RegistryKey<World>, DimensionChunks> dimensions = new HashMap<>();
    /** Chunk key -> ticks left to wait for vanilla to load it before scanning. */
    private final Long2IntLinkedOpenHashMap pending = new Long2IntLinkedOpenHashMap();
    private ClientPlayNetworkHandler lastHandler;
    private ClientWorld lastWorld;

    public NewChunks() {
        super("NewChunks", "Highlights chunks that were most likely generated for the first time just now (and "
                + "optionally older ones), using how fluids flow when a chunk arrives. Handy for spotting fresh "
                + "terrain and trails of other players. A heuristic, so expect some misses. Visible only to you.",
                Category.RENDER);
        addSetting(newColor);
        addSetting(showOld);
        addSetting(oldColor);
        addSetting(style);
        addSetting(opacity);
        addSetting(renderY);
    }

    @Override
    public boolean rendersInWorld() {
        return true;
    }

    @Override
    public void onDisable() {
        clearAll();
    }

    @Override
    protected void onRuntimeStateReleased() {
        clearAll();
    }

    private void clearAll() {
        dimensions.clear();
        pending.clear();
        lastHandler = null;
        lastWorld = null;
    }

    // ---- Netty thread: copy what is needed, then hop to the client thread -------------------------

    @Override
    public boolean handleIncomingPacket(Packet<?> packet) {
        if (packet instanceof ChunkDeltaUpdateS2CPacket delta) {
            List<BlockPos> flowing = new ArrayList<>();
            delta.visitUpdates((pos, state) -> {
                if (isFlowing(state)) flowing.add(pos.toImmutable());
            });
            if (!flowing.isEmpty()) mc.execute(() -> onFlowUpdates(flowing));
        } else if (packet instanceof BlockUpdateS2CPacket update) {
            if (isFlowing(update.getState())) {
                BlockPos pos = update.getPos().toImmutable();
                mc.execute(() -> onFlowUpdates(List.of(pos)));
            }
        } else if (packet instanceof ChunkDataS2CPacket data) {
            long key = ChunkPos.toLong(data.getChunkX(), data.getChunkZ());
            mc.execute(() -> {
                if (isEnabled() && syncState()) pending.put(key, PENDING_RETRY_TICKS);
            });
        }
        return false;
    }

    private static boolean isFlowing(BlockState state) {
        if (state == null) return false;
        FluidState fluid = state.getFluidState();
        return !fluid.isEmpty() && !fluid.isStill();
    }

    /** Client thread, before vanilla applies the update: a fresh flow next to still fluid marks a new chunk. */
    private void onFlowUpdates(List<BlockPos> positions) {
        if (!isEnabled() || !syncState()) return;
        ClientWorld world = mc.world;
        DimensionChunks dim = current();
        if (world == null || dim == null) return;
        for (BlockPos pos : positions) {
            long key = ChunkPos.toLong(pos.getX() >> 4, pos.getZ() >> 4);
            if (dim.oldChunks.contains(key) || dim.newChunks.contains(key)) continue;
            for (Direction dir : FLOW_NEIGHBOURS) {
                if (world.getBlockState(pos.offset(dir)).getFluidState().isStill()) {
                    addCapped(dim.newChunks, key);
                    break;
                }
            }
        }
    }

    // ---- Client thread ----------------------------------------------------------------------------

    @Override
    public void onTick() {
        if (!syncState()) return;
        DimensionChunks dim = current();
        if (dim == null || pending.isEmpty()) return;
        int scanned = 0;
        var it = pending.long2IntEntrySet().fastIterator();
        while (it.hasNext() && scanned < SCANS_PER_TICK) {
            var entry = it.next();
            long key = entry.getLongKey();
            WorldChunk chunk = mc.world.getChunkManager().getChunk(ChunkPos.getPackedX(key), ChunkPos.getPackedZ(key),
                    ChunkStatus.FULL, false);
            if (chunk == null) {
                int left = entry.getIntValue() - 1;
                if (left <= 0) it.remove();
                else entry.setValue(left);
                continue;
            }
            it.remove();
            scanned++;
            if (dim.newChunks.contains(key) || dim.oldChunks.contains(key)) continue;
            if (hasFlowingFluid(chunk)) addCapped(dim.oldChunks, key);
        }
    }

    /** Palette check per section: cheap, and a palette entry means the state occurs in that section. */
    private static boolean hasFlowingFluid(WorldChunk chunk) {
        for (ChunkSection section : chunk.getSectionArray()) {
            if (section == null || section.isEmpty()) continue;
            if (section.hasAny(NewChunks::isFlowing)) return true;
        }
        return false;
    }

    /**
     * Keeps state bound to the current connection and world. Returns false when there is no world.
     * A new connection (another server or a rejoin) drops everything; a dimension change keeps each
     * dimension's sets but drops chunks still waiting for a scan.
     */
    private boolean syncState() {
        ClientPlayNetworkHandler handler = mc.getNetworkHandler();
        if (handler != lastHandler) {
            dimensions.clear();
            pending.clear();
            lastHandler = handler;
        }
        if (mc.world != lastWorld) {
            pending.clear();
            lastWorld = mc.world;
        }
        return mc.world != null && handler != null;
    }

    private DimensionChunks current() {
        if (mc.world == null) return null;
        return dimensions.computeIfAbsent(mc.world.getRegistryKey(), k -> new DimensionChunks());
    }

    private void addCapped(LongSet set, long key) {
        if (set.size() >= MAX_CHUNKS_PER_SET) trimFar(set);
        set.add(key);
    }

    /** Drops chunks far from the player when a set grows past its cap. */
    private void trimFar(LongSet set) {
        if (mc.player == null) {
            set.clear();
            return;
        }
        int px = mc.player.getChunkPos().x;
        int pz = mc.player.getChunkPos().z;
        int keep = Math.max(64, mc.options.getClampedViewDistance() * 4);
        set.removeIf((long key) -> Math.abs(ChunkPos.getPackedX(key) - px) > keep
                || Math.abs(ChunkPos.getPackedZ(key) - pz) > keep);
        if (set.size() >= MAX_CHUNKS_PER_SET) set.clear();
    }

    @Override
    public void onWorldRender(MatrixStack matrices, float tickDelta) {
        if (mc.world == null || mc.player == null || mc.world != lastWorld) return;
        DimensionChunks dim = dimensions.get(mc.world.getRegistryKey());
        if (dim == null || (dim.newChunks.isEmpty() && (!showOld.isEnabled() || dim.oldChunks.isEmpty()))) return;

        Vec3d camera = mc.gameRenderer.getCamera().getCameraPos();
        int radius = Math.max(2, mc.options.getClampedViewDistance());
        int pcx = mc.player.getChunkPos().x;
        int pcz = mc.player.getChunkPos().z;
        double y = renderY.getValue();
        boolean fill = !style.is("Outline");
        boolean outline = !style.is("Filled");
        float alpha = (float) opacity.getValue();
        Color newC = newColor.getValue();
        Color oldC = oldColor.getValue();
        boolean old = showOld.isEnabled();

        try (BufferAllocator allocator = interstellarTikiTiks.apexx.client.util.render.PooledBufferAllocator.acquire(8192)) {
            VertexConsumerProvider.Immediate immediate = VertexConsumerProvider.immediate(allocator);
            for (int cx = pcx - radius; cx <= pcx + radius; cx++) {
                for (int cz = pcz - radius; cz <= pcz + radius; cz++) {
                    long key = ChunkPos.toLong(cx, cz);
                    Color c;
                    if (dim.newChunks.contains(key)) c = newC;
                    else if (old && dim.oldChunks.contains(key)) c = oldC;
                    else continue;
                    double x0 = (cx << 4) - camera.x;
                    double z0 = (cz << 4) - camera.z;
                    Box box = new Box(x0, y - camera.y, z0, x0 + 16, y + 0.02 - camera.y, z0 + 16);
                    if (fill) RenderUtils.drawBoxEsp(matrices, immediate, box, c, alpha, true);
                    if (outline) RenderUtils.drawBoxOutlineEsp(matrices, immediate, box, c, 0.9f, true);
                }
            }
            immediate.draw();
        }
    }

    private static final class DimensionChunks {
        final LongSet newChunks = new LongOpenHashSet();
        final LongSet oldChunks = new LongOpenHashSet();
    }
}
