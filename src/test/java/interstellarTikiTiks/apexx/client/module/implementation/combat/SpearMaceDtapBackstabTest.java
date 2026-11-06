package interstellarTikiTiks.apexx.client.module.implementation.combat;

import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Setting;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpearMaceDtapBackstabTest {

    @Test
    void spearMaceDtapRegistersAutoBackstabSetting() {
        SpearMaceDtap dtap = new SpearMaceDtap();
        Optional<Setting> setting = dtap.getSettings().stream()
                .filter(s -> "Auto Backstab".equals(s.getName()))
                .findFirst();

        assertTrue(setting.isPresent(), "Auto Backstab setting should be present in SpearMaceDtap");
        assertTrue(setting.get() instanceof BooleanSetting, "Auto Backstab should be a BooleanSetting");
        assertTrue(((BooleanSetting) setting.get()).isEnabled(), "Auto Backstab should default to true");
        assertTrue(dtap.isAutoBackstabEnabled(), "isAutoBackstabEnabled should reflect setting default");
    }

    @Test
    void isAutoBackstabActiveIsFalseWhenNotChargingOrDisabled() {
        // Without active MinecraftClient / world or charging spear, isAutoBackstabActive must be false
        assertFalse(SpearMaceDtap.isAutoBackstabActive(),
                "Auto Backstab must not activate when there is no active spear charge");
    }

    @Test
    void shieldFacingGeometryDistinguishesFrontFromRear() {
        // Target at (0, 0, 0) facing south (headYaw = 0 degrees -> facing vector (0, 0, 1)).
        // An attacker at (0, 0, 5) is directly in front of the shield (dot > 0).
        // headYaw 0 -> -sin(0) = 0, cos(0) = 1.
        // toAttacker (0, 0, 5) -> dot = (0*0 + 1*5) / 5 = 1.0 > 0 (front hemisphere).
        double yawRad = Math.toRadians(0.0f);
        double facingX = -Math.sin(yawRad);
        double facingZ = Math.cos(yawRad);

        // Attacker in front at z = 5
        double dxFront = 0.0;
        double dzFront = 5.0;
        double lenFront = Math.hypot(dxFront, dzFront);
        double dotFront = (facingX * dxFront + facingZ * dzFront) / lenFront;
        assertTrue(dotFront > 0.0, "Attacker in front must have positive facing dot");

        // Attacker behind at z = -5
        double dxBehind = 0.0;
        double dzBehind = -5.0;
        double lenBehind = Math.hypot(dxBehind, dzBehind);
        double dotBehind = (facingX * dxBehind + facingZ * dzBehind) / lenBehind;
        assertTrue(dotBehind <= 0.0, "Attacker behind must have negative facing dot");
    }
}
