package interstellarTikiTiks.apexx.client.event;

import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.util.ClientInternalLog;
import interstellarTikiTiks.apexx.client.util.ModuleExecutionContext;
import interstellarTikiTiks.apexx.client.util.Notifier;
import net.minecraft.client.MinecraftClient;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class EventBus {
    private static final Map<Class<? extends Event>, List<Listener>> registry = new ConcurrentHashMap<>();
    private static final Map<Object, Map<Method, ListenerFaultState>> listenerFaults = new IdentityHashMap<>();
    private static final boolean DEBUG_EVENTS = Boolean.getBoolean("apexx.debug.events");

    public static void register(Object subscriber) {
        Class<?> clazz = subscriber.getClass();
        while (clazz != null && clazz != Object.class) {
            for (Method method : clazz.getDeclaredMethods()) {
                if (method.isAnnotationPresent(EventTarget.class) && method.getParameterCount() == 1) {
                    Class<?> eventType = method.getParameterTypes()[0];
                    if (Event.class.isAssignableFrom(eventType)) {
                        EventTarget annotation = method.getAnnotation(EventTarget.class);
                        Listener listener = new Listener(subscriber, method, annotation.priority());
                        // Once here instead of on every dispatch (setAccessible re-runs its access checks).
                        try {
                            method.setAccessible(true);
                        } catch (Throwable ignored) {
                            // invoke() reports the access failure through the listener fault path.
                        }

                        @SuppressWarnings("unchecked")
                        Class<? extends Event> eventClass = (Class<? extends Event>) eventType;
                        List<Listener> listeners = registry.get(eventClass);
                        if (listeners == null) {
                            listeners = registry.computeIfAbsent(eventClass, k -> new CopyOnWriteArrayList<>());
                            // A new listened-to type can match event classes already resolved.
                            dispatchCache = new ConcurrentHashMap<>();
                        }
                        if (listeners.stream().anyMatch(existing -> existing.subscriber == subscriber
                                && existing.method.equals(method))) continue;
                        listeners.add(listener);
                        registry.get(eventClass).sort(Comparator.comparingInt(Listener::getPriority).reversed());
                    }
                }
            }
            clazz = clazz.getSuperclass();
        }
    }

    public static void unregister(Object subscriber) {
        registry.values().forEach(listeners -> listeners.removeIf(listener -> listener.subscriber == subscriber));
        synchronized (listenerFaults) {
            listenerFaults.remove(subscriber);
        }
    }

    /**
     * Posted event class -> the registry lists whose key is assignable from it, in registry iteration order.
     * The lists are the live CopyOnWriteArrayLists, so (un)registering listeners on an existing type needs no
     * invalidation; registry keys are never removed, and adding one swaps in a fresh map.
     */
    @SuppressWarnings("unchecked")
    private static volatile Map<Class<?>, List<Listener>[]> dispatchCache = new ConcurrentHashMap<>();
    @SuppressWarnings("unchecked")
    private static final List<Listener>[] NO_LISTENER_LISTS = (List<Listener>[]) new List<?>[0];

    @SuppressWarnings("unchecked")
    private static List<Listener>[] listenerListsFor(Class<?> eventClass) {
        Map<Class<?>, List<Listener>[]> cache = dispatchCache;
        List<Listener>[] cached = cache.get(eventClass);
        if (cached != null) return cached;
        List<List<Listener>> matched = new ArrayList<>();
        for (Map.Entry<Class<? extends Event>, List<Listener>> entry : registry.entrySet()) {
            if (entry.getKey().isAssignableFrom(eventClass)) matched.add(entry.getValue());
        }
        List<Listener>[] result = matched.isEmpty() ? NO_LISTENER_LISTS : matched.toArray((List<Listener>[]) new List<?>[0]);
        cache.put(eventClass, result);
        return result;
    }

    public static void post(Event event) {
        Class<?> eventClass = event.getClass();
        for (List<Listener> listeners : listenerListsFor(eventClass)) {
            for (Listener listener : listeners) {
                if (shouldSuppress(listener.subscriber, listener.method)) continue;
                try {
                    ModuleExecutionContext.run(listener.subscriber, () -> {
                        try {
                            listener.method.invoke(listener.subscriber, event);
                        } catch (Exception e) {
                            throw new ListenerDispatchException(e);
                        }
                    });
                    recordSuccess(listener.subscriber, listener.method);
                } catch (Throwable e) {
                    // Surface the real listener failure. This block was empty even under the
                    // debug flag, so any exception thrown inside an @EventTarget listener was
                    // swallowed without a trace -- a silent-failure trap for every event
                    // handler in the client. Unwrap the reflection wrapper for a useful cause.
                    Throwable cause = unwrapListenerFailure(e);
                    ClientInternalLog.error(listener.subscriber.getClass().getSimpleName(),
                            "Event listener " + listener.method.getName() + " failed for "
                                    + event.getClass().getSimpleName(), cause);
                    if (DEBUG_EVENTS) {
                        org.slf4j.LoggerFactory.getLogger("Apexx/EventBus").warn(
                                "listener {}.{} threw handling {}",
                                listener.subscriber.getClass().getSimpleName(),
                                listener.method.getName(),
                                event.getClass().getSimpleName(), cause);
                    }
                    recordFailure(listener.subscriber, listener.method, cause);
                }
            }
        }
    }

    // Set once any listener fault was recorded; lets the no-fault dispatch path skip the monitor entirely.
    private static volatile boolean anyFaults;

    private static boolean shouldSuppress(Object subscriber, Method method) {
        if (!anyFaults) return false;
        synchronized (listenerFaults) {
            Map<Method, ListenerFaultState> handlers = listenerFaults.get(subscriber);
            ListenerFaultState state = handlers == null ? null : handlers.get(method);
            return state != null && System.currentTimeMillis() < state.suppressUntilMs;
        }
    }

    private static void recordSuccess(Object subscriber, Method method) {
        if (!anyFaults) return;
        synchronized (listenerFaults) {
            Map<Method, ListenerFaultState> handlers = listenerFaults.get(subscriber);
            ListenerFaultState state = handlers == null ? null : handlers.get(method);
            if (state == null) return;
            // Fault counts represent consecutive callback failures. Any successful callback
            // means the module recovered and starts a fresh failure sequence.
            state.faults = 0;
            state.suppressUntilMs = 0L;
        }
    }

    private static void recordFailure(Object subscriber, Method method, Throwable failure) {
        if (!(subscriber instanceof Module module)) return;

        int faults;
        synchronized (listenerFaults) {
            ListenerFaultState state = listenerFaults
                    .computeIfAbsent(subscriber, ignored -> new java.util.HashMap<>())
                    .computeIfAbsent(method, ignored -> new ListenerFaultState());
            faults = ++state.faults;
            anyFaults = true;
            state.suppressUntilMs = faults >= 3 ? Long.MAX_VALUE : System.currentTimeMillis() + 1000L;
        }

        if (faults < 3) {
            runOnClientThread(() -> {
                module.releaseRuntimeState();
                ClientInternalLog.warn(module.getName(), "Event callback " + method.getName()
                        + " paused for 1000ms before retrying.");
            });
            return;
        }

        runOnClientThread(() -> {
            module.releaseRuntimeState();
            ClientInternalLog.error(module.getName(),
                    "Module disabled after 3 consecutive event callback failures.", failure);
            try {
                Notifier.error(module.getName(), "Disabled after repeated event callback failures");
            } catch (Throwable ignored) {
            }
            try {
                module.setEnabled(false);
            } catch (Throwable disableFailure) {
                ClientInternalLog.error(module.getName(), "Failed to disable broken event subscriber",
                        disableFailure);
            } finally {
                // Ensure a faulty onDisable implementation cannot leave packet-thread
                // subscribers registered after the module has been fault-disabled.
                unregister(module);
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
            ClientInternalLog.warn("EventBus", "Dropped event fault cleanup because the client is stopping.");
        }
    }

    private static Throwable unwrapListenerFailure(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof ListenerDispatchException
                || current instanceof java.lang.reflect.InvocationTargetException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static final class ListenerDispatchException extends RuntimeException {
        ListenerDispatchException(Throwable cause) {
            super(cause);
        }
    }

    private static final class ListenerFaultState {
        int faults;
        long suppressUntilMs;
    }

    private static class Listener {
        private final Object subscriber;
        private final Method method;
        private final byte priority;

        public Listener(Object subscriber, Method method, byte priority) {
            this.subscriber = subscriber;
            this.method = method;
            this.priority = priority;
        }

        public int getPriority() {
            return priority;
        }
    }
}
