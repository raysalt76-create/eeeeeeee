package interstellarTikiTiks.apexx.client.gui.gpuui.framework;

import java.util.Locale;
import java.util.Objects;
import net.minecraft.util.Identifier;

/** Icon paths used by the GPU click GUI. */
public final class ClassicIcons {
    private static final String PREFIX = "gpuui/classic_icons/";

    private ClassicIcons() { }

    /** Mirrors the original CATICON map, including its box fallback. */
    public static String forCategory(String category) {
        if (category == null) return "box";
        return switch (category.toUpperCase(Locale.ROOT)) {
            case "COMBAT" -> "sword";
            case "MOVEMENT", "MOVE" -> "move";
            case "AUTOMATIONS" -> "wrench";
            case "RENDER" -> "eye";
            // Every rail destination gets its own glyph so no two look alike.
            case "PLAYER" -> "person";
            case "FRIENDS" -> "friends";
            case "BLATANT" -> "alert";
            case "SMP" -> "smp";
            case "BEDWARS" -> "bed";
            case "MISC" -> "box";
            case "MACROS" -> "macros";
            case "UTILITY" -> "wrench";
            case "CLIENT" -> "gear";
            case "ENEMIES" -> "crosshair";
            case "SILENT" -> "crosshair";
            default -> "box";
        };
    }

    /** Classic rows use the category symbol for every module in that category. */
    public static String forModule(String category, String moduleName) {
        return forCategory(category);
    }

    /** Exact extra rail symbols from Classic's inline svg calls. */
    public static String forRail(String rail) {
        if (rail == null) return "box";
        return switch (rail.toUpperCase(Locale.ROOT)) {
            case "CONFIGS" -> "configs";
            case "MACROS" -> "macros";
            case "FRIENDS" -> "friends";
            case "SETTINGS" -> "gear";
            default -> forCategory(rail);
        };
    }

    /** The image command multiplies alpha and tint with the white SVG raster. */
    public static void draw(UiDisplayList.Builder list, String icon, float x, float y,
                            float size, int argb) {
        Objects.requireNonNull(list, "list");
        if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(size) || size <= 0f) return;
        list.image(x, y, size, size, 0, argb, texture(icon));
    }

    public static void drawLogo(UiDisplayList.Builder list, float x, float y,
                                float size, int argb) {
        draw(list, "logo", x, y, size, argb);
    }

    /** Pointer tip position within the 256px cursor sprite, as a fraction of its size. */
    private static final float CURSOR_HOTSPOT = 12.0f / 256.0f;

    /**
     * Draws the SS-Proof stand-in cursor at (mouseX, mouseY) so it reads like the real OS pointer: a fixed on-screen
     * size (independent of the menu's own scale) with a white fill over a dark outline, matching the standard arrow.
     * {@code guiScaleFactor} is {@code Window.getScaleFactor()} (device px per GUI px), so the pointer stays the same
     * physical size at any menu scale or resolution, the way the OS cursor it replaces does.
     */
    public static void drawCursor(UiDisplayList.Builder list, float mouseX, float mouseY, int guiScaleFactor) {
        if (!Float.isFinite(mouseX) || !Float.isFinite(mouseY)) return;
        // ~39 device px tall (1.5x the standard Windows arrow, by request) converted to this menu's GUI pixels.
        float size = 39.0f / Math.max(1, guiScaleFactor);
        float hot = size * CURSOR_HOTSPOT;
        float dx = mouseX - hot;
        float dy = mouseY - hot;
        Identifier sprite = texture("cursor");
        // The sprite is a solid white silhouette (no built-in border): offsetting a dark copy in eight directions
        // gives it the dark outline the OS pointer has, so it stays visible on light backdrops and on a stream.
        float o = Math.max(0.6f, size * 0.07f);
        int outline = 0xE6000000;
        for (int i = 0; i < 8; i++) {
            double a = i * Math.PI / 4.0;
            list.image(dx + (float) Math.cos(a) * o, dy + (float) Math.sin(a) * o, size, size, 0, outline, sprite);
        }
        list.image(dx, dy, size, size, 0, 0xffffffff, sprite);
    }

    public static Identifier texture(String icon) {
        return Identifier.of("dynamic_fps", PREFIX + knownIcon(icon) + ".png");
    }

    private static String knownIcon(String icon) {
        if (icon == null) return "box";
        return switch (icon) {
            case "sword", "move", "eye", "person", "wrench", "box", "gear", "dot",
                 "crosshair", "help", "info", "key", "shield", "close", "search",
                 "alert", "configs", "macros", "friends", "check", "logo", "cursor", "bed", "smp" -> icon;
            default -> "box";
        };
    }
}
