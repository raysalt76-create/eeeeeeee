package interstellarTikiTiks.apexx.client.module.implementation.render;

import interstellarTikiTiks.apexx.client.gui.overlay.OverlayCompositor;
import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.ColorSetting;
import interstellarTikiTiks.apexx.client.module.ModeSetting;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import interstellarTikiTiks.apexx.client.module.hooks.BlockOutlineProvider;
import interstellarTikiTiks.apexx.client.util.render.PooledBufferAllocator;
import interstellarTikiTiks.apexx.client.util.render.RibbonRenderer;
import interstellarTikiTiks.apexx.client.util.render.ThemeColors;
import interstellarTikiTiks.apexx.client.util.render.TrailMath;
import interstellarTikiTiks.apexx.client.util.render.types.RenderTypes;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.state.OutlineRenderState;
import net.minecraft.client.util.BufferAllocator;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import org.joml.Matrix4f;

import java.awt.Color;
import java.util.Arrays;

/**
 * Replaces the vanilla block selection outline with a thick, coloured one (optional fill, pulse, rainbow or
 * gradient). It draws exactly what vanilla would have outlined: the target and shape are taken from the
 * outline vanilla was about to draw, so vanilla's own rules (HUD hidden, adventure mode, ...) still apply.
 * While SS Proof is on the vanilla outline is kept, so a capture of the game window still shows one.
 */
public final class BlockOutline extends Module implements BlockOutlineProvider {
    private static final String LOOK = "Look";
    private static final String COLOR = "Color";
    /** A capture older than this means the hook is not running; fall back to the crosshair target. */
    private static final long CAPTURE_STALE_NANOS = 250_000_000L;
    private static final float EXPAND = 0.002f;

    private final SliderSetting thickness = new SliderSetting("Thickness", 2.0, 0.5, 6.0, 1)
            .setDescription("Outline thickness, roughly in pixels; it stays the same at any distance.")
            .setSection(LOOK);
    private final SliderSetting outlineOpacity = new SliderSetting("Outline Opacity", 1.0, 0.1, 1.0, 2)
            .setDescription("Opacity of the outline.")
            .setSection(LOOK);
    private final BooleanSetting fill = new BooleanSetting("Fill", true)
            .setDescription("Fills the block's faces with a translucent tint.")
            .setSection(LOOK);
    private final SliderSetting fillOpacity = new SliderSetting("Fill Opacity", 0.15, 0.02, 0.6, 2)
            .setDescription("Opacity of the fill.")
            .setDependsOn("Fill")
            .setSection(LOOK);
    private final BooleanSetting pulse = new BooleanSetting("Pulse", false)
            .setDescription("Gently pulses the outline and fill opacity.")
            .setSection(LOOK);
    private final SliderSetting pulseSpeed = new SliderSetting("Pulse Speed", 1.0, 0.2, 4.0, 1)
            .setDescription("Pulses per second.")
            .setDependsOn("Pulse")
            .setSection(LOOK);
    private final BooleanSetting throughWalls = new BooleanSetting("Through Walls", false)
            .setDescription("Draws the outline over blocks in front of it, like vanilla never does.")
            .setSection(LOOK);

    private final ModeSetting colorMode = new ModeSetting("Color", ThemeColors.THEME, ThemeColors.THEME,
            ThemeColors.CUSTOM, ThemeColors.RAINBOW, ThemeColors.GRADIENT)
            .setDescription("Theme follows the GUI accent; Gradient blends the accent at the bottom into Gradient End at the top.")
            .setSection(COLOR);
    private final ColorSetting customColor = new ColorSetting("Custom Color", new Color(120, 200, 255))
            .setDescription("Outline colour when Color is Custom.")
            .setDependsOn("Color:Custom")
            .setSection(COLOR);
    private final ColorSetting gradientEnd = new ColorSetting("Gradient End", new Color(255, 110, 200))
            .setDescription("Top colour when Color is Gradient.")
            .setDependsOn("Color:Gradient")
            .setSection(COLOR);
    private final SliderSetting rainbowSpeed = new SliderSetting("Rainbow Speed", 1.0, 0.1, 4.0, 1)
            .setDescription("How fast the rainbow cycles.")
            .setDependsOn("Color:Rainbow")
            .setSection(COLOR);

