package interstellarTikiTiks.apexx.client.util;

import interstellarTikiTiks.apexx.client.util.combat.TimingProfile;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-action gate that holds an action back by one {@link TimingProfile} delay the first time it
 * is requested. Delay length comes only from the global profile.
 */
public final class ModuleRandomDelay {
    private static final Map<String, Long> READY_AT_BY_ACTION = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> HELD_STATE = new ConcurrentHashMap<>();

    private ModuleRandomDelay() {
    }

    public static boolean gateAction(String actionKey) {
        String effectiveKey = normalizeBucketKey(actionKey);

        long now = System.currentTimeMillis();
        Long readyAt = READY_AT_BY_ACTION.get(effectiveKey);
        if (readyAt == null) {
            long sampledDelay = TimingProfile.delayMs();
            if (sampledDelay <= 0L) {
                return true;
            }
            READY_AT_BY_ACTION.put(effectiveKey, now + sampledDelay);
            return false;
        }

        if (now < readyAt) {
            return false;
        }

        READY_AT_BY_ACTION.remove(effectiveKey);
        return true;
    }

    public static boolean gatePress(String actionKey, boolean desiredPressed) {
        String effectiveKey = normalizeBucketKey(actionKey);

        if (!desiredPressed) {
            HELD_STATE.put(effectiveKey, false);
            READY_AT_BY_ACTION.remove(effectiveKey);
            return false;
        }

        if (HELD_STATE.getOrDefault(effectiveKey, false)) {
            return true;
        }

        if (!gateAction(effectiveKey)) {
            return false;
        }

        HELD_STATE.put(effectiveKey, true);
        return true;
    }

    public static void clearActionState(String actionKey) {
        if (actionKey == null || actionKey.isBlank()) {
            return;
        }
        String effectiveKey = normalizeBucketKey(actionKey);
        READY_AT_BY_ACTION.remove(effectiveKey);
        HELD_STATE.remove(effectiveKey);
        READY_AT_BY_ACTION.remove(actionKey);
        HELD_STATE.remove(actionKey);
    }

    private static String normalizeBucketKey(String actionKey) {
        if (actionKey == null || actionKey.isBlank()) {
            return "action.unknown";
        }

        String trimmed = actionKey.trim();
        String[] parts = trimmed.split("\\.");
        if (parts.length >= 3) {
            String domain = parts[0];
            if ("combat".equals(domain) || "player".equals(domain)) {
                String moduleKey = parts[2];
                if (!moduleKey.isBlank()) {
                    return domain + "." + moduleKey;
                }
            }
        }

        return trimmed;
    }
}
