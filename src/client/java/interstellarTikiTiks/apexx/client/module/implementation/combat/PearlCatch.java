package interstellarTikiTiks.apexx.client.module.implementation.combat;

import interstellarTikiTiks.apexx.client.event.EventBus;
import interstellarTikiTiks.apexx.client.event.EventTarget;
import interstellarTikiTiks.apexx.client.event.impl.CombatSwapLockEvent;
import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import interstellarTikiTiks.apexx.client.module.hooks.IncomingPacketInterceptor;
import interstellarTikiTiks.apexx.client.util.ModuleAccess;
import interstellarTikiTiks.apexx.client.util.RotationManager;
import interstellarTikiTiks.apexx.client.util.combat.PearlCatchEngine;
import net.minecraft.item.Items;
import net.minecraft.network.packet.Packet;
import net.minecraft.util.math.Vec3d;

/**
 * PearlCatch: throws an ender pearl where you look and a wind charge into its path, so the pearl runs
 * into the charge mid-air and teleports the player there.
 * One-shot: enabling it (keybind) runs a single sequence and turns the module back off.
 *
 * <p>The pearl always follows the look; {@link PearlCatchEngine} runs the sequence and its planner aims
 * the wind charge. Looking up, level or shallowly down the charge goes after the pearl and catches it as
 * it slows; past a downward pitch learned per fall speed the charge goes first and the pearl, which
 * speeds up on the way down, runs into it from behind. Within 85% of vertical either way (|pitch| >= 76.5)
 * a fixed macro runs unless the solver, yaw held near the camera's, does better.</p>
 *
 * <p>From the first throw until the second has left, the engine holds RotationManager's exclusive lock,
 * which RotationManager enforces: no other aim (AutoMace, AimAssist, ...) can take or steer the look in
 * between, and hotbar swaps by other modules are refused through {@link CombatSwapLockEvent}.</p>
 */
public class PearlCatch extends Module implements IncomingPacketInterceptor, interstellarTikiTiks.apexx.client.module.hooks.ClickSimulating {

    // Tuned values, not settings: the engine solves the timing itself. The gap between the throws sets
    // how long the pearl flies before the charge meets it, so the Catch Time picks it between the floor
    // and the ceiling (a 24-tick gap reaches the longest catches that still land); the spawn wait only
    // gives the engine room to re-aim at the first projectile's real launch.
    private static final float FLICK_SPEED = 60.0f;
    private static final int MIN_GAP = 1;
    private static final int MAX_GAP = 24;
    private static final int SPAWN_WAIT = 2;

    private final BooleanSetting silentAim = new BooleanSetting("Silent Aim", false)
            .setDescription("Aims the throws with a server-side rotation and leaves your visible camera where it is. Off by default; the upward vertical macro never turns the look, so it has no effect there.");

    private final SliderSetting catchTime = new SliderSetting("Catch Time", 5, 2, 8, 0)
            .setDescription("Flight time, in ticks, from the second throw to the catch, for every mode (aimed, up and down). Between catches that are about equally sure it picks the wait between the throws: higher waits longer and catches the pearl further out (the longest within it, else the earliest past it). A clearly surer catch is always taken first.");

    private final BooleanSetting swapBack = new BooleanSetting("Swap Back", true)
            .setDescription("When the catch is done, reselect the hotbar item you were holding before it started.");

    private final SliderSetting throwGap = new SliderSetting("Throw Gap (ms)", 10, 0, 250, 0).withJitter(5)
            .setSection("Delays")
            .setDescription("Extra wait added before the second throw, on top of the gap the planner needs for the catch. "
                    + "Rounded to ticks (a remainder still counts on average). The planner plans with it, and when it "
                    + "would cost the catch the press falls back to the plain gap. 0 = throw as soon as the plan allows.");
    private final SliderSetting swapBackDelay = new SliderSetting("Swap Back Delay (ms)", 40, 0, 300, 0).withJitter(12)
            .setSection("Delays")
            .setDescription("Wait after the catch finishes before your previous hotbar item is reselected.")
            .setDependsOn("Swap Back");