    // Last outline vanilla was about to draw (render thread).
    private BlockPos capturedPos;
    private VoxelShape capturedShape;
    private long capturedAtNanos;

    // Edge / box cache for the current shape, in block-local coordinates.
    private VoxelShape cachedShape;
    private float[] edges = new float[0];
    private int edgeCount;
    private float[] boxes = new float[0];
    private int boxCount;
    private float shapeMinY;
    private float shapeMaxY = 1.0f;

    public BlockOutline() {
        super("BlockOutline", "Replaces the vanilla block selection outline with a thicker coloured one, with "
                + "optional fill, pulse, rainbow or gradient. Visible only to you.", Category.RENDER);
        addSetting(thickness);
        addSetting(outlineOpacity);
        addSetting(fill);
        addSetting(fillOpacity);
        addSetting(pulse);
        addSetting(pulseSpeed);
        addSetting(throughWalls);
        addSetting(colorMode);
        addSetting(customColor);
        addSetting(gradientEnd);
        addSetting(rainbowSpeed);
    }

    @Override
    public boolean rendersInWorld() {
        return true;
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
        capturedPos = null;
        capturedShape = null;
        capturedAtNanos = 0L;
        cachedShape = null;
        edgeCount = 0;
        boxCount = 0;
    }

    @Override
    public void captureVanillaOutline(OutlineRenderState state) {
        capturedAtNanos = System.nanoTime();
        if (state == null || state.shape() == null || state.shape().isEmpty()) {
            capturedPos = null;
            capturedShape = null;
        } else {
            capturedPos = state.pos();
            capturedShape = state.shape();
        }
    }

    @Override
    public boolean cancelVanillaOutline() {
        // Under SS Proof our outline goes to the overlay; keep vanilla's so the captured game frame still has one.
        return !OverlayCompositor.isSsProofOn();
    }

    @Override
    public void onWorldRender(MatrixStack matrices, float tickDelta) {
        if (mc.player == null || mc.world == null || mc.gameRenderer == null) return;
        BlockPos pos;
        VoxelShape shape;
        if (System.nanoTime() - capturedAtNanos <= CAPTURE_STALE_NANOS) {
            pos = capturedPos;
            shape = capturedShape;
        } else {
            // Hook not running (another mod owns the outline call): follow the crosshair target instead.
            pos = null;
            shape = null;
            HitResult hit = mc.crosshairTarget;
            if (!mc.options.hudHidden && hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK) {
                BlockState state = mc.world.getBlockState(blockHit.getBlockPos());
                if (!state.isAir() && mc.world.getWorldBorder().contains(blockHit.getBlockPos())) {
                    Entity viewer = mc.getCameraEntity() != null ? mc.getCameraEntity() : mc.player;
                    pos = blockHit.getBlockPos();
                    shape = state.getOutlineShape(mc.world, pos, ShapeContext.of(viewer));
                }
            }
        }
        if (pos == null || shape == null || shape.isEmpty()) return;
        rebuildCache(shape);
        if (edgeCount == 0) return;

        Vec3d cam = mc.gameRenderer.getCamera().getCameraPos();
        float ox = (float) (pos.getX() - cam.x);
        float oy = (float) (pos.getY() - cam.y);
        float oz = (float) (pos.getZ() - cam.z);
        double time = ThemeColors.seconds();
        float pulseFactor = pulse.isEnabled() ? 0.45f + 0.55f * TrailMath.pulse(time, pulseSpeed.getValue()) : 1.0f;
        float lineAlpha = (float) outlineOpacity.getValue() * pulseFactor;
        float fillAlpha = (float) fillOpacity.getValue() * pulseFactor;
        float lineThickness = (float) thickness.getValue();

        String mode = colorMode.getCurrentMode();
        int primary = ThemeColors.resolve(ThemeColors.RAINBOW.equals(mode) ? ThemeColors.THEME : mode, customColor.getValue(), 0.0f);
        int secondary = ThemeColors.opaque(gradientEnd.getValue());
        boolean rainbow = ThemeColors.RAINBOW.equals(mode);
        boolean gradient = ThemeColors.GRADIENT.equals(mode);
        float hueBase = (float) (time * 0.2 * rainbowSpeed.getValue());

        try (BufferAllocator allocator = PooledBufferAllocator.acquire(8192)) {
            VertexConsumerProvider.Immediate immediate = VertexConsumerProvider.immediate(allocator);
            VertexConsumer buffer = immediate.getBuffer(throughWalls.isEnabled() ? RenderTypes.TRIANGLES_NO_DEPTH : RenderTypes.TRIANGLES);
            Matrix4f matrix = matrices.peek().getPositionMatrix();
            if (fill.isEnabled()) {
                for (int i = 0; i < boxCount; i++) {
                    int o = i * 6;
                    int bottom = colorAt(primary, secondary, rainbow, gradient, hueBase, boxes[o], boxes[o + 1], boxes[o + 2]);
                    int top = colorAt(primary, secondary, rainbow, gradient, hueBase, boxes[o + 3], boxes[o + 4], boxes[o + 5]);
                    RibbonRenderer.boxFill(buffer, matrix,
                            ox + boxes[o] - EXPAND, oy + boxes[o + 1] - EXPAND, oz + boxes[o + 2] - EXPAND,
                            ox + boxes[o + 3] + EXPAND, oy + boxes[o + 4] + EXPAND, oz + boxes[o + 5] + EXPAND,
                            TrailMath.withAlpha(bottom, fillAlpha), TrailMath.withAlpha(top, fillAlpha));
                }
            }
            for (int i = 0; i < edgeCount; i++) {
                int o = i * 6;
                int c1 = colorAt(primary, secondary, rainbow, gradient, hueBase, edges[o], edges[o + 1], edges[o + 2]);
                int c2 = colorAt(primary, secondary, rainbow, gradient, hueBase, edges[o + 3], edges[o + 4], edges[o + 5]);
                RibbonRenderer.edge(buffer, matrix,
                        ox + edges[o], oy + edges[o + 1], oz + edges[o + 2],
                        ox + edges[o + 3], oy + edges[o + 4], oz + edges[o + 5],
                        lineThickness, TrailMath.withAlpha(c1, lineAlpha), TrailMath.withAlpha(c2, lineAlpha));
            }
            immediate.draw();
        }
    }

