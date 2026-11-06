package interstellarTikiTiks.apexx.client.module.implementation.render;

import interstellarTikiTiks.apexx.client.mixin.GameRendererAccessor;
import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.ColorSetting;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import interstellarTikiTiks.apexx.client.module.hooks.IncomingPacketInterceptor;
import interstellarTikiTiks.apexx.client.util.Notifier;
import interstellarTikiTiks.apexx.client.util.render.ProjectionUtil;
import interstellarTikiTiks.apexx.client.util.render.RenderUtils;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.BufferAllocator;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.PlayerRemoveS2CPacket;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.awt.Color;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Marks where players logged out. A player's last position is snapshotted every tick; when the
 * server removes them from the player list (a logout, not just leaving render distance) a ghost box
 * and label stay at that spot until they rejoin, it expires, or you change world. Memory only.
 */
public final class LogoutSpots extends Module implements IncomingPacketInterceptor {
    private static final String DISPLAY = "Display";
    private static final String LIMITS = "Limits";
    /** A snapshot older than this is too stale to place a spot from. */
    private static final long SNAPSHOT_MAX_AGE_MS = 3000L;
    private static final int MAX_SPOTS = 128;

    private final ColorSetting color = new ColorSetting("Color", new Color(255, 85, 85))
            .setDescription("Colour of the ghost box and the name label.")
            .setSection(DISPLAY);
    private final SliderSetting fillOpacity = new SliderSetting("Fill Opacity", 0.25, 0.0, 1.0, 2)
            .setDescription("Opacity of the filled ghost box; 0 draws only its outline.")
            .setSection(DISPLAY);
    private final BooleanSetting showName = new BooleanSetting("Show Name", true)
            .setDescription("Draws the player's name above the spot.")
            .setSection(DISPLAY);
    private final BooleanSetting showHealth = new BooleanSetting("Show Health", true)
            .setDescription("Adds the health (and absorption) the player had when they logged out.")
            .setDependsOn("Show Name")
            .setSection(DISPLAY);
    private final BooleanSetting showDistance = new BooleanSetting("Show Distance", true)
            .setDescription("Adds your distance to the spot.")
            .setDependsOn("Show Name")
            .setSection(DISPLAY);
    private final BooleanSetting notify = new BooleanSetting("Notify", true)
            .setDescription("Pop-up with the name and coordinates when a nearby player logs out. Needs the Notifications module.")
            .setSection(DISPLAY);

    private final SliderSetting range = new SliderSetting("Range", 256, 16, 1024, 0)
            .setDescription("Spots farther than this many blocks from you are not drawn (they are kept until they expire).")
            .setSection(LIMITS);
    private final SliderSetting maxAge = new SliderSetting("Max Age", 0, 0, 60, 0)
            .setDescription("Minutes a spot stays before it is removed; 0 keeps it until the player rejoins or you change world.")
            .setSection(LIMITS);

    /** Client thread only. */
    private final Map<UUID, Snapshot> snapshots = new HashMap<>();
    private final Map<UUID, Spot> spots = new LinkedHashMap<>();
    private ClientWorld lastWorld;

    public LogoutSpots() {
        super("LogoutSpots", "Shows a ghost box with the name and health of players who logged out near you, at the "
                + "exact spot they left, until they come back. Visible only to you.", Category.RENDER);
        addSetting(color);
        addSetting(fillOpacity);
        addSetting(showName);
        addSetting(showHealth);
        addSetting(showDistance);
        addSetting(notify);
        addSetting(range);
        addSetting(maxAge);
    }

    @Override
    public boolean rendersInWorld() {
        return true;
    }

    @Override
    public void onDisable() {
        clear();
    }

    @Override
    protected void onRuntimeStateReleased() {
        clear();
    }

    private void clear() {
        snapshots.clear();
        spots.clear();
        lastWorld = null;
    }

    /** Netty thread: only copies the ids and hops to the client thread. Never cancels the packet. */
    @Override
    public boolean handleIncomingPacket(Packet<?> packet) {
        if (packet instanceof PlayerRemoveS2CPacket remove && remove.profileIds() != null && !remove.profileIds().isEmpty()) {
            List<UUID> ids = List.copyOf(remove.profileIds());
            mc.execute(() -> {
                if (isEnabled()) onPlayersRemoved(ids);
            });
        }
        return false;
    }

    private void onPlayersRemoved(List<UUID> ids) {
        if (mc.world == null || mc.player == null) return;
        syncWorld();
        long now = System.currentTimeMillis();
        for (UUID id : ids) {
            if (id == null || id.equals(mc.player.getUuid())) continue;
            Snapshot snap = snapshots.remove(id);
            if (snap == null || now - snap.time > SNAPSHOT_MAX_AGE_MS) continue;
            spots.put(id, new Spot(snap, now));
            while (spots.size() > MAX_SPOTS) {
                Iterator<UUID> oldest = spots.keySet().iterator();
                oldest.next();
                oldest.remove();
            }
            if (notify.isEnabled()) {
                Notifier.info("LogoutSpots", String.format(Locale.ROOT, "%s logged out at %d, %d, %d", snap.name,
                        (int) Math.floor(snap.pos.x), (int) Math.floor(snap.pos.y), (int) Math.floor(snap.pos.z)));
            }
        }
    }

