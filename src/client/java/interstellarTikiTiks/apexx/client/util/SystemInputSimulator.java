package interstellarTikiTiks.apexx.client.util;

import interstellarTikiTiks.apexx.client.mixin.KeyBindingAccessor;
import interstellarTikiTiks.apexx.client.mixin.KeyboardCallbackAccessor;
import interstellarTikiTiks.apexx.client.mixin.MouseCallbackAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.input.MouseInput;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

import interstellarTikiTiks.apexx.client.util.combat.HumanProfile;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import interstellarTikiTiks.apexx.client.util.combat.SlotSync;

public final class SystemInputSimulator {

    /**
     * Mouse buttons and keyboard keys both use small integer codes, so they must not
     * share the same pending-release namespace. In particular, the old mouse encoding
     * mapped left click (button 0) to -1, which is also Minecraft's UNKNOWN_KEY code.
     * That made every unbound keybinding appear pressed during an automated attack.
     */
    private static final int MOUSE_ID_BASE = -1024;

    // Action button / keyboard key hold duration (e.g. movement, hotbar, jump, sneak, sprint).
    // Can be long (50 - 95 ms) so that it reliably registers on HUDs, Keystroke mods,
    // and anti-cheat movement checks.
    private static final double KEY_HOLD_MIN_MS = 50.0;
    private static final double KEY_HOLD_MAX_MS = 95.0;

    // Mouse click hold duration (Left click / Attack, Right click / Use, middle click, etc.).
    // Fast human clicks (butterfly / jitter / quick tap) are very short (12 - 25 ms),
    // strictly less than 1 game tick (50 ms), so that single clicks never hold across ticks
    // or trigger repetitive item usage (e.g. re-equipping armor / chestplate during takeoff).
    private static final double CLICK_HOLD_MIN_MS = 15.0;
    private static final double CLICK_HOLD_MAX_MS = 28.0;

    public static long nextKeyHoldNanos() {
        return HumanProfile.clickHoldMs(KEY_HOLD_MIN_MS, KEY_HOLD_MAX_MS) * 1_000_000L
                + ThreadLocalRandom.current().nextLong(1_000_000L);
    }

    public static long nextClickHoldNanos() {
        return HumanProfile.clickHoldMs(CLICK_HOLD_MIN_MS, CLICK_HOLD_MAX_MS) * 1_000_000L
                + ThreadLocalRandom.current().nextLong(1_000_000L);
    }

    public static long nextHoldNanos() {
        return nextKeyHoldNanos();
    }

    public static boolean isMouseClick(KeyBinding binding, InputUtil.Key key) {
        MinecraftClient mc = mc();
        if (mc != null && mc.options != null) {
            if (binding == mc.options.attackKey || binding == mc.options.useKey || binding == mc.options.pickItemKey) {
                return true;
            }
        }
        return key != null && key.getCategory() == InputUtil.Type.MOUSE;
    }

    private static final Object INPUT_LOCK = new Object();
    private static final Object LEGACY_OWNER = new Object();
    private static final long HELD_UNTIL_RELEASED = Long.MAX_VALUE;

    /**
     * Simulated holds grouped by physical input, then by module identity. One module
     * releasing Attack/Use must not cancel another module's still-active hold.
     */
    private static final Map<Integer, IdentityHashMap<Object, Long>> pendingReleases = new HashMap<>();
    // A normal input that sent no GLFW press must not generate a synthetic GLFW release.
    private static final Set<Integer> callbackPressedInputs = new HashSet<>();

    // Display pulses are separate from actionable key taps: no pending slot-selection edges.
    private static final Map<Integer, HotbarPulse> hotbarPulses = new HashMap<>();

    private record HotbarPulse(Object owner, Object player, Object world, long releaseAt,
                               boolean callback, boolean feedbackOnly, int tick) {}
    private static volatile boolean clientTickActive;

    public static void beginClientTick() { clientTickActive = true; }
    public static void endClientTick() { clientTickActive = false; }

    private static volatile long suppressVanillaAttackUntil = 0L;
    private static volatile long suppressVanillaUseUntil = 0L;

    public static void suppressNextVanillaAttack() {
        suppressVanillaAttackUntil = System.currentTimeMillis() + 150L;
    }

    public static boolean shouldSuppressVanillaAttack() {
        long now = System.currentTimeMillis();
        if (now < suppressVanillaAttackUntil) {
            suppressVanillaAttackUntil = 0L;
            return true;
        }
        return false;
    }

    public static void suppressNextVanillaUse() {
        suppressVanillaUseUntil = System.currentTimeMillis() + 150L;
    }

    public static boolean shouldSuppressVanillaUse() {
        long now = System.currentTimeMillis();
        if (now < suppressVanillaUseUntil) {
            suppressVanillaUseUntil = 0L;
            return true;
        }
        return false;
    }

    private static double cachedCursorX, cachedCursorY;

    private SystemInputSimulator() {}

    public static void pressAttack()   {
        MinecraftClient mc = mc();
        if (mc == null || !SlotSync.ensureSelectionForCurrentOwner(mc)) return;
        withOptions(o -> pressBinding(o.attackKey));
    }
    public static void releaseAttack() { withOptions(o -> cancelAndRelease(o.attackKey)); }

    /**
     * True when a world attack/use must not be dispatched because a screen is open.
     *
     * <p>These two methods raise real GLFW mouse events. With a screen open Minecraft
     * routes those to the screen, so a module sequence still running after the player
     * opens their inventory does not simply lose its click -- it clicks <em>inside the
     * inventory</em> and moves items around.
     *
     * <p>The guard lives here, at the lowest layer, rather than only in
     * {@link ClickSimController}: several modules (AutoCrystal, AutoShieldBreak,
     * HitCrystal) call {@code tapAttack}/{@code tapUse} directly and would have bypassed
     * a guard placed one level up. ClickSimController delegates here, so it is covered too.
     *
     * <p>Scoped deliberately to attack/use. Hotbar switching, {@code tapInventory} and
     * {@code clickSlot} are legitimately used <em>with</em> a screen open -- AutoTotem's
     * inventory swap depends on exactly that -- and are untouched.
     */
    private static boolean screenBlocksWorldInput(MinecraftClient mc) {
        return mc == null || mc.options == null || mc.currentScreen != null;
    }

