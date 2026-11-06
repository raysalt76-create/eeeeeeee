package interstellarTikiTiks.apexx.client.module.implementation.support;

import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * Optional bridge to the Baritone pathing mod, shared by the modules that can walk (AutoBuild,
 * AutoMine...).
 *
 * <p><b>Reflection, documented:</b> Baritone is an optional mod that is not on the compile classpath,
 * so its public API ({@code baritone.api.*}) is reached reflectively. Only the stable API interfaces
 * are looked up (never implementation classes), through the game's class loader, and every call is
 * Throwable-safe: when Baritone is missing or its API changed, {@link #isAvailable()} is false and the
 * other methods do nothing and return false.
 *
 * <p>Paths are owner-scoped: {@link #cancel(Object)} only stops a path that the same owner started and
 * that is still the goal Baritone is following, so a module never cancels a path the player (or
 * another module) set afterward. Call everything on the client thread.
 */
public final class BaritoneBridge {

    private static final String API = "baritone.api.BaritoneAPI";
    private static final String GOAL = "baritone.api.pathing.goals.Goal";
    private static final String GOAL_NEAR = "baritone.api.pathing.goals.GoalNear";
    private static final String GOAL_BLOCK = "baritone.api.pathing.goals.GoalBlock";
    private static final String IBARITONE = "baritone.api.IBaritone";
    private static final String IPROVIDER = "baritone.api.IBaritoneProvider";
    private static final String ICUSTOM = "baritone.api.process.ICustomGoalProcess";
    private static final String IPATHING = "baritone.api.behavior.IPathingBehavior";

    private static boolean resolved;
    private static boolean available;
    private static Method getProvider;
    private static Method getPrimaryBaritone;
    private static Method getCustomGoalProcess;
    private static Method getPathingBehavior;
    private static Method setGoalAndPath;
    private static Method getGoal;
    private static Method customIsActive;
    private static Method isPathing;
    private static Method cancelEverything;
    private static Constructor<?> goalNear;
    private static Constructor<?> goalBlock;

    /** Owner of the path this bridge last started, and the goal object it handed to Baritone. */
    private static Object pathOwner;
    private static Object pathGoal;

    private BaritoneBridge() {
    }

    /** Whether Baritone's API is loaded and usable. */
    public static synchronized boolean isAvailable() {
        resolve();
        return available;
    }

    /**
     * Paths to within {@code range} blocks of {@code pos}. Replaces whatever goal Baritone had.
     * @return true when the goal was handed to Baritone.
     */
    public static synchronized boolean goNear(Object owner, BlockPos pos, int range) {
        if (owner == null || pos == null || !isAvailable()) return false;
        try {
            return start(owner, goalNear.newInstance(pos.toImmutable(), Math.max(0, range)));
        } catch (Throwable t) {
            return false;
        }
    }

    /** Paths to stand exactly at {@code pos} (feet position). */
    public static synchronized boolean goTo(Object owner, BlockPos pos) {
        if (owner == null || pos == null || !isAvailable()) return false;
        try {
            return start(owner, goalBlock.newInstance(pos.toImmutable()));
        } catch (Throwable t) {
            return false;
        }
    }

    /** Whether Baritone is currently pathing or following a custom goal (anyone's). */
    public static synchronized boolean isPathing() {
        if (!isAvailable()) return false;
        try {
            Object baritone = primary();
            if (baritone == null) return false;
            Object behavior = getPathingBehavior.invoke(baritone);
            if (behavior != null && Boolean.TRUE.equals(isPathing.invoke(behavior))) return true;
            Object custom = getCustomGoalProcess.invoke(baritone);
            return custom != null && Boolean.TRUE.equals(customIsActive.invoke(custom));
        } catch (Throwable t) {
            return false;
        }
    }

    /** Whether {@code owner} started the goal Baritone is still following. */
    public static synchronized boolean isOwnedBy(Object owner) {
        if (owner == null || owner != pathOwner || !isAvailable()) return false;
        try {
            Object baritone = primary();
            if (baritone == null) return false;
            Object custom = getCustomGoalProcess.invoke(baritone);
            return custom != null && getGoal.invoke(custom) == pathGoal;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Stops the current path, but only when {@code owner} started it and Baritone is still following
     * that same goal. Safe to call any number of times, with or without Baritone installed.
     */
    public static synchronized void cancel(Object owner) {
        if (owner == null || owner != pathOwner) return;
        boolean ours = isOwnedBy(owner);
        pathOwner = null;
        pathGoal = null;
        if (!ours) return;
        try {
            Object baritone = primary();
            if (baritone == null) return;
            Object behavior = getPathingBehavior.invoke(baritone);
            if (behavior != null) cancelEverything.invoke(behavior);
        } catch (Throwable ignored) {
            // Baritone went away or changed; nothing of ours is left to stop.
        }
    }

    private static boolean start(Object owner, Object goal) throws Exception {
        Object baritone = primary();
        if (baritone == null) return false;
        Object custom = getCustomGoalProcess.invoke(baritone);
        if (custom == null) return false;
        setGoalAndPath.invoke(custom, goal);
        pathOwner = owner;
        pathGoal = goal;
        return true;
    }

    private static Object primary() throws Exception {
        Object provider = getProvider.invoke(null);
        return provider == null ? null : getPrimaryBaritone.invoke(provider);
    }

    private static void resolve() {
        if (resolved) return;
        resolved = true;
        try {
            ClassLoader loader = MinecraftClient.class.getClassLoader();
            Class<?> api = Class.forName(API, false, loader);
            Class<?> goal = Class.forName(GOAL, false, loader);
            Class<?> provider = Class.forName(IPROVIDER, false, loader);
            Class<?> baritone = Class.forName(IBARITONE, false, loader);
            Class<?> custom = Class.forName(ICUSTOM, false, loader);
            Class<?> pathing = Class.forName(IPATHING, false, loader);
            getProvider = api.getMethod("getProvider");
            getPrimaryBaritone = provider.getMethod("getPrimaryBaritone");
            getCustomGoalProcess = baritone.getMethod("getCustomGoalProcess");
            getPathingBehavior = baritone.getMethod("getPathingBehavior");
            setGoalAndPath = custom.getMethod("setGoalAndPath", goal);
            getGoal = custom.getMethod("getGoal");
            customIsActive = custom.getMethod("isActive");
            isPathing = pathing.getMethod("isPathing");
            cancelEverything = pathing.getMethod("cancelEverything");
            goalNear = Class.forName(GOAL_NEAR, false, loader).getConstructor(BlockPos.class, int.class);
            goalBlock = Class.forName(GOAL_BLOCK, false, loader).getConstructor(BlockPos.class);
            available = true;
        } catch (Throwable missing) {
            available = false;
        }
    }
}
