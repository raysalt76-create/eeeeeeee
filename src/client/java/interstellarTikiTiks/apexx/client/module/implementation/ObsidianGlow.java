package interstellarTikiTiks.apexx.client.module.implementation;

import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.ColorSetting;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import interstellarTikiTiks.apexx.client.util.render.RenderUtils;
import net.minecraft.block.Blocks;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.BufferAllocator;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.awt.Color;
import java.util.*;

public class ObsidianGlow extends Module {
    private BlockPos targetPos = null;
    private BlockPos pendingTargetPos = null;
    private BlockPos lastGlowedPos = null;

    private float transitionProgress = 0f;
    private long lastUpdateTime = 0;
    private long timeLookingAtPending = 0;
    private List<BlockPos> slidePath = null;
    private float slideSpeed = 5f;

    private static final long LOOK_DELAY_MS = 0;
    private static final int MAX_PATHFINDING_DISTANCE = 32;
    private static final int MAX_PATHFINDING_DISTANCE_SQ = MAX_PATHFINDING_DISTANCE * MAX_PATHFINDING_DISTANCE;
    // Node-visit budget for the connected-component BFS. The component is capped at 32
    // blocks of reach, so this bounds worst-case work and prevents render stalls / GC churn.
    private static final int MAX_PATHFINDING_NODES = 512;

    private final ColorSetting outerGlowColor = new ColorSetting("Outer Glow", new Color(153, 51, 230, 120))
            .setDescription("Color and opacity of the outer boundary glow around targeted obsidian.");
    private final ColorSetting innerGlowColor = new ColorSetting("Inner Glow", new Color(220, 150, 255, 70))
            .setDescription("Color and opacity of the inner fill highlight inside targeted obsidian.");
    // Additive: defaults preserve existing behavior (slide enabled, pulse speed 1.0 == time*2f).
    private final BooleanSetting slideAnimation = new BooleanSetting("Slide Animation", true)
            .setDescription("Smoothly slides the glow box between adjacent obsidian blocks.");
    private final SliderSetting slideSpeedSetting = new SliderSetting("Slide Speed", 18.0, 5.0, 50.0, 1)
            .setDescription("Interpolation speed multiplier for the sliding box transition animation.")
            .setDependsOn("Slide Animation");
    private final SliderSetting pulseSpeed = new SliderSetting("Pulse Speed", 1.0, 0.1, 5.0, 1)
            .setDescription("Speed multiplier of the rhythmic breathing glow pulse effect.");

    public ObsidianGlow() {
        super("ObsidianGlow", "Highlights the obsidian block you are aiming at with a soft pulsing glow that slides smoothly between blocks, making crystal placement spots easy to read at a glance. Visual only.", Category.RENDER);
        addSetting(outerGlowColor);
        addSetting(innerGlowColor);
        addSetting(slideAnimation);
        addSetting(slideSpeedSetting);
        addSetting(pulseSpeed);
    }

    @Override
    public void onDisable() {
        targetPos = null;
        pendingTargetPos = null;
        lastGlowedPos = null;
        slidePath = null;
        transitionProgress = 0f;
        lastUpdateTime = 0;
        timeLookingAtPending = 0;
    }

