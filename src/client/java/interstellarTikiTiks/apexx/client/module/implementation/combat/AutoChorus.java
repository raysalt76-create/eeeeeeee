package interstellarTikiTiks.apexx.client.module.implementation.combat;

import interstellarTikiTiks.apexx.client.util.combat.TickActionOrder;
import interstellarTikiTiks.apexx.client.util.combat.ModuleDelays;
import interstellarTikiTiks.apexx.client.mixin.PlayerInventoryAccessor;
import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.SubCategory;
import interstellarTikiTiks.apexx.client.module.ModeSetting;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import interstellarTikiTiks.apexx.client.util.SystemInputSimulator;
import net.minecraft.item.Item;
import net.minecraft.item.Items;

/**
 * AutoChorus — Automated Chorus Fruit Consumer.
 *
 * <p>Switches to Chorus Fruit, holds consumption until teleportation occurs, and restores
 * your previous weapon/item slot to escape combos or evade lethal strikes.
 */
public class AutoChorus extends Module implements interstellarTikiTiks.apexx.client.module.hooks.ClickSimulating {

    public static AutoChorus INSTANCE;

    private final ModeSetting activation = new ModeSetting("Activation", "TOGGLE", "TOGGLE", "HOLD", "AUTO_HEALTH")
            .setDescription("TOGGLE: eats once each time the module is turned on. HOLD: keeps eating (again as the chorus cooldown ends) while the keybind is held, and turns off when it is let go. AUTO_HEALTH: eats by itself whenever your health drops below Health Threshold.");
    private final SliderSetting healthThreshold = new SliderSetting("Health Threshold", 8.0, 1.0, 20.0, 1)
            .setDescription("Health (in half-hearts, 20 = full) under which AUTO_HEALTH eats a chorus fruit to teleport away.");
    private final BooleanSetting autoSwitch = new BooleanSetting("Auto Switch", true)
            .setDescription("Selects a chorus fruit from your hotbar for you. Off: only eats when you are already holding one.");

    // ---- Delays ----
    private final SliderSetting restoreDelay = new SliderSetting("Swap Back Delay (ms)", 30, 0, 300, 0)
            .withJitter(8)
            .setDescription("Wait after the chorus fruit is eaten before switching back to your previous slot, in ms. "
                    + "The switch back always comes on a later tick than the eat, so even 0 is not glued to it.")
            .setSection("Delays");

    private enum State {
        IDLE,
        EATING,
        WAITING_RESTORE
    }

    private State state = State.IDLE;
    private int originalSlot = -1;
    private int chorusSlot = -1;
    private long stateStartTime = 0L;
    private boolean useStarted;
    private int initialCount = 0;
    private net.minecraft.util.Hand eatingHand = net.minecraft.util.Hand.MAIN_HAND;
    private Object boundPlayer;
    private Object boundWorld;
    private long restoreAt;

    public AutoChorus() {
        super("AutoChorus", "Eats a chorus fruit to teleport out of a losing combo, either on your key or automatically at low health, then switches you back to your previous item.", Category.AUTOMATIONS);
        setSubCategory(SubCategory.SMP);
        INSTANCE = this;
        addSetting(activation);
        addSetting(healthThreshold);
        addSetting(autoSwitch);
        addSetting(restoreDelay);
    }

    @Override
    public void onEnable() {
        resetState();
    }

    @Override
    protected void onRuntimeStateReleased() {
        releaseUseKey();
        restoreOriginalSlot();
        resetState();
    }

    private void resetState() {
        state = State.IDLE;
        originalSlot = -1;
        chorusSlot = -1;
        stateStartTime = 0L;
        useStarted = false;
        initialCount = 0;
        boundPlayer = boundWorld = null;
        restoreAt = 0L;
    }

