package interstellarTikiTiks.apexx.client.gui.gpuui.kit;

import interstellarTikiTiks.apexx.client.config.ClientPrefs;
import interstellarTikiTiks.apexx.client.config.CloudConfigs;
import interstellarTikiTiks.apexx.client.config.ConfigManager;
import interstellarTikiTiks.apexx.client.config.ConfigTags;
import interstellarTikiTiks.apexx.client.config.ServerAutoConfig;
import interstellarTikiTiks.apexx.client.config.hub.ConfigHubManager;
import interstellarTikiTiks.apexx.client.config.hub.PrivateConfigManager;
import interstellarTikiTiks.apexx.client.config.hub.RemoteConfig;
import interstellarTikiTiks.apexx.client.config.maker.ConfigMaker;
import interstellarTikiTiks.apexx.client.config.maker.ConfigMakerEngine;
import interstellarTikiTiks.apexx.client.gui.gpuui.framework.UiDisplayList;
import interstellarTikiTiks.apexx.client.gui.gpuui.framework.UiDrawCommand;
import interstellarTikiTiks.apexx.client.gui.gpuui.framework.UiSmoothScroll;
import interstellarTikiTiks.apexx.client.gui.gpuui.kit.UiKit.Style;
import interstellarTikiTiks.apexx.client.gui.gpuui.render.GpuUiFontMetrics;
import net.minecraft.client.MinecraftClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The Configs page: the player's own cloud configs (save with a public/private choice and filter tags,
 * right-click one to make it public/private, rename, edit its filters or delete it) and the community
 * hub (browse/filter by tag, download, share).
 */
public final class ConfigsView {
    /** The bits of the screen this page drives. */
    public interface Backend {
        List<String> savedConfigs();

        /** Cloud details of a saved config, or null for a local-only profile. */
        Info info(String name);

        boolean signedIn();

        void load(String name);

        /** Queue saving the current settings under {@code name} with the chosen visibility + tags. */
        void save(String name, boolean makePublic, List<String> tags);

        boolean canDeleteLocal(String name);

        void deleteLocal(String name);

        /** Delete one of the caller's cloud configs by name. */
        void deleteCloud(String name);

        /** Make a cloud config public/private by name. */
        void setPublic(String name, boolean makePublic);

        /** Rename a cloud config. */
        void rename(String name, String newName);

        /** Replace a cloud config's filter tags. */
        void setTags(String name, List<String> tags);

        int cloudLimit();

        int cloudCount();

        String status();

        void setStatus(String text);

        void closeMenu();

        /** Name of the named config the live settings were loaded from (the Overwrite target), or null. */
        default String loadedConfig() { return null; }

        /** True when the live settings differ from {@link #loadedConfig()}. */
        default boolean unsavedChanges() { return false; }

        /** Save the live settings into {@link #loadedConfig()}. */
        default void overwrite() { }

        /** True for Premium accounts (Config Maker is Premium only). */
        default boolean premium() { return false; }

        /**
         * Save a Config Maker config as a NEW cloud config (refuses an existing name), then optionally load it.
         * {@code onDone} runs on the client thread with true once saved, false when refused or failed.
         */
        default void saveGenerated(String name, String json, String gameMode, List<String> tags, boolean loadAfter,
                                   Consumer<Boolean> onDone) {
            setStatus("Config Maker is not available here.");
            onDone.accept(false);
        }
    }

    public record Info(String id, String gameMode, boolean active, boolean isPublic, List<String> tags) { }

    private enum Tab { MINE, COMMUNITY }

    private final Backend backend;
    private final ConfigHubManager manager = new ConfigHubManager();
    private Tab tab = Tab.MINE;

    private final UiTextField saveName = new UiTextField("Config name").maxLength(32).allow(UiTextField::isNameChar);
    private final UiTextField renameField = new UiTextField("New name").maxLength(32).allow(UiTextField::isNameChar);
    private final UiTextField hubSearch = new UiTextField("Search by name or username, Enter for full search").maxLength(40);
    private final UiTextField shareDescription = new UiTextField("Describe it (optional)").maxLength(120);
    private final UiTextField importCode = new UiTextField("Paste a share code").maxLength(64)
            .allow(cp -> (cp >= 'a' && cp <= 'z') || (cp >= 'A' && cp <= 'Z') || (cp >= '0' && cp <= '9')
                    || cp == '-' || cp == '_');
    private final UiSmoothScroll mineScroll = new UiSmoothScroll();
    private final UiSmoothScroll hubScroll = new UiSmoothScroll();

    // New-config choices (the save panel).
    private boolean newPublic;
    private final Set<String> newTags = new LinkedHashSet<>();

    // Editing one of your own configs (opened by clicking / right-clicking a row).
    private String editName;
    private String editTagsFor;
    private final Set<String> editTags = new LinkedHashSet<>();
    private String armedDeleteName;

    // Community tag filter + order.
    private final Set<String> filterTags = new LinkedHashSet<>();
    private static final String[] SORT_LABELS = {"Popular this week", "Top rated", "Newest"};
    private static final String[] SORT_KEYS = {CloudConfigs.SORT_POPULAR, CloudConfigs.SORT_TOP, CloudConfigs.SORT_NEWEST};
    private int sortIndex;
    private String lastUsernameSearch;
    private boolean rating;
    private static final int STAR_ON = 0xfffacc15;
    private boolean keepOriginalKeybinds;

    private List<RemoteConfig> hub = List.of();
    private boolean hubLoaded, hubLoading;
    private String hubError;
    private RemoteConfig selected;
    private int shareIndex;
    private final float[] mineRect = new float[4], hubRect = new float[4];
    private float lastScale = 1f;
    private String armedDeleteId;
    private long armedAt;

    // Config Maker: five questions, then a preview of the generated config (My configs tab).
    private boolean makerOpen;
    private int makerStep; // 0-4 = questions, 5 = preview
    private List<ConfigMaker.Question> makerQuestions = List.of();
    private final Map<String, String> makerAnswers = new HashMap<>();
    private ConfigMakerEngine.Plan makerPlan;
    private List<ConfigMaker.PreviewModule> makerPreview = List.of();
    private boolean makerSaving;
    private final UiTextField makerName = new UiTextField("Config name").maxLength(32).allow(UiTextField::isNameChar);
    private final UiSmoothScroll makerScroll = new UiSmoothScroll();
    private final float[] makerRect = new float[4];

    public ConfigsView(Backend backend) {
        this.backend = backend;
        makerName.onSubmit(() -> saveMaker(false));
        saveName.onSubmit(this::saveCurrent);
        hubSearch.onSubmit(this::searchByUsername);
        importCode.onSubmit(this::importShareCode);
    }

    /** Preview/testing hook: show these community configs without touching the network. */
    public void showCommunity(List<RemoteConfig> configs, RemoteConfig select) {
        tab = Tab.COMMUNITY;
        hub = List.copyOf(configs);
        hubLoaded = true;
        selected = select;
    }

    public void refresh() {
        refresh(null);
    }

    /**
     * As {@link #refresh()}, but with {@code username} run as a server-side search when non-blank -
     * the plain browse list only ever sees the first page of public configs, so searching by username
     * needs its own wider, server-filtered fetch to find a user's configs beyond that page.
     */
    private void refresh(String username) {
        if (hubLoading) return;
        hubLoading = true;
        hubError = null;
        lastUsernameSearch = username;
        int requestedSort = sortIndex;
        manager.fetchConfigs(username, SORT_KEYS[requestedSort], list -> {
            hub = list;
            hubLoading = false;
            hubLoaded = true;
            if (selected != null) {
                String id = selected.id;
                selected = hub.stream().filter(c -> c.id != null && c.id.equals(id)).findFirst().orElse(null);
            }
            if (requestedSort != sortIndex) refresh(lastUsernameSearch); // the order changed while this was loading
        }, error -> {
            hubLoading = false;
            hubLoaded = true;
            hubError = error;
        });
    }

    private void searchByUsername() {
        String query = hubSearch.value().trim();
        if (query.isEmpty()) {
            refresh();
            return;
        }
        refresh(query);
    }

    private void saveCurrent() {
        String name = saveName.value().trim();
        if (name.isEmpty()) {
            backend.setStatus("Type a name for the config first.");
            return;
        }
        backend.save(name, newPublic, new ArrayList<>(newTags));
        saveName.setValue("");
    }

