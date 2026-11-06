package interstellarTikiTiks.apexx.client.util.combat;

import interstellarTikiTiks.apexx.client.friend.FriendManager;
import interstellarTikiTiks.apexx.client.util.PlaceResolver;
import interstellarTikiTiks.apexx.client.util.PlaceSolution;
import interstellarTikiTiks.apexx.client.util.RotationManager;
import net.minecraft.block.Blocks;
import net.minecraft.block.ShapeContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/**
 * Picks where to place the next anchor so its blast does the most to a player and the least to you.
 *
 * <p>For every free cell in reach that an anchor can go into and has a solid neighbour to click, the
 * blast at the cell's centre is scored with {@link BlastModel}: damage to the target (zero when the
 * target's raised shield faces the blast, so the cells behind it win), damage to you, a small price for
 * distance, a bonus for sitting at or below the target's feet, and a price for how far the look has to
 * turn to the face that would be clicked (so equal spots that need less turning win). The best few are
 * then resolved to a real face with {@link PlaceResolver}; the highest scoring one that resolves is the
 * plan. It places nothing and aims nothing: the caller owns rotation, the swap and the click.</p>
 *
 * <p>The scoring weights are starting values to tune in-game (UNVERIFIED).</p>
 */
public final class AnchorPlanner {

    /** Cells further than this from a target (blocks) are not scored: the blast is gone by then. */
    private static final double MAX_BLAST_DISTANCE = 8.0;
    private static final int RESOLVE_TOP = 8;
    private static final double SELF_WEIGHT = 0.6;
    private static final double DISTANCE_WEIGHT = 0.15;
    private static final double UNDER_BONUS = 2.5;
    /** Score taken per degree of turn to the face. */
    private static final double TURN_WEIGHT = 0.03;
    private static final int MAX_TARGETS = 3;

    private AnchorPlanner() {
    }

    /**
     * @param allow the caller's own say on a candidate (for example, not a bot). It lives with the caller
     *              because the bot filter is a module, and this class ships in the loader, which cannot
     *              link to module classes.
     */
    public record Params(double range, double minTargetDamage, double maxSelfDamage,
                         boolean preferUnder, boolean shieldAware, Predicate<PlayerEntity> allow) {
    }

    /** A chosen placement: the cell, the face to click, and what the blast there is expected to do. */
    public record Plan(PlayerEntity target, BlockPos cell, PlaceSolution placement,
                       double targetDamage, double selfDamage, double score) {
    }

    public record Eval(double targetDamage, double selfDamage) {
    }

