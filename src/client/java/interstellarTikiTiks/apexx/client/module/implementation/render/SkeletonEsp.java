package interstellarTikiTiks.apexx.client.module.implementation.render;

import interstellarTikiTiks.apexx.client.friend.FriendManager;
import interstellarTikiTiks.apexx.client.mixin.GameRendererAccessor;
import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.ColorSetting;
import interstellarTikiTiks.apexx.client.module.ModeSetting;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import interstellarTikiTiks.apexx.client.module.hooks.EntityPoseAware;
import interstellarTikiTiks.apexx.client.module.implementation.player.BotFilterProvider;
import interstellarTikiTiks.apexx.client.theme.AccentState;
import interstellarTikiTiks.apexx.client.util.ModuleAccess;
import interstellarTikiTiks.apexx.client.util.render.PooledBufferAllocator;
import interstellarTikiTiks.apexx.client.util.render.ScreenProjector;
import interstellarTikiTiks.apexx.client.util.render.types.RenderTypes;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.model.BipedEntityModel;
import net.minecraft.client.render.entity.model.EntityModel;
import net.minecraft.client.render.entity.state.LivingEntityRenderState;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.client.util.BufferAllocator;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.awt.Color;
import java.util.ArrayDeque;
import java.util.List;

/**
 * Skeleton ESP: bone lines through walls that follow each player's actual model pose.
 *
 * <p>Joint positions come from the renderer itself ({@link EntityPoseAware} at the end of
 * {@code LivingEntityRenderer.render}): the biped model's parts are posed for that render state and
 * each joint is pushed through the part's own transform and the entity's final pose matrix, so
 * sneaking, swinging, gliding and crawling all match the drawn model. The lines are drawn as
 * camera-facing quads with a constant on-screen thickness.</p>
 */
public final class SkeletonEsp extends Module implements EntityPoseAware {
    private static final long POSE_MAX_AGE_NS = 150_000_000L;
    private static final int JOINTS = 12;

    // Joint indices.
    private static final int HEAD_TOP = 0, NECK = 1, PELVIS = 2, R_SHOULDER = 3, R_HAND = 4, L_SHOULDER = 5,
            L_HAND = 6, R_HIP = 7, R_FOOT = 8, L_HIP = 9, L_FOOT = 10, HIP_MID = 11;
    private static final int[] BONES = {
            HEAD_TOP, NECK,
            NECK, PELVIS,
            NECK, R_SHOULDER,
            NECK, L_SHOULDER,
            R_SHOULDER, R_HAND,
            L_SHOULDER, L_HAND,
            PELVIS, HIP_MID,
            R_HIP, L_HIP,
            R_HIP, R_FOOT,
            L_HIP, L_FOOT
    };

    private final SliderSetting range = new SliderSetting("Range", 96, 8, 256, 0)
            .setDescription("Farthest player, in blocks, that gets a skeleton.");
    private final SliderSetting thickness = new SliderSetting("Thickness", 1.5, 0.5, 5.0, 1)
            .setDescription("Line width in GUI pixels; it stays the same on screen at any distance.");
    private final BooleanSetting throughWalls = new BooleanSetting("Through Walls", true)
            .setDescription("Draws skeletons over terrain so you can see poses behind walls.");
    private final ModeSetting colorMode = new ModeSetting("Color Mode", "Theme", "Theme", "Custom")
            .setDescription("Theme uses the ClickGUI accent; Custom uses Custom Color.");
    private final ColorSetting customColor = new ColorSetting("Custom Color", new Color(235, 235, 245))
            .setDescription("Skeleton colour when Color Mode is Custom.")
            .setDependsOn("Color Mode:Custom");
    private final SliderSetting opacity = new SliderSetting("Opacity", 0.9, 0.1, 1.0, 2)
            .setDescription("Line opacity.");
    private final BooleanSetting friends = new BooleanSetting("Friends", true)
            .setDescription("Includes friends, drawn in Friend Color.");
    private final ColorSetting friendColor = new ColorSetting("Friend Color", new Color(85, 255, 140))
            .setDescription("Skeleton colour for friends.")
            .setDependsOn("Friends");
    private final BooleanSetting self = new BooleanSetting("Self", false)
            .setDescription("Also draws your own skeleton in third person.");

    /** Render thread only. */
    private final Int2ObjectOpenHashMap<Pose> poses = new Int2ObjectOpenHashMap<>();
    private final ArrayDeque<Pose> pool = new ArrayDeque<>();
    private final Vector3f scratch = new Vector3f();

