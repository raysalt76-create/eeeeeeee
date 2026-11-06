package interstellarTikiTiks.apexx.client.module.implementation.combat;


import interstellarTikiTiks.apexx.client.util.combat.ModuleDelays;
import interstellarTikiTiks.apexx.client.event.EventBus;
import interstellarTikiTiks.apexx.client.friend.FriendManager;
import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.ModeSetting;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import interstellarTikiTiks.apexx.client.module.implementation.player.BotFilterProvider;
import interstellarTikiTiks.apexx.client.util.ClientInternalLog;
import interstellarTikiTiks.apexx.client.util.ModuleAccess;
import interstellarTikiTiks.apexx.client.util.RotationManager;
import interstellarTikiTiks.apexx.client.util.combat.PearlChaseSolver;
import interstellarTikiTiks.apexx.client.util.combat.PearlLimit;
import interstellarTikiTiks.apexx.client.util.combat.SlotSync;
import interstellarTikiTiks.apexx.client.util.combat.ThrowPhysics;
import interstellarTikiTiks.apexx.client.util.combat.TickActionOrder;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.projectile.thrown.EnderPearlEntity;
import net.minecraft.item.Items;
import net.minecraft.util.math.Vec3d;

import java.util.HashSet;
import java.util.Set;

/**
 * PearlChase: when an enemy pearls away, throws a counter-pearl that ends where theirs will.
 *
 * <p>The enemy pearl's flight is traced from its current state to where it ends ({@link PearlChaseSolver});
 * the counter-throw is the look whose own exact flight ends there (the flat or the lobbed arc, else the
 * nearest reachable). Each tick re-solves from the live thrower state, turns one aim step toward the
 * throw, and the pearl slot is selected and the pearl thrown in the same tick once the look that will be
 * on the wire puts the counter-pearl's flight within tolerance of the landing. Pearl throws are
 * non-placement uses: the throw is gated on the sent look's flight, not on an angle threshold.</p>
 */
public class PearlChase extends Module implements interstellarTikiTiks.apexx.client.module.hooks.ClickSimulating {

    private final ModeSetting rotationMode = new ModeSetting("Rotation Mode", "Client", "Silent")
            .setDescription("How the counter-throw is aimed. Client: your camera visibly turns to the throw. Silent: only the rotation sent to the server turns; your camera stays where it is. Both solve the throw that ends where the enemy pearl will end.");
    private final BooleanSetting healthCheck = new BooleanSetting("Health Check", false)
            .setDescription("Only follow when your health is at least the thrower's, so you don't chase an opponent who is winning the trade into a worse position.");
    private final SliderSetting maxDistance = new SliderSetting("Max Distance", 64.0, 16.0, 128.0, 1)
            .setDescription("Farthest enemy ender pearl, in blocks, that PearlChase reacts to. Pearls thrown further away are ignored.");

    /** Ticks a chase may spend turning before the pearl is dropped. */
    private static final int MAX_AIM_TICKS = 10;
    /** Largest turn per tick (degrees), under the rotation profile's own ceiling. */
    private static final float MAX_TICK_TURN = 34.0f;
    /** The counter-pearl must pass this close (blocks) to the enemy landing to be thrown... */
    private static final double THROW_TOLERANCE = 1.5;
    /** ...or this close to what the best reachable throw manages. */
    private static final double SOLVED_SLACK = 0.75;
    /** A landing no throw gets within this many blocks of is not chased. */
    private static final double MAX_LANDING_ERROR = 4.0;
    // Kept (unregistered) so configs that saved it still load; the per-module slider always applies.
    private final BooleanSetting useProfile = new BooleanSetting("Use Timing Profile", false);
    private final SliderSetting swapBackDelay = new SliderSetting("Swap Back Delay (ms)", 50, 0, 500, 0)
            .withJitter(15)
            .setDescription("Wait after the counter-pearl is thrown before your previous hotbar item is reselected. Rounded to whole ticks with at least one tick, so even 0 never swaps back in the throw's own tick.")
            .setSection("Delays");
    private final SliderSetting reactionDelay = new SliderSetting("Reaction Delay (ms)", 80, 0, 500, 0)
            .withJitter(25)
            .setDescription("Pause between an enemy pearl being seen and the first aim step toward the counter-throw, like a player's reaction. Aiming runs once per tick, so it is rounded to whole ticks (a remainder still counts on average); 0 aims on the tick the pearl is seen.")
            .setSection("Delays");

