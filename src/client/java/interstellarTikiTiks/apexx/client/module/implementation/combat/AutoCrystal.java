package interstellarTikiTiks.apexx.client.module.implementation.combat;

import interstellarTikiTiks.apexx.client.event.EventBus;
import interstellarTikiTiks.apexx.client.event.EventTarget;
import interstellarTikiTiks.apexx.client.event.impl.CombatSwapLockEvent;
import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.ModeSetting;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import interstellarTikiTiks.apexx.client.module.SubCategory;
import interstellarTikiTiks.apexx.client.module.hooks.AttackHandleAware;
import interstellarTikiTiks.apexx.client.module.hooks.ItemUseBlockProvider;
import interstellarTikiTiks.apexx.client.util.AttackValidator;
import interstellarTikiTiks.apexx.client.util.RotationManager;
import interstellarTikiTiks.apexx.client.util.combat.BlockPlacementValidator;
import interstellarTikiTiks.apexx.client.util.combat.CrystalHeadbob;
import interstellarTikiTiks.apexx.client.util.combat.CrystalPlacement;
import interstellarTikiTiks.apexx.client.util.combat.InteractSim;
import interstellarTikiTiks.apexx.client.util.combat.ModuleDelays;
import interstellarTikiTiks.apexx.client.util.combat.ObsidianIntent;
import interstellarTikiTiks.apexx.client.util.combat.ServerAim;
import interstellarTikiTiks.apexx.client.util.combat.SmoothAim;
import interstellarTikiTiks.apexx.client.util.combat.SwapAction;
import interstellarTikiTiks.apexx.client.util.combat.TickActionOrder;
import net.minecraft.block.Blocks;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import interstellarTikiTiks.apexx.client.util.RotationMath;
import net.minecraft.world.GameMode;

/**
 * AutoCrystal - while right click is held, places crystals on the obsidian (or bedrock) under the
 * crosshair and breaks the crystals under it (ported from Mochi's Auto Crystal).
 *
 * <p><b>Headbob.</b> When placing on any block face that is NOT the top face (side or bottom), the look
 * rises along a diagonal toward the Hand side, over the block edge, to break the spawned crystal, and
 * returns down an arc to place the next one. Head Movement sets how far into the crystal's window the
 * rise goes (low turns as little as the crystal allows), Height Variation varies that depth from bob to
 * bob. The break fires on the tick the live ray of the look reaches the crystal; the next crystal goes
 * out on the tick the live ray reaches the base on the way down.</p>
 *
 * <p><b>Letting go.</b> If use is released while the look is away from where the bob started, the
 * module does nothing else first: it breaks the last crystal (turning up to it if need be), guides the
 * look back to the starting pose, and only then ends. Nothing new is placed during that return. With
 * Silent Aim on only the server-side look bobs and the return ends on your own camera.</p>
 *
 * <p>Obsidian Activation: also activates when holding right click with obsidian in hand targeted
 * against obsidian (or bedrock), swapping to crystal in hotbar, placing, and breaking. The block aimed
 * at is read from the live ray every tick, wherever on it the look rests.</p>
 *
 * <p>Obsidian after an anchor combo: if the player pressed an obsidian slot while an anchor combo was
 * running, the combo finishes first (pearl or not). Then this module selects that slot, places the
 * obsidian on the block under the look in the same tick, and carries on with the crystals on it, with
 * or without right click held, until the first crystal breaks.</p>
 */
public final class AutoCrystal extends Module implements AttackHandleAware, ItemUseBlockProvider, interstellarTikiTiks.apexx.client.module.hooks.ClickSimulating {

    public enum BobState {
        IDLE,
        TILT_UP,
        TILT_DOWN
    }

    /** The look counts as back at the starting pose within this many degrees. */
    private static final double REST_TOLERANCE = 0.6;
    /** The return gives up (and releases the look) this long after use was let go. */
    private static final long RETURN_LIMIT_MS = 1800L;
    /** The bob is dropped when the user's view is further than this (plus the crystal's own size) from the base. */
    private static final double LOOK_AWAY_DEG = 40.0;
    /** A bob that has neither placed nor broken anything for this long (plus the echo wait) is stalled. */
    private static final long STALL_MS = 350L;

