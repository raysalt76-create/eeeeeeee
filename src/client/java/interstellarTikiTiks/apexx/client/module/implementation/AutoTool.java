package interstellarTikiTiks.apexx.client.module.implementation;

import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import net.minecraft.block.BlockState;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;

public class AutoTool extends Module implements interstellarTikiTiks.apexx.client.module.hooks.ClickSimulating {

    // Additive. Default off keeps the original behavior of accepting any slot
    // whose effective speed beats the current best (bare-hand baseline 1.0).
    private final BooleanSetting onlyEffectiveTools = new BooleanSetting("Only Effective Tools", false)
            .setDescription("Only switches when a hotbar tool is actually the right tool for the block (e.g. a pickaxe for stone). Off: switches to whatever breaks it fastest, even a non-tool.");
    // Additive. Default 0 = no durability floor (original behavior). When > 0,
    // tools with that many or fewer uses remaining are skipped.
    private final SliderSetting durabilityFloor = new SliderSetting("Durability Floor", 0, 0, 200, 0)
            .setDescription("Never switches to a tool with this many or fewer uses left, so nearly broken tools are saved. 0 = no limit.");

    // ---- Delays ----
    /** Legacy key kept so old configs load; not registered and no longer read. */
    private final BooleanSetting useProfile = new BooleanSetting("Use Timing Profile", false);
    private final SliderSetting swapDelayTicks = new SliderSetting("Swap Delay (ms)", 50, 0, 500, 0)
            .withJitter(50)
            .setDescription("Wait after you start breaking a block before switching to the best tool. Rounded to game ticks (50 ms each); 0 switches in the same tick you start.");
    private final SliderSetting swapBackDelayTicks = new SliderSetting("Swap Back Delay (ms)", 100, 0, 1000, 0)
            .withJitter(50)
            .setDescription("Wait after you stop breaking before switching back to the item you held, so mining several blocks in a row doesn't flick back and forth. Rounded to game ticks (50 ms each).");

    private int resolveTicks(SliderSetting slider) {
        return Math.max(0, interstellarTikiTiks.apexx.client.util.combat.ModuleDelays.msToTicks(slider.sample()));
    }

    private enum State {
        IDLE,
        WAITING_TO_SWAP,
        TOOL_SWAPPED,
        WAITING_FOR_SWAP_BACK
    }

    private State currentState = State.IDLE;
    private int originalSlot = -1;
    private int waitCounter = 0;

    public AutoTool() {
        super("AutoTool", "While you mine, switches to the hotbar tool that breaks the block fastest (respecting a durability floor) and switches back to your previous item after you stop. Each switch is a real hotbar change the server sees, with configurable delays.", Category.PLAYER);
        swapDelayTicks.setSection("Delays");
        swapBackDelayTicks.setSection("Delays");
        addSetting(onlyEffectiveTools);
        addSetting(durabilityFloor);
        addSetting(swapDelayTicks);
        addSetting(swapBackDelayTicks);
    }

    @Override
    public void onDisable() {
        if (originalSlot != -1 && mc.player != null && currentState == State.TOOL_SWAPPED) {
            interstellarTikiTiks.apexx.client.util.combat.SlotSync.restore(mc, originalSlot);
        }
        reset();
    }

    @Override
    protected void onRuntimeStateReleased() {
        if (originalSlot != -1 && mc.player != null && currentState == State.TOOL_SWAPPED) {
            int cur = mc.player.getInventory().getSelectedSlot();
            if (cur != originalSlot) {
                if (interstellarTikiTiks.apexx.client.util.combat.TickActionOrder.canSwitchSlot()) {
                    interstellarTikiTiks.apexx.client.util.combat.SlotSync.select(mc, originalSlot);
                } else {
                    interstellarTikiTiks.apexx.client.util.combat.TickActionOrder.deferSlot(originalSlot, cur);
                }
            }
        }
        interstellarTikiTiks.apexx.client.util.combat.SlotSync.releaseOwner(this);
        reset();
    }

    @Override
    public void onTick() {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) {
            return;
        }

