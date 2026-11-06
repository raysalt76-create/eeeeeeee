package interstellarTikiTiks.apexx.client.module.implementation.combat;

import interstellarTikiTiks.apexx.client.friend.FriendManager;
import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.ModeSetting;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import interstellarTikiTiks.apexx.client.module.hooks.AttackExecutionAware;
import interstellarTikiTiks.apexx.client.module.hooks.MovementInputModifier;
import interstellarTikiTiks.apexx.client.module.hooks.RenderFrameAware;
import interstellarTikiTiks.apexx.client.module.implementation.player.BotFilterProvider;
import interstellarTikiTiks.apexx.client.util.ModuleAccess;
import interstellarTikiTiks.apexx.client.util.SystemInputSimulator;
import interstellarTikiTiks.apexx.client.util.combat.MeleeMechanics;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.math.Vec3d;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Strafe: after you land a hit on a valid target, moves you sideways.
 *
 * <ul>
 *   <li><b>On Hit</b> (the original behaviour): holds the real left/right key through the input simulator for
 *   Duration, exactly what a player tapping A or D produces.</li>
 *   <li><b>Circle / Zigzag / Random</b>: a hit starts a pattern that runs for Pattern Duration (0 = for as long as
 *   a target stays in range) and stops the moment no valid target is within Range. These patterns change the
 *   movement input record through {@link MovementInputModifier} (as AutoWalk does), so no key ever sticks and a
 *   side key you press yourself always wins.</li>
 * </ul>
 */
public class Strafe extends Module implements AttackExecutionAware, RenderFrameAware, MovementInputModifier, interstellarTikiTiks.apexx.client.module.hooks.ClickSimulating {

    private static final String ON_HIT = "On Hit";
    private static final String CIRCLE = "Circle";
    private static final String ZIGZAG = "Zigzag";
    private static final String RANDOM = "Random";

    private final ModeSetting pattern = new ModeSetting("Pattern", ON_HIT, ON_HIT, CIRCLE, ZIGZAG, RANDOM)
            .setDescription("On Hit: holds one side for Duration after each hit. Circle: keeps strafing one way around the target (flips when you run into a wall). Zigzag: flips left/right every Switch Interval. Random: picks left, right or a short pause at random every Switch Interval. Patterns start on a hit and stop as soon as no target is in Range.");
    private final ModeSetting targets = new ModeSetting("Targets", "Players Only", "Players Only", "Mobs Only", "Players & Mobs")
            .setDescription("Which hits start a strafe: only players, only non-player living mobs, or both. Armour stands and item frames never count.");
    private final ModeSetting direction = new ModeSetting("Direction", "Random", "Random", "Left", "Right", "Alternate")
            .setDescription("Which way to strafe after a hit (and which way Circle and Zigzag start). Random picks a side each time; Alternate flips left/right on every hit.");
    private final BooleanSetting ignoreFriends = new BooleanSetting("Ignore Friends", true)
            .setDescription("Hitting a player on your friends list never triggers a strafe, and friends never keep a pattern running.");
    private final SliderSetting range = new SliderSetting("Range", 6.0, 2.0, 12.0, 1)
            .setDescription("Patterns only run while a valid target is within this many blocks; they stop the moment none is.");
    private final BooleanSetting disableInLiquids = new BooleanSetting("Disable In Liquids", false)
            .setDescription("Never start a strafe while you are in water or lava, and stop a running hold or pattern when you enter them.");
    private final BooleanSetting disableInAir = new BooleanSetting("Disable In Air", false)
            .setDescription("Never start a strafe while you are off the ground, and stop a running hold or pattern when you leave it, e.g. for a crit jump.");

