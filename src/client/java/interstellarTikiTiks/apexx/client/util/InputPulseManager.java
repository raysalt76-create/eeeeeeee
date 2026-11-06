package interstellarTikiTiks.apexx.client.util;

import java.util.concurrent.atomic.AtomicInteger;

public final class InputPulseManager {

    private static final AtomicInteger attackPulses = new AtomicInteger();
    private static final AtomicInteger usePulses = new AtomicInteger();
    private static final AtomicInteger jumpPulses = new AtomicInteger();

    private static volatile long lastAttackMs = 0L;
    private static volatile long lastUseMs = 0L;
    private static volatile long lastJumpMs = 0L;

    private InputPulseManager() {}

    public static void recordAttackPulse() {
        lastAttackMs = System.currentTimeMillis();
        attackPulses.incrementAndGet();
    }

    public static void recordUsePulse() {
        lastUseMs = System.currentTimeMillis();
        usePulses.incrementAndGet();
    }

    public static void recordJumpPulse() {
        lastJumpMs = System.currentTimeMillis();
        jumpPulses.incrementAndGet();
    }

    public static int drainAttackPulses() {
        return attackPulses.getAndSet(0);
    }

    public static int drainUsePulses() {
        return usePulses.getAndSet(0);
    }

    public static int drainJumpPulses() {
        return jumpPulses.getAndSet(0);
    }

    public static boolean isRecentAttack(long windowMs) {
        return System.currentTimeMillis() - lastAttackMs <= windowMs;
    }

    public static boolean isRecentUse(long windowMs) {
        return System.currentTimeMillis() - lastUseMs <= windowMs;
    }

    public static boolean isRecentJump(long windowMs) {
        return System.currentTimeMillis() - lastJumpMs <= windowMs;
    }
}