    public static boolean tapAttack() {
        MinecraftClient mc = mc();
        if (screenBlocksWorldInput(mc)) {
            return false;
        }
        if (!SlotSync.ensureSelectionForCurrentOwner(mc)) {
            return false;
        }
        tapBinding(mc.options.attackKey);
        InputPulseManager.recordAttackPulse();
        return true;
    }

    public static boolean tapAttackSilent() {
        MinecraftClient mc = mc();
        if (screenBlocksWorldInput(mc)) return false;
        if (!SlotSync.ensureSelectionForCurrentOwner(mc)) return false;
        if (ClickSimController.isEnabled()) {
            showModuleClick(GLFW.GLFW_MOUSE_BUTTON_LEFT);
            InputPulseManager.recordAttackPulse();
            return true;
        }
        suppressNextVanillaAttack();
        tapBinding(mc.options.attackKey);
        InputPulseManager.recordAttackPulse();
        return true;
    }

    public static void pressUse()   {
        MinecraftClient mc = mc();
        if (mc == null || !SlotSync.ensureSelectionForCurrentOwner(mc)) return;
        withOptions(o -> pressBinding(o.useKey));
    }
    public static void releaseUse() { withOptions(o -> cancelAndRelease(o.useKey)); }

    public static boolean tapUse() {
        MinecraftClient mc = mc();
        if (screenBlocksWorldInput(mc)) {
            return false;
        }
        if (!SlotSync.ensureSelectionForCurrentOwner(mc)) {
            return false;
        }
        tapBinding(mc.options.useKey);
        InputPulseManager.recordUsePulse();
        return true;
    }

    public static boolean tapUseSilent() {
        MinecraftClient mc = mc();
        if (screenBlocksWorldInput(mc)) return false;
        if (!SlotSync.ensureSelectionForCurrentOwner(mc)) return false;
        if (ClickSimController.isEnabled()) {
            showModuleClick(GLFW.GLFW_MOUSE_BUTTON_RIGHT);
            InputPulseManager.recordUsePulse();
            return true;
        }
        suppressNextVanillaUse();
        tapBinding(mc.options.useKey);
        InputPulseManager.recordUsePulse();
        return true;
    }

    public static void pressJump() {
        withOptions(o -> pressBinding(o.jumpKey));
        InputPulseManager.recordJumpPulse();
    }
    public static void releaseJump() { withOptions(o -> cancelAndRelease(o.jumpKey)); }

    public static boolean tapJump() {
        MinecraftClient mc = mc();
        if (mc == null || mc.options == null) return false;
        tapBinding(mc.options.jumpKey);
        InputPulseManager.recordJumpPulse();
        return true;
    }

    public static void pressForward()   { withOptions(o -> pressBinding(o.forwardKey)); }
    public static void releaseForward() { withOptions(o -> cancelAndRelease(o.forwardKey)); }
    public static void pressBack()      { withOptions(o -> pressBinding(o.backKey)); }
    public static void releaseBack()    { withOptions(o -> cancelAndRelease(o.backKey)); }

    public static void pressLeft()   { withOptions(o -> pressBinding(o.leftKey)); }
    public static void releaseLeft() { withOptions(o -> cancelAndRelease(o.leftKey)); }
    public static void pressRight()   { withOptions(o -> pressBinding(o.rightKey)); }
    public static void releaseRight() { withOptions(o -> cancelAndRelease(o.rightKey)); }

    public static void pressSneak()   { withOptions(o -> pressBinding(o.sneakKey)); }
    public static void releaseSneak() { withOptions(o -> cancelAndRelease(o.sneakKey)); }

    public static void pressSprint()   { withOptions(o -> pressBinding(o.sprintKey)); }
    public static void releaseSprint() { withOptions(o -> cancelAndRelease(o.sprintKey)); }

    public static boolean tapSprint() {
        MinecraftClient mc = mc();
        if (mc == null || mc.options == null) return false;
        InputUtil.Key key = boundKey(mc.options.sprintKey);
        if (key == null) return false;
        tapBinding(mc.options.sprintKey);
        return true;
    }

    public static boolean isSprintPendingRelease() {
        MinecraftClient mc = mc();
        if (mc == null || mc.options == null) return false;
        return isSimulatedPressed(mc.options.sprintKey);
    }

    public static InputUtil.Key getBoundSprintKey() {
        MinecraftClient mc = mc();
        if (mc == null || mc.options == null) return null;
        return boundKey(mc.options.sprintKey);
    }

    public static InputUtil.Key getBoundKey(KeyBinding binding) {
        return boundKey(binding);
    }

    public static void tapDrop() {
        MinecraftClient mc = mc();
        if (mc == null || mc.options == null) return;
        tapBinding(mc.options.dropKey);
    }

    public static void tapSwapHands() {
        MinecraftClient mc = mc();
        if (mc == null || mc.options == null) return;
        // AutoTotem's offhand guard: another module may not swap a non-totem over a protected offhand totem.
        if (mc.player != null && interstellarTikiTiks.apexx.client.util.combat.OffhandGuard.deniesSwapKey(
                interstellarTikiTiks.apexx.client.util.combat.OffhandGuard.blocksActor(ModuleExecutionContext.currentOwner()),
                mc.player.getOffHandStack().isOf(net.minecraft.item.Items.TOTEM_OF_UNDYING),
                mc.player.getMainHandStack().isOf(net.minecraft.item.Items.TOTEM_OF_UNDYING))) {
            return;
        }
        tapBinding(mc.options.swapHandsKey);
    }

