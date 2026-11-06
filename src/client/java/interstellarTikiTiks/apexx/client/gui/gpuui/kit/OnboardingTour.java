package interstellarTikiTiks.apexx.client.gui.gpuui.kit;

import interstellarTikiTiks.apexx.client.config.ClientPrefs;
import interstellarTikiTiks.apexx.client.gui.gpuui.framework.UiDisplayList;
import interstellarTikiTiks.apexx.client.gui.gpuui.framework.UiDrawCommand;
import interstellarTikiTiks.apexx.client.gui.gpuui.kit.UiKit.Style;

import java.util.List;

/**
 * First-run tour over the click GUI: a few cards that point at the categories, the module list, binds,
 * the settings drawer, configs and the HUD editor, with Back / Next / Skip. It starts by itself once,
 * the first time the menu opens after the player's cloud prefs are known (the "seen" flag lives in
 * {@link ClientPrefs}, so it follows the account, not the PC), and "Replay tour" in Settings > Client
 * starts it again.
 */
public final class OnboardingTour {
    /** What a step points at; the screen supplies the on-screen rectangle for each. */
    public enum Target { NONE, RAIL, CATALOG, NAV_SETTINGS, NAV_CONFIGS, NAV_PEOPLE, NAV_HUD, NAV_SELF_DESTRUCT,
        RAIL_MACROS, RAIL_SCRIPTS }

    private record Step(String title, String body, Target target) { }

    private static final List<Step> STEPS = List.of(
            new Step("Welcome to Apexx",
                    "A quick tour of the menu. Use Next and Back, or Skip whenever you like. You can replay it from Settings > Client.",
                    Target.NONE),
            new Step("Categories",
                    "Modules are grouped by what they do. Pick a category here to list its modules, or make your own folder at the bottom of the list.",
                    Target.RAIL),
            new Step("Modules and settings",
                    "Click a module card to switch it on or off, or press and drag it to reorder. Right-click it for its settings, favourite and folders; right-click a setting to reset it or pin it to Quick Settings.",
                    Target.CATALOG),
            new Step("Search",
                    "Use the search box to find a module in the current category. Clear it to see every module again.",
                    Target.CATALOG),
            new Step("Changed settings",
                    "A small dot next to a setting means you changed it from its default. Click the dot, or right-click the row, to put it back.",
                    Target.CATALOG),
            new Step("Quick Settings",
                    "Right-click any setting and pin it. Pinned settings from every module show in one strip at the top, so you can tweak them without digging.",
                    Target.CATALOG),
            new Step("Folders",
                    "Make your own folder at the bottom of the category list, then right-click modules (or drag them onto the folder) to add them. Great for a Mace or Crystal loadout.",
                    Target.RAIL),
            new Step("Delays",
                    "Every module that waits has its own Delays section. Each delay has a StDev slider next to it so the timing varies like a real player. Lower is faster; 0 is allowed.",
                    Target.CATALOG),
            new Step("Keybinds",
                    "In a module's settings, click its keybind, then press any key or mouse button. Each module can be held or toggled.",
                    Target.CATALOG),
            new Step("Settings",
                    "Themes and menu look, client-wide options, and the Binds tab, where a key can swap your whole config.",
                    Target.NAV_SETTINGS),
            new Step("Configs",
                    "Your configs live in your Apexx cloud, never on this PC. Save, load, share with a code, or auto-load one when you join a server.",
                    Target.NAV_CONFIGS),
            new Step("People",
                    "Friends and enemies. Friends are never targeted; the Friend key adds the player you are looking at.",
                    Target.NAV_PEOPLE),
            new Step("HUD Editor",
                    "Drag HUD widgets where you want them, save layouts, and align them. On mobile it is also a module in the Client tab.",
                    Target.NAV_HUD),
            new Step("HUD colours",
                    "The HUD follows your menu theme by default. Hit COLOURS in the HUD editor to pick your own, or give a single widget its own colours.",
                    Target.NAV_HUD),
            new Step("Macros",
                    "The macro maker: pick a trigger key, then chain slot swaps, clicks and waits. Good for quick combos you do by hand every fight.",
                    Target.RAIL_MACROS),
            new Step("Scripts",
                    "Write your own modules in the Apexx script language. Click a line number to set a breakpoint and watch variables live in the Debug panel, and draw your own HUD with drawText and drawRect.",
                    Target.RAIL_SCRIPTS),
            new Step("Saving configs",
                    "After you load a config, changes stay unsaved until you press Overwrite on the Configs page. Save as new keeps your old one safe.",
                    Target.NAV_CONFIGS),
            new Step("Config Hub",
                    "Browse configs from other players, sort by Popular this week or Top rated, rate the ones you like, and import a friend's with a share code.",
                    Target.NAV_CONFIGS),
            new Step("Look and feel",
                    "In Settings you can change the theme, background, blur, menu size and UI sounds (Creamy, Clacky or Soft). The menu sizes itself to your monitor.",
                    Target.NAV_SETTINGS),
            new Step("Screenshare proof",
                    "Turn on SS-Proof in Settings and the menu and visuals stay on your screen only. Recordings and screenshots see the plain game.",
                    Target.NAV_SETTINGS),
            new Step("Self-Destruct",
                    "Wipes Apexx from the running game in one click and leaves nothing behind. Use it when you need the client gone right now.",
                    Target.NAV_SELF_DESTRUCT),
            new Step("You're set",
                    "That's the tour. The What's New button in the header shows the latest updates. Have fun, and check the module descriptions if anything is unclear.",
                    Target.NONE));

