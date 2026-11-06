package interstellarTikiTiks.apexx.client.util.combat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class ModuleDelaysTest {

    @Test
    void configuredDelayIsUsedExactlyWhenProfileOff() {
        assertEquals(45L, ModuleDelays.clampMs(45.0));
        assertEquals(0L, ModuleDelays.clampMs(-5.0));
        assertEquals(0L, ModuleDelays.clampMs(Double.NaN));
        assertEquals(1000L, ModuleDelays.clampMs(99999.0));
    }
}