    public SkeletonEsp() {
        super("SkeletonESP", "Draws a bone skeleton over every player that follows their real animation (arms, legs, "
                + "head, sneaking), visible through walls. Visible only to you.", Category.RENDER);
        addSetting(range);
        addSetting(thickness);
        addSetting(throughWalls);
        addSetting(colorMode);
        addSetting(customColor);
        addSetting(opacity);
        addSetting(friends);
        addSetting(friendColor);
        addSetting(self);
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
        for (Pose p : poses.values()) pool.push(p);
        poses.clear();
    }

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public void onEntityPose(LivingEntityRenderState state, EntityModel<?> model, MatrixStack matrices) {
        if (!(state instanceof PlayerEntityRenderState ps) || !(model instanceof BipedEntityModel<?> biped)) return;
        if (mc.player == null || mc.world == null || ps.invisible) return;
        boolean isSelf = ps.id == mc.player.getId();
        if (isSelf && !self.isEnabled()) return;
        double maxSq = range.getValue() * range.getValue();
        if (!isSelf && ps.squaredDistanceToCamera > maxSq) return;

        int color;
        Entity entity = mc.world.getEntityById(ps.id);
        boolean friend = entity instanceof PlayerEntity p && FriendManager.getInstance().isFriend(p.getUuid());
        if (friend && !friends.isEnabled()) return;
        if (!isSelf && entity != null && isBot(entity)) return;
        if (friend) {
            color = friendColor.getValue().getRGB();
        } else {
            color = colorMode.is("Custom") ? customColor.getValue().getRGB() : AccentState.accentArgb();
        }

        // The command queue applies part angles lazily at draw time; pose the parts for THIS state now.
        ((EntityModel) model).setAngles(state);
        Vec3d cam = mc.gameRenderer.getCamera().getCameraPos();

        Pose pose = poses.get(ps.id);
        if (pose == null) {
            pose = pool.isEmpty() ? new Pose() : pool.pop();
            poses.put(ps.id, pose);
        }
        pose.color = color;
        pose.timeNs = System.nanoTime();

        ModelPart root = model.getRootPart();
        matrices.push();
        try {
            root.applyTransform(matrices);
            joint(pose, HEAD_TOP, matrices, biped.head, 0, -8, 0, cam);
            joint(pose, NECK, matrices, biped.head, 0, 0, 0, cam);
            joint(pose, PELVIS, matrices, biped.body, 0, 12, 0, cam);
            joint(pose, R_SHOULDER, matrices, biped.rightArm, -1, -2, 0, cam);
            joint(pose, R_HAND, matrices, biped.rightArm, -1, 10, 0, cam);
            joint(pose, L_SHOULDER, matrices, biped.leftArm, 1, -2, 0, cam);
            joint(pose, L_HAND, matrices, biped.leftArm, 1, 10, 0, cam);
            joint(pose, R_HIP, matrices, biped.rightLeg, 0, 0, 0, cam);
            joint(pose, R_FOOT, matrices, biped.rightLeg, 0, 12, 0, cam);
            joint(pose, L_HIP, matrices, biped.leftLeg, 0, 0, 0, cam);
            joint(pose, L_FOOT, matrices, biped.leftLeg, 0, 12, 0, cam);
        } finally {
            matrices.pop();
        }
        float[] j = pose.joints;
        for (int a = 0; a < 3; a++) j[HIP_MID * 3 + a] = (j[R_HIP * 3 + a] + j[L_HIP * 3 + a]) * 0.5f;
    }

    /** Joint = point (model pixels) in {@code part}'s space, through the entity pose, stored as world - camera-at-capture + camera. */
    private void joint(Pose pose, int index, MatrixStack matrices, ModelPart part, float x, float y, float z, Vec3d cam) {
        matrices.push();
        part.applyTransform(matrices);
        Matrix4f m = matrices.peek().getPositionMatrix();
        m.transformPosition(x / 16.0f, y / 16.0f, z / 16.0f, scratch);
        matrices.pop();
        int o = index * 3;
        // Keep camera-relative floats for precision, plus the capture camera for re-basing at draw time.
        pose.joints[o] = scratch.x;
        pose.joints[o + 1] = scratch.y;
        pose.joints[o + 2] = scratch.z;
        pose.camX = cam.x;
        pose.camY = cam.y;
        pose.camZ = cam.z;
    }

