package interstellarTikiTiks.apexx.client.module.implementation;

import interstellarTikiTiks.apexx.client.gui.gpuui.GpuClickGuiScreen;
import interstellarTikiTiks.apexx.client.gui.new_ui.HudEditorScreen;
import interstellarTikiTiks.apexx.client.mixin.KeyBindingAccessor;
import interstellarTikiTiks.apexx.client.mixin.MinecraftClientAccessor;
import interstellarTikiTiks.apexx.client.module.implementation.combat.SmartWTap;
import interstellarTikiTiks.apexx.client.util.ModuleAccess;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.ModeSetting;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.util.SystemInputSimulator;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.effect.StatusEffects;

/**
 * AutoSprint — dynamically discovers the player's bound sprint keybind and executes an
 * authentic single-tap on movement initiation without holding or spamming keys.
 *
 * <p>Supports:
 * <ul>
 *   <li><b>Dynamic Keybind Detection:</b> Inspects {@code mc.options.sprintKey} dynamically every tick;
 *       respects keyboard keys, mouse buttons, and custom rebinds without hardcoding Left Control.</li>
 *   <li><b>Single-Tap Pulse:</b> When starting to move, issues exactly one authentic human tap
 *       (50-75ms hold) and releases. Vanilla owns sustained sprinting.</li>
 *   <li><b>Anti-Spam Guardrails:</b> Suppresses key spam when colliding into walls, enforces a debounce
 *       cooldown, and leaves the key released while sprinting.</li>
 *   <li><b>Modes:</b> Legit (single tap) or Vanilla (hold bound key). Both drive the real keybind;
 *       vanilla still owns the sprint decision, so every vanilla precondition is respected.</li>
 * </ul>
 *
 * <p><b>Removed on purpose:</b> Omni mode, Omnidirectional, Ignore Hunger, Sprint While Sneaking,
 * Sprint While Using and Keep When Idle. Those called {@code player.setSprinting(true)} directly,
 * which strips vanilla's own preconditions and emits a START_SPRINTING command the server can see
 * is impossible (sprinting backwards, on an empty hunger bar, while sneaking, or standing still).
 * This module presses the sprint key for you -- that is all it needs to do.
 * </ul>
 */
public class Sprint extends Module implements interstellarTikiTiks.apexx.client.module.hooks.ClickSimulating {

    private final ModeSetting mode = new ModeSetting("Mode", "Legit", "Legit", "Vanilla")
            .setDescription("Legit: presses the sprint key for you the way a player would, only when you start moving or lose sprint. Vanilla: simply keeps the sprint key held, like toggle-sprint in the game options.");
    private final interstellarTikiTiks.apexx.client.module.BooleanSetting disableInLiquids =
            new interstellarTikiTiks.apexx.client.module.BooleanSetting("Disable In Liquids", false)
                    .setDescription("Stop auto-sprinting while you are in water or lava: the sprint key is let go when you enter them and no taps are made until you are out.");

    // ---- Delays ----
    private final interstellarTikiTiks.apexx.client.module.SliderSetting tapInterval =
            new interstellarTikiTiks.apexx.client.module.SliderSetting("Tap Cooldown (ms)", 320, 0, 1000, 0)
                    .withJitter(40)
                    .setDescription("Minimum time between two automatic sprint taps, so sprint is not re-pressed in rapid bursts. Below about 100 ms the taps look machine-made.");

    private boolean wasMoving = false;
    private boolean wasSprinting = false;
    private boolean wantSprint = false;
    private long lastTapTime = 0L;

    public Sprint() {
        super("AutoSprint", "Keeps you sprinting whenever you move forward, so you never have to hold or double-tap the sprint key. Legit mode taps sprint like a player would and taps it again if sprint drops while you keep moving; Vanilla mode just holds it. Post-hit sprint resets belong to SmartWTap: while one runs, AutoSprint waits.", Category.MOVEMENT);
        tapInterval.setSection("Delays");
        addSetting(mode);
        addSetting(disableInLiquids);
        addSetting(tapInterval);
    }