    private int colorAt(int primary, int secondary, boolean rainbow, boolean gradient, float hueBase,
                        float x, float y, float z) {
        if (rainbow) return TrailMath.hsv(hueBase + (x + y + z) * 0.18f, 0.72f, 1.0f);
        if (gradient) {
            float span = shapeMaxY - shapeMinY;
            float t = span <= 1.0e-4f ? 0.0f : (y - shapeMinY) / span;
            return TrailMath.lerpArgb(primary, secondary, t);
        }
        return primary;
    }

    private void rebuildCache(VoxelShape shape) {
        if (shape == cachedShape) return;
        cachedShape = shape;
        edgeCount = 0;
        boxCount = 0;
        shapeMinY = Float.MAX_VALUE;
        shapeMaxY = -Float.MAX_VALUE;
        shape.forEachEdge((x1, y1, z1, x2, y2, z2) -> {
            if (edges.length < (edgeCount + 1) * 6) edges = Arrays.copyOf(edges, Math.max(72, edges.length * 2));
            int o = edgeCount++ * 6;
            edges[o] = (float) x1;
            edges[o + 1] = (float) y1;
            edges[o + 2] = (float) z1;
            edges[o + 3] = (float) x2;
            edges[o + 4] = (float) y2;
            edges[o + 5] = (float) z2;
            shapeMinY = Math.min(shapeMinY, (float) Math.min(y1, y2));
            shapeMaxY = Math.max(shapeMaxY, (float) Math.max(y1, y2));
        });
        shape.forEachBox((x1, y1, z1, x2, y2, z2) -> {
            if (boxes.length < (boxCount + 1) * 6) boxes = Arrays.copyOf(boxes, Math.max(36, boxes.length * 2));
            int o = boxCount++ * 6;
            boxes[o] = (float) x1;
            boxes[o + 1] = (float) y1;
            boxes[o + 2] = (float) z1;
            boxes[o + 3] = (float) x2;
            boxes[o + 4] = (float) y2;
            boxes[o + 5] = (float) z2;
        });
        if (edgeCount == 0) {
            shapeMinY = 0.0f;
            shapeMaxY = 1.0f;
        }
    }
}
