package interstellarTikiTiks.apexx.client.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The player's own ClickGUI layout: module order per category, custom categories (folders), settings pinned to
 * Quick Settings and the last opened view. Lives inside {@link ClientPrefs} (the user's cloud blob, under
 * {@code "gui"}), so it follows the account across config swaps and never touches the disk.
 *
 * <p>Everything is keyed by names (module name, setting name), never by object identity, so a payload that has
 * not loaded yet keeps its entries instead of losing them. Mutators run {@code onChange} after releasing this
 * object's lock; {@link #rememberView} deliberately does not (the view rides along with the next save).
 */
public final class GuiPrefs {
    public static final int MAX_CUSTOM_CATEGORIES = 6;
    public static final int MAX_PINS = 32;
    public static final int MAX_NAME = 24;
    static final int MAX_ORDER = 256;
    static final int MAX_MEMBERS = 128;
    static final int MAX_ORDER_KEYS = 32;
    static final int MAX_ID = 16;

    /** A user folder: stable id, display name and its modules in display order. */
    public record CustomCategory(String id, String name, List<String> modules) { }

    /** A setting pinned to Quick Settings, by module and setting name. */
    public record Pin(String module, String setting) { }

    private static final class Folder {
        final String id;
        String name;
        final List<String> modules = new ArrayList<>();

        Folder(String id, String name) {
            this.id = id;
            this.name = name;
        }

        CustomCategory snapshot() {
            return new CustomCategory(id, name, List.copyOf(modules));
        }
    }

    private final Runnable onChange;
    private final Map<String, List<String>> orders = new LinkedHashMap<>();
    private final List<Folder> folders = new ArrayList<>();
    private final List<Pin> pins = new ArrayList<>();
    private String lastView = "";
    private String lastCategory = "";
    private float lastScroll;
    /** Bumped on every change (including a restore), so views can cache on it. */
    private int revision;

    public GuiPrefs(Runnable onChange) {
        this.onChange = onChange == null ? () -> { } : onChange;
    }

    public synchronized int revision() {
        return revision;
    }

    // ---------------------------------------------------------------- module order

    /** The saved order for {@code key} (a category name), or an empty list. */
    public synchronized List<String> order(String key) {
        List<String> list = key == null ? null : orders.get(key);
        return list == null ? List.of() : List.copyOf(list);
    }

    public void setOrder(String key, List<String> names) {
        if (key == null || key.isBlank()) return;
        List<String> clean = cleanNames(names, MAX_ORDER);
        synchronized (this) {
            if (clean.equals(orders.get(key))) return;
            if (clean.isEmpty()) {
                orders.remove(key);
            } else {
                if (!orders.containsKey(key) && orders.size() >= MAX_ORDER_KEYS) return;
                orders.put(key, clean);
            }
            revision++;
        }
        onChange.run();
    }

    /**
     * {@code items} reordered by {@code order}: items named in it come first, in that order; the rest follow in
     * their original relative order. Null names sort with the rest.
     */
    public static <T> List<T> applyOrder(List<T> items, Function<T, String> nameOf, List<String> order) {
        if (items == null || items.isEmpty()) return items == null ? List.of() : items;
        if (order == null || order.isEmpty()) return items;
        Map<String, Integer> rank = new HashMap<>();
        for (int i = 0; i < order.size(); i++) rank.putIfAbsent(order.get(i), i);
        List<T> ranked = new ArrayList<>();
        List<T> rest = new ArrayList<>();
        for (T item : items) {
            String name = item == null ? null : nameOf.apply(item);
            if (name != null && rank.containsKey(name)) ranked.add(item);
            else rest.add(item);
        }
        ranked.sort((a, b) -> Integer.compare(rank.get(nameOf.apply(a)), rank.get(nameOf.apply(b))));
        ranked.addAll(rest);
        return ranked;
    }

    /**
     * Moves {@code name} so it lands before the entry that was at {@code toIndex} (0..size; size = the end).
     * Returns a new list; an unknown name returns a copy unchanged.
     */
    public static List<String> move(List<String> names, String name, int toIndex) {
        List<String> out = new ArrayList<>(names);
        int from = out.indexOf(name);
        if (from < 0) return out;
        int to = Math.max(0, Math.min(out.size(), toIndex));
        if (to > from) to--;
        out.remove(from);
        out.add(to, name);
        return out;
    }

    /**
     * The new stored list after the visible entries were rearranged to {@code visibleOrder}: the visible ones in
     * their new order, then every stored name that was not visible (an unloaded module, a filtered-out one) in its
     * old relative order.
     */
    public static List<String> mergeVisible(List<String> stored, List<String> visibleOrder) {
        LinkedHashSet<String> out = new LinkedHashSet<>(visibleOrder);
        if (stored != null) out.addAll(stored);
        return new ArrayList<>(out);
    }

