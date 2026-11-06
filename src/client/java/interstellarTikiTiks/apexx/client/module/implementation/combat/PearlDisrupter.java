package interstellarTikiTiks.apexx.client.module.implementation.combat;

import interstellarTikiTiks.apexx.client.event.EventBus;
import interstellarTikiTiks.apexx.client.event.EventTarget;
import interstellarTikiTiks.apexx.client.event.impl.CombatSwapLockEvent;
import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import interstellarTikiTiks.apexx.client.module.SubCategory;
import interstellarTikiTiks.apexx.client.util.ModuleAccess;
import interstellarTikiTiks.apexx.client.util.Notifier;
import interstellarTikiTiks.apexx.client.util.RotationManager;
import interstellarTikiTiks.apexx.client.util.combat.PearlInterceptEngine;
import net.minecraft.item.Items;

/**
 * PearlDisrupter: intercepts an enemy's ender pearl mid-flight with a wind charge. The pearl runs
 * into the charge and ends its flight there, so its owner lands at that point instead of where
 * the pearl was aimed.
 * One-shot: enabling it (keybind) runs a single sequence and turns the module back off.
 *
 * <p>It finds the nearest pearl not thrown by you or a friend, watches its flight for a few ticks
 * while turning toward the intercept, then swaps to the wind charge and throws it at the nearest
 * point of the flight the charge can catch. It then swaps back to the slot you held. The physics,
 * solver and flick are {@link interstellarTikiTiks.apexx.client.util.combat.PearlCatchEngine}'s,
 * via {@link PearlInterceptEngine}; aiming turns the visible camera at the flick speed, or only the
 * server-side rotation with Silent Aim.</p>
 */
public class PearlDisrupter extends Module implements interstellarTikiTiks.apexx.client.module.hooks.ClickSimulating {

    private final BooleanSetting silentAim = new BooleanSetting("Silent Aim", false)
            .setDescription("Aims the throw with a server-side rotation and leaves your visible camera where it is. Off by default: the camera turns at Flick Speed.");

    private final SliderSetting flickSpeed = new SliderSetting("Flick Speed", 60, 5, 180, 0)
            .setDescription("Fastest your camera turns toward the intercept, in degrees per tick; the turn is spread over the ticks available. Lower looks more natural but may miss fast pearls.");

    private final SliderSetting range = new SliderSetting("Range", 32.0, 8.0, 64.0, 1)
            .setDescription("Farthest enemy ender pearl, in blocks, that is searched for.");

    private final SliderSetting trackTicks = new SliderSetting("Track Ticks", 2, 0, 6, 0).withJitter(1)
            .setSection("Delays")
            .setDescription("Ticks the pearl's flight is watched and checked against its traced path before the wind charge is thrown. 0 throws as soon as one observation lands on the traced path.");

    private final SliderSetting maxWait = new SliderSetting("Max Wait", 20, 5, 40, 0)
            .setDescription("Latest moment, in ticks after you press the key, at which the wind charge may still be thrown. Later throws are skipped because the pearl is usually gone.");

    private final SliderSetting maxFlightTime = new SliderSetting("Max Flight Time", 40, 5, 60, 0)
            .setDescription("Longest the wind charge may fly, in ticks, to reach the intercept point. Longer allows farther intercepts but they are less reliable.");

    private final SliderSetting maxThrows = new SliderSetting("Max Throws", 2, 1, 3, 0)
            .setDescription("Most wind charges thrown at one pearl. The extra throws happen only while the keybind is held: a pearl still flying past the planned catch is re-traced and thrown at again once the wind charge is off cooldown. A tap throws once.");

    private final SliderSetting throwDelay = new SliderSetting("Throw Delay (ms)", 40, 0, 300, 0).withJitter(15)
            .setSection("Delays")
            .setDescription("Minimum wait after you press the key before the wind charge is thrown, in milliseconds (the throw still waits for Track Ticks and a reachable intercept).");
    private final SliderSetting swapBackDelay = new SliderSetting("Swap Back Delay (ms)", 50, 0, 400, 0).withJitter(15)
            .setSection("Delays")
            .setDescription("Wait after a throw before your previous hotbar item is reselected, in milliseconds. Always at least the next tick.");

