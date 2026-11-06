package interstellarTikiTiks.apexx.client.module.implementation.studio.macro;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import interstellarTikiTiks.apexx.client.mixin.MinecraftClientAccessor;
import interstellarTikiTiks.apexx.client.util.AttackValidator;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ShieldItem;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class MacroManager {
    private enum RuntimeStage {
        IDLE,
        PREPARE_STEP,
        WAIT_ACTION,
        WAIT_POST,
        RESTORE,
        COOLDOWN
    }

    private static final class RuntimeState {
        private RuntimeStage stage = RuntimeStage.IDLE;
        private long nextAt = 0L;
        private int originalSlot = -1;
        private int stepIndex = 0;
        /** A Use + Attack step has sent its use; the attack goes out on the next tick. */
        private boolean attackHalfPending = false;

        private long lastBlockInteractNonce = 0L;
        private boolean holdUse = false;
        private long holdUseUntil = 0L;
        private boolean shieldActive = false;
        private boolean shieldTriggered = false;
        private long shieldSince = 0L;

        private void resetExecution() {
            stage = RuntimeStage.IDLE;
            nextAt = 0L;
            originalSlot = -1;
            stepIndex = 0;
            attackHalfPending = false;
            holdUse = false;
            holdUseUntil = 0L;
        }

        private void resetShieldTracking() {
            shieldActive = false;
            shieldTriggered = false;
            shieldSince = 0L;
        }
    }

    private static final MacroManager INSTANCE = new MacroManager();
    private static final int MAX_MACROS = 128;
    private static final long MAX_FILE_BYTES = 1024L * 1024L;



    private final List<MacroDefinition> macros = new ArrayList<>();
    private final Map<String, RuntimeState> runtimeStates = new HashMap<>();
    private final Map<String, Boolean> lastPressedByKey = new HashMap<>();

    // Cache: (macroId + ":" + rawFilterId) -> normalizedFilterId
    // Avoids calling normalizeIdentifierAllowEmpty (which runs Identifier.tryParse)
    // on every tick for every macro that uses a block filter.
    private final Map<String, String> normalizedFilterCache = new HashMap<>();

    private long blockInteractNonce = 0L;
    private String lastInteractClickedBlockId = "";
    private String lastInteractUsedBlockId = "";

    private MacroManager() {
        reload();
    }

    public static MacroManager getInstance() {
        return INSTANCE;
    }

    private String cachedCrosshairBlockId = null;
    private String cachedHeldBlockId = null;

    public synchronized void tick(MinecraftClient client, boolean allowInputTriggers) {
        cachedCrosshairBlockId = null;
        cachedHeldBlockId = null;
        // Direct list reference is safe here: tick() is synchronized, so no
        // concurrent modification can occur. Avoids allocating a deep-copy
        // snapshot (N MacroDefinition + N*steps objects) every 50 ms.
        if (allowInputTriggers && client != null && client.player != null && client.world != null) {
            processTriggers(client, macros);
        } else {
            lastPressedByKey.clear();
            for (RuntimeState state : runtimeStates.values()) {
                state.resetShieldTracking();
            }
        }
        // Pause running macros while a screen is open: their steps call doAttack/useItem and
        // hotbar swaps, which act on the open screen (an inventory swaps the hovered stack).
        if (client != null && client.currentScreen != null) return;
        processRuntime(client, macros);
    }

    public synchronized void notifyBlockInteract() {
        notifyBlockInteract("", "");
    }

    public synchronized void notifyBlockInteract(String clickedBlockId, String usedBlockId) {
        if (blockInteractNonce == Long.MAX_VALUE) {
            blockInteractNonce = 0L;
            for (RuntimeState state : runtimeStates.values()) {
                state.lastBlockInteractNonce = 0L;
            }
        } else {
            blockInteractNonce++;
        }
        lastInteractClickedBlockId = normalizeIdentifierAllowEmpty(clickedBlockId);
        lastInteractUsedBlockId = normalizeIdentifierAllowEmpty(usedBlockId);
    }

    public synchronized List<MacroDefinition> getMacros() {
        return snapshotMacros();
    }

    public synchronized MacroDefinition getMacro(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        for (MacroDefinition macro : macros) {
            if (macro.getId().equals(id)) {
                return macro.copy();
            }
        }
        return null;
    }

    public synchronized MacroDefinition createDefaultMacro() {
        MacroDefinition macro = new MacroDefinition();
        macro.setId(UUID.randomUUID().toString());
        macro.setName(nextDefaultName());
        macro.sanitize();
        return macro;
    }

    public synchronized MacroDefinition duplicateMacro(String id) {
        MacroDefinition source = getMacro(id);
        if (source == null || macros.size() >= MAX_MACROS) {
            return null;
        }
        MacroDefinition clone = source.copy();
        clone.setId(UUID.randomUUID().toString());
        clone.setName(source.getName() + " (Copy)");
        saveMacro(clone);
        return clone;
    }

    public synchronized boolean saveMacro(MacroDefinition macro) {
        if (macro == null) {
            return false;
        }
        MacroDefinition copy = macro.copy();
        if (copy.getId() == null || copy.getId().isBlank()) {
            copy.setId(UUID.randomUUID().toString());
        }
        copy.sanitize();

        int existingIndex = findIndexById(copy.getId());
        if (existingIndex < 0 && macros.size() >= MAX_MACROS) {
            return false;
        }

        if (existingIndex >= 0) {
            macros.set(existingIndex, copy);
        } else {
            macros.add(copy);
        }

        sortMacros();
        trimInvalidRuntimeStates();
        interstellarTikiTiks.apexx.client.config.ConfigManager.getInstance().onUserContentChanged();
        return true;
    }

    public synchronized boolean deleteMacro(String id) {
        if (id == null || id.isBlank()) {
            return false;
        }
        int index = findIndexById(id);
        if (index < 0) {
            return false;
        }

        macros.remove(index);
        RuntimeState removed = runtimeStates.remove(id);
        if (removed != null) releaseState(MinecraftClient.getInstance(), removed);
        interstellarTikiTiks.apexx.client.config.ConfigManager.getInstance().onUserContentChanged();
        return true;
    }

    /** The macros in the same JSON shape the CommandCenter write-through uses, for the cloud config blob. */
    public synchronized JsonArray toJsonArray() {
        JsonArray array = new JsonArray();
        for (MacroDefinition macro : macros) {
            array.add(macro.toJson());
        }
        return array;
    }

    /** Replace every macro with those from the user's own cloud blob. Does not touch the CommandCenter. */
    public synchronized void replaceFromCloud(JsonArray array) {
        if (array == null) return;
        List<MacroDefinition> parsed = new ArrayList<>();
        try {
            Set<String> ids = new HashSet<>();
            for (JsonElement element : array) {
                if (parsed.size() >= MAX_MACROS) break;
                if (element == null || !element.isJsonObject()) continue;
                MacroDefinition macro = MacroDefinition.fromJson(element.getAsJsonObject());
                if (macro.getId() == null || macro.getId().isBlank() || ids.contains(macro.getId())) {
                    macro.setId(UUID.randomUUID().toString());
                }
                macro.sanitize();
                ids.add(macro.getId());
                parsed.add(macro);
            }
        } catch (Exception e) {
            interstellarTikiTiks.apexx.client.util.ClientInternalLog.error("MacroManager", "Cloud macros could not be parsed; keeping current macros", e);
            return;
        }
        macros.clear();
        macros.addAll(parsed);
        sortMacros();
        trimInvalidRuntimeStates();
    }

    public synchronized void reload() {
        macros.clear();
        runtimeStates.clear();
        lastPressedByKey.clear();
        blockInteractNonce = 0L;
        lastInteractClickedBlockId = "";
        lastInteractUsedBlockId = "";

    }

    public synchronized int size() {
        return macros.size();
    }

    public static String keyDisplayName(int keyCode) {
        if (keyCode == -1 || keyCode == 0) {
            return "NONE";
        }
        if (keyCode < 0) {
            int button = interstellarTikiTiks.apexx.client.module.Module.mouseButtonOf(keyCode);
            return "MOUSE" + (button + 1);
        }
        return switch (keyCode) {
            case GLFW.GLFW_KEY_RIGHT_SHIFT -> "RSHIFT";
            case GLFW.GLFW_KEY_LEFT_SHIFT -> "LSHIFT";
            case GLFW.GLFW_KEY_RIGHT_CONTROL -> "RCTRL";
            case GLFW.GLFW_KEY_LEFT_CONTROL -> "LCTRL";
            case GLFW.GLFW_KEY_RIGHT_ALT -> "RALT";
            case GLFW.GLFW_KEY_LEFT_ALT -> "LALT";
            case GLFW.GLFW_KEY_ESCAPE -> "ESC";
            case GLFW.GLFW_KEY_DELETE -> "DELETE";
            case GLFW.GLFW_KEY_INSERT -> "INSERT";
            case GLFW.GLFW_KEY_PAGE_UP -> "PAGE UP";
            case GLFW.GLFW_KEY_PAGE_DOWN -> "PAGE DOWN";
            case GLFW.GLFW_KEY_PRINT_SCREEN -> "PRINT SCREEN";
            default -> {
                String name = GLFW.glfwGetKeyName(keyCode, 0);
                yield name == null ? "UNKNOWN" : name.toUpperCase(Locale.ROOT);
            }
        };
    }

    private List<MacroDefinition> snapshotMacros() {
        List<MacroDefinition> copy = new ArrayList<>(macros.size());
        for (MacroDefinition macro : macros) {
            copy.add(macro.copy());
        }
        return copy;
    }

    private void processTriggers(MinecraftClient client, List<MacroDefinition> snapshot) {
        long now = System.currentTimeMillis();
        long windowHandle = client.getWindow().getHandle();
        boolean useKeyPressed = client.options != null && client.options.useKey != null && client.options.useKey.isPressed();

        for (MacroDefinition macro : snapshot) {
            if (!macro.isEnabled()) {
                continue;
            }
            RuntimeState state = runtimeStates.computeIfAbsent(macro.getId(), ignored -> new RuntimeState());
            boolean shouldStart = switch (macro.getTriggerMode()) {
                case KEYBIND -> evaluateKeybindTrigger(windowHandle, macro);
                case USE_KEY_HOLD -> evaluateUseKeyHoldTrigger(client, macro, useKeyPressed);
                case BLOCK_INTERACT -> evaluateBlockTrigger(state, macro);
                case LOOK_AT_BLOCK -> evaluateLookAtBlockTrigger(client, macro);
                case TARGET_SHIELD -> evaluateShieldTrigger(client, macro, state, now);
            };
            if (shouldStart) {
                startMacro(client, macro, state, now);
            }
        }
    }

    private boolean evaluateKeybindTrigger(long windowHandle, MacroDefinition macro) {
        int keyCode = macro.getKeyCode();
        if (keyCode == -1) {
            return false;
        }

        boolean pressed = isPressed(windowHandle, keyCode);
        boolean wasPressed = lastPressedByKey.getOrDefault(macro.getId(), false);
        lastPressedByKey.put(macro.getId(), pressed);

        if (macro.getActivationMode() == MacroDefinition.ActivationMode.HOLD) {
            return pressed;
        }
        return pressed && !wasPressed;
    }

    private boolean evaluateUseKeyHoldTrigger(MinecraftClient client, MacroDefinition macro, boolean useKeyPressed) {
        if (!useKeyPressed) {
            return false;
        }
        String filter = getCachedNormalizedFilter(macro);
        if (filter.isBlank()) {
            return true;
        }
        String targetBlockId = getCrosshairBlockId(client);
        String heldBlockId = getHeldBlockItemId(client.player);
        return filter.equals(targetBlockId) || filter.equals(heldBlockId);
    }

    private boolean evaluateBlockTrigger(RuntimeState state, MacroDefinition macro) {
        if (blockInteractNonce <= state.lastBlockInteractNonce) {
            return false;
        }
        state.lastBlockInteractNonce = blockInteractNonce;
        String filter = getCachedNormalizedFilter(macro);
        if (filter.isBlank()) {
            return true;
        }
        return filter.equals(lastInteractClickedBlockId) || filter.equals(lastInteractUsedBlockId);
    }

    private boolean evaluateLookAtBlockTrigger(MinecraftClient client, MacroDefinition macro) {
        String filter = getCachedNormalizedFilter(macro);
        if (filter.isBlank()) {
            return false;
        }
        return filter.equals(getCrosshairBlockId(client));
    }

    /**
     * Returns the normalized block filter ID for the given macro, using a cache
     * keyed by (macroId + ":" + rawFilterId). This avoids calling
     * normalizeIdentifierAllowEmpty (which runs Identifier.tryParse) on every
     * tick for every macro while the use key is held.
     */
    private String getCachedNormalizedFilter(MacroDefinition macro) {
        String rawFilter = macro.getBlockFilterId();
        String cacheKey = macro.getId() + ":" + (rawFilter == null ? "" : rawFilter);
        return normalizedFilterCache.computeIfAbsent(cacheKey, k -> normalizeIdentifierAllowEmpty(rawFilter));
    }

    private String getCrosshairBlockId(MinecraftClient client) {
        if (cachedCrosshairBlockId != null) return cachedCrosshairBlockId;
        if (client == null || client.world == null) {
            return cachedCrosshairBlockId = "";
        }
        if (!(client.crosshairTarget instanceof net.minecraft.util.hit.BlockHitResult blockHitResult)) {
            return cachedCrosshairBlockId = "";
        }
        try {
            return cachedCrosshairBlockId = normalizeIdentifierAllowEmpty(net.minecraft.registry.Registries.BLOCK.getId(client.world.getBlockState(blockHitResult.getBlockPos()).getBlock()).toString());
        } catch (Throwable ignored) {
            return cachedCrosshairBlockId = "";
        }
    }

    private String getHeldBlockItemId(net.minecraft.client.network.ClientPlayerEntity player) {
        if (cachedHeldBlockId != null) return cachedHeldBlockId;
        if (player == null) {
            return cachedHeldBlockId = "";
        }
        net.minecraft.item.ItemStack mainHand = player.getMainHandStack();
        if (mainHand == null || mainHand.isEmpty()) {
            return cachedHeldBlockId = "";
        }
        if (!(mainHand.getItem() instanceof net.minecraft.item.BlockItem blockItem)) {
            return cachedHeldBlockId = "";
        }
        try {
            return cachedHeldBlockId = normalizeIdentifierAllowEmpty(net.minecraft.registry.Registries.BLOCK.getId(blockItem.getBlock()).toString());
        } catch (Throwable ignored) {
            return cachedHeldBlockId = "";
        }
    }

    private boolean evaluateShieldTrigger(MinecraftClient client, MacroDefinition macro, RuntimeState state, long now) {
        if (!isTargetShielding(client)) {
            state.resetShieldTracking();
            return false;
        }
        if (!state.shieldActive) {
            state.shieldActive = true;
            state.shieldSince = now;
            state.shieldTriggered = false;
            return false;
        }
        if (!state.shieldTriggered && now - state.shieldSince >= macro.getShieldHoldMs()) {
            state.shieldTriggered = true;
            return true;
        }
        return false;
    }

    private boolean isTargetShielding(MinecraftClient client) {
        if (!(client.crosshairTarget instanceof EntityHitResult entityHit)) {
            return false;
        }
        if (!(entityHit.getEntity() instanceof PlayerEntity playerTarget)) {
            return false;
        }
        if (client.player == null || playerTarget == client.player || !playerTarget.isAlive()) {
            return false;
        }
        if (!playerTarget.isBlocking()) {
            return false;
        }
        ItemStack active = playerTarget.getActiveItem();
        return !active.isEmpty() && active.getItem() instanceof ShieldItem;
    }

    private void processRuntime(MinecraftClient client, List<MacroDefinition> snapshot) {
        Map<String, MacroDefinition> macroById = new HashMap<>(snapshot.size());
        for (MacroDefinition macro : snapshot) {
            macroById.put(macro.getId(), macro);
        }

        List<String> toRemove = new ArrayList<>();
        long now = System.currentTimeMillis();

        for (Map.Entry<String, RuntimeState> entry : runtimeStates.entrySet()) {
            String macroId = entry.getKey();
            RuntimeState state = entry.getValue();
            MacroDefinition macro = macroById.get(macroId);

            if (macro == null || !macro.isEnabled()) {
                releaseState(client, state);
                toRemove.add(macroId);
                continue;
            }

            if (client == null || client.player == null || client.world == null) {
                releaseState(null, state);
                continue;
            }

            if (state.holdUse) {
                updateUseHold(client, state, now);
            }

            if (state.stage == RuntimeStage.IDLE) {
                continue;
            }

            if (state.stage == RuntimeStage.COOLDOWN) {
                if (now >= state.nextAt) {
                    state.resetExecution();
                }
                continue;
            }

            if (now < state.nextAt) {
                continue;
            }

            if (state.stage == RuntimeStage.PREPARE_STEP) {
                MacroDefinition.Step step = macro.getStep(state.stepIndex);
                if (step == null) {
                    enterCooldown(state, macro, now);
                    continue;
                }
                if (step.getActionMode() != MacroDefinition.ActionMode.PRESS_KEY) {
                    int targetSlot = resolveTargetSlot(client.player, step);
                    if (targetSlot < 0 || targetSlot > 8) {
                        enterCooldown(state, macro, now);
                        continue;
                    }
                    boolean selected = interstellarTikiTiks.apexx.client.util.ModuleExecutionContext.call(
                            state, () -> interstellarTikiTiks.apexx.client.util.combat.SlotSync.select(client, targetSlot));
                    if (!selected) {
                        enterCooldown(state, macro, now);
                        continue;
                    }
                }
                state.stage = RuntimeStage.WAIT_ACTION;
                state.nextAt = now + step.getPreDelayMs();
                if (step.getPreDelayMs() <= 0 && step.getActionMode() != MacroDefinition.ActionMode.PRESS_KEY) {
                    // Same tick: the action follows the swap after the sampled 1-3 ms gap, not on the next tick.
                    interstellarTikiTiks.apexx.client.util.combat.SlotSync.paceAfterSwap();
                    runWaitAction(client, macro, state, now);
                }
                continue;
            }

            if (state.stage == RuntimeStage.WAIT_ACTION) {
                runWaitAction(client, macro, state, now);
                continue;
            }

            if (state.stage == RuntimeStage.WAIT_POST) {
                state.stepIndex++;
                if (state.stepIndex < macro.getStepCount()) {
                    state.stage = RuntimeStage.PREPARE_STEP;
                    state.nextAt = now;
                } else if (macro.isRestoreOriginalSlot() && state.originalSlot >= 0
                        && state.originalSlot != client.player.getInventory().getSelectedSlot()) {
                    state.stage = RuntimeStage.RESTORE;
                    state.nextAt = now;
                } else {
                    enterCooldown(state, macro, now);
                }
                continue;
            }

            if (state.stage == RuntimeStage.RESTORE) {
                int safeSlot = Math.max(0, Math.min(8, state.originalSlot));
                interstellarTikiTiks.apexx.client.util.ModuleExecutionContext.run(
                        state, () -> interstellarTikiTiks.apexx.client.util.combat.SlotSync.restore(client, safeSlot));
                enterCooldown(state, macro, now);
            }
        }

        for (String id : toRemove) {
            runtimeStates.remove(id);
        }
    }

    private void runWaitAction(MinecraftClient client, MacroDefinition macro, RuntimeState state, long now) {
        MacroDefinition.Step step = macro.getStep(state.stepIndex);
        if (step == null) {
            enterCooldown(state, macro, now);
            return;
        }
        if (state.attackHalfPending) {
            state.attackHalfPending = false;
            interstellarTikiTiks.apexx.client.util.ModuleExecutionContext.run(
                    state, () -> AttackValidator.invokeVanillaAttack(client));
            state.stage = RuntimeStage.WAIT_POST;
            state.nextAt = now + step.getPostDelayMs();
            return;
        }
        interstellarTikiTiks.apexx.client.util.ModuleExecutionContext.run(
                state, () -> runAction(client, step));
        if (step.getActionMode() == MacroDefinition.ActionMode.USE_AND_ATTACK) {
            // A use and an attack in the same tick are flagged by packet-order checks, so the attack
            // half goes out on the next tick.
            state.attackHalfPending = true;
            state.nextAt = now;
            return;
        }
        if (step.getActionMode() == MacroDefinition.ActionMode.USE_ITEM
                && client.player != null && client.player.isUsingItem()) {
            // A consumable stays in use only while Use is held: keep it down until consumed (or the step's wait ends).
            state.holdUse = true;
            state.holdUseUntil = now + Math.max(step.getPostDelayMs(), 0) + 250L;
            setUseKey(client, true);
        }
        state.stage = RuntimeStage.WAIT_POST;
        state.nextAt = now + step.getPostDelayMs();
    }

    private void updateUseHold(MinecraftClient client, RuntimeState state, long now) {
        if (client.player != null && client.player.isUsingItem() && now < state.holdUseUntil) {
            setUseKey(client, true);
            return;
        }
        state.holdUse = false;
        state.holdUseUntil = 0L;
        setUseKey(client, false);
    }

    private void setUseKey(MinecraftClient client, boolean down) {
        if (client == null || client.options == null || client.options.useKey == null) return;
        client.options.useKey.setPressed(down);
    }

    /** Drops everything a macro run owns: held Use, slot intent, and the slot it swapped away from. */
    private void releaseState(MinecraftClient client, RuntimeState state) {
        if (state.holdUse) {
            setUseKey(client, false);
        }
        boolean midRun = state.stage == RuntimeStage.PREPARE_STEP || state.stage == RuntimeStage.WAIT_ACTION
                || state.stage == RuntimeStage.WAIT_POST || state.stage == RuntimeStage.RESTORE;
        if (client != null && client.player != null && midRun && state.originalSlot >= 0) {
            interstellarTikiTiks.apexx.client.util.combat.SlotSync.restore(client, Math.min(8, state.originalSlot));
        }
        interstellarTikiTiks.apexx.client.util.combat.SlotSync.releaseOwner(state);
        state.resetExecution();
    }

    private void startMacro(MinecraftClient client, MacroDefinition macro, RuntimeState state, long now) {
        if (client.player == null || client.world == null) {
            return;
        }
        if (client.player.isUsingItem()) {
            return;
        }
        if (macro.getStepCount() <= 0) {
            return;
        }
        if (state.stage != RuntimeStage.IDLE) {
            if (state.stage == RuntimeStage.COOLDOWN && now >= state.nextAt) {
                state.resetExecution();
            } else {
                return;
            }
        }

        state.originalSlot = client.player.getInventory().getSelectedSlot();
        state.stepIndex = 0;
        state.stage = RuntimeStage.PREPARE_STEP;
        state.nextAt = now;
    }

    private void enterCooldown(RuntimeState state, MacroDefinition macro, long now) {
        interstellarTikiTiks.apexx.client.util.combat.SlotSync.releaseOwner(state);
        state.stage = RuntimeStage.COOLDOWN;
        state.nextAt = now + macro.getCooldownMs();
        state.stepIndex = 0;
    }

    private void runAction(MinecraftClient client, MacroDefinition.Step step) {
        if (step == null) {
            return;
        }
        MinecraftClientAccessor accessor = (MinecraftClientAccessor) client;
        try {
            if (step.getActionMode() != MacroDefinition.ActionMode.PRESS_KEY
                    && !interstellarTikiTiks.apexx.client.util.combat.SlotSync.ensureSelectionForCurrentOwner(client)) {
                return;
            }
            switch (step.getActionMode()) {
                case USE_ITEM -> accessor.useItem();
                case ATTACK -> AttackValidator.invokeVanillaAttack(client);
                case USE_AND_ATTACK -> accessor.useItem();
                case PRESS_KEY -> pressStepKey(step.getKeyCode());
            }
        } catch (Throwable failure) {
            interstellarTikiTiks.apexx.client.util.ClientInternalLog.error(
                    "MacroManager", "Macro action failed", failure);
        }
    }

    private void pressStepKey(int keyCode) {
        if (keyCode == -1) {
            return;
        }
        try {
            InputUtil.Key key = keyCode < 0
                    ? InputUtil.Type.MOUSE.createFromCode(interstellarTikiTiks.apexx.client.module.Module.mouseButtonOf(keyCode))
                    : InputUtil.Type.KEYSYM.createFromCode(keyCode);
            KeyBinding.setKeyPressed(key, true);
            KeyBinding.onKeyPressed(key);
            KeyBinding.setKeyPressed(key, false);
        } catch (Throwable ignored) {
        }
    }

    private int resolveTargetSlot(ClientPlayerEntity player, MacroDefinition.Step step) {
        if (step.getTargetMode() == MacroDefinition.TargetMode.SLOT) {
            return Math.max(0, Math.min(8, step.getSlot() - 1));
        }

        Identifier identifier = parseIdentifier(step.getItemId());
        if (identifier == null) {
            return -1;
        }

        Item item;
        try {
            item = Registries.ITEM.get(identifier);
        } catch (Throwable ignored) {
            return -1;
        }
        if (item == null) {
            return -1;
        }

        PlayerInventory inventory = player.getInventory();
        for (int i = 0; i < 9; i++) {
            ItemStack stack = inventory.getStack(i);
            if (!stack.isEmpty() && stack.getItem() == item) {
                return i;
            }
        }
        return -1;
    }

    private Identifier parseIdentifier(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (value.isBlank()) {
            return null;
        }
        return net.minecraft.util.Identifier.tryParse(value.indexOf(':') >= 0 ? value : "minecraft:" + value);
    }

    private String normalizeIdentifierAllowEmpty(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (value.isBlank()) {
            return "";
        }
        net.minecraft.util.Identifier id = net.minecraft.util.Identifier.tryParse(value.indexOf(':') >= 0 ? value : "minecraft:" + value);
        if (id != null) {
            return id.toString();
        }
        return value;
    }

    private boolean isPressed(long windowHandle, int keyCode) {
        if (keyCode == -1 || keyCode == 0) {
            return false;
        }
        if (keyCode < 0) {
            int button = interstellarTikiTiks.apexx.client.module.Module.mouseButtonOf(keyCode);
            if (button >= 0 && button <= GLFW.GLFW_MOUSE_BUTTON_LAST) {
                return GLFW.glfwGetMouseButton(windowHandle, button) == GLFW.GLFW_PRESS;
            }
            return false;
        }
        if (keyCode >= GLFW.GLFW_KEY_SPACE && keyCode <= GLFW.GLFW_KEY_LAST) {
            return GLFW.glfwGetKey(windowHandle, keyCode) == GLFW.GLFW_PRESS;
        }
        return false;
    }

    private String nextDefaultName() {
        Set<String> names = new HashSet<>();
        for (MacroDefinition macro : macros) {
            names.add(macro.getName().toLowerCase(Locale.ROOT));
        }
        if (!names.contains("macro")) {
            return "Macro";
        }
        int index = 2;
        while (true) {
            String candidate = "Macro " + index;
            if (!names.contains(candidate.toLowerCase(Locale.ROOT))) {
                return candidate;
            }
            index++;
        }
    }

    private int findIndexById(String id) {
        for (int i = 0; i < macros.size(); i++) {
            if (macros.get(i).getId().equals(id)) {
                return i;
            }
        }
        return -1;
    }

    private void sortMacros() {
        macros.sort(Comparator.comparing(macro -> macro.getName().toLowerCase(Locale.ROOT)));
    }

    private void trimInvalidRuntimeStates() {
        Set<String> validIds = new HashSet<>();
        for (MacroDefinition macro : macros) {
            validIds.add(macro.getId());
        }
        for (java.util.Iterator<Map.Entry<String, RuntimeState>> it = runtimeStates.entrySet().iterator(); it.hasNext();) {
            Map.Entry<String, RuntimeState> entry = it.next();
            if (!validIds.contains(entry.getKey())) {
                releaseState(MinecraftClient.getInstance(), entry.getValue());
                it.remove();
            }
        }
    }
}