    private final Set<Integer> processedPearlIds = new HashSet<>();
    private int returnSlot = -1;
    /** The pearl slot this module selected; the swap-back only applies while it is still the held one. */
    private int ownedSlot = -1;
    private int ticksRemaining = 0;
    private boolean waitingSwapBack = false;
    private boolean aiming = false;
    private int aimTicks = 0;
    /** Ticks still to wait before the first aim step (Reaction Delay). */
    private int reactionTicks = 0;
    private int chasedPearlId = -1;
    private Vec3d landing;

    public PearlChase() {
        super("PearlChase", "When an enemy throws an ender pearl to escape, PearlChase traces where it will land, turns to the throw whose own flight ends there, swaps to your pearl and throws a counter-pearl so you land where they do. The throw waits until the look being sent actually carries the pearl there, then your previous item is reselected.", Category.COMBAT);
        addSetting(rotationMode);
        addSetting(healthCheck);
        addSetting(maxDistance);
        addSetting(swapBackDelay);
        addSetting(reactionDelay);
    }

    @Override
    public void onEnable() {
        EventBus.register(this);
        EventBus.post(new interstellarTikiTiks.apexx.client.event.impl.PearlModuleExecuteEvent("PearlChase"));
        resetState();
    }

    @Override
    public void onDisable() {
        EventBus.unregister(this);
        recoverSlot();
        resetState();
    }

    @Override
    public void onTick() {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) {
            resetState();
            return;
        }

        if (waitingSwapBack) {
            // From the tick after the throw on, the aim request is released.
            RotationManager.clearTarget(this);
            if (--ticksRemaining <= 0) {
                recoverSlot();
                waitingSwapBack = false;
                returnSlot = -1;
                ownedSlot = -1;
            }
            return;
        }

