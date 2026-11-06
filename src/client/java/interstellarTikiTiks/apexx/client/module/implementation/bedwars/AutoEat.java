package interstellarTikiTiks.apexx.client.module.implementation.bedwars;

import interstellarTikiTiks.apexx.client.friend.FriendManager;
import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.ModeSetting;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import interstellarTikiTiks.apexx.client.module.StringSetting;
import interstellarTikiTiks.apexx.client.util.SystemInputSimulator;
import net.minecraft.util.hit.HitResult;

import java.util.Locale;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.FoodComponent;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.consume.UseAction;

/**
 * Eats the most useful food from your hotbar or offhand when hunger or health is low.
 *
 * <p>1.21 eating takes time, so the use is held until the food is actually consumed (checked against
 * the stack), then the slot is given back after a deferred delay. Food is scored by what it restores
 * right now (no wasted hunger points), golden apples are kept for low health, harmful foods and chorus
 * fruit are never chosen, and a food that cannot be eaten at your hunger level is never attempted. It
 * waits until no enemy is close, and can stop eating when one closes in.
 */
public class AutoEat extends Module implements interstellarTikiTiks.apexx.client.module.hooks.ClickSimulating {

    private final SliderSetting hungerThreshold = new SliderSetting("Hunger Threshold", 14, 1, 19, 0)
            .setDescription("Eat when your food level (0-20) is at or below this.");
    private final SliderSetting healthThreshold = new SliderSetting("Health Threshold", 12, 1, 19, 1)
            .setDescription("Also eat when health is at or below this and hunger is not full, so natural regeneration can resume.");
    private final SliderSetting gappleHealth = new SliderSetting("Golden Apple Health", 10, 0, 19, 1)
            .setDescription("Golden apples are only eaten at or below this health. 0 = never eat golden apples automatically.");
    private final BooleanSetting allowEnchanted = new BooleanSetting("Enchanted Golden Apples", false)
            .setDescription("Allow enchanted golden apples too (rare; off keeps them for you to use).");
    private final BooleanSetting avoidHarmful = new BooleanSetting("Avoid Harmful Food", true)
            .setDescription("Never eat rotten flesh, spider eyes, pufferfish, poisonous potatoes, suspicious stew or chorus fruit.");
    private final BooleanSetting preferOffhand = new BooleanSetting("Prefer Offhand", true)
            .setDescription("Eat food already in your offhand first, so no hotbar swap is needed (only when your main hand item has no use of its own).");
    private final BooleanSetting outOfCombat = new BooleanSetting("Out Of Combat Only", true)
            .setDescription("Do not start eating while a non-friend player is within Combat Range. Golden apples at low health are still allowed.");
    private final SliderSetting combatRange = new SliderSetting("Combat Range", 8, 3, 24, 1)
            .setDependsOn("Out Of Combat Only")
            .setDescription("Distance, in blocks, within which another player counts as a threat.");
    private final BooleanSetting abortOnThreat = new BooleanSetting("Stop On Threat", true)
            .setDescription("Stop eating ordinary food if a non-friend player comes within 4 blocks.");
    private final BooleanSetting eatWhileRetreating = new BooleanSetting("Eat While Retreating", false)
            .setDescription("While you are getting away from the nearest threat (the gap to them is growing, or they are behind you while you move away), eating is allowed even with them close: Out Of Combat Only and Stop On Threat are relaxed for as long as the retreat lasts.");
    private final SliderSetting retreatGain = new SliderSetting("Retreat Gain", 0.6, 0.2, 3.0, 1)
            .setDependsOn("Eat While Retreating")
            .setDescription("How much the gap to the threat, in blocks, must have grown over the last ~0.4 seconds to count as retreating.");

    private final SliderSetting reactionDelay = new SliderSetting("Reaction Delay (ms)", 150, 0, 1500, 0).withJitter(50)
            .setSection("Delays")
            .setDescription("Wait after the need to eat appears before starting, like a human noticing the hunger bar.");
    private final SliderSetting swapBack = new SliderSetting("Swap Back Delay (ms)", 120, 5, 1000, 0).withJitter(40)
            .setSection("Delays")
            .setDescription("Wait after eating before returning to your previous slot (never in the same instant as the last use).");
    private final SliderSetting retryCooldown = new SliderSetting("Retry Cooldown (ms)", 1500, 200, 10000, 0).withJitter(300)
            .setSection("Delays")
            .setDescription("After a refused or interrupted attempt, wait this long before trying again.");