    private final BooleanSetting autoSwap = new BooleanSetting("Auto Swap", false)
            .setDescription("Switches to your crystals by itself whenever your crosshair is on obsidian or bedrock while you hold use. Off: only works while you already hold crystals.");
    private final BooleanSetting headbob = new BooleanSetting("Headbob", true)
            .setSection("Headbob")
            .setDescription("When placing on a face other than the top of a block, rises over the edge to break the crystal and comes back down to place the next one. Letting go of use always breaks the last crystal and returns the look first.");
    private final ModeSetting hand = new ModeSetting("Hand", CrystalHeadbob.RIGHTY, CrystalHeadbob.LEFTY);
    private final SliderSetting headMovement = new SliderSetting("Head Movement", 15.0, 0.0, 100.0, 0);
    private final BooleanSetting heightVariation = new BooleanSetting("Height Variation", true);
    private final BooleanSetting silentAim = new BooleanSetting("Silent Aim", false)
            .setSection("Headbob")
            .setDescription("Keeps the Headbob turn off your camera and sends it to the server only. Off by default.");
    private final SliderSetting aimSpeed = new SliderSetting("Aim Speed", 28.0, 10.0, 72.0, 0)
            .setSection("Headbob")
            .setDescription("Largest turn, in degrees a tick, the Headbob look makes; the camera turns every render frame.");
    private final SliderSetting bobBreakDelay = new SliderSetting("Bob Break Delay (ms)", 20.0, 0.0, 300.0, 0)
            .withJitter(12.0).setSection("Delays")
            .setDescription("Headbob: reaction time between the live ray reaching the crystal and the hit. 0 hits on the tick the ray gets there.");
    private int bobDelayCrystal = -1;
    private long bobDelayStart;
    private long bobDelayNeed;
    private final SliderSetting crystalSpeed = new SliderSetting("Crystal Speed", 20.0, 2.0, 20.0, 0)
            .setDescription("Most crystal actions (a place or a break) per second. 20 is one per tick, the fastest vanilla allows; lower spaces them whole ticks apart for a calmer rhythm. Place Delay and Break Delay add on top.");
    private final SliderSetting placeDelay = new SliderSetting("Place Delay (ms)", 25.0, 0.0, 300.0, 0)
            .withJitter(12.0).setSection("Delays")
            .setDescription("Reaction time between the base being free (no crystal on it) and the next crystal going on it. Rounded to whole ticks on average; 0 places on the first tick it can.");
    private final SliderSetting breakDelay = new SliderSetting("Break Delay (ms)", 20.0, 0.0, 300.0, 0)
            .withJitter(12.0).setSection("Delays")
            .setDescription("Reaction time between the look reaching a crystal and the hit (Headbob breaks use Bob Break Delay). Rounded to whole ticks on average; 0 hits on the tick the look gets there.");
    private static final int NO_AGE = Integer.MIN_VALUE;
    private int placeArmedAge = NO_AGE;
    private BlockPos placeArmedBase;
    private int placeDelayTicks;
    private int breakDelayCrystal = -1;
    private int breakArmedAge = NO_AGE;
    private int breakDelayTicks;
    private final BooleanSetting switchBack = new BooleanSetting("Swap Back", false)
            .setDescription("When you let go of use, puts you back on the slot you were holding before, unless you switched slots yourself.");

    private final SwapAction swap = new SwapAction(this);
    private final CrystalHeadbob bob = new CrystalHeadbob();
    private Object boundPlayer;
    private Object boundWorld;
    /** The client tick (player age) of the last placement and of the last break: one of each per tick. */
    private int placeTimerTick;
    private int breakTimerTick;
    /** One obsidian has already been let through for the current held use. */
    private boolean obsidianPlaced;
    /** The crystals-on-the-new-obsidian follow-up after an anchor combo: when it began, and its end. */
    private long followSince;
    private long followUntil;
    /** How long the follow-up waits for its first crystal to be placed and broken. */
    private static final long FOLLOW_MS = 2000L;

    // Headbob state
    private BobState bobState = BobState.IDLE;
    private BlockPos bobBase;
    /** Use was let go mid-bob: break the last crystal, return to the start pose, place nothing. */
    private boolean returning;
    private long returningSince;
    /** Highest the look has risen above the start pose in this bob, degrees of pitch. */
    private double peakUp;
    private int lastBrokenId = -1;
    private long lastBrokenAt;
    private long lastPlacedAt;
    private int bobUnanswered;
    /** When the bob last made progress (started, placed or broke); the stall watchdog reads it. */
    private long bobProgressAt;

    public AutoCrystal() {
        super("AutoCrystal", "While you hold use on obsidian or bedrock, places an end crystal and breaks it the moment it appears, over and over at up to vanilla's one action per tick. Headbob rises over block edges to reach crystals you placed on a side face.", Category.COMBAT);
        setSubCategory(SubCategory.CRYSTAL);
        hand.setSection("Headbob").setDescription("Which side the rise leans toward. Righty rises up and to the right (a line from left-bottom to right-top), Lefty the mirror.");
        headMovement.setSection("Headbob").setDescription("How far the look goes into the crystal on each rise. Low turns only as far as the crystal needs, so the head bobs as little as it can; high goes deeper.");
        heightVariation.setSection("Headbob").setDescription("Varies the depth of each rise a little from bob to bob (skewed, never a fixed range). Off keeps every rise the same.");
        addSetting(autoSwap);
        addSetting(headbob);
        addSetting(hand);
        hand.setVisibility(headbob::isEnabled);
        addSetting(headMovement);
        headMovement.setVisibility(headbob::isEnabled);
        addSetting(heightVariation);
        heightVariation.setVisibility(headbob::isEnabled);
        addSetting(silentAim);
        silentAim.setVisibility(headbob::isEnabled);
        addSetting(aimSpeed);
        addSetting(bobBreakDelay);
        bobBreakDelay.setVisibility(headbob::isEnabled);
        aimSpeed.setVisibility(headbob::isEnabled);
        addSetting(crystalSpeed);
        addSetting(placeDelay);
        addSetting(breakDelay);
        addSetting(switchBack);
    }

