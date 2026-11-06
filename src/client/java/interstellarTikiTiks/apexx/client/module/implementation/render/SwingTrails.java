package interstellarTikiTiks.apexx.client.module.implementation.render;

import interstellarTikiTiks.apexx.client.mixin.GameRendererAccessor;
import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.ColorSetting;
import interstellarTikiTiks.apexx.client.module.ModeSetting;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import interstellarTikiTiks.apexx.client.util.render.PooledBufferAllocator;
import interstellarTikiTiks.apexx.client.util.render.RibbonRenderer;
import interstellarTikiTiks.apexx.client.util.render.SwingTipModel;
import interstellarTikiTiks.apexx.client.util.render.ThemeColors;
import interstellarTikiTiks.apexx.client.util.render.TrailBuffer;
import interstellarTikiTiks.apexx.client.util.render.TrailMath;
import interstellarTikiTiks.apexx.client.util.render.types.RenderTypes;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.BufferAllocator;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.util.Arm;
import net.minecraft.util.Hand;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.awt.Color;

/**
 * A fading arc behind the tip of your held item while it swings, in first and third person. The first
 * person arc is kept in view space so it moves with the hand, like the hand itself. Client-side visual only.
 */
public final class SwingTrails extends Module {
    private static final String WHEN = "When";
    private static final String LOOK = "Look";
    private static final String COLOR = "Color";
    private static final int CAPACITY = 96;

    private final BooleanSetting firstPerson = new BooleanSetting("First Person", true)
            .setDescription("Show the arc in first person.")
            .setSection(WHEN);
    private final BooleanSetting thirdPerson = new BooleanSetting("Third Person", true)
            .setDescription("Show the arc on your player model in third person.")
            .setSection(WHEN);
    private final BooleanSetting weaponsOnly = new BooleanSetting("Weapons Only", true)
            .setDescription("Only for swords, axes, spears, maces and tridents; off shows it for any held item.")
            .setSection(WHEN);

    private final SliderSetting width = new SliderSetting("Width", 0.5, 0.1, 1.0, 2)
            .setDescription("How much of the blade, from the tip down, the arc covers.")
            .setSection(LOOK);
    private final SliderSetting length = new SliderSetting("Length", 200, 60, 600, 0)
            .setDescription("Milliseconds the arc lingers behind the tip.")
            .setSection(LOOK);
    private final SliderSetting opacity = new SliderSetting("Opacity", 0.75, 0.1, 1.0, 2)
            .setDescription("Opacity of the arc at the tip.")
            .setSection(LOOK);

    private final ModeSetting colorMode = new ModeSetting("Color", ThemeColors.THEME, ThemeColors.THEME,
            ThemeColors.CUSTOM, ThemeColors.RAINBOW, ThemeColors.GRADIENT)
            .setDescription("Theme follows the GUI accent; Gradient blends the accent at the tip into the Gradient End colour.")
            .setSection(COLOR);
    private final ColorSetting customColor = new ColorSetting("Custom Color", new Color(160, 220, 255))
            .setDescription("Arc colour when Color is Custom.")
            .setDependsOn("Color:Custom")
            .setSection(COLOR);
    private final ColorSetting gradientEnd = new ColorSetting("Gradient End", new Color(255, 120, 210))
            .setDescription("Colour at the tail of the arc when Color is Gradient.")
            .setDependsOn("Color:Gradient")
            .setSection(COLOR);

    private final TrailBuffer tips = new TrailBuffer(CAPACITY);
    private final TrailBuffer inners = new TrailBuffer(CAPACITY);
    private final Matrix4f scratch = new Matrix4f();
    private final Vector3f tmp = new Vector3f();
    private final Vector3f tmp2 = new Vector3f();
    private boolean storedFirstPerson;

    public SwingTrails() {
        super("SwingTrails", "Draws a fading arc behind the tip of your held item while you swing it, in first and "
                + "third person. Visible only to you.", Category.RENDER);
        addSetting(firstPerson);
        addSetting(thirdPerson);
        addSetting(weaponsOnly);
        addSetting(width);
        addSetting(length);
        addSetting(opacity);
        addSetting(colorMode);
        addSetting(customColor);
        addSetting(gradientEnd);
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
        tips.clear();
        inners.clear();
    }

    private static boolean isWeapon(ItemStack stack) {
        return stack.isIn(ItemTags.SWORDS) || stack.isIn(ItemTags.AXES) || stack.isIn(ItemTags.SPEARS)
                || stack.isOf(Items.MACE) || stack.isOf(Items.TRIDENT);
    }

