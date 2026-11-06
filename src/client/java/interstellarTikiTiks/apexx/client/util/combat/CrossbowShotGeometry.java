package interstellarTikiTiks.apexx.client.util.combat;

import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

/** Short-range arrow geometry. World collision and target policy remain with the caller. */
public final class CrossbowShotGeometry {
    // CrossbowItem uses setVelocity(direction, 3.15F, 1), without bow-style inherited movement.
    public static final double SPEED = (double) 3.15F;
    public static final double RANGE = 6.0;

    private CrossbowShotGeometry() {}

    public record Segment(Vec3d from, Vec3d to) {}
    public record Shot(List<Segment> segments, Vec3d impact) {}

    /** Null unless the arrow enters ignition before the real cart box. No rail-cell fallback. */
    public static Shot trace(Vec3d origin, Vec3d direction, Box cart, Box ignition) {
        if (origin == null || direction == null || cart == null || ignition == null
                || !Double.isFinite(direction.lengthSquared()) || direction.lengthSquared() < 1.0e-12
                || cart.contains(origin)) return null;
        Vec3d position = origin;
        Vec3d velocity = direction.normalize().multiply(SPEED);
        List<Segment> path = new ArrayList<>(3);
        boolean lit = ignition.contains(origin);
        double travelled = 0.0;
        for (int tick = 0; tick < 3 && travelled < RANGE; tick++) {
            double length = Math.min(velocity.length(), RANGE - travelled);
            Vec3d end = position.add(velocity.normalize().multiply(length));
            var contact = cart.raycast(position, end);
            if (contact.isPresent()) end = contact.get();
            var fireContact = ignition.raycast(position, end);
            lit |= ignition.contains(position) || (fireContact.isPresent()
                    && (contact.isEmpty() || position.squaredDistanceTo(fireContact.get()) + 1.0e-8
                    < position.squaredDistanceTo(end)));
            path.add(new Segment(position, end));
            if (contact.isPresent()) return lit ? new Shot(List.copyOf(path), end) : null;
            travelled += length;
            position = end;
            velocity = ArrowFlightPredictor.advanceVelocity(velocity);
        }
        return null;
    }

    /** Nominal direction and six interior divergence probes; these are samples, not a hit guarantee. */
    public static List<Vec3d> spreadDirections(Vec3d direction) {
        List<Vec3d> directions = new ArrayList<>(7);
        directions.add(direction.normalize());
        double spread = AutoCartPlanner.SPREAD * 0.6;
        for (int axis = 0; axis < 3; axis++) {
            for (int sign = -1; sign <= 1; sign += 2) {
                directions.add(direction.add(axis == 0 ? sign * spread : 0.0,
                        axis == 1 ? sign * spread : 0.0, axis == 2 ? sign * spread : 0.0).normalize());
            }
        }
        return directions;
    }
}