    @Override
    public void onEnable() {
        EventBus.register(this);
        resetState(true);
    }

    @Override
    public void onDisable() {
        finish();
    }

    @Override
    protected void onRuntimeStateReleased() {
        finish();
        resetState(false);
    }

    @EventTarget
    public void onCombatSwapLock(CombatSwapLockEvent event) {
        if (event.isRequestedBy(this)) return;
        if (swap.holdsSlot() || swap.queuedThisTick() || bobState != BobState.IDLE) {
            event.setItemSwapLocked(true);
        }
    }

    // ------------------------------------------------------------------ tick

    @Override
    public void onTick() {
        if (!canRun()) {
            finish();
            return;
        }
        if (mc.player != boundPlayer || mc.world != boundWorld) resetState(true);
        bob.configure(hand.getCurrentMode(), headMovement.getValue(), heightVariation.isEnabled());
        if (swap.takeUserSlotChange()) {
            // The player took the hotbar: drop the slot ownership and leave the slot as chosen, so the
            // swap is kept. A bob in flight is not dropped with the look up: it returns first.
            swap.reset(true);
            if (bobState != BobState.IDLE) beginReturn();
        }
        if (swap.queuedThisTick()) return;

        boolean pressed = rightClickHeld();
        if (!pressed) obsidianPlaced = false;
        if (bobState == BobState.IDLE && stepObsidianFollowUp()) return;
        // The follow-up drives the crystals like a held right click until the first one breaks.
        boolean held = pressed || followActive();
        if (bobState != BobState.IDLE) {
            if (!headbob.isEnabled()) {
                // Headbob was switched off mid-bob: no further rise, only the way back down.
                beginReturn();
                if (System.currentTimeMillis() - returningSince > RETURN_LIMIT_MS || bob.atRest(REST_TOLERANCE)) {
                    finishBob();
                } else {
                    guideHome();
                }
                return;
            }
            if (held && returning && bobBaseStillBase()) {
                // Grabbed again before the return finished: the normal cycle simply resumes.
                returning = false;
            } else if (!held && !returning) {
                beginReturn();
            }
            if (lookedAwayFromBob() || bobStalled()) {
                // The look is not on the work any more (or the crystal never answered): drop the bob and
                // let the normal place/break path decide from the crosshair, instead of waiting on it.
                finishBob();
                return;
            }
            stepHeadbobCycle();
            return;
        }
        if (held && stepAimAssist()) return;
        if (!held || !activationAllowed()) {
            finish();
            return;
        }

        if (!handlePlaceCrystal()) {
            handleBreakCrystal();
        }
    }

    /** Vanilla left clicks do not start mining the obsidian the crystals sit on while use is held. */
    @Override
    public boolean handleAttack() {
        if (mc.player == null || mc.world == null) return false;
        if (bobState != BobState.IDLE) return true;
        if (!rightClickHeld()) return false;
        return isBaseHit(aimedHit()) || isObsidianAgainstObsidian();
    }

    /**
     * Lets exactly one obsidian go down per held use, then bars the rest (even when no crystal can be
     * placed), so held right click never stacks obsidian.
     */
    @Override
    public boolean shouldBlockItemUse() {
        if (mc.player == null || mc.world == null || mc.currentScreen != null) return false;
        if (!rightClickHeld()) {
            obsidianPlaced = false;
            return false;
        }
        if (bobState != BobState.IDLE) return true;
        if (!mc.player.getMainHandStack().isOf(Items.OBSIDIAN)) return false;
        if (obsidianPlaced) return true;
        HitResult aimed = aimedHit();
        if (aimed != null && aimed.getType() == HitResult.Type.BLOCK) {
            obsidianPlaced = true;
        }
        return false;
    }

    // ------------------------------------------------------------------ steps

    /** Places a crystal on the base under the crosshair. True when the crosshair is on a base. */
    private boolean handlePlaceCrystal() {
        if (!(aimedHit() instanceof BlockHitResult blockHit) || !isBaseHit(blockHit)) return false;
        BlockPos base = blockHit.getBlockPos();
        // A crystal already stands there (or something is in its way): only breaking it is useful.
        if (!CrystalPlacement.crystalFits(mc.world, base)) {
            if (headbob.isEnabled() && blockHit.getSide() != Direction.UP) {
                EndCrystalEntity crystal = CrystalPlacement.crystalOn(mc.world, base);
                if (crystal != null && crystal.isAlive()) {
                    startHeadbob(base);
                    stepHeadbobTiltUp();
                    return true;
                }
            }
            return false;
        }
        boolean holding = mc.player.getMainHandStack().isOf(Items.END_CRYSTAL);
        int slot = holding ? -1 : CrystalPlacement.hotbarSlot(mc.player, Items.END_CRYSTAL);
        if (!holding && slot < 0) return true;
        if (!placeDelayElapsed(base) || !placeReady()) return true;
        swap.act(slot, () -> {
            BlockHitResult live = BlockPlacementValidator.rayFromLook(mc, silentAim.isEnabled());
            if (live == null || !live.getBlockPos().equals(base) || !CrystalPlacement.isBase(mc.world, base)
                    || !CrystalPlacement.crystalFits(mc.world, base) || mc.player.isUsingItem()
                    || !mc.player.getMainHandStack().isOf(Items.END_CRYSTAL)) {
                return;
            }
            if (InteractSim.interactBlockOncePerTick(mc, live, silentAim.isEnabled())) {
                placeTimerTick = mc.player.age;
                placeArmedAge = NO_AGE;
                lastPlacedAt = System.currentTimeMillis();
                bobProgressAt = lastPlacedAt;
                // Headbob only when the face looked at is NOT the top of the block.
                if (headbob.isEnabled() && live.getSide() != Direction.UP) {
                    startHeadbob(base);
                    stepHeadbobTiltUp();
                }
            }
        });
        return true;
    }