    @Override
    public void onWorldRender(MatrixStack matrices, float tickDelta) {
        if (mc.player == null || mc.world == null || mc.gameRenderer == null) return;
        boolean fp = mc.options.getPerspective().isFirstPerson();
        if (fp != storedFirstPerson) {
            clear();
            storedFirstPerson = fp;
        }
        long now = System.currentTimeMillis();
        long life = Math.max(20L, Math.round(length.getValue()));
        tips.pruneOlderThan(now - life);
        inners.pruneOlderThan(now - life);

        sample(fp, tickDelta, now);
        if (tips.size() < 2) return;

        Camera camera = mc.gameRenderer.getCamera();
        Vec3d cam = camera.getCameraPos();
        Quaternionf rotation = camera.getRotation();
        float fovScale = 1.0f;
        if (fp) {
            GameRendererAccessor accessor = (GameRendererAccessor) mc.gameRenderer;
            fovScale = SwingTipModel.fovCorrection(accessor.callGetFov(camera, tickDelta, false),
                    accessor.callGetFov(camera, tickDelta, true));
        }

        String mode = colorMode.getCurrentMode();
        int head = ThemeColors.resolve(mode, customColor.getValue(), 0.0f);
        int tail = ThemeColors.opaque(gradientEnd.getValue());
        boolean rainbow = ThemeColors.RAINBOW.equals(mode);
        boolean gradient = ThemeColors.GRADIENT.equals(mode);
        float maxAlpha = (float) opacity.getValue();
        int n = tips.size();

        try (BufferAllocator allocator = PooledBufferAllocator.acquire(Math.max(2048, n * 6 * 16))) {
            VertexConsumerProvider.Immediate immediate = VertexConsumerProvider.immediate(allocator);
            VertexConsumer buffer = immediate.getBuffer(fp ? RenderTypes.TRIANGLES_NO_DEPTH : RenderTypes.TRIANGLES);
            Matrix4f matrix = matrices.peek().getPositionMatrix();
            float ptx = 0, pty = 0, ptz = 0, pix = 0, piy = 0, piz = 0;
            int pTip = 0, pInner = 0;
            for (int i = 0; i < n; i++) {
                position(tips, i, fp, fovScale, rotation, cam, tmp);
                position(inners, i, fp, fovScale, rotation, cam, tmp2);
                float headT = (float) i / (float) (n - 1);
                float fade = TrailMath.fade(now - tips.time(i), life) * headT;
                int c;
                if (rainbow) c = ThemeColors.resolve(ThemeColors.RAINBOW, null, headT * 0.3f);
                else if (gradient) c = TrailMath.lerpArgb(tail, head, headT);
                else c = head;
                int tipColor = TrailMath.withAlpha(TrailMath.brighten(c, 0.2f), maxAlpha * fade);
                int innerColor = TrailMath.withAlpha(c, maxAlpha * fade * 0.12f);
                if (i > 0) {
                    RibbonRenderer.quad(buffer, matrix,
                            ptx, pty, ptz, pTip,
                            pix, piy, piz, pInner,
                            tmp2.x, tmp2.y, tmp2.z, innerColor,
                            tmp.x, tmp.y, tmp.z, tipColor);
                }
                ptx = tmp.x; pty = tmp.y; ptz = tmp.z;
                pix = tmp2.x; piy = tmp2.y; piz = tmp2.z;
                pTip = tipColor;
                pInner = innerColor;
            }
            immediate.draw();
        }
    }

    /** Camera-relative position of stored point {@code i}. */
    private static void position(TrailBuffer buffer, int i, boolean fp, float fovScale, Quaternionf rotation,
                                 Vec3d cam, Vector3f out) {
        if (fp) {
            out.set((float) buffer.x(i) * fovScale, (float) buffer.y(i) * fovScale, (float) buffer.z(i));
            rotation.transform(out);
        } else {
            out.set((float) (buffer.x(i) - cam.x), (float) (buffer.y(i) - cam.y), (float) (buffer.z(i) - cam.z));
        }
    }

    private void sample(boolean fp, float tickDelta, long now) {
        if (fp ? !firstPerson.isEnabled() : !thirdPerson.isEnabled()) return;
        if (fp && mc.options.hudHidden) return;
        if (!mc.player.handSwinging) return;
        float swing = mc.player.getHandSwingProgress(tickDelta);
        if (swing <= 0.0f) return;
        Hand hand = mc.player.preferredHand == null ? Hand.MAIN_HAND : mc.player.preferredHand;
        ItemStack stack = mc.player.getStackInHand(hand);
        if (stack.isEmpty() || (weaponsOnly.isEnabled() && !isWeapon(stack))) return;
        boolean rightArm = (hand == Hand.MAIN_HAND) == (mc.player.getMainArm() == Arm.RIGHT);
        float innerT = 1.0f - (float) width.getValue();

        if (fp) {
            SwingTipModel.firstPerson(swing, rightArm, 1.0f, scratch, tmp);
            SwingTipModel.firstPerson(swing, rightArm, innerT, scratch, tmp2);
            if (tips.distanceSqToNewest(tmp.x, tmp.y, tmp.z) < 1.0e-6) return;
            tips.add(tmp.x, tmp.y, tmp.z, now);
            inners.add(tmp2.x, tmp2.y, tmp2.z, now);
            return;
        }
        if (mc.player.isGliding() || mc.player.isSwimming() || mc.player.isSleeping()) return;
        float pitch = mc.player.getPitch(tickDelta) * MathHelper.RADIANS_PER_DEGREE;
        boolean sneaking = mc.player.isInSneakingPose();
        SwingTipModel.thirdPerson(swing, rightArm, pitch, sneaking, 1.0f, scratch, tmp);
        SwingTipModel.thirdPerson(swing, rightArm, pitch, sneaking, innerT, scratch, tmp2);
        float bodyYaw = MathHelper.lerpAngleDegrees(tickDelta, mc.player.lastBodyYaw, mc.player.bodyYaw)
                * MathHelper.RADIANS_PER_DEGREE;
        double sin = Math.sin(bodyYaw);
        double cos = Math.cos(bodyYaw);
        Vec3d feet = mc.player.getLerpedPos(tickDelta);
        double tx = feet.x - cos * tmp.x - sin * tmp.z;
        double ty = feet.y + tmp.y;
        double tz = feet.z - sin * tmp.x + cos * tmp.z;
        if (tips.distanceSqToNewest(tx, ty, tz) < 1.0e-6) return;
        tips.add(tx, ty, tz, now);
        inners.add(feet.x - cos * tmp2.x - sin * tmp2.z, feet.y + tmp2.y, feet.z - sin * tmp2.x + cos * tmp2.z, now);
    }
}
