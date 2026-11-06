package interstellarTikiTiks.apexx.client.hud;

import interstellarTikiTiks.apexx.client.module.Module;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Enter/exit animation state for the GPU array list. A row exists while its module is enabled or still
 * animating out; {@link Row#progress} runs 0 (gone) .. 1 (fully shown). Render thread only.
 */
final class ArrayListAnimator {
    static final float BASE_DURATION_MS = 250f;

    static final class Row {
        final Module module;
        float progress;
        float y = Float.NaN;
        boolean active;

        Row(Module module) { this.module = module; }
    }

    private static final Map<Module, Row> rows = new IdentityHashMap<>();
    private static long lastNanos;
    private static float lastDt;

    private ArrayListAnimator() { }

    /** Drops all state; the next frame shows the current rows without an intro animation. */
    static void reset() {
        rows.clear();
        lastNanos = 0L;
        lastDt = 0f;
    }

    /** Seconds since the previous {@link #update} (0 on the first frame, capped at 0.1). */
    static float dt() { return lastDt; }

    /**
     * Advances every row toward shown (enabled) or gone (disabled) and returns the rows to draw in order.
     * {@code durationMs <= 0} disables the animation.
     */
    static List<Row> update(List<Module> active, Comparator<Module> order, float durationMs) {
        long now = System.nanoTime();
        boolean first = lastNanos == 0L;
        lastDt = first ? 0f : Math.min(0.1f, Math.max(0f, (now - lastNanos) / 1_000_000_000f));
        lastNanos = now;
        boolean instant = first || durationMs <= 0f;
        float step = instant ? 1f : lastDt * 1000f / durationMs;

        Set<Module> activeSet = Collections.newSetFromMap(new IdentityHashMap<>());
        activeSet.addAll(active);
        for (Module module : active) {
            Row row = rows.get(module);
            if (row == null) {
                row = new Row(module);
                row.progress = instant ? 1f : 0f;
                rows.put(module, row);
            }
        }
        var it = rows.values().iterator();
        while (it.hasNext()) {
            Row row = it.next();
            row.active = activeSet.contains(row.module);
            row.progress = Math.max(0f, Math.min(1f, row.progress + (row.active ? step : -step)));
            if (!row.active && (row.progress <= 0f || durationMs <= 0f)) it.remove();
        }
        List<Row> ordered = new ArrayList<>(rows.values());
        ordered.sort((a, b) -> order.compare(a.module, b.module));
        return ordered;
    }

    static float easeOutCubic(float t) {
        float u = 1f - Math.max(0f, Math.min(1f, t));
        return 1f - u * u * u;
    }

    /** Overshoots slightly past 1 before settling. */
    static float easeOutBack(float t) {
        float c1 = 1.70158f, c3 = c1 + 1f;
        float u = Math.max(0f, Math.min(1f, t)) - 1f;
        return 1f + c3 * u * u * u + c1 * u * u;
    }

    static float smoothstep(float t) {
        float c = Math.max(0f, Math.min(1f, t));
        return c * c * (3f - 2f * c);
    }
}
