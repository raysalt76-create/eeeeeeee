package interstellarTikiTiks.apexx.client.module.implementation.player;

import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import interstellarTikiTiks.apexx.client.util.combat.ModuleDelays;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.EquippableComponent;
import net.minecraft.component.type.PotionContentsComponent;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.effect.StatusEffectCategory;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;


/**
 * Intelligent inventory cleaner that identifies superior armor and tools while
 * disposing of junk, duplicate items, and negative potions with authentic human click delays.
 */
public class InvCleaner extends Module implements interstellarTikiTiks.apexx.client.module.hooks.ClickSimulating {

    private final BooleanSetting bestItems = new BooleanSetting("Best Items", true)
            .setDescription("Keeps only your best piece of each armor slot and your best sword, axe, pickaxe, shovel and bow, and throws out the weaker duplicates. Enchanted, renamed and custom-data items are never thrown.");
    private final BooleanSetting removeNegativePots = new BooleanSetting("Remove Bad Potions", true)
            .setDescription("Throws out drinkable, splash and lingering potions whose effects are harmful (Poison, Weakness, Slowness, ...).");
    private final BooleanSetting dropJunk = new BooleanSetting("Drop Junk", true)
            .setDescription("Throws out common mob-drop clutter: rotten flesh, poisonous potatoes, string, spider eyes and bones.");
    private final BooleanSetting inventoryOnly = new BooleanSetting("Inventory Only", true)
            .setDescription("Only cleans while your own inventory screen is open, which is the only time a player could click items out. Turning this off also cleans with no screen open.");

    // ---- Delays ----
    /** Kept unregistered so old configs that saved it still load; the sliders below are always used. */
    private final BooleanSetting useProfile = new BooleanSetting("Use Timing Profile", false);
    private final SliderSetting openDelay = new SliderSetting("Open Delay (ms)", 150, 0, 1000, 0)
            .withJitter(40)
            .setDescription("How long to wait after the inventory opens before the first item is thrown, like a player looking over their items first.")
            .setDependsOn("Inventory Only");
    private final SliderSetting dropDelay = new SliderSetting("Drop Delay (ms)", 70, 0, 500, 0)
            .withJitter(20)
            .setDescription("Wait between two thrown items. Lower cleans faster; very low values look automated to servers that time inventory clicks.");

    private long nextActionTime = 0L;
    private boolean inventoryWasOpen = false;

    public InvCleaner() {
        super("InvCleaner", "Sorts out your inventory: throws away junk, harmful potions and weaker duplicate armor and tools, keeping the best of each, one click at a time with configurable randomised pacing.", Category.PLAYER);
        openDelay.setSection("Delays");
        dropDelay.setSection("Delays");
        addSetting(bestItems);
        addSetting(removeNegativePots);
        addSetting(dropJunk);
        addSetting(inventoryOnly);
        addSetting(openDelay);
        addSetting(dropDelay);
    }

