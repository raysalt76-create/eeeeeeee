package interstellarTikiTiks.apexx.client.util;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ModuleExecutionContextTest {

    @Test
    void nestedCallbacksRestoreOuterOwner() {
        Object outer = new Object();
        Object inner = new Object();

        ModuleExecutionContext.run(outer, () -> {
            assertSame(outer, ModuleExecutionContext.currentOwner());
            ModuleExecutionContext.run(inner,
                    () -> assertSame(inner, ModuleExecutionContext.currentOwner()));
            assertSame(outer, ModuleExecutionContext.currentOwner());
        });

        assertNull(ModuleExecutionContext.currentOwner());
    }

    @Test
    void exceptionsCannotLeakOwnership() {
        Object owner = new Object();

        assertThrows(IllegalStateException.class,
                () -> ModuleExecutionContext.run(owner, () -> {
                    throw new IllegalStateException("expected");
                }));

        assertNull(ModuleExecutionContext.currentOwner());
    }

    @Test
    void ownershipIsIsolatedPerThread() throws Exception {
        Object owner = new Object();
        AtomicReference<Object> otherThreadOwner = new AtomicReference<>(owner);

        ModuleExecutionContext.run(owner, () -> {
            Thread worker = new Thread(() -> otherThreadOwner.set(ModuleExecutionContext.currentOwner()));
            worker.start();
            try {
                worker.join();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
            assertSame(owner, ModuleExecutionContext.currentOwner());
        });

        assertNull(otherThreadOwner.get());
        assertNull(ModuleExecutionContext.currentOwner());
    }

    @Test
    void callReturnsValueAndRestoresOwner() {
        Object owner = new Object();
        String result = ModuleExecutionContext.call(owner, () -> {
            assertSame(owner, ModuleExecutionContext.currentOwner());
            return "ok";
        });

        assertEquals("ok", result);
        assertNull(ModuleExecutionContext.currentOwner());
    }
}