    /** Breaks the crystal under the crosshair. */
    private void handleBreakCrystal() {
        EndCrystalEntity crystal = aimedCrystal();
        if (crystal == null) return;
        if (!breakDelayElapsed(crystal) || !breakReady()) return;
        int slot = CrystalPlacement.breakingSlot(mc.player);
        if (slot == -2) return;
        swap.act(slot, () -> {
            if (!crystal.isAlive() || aimedCrystal() != crystal) {
                return;
            }
            if (AttackValidator.dispatchEntityAttack(mc, crystal, false, true)) {
                TickActionOrder.recordAttack();
                breakTimerTick = mc.player.age;
                lastBrokenId = crystal.getId();
                lastBrokenAt = System.currentTimeMillis();
            }
        });
    }

    // ------------------------------------------------------------------ headbob

    private void startHeadbob(BlockPos base) {
        bobBase = base.toImmutable();
        bob.begin(silentAim.isEnabled());
        bobState = BobState.TILT_UP;
        returning = false;
        peakUp = 0.0;
        bobUnanswered = 0;
        bobProgressAt = System.currentTimeMillis();
    }

    /** True when the user's own view points well away from the base the bob works on. */
    private boolean lookedAwayFromBob() {
        if (bobBase == null || mc.player == null) return false;
        Vec3d eye = mc.player.getEyePos();
        Vec3d target = new Vec3d(bobBase.getX() + 0.5, bobBase.getY() + 1.0, bobBase.getZ() + 0.5);
        Vec3d to = target.subtract(eye);
        double dist = to.length();
        if (dist < 1.0e-3) return false;
        Vec3d look = RotationMath.direction(RotationManager.getVisualYaw(), RotationManager.getVisualPitch());
        double angle = Math.toDegrees(Math.acos(MathHelper.clamp(look.dotProduct(to) / dist, -1.0, 1.0)));
        // A crystal is two blocks wide: up close it spans a wide angle, so the allowance grows with it.
        double allowance = LOOK_AWAY_DEG + Math.toDegrees(Math.atan2(1.0, dist));
        return angle > allowance;
    }

    /** No place or break went out for too long while the bob is up (not on the way home). */
    private boolean bobStalled() {
        if (returning) return false;
        return System.currentTimeMillis() - bobProgressAt > STALL_MS + CrystalPlacement.echoWaitMs(mc);
    }

    /** Use was let go (or the hotbar taken) with the look away from rest: finish the job, then go home. */
    private void beginReturn() {
        if (returning) return;
        returning = true;
        returningSince = System.currentTimeMillis();
        bobUnanswered = 0;
    }

    private boolean bobBaseStillBase() {
        return bobBase != null && !outOfBlockReach(bobBase) && CrystalPlacement.isBase(mc.world, bobBase);
    }

    private void stepHeadbobCycle() {
        if (returning && System.currentTimeMillis() - returningSince > RETURN_LIMIT_MS) {
            // Last resort: the look is released where it is rather than held forever.
            finishBob();
            return;
        }
        if (!bobBaseStillBase()) {
            // Nothing left to break or place: only the way home remains.
            beginReturn();
            guideHome();
            return;
        }
        notePeak();
        if (bobState == BobState.TILT_UP) {
            stepHeadbobTiltUp();
        } else if (bobState == BobState.TILT_DOWN) {
            stepHeadbobTiltDown();
        }
    }

    private void notePeak() {
        peakUp = Math.max(peakUp, bob.risen());
    }