    public static void tapInventory() {
        MinecraftClient mc = mc();
        if (mc == null || mc.options == null) return;
        tapBinding(mc.options.inventoryKey);
    }

    public static void tapPickBlock() {
        MinecraftClient mc = mc();
        if (mc == null || mc.options == null) return;
        tapBinding(mc.options.pickItemKey);
    }

    public static void press(KeyBinding binding) { pressBinding(binding); }

    public static void release(KeyBinding binding) { cancelAndRelease(binding); }

    public static void setKey(KeyBinding binding, boolean pressed) {
        if (pressed) pressBinding(binding);
        else cancelAndRelease(binding);
    }

    public static void tap(KeyBinding binding) { tapBinding(binding); }

    public static void pulse(KeyBinding binding) {
        if (ClickSimController.isEnabled()) tapBinding(binding);
        else pulseBinding(binding);
    }

    public static void pressKey(int glfwKeyCode) {
        InputUtil.Key key = InputUtil.Type.KEYSYM.createFromCode(glfwKeyCode);
        if (!isUsableKey(key)) return;
        claimInput(encodeId(key), HELD_UNTIL_RELEASED);
        dispatchEvent(key, GLFW.GLFW_PRESS);
    }

    public static void releaseKey(int glfwKeyCode) {
        InputUtil.Key key = InputUtil.Type.KEYSYM.createFromCode(glfwKeyCode);
        if (!isUsableKey(key)) return;
        releaseInput(encodeId(key), currentInputOwner());
    }

    public static void tapKey(int glfwKeyCode) {
        InputUtil.Key key = InputUtil.Type.KEYSYM.createFromCode(glfwKeyCode);
        if (!isUsableKey(key)) return;
        int id = encodeId(key);
        dispatchEvent(key, GLFW.GLFW_PRESS);
        claimInput(id, System.nanoTime() + nextKeyHoldNanos());
    }

    public static void pressMouseButton(int button) {
        InputUtil.Key key = InputUtil.Type.MOUSE.createFromCode(button);
        if (!isUsableKey(key)) return;
        claimInput(encodeId(key), HELD_UNTIL_RELEASED);
        dispatchEvent(key, GLFW.GLFW_PRESS);
    }

    public static void releaseMouseButton(int button) {
        InputUtil.Key key = InputUtil.Type.MOUSE.createFromCode(button);
        if (!isUsableKey(key)) return;
        releaseInput(encodeId(key), currentInputOwner());
    }

    public static void tapMouseButton(int button) {
        InputUtil.Key key = InputUtil.Type.MOUSE.createFromCode(button);
        if (!isUsableKey(key)) return;
        int id = encodeId(key);
        dispatchEvent(key, GLFW.GLFW_PRESS);
        claimInput(id, System.nanoTime() + nextClickHoldNanos());
    }

    public static void tapHotbarSlot(int slot) {
        swapToSlotLegit(slot);
    }

    /** Sends only the bound number-key edge. Vanilla owns the later slot change. */
    public static void tapHotbarSlotPhysical(int slot) {
        if (slot < 0 || slot >= 9) return;
        MinecraftClient mc = mc();
        if (mc == null || mc.options == null || mc.options.hotbarKeys == null) return;
        KeyBinding binding = mc.options.hotbarKeys[slot];
        if (binding != null) tapBinding(binding);
    }

    public static void setHotbarSlot(int slot) {
        swapToSlotLegit(slot);
    }

    /** Uses the same selection/synchronization seam as modules; the inventory hook displays it. */
    public static void swapToSlotLegit(int slot) {
        SlotSync.selectNow(mc(), slot);
    }

    /** Brief display feedback only; never queues a second vanilla slot selection. */
    public static void showHotbarSelection(int slot) {
        MinecraftClient mc = mc();
        if (!ClickSimController.isEnabled()
                || mc == null || !mc.isOnThread() || mc.player == null || mc.world == null
                || mc.currentScreen != null || !mc.isWindowFocused() || mc.options == null
                || mc.options.hotbarKeys == null || slot < 0 || slot >= mc.options.hotbarKeys.length) return;
        KeyBinding binding = mc.options.hotbarKeys[slot];
        InputUtil.Key key = boundKey(binding);
        if (key == null) return;

        // Log-normal holds are usually about 60ms, bounded to 35-110ms. They never pace actions.
        long holdNanos = (35L + Math.min(75L, HumanProfile.logNormalMs(25.0, 0.65))) * 1_000_000L;
        // Only ordinary number keys go through onKey: rebound function/mouse keys can run
        // immediate vanilla actions. Rebound keys still appear through KeyBinding/InputUtil.
        boolean callback = key.getCategory() == InputUtil.Type.KEYSYM
                && key.getCode() >= GLFW.GLFW_KEY_1 && key.getCode() <= GLFW.GLFW_KEY_9
                && !isPhysicallyPressed(binding);
        synchronized (INPUT_LOCK) {
            HotbarPulse previous = hotbarPulses.get(encodeId(key));
            if (previous != null && previous.feedbackOnly()) {
                hotbarPulses.remove(encodeId(key));
                releaseDisplayPulse(encodeId(key), previous);
                previous = null;
            }
            hotbarPulses.put(encodeId(key), new HotbarPulse(currentInputOwner(), mc.player, mc.world,
                    System.nanoTime() + holdNanos, callback || (previous != null && previous.callback()), false, mc.player.age));
        }
        if (callback) {
            // Keyboard callbacks increment wasPressed counters. Preserve every binding's count
            // so a later tick cannot replay an older axe/pearl/restore slot after a newer swap.
            KeyBinding[] bindings = mc.options.allKeys;
            int[] counts = new int[bindings.length];
            for (int i = 0; i < bindings.length; i++) {
                if (bindings[i] instanceof KeyBindingAccessor acc) counts[i] = acc.getTimesPressed();
            }
            try {
                dispatchEvent(key, GLFW.GLFW_PRESS);
            } finally {
                for (int i = 0; i < bindings.length; i++) {
                    if (bindings[i] instanceof KeyBindingAccessor acc) acc.setTimesPressed(counts[i]);
                }
            }
        }
        binding.setPressed(true);
    }

