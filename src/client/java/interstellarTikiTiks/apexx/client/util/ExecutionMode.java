package interstellarTikiTiks.apexx.client.util;

import interstellarTikiTiks.apexx.client.mixin.MinecraftClientAccessor;

/**
 * Client-wide choice of how module attacks and hotbar swaps are executed.
 *
 * <ul>
 *   <li><b>Legit</b> (default): the established Apexx path. Module clicks use normal client input
 *       unless that module's Click Sim toggle is on. A slot change is followed by a
 *       sub-tick micro-delay before the click, and the vanilla attack cooldown is cleared before a
 *       prepared attack.</li>
 *   <li><b>Direct</b>: the immediate path. The slot is selected and the vanilla {@code doAttack}
 *       / {@code doItemUse} runs in the same call, with no GLFW round trip, no micro-delay queue
 *       and no cooldown override, so vanilla's own attack-interval gate still applies.</li>
 * </ul>
 *
 * <p>This only chooses between two dispatch paths at the shared seams ({@code ClickSimController},
 * {@code AttackValidator}, {@code SubTickSequencer}, prepared-attack cooldown handling). Module
 * policy (targets, thresholds, state machines) is identical in both modes. A module's Click Sim
 * toggle enables GLFW callbacks and brief visible holds in either execution mode;
 * Direct action timing and cooldown handling remain unchanged.</p>
 */
public final class ExecutionMode {
    public static final String LEGIT = "Legit";
    public static final String DIRECT = "Direct";

    private static volatile boolean direct = false;

    private ExecutionMode() {
    }

    public static boolean isDirect() {
        return direct;
    }

    public static boolean isLegit() {
        return !direct;
    }

    public static void set(String mode) {
        direct = DIRECT.equalsIgnoreCase(mode);
    }

    /** Clears the vanilla attack cooldown in Legit mode only; Direct leaves vanilla's gate intact. */
    public static void relaxAttackCooldown(MinecraftClientAccessor accessor) {
        if (!direct && accessor != null) {
            accessor.setAttackCooldown(0);
        }
    }
}