    @Override
    public void onTick() {
        if (mc.player == null || mc.world == null || !mc.player.isAlive() || mc.currentScreen != null
                || (state != State.IDLE && (mc.player != boundPlayer || mc.world != boundWorld))) {
            releaseRuntimeState();
            return;
        }

        long now = System.currentTimeMillis();
        String mode = activation.getCurrentMode();
        boolean holdMode = "HOLD".equalsIgnoreCase(mode);

        switch (state) {
            case IDLE -> {
                // HOLD: eats only while the key is held; letting go turns the module off.
                if (holdMode && !keyHeld()) {
                    setEnabled(false);
                    return;
                }
                boolean trigger = false;
                if ("AUTO_HEALTH".equalsIgnoreCase(mode)) {
                    float hp = mc.player.getHealth() + mc.player.getAbsorptionAmount();
                    if (hp <= healthThreshold.getValue()) {
                        trigger = true;
                    }
                } else {
                    trigger = true;
                }

                if (!trigger) return;

                int slot = findHotbarSlot(Items.CHORUS_FRUIT);
                if (slot == -1 && !mc.player.getMainHandStack().isOf(Items.CHORUS_FRUIT) && !mc.player.getOffHandStack().isOf(Items.CHORUS_FRUIT)) {
                    if ("AUTO_HEALTH".equalsIgnoreCase(mode)) return;
                    setEnabled(false);
                    return;
                }

                if (!autoSwitch.isEnabled() && !mc.player.getMainHandStack().isOf(Items.CHORUS_FRUIT)
                        && !mc.player.getOffHandStack().isOf(Items.CHORUS_FRUIT)) return;
                if (mc.player.isUsingItem()
                        || !interstellarTikiTiks.apexx.client.util.combat.InteractSim.useReady(mc)
                        || mc.player.getItemCooldownManager().isCoolingDown(Items.CHORUS_FRUIT.getDefaultStack())) return;
                boundPlayer = mc.player;
                boundWorld = mc.world;
                rememberOriginalSlot();
                if (slot != -1 && autoSwitch.isEnabled()) {
                    if (!TickActionOrder.canSwitchSlot()) return;
                    if (!interstellarTikiTiks.apexx.client.util.combat.SlotSync.select(mc, slot)) {
                        releaseRuntimeState();
                        return;
                    }
                }
                chorusSlot = mc.player.getInventory().getSelectedSlot();
                eatingHand = mc.player.getMainHandStack().isOf(Items.CHORUS_FRUIT)
                        ? net.minecraft.util.Hand.MAIN_HAND : net.minecraft.util.Hand.OFF_HAND;
                initialCount = mc.player.getStackInHand(eatingHand).getCount();
                state = State.EATING;
                stateStartTime = now;
                interstellarTikiTiks.apexx.client.util.combat.SubTickSequencer.queueAction(this,
                        () -> {
                            if (mc.player == boundPlayer && mc.world == boundWorld && mc.currentScreen == null
                                    && mc.player.getInventory().getSelectedSlot() == chorusSlot
                                    && mc.player.getStackInHand(eatingHand).isOf(Items.CHORUS_FRUIT)) {
                                pressUseKey();
                                useStarted = true;
                            }
                        }, interstellarTikiTiks.apexx.client.util.combat.SubTickSequencer.sampleMicroDelayMs());
            }
            case EATING -> {
                // A missed originating-tick start is cancelled, not retried with a stale slot.
                if (!useStarted) {
                    releaseRuntimeState();
                    return;
                }
                boolean countDecreased = mc.player.getStackInHand(eatingHand).getCount() < initialCount;
                boolean notHolding = !mc.player.getStackInHand(eatingHand).isOf(Items.CHORUS_FRUIT)
                        || mc.player.getInventory().getSelectedSlot() != chorusSlot;

                boolean holdReleased = holdMode && !keyHeld();
                if (countDecreased || notHolding || holdReleased || now - stateStartTime > 2500L
                        || mc.player.getItemCooldownManager().isCoolingDown(Items.CHORUS_FRUIT.getDefaultStack())) {
                    releaseUseKey();
                    state = State.WAITING_RESTORE;
                    stateStartTime = now;
                    restoreAt = now + ModuleDelays.clampMs(restoreDelay.sample());
                } else {
                    pressUseKey();
                }
            }
            case WAITING_RESTORE -> {
                if (now >= restoreAt) {
                    restoreOriginalSlot();
                    resetState();
                    if ("TOGGLE".equalsIgnoreCase(mode) || (holdMode && !keyHeld())) {
                        setEnabled(false);
                    }
                }
            }
        }
    }

    /** HOLD: the module's keybind is held; without a keybind, being enabled counts as holding. */
    private boolean keyHeld() {
        int key = getKeyCode();
        if (key == -1 || key == 0) return true;
        return isBindHeld();
    }

    private void pressUseKey() {
        if (mc.options != null && mc.options.useKey != null) {
            SystemInputSimulator.pressUse();
            TickActionOrder.recordUse();
        }
    }

    private void releaseUseKey() {
        if (mc.options != null && mc.options.useKey != null) {
            SystemInputSimulator.releaseUse();
        }
    }

    private int findHotbarSlot(Item item) {
        if (mc.player == null) return -1;
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).isOf(item)) {
                return i;
            }
        }
        return -1;
    }

    private void rememberOriginalSlot() {
        if (mc.player == null) return;
        if (originalSlot == -1) {
            originalSlot = ((PlayerInventoryAccessor) mc.player.getInventory()).getSelectedSlot();
        }
    }

    private void restoreOriginalSlot() {
        if (mc.player != null && mc.player == boundPlayer && mc.world == boundWorld && originalSlot != -1
                && mc.player.getInventory().getSelectedSlot() == chorusSlot) {
            interstellarTikiTiks.apexx.client.util.combat.SlotSync.restore(mc, originalSlot);
        }
        originalSlot = -1;
    }
}
