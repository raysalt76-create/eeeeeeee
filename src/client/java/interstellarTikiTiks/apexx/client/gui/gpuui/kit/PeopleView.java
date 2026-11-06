package interstellarTikiTiks.apexx.client.gui.gpuui.kit;

import interstellarTikiTiks.apexx.client.gui.gpuui.framework.UiDisplayList;
import interstellarTikiTiks.apexx.client.gui.gpuui.framework.UiDrawCommand;
import interstellarTikiTiks.apexx.client.gui.gpuui.framework.UiSmoothScroll;
import interstellarTikiTiks.apexx.client.gui.gpuui.kit.UiKit.Style;
import interstellarTikiTiks.apexx.client.gui.gpuui.render.GpuUiFontMetrics;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Friends and enemies in one page: everyone you saved on the left, players currently loaded in your world
 * on the right with one-click Friend / Enemy toggles. Friends are never targeted; enemies are prioritised.
 */
public final class PeopleView {
    public record Person(String name, UUID uuid, boolean friend, boolean enemy) { }

    /** Game-side access, so the page itself has no Minecraft dependencies. */
    public interface Backend {
        List<Person> saved();

        List<Person> nearby();

        /** Returns the status message to show. */
        String toggleFriend(Person person);

        String toggleEnemy(Person person);

        String remove(Person person);

        /** Bind code of the key/mouse button that friends the player under the crosshair (-1 = none). */
        default int friendKey() {
            return interstellarTikiTiks.apexx.client.friend.FriendKeybindManager.getInstance().getKeyCode();
        }

        default void setFriendKey(int code) {
            interstellarTikiTiks.apexx.client.friend.FriendKeybindManager.getInstance().setKeyCode(code);
        }

        default String friendKeyName() {
            return interstellarTikiTiks.apexx.client.friend.FriendKeybindManager.getInstance().getKeyDisplayName();
        }

        /** Bind code of the key/mouse button that marks the player under the crosshair as an enemy (-1 = none). */
        default int enemyKey() {
            return interstellarTikiTiks.apexx.client.enemy.EnemyKeybindManager.getInstance().getKeyCode();
        }

        default void setEnemyKey(int code) {
            interstellarTikiTiks.apexx.client.enemy.EnemyKeybindManager.getInstance().setKeyCode(code);
        }

        default String enemyKeyName() {
            return interstellarTikiTiks.apexx.client.enemy.EnemyKeybindManager.getInstance().getKeyDisplayName();
        }
    }

    private enum BindTarget { NONE, FRIEND, ENEMY }

    private enum Filter { ALL, FRIENDS, ENEMIES }

    private final Backend backend;
    private Filter filter = Filter.ALL;
    private String status = "";
    private long statusAt = Long.MIN_VALUE;
    private final UiSmoothScroll savedScroll = new UiSmoothScroll();
    private final UiSmoothScroll nearbyScroll = new UiSmoothScroll();
    private final float[] savedRect = new float[4], nearbyRect = new float[4];
    private float lastScale = 1f;
    private List<Person> fixedSaved, fixedNearby;
    /** Which bind the next captured key goes to, while the host is capturing for this page. */
    private BindTarget binding = BindTarget.NONE;

    public PeopleView(Backend backend) {
        this.backend = backend;
    }

    /** Preview/testing hook. */
    public void preview(List<Person> saved, List<Person> nearby) {
        fixedSaved = saved;
        fixedNearby = nearby;
    }

    public boolean scroll(double mx, double my, double amount, long now) {
        if (inside(mx, my, savedRect)) { savedScroll.wheel(amount, 56f * lastScale, now); return true; }
        if (inside(mx, my, nearbyRect)) { nearbyScroll.wheel(amount, 56f * lastScale, now); return true; }
        return false;
    }

    private static boolean inside(double mx, double my, float[] r) {
        return mx >= r[0] && mx <= r[0] + r[2] && my >= r[1] && my <= r[1] + r[3];
    }

    private boolean matches(Person p) {
        return switch (filter) {
            case ALL -> true;
            case FRIENDS -> p.friend();
            case ENEMIES -> p.enemy();
        };
    }

    private void say(String text, UiHost host) {
        status = text == null ? "" : text;
        statusAt = host.now();
    }