    // ---- Golden Heads (formerly the separate AutoGHead module) ----
    private final BooleanSetting goldenHeads = new BooleanSetting("Golden Heads", false)
            .setSection("Golden Heads")
            .setDescription("Uses a server Golden Head (a named player head) when your health is low: held until eaten when the server makes heads edible, or clicked once and confirmed when a plugin heals instantly. Never places the head as a block. Takes priority over ordinary food.");
    private final SliderSetting headHealth = new SliderSetting("Golden Head Health", 8, 1, 19, 1)
            .setSection("Golden Heads").setDependsOn("Golden Heads")
            .setDescription("Use a golden head when health is at or below this.");
    private final StringSetting headName = new StringSetting("Name Contains", "golden head");
    private final BooleanSetting playerHeads = new BooleanSetting("Player Heads", true)
            .setSection("Golden Heads").setDependsOn("Golden Heads")
            .setDescription("Match player heads whose name contains the text above.");
    private final ModeSetting headUse = new ModeSetting("Head Use", "Auto", "Hold", "Instant")
            .setSection("Golden Heads").setDependsOn("Golden Heads")
            .setDescription("How a head is used. Auto: held until eaten when the server makes it edible, otherwise clicked once (plugin heal). Hold: always held until eaten. Instant: always one click, confirmed by the heal.");
    private final BooleanSetting gappleFallback = new BooleanSetting("Golden Apple Fallback", false)
            .setSection("Golden Heads").setDependsOn("Golden Heads")
            .setDescription("When no golden head is in the hotbar, use a golden apple instead at Golden Head Health (held until eaten).");
    private final SliderSetting headReaction = new SliderSetting("Head Reaction Delay (ms)", 50, 0, 600, 0).withJitter(15)
            .setSection("Delays")
            .setDescription("Golden Heads: wait after health drops below Golden Head Health before swapping to the head.");
    private final SliderSetting headSwapBack = new SliderSetting("Head Swap Back Delay (ms)", 80, 5, 600, 0).withJitter(25)
            .setSection("Delays")
            .setDescription("Golden Heads: wait after the head is used before returning to your previous slot.");
    private final SliderSetting headCooldown = new SliderSetting("Head Cooldown (ms)", 1500, 200, 10000, 0).withJitter(250)
            .setSection("Delays")
            .setDescription("Golden Heads: least time between two heads; the absorption from one needs a moment to show.");

    private final ConsumeRoutine routine = new ConsumeRoutine();
    private long startAtMs = -1L;
    private long nextAllowedMs;
    /** The active routine is a golden head (or its golden-apple fallback), not ordinary food. */
    private boolean headRoutine;
    private long headStartAtMs = -1L;
    private long headNextAllowedMs;

    public AutoEat() {
        super("AutoEat", "Eats the most useful food from your hotbar or offhand when hunger or health is low, holding use until it is really eaten and swapping back afterwards. Keeps golden apples for low health, skips harmful food, and waits until no enemy is close. Can also use server Golden Heads at low health (Golden Heads section).", Category.BEDWARS);
        headName.setDescription("Golden Heads: text the head's name must contain (case-insensitive), e.g. \"golden head\".");
        headName.setSection("Golden Heads");
        headName.setDependsOn("Golden Heads");
        addSetting(hungerThreshold);
        addSetting(healthThreshold);
        addSetting(gappleHealth);
        addSetting(allowEnchanted);
        addSetting(avoidHarmful);
        addSetting(preferOffhand);
        addSetting(outOfCombat);
        addSetting(combatRange);
        addSetting(abortOnThreat);
        addSetting(eatWhileRetreating);
        addSetting(retreatGain);
        addSetting(goldenHeads);
        addSetting(headHealth);
        addSetting(headName);
        addSetting(playerHeads);
        addSetting(headUse);
        addSetting(gappleFallback);
        addSetting(reactionDelay);
        addSetting(swapBack);
        addSetting(retryCooldown);
        addSetting(headReaction);
        addSetting(headSwapBack);
        addSetting(headCooldown);
    }

