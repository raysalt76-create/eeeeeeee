package interstellarTikiTiks.apexx.client.module.implementation.render;

import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.ColorSetting;
import interstellarTikiTiks.apexx.client.module.ModeSetting;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import interstellarTikiTiks.apexx.client.module.hooks.AttackTargetAware;
import interstellarTikiTiks.apexx.client.module.hooks.IncomingPacketInterceptor;
import interstellarTikiTiks.apexx.client.theme.AccentState;
import interstellarTikiTiks.apexx.client.util.combat.MeleeMechanics;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.Entity;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.EntityAnimationS2CPacket;

import java.awt.Color;

/**
 * A short screen-edge flash when you land a critical hit.
 *
 * <p>Predicted mode fires from the local attack itself, using the crit conditions vanilla checks
 * ({@link MeleeMechanics#onAttackEntity} records them at {@code attackEntity} HEAD for every attack,
 * module-dispatched or manual). Server Confirmed mode waits for the server's crit animation packet
 * ({@code EntityAnimationS2CPacket.CRIT}) on the entity you just hit, so it only flashes for crits the
 * server actually applied.</p>
 */
public final class CritFlash extends Module implements AttackTargetAware, IncomingPacketInterceptor {
    private static final long CONFIRM_WINDOW_MS = 600L;
    private static final int BANDS = 14;

    private final ModeSetting trigger = new ModeSetting("Trigger", "Predicted", "Predicted", "Server Confirmed")
            .setDescription("Predicted flashes the moment you swing with crit conditions; Server Confirmed waits for the "
                    + "server's crit particles on your target (a ping later, but never wrong).");
    private final SliderSetting intensity = new SliderSetting("Intensity", 0.55, 0.1, 1.0, 2)
            .setDescription("Opacity of the flash at its brightest.");
    private final SliderSetting size = new SliderSetting("Size", 14, 4, 40, 0)
            .setDescription("How far the flash reaches in from the screen edges, in percent of the screen.");
    private final SliderSetting duration = new SliderSetting("Duration", 260, 80, 1000, 0)
            .setDescription("Milliseconds the flash takes to fade out.");
    private final ModeSetting colorMode = new ModeSetting("Color Mode", "Theme", "Theme", "Custom")
            .setDescription("Theme uses the ClickGUI accent; Custom uses Custom Color.");
    private final ColorSetting customColor = new ColorSetting("Custom Color", new Color(255, 200, 80))
            .setDescription("Flash colour when Color Mode is Custom.")
            .setDependsOn("Color Mode:Custom");

    private long flashStart = -1L;
    /** Client thread only. */
    private int pendingTargetId = Integer.MIN_VALUE;
    private long pendingAt;

    public CritFlash() {
        super("CritFlash", "Flashes the edges of your screen when you land a critical hit, so you can feel your crit "
                + "timing without watching particles. Visible only to you.", Category.RENDER);
        addSetting(trigger);
        addSetting(intensity);
        addSetting(size);
        addSetting(duration);
        addSetting(colorMode);
        addSetting(customColor);
    }

    @Override
    public boolean rendersInWorld() {
        return false;
    }

    @Override
    public void onDisable() {
        reset();
    }

    @Override
    protected void onRuntimeStateReleased() {
        reset();
    }

    private void reset() {
        flashStart = -1L;
        pendingTargetId = Integer.MIN_VALUE;
    }

    /** {@code attackEntity} HEAD, client thread, after MeleeMechanics recorded this hit. */
    @Override
    public void onAttack(Entity target) {
        if (target == null || mc.player == null) return;
        MeleeMechanics.AttackRecord record = MeleeMechanics.lastAttack();
        if (record == null || record.targetId() != target.getId() || !record.crit()) return;
        if (trigger.is("Predicted")) {
            flashStart = System.currentTimeMillis();
        } else {
            pendingTargetId = target.getId();
            pendingAt = System.currentTimeMillis();
        }
    }

    /** Netty thread: copy two ints and hop to the client thread. Never cancels. */
    @Override
    public boolean handleIncomingPacket(Packet<?> packet) {
        if (packet instanceof EntityAnimationS2CPacket anim && anim.getAnimationId() == EntityAnimationS2CPacket.CRIT) {
            int id = anim.getEntityId();
            mc.execute(() -> onServerCrit(id));
        }
        return false;
    }

    private void onServerCrit(int entityId) {
        if (!isEnabled() || !trigger.is("Server Confirmed")) return;
        long now = System.currentTimeMillis();
        if (entityId == pendingTargetId && now - pendingAt <= CONFIRM_WINDOW_MS) {
            flashStart = now;
            pendingTargetId = Integer.MIN_VALUE;
        }
    }

    @Override
    public void onRender(DrawContext context, float tickDelta) {
        if (context == null || flashStart < 0L) return;
        long elapsed = System.currentTimeMillis() - flashStart;
        double dur = Math.max(1.0, duration.getValue());
        if (elapsed >= dur) {
            flashStart = -1L;
            return;
        }
        float t = (float) (elapsed / dur);
        // Fast attack (first 12%), then an ease-out fade.
        float envelope = t < 0.12f ? t / 0.12f : (float) Math.pow(1.0 - (t - 0.12f) / 0.88f, 2.0);
        float peak = (float) intensity.getValue() * envelope;
        if (peak <= 0.004f) return;

        int rgb = (colorMode.is("Custom") ? customColor.getValue() : AccentState.accentColor()).getRGB() & 0x00FFFFFF;
        int w = context.getScaledWindowWidth();
        int h = context.getScaledWindowHeight();
        float reach = (float) (size.getValue() / 100.0);
        int depthX = Math.max(BANDS, Math.round(w * reach));
        int depthY = Math.max(BANDS, Math.round(h * reach));

        // Stepped bands approximate a smooth falloff from each edge (quadratic, brightest at the edge).
        for (int i = 0; i < BANDS; i++) {
            float k = 1.0f - (float) i / BANDS;
            int alpha = Math.round(255.0f * peak * k * k / 3.0f);
            if (alpha <= 0) continue;
            int color = (Math.min(255, alpha) << 24) | rgb;
            int ix = depthX * i / BANDS;
            int ix2 = depthX * (i + 1) / BANDS;
            int iy = depthY * i / BANDS;
            int iy2 = depthY * (i + 1) / BANDS;
            context.fill(0, iy, w, iy2, color);                 // top
            context.fill(0, h - iy2, w, h - iy, color);         // bottom
            context.fill(ix, iy2, ix2, h - iy2, color);         // left (between top/bottom bands)
            context.fill(w - ix2, iy2, w - ix, h - iy2, color); // right
        }
    }
}