    /** Covers direct-target, placement, item-use and inventory endpoints without replaying actions. */
    public static void showModuleClick(int button) {
        showModuleClick(button, false);
    }

    public static void showModuleClick(int button, boolean newClick) {
        MinecraftClient mc = mc();
        Object owner = ModuleExecutionContext.currentOwner();
        if (!ClickSimController.isEnabledFor(owner) || mc == null || !mc.isOnThread()
                || mc.player == null || mc.world == null || !mc.isWindowFocused()
                || mc.options == null || mc.getWindow() == null || mc.getWindow().getHandle() == 0L
                || button < 0 || button > GLFW.GLFW_MOUSE_BUTTON_MIDDLE) return;
        int id = mouseId(button);
        if (GLFW.glfwGetMouseButton(mc.getWindow().getHandle(), button) == GLFW.GLFW_PRESS) return;
        long now = System.nanoTime();
        synchronized (INPUT_LOCK) {
            // A normal simulated tap already sent the callback. Main/offhand and
            // block/item fallbacks within the same click also share one visible hold.
            if (pendingReleases.containsKey(id)) return;
            HotbarPulse previous = hotbarPulses.get(id);
            if (previous != null && now < previous.releaseAt()
                    && (!previous.feedbackOnly() || (!newClick && previous.owner() == owner
                    && previous.tick() == mc.player.age))) return;
            if (previous != null) {
                hotbarPulses.remove(id);
                releaseDisplayPulse(id, previous);
            }
            // Display-only holds can span a tick without repeating attack/use.
            long hold = (35L + Math.min(60L, HumanProfile.logNormalMs(20.0, 0.65))) * 1_000_000L;
            hotbarPulses.put(id, new HotbarPulse(owner, mc.player, mc.world, now + hold, true, true, mc.player.age));
            dispatchFeedbackMouse(button, GLFW.GLFW_PRESS);
        }
    }

    static void dispatchFeedbackMouse(int button, int action) {
        MinecraftClient mc = mc();
        if (mc == null || mc.mouse == null || mc.getWindow() == null
                || mc.getWindow().getHandle() == 0L) return;
        try {
            InputTracker.runFeedbackCallback(() -> ((MouseCallbackAccessor) mc.mouse)
                    .invokeOnMouseButton(mc.getWindow().getHandle(), new MouseInput(button, 0), action));
        } catch (RuntimeException | LinkageError ignored) {
            // Overlay feedback must not cancel the original validated interaction if
            // another mod replaces or rejects the mouse callback. Polling still sees the hold.
        }
    }

    private static void releaseDisplayPulse(int id, HotbarPulse pulse) {
        if (!pulse.feedbackOnly()) {
            dispatchRelease(id, pulse.callback());
            return;
        }
        // Feedback never changes a real binding or its wasPressed counter.
        // Leave a newly pressed physical button in its real down state.
        synchronized (INPUT_LOCK) {
            if (pendingReleases.containsKey(id)) return;
        }
        MinecraftClient mc = mc();
        int button = MOUSE_ID_BASE - id;
        if (mc != null && mc.getWindow() != null && mc.getWindow().getHandle() != 0L
                && GLFW.glfwGetMouseButton(mc.getWindow().getHandle(), button) != GLFW.GLFW_PRESS) {
            dispatchFeedbackMouse(button, GLFW.GLFW_RELEASE);
        }
    }

    public static void releaseHotbarPulses() {
        synchronized (INPUT_LOCK) {
            Map<Integer, HotbarPulse> released = new HashMap<>(hotbarPulses);
            hotbarPulses.clear();
            released.forEach(SystemInputSimulator::releaseDisplayPulse);
        }
    }

    public static void clickSlot(int slotId, int button,
                                 net.minecraft.screen.slot.SlotActionType actionType) {
        MinecraftClient mc = mc();
        if (mc == null || mc.interactionManager == null || mc.player == null) return;
        if (mc.player.currentScreenHandler == null) return;
        int syncId = mc.player.currentScreenHandler.syncId;
        if (interstellarTikiTiks.apexx.client.util.combat.SubTickSequencer.isVariationEnabled()) {
            interstellarTikiTiks.apexx.client.util.combat.SubTickSequencer.queueContainerClick(mc, syncId, slotId, button, actionType);
        } else {
            clickSlotNow(slotId, button, actionType);
        }
    }

    /**
     * Immediate click for step-per-tick sequences whose next step reads the click's result, so it
     * must not sit in the sequencer queue. Only clicks while the container the caller planned against
     * ({@code syncId}) is still the open one; returns false without clicking otherwise.
     */
    public static boolean clickSlotNow(int syncId, int slotId, int button,
                                       net.minecraft.screen.slot.SlotActionType actionType) {
        MinecraftClient mc = mc();
        if (mc == null || mc.interactionManager == null || mc.player == null) return false;
        if (mc.player.currentScreenHandler == null || mc.player.currentScreenHandler.syncId != syncId) return false;
        if (offhandGuardDenies(mc, slotId, button, actionType)) return false;
        clickSlotNow(slotId, button, actionType);
        return true;
    }

    /** Apexx alias used by the inventory modules: clicks only while {@code expectedSyncId} is the open handler. */
    public static boolean clickSlotBound(int expectedSyncId, int slotId, int button,
                                         net.minecraft.screen.slot.SlotActionType actionType) {
        return clickSlotNow(expectedSyncId, slotId, button, actionType);
    }

    public static boolean clickSlotBoundNow(int expectedSyncId, int slotId, int button,
                                            net.minecraft.screen.slot.SlotActionType actionType) {
        return clickSlotNow(expectedSyncId, slotId, button, actionType);
    }