    @Override
    public void onTick() {
        if (mc.player == null || mc.world == null || mc.options == null) {
            resetState();
            return;
        }

        // 1. Screens that capture movement (inventory, chests, etc.) must immediately release sprint.
        // Chat screens capture typing, so we must never fire simulated keypresses into chat.
        if (mc.currentScreen != null) {
            if (!isInWorldScreen(mc.currentScreen)) {
                releaseSprintKey();
                wasMoving = false;
                wasSprinting = false;
            }
            return;
        }

        // 1b. Disable In Liquids: let go of the key once on entry (the pending tap release is cancelled
        // too) and make no taps until the player is out. wasMoving is cleared so leaving the water
        // counts as starting to move again and sprint is re-tapped as usual.
        if (disableInLiquids.isEnabled()
                && interstellarTikiTiks.apexx.client.util.combat.MeleeMechanics.isInLiquid(mc.player)) {
            if (wasMoving || SystemInputSimulator.isSprintPendingRelease()) {
                releaseSprintKey();
            }
            wasMoving = false;
            wasSprinting = mc.player.isSprinting();
            wantSprint = false;
            return;
        }

        // 2. Compute whether the player is eligible to sprint right now.
        if (!canSprint()) {
            if ("Vanilla".equalsIgnoreCase(mode.getCurrentMode())) {
                mc.options.sprintKey.setPressed(false);
            }
            // Sprint begun while fed continues after hunger drops to the vanilla threshold; the server
            // rejects sprinting at <= 6 food (SprintA), so stop it the way a player releasing the key would.
            if (mc.player.isSprinting() && mc.player.getHungerManager().getFoodLevel() <= 6) {
                mc.player.setSprinting(false);
            }
            wasMoving = false;
            wasSprinting = mc.player.isSprinting();
            return;
        }

        // 3. Movement intent detection.
        boolean movingNow = hasMovementIntent();
        boolean currentlySprinting = mc.player.isSprinting();
        String currentMode = mode.getCurrentMode();

        // 4. Mode: Vanilla (Hold bound key)
        if ("Vanilla".equalsIgnoreCase(currentMode)) {
            mc.options.sprintKey.setPressed(movingNow);
            wasMoving = movingNow;
            wasSprinting = currentlySprinting;
            return;
        }

        // 5. Mode: Legit (Single-Tap on movement initiation, zero continuous spam)
        handleLegitTap(movingNow, currentlySprinting);
    }

    /**
     * Executes the authentic single-tap logic for Legit mode.
     * Only taps once when movement begins or when sprint is legitimately lost while moving,
     * suppressing all spam against walls or in rapid loops.
     */
    private void handleLegitTap(boolean movingNow, boolean currentlySprinting) {
        long now = System.currentTimeMillis();

        if (!movingNow) {
            // Stationary: ensure any pending release or keyhold is cancelled
            if (wasMoving) {
                releaseSprintKey();
            }
            wasMoving = false;
            wasSprinting = false;
            wantSprint = false;
            return;
        }

        // Detect rising edge: player just started moving forward from idle
        boolean startedMoving = !wasMoving;

        // Detect sprint lost while continuously moving (a hit, a collision). This only keeps sprint going;
        // a deliberate post-hit sprint reset is SmartWTap's job, so while its reset runs AutoSprint never
        // taps (the latched want is served once that reset has finished).
        boolean sprintLostWhileMoving = wasMoving && wasSprinting && !currentlySprinting;
        boolean resetInProgress = smartWTapResetting();

        // The want is latched until sprint actually comes back: a tap skipped by the cooldown, a wall,
        // or one vanilla ignored is retried instead of leaving the player walking until W is released.
        if (currentlySprinting) wantSprint = false;
        else if (startedMoving || sprintLostWhileMoving) wantSprint = true;

        if (wantSprint && !currentlySprinting && !resetInProgress) {
            // Anti-Spam Guard 1: Suppress tapping if player is walking straight into a solid wall.
            // In vanilla, running into a wall immediately kills sprinting every tick; tapping here would spam.
            // The want stays latched, so sprint is re-tapped once the wall is cleared.
            boolean blockedByWall = mc.player.horizontalCollision && mc.player.isOnGround();

            // Anti-Spam Guard 2: Enforce debounce cooldown between consecutive taps
            if (!blockedByWall && now - lastTapTime >= (long) tapInterval.sample()) {
                // Anti-Spam Guard 3: Do not double-tap if a keyhold pulse is already active
                if (!SystemInputSimulator.isSprintPendingRelease()) {
                    executeSingleSprintTap(now);
                }
            }
        }

        // While player is already sprinting, DO NOT touch or spam the sprint key.
        // It remains unpressed / released so the keystrokes HUD shows the real key state.
        if (currentlySprinting && !SystemInputSimulator.isSprintPendingRelease()) {
            mc.options.sprintKey.setPressed(false);
        }

        wasMoving = movingNow;
        wasSprinting = mc.player.isSprinting();
    }