    @Override
    public void onDisable() {
        routine.abort();
        startAtMs = -1L;
        headStartAtMs = -1L;
        headRoutine = false;
        resetRetreat();
    }

    @Override
    protected void onRuntimeStateReleased() {
        routine.abort();
        startAtMs = -1L;
        headStartAtMs = -1L;
        headRoutine = false;
        resetRetreat();
    }

    @Override
    public void onTick() {
        if (mc.player == null || mc.world == null) {
            routine.abort();
            headRoutine = false;
            resetRetreat();
            return;
        }
        long now = System.currentTimeMillis();
        updateRetreat();
        if (routine.active() && headRoutine) {
            routine.tick();
            if (!routine.active()) {
                headNextAllowedMs = now + Math.round(headCooldown.sample());
                headRoutine = false;
            }
            routine.takeResult();
            return;
        }
        headRoutine = false;
        if (!routine.active() && tryGoldenHead(now)) return;
        if (routine.active()) {
            // Eating slows you to a walk, so a retreat that started the bite keeps covering it unless the threat
            // gets right on top of you.
            boolean retreatCover = retreating || (eatStartedRetreating && nearestThreat() > RETREAT_CLOSE_LIMIT);
            if (routine.consuming() && abortOnThreat.isEnabled() && !eatingGoldenApple() && nearestThreat() <= 4.0
                    && !retreatCover) {
                routine.abort();
                nextAllowedMs = now + Math.round(retryCooldown.sample());
                return;
            }
            routine.tick();
            if (routine.takeResult() == ConsumeRoutine.Result.FAILED) {
                nextAllowedMs = now + Math.round(retryCooldown.sample());
            }
            return;
        }
        eatStartedRetreating = false;
        if (now < nextAllowedMs || mc.currentScreen != null || mc.player.isUsingItem()
                || SystemInputSimulator.isPhysicallyPressed(mc.options.useKey)
                || SystemInputSimulator.isPhysicallyPressed(mc.options.attackKey)) {
            startAtMs = -1L;
            return;
        }
        int choice = chooseFood();
        if (choice == Integer.MIN_VALUE) {
            startAtMs = -1L;
            return;
        }
        if (startAtMs < 0L) startAtMs = now + Math.round(reactionDelay.sample());
        if (now < startAtMs) return;
        startAtMs = -1L;
        if (!routine.start(choice, ConsumeRoutine.Kind.HOLD, 0L, Math.round(swapBack.sample()), false)) {
            nextAllowedMs = now + Math.round(retryCooldown.sample());
        } else {
            eatStartedRetreating = retreating;
        }
    }

    /**
     * Golden Heads: when health is at or below Golden Head Health, uses a named player head (or a golden apple as
     * fallback). Returns true while the head path owns this tick (waiting on its reaction delay or just started).
     */
    private boolean tryGoldenHead(long now) {
        if (!goldenHeads.isEnabled()) {
            headStartAtMs = -1L;
            return false;
        }
        if (now < headNextAllowedMs || mc.currentScreen != null || mc.player.isUsingItem()
                || mc.player.getHealth() > headHealth.getValue()) {
            headStartAtMs = -1L;
            return false;
        }
        int slot = findHeadSlot();
        boolean head = slot >= 0;
        if (!head && gappleFallback.isEnabled()) slot = findGappleSlot();
        if (slot < 0) {
            headStartAtMs = -1L;
            return false;
        }
        ItemStack stack = mc.player.getInventory().getStack(slot);
        boolean hold;
        if (!head || headUse.is("Hold")) hold = true;
        else if (headUse.is("Instant")) hold = false;
        else hold = stack.contains(DataComponentTypes.CONSUMABLE);
        // A non-edible head is a block: only click it into the air, where the server plugin handles it.
        if (!hold && mc.crosshairTarget != null && mc.crosshairTarget.getType() == HitResult.Type.BLOCK) {
            return true;
        }
        if (headStartAtMs < 0L) headStartAtMs = now + Math.round(headReaction.sample());
        if (now < headStartAtMs) return true;
        headStartAtMs = -1L;
        startAtMs = -1L;
        ConsumeRoutine.Kind kind = hold ? ConsumeRoutine.Kind.HOLD : ConsumeRoutine.Kind.INSTANT;
        if (routine.start(slot, kind, 0L, Math.round(headSwapBack.sample()), false)) {
            headRoutine = true;
        } else {
            headNextAllowedMs = now + Math.round(headCooldown.sample());
        }
        return true;
    }

