package interstellarTikiTiks.apexx.client.module.implementation.player.autobuild;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * How vanilla derives a block's directional state from a placement, expressed as the click a
 * builder must make: which support face to click, where on it, and which way the player must be
 * facing. Registry-free so it can be unit tested; the module maps these strings to MC types.
 *
 * <p>Directions are vanilla's lower-case names ({@code north}, {@code up}, ...). A requirement's
 * {@code sides} is the side of the clicked support block (the hit face). {@code half} only applies
 * to horizontal sides: where on that face the hit must land.
 */
public final class PlacementRules {

    public enum Half { ANY, UPPER, LOWER }

    public record Requirement(Set<String> sides, String playerFacing, String lookDirection, Half half) {
        public static final Requirement ANY = new Requirement(null, null, null, Half.ANY);

        public boolean allowsSide(String side) {
            return sides == null || sides.contains(side);
        }
    }

    /** What the module has to do for one palette state. */
    public record Rule(List<Requirement> options, boolean skip, boolean attachment, boolean doubleSlab) {
        static Rule of(Requirement... options) {
            return new Rule(List.of(options), false, false, false);
        }
    }

    /** Properties that placement controls and that a "correct" block must match. */
    public static final Set<String> MATCHED_PROPERTIES = Set.of("facing", "axis", "half", "type", "face");

    private static final Set<String> ALL_SIDES = Set.of("up", "down", "north", "south", "east", "west");
    private static final Set<String> UP_OR_HORIZONTAL = Set.of("up", "north", "south", "east", "west");
    private static final Set<String> DOWN_OR_HORIZONTAL = Set.of("down", "north", "south", "east", "west");

    private PlacementRules() {
    }