    @Override
    public void onWorldRender(MatrixStack matrices, float tickDelta) {
        if (poses.isEmpty() || mc.world == null || mc.player == null) return;
        Camera camera = mc.gameRenderer.getCamera();
        Vec3d cam = camera.getCameraPos();
        long now = System.nanoTime();
        float fov;
        try {
            fov = ((GameRendererAccessor) mc.gameRenderer).callGetFov(camera, tickDelta, true);
        } catch (Throwable t) {
            fov = 70.0f;
        }
        int fbHeight = Math.max(1, mc.getWindow().getFramebufferHeight());
        float pxWidth = (float) thickness.getValue() * Math.max(1, mc.getWindow().getScaleFactor());
        float alpha = (float) opacity.getValue();

        try (BufferAllocator allocator = PooledBufferAllocator.acquire(8192)) {
            VertexConsumerProvider.Immediate immediate = VertexConsumerProvider.immediate(allocator);
            VertexConsumer buffer = immediate.getBuffer(throughWalls.isEnabled() ? RenderTypes.TRIANGLES_NO_DEPTH : RenderTypes.TRIANGLES);
            Matrix4f matrix = matrices.peek().getPositionMatrix();
            ObjectIterator<Int2ObjectMap.Entry<Pose>> it = poses.int2ObjectEntrySet().fastIterator();
            while (it.hasNext()) {
                Pose pose = it.next().getValue();
                if (now - pose.timeNs > POSE_MAX_AGE_NS) {
                    pool.push(pose);
                    it.remove();
                    continue;
                }
                float ox = (float) (pose.camX - cam.x);
                float oy = (float) (pose.camY - cam.y);
                float oz = (float) (pose.camZ - cam.z);
                float r = ((pose.color >> 16) & 0xFF) / 255.0f;
                float g = ((pose.color >> 8) & 0xFF) / 255.0f;
                float b = (pose.color & 0xFF) / 255.0f;
                float[] j = pose.joints;
                for (int i = 0; i < BONES.length; i += 2) {
                    int a = BONES[i] * 3;
                    int c = BONES[i + 1] * 3;
                    segment(buffer, matrix, j[a] + ox, j[a + 1] + oy, j[a + 2] + oz,
                            j[c] + ox, j[c + 1] + oy, j[c + 2] + oz, pxWidth, fov, fbHeight, r, g, b, alpha);
                }
            }
            immediate.draw();
        }
    }

    /** Camera-facing quad from a to b (camera-relative), {@code px} screen pixels wide. */
    private static void segment(VertexConsumer buf, Matrix4f m, float ax, float ay, float az, float bx, float by, float bz,
                                float px, float fov, int fbHeight, float r, float g, float b, float alpha) {
        float dx = bx - ax, dy = by - ay, dz = bz - az;
        float mx = (ax + bx) * 0.5f, my = (ay + by) * 0.5f, mz = (az + bz) * 0.5f;
        // side = normalize(d x toCamera); toCamera = -mid
        float sx = dy * -mz - dz * -my;
        float sy = dz * -mx - dx * -mz;
        float sz = dx * -my - dy * -mx;
        float len = (float) Math.sqrt(sx * sx + sy * sy + sz * sz);
        if (len < 1.0e-6f) return;
        double dist = Math.sqrt(mx * mx + my * my + mz * mz);
        float half = (float) (ScreenProjector.worldPerPixel(dist, fov, fbHeight) * px * 0.5);
        sx = sx / len * half;
        sy = sy / len * half;
        sz = sz / len * half;
        buf.vertex(m, ax - sx, ay - sy, az - sz).color(r, g, b, alpha);
        buf.vertex(m, ax + sx, ay + sy, az + sz).color(r, g, b, alpha);
        buf.vertex(m, bx + sx, by + sy, bz + sz).color(r, g, b, alpha);
        buf.vertex(m, ax - sx, ay - sy, az - sz).color(r, g, b, alpha);
        buf.vertex(m, bx + sx, by + sy, bz + sz).color(r, g, b, alpha);
        buf.vertex(m, bx - sx, by - sy, bz - sz).color(r, g, b, alpha);
    }

    private static boolean isBot(Entity entity) {
        List<BotFilterProvider> filters = ModuleAccess.getEnabledHooks(BotFilterProvider.class);
        for (int i = 0; i < filters.size(); i++) {
            try {
                if (filters.get(i).isBot(entity)) return true;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    private static final class Pose {
        final float[] joints = new float[JOINTS * 3];
        double camX, camY, camZ;
        int color;
        long timeNs;
    }
}