    public static void clickSlotNow(int slotId, int button,
                                    net.minecraft.screen.slot.SlotActionType actionType) {
        MinecraftClient mc = mc();
        if (mc == null || mc.interactionManager == null || mc.player == null) return;
        if (mc.player.currentScreenHandler == null) return;
        if (offhandGuardDenies(mc, slotId, button, actionType)) return;
        int syncId = mc.player.currentScreenHandler.syncId;
        interstellarTikiTiks.apexx.client.util.combat.SubTickSequencer.recordContainerClick(syncId);
        mc.interactionManager.clickSlot(
                syncId, slotId, button, actionType, mc.player);
    }

    /** AutoTotem's offhand guard for a window click sent by another module (see OffhandGuard). */
    private static boolean offhandGuardDenies(MinecraftClient mc, int slotId, int button,
                                              net.minecraft.screen.slot.SlotActionType actionType) {
        if (!interstellarTikiTiks.apexx.client.util.combat.OffhandGuard.blocksActor(ModuleExecutionContext.currentOwner())
                || mc.player == null || mc.player.currentScreenHandler == null) {
            return false;
        }
        net.minecraft.screen.ScreenHandler handler = mc.player.currentScreenHandler;
        boolean sourceTotem = slotId >= 0 && slotId < handler.slots.size()
                && handler.getSlot(slotId).getStack().isOf(net.minecraft.item.Items.TOTEM_OF_UNDYING);
        return interstellarTikiTiks.apexx.client.util.combat.OffhandGuard.deniesWindowClick(true,
                mc.player.getOffHandStack().isOf(net.minecraft.item.Items.TOTEM_OF_UNDYING), slotId, button,
                actionType == net.minecraft.screen.slot.SlotActionType.SWAP, sourceTotem,
                handler == mc.player.playerScreenHandler);
    }

    public static void pickupSlot(int slotId) {
        clickSlot(slotId, 0, net.minecraft.screen.slot.SlotActionType.PICKUP);
    }

    public static void pickupSlotRight(int slotId) {
        clickSlot(slotId, 1, net.minecraft.screen.slot.SlotActionType.PICKUP);
    }

    public static void quickMoveSlot(int slotId) {
        clickSlot(slotId, 0, net.minecraft.screen.slot.SlotActionType.QUICK_MOVE);
    }

    public static void swapSlot(int slotId, int hotbarSlot) {
        clickSlot(slotId, hotbarSlot, net.minecraft.screen.slot.SlotActionType.SWAP);
    }

    public static void throwSlot(int slotId) {
        clickSlot(slotId, 0, net.minecraft.screen.slot.SlotActionType.THROW);
    }

    public static void throwSlotAll(int slotId) {
        clickSlot(slotId, 1, net.minecraft.screen.slot.SlotActionType.THROW);
    }

    public static void processPendingReleases() {
        long now = System.nanoTime();
        synchronized (INPUT_LOCK) {
            if (pendingReleases.isEmpty() && hotbarPulses.isEmpty()) return;
            List<Integer> fullyReleased = new ArrayList<>();
            var inputIterator = pendingReleases.entrySet().iterator();
            while (inputIterator.hasNext()) {
                Map.Entry<Integer, IdentityHashMap<Object, Long>> input = inputIterator.next();
                input.getValue().entrySet().removeIf(owner -> owner.getValue() != HELD_UNTIL_RELEASED
                        && now >= owner.getValue());
                if (input.getValue().isEmpty()) {
                    fullyReleased.add(input.getKey());
                    inputIterator.remove();
                }
            }
            fullyReleased.forEach(SystemInputSimulator::dispatchRelease);
            MinecraftClient mc = mc();
            boolean clear = mc == null || mc.player == null || mc.world == null
                    || !mc.isWindowFocused();
            var pulses = hotbarPulses.entrySet().iterator();
            while (pulses.hasNext()) {
                var entry = pulses.next();
                HotbarPulse pulse = entry.getValue();
                if (clear || !ClickSimController.isEnabledFor(pulse.owner())
                        || (!pulse.feedbackOnly() && mc.currentScreen != null)
                        || pulse.player() != mc.player || pulse.world() != mc.world || now >= pulse.releaseAt()) {
                    pulses.remove();
                    releaseDisplayPulse(entry.getKey(), pulse);
                }
            }
        }
    }

    /** Releases only input owned by this module, preserving holds from other modules. */
    public static void releaseOwner(Object owner) {
        if (owner == null) return;
        synchronized (INPUT_LOCK) {
            List<Integer> fullyReleased = new ArrayList<>();
            var inputIterator = pendingReleases.entrySet().iterator();
            while (inputIterator.hasNext()) {
                Map.Entry<Integer, IdentityHashMap<Object, Long>> input = inputIterator.next();
                input.getValue().remove(owner);
                if (input.getValue().isEmpty()) {
                    fullyReleased.add(input.getKey());
                    inputIterator.remove();
                }
            }
            var pulses = hotbarPulses.entrySet().iterator();
            while (pulses.hasNext()) {
                var entry = pulses.next();
                if (entry.getValue().owner() == owner) {
                    HotbarPulse pulse = entry.getValue();
                    pulses.remove();
                    releaseDisplayPulse(entry.getKey(), pulse);
                }
            }
            fullyReleased.forEach(SystemInputSimulator::dispatchRelease);
        }
    }

    public static void releaseAll() {
        synchronized (INPUT_LOCK) {
            List<Integer> inputs = new ArrayList<>(pendingReleases.keySet());
            pendingReleases.clear();
            inputs.forEach(SystemInputSimulator::dispatchRelease);
            releaseHotbarPulses();
        }
    }

    public static boolean hasPendingReleases() {
        synchronized (INPUT_LOCK) {
            return !pendingReleases.isEmpty() || !hotbarPulses.isEmpty();
        }
    }