    private final PearlInterceptEngine engine = new PearlInterceptEngine(this);
    private int sampledTrackTicks = 2;

    public PearlDisrupter() {
        super("PearlDisrupter", "Stops enemy escapes: when you press the key, it finds an enemy ender pearl in flight, traces its path, and throws an aimed wind charge that meets the pearl mid-air and knocks it off course. Holding the key allows a follow-up throw at a pearl that is still flying.", Category.COMBAT);
        setSubCategory(SubCategory.MACE);
        addSetting(silentAim);
        addSetting(flickSpeed);
        addSetting(range);
        addSetting(trackTicks);
        addSetting(maxWait);
        addSetting(maxFlightTime);
        addSetting(maxThrows);
        addSetting(throwDelay);
        addSetting(swapBackDelay);
    }

    @Override
    public boolean isOneShot() {
        return true;
    }

    @Override
    public void onEnable() {
        if (mc.player == null || mc.world == null) {
            toggle();
            return;
        }
        if (!hasHotbarItem(Items.WIND_CHARGE)) {
            Notifier.warn(getName(), "Requires a Wind Charge in the hotbar");
            toggle();
            return;
        }
        if (mc.player.getItemCooldownManager().isCoolingDown(Items.WIND_CHARGE.getDefaultStack())) {
            Notifier.warn(getName(), "Wind Charge is on cooldown");
            toggle();
            return;
        }

        EventBus.register(this);
        // Sampled once per sequence so the watch length stays fixed while the pearl flies.
        sampledTrackTicks = (int) Math.round(trackTicks.sample());
        boolean started;
        try {
            started = engine.start(config());
        } catch (RuntimeException error) {
            Notifier.warn(getName(), "Start error: " + error);
            toggle();
            return;
        }
        if (!started) {
            String failure = engine.failure();
            if (failure != null) Notifier.warn(getName(), failure);
            toggle();
            return;
        }
        RotationManager.acquireExclusiveLock(this);
        pauseCombatAssists();
    }

    @Override
    public void onDisable() {
        EventBus.unregister(this);
        releaseSequence();
    }

    @Override
    protected void onRuntimeStateReleased() {
        releaseSequence();
    }

    @Override
    public void onTick() {
        if (mc.player == null || mc.world == null) {
            toggle();
            return;
        }
        if (!engine.isRunning()) {
            toggle();
            return;
        }

        pauseCombatAssists();
        if (!engine.tick(config())) {
            String failure = engine.failure();
            if (failure != null) Notifier.warn(getName(), failure);
            toggle();
        }
    }

    private PearlInterceptEngine.Config config() {
        return new PearlInterceptEngine.Config(
                silentAim.isEnabled(),
                (float) flickSpeed.getValue(),
                range.getValue(),
                sampledTrackTicks,
                (int) maxWait.getValue(),
                (int) maxFlightTime.getValue(),
                isBindHeld() ? (int) maxThrows.getValue() : 1,
                throwDelay.sample(),
                swapBackDelay.sample()
        );
    }

    @EventTarget
    public void onCombatSwapLock(CombatSwapLockEvent event) {
        if (RotationManager.isExclusiveLocked()) {
            event.setItemSwapLocked(true);
            event.setAutoAttackLocked(true);
        }
    }

    private void releaseSequence() {
        engine.stop();
        RotationManager.releaseExclusiveLock(this);
        RotationManager.clearTarget(this);
    }

    private void pauseCombatAssists() {
        AimAssist aim = ModuleAccess.getModule(AimAssist.class);
        if (aim != null) aim.externalPause(250L);
        AutoMace mace = ModuleAccess.getModule(AutoMace.class);
        if (mace != null) mace.externalPause(250L);
    }

    private boolean hasHotbarItem(net.minecraft.item.Item item) {
        if (mc.player == null) return false;
        for (int slot = 0; slot < 9; slot++) {
            if (mc.player.getInventory().getStack(slot).isOf(item)) return true;
        }
        return false;
    }
}