    public void draw(UiDisplayList.Builder b, UiHost host, float x, float y, float w, float h, float s,
                     float mx, float my) {
        lastScale = s;
        float padX = Math.round(22f * s);
        UiKit.title(b, host, "People", x + padX, y + Math.round(20f * s), s);
        float segW = Math.round(250f * s), segH = Math.round(30f * s);
        UiKit.segmented(b, host, new String[]{"All", "Friends", "Enemies"}, filter.ordinal(),
                x + w - padX - segW, y + Math.round(14f * s), segW, segH, s, mx, my, i -> filter = Filter.values()[i]);
        drawBindButtons(b, host, x + w - padX - segW - Math.round(12f * s), y + Math.round(14f * s), segH, s, mx, my);
        UiKit.divider(b, host, x + padX, y + Math.round(56f * s), w - padX * 2f);

        float top = y + Math.round(72f * s), bottom = y + h - Math.round(36f * s);
        float gap = Math.round(20f * s);
        float colW = (w - padX * 2f - gap) * 0.5f;
        float lx = x + padX, rx = lx + colW + gap;

        List<Person> saved = new ArrayList<>();
        for (Person p : fixedSaved != null ? fixedSaved : backend.saved()) if (matches(p)) saved.add(p);
        List<Person> nearby = new ArrayList<>();
        for (Person p : fixedNearby != null ? fixedNearby : backend.nearby()) if (matches(p)) nearby.add(p);

        UiKit.label(b, host, "Saved (" + saved.size() + ")", lx, top, s);
        drawList(b, host, saved, true, lx, top + Math.round(20f * s), colW, bottom - top - Math.round(20f * s), s, mx, my,
                savedScroll, savedRect,
                filter == Filter.ALL ? "No saved friends or enemies yet." : filter == Filter.FRIENDS ? "No saved friends yet." : "No saved enemies yet.");
        UiKit.label(b, host, "Players nearby (" + nearby.size() + ")", rx, top, s);
        drawList(b, host, nearby, false, rx, top + Math.round(20f * s), colW, bottom - top - Math.round(20f * s), s, mx, my,
                nearbyScroll, nearbyRect, "Nobody else is loaded in your world.");

        UiKit.divider(b, host, x + padX, bottom + Math.round(4f * s), w - padX * 2f);
        if (!status.isEmpty() && host.now() - statusAt < 5_000_000_000L) {
            b.text(lx, bottom + Math.round(14f * s), 10f * s, host.accent(), UiKit.fit(status, w - padX * 2f, 10f * s));
        } else {
            b.text(lx, bottom + Math.round(14f * s), 10f * s, host.muted2Color(),
                    "Friends are never targeted by combat modules. Enemies are always prioritised.");
        }
    }

    /**
     * "Friend key" and "Enemy key" bind buttons, right-aligned so they end at {@code right}. A click starts a
     * capture: the next key or mouse button (middle and side buttons included) becomes the bind, Escape
     * clears it, and a left click cancels.
     */
    private void drawBindButtons(UiDisplayList.Builder b, UiHost host, float right, float y, float h, float s,
                                 float mx, float my) {
        if (binding != BindTarget.NONE && !host.capturingKey()) binding = BindTarget.NONE;
        String friendLabel = "Friend key: " + (binding == BindTarget.FRIEND ? "press a key..." : backend.friendKeyName());
        String enemyLabel = "Enemy key: " + (binding == BindTarget.ENEMY ? "press a key..." : backend.enemyKeyName());
        float enemyW = UiKit.buttonWidth(enemyLabel, s);
        float friendW = UiKit.buttonWidth(friendLabel, s);
        float ex = right - enemyW;
        float fx = ex - Math.round(8f * s) - friendW;
        boolean free = binding == BindTarget.NONE && !host.capturingKey();
        UiKit.button(b, host, friendLabel, fx, y, friendW, h, s,
                binding == BindTarget.FRIEND ? Style.PRIMARY : Style.SECONDARY, free, mx, my, () -> {
                    binding = BindTarget.FRIEND;
                    say("Press a key or mouse button for the friend key (Esc clears, left click cancels).", host);
                    host.captureKey(code -> {
                        binding = BindTarget.NONE;
                        backend.setFriendKey(code);
                        say(code == -1 ? "Friend key cleared." : "Friend key set to " + backend.friendKeyName() + ".", host);
                    });
                });
        UiKit.button(b, host, enemyLabel, ex, y, enemyW, h, s,
                binding == BindTarget.ENEMY ? Style.PRIMARY : Style.SECONDARY, free, mx, my, () -> {
                    binding = BindTarget.ENEMY;
                    say("Press a key or mouse button for the enemy key (Esc clears, left click cancels).", host);
                    host.captureKey(code -> {
                        binding = BindTarget.NONE;
                        backend.setEnemyKey(code);
                        say(code == -1 ? "Enemy key cleared." : "Enemy key set to " + backend.enemyKeyName() + ".", host);
                    });
                });
    }