    public static double[] getScaledCursorPos(int scaledWidth, int scaledHeight) {
        MinecraftClient mc = mc();
        if (mc == null || mc.getWindow() == null || scaledWidth <= 0 || scaledHeight <= 0)
            return new double[]{0.0, 0.0};
        getCursorPos(mc);
        int ww = Math.max(1, mc.getWindow().getWidth());
        int wh = Math.max(1, mc.getWindow().getHeight());
        return new double[]{
                cachedCursorX * ((double) scaledWidth / ww),
                cachedCursorY * ((double) scaledHeight / wh)
        };
    }

    public static double getScaledCursorX(int scaledWidth) {
        MinecraftClient mc = mc();
        if (mc == null || mc.getWindow() == null || scaledWidth <= 0) return 0.0;
        getCursorPos(mc);
        return cachedCursorX * ((double) scaledWidth / Math.max(1, mc.getWindow().getWidth()));
    }

    public static double getScaledCursorY(int scaledHeight) {
        MinecraftClient mc = mc();
        if (mc == null || mc.getWindow() == null || scaledHeight <= 0) return 0.0;
        getCursorPos(mc);
        return cachedCursorY * ((double) scaledHeight / Math.max(1, mc.getWindow().getHeight()));
    }

    public static boolean moveCursorToScaled(
            int scaledWidth, int scaledHeight,
            double targetScaledX, double targetScaledY,
            double speedPerTick, String mode) {
        MinecraftClient mc = mc();
        if (mc == null || mc.getWindow() == null || scaledWidth <= 0 || scaledHeight <= 0) return false;
        // Wayland does not let an application warp the pointer: behave like the "Instant" mode with no visible
        // glide (report arrival) instead of calling glfwSetCursorPos, which only raises an error there.
        if (GlfwPlatform.isWayland()) return true;

        long handle = mc.getWindow().getHandle();
        int windowWidth  = Math.max(1, mc.getWindow().getWidth());
        int windowHeight = Math.max(1, mc.getWindow().getHeight());

        double targetWindowX = targetScaledX * ((double) windowWidth  / scaledWidth);
        double targetWindowY = targetScaledY * ((double) windowHeight / scaledHeight);

        getCursorPos(mc);
        double dx   = targetWindowX - cachedCursorX;
        double dy   = targetWindowY - cachedCursorY;
        double dist = Math.sqrt(dx * dx + dy * dy);
        if (dist <= 1.0) {
            GLFW.glfwSetCursorPos(handle, targetWindowX, targetWindowY);
            return true;
        }

        String resolvedMode = (mode == null) ? "Smooth" : mode;
        if ("Instant".equalsIgnoreCase(resolvedMode)) {
            GLFW.glfwSetCursorPos(handle, targetWindowX, targetWindowY);
            return true;
        }

        double speed = Math.max(1.0, speedPerTick);
        if ("Heuristic".equalsIgnoreCase(resolvedMode)) {
            if      (dist > 200.0) speed *= 2.3;
            else if (dist > 90.0)  speed *= 1.7;
            else if (dist > 40.0)  speed *= 1.25;
        } else if ("EaseOut".equalsIgnoreCase(resolvedMode)) {
            double factor = Math.min(0.9, speed / 50.0);
            GLFW.glfwSetCursorPos(handle,
                    cachedCursorX + dx * factor,
                    cachedCursorY + dy * factor);
            return false;
        }

        double step = Math.min(dist, speed);
        GLFW.glfwSetCursorPos(handle,
                cachedCursorX + (dx / dist) * step,
                cachedCursorY + (dy / dist) * step);
        return false;
    }

    public static boolean isUsingNative() {
        return false;
    }
    
    public static boolean isPhysicallyPressed(KeyBinding binding) {
        InputUtil.Key key = boundKey(binding);
        if (key == null) return false;
        MinecraftClient mc = mc();
        if (mc == null || mc.getWindow() == null || mc.getWindow().getHandle() == 0L) return false;
        
        if (key.getCategory() == InputUtil.Type.MOUSE) {
            return GLFW.glfwGetMouseButton(mc.getWindow().getHandle(), key.getCode()) == GLFW.GLFW_PRESS;
        } else {
            return GLFW.glfwGetKey(mc.getWindow().getHandle(), key.getCode()) == GLFW.GLFW_PRESS;
        }
    }

    /**
     * Restores a binding to the current GLFW state without creating a simulated hold.
     *
     * <p>Movement assists use this after temporarily suppressing a real key. Calling
     * {@link #press(KeyBinding)} here would create an indefinitely-held synthetic input,
     * which can survive the user's physical key release.</p>
     */
    public static void restorePhysicalState(KeyBinding binding) {
        InputUtil.Key key = boundKey(binding);
        if (!isUsableKey(key)) return;

        boolean physicallyHeld = isPhysicallyPressed(binding);
        binding.setPressed(physicallyHeld);
        KeyBinding.setKeyPressed(key, physicallyHeld);
        if (key.getCategory() == InputUtil.Type.MOUSE) {
            InputTracker.setMouseButtonState(key.getCode(), physicallyHeld);
        } else if (key.getCategory() == InputUtil.Type.KEYSYM) {
            InputTracker.setKeyState(key.getCode(), physicallyHeld);
        }
    }

    public static boolean isSimulatedPressed(KeyBinding binding) {
        InputUtil.Key key = boundKey(binding);
        if (!isUsableKey(key)) return false;
        return hasVisibleInput(encodeId(key));
    }

    public static boolean isBindingSimulatedPressed(KeyBinding binding) {
        return isSimulatedPressed(binding);
    }

    public static boolean isMouseButtonSimulatedPressed(int button) {
        return button >= 0 && hasVisibleInput(mouseId(button));
    }

    public static boolean isKeySimulatedPressed(int glfwKeyCode) {
        if (glfwKeyCode >= 0 && glfwKeyCode <= GLFW.GLFW_MOUSE_BUTTON_LAST) {
            return isMouseButtonSimulatedPressed(glfwKeyCode);
        }
        return glfwKeyCode >= GLFW.GLFW_KEY_SPACE && hasVisibleInput(glfwKeyCode);
    }

