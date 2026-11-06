package interstellarTikiTiks.apexx.client.module.implementation.render;

import interstellarTikiTiks.apexx.client.friend.FriendManager;
import interstellarTikiTiks.apexx.client.manager.TargetManager;
import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.ColorSetting;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import interstellarTikiTiks.apexx.client.util.render.ProceduralTextures;
import interstellarTikiTiks.apexx.client.util.render.ProceduralTextures.Blend;
import interstellarTikiTiks.apexx.client.util.render.VisualGeometry;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.BufferAllocator;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.awt.Color;

/**
 * Lock-on marker for the active combat target: a terrain-following ground reticle (ring, sonar ping, rotating tick
 * dial, health arc) plus three gyroscopic comets whose tapered light ribbons orbit the body. A hit triggers a
 * shock ring and speeds the comets up. Everything is smoothly faded in and out when the target changes.
 */
public final class TargetESP extends Module {
    private static final int TRAIL_SAMPLES = 30;
    private static final float TRAIL_STEP = 0.07f;
    private static final int TICKS = 40;
    private static final int ARC_SEGMENTS = 72;
    private static final float[] ORBIT_TILT = {0.0f, 1.02f, -1.02f};
    private static final float[] ORBIT_YAW = {0.0f, 0.0f, 1.5708f};

    private final SliderSetting range = new SliderSetting("Range", 64.0, 8.0, 128.0, 0)
            .setDescription("Farthest distance in blocks at which the current target is marked.");
    private final SliderSetting opacity = new SliderSetting("Opacity", 0.80, 0.1, 1.0, 2)
            .setDescription("How opaque the whole marker is.");
    private final SliderSetting ringSpeed = new SliderSetting("Ring Speed", 1.0, 0.1, 3.0, 1)
            .setDescription("How fast the ground dial turns and the comets orbit.");
    private final ColorSetting color = new ColorSetting("Color", new Color(255, 79, 163))
            .setDescription("Main colour of the marker.");
    private final BooleanSetting comets = new BooleanSetting("Comets", true)
            .setDescription("Three light comets orbiting the target with tapered trails.");
    private final BooleanSetting reticle = new BooleanSetting("Ground Reticle", true)
            .setDescription("A ring on the ground under the target that follows the terrain.");
    private final BooleanSetting healthArc = new BooleanSetting("Health Arc", true)
            .setDescription("An arc on the ground ring showing how much health the target has left.");
    private final BooleanSetting tickDial = new BooleanSetting("Tick Dial", true)
            .setDescription("A rotating dial with tick marks on the ground ring.");
    private final BooleanSetting sonarPing = new BooleanSetting("Sonar Ping", true)
            .setDescription("A ring that repeatedly expands outward from under the target.");
    private final BooleanSetting hitShockwave = new BooleanSetting("Hit Shockwave", true)
            .setDescription("A shock ring on the ground every time the target takes a hit.");
    private final BooleanSetting cometCore = new BooleanSetting("Comet Core", true)
            .setDescription("A bright white point at the head of each comet.");
    private final SliderSetting cometTrailWidth = new SliderSetting("Comet Trail Width", 100, 25, 300, 0)
            .setDescription("Thickness of the comet trails in percent of the default.");
    private final BooleanSetting throughWalls = new BooleanSetting("Through Walls", true)
            .setDescription("Keeps the marker visible when the target is behind blocks.");

    private LivingEntity shown;
    private float fade;
    private float groundY = Float.NaN;
    private double lastX, lastY, lastZ;
    private float lastWidth = 0.6f, lastHeight = 1.8f, lastHealth = 1f;
    private float hitBoost;
    private int lastHurt;
    private long lastNanos = -1L;
    private long pulseStart = -1L;

    public TargetESP() {
        super("TargetESP", "Marks the player you are currently fighting (the shared combat target) with a ground reticle, a health arc and orbiting light comets so you never lose track of them. Purely visual.", Category.RENDER);
        addSetting(range);
        addSetting(opacity);
        addSetting(ringSpeed);
        addSetting(color);
        addSetting(comets);
        addSetting(reticle);
        addSetting(healthArc);
        addSetting(tickDial);
        addSetting(sonarPing);
        addSetting(hitShockwave);
        addSetting(cometCore);
        addSetting(cometTrailWidth);
        addSetting(throughWalls);
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
        shown = null;
        fade = 0f;
        groundY = Float.NaN;
        hitBoost = 0f;
        lastNanos = -1L;
        pulseStart = -1L;
        lastHurt = 0;
    }

