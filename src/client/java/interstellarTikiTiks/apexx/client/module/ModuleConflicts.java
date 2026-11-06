package interstellarTikiTiks.apexx.client.module;

import interstellarTikiTiks.apexx.client.Apexx;
import interstellarTikiTiks.apexx.client.util.ClientInternalLog;
import interstellarTikiTiks.apexx.client.util.Notifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Mutually exclusive module groups. Two members of one group fight over the same thing
 * (hotbar slot, rotation, the attack/use key), so enabling one turns the others off.
 *
 * <p>Data-driven: add a group by adding an entry to {@link #GROUPS}. Members are matched
 * by class simple name, or by display name with spaces removed. A module may sit in
 * several groups; groups are pairwise only where the members really collide, so
 * complementary modules (AimAssist aims, TriggerBot clicks) can still run together.
 *
 * <p>Config apply enables many modules in one pass; enforcement is suspended for that
 * window ({@link #beginBulkApply()} / {@link #endBulkApply()}) so loading a config never
 * fights the saved state and never toasts.
 */
public final class ModuleConflicts {

    private ModuleConflicts() {
    }

    /** group name -> member class simple names. */
    private static final Map<String, Set<String>> GROUPS = Map.of(
            // Both select the anchor/glowstone slots and charge + detonate anchors.
            "anchor", Set.of("SafeAnchor", "AutoAnchor"),
            // Both fire melee attacks on their own.
            "melee-attack", Set.of("SwordAura", "TriggerBot"),
            // Each CONTINUOUSLY owns a rotation request toward a combat target. Two at once fight
            // over the aim: RotationManager arbitrates one away by priority, which looks like the
            // loser "not working". Enabling one turns the others off. Situational utility aimers
            // (AutoHeal/AutoDrain/AutoWeb/AutoWater) are deliberately excluded -- they aim briefly
            // and rely on priority, so hard-disabling the primary aimer for them would be wrong.
            "combat-aim", Set.of("SwordAura", "AimAssist", "SpearAssist", "AutoMace", "BowAimbot")
    );

    private static int bulkDepth = 0;

    /** Suspends enforcement while a config is applied. Always pair with {@link #endBulkApply()}. */
    public static synchronized void beginBulkApply() {
        bulkDepth++;
    }

    public static synchronized void endBulkApply() {
        if (bulkDepth > 0) bulkDepth--;
    }

    public static synchronized boolean isBulkApplying() {
        return bulkDepth > 0;
    }

    private static String normalize(String s) {
        return s == null ? "" : s.replace(" ", "").replace("-", "").toLowerCase(Locale.ROOT);
    }

    private static boolean isMember(Module module, Set<String> members) {
        String cls = module.getClass().getSimpleName();
        if (members.contains(cls)) return true;
        String display = normalize(module.getName());
        for (String m : members) {
            if (normalize(m).equals(display)) return true;
        }
        return false;
    }

    /** Called by {@link Module#setEnabled} after a successful enable. */
    static void onModuleEnabled(Module enabled) {
        if (enabled == null || isBulkApplying()) return;
        if (Apexx.getInstance() == null || Apexx.getInstance().getModuleManager() == null) return;

        List<Module> toDisable = new ArrayList<>();
        for (Set<String> members : GROUPS.values()) {
            if (!isMember(enabled, members)) continue;
            for (Module other : Apexx.getInstance().getModuleManager().getModules()) {
                if (other == enabled || !other.isEnabled() || toDisable.contains(other)) continue;
                if (isMember(other, members)) toDisable.add(other);
            }
        }
        for (Module other : toDisable) {
            try {
                other.setEnabled(false);
                Notifier.warn(enabled.getName(), "Turned off " + other.getName() + " (conflicts with " + enabled.getName() + ")");
                ClientInternalLog.info(enabled.getName(), "Disabled conflicting module " + other.getName());
            } catch (Throwable t) {
                ClientInternalLog.error(enabled.getName(), "Failed to disable conflicting module " + other.getName(), t);
            }
        }
    }
}