    // ------------------------------------------------------------------------------------------

    /** Wheel input: scrolls whichever list is under the pointer. */
    public boolean scroll(double mx, double my, double amount, long now) {
        boolean maker = makerOpen && tab == Tab.MINE;
        float[] r = maker ? makerRect : tab == Tab.MINE ? mineRect : hubRect;
        if (mx < r[0] || mx > r[0] + r[2] || my < r[1] || my > r[1] + r[3]) return false;
        (maker ? makerScroll : tab == Tab.MINE ? mineScroll : hubScroll).wheel(amount, 56f * lastScale, now);
        return true;
    }

    public void draw(UiDisplayList.Builder b, UiHost host, float x, float y, float w, float h, float s,
                     float mx, float my) {
        lastScale = s;
        float padX = Math.round(22f * s);
        UiKit.title(b, host, "Configs", x + padX, y + Math.round(20f * s), s);
        float segW = Math.round(220f * s), segH = Math.round(30f * s);
        UiKit.segmented(b, host, new String[]{"My configs", "Community"}, tab.ordinal(),
                x + w - padX - segW, y + Math.round(14f * s), segW, segH, s, mx, my, i -> {
                    tab = Tab.values()[i];
                    host.focus(null);
                });
        drawMakerButton(b, host, x + w - padX - segW - Math.round(10f * s), y + Math.round(14f * s), segH, s, mx, my);
        UiKit.divider(b, host, x + padX, y + Math.round(56f * s), w - padX * 2f);

        float top = y + Math.round(72f * s);
        float statusH = Math.round(36f * s);
        float bottom = y + h - statusH;
        float cx = x + padX, cw = w - padX * 2f;
        if (tab == Tab.MINE && makerOpen) drawMaker(b, host, cx, top, cw, bottom - top, s, mx, my);
        else if (tab == Tab.MINE) drawMine(b, host, cx, top, cw, bottom - top, s, mx, my);
        else drawCommunity(b, host, cx, top, cw, bottom - top, s, mx, my);

        UiKit.divider(b, host, x + padX, bottom + Math.round(4f * s), w - padX * 2f);
        String status = backend.status();
        if (status != null && !status.isEmpty()) {
            b.text(cx, bottom + Math.round(14f * s), 10f * s, host.accent(), UiKit.fit(status, cw, 10f * s));
        } else if (!backend.signedIn()) {
            b.text(cx, bottom + Math.round(14f * s), 10f * s, host.muted2Color(),
                    "Sign in to Apexx to use cloud configs. Nothing is ever stored on this PC.");
        }
    }

    // ---- config maker ----------------------------------------------------------------------------

    private void drawMakerButton(UiDisplayList.Builder b, UiHost host, float right, float y, float h, float s,
                                 float mx, float my) {
        // Premium only: free sessions get no button at all (and an open maker closes).
        if (!backend.premium()) {
            makerOpen = false;
            return;
        }
        String label = "Config Maker";
        float bw = UiKit.buttonWidth(label, s);
        float bx = right - bw;
        boolean active = makerOpen && tab == Tab.MINE;
        UiKit.button(b, host, label, bx, y, bw, h, s, active ? Style.PRIMARY : Style.SECONDARY, true, mx, my,
                this::toggleMaker);
    }

    private void toggleMaker() {
        if (!backend.premium()) return;
        if (makerOpen && tab == Tab.MINE) {
            makerOpen = false;
            return;
        }
        tab = Tab.MINE;
        if (makerOpen) return; // keep the answers when coming back from the Community tab
        makerQuestions = ConfigMaker.questions();
        if (makerQuestions.isEmpty()) {
            backend.setStatus("The Config Maker rules are missing from this build.");
            return;
        }
        makerOpen = true;
        makerStep = 0;
        makerAnswers.clear();
        makerPlan = null;
        makerPreview = List.of();
        makerSaving = false;
    }

    private void makerNext() {
        if (makerStep < makerQuestions.size() - 1) {
            makerStep++;
            return;
        }
        makerPlan = ConfigMaker.plan(makerAnswers);
        if (makerPlan == null) {
            backend.setStatus("Could not build a config from these answers.");
            return;
        }
        makerPreview = ConfigMaker.preview(makerPlan);
        makerName.setValue(makerPlan.name);
        makerStep = makerQuestions.size();
    }

    private void saveMaker(boolean loadAfter) {
        if (makerSaving || makerPlan == null) return;
        if (!backend.premium()) {
            backend.setStatus("Config Maker is a Premium feature.");
            return;
        }
        String name = makerName.value().trim();
        if (name.isEmpty()) {
            backend.setStatus("Give the config a name first.");
            return;
        }
        String json = ConfigMaker.buildConfigJson(makerPlan);
        if (json == null) {
            backend.setStatus("Modules are still loading. Try again in a moment.");
            return;
        }
        makerSaving = true;
        backend.saveGenerated(name, json, makerPlan.gameMode, List.copyOf(makerPlan.tags), loadAfter, saved -> {
            makerSaving = false;
            if (saved) makerOpen = false;
        });
    }

    private void drawMaker(UiDisplayList.Builder b, UiHost host, float x, float y, float w, float h, float s,
                           float mx, float my) {
        int count = makerQuestions.size();
        boolean preview = makerStep >= count;
        float colW = Math.min(w, Math.round(720f * s));
        float cx = x + (w - colW) * 0.5f;

        b.text(cx, y, 13f * s, host.textColor(), "Config Maker", UiDrawCommand.TextWeight.SEMIBOLD);
        String stepText = preview ? "Preview" : "Question " + (makerStep + 1) + " of " + count;
        b.text(cx + colW - GpuUiFontMetrics.textWidth(stepText, 9.5f * s), y + Math.round(2f * s), 9.5f * s,
                host.muted2Color(), stepText);
        float barY = y + Math.round(24f * s), barH = Math.round(4f * s), segGap = Math.round(6f * s);
        float segW = (colW - segGap * (count - 1)) / Math.max(1, count);
        for (int i = 0; i < count; i++) {
            int color = preview || i < makerStep ? host.accent()
                    : i == makerStep ? UiKit.withAlpha(host.accent(), 0x70) : host.controlFill(0x22);
            b.rect(cx + i * (segW + segGap), barY, segW, barH, barH * 0.5f, color);
        }

        float bodyTop = barY + barH + Math.round(18f * s);
        float btnH = Math.round(32f * s);
        float footY = y + h - btnH;
        float bodyH = Math.max(0f, footY - Math.round(12f * s) - bodyTop);
        if (preview) drawMakerPreview(b, host, cx, bodyTop, colW, bodyH, s, mx, my);
        else drawMakerQuestion(b, host, cx, bodyTop, colW, s, mx, my);

        float gap = Math.round(8f * s);
        if (preview) {
            float bx = cx;
            float backW = UiKit.buttonWidth("Back", s);
            UiKit.button(b, host, "Back", bx, footY, backW, btnH, s, Style.SECONDARY, !makerSaving, mx, my,
                    () -> makerStep = count - 1);
            bx += backW + gap;
            float overW = UiKit.buttonWidth("Start over", s);
            UiKit.button(b, host, "Start over", bx, footY, overW, btnH, s, Style.GHOST, !makerSaving, mx, my, () -> {
                makerAnswers.clear();
                makerStep = 0;
                makerPlan = null;
            });
            float saveW = UiKit.buttonWidth(makerSaving ? "Saving" : "Save as new config", s);
            float loadW = UiKit.buttonWidth("Save and load", s);
            float rx = cx + colW - saveW;
            UiKit.button(b, host, makerSaving ? "Saving" : "Save as new config", rx, footY, saveW, btnH, s, Style.PRIMARY,
                    !makerSaving, mx, my, () -> saveMaker(false));
            UiKit.button(b, host, "Save and load", rx - gap - loadW, footY, loadW, btnH, s, Style.SECONDARY,
                    !makerSaving, mx, my, () -> saveMaker(true));
        } else {
            float cancelW = UiKit.buttonWidth("Cancel", s);
            UiKit.button(b, host, "Cancel", cx, footY, cancelW, btnH, s, Style.GHOST, true, mx, my, () -> makerOpen = false);
            boolean answered = makerAnswers.containsKey(makerQuestions.get(makerStep).id());
            String nextLabel = makerStep == count - 1 ? "Preview" : "Next";
            float nextW = Math.max(UiKit.buttonWidth(nextLabel, s), Math.round(90f * s));
            float nx = cx + colW - nextW;
            UiKit.button(b, host, nextLabel, nx, footY, nextW, btnH, s, Style.PRIMARY, answered, mx, my, this::makerNext);
            float backW = UiKit.buttonWidth("Back", s);
            UiKit.button(b, host, "Back", nx - gap - backW, footY, backW, btnH, s, Style.SECONDARY, makerStep > 0, mx, my,
                    () -> makerStep--);
        }
    }