        try {
            if (aiming) {
                if (reactionTicks > 0) {
                    reactionTicks--;
                    return; // still reacting to the enemy pearl
                }
                continueThrow();
                return;
            }
            EnderPearlEntity targetPearl = findEnemyPearl();
            if (targetPearl == null) return;
            processedPearlIds.add(targetPearl.getId());

            Entity owner = targetPearl.getOwner();
            if (healthCheck.isEnabled() && owner instanceof LivingEntity living
                    && mc.player.getHealth() < living.getHealth()) {
                return;
            }
            if (findPearlSlot() < 0 || !PearlLimit.canThrow(mc)) return;

            landing = enemyLanding(targetPearl);
            if (landing == null) return;
            chasedPearlId = targetPearl.getId();
            aiming = true;
            aimTicks = 0;
            reactionTicks = ModuleDelays.msToTicks(reactionDelay.sample());
            if (reactionTicks > 0) return;
            // No reaction delay: the first aim step (and, when it already lines up, the throw) happens in this tick.
            continueThrow();
        } catch (RuntimeException error) {
            ClientInternalLog.error(getName(), "Chase tick failed", error);
            RotationManager.clearTarget(this);
            aiming = false;
        }
    }

    /** Where the enemy pearl ends, traced from the state the client has for it now. */
    private Vec3d enemyLanding(EnderPearlEntity pearl) {
        return PearlChaseSolver.landing(mc.world, pearl.getOwner(), pearl, pearl.getEntityPos(), pearl.getVelocity(),
                pearl.age);
    }

    /**
     * One tick of the chase: re-solve from this tick's thrower, step the look toward it and, once the
     * look the use packet will carry lands the counter-pearl at the enemy landing, pearl slot then throw.
     */
    private void continueThrow() {
        if (++aimTicks > MAX_AIM_TICKS) {
            drop();
            return;
        }
        int pearlSlot = findPearlSlot();
        if (pearlSlot < 0 || !PearlLimit.canThrow(mc)) {
            drop();
            return;
        }
        // While the enemy pearl still flies its landing is re-traced (the server corrects the client copy).
        Entity live = mc.world.getEntityById(chasedPearlId);
        if (live instanceof EnderPearlEntity pearl && pearl.isAlive() && !pearl.isRemoved()) {
            Vec3d refreshed = enemyLanding(pearl);
            if (refreshed != null) landing = refreshed;
        }
        Vec3d spawn = ThrowPhysics.pearlSpawn(mc.player.getEntityPos(), mc.player.getEyeY() - mc.player.getY());
        Vec3d movement = ThrowPhysics.localShooterMovement(mc.player);
        boolean onGround = mc.player.isOnGround();
        PearlChaseSolver.Shot shot = PearlChaseSolver.solve(mc.world, mc.player, spawn, movement, onGround, landing,
                RotationManager.getFinalYaw(), RotationManager.getFinalPitch());
        if (shot == null || shot.error() > MAX_LANDING_ERROR) {
            drop();
            return;
        }
        float step = Math.min(MAX_TICK_TURN, RotationManager.aimStepCeiling());
        if (!RotationManager.stepAim(this, RotationManager.Priority.HIGHEST, shot.yaw(), shot.pitch(), step, isSilent())) {
            return; // another aim holds the look this tick
        }
        float[] applied = appliedLook();
        PearlChaseSolver.Shot exact = PearlChaseSolver.evaluate(mc.world, mc.player, spawn, movement, onGround,
                applied[0], applied[1], landing);
        if (exact.error() > Math.max(THROW_TOLERANCE, shot.error() + SOLVED_SLACK)) return; // still turning
        if (mc.player.getItemCooldownManager().isCoolingDown(Items.ENDER_PEARL.getDefaultStack())
                || !interstellarTikiTiks.apexx.client.util.combat.InteractSim.useReady(mc)) {
            return;
        }
        int currentSlot = mc.player.getInventory().getSelectedSlot();
        if (currentSlot != pearlSlot) {
            if (!TickActionOrder.canSwitchSlot() || !SlotSync.select(mc, pearlSlot)) {
                return; // hotbar change not allowed yet this packet interval; retry next tick
            }
        }
        this.returnSlot = currentSlot;
        this.ownedSlot = pearlSlot;

        // Vanilla's use reads the crosshair, last computed for the previous look.
        mc.gameRenderer.updateCrosshairTarget(1.0f);
        TickActionOrder.recordUse();
        interstellarTikiTiks.apexx.client.util.ClickSimController.use(mc, true);
        // The throw's look is kept for the rest of this tick; it is released from the next one.
        RotationManager.setPostAttackHold(this, 1, applied[0], applied[1]);

        this.aiming = false;
        this.waitingSwapBack = true;
        this.ticksRemaining = Math.max(1, ModuleDelays.msToTicks(swapBackDelay.sample()));
    }

    private void drop() {
        RotationManager.clearTarget(this);
        aiming = false;
        reactionTicks = 0;
        chasedPearlId = -1;
        landing = null;
    }

    private boolean isSilent() {
        return "Silent".equalsIgnoreCase(rotationMode.getCurrentMode());
    }

    /** The look the use packet will carry. */
    private float[] appliedLook() {
        if (RotationManager.isSilentRotationActive()) {
            return new float[]{RotationManager.getFinalYaw(), RotationManager.getFinalPitch()};
        }
        return new float[]{mc.player.getYaw(), mc.player.getPitch()};
    }

    private EnderPearlEntity findEnemyPearl() {
        if (mc.world == null || mc.player == null) return null;

        double maxDistSq = maxDistance.getValue() * maxDistance.getValue();
        for (Entity entity : mc.world.getEntities()) {
            if (!(entity instanceof EnderPearlEntity pearl) || pearl.isRemoved()) {
                continue;
            }

            if (processedPearlIds.contains(pearl.getId())) {
                continue;
            }

            Entity owner = pearl.getOwner();
            if (owner != null && owner.getUuid().equals(mc.player.getUuid())) {
                continue; // Local player pearl
            }
            if (owner != null && !interstellarTikiTiks.apexx.client.util.combat.StickyTargets.allows(this, owner)) {
                continue; // Sticky Target: another player is locked
            }
            if (owner != null && FriendManager.getInstance().isFriend(owner.getUuid())) {
                continue; // A friend's pearl is not a target
            }
            if (owner != null && isBot(owner)) {
                continue; // A bot's pearl is not chased
            }

            if (mc.player.squaredDistanceTo(pearl) > maxDistSq) {
                continue;
            }

            return pearl;
        }

        return null;
    }

    private static boolean isBot(Entity owner) {
        for (BotFilterProvider botFilter : ModuleAccess.getEnabledHooks(BotFilterProvider.class)) {
            if (botFilter.isBot(owner)) return true;
        }
        return false;
    }

    private int findPearlSlot() {
        if (mc.player == null) return -1;
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).isOf(Items.ENDER_PEARL)) {
                return i;
            }
        }
        return -1;
    }

    private void recoverSlot() {
        if (returnSlot >= 0 && returnSlot < 9 && mc.player != null && ownedSlot != returnSlot
                && mc.player.getInventory().getSelectedSlot() == ownedSlot) {
            SlotSync.restore(mc, returnSlot);
        }
    }

    @Override
    protected void onRuntimeStateReleased() {
        recoverSlot();
        resetState();
    }

    private void resetState() {
        RotationManager.clearTarget(this);
        this.processedPearlIds.clear();
        this.returnSlot = -1;
        this.ownedSlot = -1;
        this.ticksRemaining = 0;
        this.waitingSwapBack = false;
        this.aiming = false;
        this.aimTicks = 0;
        this.reactionTicks = 0;
        this.chasedPearlId = -1;
        this.landing = null;
    }
}
