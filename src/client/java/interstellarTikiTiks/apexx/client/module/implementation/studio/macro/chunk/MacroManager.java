package interstellarTikiTiks.apexx.client.module.implementation.studio.macro.chunk;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import interstellarTikiTiks.apexx.client.util.SystemInputSimulator;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Chunk-macro runtime — wholly independent of the legacy
 * {@link interstellarTikiTiks.apexx.client.module.implementation.studio.macro.MacroManager} (item/slot Step model) and of
 * {@link interstellarTikiTiks.apexx.client.config.ConfigManager}'s config poll.
 *
 * Responsibilities:
 *  1. {@link #init()} registers its OWN Fabric END_CLIENT_TICK callback and a
 *     disconnect hook (abort + release held inputs).
 *  2. Every tick (~1s cadence, hash-guarded) it polls GET /macros from the
 *     CommandCenter and re-parses macros.json into {@link ChunkMacro}s.
 *  3. Every tick it edge-tracks each macro's bound GLFW key; a rising edge
 *     compiles the chunk timeline into a queue of timed events and runs it on a
 *     single daemon scheduler thread. Each actual input call is marshalled onto
 *     the client thread via {@link MinecraftClient#execute(Runnable)}.
 *
 * Timeline rules (from the data contract):
 *  - Consecutive chunks with NO pause between them: the next chunk starts +1ms
 *    after the previous chunk's last action ("+1ms chaining").
 *  - A PAUSE chunk contributes its own ms as the gap to the following chunk.
 *  - CLICK / DRAG_CLICK: inter-click spacing = 1000/cps ms; one click each.
 *    (CLICK fires one tap per chunk; chain multiple CLICK chunks for bursts.)
 *  - HOLD timed: press at chunk start, release after ms. HOLD infinite: press
 *    at chunk start, released only on abort / key-release.
 */
public final class MacroManager {

    private static final Logger LOGGER = org.slf4j.helpers.NOPLogger.NOP_LOGGER;


    private static final MacroManager INSTANCE = new MacroManager();


    // Parsed macros (replaced atomically on each successful sync).
    private volatile List<ChunkMacro> macros = new ArrayList<>();

    // Per-macro previous key state, parallel to `macros` by index — rebuilt on sync.
    private boolean[] prevDown = new boolean[0];

    // GLFW key of the macro that owns the currently-running timeline, so releasing
    // it ends an in-flight run (notably stopping HOLD INFINITE). MIN_VALUE = none.
    private int runningMacroKey = Integer.MIN_VALUE;

    // True when the running macro holds an input indefinitely (HOLD INFINITE), so
    // its run is HOLD-style: releasing the trigger key ends it. PRESS-style macros
    // (no infinite hold) run their timeline to completion regardless of key-up, so
    // a quick tap of a burst macro isn't truncated.
    private boolean runningMacroIsHold = false;

    // The scheduler thread + its event queue.
    private final Scheduler scheduler = new Scheduler();

    private boolean initialized = false;

    private MacroManager() {}

    public static MacroManager getInstance() {
        return INSTANCE;
    }

    /** Call once from the client ModInitializer. Registers tick + disconnect hooks. */
    public void init() {
        if (initialized) return;
        initialized = true;

        scheduler.start();

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> abortAll());

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            try {
                // Stop triggering once signed out or self-destructed (no traffic, no work).
                if (interstellarTikiTiks.apexx.client.Apexx.isTornDown()) return;
                tickTriggers(client);
            } catch (Throwable t) {
                // Never let a macro fault kill the tick loop.
                LOGGER.debug("[MacroManager] tick error: {}", t.toString());
            }
        });

        LOGGER.info("[MacroManager] chunk-macro runtime initialized");
    }

    /** Applies a macro list (JSON array or {"macros":[...]}) held in memory; there is no network source. */
    public void parseAndApply(String body) {
        List<ChunkMacro> parsed = new ArrayList<>();
        try {
            JsonElement root = JsonParser.parseString(body);
            JsonArray arr = null;
            if (root.isJsonArray()) {
                arr = root.getAsJsonArray();
            } else if (root.isJsonObject()) {
                JsonObject obj = root.getAsJsonObject();
                if (obj.has("macros") && obj.get("macros").isJsonArray()) {
                    arr = obj.getAsJsonArray("macros");
                }
            }
            if (arr != null) {
                for (JsonElement el : arr) {
                    if (!el.isJsonObject()) continue;
                    ChunkMacro m = ChunkMacro.fromJson(el.getAsJsonObject());
                    if (m != null) parsed.add(m);
                }
            }
        } catch (Exception e) {
            LOGGER.debug("[MacroManager] failed to parse macros.json: {}", e.toString());
            return; // keep the previous good set on a parse error (fail-soft)
        }

        this.macros = parsed;
        // Reset edge tracking to match the new list length so a sync never
        // spuriously fires (rebuilt all-false → needs a fresh rising edge).
        this.prevDown = new boolean[parsed.size()];
        LOGGER.debug("[MacroManager] loaded {} chunk macro(s)", parsed.size());
    }

    // ── Trigger: rising-edge key detection ──────────────────────────────────────

    private void tickTriggers(MinecraftClient client) {
        if (client == null) return;

        List<ChunkMacro> snapshot = this.macros;
        boolean[] prev = this.prevDown;
        if (prev.length != snapshot.size()) {
            // Defensive: keep arrays aligned if a sync raced this tick.
            prev = new boolean[snapshot.size()];
            this.prevDown = prev;
        }

        // Abort everything when there's no player / a screen is open (e.g. inv,
        // chat, pause) so input never lands somewhere unexpected.
        // No macro while a screen is open: the trigger is a raw key read, so typing the bound key in
        // chat started it, and swaps with an inventory open move the hovered stack.
        boolean canRun = client.player != null
                && client.currentScreen == null
                && client.getWindow() != null
                && client.getWindow().getHandle() != 0L;

        for (int i = 0; i < snapshot.size(); i++) {
            ChunkMacro macro = snapshot.get(i);
            if (!macro.hasWork()) { prev[i] = false; continue; }

            boolean down = canRun && isKeyDown(client, macro.key);
            boolean rising = down && !prev[i];
            boolean falling = !down && prev[i];
            prev[i] = down;

            if (rising) {
                runningMacroKey = macro.key;
                runningMacroIsHold = macro.hasInfiniteHold();
                scheduler.run(macro);
            } else if (falling && macro.key == runningMacroKey && runningMacroIsHold) {
                // Releasing the trigger key ends a HOLD-style run — this is what
                // makes a HOLD INFINITE chunk stop (release held inputs) on key-up.
                // PRESS-style macros (no infinite hold) are left to run to
                // completion so a quick tap of a burst macro isn't truncated.
                runningMacroKey = Integer.MIN_VALUE;
                runningMacroIsHold = false;
                scheduler.abort();
            }
        }

        if (!canRun) {
            // Player gone / screen open — kill any in-flight timeline + held inputs.
            runningMacroKey = Integer.MIN_VALUE;
            runningMacroIsHold = false;
            scheduler.abort();
        }
    }

    private static boolean isKeyDown(MinecraftClient client, int glfwKey) {
        if (glfwKey < GLFW.GLFW_KEY_SPACE || glfwKey > GLFW.GLFW_KEY_LAST) return false;
        try {
            return InputUtil.isKeyPressed(client.getWindow(), glfwKey);
        } catch (Throwable t) {
            try {
                return GLFW.glfwGetKey(client.getWindow().getHandle(), glfwKey) == GLFW.GLFW_PRESS;
            } catch (Throwable ignored) {
                return false;
            }
        }
    }

    public void abortAll() {
        runningMacroKey = Integer.MIN_VALUE;
        runningMacroIsHold = false;
        scheduler.abort();
    }

    /** Permanently stop macro work when the client payload is unloaded. */
    public void shutdown() {
        runningMacroKey = Integer.MIN_VALUE;
        runningMacroIsHold = false;
        scheduler.shutdown();
    }

    // ── Scheduler ───────────────────────────────────────────────────────────────

    /**
     * A single daemon thread that only computes WHEN each input fires. Every
     * actual input call is dispatched onto the client thread via
     * MinecraftClient.execute(...). One timeline runs at a time; triggering a new
     * macro replaces the current run (and releases any inputs it was holding).
     */
    private static final class Scheduler {

        private final ReentrantLock lock = new ReentrantLock();
        private final PriorityQueue<Event> queue = new PriorityQueue<>();
        private volatile Thread thread;
        private volatile boolean running = true;

        // Inputs currently held by the active timeline, so abort() can release them.
        private final Set<HeldInput> held = new HashSet<>();
        // Generation token: bumping it invalidates a still-pending timeline.
        private volatile int generation = 0;

        void start() {
            if (!running || thread != null) return;
            thread = new Thread(this::loop, "ApexxMacroScheduler");
            thread.setDaemon(true);
            thread.setPriority(Thread.NORM_PRIORITY);
            thread.start();
        }

        /** Compile a macro into timed events and schedule them (replacing any current run). */
        void run(ChunkMacro macro) {
            if (!running) return;
            lock.lock();
            try {
                if (!running) return;
                // Abort the previous run first (release held inputs, clear queue).
                releaseHeldLocked();
                queue.clear();
                int gen = ++generation;

                long cursorMs = 0; // ms offset from "now" for the next chunk's start
                long nowNanos = System.nanoTime();

                for (MacroChunk chunk : macro.chunks) {
                    cursorMs = compileChunk(chunk, gen, nowNanos, cursorMs);
                }
            } finally {
                lock.unlock();
            }
            LockSupport.unpark(thread);
        }

        /**
         * Schedule one chunk's events starting at startMs and return the ms offset
         * at which the FOLLOWING chunk should start.
         *
         * The "+1ms chaining" rule: when a chunk is NOT a pause, the next chunk
         * starts 1ms after this chunk's last action. A pause chunk advances the
         * cursor by its own duration instead.
         */
        private long compileChunk(MacroChunk chunk, int gen, long baseNanos, long startMs) {
            switch (chunk.type) {
                case PAUSE: {
                    // No action; the cursor simply advances by the pause duration.
                    return startMs + chunk.ms;
                }

                case OFFHAND: {
                    enqueue(baseNanos, startMs, gen, () -> SystemInputSimulator.tapSwapHands());
                    return startMs + 1; // +1ms chain
                }

                case SWAP_TO: {
                    final int slot = chunk.slot;
                    enqueue(baseNanos, startMs, gen, () -> interstellarTikiTiks.apexx.client.util.combat.SlotSync.select(net.minecraft.client.MinecraftClient.getInstance(), slot));
                    return startMs + 1;
                }

                case CLICK: {
                    // One clean click. Per the contract, "CPS sets inter-click ms":
                    // when a CLICK is followed by another non-pause chunk, the gap
                    // is 1000/cps (so chained CLICK chunks autoclick at that CPS)
                    // rather than the default +1ms.
                    final MacroChunk.Button b = chunk.button;
                    enqueue(baseNanos, startMs, gen, () -> tapButton(b));
                    return startMs + chunk.clickIntervalMs();
                }

                case DRAG_CLICK: {
                    // Hold the button down, then issue rapid taps at the CPS spacing
                    // for a short burst, then release. The held button is registered
                    // so abort() releases it if interrupted mid-burst.
                    final MacroChunk.Button b = chunk.button;
                    final HeldInput holdToken = HeldInput.mouse(b);
                    final long interval = chunk.clickIntervalMs();
                    final int taps = 8; // burst length

                    enqueue(baseNanos, startMs, gen, () -> {
                        pressButton(b);
                        markHeld(holdToken);
                    });
                    long t = startMs;
                    for (int k = 0; k < taps; k++) {
                        final long tt = t;
                        enqueue(baseNanos, tt, gen, () -> tapButton(b));
                        t += interval;
                    }
                    final long releaseAt = t;
                    enqueue(baseNanos, releaseAt, gen, () -> {
                        releaseButton(b);
                        unmarkHeld(holdToken);
                    });
                    return releaseAt + 1; // +1ms chain after the drag finishes
                }

                case HOLD: {
                    final HeldInput token = HeldInput.fromChunk(chunk);
                    enqueue(baseNanos, startMs, gen, () -> {
                        pressHold(chunk);
                        markHeld(token);
                    });
                    if (chunk.holdMode == MacroChunk.HoldMode.TIMED) {
                        long releaseAt = startMs + chunk.ms;
                        enqueue(baseNanos, releaseAt, gen, () -> {
                            releaseHold(chunk);
                            unmarkHeld(token);
                        });
                        return releaseAt + 1; // +1ms chain after a timed hold
                    }
                    // INFINITE: never auto-released; held until abort / key release.
                    // The next chunk still chains +1ms after the press edge.
                    return startMs + 1;
                }

                default:
                    return startMs + 1;
            }
        }

        private void enqueue(long baseNanos, long offsetMs, int gen, Runnable action) {
            queue.add(new Event(baseNanos + offsetMs * 1_000_000L, gen, action));
        }

        /**
         * Run one event's action on the client thread, atomic against abort().
         *
         * The gen-check and the action's press+markHeld MUST be indivisible w.r.t.
         * abort(): otherwise abort() can snapshot+release the held set in the window
         * between the check passing and markHeld recording the press, leaving a
         * HOLD-INFINITE input stuck DOWN forever (it was pressed after its run was
         * superseded, yet never made it into the set abort releases). Holding the
         * SAME lock abort() uses — and re-checking the generation after acquiring it —
         * closes that window. The lock is reentrant, so markHeld/unmarkHeld nest fine.
         */
        private void runEventAtomically(Event ev, int genAtSchedule) {
            lock.lock();
            try {
                // Re-check UNDER the lock: if abort()/a new run() bumped the generation,
                // this action was superseded — drop it so it can't press a now-orphaned
                // input that abort already finished releasing.
                if (genAtSchedule != generation) return;
                ev.action.run();
            } catch (Throwable t) {
                LOGGER.debug("[MacroManager] action error: {}", t.toString());
            } finally {
                lock.unlock();
            }
        }

        /** Abort the current timeline and release every held input. */
        void abort() {
            lock.lock();
            try {
                generation++;
                queue.clear();
                releaseHeldLocked();
            } finally {
                lock.unlock();
            }
        }

        void shutdown() {
            lock.lock();
            try {
                running = false;
                generation++;
                queue.clear();
                releaseHeldLocked();
            } finally {
                lock.unlock();
            }
            Thread worker = thread;
            if (worker != null) LockSupport.unpark(worker);
        }

        private void loop() {
            while (running) {
                Event next;
                long waitNanos;
                lock.lock();
                try {
                    Event head = queue.peek();
                    if (head == null) {
                        waitNanos = -1; // park indefinitely
                        next = null;
                    } else {
                        long delta = head.whenNanos - System.nanoTime();
                        if (delta <= 0) {
                            next = queue.poll();
                            waitNanos = 0;
                        } else {
                            next = null;
                            waitNanos = delta;
                        }
                    }
                } finally {
                    lock.unlock();
                }

                if (next != null) {
                    final Event ev = next;
                    final int genAtSchedule = ev.generation;
                    MinecraftClient mc = MinecraftClient.getInstance();
                    if (mc != null) {
                        mc.execute(() -> runEventAtomically(ev, genAtSchedule));
                    }
                    continue;
                }

                if (waitNanos < 0) {
                    LockSupport.park();
                } else {
                    LockSupport.parkNanos(waitNanos);
                }
            }
        }

        // ── Held-input bookkeeping (called on scheduler thread, guarded by lock) ─

        private void markHeld(HeldInput token) {
            lock.lock();
            try { held.add(token); } finally { lock.unlock(); }
        }

        private void unmarkHeld(HeldInput token) {
            lock.lock();
            try { held.remove(token); } finally { lock.unlock(); }
        }

        private void releaseHeldLocked() {
            if (held.isEmpty()) {
                // Still flush any pending tapKey/tapMouse releases for safety.
                MinecraftClient mc = MinecraftClient.getInstance();
                if (mc != null) mc.execute(SystemInputSimulator::processPendingReleases);
                return;
            }
            List<HeldInput> toRelease = new ArrayList<>(held);
            held.clear();
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc == null) return;
            mc.execute(() -> {
                for (HeldInput h : toRelease) {
                    try { h.release(); } catch (Throwable ignored) {}
                }
                // Belt-and-suspenders: release holds made outside any module context (which is
                // where macro input lands). releaseAll() also dropped every module's holds, e.g.
                // AutoHeal's held use.
                SystemInputSimulator.releaseUnowned();
            });
        }

        // ── Input primitive wrappers (run on the client thread) ─────────────────

        private static void tapButton(MacroChunk.Button b) {
            if (b == MacroChunk.Button.LEFT) SystemInputSimulator.tapAttack();
            else SystemInputSimulator.tapUse();
        }

        private static void pressButton(MacroChunk.Button b) {
            if (b == MacroChunk.Button.LEFT) SystemInputSimulator.pressAttack();
            else SystemInputSimulator.pressUse();
        }

        private static void releaseButton(MacroChunk.Button b) {
            if (b == MacroChunk.Button.LEFT) SystemInputSimulator.releaseAttack();
            else SystemInputSimulator.releaseUse();
        }

        private static void pressHold(MacroChunk chunk) {
            switch (chunk.holdInput) {
                case ATTACK -> SystemInputSimulator.pressAttack();
                case USE -> SystemInputSimulator.pressUse();
                case KEY -> { if (chunk.holdKey >= 0) SystemInputSimulator.pressKey(chunk.holdKey); }
            }
        }

        private static void releaseHold(MacroChunk chunk) {
            switch (chunk.holdInput) {
                case ATTACK -> SystemInputSimulator.releaseAttack();
                case USE -> SystemInputSimulator.releaseUse();
                case KEY -> { if (chunk.holdKey >= 0) SystemInputSimulator.releaseKey(chunk.holdKey); }
            }
        }
    }

    // ── Event + held-input value types ──────────────────────────────────────────

    private static final class Event implements Comparable<Event> {
        final long whenNanos;
        final int generation;
        final Runnable action;

        Event(long whenNanos, int generation, Runnable action) {
            this.whenNanos = whenNanos;
            this.generation = generation;
            this.action = action;
        }

        @Override
        public int compareTo(Event o) {
            return Long.compare(whenNanos, o.whenNanos);
        }
    }

    /**
     * A handle to an input currently being held, so it can be released on abort.
     * Kind 0 = attack, 1 = use, 2 = glfw key, 3 = mouse-left, 4 = mouse-right.
     */
    private static final class HeldInput {
        final int kind;
        final int key; // glfw key for kind==2

        private HeldInput(int kind, int key) {
            this.kind = kind;
            this.key = key;
        }

        static HeldInput mouse(MacroChunk.Button b) {
            return new HeldInput(b == MacroChunk.Button.LEFT ? 3 : 4, -1);
        }

        static HeldInput fromChunk(MacroChunk chunk) {
            return switch (chunk.holdInput) {
                case ATTACK -> new HeldInput(0, -1);
                case USE -> new HeldInput(1, -1);
                case KEY -> new HeldInput(2, chunk.holdKey);
            };
        }

        void release() {
            switch (kind) {
                case 0 -> SystemInputSimulator.releaseAttack();
                case 1 -> SystemInputSimulator.releaseUse();
                case 2 -> { if (key >= 0) SystemInputSimulator.releaseKey(key); }
                case 3 -> SystemInputSimulator.releaseAttack();
                case 4 -> SystemInputSimulator.releaseUse();
            }
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof HeldInput h)) return false;
            return kind == h.kind && key == h.key;
        }

        @Override
        public int hashCode() {
            return kind * 31 + key;
        }
    }
}
