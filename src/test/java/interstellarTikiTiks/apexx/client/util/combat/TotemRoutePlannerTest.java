package interstellarTikiTiks.apexx.client.util.combat;

import interstellarTikiTiks.apexx.client.util.combat.TotemRoutePlanner.Route;
import interstellarTikiTiks.apexx.client.util.combat.TotemRoutePlanner.State;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TotemRoutePlannerTest {

    /** Builder-ish helper: everything usable, offhand goal, desktop, no silent. */
    private static State state(String mode, boolean hotbarTotem, boolean keyUsable, boolean invOpen,
                               boolean canOpen, boolean sim, boolean allowSilent) {
        return new State(mode, true, true, hotbarTotem, keyUsable, invOpen, false, canOpen, sim, allowSilent,
                false, true);
    }

    @Test
    void autoPrefersSwapKeyWithHotbarTotem() {
        assertEquals(Route.SWAP_KEY, TotemRoutePlanner.choose(state("Auto", true, true, false, true, true, false)));
    }

    @Test
    void autoUsesOpenInventoryWithCursor() {
        assertEquals(Route.IN_INVENTORY, TotemRoutePlanner.choose(state("Auto", true, true, true, true, true, false)));
    }

    @Test
    void autoOpensInventoryWithSimCursorWhenNoHotbarTotem() {
        assertEquals(Route.OPEN_SIM_CURSOR, TotemRoutePlanner.choose(state("Auto", false, true, false, true, true, false)));
        // Unbound key / unfocused window: same.
        assertEquals(Route.OPEN_SIM_CURSOR, TotemRoutePlanner.choose(state("Auto", true, false, false, true, true, false)));
    }

    @Test
    void autoOnMobileOpensVisible() {
        assertEquals(Route.OPEN_VISIBLE, TotemRoutePlanner.choose(state("Auto", false, true, false, true, false, false)));
    }

    @Test
    void autoUsesSilentOnlyWithOptIn() {
        assertEquals(Route.NONE, TotemRoutePlanner.choose(state("Auto", false, true, false, false, true, false)));
        assertEquals(Route.SILENT, TotemRoutePlanner.choose(state("Auto", false, true, false, false, true, true)));
    }

    @Test
    void swapKeyOnlyReachesTheOffhand() {
        State hotbarGoal = new State("Auto", false, true, true, true, false, false, true, true, false, false, true);
        assertEquals(Route.OPEN_SIM_CURSOR, TotemRoutePlanner.choose(hotbarGoal));
    }

    @Test
    void swapKeyModeFallsBackToVisibleWithoutSilent() {
        assertEquals(Route.OPEN_VISIBLE, TotemRoutePlanner.choose(state("Swap Key", false, true, false, true, true, false)));
        assertEquals(Route.SILENT, TotemRoutePlanner.choose(state("Swap Key", false, true, false, true, true, true)));
    }

    @Test
    void silentExhaustedFallsBackToVisible() {
        State exhausted = new State("Silent", true, true, false, false, false, false, false, true, true, true, true);
        assertEquals(Route.OPEN_VISIBLE, TotemRoutePlanner.choose(exhausted));
        State noFallback = new State("Silent", true, true, false, false, false, false, false, true, true, true, false);
        assertEquals(Route.NONE, TotemRoutePlanner.choose(noFallback));
    }

    @Test
    void visibleAndSimCursorNeedManageInventory() {
        assertEquals(Route.NONE, TotemRoutePlanner.choose(state("Visible", false, false, false, false, true, true)));
        assertEquals(Route.NONE, TotemRoutePlanner.choose(state("Sim Cursor", false, false, false, false, true, true)));
        assertEquals(Route.OPEN_SIM_CURSOR, TotemRoutePlanner.choose(state("Sim Cursor", true, true, false, true, true, false)));
    }

    @Test
    void nothingWithoutSourceOrInsideAnotherScreen() {
        State noSource = new State("Auto", true, false, true, true, false, false, true, true, true, false, true);
        assertEquals(Route.NONE, TotemRoutePlanner.choose(noSource));
        State chest = new State("Auto", true, true, true, true, false, true, true, true, true, false, true);
        assertEquals(Route.NONE, TotemRoutePlanner.choose(chest));
    }
}
