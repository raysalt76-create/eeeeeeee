package interstellarTikiTiks.apexx.client.module.implementation.smp;

import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.ColorSetting;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import interstellarTikiTiks.apexx.client.util.Notifier;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.util.BufferAllocator;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.awt.Color;
import java.util.Locale;

/**
 * Death Coords: when you die, shows where (toast, optional chat line) and keeps a "Last Death" waypoint
 * (box, optional tracer and an on-screen line with the distance) until you reach it or die again.
 *
 * <p>Memory only: nothing is written to disk. The vanilla chat log does record chat lines, which is why the
 * chat line is off by default.</p>
 */
public final class DeathCoords extends Module {

    private final BooleanSetting toast = new BooleanSetting("Toast", true)
            .setDescription("Shows your death position and dimension as a notification.");
    private final BooleanSetting chatLine = new BooleanSetting("Chat Line", false)
            .setDescription("Also prints the position in your chat (only you see it). Off by default because the game writes chat to its log file.");
    private final BooleanSetting waypoint = new BooleanSetting("Waypoint", true)
            .setDescription("Marks the last death spot in the world with a box you can see through walls (same dimension and server only).");
    private final BooleanSetting tracer = new BooleanSetting("Tracer", false)
            .setDependsOn("Waypoint")
            .setDescription("Draws a line from your view to the last death spot.");
    private final BooleanSetting hudLine = new BooleanSetting("HUD Line", true)
            .setDescription("Shows 'Last Death: x y z (distance)' at the top left of the screen while the waypoint is active.");
    private final ColorSetting color = new ColorSetting("Color", new Color(255, 70, 70))
            .setDependsOn("Waypoint");
    private final SliderSetting clearDistance = new SliderSetting("Clear Distance", 3, 0, 32, 0)
            .setDescription("The waypoint is removed once you come within this many blocks of it. 0 = keep it until your next death.");

    private BlockPos deathPos;
    private String deathDimension;
    private String deathServer;
    private boolean wasDead;

    public DeathCoords() {
        super("DeathCoords", "When you die, tells you exactly where (toast and optional chat line) and keeps a Last Death waypoint in the world until you get back to it. Nothing is saved to disk.", Category.SMP);
        addSetting(toast);
        addSetting(chatLine);
        addSetting(waypoint);
        addSetting(tracer);
        addSetting(hudLine);
        addSetting(color);
        addSetting(clearDistance);
    }

    @Override
    public void onDisable() {
        wasDead = false;
    }

    @Override
    protected void onRuntimeStateReleased() {
        wasDead = false;
    }

    @Override
    public void onTick() {
        if (mc.player == null || mc.world == null) {
            wasDead = false;
            return;
        }
        boolean dead = mc.player.isDead() || mc.player.getHealth() <= 0.0f;
        if (dead && !wasDead) recordDeath();
        wasDead = dead;

        if (!dead && deathPos != null && sameContext() && clearDistance.getValue() > 0) {
            double max = clearDistance.getValue();
            if (mc.player.getEntityPos().squaredDistanceTo(Vec3d.ofCenter(deathPos)) <= max * max) {
                Notifier.info("DeathCoords", "Reached your last death spot.");
                deathPos = null;
            }
        }
    }

    private void recordDeath() {
        deathPos = mc.player.getBlockPos().toImmutable();
        deathDimension = dimensionId();
        deathServer = serverId();
        String where = String.format(Locale.ROOT, "%d %d %d in %s", deathPos.getX(), deathPos.getY(), deathPos.getZ(),
                prettyDimension(deathDimension));
        if (toast.isEnabled()) Notifier.warn("DeathCoords", "You died at " + where);
        if (chatLine.isEnabled() && mc.inGameHud != null) {
            mc.inGameHud.getChatHud().addMessage(Text.literal("[Apexx] You died at " + where));
        }
    }

    private boolean sameContext() {
        return deathPos != null && mc.world != null && dimensionId().equals(deathDimension)
                && serverId().equals(deathServer);
    }

    private String dimensionId() {
        return mc.world == null ? "" : mc.world.getRegistryKey().getValue().toString();
    }

    private String serverId() {
        ServerInfo server = mc.getCurrentServerEntry();
        if (server != null && server.address != null) return server.address.toLowerCase(Locale.ROOT);
        return mc.isInSingleplayer() ? "singleplayer" : "";
    }

    private static String prettyDimension(String id) {
        if (id == null) return "?";
        return switch (id) {
            case "minecraft:overworld" -> "the Overworld";
            case "minecraft:the_nether" -> "the Nether";
            case "minecraft:the_end" -> "the End";
            default -> id;
        };
    }

    @Override
    public void onRender(DrawContext context, float tickDelta) {
        if (!hudLine.isEnabled() || context == null || mc.player == null || mc.textRenderer == null
                || !sameContext() || mc.options.hudHidden) {
            return;
        }
        double distance = Math.sqrt(mc.player.getEntityPos().squaredDistanceTo(Vec3d.ofCenter(deathPos)));
        String line = String.format(Locale.ROOT, "Last Death: %d %d %d (%.0fm)", deathPos.getX(), deathPos.getY(),
                deathPos.getZ(), distance);
        context.drawTextWithShadow(mc.textRenderer, line, 4, 4, 0xFF000000 | (color.getValue().getRGB() & 0xFFFFFF));
    }

    @Override
    public boolean rendersInWorld() {
        return true;
    }

    @Override
    public void onWorldRender(MatrixStack matrices, float tickDelta) {
        if (!waypoint.isEnabled() || mc.player == null || mc.world == null || !sameContext()) return;
        Vec3d camera = mc.gameRenderer.getCamera().getCameraPos();
        Color c = color.getValue();
        Box box = new Box(deathPos).expand(0.02);
        try (BufferAllocator allocator = interstellarTikiTiks.apexx.client.util.render.PooledBufferAllocator.acquire(2048)) {
            VertexConsumerProvider.Immediate immediate = VertexConsumerProvider.immediate(allocator);
            SmpRender.box(matrices, immediate, box, camera, c, SmpRender.BOTH, 0.25f);
            // A thin pillar so the spot is visible from far away.
            Box beam = new Box(deathPos.getX() + 0.4, deathPos.getY() + 1.0, deathPos.getZ() + 0.4,
                    deathPos.getX() + 0.6, deathPos.getY() + 24.0, deathPos.getZ() + 0.6);
            SmpRender.box(matrices, immediate, beam, camera, c, SmpRender.FILLED, 0.18f);
            if (tracer.isEnabled()) SmpRender.tracer(matrices, immediate, camera, box.getCenter(), c, tickDelta);
            immediate.draw();
        }
    }
}
