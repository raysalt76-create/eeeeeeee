package interstellarTikiTiks.apexx.client.util;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/**
 * A resolved answer to "how do I make a block appear at {@code target}?".
 *
 * <p>To place a block at an empty {@code target} position you don't click the
 * target itself — you click a solid neighbour on the face that points back at
 * the target, and the game drops the new block into the empty space. This record
 * carries the three things the interaction needs:
 *
 * <ul>
 *   <li>{@link #against} — the existing solid block you click;</li>
 *   <li>{@link #side} — which face of {@code against} to click (points toward the
 *       target, so {@code against.offset(side)} == the original target);</li>
 *   <li>{@link #hitVec} — the exact point on that face to aim at / report in the
 *       interaction, i.e. the centre of the clicked face.</li>
 * </ul>
 *
 * <p>Produced by {@link PlaceResolver#resolvePlacement}. It is pure geometry —
 * it says nothing about rotation, item selection or timing; a placement state
 * machine consumes it and drives those.
 */
public record PlaceSolution(BlockPos against, Direction side, Vec3d hitVec) {

    /** The block position the new block will occupy — {@code against.offset(side)}. */
    public BlockPos placedAt() {
        return against.offset(side);
    }
}