    private final SliderSetting duration = new SliderSetting("Duration (ms)", 300, 0, 1000, 0).withJitter(60)
            .setSection("Delays")
            .setDescription("On Hit: how long the sideways key is held after a hit, in milliseconds (up to 1 second). The StDev varies it per hit so it never looks metronomic. 0 disables the hold.");
    private final SliderSetting patternDuration = new SliderSetting("Pattern Duration (ms)", 1500, 0, 10000, 0).withJitter(250)
            .setSection("Delays")
            .setDescription("Circle / Zigzag / Random: how long a pattern runs after the hit that started it. 0 = until no target is within Range.");
    private final SliderSetting switchInterval = new SliderSetting("Switch Interval (ms)", 350, 0, 2000, 0).withJitter(80)
            .setSection("Delays")
            .setDescription("Zigzag / Random: time between direction changes. 0 changes direction every tick.");

    /** +1 = right, -1 = left; current side while strafing and the side Alternate will use next. */
    private int activeSide;
    private int nextAlternateSide = 1;
    private long releaseAtMs = -1L;

    // ---- pattern state (client thread only) ----
    private boolean patternActive;
    /** +1 right, -1 left, 0 pause (Random only). */
    private int patternSide;
    private long patternEndMs = -1L;
    private long nextSwitchMs;
    private boolean targetInRange;

    public Strafe() {
        super("Strafe", "After you hit a valid target, moves you sideways so you juke the moment a hit lands: a timed side hold, or a Circle / Zigzag / Random pattern that keeps going while the target is in range. Choose players/mobs, the direction and the timings.", Category.COMBAT);
        addSetting(pattern);
        addSetting(targets);
        addSetting(direction);
        addSetting(ignoreFriends);
        addSetting(disableInLiquids);
        addSetting(disableInAir);
        addSetting(range);
        range.setVisibility(() -> !pattern.is(ON_HIT));
        addSetting(duration);
        duration.setVisibility(() -> pattern.is(ON_HIT));
        addSetting(patternDuration);
        patternDuration.setVisibility(() -> !pattern.is(ON_HIT));
        addSetting(switchInterval);
        switchInterval.setVisibility(() -> pattern.is(ZIGZAG) || pattern.is(RANDOM));
    }

    @Override
    public void onDisable() {
        stopStrafe();
        stopPattern();
    }

    @Override
    protected void onRuntimeStateReleased() {
        stopStrafe();
        stopPattern();
    }

    /** Disable In Liquids / Disable In Air, read wherever the module starts or continues a strafe. */
    private boolean isGated(Entity player) {
        return (disableInLiquids.isEnabled() && MeleeMechanics.isInLiquid(player))
                || (disableInAir.isEnabled() && MeleeMechanics.isInAir(player));
    }

    @Override
    public void onVanillaEntityAttack(Entity target, Vec3d hitPosition) {
        if (!isEnabled() || mc.player == null || mc.world == null) return;
        if (isGated(mc.player)) return;
        if (!isValidTarget(target)) return;
        if (!pattern.is(ON_HIT)) {
            startPattern();
            return;
        }
        long holdMs = (long) duration.sample();
        if (holdMs <= 0L) return;

        int side = resolveSide();
        if (side != activeSide) {
            releaseSideKeys();
            activeSide = side;
        }
        if (side > 0) {
            SystemInputSimulator.pressRight();
        } else {
            SystemInputSimulator.pressLeft();
        }
        releaseAtMs = System.currentTimeMillis() + holdMs;
    }

    @Override
    public void onRenderFrame() {
        // Poll on the render frame for millisecond-accurate release, rather than quantising to ticks.
        // A gate turning on mid-hold releases the side key early through the same path.
        if (releaseAtMs >= 0L && (System.currentTimeMillis() >= releaseAtMs
                || (mc.player != null && isGated(mc.player)))) {
            stopStrafe();
        }
    }