    private int step = -1;
    private boolean autoChecked;
    /** True when Macros and Scripts are shown (Premium); their steps are left out otherwise. */
    private final java.util.function.BooleanSupplier studioVisible;

    public OnboardingTour() {
        this(interstellarTikiTiks.apexx.client.util.PremiumGate::studio);
    }

    public OnboardingTour(java.util.function.BooleanSupplier studioVisible) {
        this.studioVisible = studioVisible == null ? () -> true : studioVisible;
    }

    /** The steps for this session: Premium-only features (Macros, Scripts) are skipped when they are hidden. */
    private List<Step> steps() {
        boolean studio;
        try {
            studio = studioVisible.getAsBoolean();
        } catch (Throwable t) {
            studio = false;
        }
        if (studio) return STEPS;
        List<Step> out = new java.util.ArrayList<>(STEPS.size());
        for (Step st : STEPS) {
            if (st.target() != Target.RAIL_MACROS && st.target() != Target.RAIL_SCRIPTS) out.add(st);
        }
        return out;
    }

    /** The current step, clamped in case the step list changed under the tour. */
    private Step current(List<Step> steps) {
        if (step >= steps.size()) step = steps.size() - 1;
        return steps.get(Math.max(0, step));
    }

    public boolean active() {
        return step >= 0;
    }

    public Target target() {
        return active() ? current(steps()).target() : Target.NONE;
    }

    /** Replay from the start. */
    public void start() {
        step = 0;
    }

    /**
     * Starts the tour the first time it is allowed to: once per menu, and only when the player's prefs
     * are known and say the tour was never shown. Marked seen right away so it only ever appears once.
     */
    public void maybeAutoStart() {
        if (autoChecked || active()) return;
        ClientPrefs prefs = ClientPrefs.get();
        if (prefs.isOwned()) {
            autoChecked = true;
            if (!prefs.isOnboardingSeen() && !shownThisSession) {
                shownThisSession = true;
                step = 0;
                prefs.setOnboardingSeen(true);
            } else if (shownThisSession && !prefs.isOnboardingSeen()) {
                // Shown earlier from the fallback below, before the prefs arrived: remember it now.
                prefs.setOnboardingSeen(true);
            }
            return;
        }
        // The prefs that say whether the tour was seen are not loaded. When they never will be this session
        // (signed out, cloud unreachable, dev build) or are taking too long, show it on the first menu open
        // anyway, once per game session; nothing is written to disk.
        if (firstWaitMs == 0L) firstWaitMs = System.currentTimeMillis();
        var status = interstellarTikiTiks.apexx.client.config.CloudConfigSync.getStatus();
        boolean noPrefsComing = status == interstellarTikiTiks.apexx.client.config.CloudConfigSync.Status.SIGNED_OUT
                || status == interstellarTikiTiks.apexx.client.config.CloudConfigSync.Status.FAILED;
        if (!noPrefsComing && System.currentTimeMillis() - firstWaitMs < PREFS_WAIT_MS) return;
        autoChecked = true;
        if (!shownThisSession) {
            shownThisSession = true;
            step = 0;
        }
    }

    /** How long the first menu open waits for the cloud prefs before showing the tour anyway. */
    private static final long PREFS_WAIT_MS = 2500L;
    /** Once per game session at most, across menu instances. */
    private static boolean shownThisSession;
    private long firstWaitMs;

    public void next() {
        if (step >= steps().size() - 1) finish();
        else step++;
    }

    public void back() {
        if (step > 0) step--;
    }

    /** Skip or Finish. */
    public void finish() {
        step = -1;
        ClientPrefs.get().setOnboardingSeen(true);
    }

