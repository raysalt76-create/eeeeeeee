package interstellarTikiTiks.apexx.client.hud;

import interstellarTikiTiks.apexx.client.Apexx;
import interstellarTikiTiks.apexx.client.gui.gpuui.framework.UiDisplayList;
import interstellarTikiTiks.apexx.client.gui.gpuui.framework.UiDrawCommand;
import interstellarTikiTiks.apexx.client.gui.gpuui.framework.UiGlass;
import interstellarTikiTiks.apexx.client.gui.gpuui.render.GpuUiRenderer;
import interstellarTikiTiks.apexx.client.manager.ModuleManager;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.theme.AccentState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.item.ItemStack;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Builds every requested HUD surface into one custom-GPU display list per frame. */
public final class GpuHudRenderer {
    /** Per-frame memo of array-list label widths (render thread only; cleared at the start of each array-list draw). */
    private static final java.util.IdentityHashMap<Module, Double> ARRAY_LIST_WIDTHS = new java.util.IdentityHashMap<>();
    private static final float REFERENCE_WIDTH = 960f;
    private static final float REFERENCE_HEIGHT = 540f;
    // Palette for the frame being built; synced from HudTheme (ClickGUI theme / HUD Style) by syncTheme().
    // Liquid Glass resolves to the original white values.
    private static int TEXT = 0xFFFFFFFF;
    private static int MUTED = 0xFFB8B8B8;
    private static int SUBTLE = 0xFF858585;
    private static int ACCENT = 0xFFFFFFFF;
    private static final float INSET = 12f;
    private static final float ROW = 19f;
    private static final float SCALE_EPSILON = 1e-3f;

    private GpuHudRenderer() { }