    private void drawList(UiDisplayList.Builder b, UiHost host, List<Person> people, boolean savedList, float x, float y,
                          float w, float h, float s, float mx, float my, UiSmoothScroll scroll, float[] rect, String empty) {
        rect[0] = x; rect[1] = y; rect[2] = w; rect[3] = h;
        float rowH = Math.round(48f * s), gap = Math.round(6f * s), pitch = rowH + gap;
        scroll.update(host.frameDelta(), h, Math.max(0f, people.size() * pitch - gap), host.now());
        float off = scroll.offset();
        if (people.isEmpty()) UiKit.emptyState(b, host, empty, "", x, y + Math.round(30f * s), w, s);
        b.clipPush(x, y, w, h, 0);
        host.beginHitClip(x, y, w, h);
        for (int i = 0; i < people.size(); i++) {
            float ry = y + i * pitch - off;
            if (ry + rowH < y || ry > y + h) continue;
            Person p = people.get(i);
            UiKit.card(b, host, x, ry, w, rowH, s);
            float pad = Math.round(16f * s);
            float btnH = Math.round(28f * s), by = ry + (rowH - btnH) * 0.5f;
            if (savedList) {
                float rmW = UiKit.buttonWidth("Remove", s);
                UiKit.button(b, host, "Remove", x + w - pad + Math.round(4f * s) - rmW, by, rmW, btnH, s, Style.DANGER, true, mx, my,
                        () -> say(backend.remove(p), host));
                float nameMax = w - pad * 2f - rmW - 70f * s;
                b.text(x + pad, GpuUiFontMetrics.topInBox(ry, rowH, 11.5f * s), 11.5f * s, host.textColor(), UiKit.fit(p.name(), nameMax, 11.5f * s),
                        UiDrawCommand.TextWeight.SEMIBOLD);
                float chipX = x + w - pad - rmW - Math.round(10f * s);
                if (p.enemy()) {
                    float cw = GpuUiFontMetrics.textWidth("Enemy", 8.5f * s) + 14f * s;
                    chipX -= cw;
                    UiKit.chip(b, host, "Enemy", chipX, ry + (rowH - Math.round(17f * s)) * 0.5f, s, UiKit.DANGER);
                    chipX -= Math.round(6f * s);
                }
                if (p.friend()) {
                    float cw = GpuUiFontMetrics.textWidth("Friend", 8.5f * s) + 14f * s;
                    chipX -= cw;
                    UiKit.chip(b, host, "Friend", chipX, ry + (rowH - Math.round(17f * s)) * 0.5f, s, host.accent());
                }
            } else {
                float enW = UiKit.buttonWidth("Not enemy", s), frW = UiKit.buttonWidth("Unfriend", s);
                float ex = x + w - pad + Math.round(4f * s) - enW;
                float fx = ex - Math.round(6f * s) - frW;
                UiKit.button(b, host, p.enemy() ? "Not enemy" : "Enemy", ex, by, enW, btnH, s,
                        p.enemy() ? Style.DANGER : Style.SECONDARY, true, mx, my, () -> say(backend.toggleEnemy(p), host));
                UiKit.button(b, host, p.friend() ? "Unfriend" : "Friend", fx, by, frW, btnH, s,
                        p.friend() ? Style.DANGER : Style.SECONDARY, true, mx, my, () -> say(backend.toggleFriend(p), host));
                b.text(x + pad, GpuUiFontMetrics.topInBox(ry, rowH, 11.5f * s), 11.5f * s, host.textColor(),
                        UiKit.fit(p.name(), fx - x - pad - 8f * s, 11.5f * s), UiDrawCommand.TextWeight.SEMIBOLD);
            }
        }
        host.endHitClip();
        b.clipPop();
        scroll.paintBar(b, x + w + Math.round(3f * s), y, h, s, host.textColor(), host.now());
    }
}