    private void drawMakerQuestion(UiDisplayList.Builder b, UiHost host, float x, float y, float w, float s,
                                   float mx, float my) {
        ConfigMaker.Question q = makerQuestions.get(makerStep);
        b.text(x, y, 17f * s, host.titleColor(), UiKit.fit(q.title(), w, 17f * s), UiDrawCommand.TextWeight.SEMIBOLD);
        b.text(x, y + Math.round(26f * s), 9.5f * s, host.muted2Color(),
                UiKit.fit("Pick one. You can look over the whole config before it is saved.", w, 9.5f * s));
        String chosen = makerAnswers.get(q.id());
        float rowY = y + Math.round(48f * s), rowH = Math.round(40f * s), gap = Math.round(8f * s);
        float r = Math.round(12f * s);
        for (ConfigMaker.Option option : q.options()) {
            boolean sel = option.id().equals(chosen);
            boolean hover = host.hovered(mx, my, x, rowY, w, rowH);
            UiKit.card(b, host, x, rowY, w, rowH, s);
            if (sel) {
                b.rect(x, rowY, w, rowH, r, UiKit.withAlpha(host.accent(), 0x18));
                b.border(x, rowY, w, rowH, r, 1.4f, UiKit.withAlpha(host.accent(), 0xb0));
            } else if (hover) {
                b.rect(x, rowY, w, rowH, r, host.controlFill(0x0c));
            }
            float dot = Math.round(12f * s);
            float dotX = x + Math.round(16f * s), dotY = rowY + (rowH - dot) * 0.5f;
            b.rect(dotX, dotY, dot, dot, dot * 0.5f, sel ? host.accent() : host.controlFill(0x26));
            if (!sel) b.border(dotX, dotY, dot, dot, dot * 0.5f, 1f, 0x22ffffff);
            float textX = dotX + dot + Math.round(12f * s);
            b.text(textX, GpuUiFontMetrics.topInBox(rowY, rowH, 11.5f * s), 11.5f * s, sel ? host.textColor() : host.mutedColor(),
                    UiKit.fit(option.label(), x + w - textX - Math.round(12f * s), 11.5f * s), UiDrawCommand.TextWeight.SEMIBOLD);
            String id = q.id(), value = option.id();
            host.hit(x, rowY, w, rowH, () -> makerAnswers.put(id, value));
            rowY += rowH + gap;
        }
    }

    private void drawMakerPreview(UiDisplayList.Builder b, UiHost host, float x, float y, float w, float h, float s,
                                  float mx, float my) {
        UiKit.label(b, host, "Name", x, y, s);
        float fieldY = y + Math.round(16f * s), fieldH = Math.round(34f * s);
        makerName.draw(b, host, x, fieldY, w, fieldH, s);

        float chipY = fieldY + fieldH + Math.round(10f * s);
        float chipX = x;
        if (makerPlan != null) {
            chipX += UiKit.chip(b, host, makerPlan.gameMode, chipX, chipY, s, host.accent()) + Math.round(6f * s);
            for (String tag : makerPlan.tags) chipX += UiKit.chip(b, host, tag, chipX, chipY, s, host.mutedColor()) + Math.round(5f * s);
        }
        int on = 0;
        for (ConfigMaker.PreviewModule m : makerPreview) if (m.enabled()) on++;
        int tuned = makerPreview.size() - on;
        String summary = on + " module" + (on == 1 ? "" : "s") + " on" + (tuned > 0 ? ", " + tuned + " set up but off" : "");
        float sumW = GpuUiFontMetrics.textWidth(summary, 9.5f * s);
        if (x + w - sumW > chipX + Math.round(8f * s)) {
            b.text(x + w - sumW, chipY + Math.round(3f * s), 9.5f * s, host.muted2Color(), summary);
        }

        float listTop = chipY + Math.round(28f * s);
        // Warnings for risky choices (e.g. Silent on a strict anticheat). The player's choice still stands.
        if (makerPlan != null) {
            for (String warning : makerPlan.warnings) {
                b.text(x, listTop, 9.5f * s, 0xffffc66b, UiKit.fit(warning, w, 9.5f * s));
                listTop += Math.round(16f * s);
            }
            if (!makerPlan.warnings.isEmpty()) listTop += Math.round(4f * s);
        }
        float listH = Math.max(0f, y + h - listTop);
        makerRect[0] = x; makerRect[1] = listTop; makerRect[2] = w; makerRect[3] = listH;
        if (makerPreview.isEmpty()) {
            UiKit.emptyState(b, host, "Nothing to show yet", "Modules are still loading. Go back and press Preview again.",
                    x, listTop + Math.round(30f * s), w, s);
            return;
        }
        float headH = Math.round(22f * s), rowH = Math.round(46f * s), rowGap = Math.round(6f * s);
        // Layout pass: a header whenever the group changes (category for enabled modules, one group for the rest).
        List<float[]> layout = new ArrayList<>();
        List<String> headers = new ArrayList<>();
        String group = null;
        float cy = 0f;
        for (int i = 0; i < makerPreview.size(); i++) {
            ConfigMaker.PreviewModule m = makerPreview.get(i);
            String g = m.enabled() ? m.category().toUpperCase(Locale.ROOT) : "SET UP BUT OFF (TURN ON OR BIND A KEY)";
            if (!g.equals(group)) {
                group = g;
                headers.add(g);
                layout.add(new float[]{cy, -headers.size()});
                cy += headH;
            }
            layout.add(new float[]{cy, i});
            cy += rowH + rowGap;
        }
        makerScroll.update(host.frameDelta(), listH, Math.max(0f, cy - rowGap), host.now());
        float scroll = makerScroll.offset();
        b.clipPush(x, listTop, w, listH, 0);
        host.beginHitClip(x, listTop, w, listH);
        float pad = Math.round(14f * s);
        for (float[] item : layout) {
            float iy = listTop + item[0] - scroll;
            int ref = (int) item[1];
            if (ref < 0) {
                if (iy + headH < listTop || iy > listTop + listH) continue;
                b.text(x + Math.round(2f * s), iy + Math.round(6f * s), 9f * s, host.muted2Color(), headers.get(-ref - 1),
                        UiDrawCommand.TextWeight.SEMIBOLD);
                continue;
            }
            if (iy + rowH < listTop || iy > listTop + listH) continue;
            ConfigMaker.PreviewModule m = makerPreview.get(ref);
            UiKit.card(b, host, x, iy, w, rowH, s);
            String state = m.enabled() ? "On" : "Off";
            float stateW = Math.round(GpuUiFontMetrics.textWidth(state, 8.5f * s, UiDrawCommand.TextWeight.SEMIBOLD) + 16f * s);
            UiKit.chip(b, host, state, x + w - pad - stateW, iy + Math.round(9f * s), s, m.enabled() ? UiKit.SUCCESS : host.mutedColor());
            b.text(x + pad, iy + Math.round(9f * s), 11.5f * s, host.textColor(),
                    UiKit.fit(m.name(), w - pad * 2f - stateW - Math.round(8f * s), 11.5f * s), UiDrawCommand.TextWeight.SEMIBOLD);
            String details = m.highlights().isEmpty() ? "Default settings" : String.join("  ·  ", m.highlights());
            b.text(x + pad, iy + Math.round(27f * s), 9.5f * s, host.mutedColor(), UiKit.fit(details, w - pad * 2f, 9.5f * s));
        }
        host.endHitClip();
        b.clipPop();
        makerScroll.paintBar(b, x + w + Math.round(4f * s), listTop, listH, s, host.textColor(), host.now());
    }

    // ---- clickable tag chips -------------------------------------------------------------------

