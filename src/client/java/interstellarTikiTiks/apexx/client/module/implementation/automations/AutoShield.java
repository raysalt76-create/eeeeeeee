package interstellarTikiTiks.apexx.client.module.implementation.automations;

import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.ModeSetting;
import interstellarTikiTiks.apexx.client.module.implementation.combat.AutoMace;
import interstellarTikiTiks.apexx.client.util.ModuleAccess;
import interstellarTikiTiks.apexx.client.util.SystemInputSimulator;
import interstellarTikiTiks.apexx.client.util.combat.CombatItemUtil;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.ShieldItem;

/**
 * Hold the bind to swap to the hotbar shield and hold the use key; release to swap back.
 *
 * <p>"Right Click Shield" (off by default) adds the older behaviour: holding the physical use button
 * while a weapon is selected swaps to the shield and blocks until the button is released. A shield
 * already in the offhand needs no swap, so only the use key is held.</p>
 *
 * <p>AutoMace's "Auto Unblock" still applies: it drops the use key on the dive and re-raises it
 * afterwards; while it holds the shield down this module does not re-press the key.</p>
 */
public class AutoShield extends HotbarHoldModule {
    private final BooleanSetting rightClickShield = new BooleanSetting("Right Click Shield", false)
            .setDescription("While the module is on, holding right click with a weapon selected swaps to your hotbar shield and blocks; letting go swaps back to the weapon. Works without the keybind. A shield already in the offhand blocks natively, so nothing is swapped.");
    private final ModeSetting weaponType = new ModeSetting("Weapon Type", "Both", "Both", "Swords", "Axes", "All Weapons")
            .setDescription("Which held weapons let Right Click Shield take over: Both = swords and axes, or only swords, only axes, or every weapon type including maces and spears.")
            .setDependsOn("Right Click Shield");
    private final interstellarTikiTiks.apexx.client.module.SliderSetting rightClickDelay =
            new interstellarTikiTiks.apexx.client.module.SliderSetting("Right Click Delay (ms)", 60, 0, 300, 0)
                    .withJitter(15)
                    .setDescription("How long right click must be held before Right Click Shield swaps to the shield. Longer values let quick right-clicks (eating, placing) through untouched.")
                    .setDependsOn("Right Click Shield");

    private long requestTime;
    private long requestDelayMs;
    /** A session ended while the use button was still held: no new one until it is released and pressed again. */
    private boolean rightClickLatched;

    public AutoShield() {
        super("AutoShield", "Hold the bind to block with your shield without managing your hotbar: it selects the hotbar shield, holds it up while the key is down and returns to your previous item when you let go. Uses an offhand shield directly when you have one. Binding it to the shield's own hotbar key also works.");
        addSetting(rightClickShield);
        addSetting(weaponType);
        rightClickDelay.setSection("Delays");
        addSetting(rightClickDelay);
    }

    /** True while this module holds a shield up, from the bind or from Right Click Shield. */
    public static boolean isShielding() {
        try {
            AutoShield module = ModuleAccess.getModule(AutoShield.class);
            return module != null && module.isHolding();
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Override
    protected boolean isTarget(ItemStack stack) {
        return stack != null && !stack.isEmpty() && (stack.getItem() instanceof ShieldItem || stack.isOf(Items.SHIELD));
    }

    @Override protected String itemLabel() { return "shield"; }
    @Override protected boolean allowOffhand() { return true; }
    @Override protected boolean isHoldSuppressed() { return AutoMace.isOwnShieldSuppressed(); }

    @Override
    public void onTick() {
        super.onTick();
        if (isHolding() || !rightClickShield.isEnabled()) {
            requestTime = 0L;
            return;
        }
        tickRightClick();
    }

    @Override
    protected void onSessionEnd() {
        // One session per press (see HotbarHoldModule): a stop with the button still down (slot taken
        // by AutoMace, limit, cancel) must not re-swap and re-raise every tick.
        rightClickLatched = mc.options != null && mc.options.useKey != null
                && SystemInputSimulator.isPhysicallyPressed(mc.options.useKey);
    }

    private void tickRightClick() {
        if (mc.player == null || mc.world == null || mc.currentScreen != null || mc.options == null
                || !SystemInputSimulator.isPhysicallyPressed(mc.options.useKey)) {
            requestTime = 0L;
            rightClickLatched = false;
            return;
        }
        // AutoMace's Auto Unblock has the shield down on purpose; do not swap to it or re-raise it.
        if (rightClickLatched || isHoldSuppressed()) {
            requestTime = 0L;
            return;
        }
        // A shield in the offhand blocks natively on right click; nothing to do.
        if (isTarget(mc.player.getOffHandStack()) || !isEligibleWeapon(mc.player.getMainHandStack())
                || findSlot() < 0) {
            requestTime = 0L;
            return;
        }
        long now = System.currentTimeMillis();
        if (requestTime == 0L) {
            requestTime = now;
            requestDelayMs = Math.round(rightClickDelay.sample());
            return;
        }
        if (now - requestTime < requestDelayMs) return;
        if (startSession(true)) requestTime = 0L;
    }

    private boolean isEligibleWeapon(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        return switch (weaponType.getCurrentMode()) {
            case "Swords" -> CombatItemUtil.isSword(stack);
            case "Axes" -> CombatItemUtil.isAxe(stack);
            case "All Weapons" -> CombatItemUtil.isWeapon(stack);
            default -> CombatItemUtil.isSword(stack) || CombatItemUtil.isAxe(stack);
        };
    }
}