    // ---------------------------------------------------------------- custom categories

    public synchronized List<CustomCategory> customCategories() {
        List<CustomCategory> out = new ArrayList<>(folders.size());
        for (Folder f : folders) out.add(f.snapshot());
        return out;
    }

    public synchronized CustomCategory customCategory(String id) {
        Folder f = folder(id);
        return f == null ? null : f.snapshot();
    }

    public synchronized boolean canCreateCategory() {
        return folders.size() < MAX_CUSTOM_CATEGORIES;
    }

    /** Creates a folder; returns its id, or null when the limit is reached. */
    public String createCategory(String name) {
        String id;
        synchronized (this) {
            if (folders.size() >= MAX_CUSTOM_CATEGORIES) return null;
            int n = 1;
            while (folder("f" + n) != null) n++;
            id = "f" + n;
            folders.add(new Folder(id, cleanName(name, "Folder " + n)));
            revision++;
        }
        onChange.run();
        return id;
    }

    public boolean renameCategory(String id, String name) {
        synchronized (this) {
            Folder f = folder(id);
            if (f == null) return false;
            String clean = cleanName(name, f.name);
            if (clean.equals(f.name)) return true;
            f.name = clean;
            revision++;
        }
        onChange.run();
        return true;
    }

    public void deleteCategory(String id) {
        synchronized (this) {
            Folder f = folder(id);
            if (f == null) return;
            folders.remove(f);
            revision++;
        }
        onChange.run();
    }

    public synchronized boolean isMember(String id, String module) {
        Folder f = folder(id);
        return f != null && module != null && f.modules.contains(module);
    }

    /** Adds or removes {@code module}; returns whether it is a member afterwards. */
    public boolean toggleMember(String id, String module) {
        boolean member;
        synchronized (this) {
            Folder f = folder(id);
            if (f == null || module == null || module.isBlank()) return false;
            if (f.modules.remove(module)) {
                member = false;
            } else {
                if (f.modules.size() >= MAX_MEMBERS) return false;
                f.modules.add(module);
                member = true;
            }
            revision++;
        }
        onChange.run();
        return member;
    }

    public void setMembers(String id, List<String> modules) {
        List<String> clean = cleanNames(modules, MAX_MEMBERS);
        synchronized (this) {
            Folder f = folder(id);
            if (f == null || f.modules.equals(clean)) return;
            f.modules.clear();
            f.modules.addAll(clean);
            revision++;
        }
        onChange.run();
    }

    private Folder folder(String id) {
        if (id == null) return null;
        for (Folder f : folders) if (f.id.equals(id)) return f;
        return null;
    }

    // ---------------------------------------------------------------- pins

    public synchronized List<Pin> pins() {
        return List.copyOf(pins);
    }

    public synchronized boolean isPinned(String module, String setting) {
        return pins.contains(new Pin(module, setting));
    }

    public synchronized boolean canPin() {
        return pins.size() < MAX_PINS;
    }

    /** Pins or unpins; returns whether it is pinned afterwards (false when the limit stopped a new pin). */
    public boolean togglePin(String module, String setting) {
        if (module == null || setting == null || module.isBlank() || setting.isBlank()) return false;
        Pin pin = new Pin(module, setting);
        boolean pinned;
        synchronized (this) {
            if (pins.remove(pin)) {
                pinned = false;
            } else {
                if (pins.size() >= MAX_PINS) return false;
                pins.add(pin);
                pinned = true;
            }
            revision++;
        }
        onChange.run();
        return pinned;
    }

    // ---------------------------------------------------------------- last view

    public synchronized String lastView() {
        return lastView;
    }

    public synchronized String lastCategory() {
        return lastCategory;
    }

    public synchronized float lastScroll() {
        return lastScroll;
    }

    /**
     * Records where the menu was left. Not a change notification on purpose: it is written when the menu closes,
     * and the save that follows the close carries it to the cloud.
     */
    public synchronized void rememberView(String view, String category, float scroll) {
        lastView = view == null ? "" : clip(view, MAX_ID * 2);
        lastCategory = category == null ? "" : clip(category, MAX_ID * 2);
        lastScroll = Float.isFinite(scroll) ? Math.max(0f, Math.min(100_000f, scroll)) : 0f;
    }

    // ---------------------------------------------------------------- json

