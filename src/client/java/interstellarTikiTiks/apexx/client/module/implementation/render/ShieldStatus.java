package interstellarTikiTiks.apexx.client.module.implementation.render;

import interstellarTikiTiks.apexx.client.friend.FriendManager;
import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.ColorSetting;
import interstellarTikiTiks.apexx.client.module.ModeSetting;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import interstellarTikiTiks.apexx.client.module.hooks.AttackTargetAware;
import interstellarTikiTiks.apexx.client.module.hooks.ShieldBreakConfirmationHandler;
import interstellarTikiTiks.apexx.client.theme.AccentState;
import interstellarTikiTiks.apexx.client.util.render.PooledBufferAllocator;
import interstellarTikiTiks.apexx.client.util.render.ScreenProjector;
import interstellarTikiTiks.apexx.client.util.render.types.RenderTypes;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.BufferAllocator;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.BlocksAttacksComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix3x2fStack;
import org.joml.Matrix4f;

import java.awt.Color;

/**
 * Shield Status: a shrinking ring under enemies whose shield was just disabled, with the seconds left
 * until it can block again, and a small shield icon over players who are blocking right now.
 *
 * <p>The disable is the server's entity status 30 ({@link ShieldBreakConfirmationHandler}). Its length is
 * the attacker's weapon {@code disable_blocking_for_seconds} times the shield's
 * {@code blocks_attacks.disable_cooldown_scale}: when you were the one who just hit that player, your
 * held weapon's value is used; otherwise Default Duration (vanilla axe: 5 s). A player who raises a
 * shield again is cleared early, since that proves it is usable.</p>
 */
public final class ShieldStatus extends Module implements ShieldBreakConfirmationHandler, AttackTargetAware {
    private static final long OWN_HIT_WINDOW_MS = 1500L;
    private static final int SEGMENTS = 48;

    private final SliderSetting defaultDuration = new SliderSetting("Default Duration", 5.0, 1.0, 10.0, 1)
            .setDescription("Seconds a shield stays disabled when the hit was not yours (vanilla axe: 5).");
    private final BooleanSetting ring = new BooleanSetting("Ring", true)
            .setDescription("Draws the shrinking timer ring under the player.");
    private final SliderSetting ringSize = new SliderSetting("Ring Size", 0.8, 0.4, 1.6, 2)
            .setDescription("Ring radius in blocks.").setDependsOn("Ring");
    private final BooleanSetting throughWalls = new BooleanSetting("Through Walls", true)
            .setDescription("Draws rings over terrain.").setDependsOn("Ring");
    private final BooleanSetting timerText = new BooleanSetting("Timer Text", true)
            .setDescription("Shows the seconds left next to the ring.");
    private final BooleanSetting blockingIcon = new BooleanSetting("Blocking Icon", true)
            .setDescription("Shows a small shield icon over players who are blocking right now.");
    private final BooleanSetting includeFriends = new BooleanSetting("Friends", false)
            .setDescription("Also tracks friends.");
    private final ModeSetting colorMode = new ModeSetting("Color Mode", "Theme", "Theme", "Custom")
            .setDescription("Theme uses the ClickGUI accent; Custom uses Custom Color.");
    private final ColorSetting customColor = new ColorSetting("Custom Color", new Color(255, 120, 60))
            .setDescription("Ring colour when Color Mode is Custom.").setDependsOn("Color Mode:Custom");

    /** Client thread only. */
    private final Int2ObjectOpenHashMap<Disable> disabled = new Int2ObjectOpenHashMap<>();
    private final float[] screen = new float[3];
    private ClientWorld lastWorld;
    private int lastHitTargetId = Integer.MIN_VALUE;
    private long lastHitAt;
    private float lastHitDisableSeconds;
    private ItemStack shieldIcon;

    public ShieldStatus() {
        super("ShieldStatus", "Shows a countdown ring under players whose shield you (or anyone) just disabled, with the "
                + "seconds until they can block again, and a shield icon over players who are blocking. Visible only to you.",
                Category.RENDER);
        addSetting(defaultDuration);
        addSetting(ring);
        addSetting(ringSize);
        addSetting(throughWalls);
        addSetting(timerText);
        addSetting(blockingIcon);
        addSetting(includeFriends);
        addSetting(colorMode);
        addSetting(customColor);
    }

    @Override
    public boolean rendersInWorld() {
        return ring.isEnabled();
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
        disabled.clear();
        lastHitTargetId = Integer.MIN_VALUE;
        lastWorld = null;
    }