    private int findHeadSlot() {
        if (!playerHeads.isEnabled()) return -1;
        String needle = headName.getValue() == null ? "" : headName.getValue().trim().toLowerCase(Locale.ROOT);
        for (int i = 0; i < 9; i++) {
            ItemStack s = mc.player.getInventory().getStack(i);
            if (!s.isOf(Items.PLAYER_HEAD)) continue;
            if (needle.isEmpty() || s.getName().getString().toLowerCase(Locale.ROOT).contains(needle)) return i;
        }
        return -1;
    }

    private int findGappleSlot() {
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).isOf(Items.GOLDEN_APPLE)) return i;
        }
        return -1;
    }

    /** The slot to eat from (0-8, or -1 for the offhand), or {@link Integer#MIN_VALUE} when nothing should be eaten. */
    private int chooseFood() {
        PlayerEntity p = mc.player;
        int food = p.getHungerManager().getFoodLevel();
        float health = p.getHealth();
        boolean hungry = food <= hungerThreshold.getValue();
        boolean lowHealth = health <= healthThreshold.getValue() && food < 20;
        boolean gappleTime = gappleHealth.getValue() > 0 && health <= gappleHealth.getValue();
        if (!hungry && !lowHealth && !gappleTime) return Integer.MIN_VALUE;
        boolean threatened = outOfCombat.isEnabled() && nearestThreat() <= combatRange.getValue() && !retreating;

        int best = Integer.MIN_VALUE;
        double bestScore = 0.0;
        if (preferOffhand.isEnabled() && mainHandPassesUse()) {
            double s = score(p.getOffHandStack(), food, hungry || lowHealth, gappleTime, threatened);
            if (s > 0.0) {
                best = -1;
                bestScore = s + 3.0;
            }
        }
        for (int i = 0; i < 9; i++) {
            double s = score(p.getInventory().getStack(i), food, hungry || lowHealth, gappleTime, threatened);
            if (s > bestScore) {
                bestScore = s;
                best = i;
            }
        }
        return best;
    }

    private double score(ItemStack stack, int food, boolean wantFood, boolean gappleTime, boolean threatened) {
        if (stack == null || stack.isEmpty()) return 0.0;
        FoodComponent f = stack.get(DataComponentTypes.FOOD);
        if (f == null) return 0.0;
        if (avoidHarmful.isEnabled() && isHarmful(stack)) return 0.0;
        boolean golden = stack.isOf(Items.GOLDEN_APPLE);
        boolean enchanted = stack.isOf(Items.ENCHANTED_GOLDEN_APPLE);
        if (enchanted && !allowEnchanted.isEnabled()) return 0.0;
        if ((golden || enchanted) && !gappleTime) return 0.0;
        if (!golden && !enchanted && (!wantFood || threatened)) return 0.0;
        if (!mc.player.canConsume(f.canAlwaysEat())) return 0.0;
        int missing = 20 - food;
        double useful = Math.min(f.nutrition(), missing) * 2.0 + Math.min(f.saturation(), missing);
        double waste = Math.max(0, f.nutrition() - missing);
        double s = useful - waste * 0.75;
        if (golden || enchanted) s += 25.0;
        return Math.max(0.01, s);
    }

    private static boolean isHarmful(ItemStack stack) {
        return stack.isOf(Items.ROTTEN_FLESH) || stack.isOf(Items.SPIDER_EYE) || stack.isOf(Items.PUFFERFISH)
                || stack.isOf(Items.POISONOUS_POTATO) || stack.isOf(Items.SUSPICIOUS_STEW) || stack.isOf(Items.CHORUS_FRUIT);
    }

    /** Vanilla tries the main hand first; the offhand only gets the use when the main hand item has none. */
    private boolean mainHandPassesUse() {
        ItemStack main = mc.player.getMainHandStack();
        if (main.isEmpty()) return true;
        if (main.getItem() instanceof BlockItem) return false;
        if (main.contains(DataComponentTypes.FOOD) || main.contains(DataComponentTypes.CONSUMABLE)) return false;
        return main.getUseAction() == UseAction.NONE;
    }

    private boolean eatingGoldenApple() {
        ItemStack active = mc.player.getActiveItem();
        return active.isOf(Items.GOLDEN_APPLE) || active.isOf(Items.ENCHANTED_GOLDEN_APPLE);
    }

    private double nearestThreat() {
        PlayerEntity threat = nearestThreatPlayer();
        return threat == null ? Double.MAX_VALUE : mc.player.distanceTo(threat);
    }

    private PlayerEntity nearestThreatPlayer() {
        PlayerEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (PlayerEntity other : mc.world.getPlayers()) {
            if (other == mc.player || !other.isAlive() || other.isSpectator()) continue;
            if (FriendManager.getInstance().isFriend(other.getUuid()) || mc.player.isTeammate(other)) continue;
            double distance = mc.player.distanceTo(other);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = other;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ retreat detection

    /** Ticks of distance history the retreat test looks back over (~0.4 s). */
    private static final int RETREAT_WINDOW = 8;
    private final double[] gapHistory = new double[RETREAT_WINDOW];
    private int gapCount;
    private int gapHead;
    private int trackedThreatId = Integer.MIN_VALUE;
    /** True while Eat While Retreating is on and you are getting away from the nearest threat. */
    private boolean retreating;
    /** The current bite was started during a retreat. */
    private boolean eatStartedRetreating;
    /** A threat this close always stops a bite, retreat or not. */
    private static final double RETREAT_CLOSE_LIMIT = 2.5;

    private void resetRetreat() {
        eatStartedRetreating = false;
        gapCount = 0;
        gapHead = 0;
        trackedThreatId = Integer.MIN_VALUE;
        retreating = false;
    }

    /**
     * Retreating = the gap to the nearest threat grew by at least Retreat Gain over the window, or the
     * threat is behind you (more than ~115 degrees off your look) while you are moving away from it.
     */
    private void updateRetreat() {
        if (!eatWhileRetreating.isEnabled()) {
            resetRetreat();
            return;
        }
        PlayerEntity threat = nearestThreatPlayer();
        if (threat == null) {
            resetRetreat();
            return;
        }
        if (threat.getId() != trackedThreatId) {
            resetRetreat();
            trackedThreatId = threat.getId();
        }
        double gap = mc.player.distanceTo(threat);
        gapHistory[gapHead] = gap;
        gapHead = (gapHead + 1) % RETREAT_WINDOW;
        if (gapCount < RETREAT_WINDOW) gapCount++;

        boolean gaining = false;
        if (gapCount >= RETREAT_WINDOW) {
            double oldest = gapHistory[gapHead]; // the next slot to be overwritten is the oldest sample
            gaining = gap - oldest >= retreatGain.getValue();
        }

        net.minecraft.util.math.Vec3d toThreat = threat.getEntityPos().subtract(mc.player.getEntityPos());
        net.minecraft.util.math.Vec3d flatToThreat = new net.minecraft.util.math.Vec3d(toThreat.x, 0.0, toThreat.z);
        net.minecraft.util.math.Vec3d look = mc.player.getRotationVector();
        net.minecraft.util.math.Vec3d flatLook = new net.minecraft.util.math.Vec3d(look.x, 0.0, look.z);
        net.minecraft.util.math.Vec3d velocity = mc.player.getVelocity();
        net.minecraft.util.math.Vec3d flatVelocity = new net.minecraft.util.math.Vec3d(velocity.x, 0.0, velocity.z);
        boolean behindAndMovingAway = false;
        if (flatToThreat.lengthSquared() > 1.0e-4 && flatLook.lengthSquared() > 1.0e-4) {
            net.minecraft.util.math.Vec3d dir = flatToThreat.normalize();
            boolean behind = flatLook.normalize().dotProduct(dir) < -0.42; // > ~115 degrees off the look
            boolean movingAway = flatVelocity.dotProduct(dir) < -0.05;
            behindAndMovingAway = behind && movingAway;
        }
        retreating = gaining || behindAndMovingAway;
    }
}
