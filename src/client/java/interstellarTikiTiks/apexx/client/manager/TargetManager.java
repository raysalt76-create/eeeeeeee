package interstellarTikiTiks.apexx.client.manager;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.hit.EntityHitResult;

public class TargetManager {
    private static LivingEntity lockedTarget = null;
    private static boolean wasAttackKeyPressed = false;

    private static LivingEntity lastManualHitTarget = null;
    private static long lastManualHitTime = 0L;

    public static void update() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) {
            lockedTarget = null;
            return;
        }

        if (lockedTarget != null) {
            if (!lockedTarget.isAlive() || lockedTarget.isRemoved() || lockedTarget.getHealth() <= 0
                    || mc.player.distanceTo(lockedTarget) > 15.0) {
                lockedTarget = null;
            }
        }

        boolean isAttackKeyPressed = mc.options.attackKey.isPressed();
        boolean manualAttack = isAttackKeyPressed && !wasAttackKeyPressed;
        wasAttackKeyPressed = isAttackKeyPressed;

        if (manualAttack && mc.crosshairTarget instanceof EntityHitResult entityHit
                && entityHit.getEntity() instanceof LivingEntity target) {
            lockedTarget = target;
            lastManualHitTarget = target;
            lastManualHitTime = System.currentTimeMillis();
        }
    }

    public static boolean isLocked(Entity target) {
        // Pure predicate. This used to lazily assign lockedTarget to whatever entity was passed
        // in first when nothing was locked -- but it is called as a filter inside a candidate
        // LOOP, so it pinned the FIRST-ITERATED player (arbitrary order), stranding the lock on
        // the wrong target. Acquisition is now the caller's job (manual-hit path here, or an
        // explicit setLockedTarget of the best candidate).
        return lockedTarget != null && target == lockedTarget;
    }

    public static LivingEntity getLockedTarget() {
        return lockedTarget;
    }

    public static void setLockedTarget(LivingEntity target) {
        lockedTarget = target;
    }

    public static void clear() {
        lockedTarget = null;
    }

    public static LivingEntity getLastManualHitTarget() {
        return lastManualHitTarget;
    }

    public static long getLastManualHitTime() {
        return lastManualHitTime;
    }

    public static long msSinceLastHit() {
        if (lastManualHitTime == 0L) return Long.MAX_VALUE;
        return System.currentTimeMillis() - lastManualHitTime;
    }
}