    private static void pressBinding(KeyBinding binding) {
        InputUtil.Key key = boundKey(binding);
        if (key == null) return;
        claimInput(encodeId(key), HELD_UNTIL_RELEASED);
        dispatchEvent(key, GLFW.GLFW_PRESS);
        binding.setPressed(true);
        if (binding instanceof KeyBindingAccessor acc) {
            acc.setTimesPressed(Math.max(acc.getTimesPressed() + 1, 1));
        }
        // Simulated input must not register as physical: the tracker ignores writes made inside a synthetic callback.
        InputTracker.runSyntheticCallback(() -> {
            if (key.getCategory() == InputUtil.Type.MOUSE) {
                InputTracker.setMouseButtonState(key.getCode(), true);
            } else if (key.getCategory() == InputUtil.Type.KEYSYM) {
                InputTracker.setKeyState(key.getCode(), true);
            }
        });
    }

    private static void cancelAndRelease(KeyBinding binding) {
        InputUtil.Key key = boundKey(binding);
        if (key == null) return;
        int id = encodeId(key);
        if (releaseInput(id, currentInputOwner())) {
            binding.setPressed(false);
        }
    }

    private static void tapBinding(KeyBinding binding) {
        // With the module's Click Sim off, Direct keeps its plain vanilla press edge and no hold timer.
        if (ExecutionMode.isDirect() && !ClickSimController.isEnabled()) {
            pulseBinding(binding);
            return;
        }
        InputUtil.Key key = boundKey(binding);
        if (key == null) return;
        int id = encodeId(key);
        dispatchEvent(key, GLFW.GLFW_PRESS);
        binding.setPressed(true);
        boolean mouseClick = isMouseClick(binding, key);
        if (binding instanceof KeyBindingAccessor acc) {
            if (acc.getTimesPressed() <= 0) {
                acc.setTimesPressed(1);
            }
        }
        // Simulated input must not register as physical: the tracker ignores writes made inside a synthetic callback.
        InputTracker.runSyntheticCallback(() -> {
            if (key.getCategory() == InputUtil.Type.MOUSE) {
                InputTracker.setMouseButtonState(key.getCode(), true);
            } else if (key.getCategory() == InputUtil.Type.KEYSYM) {
                InputTracker.setKeyState(key.getCode(), true);
            }
        });
        long hold = mouseClick ? nextClickHoldNanos() : nextKeyHoldNanos();
        claimInput(id, System.nanoTime() + hold);
    }

    private static Object currentInputOwner() {
        Object owner = ModuleExecutionContext.currentOwner();
        return owner != null ? owner : LEGACY_OWNER;
    }

    private static void claimInput(int id, long releaseAtNanos) {
        if (id == Integer.MIN_VALUE) return;
        synchronized (INPUT_LOCK) {
            IdentityHashMap<Object, Long> owners = pendingReleases.computeIfAbsent(
                    id, ignored -> new IdentityHashMap<>());
            Object owner = currentInputOwner();
            Long previous = owners.get(owner);
            owners.put(owner, previous == null ? releaseAtNanos : Math.max(previous, releaseAtNanos));
        }
    }

    /** @return true when the physical input was fully released. */
    private static boolean releaseInput(int id, Object owner) {
        synchronized (INPUT_LOCK) {
            IdentityHashMap<Object, Long> owners = pendingReleases.get(id);
            if (owners == null) return false;
            owners.remove(owner);
            if (!owners.isEmpty()) return false;
            pendingReleases.remove(id);
            dispatchRelease(id);
            return true;
        }
    }

    private static boolean hasVisibleInput(int id) {
        synchronized (INPUT_LOCK) {
            if (pendingReleases.containsKey(id)) return true;
            HotbarPulse pulse = hotbarPulses.get(id);
            return pulse != null && (!pulse.feedbackOnly()
                    || (!clientTickActive && ModuleExecutionContext.currentOwner() == null));
        }
    }

    /**
     * Like {@link #hasVisibleInput} but only for holds made by a module whose Click Sim toggle is
     * on: the only holds keystroke overlays may see.
     */
    private static boolean hasClickSimInput(int id) {
        synchronized (INPUT_LOCK) {
            IdentityHashMap<Object, Long> owners = pendingReleases.get(id);
            if (owners != null) {
                for (Object owner : owners.keySet()) {
                    if (ClickSimController.isEnabledFor(owner)) return true;
                }
            }
            HotbarPulse pulse = hotbarPulses.get(id);
            return pulse != null && ClickSimController.isEnabledFor(pulse.owner()) && (!pulse.feedbackOnly()
                    || (!clientTickActive && ModuleExecutionContext.currentOwner() == null));
        }
    }

    /** Overlay view of a simulated binding hold: only holds from a module with Click Sim on. */
    public static boolean isBindingClickSimPressed(KeyBinding binding) {
        InputUtil.Key key = boundKey(binding);
        return isUsableKey(key) && hasClickSimInput(encodeId(key));
    }

    /** Overlay view of a simulated key or mouse-button hold: only holds from a module with Click Sim on. */
    public static boolean isKeyClickSimPressed(int glfwKeyCode) {
        if (glfwKeyCode >= 0 && glfwKeyCode <= GLFW.GLFW_MOUSE_BUTTON_LAST) {
            return hasClickSimInput(mouseId(glfwKeyCode));
        }
        return glfwKeyCode >= GLFW.GLFW_KEY_SPACE && hasClickSimInput(glfwKeyCode);
    }

    private static boolean hasBindingClaim(int id) {
        synchronized (INPUT_LOCK) {
            HotbarPulse pulse = hotbarPulses.get(id);
            // Display feedback must never prevent an actionable tap from releasing its binding.
            return pendingReleases.containsKey(id) || (pulse != null && !pulse.feedbackOnly());
        }
    }