    private float drawTagChip(UiDisplayList.Builder b, UiHost host, String label, boolean on, boolean clickable,
                              float x, float y, float s, float mx, float my, Runnable click) {
        float size = 9f * s;
        float w = Math.round(GpuUiFontMetrics.textWidth(label, size, UiDrawCommand.TextWeight.SEMIBOLD) + 18f * s);
        float h = Math.round(20f * s);
        boolean hover = clickable && host.hovered(mx, my, x, y, w, h);
        b.rect(x, y, w, h, h * 0.5f, on ? UiKit.withAlpha(host.accent(), 0x30) : host.controlFill(hover ? 0x22 : 0x12));
        b.border(x, y, w, h, h * 0.5f, 1f, on ? UiKit.withAlpha(host.accent(), 0x90) : 0x18ffffff);
        b.text(x + 9f * s, GpuUiFontMetrics.topInBox(y, h, size), size, on ? host.accent() : host.muted2Color(), label,
                UiDrawCommand.TextWeight.SEMIBOLD);
        if (clickable && click != null) host.hit(x, y, w, h, click);
        return w;
    }

    /** Lay the full tag set out as a wrapping row of toggle chips. Returns the height it used. */
    private float drawTagToggles(UiDisplayList.Builder b, UiHost host, float x, float y, float w, float s,
                                 float mx, float my, Set<String> selected, Consumer<String> onToggle) {
        float gap = Math.round(6f * s), rowH = Math.round(20f * s), lineGap = Math.round(6f * s);
        float cxp = x, cyp = y;
        for (String tag : ConfigTags.ALL) {
            float cw = Math.round(GpuUiFontMetrics.textWidth(tag, 9f * s, UiDrawCommand.TextWeight.SEMIBOLD) + 18f * s);
            if (cxp + cw > x + w) { cxp = x; cyp += rowH + lineGap; }
            drawTagChip(b, host, tag, selected.contains(tag), true, cxp, cyp, s, mx, my, () -> onToggle.accept(tag));
            cxp += cw + gap;
        }
        return (cyp - y) + rowH;
    }

    // ---- my configs ----------------------------------------------------------------------------

    private void drawMine(UiDisplayList.Builder b, UiHost host, float x, float y, float w, float h, float s,
                          float mx, float my) {
        float gap = Math.round(20f * s);
        float leftW = Math.round(Math.min(430f * s, w * 0.52f));
        float rightX = x + leftW + gap, rightW = w - leftW - gap;

        // Edits are kept in a hidden working copy; the loaded config only changes on Overwrite.
        String loaded = backend.loadedConfig();
        boolean unsaved = loaded != null && backend.unsavedChanges();

        // Left: the list of saved configs.
        List<String> configs = backend.savedConfigs();
        UiKit.label(b, host, "Your configs", x, y, s);
        String count = backend.cloudCount() + " / " + backend.cloudLimit() + " cloud slots";
        float cs = 9.5f * s;
        b.text(x + leftW - GpuUiFontMetrics.textWidth(count, cs), y, cs, host.muted2Color(), count);
        float listTop = y + Math.round(20f * s), listH = Math.max(0f, h - Math.round(20f * s));
        mineRect[0] = x; mineRect[1] = listTop; mineRect[2] = leftW; mineRect[3] = listH;
        float rowH = Math.round(58f * s), rowGap = Math.round(8f * s), pitch = rowH + rowGap;
        mineScroll.update(host.frameDelta(), listH, Math.max(0f, configs.size() * pitch - rowGap), host.now());
        float scroll = mineScroll.offset();

        if (configs.isEmpty()) {
            UiKit.emptyState(b, host, "No configs yet", "Save your current settings to create one.",
                    x, listTop + Math.round(36f * s), leftW, s);
        }
        b.clipPush(x, listTop, leftW, listH, 0);
        host.beginHitClip(x, listTop, leftW, listH);
        for (int i = 0; i < configs.size(); i++) {
            float ry = listTop + i * pitch - scroll;
            if (ry + rowH < listTop || ry > listTop + listH) continue;
            String name = configs.get(i);
            Info info = backend.info(name);
            boolean active = info != null && info.active();
            boolean editing = name.equals(editName);
            boolean hover = host.hovered(mx, my, x, ry, leftW, rowH);
            UiKit.card(b, host, x, ry, leftW, rowH, s);
            if (hover && !editing) b.rect(x, ry, leftW, rowH, Math.round(12f * s), host.controlFill(0x0c));
            if (editing) b.border(x, ry, leftW, rowH, Math.round(12f * s), 1.4f, UiKit.withAlpha(host.accent(), 0xc0));
            else if (active) {
                b.border(x, ry, leftW, rowH, Math.round(12f * s), 1f, UiKit.withAlpha(host.accent(), 0x90));
                b.rect(x + Math.round(7f * s), ry + Math.round(14f * s), Math.round(3f * s), rowH - Math.round(28f * s),
                        Math.round(1.5f * s), host.accent());
            }
            float textX = x + Math.round(20f * s);
            float loadW = Math.round(62f * s), btnH = Math.round(28f * s);
            float btnY = ry + (rowH - btnH) * 0.5f;
            float loadX = x + leftW - loadW - Math.round(14f * s);
            float nameMaxW = loadX - textX - Math.round(10f * s);
            b.text(textX, ry + Math.round(12f * s), 12.5f * s, host.textColor(),
                    UiKit.fit(name, nameMaxW, 12.5f * s), UiDrawCommand.TextWeight.SEMIBOLD);
            // Second line: visibility + first couple of tags, or the local-profile note.
            if (info == null) {
                b.text(textX, ry + Math.round(33f * s), 9.5f * s, host.muted2Color(), "Local profile");
            } else {
                float chipX = textX;
                chipX += UiKit.chip(b, host, info.isPublic() ? "Public" : "Private", chipX, ry + Math.round(31f * s), s,
                        info.isPublic() ? UiKit.SUCCESS : 0xffffc66b) + Math.round(6f * s);
                if (active) chipX += UiKit.chip(b, host, "Active", chipX, ry + Math.round(31f * s), s, host.accent()) + Math.round(6f * s);
                if (unsaved && name.equalsIgnoreCase(loaded)) {
                    chipX += UiKit.chip(b, host, "Unsaved", chipX, ry + Math.round(31f * s), s, 0xffffc66b) + Math.round(6f * s);
                }
                List<String> tags = info.tags();
                for (int t = 0; t < tags.size() && chipX < loadX - 50f * s; t++) {
                    chipX += UiKit.chip(b, host, tags.get(t), chipX, ry + Math.round(31f * s), s, host.mutedColor()) + Math.round(5f * s);
                }
            }
            UiKit.button(b, host, "Load", loadX, btnY, loadW, btnH, s, Style.PRIMARY, true, mx, my,
                    () -> backend.load(name));
            // Click the row body (or right-click anywhere) to open its edit panel on the right.
            float bodyW = loadX - x - Math.round(6f * s);
            host.hit(x, ry, bodyW, rowH, () -> openEdit(name, info));
            host.hitSecondary(x, ry, leftW, rowH, () -> openEdit(name, info));
        }
        host.endHitClip();
        b.clipPop();
        mineScroll.paintBar(b, x + leftW + Math.round(4f * s), listTop, listH, s, host.textColor(), host.now());

        // Right: save panel (with public + tags), and either the edit panel or the hub pointer.
        float p = Math.round(18f * s);
        float saveH = Math.round(210f * s);
        UiKit.card(b, host, rightX, y, rightW, saveH, s);
        String saveTitle = loaded != null ? "Save as new config" : "Save current settings";
        b.text(rightX + p, y + Math.round(16f * s), 13f * s, host.textColor(), saveTitle,
                UiDrawCommand.TextWeight.SEMIBOLD);
        if (loaded != null) {
            // Unsaved state + Overwrite, on the title row's right.
            float titleW = GpuUiFontMetrics.textWidth(saveTitle, 13f * s, UiDrawCommand.TextWeight.SEMIBOLD);
            float rowRight = rightX + rightW - p;
            float owH = Math.round(26f * s), owY = y + Math.round(10f * s);
            if (unsaved) {
                float owW = UiKit.buttonWidth("Overwrite", s);
                UiKit.button(b, host, "Overwrite", rowRight - owW, owY, owW, owH, s, Style.PRIMARY, backend.signedIn(),
                        mx, my, backend::overwrite);
                String note = UiKit.fit("Unsaved changes in \"" + loaded + "\"",
                        Math.max(0f, rowRight - owW - Math.round(10f * s) - (rightX + p + titleW + Math.round(12f * s))), 9.5f * s);
                b.text(rowRight - owW - Math.round(10f * s) - GpuUiFontMetrics.textWidth(note, 9.5f * s),
                        GpuUiFontMetrics.topInBox(owY, owH, 9.5f * s), 9.5f * s, 0xffffc66b, note);
            } else {
                String note = UiKit.fit("\"" + loaded + "\" is up to date",
                        Math.max(0f, rowRight - (rightX + p + titleW + Math.round(12f * s))), 9.5f * s);
                b.text(rowRight - GpuUiFontMetrics.textWidth(note, 9.5f * s), GpuUiFontMetrics.topInBox(owY, owH, 9.5f * s),
                        9.5f * s, host.muted2Color(), note);
            }
        }
        float btnW = UiKit.buttonWidth("Save", s) + Math.round(10f * s), fieldH = Math.round(34f * s);
        float fieldY = y + Math.round(44f * s);
        saveName.draw(b, host, rightX + p, fieldY, rightW - p * 2f - btnW - Math.round(10f * s), fieldH, s);
        UiKit.button(b, host, "Save", rightX + rightW - p - btnW, fieldY, btnW, fieldH, s, Style.PRIMARY,
                true, mx, my, this::saveCurrent);
        // Public toggle.
        float togY = fieldY + fieldH + Math.round(12f * s);
        UiKit.toggle(b, host, rightX + p, togY, s, newPublic, () -> newPublic = !newPublic);
        b.text(rightX + p + Math.round(42f * s), GpuUiFontMetrics.topInBox(togY, Math.round(18f * s), 10.5f * s), 10.5f * s,
                host.textColor(), newPublic ? "Public - shared to the community" : "Private - only you");
        // Filter tags.
        float tagsY = togY + Math.round(28f * s);
        b.text(rightX + p, tagsY, 9f * s, host.muted2Color(), "FILTERS", UiDrawCommand.TextWeight.SEMIBOLD);
        drawTagToggles(b, host, rightX + p, tagsY + Math.round(14f * s), rightW - p * 2f, s, mx, my, newTags,
                tag -> { if (!newTags.remove(tag)) newTags.add(tag); });

        float secondY = y + saveH + Math.round(16f * s);
        float secondH = Math.max(Math.round(120f * s), h - saveH - Math.round(16f * s));
        if (editName != null) drawEditPanel(b, host, rightX, secondY, rightW, secondH, s, mx, my);
        else drawHubPointer(b, host, rightX, secondY, rightW, s, mx, my);
    }

