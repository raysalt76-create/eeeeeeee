package interstellarTikiTiks.apexx.client.cosmetics;

import java.util.List;

/** Every cosmetic the client can draw. All of them are unlocked for every signed-in user. */
public final class CosmeticCatalog {
    private CosmeticCatalog() { }

    public record Item(String id, String name, String description, int defaultColor) { }

    public static final List<Item> HATS = List.of(
            new Item("halo", "Halo", "A glowing golden ring floating above your head.", 0xFFE27A),
            new Item("nimbus", "Nimbus", "Two comet-like sparks circling above your head.", CosmeticSelection.THEME_COLOR),
            new Item("crown", "Crown", "A gold crown with jewelled points.", 0xF5C542),
            new Item("horns", "Horns", "Curved horns that sweep back from your head.", 0x3A0D0D),
            new Item("cat_ears", "Cat Ears", "Pointed cat ears with soft inner fur.", 0x2B2B2B),
            new Item("fox_ears", "Fox Ears", "Tall fox ears with white tips.", 0xE0702A),
            new Item("bunny_ears", "Bunny Ears", "Long floppy rabbit ears.", 0xF2F2F2),
            new Item("top_hat", "Top Hat", "A tall silk top hat with a coloured band.", 0x161616),
            new Item("wizard_hat", "Wizard Hat", "A pointed wizard hat with a bent tip.", 0x3B2A7A));

    public static final List<Item> WINGS = List.of(
            new Item("angel", "Angel Wings", "Layered feathers that open, beat and fold with how you move.", 0xF8F7F4),
            new Item("dragon", "Dragon Wings", "Bony fingers with a veined membrane between them.", 0x2C2A3A));

    public static Item hat(String id) {
        if (id == null) return null;
        for (Item i : HATS) if (i.id().equals(id)) return i;
        return null;
    }
}
