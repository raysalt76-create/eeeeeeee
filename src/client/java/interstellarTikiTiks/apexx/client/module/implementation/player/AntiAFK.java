package interstellarTikiTiks.apexx.client.module.implementation.player;

import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import interstellarTikiTiks.apexx.client.module.hooks.MovementInputModifier;
import interstellarTikiTiks.apexx.client.util.RotationManager;
import interstellarTikiTiks.apexx.client.util.combat.SmoothAim;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.Hand;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.math.MathHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Anti-AFK: every so often (a jittered interval) does one small, ordinary action - a short look around, a jump,
 * a brief side step or an arm swing - so servers that kick idle players see you as active. Movement goes
 * through the movement input record ({@link MovementInputModifier}), the look through an owner-scoped
 * {@link RotationManager} request, so nothing sticks when the module is turned off.
 */
public final class AntiAFK extends Module implements MovementInputModifier {

    private static final int ROTATE = 0;
    private static final int JUMP = 1;
    private static final int STRAFE = 2;
    private static final int SWING = 3;

    private final BooleanSetting rotate = new BooleanSetting("Rotate", true)
            .setDescription("Turns the look a little to one side and back.");
    private final BooleanSetting jump = new BooleanSetting("Jump", true)
            .setDescription("Jumps once.");
    private final BooleanSetting strafe = new BooleanSetting("Strafe", false)
            .setDescription("Steps briefly left or right.");
    private final BooleanSetting swing = new BooleanSetting("Swing", true)
            .setDescription("Swings your arm, like clicking at the air.");
    private final BooleanSetting onlyWhenIdle = new BooleanSetting("Only When Idle", true)
            .setDescription("Only acts once you have not moved, looked around or clicked for the Idle Time, so it never gets in your way while you play.");
    private final SliderSetting idleTime = new SliderSetting("Idle Time (s)", 30, 5, 600, 0)
            .setDependsOn("Only When Idle")
            .setDescription("How long without any input of yours before Anti-AFK starts acting.");
    private final SliderSetting rotateAmount = new SliderSetting("Rotate Amount", 25, 5, 90, 0)
            .setDependsOn("Rotate")
            .setDescription("Largest look turn, in degrees, of a Rotate action.");

    private final SliderSetting interval = new SliderSetting("Interval (ms)", 15000, 0, 120000, 0).withJitter(4000)
            .setSection("Delays")
            .setDescription("Time between two actions. The StDev varies it so the actions never come at a fixed beat.");
    private final SliderSetting strafeTime = new SliderSetting("Strafe Time (ms)", 300, 0, 1500, 0).withJitter(80)
            .setSection("Delays")
            .setDescription("How long a Strafe action holds the side step.");

    private long nextActionAt = -1L;
    private long lastUserInputAt;
    private float lastYaw;
    private float lastPitch;
    // Movement plan, consumed in the movement tick.
    private int jumpTicks;
    private int strafeSide;
    private long strafeUntil = -1L;
    // Look plan.
    private boolean turning;
    private boolean turningBack;
    private float baseYaw;
    private float turnYaw;
    private long turnUntil = -1L;

    public AntiAFK() {
        super("AntiAFK", "Keeps you from being kicked for idling: every so often does one small, normal action (a short look around, a jump, a side step or an arm swing) at a randomised interval, optionally only after you have been idle for a while.", Category.PLAYER);
        addSetting(rotate);
        addSetting(jump);
        addSetting(strafe);
        addSetting(swing);
        addSetting(onlyWhenIdle);
        addSetting(idleTime);
        addSetting(rotateAmount);
        addSetting(interval);
        addSetting(strafeTime);
    }

    @Override
    public void onEnable() {
        resetPlan();
        lastUserInputAt = System.currentTimeMillis();
        if (mc.player != null) {
            lastYaw = mc.player.getYaw();
            lastPitch = mc.player.getPitch();
        }
    }

    @Override
    public void onDisable() {
        resetPlan();
    }

    @Override
    protected void onRuntimeStateReleased() {
        resetPlan();
    }

    private void resetPlan() {
        nextActionAt = -1L;
        jumpTicks = 0;
        strafeSide = 0;
        strafeUntil = -1L;
        endTurn();
    }

    /** A sampled ms value as a non-negative wait (NaN = 0); the sliders carry their own upper bounds. */
    private static long ms(double sampled) {
        return Double.isFinite(sampled) && sampled > 0.0 ? Math.round(sampled) : 0L;
    }