        // Don't run inventory/screen logic while a screen is open; selecting a
        // slot mid-screen can desync and is a tell.
        if (mc.currentScreen != null) {
            return;
        }

        switch (currentState) {
            case IDLE:
                if (mc.interactionManager.isBreakingBlock()) {
                    HitResult target = mc.crosshairTarget;
                    if (target instanceof BlockHitResult) {
                        BlockState blockState = mc.world.getBlockState(((BlockHitResult) target).getBlockPos());
                        int bestToolSlot = findBestTool(blockState);
                        if (bestToolSlot != -1 && mc.player.getInventory().getSelectedSlot() != bestToolSlot) {
                            startSwap();
                        }
                    }
                }
                break;
            case WAITING_TO_SWAP:
                if (waitCounter > 0) {
                    waitCounter--;
                } else {
                    swapToBestTool();
                    currentState = State.TOOL_SWAPPED;
                }
                break;
            case TOOL_SWAPPED:
                if (!mc.interactionManager.isBreakingBlock()) {
                    currentState = State.WAITING_FOR_SWAP_BACK;
                    waitCounter = resolveTicks(swapBackDelayTicks);
                }
                break;
            case WAITING_FOR_SWAP_BACK:
                if (mc.interactionManager.isBreakingBlock()) {
                    currentState = State.TOOL_SWAPPED;
                    break;
                }
                if (waitCounter > 0) {
                    waitCounter--;
                } else {
                    swapBack();
                }
                break;
        }
    }

    private int findBestTool(BlockState blockState) {
        PlayerInventory inventory = mc.player.getInventory();
        int bestSlot = -1;
        float bestSpeed = 1.0f;

        RegistryEntry<Enchantment> efficiencyEntry = mc.world.getRegistryManager()
                .getOrThrow(RegistryKeys.ENCHANTMENT)
                .getOrThrow(Enchantments.EFFICIENCY);

        for (int i = 0; i < 9; i++) {
            ItemStack stack = inventory.getStack(i);
            if (stack.isEmpty()) continue;

            float speed = stack.getMiningSpeedMultiplier(blockState);

            // Skip non-tools (bare-hand-equivalent speed) when requested.
            if (onlyEffectiveTools.isEnabled() && speed <= 1.0f) {
                continue;
            }

            // Skip tools at or below the durability floor so we don't snap to a
            // tool about to break. A floor of 0 disables the check (default).
            if (!hasEnoughDurability(stack)) {
                continue;
            }

            int efficiencyLevel = EnchantmentHelper.getLevel(efficiencyEntry, stack);
            if (efficiencyLevel > 0) {
                speed += efficiencyLevel * efficiencyLevel + 1;
            }

            if (speed > bestSpeed) {
                bestSpeed = speed;
                bestSlot = i;
            }
        }
        return bestSlot;
    }

    private boolean hasEnoughDurability(ItemStack stack) {
        int floor = (int) durabilityFloor.getValue();
        if (floor <= 0 || !stack.isDamageable()) {
            return true;
        }
        int remaining = stack.getMaxDamage() - stack.getDamage();
        return remaining > floor;
    }

    private void startSwap() {
        originalSlot = mc.player.getInventory().getSelectedSlot();
        waitCounter = resolveTicks(swapDelayTicks);
        currentState = State.WAITING_TO_SWAP;
    }

    private void swapToBestTool() {
        if (mc.crosshairTarget instanceof BlockHitResult) {
            BlockState blockState = mc.world.getBlockState(((BlockHitResult) mc.crosshairTarget).getBlockPos());
            int bestSlot = findBestTool(blockState);
            if (bestSlot != -1) {
                if (!interstellarTikiTiks.apexx.client.util.combat.TickActionOrder.canSwitchSlot()) {
                    return;
                }
                interstellarTikiTiks.apexx.client.util.combat.SlotSync.select(mc, bestSlot);
            }
        }
    }

    private void swapBack() {
        if (originalSlot != -1) {
            interstellarTikiTiks.apexx.client.util.combat.SlotSync.restore(mc, originalSlot);
        }
        reset();
    }

    private void reset() {
        currentState = State.IDLE;
        originalSlot = -1;
        waitCounter = 0;
    }
}
