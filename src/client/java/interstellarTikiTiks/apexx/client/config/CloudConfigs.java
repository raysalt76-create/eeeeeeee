package interstellarTikiTiks.apexx.client.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import interstellarTikiTiks.apexx.client.ApexxAuth;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Client for the named-config half of {@code POST /api/config}.
 *
 * <h2>Why this class exists</h2>
 * The Configs menu has always offered "Download config by ID", "Browse public configs", "Import
 * from files" and a "Public config" checkbox — and none of them did anything. The retired menu bridge had
 * <em>zero</em> handlers for {@code downloadConfig}, {@code getPublicConfigs} and
 * {@code uploadConfigFile}, and its config list hard-coded every share id to the empty string with
 * the comment "No cloud in this reduced bridge". The buttons were wired to nothing, so clicking
 * them was silently a no-op, which is what made cloud configs feel broken.
 *
 * <p>The server side was never the problem: {@code api/config.ts} is Firestore-backed and already
 * implements {@code list}, {@code listPublic}, {@code getPublic}, {@code importPublic},
 * {@code setPublic}, {@code create}, {@code save}, {@code delete} and {@code setActive}. This class
 * is the missing client for those actions.
 *
 * <h2>Relationship to {@link CloudConfigSync}</h2>
 * {@link CloudConfigSync} pulls the active profile at client launch and, after the GUI closes,
 * sends one update only when its opening and closing snapshots differ. This class handles the
 * explicit, user-driven operations on <em>named</em> configs. They share the endpoint and session
 * token but nothing else.
 *
 * <p>Every call is blocking and returns null / empty on failure — callers run it off-thread and
 * report the outcome through the GUI rather than a log, since the client ships without diagnostics.
 */
public final class CloudConfigs {