    /** {@code attackEntity} HEAD: remember who we hit and our weapon's shield-disable time. */
    @Override
    public void onAttack(Entity target) {
        if (!(target instanceof PlayerEntity) || mc.player == null) return;
        lastHitTargetId = target.getId();
        lastHitAt = System.currentTimeMillis();
        lastHitDisableSeconds = mc.player.getWeaponDisableBlockingForSeconds();
    }

    /** Entity status 30, client thread. */
    @Override
    public void breakConfirmed(PlayerEntity player) {
        if (player == null || player == mc.player) return;
        long now = System.currentTimeMillis();
        float seconds = (float) defaultDuration.getValue();
        if (player.getId() == lastHitTargetId && now - lastHitAt <= OWN_HIT_WINDOW_MS && lastHitDisableSeconds > 0.0f) {
            seconds = lastHitDisableSeconds;
        }
        seconds *= shieldScale(player);
        if (seconds <= 0.0f) return;
        Disable d = disabled.get(player.getId());
        if (d == null) {
            d = new Disable();
            disabled.put(player.getId(), d);
        }
        d.start = now;
        d.end = now + Math.round(seconds * 1000.0);
    }

    private static float shieldScale(PlayerEntity player) {
        ItemStack[] candidates = {player.getActiveItem(), player.getOffHandStack(), player.getMainHandStack()};
        for (ItemStack stack : candidates) {
            if (stack == null || stack.isEmpty()) continue;
            BlocksAttacksComponent blocks = stack.get(DataComponentTypes.BLOCKS_ATTACKS);
            if (blocks != null) return Math.max(0.0f, blocks.disableCooldownScale());
        }
        return 1.0f;
    }

    @Override
    public void onTick() {
        if (mc.world == null) return;
        if (mc.world != lastWorld) {
            disabled.clear();
            lastWorld = mc.world;
        }
        if (disabled.isEmpty()) return;
        long now = System.currentTimeMillis();
        ObjectIterator<Int2ObjectMap.Entry<Disable>> it = disabled.int2ObjectEntrySet().fastIterator();
        while (it.hasNext()) {
            Int2ObjectMap.Entry<Disable> entry = it.next();
            Entity e = mc.world.getEntityById(entry.getIntKey());
            boolean blockingAgain = e instanceof PlayerEntity p && p.isBlocking()
                    && now - entry.getValue().start > 250L;
            if (now >= entry.getValue().end || !(e instanceof PlayerEntity) || !e.isAlive() || blockingAgain) {
                it.remove();
            }
        }
    }

    private boolean tracked(Entity e) {
        return e instanceof PlayerEntity p && p != mc.player
                && (includeFriends.isEnabled() || !FriendManager.getInstance().isFriend(p.getUuid()));
    }

    private int color() {
        return 0xFF000000 | ((colorMode.is("Custom") ? customColor.getValue().getRGB() : AccentState.accentArgb()) & 0x00FFFFFF);
    }

    @Override
    public void onWorldRender(MatrixStack matrices, float tickDelta) {
        if (!ring.isEnabled() || disabled.isEmpty() || mc.world == null) return;
        Vec3d cam = mc.gameRenderer.getCamera().getCameraPos();
        long now = System.currentTimeMillis();
        int argb = color();
        float r = ((argb >> 16) & 0xFF) / 255.0f, g = ((argb >> 8) & 0xFF) / 255.0f, b = (argb & 0xFF) / 255.0f;
        float radius = (float) ringSize.getValue();
        float inner = radius * 0.86f;
        try (BufferAllocator allocator = PooledBufferAllocator.acquire(8192)) {
            VertexConsumerProvider.Immediate immediate = VertexConsumerProvider.immediate(allocator);
            VertexConsumer buf = immediate.getBuffer(throughWalls.isEnabled() ? RenderTypes.TRIANGLES_NO_DEPTH : RenderTypes.TRIANGLES);
            Matrix4f m = matrices.peek().getPositionMatrix();
            ObjectIterator<Int2ObjectMap.Entry<Disable>> it = disabled.int2ObjectEntrySet().fastIterator();
            while (it.hasNext()) {
                Int2ObjectMap.Entry<Disable> entry = it.next();
                Entity e = mc.world.getEntityById(entry.getIntKey());
                if (!tracked(e)) continue;
                Disable d = entry.getValue();
                float remaining = MathHelper.clamp((d.end - now) / (float) Math.max(1L, d.end - d.start), 0.0f, 1.0f);
                float cx = (float) (MathHelper.lerp(tickDelta, e.lastRenderX, e.getX()) - cam.x);
                float cy = (float) (MathHelper.lerp(tickDelta, e.lastRenderY, e.getY()) - cam.y) + 0.04f;
                float cz = (float) (MathHelper.lerp(tickDelta, e.lastRenderZ, e.getZ()) - cam.z);
                int filled = Math.round(remaining * SEGMENTS);
                for (int i = 0; i < SEGMENTS; i++) {
                    float alpha = i < filled ? 0.85f : 0.18f;
                    // Clockwise from the player's front-independent north; segment i spans angles a0..a1.
                    double a0 = Math.PI * 2.0 * i / SEGMENTS - Math.PI / 2.0;
                    double a1 = Math.PI * 2.0 * (i + 1) / SEGMENTS - Math.PI / 2.0;
                    float c0 = (float) Math.cos(a0), s0 = (float) Math.sin(a0);
                    float c1 = (float) Math.cos(a1), s1 = (float) Math.sin(a1);
                    float ox0 = cx + c0 * radius, oz0 = cz + s0 * radius;
                    float ox1 = cx + c1 * radius, oz1 = cz + s1 * radius;
                    float ix0 = cx + c0 * inner, iz0 = cz + s0 * inner;
                    float ix1 = cx + c1 * inner, iz1 = cz + s1 * inner;
                    buf.vertex(m, ix0, cy, iz0).color(r, g, b, alpha);
                    buf.vertex(m, ox0, cy, oz0).color(r, g, b, alpha);
                    buf.vertex(m, ox1, cy, oz1).color(r, g, b, alpha);
                    buf.vertex(m, ix0, cy, iz0).color(r, g, b, alpha);
                    buf.vertex(m, ox1, cy, oz1).color(r, g, b, alpha);
                    buf.vertex(m, ix1, cy, iz1).color(r, g, b, alpha);
                }
            }
            immediate.draw();
        }
    }