    private void stepHeadbobTiltUp() {
        if (bobBase == null) return;
        EndCrystalEntity crystal = CrystalPlacement.crystalOn(mc.world, bobBase);
        double reach = mc.player.getEntityInteractionRange();

        if (crystal != null && crystal.isAlive()) {
            bobUnanswered = 0;

            if (crystal.getId() == lastBrokenId
                    && System.currentTimeMillis() - lastBrokenAt < CrystalPlacement.echoWaitMs(mc)) {
                return;
            }

            if (rayHits(crystal, reach)) {
                if (breakReady() && bobBreakDelayElapsed(crystal)) {
                    attackCrystal(crystal);
                    bobState = BobState.TILT_DOWN;
                }
                return;
            }

            float[] goal = bob.upGoal(crystal, null, reach);
            if (goal == null) {
                // Out of reach from here: go home rather than sit with the look up.
                if (bob.atRest(REST_TOLERANCE)) finishBob();
                else guideHome();
                return;
            }

            // The camera turns toward the crystal every render frame; the break below needs the live ray.
            if (!CrystalHeadbob.aim(this, goal[0], goal[1], aimDegPerSec(), silentAim.isEnabled())) {
                // Another module holds the look: do not sit on a crystal waiting for it.
                finishBob();
                return;
            }

            if (rayHits(crystal, reach) && breakReady() && bobBreakDelayElapsed(crystal)) {
                attackCrystal(crystal);
                bobState = BobState.TILT_DOWN;
            }
        } else {
            if (++bobUnanswered > unansweredLimit()) {
                // No crystal came: head home, and (unless returning) place again from the base.
                bobState = BobState.TILT_DOWN;
                return;
            }

            // Pre-aim over the edge so the break is on the tick the crystal shows up.
            Box area = new Box(bobBase.getX() - 0.5, bobBase.getY() + 1.0, bobBase.getZ() - 0.5,
                    bobBase.getX() + 1.5, bobBase.getY() + 3.0, bobBase.getZ() + 1.5);
            float[] goal = bob.upGoal(null, area, reach);
            if (goal != null) {
                CrystalHeadbob.aim(this, goal[0], goal[1], aimDegPerSec(), silentAim.isEnabled());
            }
        }
    }

    /** Bob Break Delay: a sampled reaction time after the live ray first reaches this crystal. */
    private boolean bobBreakDelayElapsed(EndCrystalEntity crystal) {
        long now = System.currentTimeMillis();
        if (crystal.getId() != bobDelayCrystal) {
            bobDelayCrystal = crystal.getId();
            bobDelayStart = now;
            bobDelayNeed = ModuleDelays.clampMs(bobBreakDelay.sample());
        }
        return now - bobDelayStart >= bobDelayNeed;
    }

    /** Break Delay: counted from the first tick the look is on this crystal, in probabilistically rounded ticks. */
    private boolean breakDelayElapsed(EndCrystalEntity crystal) {
        int age = mc.player.age;
        if (crystal.getId() != breakDelayCrystal || breakArmedAge == NO_AGE || age < breakArmedAge) {
            breakDelayCrystal = crystal.getId();
            breakArmedAge = age;
            breakDelayTicks = ModuleDelays.msToTicks(breakDelay.sample());
        }
        return age - breakArmedAge >= breakDelayTicks;
    }

    /** Place Delay: counted from the first tick this base is seen free, in probabilistically rounded ticks. */
    private boolean placeDelayElapsed(BlockPos base) {
        int age = mc.player.age;
        if (placeArmedAge == NO_AGE || age < placeArmedAge || !base.equals(placeArmedBase)) {
            placeArmedAge = age;
            placeArmedBase = base.toImmutable();
            placeDelayTicks = ModuleDelays.msToTicks(placeDelay.sample());
        }
        return age - placeArmedAge >= placeDelayTicks;
    }

    private void stepHeadbobTiltDown() {
        if (bobBase == null) return;

        EndCrystalEntity crystal = CrystalPlacement.crystalOn(mc.world, bobBase);
        if (crystal != null && crystal.isAlive()) {
            if (System.currentTimeMillis() - lastBrokenAt < CrystalPlacement.echoWaitMs(mc)) {
                return;
            }
            bobState = BobState.TILT_UP;
            stepHeadbobTiltUp();
            return;
        }

        // A crystal placed a moment ago may not have shown yet: letting go waits for it, then breaks it.
        if (returning && System.currentTimeMillis() - lastPlacedAt < CrystalPlacement.spawnWaitMs(mc)
                && bobUnanswered <= unansweredLimit()) {
            bobState = BobState.TILT_UP;
            stepHeadbobTiltUp();
            return;
        }

        // Fast overlap placement: the live ray already reaches the base on the way down, so place now.
        if (!returning && tryPlaceFromLook()) return;

        if (bob.atRest(REST_TOLERANCE)) {
            finishBob();
            return;
        }
        guideHome();
    }

    /** Aims at the next point of the way back to the starting pose. */
    private void guideHome() {
        float[] goal = bob.downGoal(peakUp);
        CrystalHeadbob.aim(this, goal[0], goal[1], aimDegPerSec(), silentAim.isEnabled());
    }

    /** Places on the base if the live ray of the current look is on it. True when a place went out. */
    private boolean tryPlaceFromLook() {
        if (!CrystalPlacement.crystalFits(mc.world, bobBase)) return false;
        BlockHitResult live = BlockPlacementValidator.rayFromLook(mc, silentAim.isEnabled());
        if (live == null || !live.getBlockPos().equals(bobBase) || !CrystalPlacement.isBase(mc.world, bobBase)) {
            return false;
        }
        if (!placeDelayElapsed(bobBase) || !placeReady() || !tryPlaceOnBase(bobBase)) return false;
        bobState = BobState.TILT_UP;
        bobUnanswered = 0;
        bob.nextCycle();
        return true;
    }