    /**
     * All world scanning + connected-component BFS pathfinding runs here, once per game
     * tick on the main thread, and caches results for the render thread to consume.
     * This keeps the (potentially up-to-512-node) BFS off the render thread.
     */
    @Override
    public void onTick() {
        if (mc.player == null || mc.world == null) return;

        BlockPos currentLookedAtPos = null;
        if (mc.crosshairTarget != null && mc.crosshairTarget.getType() == HitResult.Type.BLOCK) {
            BlockPos pos = ((BlockHitResult) mc.crosshairTarget).getBlockPos();
            if (isGlowBlock(pos)) {
                currentLookedAtPos = pos;
            }
        } else if (mc.crosshairTarget != null && mc.crosshairTarget.getType() == HitResult.Type.ENTITY) {
            Vec3d hitPos = mc.crosshairTarget.getPos();
            BlockPos crystalPos = BlockPos.ofFloored(hitPos);
            BlockPos belowPos = crystalPos.down();
            if (isGlowBlock(belowPos)) {
                currentLookedAtPos = belowPos;
            }
        }

        long currentTime = System.currentTimeMillis();

        if (currentLookedAtPos != null && !currentLookedAtPos.equals(pendingTargetPos)) {
            pendingTargetPos = currentLookedAtPos;
            timeLookingAtPending = currentTime;
        } else if (currentLookedAtPos == null) {
            pendingTargetPos = null;
        }

        if (pendingTargetPos != null && !pendingTargetPos.equals(targetPos)) {
            if (currentTime - timeLookingAtPending >= LOOK_DELAY_MS) {
                targetPos = pendingTargetPos;
                transitionProgress = 0f;

                if (slideAnimation.isEnabled() && lastGlowedPos != null && !lastGlowedPos.equals(targetPos)) {
                    slidePath = findObsidianPath(lastGlowedPos, targetPos);
                    if (slidePath == null) {
                        slidePath = Arrays.asList(lastGlowedPos, targetPos);
                    }
                    double distance = Math.sqrt(lastGlowedPos.getSquaredDistance(targetPos));
                    float speedMultiplier = (float) slideSpeedSetting.getValue() / 10f;
                    slideSpeed = Math.max(10f, (float) distance * 2.5f) * speedMultiplier;
                } else {
                    slidePath = null;
                }
            }
        } else if (pendingTargetPos == null && targetPos != null) {
            targetPos = null;
            transitionProgress = 0f;
            slidePath = null;
        }
    }

    @Override
    public void onWorldRender(MatrixStack matrices, float tickDelta) {
        if (mc.player == null || mc.world == null) return;

        long currentTime = System.currentTimeMillis();
        float deltaTime = lastUpdateTime == 0 ? 0f : (currentTime - lastUpdateTime) / 1000f;
        lastUpdateTime = currentTime;
        if (transitionProgress < 1f) {
            transitionProgress = Math.min(1f, transitionProgress + deltaTime * slideSpeed);
        }

        Vec3d camera = mc.gameRenderer.getCamera().getCameraPos();
        long worldTime = mc.world.getTime();

        boolean isSliding = slidePath != null && !slidePath.isEmpty();

        try (BufferAllocator allocator = interstellarTikiTiks.apexx.client.util.render.PooledBufferAllocator.acquire(1536)) {
            VertexConsumerProvider.Immediate immediate = VertexConsumerProvider.immediate(allocator);

            if (targetPos != null) {
                if (isSliding) {
                    renderSlide(matrices, immediate, camera, worldTime, tickDelta);
                } else {
                    renderGlowAt(matrices, immediate, camera, targetPos, worldTime, tickDelta, transitionProgress);
                }
                lastGlowedPos = targetPos;
            } else if (lastGlowedPos != null) {
                float alpha = 1f - transitionProgress;
                renderGlowAt(matrices, immediate, camera, lastGlowedPos, worldTime, tickDelta, alpha);
            }

            immediate.draw();
        }
    }

    private void renderSlide(MatrixStack matrices, VertexConsumerProvider vertexConsumers, Vec3d camera, long worldTime, float tickDelta) {
        BlockPos endPos = slidePath.get(slidePath.size() - 1);

        if (transitionProgress >= 1f) {
            renderGlowAt(matrices, vertexConsumers, camera, endPos, worldTime, tickDelta, 1f);
            return;
        }

        float pathIndexFloat = transitionProgress * (slidePath.size() - 1);
        int fromIndex = (int) pathIndexFloat;
        int toIndex = Math.min(fromIndex + 1, slidePath.size() - 1);

        BlockPos fromPos = slidePath.get(fromIndex);
        BlockPos toPos = slidePath.get(toIndex);

        float segmentProgress = pathIndexFloat - fromIndex;
        double renderX = fromPos.getX() + (toPos.getX() - fromPos.getX()) * segmentProgress;
        double renderY = fromPos.getY() + (toPos.getY() - fromPos.getY()) * segmentProgress;
        double renderZ = fromPos.getZ() + (toPos.getZ() - fromPos.getZ()) * segmentProgress;

        renderGlowAt(matrices, vertexConsumers, camera, renderX, renderY, renderZ, worldTime, tickDelta, 1f);
    }

