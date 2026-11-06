package interstellarTikiTiks.apexx.client.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import interstellarTikiTiks.apexx.client.ApexxAuth;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Cloud config sync (client side) against POST /api/config.
 *
 * Model: the cloud is the source of truth on sign-in (pull once, apply). The GUI compares
 * its opening and closing snapshots, then pushes at most once after it closes. The wire payload
 * is the SAME native config
 * blob the mod already writes to disk ({@code {theme,hudElements,modules:{...}}}), so no
 * translation is needed — the website's Cloud Config editor and this endpoint share it.
 *
 * Credential = the short-lived session token from /api/license ({@link ApexxAuth#getSessionToken()}).
 * Everything is off-thread and fail-soft: if the user isn't signed in, or the network is
 * down, sync silently no-ops and the local config keeps working.
 */
public final class CloudConfigSync {
    private static final Logger LOGGER = org.slf4j.helpers.NOPLogger.NOP_LOGGER;
    private static final String ENDPOINT = "https://example.invalid/api/config";
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8)).build();
    private static final ExecutorService EXEC =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "apexx-cloud-config");
                t.setDaemon(true);
                return t;
            });

    private static final Object STATE_LOCK = new Object();
    private static long stateEpoch;
    private record RequestContext(String token, long epoch) {}

    private static RequestContext captureContext() {
        synchronized (STATE_LOCK) { return new RequestContext(ApexxAuth.getSessionToken(), stateEpoch); }
    }

    /**
     * The session token is re-minted by the auth heartbeat every few minutes, so a request that started under one token
     * and finishes under the next is still the SAME sign-in. Comparing token strings made every save that overlapped a
     * heartbeat look "stale": the server had already stored it, but the client threw the confirmation away, never
     * recorded it as synced, and left the status stuck on PENDING. Identity is the account (uid inside the token)
     * plus the sign-in epoch that reset() bumps on sign-out.
     */
    private static boolean isCurrent(RequestContext context) {
        synchronized (STATE_LOCK) {
            if (context.token == null || context.epoch != stateEpoch) return false;
            String now = ApexxAuth.getSessionToken();
            return now != null && java.util.Objects.equals(uidOf(context.token), uidOf(now));
        }
    }

    /** The account id inside a session token ({@code base64url(json{uid,...}).sig}), or null if unreadable. */
    private static String uidOf(String token) {
        try {
            int dot = token.indexOf('.');
            if (dot <= 0) return null;
            byte[] raw = java.util.Base64.getUrlDecoder().decode(token.substring(0, dot));
            JsonObject payload = JsonParser.parseString(new String(raw, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            return payload.has("uid") && !payload.get("uid").isJsonNull() ? payload.get("uid").getAsString() : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void markStatusIfCurrent(RequestContext context, Status next, String error) {
        synchronized (STATE_LOCK) { if (isCurrent(context)) markStatus(next, error); }
    }

    // WORKING COPY. Every automatic push (GUI/HUD editor close, macro/script/pref changes, shutdown) goes to the
    // hidden "__autosave__" slot, never into the named config the user loaded: a named config only changes on an
    // explicit Overwrite or Save. The working blob carries a small marker naming the config it started from and
    // whether it differs from it, so a restart restores the working state and the unsaved-changes indicator.
    public static final String WORKING_MARKER = "apexxWorking";
    private static volatile String workingSlotId = null;
    /** Id of the named config that is loaded/active (the one Overwrite targets), or null. */
    private static volatile String activeConfigId = null;
    private static volatile boolean applying = false;      // suppress push while applying a pulled config
    private static volatile boolean pulledThisSession = false;
    private static volatile String lastSyncedJson = null;  // dedup: don't re-push what we just pulled/pushed

    // ── Observable status ───────────────────────────────────────────────────────
    //
    // Logging here is a NOP logger by design (the client ships with no diagnostics), so a failed
    // push left absolutely no trace: post() swallows every throwable and returns null, pushDirect
    // catches everything, and the settings panel displayed a HARD-CODED green "Synchronized"
    // regardless. There was therefore no way, anywhere, to tell whether a config had ever reached
    // the cloud. These fields are the honest answer, read by the GUI.

    public enum Status { SIGNED_OUT, IDLE, PENDING, SYNCED, FAILED }

    private static volatile Status status = Status.SIGNED_OUT;
    private static volatile long lastSyncedAtMs = 0L;
    private static volatile String lastError = null;
    private static volatile Future<?> lastSaveTask;

    // A failed push (offline, 5xx, expired token between heartbeats, config deleted on the website) is retried
    // with the NEWEST pending snapshot instead of being dropped until the user happens to close the GUI again.
    private static final int MAX_SAVE_RETRIES = 6;
    private static final java.util.concurrent.ScheduledExecutorService RETRY =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "apexx-cloud-config-retry");
                t.setDaemon(true);
                return t;
            });
    private static volatile String pendingJson = null;
    private static volatile int saveRetries = 0;
    private static volatile java.util.concurrent.ScheduledFuture<?> retryTask;

    private static boolean retryable(String reason) {
        return !("BAD_REQUEST".equals(reason) || "TOO_LARGE".equals(reason) || "FORBIDDEN".equals(reason));
    }

    private static void scheduleRetry(long epoch) {
        synchronized (STATE_LOCK) {
            if (epoch != stateEpoch || pendingJson == null || saveRetries >= MAX_SAVE_RETRIES) return;
            if (retryTask != null) retryTask.cancel(false);
            long delay = Math.min(60L, 5L << saveRetries++); // 5s, 10s, 20s, 40s, 60s, 60s
            retryTask = RETRY.schedule(() -> {
                String json = pendingJson;
                if (json == null) return;
                RequestContext fresh = captureContext();
                if (fresh.token == null) { scheduleRetry(epoch); return; } // token expired while offline: wait for the heartbeat
                synchronized (STATE_LOCK) {
                    if (fresh.epoch != epoch || !isCurrent(fresh)) return;
                    markStatus(Status.PENDING, null);
                    lastSaveTask = EXEC.submit(() -> pushDirect(json, fresh));
                }
            }, delay, TimeUnit.SECONDS);
        }
    }

    /** Current sync state — what the settings panel should actually display. */
    public static Status getStatus() {
        return ApexxAuth.getSessionToken() == null ? Status.SIGNED_OUT : status;
    }

    /** Epoch millis of the last confirmed successful push, or 0 if nothing has ever synced. */
    public static long getLastSyncedAtMs() { return lastSyncedAtMs; }

    /** Short reason for the last failure, or null. */
    public static String getLastError() { return lastError; }

    private static void markStatus(Status s, String err) {
        status = s;
        lastError = err;
        if (s == Status.SYNCED) lastSyncedAtMs = System.currentTimeMillis();
    }

    private CloudConfigSync() {}

    /** True while a pulled cloud config is being applied — used to gate the resulting local save. */
    public static boolean isApplying() { return applying; }
    public static String getActiveConfigId() { return activeConfigId; }
    static void setApplying(boolean v) { applying = v; }

    /** Reset per-session state (called on sign-out). Next sign-in pulls fresh. */
    public static void reset() {
        synchronized (STATE_LOCK) {
            stateEpoch++;
            activeConfigId = null;
            workingSlotId = null;
            pulledThisSession = false;
            lastSyncedJson = null;
            lastSyncedAtMs = 0L;
            pendingJson = null;
            saveRetries = 0;
            if (retryTask != null) retryTask.cancel(false);
            retryTask = null;
            markStatus(Status.SIGNED_OUT, null);
            if (lastSaveTask != null) lastSaveTask.cancel(false);
            lastSaveTask = null;
        }
    }

    /**
     * Adopt a config explicitly selected through the named-config API. It becomes the Overwrite target; the
     * working slot keeps receiving the automatic saves, so {@code alreadySavedJson} is not a dedup baseline for it.
     */
    public static void adoptActiveConfig(String id, String alreadySavedJson) {
        if (id == null || id.isBlank()) return;
        synchronized (STATE_LOCK) {
            stateEpoch++;
            activeConfigId = id;
            lastSyncedJson = null; // the working slot still holds the previous profile's state; resync it on the next push
            pendingJson = null; // a queued save belongs to the previous profile; never replay it into this one
            saveRetries = 0;
            if (retryTask != null) retryTask.cancel(false);
        }
        // Point the working copy at the newly active config right away, so a restart restores this state and not
        // the previous profile's (the push that would have done it was just invalidated by the epoch bump).
        ConfigManager.queueOnClientThread(() -> {
            if (applying || ApexxAuth.getSessionToken() == null) return;
            String json = ConfigManager.getInstance().exportCurrentConfigJsonForCloud();
            if (json != null) saveIfChangedNow(json);
        });
    }

    /** {@code blobJson} with the working-copy marker (loaded config name/id + dirty flag) added. */
    static String withWorkingMarker(String blobJson) {
        try {
            JsonObject marker = ConfigManager.getInstance().workingMarker(activeConfigId);
            if (marker == null) return blobJson;
            JsonElement parsed = JsonParser.parseString(blobJson);
            if (!parsed.isJsonObject()) return blobJson;
            JsonObject root = parsed.getAsJsonObject();
            root.add(WORKING_MARKER, marker);
            return root.toString();
        } catch (Throwable t) {
            return blobJson;
        }
    }

    /** The marker object in a working blob, or null. */
    static JsonObject markerOf(JsonObject blob) {
        if (blob == null) return null;
        JsonElement m = blob.get(WORKING_MARKER);
        return m != null && m.isJsonObject() ? m.getAsJsonObject() : null;
    }

    private static String markerString(JsonObject marker, String key) {
        try {
            return marker != null && marker.has(key) && !marker.get(key).isJsonNull() ? marker.get(key).getAsString() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Compare the closed GUI snapshot with the last confirmed cloud snapshot, then push once (to the working slot). */
    public static void saveIfChangedNow(String blobJson) {
        if (applying || blobJson == null || blobJson.isBlank()) return;
        ConfigManager.getInstance().noticeUnsavedChangesOnce();
        blobJson = withWorkingMarker(blobJson);
        if (sameConfig(lastSyncedJson, blobJson)) {
            pendingJson = null;
            markStatus(ApexxAuth.getSessionToken() == null ? Status.SIGNED_OUT : Status.IDLE, null);
            return;
        }
        RequestContext context = captureContext();
        if (context.token == null) {
            // Expired between heartbeats (offline) or signed out. Keep the change; the retry sends it once a token exists.
            pendingJson = blobJson;
            markStatus(Status.SIGNED_OUT, "not signed in");
            scheduleRetry(context.epoch);
            return;
        }
        final String marked = blobJson;
        synchronized (STATE_LOCK) {
            if (!isCurrent(context)) return;
            pendingJson = marked;
            saveRetries = 0;
            markStatus(Status.PENDING, null);
            lastSaveTask = EXEC.submit(() -> pushDirect(marked, context));
        }
    }

    /** Let a save queued by GUI removal finish if Minecraft exits immediately afterward. */
    public static void awaitPendingSave() {
        Future<?> save = lastSaveTask;
        if (save != null && !save.isDone()) {
            try {
                save.get(40, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Throwable t) {
                markStatus(Status.FAILED, t.getClass().getSimpleName());
            }
        }
        // A failed push waiting on its retry timer would die with the JVM. Flush it once, right now.
        String queued = pendingJson;
        if (queued != null && !Thread.currentThread().isInterrupted() && ApexxAuth.getSessionToken() != null) {
            if (retryTask != null) retryTask.cancel(false);
            pushDirect(queued, captureContext());
        }
    }

    public static void pushDirect(String json) {
        pushDirect(json, captureContext());
    }

    private static void pushDirect(String json, RequestContext context) {
        if (!ConfigManager.isValidConfigJson(json) || applying || !isCurrent(context)) return;
        try {
            if (sameConfig(lastSyncedJson, json)) return;
            String id = ensureWorkingId(context);
            if (!isCurrent(context)) return;
            if (id == null) {
                failPush(context, "no cloud config slot", true);
                return;
            }
            JsonObject req = new JsonObject();
            req.addProperty("token", context.token); // post() swaps in the newest token for each attempt
            req.addProperty("action", "save");
            req.addProperty("id", id);
            req.add("data", JsonParser.parseString(json)); // embed the blob as a JSON object, not a string
            JsonObject resp = post(req);
            synchronized (STATE_LOCK) {
                if (!isCurrent(context)) return;
                if (ok(resp)) {
                    lastSyncedJson = json;
                    if (sameConfig(pendingJson, json)) pendingJson = null; // a newer snapshot stays queued
                    saveRetries = 0;
                    markStatus(Status.SYNCED, null);
                } else {
                    String reason = resp == null ? "no response" : reasonOf(resp);
                    workingSlotId = null; // re-resolve the slot next time (it may have been deleted on the website)
                    failPush(context, reason, resp == null || retryable(reason));
                }
            }
        } catch (Throwable t) {
            failPush(context, t.getClass().getSimpleName(), true);
        }
    }

    private static void failPush(RequestContext context, String reason, boolean retry) {
        synchronized (STATE_LOCK) {
            if (!isCurrent(context)) return;
            markStatus(Status.FAILED, reason);
            if (retry) scheduleRetry(context.epoch);
            else pendingJson = null; // the server will never accept this payload as-is; do not loop on it
        }
    }

    private static String reasonOf(JsonObject o) {
        try {
            if (o.has("reason") && !o.get("reason").isJsonNull()) return o.get("reason").getAsString();
        } catch (Throwable ignored) {
        }
        return "rejected";
    }

    private static boolean sameConfig(String first, String second) {
        if (first == null || second == null) return first == second;
        try {
            return JsonParser.parseString(first).equals(JsonParser.parseString(second));
        } catch (Throwable ignored) {
            return first.equals(second);
        }
    }

    /** Resolve (and cache) the hidden working slot's id, creating the slot if the user has none yet. */
    private static String ensureWorkingId(RequestContext context) {
        String token = context.token;
        synchronized (STATE_LOCK) {
            if (!isCurrent(context)) return null;
            if (workingSlotId != null) return workingSlotId;
        }
        String found = findWorkingId(token);
        if (found == null) {
            if (!isCurrent(context)) return null;
            JsonObject create = new JsonObject();
            create.addProperty("token", token);
            create.addProperty("action", "create");
            create.addProperty("name", CloudConfigs.WORKING_SLOT);
            JsonObject c = post(create);
            if (ok(c) && c.has("id") && !c.get("id").isJsonNull()) found = c.get("id").getAsString();
        }
        if (found == null) return null;
        synchronized (STATE_LOCK) {
            if (!isCurrent(context)) return null;
            workingSlotId = found;
            return found;
        }
    }

    /** The working slot's id from the caller's config list, or null. */
    private static String findWorkingId(String token) {
        JsonObject list = new JsonObject();
        list.addProperty("token", token);
        list.addProperty("action", "list");
        JsonObject r = post(list);
        if (!ok(r) || !r.has("configs") || !r.get("configs").isJsonArray()) return null;
        for (JsonElement el : r.getAsJsonArray("configs")) {
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            if (CloudConfigs.isWorkingSlot(markerString(o, "name")) && o.has("id") && !o.get("id").isJsonNull()) {
                return o.get("id").getAsString();
            }
        }
        return null;
    }

    /** A blob the loader accepts that carries real content (modules, or a theme/HUD/legacy-key config). */
    private static boolean hasRealConfig(JsonObject d) {
        if (d == null) return false;
        boolean modulesPopulated = d.has("modules") && d.get("modules").isJsonObject()
                && d.getAsJsonObject("modules").size() > 0;
        boolean otherContent = d.has("theme") || d.has("hudElements")
                || d.has("IIllIlIl30") || d.has("IllIIllI30"); // legacy obfuscated root keys
        return (modulesPopulated || otherContent) && ConfigManager.isValidConfigJson(d.toString());
    }

    /**
     * The active named config to apply when the working copy does not belong to it, with this account's private
     * keys (macros, scripts, per-user prefs) taken from the working copy so switching on the website loses none.
     */
    static JsonObject mergePrivateKeys(JsonObject named, JsonObject working) {
        JsonObject out = named.deepCopy();
        if (working == null) return out;
        for (String key : new String[]{UserContentBlob.MACROS, UserContentBlob.SCRIPTS, ClientPrefs.KEY}) {
            JsonElement value = working.get(key);
            if (value != null && !value.isJsonNull()) out.add(key, value.deepCopy());
        }
        return out;
    }

    /**
     * Sync with the cloud on sign-in/launch:
     * Pull the active cloud profile at launch unless the user has since loaded or saved another
     * profile. An empty cloud slot stays read-only until the user changes settings and closes the GUI.
     */
    public static void pullActiveAsync() {
        RequestContext context = captureContext();
        synchronized (STATE_LOCK) {
            if (pulledThisSession || !isCurrent(context)) return;
            pulledThisSession = true;
        }
        long loadGeneration = ConfigManager.getInstance().getConfigLoadGeneration();
        EXEC.submit(() -> {
            try {
                if (!isCurrent(context)) return;
                String token = context.token;

                // CLOUD IS AUTHORITATIVE ON LAUNCH. Configs are cloud-only: there is no on-disk config,
                // so the in-memory state at client-init is just DEFAULTS, never a user's saved config.
                // The old code treated that default state as "local authority" and pushed it over the
                // cloud before ever pulling — which is exactly why saved cloud configs never loaded.
                // Now: pull the active cloud config and apply it only if no newer config action has
                // superseded this launch request.
                JsonObject get = new JsonObject();
                get.addProperty("token", token);
                get.addProperty("action", "get");
                JsonObject r = post(get);
                synchronized (STATE_LOCK) {
                    if (!isCurrent(context)) return;
                    if (!ok(r)) { pulledThisSession = false; return; }
                }
                String responseConfigId = r.has("id") && !r.get("id").isJsonNull()
                        ? r.get("id").getAsString() : null;
                String responseName = markerString(r, "name");
                JsonObject activeData = r.has("data") && r.get("data").isJsonObject() ? r.getAsJsonObject("data") : null;

                // The working copy: an older server may report it as the active config; otherwise look it up.
                JsonObject workingData = null;
                String workingId = null;
                if (CloudConfigs.isWorkingSlot(responseName)) {
                    workingId = responseConfigId;
                    workingData = activeData;
                    responseConfigId = null;
                    responseName = null;
                    activeData = null;
                } else {
                    workingId = findWorkingId(token);
                    if (workingId != null && isCurrent(context)) {
                        JsonObject getWorking = new JsonObject();
                        getWorking.addProperty("token", token);
                        getWorking.addProperty("action", "get");
                        getWorking.addProperty("id", workingId);
                        JsonObject w = post(getWorking);
                        if (ok(w) && w.has("data") && w.get("data").isJsonObject()) workingData = w.getAsJsonObject("data");
                    }
                }
                if (!isCurrent(context)) return;
                if (workingId != null) {
                    synchronized (STATE_LOCK) { if (isCurrent(context)) workingSlotId = workingId; }
                }

                // Restore the working copy when it belongs to the config that is active now (or there is none);
                // a different active config (switched on the website) wins, keeping this account's private keys.
                JsonObject marker = markerOf(workingData);
                String markerId = markerString(marker, "id");
                boolean useWorking = hasRealConfig(workingData)
                        && (responseConfigId == null || responseConfigId.equals(markerId));
                final String namedId = useWorking ? (markerId != null ? markerId : responseConfigId) : responseConfigId;
                final String namedName = useWorking ? markerString(marker, "from") : responseName;
                final boolean restoredDirty = useWorking && marker != null && marker.has("dirty")
                        && marker.get("dirty").isJsonPrimitive() && marker.get("dirty").getAsBoolean();

                // A real saved config is one the loader itself considers valid AND that carries actual
                // content — populated modules, OR a theme/HUD/legacy-key blob. The earlier check only
                // accepted a populated top-level "modules" object, so a theme/HUD-only or legacy-key
                // config read as "empty" and got clobbered with defaults (data loss). An empty config
                // remains read-only until the user closes the GUI after making a change.
                String blobStr = null;
                boolean cloudHasConfig = false;
                String syncedSnapshot = null;
                if (useWorking) {
                    syncedSnapshot = workingData.toString(); // marker included: an unchanged state is not pushed again
                    JsonObject clean = workingData.deepCopy();
                    clean.remove(WORKING_MARKER);
                    blobStr = clean.toString();
                    cloudHasConfig = true;
                } else if (activeData != null) {
                    blobStr = mergePrivateKeys(activeData, workingData).toString();
                    cloudHasConfig = hasRealConfig(activeData);
                }
                final String resolvedBlob = blobStr;
                final String resolvedSnapshot = syncedSnapshot;

                if (cloudHasConfig) {
                    ConfigManager.getInstance().applyCloudBlob(resolvedBlob, loadGeneration, applied -> {
                        synchronized (STATE_LOCK) {
                            if (!applied || !isCurrent(context)) return;
                            activeConfigId = namedId;
                            lastSyncedJson = resolvedSnapshot; // null: resync the working slot on the next push
                            pendingJson = null;
                        }
                        ConfigManager.getInstance().restoreLoadedConfig(namedName, restoredDirty);
                    }, () -> isCurrent(context));
                } else {
                    // Keep launch read-only. The first save creates the cloud slot when the user
                    // closes the GUI after making changes. Do not let a stale launch response alter
                    // the active profile id or dedup snapshot after a newer config action.
                    final String workingSnapshot = workingData == null ? null : workingData.toString();
                    final String prefsSource = resolvedBlob != null ? resolvedBlob : workingSnapshot;
                    ConfigManager.getInstance().runIfConfigLoadGenerationCurrent(loadGeneration, () -> {
                        synchronized (STATE_LOCK) {
                            if (!isCurrent(context)) return;
                            activeConfigId = namedId;
                            lastSyncedJson = workingSnapshot;
                        }
                        // No config to restore: the live per-user prefs (or whatever the slot holds) are authoritative now.
                        com.google.gson.JsonElement prefs = null;
                        try {
                            if (prefsSource != null) {
                                com.google.gson.JsonElement parsed = JsonParser.parseString(prefsSource);
                                if (parsed.isJsonObject()) prefs = parsed.getAsJsonObject().get(ClientPrefs.KEY);
                            }
                        } catch (RuntimeException malformed) {
                            prefs = null;
                        }
                        ClientPrefs.get().applyFromBlob(prefs);
                    });
                }
            } catch (Throwable t) {
                synchronized (STATE_LOCK) { if (isCurrent(context)) pulledThisSession = false; }
                LOGGER.debug("[cloud-config] sync failed: {}", t.getMessage());
            }
        });
    }

    private static boolean ok(JsonObject o) {
        return o != null && o.has("ok") && !o.get("ok").isJsonNull() && o.get("ok").getAsBoolean();
    }

    private static JsonObject post(JsonObject body) {
        String action = body.has("action") ? body.get("action").getAsString() : "?";
        // `create` is the only action here a replay could duplicate; everything else is safe to resend.
        int attempts = "create".equals(action) ? 1 : 3;
        JsonObject last = null;
        for (int attempt = 0; attempt < attempts; attempt++) {
            if (attempt > 0) {
                try { Thread.sleep(800L << (attempt - 1)); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return last; }
            }
            // The heartbeat may have rotated the token since this request was built (or since the last attempt).
            String fresh = ApexxAuth.getSessionToken();
            if (fresh != null) body.addProperty("token", fresh);
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(ENDPOINT))
                        .timeout(Duration.ofSeconds(12))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                        .build();
                HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
                int status = resp.statusCode();
                String b = resp.body() == null ? "" : resp.body();
                JsonObject parsed = null;
                try {
                    JsonElement el = JsonParser.parseString(b);
                    if (el.isJsonObject()) parsed = el.getAsJsonObject();
                } catch (Throwable ignored) {
                }
                if (status / 100 == 2) return parsed;
                // Keep the server's {ok:false, reason} so callers can tell BAD_REQUEST (do not retry) from
                // SERVER_ERROR / INVALID_TOKEN (retry) instead of seeing every failure as "no response".
                last = parsed;
                boolean transientFailure = status >= 500 || status == 429
                        || (status == 401 && parsed != null && "INVALID_TOKEN".equals(reasonOf(parsed)));
                if (!transientFailure) return parsed;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return last;
            } catch (Throwable t) {
                last = null;
            }
        }
        return last;
    }

}
