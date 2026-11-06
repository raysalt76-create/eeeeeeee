package interstellarTikiTiks.apexx.client.module.implementation.smp;

import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.hooks.ItemUseSlowdownSuppression;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.consume.UseAction;

/**
 * NoSlow (Blatant) - keeps full movement speed while using an item. Vanilla scales the movement
 * input by 0.2 while eating, drinking, blocking or drawing a bow; this skips that factor on the
 * client, so the player's own movement is what the server then sees.
 *
 * <p>Servers with movement checks compare the speed against the item-use slowdown and will flag it.</p>
 */
public final class NoSlow extends Module implements ItemUseSlowdownSuppression {

    private final BooleanSetting food = new BooleanSetting("Food and Drinks", true)
            .setDescription("No slowdown while eating or drinking.");
    private final BooleanSetting shield = new BooleanSetting("Shield", true)
            .setDescription("No slowdown while blocking with a shield.");
    private final BooleanSetting ranged = new BooleanSetting("Bows and Crossbows", true)
            .setDescription("No slowdown while drawing a bow or loading a crossbow.");
    private final BooleanSetting other = new BooleanSetting("Other Items", true)
            .setDescription("No slowdown for everything else that is held down: trident, spear, spyglass, goat horn, brush.");

    public NoSlow() {
        super("NoSlow", "Keeps your full walking speed while eating, drinking, blocking with a shield or drawing a bow. Blatant: any server with movement checks will flag it.", Category.BLATANT);
        addSetting(food);
        addSetting(shield);
        addSetting(ranged);
        addSetting(other);
    }

    @Override
    public boolean suppressItemUseSlowdown(ClientPlayerEntity player) {
        if (player == null || !player.isUsingItem()) return false;
        ItemStack stack = player.getActiveItem();
        UseAction action = stack.getUseAction();
        return switch (action) {
            case EAT, DRINK -> food.isEnabled();
            case BLOCK -> shield.isEnabled();
            case BOW, CROSSBOW -> ranged.isEnabled();
            default -> other.isEnabled();
        };
    }
}