    /**
     * Dispatches a single authentic tap on the dynamically resolved sprint key.
     * Uses whatever key or mouse button the user bound in Controls; falls back gracefully
     * to direct sprint state if unbound.
     */
    private void executeSingleSprintTap(long timestamp) {
        lastTapTime = timestamp;

        InputUtil.Key boundKey = getBoundSprintKey();
        if (boundKey != null) {
            // Authentic pulse: dispatches GLFW press on the bound key, increments timesPressed for
            // ToggleSprint wasPressed() support, and schedules release after 50-75ms natural hold.
            boolean dispatched = SystemInputSimulator.tapSprint();
            if (!dispatched && mc.player != null) {
                mc.player.setSprinting(true);
            }
        } else {
            // Unbound sprint keybind fallback: set sprint state directly without sending invalid keys
            if (mc.player != null) {
                mc.player.setSprinting(true);
            }
        }
    }

    /**
     * Dynamically finds the bound key for the sprint keybind in game options.
     * Respects keyboard keys (KEYSYM), mouse buttons (MOUSE), and scancodes.
     */
    public InputUtil.Key getBoundSprintKey() {
        if (mc.options == null || mc.options.sprintKey == null) return null;
        if (mc.options.sprintKey instanceof KeyBindingAccessor acc) {
            InputUtil.Key key = acc.getBoundKey();
            if (isUsable(key)) return key;
        }
        try {
            InputUtil.Key key = InputUtil.fromTranslationKey(mc.options.sprintKey.getBoundKeyTranslationKey());
            if (isUsable(key)) return key;
        } catch (Throwable ignored) {}
        return null;
    }

    private static boolean isUsable(InputUtil.Key key) {
        return key != null && key != InputUtil.UNKNOWN_KEY && key.getCode() >= 0;
    }

    /**
     * Verifies all conditions required for sprinting to be physically possible and safe.
     */
    private boolean canSprint() {
        if (mc.player == null) return false;
        if (!mc.player.isAlive()) return false;
        if (mc.player.hasVehicle()) return false;
        if (mc.player.isGliding() || mc.player.getAbilities().flying) return false;

        // Blindness prevents sprinting in vanilla
        if (mc.player.hasStatusEffect(StatusEffects.BLINDNESS)) {
            return false;
        }

        // Every vanilla precondition, enforced rather than skipped. Pressing the key when vanilla
        // would refuse anyway just produces pointless keypresses, so bail on all of them.
        if (mc.player.getHungerManager().getFoodLevel() <= 6) {
            return false;
        }
        if (mc.player.isSneaking()) {
            return false;
        }
        if (isUsingOrPlacing()) {
            return false;
        }

        return true;
    }

    /** Forward input only -- vanilla sprints on forward movement and nothing else. */
    private boolean hasMovementIntent() {
        if (mc.player == null || mc.player.input == null) return false;
        return mc.player.input.hasForwardMovement()
                || (mc.options != null && mc.options.forwardKey.isPressed());
    }

    private boolean isUsingOrPlacing() {
        if (mc.player == null) return false;
        return mc.player.isUsingItem()
                || (mc.options != null && mc.options.useKey.isPressed())
                || ((MinecraftClientAccessor) mc).getItemUseCooldown() > 0;
    }

    /** A SmartWTap sprint reset is planned or running: it owns the sprint/forward input until it ends. */
    private static boolean smartWTapResetting() {
        SmartWTap wtap = ModuleAccess.getEnabledModule(SmartWTap.class);
        return wtap != null && wtap.isResetActive();
    }

    private boolean isInWorldScreen(Screen screen) {
        return screen instanceof ChatScreen
                || screen instanceof GpuClickGuiScreen
                || screen instanceof interstellarTikiTiks.apexx.client.gui.mobile.MobileClickGuiScreen
                || screen instanceof HudEditorScreen;
    }

    private void releaseSprintKey() {
        SystemInputSimulator.releaseSprint();
        if (mc.options != null && mc.options.sprintKey != null) {
            mc.options.sprintKey.setPressed(false);
        }
    }

    private void resetState() {
        wasMoving = false;
        wasSprinting = false;
        wantSprint = false;
        lastTapTime = 0L;
    }

    @Override
    public void onDisable() {
        releaseSprintKey();
        resetState();
    }

    @Override
    protected void onRuntimeStateReleased() {
        releaseSprintKey();
        resetState();
    }
}
