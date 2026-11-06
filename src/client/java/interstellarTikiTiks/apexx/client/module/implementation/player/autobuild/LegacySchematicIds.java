package interstellarTikiTiks.apexx.client.module.implementation.player.autobuild;

/**
 * Maps pre-1.13 numeric block ids + 4-bit data (MCEdit / WorldEdit {@code .schematic}) to modern
 * block-state strings. Covers the common building blocks, their colour/wood variants and the
 * directional data of stairs, logs, pillars, torches and facing blocks; anything unknown becomes
 * air (left out of the build) rather than a wrong block.
 */
final class LegacySchematicIds {

    private static final String[] COLORS = {"white", "orange", "magenta", "light_blue", "yellow", "lime", "pink",
            "gray", "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"};
    private static final String[] WOODS = {"oak", "spruce", "birch", "jungle", "acacia", "dark_oak"};
    private static final String[] BASE = new String[256];

    static {
        String[] simple = {
                "0", "air", "2", "grass_block", "4", "cobblestone", "7", "bedrock", "13", "gravel", "14", "gold_ore",
                "15", "iron_ore", "16", "coal_ore", "19", "sponge", "20", "glass", "21", "lapis_ore",
                "22", "lapis_block", "25", "note_block", "30", "cobweb", "32", "dead_bush", "37", "dandelion",
                "39", "brown_mushroom", "40", "red_mushroom", "41", "gold_block", "42", "iron_block", "45", "bricks",
                "46", "tnt", "47", "bookshelf", "48", "mossy_cobblestone", "49", "obsidian", "52", "spawner",
                "55", "redstone_wire", "56", "diamond_ore", "57", "diamond_block", "58", "crafting_table",
                "60", "farmland", "66", "rail", "70", "stone_pressure_plate", "72", "oak_pressure_plate",
                "73", "redstone_ore", "74", "redstone_ore", "78", "snow", "79", "ice", "80", "snow_block",
                "81", "cactus", "82", "clay", "83", "sugar_cane", "84", "jukebox", "85", "oak_fence",
                "87", "netherrack", "88", "soul_sand", "89", "glowstone", "101", "iron_bars", "102", "glass_pane",
                "103", "melon", "106", "vine", "110", "mycelium", "111", "lily_pad", "112", "nether_bricks",
                "113", "nether_brick_fence", "116", "enchanting_table", "117", "brewing_stand", "118", "cauldron",
                "121", "end_stone", "122", "dragon_egg", "123", "redstone_lamp", "124", "redstone_lamp",
                "129", "emerald_ore", "133", "emerald_block", "138", "beacon", "140", "flower_pot",
                "147", "light_weighted_pressure_plate", "148", "heavy_weighted_pressure_plate",
                "151", "daylight_detector", "152", "redstone_block", "153", "nether_quartz_ore",
                "165", "slime_block", "166", "barrier", "169", "sea_lantern", "172", "terracotta",
                "173", "coal_block", "174", "packed_ice", "178", "daylight_detector",
                "188", "spruce_fence", "189", "birch_fence", "190", "jungle_fence", "191", "dark_oak_fence",
                "192", "acacia_fence", "199", "chorus_plant", "200", "chorus_flower", "201", "purpur_block",
                "206", "end_stone_bricks", "208", "dirt_path", "213", "magma_block", "214", "nether_wart_block",
                "215", "red_nether_bricks", "217", "structure_void"
        };
        for (int i = 0; i < simple.length; i += 2) {
            BASE[Integer.parseInt(simple[i])] = simple[i + 1];
        }
    }

    private LegacySchematicIds() {
    }

    /** Modern state string for a legacy id/data pair; {@code minecraft:air} when unknown. */
    static String state(int id, int data) {
        String s = resolve(id, data & 0xF);
        return s == null ? "minecraft:air" : (s.contains(":") ? s : "minecraft:" + s);
    }

