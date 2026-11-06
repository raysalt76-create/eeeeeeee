package interstellarTikiTiks.apexx.client.util.combat;

import interstellarTikiTiks.apexx.client.util.RotationManager;
import interstellarTikiTiks.apexx.client.util.RotationMath;
import interstellarTikiTiks.apexx.client.util.RotationRequest;
import interstellarTikiTiks.apexx.client.util.SystemInputSimulator;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Items;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

/**
 * Shared pieces of the AlienWC dive-boost: predictive floor distance, the throw window, the
 * look-down / look-up aim requests and the post-throw {@link Recovery}.
 *
 * <p>Floor distance samples three points along the glide (underfoot, one tick ahead, two and a
 * half ticks ahead) and steps down past side faces up to eight times, so a wall or fence post in
 * the flight path is not mistaken for floor. COMPILED only; UNVERIFIED in-game.
 */
public final class AlienWCSupport {
    private static final MinecraftClient mc = MinecraftClient.getInstance();

    /** Upper bound of the post-throw pull-up pitch. */
    public static final float RECOVERY_PITCH_TOP = -26.0f;
    /** Span below the top: pull-up pitch lies in [-45, -26). */
    public static final float RECOVERY_PITCH_SPAN = 19.0f;
    /** Degrees from the pull-up pitch within which the look-up counts as finished. */
    public static final float PITCH_TOLERANCE = 3.5f;
    /** Highest pitch a look-down aims at; a straight-down aim point has no yaw. */
    private static final float DOWN_AIM_PITCH = 89.5f;

    private AlienWCSupport() {}

    // ── Floor detection ─────────────────────────────────────────────────────

    /** Nearest ground below the feet now and along the next two and a half ticks of travel. */
    public static double floorDistance() {
        if (mc.player == null || mc.world == null) return Double.MAX_VALUE;
        if (mc.player.horizontalCollision) return Double.MAX_VALUE;

        double feetY = mc.player.getBoundingBox().minY;
        double x = mc.player.getX();
        double z = mc.player.getZ();
        Vec3d vel = mc.player.getVelocity();

        double dist = groundDistanceAt(x, z, feetY);
        dist = Math.min(dist, groundDistanceAt(x + vel.x, z + vel.z, feetY));
        dist = Math.min(dist, groundDistanceAt(x + vel.x * 2.5, z + vel.z * 2.5, feetY));
        return dist;
    }

    private static double groundDistanceAt(double x, double z, double feetY) {
        double currentStartY = feetY + 0.05d;
        double maxDepthY = feetY - 64.0d;

        for (int step = 0; step < 8; step++) {
            Vec3d start = new Vec3d(x, currentStartY, z);
            Vec3d end = new Vec3d(x, maxDepthY, z);

            BlockHitResult res = mc.world.raycast(new RaycastContext(
                    start, end,
                    RaycastContext.ShapeType.COLLIDER,
                    RaycastContext.FluidHandling.NONE,
                    mc.player));

            if (res.getType() != HitResult.Type.BLOCK) {
                return Double.MAX_VALUE;
            }

            // Ground is a top face with open space above it.
            if (res.getSide() == Direction.UP) {
                BlockPos hitPos = res.getBlockPos();
                BlockState stateAbove = mc.world.getBlockState(hitPos.up());
                if (!stateAbove.isFullCube(mc.world, hitPos.up())
                        && stateAbove.getCollisionShape(mc.world, hitPos.up()).isEmpty()) {
                    double hitY = res.getPos().y;
                    if (hitY <= feetY + 0.1d) {
                        return Math.max(0.0d, feetY - hitY);
                    }
                }
            }

            // Step down past the obstacle face.
            currentStartY = res.getPos().y - 0.05d;
            if (currentStartY <= maxDepthY) {
                break;
            }
        }
        return Double.MAX_VALUE;
    }

    /** Blocks the player drops per tick; widens the throw window so a fast dive cannot skip it. */
    public static double descentPerTick() {
        return mc.player == null ? 0.0 : Math.max(0.0, -mc.player.getVelocity().y);
    }

    public static boolean isInThrowWindow(double floorDist, double threshold) {
        return floorDist <= Math.max(threshold, descentPerTick());
    }

    // ── Items ───────────────────────────────────────────────────────────────