    public synchronized JsonObject toJson() {
        JsonObject root = new JsonObject();
        JsonObject order = new JsonObject();
        orders.forEach((key, list) -> order.add(key, array(list)));
        root.add("order", order);
        JsonArray fs = new JsonArray();
        for (Folder f : folders) {
            JsonObject o = new JsonObject();
            o.addProperty("id", f.id);
            o.addProperty("name", f.name);
            o.add("modules", array(f.modules));
            fs.add(o);
        }
        root.add("folders", fs);
        JsonArray ps = new JsonArray();
        for (Pin p : pins) {
            JsonObject o = new JsonObject();
            o.addProperty("m", p.module());
            o.addProperty("s", p.setting());
            ps.add(o);
        }
        root.add("pins", ps);
        root.addProperty("view", lastView);
        root.addProperty("category", lastCategory);
        root.addProperty("scroll", lastScroll);
        return root;
    }

    /** Restores from the blob. Malformed parts are skipped; a missing key keeps the live value. No change event. */
    public void applyJson(JsonElement element) {
        if (element == null || !element.isJsonObject()) return;
        JsonObject root = element.getAsJsonObject();
        synchronized (this) {
            JsonElement order = root.get("order");
            if (order != null && order.isJsonObject()) {
                orders.clear();
                for (Map.Entry<String, JsonElement> e : order.getAsJsonObject().entrySet()) {
                    if (orders.size() >= MAX_ORDER_KEYS) break;
                    List<String> list = cleanNames(strings(e.getValue()), MAX_ORDER);
                    if (!e.getKey().isBlank() && !list.isEmpty()) orders.put(clip(e.getKey(), 64), list);
                }
            }
            JsonElement fs = root.get("folders");
            if (fs != null && fs.isJsonArray()) {
                folders.clear();
                for (JsonElement fe : fs.getAsJsonArray()) {
                    if (folders.size() >= MAX_CUSTOM_CATEGORIES) break;
                    if (!fe.isJsonObject()) continue;
                    JsonObject o = fe.getAsJsonObject();
                    String id = string(o.get("id"));
                    if (id == null || id.isBlank() || folder(clip(id, MAX_ID)) != null) continue;
                    Folder f = new Folder(clip(id, MAX_ID), cleanName(string(o.get("name")), "Folder"));
                    f.modules.addAll(cleanNames(strings(o.get("modules")), MAX_MEMBERS));
                    folders.add(f);
                }
            }
            JsonElement ps = root.get("pins");
            if (ps != null && ps.isJsonArray()) {
                pins.clear();
                for (JsonElement pe : ps.getAsJsonArray()) {
                    if (pins.size() >= MAX_PINS) break;
                    if (!pe.isJsonObject()) continue;
                    String m = string(pe.getAsJsonObject().get("m"));
                    String st = string(pe.getAsJsonObject().get("s"));
                    if (m == null || st == null || m.isBlank() || st.isBlank()) continue;
                    Pin pin = new Pin(clip(m, 64), clip(st, 64));
                    if (!pins.contains(pin)) pins.add(pin);
                }
            }
            String view = string(root.get("view"));
            if (view != null) lastView = clip(view, MAX_ID * 2);
            String category = string(root.get("category"));
            if (category != null) lastCategory = clip(category, MAX_ID * 2);
            JsonElement scroll = root.get("scroll");
            if (scroll != null && scroll.isJsonPrimitive() && scroll.getAsJsonPrimitive().isNumber()) {
                float v = scroll.getAsFloat();
                lastScroll = Float.isFinite(v) ? Math.max(0f, Math.min(100_000f, v)) : 0f;
            }
            revision++;
        }
    }

    // ---------------------------------------------------------------- helpers

    static String cleanName(String name, String fallback) {
        String n = name == null ? "" : name.trim().replaceAll("\\s+", " ");
        n = n.codePoints().filter(cp -> !Character.isISOControl(cp))
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString();
        if (n.isEmpty()) n = fallback == null ? "Folder" : fallback;
        return clip(n, MAX_NAME);
    }

    private static List<String> cleanNames(List<String> names, int max) {
        if (names == null) return List.of();
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String name : names) {
            if (out.size() >= max) break;
            if (name != null && !name.isBlank()) out.add(clip(name, 64));
        }
        return new ArrayList<>(out);
    }

    private static List<String> strings(JsonElement e) {
        if (e == null || !e.isJsonArray()) return List.of();
        List<String> out = new ArrayList<>();
        for (JsonElement item : e.getAsJsonArray()) {
            String s = string(item);
            if (s != null) out.add(s);
        }
        return out;
    }

    private static JsonArray array(List<String> list) {
        JsonArray a = new JsonArray();
        for (String s : list) a.add(s);
        return a;
    }

    private static String string(JsonElement e) {
        try {
            return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    private static String clip(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }
}