    public static Rule forState(String state) {
        String path = StateString.path(state);
        Map<String, String> p = StateString.props(state);
        String facing = p.get("facing");

        // Second halves that the game places by itself.
        if (path.endsWith("_door") && "upper".equals(p.get("half"))) return skip();
        if (path.endsWith("_bed") && "head".equals(p.get("part"))) return skip();
        if ("upper".equals(p.get("half")) && !path.endsWith("_door")) return skip(); // tall plants, pitcher, etc.
        if (path.equals("piston_head") || path.equals("moving_piston")) return skip();

        if (path.endsWith("_slab")) {
            String type = p.getOrDefault("type", "bottom");
            if (type.equals("top")) {
                return Rule.of(new Requirement(DOWN_OR_HORIZONTAL, null, null, Half.UPPER));
            }
            Requirement bottom = new Requirement(UP_OR_HORIZONTAL, null, null, Half.LOWER);
            return new Rule(List.of(bottom), false, false, type.equals("double"));
        }

        if (path.endsWith("_stairs")) {
            boolean top = "top".equals(p.get("half"));
            return Rule.of(new Requirement(top ? DOWN_OR_HORIZONTAL : UP_OR_HORIZONTAL,
                    horizontalOrNull(facing), null, top ? Half.UPPER : Half.LOWER));
        }

        if (path.endsWith("_trapdoor")) {
            boolean top = "top".equals(p.get("half"));
            Half half = top ? Half.UPPER : Half.LOWER;
            if (SchematicTransform.isHorizontal(facing)) {
                Requirement onSide = new Requirement(Set.of(facing), null, null, half);
                Requirement onTopOrBottom = new Requirement(Set.of(top ? "down" : "up"),
                        SchematicTransform.opposite(facing), null, Half.ANY);
                return Rule.of(onSide, onTopOrBottom);
            }
            return Rule.of(Requirement.ANY);
        }

        if (path.endsWith("_door") || path.endsWith("_bed")) {
            return Rule.of(new Requirement(Set.of("up"), horizontalOrNull(facing), null, Half.ANY));
        }

        // Buttons, levers, grindstone: face + facing.
        String face = p.get("face");
        if (face != null && facing != null) {
            boolean attach = !path.equals("grindstone");
            Requirement r = switch (face) {
                case "floor" -> new Requirement(Set.of("up"), horizontalOrNull(facing), null, Half.ANY);
                case "ceiling" -> new Requirement(Set.of("down"), horizontalOrNull(facing), null, Half.ANY);
                default -> new Requirement(Set.of(facing), null, null, Half.ANY);
            };
            return new Rule(List.of(r), false, attach, false);
        }

        if (isWallMounted(path) && SchematicTransform.isHorizontal(facing)) {
            return new Rule(List.of(new Requirement(Set.of(facing), null, null, Half.ANY)), false, true, false);
        }

        if (isStandingAttachment(path)) {
            return new Rule(List.of(new Requirement(Set.of("up"), null, null, Half.ANY)), false, true, false);
        }

        if (isFloorAttachment(path)) {
            return new Rule(List.of(Requirement.ANY), false, true, false);
        }

        // Facing taken from the clicked face (end rods, lightning rods, amethyst, hopper).
        if (facing != null && isSideFacing(path)) {
            if (path.equals("hopper")) {
                if ("down".equals(facing)) return Rule.of(new Requirement(Set.of("up", "down"), null, null, Half.ANY));
                return Rule.of(new Requirement(Set.of(SchematicTransform.opposite(facing)), null, null, Half.ANY));
            }
            return Rule.of(new Requirement(Set.of(facing), null, null, Half.ANY));
        }

        // Six-way blocks that face away from (or toward) where the player looks.
        if (facing != null && isLookOpposite6(path)) {
            return Rule.of(new Requirement(null, null, SchematicTransform.opposite(facing), Half.ANY));
        }
        if (facing != null && path.equals("observer")) {
            return Rule.of(new Requirement(null, null, facing, Half.ANY));
        }

        if (p.containsKey("axis") && !path.equals("nether_portal")) {
            String axis = p.get("axis");
            Set<String> sides = switch (axis) {
                case "x" -> Set.of("east", "west");
                case "z" -> Set.of("north", "south");
                default -> Set.of("up", "down");
            };
            return Rule.of(new Requirement(sides, null, null, Half.ANY));
        }

        if (SchematicTransform.isHorizontal(facing)) {
            if (path.endsWith("anvil")) {
                // Anvils face the player's facing rotated clockwise.
                return Rule.of(new Requirement(null, SchematicTransform.rotateDir(facing, -1), null, Half.ANY));
            }
            if (isFacingSameAsPlayer(path)) {
                return Rule.of(new Requirement(null, facing, null, Half.ANY));
            }
            // Default: the block's front faces the player.
            return Rule.of(new Requirement(null, SchematicTransform.opposite(facing), null, Half.ANY));
        }

        return Rule.of(Requirement.ANY);
    }

    private static Rule skip() {
        return new Rule(List.of(), true, false, false);
    }

    private static String horizontalOrNull(String dir) {
        return SchematicTransform.isHorizontal(dir) ? dir : null;
    }

    static boolean isWallMounted(String path) {
        return path.endsWith("wall_torch") || path.endsWith("_wall_sign") || path.endsWith("_wall_banner")
                || path.equals("ladder") || path.endsWith("_wall_head") || path.endsWith("_wall_skull")
                || path.equals("tripwire_hook") || path.endsWith("_wall_fan") || path.equals("cocoa");
    }

    static boolean isStandingAttachment(String path) {
        return path.equals("torch") || path.equals("soul_torch") || path.equals("redstone_torch")
                || path.endsWith("copper_torch")
                || (path.endsWith("_sign") && !path.endsWith("_wall_sign") && !path.endsWith("hanging_sign"))
                || (path.endsWith("_banner") && !path.endsWith("_wall_banner"))
                || path.equals("lantern") || path.equals("soul_lantern");
    }