    private PearlCatchEngine engine;

    public PearlCatch() {
        super("PearlCatch", "One key press performs a pearl catch: it throws an ender pearl where you are looking, then throws a wind charge timed and aimed so it meets the pearl mid-air, launching you up with the pearl's teleport for a mace dive. It simulates both projectiles and picks the throw gap with the surest catch.", Category.COMBAT);
        interstellarTikiTiks.apexx.client.util.combat.PearlTiming.warmUpCatchPlanner();
        addSetting(silentAim);
        addSetting(catchTime);
        addSetting(swapBack);
        addSetting(throwGap);
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
        if (!interstellarTikiTiks.apexx.client.util.combat.PearlLimit.canThrow(mc)) {
            warn("Catch failed: Pearl Limit reached");
            toggle();
            return;
        }
        boolean hasPearl = hasHotbarItem(Items.ENDER_PEARL);
        boolean hasCharge = hasHotbarItem(Items.WIND_CHARGE);
        if (!hasPearl || !hasCharge) {
            warn("Catch failed: " + (!hasPearl && !hasCharge ? "no Ender Pearl or Wind Charge"
                    : !hasPearl ? "no Ender Pearl" : "no Wind Charge") + " in the hotbar");
            toggle();
            return;
        }

        EventBus.register(this);
        // The engine takes the rotation channel itself when the first throw actually starts. A press
        // while the last catch's items still cool down stays armed in the engine until they are ready.
        pauseCombatAssists();
        if (engine == null) engine = new PearlCatchEngine(this);
        boolean started;
        try {
            started = engine.start(config());
        } catch (RuntimeException error) {
            warn("Catch failed: start error " + error);
            toggle();
            return;
        }
        if (!started) {
            String failure = engine.failure();
            if (failure != null) warn("Catch failed: " + failure);
            toggle();
        }
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
        if (engine == null || !engine.isRunning()) {
            toggle();
            return;
        }

        // Once both throws are out, AutoMace and AimAssist are not held any longer: the dive needs them.
        if (!engine.isFinishing()) pauseCombatAssists();
        if (!engine.tick(config())) {
            String failure = engine.failure();
            String notice = engine.notice();
            if (failure != null) {
                warn("Catch failed: " + failure);
            } else if (notice == null) {
                success("Catch done: pearl and wind charge thrown");
            }
            if (notice != null) warn(notice);
            toggle();
        }
    }

    private PearlCatchEngine.Config config() {
        return new PearlCatchEngine.Config(
                silentAim.isEnabled(),
                FLICK_SPEED,
                MIN_GAP,
                MAX_GAP,
                SPAWN_WAIT,
                (int) catchTime.getValue(),
                0.0f,
                swapBack.isEnabled(),
                throwGap.sample(),
                swapBackDelay.sample()
        );
    }

    @Override
    public boolean handleIncomingPacket(Packet<?> packet) {
        if (engine != null) engine.receive(packet);
        return false;
    }

    @EventTarget
    public void onCombatSwapLock(CombatSwapLockEvent event) {
        if (RotationManager.isExclusiveLocked()) {
            event.setItemSwapLocked(true);
            event.setAutoAttackLocked(true);
        }
    }

    /** Catch outcomes go to the internal log only; the on-screen cards are reserved for module on/off. */
    private void warn(String message) {
        interstellarTikiTiks.apexx.client.util.ClientInternalLog.info(getName(), message);
    }

    private void success(String message) {
        interstellarTikiTiks.apexx.client.util.ClientInternalLog.info(getName(), message);
    }

    private void releaseSequence() {
        if (engine != null) engine.stop();
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

    public static double computeClosestDistance(Vec3d pearlStart, Vec3d pearlEnd, Vec3d windStart, Vec3d windEnd) {
        return PearlCatchEngine.computeClosestDistance(pearlStart, pearlEnd, windStart, windEnd);
    }
}