    @Override
    public void onTick() {
        if (!patternActive) return;
        if (mc.player == null || mc.world == null || pattern.is(ON_HIT) || isGated(mc.player)) {
            stopPattern();
            return;
        }
        long now = System.currentTimeMillis();
        targetInRange = anyTargetInRange();
        if (!targetInRange || (patternEndMs >= 0L && now >= patternEndMs)) {
            stopPattern();
            return;
        }
        if (pattern.is(CIRCLE)) {
            // Running into a wall would pin the orbit; go the other way round instead.
            if (mc.player.horizontalCollision && patternSide != 0) patternSide = -patternSide;
            return;
        }
        if (now >= nextSwitchMs) {
            if (pattern.is(ZIGZAG)) {
                patternSide = patternSide == 0 ? resolveSide() : -patternSide;
            } else {
                int roll = ThreadLocalRandom.current().nextInt(5);
                patternSide = roll < 2 ? -1 : roll < 4 ? 1 : 0;
            }
            nextSwitchMs = now + ms(switchInterval.sample());
        }
    }

    @Override
    public PlayerInput modifyMovementInput(MinecraftClient client, PlayerInput input) {
        if (!patternActive || !targetInRange || patternSide == 0 || client.player == null
                || client.currentScreen != null || isGated(client.player)) {
            return input;
        }
        // The player's own side key always wins over the pattern.
        if (input.left() || input.right()) return input;
        boolean right = patternSide > 0;
        return new PlayerInput(input.forward(), input.backward(), !right, right, input.jump(), input.sneak(),
                input.sprint());
    }

    private void startPattern() {
        long now = System.currentTimeMillis();
        if (!patternActive) {
            patternSide = resolveSide();
            nextSwitchMs = now + ms(switchInterval.sample());
        }
        patternActive = true;
        targetInRange = true;
        patternEndMs = patternDuration.getValue() <= 0.0 ? -1L : now + Math.max(1L, (long) patternDuration.sample());
    }

    /** A sampled ms value as a non-negative wait (NaN = 0); the sliders carry their own upper bounds. */
    private static long ms(double sampled) {
        return Double.isFinite(sampled) && sampled > 0.0 ? Math.round(sampled) : 0L;
    }

    private void stopPattern() {
        patternActive = false;
        patternSide = 0;
        patternEndMs = -1L;
        targetInRange = false;
    }

    private boolean anyTargetInRange() {
        double max = range.getValue();
        double maxSq = max * max;
        for (Entity entity : mc.world.getEntities()) {
            if (entity == mc.player || mc.player.squaredDistanceTo(entity) > maxSq) continue;
            if (isValidTarget(entity)) return true;
        }
        return false;
    }

    private int resolveSide() {
        return switch (direction.getCurrentMode()) {
            case "Left" -> -1;
            case "Right" -> 1;
            case "Alternate" -> {
                int s = nextAlternateSide;
                nextAlternateSide = -nextAlternateSide;
                yield s;
            }
            default -> ThreadLocalRandom.current().nextBoolean() ? 1 : -1;
        };
    }

    private boolean isValidTarget(Entity target) {
        if (!(target instanceof LivingEntity living) || !living.isAlive() || target instanceof ArmorStandEntity) {
            return false;
        }
        if (target == mc.player) return false;
        boolean player = target instanceof PlayerEntity;
        boolean wantPlayers = !targets.is("Mobs Only");
        boolean wantMobs = !targets.is("Players Only");
        if (player && !wantPlayers) return false;
        if (!player && !wantMobs) return false;
        if (player && ignoreFriends.isEnabled() && FriendManager.getInstance().isFriend(target.getUuid())) return false;
        // Sticky Target: while another player is locked, only that player counts.
        if (!interstellarTikiTiks.apexx.client.util.combat.StickyTargets.allows(this, target)) return false;
        for (BotFilterProvider bots : ModuleAccess.getEnabledHooks(BotFilterProvider.class)) {
            if (bots.isBot(target)) return false;
        }
        return true;
    }

    private void stopStrafe() {
        releaseAtMs = -1L;
        releaseSideKeys();
        activeSide = 0;
    }

    private void releaseSideKeys() {
        SystemInputSimulator.releaseLeft();
        SystemInputSimulator.releaseRight();
    }
}
