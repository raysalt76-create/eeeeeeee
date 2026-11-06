package interstellarTikiTiks.apexx.client.module.implementation.combat;

import interstellarTikiTiks.apexx.client.Apexx;
import interstellarTikiTiks.apexx.client.module.*;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.util.ClickSimController;
import interstellarTikiTiks.apexx.client.util.combat.ModuleDelays;
import interstellarTikiTiks.apexx.client.util.RotationManager;
import interstellarTikiTiks.apexx.client.util.SystemInputSimulator;
import interstellarTikiTiks.apexx.client.util.combat.InteractSim;
import interstellarTikiTiks.apexx.client.util.combat.SlotSync;
import interstellarTikiTiks.apexx.client.util.combat.TickActionOrder;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.PotionContentsComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.potion.Potion;
import net.minecraft.potion.Potions;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.screen.slot.SlotActionType;

import java.util.Optional;

public class AutoHeal extends Module implements interstellarTikiTiks.apexx.client.module.hooks.ClickSimulating {

    private final SliderSetting health = new SliderSetting("Health", 9.5, 1, 20, 0)
            .setDescription("Health, in half-hearts (20 = full), at or below which a healing potion is splashed at your feet.");
    private final SliderSetting potToHealth = new SliderSetting("Pot To Health", 20, 1, 20, 0)
            .setDescription("Keep splashing until your health reaches this value, in half-hearts. 20 heals to full; lower saves potions.");
    private final SliderSetting strength = new SliderSetting("Strength", 11.5, 1.0, 20.0, 1)
            .setDescription("How fast the camera turns down to splash at your feet. Higher is quicker but less natural.");
    private final ModeSetting rotPattern = new ModeSetting("Pattern", "Sine", "Smooth", "Linear")
            .setDescription("Shape of the look-down motion. Sine and Smooth ease in and out like a hand; Linear turns at a constant speed.");
    private final SliderSetting rotRandom = new SliderSetting("Aim Randomness", 0.35, 0.0, 1.0, 2)
            .setDescription("How much the look-down angle varies from throw to throw (0-1), so every splash is not aimed identically.");
    private final BooleanSetting silentRotation = new BooleanSetting("Silent Aim", false)
            .setDescription("Looks down only for the server and keeps your screen steady. Off (default) moves your real camera, which looks most natural.");

    // ---- Delays ----
    private final SliderSetting throwDelay = new SliderSetting("Throw Delay (ms)", 60, 0, 400, 0)
            .withJitter(12)
            .setDescription("Wait between each inventory step (open inventory, move the potion to your hotbar, close) "
                    + "when the potion has to be fetched from your inventory, in ms. 0 runs one step per tick; "
                    + "50-80 ms reads as a quick but human hand.")
            .setSection("Delays");
    private final SliderSetting swapBackDelay = new SliderSetting("Swap Back Delay (ms)", 50, 0, 500, 0)
            .withJitter(15)
            .setDescription("Wait after the throw before your previous hotbar item is reselected, in ms. "
                    + "Always at least the next tick, never in the same tick as the throw.")
            .setSection("Delays");

    private enum State {
        IDLE, MOVING_TO_HOTBAR, ROTATING, USING, RETURNING_SLOT, RESTORING_INVENTORY, COOLDOWN
    }

    // Inventory swap sub-steps, a Throw Delay apart: open the inventory, SWAP click, close it.
    private enum InventoryStep { OPEN, CLICK, CLOSE }

    private State currentState = State.IDLE;
    private InventoryStep inventoryStep = InventoryStep.OPEN;
    private long nextInventoryActionMs = 0L;
    private int potionSlot = -1;
    private int originalSlot = -1;
    private int inventoryHandlerSlot = -1;
    private boolean didInventorySwap = false;

    private long returnAtMs = 0L;
    private long rotatingSinceMs = 0L;
    private long cooldownEndMs = 0L;
    /** Set after a splash while health is still below Pot To Health: keep healing until it is reached. */
    private boolean topUp = false;
    private static final long ROTATE_TIMEOUT_MS = 1500L;
    private static final long HEAL_SETTLE_MS = 500L;