    private static void pulseBinding(KeyBinding binding) {
        if (binding == null) return;
        binding.setPressed(true);
        if (binding instanceof KeyBindingAccessor acc) {
            acc.setTimesPressed(acc.getTimesPressed() + 1);
        }
        binding.setPressed(false);
    }

    private static void dispatchEvent(InputUtil.Key key, int action) {
        MinecraftClient mc = mc();
        if (mc == null || key == null || key == InputUtil.UNKNOWN_KEY || key.getCode() == -1) return;
        boolean pressed = action != GLFW.GLFW_RELEASE;
        synchronized (INPUT_LOCK) {
            if (pressed) {
                if (!ClickSimController.isEnabled()) return;
            } else if (!callbackPressedInputs.remove(encodeId(key))) {
                return;
            }
        }
        boolean windowReady = mc.getWindow() != null && mc.getWindow().getHandle() != 0L;
        try {
            if (key.getCategory() == InputUtil.Type.MOUSE) {
                if (mc.currentScreen == null && mc.mouse != null && windowReady) {
                    if (pressed) {
                        synchronized (INPUT_LOCK) { callbackPressedInputs.add(encodeId(key)); }
                    }
                    InputTracker.runSyntheticCallback(() -> ((MouseCallbackAccessor) mc.mouse)
                            .invokeOnMouseButton(mc.getWindow().getHandle(),
                                    new MouseInput(key.getCode(), 0), action));
                } else {
                    KeyBinding.setKeyPressed(key, pressed);
                }
            } else if (key.getCategory() == InputUtil.Type.KEYSYM) {
                if (mc.keyboard != null && windowReady) {
                    if (pressed) {
                        synchronized (INPUT_LOCK) { callbackPressedInputs.add(encodeId(key)); }
                    }
                    InputTracker.runSyntheticCallback(() -> ((KeyboardCallbackAccessor) mc.keyboard)
                            .invokeOnKey(mc.getWindow().getHandle(), action,
                                    new KeyInput(key.getCode(), 0, 0)));
                } else {
                    KeyBinding.setKeyPressed(key, pressed);
                }
            }
        } catch (Throwable t) {
            try {
                KeyBinding.setKeyPressed(key, pressed);
            } catch (Throwable ignored) {}
        }
    }

    private static void dispatchRelease(int id) {
        dispatchRelease(id, true);
    }

    private static void dispatchRelease(int id, boolean callback) {
        if (hasBindingClaim(id)) return;
        InputUtil.Key key = decodeId(id);
        if (key == null) return;
        MinecraftClient mc = mc();
        boolean stillPhysicallyPressed = false;
        if (mc != null && mc.options != null && mc.options.allKeys != null) {
            for (KeyBinding kb : mc.options.allKeys) {
                if (kb != null && key.equals(boundKey(kb))) {
                    boolean physicallyHeld = isPhysicallyPressed(kb);
                    kb.setPressed(physicallyHeld);
                    if (physicallyHeld) {
                        stillPhysicallyPressed = true;
                    }
                }
            }
        }
        if (stillPhysicallyPressed) {
            // The physical key now owns its eventual release; do not retain a synthetic edge.
            synchronized (INPUT_LOCK) { callbackPressedInputs.remove(id); }
            KeyBinding.setKeyPressed(key, true);
            if (key.getCategory() == InputUtil.Type.MOUSE) {
                InputTracker.setMouseButtonState(key.getCode(), true);
            } else if (key.getCategory() == InputUtil.Type.KEYSYM) {
                InputTracker.setKeyState(key.getCode(), true);
            }
        } else {
            if (callback) dispatchEvent(key, GLFW.GLFW_RELEASE);
            KeyBinding.setKeyPressed(key, false);
            if (key.getCategory() == InputUtil.Type.MOUSE) {
                InputTracker.setMouseButtonState(key.getCode(), false);
            } else if (key.getCategory() == InputUtil.Type.KEYSYM) {
                InputTracker.setKeyState(key.getCode(), false);
            }
        }
    }

    private static int encodeId(InputUtil.Key key) {
        if (!isUsableKey(key)) return Integer.MIN_VALUE;
        return (key.getCategory() == InputUtil.Type.MOUSE)
                ? mouseId(key.getCode())
                :  key.getCode();
    }

    private static InputUtil.Key decodeId(int id) {
        return (id <= MOUSE_ID_BASE)
                ? InputUtil.Type.MOUSE.createFromCode(MOUSE_ID_BASE - id)
                : InputUtil.Type.KEYSYM.createFromCode(id);
    }

    private static int mouseId(int button) {
        return MOUSE_ID_BASE - button;
    }

    private static boolean isUsableKey(InputUtil.Key key) {
        return key != null && key != InputUtil.UNKNOWN_KEY && key.getCode() >= 0;
    }

    private static InputUtil.Key boundKey(KeyBinding binding) {
        if (binding == null) return null;
        if (binding instanceof KeyBindingAccessor acc) {
            InputUtil.Key key = acc.getBoundKey();
            return isUsableKey(key) ? key : null;
        }
        try {
            InputUtil.Key key = InputUtil.fromTranslationKey(binding.getBoundKeyTranslationKey());
            return isUsableKey(key) ? key : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void getCursorPos(MinecraftClient mc) {
        double[] x = new double[1], y = new double[1];
        GLFW.glfwGetCursorPos(mc.getWindow().getHandle(), x, y);
        cachedCursorX = x[0];
        cachedCursorY = y[0];
    }

    private static MinecraftClient mc() {
        return MinecraftClient.getInstance();
    }

    @FunctionalInterface
    private interface OptionsConsumer {
        void accept(net.minecraft.client.option.GameOptions options);
    }

    private static void withOptions(OptionsConsumer consumer) {
        MinecraftClient mc = mc();
        if (mc != null && mc.options != null) consumer.accept(mc.options);
    }

    /** Releases any simulated inputs not claimed by the currently executing module. */
    public static void releaseUnowned() {
    }
}
