package interstellarTikiTiks.apexx.client.gui.gpuui.kit;

import interstellarTikiTiks.apexx.client.auth.DevlogClient;
import interstellarTikiTiks.apexx.client.config.ClientPrefs;
import interstellarTikiTiks.apexx.client.gui.gpuui.framework.UiDisplayList;
import interstellarTikiTiks.apexx.client.gui.gpuui.framework.UiDrawCommand;
import interstellarTikiTiks.apexx.client.gui.gpuui.kit.UiKit.Style;
import net.minecraft.client.MinecraftClient;

import java.util.ArrayList;
import java.util.List;

/**
 * "What's new" in the click GUI: the same devlog feed the sign-in window shows (live from the Apexx
 * site, falling back to the entries bundled with the client). Fetched once per session on a worker
 * thread and handed to the client thread; the menu never waits on it. The newest entry the player has
 * opened is remembered in {@link ClientPrefs}, which drives the unread dot on the header button.
 */
public final class WhatsNewPanel {
    private static volatile List<DevlogClient.Entry> entries;
    private static volatile boolean loading;

    private boolean open;
    private float scroll;
    private float maxScroll;
    private final float[] listRect = new float[4];

    /** Start the one-per-session fetch (no-op once loaded or while loading). Safe from any thread. */
    public static void preload() {
        if (entries != null || loading) return;
        loading = true;
        Thread worker = new Thread(() -> {
            List<DevlogClient.Entry> fetched;
            try {
                fetched = DevlogClient.fetchRecent();
            } catch (Throwable unavailable) {
                fetched = new ArrayList<>();
            }
            List<DevlogClient.Entry> result = fetched == null ? List.of() : List.copyOf(fetched);
            Runnable publish = () -> {
                entries = result;
                loading = false;
            };
            try {
                MinecraftClient client = MinecraftClient.getInstance();
                if (client != null) client.execute(publish);
                else publish.run();
            } catch (Throwable stopping) {
                publish.run();
            }
        }, "apexx-devlog");
        worker.setDaemon(true);
        worker.start();
    }

    static String idOf(DevlogClient.Entry entry) {
        return (entry.date == null ? "" : entry.date) + "|" + entry.title;
    }

    /** A newer devlog entry than the one the player last opened (only once their prefs are known). */
    public static boolean hasUnread() {
        List<DevlogClient.Entry> list = entries;
        if (list == null || list.isEmpty()) return false;
        ClientPrefs prefs = ClientPrefs.get();
        return prefs.isOwned() && !idOf(list.get(0)).equals(prefs.getLastSeenDevlog());
    }

    public boolean isOpen() {
        return open;
    }

    public void open() {
        open = true;
        scroll = 0f;
        preload();
    }

    public void close() {
        open = false;
    }

    /** Wheel over the list. Always consumes while open (it is modal). */
    public boolean scroll(double mx, double my, double amount, float s) {
        if (!open) return false;
        scroll = Math.max(0f, Math.min(maxScroll, scroll - (float) amount * 40f * s));
        return true;
    }

    /** Modal panel; the caller clears its click regions first. */
    public void draw(UiDisplayList.Builder b, UiHost host, float sw, float sh, float s, float mx, float my) {
        if (!open) return;
        List<DevlogClient.Entry> list = entries;
        if (list != null && !list.isEmpty()) ClientPrefs.get().setLastSeenDevlog(idOf(list.get(0)));

        host.hit(0, 0, sw, sh, this::close);
        b.rect(0, 0, sw, sh, 0, 0x8c000000);
        float w = Math.round(Math.min(560f * s, sw - 32f * s));
        float h = Math.round(Math.min(540f * s, sh - 32f * s));
        float x = Math.round((sw - w) * 0.5f), y = Math.round((sh - h) * 0.5f);
        float r = Math.round(14f * s * host.cornerScale());
        b.shadow(x, y + 2f * s, w, h, r, 14f * s, 0f, 0x80000000);
        b.rect(x, y, w, h, r, 0xf4101318);
        b.border(x, y, w, h, r, Math.max(1f, s), 0x30ffffff);
        host.hit(x, y, w, h, () -> { });

        float pad = Math.round(20f * s);
        b.text(x + pad, y + pad, 16f * s, host.titleColor(), "What's new", UiDrawCommand.TextWeight.SEMIBOLD);
        float closeW = UiKit.buttonWidth("Close", s), btnH = Math.round(28f * s);
        UiKit.button(b, host, "Close", x + w - pad - closeW, y + pad - 4f * s, closeW, btnH, s, Style.SECONDARY,
                true, mx, my, this::close);
        UiKit.divider(b, host, x + pad, y + pad + 34f * s, w - pad * 2f);

        float top = y + pad + 44f * s, bottom = y + h - pad;
        listRect[0] = x; listRect[1] = top; listRect[2] = w; listRect[3] = bottom - top;
        if (list == null) {
            b.text(x + pad, top + 8f * s, 11f * s, host.mutedColor(), "Loading the devlog...");
            return;
        }
        if (list.isEmpty()) {
            b.text(x + pad, top + 8f * s, 11f * s, host.mutedColor(), "No devlog entries right now. Check back later.");
            return;
        }
        float contentW = w - pad * 2f;
        b.clipPush(x, top, w, bottom - top, 0);
        float cy = top - scroll;
        float bodySize = 10.5f * s, pitch = Math.round(15f * s);
        for (DevlogClient.Entry entry : list) {
            b.text(x + pad, cy, 13f * s, host.textColor(),
                    UiKit.fit(entry.title, contentW, 13f * s, UiDrawCommand.TextWeight.SEMIBOLD), UiDrawCommand.TextWeight.SEMIBOLD);
            cy += Math.round(19f * s);
            StringBuilder meta = new StringBuilder();
            if (entry.date != null && !entry.date.isBlank()) meta.append(entry.date);
            if (entry.version != null && !entry.version.isBlank()) meta.append(meta.isEmpty() ? "" : "  ·  ").append(entry.version);
            if (entry.tag != null && !entry.tag.isBlank()) meta.append(meta.isEmpty() ? "" : "  ·  ").append(entry.tag);
            if (!meta.isEmpty()) {
                b.text(x + pad, cy, 9f * s, host.accent(), UiKit.fit(meta.toString(), contentW, 9f * s));
                cy += Math.round(16f * s);
            }
            if (entry.body != null) {
                for (String line : entry.body) {
                    List<String> wrapped = UiKit.wrap(line, contentW - 14f * s, bodySize, 8);
                    for (int i = 0; i < wrapped.size(); i++) {
                        if (i == 0) b.text(x + pad, cy, bodySize, host.accent(), "•");
                        b.text(x + pad + 14f * s, cy, bodySize, host.mutedColor(), wrapped.get(i));
                        cy += pitch;
                    }
                }
            }
            cy += Math.round(14f * s);
            UiKit.divider(b, host, x + pad, cy - Math.round(8f * s), contentW);
        }
        b.clipPop();
        maxScroll = Math.max(0f, (cy + scroll) - top - (bottom - top));
        scroll = Math.min(scroll, maxScroll);
    }
}