    private boolean tryPlaceOnBase(BlockPos base) {
        boolean holding = mc.player.getMainHandStack().isOf(Items.END_CRYSTAL);
        int slot = holding ? -1 : CrystalPlacement.hotbarSlot(mc.player, Items.END_CRYSTAL);
        if (!holding && slot < 0) return false;

        return swap.act(slot, () -> {
            BlockHitResult live = BlockPlacementValidator.rayFromLook(mc, silentAim.isEnabled());
            if (live == null || !live.getBlockPos().equals(base) || !CrystalPlacement.isBase(mc.world, base)
                    || !CrystalPlacement.crystalFits(mc.world, base) || mc.player.isUsingItem()
                    || !mc.player.getMainHandStack().isOf(Items.END_CRYSTAL)) {
                return;
            }
            if (InteractSim.interactBlockOncePerTick(mc, live, silentAim.isEnabled())) {
                placeTimerTick = mc.player.age;
                placeArmedAge = NO_AGE;
                lastPlacedAt = System.currentTimeMillis();
                bobProgressAt = lastPlacedAt;
            }
        });
    }

    private void attackCrystal(EndCrystalEntity crystal) {
        int slot = CrystalPlacement.breakingSlot(mc.player);
        if (slot == -2) {
            // Nothing in the hotbar breaks a crystal (Weakness): the look still has to come home.
            bobState = BobState.TILT_DOWN;
            beginReturn();
            return;
        }
        double reach = mc.player.getEntityInteractionRange();
        swap.act(slot, () -> {
            if (!crystal.isAlive() || !rayHits(crystal, reach)) return;
            if (!AttackValidator.dispatchEntityAttack(mc, crystal, false, true)) return;
            TickActionOrder.recordAttack();
            breakTimerTick = mc.player.age;
            lastBrokenId = crystal.getId();
            lastBrokenAt = System.currentTimeMillis();
            bobProgressAt = lastBrokenAt;
            // The look reached this tick keeps for the rest of it; the way down starts next tick.
            RotationManager.setPostAttackHold(this, 1, RotationManager.getFinalYaw(), RotationManager.getFinalPitch());
        });
    }

    /**
     * The live crystal the look is on, read from the ray of the rotation that goes on the wire (the one the
     * server validates the hit against) rather than the per-frame crosshair, which can lag behind it or follow
     * the camera instead. This is what lets a crystal on a side-face base break with Headbob off as soon as
     * the look gets onto it, with no rise.
     */
    private EndCrystalEntity aimedCrystal() {
        if (mc.player == null) return null;
        HitResult live = ServerAim.getCrosshairTarget(RotationManager.getFinalYaw(), RotationManager.getFinalPitch(),
                mc.player.getEntityInteractionRange());
        if (live instanceof EntityHitResult hit && hit.getEntity() instanceof EndCrystalEntity crystal
                && !crystal.isRemoved() && crystal.isAlive()) {
            return crystal;
        }
        return null;
    }

    private boolean rayHits(EndCrystalEntity crystal, double reach) {
        if (mc.player == null) return false;
        HitResult live = ServerAim.getCrosshairTarget(RotationManager.getFinalYaw(), RotationManager.getFinalPitch(), reach);
        return live instanceof EntityHitResult hit && hit.getEntity() == crystal;
    }

    /** Ticks without a crystal before the bob gives up waiting: the spawn wait plus a margin. */
    private int unansweredLimit() {
        return Math.max(5, (int) (CrystalPlacement.spawnWaitMs(mc) / 50L) + 2);
    }

    private boolean outOfBlockReach(BlockPos pos) {
        if (mc.player == null) return true;
        double reach = mc.player.getBlockInteractionRange();
        return new Box(pos).squaredMagnitude(mc.player.getEyePos()) > reach * reach;
    }

    /** Look speed in degrees a second: Aim Speed is degrees a tick; the controller caps it. */
    private double aimDegPerSec() {
        return aimSpeed.getValue() * 20.0;
    }

    /** Crystal Speed: a place or a break goes out whole ticks after the last action of either kind. */
    private boolean speedReady() {
        int gap = Math.max(1, (int) Math.round(20.0 / Math.max(1.0, crystalSpeed.getValue())));
        return mc.player.age - Math.max(placeTimerTick, breakTimerTick) >= gap;
    }

    private void finish() {
        finishBob();
        if (switchBack.isEnabled()) swap.restoreNow();
        else swap.reset(false);
    }

    private void finishBob() {
        releaseBob();
    }

    private void releaseBob() {
        releaseAssist();
        if (bobState != BobState.IDLE || bob.isActive()) {
            bobState = BobState.IDLE;
            bobBase = null;
            bobUnanswered = 0;
            returning = false;
            peakUp = 0.0;
            bob.end();
            RotationManager.clearTarget(this);
        }
    }