    private void syncWorld() {
        if (mc.world != lastWorld) {
            lastWorld = mc.world;
            snapshots.clear();
            spots.clear();
        }
    }

    @Override
    public void onTick() {
        if (mc.world == null || mc.player == null) return;
        syncWorld();
        long now = System.currentTimeMillis();

        for (AbstractClientPlayerEntity player : mc.world.getPlayers()) {
            if (player == null || player == mc.player || player.isRemoved()) continue;
            snapshots.put(player.getUuid(), new Snapshot(player.getName().getString(), player.getBoundingBox(),
                    new Vec3d(player.getX(), player.getY(), player.getZ()), player.getHealth(),
                    player.getAbsorptionAmount(), now));
        }
        snapshots.values().removeIf(s -> now - s.time > SNAPSHOT_MAX_AGE_MS);

        long ageLimit = Math.round(maxAge.getValue() * 60_000.0);
        ClientPlayNetworkHandler handler = mc.getNetworkHandler();
        Iterator<Map.Entry<UUID, Spot>> it = spots.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Spot> entry = it.next();
            Spot spot = entry.getValue();
            if (ageLimit > 0 && now - spot.loggedAt > ageLimit) {
                it.remove();
                continue;
            }
            // A rejoin is the tab entry or the entity coming back after we saw it gone; this keeps a
            // spot alive when the server sends the list removal before the entity removal.
            boolean inTab = handler != null && handler.getPlayerListEntry(entry.getKey()) != null;
            boolean inWorld = mc.world.getPlayerByUuid(entry.getKey()) != null;
            if ((spot.tabGone && inTab) || (spot.entityGone && inWorld)) {
                it.remove();
                continue;
            }
            if (!inTab) spot.tabGone = true;
            if (!inWorld) spot.entityGone = true;
        }
    }

    @Override
    public void onWorldRender(MatrixStack matrices, float tickDelta) {
        if (spots.isEmpty() || mc.world == null || mc.player == null || mc.world != lastWorld) return;
        Vec3d camera = mc.gameRenderer.getCamera().getCameraPos();
        double maxSq = range.getValue() * range.getValue();
        Color c = color.getValue();
        float fill = (float) fillOpacity.getValue();
        try (BufferAllocator allocator = interstellarTikiTiks.apexx.client.util.render.PooledBufferAllocator.acquire(4096)) {
            VertexConsumerProvider.Immediate immediate = VertexConsumerProvider.immediate(allocator);
            for (Spot spot : spots.values()) {
                if (mc.player.squaredDistanceTo(spot.pos) > maxSq) continue;
                Box local = spot.box.offset(-camera.x, -camera.y, -camera.z);
                if (fill > 0.0f) RenderUtils.drawBoxEsp(matrices, immediate, local, c, fill, true);
                RenderUtils.drawBoxOutlineEsp(matrices, immediate, local, c, 0.95f, true);
            }
            immediate.draw();
        }
    }

    @Override
    public void onRender(DrawContext context, float tickDelta) {
        if (!showName.isEnabled() || spots.isEmpty() || context == null || mc.player == null || mc.world == null
                || mc.world != lastWorld || mc.textRenderer == null || mc.gameRenderer == null) {
            return;
        }
        Camera camera = mc.gameRenderer.getCamera();
        float fov = ((GameRendererAccessor) mc.gameRenderer).callGetFov(camera, tickDelta, true);
        double maxSq = range.getValue() * range.getValue();
        int rgb = 0xFF000000 | (color.getValue().getRGB() & 0x00FFFFFF);
        for (Spot spot : spots.values()) {
            double distSq = mc.player.squaredDistanceTo(spot.pos);
            if (distSq > maxSq) continue;
            Vec3d screen = ProjectionUtil.worldToScreen(new Vec3d(spot.pos.x, spot.box.maxY + 0.45, spot.pos.z), camera, fov);
            if (screen == null) continue;
            StringBuilder label = new StringBuilder(spot.name);
            if (showHealth.isEnabled()) {
                label.append(String.format(Locale.ROOT, " %.1f", spot.health));
                if (spot.absorption > 0.0f) label.append(String.format(Locale.ROOT, "+%.1f", spot.absorption));
                label.append("hp");
            }
            if (showDistance.isEnabled()) label.append(String.format(Locale.ROOT, " (%dm)", Math.round(Math.sqrt(distSq))));
            String text = label.toString();
            int w = mc.textRenderer.getWidth(text);
            int x = (int) Math.round(screen.x) - w / 2;
            int y = (int) Math.round(screen.y) - mc.textRenderer.fontHeight;
            context.fill(x - 2, y - 2, x + w + 2, y + mc.textRenderer.fontHeight + 1, 0x90000000);
            context.drawTextWithShadow(mc.textRenderer, text, x, y, rgb);
        }
    }

    private record Snapshot(String name, Box box, Vec3d pos, float health, float absorption, long time) {
    }

    private static final class Spot {
        final String name;
        final Box box;
        final Vec3d pos;
        final float health;
        final float absorption;
        final long loggedAt;
        boolean tabGone;
        boolean entityGone;

        Spot(Snapshot s, long loggedAt) {
            this.name = s.name();
            this.box = s.box();
            this.pos = s.pos();
            this.health = s.health();
            this.absorption = s.absorption();
            this.loggedAt = loggedAt;
        }
    }
}
