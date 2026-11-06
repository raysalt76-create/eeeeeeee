package interstellarTikiTiks.apexx.client.module.implementation.render;

import interstellarTikiTiks.apexx.client.gui.gpuui.framework.UiDisplayList;
import interstellarTikiTiks.apexx.client.gui.gpuui.framework.UiDrawCommand;
import interstellarTikiTiks.apexx.client.gui.gpuui.render.GpuUiFontMetrics;
import interstellarTikiTiks.apexx.client.gui.gpuui.render.GpuUiRenderer;
import interstellarTikiTiks.apexx.client.hud.HudTheme;
import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.ModeSetting;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import interstellarTikiTiks.apexx.client.module.hooks.NotificationSink;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.math.MathHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * Toast notifications. Cards slide in from the screen edge, glide to their stack slot when a neighbour leaves,
 * slide back out when they expire, and merge when the same message repeats. A module toggle shows a small
 * switch that flips to the new state; other messages show a vector status icon.
 *
 * <p>Drawn through the GPU UI display list (the same SDF text/shape pipeline as the ClickGUI and HUD) in
 * framebuffer pixel space, so text and edges are sharp at every GUI scale and Card Scale only scales geometry.
 * Coordinates are in quarter device pixels ({@link #SS}): card edges and text origins are snapped to whole
 * pixels, while the icon strokes keep sub-pixel precision. The commands go into the same GUI render state as
 * the HUD, so the SS-Proof overlay redirect applies to them unchanged.
 */
public final class Notifications extends Module implements NotificationSink {
    private static final long LIFETIME_MS = 3400L;
    private static final long ENTER_MS = 320L;
    private static final long EXIT_MS = 280L;
    private static final long KNOB_DELAY_MS = 110L;
    private static final long KNOB_MS = 240L;
    private static final int MAX_CARDS = 5;

    /** Sub-pixel units per framebuffer pixel. */
    private static final float SS = 4f;

    // Card metrics in card units (GUI units at Card Scale 1.0).
    private static final float PAD = 10f;
    private static final float LEAD = 20f;
    private static final float LEAD_GAP = 9f;
    private static final float HEIGHT_TWO_LINE = 34f;
    private static final float HEIGHT_ONE_LINE = 26f;
    private static final float TITLE_SIZE = 9.5f;
    private static final float MESSAGE_SIZE = 8f;
    private static final float BADGE_SIZE = 7f;
    private static final float MIN_WIDTH = 150f;
    private static final float GAP = 6f;
    private static final float MARGIN = 10f;

    private static final int TITLE = 0xFFF5F7FA;
    private static final int MESSAGE = 0xFFA3ACBA;
    private static final int BODY_RGB = 0x0E1117;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int ON = 0xFF3FD18A;
    private static final int OFF_TRACK = 0xFF2A2F39;
    private static final int OFF_KNOB = 0xFFE0707A;
    private static final int OFF_GLOW = 0xFF8A93A3;
    private static final int SUCCESS = 0xFF3FD18A;
    private static final int ERROR = 0xFFF0707A;
    private static final int WARNING = 0xFFF5B54A;
    private static final int INFO = 0xFF6CB8FF;
    private static final int NEUTRAL = 0xFF9AA5B6;

    private final ModeSetting position = new ModeSetting("Position", "Bottom Right",
            "Bottom Right", "Top Right", "Top Left", "Bottom Left")
            .setDescription("Screen corner where notification cards appear and stack.");
    private final ModeSetting colorMode = new ModeSetting("Color Mode", "Apexx Modern",
            "Apexx Modern", "Severity", "Minimal Dark")
            .setDescription("Colour scheme of the cards and their status accents.");
    private final SliderSetting scale = new SliderSetting("Card Scale", 1.0, 0.7, 1.5, 2)
            .setDescription("Size of the cards; 1.0 is the default size.");
    // Card rounding is fixed and card opacity follows the HUD editor's Glass Opacity (100% = 0.90), so the
    // HUD look is set in one place.
    private static final float CORNER_RADIUS = 5f;
    private static final float CARD_OPACITY = 0.90f;
    private final BooleanSetting showGlow = new BooleanSetting("Status Glow", true)
            .setDescription("Adds a soft glow in the card's status colour (green success, amber warning, red error).");
    private final BooleanSetting showProgressBar = new BooleanSetting("Progress Bar", true)
            .setDescription("Shows a shrinking bar along the card for how long it will stay on screen.");

    private final List<NotificationCard> cards = new ArrayList<>();
    private long lastNanos = -1L;

    public Notifications() {
        super("Notifications", "Shows a small animated card in a screen corner when a module is turned on or off, with configurable position, size and style. Also listed in the HUD editor.", Category.RENDER);
        // On for a fresh install: modules report missing items/conditions through these cards.
        // A saved config's "enabled" value still wins on load.
        setEnabled(true);
        addSetting(position);
        addSetting(colorMode);
        addSetting(scale);
        addSetting(showGlow);
        addSetting(showProgressBar);
    }

    @Override
    public synchronized void show(String title, String message, String type) {
        long now = System.currentTimeMillis();
        String effectiveTitle = (title == null || title.isBlank()) ? "Apexx" : title.trim();
        String effectiveMessage = (message == null) ? "" : message.trim();
        String effectiveType = type == null ? "INFO" : type;
        boolean toggle = isToggleMessage(effectiveMessage);
        // Only module on/off cards are shown; every other client message is dropped here on purpose.
        if (!toggle) return;

        for (NotificationCard card : cards) {
            if (card.leaving(now) || !card.title.equals(effectiveTitle)) continue;
            if (toggle && card.toggle) {
                // The same module flipped again: retarget its switch instead of stacking a second card.
                boolean on = "Enabled".equalsIgnoreCase(effectiveMessage);
                if (card.on != on) {
                    card.knobFrom = card.knobPos(now);
                    card.knobStart = now - KNOB_DELAY_MS;
                    card.on = on;
                    card.message = effectiveMessage;
                    card.type = effectiveType;
                    card.count = 1;
                } else {
                    card.count++;
                }
                card.measuredUnit = -1f;
                card.expires = now + LIFETIME_MS;
                return;
            }
            if (card.message.equals(effectiveMessage) && card.type.equalsIgnoreCase(effectiveType)) {
                // A repeat of a live card refreshes it (without replaying the slide-in) and bumps its counter.
                card.count++;
                card.measuredUnit = -1f;
                card.expires = now + LIFETIME_MS;
                return;
            }
        }

        // Retire the oldest live cards gracefully rather than deleting them mid-frame.
        int live = 0;
        for (NotificationCard card : cards) {
            if (!card.leaving(now)) live++;
        }
        for (int i = 0; i < cards.size() && live >= MAX_CARDS; i++) {
            NotificationCard card = cards.get(i);
            if (card.leaving(now)) continue;
            card.expires = Math.min(card.expires, now + EXIT_MS);
            live--;
        }
        cards.add(new NotificationCard(effectiveTitle, effectiveMessage, effectiveType, toggle, now));
    }

    @Override
    public synchronized void onRender(DrawContext context, float tickDelta) {
        if (context == null || mc == null || mc.player == null) {
            return;
        }
        long now = System.currentTimeMillis();
        long nanos = System.nanoTime();
        float dt = lastNanos < 0 ? 0.016f : MathHelper.clamp((nanos - lastNanos) / 1.0e9f, 0.001f, 0.1f);
        lastNanos = nanos;

        for (int i = cards.size() - 1; i >= 0; i--) {
            if (now >= cards.get(i).expires) cards.remove(i);
        }
        if (cards.isEmpty()) {
            return;
        }

        int screenW = context.getScaledWindowWidth();
        int screenH = context.getScaledWindowHeight();
        if (screenW <= 0 || screenH <= 0) {
            return;
        }
        var window = mc.getWindow();
        float k = window == null ? 1f : Math.max(1f, window.getFramebufferWidth() / (float) screenW);
        float p = k * SS;                                    // sub-pixel units per GUI unit
        float cardScale = (float) scale.getValue();
        float u = p * cardScale;                             // sub-pixel units per card unit
        HudTheme.refresh();

        boolean isRight = position.is("Bottom Right") || position.is("Top Right");
        boolean isBottom = position.is("Bottom Right") || position.is("Bottom Left");
        float maxWidth = Math.max(MIN_WIDTH * u, screenW * 0.45f * p);

        UiDisplayList.Builder b = UiDisplayList.builder();
        float offset = 0f;
        float follow = 1f - (float) Math.exp(-dt * 14f);
        // Newest card sits closest to the anchor corner; older ones are pushed away from it.
        for (int i = cards.size() - 1; i >= 0; i--) {
            NotificationCard card = cards.get(i);
            card.measure(u);

            float enter = MathHelper.clamp((now - card.born) / (float) ENTER_MS, 0f, 1f);
            float enterEase = 1f - (1f - enter) * (1f - enter) * (1f - enter) * (1f - enter);
            float exit = MathHelper.clamp(1f - (card.expires - now) / (float) EXIT_MS, 0f, 1f);
            float exitEase = exit * exit * exit;
            float alpha = MathHelper.clamp(enter * 1.6f, 0f, 1f) * (1f - exit);

            float widthQ = Math.min(maxWidth, card.naturalWidth);
            float heightQ = (card.message.isEmpty() ? HEIGHT_ONE_LINE : HEIGHT_TWO_LINE) * u;
            float width = widthQ / p;
            float height = heightQ / p;

            float targetY = isBottom ? screenH - MARGIN - height - offset : MARGIN + offset;
            card.y = Float.isNaN(card.y) ? targetY : card.y + (targetY - card.y) * follow;
            offset += (height + GAP * cardScale) * (1f - exitEase);
            if (alpha <= 0.01f) {
                continue;
            }

            float slide = (1f - enterEase) * (width + MARGIN) + exitEase * (width * 0.6f + MARGIN);
            float restX = isRight ? screenW - MARGIN - width : MARGIN;
            float x = restX + (isRight ? slide : -slide);

            drawCard(b, card, snap(x * p), snap(card.y * p), snap(widthQ), snap(heightQ), u, alpha, now);
        }
        GpuUiRenderer.renderPixels(context, b.build(), p);
    }

    private void drawCard(UiDisplayList.Builder b, NotificationCard card, float x, float y, float w, float h,
                          float u, float a, long now) {
        boolean minimal = colorMode.is("Minimal Dark");
        int status = statusColor(card, minimal);
        int statusRgb = status & 0xFFFFFF;
        float radius = Math.min(CORNER_RADIUS * u, h * 0.5f);
        float bg = CARD_OPACITY * interstellarTikiTiks.apexx.client.hud.HudFill.hudGlassOpacity();
        boolean progress = showProgressBar.isEnabled();

        // Surface: soft drop shadow, optional status glow, dark translucent body, top sheen, hairline rim.
        b.shadow(x, y + 2f * u, w, h, radius, 10f * u, 0f, tint(0x70000000, a));
        if (showGlow.isEnabled() && !minimal) {
            b.shadow(x, y, w, h, radius, 12f * u, 0f, tint(0x30000000 | statusRgb, a));
        }
        b.rect(x, y, w, h, radius, tint(((int) (MathHelper.clamp(bg, 0f, 1f) * 238f) << 24) | BODY_RGB, a));
        b.gradient(x, y, w, h, radius, tint(0x10FFFFFF, a), tint(0x10FFFFFF, a), 0, 0);
        b.border(x, y, w, h, radius, SS, tint(0x24FFFFFF, a));

        float lx = x + PAD * u;
        float cy = y + h * 0.5f - (progress ? 1.5f * u : 0f);
        if (card.toggle) {
            drawSwitch(b, lx, cy, u, card.knobPos(now), a);
        } else {
            drawIcon(b, card.type, lx, cy, u, status, a);
        }

        // Text block: title (+ repeat badge) over message, centred on the content line.
        float textX = snap(lx + (LEAD + LEAD_GAP) * u);
        float titleSize = TITLE_SIZE * u;
        float messageSize = MESSAGE_SIZE * u;
        float textRight = x + w - PAD * u;
        boolean clip = textX + card.contentWidth > textRight + 0.5f;
        if (clip) b.clipPush(textX, y, Math.max(1f, textRight - textX), h, 0f);
        float titleCenter = cy;
        float messageCenter = cy;
        if (!card.message.isEmpty()) {
            float titleCap = titleSize * GpuUiFontMetrics.CAP_HEIGHT;
            float messageCap = messageSize * GpuUiFontMetrics.CAP_HEIGHT;
            float block = titleCap + 5f * u + messageCap;
            titleCenter = cy - block * 0.5f + titleCap * 0.5f;
            messageCenter = cy + block * 0.5f - messageCap * 0.5f;
        }
        b.text(textX, snap(GpuUiFontMetrics.topForCenter(titleCenter, titleSize)), titleSize, tint(TITLE, a),
                card.title, UiDrawCommand.TextWeight.SEMIBOLD);
        if (!card.badge.isEmpty()) {
            float bx = snap(textX + card.titleWidth + 6f * u);
            float bh = 11f * u;
            b.rect(bx, titleCenter - bh * 0.5f, card.badgeWidth, bh, bh * 0.5f, tint(0x1FFFFFFF, a));
            b.text(snap(bx + 4f * u), snap(GpuUiFontMetrics.topForCenter(titleCenter, BADGE_SIZE * u)),
                    BADGE_SIZE * u, tint(0xFFD5DAE2, a), card.badge, UiDrawCommand.TextWeight.SEMIBOLD);
        }
        if (!card.message.isEmpty()) {
            int messageColor = !card.toggle ? MESSAGE
                    : 0xFF000000 | HudTheme.mix(MESSAGE, card.on ? ON : OFF_KNOB, card.on ? 0.55f : 0.35f);
            b.text(textX, snap(GpuUiFontMetrics.topForCenter(messageCenter, messageSize)), messageSize,
                    tint(messageColor, a), card.message);
        }
        if (clip) b.clipPop();

        // Remaining-time line: a thin rounded track with the depleting fill on top.
        if (progress) {
            float remaining = MathHelper.clamp((card.expires - now) / (float) LIFETIME_MS, 0f, 1f);
            float bh = Math.max(SS, snap(1.25f * u));
            float bx = x + PAD * u;
            float bw = w - 2f * PAD * u;
            float by = snap(y + h - 4.5f * u - bh * 0.5f);
            b.rect(bx, by, bw, bh, bh * 0.5f, tint(0x14FFFFFF, a));
            float fill = bw * remaining;
            if (fill >= bh) {
                b.rect(bx, by, fill, bh, bh * 0.5f, tint(progressColor(status, minimal), a));
            }
        }
    }

    /** Toggle switch: the track blends grey to green and the knob slides as the state flips. */
    private static void drawSwitch(UiDisplayList.Builder b, float lx, float cy, float u, float pos, float a) {
        float tw = LEAD * u;
        float th = 11f * u;
        float ty = cy - th * 0.5f;
        int track = 0xFF000000 | HudTheme.mix(OFF_TRACK, ON, pos);
        b.rect(lx, ty, tw, th, th * 0.5f, tint(track, a));
        b.border(lx, ty, tw, th, th * 0.5f, SS, tint(0x1FFFFFFF, a));
        float d = 7.5f * u;
        float inset = (th - d) * 0.5f;
        float kx = lx + inset + (tw - th) * pos;
        float ky = cy - d * 0.5f;
        b.shadow(kx, ky + 0.5f * u, d, d, d * 0.5f, 2f * u, 0f, tint(0x55000000, a));
        b.rect(kx, ky, d, d, d * 0.5f, tint(0xFF000000 | HudTheme.mix(OFF_KNOB, WHITE, pos), a));
    }

    /** Tinted rounded tile with a vector glyph: check, cross, exclamation or info mark. */
    private static void drawIcon(UiDisplayList.Builder b, String type, float lx, float cy, float u, int status, float a) {
        float size = LEAD * u;
        int rgb = status & 0xFFFFFF;
        b.rect(lx, cy - size * 0.5f, size, size, 6f * u, tint(0x24000000 | rgb, a));
        b.border(lx, cy - size * 0.5f, size, size, 6f * u, SS, tint(0x3D000000 | rgb, a));
        float cx = lx + size * 0.5f;
        float t = 1.8f * u;
        int ink = tint(status, a);
        if ("SUCCESS".equalsIgnoreCase(type)) {
            stroke(b, cx - 4.0f * u, cy + 0.2f * u, cx - 1.2f * u, cy + 3.0f * u, t, ink);
            stroke(b, cx - 1.2f * u, cy + 3.0f * u, cx + 4.2f * u, cy - 2.8f * u, t, ink);
        } else if ("ERROR".equalsIgnoreCase(type)) {
            stroke(b, cx - 3.2f * u, cy - 3.2f * u, cx + 3.2f * u, cy + 3.2f * u, t, ink);
            stroke(b, cx + 3.2f * u, cy - 3.2f * u, cx - 3.2f * u, cy + 3.2f * u, t, ink);
        } else if ("WARNING".equalsIgnoreCase(type)) {
            b.rect(cx - t * 0.5f, cy - 4.6f * u, t, 6.0f * u, t * 0.5f, ink);
            b.rect(cx - t * 0.6f, cy + 3.0f * u - t * 0.6f, t * 1.2f, t * 1.2f, t * 0.6f, ink);
        } else {
            b.rect(cx - t * 0.6f, cy - 3.8f * u - t * 0.6f, t * 1.2f, t * 1.2f, t * 0.6f, ink);
            b.rect(cx - t * 0.5f, cy - 1.0f * u, t, 5.4f * u, t * 0.5f, ink);
        }
    }

    /** Round-capped line as a chain of anti-aliased discs (the shape pipeline has no rotated quads). */
    private static void stroke(UiDisplayList.Builder b, float x0, float y0, float x1, float y1, float t, int argb) {
        float dx = x1 - x0;
        float dy = y1 - y0;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        int n = Math.max(1, (int) Math.ceil(len / (t * 0.35f)));
        float r = t * 0.5f;
        for (int i = 0; i <= n; i++) {
            float f = i / (float) n;
            b.rect(x0 + dx * f - r, y0 + dy * f - r, t, t, r, argb);
        }
    }

    @Override
    public synchronized void onDisable() {
        cards.clear();
        lastNanos = -1L;
        super.onDisable();
    }

    /** Rounds a sub-pixel coordinate to a whole framebuffer pixel. */
    private static float snap(float v) {
        return Math.round(v / SS) * SS;
    }

    private static int tint(int argb, float alpha) {
        int a = Math.round(((argb >>> 24) & 0xFF) * MathHelper.clamp(alpha, 0f, 1f));
        return (a << 24) | (argb & 0xFFFFFF);
    }

    private static boolean isToggleMessage(String message) {
        return "Enabled".equalsIgnoreCase(message) || "Disabled".equalsIgnoreCase(message);
    }

    private int statusColor(NotificationCard card, boolean minimal) {
        if (card.toggle) return card.on ? ON : OFF_GLOW;
        if (minimal) return NEUTRAL;
        if ("ERROR".equalsIgnoreCase(card.type)) return ERROR;
        if ("WARNING".equalsIgnoreCase(card.type)) return WARNING;
        if ("SUCCESS".equalsIgnoreCase(card.type)) return SUCCESS;
        return INFO;
    }

    private int progressColor(int status, boolean minimal) {
        if (minimal) return 0xCC8A94A6;
        if (colorMode.is("Severity")) return 0xE6000000 | (status & 0xFFFFFF);
        return 0xE6000000 | (HudTheme.accent() & 0xFFFFFF);
    }

    private static final class NotificationCard {
        final String title;
        final boolean toggle;
        final long born;
        String message;
        String type;
        long expires;
        int count = 1;
        float y = Float.NaN;
        boolean on;
        float knobFrom;
        long knobStart;

        // Text measurements, cached per sub-pixel unit size.
        float measuredUnit = -1f;
        float titleWidth;
        float badgeWidth;
        float contentWidth;
        float naturalWidth;
        String badge = "";

        NotificationCard(String title, String message, String type, boolean toggle, long now) {
            this.title = title;
            this.message = message;
            this.type = type;
            this.toggle = toggle;
            this.born = now;
            this.expires = now + LIFETIME_MS;
            this.on = "Enabled".equalsIgnoreCase(message);
            this.knobFrom = on ? 0f : 1f;
            this.knobStart = now;
        }

        boolean leaving(long now) {
            return expires - now <= EXIT_MS;
        }

        float knobPos(long now) {
            float t = MathHelper.clamp((now - knobStart - KNOB_DELAY_MS) / (float) KNOB_MS, 0f, 1f);
            float ease = 1f - (1f - t) * (1f - t) * (1f - t);
            float target = on ? 1f : 0f;
            return knobFrom + (target - knobFrom) * ease;
        }

        void measure(float u) {
            if (measuredUnit == u) return;
            measuredUnit = u;
            badge = count > 1 ? "x" + count : "";
            titleWidth = GpuUiRenderer.textWidth(title, TITLE_SIZE * u, UiDrawCommand.TextWeight.SEMIBOLD);
            badgeWidth = badge.isEmpty() ? 0f
                    : GpuUiRenderer.textWidth(badge, BADGE_SIZE * u, UiDrawCommand.TextWeight.SEMIBOLD) + 8f * u;
            float messageWidth = message.isEmpty() ? 0f : GpuUiRenderer.textWidth(message, MESSAGE_SIZE * u);
            contentWidth = Math.max(titleWidth + (badge.isEmpty() ? 0f : 6f * u + badgeWidth), messageWidth);
            naturalWidth = Math.max(MIN_WIDTH * u, (PAD + LEAD + LEAD_GAP + PAD) * u + contentWidth);
        }
    }
}