    @Override
    public void onTick() {
        if (!isEnabled() || mc.player == null || mc.interactionManager == null) return;

        long now = System.currentTimeMillis();
        boolean inventoryOpen = mc.currentScreen instanceof InventoryScreen;
        if (inventoryOpen && !inventoryWasOpen && inventoryOnly.isEnabled()) {
            nextActionTime = Math.max(nextActionTime, now + ModuleDelays.clampMs(openDelay.sample()));
        }
        inventoryWasOpen = inventoryOpen;

        if (inventoryOnly.isEnabled() && !inventoryOpen) {
            return;
        }

        if (now < nextActionTime) return;

        PlayerScreenHandler handler = mc.player.playerScreenHandler;
        if (handler == null || mc.player.currentScreenHandler != handler) return;

        // Collect best equipment references
        ItemStack bestHelmet = null;
        ItemStack bestChestplate = null;
        ItemStack bestLeggings = null;
        ItemStack bestBoots = null;
        ItemStack bestSword = null;
        ItemStack bestAxe = null;
        ItemStack bestPickaxe = null;
        ItemStack bestShovel = null;
        ItemStack bestBow = null;

        if (bestItems.isEnabled()) {
            for (Slot slot : handler.slots) {
                if (!slot.hasStack()) continue;
                ItemStack stack = slot.getStack();
                if (isProtected(stack)) continue;

                EquipmentSlot armorSlot = getArmorSlot(stack);
                if (armorSlot != null) {
                    if (armorSlot == EquipmentSlot.HEAD && (bestHelmet == null || scoreArmor(stack) > scoreArmor(bestHelmet))) {
                        bestHelmet = stack;
                    } else if (armorSlot == EquipmentSlot.CHEST && (bestChestplate == null || scoreArmor(stack) > scoreArmor(bestChestplate))) {
                        bestChestplate = stack;
                    } else if (armorSlot == EquipmentSlot.LEGS && (bestLeggings == null || scoreArmor(stack) > scoreArmor(bestLeggings))) {
                        bestLeggings = stack;
                    } else if (armorSlot == EquipmentSlot.FEET && (bestBoots == null || scoreArmor(stack) > scoreArmor(bestBoots))) {
                        bestBoots = stack;
                    }
                } else if (isSword(stack)) {
                    if (bestSword == null || scoreWeapon(stack) > scoreWeapon(bestSword)) {
                        bestSword = stack;
                    }
                } else if (isAxe(stack)) {
                    if (bestAxe == null || scoreWeapon(stack) > scoreWeapon(bestAxe)) {
                        bestAxe = stack;
                    }
                } else if (isPickaxe(stack)) {
                    if (bestPickaxe == null || scoreTool(stack) > scoreTool(bestPickaxe)) {
                        bestPickaxe = stack;
                    }
                } else if (isShovel(stack)) {
                    if (bestShovel == null || scoreTool(stack) > scoreTool(bestShovel)) {
                        bestShovel = stack;
                    }
                } else if (isBow(stack)) {
                    if (bestBow == null || remainingDurability(stack) > remainingDurability(bestBow)) {
                        bestBow = stack;
                    }
                }
            }
        }

        // Iterate through inventory and hotbar slots (slots 9 to 44)
        for (int i = 9; i < 45; i++) {
            Slot slot = handler.getSlot(i);
            if (!slot.hasStack()) continue;
            ItemStack stack = slot.getStack();
            if (isProtected(stack)) continue;
            Item item = stack.getItem();

            boolean shouldDrop = false;

            // 1. Check duplicate / inferior tools & armor
            if (bestItems.isEnabled()) {
                EquipmentSlot armorSlot = getArmorSlot(stack);
                if (armorSlot != null) {
                    if (armorSlot == EquipmentSlot.HEAD && stack != bestHelmet) shouldDrop = true;
                    else if (armorSlot == EquipmentSlot.CHEST && stack != bestChestplate) shouldDrop = true;
                    else if (armorSlot == EquipmentSlot.LEGS && stack != bestLeggings) shouldDrop = true;
                    else if (armorSlot == EquipmentSlot.FEET && stack != bestBoots) shouldDrop = true;
                } else if (isSword(stack) && stack != bestSword) {
                    shouldDrop = true;
                } else if (isAxe(stack) && stack != bestAxe) {
                    shouldDrop = true;
                } else if (isPickaxe(stack) && stack != bestPickaxe) {
                    shouldDrop = true;
                } else if (isShovel(stack) && stack != bestShovel) {
                    shouldDrop = true;
                } else if (isBow(stack) && bestBow != null && stack.isOf(bestBow.getItem()) && stack != bestBow) {
                    shouldDrop = true;
                }
            }

            // 2. Check harmful potions
            if (!shouldDrop && removeNegativePots.isEnabled() && isNegativePotion(stack)) {
                shouldDrop = true;
            }

            // 3. Check junk items
            if (!shouldDrop && dropJunk.isEnabled() && isJunkItem(item)) {
                shouldDrop = true;
            }

            if (shouldDrop) {
                // Drop the item outside the inventory window
                interstellarTikiTiks.apexx.client.util.SystemInputSimulator.clickSlotBound(handler.syncId, i, 1, SlotActionType.THROW);

                nextActionTime = now + ModuleDelays.clampMs(dropDelay.sample());
                return;
            }
        }
    }

    @Override
    protected void onRuntimeStateReleased() {
        nextActionTime = 0L;
        inventoryWasOpen = false;
    }

    static boolean isProtected(ItemStack stack) {
        return stack.contains(DataComponentTypes.CUSTOM_NAME)
                || stack.contains(DataComponentTypes.CUSTOM_DATA)
                || !stack.getEnchantments().isEmpty();
    }

    private static int remainingDurability(ItemStack stack) {
        return Math.max(0, stack.getMaxDamage() - stack.getDamage());
    }

    private EquipmentSlot getArmorSlot(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        EquippableComponent equippable = stack.get(DataComponentTypes.EQUIPPABLE);
        if (equippable != null) {
            EquipmentSlot slot = equippable.slot();
            if (slot == EquipmentSlot.HEAD || slot == EquipmentSlot.CHEST || slot == EquipmentSlot.LEGS || slot == EquipmentSlot.FEET) {
                return slot;
            }
        }
        return null;
    }