    @Override
    public void onWorldRender(MatrixStack matrices, float tickDelta) {
        if (mc.world == null || mc.player == null) {
            reset();
            return;
        }
        long now = System.nanoTime();
        float dt = lastNanos < 0 ? 0.016f : MathHelper.clamp((now - lastNanos) / 1.0e9f, 0.001f, 0.1f);
        lastNanos = now;

        LivingEntity target = validTarget(selectTarget());
        if (target != null) {
            if (target != shown) {
                shown = target;
                groundY = Float.NaN;
                lastHurt = target.hurtTime;
                hitBoost = 0f;
            }
            fade = Math.min(1f, fade + dt / 0.16f);
            lastX = MathHelper.lerp(tickDelta, target.lastRenderX, target.getX());
            lastY = MathHelper.lerp(tickDelta, target.lastRenderY, target.getY());
            lastZ = MathHelper.lerp(tickDelta, target.lastRenderZ, target.getZ());
            lastWidth = target.getWidth();
            lastHeight = target.getHeight();
            lastHealth = MathHelper.clamp(target.getHealth() / Math.max(1f, target.getMaxHealth()), 0f, 1f);
            if (target.hurtTime > lastHurt) {
                hitBoost = 1f;
                pulseStart = now;
            }
            lastHurt = target.hurtTime;
        } else {
            fade = Math.max(0f, fade - dt / 0.28f);
            if (fade <= 0f) {
                shown = null;
                return;
            }
        }
        hitBoost = Math.max(0f, hitBoost - dt * 2.4f);
        if (fade <= 0.003f || !ProceduralTextures.ensure()) return;

        Camera camera = mc.gameRenderer.getCamera();
        if (camera == null) return;
        Vec3d cam = camera.getCameraPos();

        double groundTarget = findGround(lastX, lastY, lastZ);
        groundY = Float.isNaN(groundY) ? (float) groundTarget
                : groundY + ((float) groundTarget - groundY) * (1f - (float) Math.exp(-dt * 16f));

        float master = (float) opacity.getValue() * easeInOut(fade);
        Color base = color.getValue() == null ? new Color(255, 79, 163) : color.getValue();
        float time = (System.nanoTime() / 1.0e9f) % 4000f;
        float speed = (float) ringSpeed.getValue();

        Quaternionf camRot = camera.getRotation();
        Vector3f right = new Vector3f(1f, 0f, 0f).rotate(camRot);
        Vector3f up = new Vector3f(0f, 1f, 0f).rotate(camRot);

        Matrix4f m = matrices.peek().getPositionMatrix();
        boolean depth = !throughWalls.isEnabled();
        double cx = lastX - cam.x, cz = lastZ - cam.z;
        double bodyY = (lastY + lastHeight * 0.52) - cam.y;
        double gy = groundY - cam.y + 0.014;
        float reticleR = Math.max(0.62f, lastWidth * 0.95f + 0.34f);

        try (BufferAllocator allocator = interstellarTikiTiks.apexx.client.util.render.PooledBufferAllocator.acquire(1 << 17)) {
            VertexConsumerProvider.Immediate imm = VertexConsumerProvider.immediate(allocator);

            if (reticle.isEnabled()) {
                // Soft ring body: sits under everything else.
                VertexConsumer ringBuf = imm.getBuffer(ProceduralTextures.layer(ProceduralTextures.RING, Blend.ADDITIVE, depth));
                float half = reticleR / 0.8f;
                flatQuad(ringBuf, m, cx, gy, cz, half, 0f, VisualGeometry.color(base, master * 0.85f));
                if (sonarPing.isEnabled()) {
                    float tp = (time / 1.7f) % 1f;
                    float pingR = reticleR * (0.72f + 0.95f * tp) / 0.8f;
                    flatQuad(ringBuf, m, cx, gy, cz, pingR, 0f, VisualGeometry.color(base, master * 0.55f * (float) Math.pow(1f - tp, 1.4)));
                }
                if (hitShockwave.isEnabled() && pulseStart >= 0L) {
                    float age = (System.nanoTime() - pulseStart) / 1.0e9f;
                    if (age < 0.55f) {
                        float k = age / 0.55f;
                        float r = reticleR * (1f + 2.6f * (1f - (1f - k) * (1f - k) * (1f - k))) / 0.8f;
                        flatQuad(ringBuf, m, cx, gy + 0.004, cz, r, 0f, VisualGeometry.color(lighten(base, 0.55f), master * (1f - k) * 0.95f));
                    } else {
                        pulseStart = -1L;
                    }
                }
                imm.draw();

                VertexConsumer bolt = imm.getBuffer(ProceduralTextures.layer(ProceduralTextures.BOLT, Blend.ADDITIVE, depth));
                if (tickDial.isEnabled()) {
                    drawTicks(bolt, m, cx, gy, cz, reticleR * 1.10f, time * 0.45f * speed, base, master);
                }
                if (healthArc.isEnabled()) {
                    drawHealthArc(bolt, m, cx, gy + 0.002, cz, reticleR * 1.30f, lastHealth, master);
                }
                imm.draw();
            }

            if (comets.isEnabled()) {
                double orbitR = Math.max(0.55, lastWidth * 0.78 + 0.32);
                float trailW = (float) (cometTrailWidth.getValue() / 100.0);
                VertexConsumer trail = imm.getBuffer(ProceduralTextures.layer(ProceduralTextures.BOLT, Blend.ADDITIVE, depth));
                double[][] heads = new double[3][3];
                for (int i = 0; i < 3; i++) {
                    float dir = i == 1 ? -1f : 1f;
                    float w = (1.30f + 0.42f * i) * speed * (1f + hitBoost * 1.6f);
                    float theta = time * w * dir + i * 2.09f;
                    double bob = 0.04 * Math.sin(time * 1.3 + i);
                    double[] prev = null;
                    for (int k = 0; k < TRAIL_SAMPLES; k++) {
                        float th = theta - dir * k * TRAIL_STEP * (1f + hitBoost * 0.5f);
                        double[] p = orbitPoint(i, th, orbitR, cx, bodyY + bob, cz, lastHeight);
                        if (k == 0) {
                            heads[i] = p;
                        }
                        if (prev != null) {
                            float t0 = (k - 1) / (float) (TRAIL_SAMPLES - 1), t1 = k / (float) (TRAIL_SAMPLES - 1);
                            float w0 = 0.115f * trailW * (float) Math.pow(1f - t0, 0.85), w1 = 0.115f * trailW * (float) Math.pow(1f - t1, 0.85);
                            float a0 = master * (float) Math.pow(1f - t0, 1.7), a1 = master * (float) Math.pow(1f - t1, 1.7);
                            int c0 = VisualGeometry.color(mixColor(lighten(base, 0.7f), base, t0), a0);
                            int c1 = VisualGeometry.color(mixColor(lighten(base, 0.7f), base, t1), a1);
                            ribbonSegment(trail, m, prev, p, w0, w1, c0, c1);
                        }
                        prev = p;
                    }
                }
                imm.draw();

                VertexConsumer glow = imm.getBuffer(ProceduralTextures.layer(ProceduralTextures.GLOW, Blend.ADDITIVE, depth));
                for (int i = 0; i < 3; i++) {
                    double[] h = heads[i];
                    VisualGeometry.sprite(glow, m, h[0], h[1], h[2], right.x, right.y, right.z, up.x, up.y, up.z,
                            0.26, 0.26, 0f, 0f, 0f, 1f, 1f, VisualGeometry.color(base, master * 0.55f), VisualGeometry.FULL_BRIGHT);
                    if (cometCore.isEnabled()) {
                        VisualGeometry.sprite(glow, m, h[0], h[1], h[2], right.x, right.y, right.z, up.x, up.y, up.z,
                                0.105, 0.105, 0f, 0f, 0f, 1f, 1f, VisualGeometry.color(Color.WHITE, master), VisualGeometry.FULL_BRIGHT);
                    }
                }
                // Faint chest flare that swells on hit.
                float flare = 0.55f + hitBoost * 0.9f;
                VisualGeometry.sprite(glow, m, cx, bodyY, cz, right.x, right.y, right.z, up.x, up.y, up.z,
                        flare, flare, 0f, 0f, 0f, 1f, 1f, VisualGeometry.color(base, master * (0.07f + hitBoost * 0.30f)), VisualGeometry.FULL_BRIGHT);
                imm.draw();
            }
        } catch (Throwable t) {
            // Keep target visuals isolated from the rest of the world render.
            interstellarTikiTiks.apexx.client.util.ClientInternalLog.warn("TargetESP", "render failed", t);
        }
    }