    private AimAssist aimAssist = null;
    /** How long each pause request keeps AimAssist from steering; re-armed every tick of the look-down. */
    private static final long AIM_ASSIST_PAUSE_MS = 150L;

    public AutoHeal() {
        super("AutoHeal", "When your health drops below the threshold, looks down and splashes a healing potion at your feet, using your hotbar first and fetching one from your inventory if needed, then switches back.", Category.COMBAT);
        setSubCategory(SubCategory.SMP);
        addSetting(health);
        addSetting(potToHealth);
        addSetting(strength);
        addSetting(rotPattern);
        addSetting(rotRandom);
        addSetting(silentRotation);
        addSetting(throwDelay);
        addSetting(swapBackDelay);
    }

    @Override
    public void onEnable() {
        aimAssist = Apexx.getInstance().getModuleManager().getModule(AimAssist.class);
        topUp = false;
        reset();
    }

    @Override
    public void onDisable() {
        releaseSequence();
    }

    @Override
    protected void onRuntimeStateReleased() {
        // Also runs right after onDisable, and on faults and world changes; releaseSequence is idempotent.
        releaseSequence();
    }

    /**
     * Ends the sequence: puts the held slot back (deferred to the next packet interval when it cannot
     * be sent yet, never dropped), undoes an open inventory swap and releases the look-down request.
     */
    private void releaseSequence() {
        restoreOwnedSlot();
        // The potion's inventory swap can only be undone with the inventory open. With it closed the
        // swap is left as it is rather than clicking with no inventory open.
        boolean ownInventoryOpen = mc.player != null && mc.currentScreen instanceof InventoryScreen;
        if (didInventorySwap && ownInventoryOpen && inventoryStep != InventoryStep.OPEN) {
            SystemInputSimulator.clickSlotNow(mc.player.currentScreenHandler.syncId,
                    inventoryHandlerSlot, 0, SlotActionType.SWAP);
        }
        if (ownInventoryOpen && inventoryStep != InventoryStep.OPEN) {
            mc.player.closeHandledScreen();
        }
        RotationManager.clearTarget(this);
        reset();
    }