    private boolean isSword(ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.isIn(ItemTags.SWORDS);
    }

    private boolean isAxe(ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.isIn(ItemTags.AXES);
    }

    private boolean isPickaxe(ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.isIn(ItemTags.PICKAXES);
    }

    private boolean isShovel(ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.isIn(ItemTags.SHOVELS);
    }

    private boolean isBow(ItemStack stack) {
        return stack != null && !stack.isEmpty() && (stack.isOf(Items.BOW) || stack.isOf(Items.CROSSBOW));
    }

    private double scoreArmor(ItemStack stack) {
        if (stack.isEmpty()) return 0.0;
        Item item = stack.getItem();
        double score = 0.0;
        if (item == Items.NETHERITE_HELMET || item == Items.NETHERITE_CHESTPLATE || item == Items.NETHERITE_LEGGINGS || item == Items.NETHERITE_BOOTS) score = 100.0;
        else if (item == Items.DIAMOND_HELMET || item == Items.DIAMOND_CHESTPLATE || item == Items.DIAMOND_LEGGINGS || item == Items.DIAMOND_BOOTS) score = 80.0;
        else if (item == Items.IRON_HELMET || item == Items.IRON_CHESTPLATE || item == Items.IRON_LEGGINGS || item == Items.IRON_BOOTS) score = 60.0;
        else if (item == Items.CHAINMAIL_HELMET || item == Items.CHAINMAIL_CHESTPLATE || item == Items.CHAINMAIL_LEGGINGS || item == Items.CHAINMAIL_BOOTS) score = 40.0;
        else if (item == Items.GOLDEN_HELMET || item == Items.GOLDEN_CHESTPLATE || item == Items.GOLDEN_LEGGINGS || item == Items.GOLDEN_BOOTS) score = 30.0;
        else if (item == Items.LEATHER_HELMET || item == Items.LEATHER_CHESTPLATE || item == Items.LEATHER_LEGGINGS || item == Items.LEATHER_BOOTS) score = 10.0;

        score += remainingDurability(stack) * 0.01;
        return score;
    }

    private double scoreWeapon(ItemStack stack) {
        if (stack.isEmpty()) return 0.0;
        Item item = stack.getItem();
        if (item == Items.NETHERITE_SWORD || item == Items.NETHERITE_AXE) return 100.0;
        if (item == Items.DIAMOND_SWORD || item == Items.DIAMOND_AXE) return 80.0;
        if (item == Items.IRON_SWORD || item == Items.IRON_AXE) return 60.0;
        if (item == Items.STONE_SWORD || item == Items.STONE_AXE) return 40.0;
        if (item == Items.GOLDEN_SWORD || item == Items.GOLDEN_AXE) return 30.0;
        if (item == Items.WOODEN_SWORD || item == Items.WOODEN_AXE) return 20.0;
        return 0.0;
    }

    private double scoreTool(ItemStack stack) {
        if (stack.isEmpty()) return 0.0;
        Item item = stack.getItem();
        if (item == Items.NETHERITE_PICKAXE || item == Items.NETHERITE_SHOVEL) return 100.0;
        if (item == Items.DIAMOND_PICKAXE || item == Items.DIAMOND_SHOVEL) return 80.0;
        if (item == Items.IRON_PICKAXE || item == Items.IRON_SHOVEL) return 60.0;
        if (item == Items.STONE_PICKAXE || item == Items.STONE_SHOVEL) return 40.0;
        if (item == Items.GOLDEN_PICKAXE || item == Items.GOLDEN_SHOVEL) return 30.0;
        if (item == Items.WOODEN_PICKAXE || item == Items.WOODEN_SHOVEL) return 20.0;
        return 0.0;
    }

    private boolean isNegativePotion(ItemStack stack) {
        if (!stack.isOf(Items.SPLASH_POTION) && !stack.isOf(Items.LINGERING_POTION) && !stack.isOf(Items.POTION)) {
            return false;
        }
        PotionContentsComponent contents = stack.get(DataComponentTypes.POTION_CONTENTS);
        if (contents == null) return false;

        for (StatusEffectInstance effect : contents.getEffects()) {
            if (effect.getEffectType().value().getCategory() == StatusEffectCategory.HARMFUL) {
                return true;
            }
        }
        return false;
    }

    private boolean isJunkItem(Item item) {
        return item == Items.ROTTEN_FLESH ||
                item == Items.POISONOUS_POTATO ||
                item == Items.STRING ||
                item == Items.SPIDER_EYE ||
                item == Items.BONE;
    }
}
