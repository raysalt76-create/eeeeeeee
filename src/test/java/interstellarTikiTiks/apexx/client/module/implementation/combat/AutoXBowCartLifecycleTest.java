package interstellarTikiTiks.apexx.client.module.implementation.combat;

import interstellarTikiTiks.apexx.client.util.RotationManager;
import interstellarTikiTiks.apexx.client.util.combat.SlotSync;
import interstellarTikiTiks.apexx.client.util.combat.SubTickSequencer;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Exercises state/cleanup without loading Minecraft registries or a Fabric runtime. */
class AutoXBowCartLifecycleTest {
    private static Field field(String name) throws Exception {
        Field field = AutoXBowCart.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static void complete(AutoXBowCart cart, long now) throws Exception {
        var method = AutoXBowCart.class.getDeclaredMethod("completeStep", long.class);
        method.setAccessible(true);
        method.invoke(cart, now);
    }

    @Test
    void railCompletionAdvancesImmediatelyWithOnlySharedPacing() throws Exception {
        AutoXBowCart cart = new AutoXBowCart();
        field("stage").set(cart, AutoXBowCart.Stage.RAIL);
        complete(cart, 1000L);
        assertEquals(AutoXBowCart.Stage.CART, field("stage").get(cart));
        long next = field("nextActionTimeMs").getLong(cart);
        // Pacing comes from the module's own jittered Rail Delay (Gaussian, truncated at +-2.5 sigma).
        interstellarTikiTiks.apexx.client.module.SliderSetting step =
                (interstellarTikiTiks.apexx.client.module.SliderSetting) cart.getSetting("Rail Delay (ms)");
        double sigma = step.getJitter() == null ? 0.0 : step.getJitter().getValue();
        assertTrue(next >= 1000 + Math.floor(step.getValue() - 2.5 * sigma) - 1);
        assertTrue(next <= 1000 + Math.ceil(step.getValue() + 2.5 * sigma) + 1);
        assertNull(cart.getSetting("Tick Randomness"));
    }

    @Test
    void doubleCartRequiresBothClicksBeforeConfirmation() throws Exception {
        AutoXBowCart cart = new AutoXBowCart();
        field("stage").set(cart, AutoXBowCart.Stage.CART);
        field("targetCartsCount").setInt(cart, 2);
        complete(cart, 1000L);
        assertEquals(AutoXBowCart.Stage.CART, field("stage").get(cart));
        assertEquals(1, field("cartsPlacedCount").getInt(cart));
        complete(cart, 1050L);
        assertEquals(AutoXBowCart.Stage.CART_CONFIRM, field("stage").get(cart));
        assertEquals(2, field("cartsPlacedCount").getInt(cart));
    }

    @Test
    void clickCompletionCannotPretendCrossbowWasFired() throws Exception {
        AutoXBowCart cart = new AutoXBowCart();
        field("stage").set(cart, AutoXBowCart.Stage.SHOOT);
        complete(cart, 1000L);
        assertEquals(AutoXBowCart.Stage.SHOOT_CONFIRM, field("stage").get(cart));
        assertTrue(cart.isBusy());
    }

    @Test
    void ignitionCompletionNeedsWorldConfirmation() throws Exception {
        AutoXBowCart cart = new AutoXBowCart();
        field("stage").set(cart, AutoXBowCart.Stage.IGNITE);
        complete(cart, 1000L);
        assertEquals(AutoXBowCart.Stage.IGNITE_CONFIRM, field("stage").get(cart));
    }

    @Test
    void cleanupCancelsPendingWorkAndResetsRetriesAndDeadlines() throws Exception {
        try (var rotations = mockStatic(RotationManager.class);
             var slots = mockStatic(SlotSync.class);
             var sequencer = mockStatic(SubTickSequencer.class)) {
            AutoXBowCart cart = new AutoXBowCart();
            field("stage").set(cart, AutoXBowCart.Stage.CART_CONFIRM);
            field("shotRetries").setInt(cart, 3);
            field("cartRetries").setInt(cart, 3);
            field("stageStartTimeMs").setLong(cart, 1000L);
            field("nextActionTimeMs").setLong(cart, 2000L);
            field("lastActionTick").setLong(cart, 100L);
            field("expectedSlot").setInt(cart, 2);
            field("originalSlot").setInt(cart, 0);
            var pending = mock(SubTickSequencer.FluentSequence.class);
            field("pendingSequence").set(cart, pending);
            cart.onDisable();
            cart.onDisable();
            assertFalse(cart.isBusy());
            assertNull(field("pendingSequence").get(cart));
            assertEquals(0, field("shotRetries").getInt(cart));
            assertEquals(0, field("cartRetries").getInt(cart));
            assertEquals(0, field("nextActionTimeMs").getLong(cart));
            assertEquals(-1, field("lastActionTick").getLong(cart));
            assertEquals(-1, field("expectedSlot").getInt(cart));
            rotations.verify(() -> RotationManager.stop(cart), times(2));
            slots.verify(() -> SlotSync.releaseOwner(cart), times(2));
            slots.verify(() -> SlotSync.restore(any(), anyInt()), never());
            sequencer.verify(() -> SubTickSequencer.cancelOwner(cart), atLeastOnce());
        }
    }

    @Test
    void cannotStartOverAnActiveSequence() throws Exception {
        AutoXBowCart cart = new AutoXBowCart();
        field("stage").set(cart, AutoXBowCart.Stage.IGNITE_CONFIRM);
        assertFalse(cart.tryStartSequence());
        assertEquals(AutoXBowCart.Stage.IGNITE_CONFIRM, field("stage").get(cart));
    }
}
