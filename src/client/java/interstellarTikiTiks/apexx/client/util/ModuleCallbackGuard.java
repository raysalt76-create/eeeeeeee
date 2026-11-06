package interstellarTikiTiks.apexx.client.util;

import interstellarTikiTiks.apexx.client.module.Module;
import net.minecraft.client.MinecraftClient;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Fault boundary for module hooks invoked directly by mixins rather than ModuleManager.
 * A broken packet, attack, or item-use hook must never escape into Minecraft's input/network
 * thread, and it must not continue failing invisibly forever.
 */
public final class ModuleCallbackGuard {
    private static final Object LOCK = new Object();
    private static final Map<Object, Map<String, FaultState>> states = new IdentityHashMap<>();
    private static final int MAX_CONSECUTIVE_FAILURES = 3;
    private static final long RETRY_DELAY_MS = 1000L;

    private ModuleCallbackGuard() {
    }

    public static void run(Object owner, String channel, Runnable callback) {
        // Same contract as call(), without wrapping the Runnable in a Supplier per invocation (hot input/packet path).
        if (callback == null) return;
        String resolvedChannel = channel == null || channel.isBlank() ? "callback" : channel;
        if (isSuppressed(owner, resolvedChannel)) return;
        try {
            ModuleExecutionContext.run(owner, callback);
            recordSuccess(owner, resolvedChannel);
        } catch (Throwable failure) {
            recordFailure(owner, resolvedChannel, failure);
        }
    }

    public static <T> T call(Object owner, String channel, Supplier<T> callback, T fallback) {
        if (callback == null) return fallback;
        String resolvedChannel = channel == null || channel.isBlank() ? "callback" : channel;
        if (isSuppressed(owner, resolvedChannel)) return fallback;

        try {
            T result = ModuleExecutionContext.call(owner, callback);
            recordSuccess(owner, resolvedChannel);
            return result;
        } catch (Throwable failure) {
            recordFailure(owner, resolvedChannel, failure);
            return fallback;
        }
    }

    public static void clearOwner(Object owner) {
        if (owner == null) return;
        synchronized (LOCK) {
            states.remove(owner);
            anyState = !states.isEmpty();
        }
    }

    /**
     * Whether any fault state exists. Written under LOCK, read without it so the common no-fault path
     * (every packet / input hook call, on the netty and client threads) never takes the monitor.
     */
    private static volatile boolean anyState;

    private static boolean isSuppressed(Object owner, String channel) {
        if (owner == null || !anyState) return false;
        synchronized (LOCK) {
            Map<String, FaultState> ownerStates = states.get(owner);
            FaultState state = ownerStates == null ? null : ownerStates.get(channel);
            return state != null && System.currentTimeMillis() < state.suppressUntilMs;
        }
    }

    private static void recordSuccess(Object owner, String channel) {
        if (owner == null || !anyState) return;
        synchronized (LOCK) {
            Map<String, FaultState> ownerStates = states.get(owner);
            FaultState state = ownerStates == null ? null : ownerStates.get(channel);
            if (state == null) return;
            state.failures = 0;
            state.suppressUntilMs = 0L;
        }
    }

    private static void recordFailure(Object owner, String channel, Throwable failure) {
        String source = owner instanceof Module module ? module.getName()
                : owner == null ? "Module callback" : owner.getClass().getSimpleName();
        int failures = 1;
        if (owner != null) {
            synchronized (LOCK) {
                FaultState state = states.computeIfAbsent(owner, ignored -> new HashMap<>())
                        .computeIfAbsent(channel, ignored -> new FaultState());
                failures = ++state.failures;
                anyState = true;
                state.suppressUntilMs = System.currentTimeMillis() + RETRY_DELAY_MS;
            }
        }

        ClientInternalLog.error(source, channel + " failed (fault #" + failures + ")", failure);
        if (!(owner instanceof Module module)) return;

        int failureCount = failures;
        runOnClientThread(() -> {
            module.releaseRuntimeState();
            if (failureCount < MAX_CONSECUTIVE_FAILURES) {
                ClientInternalLog.warn(module.getName(), channel
                        + " paused for " + RETRY_DELAY_MS + "ms before retrying.");
                return;
            }

            clearOwner(module);
            try {
                if (!"Notifications".equalsIgnoreCase(module.getName())) {
                    Notifier.error(module.getName(), "Disabled after repeated " + channel + " failures");
                }
            } catch (Throwable ignored) {
            }
            try {
                module.setEnabled(false);
            } catch (Throwable disableFailure) {
                ClientInternalLog.error(module.getName(),
                        "Failed to disable module after " + channel + " failures", disableFailure);
            }
        });
    }

    private static void runOnClientThread(Runnable action) {
        MinecraftClient client;
        try {
            client = MinecraftClient.getInstance();
        } catch (Throwable unavailable) {
            return;
        }
        if (client == null) return;
        try {
            if (client.isOnThread()) action.run();
            else client.execute(action);
        } catch (Throwable rejected) {
            ClientInternalLog.warn("ModuleCallbackGuard",
                    "Dropped module fault cleanup because the client is stopping.");
        }
    }

    private static final class FaultState {
        int failures;
        long suppressUntilMs;
    }
}
