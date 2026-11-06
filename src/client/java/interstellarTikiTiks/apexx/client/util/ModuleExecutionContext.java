package interstellarTikiTiks.apexx.client.util;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Supplier;

/**
 * Identifies the module currently executing on a thread.
 *
 * <p>This lets shared loader services assign input, delayed work, and future transactions to the
 * module that created them without adding new methods to payload module APIs. A stack is used because
 * module callbacks can synchronously dispatch events that invoke another module.</p>
 */
public final class ModuleExecutionContext {
    // The per-thread stack is kept once created (it holds no owner references when empty). Removing it
    // whenever it emptied re-allocated an ArrayDeque for every guarded callback on every tick/frame/ms.
    private static final ThreadLocal<Deque<Object>> OWNERS = ThreadLocal.withInitial(ArrayDeque::new);

    private ModuleExecutionContext() {
    }

    public static Object currentOwner() {
        return OWNERS.get().peekLast();
    }

    public static void run(Object owner, Runnable action) {
        if (action == null) return;
        if (owner == null) {
            action.run();
            return;
        }
        Deque<Object> stack = OWNERS.get();
        stack.addLast(owner);
        try {
            action.run();
        } finally {
            stack.removeLast();
        }
    }

    public static <T> T call(Object owner, Supplier<T> action) {
        if (action == null) return null;
        if (owner == null) return action.get();
        Deque<Object> stack = OWNERS.get();
        stack.addLast(owner);
        try {
            return action.get();
        } finally {
            stack.removeLast();
        }
    }
}