    /** The best placement for any target in range, or null when no cell meets the damage limits. */
    public static Plan plan(MinecraftClient mc, Params params) {
        ClientPlayerEntity me = mc.player;
        World world = mc.world;
        if (me == null || world == null) return null;
        List<PlayerEntity> targets = targets(mc, params);
        if (targets.isEmpty()) return null;

        double reach = me.getBlockInteractionRange();
        double reachSq = (reach + 0.9) * (reach + 0.9);
        Vec3d eye = me.getEyePos();
        BlockPos origin = me.getBlockPos();
        int radius = (int) Math.ceil(reach) + 1;
        double selfLimit = me.getHealth() + me.getAbsorptionAmount();

        record Candidate(PlayerEntity target, BlockPos cell, double targetDamage, double selfDamage, double score) {
        }
        List<Candidate> candidates = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos cell = origin.add(dx, dy, dz);
                    Vec3d center = Vec3d.ofCenter(cell);
                    if (eye.squaredDistanceTo(center) > reachSq) continue;
                    if (!usable(world, me, cell)) continue;
                    Double selfDamage = null;
                    for (PlayerEntity target : targets) {
                        if (target.getEntityPos().distanceTo(center) > MAX_BLAST_DISTANCE) continue;
                        double targetDamage = targetDamage(world, target, center, params);
                        if (targetDamage < params.minTargetDamage()) continue;
                        if (selfDamage == null) {
                            selfDamage = BlastModel.damage(world, me, center, BlastModel.ANCHOR_POWER);
                        }
                        if (selfDamage > params.maxSelfDamage() || selfDamage >= selfLimit) continue;
                        double score = targetDamage - SELF_WEIGHT * selfDamage
                                - DISTANCE_WEIGHT * eye.distanceTo(center);
                        if (params.preferUnder() && cell.getY() <= MathHelper.floor(target.getY())) {
                            score += UNDER_BONUS;
                        }
                        candidates.add(new Candidate(target, cell, targetDamage, selfDamage, score));
                    }
                }
            }
        }
        if (candidates.isEmpty()) return null;
        candidates.sort(Comparator.comparingDouble(Candidate::score).reversed());

        float yaw = RotationManager.getFinalYaw();
        float pitch = RotationManager.getFinalPitch();
        Plan best = null;
        for (int i = 0; i < candidates.size() && i < RESOLVE_TOP; i++) {
            Candidate candidate = candidates.get(i);
            PlaceSolution placement = PlaceResolver.resolvePlacement(mc, candidate.cell(), reach);
            if (placement == null) continue;
            float[] needed = RotationManager.calculateRotationsToPos(placement.hitVec(), yaw);
            double turn = Math.abs(MathHelper.wrapDegrees(needed[0] - yaw)) + Math.abs(needed[1] - pitch);
            double score = candidate.score() - TURN_WEIGHT * turn;
            if (best == null || score > best.score()) {
                best = new Plan(candidate.target(), candidate.cell(), placement,
                        candidate.targetDamage(), candidate.selfDamage(), score);
            }
        }
        return best;
    }

    /** The blast at {@code cell} against {@code target} and against you, as the planner scores it. */
    public static Eval evaluate(MinecraftClient mc, PlayerEntity target, BlockPos cell, Params params) {
        Vec3d center = Vec3d.ofCenter(cell);
        double targetDamage = targetDamage(mc.world, target, center, params);
        double selfDamage = BlastModel.damage(mc.world, mc.player, center, BlastModel.ANCHOR_POWER);
        return new Eval(targetDamage, selfDamage);
    }

    /** Whether {@code target} is still one the planner would consider. */
    public static boolean validTarget(MinecraftClient mc, PlayerEntity target, double range, Predicate<PlayerEntity> allow) {
        if (target == null || mc.player == null || target == mc.player || !target.isAlive() || target.isRemoved()
                || target.getHealth() <= 0.0f || target.isSpectator() || target.isCreative()) {
            return false;
        }
        if (mc.player.distanceTo(target) > range) return false;
        if (FriendManager.getInstance().isFriend(target.getUuid())) return false;
        return allow == null || allow.test(target);
    }

    /** Whether an anchor can still go into {@code cell}: free, placeable, and with something to click. */
    public static boolean usable(World world, ClientPlayerEntity me, BlockPos cell) {
        if (!world.getBlockState(cell).isReplaceable() || !world.getFluidState(cell).isEmpty()) return false;
        if (!hasSolidNeighbour(world, cell)) return false;
        return world.canPlace(Blocks.RESPAWN_ANCHOR.getDefaultState(), cell, ShapeContext.of(me));
    }

    private static double targetDamage(World world, PlayerEntity target, Vec3d center, Params params) {
        if (params.shieldAware() && BlastModel.shieldBlocks(target, center)) return 0.0;
        return BlastModel.damage(world, target, center, BlastModel.ANCHOR_POWER);
    }

    private static List<PlayerEntity> targets(MinecraftClient mc, Params params) {
        List<PlayerEntity> result = new ArrayList<>();
        for (PlayerEntity player : mc.world.getPlayers()) {
            if (validTarget(mc, player, params.range(), params.allow())) result.add(player);
        }
        result.sort(Comparator.comparingDouble(mc.player::distanceTo));
        return result.size() > MAX_TARGETS ? new ArrayList<>(result.subList(0, MAX_TARGETS)) : result;
    }

    private static boolean hasSolidNeighbour(World world, BlockPos cell) {
        for (Direction direction : Direction.values()) {
            var state = world.getBlockState(cell.offset(direction));
            if (!state.isAir() && !state.isReplaceable()) return true;
        }
        return false;
    }
}