    @Override
    public void onTick() {
        if (mc.player == null || mc.world == null) return;

        switch (currentState) {
            case IDLE:
                // Starts only with no screen open: the throw cannot happen behind one, and the
                // inventory path opens (and closes) the inventory itself.
                if (topUp && mc.player.getHealth() >= potToHealth.getPreciseValue()) topUp = false;
                if (mc.currentScreen == null && (mc.player.getHealth() <= health.getPreciseValue() || topUp)) {
                    int hotbarSlot = findPotionInHotbar();
                    if (hotbarSlot != -1) {
                        potionSlot = hotbarSlot;
                        didInventorySwap = false;
                        inventoryHandlerSlot = -1;
                        originalSlot = mc.player.getInventory().getSelectedSlot();
                        pauseAimAssist();
                        engageRotation();
                        rotatingSinceMs = System.currentTimeMillis();
                        currentState = State.ROTATING;
                    } else {
                        int invHandler = findPotionInInventory();
                        if (invHandler != -1) {
                            inventoryHandlerSlot = invHandler;
                            originalSlot = mc.player.getInventory().getSelectedSlot();
                            inventoryStep = InventoryStep.OPEN;
                            currentState = State.MOVING_TO_HOTBAR;
                        } else {
                            topUp = false; // out of potions: stop topping up
                        }
                    }
                }
                break;

            case MOVING_TO_HOTBAR:
                // Swap the main-inventory potion (handler slot 9-35) into hotbar[0] the way a player
                // does it: open the inventory, press 1 over the potion, close the inventory.
                if (!stepInventorySwap()) break;
                if (!didInventorySwap) {
                    reset(); // the inventory did not stay open, so nothing moved
                    break;
                }
                potionSlot = 0;
                pauseAimAssist();
                engageRotation();
                rotatingSinceMs = System.currentTimeMillis();
                currentState = State.ROTATING;
                break;

            case ROTATING:
                pauseAimAssist();
                if (System.currentTimeMillis() - rotatingSinceMs > ROTATE_TIMEOUT_MS) {
                    // The look-down never completed (request refused or lost): give up cleanly.
                    topUp = false;
                    releaseSequence();
                    break;
                }
                // Re-request every tick so a refused or dropped request is picked up again.
                engageRotation();
                if (RotationManager.getFinalPitch() >= 85.0f) {
                    currentState = State.USING;
                }
                break;

            case USING:
                // Re-check the look-down right before the throw, then select the potion and throw it in
                // this same tick. Stays here while the use cooldown or packet order says wait.
                pauseAimAssist();
                if (RotationManager.getFinalPitch() < 85.0f) {
                    rotatingSinceMs = System.currentTimeMillis();
                    currentState = State.ROTATING;
                    break;
                }
                if (mc.currentScreen != null || !InteractSim.useReady(mc)) break;
                if (mc.player.getInventory().getSelectedSlot() != potionSlot) {
                    // The swap must lead every action in this interval; SlotSync.select does not check that.
                    if (!TickActionOrder.canSwitchSlot() || !SlotSync.select(mc, potionSlot)
                            || mc.player.getInventory().getSelectedSlot() != potionSlot) break;
                }
                ClickSimController.use(mc);
                returnAtMs = System.currentTimeMillis() + ModuleDelays.clampMs(swapBackDelay.sample());
                currentState = State.RETURNING_SLOT;
                break;

            case RETURNING_SLOT:
                // From the tick after the throw: release the look-down request, then put the held slot
                // back once the Swap Back Delay has passed.
                RotationManager.clearTarget(this);
                if (System.currentTimeMillis() < returnAtMs) break;
                restoreOwnedSlot();
                inventoryStep = InventoryStep.OPEN;
                currentState = didInventorySwap ? State.RESTORING_INVENTORY : State.COOLDOWN;
                break;

            case RESTORING_INVENTORY:
                // A hotbar change cannot be sent with the inventory open, so a restore still waiting
                // on packet order has to land before the inventory opens to swap the potion slot back.
                if (inventoryStep == InventoryStep.OPEN
                        && mc.player.getInventory().getSelectedSlot() != originalSlot
                        && mc.player.getInventory().getSelectedSlot() == potionSlot) {
                    restoreOwnedSlot();
                    break;
                }
                if (stepInventorySwap()) {
                    // Swapped back, or the inventory did not stay open: either way this heal is over.
                    currentState = State.COOLDOWN;
                }
                break;

            case COOLDOWN:
                // Give the splash a moment to land, then either stop (health reached Pot To Health) or go
                // round again for another potion.
                if (cooldownEndMs == 0L) cooldownEndMs = System.currentTimeMillis() + HEAL_SETTLE_MS;
                if (System.currentTimeMillis() < cooldownEndMs) break;
                topUp = mc.player.getHealth() < potToHealth.getPreciseValue();
                reset();
                break;
        }
    }

    /**
     * Advances the swap of {@code inventoryHandlerSlot} with hotbar slot 0 through the open inventory
     * by one step. Returns true once the sequence is over; {@link #didInventorySwap} then says whether
     * the two slots are exchanged. Another screen, or the player closing the inventory first, ends the
     * sequence without a click.
     */
    private boolean stepInventorySwap() {
        if (System.currentTimeMillis() < nextInventoryActionMs) return false;
        switch (inventoryStep) {
            case OPEN:
                if (mc.currentScreen != null) return true;
                mc.setScreen(new InventoryScreen(mc.player));
                inventoryStep = InventoryStep.CLICK;
                break;
            case CLICK:
                if (!(mc.currentScreen instanceof InventoryScreen)) {
                    inventoryStep = InventoryStep.OPEN;
                    return true;
                }
                if (SystemInputSimulator.clickSlotNow(mc.player.currentScreenHandler.syncId,
                        inventoryHandlerSlot, 0, SlotActionType.SWAP)) {
                    didInventorySwap = !didInventorySwap;
                }
                inventoryStep = InventoryStep.CLOSE;
                break;
            case CLOSE:
                if (mc.currentScreen instanceof InventoryScreen) {
                    mc.player.closeHandledScreen();
                }
                inventoryStep = InventoryStep.OPEN;
                return true;
        }
        nextInventoryActionMs = System.currentTimeMillis() + ModuleDelays.clampMs(throwDelay.sample());
        return false;
    }