    @Override
    public void onRender(DrawContext context, float tickDelta) {
        if (context == null || mc.world == null || mc.player == null || mc.textRenderer == null) return;
        boolean wantTimers = timerText.isEnabled() && !disabled.isEmpty();
        if (!wantTimers && !blockingIcon.isEnabled()) return;
        if (!ScreenProjector.begin(tickDelta)) return;
        long now = System.currentTimeMillis();

        if (wantTimers) {
            int argb = color();
            for (Int2ObjectMap.Entry<Disable> entry : disabled.int2ObjectEntrySet()) {
                Entity e = mc.world.getEntityById(entry.getIntKey());
                if (!tracked(e)) continue;
                long left = entry.getValue().end - now;
                if (left <= 0L) continue;
                double x = MathHelper.lerp(tickDelta, e.lastRenderX, e.getX());
                double y = MathHelper.lerp(tickDelta, e.lastRenderY, e.getY());
                double z = MathHelper.lerp(tickDelta, e.lastRenderZ, e.getZ());
                if (!ScreenProjector.toGui(x, y + e.getHeight() * 0.5, z, screen)) continue;
                String label = tenths(left);
                int w = mc.textRenderer.getWidth(label);
                int tx = Math.round(screen[0]) - w / 2;
                int ty = Math.round(screen[1]);
                context.fill(tx - 2, ty - 2, tx + w + 2, ty + mc.textRenderer.fontHeight, 0x90000000);
                context.drawTextWithShadow(mc.textRenderer, label, tx, ty, argb);
            }
        }

        if (blockingIcon.isEnabled()) {
            if (shieldIcon == null) shieldIcon = new ItemStack(Items.SHIELD);
            Matrix3x2fStack m = context.getMatrices();
            for (AbstractClientPlayerEntity p : mc.world.getPlayers()) {
                if (!tracked(p) || !p.isBlocking() || p.isInvisible()) continue;
                double x = MathHelper.lerp(tickDelta, p.lastRenderX, p.getX());
                double y = MathHelper.lerp(tickDelta, p.lastRenderY, p.getY());
                double z = MathHelper.lerp(tickDelta, p.lastRenderZ, p.getZ());
                if (!ScreenProjector.toGui(x, y + p.getHeight() + 0.25, z, screen)) continue;
                float s = clamp(8.0f / Math.max(screen[2], 1.0f), 0.45f, 0.9f);
                m.pushMatrix();
                m.translate(screen[0], screen[1]);
                m.scale(s, s);
                context.drawItem(shieldIcon, -8, -16);
                m.popMatrix();
            }
        }
    }

    /** "3.4s" without String.format. */
    private static String tenths(long millis) {
        long tenths = (millis + 50L) / 100L;
        return (tenths / 10L) + "." + (tenths % 10L) + "s";
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static final class Disable {
        long start;
        long end;
    }
}