    private void endTurn() {
        if (turning) RotationManager.clearTarget(this);
        turning = false;
        turningBack = false;
        turnUntil = -1L;
    }

    @Override
    public void onTick() {
        if (mc.player == null || mc.world == null || mc.player.isDead()) {
            resetPlan();
            return;
        }
        long now = System.currentTimeMillis();
        trackUserInput(now);
        stepTurn(now);
        if (strafeUntil >= 0L && now >= strafeUntil) {
            strafeSide = 0;
            strafeUntil = -1L;
        }
        if (onlyWhenIdle.isEnabled() && now - lastUserInputAt < Math.round(idleTime.getValue() * 1000.0)) {
            nextActionAt = -1L;
            return;
        }
        if (nextActionAt < 0L) {
            nextActionAt = now + ms(interval.sample());
            return;
        }
        if (now < nextActionAt || turning || strafeSide != 0 || jumpTicks > 0) return;
        nextActionAt = now + Math.max(50L, ms(interval.sample()));
        performAction(now);
    }

    /** Any key, click or look change the player made themselves counts as activity. */
    private void trackUserInput(long now) {
        boolean keys = mc.options.forwardKey.isPressed() || mc.options.backKey.isPressed()
                || mc.options.leftKey.isPressed() || mc.options.rightKey.isPressed()
                || mc.options.attackKey.isPressed() || mc.options.useKey.isPressed()
                || (mc.options.jumpKey.isPressed() && jumpTicks == 0) || mc.options.sneakKey.isPressed()
                || mc.currentScreen != null;
        float yaw = mc.player.getYaw();
        float pitch = mc.player.getPitch();
        boolean looked = !turning && (Math.abs(MathHelper.wrapDegrees(yaw - lastYaw)) > 0.5f
                || Math.abs(pitch - lastPitch) > 0.5f);
        if (keys || looked) lastUserInputAt = now;
        lastYaw = yaw;
        lastPitch = pitch;
    }

    private void performAction(long now) {
        List<Integer> options = new ArrayList<>(4);
        if (rotate.isEnabled()) options.add(ROTATE);
        if (jump.isEnabled()) options.add(JUMP);
        if (strafe.isEnabled()) options.add(STRAFE);
        if (swing.isEnabled()) options.add(SWING);
        if (options.isEmpty()) return;
        ThreadLocalRandom random = ThreadLocalRandom.current();
        switch (options.get(random.nextInt(options.size()))) {
            case ROTATE -> {
                double amount = rotateAmount.getValue() * (0.5 + random.nextDouble() * 0.5);
                baseYaw = mc.player.getYaw();
                turnYaw = baseYaw + (float) (random.nextBoolean() ? amount : -amount);
                turning = true;
                turningBack = false;
                turnUntil = now + 1500L;
            }
            case JUMP -> {
                if (mc.player.isOnGround()) jumpTicks = 1;
            }
            case STRAFE -> {
                strafeSide = random.nextBoolean() ? 1 : -1;
                strafeUntil = now + Math.max(50L, ms(strafeTime.sample()));
            }
            default -> mc.player.swingHand(Hand.MAIN_HAND);
        }
    }

    private void stepTurn(long now) {
        if (!turning) return;
        if (mc.currentScreen != null) {
            endTurn();
            return;
        }
        float goal = turningBack ? baseYaw : turnYaw;
        float pitch = mc.player.getPitch();
        SmoothAim.aimAngles(this, goal, pitch, 90.0, false);
        float error = Math.abs(MathHelper.wrapDegrees(mc.player.getYaw() - goal));
        if (error < 1.5f || now >= turnUntil) {
            if (turningBack) {
                endTurn();
            } else {
                turningBack = true;
                turnUntil = now + 1500L;
            }
        }
        lastYaw = mc.player.getYaw();
        lastPitch = mc.player.getPitch();
    }

    @Override
    public PlayerInput modifyMovementInput(MinecraftClient client, PlayerInput input) {
        if (client.player == null || client.currentScreen != null) return input;
        boolean doJump = false;
        if (jumpTicks > 0) {
            jumpTicks--;
            doJump = true;
        }
        if (!doJump && strafeSide == 0) return input;
        boolean left = input.left();
        boolean right = input.right();
        if (strafeSide != 0 && !left && !right) {
            left = strafeSide < 0;
            right = strafeSide > 0;
        }
        return new PlayerInput(input.forward(), input.backward(), left, right, input.jump() || doJump,
                input.sneak(), input.sprint());
    }
}