    private void openEdit(String name, Info info) {
        editName = name;
        armedDeleteName = null;
        renameField.setValue("");
        editTags.clear();
        if (info != null) editTags.addAll(info.tags());
        editTagsFor = name;
    }

    private void drawEditPanel(UiDisplayList.Builder b, UiHost host, float x, float y, float w, float h, float s,
                               float mx, float my) {
        Info info = backend.info(editName);
        float p = Math.round(18f * s);
        UiKit.card(b, host, x, y, w, h, s);
        b.text(x + p, y + Math.round(16f * s), 13f * s, host.textColor(),
                UiKit.fit("Edit \"" + editName + "\"", w - p * 2f - 60f * s, 13f * s), UiDrawCommand.TextWeight.SEMIBOLD);
        float closeW = UiKit.buttonWidth("Done", s);
        UiKit.button(b, host, "Done", x + w - p - closeW, y + Math.round(12f * s), closeW, Math.round(26f * s), s,
                Style.GHOST, true, mx, my, () -> { editName = null; armedDeleteName = null; });

        if (info == null) {
            b.text(x + p, y + Math.round(48f * s), 10f * s, host.muted2Color(),
                    "Local profile - sign in and save it to the cloud to share or tag it.");
            return;
        }

        // Everything below scrolls out of a short card instead of spilling past it.
        float clipTop = y + Math.round(40f * s);
        b.clipPush(x, clipTop, w, Math.max(0f, y + h - clipTop - Math.round(6f * s)), 0);
        host.beginHitClip(x, clipTop, w, Math.max(0f, y + h - clipTop - Math.round(6f * s)));

        // Rename.
        float rowY = y + Math.round(44f * s), fieldH = Math.round(32f * s);
        float renameBtnW = UiKit.buttonWidth("Rename", s);
        renameField.draw(b, host, x + p, rowY, w - p * 2f - renameBtnW - Math.round(8f * s), fieldH, s);
        UiKit.button(b, host, "Rename", x + w - p - renameBtnW, rowY, renameBtnW, fieldH, s, Style.SECONDARY,
                !renameField.value().trim().isEmpty(), mx, my, () -> {
                    String nn = renameField.value().trim();
                    backend.rename(editName, nn);
                    ClientPrefs.get().renameConfig(editName, nn);
                    ConfigManager.getInstance().onConfigRenamed(editName, nn);
                    editName = nn;
                    renameField.setValue("");
                });

        // Visibility + delete.
        float actY = rowY + fieldH + Math.round(12f * s), btnH = Math.round(30f * s);
        String vis = info.isPublic() ? "Make private" : "Make public";
        float visW = UiKit.buttonWidth(vis, s);
        UiKit.button(b, host, vis, x + p, actY, visW, btnH, s, Style.SECONDARY, true, mx, my,
                () -> backend.setPublic(editName, !info.isPublic()));
        boolean armed = editName.equals(armedDeleteName);
        String delLabel = armed ? "Confirm delete" : "Delete";
        float delW = UiKit.buttonWidth("Confirm delete", s);
        UiKit.button(b, host, delLabel, x + p + visW + Math.round(8f * s), actY, delW, btnH, s, Style.DANGER, true, mx, my, () -> {
            if (armed) {
                backend.deleteCloud(editName);
                ClientPrefs.get().renameConfig(editName, null);
                ConfigManager.getInstance().onConfigRenamed(editName, null);
                editName = null;
                armedDeleteName = null;
            } else {
                armedDeleteName = editName;
            }
        });

        // Share code: copies the config's code to the clipboard (sharing makes it public).
        float shareY = actY + btnH + Math.round(10f * s);
        String shareLabel = "Copy share code";
        float shareW = UiKit.buttonWidth(shareLabel, s);
        String sharing = editName;
        UiKit.button(b, host, shareLabel, x + p, shareY, shareW, btnH, s, Style.SECONDARY, backend.signedIn(), mx, my,
                () -> copyShareCode(host, sharing));
        List<String> shareNote = UiKit.wrap("Anyone with the code can import it. Sharing makes the config public.",
                Math.max(0f, w - p * 2f - shareW - Math.round(10f * s)), 9f * s, 2);
        for (int i = 0; i < shareNote.size(); i++) {
            b.text(x + p + shareW + Math.round(10f * s), shareY + Math.round(4f * s) + i * Math.round(12f * s), 9f * s,
                    host.muted2Color(), shareNote.get(i));
        }

        // Per-server auto-load.
        float serverY = shareY + btnH + Math.round(14f * s);
        b.text(x + p, serverY, 9f * s, host.muted2Color(), "AUTO-LOAD ON SERVER", UiDrawCommand.TextWeight.SEMIBOLD);
        float serverRowY = serverY + Math.round(14f * s);
        String address = ServerAutoConfig.currentServerAddress(MinecraftClient.getInstance());
        List<String> linked = ClientPrefs.get().serversFor(editName);
        if (address == null) {
            b.text(x + p, serverRowY + Math.round(3f * s), 9.5f * s, host.mutedColor(),
                    UiKit.fit("Join a server to make this config load there automatically.", w - p * 2f, 9.5f * s));
        } else {
            String server = ClientPrefs.normalizeAddress(address);
            boolean on = editName.equalsIgnoreCase(String.valueOf(ClientPrefs.get().serverConfigFor(address)));
            String target = editName;
            UiKit.toggle(b, host, x + p, serverRowY, s, on,
                    () -> ClientPrefs.get().setServerConfig(address, on ? null : target));
            b.text(x + p + Math.round(42f * s), GpuUiFontMetrics.topInBox(serverRowY, Math.round(18f * s), 10f * s), 10f * s,
                    host.textColor(), UiKit.fit("Load when joining " + server, w - p * 2f - 42f * s, 10f * s));
        }
        float listY = serverRowY + Math.round(24f * s);
        if (!linked.isEmpty()) {
            String clearLabel = "Unlink all";
            float clearW = UiKit.buttonWidth(clearLabel, s);
            b.text(x + p, listY + Math.round(4f * s), 9f * s, host.mutedColor(),
                    UiKit.fit("Linked: " + String.join(", ", linked), w - p * 2f - clearW - Math.round(8f * s), 9f * s));
            String unlinking = editName;
            UiKit.button(b, host, clearLabel, x + w - p - clearW, listY - Math.round(3f * s), clearW, Math.round(22f * s), s,
                    Style.GHOST, true, mx, my, () -> ClientPrefs.get().renameConfig(unlinking, null));
            listY += Math.round(24f * s);
        }

        // Filters (apply immediately on toggle).
        float tagsY = listY + Math.round(6f * s);
        b.text(x + p, tagsY, 9f * s, host.muted2Color(), "FILTERS", UiDrawCommand.TextWeight.SEMIBOLD);
        if (!editName.equals(editTagsFor)) { editTags.clear(); editTags.addAll(info.tags()); editTagsFor = editName; }
        drawTagToggles(b, host, x + p, tagsY + Math.round(14f * s), w - p * 2f, s, mx, my, editTags, tag -> {
            if (!editTags.remove(tag)) editTags.add(tag);
            backend.setTags(editName, new ArrayList<>(editTags));
        });
        host.endHitClip();
        b.clipPop();
    }

