package interstellarTikiTiks.apexx.client;

/**
 * Standalone build: there is no account, license or server. Every feature is available and nothing is ever sent
 * anywhere. The session token is always null, so anything that would talk to a service stays offline.
 */
public final class ApexxAuth {

    private ApexxAuth() {
    }

    public static boolean isAuthorized() {
        return true;
    }

    public static boolean isAuthenticated() {
        return true;
    }

    public static String getSessionTier() {
        return "premium";
    }

    public static String getSessionToken() {
        return null;
    }

    public static int authGateToken(int salt) {
        return 1;
    }

    public static String buildFeaturePermitContext(String feature, String action, String scope) {
        return "";
    }

    public static boolean hasFeatureActionPermitFast(String feature, String action, String context) {
        return true;
    }

    public static void prefetchFeatureActionPermit(String feature, String action, String context) {
    }

    public static void clearRuntimeSession() {
    }
}