    private List<BlockPos> findObsidianPath(BlockPos start, BlockPos end) {
        if (start.equals(end)) return null;
        Queue<BlockPos> queue = new LinkedList<>();
        Map<BlockPos, BlockPos> parentMap = new HashMap<>();
        Set<BlockPos> visited = new HashSet<>();
        queue.add(start);
        visited.add(start);

        int nodeBudget = MAX_PATHFINDING_NODES;
        while (!queue.isEmpty()) {
            if (nodeBudget-- <= 0) return null; // budget exhausted — fall back to straight slide
            BlockPos current = queue.poll();
            if (current.equals(end)) {
                LinkedList<BlockPos> path = new LinkedList<>();
                BlockPos at = end;
                while (at != null) {
                    path.addFirst(at);
                    at = parentMap.get(at);
                }
                return path;
            }
            // Squared-distance compare avoids a per-node Math.sqrt.
            if (current.getSquaredDistance(start) > MAX_PATHFINDING_DISTANCE_SQ) continue;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (Math.abs(dx) + Math.abs(dy) + Math.abs(dz) != 1) continue;
                        BlockPos neighbor = current.add(dx, dy, dz);
                        if (!visited.contains(neighbor) && isGlowBlock(neighbor)) {
                            visited.add(neighbor);
                            parentMap.put(neighbor, current);
                            queue.add(neighbor);
                        }
                    }
                }
            }
        }
        return null;
    }

    private void renderGlowAt(MatrixStack matrices, VertexConsumerProvider vertexConsumers, Vec3d camera, BlockPos pos, long worldTime, float tickDelta, float alphaMultiplier) {
        renderGlowAt(matrices, vertexConsumers, camera, pos.getX(), pos.getY(), pos.getZ(), worldTime, tickDelta, alphaMultiplier);
    }

    private void renderGlowAt(MatrixStack matrices, VertexConsumerProvider vertexConsumers, Vec3d camera, double x, double y, double z, long worldTime, float tickDelta, float alphaMultiplier) {
        if (alphaMultiplier <= 0.01f) return;

        float time = (worldTime + tickDelta) / 20f;
        float pulseRate = (float) pulseSpeed.getValue();
        float pulse = (float) (Math.sin(time * 2f * pulseRate) * 0.5 + 0.5);
        float expand = 0.002f + pulse * 0.003f;

        matrices.push();
        matrices.translate(x - camera.x - expand, y - camera.y - expand, z - camera.z - expand);
        float scale = 1f + expand * 2f;
        matrices.scale(scale, scale, scale);

        Box unitBox = new Box(0, 0, 0, 1, 1, 1);

        Color outerColor = outerGlowColor.getValue();
        float outerAlpha = clamp01((outerColor.getAlpha() / 255f - 0.1f + pulse * 0.2f) * alphaMultiplier);
        if (outerAlpha > 0f) {
            RenderUtils.drawFilledBox(matrices, vertexConsumers, unitBox, outerColor, outerAlpha);
        }

        matrices.translate(0.05f, 0.05f, 0.05f);
        matrices.scale(0.9f, 0.9f, 0.9f);

        Color innerColor = innerGlowColor.getValue();
        float innerPulse = (float) (Math.sin(time * 3f * pulseRate) * 0.5 + 0.5);
        float innerAlpha = clamp01((innerColor.getAlpha() / 255f - 0.15f + innerPulse * 0.3f) * alphaMultiplier);
        if (innerAlpha > 0f) {
            RenderUtils.drawFilledBox(matrices, vertexConsumers, unitBox, innerColor, innerAlpha);
        }

        matrices.pop();
    }

    private static float clamp01(float v) {
        if (v < 0f) return 0f;
        return Math.min(v, 1f);
    }

    private boolean isGlowBlock(BlockPos pos) {
        var block = mc.world.getBlockState(pos).getBlock();
        return block == Blocks.OBSIDIAN || block == Blocks.CRYING_OBSIDIAN;
    }
}