    private static final String ENDPOINT = "https://example.invalid/api/config";

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8)).build();

    private CloudConfigs() {
    }

    /** One config as the cloud sees it. */
    public record Entry(String id, String name, String ownerName, String gameMode, String description, boolean isPublic,
                        boolean active, List<String> tags, double ratingAvg, int ratingCount, int weeklyScore,
                        int myRating) {
        public Entry(String id, String name, String ownerName, String gameMode, String description, boolean isPublic,
                     boolean active, List<String> tags) {
            this(id, name, ownerName, gameMode, description, isPublic, active, tags, 0d, 0, 0, 0);
        }
    }

    /** Community hub order for {@link #listPublic(int, List, String, String)}. */
    public static final String SORT_POPULAR = "popular", SORT_TOP = "top", SORT_NEWEST = "newest";

    /** Name of the hidden cloud slot that holds the live working state (never shown as a config). */
    public static final String WORKING_SLOT = "__autosave__";

    /** True for the hidden working slot's name. */
    public static boolean isWorkingSlot(String name) {
        return WORKING_SLOT.equals(name);
    }

    // The exact cause of the most recent failed call, so a "cloud upload failed" message can name
    // WHY (INVALID_TOKEN vs BAD_REQUEST vs a network error) instead of being a dead end.
    private static volatile String lastError = null;

    /** Human-readable reason for the last failed call, or null if the last call succeeded. */
    public static String lastError() {
        return lastError;
    }

    public static boolean signedIn() {
        return ApexxAuth.getSessionToken() != null;
    }

    // ── The caller's own configs ────────────────────────────────────────────────

    /** The caller's configs. Empty when signed out or unreachable. */
    public static List<Entry> list() {
        JsonObject r = call("list", null);
        List<Entry> out = new ArrayList<>();
        if (!ok(r) || !r.has("configs") || !r.get("configs").isJsonArray()) return out;
        for (JsonElement el : r.getAsJsonArray("configs")) {
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            out.add(new Entry(str(o, "id"), str(o, "name"), str(o, "ownerName"), str(o, "gameMode"), str(o, "description"),
                    bool(o, "isPublic"), bool(o, "active"), strArray(o, "tags")));
        }
        return out;
    }

    /** Resolve a config id by name, or null. */
    public static String idForName(String name) {
        if (name == null || name.isBlank()) return null;
        for (Entry e : list()) {
            if (name.equalsIgnoreCase(e.name())) return e.id();
        }
        return null;
    }

    /**
     * Upload {@code blobJson} under {@code name}, creating the cloud config if it does not exist
     * yet, and set its visibility.
     *
     * @return the config id on success, else null
     */
    public static String saveNamed(String name, String blobJson, boolean makePublic) {
        return saveNamed(name, blobJson, makePublic, null, null, null);
    }

    public static String saveNamed(String name, String blobJson, boolean makePublic, String gameMode, String description) {
        return saveNamed(name, blobJson, makePublic, gameMode, description, null);
    }

    /**
     * Save a named config. {@code gameMode}/{@code description} are hub metadata the website owns: pass null to leave
     * whatever is stored untouched (the old code always sent "Other"/"" and wiped them on every in-game save).
     * {@code makePublic} only ever turns sharing ON here; an in-game save must never silently unshare a public
     * config, so unsharing goes through {@link #setPublic}.
     */
    public static String saveNamed(String name, String blobJson, boolean makePublic, String gameMode, String description,
            List<String> tags) {
        if (name == null || name.isBlank() || blobJson == null || blobJson.isBlank()) return null;
        JsonElement blob;
        try {
            blob = JsonParser.parseString(blobJson);
        } catch (Throwable t) {
            lastError = "config is not valid JSON";
            return null;
        }
        String id = idForName(name);
        if (id == null) {
            if (lastError != null) return null; // the list call itself failed: do not guess and create a duplicate
            JsonObject create = new JsonObject();
            create.addProperty("name", name);
            create.addProperty("isPublic", makePublic);
            create.addProperty("gameMode", gameMode == null || gameMode.isBlank() ? "Other" : gameMode);
            create.addProperty("description", description == null ? "" : description);
            if (tags != null) create.add("tags", tagsArray(tags));
            JsonObject c = call("create", create);
            if (!ok(c)) return null;
            id = str(c, "id");
            if (id.isEmpty()) return null;
        }
        JsonObject save = new JsonObject();
        save.addProperty("id", id);
        save.addProperty("name", name);
        if (gameMode != null && !gameMode.isBlank()) save.addProperty("gameMode", gameMode);
        if (description != null) save.addProperty("description", description);
        if (makePublic) save.addProperty("isPublic", true);
        if (tags != null) save.add("tags", tagsArray(tags));
        save.add("data", blob);
        return ok(call("save", save)) ? id : null;
    }

    /**
     * Rate a public config 1-5 stars (0 removes the caller's rating). Returns {@code {avg, count}} after the
     * change, or null on failure ({@link #lastError()} names why, e.g. OWN_CONFIG or RATE_LIMITED).
     */
    public static double[] rate(String id, int stars) {
        if (id == null || id.isBlank() || stars < 0 || stars > 5) return null;
        JsonObject b = new JsonObject();
        b.addProperty("id", id);
        b.addProperty("stars", stars);
        JsonObject r = call("rate", b);
        if (!ok(r)) return null;
        return new double[]{num(r, "ratingAvg"), num(r, "ratingCount")};
    }

    /** Replace a config's filter tags by id. The worker enforces ownership via the auth token. */
    public static boolean setTagsById(String id, List<String> tags) {
        if (id == null || id.isBlank()) return false;
        JsonObject b = new JsonObject();
        b.addProperty("id", id);
        b.add("tags", tagsArray(tags));
        return ok(call("setTags", b));
    }

    /** Rename a config by id. The worker enforces ownership via the auth token. */
    public static boolean renameById(String id, String newName) {
        if (id == null || id.isBlank() || newName == null || newName.isBlank()) return false;
        JsonObject b = new JsonObject();
        b.addProperty("id", id);
        b.addProperty("name", newName);
        return ok(call("rename", b));
    }

    /** Fetch one of the caller's OWN configs by id. Returns {@code [name, blobJson]}, or null. */
    public static String[] getOwn(String id) {
        if (id == null || id.isBlank()) return null;
        JsonObject b = new JsonObject();
        b.addProperty("id", id);
        JsonObject r = call("get", b);
        if (!ok(r) || !r.has("data") || !r.get("data").isJsonObject()) return null;
        String name = str(r, "name");
        return new String[]{name.isEmpty() ? "Config" : name, r.getAsJsonObject("data").toString()};
    }

    /** Fetch one of the caller's own configs by name. Returns {@code [name, blobJson]}, or null. */
    public static String[] getOwnByName(String name) {
        String id = idForName(name);
        return id == null ? null : getOwn(id);
    }

    /** Delete a cloud config by name. Missing or already-gone counts as success. */
    public static boolean deleteNamed(String name) {
        String id = idForName(name);
        if (id == null) return true;
        JsonObject b = new JsonObject();
        b.addProperty("id", id);
        return ok(call("delete", b));
    }

    /** Flip an existing config's visibility. */
    public static boolean setPublic(String name, boolean makePublic) {
        String id = idForName(name);
        if (id == null) return false;
        JsonObject b = new JsonObject();
        b.addProperty("id", id);
        b.addProperty("isPublic", makePublic);
        return ok(call("setPublic", b));
    }

    /** Delete a cloud config by its id. The worker enforces ownership via the auth token. */
    public static boolean deleteById(String id) {
        if (id == null || id.isBlank()) return false;
        JsonObject b = new JsonObject();
        b.addProperty("id", id);
        return ok(call("delete", b));
    }

    /** Flip a cloud config's visibility by its id. The worker enforces ownership via the auth token. */
    public static boolean setPublicById(String id, boolean makePublic) {
        if (id == null || id.isBlank()) return false;
        JsonObject b = new JsonObject();
        b.addProperty("id", id);
        b.addProperty("isPublic", makePublic);
        return ok(call("setPublic", b));
    }

    /** Select a named config as the one automatically synchronized by the client. */
    public static boolean setActiveByName(String name) {
        String id = idForName(name);
        if (id == null) return false;
        JsonObject b = new JsonObject();
        b.addProperty("id", id);
        if (!ok(call("setActive", b))) return false;
        CloudConfigSync.adoptActiveConfig(id, null);
        return true;
    }

    /** Set a config active after a named save and keep the sync cache aligned with it. */
    public static boolean setActive(String id, String configJson) {
        if (id == null || id.isBlank()) return false;
        JsonObject b = new JsonObject();
        b.addProperty("id", id);
        if (!ok(call("setActive", b))) return false;
        CloudConfigSync.adoptActiveConfig(id, configJson);
        return true;
    }

    // ── Public configs ──────────────────────────────────────────────────────────

    /** Browse public configs across all users, newest first. */
    public static List<Entry> listPublic(int limit) {
        return listPublic(limit, null);
    }

    /** Browse public configs, optionally only those carrying at least one of {@code filterTags}. */
    public static List<Entry> listPublic(int limit, List<String> filterTags) {
        return listPublic(limit, filterTags, null);
    }

    /**
     * Browse public configs, optionally filtered by tag and/or an uploader username search (matched
     * server-side, case-insensitively, against the whole pool of public configs rather than only
     * whatever page {@code limit} would otherwise cap the browse list to).
     */
    public static List<Entry> listPublic(int limit, List<String> filterTags, String username) {
        return listPublic(limit, filterTags, username, null);
    }

    /** As above, ordered by {@code sort} ({@link #SORT_POPULAR}, {@link #SORT_TOP}, {@link #SORT_NEWEST}; null = newest). */
    public static List<Entry> listPublic(int limit, List<String> filterTags, String username, String sort) {
        JsonObject b = new JsonObject();
        b.addProperty("limit", Math.max(1, Math.min(500, limit)));
        if (filterTags != null && !filterTags.isEmpty()) b.add("tags", tagsArray(filterTags));
        if (username != null && !username.isBlank()) b.addProperty("username", username.trim());
        if (SORT_POPULAR.equals(sort) || SORT_TOP.equals(sort) || SORT_NEWEST.equals(sort)) b.addProperty("sort", sort);
        JsonObject r = call("listPublic", b);
        List<Entry> out = new ArrayList<>();
        if (!ok(r) || !r.has("configs") || !r.get("configs").isJsonArray()) return out;
        for (JsonElement el : r.getAsJsonArray("configs")) {
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            out.add(new Entry(str(o, "id"), str(o, "name"), str(o, "ownerName"), str(o, "gameMode"), str(o, "description"),
                    true, false, strArray(o, "tags"), num(o, "ratingAvg"), (int) num(o, "ratingCount"),
                    (int) num(o, "weeklyScore"), (int) num(o, "myRating")));
        }
        return out;
    }

    /** One public config's full blob. Returns {@code [name, blobJson]}, or null. */
    public static String[] getPublic(String id) {
        if (id == null || id.isBlank()) return null;
        JsonObject b = new JsonObject();
        b.addProperty("id", id);
        JsonObject r = call("getPublic", b);
        if (!ok(r) || !r.has("data") || !r.get("data").isJsonObject()) return null;
        String name = str(r, "name");
        return new String[]{name.isEmpty() ? "Downloaded" : name, r.getAsJsonObject("data").toString()};
    }

    /** Clone a public config into the caller's own account. Returns its new id, or null. */
    public static String importPublic(String id, String asName) {
        if (id == null || id.isBlank()) return null;
        JsonObject b = new JsonObject();
        b.addProperty("id", id);
        if (asName != null && !asName.isBlank()) b.addProperty("name", asName);
        JsonObject r = call("importPublic", b);
        return ok(r) ? str(r, "id") : null;
    }

    // ── plumbing ────────────────────────────────────────────────────────────────

    private static JsonObject call(String action, JsonObject extra) {
        String token = ApexxAuth.getSessionToken();
        if (token == null) {
            lastError = "not signed in (no session token)";
            return null;
        }
        JsonObject body = new JsonObject();
        body.addProperty("token", token);
        body.addProperty("action", action);
        if (extra != null) {
            for (String k : extra.keySet()) body.add(k, extra.get(k));
        }
        JsonObject resp = post(body);
        if (resp == null) {
            // post() already set lastError to the transport/HTTP detail.
            return null;
        }
        if (!ok(resp)) {
            lastError = action + ": " + reasonOf(resp);
        } else {
            lastError = null;
        }
        return resp;
    }

    private static String reasonOf(JsonObject o) {
        try {
            if (o.has("reason") && !o.get("reason").isJsonNull()) return o.get("reason").getAsString();
        } catch (Throwable ignored) {
        }
        return "rejected";
    }

    private static boolean ok(JsonObject o) {
        try {
            return o != null && o.has("ok") && !o.get("ok").isJsonNull() && o.get("ok").getAsBoolean();
        } catch (Throwable t) {
            return false;
        }
    }

    private static String str(JsonObject o, String k) {
        try {
            return o != null && o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : "";
        } catch (Throwable t) {
            return "";
        }
    }

    private static double num(JsonObject o, String k) {
        try {
            if (o == null || !o.has(k) || o.get(k).isJsonNull()) return 0d;
            double v = o.get(k).getAsDouble();
            return Double.isFinite(v) && v > 0 ? v : 0d;
        } catch (Throwable t) {
            return 0d;
        }
    }

    private static List<String> strArray(JsonObject o, String k) {
        List<String> out = new ArrayList<>();
        try {
            if (o != null && o.has(k) && o.get(k).isJsonArray()) {
                for (JsonElement el : o.getAsJsonArray(k)) {
                    if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isString()) out.add(el.getAsString());
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static JsonArray tagsArray(List<String> tags) {
        JsonArray array = new JsonArray();
        for (String tag : ConfigTags.clean(tags)) array.add(tag);
        return array;
    }

    private static boolean bool(JsonObject o, String k) {
        try {
            return o != null && o.has(k) && !o.get(k).isJsonNull() && o.get(k).getAsBoolean();
        } catch (Throwable t) {
            return false;
        }
    }

    private static JsonObject post(JsonObject body) {
        String action = body.has("action") ? body.get("action").getAsString() : "?";
        // `create` and `importPublic` are the only calls here that a replay could duplicate.
        int attempts = ("create".equals(action) || "importPublic".equals(action)) ? 1 : 3;
        JsonObject last = null;
        for (int attempt = 0; attempt < attempts; attempt++) {
            if (attempt > 0) {
                try { Thread.sleep(800L << (attempt - 1)); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return last; }
            }
            String fresh = ApexxAuth.getSessionToken(); // the heartbeat may have rotated it since the body was built
            if (fresh != null) body.addProperty("token", fresh);
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(ENDPOINT))
                        .timeout(Duration.ofSeconds(15))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                        .build();
                HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
                int status = resp.statusCode();
                JsonObject parsed = null;
                try {
                    JsonElement el = JsonParser.parseString(resp.body());
                    if (el.isJsonObject()) parsed = el.getAsJsonObject();
                } catch (Throwable ignored) {
                }
                if (parsed == null) {
                    lastError = "HTTP " + status + " (non-JSON response)";
                    last = null;
                    if (status >= 500 || status == 429) continue;
                    return null;
                }
                last = parsed; // returned even on non-2xx so callers can read {ok:false, reason:...}
                boolean transientFailure = status >= 500 || status == 429
                        || (status == 401 && "INVALID_TOKEN".equals(reasonOf(parsed)));
                if (!transientFailure) return parsed;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                lastError = "interrupted";
                return last;
            } catch (java.net.http.HttpTimeoutException t) {
                lastError = "connection timed out";
                last = null;
            } catch (java.io.IOException t) {
                lastError = "network error (" + t.getClass().getSimpleName() + ")";
                last = null;
            } catch (Throwable t) {
                lastError = t.getClass().getSimpleName();
                last = null;
            }
        }
        return last;
    }
}