    // ------------------------------------------------------------------ activation

    private boolean rightClickHeld() {
        return mc.options.useKey.isPressed();
    }

    private boolean activationAllowed() {
        if (bobState != BobState.IDLE) return true;
        return isValidActivationItem(mc.player.getMainHandStack()) || autoSwapAllows() || isObsidianAgainstObsidian()
                || (followActive() && isBaseHit(aimedHit()));
    }

    /**
     * What the look rests on right now: a crystal under the crosshair stays the target, otherwise the
     * block the live ray reaches this tick. The crosshair target is only refreshed per render frame, so
     * right after an obsidian goes down it can still name the block behind it.
     */
    private HitResult aimedHit() {
        HitResult crosshair = mc.crosshairTarget;
        if (crosshair instanceof EntityHitResult) return crosshair;
        BlockHitResult live = BlockPlacementValidator.rayFromLook(mc, silentAim.isEnabled());
        return live != null ? live : crosshair;
    }

    // ------------------------------------------------------------------ obsidian after an anchor combo

    /** Obsidian or bedrock whose face lies this close to the look is pulled onto the crosshair. */
    private static final double ASSIST_DEG = 15.0;
    private static final int ASSIST_RADIUS = 5;
    private boolean assisting;
    private Vec3d assistPoint;

    /**
     * Right click held with the look on a face edge or just above a base (so the crosshair is on nothing
     * useful): turns the look onto the nearest base face in reach, then the normal place/break path runs
     * on the live ray. True while the turn is under way.
     */
    private boolean stepAimAssist() {
        boolean itemOk = mc.player.getMainHandStack().isOf(Items.OBSIDIAN)
                ? CrystalPlacement.hotbarSlot(mc.player, Items.END_CRYSTAL) >= 0
                : (isValidActivationItem(mc.player.getMainHandStack()) || autoSwap.isEnabled());
        HitResult aimed = aimedHit();
        if (!itemOk || aimed instanceof EntityHitResult || isBaseHit(aimed)) {
            releaseAssist();
            return false;
        }
        Vec3d point = nearestBasePoint();
        if (point == null) {
            releaseAssist();
            return false;
        }
        assistPoint = point;
        assisting = SmoothAim.aimPoint(this, () -> assistPoint, aimDegPerSec(), false);
        return assisting;
    }

    private void releaseAssist() {
        if (!assisting) return;
        assisting = false;
        assistPoint = null;
        if (bobState == BobState.IDLE) RotationManager.clearTarget(this);
    }

    private Vec3d nearestBasePoint() {
        Vec3d eye = mc.player.getEyePos();
        Vec3d look = RotationMath.direction(RotationManager.getFinalYaw(), RotationManager.getFinalPitch());
        double reach = mc.player.getBlockInteractionRange();
        BlockPos origin = mc.player.getBlockPos();
        Vec3d best = null;
        double bestAngle = ASSIST_DEG;
        for (BlockPos pos : BlockPos.iterate(origin.add(-ASSIST_RADIUS, -ASSIST_RADIUS, -ASSIST_RADIUS),
                origin.add(ASSIST_RADIUS, ASSIST_RADIUS, ASSIST_RADIUS))) {
            if (!CrystalPlacement.isBase(mc.world, pos)) continue;
            if (!CrystalPlacement.crystalFits(mc.world, pos) && CrystalPlacement.crystalOn(mc.world, pos) == null) continue;
            Vec3d point = SmoothAim.nearestFacePoint(mc, pos.toImmutable(), null);
            if (point == null || eye.squaredDistanceTo(point) > reach * reach) continue;
            Vec3d to = point.subtract(eye);
            double len = to.length();
            if (len < 1.0e-3) continue;
            double angle = Math.toDegrees(Math.acos(MathHelper.clamp(look.dotProduct(to) / len, -1.0, 1.0)));
            if (angle < bestAngle) {
                bestAngle = angle;
                best = point;
            }
        }
        return best;
    }

    private boolean followActive() {
        return followSince > 0L && System.currentTimeMillis() < followUntil && lastBrokenAt < followSince;
    }

    private void startFollow() {
        followSince = System.currentTimeMillis();
        followUntil = followSince + FOLLOW_MS;
    }

