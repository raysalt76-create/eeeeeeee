package interstellarTikiTiks.apexx.client.module.implementation.render;

import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import interstellarTikiTiks.apexx.client.util.Notifier;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.sound.SoundEvents;

/**
 * Warns once when a worn armour piece drops to or below its durability threshold. The warning re-arms
 * after the piece is repaired above the threshold or replaced.
 */
public final class ArmorWarnings extends Module {
    private static final String THRESHOLDS = "Thresholds";
    private static final String ALERT = "Alert";
    /** Re-arm margin so mending flicker around the threshold cannot spam warnings. */
    private static final double REARM_MARGIN = 2.0;

    private static final EquipmentSlot[] SLOTS = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
    private static final String[] NAMES = {"Helmet", "Chestplate", "Leggings", "Boots"};

    private final SliderSetting helmet = threshold("Helmet");
    private final SliderSetting chest = threshold("Chestplate");
    private final SliderSetting legs = threshold("Leggings");
    private final SliderSetting boots = threshold("Boots");
    private final SliderSetting[] thresholds = {helmet, chest, legs, boots};

    private final BooleanSetting warnOnEquip = new BooleanSetting("Warn On Equip", true)
            .setDescription("Also warns when you put on a piece that is already at or below its threshold.")
            .setSection(ALERT);
    private final BooleanSetting sound = new BooleanSetting("Sound", true)
            .setDescription("Plays a short alert sound with each warning (only you hear it).")
            .setSection(ALERT);
    private final SliderSetting volume = new SliderSetting("Volume", 0.8, 0.1, 1.0, 2)
            .setDescription("Loudness of the alert sound.")
            .setDependsOn("Sound")
            .setSection(ALERT);

    private final Item[] lastItem = new Item[SLOTS.length];
    private final boolean[] warned = new boolean[SLOTS.length];
    private ClientWorld lastWorld;
    private boolean primed;

    public ArmorWarnings() {
        super("ArmorWarnings", "Pops a notification (and an optional sound) when a piece of armour you are wearing "
                + "drops to its durability threshold, once per drop, so you can repair or swap it in time. Needs the "
                + "Notifications module for the pop-up.", Category.RENDER);
        for (SliderSetting s : thresholds) addSetting(s);
        addSetting(warnOnEquip);
        addSetting(sound);
        addSetting(volume);
    }

    private static SliderSetting threshold(String piece) {
        return new SliderSetting(piece + " Threshold", 20, 1, 100, 0)
                .setDescription("Warn when your " + piece.toLowerCase(java.util.Locale.ROOT)
                        + " has this much durability left or less, in percent.")
                .setSection(THRESHOLDS);
    }

    @Override
    public boolean rendersInWorld() {
        return false;
    }

    @Override
    public void onEnable() {
        reset();
    }

    @Override
    public void onDisable() {
        reset();
    }

    @Override
    protected void onRuntimeStateReleased() {
        reset();
    }

    private void reset() {
        java.util.Arrays.fill(lastItem, null);
        java.util.Arrays.fill(warned, false);
        lastWorld = null;
        primed = false;
    }

    @Override
    public void onTick() {
        if (mc.player == null || mc.world == null) return;
        if (mc.world != lastWorld) {
            // New world or respawn into another dimension: take a silent snapshot first so pieces that are
            // already low do not all fire at once on join.
            lastWorld = mc.world;
            primed = false;
        }
        boolean silent = !primed;
        primed = true;

        for (int i = 0; i < SLOTS.length; i++) {
            ItemStack stack = mc.player.getEquippedStack(SLOTS[i]);
            Item item = stack.isEmpty() ? null : stack.getItem();
            boolean changed = item != lastItem[i];
            lastItem[i] = item;
            if (item == null || !stack.isDamageable() || stack.getMaxDamage() <= 0) {
                warned[i] = false;
                continue;
            }
            double percent = 100.0 * (stack.getMaxDamage() - stack.getDamage()) / stack.getMaxDamage();
            double limit = thresholds[i].getValue();
            if (changed) {
                boolean low = percent <= limit;
                warned[i] = low;
                if (low && !silent && warnOnEquip.isEnabled()) warn(i, percent);
                continue;
            }
            if (!warned[i] && percent <= limit) {
                warned[i] = true;
                if (!silent) warn(i, percent);
            } else if (warned[i] && percent > limit + REARM_MARGIN) {
                warned[i] = false;
            }
        }
    }

    private void warn(int index, double percent) {
        Notifier.warn("ArmorWarnings", NAMES[index] + " is at " + Math.max(0, (int) Math.floor(percent)) + "% durability");
        if (sound.isEnabled()) {
            try {
                mc.getSoundManager().play(PositionedSoundInstance.ui(SoundEvents.BLOCK_ANVIL_LAND, 1.6f,
                        (float) volume.getValue()));
            } catch (Throwable ignored) {
            }
        }
    }
}
