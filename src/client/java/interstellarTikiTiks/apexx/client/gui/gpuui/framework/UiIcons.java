package interstellarTikiTiks.apexx.client.gui.gpuui.framework;

import java.util.Objects;
import java.util.Locale;

/** Small, scalable symbols painted with the GPU UI display-list primitives. */
public final class UiIcons {
    private static final float VIEWBOX = 24f;

    public enum Icon {
        COMBAT, MOVEMENT, MACROS, RENDER, PLAYER, CLIENT, FRIENDS, ENEMIES,
        SEARCH, CLOSE, CHEVRON_RIGHT, CHEVRON_DOWN, BACK, SLIDERS, CHECK, PLUS, MINUS, INFO,
        BLATANT, UTILITY, MISC, SILENT, SHIELD, KEY
    }

    private UiIcons() { }

    /** The angular 100 by 100 mark used in the brand rail. */
    public static void drawLogo(UiDisplayList.Builder list, float x, float y, float size, int argb) {
        Objects.requireNonNull(list, "list");
        if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(size) || size <= 0f) return;
        float[] xy = {50, 5, 90, 85, 65, 85, 55, 65, 75, 65, 50, 25,
                25, 65, 45, 65, 35, 85, 10, 85};
        float scale = size / 100f;
        for (int row = 5; row < 85; row++) {
            float scan = row + .5f;
            float[] hits = new float[xy.length / 2]; int count = 0;
            for (int i = 0; i < xy.length; i += 2) {
                int j = (i + 2) % xy.length;
                float y0 = xy[i + 1], y1 = xy[j + 1];
                if ((y0 <= scan && scan < y1) || (y1 <= scan && scan < y0))
                    hits[count++] = xy[i] + (scan - y0) * (xy[j] - xy[i]) / (y1 - y0);
            }
            java.util.Arrays.sort(hits, 0, count);
            for (int i = 0; i + 1 < count; i += 2)
                list.rect(x + hits[i] * scale, y + row * scale,
                        (hits[i + 1] - hits[i]) * scale, scale * 1.01f, 0, argb);
        }
    }

    /** A deterministic symbol for familiar modules; all others retain their category icon. */
    public static Icon forModule(String moduleName, Icon categoryFallback) {
        Objects.requireNonNull(categoryFallback, "categoryFallback");
        if (moduleName == null) return categoryFallback;
        String name = moduleName.toLowerCase(Locale.ROOT);
        if (name.contains("aim") || name.contains("aura") || name.contains("trigger")) return Icon.SILENT;
        if (name.contains("shield") || name.contains("proof")) return Icon.SHIELD;
        if (name.contains("bind") || name.contains("key")) return Icon.KEY;
        if (name.contains("esp") || name.contains("vision")) return Icon.RENDER;
        return categoryFallback;
    }

    /** Paints an icon into a square; coordinates and size use display-list pixels. */
    public static void draw(UiDisplayList.Builder list, Icon icon, float x, float y, float size, int argb) {
        Objects.requireNonNull(list, "list");
        Objects.requireNonNull(icon, "icon");
        if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(size) || size <= 0f) return;
        Pen p = new Pen(list, x, y, size / VIEWBOX, argb);
        switch (icon) {
            case COMBAT -> {
                p.path(14.5f, 17.5f, 3, 6, 3, 3, 6, 3, 17.5f, 14.5f);
                p.path(13, 19, 19, 13); p.path(16, 16, 20, 20);
            }
            case MOVEMENT -> {
                p.ring(13, 4, 2, 2); p.path(6, 20, 9, 16, 12, 18, 14, 13);
                p.path(9, 12, 11, 9, 15, 10, 17, 13);
            }
            case MACROS -> {
                p.box();
            }
            case RENDER -> {
                p.cubic(2, 12, 2, 12, 5.5f, 5, 12, 5);
                p.cubic(12, 5, 18.5f, 5, 22, 12, 22, 12);
                p.cubic(22, 12, 22, 12, 18.5f, 19, 12, 19);
                p.cubic(12, 19, 5.5f, 19, 2, 12, 2, 12);
                p.ring(12, 12, 3, 2);
            }
            case PLAYER, FRIENDS -> {
                p.ring(12, 7, 4, 2);
                p.cubic(5.5f, 21, 5.5f, 12.3f, 18.5f, 12.3f, 18.5f, 21);
            }
            case CLIENT -> {
                for (int i = 0; i < 8; i++) {
                    double a = i * Math.PI / 4;
                    p.line(12 + 8 * (float) Math.cos(a), 12 + 8 * (float) Math.sin(a),
                            12 + 10 * (float) Math.cos(a), 12 + 10 * (float) Math.sin(a));
                }
                p.ring(12, 12, 8, 2); p.ring(12, 12, 3, 2);
            }
            case ENEMIES -> {
                p.ring(12, 12, 8, 2);
            }
            case UTILITY -> {
                p.cubic(14.7f, 6.3f, 10.7f, 3.8f, 7.1f, 7.5f, 9.3f, 11.7f);
                p.path(9.3f, 11.7f, 3, 18, 6, 21, 12.3f, 14.7f);
                p.cubic(12.3f, 14.7f, 16.5f, 16.9f, 20.2f, 13.3f, 17.7f, 9.3f);
                p.path(17.7f, 9.3f, 15.1f, 11.9f, 13.1f, 9.9f, 15.7f, 7.3f);
            }
            case MISC -> p.box();
            case SILENT -> {
                p.ring(12, 12, 10, 2); p.path(22, 12, 18, 12); p.path(6, 12, 2, 12);
                p.path(12, 6, 12, 2); p.path(12, 22, 12, 18);
            }
            case BLATANT -> {
                p.path(10.3f, 3.9f, 1.8f, 18, 3.5f, 21, 20.5f, 21, 22.2f, 18, 13.7f, 3.9f, 10.3f, 3.9f);
                p.path(12, 9, 12, 13); p.circle(12, 17, 1);
            }
            case SHIELD -> p.path(12, 22, 20, 17, 20, 5, 12, 2, 4, 5, 4, 17, 12, 22);
            case KEY -> {
                p.ring(7.5f, 15.5f, 4.5f, 2); p.path(10.5f, 12.5f, 17.5f, 5.5f);
                p.path(15, 8, 17.5f, 10.5f); p.path(18, 5, 20.5f, 7.5f);
            }
            case SEARCH -> {
                p.ring(11, 11, 7, 2); p.line(20, 20, 16.5f, 16.5f);
            }
            case CLOSE -> { p.ring(12, 12, 10, 2); p.path(15, 9, 9, 15); p.path(9, 9, 15, 15); }
            case CHEVRON_RIGHT -> p.path(9, 6, 15, 12, 9, 18);
            case CHEVRON_DOWN -> p.path(6, 9, 12, 15, 18, 9);
            case BACK -> p.path(15, 6, 9, 12, 15, 18);
            case SLIDERS -> {
                p.path(4, 7, 14, 7); p.path(18, 7, 20, 7); p.ring(16, 7, 2, 2);
                p.path(4, 17, 6, 17); p.path(10, 17, 20, 17); p.ring(8, 17, 2, 2);
            }
            case CHECK -> { p.path(20, 6, 9, 17, 4, 12); }
            case PLUS -> { p.path(12, 5, 12, 19); p.path(5, 12, 19, 12); }
            case MINUS -> p.path(5, 12, 19, 12);
            case INFO -> { p.ring(12, 12, 10, 2); p.circle(12, 8, 1); p.path(12, 12, 12, 16); }
        }
    }

    private static final class Pen {
        private final UiDisplayList.Builder list;
        private final float x, y, scale;
        private final int color;

        private Pen(UiDisplayList.Builder list, float x, float y, float scale, int color) {
            this.list = list; this.x = x; this.y = y; this.scale = scale; this.color = color;
        }

        private void circle(float cx, float cy, float radius) {
            list.circle(x + cx * scale, y + cy * scale, radius * scale, color);
        }

        private void ring(float cx, float cy, float radius, float stroke) {
            float left = x + (cx - radius) * scale, top = y + (cy - radius) * scale;
            float diameter = radius * 2 * scale;
            list.border(left, top, diameter, diameter, radius * scale, stroke * scale, color);
        }

        private void path(float... xy) {
            for (int i = 2; i < xy.length; i += 2) line(xy[i - 2], xy[i - 1], xy[i], xy[i + 1]);
        }

        private void box() {
            path(21, 8, 12, 3, 3, 8, 3, 16, 12, 21, 21, 16, 21, 8);
            path(3, 8, 12, 13, 21, 8); path(12, 13, 12, 21);
        }

        private void cubic(float x0, float y0, float x1, float y1, float x2, float y2, float x3, float y3) {
            float px = x0, py = y0;
            for (int i = 1; i <= 16; i++) {
                float t = i / 16f, u = 1 - t;
                float nx = u*u*u*x0 + 3*u*u*t*x1 + 3*u*t*t*x2 + t*t*t*x3;
                float ny = u*u*u*y0 + 3*u*u*t*y1 + 3*u*t*t*y2 + t*t*t*y3;
                line(px, py, nx, ny); px = nx; py = ny;
            }
        }

        private void line(float x1, float y1, float x2, float y2) {
            float stroke = 2f * scale;
            float ax = x + x1 * scale, ay = y + y1 * scale;
            float bx = x + x2 * scale, by = y + y2 * scale;
            if (Math.abs(ay - by) < 0.001f) {
                list.pill(Math.min(ax, bx) - stroke * .5f, ay - stroke * .5f,
                        Math.abs(bx - ax) + stroke, stroke, color);
                return;
            }
            if (Math.abs(ax - bx) < 0.001f) {
                list.rect(ax - stroke * .5f, Math.min(ay, by) - stroke * .5f,
                        stroke, Math.abs(by - ay) + stroke, stroke * .5f, color);
                return;
            }
            // Overlapping SDF circles form an antialiased round-capped diagonal.
            int steps = Math.max(1, (int) Math.ceil(Math.hypot(bx - ax, by - ay) / (stroke * .65f)));
            for (int i = 0; i <= steps; i++) {
                float t = (float) i / steps;
                list.circle(ax + (bx - ax) * t, ay + (by - ay) * t, stroke * .5f, color);
            }
        }
    }
}