    public static int findHotbarSlot(net.minecraft.item.Item item) {
        if (mc.player == null) return -1;
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).isOf(item)) return i;
        }
        return -1;
    }

    public static int findWindChargeSlot() {
        return findHotbarSlot(Items.WIND_CHARGE);
    }

    /** True when a use right now would throw: vanilla use delay and item cooldown both clear. */
    public static boolean isChargeReady(int slot) {
        if (mc.player == null || slot < 0 || slot > 8) return false;
        net.minecraft.item.ItemStack stack = mc.player.getInventory().getStack(slot);
        return stack.isOf(Items.WIND_CHARGE)
                && InteractSim.useReady(mc)
                && !mc.player.getItemCooldownManager().isCoolingDown(stack);
    }

    // ── Aim ─────────────────────────────────────────────────────────────────

    /** Pitch toward the floor at {@code aimSpeed * 100} degrees per second. */
    public static void aimDown(Object owner, double aimSpeed, boolean silent) {
        aimPitch(owner, DOWN_AIM_PITCH, aimSpeed, silent);
    }

    /** Pitch toward {@code pitch}, yaw following the live view, at {@code aimSpeed * 100} deg/s. */
    public static void aimPitch(Object owner, float pitch, double aimSpeed, boolean silent) {
        RotationManager.setRotationSupplier(owner, RotationManager.Priority.HIGHEST,
                () -> {
                    if (mc.player == null) return null;
                    return mc.player.getEyePos()
                            .add(RotationMath.direction(mc.player.getYaw(), pitch).multiply(16.0));
                },
                RotationRequest.create()
                        .speed(aimSpeed * 100.0)
                        .silent(silent)
                        .placement(true));
    }

    // ── Recovery ────────────────────────────────────────────────────────────

    /**
     * Post-throw management: pull the pitch back up, reopen the wings if the blast closed them,
     * keep the charge slot for the owning module's Swap Back delay and then swap back to the original one.
     * If anything else changes the slot during that hold, the hold ends without a swap back.
     */
    public static final class Recovery {
        private final Object owner;
        private final double aimSpeed;
        private final boolean silent;
        private final int chargeSlot;
        private final int originalSlot;
        private final int slotHoldTicks;
        private final long reopenDelayMs;
        private final long reopenIntervalMs;
        private final long thrownAt;
        private final float targetPitch;
        private final long aimUpTimeoutMs;
        private final int thrownTick;

        private boolean pitchDone = false;
        private boolean slotReverted = false;
        private long lastGlidePulse = 0L;

        public Recovery(Object owner, double aimSpeed, boolean silent, int chargeSlot, int originalSlot,
                        int slotHoldTicks, long reopenDelayMs, long reopenIntervalMs) {
            this.owner = owner;
            this.aimSpeed = aimSpeed;
            this.silent = silent;
            this.chargeSlot = chargeSlot;
            this.originalSlot = originalSlot;
            this.slotHoldTicks = Math.max(0, slotHoldTicks);
            this.reopenDelayMs = Math.max(0L, reopenDelayMs);
            this.reopenIntervalMs = Math.max(0L, reopenIntervalMs);
            this.thrownAt = System.currentTimeMillis();
            this.thrownTick = mc.player != null ? mc.player.age : 0;

            this.targetPitch = RECOVERY_PITCH_TOP - ThreadLocalRandom.current().nextFloat() * RECOVERY_PITCH_SPAN;
            float startPitch = mc.player != null ? RotationManager.getFinalPitch() : 90.0f;
            this.aimUpTimeoutMs = aimUpTimeout(startPitch - targetPitch, aimSpeed);
        }

        private static long aimUpTimeout(float degrees, double aimSpeed) {
            double degPerSec = Math.max(1.0, aimSpeed * 100.0);
            double linearMs = Math.abs(degrees) / degPerSec * 1000.0;
            return Math.clamp((long) (linearMs * 3.0) + 150L, 350L, 1500L);
        }

        public long elapsedMs() {
            return System.currentTimeMillis() - thrownAt;
        }

        public boolean isPitchDone() {
            return pitchDone;
        }

        public void tick() {
            if (mc.player == null) return;
            long now = System.currentTimeMillis();

            if (!pitchDone) {
                if (hasReachedPitch() || elapsedMs() >= aimUpTimeoutMs) {
                    RotationManager.clearTarget(owner);
                    pitchDone = true;
                } else {
                    aimPitch(owner, targetPitch, aimSpeed, silent);
                }
            }

            // The blast can close the wings; a jump press opens them again.
            if (elapsedMs() >= reopenDelayMs && !mc.player.isGliding() && !mc.player.isOnGround()
                    && now - lastGlidePulse >= reopenIntervalMs) {
                SystemInputSimulator.tapJump();
                lastGlidePulse = now;
            }

            if (!slotReverted) {
                if (SlotSync.current(mc) != chargeSlot) {
                    // The player (or another module) took the hotbar: end the hold, never fight it.
                    slotReverted = true;
                } else if (mc.player.age - thrownTick >= slotHoldTicks) {
                    // Recovery ticks start after the throw's own call; SlotSync.restore defers past the
                    // use's interval when needed, so even a 0 hold never swaps back with the throw.
                    revertSlot();
                }
            }
        }

        public void finish() {
            if (!slotReverted) {
                revertSlot();
            }
            pitchDone = true;
            RotationManager.clearTarget(owner);
        }

        private void revertSlot() {
            // Only swap back while the charge slot is still the one this run selected.
            if (originalSlot >= 0 && mc.player != null && SlotSync.current(mc) == chargeSlot) {
                SlotSync.restore(mc, originalSlot);
            }
            slotReverted = true;
        }

        private boolean hasReachedPitch() {
            return mc.player != null && RotationManager.getFinalPitch() <= targetPitch + PITCH_TOLERANCE;
        }
    }
}
