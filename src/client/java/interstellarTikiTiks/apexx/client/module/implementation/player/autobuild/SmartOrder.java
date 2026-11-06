package interstellarTikiTiks.apexx.client.module.implementation.player.autobuild;

import java.util.Set;

/**
 * Dependency classes for the "Smart" build order, registry-free so it can be unit tested. Inside a
 * layer the builder places plain blocks first, then gravity blocks (they only stay put on something
 * solid), then blocks that hang on or stand on another block, and last the fluid-sensitive ones
 * (sea plants, coral, waterlogged states) so flowing water from the build cannot wash anything off.
 */
public final class SmartOrder {

    public static final int SOLID = 0;
    public static final int GRAVITY = 1;
    public static final int ATTACHED = 2;
    public static final int LAST = 3;
    public static final int PASSES = 4;

    private static final Set<String> GRAVITY_BLOCKS = Set.of(
            "sand", "red_sand", "gravel", "suspicious_sand", "suspicious_gravel", "anvil", "chipped_anvil",
            "damaged_anvil", "dragon_egg", "pointed_dripstone", "scaffolding");

    private static final Set<String> DEPENDENT_BLOCKS = Set.of(
            "lever", "redstone_wire", "repeater", "comparator", "ladder", "vine", "cave_vines", "cave_vines_plant",
            "weeping_vines", "weeping_vines_plant", "twisting_vines", "twisting_vines_plant", "glow_lichen",
            "sculk_vein", "short_grass", "grass", "tall_grass", "fern", "large_fern", "dead_bush", "sugar_cane",
            "cactus", "bamboo", "bamboo_sapling", "wheat", "carrots", "potatoes", "beetroots", "sweet_berry_bush",
            "nether_wart", "melon_stem", "pumpkin_stem", "attached_melon_stem", "attached_pumpkin_stem",
            "torchflower_crop", "pitcher_crop", "lily_pad", "snow", "flower_pot", "lantern", "soul_lantern",
            "tripwire", "tripwire_hook", "dandelion", "poppy", "blue_orchid", "allium", "azure_bluet",
            "oxeye_daisy", "cornflower", "lily_of_the_valley", "wither_rose", "sunflower", "lilac", "rose_bush",
            "peony", "torchflower", "pitcher_plant", "pink_petals", "spore_blossom", "hanging_roots",
            "nether_sprouts", "crimson_roots", "warped_roots", "crimson_fungus", "warped_fungus",
            "brown_mushroom", "red_mushroom", "small_dripleaf", "big_dripleaf", "big_dripleaf_stem",
            "chorus_flower", "chorus_plant", "frogspawn", "bell", "chain", "candle", "end_rod", "lightning_rod",
            "moss_carpet", "pale_moss_carpet", "leaf_litter", "wildflowers", "firefly_bush", "bush",
            "short_dry_grass", "tall_dry_grass", "cactus_flower", "pale_hanging_moss");

    private static final Set<String> FLUID_SENSITIVE = Set.of(
            "water", "lava", "bubble_column", "kelp", "kelp_plant", "seagrass", "tall_seagrass", "sea_pickle");

    private SmartOrder() {
    }

    /** Pass for a (transformed) state; {@code attachmentRule} is the placement rule's own flag. */
    public static int passOf(String state, boolean attachmentRule) {
        String path = StateString.path(state);
        if (isFluidSensitive(path) || "true".equals(StateString.props(state).get("waterlogged"))) return LAST;
        if (attachmentRule || isDependent(path)) return ATTACHED;
        if (isGravity(path)) return GRAVITY;
        return SOLID;
    }

    public static boolean isGravity(String path) {
        return GRAVITY_BLOCKS.contains(path) || path.endsWith("_concrete_powder");
    }

    /** Blocks that break or cannot be placed without the block they hang on or stand on. */
    public static boolean isDependent(String path) {
        return DEPENDENT_BLOCKS.contains(path)
                || path.endsWith("torch") || path.endsWith("_button") || path.endsWith("rail")
                || path.endsWith("_carpet") || path.endsWith("_pressure_plate") || path.endsWith("_sign")
                || path.endsWith("_banner") || path.endsWith("_door") || path.endsWith("_sapling")
                || path.endsWith("_tulip") || path.endsWith("_candle") || path.startsWith("potted_")
                || path.endsWith("_propagule");
    }

    /** Coral and sea plants, which need (or are changed by) water. */
    public static boolean isFluidSensitive(String path) {
        return FLUID_SENSITIVE.contains(path) || (path.contains("coral") && !path.endsWith("coral_block"));
    }

    /** Dependent blocks that stand on the block below rather than hanging on a side. */
    public static boolean needsFloor(String path) {
        return isGravity(path) || path.endsWith("_carpet") || path.endsWith("rail") || path.equals("redstone_wire")
                || path.equals("repeater") || path.equals("comparator") || path.endsWith("_pressure_plate")
                || path.endsWith("_sapling") || path.equals("snow") || path.equals("sugar_cane")
                || path.equals("cactus") || path.endsWith("_door") || path.equals("flower_pot")
                || path.startsWith("potted_") || path.endsWith("_tulip") || path.equals("short_grass")
                || path.equals("tall_grass") || path.equals("fern") || path.equals("large_fern")
                || path.equals("wheat") || path.equals("carrots") || path.equals("potatoes")
                || path.equals("beetroots") || path.equals("nether_wart") || path.equals("lily_pad");
    }
}