    /**
     * The obsidian slot the player pressed during an anchor combo: once nothing else holds the hotbar,
     * selects it and places the obsidian on the block under the look in that same tick (the swap first),
     * then the crystal path takes over on it. True while it is still waiting for its turn or acting, so
     * the normal path stays out of the way for that tick.
     */
    private boolean stepObsidianFollowUp() {
        int slot = ObsidianIntent.slot();
        if (slot < 0) return false;
        if (!mc.player.getInventory().getStack(slot).isOf(Items.OBSIDIAN)
                || CrystalPlacement.hotbarSlot(mc.player, Items.END_CRYSTAL) < 0) {
            ObsidianIntent.clear();
            return false;
        }
        if (swapHeldByOthers()) return false;
        if (obsidianPlaced && rightClickHeld()) {
            // Held right click already let this use's obsidian through: crystals follow on it.
            ObsidianIntent.clear();
            startFollow();
            return false;
        }
        HitResult aimed = aimedHit();
        if (!(aimed instanceof BlockHitResult target) || target.getType() != HitResult.Type.BLOCK) return false;
        BlockPos support = target.getBlockPos();
        Direction face = target.getSide();
        swap.act(slot, () -> {
            BlockHitResult live = BlockPlacementValidator.rayFromLook(mc, silentAim.isEnabled());
            if (live == null || !live.getBlockPos().equals(support) || live.getSide() != face
                    || mc.player.isUsingItem() || !mc.player.getMainHandStack().isOf(Items.OBSIDIAN)) {
                return;
            }
            if (InteractSim.interactBlockOncePerTick(mc, live, silentAim.isEnabled())) {
                obsidianPlaced = true;
                placeTimerTick = mc.player.age;
                ObsidianIntent.clear();
                startFollow();
            }
        });
        return true;
    }

    /** Another module (an anchor combo) holds the hotbar right now. */
    private boolean swapHeldByOthers() {
        CombatSwapLockEvent lock = new CombatSwapLockEvent(this);
        EventBus.post(lock);
        return lock.isItemSwapLocked();
    }

    private boolean isObsidianAgainstObsidian() {
        if (mc.player == null || mc.world == null) return false;
        ItemStack stack = mc.player.getMainHandStack();
        boolean holdingObsidian = stack.isOf(Items.OBSIDIAN)
                || (swap.holdsSlot() && swap.originalSlot() >= 0
                    && mc.player.getInventory().getStack(swap.originalSlot()).isOf(Items.OBSIDIAN));
        if (!holdingObsidian) return false;
        if (CrystalPlacement.hotbarSlot(mc.player, Items.END_CRYSTAL) < 0
                && !stack.isOf(Items.END_CRYSTAL)) {
            return false;
        }
        HitResult aimed = aimedHit();
        if (aimed instanceof BlockHitResult blockHit) {
            return blockHit.getType() == HitResult.Type.BLOCK
                    && CrystalPlacement.isBase(mc.world, blockHit.getBlockPos());
        }
        if (aimed instanceof EntityHitResult entityHit
                && entityHit.getEntity() instanceof EndCrystalEntity crystal) {
            BlockPos under = crystal.getBlockPos().down();
            return mc.world.getBlockState(under).isOf(Blocks.OBSIDIAN);
        }
        return false;
    }

    private boolean autoSwapAllows() {
        if (!autoSwap.isEnabled() || !isBaseHit(aimedHit())) return false;
        return CrystalPlacement.hotbarSlot(mc.player, Items.END_CRYSTAL) != -1;
    }

    private boolean isValidActivationItem(ItemStack stack) {
        Item item = stack.getItem();
        if (item == Items.END_CRYSTAL) return true;
        if (item == Items.SHIELD || item == Items.GLOWSTONE || item == Items.RESPAWN_ANCHOR) return false;
        if (stack.isIn(ItemTags.SPEARS)) return false;
        return stack.isIn(ItemTags.SWORDS) || stack.isIn(ItemTags.AXES) || item == Items.MACE;
    }

    private boolean isBaseHit(HitResult hit) {
        return hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK
                && mc.world != null && CrystalPlacement.isBase(mc.world, blockHit.getBlockPos());
    }

    // ------------------------------------------------------------------ pacing

    private boolean placeReady() {
        int now = mc.player.age;
        if (now < placeTimerTick) {
            placeTimerTick = now;
            return false;
        }
        return now != placeTimerTick && speedReady();
    }

    private boolean breakReady() {
        int now = mc.player.age;
        if (now < breakTimerTick) {
            breakTimerTick = now;
            return false;
        }
        return now != breakTimerTick && speedReady();
    }

    // ------------------------------------------------------------------ state

    private boolean canRun() {
        return mc.player != null && mc.world != null && mc.interactionManager != null && mc.currentScreen == null
                && !mc.isPaused() && mc.player.isAlive() && !mc.player.isUsingItem()
                && mc.interactionManager.getCurrentGameMode() != GameMode.SPECTATOR;
    }

    private void resetState(boolean dropDeferredRestore) {
        swap.reset(dropDeferredRestore);
        obsidianPlaced = false;
        followSince = 0L;
        followUntil = 0L;
        ObsidianIntent.clear();
        releaseBob();
        int now = mc.player != null ? mc.player.age : 0;
        placeTimerTick = now;
        breakTimerTick = now;
        placeArmedAge = NO_AGE;
        placeArmedBase = null;
        breakDelayCrystal = -1;
        breakArmedAge = NO_AGE;
        lastBrokenId = -1;
        lastBrokenAt = 0L;
        lastPlacedAt = 0L;
        boundPlayer = mc.player;
        boundWorld = mc.world;
    }

    public BobState getBobState() {
        return bobState;
    }

    public BlockPos getBobBase() {
        return bobBase;
    }

    /** True while the module is breaking the last crystal and bringing the look home after a release. */
    public boolean isReturning() {
        return returning;
    }
}
