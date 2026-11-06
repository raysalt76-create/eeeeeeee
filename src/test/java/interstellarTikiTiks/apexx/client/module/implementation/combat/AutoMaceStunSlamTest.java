package interstellarTikiTiks.apexx.client.module.implementation.combat;

import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.ModeSetting;
import interstellarTikiTiks.apexx.client.module.Setting;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoMaceStunSlamTest {

    @Test
    void adaptiveProfileUsesTheConfiguredVelocityBuckets() {
        assertEquals(4, AutoMace.adaptiveStunTickGap(-0.60), "Conservative low-speed fall should use four ticks");
        assertEquals(3, AutoMace.adaptiveStunTickGap(-1.1395), "10-block-like downward speed should use three ticks");
        assertEquals(2, AutoMace.adaptiveStunTickGap(-1.3553), "15-block-like downward speed should now use two ticks");
        assertEquals(2, AutoMace.adaptiveStunTickGap(-1.40), "Mid/high fall speed should use two ticks");
        assertEquals(1, AutoMace.adaptiveStunTickGap(-1.7817), "30-block-like downward speed uses the fastest legal follow-up, one tick");
    }

    @Test
    void adaptiveProfileHasStableBoundaryBehavior() {
        assertEquals(4, AutoMace.adaptiveStunTickGap(-0.6174));
        assertEquals(3, AutoMace.adaptiveStunTickGap(-0.6175));
        assertEquals(3, AutoMace.adaptiveStunTickGap(-1.2324));
        assertEquals(2, AutoMace.adaptiveStunTickGap(-1.2325));
        assertEquals(2, AutoMace.adaptiveStunTickGap(-1.4449));
        assertEquals(1, AutoMace.adaptiveStunTickGap(-1.445));
    }

    @Test
    void adaptiveProfileUsesOnlyHorizontalDistanceForTheFastFollowUp() {
        assertEquals(0.0, AutoMace.horizontalDistanceBetween(0.0, 0.0, 0.0, 0.0), 0.0001,
                "Vertical separation is not part of horizontal distance");
        assertEquals(3.0, AutoMace.horizontalDistanceBetween(0.0, 0.0, 3.0, 0.0), 0.0001,
                "A three-block X/Z offset is three blocks horizontally");

        assertEquals(4, AutoMace.adaptiveStunTickGap(-0.60, 2.0),
                "Exactly two horizontal blocks keeps the original gap");
        assertEquals(2, AutoMace.adaptiveStunTickGap(-0.60, 2.01),
                "Four ticks are halved and floored beyond two horizontal blocks");
        assertEquals(1, AutoMace.adaptiveStunTickGap(-1.1395, 2.01),
                "Three ticks are halved and floored beyond two horizontal blocks");
        assertEquals(1, AutoMace.adaptiveStunTickGap(-1.40, 3.0),
                "Two ticks become the fastest legal follow-up beyond two horizontal blocks");
        assertEquals(1, AutoMace.adaptiveStunTickGap(-1.7817, 3.0),
                "The fastest adaptive profile stays at one tick, never zero");
    }

    @Test
    void adaptiveProfileShortensTheGapAsAbsoluteDistanceCloses() {
        assertEquals(4, AutoMace.adaptiveStunTickGap(-0.60, 0.0, 3.0), "Beyond 2.7 blocks nothing changes");
        assertEquals(4, AutoMace.adaptiveStunTickGap(-0.60, 0.0, 2.7));
        assertEquals(3, AutoMace.adaptiveStunTickGap(-0.60, 0.0, 2.0), "Two blocks away must not use the full four ticks");
        assertEquals(2, AutoMace.adaptiveStunTickGap(-0.60, 0.0, 1.0), "At one block it is two ticks faster");
        assertEquals(2, AutoMace.adaptiveStunTickGap(-0.60, 0.0, 0.5), "Closer than one block stays at the maximum reduction");
        assertEquals(1, AutoMace.adaptiveStunTickGap(-1.1395, 0.0, 1.0));
        assertEquals(1, AutoMace.adaptiveStunTickGap(-1.40, 0.0, 1.0), "Never below one tick");
        assertEquals(1, AutoMace.adaptiveStunTickGap(-0.60, 3.0, 1.0), "Halved to 2, then reduced by 2 and held at the one tick minimum");
    }

    @Test
    void followUpWaitsForTheConfiguredClientTickGap() {
        assertTrue(!AutoMace.stunFollowUpDue(0, 0, 1), "Never on the axe's own tick");
        assertTrue(!AutoMace.stunFollowUpDue(1, 1, 2), "Gap 2 is not due one tick after the axe");
        assertTrue(AutoMace.stunFollowUpDue(2, 1, 2), "Gap 2 is due two client ticks after the axe click");
        assertTrue(AutoMace.stunFollowUpDue(3, 3, 2), "A later frame stays due");
    }

    @Test
    void followUpNeverSharesTheAxesWireTick() {
        assertTrue(!AutoMace.stunFollowUpDue(1, 0, 1),
                "A render-frame axe leaves at the next tick head; the mace must be on a later wire tick");
        assertTrue(AutoMace.stunFollowUpDue(1, 1, 1));
    }

    @Test
    void adaptiveZeroGapMeansAsSoonAsLegalNotNever() {
        assertTrue(AutoMace.stunFollowUpDue(1, 1, 0), "A 0 gap fires one tick after the axe");
        assertTrue(!AutoMace.stunFollowUpDue(0, 1, 0), "...but never on the same client tick");
    }

    @Test
    void adaptiveCountsTheAxesIntervalNormalCountsClientTicks() {
        // In-tick axe: client and wire clocks agree.
        assertEquals(2L, AutoMace.stunEngineTicksSinceAxe(true, 2, 2));
        assertEquals(2L, AutoMace.stunEngineTicksSinceAxe(false, 2, 2));
        // Render-frame axe, held to the next tick head: the forecast index follows the wire.
        assertEquals(1L, AutoMace.stunEngineTicksSinceAxe(true, 2, 1));
        assertEquals(2L, AutoMace.stunEngineTicksSinceAxe(false, 2, 1));
    }

    @Test
    void autoMaceExposesCurrentTimingModesAndDirectTickSlider() {
        AutoMace autoMace = new AutoMace();
        List<Setting> settings = autoMace.getSettings();

        Setting modeSetting = settings.stream()
                .filter(setting -> setting.getName().equals("StunSlam Mode"))
                .findFirst()
                .orElse(null);
        assertNotNull(modeSetting);
        ModeSetting mode = (ModeSetting) modeSetting;
        assertEquals("Normal", mode.getCurrentMode());
        assertEquals(List.of("Normal", "Adaptive"), mode.getModes());

        assertTrue(settings.stream().noneMatch(setting -> List.of(
                        "0-Tick", "MultiTick", "Stun Delay (Ticks)", "Min Stun Ticks", "Max Stun Ticks")
                .contains(setting.getName())),
                "Legacy-only timing controls must be removed from AutoMace.");

        Setting ticksSetting = settings.stream()
                .filter(setting -> setting.getName().equals("Stun Slam Ticks"))
                .findFirst()
                .orElse(null);
        assertNotNull(ticksSetting);
        SliderSetting ticks = (SliderSetting) ticksSetting;
        assertEquals(1.0, ticks.getValue(), 0.001);
        assertEquals(1.0, ticks.getMin(), 0.001);
        assertEquals(10.0, ticks.getMax(), 0.001);
        // The Stun Slam timing controls show while Shield Counter is Stun Slam or "Backstab then Stun Slam"
        // (the fallback also stuns), so they use a visibility predicate instead of a single dependsOn.
        assertTrue(mode.isVisible());
        assertTrue(ticks.isVisible());
        assertNotNull(ticks.getJitter(), "Stun Slam Ticks carries a Gaussian StDev companion");

        int modeIndex = settings.indexOf(modeSetting);
        int ticksIndex = settings.indexOf(ticksSetting);
        assertEquals(modeIndex + 1, ticksIndex,
                "The Normal-mode timing slider must remain directly below its mode selector so it appears in view after selecting Normal.");
        Setting shieldCounterSetting = settings.stream()
                .filter(setting -> setting.getName().equals("Shield Counter"))
                .findFirst()
                .orElseThrow();
        ModeSetting shieldCounter = (ModeSetting) shieldCounterSetting;
        assertEquals("Stun Slam", shieldCounter.getCurrentMode(), "Stun Slam stays the default shield counter.");
        assertEquals(List.of("Stun Slam", "Backstab", "Backstab then Stun Slam", "None"), shieldCounter.getModes());
        assertTrue(modeIndex > settings.indexOf(shieldCounterSetting),
                "The mode selector must follow the Shield Counter selector.");

        shieldCounter.setCurrentMode("Backstab");
        assertFalse(mode.isVisible(), "Stun Slam controls hide when the counter never stuns");
        shieldCounter.setCurrentMode("Backstab then Stun Slam");
        assertTrue(mode.isVisible(), "The fallback mode still stuns, so its controls show");
    }

    @Test
    void testAutoUnblockSetting() {
        AutoMace autoMace = new AutoMace();
        Setting unblockSetting = autoMace.getSettings().stream()
                .filter(s -> s.getName().equals("Auto Unblock"))
                .findFirst()
                .orElse(null);
        assertNotNull(unblockSetting, "Auto Unblock setting must be registered in AutoMace");
        assertTrue(unblockSetting instanceof BooleanSetting, "Auto Unblock must be a BooleanSetting");
        BooleanSetting setting = (BooleanSetting) unblockSetting;
        assertTrue(setting.isEnabled(), "Auto Unblock should default to enabled");
        assertEquals("Equipment", setting.getSection(), "Auto Unblock should be in Equipment section");
    }

    @Test
    void testAimOptionsCondensed() {
        AutoMace autoMace = new AutoMace();
        List<String> aim = autoMace.getSettings().stream()
                .filter(s -> "Aim".equals(s.getSection()))
                .map(Setting::getName)
                .toList();
        assertEquals(List.of("Silent Aim", "Aim Speed", "Pre-Aim Distance"), aim,
                "Aim section must only expose Silent Aim, Aim Speed and the acquisition distance");
        for (String removed : List.of("Aim Mode", "Aim Strength", "Hard Track Distance",
                "Predictive Pre-Aim", "Prediction Confidence", "FOV", "Advanced Tracking")) {
            assertTrue(autoMace.getSettings().stream().noneMatch(s -> s.getName().equals(removed)),
                    removed + " must be removed");
        }
    }
}