    // ---- target selection -------------------------------------------------------------------------

    private LivingEntity validTarget(LivingEntity target) {
        if (!(target instanceof net.minecraft.entity.player.PlayerEntity)) return null; // players only
        if (target == mc.player || !target.isAlive() || target.isSpectator() || target.isRemoved()) return null;
        if (FriendManager.getInstance().isFriend(target.getName().getString())) return null;
        if (mc.player.squaredDistanceTo(target) > range.getValue() * range.getValue()) return null;
        return target;
    }

    private LivingEntity selectTarget() {
        LivingEntity locked = TargetManager.getLockedTarget();
        if (locked != null && locked.isAlive() && !locked.isRemoved()) return locked;
        if (mc.targetedEntity instanceof LivingEntity living) return living;
        if (mc.crosshairTarget instanceof EntityHitResult hit && hit.getEntity() instanceof LivingEntity living) {
            return living;
        }
        return null;
    }

    private double findGround(double x, double y, double z) {
        Vec3d from = new Vec3d(x, y + 0.4, z);
        Vec3d to = new Vec3d(x, y - 12.0, z);
        HitResult hit = mc.world.raycast(new RaycastContext(from, to, RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE, mc.player));
        return hit.getType() == HitResult.Type.BLOCK ? hit.getPos().y : y;
    }

