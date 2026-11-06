package interstellarTikiTiks.apexx.client.module.implementation.player.autobuild;

import java.util.List;
import java.util.Map;

/**
 * Mirror-then-rotate transform of a schematic's horizontal plane (the order vanilla structure
 * placement uses), applied to cell coordinates and to block-state properties.
 *
 * <p>Rotation is clockwise seen from above (north -> east). {@link Mirror#FRONT_BACK} flips the
 * X axis (east &lt;-&gt; west), {@link Mirror#LEFT_RIGHT} flips Z (north &lt;-&gt; south).
 */
public final class SchematicTransform {

    public enum Rotation {
        NONE, CW_90, CW_180, CW_270;

        public int steps() {
            return ordinal();
        }

        public static Rotation fromLabel(String label) {
            if (label == null) return NONE;
            return switch (label.trim()) {
                case "90", "90°" -> CW_90;
                case "180", "180°" -> CW_180;
                case "270", "270°" -> CW_270;
                default -> NONE;
            };
        }
    }

    public enum Mirror {
        NONE, FRONT_BACK, LEFT_RIGHT;

        public static Mirror fromLabel(String label) {
            if (label == null) return NONE;
            return switch (label.trim().toLowerCase(java.util.Locale.ROOT)) {
                case "front-back", "front_back", "x" -> FRONT_BACK;
                case "left-right", "left_right", "z" -> LEFT_RIGHT;
                default -> NONE;
            };
        }
    }

    private static final List<String> HORIZONTAL = List.of("north", "east", "south", "west");

    private final int srcX;
    private final int srcZ;
    private final Rotation rotation;
    private final Mirror mirror;

    public SchematicTransform(int srcX, int srcZ, Rotation rotation, Mirror mirror) {
        this.srcX = srcX;
        this.srcZ = srcZ;
        this.rotation = rotation == null ? Rotation.NONE : rotation;
        this.mirror = mirror == null ? Mirror.NONE : mirror;
    }

    public Rotation rotation() {
        return rotation;
    }

    public Mirror mirror() {
        return mirror;
    }

    public int sizeX() {
        return rotation.steps() % 2 == 0 ? srcX : srcZ;
    }

    public int sizeZ() {
        return rotation.steps() % 2 == 0 ? srcZ : srcX;
    }

    /** Transformed {x, z} of a source cell; both stay inside [0, size). */
    public int[] apply(int x, int z) {
        if (mirror == Mirror.FRONT_BACK) {
            x = srcX - 1 - x;
        } else if (mirror == Mirror.LEFT_RIGHT) {
            z = srcZ - 1 - z;
        }
        return switch (rotation) {
            case NONE -> new int[]{x, z};
            case CW_90 -> new int[]{srcZ - 1 - z, x};
            case CW_180 -> new int[]{srcX - 1 - x, srcZ - 1 - z};
            case CW_270 -> new int[]{z, srcX - 1 - x};
        };
    }

    // ------------------------------------------------------------------ directions

    public static boolean isHorizontal(String dir) {
        return dir != null && HORIZONTAL.contains(dir);
    }

    public static String rotateDir(String dir, int steps) {
        int i = HORIZONTAL.indexOf(dir);
        if (i < 0) return dir;
        return HORIZONTAL.get(Math.floorMod(i + steps, 4));
    }

    public static String mirrorDir(String dir, Mirror mirror) {
        if (dir == null) return null;
        if (mirror == Mirror.FRONT_BACK) {
            if (dir.equals("east")) return "west";
            if (dir.equals("west")) return "east";
        } else if (mirror == Mirror.LEFT_RIGHT) {
            if (dir.equals("north")) return "south";
            if (dir.equals("south")) return "north";
        }
        return dir;
    }

    public static String opposite(String dir) {
        if (dir == null) return null;
        return switch (dir) {
            case "north" -> "south";
            case "south" -> "north";
            case "east" -> "west";
            case "west" -> "east";
            case "up" -> "down";
            case "down" -> "up";
            default -> dir;
        };
    }

