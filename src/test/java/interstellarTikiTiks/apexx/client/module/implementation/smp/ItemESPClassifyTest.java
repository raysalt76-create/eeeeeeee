package interstellarTikiTiks.apexx.client.module.implementation.smp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class ItemESPClassifyTest {

    @Test
    public void classifiesValuablesByRegistryPath() {
        assertEquals(ItemESP.Kind.NETHERITE, ItemESP.classifyPath("netherite_sword", false));
        assertEquals(ItemESP.Kind.NETHERITE, ItemESP.classifyPath("netherite_upgrade_smithing_template", false));
        assertEquals(ItemESP.Kind.NETHERITE, ItemESP.classifyPath("ancient_debris", false));
        assertEquals(ItemESP.Kind.DIAMOND, ItemESP.classifyPath("diamond", false));
        assertEquals(ItemESP.Kind.DIAMOND, ItemESP.classifyPath("diamond_chestplate", false));
        assertEquals(ItemESP.Kind.TOTEM, ItemESP.classifyPath("totem_of_undying", false));
        assertEquals(ItemESP.Kind.ELYTRA, ItemESP.classifyPath("elytra", false));
        assertEquals(ItemESP.Kind.ENCHANTED, ItemESP.classifyPath("enchanted_book", true));
        assertEquals(ItemESP.Kind.RARE, ItemESP.classifyPath("enchanted_golden_apple", false));
        assertEquals(ItemESP.Kind.RARE, ItemESP.classifyPath("purple_shulker_box", false));
        assertEquals(ItemESP.Kind.RARE, ItemESP.classifyPath("mace", false));
        assertEquals(ItemESP.Kind.COMMON, ItemESP.classifyPath("cobblestone", false));
        assertEquals(ItemESP.Kind.COMMON, ItemESP.classifyPath(null, false));
    }
}