    public static void render(DrawContext context, float delta) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (context == null || mc == null || mc.player == null || mc.currentScreen != null) return;
        int sw = context.getScaledWindowWidth();
        int sh = context.getScaledWindowHeight();
        float scale = viewportScale(sw, sh);
        var matrices = context.getMatrices();
        matrices.pushMatrix();
        try {
            matrices.scale(scale, scale);
            renderElements(context, HUDManager.getInstance().getElements(),
                    Math.round(sw / scale), Math.round(sh / scale), delta, false);
        } finally {
            matrices.popMatrix();
        }
    }

    /**
     * Draws every widget in layout units (the caller has already applied the viewport scale). Widgets at
     * scale 1 share one display list; a widget with its own Scale is submitted separately under a matrix
     * scaled about its top-left corner, so its layout code stays in unscaled units.
     */
    public static void renderElements(DrawContext context, List<HUDElement> elements, int layoutWidth,
                                      int layoutHeight, float delta, boolean editorPreview) {
        if (context == null || elements == null) return;
        syncTheme();
        UiDisplayList.Builder shared = UiDisplayList.builder();
        boolean anyScaled = false;
        for (HUDElement element : elements) {
            if (ownsScale(element)) {
                appendElement(shared, element, layoutWidth, layoutHeight, delta, editorPreview);
            } else {
                anyScaled = true;
            }
        }
        GpuUiRenderer.render(context, shared.build());
        if (!anyScaled) return;
        var matrices = context.getMatrices();
        for (HUDElement element : elements) {
            if (ownsScale(element)) continue;
            UiDisplayList.Builder single = UiDisplayList.builder();
            appendElement(single, element, layoutWidth, layoutHeight, delta, editorPreview);
            UiDisplayList list = single.build();
            if (list.commands().isEmpty()) continue;
            float s = (float) element.getScale();
            float ox = resolveX(element, (float) element.getWidth(), layoutWidth), oy = (float) element.getY();
            matrices.pushMatrix();
            try {
                matrices.translate(ox, oy);
                matrices.scale(s, s);
                matrices.translate(-ox, -oy);
                GpuUiRenderer.render(context, list);
            } finally {
                matrices.popMatrix();
            }
        }
    }

    /** True when the widget can share the unscaled list: scale 1, or it applies its own scale (TargetHUD). */
    private static boolean ownsScale(HUDElement element) {
        return element instanceof TargetHudElement || Math.abs(element.getScale() - 1.0) < SCALE_EPSILON;
    }

    /** True for elements drawn here (or not drawn at all); the rest use the legacy Renderer path. */
    public static boolean isGpuWidget(HUDElement element) {
        return element instanceof GpuWidget || element instanceof GpuHudElement || element instanceof FPSHudElement
                || element instanceof CoordinatesHudElement || element instanceof TargetHudElement
                || element instanceof ArmorHudElement || element instanceof BrandingHudElement
                || element instanceof CpsHudElement
                || element instanceof HudStyleElement;
    }

    private static void syncTheme() {
        HudTheme.refresh();
        syncPalette();
    }

    /** Copies the current HudTheme palette (HUD-wide, or one widget's own colours) into the frame fields. */
    private static void syncPalette() {
        TEXT = HudTheme.text();
        MUTED = HudTheme.muted();
        SUBTLE = HudTheme.subtle();
        ACCENT = HudTheme.accent();
    }

    /** Scale custom HUD widgets with the logical viewport while preserving their saved layout coordinates. */
    public static float viewportScale(int screenWidth, int screenHeight) {
        float fit = Math.min(screenWidth / REFERENCE_WIDTH, screenHeight / REFERENCE_HEIGHT);
        float scale = Math.max(0.62f, Math.min(1.12f, fit));
        // Mobile launchers render the HUD (and its editor) far too large on a small physical screen;
        // shrink it there so the client reads at a sane size. Per-widget scale still applies on top.
        if (interstellarTikiTiks.apexx.client.gui.gpuui.GpuClickGuiScreen.isMobileClient()) scale *= 0.8f;
        return scale;
    }

    /** Editor and gameplay share the same display-list widgets and measurement path. */
    public static UiDisplayList build(List<HUDElement> elements, int screenWidth, int screenHeight,
                                      float delta, boolean editorPreview) {
        UiDisplayList.Builder b = UiDisplayList.builder();
        appendTo(b, elements, screenWidth, screenHeight, delta, editorPreview);
        return b.build();
    }

    public static void appendTo(UiDisplayList.Builder b, List<HUDElement> elements, int screenWidth,
                                int screenHeight, float delta, boolean editorPreview) {
        if (elements == null) return;
        syncTheme();
        for (HUDElement element : elements) appendElement(b, element, screenWidth, screenHeight, delta, editorPreview);
    }

    /** Draws one widget, with its own colours swapped into the palette when its "Use Own Colours" is on. */
    private static void appendElement(UiDisplayList.Builder b, HUDElement element, int screenWidth,
                                      int screenHeight, float delta, boolean editorPreview) {
        boolean own = HudTheme.enter(element);
        if (own) syncPalette();
        try {
            appendElementBody(b, element, screenWidth, screenHeight, delta, editorPreview);
        } finally {
            if (own) {
                HudTheme.exit();
                syncPalette();
            }
        }
    }

    private static void appendElementBody(UiDisplayList.Builder b, HUDElement element, int screenWidth,
                                          int screenHeight, float delta, boolean editorPreview) {
        if (element instanceof HudStyleElement) return;
        // Per-widget "Only In Combat": hidden in-game outside combat, always shown in the editor.
        if (!editorPreview && element.isEnabled() && element.isHiddenOutOfCombat()) {
            if (element instanceof GpuHudElement gpu && gpu.kind() == GpuHudElement.Kind.ARRAY_LIST) {
                ArrayListAnimator.reset();
            }
            return;
        }
        if (element instanceof GpuWidget widget) {
            if (widget.isEnabled() || editorPreview) {
                widget.appendGpu(b, screenWidth, screenHeight, delta, widget.isEnabled() ? 1f : 0.55f, editorPreview);
            }
        } else if (element instanceof GpuHudElement gpu) {
            // The widget's own enabled flag is the only "on" switch (the old ArrayList module is gone).
            if (gpu.isEnabled() || editorPreview) appendGpuElement(b, gpu, screenWidth, screenHeight,
                    editorPreview);
        } else if (element instanceof FPSHudElement fps) {
            if (fps.isEnabled() || editorPreview) appendPerformance(b, fps, screenWidth,
                    fps.isEnabled() ? 1f : 0.55f);
        } else if (element instanceof CoordinatesHudElement coordinates) {
            if (coordinates.isEnabled() || editorPreview) appendCoordinates(b, coordinates, screenWidth,
                    coordinates.isEnabled() ? 1f : 0.55f, editorPreview);
        } else if (element instanceof TargetHudElement target) {
            if (target.isEnabled() || editorPreview) {
                target.appendGpu(b, screenWidth, screenHeight, delta, editorPreview);
            }
        } else if (element instanceof BrandingHudElement branding) {
            if (branding.isEnabled() || editorPreview) appendBranding(b, branding, screenWidth,
                    branding.isEnabled() ? 1f : 0.55f);
        } else if (element instanceof ArmorHudElement armor) {
            if (armor.isEnabled() || editorPreview) appendArmor(b, armor, screenWidth,
                    armor.isEnabled() ? 1f : 0.55f, editorPreview);
        } else if (element instanceof CpsHudElement cps) {
            if (cps.isEnabled() || editorPreview) appendCps(b, cps, screenWidth,
                    cps.isEnabled() ? 1f : 0.55f, editorPreview);
        } else if (editorPreview) {
            appendLegacyPreview(b, element, screenWidth);
        }
    }

    private static void appendCps(UiDisplayList.Builder b, CpsHudElement element, int screenWidth,
                                  float opacity, boolean editorPreview) {
        if (!editorPreview) element.sample();
        boolean left = element.showLeft(), right = element.showRight();
        if (!left && !right) {
            if (!editorPreview) {
                element.setWidth(0);
                element.setHeight(0);
                return;
            }
            left = true;
        }
        float font = Math.max(8f, Math.min(18f, element.fontSize()));
        float labelSize = font - 2.5f;
        String l = cpsText(element.leftCps()), r = cpsText(element.rightCps());
        float width = 24f + GpuUiRenderer.textWidth("CPS", labelSize) + 6f;
        if (left) width += GpuUiRenderer.textWidth(l, font);
        if (left && right) width += GpuUiRenderer.textWidth(" / ", font);
        if (right) width += GpuUiRenderer.textWidth(r, font);
        width = Math.max(width, element.showGraph() ? 96f : 0f);
        float rowHeight = font + 12f;
        float graphHeight = element.showGraph() ? 26f : 0f;
        float height = rowHeight + (graphHeight > 0 ? graphHeight + 6f : 0f);
        float x = resolveX(element, width, screenWidth), y = (float) element.getY();
        element.setWidth(width);
        element.setHeight(height);
        float radius = Math.min(rowHeight * .5f, element.cornerRadius());
        styledCard(b, x, y, width, height, radius, opacity, element.bgOpacity(), element.showBorder(), false, 1f,
                element.isEnabled());
        float cx = x + 12f, ty = y + (rowHeight - font) * .5f;
        b.text(cx, ty + 1, labelSize, tint(MUTED, opacity), "CPS", UiDrawCommand.TextWeight.SEMIBOLD);
        cx += GpuUiRenderer.textWidth("CPS", labelSize) + 6f;
        if (left) {
            b.text(cx, ty, font, tint(element.leftColour(), opacity), l, UiDrawCommand.TextWeight.SEMIBOLD);
            cx += GpuUiRenderer.textWidth(l, font);
        }
        if (left && right) {
            b.text(cx, ty, font, tint(SUBTLE, opacity), " / ", UiDrawCommand.TextWeight.SEMIBOLD);
            cx += GpuUiRenderer.textWidth(" / ", font);
        }
        if (right) b.text(cx, ty, font, tint(element.rightColour(), opacity), r, UiDrawCommand.TextWeight.SEMIBOLD);
        if (graphHeight <= 0) return;

        float gx = x + 10f, gy = y + rowHeight, gw = width - 20f;
        b.rect(gx, gy, gw, graphHeight, 3f, tint(0x40000000, opacity));
        b.border(gx, gy, gw, graphHeight, 3f, .7f, tint(HudTheme.rim(0x26), opacity));
        int samples = CpsHudElement.HISTORY - 1;
        float step = gw / samples;
        float max = 20f;
        for (int i = 0; i < samples; i++) {
            float bx = gx + i * step;
            if (left) {
                float v = Math.min(element.leftHistory(i), max);
                float bh = Math.max(1f, v / max * (graphHeight - 2f));
                b.rect(bx, gy + graphHeight - 1f - bh, Math.max(.5f, step - .6f), bh, 0,
                        tint(element.leftColour(), opacity * .45f));
            }
            if (right) {
                float v = Math.min(element.rightHistory(i), max);
                float bh = Math.max(1f, v / max * (graphHeight - 2f));
                b.rect(bx, gy + graphHeight - 1f - bh, Math.max(.5f, step - .6f), bh, 0,
                        tint(element.rightColour(), opacity * .35f));
            }
        }
    }

    // Integer CPS values for 0..99 are cached so the counter never formats a string per frame.
    private static final String[] SMALL_INTS = new String[100];

    private static String cpsText(float cps) {
        int value = Math.max(0, Math.round(cps));
        if (value >= SMALL_INTS.length) return Integer.toString(value);
        String cached = SMALL_INTS[value];
        if (cached == null) cached = SMALL_INTS[value] = Integer.toString(value);
        return cached;
    }

    private static void appendBranding(UiDisplayList.Builder b, BrandingHudElement element,
                                       int screenWidth, float opacity) {
        MinecraftClient mc = MinecraftClient.getInstance();
        String label = element.getLabelSetting().getValue();
        if (label == null || label.isBlank()) label = "Apexx";
        label = label.trim();
        if (label.length() > 24) label = label.substring(0, 23) + "…";

        StringBuilder detail = new StringBuilder();
        if (element.getShowUsername().isEnabled() && mc.getSession() != null) {
            detail.append(mc.getSession().getUsername());
        }
        if (element.getShowClock().isEnabled()) {
            if (!detail.isEmpty()) detail.append("  /  ");
            detail.append(java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")));
        }
        if (detail.isEmpty()) detail.append("UTILITY CLIENT");

        float nameSize = 10.5f;
        float detailSize = 7.5f;
        float tile = 20f;
        float width = 12f + tile + 9f + GpuUiRenderer.textWidth(label, nameSize)
                + 14f + GpuUiRenderer.textWidth(detail.toString(), detailSize) + 10f;
        float height = 30f;
        float x = resolveX(element, width, screenWidth), y = (float) element.getY();
        element.setWidth(width);
        element.setHeight(height);
        apexxPanel(b, x, y, width, height, opacity, element.isEnabled());

        int accent = tint(ACCENT, opacity);
        float tileX = x + 8f, tileY = y + 5f;
        b.rect(tileX, tileY, tile, tile, 3f, tint(0x66000000, opacity));
        b.border(tileX, tileY, tile, tile, 3f, 1f, tint(ACCENT, opacity * 0.62f));
        b.text(tileX + 5.2f, tileY + 5.2f, 9f, accent, "A", UiDrawCommand.TextWeight.SEMIBOLD);
        b.text(x + 37f, y + 5f, nameSize, tint(TEXT, opacity), label.toUpperCase(Locale.ROOT),
                UiDrawCommand.TextWeight.SEMIBOLD);
        b.text(x + 37f, y + 18f, detailSize, tint(MUTED, opacity), detail.toString());
    }

    private static String titleCase(String value) {
        if (value == null || value.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(value.length());
        for (String word : value.split("[_ ]+")) {
            if (word.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1).toLowerCase(Locale.ROOT));
        }
        return sb.toString();
    }

    private static void appendArmor(UiDisplayList.Builder b, ArmorHudElement element,
                                    int screenWidth, float opacity, boolean editorPreview) {
        ArmorHudElement.ArmorLayout layout = element.getArmorLayout();
        List<ArmorHudElement.SlotPlacement> slots = layout.slots();
        if (slots.isEmpty() && !editorPreview) {
            element.setWidth(0);
            element.setHeight(0);
            return;
        }
        float width = layout.width(), height = layout.height();
        float x = resolveX(element, width, screenWidth), y = (float) element.getY();
        element.setWidth(width);
        element.setHeight(height);
        apexxPanel(b, x, y, width, height, opacity, element.isEnabled());
        b.text(x + 8f, y + 5f, 7.2f, tint(MUTED, opacity), "EQUIPMENT", UiDrawCommand.TextWeight.SEMIBOLD);
        if (slots.isEmpty()) {
            b.text(x + 8f, y + 29f, 8f, tint(SUBTLE, opacity), "NO EQUIPMENT", UiDrawCommand.TextWeight.SEMIBOLD);
            return;
        }

        for (ArmorHudElement.SlotPlacement slot : slots) {
            float cellX = x + slot.cellX(), cellY = y + slot.cellY();
            b.rect(cellX, cellY, slot.cellWidth(), slot.cellHeight(), 3f, tint(0x80000000, opacity));
            b.border(cellX, cellY, slot.cellWidth(), slot.cellHeight(), 3f, 0.7f, tint(HudTheme.rim(0x30), opacity));
            if (slot.item().stack().isEmpty()) {
                String label = slot.item().label();
                float labelWidth = GpuUiRenderer.textWidth(label, 7f);
                b.text(cellX + (slot.cellWidth() - labelWidth) * 0.5f,
                        cellY + (slot.cellHeight() - 7f) * 0.5f, 7f, tint(SUBTLE, opacity), label,
                        UiDrawCommand.TextWeight.SEMIBOLD);
            }
            ItemStack stack = slot.item().stack();
            if (element.getShowDurability().isEnabled() && !stack.isEmpty() && stack.isDamageable()) {
                float ratio = Math.max(0f, Math.min(1f, 1f - stack.getDamage() / (float) stack.getMaxDamage()));
                float barWidth = slot.detailWidth();
                float barX = x + slot.detailX(), barY = y + slot.detailY();
                if (!element.isHorizontal()) {
                    b.text(barX, barY, 7f, tint(TEXT, opacity), slot.item().label(), UiDrawCommand.TextWeight.SEMIBOLD);
                    barY = y + slot.cellY() + 14f;
                }
                b.rect(barX, barY, barWidth, 2f, 1f, tint(HudTheme.rim(0x40), opacity));
                int durabilityColor = ratio > 0.5f ? 0xFF65DCA8 : ratio > 0.2f ? 0xFFFFC45C : 0xFFFF7772;
                b.rect(barX, barY, Math.max(1f, barWidth * ratio), 2f, 1f, tint(durabilityColor, opacity));
            }
            if (element.getShowPercentage().isEnabled() && !stack.isEmpty() && stack.isDamageable()) {
                int percent = Math.max(0, Math.min(100,
                        Math.round(100f * (1f - stack.getDamage() / (float) stack.getMaxDamage()))));
                String value = percent + "%";
                float valueWidth = GpuUiRenderer.textWidth(value, 7f);
                float px = element.isHorizontal()
                        ? x + slot.cellX() + (slot.cellWidth() - valueWidth) * 0.5f
                        : x + slot.detailX() + (slot.detailWidth() - valueWidth);
                float py = element.isHorizontal() ? y + slot.detailY() + 4f : y + slot.cellY() + 2f;
                b.text(px, py, 7f, tint(percent > 50 ? 0xFF9CE9C4 : percent > 20 ? 0xFFFFD180 : 0xFFFF938C, opacity),
                        value, UiDrawCommand.TextWeight.SEMIBOLD);
            }
        }
    }

    /** Liquid-glass pane: refracted blurred backdrop, dark tint, specular sheen and a rim light. */
    public static void glassPane(UiDisplayList.Builder b, float x, float y, float w, float h,
                                 float radius, float opacity, float strength) {
        // Shared with the ClickGUI so the menu's liquid glass is the same material as the HUD's. With the
        // Liquid Glass theme and default HUD Style this is exactly UiGlass.pane(..., strength).
        UiGlass.pane(b, x, y, w, h, radius, opacity, HudTheme.blur(), HudTheme.bodyTint(strength), HudTheme.rim());
    }

    /**
     * Glass pane with the per-widget card options. Default options (border on, width 1, no gradient)
     * delegate to the shared recipe; otherwise the same layers are drawn with the rims adjusted/omitted
     * and an optional accent wash on top.
     */
    public static void glassPane(UiDisplayList.Builder b, float x, float y, float w, float h, float radius,
                                 float opacity, float strength, boolean showBorder, float borderWidth, boolean gradient) {
        boolean standardRim = showBorder && Math.abs(borderWidth - 1f) < 1e-3f;
        if (standardRim) {
            glassPane(b, x, y, w, h, radius, opacity, strength);
        } else {
            float o = Math.max(0f, Math.min(1f, opacity));
            int rim = HudTheme.rim() & 0x00FFFFFF;
            b.shadow(x, y + 3f, w, h, radius, 12f, 0, tint(0x50000000, o));
            b.glass(x, y, w, h, radius, HudTheme.blur(), tint(HudTheme.bodyTint(strength), o));
            b.gradient(x, y, w, h, radius,
                    tint(0x30000000 | rim, o), tint(0x18000000 | rim, o), tint(0x04000000 | rim, o), tint(0x0A000000 | rim, o));
            b.gradient(x + 1f, y + 1f, w - 2f, Math.max(1f, h * .45f), Math.max(0f, radius - 1f),
                    tint(0x1A000000 | rim, o), tint(0x1A000000 | rim, o), tint(rim, o), tint(rim, o));
            if (showBorder) {
                float bw = Math.max(.3f, borderWidth);
                b.border(x, y, w, h, radius, 0.9f * bw, tint(0x50000000 | rim, o));
                b.border(x + bw, y + bw, w - 2f * bw, h - 2f * bw, Math.max(0f, radius - bw), 0.6f,
                        tint(0x12000000 | rim, o));
            }
        }
        if (gradient) {
            // Accent wash: reads as a coloured glass body without hiding the refraction underneath.
            int accent = HudTheme.accent() & 0x00FFFFFF;
            float o = Math.max(0f, Math.min(1f, opacity));
            b.gradient(x, y, w, h, radius, tint(0x2E000000 | accent, o), tint(0x14000000 | accent, o),
                    tint(0x00000000 | accent, o), tint(0x0C000000 | accent, o));
        }
    }

    /** Clean dark card treatment for untoggled modules, avoiding washed-out liquidglass refraction through the editor backdrop. */
    public static void untoggledPane(UiDisplayList.Builder b, float x, float y, float w, float h, float radius) {
        b.shadow(x, y + 2f, w, h, radius, 10f, 0, 0x66000000);
        b.gradient(x, y, w, h, radius, 0xF0121A25, 0xF00A0F17, 0xF00A0F17, 0xF0121A25);
        b.rect(x + radius * 0.6f, y + 1f, Math.max(0, w - radius * 1.2f), 1f, 0.5f, 0x18FFFFFF);
        b.border(x, y, w, h, radius, 0.9f, 0x2EFFFFFF);
    }

    public static int argb(float alpha, int rgb) {
        return (Math.max(0, Math.min(255, Math.round(alpha))) << 24) | (rgb & 0xFFFFFF);
    }

    public static int tint(int color, float alpha) {
        int a = Math.round(((color >>> 24) & 0xFF) * Math.max(0f, Math.min(1f, alpha)));
        return (a << 24) | (color & 0x00FFFFFF);
    }

    /** Liquid-glass panel treatment. */
    public static void apexxPanel(UiDisplayList.Builder b, float x, float y, float width,
                                  float height, float opacity) {
        apexxPanel(b, x, y, width, height, opacity, opacity >= 0.85f);
    }

    public static void apexxPanel(UiDisplayList.Builder b, float x, float y, float width,
                                  float height, float opacity, boolean enabled) {
        if (!enabled) {
            untoggledPane(b, x, y, width, height, 8f);
            return;
        }
        glassPane(b, x, y, width, height, 8f, opacity, 1f);
    }

    public static float editorX(HUDElement element, int screenWidth, int screenHeight) {
        float scale = viewportScale(screenWidth, screenHeight);
        int layoutWidth = Math.round(screenWidth / scale);
        if (element instanceof TargetHudElement target) return target.getEditorX(layoutWidth) * scale;
        return resolveX(element, (float) element.getWidth(), layoutWidth) * scale;
    }

    public static float editorY(HUDElement element, int screenWidth, int screenHeight) {
        float scale = viewportScale(screenWidth, screenHeight);
        int layoutHeight = Math.round(screenHeight / scale);
        if (element instanceof TargetHudElement target) return target.getEditorY(layoutHeight) * scale;
        return (float) element.getY() * scale;
    }

    public static float editorWidth(HUDElement element, int screenWidth, int screenHeight) {
        float scale = viewportScale(screenWidth, screenHeight);
        if (element instanceof TargetHudElement target) return target.getWidgetWidth() * (float) target.getScale() * scale;
        return (float) element.getWidth() * (float) element.getScale() * scale;
    }

    public static float editorHeight(HUDElement element, int screenWidth, int screenHeight) {
        float scale = viewportScale(screenWidth, screenHeight);
        if (element instanceof TargetHudElement target) return target.getWidgetHeight() * (float) target.getScale() * scale;
        return (float) element.getHeight() * (float) element.getScale() * scale;
    }

    private static void appendGpuElement(UiDisplayList.Builder b, GpuHudElement element,
                                         int screenWidth, int screenHeight, boolean editor) {
        float opacity = element.isEnabled() ? 1f : 0.55f;
        switch (element.kind()) {
            case PERFORMANCE -> appendPerformance(b, element, screenWidth, opacity);
            case COORDINATES -> appendCoordinates(b, element, screenWidth, opacity, editor);
            case KEYBINDS -> appendKeybinds(b, element, screenWidth, opacity);
            case ARRAY_LIST -> appendArrayList(b, element, screenWidth, opacity, editor);
        }
    }

    private static void appendPerformance(UiDisplayList.Builder b, HUDElement element,
                                         int screenWidth, float opacity) {
        float fontSize = element instanceof FPSHudElement fps ? Math.max(8f, Math.min(32f, (float) fps.getFontSize().getValue())) : 11f;
        int fpsValue = MinecraftClient.getInstance().getCurrentFps();
        String[] values = {Integer.toString(Math.max(0, fpsValue)),
                String.format(java.util.Locale.ROOT, "%.1f", HudTelemetry.blocksPerSecond()),
                String.format(java.util.Locale.ROOT, "%.1f", HudTelemetry.ticksPerSecond())};
        String[] labels = {"FPS", "BPS", "TICK"}; // TICK = this client's tick rate; Ping & TPS shows the server rate
        float labelSize = fontSize - 2.5f;
        float width = 16;
        for (int i = 0; i < 3; i++) {
            width += GpuUiRenderer.textWidth(labels[i], labelSize) + 4 + GpuUiRenderer.textWidth(values[i], fontSize) + (i < 2 ? 14 : 0);
        }
        float height = fontSize + 12f;
        float x = resolveX(element, width, screenWidth), y = (float) element.getY();
        element.setWidth(width);
        element.setHeight(height);
        float bgOpacity = element instanceof FPSHudElement fps ? (float) fps.getBgOpacity().getValue() : 0.85f;
        float radius = Math.min(height * .5f, element instanceof FPSHudElement fps ? (float) fps.getCornerRadius().getValue() : 8f);
        boolean border = !(element instanceof FPSHudElement fps) || fps.getShowBorder().isEnabled();
        if (element.isEnabled() && element instanceof FPSHudElement fps && fps.getGlowEffect().isEnabled()) {
            float g = (float) fps.getGlowSize().getValue() / 3f;
            b.bloom(x - g, y - g, width + 2 * g, height + 2 * g, radius + g, (float) fps.getGlowIntensity().getValue());
        }
        boolean gradient = element instanceof FPSHudElement fps && fps.getGradientBackground().isEnabled();
        float borderWidth = element instanceof FPSHudElement fps ? (float) fps.getBorderWidth().getPreciseValue() : 1f;
        styledCard(b, x, y, width, height, radius, opacity, bgOpacity, border, gradient, borderWidth, element.isEnabled());
        int accent = ACCENT; // tinted once, when drawn below
        float cx = x + 12;
        float ty = y + (height - fontSize) * .5f;
        for (int i = 0; i < 3; i++) {
            b.text(cx, ty + 1, labelSize, tint(MUTED, opacity), labels[i], UiDrawCommand.TextWeight.SEMIBOLD);
            cx += GpuUiRenderer.textWidth(labels[i], labelSize) + 4;
            int valueColor = TEXT;
            if (i == 0 && element instanceof FPSHudElement fpsSetting && fpsSetting.getColorCodedFPS().isEnabled()) {
                valueColor = fpsValue < 30 ? 0xFFFF736D : fpsValue < 60 ? 0xFFFFC45C : accent;
            }
            b.text(cx, ty, fontSize, tint(valueColor, opacity), values[i], UiDrawCommand.TextWeight.SEMIBOLD);
            cx += GpuUiRenderer.textWidth(values[i], fontSize);
            if (i < 2) cx += 14;
        }
    }

    private static void appendCoordinates(UiDisplayList.Builder b, HUDElement element,
                                          int screenWidth, float opacity, boolean editorPreview) {
        MinecraftClient mc = MinecraftClient.getInstance();
        float fontSize = element instanceof CoordinatesHudElement coordinates
                ? (float) coordinates.getFontSize().getValue() : 11.5f;
        float rowHeight = fontSize + 12f;
        List<CoordinatePart> parts = coordinateParts(element, mc, editorPreview);
        float textWidth = 0f;
        for (CoordinatePart part : parts) textWidth += GpuUiRenderer.textWidth(part.text(), fontSize);
        String conversion = element instanceof CoordinatesHudElement c && c.showNetherConversion()
                ? netherConversionLine(mc, editorPreview, c.getUppercase().isEnabled()) : null;
        float conversionSize = Math.max(7f, fontSize * .78f);
        float height = conversion == null ? rowHeight : rowHeight + conversionSize + 3f;
        float width = Math.max(textWidth, conversion == null ? 0f
                : GpuUiRenderer.textWidth(conversion, conversionSize)) + 32f;
        float x = resolveX(element, width, screenWidth), y = (float) element.getY();
        element.setWidth(width);
        element.setHeight(height);
        float bgOpacity = element instanceof CoordinatesHudElement coordinates
                ? (float) coordinates.getBgOpacity().getValue() : 0.91f;
        float radius = element instanceof CoordinatesHudElement coordinates
                ? Math.min(rowHeight * .5f, (float) coordinates.getCornerRadius().getValue()) : rowHeight * .5f;
        boolean border = !(element instanceof CoordinatesHudElement coordinates) || coordinates.getShowBorder().isEnabled();
        boolean gradient = !(element instanceof CoordinatesHudElement coordinates) || coordinates.getGradientBackground().isEnabled();
        float borderWidth = element instanceof CoordinatesHudElement coordinates ? (float) coordinates.getBorderWidth().getValue() : 1f;
        if (element.isEnabled() && element instanceof CoordinatesHudElement coordinates && coordinates.getGlowEffect().isEnabled()) {
            float g = (float) coordinates.getGlowSize().getValue() / 3f;
            b.bloom(x - g, y - g, width + 2 * g, height + 2 * g, radius + g,
                    (float) coordinates.getGlowIntensity().getValue());
        }
        styledCard(b, x, y, width, height, radius, opacity, bgOpacity, border, gradient, borderWidth, element.isEnabled());
        float textX = x + 16f;
        float textY = y + (rowHeight - fontSize) * .5f;
        for (CoordinatePart part : parts) {
            b.text(textX, textY, fontSize, tint(part.color(), opacity), part.text(),
                    UiDrawCommand.TextWeight.SEMIBOLD);
            textX += GpuUiRenderer.textWidth(part.text(), fontSize);
        }
        if (conversion != null) {
            b.text(x + 16f, textY + fontSize + 3f, conversionSize, tint(MUTED, opacity), conversion,
                    UiDrawCommand.TextWeight.SEMIBOLD);
        }
    }

    /**
     * "NETHER  15, -5" in the Overworld (block position / 8) or "OVERWORLD  960, 344" in the Nether
     * (x 8); null in other dimensions or without a world (except the editor's placeholder).
     */
    private static String netherConversionLine(MinecraftClient mc, boolean editorPreview, boolean uppercase) {
        if (mc == null || mc.player == null || mc.world == null) {
            return editorPreview ? (uppercase ? "NETHER  15, -4" : "Nether  15, -4") : null;
        }
        var key = mc.world.getRegistryKey();
        double px = mc.player.getX(), pz = mc.player.getZ();
        String label;
        long cx, cz;
        if (net.minecraft.world.World.OVERWORLD.equals(key)) {
            label = "Nether";
            cx = (long) Math.floor(px / 8.0);
            cz = (long) Math.floor(pz / 8.0);
        } else if (net.minecraft.world.World.NETHER.equals(key)) {
            label = "Overworld";
            cx = (long) Math.floor(px * 8.0);
            cz = (long) Math.floor(pz * 8.0);
        } else {
            return null;
        }
        return (uppercase ? label.toUpperCase(Locale.ROOT) : label) + "  " + cx + ", " + cz;
    }

    private static List<CoordinatePart> coordinateParts(HUDElement element, MinecraftClient mc,
                                                          boolean editorPreview) {
        CoordinatesHudElement coordinates = element instanceof CoordinatesHudElement c ? c : null;
        boolean compact = coordinates != null && coordinates.getMode().getCurrentMode().equalsIgnoreCase("Compact");
        boolean decimals = coordinates != null && coordinates.getShowDecimals().isEnabled();
        int places = coordinates == null ? 1 : Math.max(0, Math.min(3, (int) coordinates.getDecimalPlaces().getValue()));
        boolean colored = coordinates != null && coordinates.getColoredAxes().isEnabled();
        boolean uppercase = coordinates == null || coordinates.getUppercase().isEnabled();
        boolean showFacing = coordinates != null && coordinates.getShowFacing().isEnabled();
        boolean showDimension = coordinates != null && coordinates.getShowDimension().isEnabled();
        boolean showBiome = coordinates != null && coordinates.getShowBiome().isEnabled();
        double[] position = mc.player == null
                ? (editorPreview ? new double[]{120.3, 64.0, -38.7} : null)
                : new double[]{mc.player.getX(), mc.player.getY(), mc.player.getZ()};
        if (position == null) return List.of(new CoordinatePart("—", TEXT));

        List<CoordinatePart> parts = new java.util.ArrayList<>(16);
        boolean themedAxes = coordinates != null && coordinates.themedAxisColours();
        String[] axisNames = {"X", "Y", "Z"};
        for (int i = 0; i < 3; i++) {
            if (i > 0) parts.add(new CoordinatePart(compact ? "  " : "   ", TEXT));
            String axis = uppercase ? axisNames[i] : axisNames[i].toLowerCase(Locale.ROOT);
            parts.add(new CoordinatePart(axis + (compact ? "" : ": "), colored ? HudTheme.axis(i, themedAxes) : TEXT));
            String value;
            if (!decimals || places == 0) {
                value = mc.player == null ? Integer.toString((int) Math.floor(position[i]))
                        : switch (i) {
                            case 0 -> Integer.toString(mc.player.getBlockX());
                            case 1 -> Integer.toString(mc.player.getBlockY());
                            default -> Integer.toString(mc.player.getBlockZ());
                        };
            } else {
                value = String.format(Locale.ROOT, "%." + places + "f", position[i]);
            }
            parts.add(new CoordinatePart(value, TEXT));
        }

        if (showFacing) {
            String facing = mc.player == null ? "north" : mc.player.getHorizontalFacing().asString();
            parts.add(new CoordinatePart("   ", SUBTLE));
            parts.add(new CoordinatePart(uppercase ? facing.toUpperCase(Locale.ROOT) : titleCase(facing),
                    ACCENT));
        }
        if (showDimension) {
            String dimension = mc.world == null ? (editorPreview ? "Overworld" : "")
                    : titleCase(mc.world.getRegistryKey().getValue().getPath());
            if (!dimension.isBlank()) {
                parts.add(new CoordinatePart("   ", SUBTLE));
                parts.add(new CoordinatePart(dimension, MUTED));
            }
        }
        if (showBiome) {
            String biome = "";
            if (mc.world != null && mc.player != null) {
                biome = mc.world.getBiome(mc.player.getBlockPos()).getKey()
                        .map(key -> titleCase(key.getValue().getPath())).orElse("");
            } else if (editorPreview) {
                biome = "Plains";
            }
            if (!biome.isBlank()) {
                parts.add(new CoordinatePart("   ", SUBTLE));
                parts.add(new CoordinatePart(biome, MUTED));
            }
        }
        return parts;
    }

    private record CoordinatePart(String text, int color) { }

    /** One line of the bound-modules list: a module bind or a setting-level bind. */
    private record BindRow(String name, String key, String mode, boolean enabled, boolean active) { }

    private static boolean isBoundKey(int key) {
        return key != -1 && key != 0;
    }

    /** Mouse binds are stored as negative codes (-(button + 100)); keyboard binds are positive. */
    private static boolean passesMouseFilter(GpuHudElement element, int key) {
        return element.includeMouseBinds() || key > 0;
    }

    private static List<BindRow> bindRows(GpuHudElement element) {
        java.util.ArrayList<BindRow> rows = new java.util.ArrayList<>();
        for (Module module : modules()) {
            if (module.isHidden()) continue;
            int key = module.getKeyCode();
            if (isBoundKey(key) && passesMouseFilter(element, key)
                    && (!element.onlyEnabledModules() || module.isEnabled())) {
                rows.add(new BindRow(module.getName(), module.getKeyDisplayName(), module.getBindModeLabel(),
                        module.isEnabled(), module.isEnabled() && module.isTriggerActive()));
            }
            if (!element.includeSettingBinds()) continue;
            for (interstellarTikiTiks.apexx.client.module.Setting setting : module.getSettings()) {
                if (!(setting instanceof interstellarTikiTiks.apexx.client.module.BooleanSetting bs)
                        || !bs.isBindable()) continue;
                int skey = bs.getKeyCode();
                if (!isBoundKey(skey) || !passesMouseFilter(element, skey)
                        || (element.onlyEnabledModules() && !bs.isEnabled())) continue;
                rows.add(new BindRow(module.getName() + " \u00b7 " + bs.getName(), Module.getSingleKeyName(skey),
                        bs.isKeyHoldMode() ? "HOLD" : "TOGGLE", bs.isEnabled(), false));
            }
        }
        rows.sort(Comparator.comparing((BindRow r) -> !r.active())
                .thenComparing(r -> !r.enabled())
                .thenComparing(BindRow::name, String.CASE_INSENSITIVE_ORDER));
        return rows;
    }

    private static void appendKeybinds(UiDisplayList.Builder b, GpuHudElement element,
                                       int screenWidth, float opacity) {
        List<BindRow> bound = bindRows(element);
        int shown = Math.min(element.maxRows(), bound.size());
        boolean overflow = bound.size() > shown;
        int rows = Math.max(1, shown) + (overflow ? 1 : 0);
        float headerHeight = element.showHeader() ? 29f : 8f;
        float height = headerHeight + rows * ROW + 7;
        float width = 186;
        for (int i = 0; i < shown; i++) {
            BindRow row = bound.get(i);
            float modeW = element.showMode() ? GpuUiRenderer.textWidth(row.mode(), 7.5f) + 14 : 0;
            width = Math.max(width, 32 + GpuUiRenderer.textWidth(abbreviate(row.name(), 21), 10.5f) + modeW
                    + GpuUiRenderer.textWidth(abbreviate(row.key(), 10), 8.5f) + 33);
        }
        width = Math.min(320, width);
        float x = resolveX(element, width, screenWidth), y = (float) element.getY();
        element.setWidth(width);
        element.setHeight(height);
        apexxPanel(b, x, y, width, height, opacity, element.isEnabled());
        if (element.showHeader()) {
            b.text(x + INSET, y + 8, 8.5f, tint(MUTED, opacity), "BOUND MODULES",
                    UiDrawCommand.TextWeight.SEMIBOLD);
            b.text(x + width - 25, y + 7, 8.5f, tint(SUBTLE, opacity), Integer.toString(bound.size()),
                    UiDrawCommand.TextWeight.SEMIBOLD);
        }
        float cy = y + headerHeight;
        if (bound.isEmpty()) {
            b.text(x + INSET, cy + 1, 9f, tint(SUBTLE, opacity),
                    element.onlyEnabledModules() ? "No enabled bindings" : "No bindings assigned");
            return;
        }
        for (int i = 0; i < shown; i++) {
            BindRow row = bound.get(i);
            // Filled bright dot: acting right now. Accent dot: toggled on (armed). Grey: off.
            int state = row.active() ? tint(TEXT, opacity)
                    : row.enabled() ? tint(ACCENT, opacity * .7f) : tint(SUBTLE, opacity);
            b.circle(x + 14, cy + 8, row.active() ? 3f : 2f, state);
            b.text(x + 22, cy + 3, 10.5f, tint(row.enabled() ? TEXT : MUTED, opacity),
                    abbreviate(row.name(), 21));
            String key = abbreviate(row.key(), 10);
            float badgeWidth = Math.max(30, GpuUiRenderer.textWidth(key, 8f) + 12);
            float bx = x + width - badgeWidth - 10;
            b.pill(bx, cy + 1, badgeWidth, 15, tint(HudTheme.rim(row.active() ? 0x55 : 0x28), opacity));
            b.text(bx + (badgeWidth - GpuUiRenderer.textWidth(key, 8f)) * .5f, cy + 4,
                    8f, tint(row.active() ? TEXT : MUTED, opacity), key, UiDrawCommand.TextWeight.SEMIBOLD);
            if (element.showMode()) {
                float modeW = GpuUiRenderer.textWidth(row.mode(), 7.5f);
                b.text(bx - modeW - 8, cy + 4.5f, 7.5f, tint(SUBTLE, opacity), row.mode(),
                        UiDrawCommand.TextWeight.SEMIBOLD);
            }
            cy += ROW;
        }
        if (overflow) b.text(x + INSET, cy + 3, 9f, tint(SUBTLE, opacity), "+" + (bound.size() - shown) + " more");
    }

    private static void appendArrayList(UiDisplayList.Builder b, GpuHudElement element,
                                        int screenWidth, float opacity, boolean editor) {
        // Everything comes from the widget: its settings for the look, the HUD palette (or the widget's own
        // colours, swapped in by appendElement) for the colours.
        float fontSize = Math.max(8f, Math.min(24f, element.listTextSize()));
        float padding = element.listPadding();
        float spacing = element.listSpacing();
        boolean drawBackground = element.listBackground();
        boolean drawOutline = element.listOutline();
        boolean accentText = element.listAccentText();
        float backgroundOpacity = Math.max(0f, Math.min(1f, element.listBgOpacity()));
        int accent = ACCENT;

        String sortMode = element.listSort();
        // Text widths measured once per module per frame: the comparator runs O(n log n) times per sort and the
        // list is sorted twice per frame (here and in ArrayListAnimator), each call re-measuring the label.
        ARRAY_LIST_WIDTHS.clear();
        Comparator<Module> byWidth = Comparator.comparingDouble((Module m) -> ARRAY_LIST_WIDTHS.computeIfAbsent(m,
                k -> (double) GpuUiRenderer.textWidth(element.listLabel(k), fontSize)))
                .reversed();
        Comparator<Module> order;
        if ("Alphabetical".equalsIgnoreCase(sortMode)) {
            order = Comparator.comparing(element::listLabel, String.CASE_INSENSITIVE_ORDER);
        } else if ("Category".equalsIgnoreCase(sortMode)) {
            order = Comparator.comparingInt((Module m) -> m.getCategory() == null ? Integer.MAX_VALUE
                    : m.getCategory().ordinal()).thenComparing(byWidth);
        } else {
            order = byWidth;
        }
        List<Module> active = modules().stream()
                .filter(m -> m.isEnabled() && !m.isHidden())
                .sorted(order)
                .toList();

        // Enter/exit animation (in-game only; the editor shows the settled list).
        String animation = element.listAnimation();
        boolean animate = !editor && animation != null && !"None".equalsIgnoreCase(animation);
        float speed = Math.max(0.25f, Math.min(3f, element.listAnimationSpeed()));
        List<ArrayListAnimator.Row> rows;
        if (animate) {
            rows = ArrayListAnimator.update(active, order, ArrayListAnimator.BASE_DURATION_MS / speed);
        } else {
            if (!editor) ArrayListAnimator.reset();
            rows = new java.util.ArrayList<>(active.size());
            for (Module m : active) {
                ArrayListAnimator.Row row = new ArrayListAnimator.Row(m);
                row.progress = 1f;
                row.active = true;
                rows.add(row);
            }
        }
        boolean bounce = "Bounce".equalsIgnoreCase(animation);
        boolean slide = bounce || "Slide".equalsIgnoreCase(animation);

        float pad = Math.max(4f, padding * .8f);
        float bar = 0f;
        float rowHeight = Math.max(14f, fontSize + pad + 3f);
        float gap = Math.max(1f, spacing * .5f);
        float maxRow = 96f;
        float contentHeight = 0f;
        for (ArrayListAnimator.Row row : rows) {
            Module m = row.module;
            if (row.active) {
                maxRow = Math.max(maxRow, rowWidth(element.listLabel(m), fontSize, pad, bar));
            }
            contentHeight += (rowHeight + gap) * ArrayListAnimator.smoothstep(row.progress);
        }
        float height = Math.max(rowHeight, contentHeight - gap);
        float x = resolveX(element, maxRow, screenWidth), y = (float) element.getY();
        element.setWidth(maxRow);
        element.setHeight(height);
        boolean right = element.getX() < 0 || x + maxRow * .5f > screenWidth * .5f;
        if (rows.isEmpty()) {
            if (editor) b.text(x, y + 2, 9.5f, tint(SUBTLE, opacity), "No active modules");
            return;
        }
        float follow = 1f - (float) Math.exp(-ArrayListAnimator.dt() * 18f);
        float targetY = y;
        int index = 0;
        for (ArrayListAnimator.Row row : rows) {
            Module m = row.module;
            // Rows ease into their slot so neighbours slide instead of jumping when one enters or leaves.
            if (!animate || Float.isNaN(row.y)) row.y = targetY;
            else row.y += (targetY - row.y) * follow;
            float cy = row.y;
            targetY += (rowHeight + gap) * ArrayListAnimator.smoothstep(row.progress);

            float shown = row.active && bounce ? ArrayListAnimator.easeOutBack(row.progress)
                    : ArrayListAnimator.easeOutCubic(row.progress);
            float rowOpacity = opacity * Math.max(0f, Math.min(1f, row.progress < 1f ? ArrayListAnimator.easeOutCubic(row.progress) : 1f));
            if (rowOpacity <= 0.01f) {
                index++;
                continue;
            }
            String label = element.listLabel(m);
            String[] parts = label.split(GpuHudElement.SUFFIX_SEPARATOR, 2);
            float nameW = GpuUiRenderer.textWidth(parts[0], fontSize);
            float rw = rowWidth(label, fontSize, pad, bar);
            float rx = right ? x + maxRow - rw : x;
            if (slide && animate) {
                float offset = (1f - shown) * (rw + 12f);
                rx += right ? offset : -offset;
            }
            float fade = Math.max(.55f, 1f - index * .035f);
            int rowAccent = tint(accent, rowOpacity * fade);
            if (drawBackground) {
                if (!element.isEnabled()) {
                    untoggledPane(b, rx, cy, rw, rowHeight, 6f);
                } else {
                    glassPane(b, rx, cy, rw, rowHeight, 6f, rowOpacity * Math.max(0f, Math.min(1f, backgroundOpacity)), 1f);
                }
            }
            if (drawOutline) b.border(rx, cy, rw, rowHeight, 5, .8f, tint(accent, rowOpacity * .4f));
            float tx = rx + pad;
            float ty = cy + (rowHeight - fontSize) * .5f - .5f;
            b.text(tx, ty, fontSize, accentText ? rowAccent : tint(TEXT, rowOpacity), parts[0],
                    UiDrawCommand.TextWeight.SEMIBOLD);
            if (parts.length > 1) b.text(tx + nameW + 4, ty, fontSize - 1.5f, tint(MUTED, rowOpacity), parts[1]);
            index++;
        }
    }

    private static float rowWidth(String label, float fontSize, float pad, float bar) {
        String[] parts = label.split(GpuHudElement.SUFFIX_SEPARATOR, 2);
        float w = GpuUiRenderer.textWidth(parts[0], fontSize);
        if (parts.length > 1) w += 4 + GpuUiRenderer.textWidth(parts[1], fontSize - 1.5f);
        return w + pad * 2 + bar + 1f;
    }

    private static void card(UiDisplayList.Builder b, float x, float y, float w, float h,
                             float radius, float opacity) {
        card(b, x, y, w, h, radius, opacity, opacity >= 0.85f);
    }

    private static void card(UiDisplayList.Builder b, float x, float y, float w, float h,
                             float radius, float opacity, boolean enabled) {
        styledCard(b, x, y, w, h, radius, opacity, 0.91f, true, true, 1f, enabled);
    }

    private static void styledCard(UiDisplayList.Builder b, float x, float y, float w, float h,
                                   float radius, float opacity, float backgroundOpacity, boolean showBorder,
                                   boolean gradient, float borderWidth) {
        styledCard(b, x, y, w, h, radius, opacity, backgroundOpacity, showBorder, gradient, borderWidth, opacity >= 0.85f);
    }

    private static void styledCard(UiDisplayList.Builder b, float x, float y, float w, float h,
                                   float radius, float opacity, float backgroundOpacity, boolean showBorder,
                                   boolean gradient, float borderWidth, boolean enabled) {
        if (!enabled) {
            untoggledPane(b, x, y, w, h, Math.max(radius, 0f));
            return;
        }
        glassPane(b, x, y, w, h, Math.max(radius, 0f), opacity * Math.max(0f, Math.min(1f, backgroundOpacity)), 1f,
                showBorder, borderWidth, gradient);
    }

    /** Layout X of a widget; a right-anchored array list keeps its scaled right edge 16 units from the side. */
    private static float resolveX(HUDElement element, float width, int screenWidth) {
        if (element instanceof GpuHudElement gpu && gpu.kind() == GpuHudElement.Kind.ARRAY_LIST
                && gpu.getX() < 0) return Math.max(8, screenWidth - width * (float) gpu.getScale() - 16);
        return (float) element.getX();
    }

    /**
     * Editor placeholder for widgets that only render in-game. Their live size depends on runtime state
     * (active effects, key layout), so the preview uses a bounded chip instead of the stale in-game size.
     */
    private static void appendLegacyPreview(UiDisplayList.Builder b, HUDElement element, int screenWidth) {
        float width = Math.max(96f, GpuUiRenderer.textWidth(element.getName(), 9f) + 28f);
        float height = 28f;
        float x = resolveX(element, width, screenWidth), y = (float) element.getY();
        element.setWidth(width);
        element.setHeight(height);
        float opacity = element.isEnabled() ? .9f : .55f;
        card(b, x, y, width, height, 8f, opacity, element.isEnabled());
        b.text(x + 12, y + (height - 9) * .5f, 9f,
                tint(element.isEnabled() ? TEXT : MUTED, opacity), element.getName(), UiDrawCommand.TextWeight.SEMIBOLD);
    }

    private static List<Module> modules() {
        try {
            ModuleManager manager = Apexx.getInstance().getModuleManager();
            return manager == null ? List.of() : manager.getModules();
        } catch (Throwable ignored) {
            return List.of();
        }
    }

    private static String abbreviate(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, Math.max(1, max - 1)) + "…";
    }


    private static int colorArgb(java.awt.Color color) {
        if (color == null) return ACCENT;
        return (color.getAlpha() << 24) | (color.getRed() << 16) | (color.getGreen() << 8) | color.getBlue();
    }

    private static int colorWithOpacity(java.awt.Color color, float opacity) {
        int rgba = colorArgb(color);
        int alpha = (int) Math.max(0, Math.min(255, ((rgba >>> 24) * opacity)));
        return (alpha << 24) | (rgba & 0x00FFFFFF);
    }

}