    /**
     * Draws the dimmed overlay with a cut-out around {@code rect} (x, y, w, h; null = none) and the step
     * card. The caller clears its click regions first, so everything under the overlay is inert.
     */
    public void draw(UiDisplayList.Builder b, UiHost host, float sw, float sh, float s, float[] rect,
                     float mx, float my) {
        if (!active()) return;
        List<Step> steps = steps();
        Step current = current(steps);
        int dim = 0xa6000000;
        // Clicks outside the card do nothing (they must not reach the menu below).
        host.hit(0, 0, sw, sh, () -> { });
        boolean hole = rect != null && rect[2] > 0 && rect[3] > 0;
        float hx = 0, hy = 0, hw = 0, hh = 0;
        if (hole) {
            float m = Math.round(5f * s);
            hx = Math.max(0, rect[0] - m);
            hy = Math.max(0, rect[1] - m);
            hw = Math.min(sw - hx, rect[2] + m * 2f);
            hh = Math.min(sh - hy, rect[3] + m * 2f);
            b.rect(0, 0, sw, hy, 0, dim);
            b.rect(0, hy + hh, sw, sh - hy - hh, 0, dim);
            b.rect(0, hy, hx, hh, 0, dim);
            b.rect(hx + hw, hy, sw - hx - hw, hh, 0, dim);
            float r = Math.round(10f * s);
            b.border(hx, hy, hw, hh, r, Math.max(1.5f, 2f * s), UiKit.withAlpha(host.accent(), 0xe0));
        } else {
            b.rect(0, 0, sw, sh, 0, dim);
        }

        float pad = Math.round(18f * s);
        float w = Math.round(Math.min(360f * s, sw - 32f * s));
        float bodySize = 10.5f * s, pitch = Math.round(15f * s);
        List<String> lines = UiKit.wrap(current.body(), w - pad * 2f, bodySize, 6);
        float btnH = Math.round(30f * s);
        float h = Math.round(pad + 18f * s + 12f * s + lines.size() * pitch + 16f * s + btnH + pad);
        float x, y;
        if (hole) {
            x = hx + hw * 0.5f - w * 0.5f;
            if (hy + hh + 12f * s + h <= sh - 12f * s) y = hy + hh + 12f * s;            // below
            else if (hy - 12f * s - h >= 12f * s) y = hy - 12f * s - h;                  // above
            else {                                                                       // beside
                y = hy + hh * 0.5f - h * 0.5f;
                x = hx + hw + 12f * s + w <= sw - 12f * s ? hx + hw + 12f * s : hx - 12f * s - w;
            }
        } else {
            x = (sw - w) * 0.5f;
            y = (sh - h) * 0.5f;
        }
        x = Math.round(Math.max(12f * s, Math.min(sw - w - 12f * s, x)));
        y = Math.round(Math.max(12f * s, Math.min(sh - h - 12f * s, y)));

        float r = Math.round(14f * s * host.cornerScale());
        b.shadow(x, y + 2f * s, w, h, r, 14f * s, 0f, 0x80000000);
        b.rect(x, y, w, h, r, 0xf4101318);
        b.border(x, y, w, h, r, Math.max(1f, s), UiKit.withAlpha(host.accent(), 0x70));
        host.hit(x, y, w, h, () -> { });

        b.text(x + pad, y + pad, 14f * s, host.titleColor(),
                UiKit.fit(current.title(), w - pad * 2f - 50f * s, 14f * s, UiDrawCommand.TextWeight.SEMIBOLD),
                UiDrawCommand.TextWeight.SEMIBOLD);
        String counter = (step + 1) + " / " + steps.size();
        float cw = interstellarTikiTiks.apexx.client.gui.gpuui.render.GpuUiFontMetrics.textWidth(counter, 9.5f * s);
        b.text(x + w - pad - cw, y + pad + 3f * s, 9.5f * s, host.muted2Color(), counter);
        float ty = y + pad + 18f * s + 12f * s;
        for (int i = 0; i < lines.size(); i++) {
            b.text(x + pad, ty + i * pitch, bodySize, host.mutedColor(), lines.get(i));
        }

        float by = y + h - pad - btnH;
        float skipW = UiKit.buttonWidth("Skip tour", s);
        UiKit.button(b, host, "Skip tour", x + pad, by, skipW, btnH, s, Style.GHOST, true, mx, my, this::finish);
        boolean last = step == steps.size() - 1;
        String nextLabel = last ? "Finish" : "Next";
        float nextW = UiKit.buttonWidth(nextLabel, s) + Math.round(10f * s);
        float backW = UiKit.buttonWidth("Back", s);
        UiKit.button(b, host, nextLabel, x + w - pad - nextW, by, nextW, btnH, s, Style.PRIMARY, true, mx, my, this::next);
        UiKit.button(b, host, "Back", x + w - pad - nextW - Math.round(8f * s) - backW, by, backW, btnH, s,
                Style.SECONDARY, step > 0, mx, my, this::back);
    }
}