    static boolean isFloorAttachment(String path) {
        return path.endsWith("_carpet") || path.endsWith("rail") || path.equals("redstone_wire")
                || path.equals("repeater") || path.equals("comparator") || path.endsWith("_pressure_plate")
                || path.equals("snow") || path.endsWith("_sapling") || path.equals("flower_pot")
                || path.startsWith("potted_") || path.equals("lily_pad") || path.equals("tripwire");
    }

    static boolean isSideFacing(String path) {
        return path.equals("end_rod") || path.equals("lightning_rod") || path.endsWith("amethyst_cluster")
                || path.endsWith("amethyst_bud") || path.equals("hopper");
    }

    static boolean isLookOpposite6(String path) {
        return path.equals("dispenser") || path.equals("dropper") || path.equals("piston")
                || path.equals("sticky_piston") || path.equals("barrel");
    }

    static boolean isFacingSameAsPlayer(String path) {
        return path.endsWith("_fence_gate") || path.equals("campfire") || path.equals("soul_campfire")
                || path.equals("decorated_pot");
    }

    // ------------------------------------------------------------------ player orientation

    /** Vanilla horizontal facing for a yaw (0 = south, 90 = west, 180 = north, 270 = east). */
    public static String horizontalFacing(float yaw) {
        int i = Math.floorMod((int) Math.floor(yaw / 90.0 + 0.5), 4);
        return switch (i) {
            case 0 -> "south";
            case 1 -> "west";
            case 2 -> "north";
            default -> "east";
        };
    }

    /** Degrees between {@code yaw} and the nearest 45° quadrant boundary. */
    public static double facingMargin(float yaw) {
        double into = Math.floorMod((long) Math.floor((yaw + 45.0) * 1000.0), 90_000L) / 1000.0;
        return Math.min(into, 90.0 - into);
    }

    /** Vanilla's primary look direction: the axis with the largest component of the look vector. */
    public static String lookDirection(float yaw, float pitch) {
        double y = Math.toRadians(yaw);
        double p = Math.toRadians(pitch);
        double lx = -Math.sin(y) * Math.cos(p);
        double ly = -Math.sin(p);
        double lz = Math.cos(y) * Math.cos(p);
        double ax = Math.abs(lx), ay = Math.abs(ly), az = Math.abs(lz);
        if (ay >= ax && ay >= az) return ly > 0 ? "up" : "down";
        if (ax >= az) return lx > 0 ? "east" : "west";
        return lz > 0 ? "south" : "north";
    }

    /** True when a requirement's player orientation is met with at least {@code marginDeg} of slack. */
    public static boolean orientationOk(Requirement r, float yaw, float pitch, double marginDeg) {
        if (r.playerFacing() != null) {
            if (!r.playerFacing().equals(horizontalFacing(yaw)) || facingMargin(yaw) < marginDeg) return false;
        }
        if (r.lookDirection() != null && !r.lookDirection().equals(lookDirection(yaw, pitch))) {
            return false;
        }
        return true;
    }

    /** True when the hit fraction (0..1 up the placement cell) satisfies a horizontal-side half. */
    public static boolean halfOk(Half half, String side, double hitFractionY) {
        if (half == Half.ANY || side.equals("up") || side.equals("down")) return true;
        return half == Half.UPPER ? hitFractionY > 0.55 : hitFractionY < 0.45;
    }

    /** True when the placement-controlled properties of {@code current} match {@code target}. */
    public static boolean propertiesMatch(Map<String, String> target, Map<String, String> current) {
        for (String key : MATCHED_PROPERTIES) {
            String want = target.get(key);
            if (want == null) continue;
            String have = current.get(key);
            if (have != null && !have.equals(want)) {
                // Chests' left/right "type" depends on neighbours, not on the click.
                if (key.equals("type") && (want.equals("left") || want.equals("right") || want.equals("single"))) continue;
                return false;
            }
        }
        return true;
    }

    public static Set<String> allSides() {
        return ALL_SIDES;
    }
}