    private void copyShareCode(UiHost host, String name) {
        if (!backend.signedIn()) {
            backend.setStatus("Sign in to share configs.");
            return;
        }
        backend.setStatus("Getting a share code for \"" + name + "\"...");
        PrivateConfigManager.getInstance().generateShareCode(name, code -> {
            host.setClipboard(code);
            backend.setStatus("Share code for \"" + name + "\" copied: " + code + " (the config is now public)");
        }, error -> backend.setStatus(ConfigHubManager.SHARE_BLOCKED_MESSAGE.equals(error) ? error
                : "Could not share \"" + name + "\": " + (error == null || error.isBlank() ? "cloud error" : error)));
    }

    private void importShareCode() {
        String code = importCode.value().trim();
        if (code.isEmpty()) {
            backend.setStatus("Paste a share code first (Ctrl+V).");
            return;
        }
        if (!backend.signedIn()) {
            backend.setStatus("Sign in to import shared configs.");
            return;
        }
        if (!PrivateConfigManager.isShareCode(code)) {
            backend.setStatus("That is not a share code. Codes are letters, digits, '-' and '_'.");
            return;
        }
        backend.setStatus("Importing shared config...");
        PrivateConfigManager.getInstance().importByShareCode(code, keepOriginalKeybinds, name -> {
            importCode.setValue("");
            backend.setStatus("Imported and loaded \"" + name + "\".");
            backend.closeMenu(); // same as "Download and use": the new profile is active now
        }, error -> backend.setStatus("Import failed: " + error));
    }

    private void drawHubPointer(UiDisplayList.Builder b, UiHost host, float x, float y, float w, float s, float mx, float my) {
        float p = Math.round(18f * s);
        float lineH = Math.round(14f * s), btnH = Math.round(30f * s);
        // Size the card to its content so the button always sits BELOW the (variable-length) hint text
        // instead of overlapping the last wrapped line.
        List<String> hubHint = UiKit.wrap("Browse configs other players shared (filter by tag), or share one of yours. "
                + "Click a config above to make it public, rename, tag, link it to a server or copy its share code.",
                w - p * 2f, 9.5f * s, 3);
        float textTop = Math.round(36f * s);
        float btnY = y + textTop + hubHint.size() * lineH + Math.round(10f * s);
        float importY = btnY + btnH + Math.round(12f * s);
        float hubH = (importY + btnH + Math.round(16f * s)) - y;
        UiKit.card(b, host, x, y, w, hubH, s);
        b.text(x + p, y + Math.round(16f * s), 13f * s, host.textColor(), "Community hub",
                UiDrawCommand.TextWeight.SEMIBOLD);
        for (int i = 0; i < hubHint.size(); i++) {
            b.text(x + p, y + textTop + i * lineH, 9.5f * s, host.muted2Color(), hubHint.get(i));
        }
        float openW = UiKit.buttonWidth("Open community hub", s);
        UiKit.button(b, host, "Open community hub", x + p, btnY, openW,
                btnH, s, Style.SECONDARY, true, mx, my, () -> tab = Tab.COMMUNITY);
        // Import by share code: copied into your configs as "<name> (copy)", loaded and made active.
        float importW = UiKit.buttonWidth("Import", s) + Math.round(8f * s);
        importCode.draw(b, host, x + p, importY, w - p * 2f - importW - Math.round(8f * s), btnH, s);
        UiKit.button(b, host, "Import", x + w - p - importW, importY, importW, btnH, s, Style.PRIMARY,
                !importCode.value().trim().isEmpty(), mx, my, this::importShareCode);
    }

    // ---- community hub ---------------------------------------------------------------------------

    private void drawCommunity(UiDisplayList.Builder b, UiHost host, float x, float y, float w, float h, float s,
                               float mx, float my) {
        if (!hubLoaded && !hubLoading) refresh();
        float gap = Math.round(20f * s);
        float leftW = Math.round(Math.min(440f * s, w * 0.5f));
        float rightX = x + leftW + gap, rightW = w - leftW - gap;

        // Search + refresh.
        float fieldH = Math.round(32f * s);
        float refreshW = UiKit.buttonWidth(hubLoading ? "Loading" : "Refresh", s);
        hubSearch.draw(b, host, x, y, leftW - refreshW - Math.round(8f * s), fieldH, s);
        UiKit.button(b, host, hubLoading ? "Loading" : "Refresh", x + leftW - refreshW, y, refreshW, fieldH, s,
                Style.SECONDARY, !hubLoading, mx, my, this::refresh);

        // Order: popular this week / top rated / newest (sorted server-side).
        float sortY = y + fieldH + Math.round(8f * s), sortH = Math.round(26f * s);
        UiKit.segmented(b, host, SORT_LABELS, sortIndex, x, sortY, leftW, sortH, s, mx, my, i -> {
            if (i == sortIndex) return;
            sortIndex = i;
            refresh(lastUsernameSearch);
        });

        // Tag filter row.
        float filterY = sortY + sortH + Math.round(10f * s);
        float filterH = drawTagToggles(b, host, x, filterY, leftW, s, mx, my, filterTags,
                tag -> { if (!filterTags.remove(tag)) filterTags.add(tag); });

        String query = hubSearch.value().trim().toLowerCase(Locale.ROOT);
        List<RemoteConfig> shown = new ArrayList<>();
        for (RemoteConfig c : hub) {
            boolean matchText = query.isEmpty() || contains(c.name, query) || contains(c.author, query)
                    || contains(c.gameMode, query) || contains(c.description, query);
            boolean matchTags = filterTags.isEmpty() || (c.tags != null && c.tags.stream().anyMatch(filterTags::contains));
            if (matchText && matchTags) shown.add(c);
        }

        float listTop = filterY + filterH + Math.round(12f * s), listH = Math.max(0f, h - (listTop - y));
        hubRect[0] = x; hubRect[1] = listTop; hubRect[2] = leftW; hubRect[3] = listH;
        float rowH = Math.round(78f * s), rowGap = Math.round(8f * s), pitch = rowH + rowGap;
        hubScroll.update(host.frameDelta(), listH, Math.max(0f, shown.size() * pitch - rowGap), host.now());
        float scroll = hubScroll.offset();
        if (hubError != null) {
            UiKit.emptyState(b, host, "Could not load the hub", UiKit.fit(hubError, leftW, 10f * s),
                    x, listTop + Math.round(36f * s), leftW, s);
        } else if (shown.isEmpty()) {
            UiKit.emptyState(b, host, hubLoading ? "Loading community configs..." : "Nothing here yet",
                    hubLoading ? "" : query.isEmpty() && filterTags.isEmpty() ? "Be the first to share a config." : "No config matches your filters.",
                    x, listTop + Math.round(36f * s), leftW, s);
        }
        b.clipPush(x, listTop, leftW, listH, 0);
        host.beginHitClip(x, listTop, leftW, listH);
        for (int i = 0; i < shown.size(); i++) {
            float ry = listTop + i * pitch - scroll;
            if (ry + rowH < listTop || ry > listTop + listH) continue;
            drawHubRow(b, host, shown.get(i), x, ry, leftW, rowH, s, mx, my);
        }
        host.endHitClip();
        b.clipPop();
        hubScroll.paintBar(b, x + leftW + Math.round(4f * s), listTop, listH, s, host.textColor(), host.now());

        drawDetails(b, host, rightX, y, rightW, h, s, mx, my);
    }