    private static String resolve(int id, int d) {
        if (id < 0 || id > 255) return null;
        switch (id) {
            case 1: return pick(d, "stone", "granite", "polished_granite", "diorite", "polished_diorite", "andesite", "polished_andesite");
            case 3: return pick(d, "dirt", "coarse_dirt", "podzol");
            case 5: return wood(d & 7) == null ? null : wood(d & 7) + "_planks";
            case 6: return wood(d & 7) == null ? null : wood(d & 7) + "_sapling";
            case 8: case 9: return "water";
            case 10: case 11: return "lava";
            case 12: return d == 1 ? "red_sand" : "sand";
            case 17: return log(WOODS[d & 3], d);
            case 162: return log(WOODS[4 + (d & 1)], d);
            case 18: return WOODS[d & 3] + "_leaves[persistent=true]";
            case 161: return WOODS[4 + (d & 1)] + "_leaves[persistent=true]";
            case 23: return "dispenser[facing=" + dir6(d) + "]";
            case 158: return "dropper[facing=" + dir6(d) + "]";
            case 29: return "sticky_piston[facing=" + dir6(d) + "]";
            case 33: return "piston[facing=" + dir6(d) + "]";
            case 218: return "observer[facing=" + dir6(d) + "]";
            case 24: return pick(d, "sandstone", "chiseled_sandstone", "cut_sandstone");
            case 179: return pick(d, "red_sandstone", "chiseled_red_sandstone", "cut_red_sandstone");
            case 31: return pick(d, "dead_bush", "short_grass", "fern");
            case 35: return COLORS[d] + "_wool";
            case 95: return COLORS[d] + "_stained_glass";
            case 159: return COLORS[d] + "_terracotta";
            case 160: return COLORS[d] + "_stained_glass_pane";
            case 171: return COLORS[d] + "_carpet";
            case 251: return COLORS[d] + "_concrete";
            case 252: return COLORS[d] + "_concrete_powder";
            case 38: return pick(d, "poppy", "blue_orchid", "allium", "azure_bluet", "red_tulip", "orange_tulip",
                    "white_tulip", "pink_tulip", "oxeye_daisy");
            case 43: return stoneSlab(d & 7, "double");
            case 44: return stoneSlab(d & 7, (d & 8) != 0 ? "top" : "bottom");
            case 125: return wood(d & 7) == null ? null : wood(d & 7) + "_slab[type=double]";
            case 126: return wood(d & 7) == null ? null : wood(d & 7) + "_slab[type=" + ((d & 8) != 0 ? "top" : "bottom") + "]";
            case 181: return "red_sandstone_slab[type=double]";
            case 182: return "red_sandstone_slab[type=" + ((d & 8) != 0 ? "top" : "bottom") + "]";
            case 204: return "purpur_slab[type=double]";
            case 205: return "purpur_slab[type=" + ((d & 8) != 0 ? "top" : "bottom") + "]";
            case 50: return torch("torch", "wall_torch", d);
            case 75: case 76: return torch("redstone_torch", "redstone_wall_torch", d);
            case 53: return stairs("oak", d);
            case 67: return stairs("cobblestone", d);
            case 108: return stairs("brick", d);
            case 109: return stairs("stone_brick", d);
            case 114: return stairs("nether_brick", d);
            case 128: return stairs("sandstone", d);
            case 134: return stairs("spruce", d);
            case 135: return stairs("birch", d);
            case 136: return stairs("jungle", d);
            case 156: return stairs("quartz", d);
            case 163: return stairs("acacia", d);
            case 164: return stairs("dark_oak", d);
            case 180: return stairs("red_sandstone", d);
            case 203: return stairs("purpur", d);
            case 54: return "chest[facing=" + dir4(d) + "]";
            case 130: return "ender_chest[facing=" + dir4(d) + "]";
            case 146: return "trapped_chest[facing=" + dir4(d) + "]";
            case 61: case 62: return "furnace[facing=" + dir4(d) + "]";
            case 65: return "ladder[facing=" + dir4(d) + "]";
            case 86: return "carved_pumpkin[facing=" + pumpkin(d) + "]";
            case 91: return "jack_o_lantern[facing=" + pumpkin(d) + "]";
            case 97: return pick(d, "infested_stone", "infested_cobblestone", "infested_stone_bricks",
                    "infested_mossy_stone_bricks", "infested_cracked_stone_bricks", "infested_chiseled_stone_bricks");
            case 98: return pick(d, "stone_bricks", "mossy_stone_bricks", "cracked_stone_bricks", "chiseled_stone_bricks");
            case 139: return d == 1 ? "mossy_cobblestone_wall" : "cobblestone_wall";
            case 155: return switch (d) {
                case 1 -> "chiseled_quartz_block";
                case 2 -> "quartz_pillar[axis=y]";
                case 3 -> "quartz_pillar[axis=x]";
                case 4 -> "quartz_pillar[axis=z]";
                default -> "quartz_block";
            };
            case 168: return pick(d, "prismarine", "prismarine_bricks", "dark_prismarine");
            case 170: return "hay_block[axis=" + pillarAxis(d) + "]";
            case 216: return "bone_block[axis=" + pillarAxis(d) + "]";
            case 202: return "purpur_pillar[axis=" + pillarAxis(d) + "]";
            default:
                if (id >= 219 && id <= 234) return COLORS[id - 219] + "_shulker_box";
                if (id >= 235 && id <= 250) return COLORS[id - 235] + "_glazed_terracotta[facing=" + pumpkin(d) + "]";
                return BASE[id];
        }
    }

