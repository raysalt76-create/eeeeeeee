package interstellarTikiTiks.apexx.client.util;

import interstellarTikiTiks.apexx.client.ApexxAuth;
import interstellarTikiTiks.apexx.client.module.hooks.CosmeticsProvider;
import interstellarTikiTiks.apexx.client.module.hooks.StudioProvider;

/**
 * The one check for Premium-only features (Cosmetics, Macros, Scripts, Config Maker). Policy: a Premium feature is
 * not shown at all to a free session, not even as a locked card or a "Premium" tag; its nav entry, tab, button and
 * tour step are hidden and its view cannot be opened. Saved data for those features is still carried through.
 *
 * <p>Premium = the premium payload is loaded (its hooks are registered; the free payload has none of them) AND the
 * live session tier is "premium" (the dev build reports premium).</p>
 */
public final class PremiumGate {
    private PremiumGate() { }

    /** Premium session with the premium payload loaded. */
    public static boolean isPremium() {
        return premiumTier() && (hook(StudioProvider.class) != null || hook(CosmeticsProvider.class) != null);
    }

    /** Macros and Scripts tabs. */
    public static boolean studio() {
        return premiumTier() && hook(StudioProvider.class) != null;
    }

    /** Cosmetics tab. */
    public static boolean cosmetics() {
        return premiumTier() && hook(CosmeticsProvider.class) != null;
    }

    private static boolean premiumTier() {
        try {
            return "premium".equalsIgnoreCase(ApexxAuth.getSessionTier());
        } catch (Throwable t) {
            return false;
        }
    }

    private static <T> T hook(Class<T> type) {
        try {
            return ModuleAccess.getHook(type);
        } catch (Throwable t) {
            return null;
        }
    }
}