    // ---- geometry ---------------------------------------------------------------------------------

    /** Flat (horizontal) textured quad of half-extent {@code half} centred on the point, rotated by {@code rot}. */
    private static void flatQuad(VertexConsumer buf, Matrix4f m, double cx, double cy, double cz, float half, float rot, int argb) {
        double c = Math.cos(rot) * half, s = Math.sin(rot) * half;
        VisualGeometry.quad(buf, m,
                cx - c - s, cy, cz + s - c,
                cx + c - s, cy, cz - s - c,
                cx + c + s, cy, cz - s + c,
                cx - c + s, cy, cz + s + c,
                0f, 0f, 1f, 1f, argb, argb, argb, argb, VisualGeometry.FULL_BRIGHT);
    }

    private void drawTicks(VertexConsumer buf, Matrix4f m, double cx, double cy, double cz, float radius, float spin, Color base, float master) {
        for (int i = 0; i < TICKS; i++) {
            float ang = spin + (float) (Math.PI * 2.0 * i / TICKS);
            boolean major = i % 5 == 0;
            float len = major ? 0.13f : 0.065f;
            float thick = major ? 0.020f : 0.012f;
            float a = master * (major ? 0.95f : 0.55f);
            double cos = Math.cos(ang), sin = Math.sin(ang);
            double r0 = radius, r1 = radius + len;
            double tx = -sin * thick, tz = cos * thick;
            int col = VisualGeometry.color(major ? lighten(base, 0.4f) : base, a);
            VisualGeometry.quadCross(buf, m,
                    cx + cos * r0 - tx, cy, cz + sin * r0 - tz,
                    cx + cos * r0 + tx, cy, cz + sin * r0 + tz,
                    cx + cos * r1 + tx, cy, cz + sin * r1 + tz,
                    cx + cos * r1 - tx, cy, cz + sin * r1 - tz,
                    col, col, col, col, VisualGeometry.FULL_BRIGHT);
        }
    }

