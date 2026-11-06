package interstellarTikiTiks.apexx.client.util.combat;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.MaceItem;
import net.minecraft.item.TridentItem;
import net.minecraft.registry.tag.ItemTags;

public final class CombatItemUtil {
    private CombatItemUtil() {
    }

    /** Any melee weapon: the strict weapons plus other tools that carry attack attributes. */
    public static boolean isWeapon(ItemStack stack) {
        if (isStrictWeapon(stack)) {
            return true;
        }
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        Item item = stack.getItem();
        return item.getComponents().contains(DataComponentTypes.TOOL)
                && item.getComponents().contains(DataComponentTypes.ATTRIBUTE_MODIFIERS);
    }

    /** Sword, axe, mace, spear or trident only (not every tool that carries attack attributes). */
    public static boolean isStrictWeapon(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        Item item = stack.getItem();
        return stack.isIn(ItemTags.SWORDS) || stack.isIn(ItemTags.AXES)
                || item instanceof MaceItem || item instanceof TridentItem
                || net.minecraft.registry.Registries.ITEM.getId(item).getPath().contains("spear");
    }

    public static boolean isSword(ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.isIn(ItemTags.SWORDS);
    }

    public static boolean isAxe(ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.isIn(ItemTags.AXES);
    }

    public static boolean isMace(ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.getItem() instanceof MaceItem;
    }
}