    /** Puts the held slot back, only while the potion slot this module selected is still the held one. */
    private void restoreOwnedSlot() {
        if (originalSlot != -1 && potionSlot != -1 && mc.player != null
                && mc.player.getInventory().getSelectedSlot() == potionSlot) {
            SlotSync.restore(mc, originalSlot);
        }
    }

    private void engageRotation() {
        RotationManager.setRotationSupplier(
                this,
                RotationManager.Priority.HIGH,
                () -> {
                    float yaw = mc.player.getYaw();
                    double rad = Math.toRadians(yaw);
                    double dx = -Math.sin(rad) * 0.1;
                    double dz = Math.cos(rad) * 0.1;
                    return mc.player.getEntityPos().add(dx, -5, dz);
                },
                strength.getValue(),
                getRotationMode(),
                rotRandom.getValue(),
                silentRotation.isEnabled(),
                false
        );
    }

    // Asks AimAssist to stand down while the potion is aimed, through its own pause API; this module
    // never releases another module's rotation request. Our HIGH-priority request wins regardless, and
    // AimAssist resumes on its own once the short pause lapses after the throw.
    private void pauseAimAssist() {
        if (aimAssist != null && aimAssist.isEnabled()) {
            aimAssist.externalPause(AIM_ASSIST_PAUSE_MS);
        }
    }

    private RotationManager.RotationMode getRotationMode() {
        try {
            RotationManager.RotationMode mode = RotationManager.RotationMode.valueOf(rotPattern.getCurrentMode().toUpperCase());
            // A saved "Instant" pattern from an older config is never a snap.
            return mode == RotationManager.RotationMode.INSTANT ? RotationManager.RotationMode.SINE : mode;
        } catch (Exception e) {
            return RotationManager.RotationMode.SINE;
        }
    }

    private int findPotionInHotbar() {
        for (int i = 0; i < 9; i++) {
            if (isHealingPotion(mc.player.getInventory().getStack(i))) return i;
        }
        return -1;
    }

    /**
     * Returns the PlayerScreenHandler slot index (9-35) of a healing splash potion in main
     * inventory. Slot layout: 5-8=armor, 9-35=main inventory, 36-44=hotbar, 45=offhand.
     */
    private int findPotionInInventory() {
        for (int i = 9; i < 36; i++) {
            if (isHealingPotion(mc.player.getInventory().getStack(i))) return i;
        }
        return -1;
    }

    private boolean isHealingPotion(ItemStack stack) {
        if (stack.getItem() != Items.SPLASH_POTION) return false;
        PotionContentsComponent contents = stack.get(DataComponentTypes.POTION_CONTENTS);
        if (contents == null) return false;
        Optional<RegistryEntry<Potion>> opt = contents.potion();
        if (opt.isEmpty()) return false;
        RegistryEntry<Potion> entry = opt.get();
        return entry.matches(Potions.HEALING) || entry.matches(Potions.STRONG_HEALING);
    }

    private void reset() {
        this.currentState = State.IDLE;
        this.inventoryStep = InventoryStep.OPEN;
        this.nextInventoryActionMs = 0L;
        this.potionSlot = -1;
        this.originalSlot = -1;
        this.inventoryHandlerSlot = -1;
        this.didInventorySwap = false;
        this.returnAtMs = 0L;
        this.rotatingSinceMs = 0L;
        this.cooldownEndMs = 0L;
    }
}
