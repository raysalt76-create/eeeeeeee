package interstellarTikiTiks.apexx.client.gui.gpuui.kit;

import interstellarTikiTiks.apexx.client.cosmetics.CapeDesign;
import interstellarTikiTiks.apexx.client.cosmetics.CosmeticCatalog;
import interstellarTikiTiks.apexx.client.cosmetics.CosmeticPrefs;
import interstellarTikiTiks.apexx.client.cosmetics.CosmeticSelection;
import interstellarTikiTiks.apexx.client.gui.gpuui.framework.UiDisplayList;
import interstellarTikiTiks.apexx.client.gui.gpuui.framework.UiDrawCommand;
import interstellarTikiTiks.apexx.client.gui.gpuui.framework.UiSmoothScroll;
import interstellarTikiTiks.apexx.client.gui.gpuui.kit.UiKit.Style;
import interstellarTikiTiks.apexx.client.gui.gpuui.render.GpuUiFontMetrics;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * The Cosmetics page: categories on the left, the items of the chosen category in the middle, and a live,
 * draggable preview of your own player on the right. Everything is unlocked; clicking an item wears it at once.
 * The 3D preview itself is drawn by the host after the menu (it needs the game's entity renderer); this view only
 * lays out its box and handles the drag.
 */
public final class CosmeticsView {

    /** Game-side access. */
    public interface Backend {
        /** False on the free edition or without a premium session: the page draws nothing (its tab is hidden). */
        boolean unlocked();

        CosmeticPrefs prefs();

        /** Texture of a cape design for thumbnails, or null while it is being generated. */
        Identifier capeTexture(CapeDesign design);

        /** Theme accent (RGB). */
        int accentRgb();

        boolean previewAvailable();

        boolean signedIn();

        String syncStatus();

        /** Opens the image picker; one callback runs later with the encoded image or an error. */
        void importImage(Consumer<String> onEncoded, Consumer<String> onError);
    }

    private enum Section {
        CAPES("Capes"), HATS("Hats"), WINGS("Wings"), MAKER("Cape Maker"), SETTINGS("Settings");
        final String label;
        Section(String label) { this.label = label; }
    }

    private static final int[] PALETTE = {0xFFFFFF, 0xC9CED6, 0x5B6270, 0x14182A, 0x000000, 0xE53935, 0xFF7A1A, 0xF5C542,
            0x43A047, 0x1FD1A5, 0x1E88E5, 0x3A6BD8, 0x6A2CC2, 0xE04BB2, 0xFF8FB8, 0x8B5A2B};

    private final Backend backend;
    private Section section = Section.CAPES;
    private final UiSmoothScroll scroll = new UiSmoothScroll();
    private final float[] centerRect = new float[4];
    private final float[] previewRect = new float[4];
    private float contentH;
    private float lastScale = 1f;
    private String status = "";
    private long statusAt = Long.MIN_VALUE;
    private float yaw = -28f;
    private boolean dragging;
    private double dragLastX;
    private long lastSpinAt;
    private final UiTextField textField = new UiTextField("Initials (up to 4)").maxLength(CapeDesign.MAX_TEXT);

    /** A hat or wings tile whose art box the host fills with a live 3D render (framebuffer pixels). */
    public record Thumbnail(float x, float y, float w, float h, CosmeticSelection selection, boolean wings) { }

    private final List<Thumbnail> thumbnails = new ArrayList<>();

    public CosmeticsView(Backend backend) {
        this.backend = backend;
        textField.allow(cp -> Character.isLetterOrDigit(cp) && cp < 128);
        textField.onChange(() -> {
            CapeDesign d = backend.prefs().customDesign().withText(textField.value());
            applyCustom(d);
        });
    }

    // ------------------------------------------------------------------ host hooks

    /** Preview box in framebuffer pixels (x, y, w, h); w is 0 when nothing should be drawn. */
    public float[] previewRect() {
        return previewRect;
    }

    public float previewYaw() {
        return yaw;
    }

    /** This frame's hat and wing thumbnails, to render over the tiles. */
    public List<Thumbnail> thumbnails() {
        return thumbnails;
    }

    /** The scrolling item area (x, y, w, h); thumbnails must be clipped to it. */
    public float[] thumbnailClip() {
        return centerRect;
    }

    public boolean scroll(double mx, double my, double amount, long now) {
        if (inside(mx, my, centerRect)) {
            scroll.wheel(amount, 56f * lastScale, now);
            return true;
        }
        return false;
    }

    public boolean mouseDragged(double mx, double my) {
        if (!dragging) return false;
        yaw += (float) (mx - dragLastX) * 0.55f / Math.max(0.5f, lastScale);
        dragLastX = mx;
        return true;
    }

    public void mouseReleased() {
        dragging = false;
    }

    private static boolean inside(double mx, double my, float[] r) {
        return mx >= r[0] && mx <= r[0] + r[2] && my >= r[1] && my <= r[1] + r[3];
    }

    private void say(String text, UiHost host) {
        status = text == null ? "" : text;
        statusAt = host.now();
    }

    // ------------------------------------------------------------------ layout

    public void draw(UiDisplayList.Builder b, UiHost host, float x, float y, float w, float h, float s, float mx, float my) {
        lastScale = s;
        CosmeticPrefs prefs = backend.prefs();
        thumbnails.clear();
        // Premium only: free sessions never reach this page (the menu hides its tab), so there is no locked card.
        if (!backend.unlocked()) {
            previewRect[2] = 0f;
            return;
        }
        float padX = Math.round(22f * s);
        UiKit.title(b, host, "Cosmetics", x + padX, y + Math.round(20f * s), s);
        String chipText = "Everything unlocked";
        float chipW = GpuUiFontMetrics.textWidth(chipText, 8.5f * s, UiDrawCommand.TextWeight.SEMIBOLD) + 16f * s;
        UiKit.chip(b, host, chipText, x + w - padX - chipW, y + Math.round(24f * s), s, host.accent());
        UiKit.divider(b, host, x + padX, y + Math.round(56f * s), w - padX * 2f);

        float top = y + Math.round(70f * s), bottom = y + h - Math.round(30f * s);
        float gap = Math.round(16f * s);
        float leftW = Math.round(150f * s);
        float avail = w - padX * 2f - leftW - gap * 2f;
        float previewW = Math.round(Math.max(170f * s, Math.min(300f * s, avail * 0.36f)));
        float centerW = avail - previewW;
        float lx = x + padX, cx = lx + leftW + gap, px = cx + centerW + gap;

        drawSections(b, host, prefs, lx, top, leftW, bottom - top, s, mx, my);
        drawCenter(b, host, prefs, cx, top, centerW, bottom - top, s, mx, my);
        drawPreview(b, host, prefs, px, top, previewW, bottom - top, s, mx, my);

        if (!status.isEmpty() && host.now() - statusAt < 6_000_000_000L) {
            b.text(lx, bottom + Math.round(10f * s), 10f * s, host.accent(), UiKit.fit(status, w - padX * 2f, 10f * s));
        } else {
            b.text(lx, bottom + Math.round(10f * s), 10f * s, host.muted2Color(),
                    UiKit.fit("Click an item to wear it. Your choices follow your account, not your config.", w - padX * 2f, 10f * s));
        }
    }

    private void drawSections(UiDisplayList.Builder b, UiHost host, CosmeticPrefs prefs, float x, float y, float w, float h,
                              float s, float mx, float my) {
        CosmeticSelection sel = prefs.selection();
        float rowH = Math.round(44f * s), gap = Math.round(6f * s);
        float ry = y;
        for (Section sec : Section.values()) {
            boolean active = sec == section;
            boolean hover = host.hovered(mx, my, x, ry, w, rowH);
            float r = Math.round(10f * s * host.cornerScale());
            if (active) {
                b.rect(x, ry, w, rowH, r, UiKit.withAlpha(host.accent(), 0x26));
                b.border(x, ry, w, rowH, r, 1f, UiKit.withAlpha(host.accent(), 0x60));
            } else if (hover) {
                b.rect(x, ry, w, rowH, r, host.controlFill(0x14));
            }
            float ts = 11f * s;
            b.text(x + Math.round(12f * s), ry + Math.round(8f * s), ts, active ? host.accent() : host.textColor(), sec.label,
                    UiDrawCommand.TextWeight.SEMIBOLD);
            String sub = switch (sec) {
                case CAPES -> sel.cape() == null ? "None" : capeName(sel.cape());
                case HATS -> sel.hat() == null ? "None" : itemName(CosmeticCatalog.HATS, sel.hat().id());
                case WINGS -> sel.wings() == null ? "None" : itemName(CosmeticCatalog.WINGS, sel.wings().type());
                case MAKER -> prefs.customDesign().image() != null ? "Your image" : "Design your own";
                case SETTINGS -> prefs.shareMine() ? "Sharing on" : "Sharing off";
            };
            b.text(x + Math.round(12f * s), ry + Math.round(25f * s), 9f * s, host.muted2Color(), UiKit.fit(sub, w - 24f * s, 9f * s));
            Section target = sec;
            host.hit(x, ry, w, rowH, () -> {
                if (section != target) {
                    section = target;
                    scroll.reset();
                }
            });
            ry += rowH + gap;
        }
    }

    private void drawCenter(UiDisplayList.Builder b, UiHost host, CosmeticPrefs prefs, float x, float y, float w, float h,
                            float s, float mx, float my) {
        centerRect[0] = x; centerRect[1] = y; centerRect[2] = w; centerRect[3] = h;
        scroll.update(host.frameDelta(), h, contentH, host.now());
        float off = scroll.offset();
        b.clipPush(x, y, w, h, 0);
        host.beginHitClip(x, y, w, h);
        float cy = y - off;
        float end = switch (section) {
            case CAPES -> drawCapes(b, host, prefs, x, cy, w, s, mx, my);
            case HATS -> drawHats(b, host, prefs, x, cy, w, s, mx, my);
            case WINGS -> drawWings(b, host, prefs, x, cy, w, s, mx, my);
            case MAKER -> drawMaker(b, host, prefs, x, cy, w, s, mx, my);
            case SETTINGS -> drawSettings(b, host, prefs, x, cy, w, s, mx, my);
        };
        contentH = Math.max(0f, end - cy + Math.round(8f * s));
        host.endHitClip();
        b.clipPop();
        scroll.paintBar(b, x + w + Math.round(3f * s), y, h, s, host.textColor(), host.now());
    }

    // ------------------------------------------------------------------ capes

    private float drawCapes(UiDisplayList.Builder b, UiHost host, CosmeticPrefs prefs, float x, float y, float w, float s,
                            float mx, float my) {
        CosmeticSelection sel = prefs.selection();
        String current = sel.cape() == null ? null : sel.cape().preset();
        int cols = Math.max(2, (int) ((w + 10f * s) / (118f * s)));
        float gap = Math.round(10f * s);
        float tileW = (w - gap * (cols - 1)) / cols, tileH = Math.round(tileW * 1.28f);
        int count = CapeDesign.PRESETS.size() + 2;
        for (int i = 0; i < count; i++) {
            float tx = x + (i % cols) * (tileW + gap), ty = y + (i / cols) * (tileH + gap);
            if (i == 0) {
                tile(b, host, tx, ty, tileW, tileH, s, mx, my, "None", sel.cape() == null, null, 0, () -> {
                    prefs.setSelection(prefs.selection().withCape(null));
                    say("Cape removed.", host);
                });
            } else if (i == 1) {
                CapeDesign custom = prefs.customDesign().withPreset(CapeDesign.CUSTOM);
                tile(b, host, tx, ty, tileW, tileH, s, mx, my, "Your Cape", CapeDesign.CUSTOM.equals(current), custom, 0, () -> {
                    prefs.setSelection(prefs.selection().withCape(custom));
                    say("Wearing your custom cape. Change it in Cape Maker.", host);
                });
            } else {
                CapeDesign.Preset p = CapeDesign.PRESETS.get(i - 2);
                CapeDesign design = "theme".equals(p.id()) ? CapeDesign.themed(backend.accentRgb()) : p.design();
                tile(b, host, tx, ty, tileW, tileH, s, mx, my, p.name(), p.id().equals(current), design, 0, () -> {
                    prefs.setSelection(prefs.selection().withCape(design));
                    say("Wearing " + p.name() + ".", host);
                });
            }
        }
        int rows = (count + cols - 1) / cols;
        return y + rows * (tileH + gap) - gap;
    }

    private void tile(UiDisplayList.Builder b, UiHost host, float x, float y, float w, float h, float s, float mx, float my,
                      String name, boolean equipped, CapeDesign cape, int swatch, Runnable onClick) {
        tile(b, host, x, y, w, h, s, mx, my, name, equipped, cape, swatch, null, false, onClick);
    }

    /**
     * One grid tile: a cape thumbnail (when {@code cape} is set), a live 3D render of {@code model} (a hat or wings,
     * drawn by the host over the art box), or a plain colour swatch when no world is loaded to render in.
     */
    private void tile(UiDisplayList.Builder b, UiHost host, float x, float y, float w, float h, float s, float mx, float my,
                      String name, boolean equipped, CapeDesign cape, int swatch, CosmeticSelection model, boolean wings,
                      Runnable onClick) {
        boolean hover = host.hovered(mx, my, x, y, w, h);
        float r = Math.round(12f * s * host.cornerScale());
        b.rect(x, y, w, h, r, hover ? host.controlFill(0x22) : host.cardFill());
        b.border(x, y, w, h, r, equipped ? Math.max(1f, 2f * s) : 1f, equipped ? UiKit.withAlpha(host.accent(), 0xd0) : 0x14ffffff);
        float labelH = Math.round(30f * s);
        float artH = h - labelH - Math.round(10f * s);
        if (cape != null) {
            float ih = artH, iw = ih * 10f / 16f;
            float ix = x + (w - iw) * 0.5f, iy = y + Math.round(10f * s);
            b.shadow(ix, iy, iw, ih, Math.round(3f * s), Math.round(10f * s), 0f, 0x60000000);
            Identifier tex = backend.capeTexture(cape);
            if (tex != null) {
                b.image(ix, iy, iw, ih, Math.round(3f * s), 0xffffffff, tex, 1f / 64f, 1f / 32f, 11f / 64f, 17f / 32f);
            } else {
                b.gradient(ix, iy, iw, ih, Math.round(3f * s), cape.base1(), cape.base1(), cape.base2(), cape.base2());
            }
        } else if (model != null && backend.previewAvailable()) {
            // A soft glow in the item's colour behind the live model the host renders over this box.
            float ax = x + Math.round(6f * s), ay = y + Math.round(6f * s), aw = w - Math.round(12f * s), ah = artH + Math.round(4f * s);
            float ar = Math.round(9f * s * host.cornerScale());
            int glow = swatch == 0 ? host.accent() : 0xff000000 | swatch;
            b.gradient(ax, ay, aw, ah, ar, 0x00000000, 0x00000000, UiKit.withAlpha(glow, 0x30), UiKit.withAlpha(glow, 0x30));
            float[] c = centerRect;
            if (ay + ah > c[1] && ay < c[1] + c[3]) thumbnails.add(new Thumbnail(ax, ay, aw, ah, model, wings));
        } else if (swatch != 0) {
            float rad = Math.min(artH, w) * 0.30f;
            float ccx = x + w * 0.5f, ccy = y + Math.round(10f * s) + artH * 0.5f;
            b.circle(ccx, ccy, rad + Math.max(1f, s), 0x40000000);
            b.circle(ccx, ccy, rad, swatch | 0xff000000);
        } else {
            float ms = 11f * s;
            String dash = "-";
            b.text(x + (w - GpuUiFontMetrics.textWidth(dash, ms)) * 0.5f, y + Math.round(10f * s) + artH * 0.5f - ms * 0.6f, ms,
                    host.muted2Color(), dash);
        }
        float ns = 10f * s;
        String shown = UiKit.fit(name, w - 12f * s, ns, UiDrawCommand.TextWeight.SEMIBOLD);
        b.text(x + (w - GpuUiFontMetrics.textWidth(shown, ns, UiDrawCommand.TextWeight.SEMIBOLD)) * 0.5f,
                y + h - labelH + Math.round(6f * s), ns, equipped ? host.accent() : host.textColor(), shown, UiDrawCommand.TextWeight.SEMIBOLD);
        if (equipped) {
            String eq = "Equipped";
            float es = 8.5f * s;
            b.text(x + (w - GpuUiFontMetrics.textWidth(eq, es)) * 0.5f, y + h - labelH + Math.round(19f * s), es, host.muted2Color(), eq);
        }
        host.hit(x, y, w, h, onClick);
    }

    private static float luminance(int rgb) {
        return (0.299f * ((rgb >> 16) & 255) + 0.587f * ((rgb >> 8) & 255) + 0.114f * (rgb & 255)) / 255f;
    }

    // ------------------------------------------------------------------ hats

    private float drawHats(UiDisplayList.Builder b, UiHost host, CosmeticPrefs prefs, float x, float y, float w, float s,
                           float mx, float my) {
        CosmeticSelection sel = prefs.selection();
        String current = sel.hat() == null ? null : sel.hat().id();
        int cols = Math.max(2, (int) ((w + 10f * s) / (118f * s)));
        float gap = Math.round(10f * s);
        float tileW = (w - gap * (cols - 1)) / cols, tileH = Math.round(Math.min(tileW, 120f * s));
        List<CosmeticCatalog.Item> hats = CosmeticCatalog.HATS;
        int count = hats.size() + 1;
        for (int i = 0; i < count; i++) {
            float tx = x + (i % cols) * (tileW + gap), ty = y + (i / cols) * (tileH + gap);
            if (i == 0) {
                tile(b, host, tx, ty, tileW, tileH, s, mx, my, "None", current == null, null, 0, () -> {
                    prefs.setSelection(prefs.selection().withHat(null));
                    say("Hat removed.", host);
                });
                continue;
            }
            CosmeticCatalog.Item item = hats.get(i - 1);
            boolean equipped = item.id().equals(current);
            int shown = equipped ? displayColor(sel.hat().color()) : displayColor(item.defaultColor());
            CosmeticSelection model = CosmeticSelection.NONE.withHat(
                    new CosmeticSelection.Hat(item.id(), equipped ? sel.hat().color() : item.defaultColor()));
            tile(b, host, tx, ty, tileW, tileH, s, mx, my, item.name(), equipped, null, shown, model, false, () -> {
                prefs.setSelection(prefs.selection().withHat(new CosmeticSelection.Hat(item.id(), item.defaultColor())));
                say("Wearing " + item.name() + ". " + item.description(), host);
            });
        }
        int rows = (count + cols - 1) / cols;
        float cy = y + rows * (tileH + gap) + Math.round(8f * s);
        if (sel.hat() != null) {
            UiKit.label(b, host, "Hat colour", x, cy, s);
            cy += Math.round(18f * s);
            int color = sel.hat().color();
            cy = swatches(b, host, x, cy, w, s, mx, my, true, color == CosmeticSelection.THEME_COLOR ? -2 : color & 0xFFFFFF, c -> {
                CosmeticSelection.Hat hat = prefs.selection().hat();
                if (hat != null) prefs.setSelection(prefs.selection().withHat(new CosmeticSelection.Hat(hat.id(), c)));
            });
        }
        return cy;
    }

    private int displayColor(int color) {
        return color == CosmeticSelection.THEME_COLOR ? backend.accentRgb() & 0xFFFFFF : color & 0xFFFFFF;
    }

    // ------------------------------------------------------------------ wings

    private float drawWings(UiDisplayList.Builder b, UiHost host, CosmeticPrefs prefs, float x, float y, float w, float s,
                            float mx, float my) {
        CosmeticSelection sel = prefs.selection();
        CosmeticSelection.Wings wings = sel.wings();
        String current = wings == null ? null : wings.type();
        int cols = 3;
        float gap = Math.round(10f * s);
        float tileW = (w - gap * (cols - 1)) / cols, tileH = Math.round(Math.min(tileW, 120f * s));
        tile(b, host, x, y, tileW, tileH, s, mx, my, "None", wings == null, null, 0, () -> {
            prefs.setSelection(prefs.selection().withWings(null));
            say("Wings removed.", host);
        });
        for (int i = 0; i < CosmeticCatalog.WINGS.size(); i++) {
            CosmeticCatalog.Item item = CosmeticCatalog.WINGS.get(i);
            float tx = x + (i + 1) * (tileW + gap);
            boolean equipped = item.id().equals(current);
            // Shown in your current wing colours and size, so the tile matches what you would wear.
            CosmeticSelection model = CosmeticSelection.NONE.withWings(
                    wings == null ? CosmeticSelection.Wings.of(item.id()) : wings.withType(item.id()));
            tile(b, host, tx, y, tileW, tileH, s, mx, my, item.name(), equipped, null, item.defaultColor(), model, true, () -> {
                CosmeticSelection.Wings now = prefs.selection().wings();
                prefs.setSelection(prefs.selection().withWings(now == null ? CosmeticSelection.Wings.of(item.id()) : now.withType(item.id())));
                say("Wearing " + item.name() + ". " + item.description(), host);
            });
        }
        float cy = y + tileH + Math.round(16f * s);
        if (wings == null) return cy;

        UiKit.label(b, host, "Colour", x, cy, s);
        cy += Math.round(18f * s);
        List<String> modes = CosmeticSelection.Wings.COLOR_MODES;
        float segH = Math.round(30f * s);
        UiKit.segmented(b, host, modes.toArray(new String[0]), modes.indexOf(wings.colorMode()), x, cy, w, segH, s, mx, my,
                i -> prefs.setSelection(prefs.selection().withWings(prefs.selection().wings().withColorMode(modes.get(i)))));
        cy += segH + Math.round(10f * s);
        if ("Custom".equals(wings.colorMode())) {
            cy = swatches(b, host, x, cy, w, s, mx, my, false, wings.customColor() & 0xFFFFFF,
                    c -> prefs.setSelection(prefs.selection().withWings(prefs.selection().wings().withCustomColor(c))));
            cy += Math.round(6f * s);
        }
        cy = stepper(b, host, x, cy, w, s, mx, my, "Size", Math.round(wings.scale() * 100f) + "%",
                () -> prefs.setSelection(prefs.selection().withWings(prefs.selection().wings().withScale(prefs.selection().wings().scale() - 0.05f))),
                () -> prefs.setSelection(prefs.selection().withWings(prefs.selection().wings().withScale(prefs.selection().wings().scale() + 0.05f))));
        cy = stepper(b, host, x, cy, w, s, mx, my, "Tip tint", Math.round(wings.tipTint() * 100f) + "%",
                () -> prefs.setSelection(prefs.selection().withWings(prefs.selection().wings().withTip(prefs.selection().wings().tipTint() - 0.05f))),
                () -> prefs.setSelection(prefs.selection().withWings(prefs.selection().wings().withTip(prefs.selection().wings().tipTint() + 0.05f))));
        cy = toggleRow(b, host, x, cy, w, s, "Wing animation",
                "Flap, open and fold with spring physics as you fall, jump, sneak and glide.", wings.animate(),
                () -> prefs.setSelection(prefs.selection().withWings(prefs.selection().wings().withAnimate(!prefs.selection().wings().animate()))));
        return cy;
    }

    // ------------------------------------------------------------------ cape maker

    private float drawMaker(UiDisplayList.Builder b, UiHost host, CosmeticPrefs prefs, float x, float y, float w, float s,
                            float mx, float my) {
        CapeDesign d = prefs.customDesign();
        if (!textField.focused(host) && !textField.value().equals(d.text())) textField.setValue(d.text());
        float cy = y;
        // Big preview of the design with the actions beside it.
        float artH = Math.round(150f * s), artW = artH * 10f / 16f;
        b.shadow(x, cy, artW, artH, Math.round(4f * s), Math.round(14f * s), 0f, 0x70000000);
        Identifier tex = backend.capeTexture(d);
        if (tex != null) b.image(x, cy, artW, artH, Math.round(4f * s), 0xffffffff, tex, 1f / 64f, 1f / 32f, 11f / 64f, 17f / 32f);
        else b.gradient(x, cy, artW, artH, Math.round(4f * s), d.base1(), d.base1(), d.base2(), d.base2());
        float bx = x + artW + Math.round(18f * s), bw = Math.min(w - artW - 18f * s, Math.round(220f * s));
        float bh = Math.round(30f * s), bgap = Math.round(8f * s);
        boolean wearing = prefs.selection().cape() != null && CapeDesign.CUSTOM.equals(prefs.selection().cape().preset());
        UiKit.button(b, host, wearing ? "Wearing this cape" : "Wear this cape", bx, cy, bw, bh, s, Style.PRIMARY, !wearing, mx, my, () -> {
            prefs.setSelection(prefs.selection().withCape(prefs.customDesign().withPreset(CapeDesign.CUSTOM)));
            say("Wearing your custom cape.", host);
        });
        UiKit.button(b, host, "Import image...", bx, cy + bh + bgap, bw, bh, s, Style.SECONDARY, true, mx, my, () -> {
            say("Choose a PNG or JPG. It is shrunk to the cape and kept only in your account prefs.", host);
            backend.importImage(encoded -> {
                applyCustom(prefs.customDesign().withImage(encoded));
                say("Image imported (" + encoded.length() + " bytes stored).", host);
            }, err -> say(err, host));
        });
        UiKit.button(b, host, "Remove image", bx, cy + (bh + bgap) * 2f, bw, bh, s, Style.SECONDARY, d.image() != null, mx, my, () -> {
            applyCustom(prefs.customDesign().withImage(null));
            say("Image removed.", host);
        });
        UiKit.button(b, host, "Reset design", bx, cy + (bh + bgap) * 3f, bw, bh, s, Style.GHOST, true, mx, my, () -> {
            applyCustom(CapeDesign.defaultCustom());
            textField.setValue("");
            say("Design reset.", host);
        });
        cy += Math.max(artH, (bh + bgap) * 4f) + Math.round(16f * s);

        if (d.image() != null) {
            b.text(x, cy, 10f * s, host.muted2Color(), UiKit.fit("Using your image. The border below still applies; remove the image to use colours and patterns.", w, 10f * s));
            cy += Math.round(22f * s);
        } else {
            cy = section(b, host, "Top colour", x, cy, s);
            cy = swatches(b, host, x, cy, w, s, mx, my, false, d.base1() & 0xFFFFFF,
                    c -> applyCustom(prefs.customDesign().withColors(c, d2().base2(), d2().patternColor(), d2().textColor(), d2().emblemColor(), d2().borderColor())));
            cy = section(b, host, "Bottom colour", x, cy, s);
            cy = swatches(b, host, x, cy, w, s, mx, my, false, d.base2() & 0xFFFFFF,
                    c -> applyCustom(prefs.customDesign().withColors(d2().base1(), c, d2().patternColor(), d2().textColor(), d2().emblemColor(), d2().borderColor())));
            cy = section(b, host, "Gradient", x, cy, s);
            cy = choices(b, host, x, cy, w, s, mx, my, CapeDesign.Gradient.values(), d.gradient().ordinal(),
                    i -> applyCustom(d2().withShape(CapeDesign.Gradient.values()[i], d2().pattern(), d2().emblem(), d2().border())));
            cy = section(b, host, "Pattern", x, cy, s);
            cy = choices(b, host, x, cy, w, s, mx, my, CapeDesign.Pattern.values(), d.pattern().ordinal(),
                    i -> applyCustom(d2().withShape(d2().gradient(), CapeDesign.Pattern.values()[i], d2().emblem(), d2().border())));
            if (d.pattern() != CapeDesign.Pattern.NONE) {
                cy = swatches(b, host, x, cy, w, s, mx, my, false, d.patternColor() & 0xFFFFFF,
                        c -> applyCustom(d2().withColors(d2().base1(), d2().base2(), c, d2().textColor(), d2().emblemColor(), d2().borderColor())));
            }
            cy = section(b, host, "Initials", x, cy, s);
            float fh = Math.round(30f * s);
            textField.draw(b, host, x, cy, Math.min(w, Math.round(220f * s)), fh, s);
            cy += fh + Math.round(8f * s);
            if (!d.text().isEmpty()) {
                cy = swatches(b, host, x, cy, w, s, mx, my, false, d.textColor() & 0xFFFFFF,
                        c -> applyCustom(d2().withColors(d2().base1(), d2().base2(), d2().patternColor(), c, d2().emblemColor(), d2().borderColor())));
            }
            cy = section(b, host, "Emblem", x, cy, s);
            cy = choices(b, host, x, cy, w, s, mx, my, CapeDesign.Emblem.values(), d.emblem().ordinal(),
                    i -> applyCustom(d2().withShape(d2().gradient(), d2().pattern(), CapeDesign.Emblem.values()[i], d2().border())));
            if (d.emblem() != CapeDesign.Emblem.NONE) {
                cy = swatches(b, host, x, cy, w, s, mx, my, false, d.emblemColor() & 0xFFFFFF,
                        c -> applyCustom(d2().withColors(d2().base1(), d2().base2(), d2().patternColor(), d2().textColor(), c, d2().borderColor())));
            }
        }
        cy = section(b, host, "Border", x, cy, s);
        cy = choices(b, host, x, cy, w, s, mx, my, CapeDesign.Border.values(), d.border().ordinal(),
                i -> applyCustom(d2().withShape(d2().gradient(), d2().pattern(), d2().emblem(), CapeDesign.Border.values()[i])));
        if (d.border() != CapeDesign.Border.NONE) {
            cy = swatches(b, host, x, cy, w, s, mx, my, false, d.borderColor() & 0xFFFFFF,
                    c -> applyCustom(d2().withColors(d2().base1(), d2().base2(), d2().patternColor(), d2().textColor(), d2().emblemColor(), c)));
        }
        return cy;
    }

    private CapeDesign d2() {
        return backend.prefs().customDesign();
    }

    /** Saves the maker's design and, when the custom cape is being worn, updates what is worn too. */
    private void applyCustom(CapeDesign design) {
        CosmeticPrefs prefs = backend.prefs();
        CapeDesign custom = design.withPreset(CapeDesign.CUSTOM);
        prefs.setCustomDesign(custom);
        CosmeticSelection sel = prefs.selection();
        if (sel.cape() != null && CapeDesign.CUSTOM.equals(sel.cape().preset())) prefs.setSelection(sel.withCape(custom));
    }

    private float section(UiDisplayList.Builder b, UiHost host, String label, float x, float y, float s) {
        UiKit.label(b, host, label, x, y, s);
        return y + Math.round(18f * s);
    }

    /** Wrapping row of pill choices named after the enum constants. */
    private float choices(UiDisplayList.Builder b, UiHost host, float x, float y, float w, float s, float mx, float my,
                          Enum<?>[] values, int selected, IntConsumer onPick) {
        float h = Math.round(26f * s), gap = Math.round(6f * s);
        float cx = x, cy = y;
        for (int i = 0; i < values.length; i++) {
            String name = pretty(values[i].name());
            float bw = UiKit.buttonWidth(name, s) - Math.round(6f * s);
            if (cx + bw > x + w && cx > x) {
                cx = x;
                cy += h + gap;
            }
            int index = i;
            UiKit.button(b, host, name, cx, cy, bw, h, s, i == selected ? Style.PRIMARY : Style.SECONDARY, true, mx, my, () -> onPick.accept(index));
            cx += bw + gap;
        }
        return cy + h + Math.round(12f * s);
    }

    private static String pretty(String constant) {
        String lower = constant.toLowerCase(Locale.ROOT);
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    /**
     * Wrapping row of colour swatches. The first swatch is the theme accent: with {@code themeOption} it picks
     * "follow the theme" (selected value -2), otherwise it copies the current accent colour.
     */
    private float swatches(UiDisplayList.Builder b, UiHost host, float x, float y, float w, float s, float mx, float my,
                           boolean themeOption, int selected, IntConsumer onPick) {
        float r = Math.round(10f * s), pitch = Math.round(26f * s);
        float cx = x + r, cy = y + r;
        int accent = backend.accentRgb() & 0xFFFFFF;
        for (int i = -1; i < PALETTE.length; i++) {
            if (cx + r > x + w) {
                cx = x + r;
                cy += pitch;
            }
            int color = i < 0 ? accent : PALETTE[i];
            boolean isSel = i < 0 ? (themeOption ? selected == -2 : selected == accent) : !(themeOption && selected == -2) && selected == color;
            boolean hover = host.hovered(mx, my, cx - r, cy - r, r * 2f, r * 2f);
            if (isSel) b.circle(cx, cy, r + Math.max(2f, 3f * s), UiKit.withAlpha(host.accent(), 0xd0));
            else if (hover) b.circle(cx, cy, r + Math.max(1f, 2f * s), 0x50ffffff);
            b.circle(cx, cy, r, 0xff000000 | color);
            if (i < 0) {
                // Marks the theme swatch.
                float ds = 8f * s;
                String t = themeOption ? "T" : "A";
                b.text(cx - GpuUiFontMetrics.textWidth(t, ds, UiDrawCommand.TextWeight.SEMIBOLD) * 0.5f,
                        GpuUiFontMetrics.topInBox(cy - r, r * 2f, ds), ds, luminance(color) > 0.6f ? 0xff101010 : 0xffffffff, t,
                        UiDrawCommand.TextWeight.SEMIBOLD);
            }
            int pick = i < 0 ? (themeOption ? CosmeticSelection.THEME_COLOR : accent) : color;
            host.hit(cx - r, cy - r, r * 2f, r * 2f, () -> onPick.accept(pick));
            cx += pitch;
        }
        return cy + r + Math.round(12f * s);
    }

    private float stepper(UiDisplayList.Builder b, UiHost host, float x, float y, float w, float s, float mx, float my,
                          String label, String value, Runnable minus, Runnable plus) {
        float h = Math.round(28f * s);
        b.text(x, GpuUiFontMetrics.topInBox(y, h, 11f * s), 11f * s, host.textColor(), label, UiDrawCommand.TextWeight.SEMIBOLD);
        float bw = Math.round(32f * s);
        float vx = x + Math.round(120f * s);
        UiKit.button(b, host, "-", vx, y, bw, h, s, Style.SECONDARY, true, mx, my, minus);
        float vs = 10.5f * s, valW = Math.round(56f * s);
        b.text(vx + bw + (valW - GpuUiFontMetrics.textWidth(value, vs)) * 0.5f, GpuUiFontMetrics.topInBox(y, h, vs), vs, host.textColor(), value);
        UiKit.button(b, host, "+", vx + bw + valW, y, bw, h, s, Style.SECONDARY, true, mx, my, plus);
        return y + h + Math.round(10f * s);
    }

    private float toggleRow(UiDisplayList.Builder b, UiHost host, float x, float y, float w, float s, String title, String desc,
                            boolean on, Runnable toggle) {
        float pad = Math.round(14f * s);
        float textW = w - pad * 3f - 34f * s;
        List<String> lines = UiKit.wrap(desc, textW, 9.5f * s, 5);
        float h = Math.round(22f * s) + lines.size() * Math.round(13f * s) + pad;
        UiKit.card(b, host, x, y, w, h, s);
        b.text(x + pad, y + Math.round(10f * s), 11f * s, host.textColor(), UiKit.fit(title, textW, 11f * s), UiDrawCommand.TextWeight.SEMIBOLD);
        float ly = y + Math.round(27f * s);
        for (String line : lines) {
            b.text(x + pad, ly, 9.5f * s, host.muted2Color(), line);
            ly += Math.round(13f * s);
        }
        UiKit.toggle(b, host, x + w - pad - 34f * s, y + Math.round(10f * s), s, on, toggle);
        return y + h + Math.round(8f * s);
    }

    // ------------------------------------------------------------------ settings

    private float drawSettings(UiDisplayList.Builder b, UiHost host, CosmeticPrefs prefs, float x, float y, float w, float s,
                               float mx, float my) {
        float cy = section(b, host, "Cape physics", x, y, s);
        CosmeticPrefs.CapeMode[] modes = CosmeticPrefs.CapeMode.values();
        String[] labels = new String[modes.length];
        for (int i = 0; i < modes.length; i++) labels[i] = modes[i].label;
        float segH = Math.round(30f * s);
        UiKit.segmented(b, host, labels, prefs.capeMode().ordinal(), x, cy, Math.min(w, Math.round(360f * s)), segH, s, mx, my,
                i -> prefs.setCapeMode(modes[i]));
        cy += segH + Math.round(8f * s);
        String about = switch (prefs.capeMode()) {
            case PHYSICS -> "Cloth simulation: the cape sways, lifts when you fall and swings when you strafe or turn.";
            case STATIC -> "No physics: the cape hangs still behind you.";
            case VANILLA -> "Uses Minecraft's own cape model and motion with your Apexx design.";
        };
        for (String line : UiKit.wrap(about, w, 9.5f * s, 3)) {
            b.text(x, cy, 9.5f * s, host.muted2Color(), line);
            cy += Math.round(13f * s);
        }
        cy += Math.round(10f * s);
        cy = toggleRow(b, host, x, cy, w, s, "Elytra shows your cape design",
                "While gliding the cape is hidden like in vanilla; with this on, your elytra wears the cape design instead.",
                prefs.elytraUsesCape(), () -> prefs.setElytraUsesCape(!prefs.elytraUsesCape()));
        cy += Math.round(6f * s);
        cy = section(b, host, "Sharing", x, cy, s);
        cy = toggleRow(b, host, x, cy, w, s, "Show my cosmetics to other Apexx users",
                "Off by default. When on, only your cape design, hat and wings are published under your Minecraft UUID so other "
                        + "Apexx users see them on you. Anyone with Apexx who looks up your UUID can tell this account uses Apexx. "
                        + "The first time, your Minecraft account is verified the way a server login does it.",
                prefs.shareMine(), () -> {
                    if (!backend.signedIn()) {
                        say("Sign in to Apexx to share cosmetics.", host);
                        return;
                    }
                    prefs.setShareMine(!prefs.shareMine());
                    say(prefs.shareMine() ? "Sharing turned on; publishing in a few seconds." : "Sharing turned off; your record will be removed.", host);
                });
        cy = toggleRow(b, host, x, cy, w, s, "Show other players' Apexx cosmetics",
                "Looks up players you can see, a few at a time, so Apexx users who share their cosmetics show them. Only their "
                        + "Minecraft UUIDs are sent.",
                prefs.showOthers(), () -> prefs.setShowOthers(!prefs.showOthers()));
        String sync = backend.syncStatus();
        String line = !backend.signedIn() ? "Not signed in: sharing and viewing other players' cosmetics need an Apexx sign-in."
                : sync == null || sync.isEmpty() ? (prefs.shareMine() ? "Sharing is on." : "Sharing is off.") : sync;
        for (String l : UiKit.wrap(line, w, 9.5f * s, 3)) {
            b.text(x, cy, 9.5f * s, host.mutedColor(), l);
            cy += Math.round(13f * s);
        }
        return cy;
    }

    // ------------------------------------------------------------------ preview

    private void drawPreview(UiDisplayList.Builder b, UiHost host, CosmeticPrefs prefs, float x, float y, float w, float h,
                             float s, float mx, float my) {
        UiKit.card(b, host, x, y, w, h, s);
        float pad = Math.round(14f * s);
        b.text(x + pad, y + Math.round(12f * s), 11f * s, host.textColor(), "Preview", UiDrawCommand.TextWeight.SEMIBOLD);
        float boxY = y + Math.round(34f * s), boxH = h - Math.round(34f * s) - Math.round(86f * s);
        float boxX = x + pad, boxW = w - pad * 2f;
        // Soft floor glow under the player.
        b.gradient(boxX, boxY, boxW, boxH, Math.round(10f * s), 0x00000000, 0x00000000,
                UiKit.withAlpha(host.accent(), 0x22), UiKit.withAlpha(host.accent(), 0x22));
        long now = host.now();
        float dt = lastSpinAt == 0 ? 0f : Math.min(0.1f, (now - lastSpinAt) / 1.0e9f);
        lastSpinAt = now;
        if (prefs.autoSpin() && !dragging) yaw += 32f * dt;
        if (yaw > 360f) yaw -= 360f;
        if (yaw < -360f) yaw += 360f;
        if (backend.previewAvailable() && boxH > 40f * s) {
            previewRect[0] = boxX; previewRect[1] = boxY; previewRect[2] = boxW; previewRect[3] = boxH;
            host.hit(boxX, boxY, boxW, boxH, () -> {
                dragging = true;
                dragLastX = host.pressX();
            });
        } else {
            previewRect[2] = 0f;
            String msg = "Join a world to preview";
            float ms = 10f * s;
            b.text(boxX + (boxW - GpuUiFontMetrics.textWidth(msg, ms)) * 0.5f, boxY + boxH * 0.5f - ms * 0.5f, ms, host.muted2Color(), msg);
        }
        float rowY = boxY + boxH + Math.round(12f * s);
        b.text(x + pad, rowY + Math.round(3f * s), 10f * s, host.textColor(), "Auto-spin");
        UiKit.toggle(b, host, x + w - pad - 34f * s, rowY, s, prefs.autoSpin(), () -> prefs.setAutoSpin(!prefs.autoSpin()));
        b.text(x + pad, rowY + Math.round(26f * s), 9f * s, host.muted2Color(), UiKit.fit("Drag the player to turn it.", w - pad * 2f, 9f * s));
        CosmeticSelection sel = prefs.selection();
        String worn = (sel.cape() == null ? "" : capeName(sel.cape()))
                + (sel.hat() == null ? "" : (sel.cape() == null ? "" : " - ") + itemName(CosmeticCatalog.HATS, sel.hat().id()))
                + (sel.wings() == null ? "" : ((sel.cape() == null && sel.hat() == null) ? "" : " - ") + itemName(CosmeticCatalog.WINGS, sel.wings().type()));
        b.text(x + pad, rowY + Math.round(44f * s), 9f * s, host.mutedColor(),
                UiKit.fit(worn.isEmpty() ? "Wearing nothing" : worn, w - pad * 2f, 9f * s));
    }

    // ------------------------------------------------------------------ names

    private static String capeName(CapeDesign cape) {
        if (CapeDesign.CUSTOM.equals(cape.preset())) return "Your Cape";
        CapeDesign.Preset p = CapeDesign.presetById(cape.preset());
        return p == null ? "Cape" : p.name();
    }

    private static String itemName(List<CosmeticCatalog.Item> items, String id) {
        for (CosmeticCatalog.Item i : items) if (i.id().equals(id)) return i.name();
        return id;
    }
}
