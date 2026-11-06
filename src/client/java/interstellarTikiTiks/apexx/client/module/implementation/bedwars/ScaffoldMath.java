package interstellarTikiTiks.apexx.client.module.implementation.bedwars;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

/** World-free geometry and physics used by {@link Scaffold}: cell paths, jump prediction, face aim points. */
final class ScaffoldMath {

    /** Vanilla gravity and vertical drag per tick for a falling/jumping player. */
    static final double GRAVITY = 0.08;
    static final double VERTICAL_DRAG = 0.98;
    /** Vanilla horizontal air drag and the sprinting air acceleration per tick. */
    static final double AIR_DRAG = 0.91;
    static final double SPRINT_AIR_ACCEL = 0.026;

    private ScaffoldMath() {
    }

    /**
     * The grid cells a straight line from (x0,z0) to (x1,z1) passes through, in order, stepping one
     * axis at a time. Every cell shares a side with the one before it, so a bridge laid along the
     * list can always be placed against the previous block (a diagonal becomes a staircase).
     */
    static List<int[]> staircase(double x0, double z0, double x1, double z1, int maxCells) {
        List<int[]> cells = new ArrayList<>();
        int cx = MathHelper.floor(x0);
        int cz = MathHelper.floor(z0);
        int ex = MathHelper.floor(x1);
        int ez = MathHelper.floor(z1);
        cells.add(new int[]{cx, cz});
        double dx = x1 - x0;
        double dz = z1 - z0;
        int sx = dx > 0 ? 1 : dx < 0 ? -1 : 0;
        int sz = dz > 0 ? 1 : dz < 0 ? -1 : 0;
        double tDeltaX = sx == 0 ? Double.POSITIVE_INFINITY : 1.0 / Math.abs(dx);
        double tDeltaZ = sz == 0 ? Double.POSITIVE_INFINITY : 1.0 / Math.abs(dz);
        double tMaxX = sx == 0 ? Double.POSITIVE_INFINITY
                : (sx > 0 ? (cx + 1 - x0) : (x0 - cx)) * tDeltaX;
        double tMaxZ = sz == 0 ? Double.POSITIVE_INFINITY
                : (sz > 0 ? (cz + 1 - z0) : (z0 - cz)) * tDeltaZ;
        while ((cx != ex || cz != ez) && cells.size() < maxCells) {
            if (tMaxX < tMaxZ) {
                if (tMaxX > 1.0 + 1.0E-9) break;
                cx += sx;
                tMaxX += tDeltaX;
            } else {
                if (tMaxZ > 1.0 + 1.0E-9) break;
                cz += sz;
                tMaxZ += tDeltaZ;
            }
            cells.add(new int[]{cx, cz});
        }
        return cells;
    }

    /** Vanilla yaw (degrees) of a horizontal direction. */
    static float yawOf(double dx, double dz) {
        return (float) Math.toDegrees(Math.atan2(-dx, dz));
    }

    /** Smallest signed difference {@code to - from}, in degrees. */
    static float yawDelta(float from, float to) {
        return MathHelper.wrapDegrees(to - from);
    }

    /**
     * Ticks until a player at {@code y} with vertical velocity {@code vy} comes down to {@code landY}
     * (vanilla order: move, then gravity and drag). {@code -1} when it does not land within {@code max}.
     */
    static int ticksToLand(double y, double vy, double landY, int max) {
        for (int k = 1; k <= max; k++) {
            y += vy;
            if (vy < 0 && y <= landY + 1.0E-6) return k;
            vy = (vy - GRAVITY) * VERTICAL_DRAG;
        }
        return -1;
    }

    /** Horizontal distance a sprinting airborne player covers in {@code ticks}, from horizontal speed {@code speed}. */
    static double airDistance(double speed, int ticks) {
        double total = 0.0;
        double s = speed;
        for (int k = 0; k < ticks; k++) {
            total += s;
            s = s * AIR_DRAG + SPRINT_AIR_ACCEL;
        }
        return total;
    }

    /**
     * A point on the {@code side} face of {@code against}, kept {@code margin} blocks inside its edges,
     * as close as possible to where the look ray from {@code eye} along {@code look} meets the face's
     * plane. Aiming here keeps each turn short and varies where on the face the click lands instead
     * of always clicking its centre. The point sits a hair inside the block so a ray to it hits that face.
     */
    static Vec3d facePoint(BlockPos against, Direction side, Vec3d eye, Vec3d look, double margin) {
        Direction.Axis axis = side.getAxis();
        double plane = side.getDirection() == Direction.AxisDirection.POSITIVE
                ? against.getComponentAlongAxis(axis) + 1.0 : against.getComponentAlongAxis(axis);
        double eyeAxis = eye.getComponentAlongAxis(axis);
        double lookAxis = look.getComponentAlongAxis(axis);
        Vec3d onPlane;
        double t = Math.abs(lookAxis) < 1.0E-6 ? -1.0 : (plane - eyeAxis) / lookAxis;
        if (t > 0.0) {
            onPlane = eye.add(look.multiply(t));
        } else {
            onPlane = withAxis(eye, axis, plane);
        }
        double m = MathHelper.clamp(margin, 0.0, 0.49);
        double x = axis == Direction.Axis.X ? plane
                : MathHelper.clamp(onPlane.x, against.getX() + m, against.getX() + 1.0 - m);
        double y = axis == Direction.Axis.Y ? plane
                : MathHelper.clamp(onPlane.y, against.getY() + m, against.getY() + 1.0 - m);
        double z = axis == Direction.Axis.Z ? plane
                : MathHelper.clamp(onPlane.z, against.getZ() + m, against.getZ() + 1.0 - m);
        // Nudge into the block so the ray terminates on this face rather than grazing its plane.
        return new Vec3d(x - side.getOffsetX() * 0.01, y - side.getOffsetY() * 0.01, z - side.getOffsetZ() * 0.01);
    }

    private static Vec3d withAxis(Vec3d v, Direction.Axis axis, double value) {
        return switch (axis) {
            case X -> new Vec3d(value, v.y, v.z);
            case Y -> new Vec3d(v.x, value, v.z);
            case Z -> new Vec3d(v.x, v.y, value);
        };
    }
}