    private void drawHealthArc(VertexConsumer buf, Matrix4f m, double cx, double cy, double cz, float radius, float fraction, float master) {
        if (fraction <= 0.01f) return;
        int segs = Math.max(2, (int) (ARC_SEGMENTS * fraction));
        float span = (float) (Math.PI * 2.0) * fraction;
        float start = (float) (-Math.PI / 2.0);
        Color hue = Color.getHSBColor(0.33f * fraction, 0.78f, 1f);
        double half = 0.028;
        for (int i = 0; i < segs; i++) {
            float a0 = start + span * i / segs, a1 = start + span * (i + 1) / segs;
            double c0 = Math.cos(a0), s0 = Math.sin(a0), c1 = Math.cos(a1), s1 = Math.sin(a1);
            float edge = i < 3 ? (i + 1) / 4f : (i > segs - 4 ? (segs - i) / 4f : 1f);
            int col = VisualGeometry.color(hue, master * 0.9f * Math.min(1f, edge + 0.15f));
            VisualGeometry.quadCross(buf, m,
                    cx + c0 * (radius - half), cy, cz + s0 * (radius - half),
                    cx + c0 * (radius + half), cy, cz + s0 * (radius + half),
                    cx + c1 * (radius + half), cy, cz + s1 * (radius + half),
                    cx + c1 * (radius - half), cy, cz + s1 * (radius - half),
                    col, col, col, col, VisualGeometry.FULL_BRIGHT);
        }
    }

    /** Point on gyroscopic orbit {@code i} at angle {@code theta}, camera-relative. */
    private static double[] orbitPoint(int i, float theta, double radius, double cx, double cy, double cz, float height) {
        double cos = Math.cos(theta), sin = Math.sin(theta);
        double yawC = Math.cos(ORBIT_YAW[i]), yawS = Math.sin(ORBIT_YAW[i]);
        double tiltC = Math.cos(ORBIT_TILT[i]), tiltS = Math.sin(ORBIT_TILT[i]);
        // Base circle in the XZ plane, rotated about X by the tilt and about Y by the yaw offset.
        double x0 = cos * radius, z0 = sin * radius;
        double y1 = -z0 * tiltS, z1 = z0 * tiltC;
        double x2 = x0 * yawC + z1 * yawS, z2 = -x0 * yawS + z1 * yawC;
        return new double[]{cx + x2, cy + y1 * Math.min(1.0, height * 0.5 / Math.max(0.1, radius)), cz + z2};
    }

    /** Camera-facing ribbon segment between two points with per-end width and colour. */
    private void ribbonSegment(VertexConsumer buf, Matrix4f m, double[] p0, double[] p1, float w0, float w1, int c0, int c1) {
        double tx = p0[0] - p1[0], ty = p0[1] - p1[1], tz = p0[2] - p1[2];
        // View direction is toward the camera at the origin.
        double vx = -(p0[0] + p1[0]) * 0.5, vy = -(p0[1] + p1[1]) * 0.5, vz = -(p0[2] + p1[2]) * 0.5;
        double sx = ty * vz - tz * vy, sy = tz * vx - tx * vz, sz = tx * vy - ty * vx;
        double sl = Math.sqrt(sx * sx + sy * sy + sz * sz);
        if (sl < 1e-9) return;
        sx /= sl; sy /= sl; sz /= sl;
        VisualGeometry.quadCross(buf, m,
                p0[0] + sx * w0, p0[1] + sy * w0, p0[2] + sz * w0,
                p0[0] - sx * w0, p0[1] - sy * w0, p0[2] - sz * w0,
                p1[0] - sx * w1, p1[1] - sy * w1, p1[2] - sz * w1,
                p1[0] + sx * w1, p1[1] + sy * w1, p1[2] + sz * w1,
                c0, c0, c1, c1, VisualGeometry.FULL_BRIGHT);
    }

    private static float easeInOut(float t) {
        t = MathHelper.clamp(t, 0f, 1f);
        return t * t * (3f - 2f * t);
    }

    private static Color lighten(Color c, float k) {
        return new Color(
                MathHelper.clamp((int) (c.getRed() + (255 - c.getRed()) * k), 0, 255),
                MathHelper.clamp((int) (c.getGreen() + (255 - c.getGreen()) * k), 0, 255),
                MathHelper.clamp((int) (c.getBlue() + (255 - c.getBlue()) * k), 0, 255));
    }

    private static Color mixColor(Color a, Color b, float t) {
        t = MathHelper.clamp(t, 0f, 1f);
        return new Color(
                (int) (a.getRed() + (b.getRed() - a.getRed()) * t),
                (int) (a.getGreen() + (b.getGreen() - a.getGreen()) * t),
                (int) (a.getBlue() + (b.getBlue() - a.getBlue()) * t));
    }
}