    private static boolean contains(String hay, String needle) {
        return hay != null && hay.toLowerCase(Locale.ROOT).contains(needle);
    }

    private void drawHubRow(UiDisplayList.Builder b, UiHost host, RemoteConfig c, float x, float y, float w, float h,
                            float s, float mx, float my) {
        boolean sel = selected != null && c.id != null && c.id.equals(selected.id);
        boolean hover = host.hovered(mx, my, x, y, w, h);
        float r = Math.round(10f * s);
        UiKit.card(b, host, x, y, w, h, s);
        if (hover && !sel) b.rect(x, y, w, h, r, host.controlFill(0x0c));
        if (sel) {
            b.rect(x, y, w, h, r, UiKit.withAlpha(host.accent(), 0x14));
            b.border(x, y, w, h, r, 1f, UiKit.withAlpha(host.accent(), 0xa0));
        }
        float pad = Math.round(16f * s);
        b.text(x + pad, y + Math.round(12f * s), 12.5f * s, host.textColor(), UiKit.fit(c.name, w - pad * 2f - 70f * s, 12.5f * s),
                UiDrawCommand.TextWeight.SEMIBOLD);
        b.text(x + pad, y + Math.round(30f * s), 9.5f * s, host.mutedColor(),
                UiKit.fit("by " + (c.author == null ? "unknown" : c.author), Math.max(0f, w - pad * 2f - 170f * s), 9.5f * s));
        // Rating on the author line's right: five dots + "4.3 (12)", and a trend chip in "Popular this week".
        String ratingText = c.ratingCount > 0 ? String.format(Locale.ROOT, "%.1f (%d)", c.ratingAvg, c.ratingCount) : "Unrated";
        float rtW = GpuUiFontMetrics.textWidth(ratingText, 9f * s);
        float dotsW = ratingDotsWidth(Math.round(7f * s), Math.round(3f * s));
        float rx = x + w - pad - rtW;
        b.text(rx, y + Math.round(30f * s), 9f * s, host.muted2Color(), ratingText);
        drawRatingDots(b, host, rx - Math.round(6f * s) - dotsW, y + Math.round(31f * s), Math.round(7f * s), Math.round(3f * s),
                c.ratingAvg, c.myRating, false, mx, my, null);
        if (sortIndex == 0 && c.weeklyScore > 0) {
            float tw = GpuUiFontMetrics.textWidth("Trending", 8.5f * s) + 14f * s;
            UiKit.chip(b, host, "Trending", rx - Math.round(12f * s) - dotsW - tw, y + Math.round(28f * s), s, host.accent());
        }
        // Tag chips (or the description when there are none).
        if (c.tags != null && !c.tags.isEmpty()) {
            float chipX = x + pad;
            for (int t = 0; t < c.tags.size() && chipX < x + w - pad - 50f * s; t++) {
                chipX += UiKit.chip(b, host, c.tags.get(t), chipX, y + Math.round(48f * s), s, host.mutedColor()) + Math.round(5f * s);
            }
        } else {
            String desc = c.description == null || c.description.isBlank() ? "No description." : c.description;
            b.text(x + pad, y + Math.round(50f * s), 9.5f * s, host.muted2Color(), UiKit.fit(desc, w - pad * 2f, 9.5f * s));
        }
        String mode = c.gameMode == null ? "Other" : c.gameMode;
        float chipW = GpuUiFontMetrics.textWidth(mode, 8.5f * s) + 14f * s;
        UiKit.chip(b, host, mode, x + w - pad - chipW, y + Math.round(12f * s), s, host.accent());
        if (c.owned) {
            float yw = GpuUiFontMetrics.textWidth("Yours", 8.5f * s) + 14f * s;
            UiKit.chip(b, host, "Yours", x + w - pad - chipW - yw - Math.round(6f * s), y + Math.round(12f * s), s, UiKit.SUCCESS);
        }
        host.hit(x, y, w, h, () -> {
            selected = c;
            armedDeleteId = null;
        });
    }

    private void drawDetails(UiDisplayList.Builder b, UiHost host, float x, float y, float w, float h, float s,
                             float mx, float my) {
        float p = Math.round(18f * s);
        List<String> lines = selected == null ? List.of() : UiKit.wrap(selected.description == null
                || selected.description.isBlank() ? "The author did not add a description." : selected.description,
                w - p * 2f, 10f * s, 4);
        float btnH = Math.round(32f * s);
        float toggleRowH = Math.round(24f * s);
        float ratingRowH = Math.round(26f * s);
        float detailH = selected == null ? Math.round(150f * s)
                : Math.min(h * 0.66f, Math.round(104f * s) + ratingRowH + lines.size() * Math.round(15f * s) + toggleRowH
                        + Math.round(20f * s) + btnH + p);
        UiKit.card(b, host, x, y, w, detailH, s);
        if (selected == null) {
            UiKit.emptyState(b, host, "Select a config", "Pick one from the list to see its details.",
                    x, y + detailH * 0.5f - Math.round(10f * s), w, s);
        } else {
            RemoteConfig c = selected;
            b.text(x + p, y + Math.round(16f * s), 17f * s, 0xfff6f7f9, UiKit.fit(c.name, w - p * 2f, 17f * s),
                    UiDrawCommand.TextWeight.SEMIBOLD);
            b.text(x + p, y + Math.round(40f * s), 10f * s, host.mutedColor(), "by " + (c.author == null ? "unknown" : c.author));
            float cxp = x + p, cyp = y + Math.round(60f * s);
            cxp += UiKit.chip(b, host, c.gameMode == null ? "Other" : c.gameMode, cxp, cyp, s, host.accent()) + Math.round(6f * s);
            if (c.owned) {
                cxp += UiKit.chip(b, host, c.isPublic ? "Public" : "Private", cxp, cyp, s,
                        c.isPublic ? UiKit.SUCCESS : 0xffffc66b) + Math.round(6f * s);
            }
            if (c.tags != null) {
                for (String tag : c.tags) {
                    if (cxp > x + w - p - 50f * s) break;
                    cxp += UiKit.chip(b, host, tag, cxp, cyp, s, host.mutedColor()) + Math.round(5f * s);
                }
            }
            // Rating: click a dot to rate 1-5 (click your current rating again to clear it). Not on your own configs.
            float ratingY = y + Math.round(86f * s);
            boolean canRate = !c.owned && backend.signedIn() && !rating;
            float dot = Math.round(12f * s), dotGap = Math.round(5f * s);
            drawRatingDots(b, host, x + p, ratingY + Math.round(2f * s), dot, dotGap, c.ratingAvg, c.myRating, canRate, mx, my,
                    stars -> rate(c, stars == c.myRating ? 0 : stars));
            String summary = c.ratingCount > 0
                    ? String.format(Locale.ROOT, "%.1f from %d rating%s", c.ratingAvg, c.ratingCount, c.ratingCount == 1 ? "" : "s")
                    : "No ratings yet";
            if (c.myRating > 0) summary += " · yours: " + c.myRating;
            else if (c.owned) summary += " · your config";
            else if (!backend.signedIn()) summary += " · sign in to rate";
            float sumX = x + p + ratingDotsWidth(dot, dotGap) + Math.round(10f * s);
            b.text(sumX, GpuUiFontMetrics.topInBox(ratingY, dot + Math.round(4f * s), 9.5f * s), 9.5f * s, host.mutedColor(),
                    UiKit.fit(summary, x + w - p - sumX, 9.5f * s));
            for (int i = 0; i < lines.size(); i++) {
                b.text(x + p, y + Math.round(88f * s) + ratingRowH + i * Math.round(15f * s), 10f * s, host.mutedColor(), lines.get(i));
            }

            float btnY = y + detailH - p - btnH;
            float toggleY = btnY - toggleRowH;
            float toggleKnobY = toggleY + Math.round((toggleRowH - 18f * s) * 0.5f);
            UiKit.toggle(b, host, x + p, toggleKnobY, s, keepOriginalKeybinds, () -> keepOriginalKeybinds = !keepOriginalKeybinds);
            b.text(x + p + Math.round(42f * s), toggleY + Math.round((toggleRowH - 9.5f * s) * 0.5f), 9.5f * s,
                    host.mutedColor(), "Keep my own keybinds instead of this config's");

            float bx = x + p;
            float dlW = UiKit.buttonWidth("Download and use", s);
            UiKit.button(b, host, "Download and use", bx, btnY, dlW, btnH, s, Style.PRIMARY, true, mx, my, () -> download(c));
            bx += dlW + Math.round(8f * s);
            if (c.owned) {
                String visl = c.isPublic ? "Make private" : "Make public";
                float visW = UiKit.buttonWidth(visl, s);
                UiKit.button(b, host, visl, bx, btnY, visW, btnH, s, Style.SECONDARY, true, mx, my, () -> toggleVisibility(c));
                bx += visW + Math.round(8f * s);
                boolean armed = armedDeleteId != null && armedDeleteId.equals(c.id)
                        && host.now() - armedAt < 3_500_000_000L;
                String delLabel = armed ? "Confirm delete" : "Delete";
                float delW = UiKit.buttonWidth("Confirm delete", s);
                UiKit.button(b, host, delLabel, bx, btnY, delW, btnH, s, Style.DANGER, true, mx, my, () -> {
                    if (armed) delete(c);
                    else { armedDeleteId = c.id; armedAt = host.now(); }
                });
            }
        }
        drawShare(b, host, x, y + detailH + Math.round(16f * s), w, h - detailH - Math.round(16f * s), s, mx, my);
    }