    private static String pick(int d, String... names) {
        return d >= 0 && d < names.length ? names[d] : names[0];
    }

    private static String wood(int i) {
        return i >= 0 && i < WOODS.length ? WOODS[i] : null;
    }

    private static String log(String wood, int d) {
        int axisBits = (d >> 2) & 3;
        if (axisBits == 3) return wood + "_wood[axis=y]";
        return wood + "_log[axis=" + (axisBits == 1 ? "x" : axisBits == 2 ? "z" : "y") + "]";
    }

    private static String pillarAxis(int d) {
        int axisBits = (d >> 2) & 3;
        return axisBits == 1 ? "x" : axisBits == 2 ? "z" : "y";
    }

    private static String stoneSlab(int variant, String type) {
        String kind = switch (variant) {
            case 1 -> "sandstone_slab";
            case 2 -> "petrified_oak_slab";
            case 3 -> "cobblestone_slab";
            case 4 -> "brick_slab";
            case 5 -> "stone_brick_slab";
            case 6 -> "nether_brick_slab";
            case 7 -> "quartz_slab";
            default -> "smooth_stone_slab";
        };
        return kind + "[type=" + type + "]";
    }

    private static String stairs(String material, int d) {
        String facing = switch (d & 3) {
            case 0 -> "east";
            case 1 -> "west";
            case 2 -> "south";
            default -> "north";
        };
        return material + "_stairs[facing=" + facing + ",half=" + ((d & 4) != 0 ? "top" : "bottom") + "]";
    }

    private static String torch(String standing, String wall, int d) {
        return switch (d) {
            case 1 -> wall + "[facing=east]";
            case 2 -> wall + "[facing=west]";
            case 3 -> wall + "[facing=south]";
            case 4 -> wall + "[facing=north]";
            default -> standing;
        };
    }

    /** Chest/furnace/ladder data: 2 north, 3 south, 4 west, 5 east. */
    private static String dir4(int d) {
        return switch (d) {
            case 3 -> "south";
            case 4 -> "west";
            case 5 -> "east";
            default -> "north";
        };
    }

    /** Dispenser/piston data: 0 down, 1 up, 2 north, 3 south, 4 west, 5 east (bit 8 = extended/powered). */
    private static String dir6(int d) {
        return switch (d & 7) {
            case 0 -> "down";
            case 1 -> "up";
            case 2 -> "north";
            case 3 -> "south";
            case 4 -> "west";
            default -> "east";
        };
    }

    /** Pumpkin / glazed terracotta data: 0 south, 1 west, 2 north, 3 east. */
    private static String pumpkin(int d) {
        return switch (d & 3) {
            case 1 -> "west";
            case 2 -> "north";
            case 3 -> "east";
            default -> "south";
        };
    }
}