    private String dir(String d) {
        return rotateDir(mirrorDir(d, mirror), rotation.steps());
    }

    // ------------------------------------------------------------------ states

    /** Applies the mirror and rotation to the direction-bearing properties of a state string. */
    public String transformState(String state) {
        if (state == null || (rotation == Rotation.NONE && mirror == Mirror.NONE)) {
            return state;
        }
        Map<String, String> props = StateString.props(state);
        if (props.isEmpty()) {
            return state;
        }
        String base = StateString.base(state);
        boolean mirrored = mirror != Mirror.NONE;

        // Connection keys move together: the value that faced north now faces dir("north").
        Map<String, String> moved = new java.util.TreeMap<>();
        for (String h : HORIZONTAL) {
            if (props.containsKey(h)) {
                moved.put(dir(h), props.get(h));
            }
        }

        Map<String, String> out = new java.util.TreeMap<>();
        for (Map.Entry<String, String> e : props.entrySet()) {
            String key = e.getKey();
            String value = e.getValue();
            if (HORIZONTAL.contains(key)) {
                continue; // re-added from 'moved'
            }
            switch (key) {
                case "facing" -> value = isHorizontal(value) ? dir(value) : value;
                case "axis" -> {
                    if (rotation.steps() % 2 == 1) {
                        if (value.equals("x")) value = "z";
                        else if (value.equals("z")) value = "x";
                    }
                }
                case "rotation" -> value = transformRotation16(value);
                case "hinge" -> {
                    if (mirrored) value = swapLeftRight(value);
                }
                case "type" -> {
                    if (mirrored && (value.equals("left") || value.equals("right"))) value = swapLeftRight(value);
                }
                case "shape" -> value = transformShape(value, mirrored);
                case "orientation" -> value = transformTokens(value);
                default -> {
                }
            }
            out.put(key, value);
        }
        out.putAll(moved);
        return StateString.compose(base, out);
    }

    private String transformRotation16(String value) {
        int r;
        try {
            r = Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return value;
        }
        int full = 16;
        int half = full / 2;
        if (mirror != Mirror.NONE) {
            int j = r > half ? r - full : r;
            r = mirror == Mirror.FRONT_BACK ? Math.floorMod(full - j, full) : Math.floorMod(half - j + full, full);
        }
        r = Math.floorMod(r + 4 * rotation.steps(), full);
        return Integer.toString(r);
    }

    private static String swapLeftRight(String value) {
        if (value.endsWith("left")) return value.substring(0, value.length() - 4) + "right";
        if (value.endsWith("right")) return value.substring(0, value.length() - 5) + "left";
        return value;
    }

    private String transformShape(String value, boolean mirrored) {
        if (value.startsWith("inner_") || value.startsWith("outer_")) {
            return mirrored ? swapLeftRight(value) : value; // stairs; recomputed in-game anyway
        }
        if (value.startsWith("ascending_")) {
            return "ascending_" + dir(value.substring("ascending_".length()));
        }
        String[] parts = value.split("_");
        if (parts.length == 2 && isHorizontal(parts[0]) && isHorizontal(parts[1])) {
            return canonicalRail(dir(parts[0]), dir(parts[1]));
        }
        return value;
    }

    /** Rail shapes name straights as north_south / east_west and curves north-or-south first. */
    static String canonicalRail(String a, String b) {
        boolean aNs = a.equals("north") || a.equals("south");
        boolean bNs = b.equals("north") || b.equals("south");
        if (aNs && bNs) return "north_south";
        if (!aNs && !bNs) return "east_west";
        String ns = aNs ? a : b;
        String ew = aNs ? b : a;
        return ns + "_" + ew;
    }

    private String transformTokens(String value) {
        String[] parts = value.split("_");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append('_');
            sb.append(isHorizontal(parts[i]) ? dir(parts[i]) : parts[i]);
        }
        return sb.toString();
    }
}