    private void drawShare(UiDisplayList.Builder b, UiHost host, float x, float y, float w, float h, float s,
                           float mx, float my) {
        float shareH = Math.min(h, Math.round(170f * s));
        if (shareH < Math.round(110f * s)) return;
        UiKit.card(b, host, x, y, w, shareH, s);
        float p = Math.round(18f * s);
        b.text(x + p, y + Math.round(16f * s), 13f * s, host.textColor(), "Share one of your configs",
                UiDrawCommand.TextWeight.SEMIBOLD);
        List<String> mine = backend.savedConfigs();
        if (mine.isEmpty()) {
            b.text(x + p, y + Math.round(46f * s), 10f * s, host.muted2Color(), "Save a config first, then you can share it here.");
            return;
        }
        shareIndex = Math.floorMod(shareIndex, mine.size());
        String chosen = mine.get(shareIndex);
        float rowY = y + Math.round(42f * s), rowH = Math.round(32f * s);
        float arrowW = Math.round(30f * s);
        UiKit.button(b, host, "<", x + p, rowY, arrowW, rowH, s, Style.SECONDARY, mine.size() > 1, mx, my, () -> shareIndex--);
        float shareBtnW = UiKit.buttonWidth("Share", s) + Math.round(8f * s);
        float nameW = w - p * 2f - arrowW * 2f - Math.round(12f * s);
        float nameX = x + p + arrowW + Math.round(6f * s);
        b.rect(nameX, rowY, nameW, rowH, Math.min(rowH * 0.5f, Math.round(12f * s)), host.controlFill(0x10));
        String shownName = UiKit.fit(chosen, nameW - 16f * s, 11f * s);
        b.text(nameX + (nameW - GpuUiFontMetrics.textWidth(shownName, 11f * s)) * 0.5f, GpuUiFontMetrics.topInBox(rowY, rowH, 11f * s), 11f * s,
                host.textColor(), shownName, UiDrawCommand.TextWeight.SEMIBOLD);
        UiKit.button(b, host, ">", x + w - p - arrowW, rowY, arrowW, rowH, s, Style.SECONDARY, mine.size() > 1, mx, my, () -> shareIndex++);

        float descY = rowY + rowH + Math.round(10f * s);
        shareDescription.draw(b, host, x + p, descY, w - p * 2f - shareBtnW - Math.round(8f * s), rowH, s);
        UiKit.button(b, host, "Share", x + w - p - shareBtnW, descY, shareBtnW, rowH, s, Style.PRIMARY,
                backend.signedIn(), mx, my, () -> share(chosen));
        List<String> note = UiKit.wrap("Sharing makes it public. Set its filters and visibility from the My configs tab.",
                w - p * 2f, 9f * s, 2);
        for (int i = 0; i < note.size(); i++) {
            b.text(x + p, descY + rowH + Math.round(10f * s) + i * Math.round(13f * s), 9f * s, host.muted2Color(), note.get(i));
        }
    }

    // ---- rating dots ----------------------------------------------------------------------------

    private static float ratingDotsWidth(float dot, float gap) {
        return dot * 5f + gap * 4f;
    }

    /**
     * Five round "stars": the rounded average (or your own rating, in the accent colour) filled. When
     * {@code clickable}, hovering previews and clicking dot n calls {@code onRate} with n.
     */
    private void drawRatingDots(UiDisplayList.Builder b, UiHost host, float x, float y, float dot, float gap, double avg,
                                int mine, boolean clickable, float mx, float my, java.util.function.IntConsumer onRate) {
        int hover = 0;
        if (clickable) {
            for (int n = 1; n <= 5; n++) {
                float dx = x + (n - 1) * (dot + gap);
                if (host.hovered(mx, my, dx - gap * 0.5f, y - gap, dot + gap, dot + gap * 2f)) hover = n;
            }
        }
        int filled = hover > 0 ? hover : mine > 0 ? mine : (int) Math.round(avg);
        int onColor = hover > 0 || mine > 0 ? host.accent() : STAR_ON;
        for (int n = 1; n <= 5; n++) {
            float dx = x + (n - 1) * (dot + gap);
            boolean on = n <= filled;
            b.rect(dx, y, dot, dot, dot * 0.5f, on ? onColor : host.controlFill(0x26));
            if (!on) b.border(dx, y, dot, dot, dot * 0.5f, 1f, 0x22ffffff);
            if (clickable && onRate != null) {
                int stars = n;
                host.hit(dx - gap * 0.5f, y - gap, dot + gap, dot + gap * 2f, () -> onRate.accept(stars));
            }
        }
    }

    private void rate(RemoteConfig c, int stars) {
        if (rating) return;
        rating = true;
        backend.setStatus(stars == 0 ? "Removing your rating..." : "Rating \"" + c.name + "\"...");
        manager.rate(c, stars, message -> {
            rating = false;
            backend.setStatus(message);
        }, error -> {
            rating = false;
            backend.setStatus(error);
        });
    }

    // ---- actions --------------------------------------------------------------------------------

    private void download(RemoteConfig c) {
        backend.setStatus("Downloading \"" + c.name + "\"...");
        manager.downloadConfig(c, keepOriginalKeybinds, saved -> {
            backend.setStatus("Downloaded and loaded \"" + c.name + "\".");
            backend.closeMenu();
        }, error -> backend.setStatus("Download failed: " + error));
    }

    private void toggleVisibility(RemoteConfig c) {
        boolean makePublic = !c.isPublic;
        backend.setStatus("Updating visibility...");
        manager.setVisibility(c, makePublic, message -> {
            c.isPublic = makePublic;
            backend.setStatus(message);
            refresh();
        }, error -> backend.setStatus(error));
    }

    private void delete(RemoteConfig c) {
        armedDeleteId = null;
        backend.setStatus("Deleting \"" + c.name + "\"...");
        manager.deleteConfig(c, message -> {
            selected = null;
            backend.setStatus(message);
            refresh();
        }, error -> backend.setStatus(error));
    }

    private void share(String localName) {
        backend.setStatus("Sharing \"" + localName + "\"...");
        manager.uploadConfig(localName, localName, shareDescription.value().trim(), message -> {
            backend.setStatus(message);
            shareDescription.setValue("");
            refresh();
        }, error -> backend.setStatus("Share failed: " + error));
    }
}
