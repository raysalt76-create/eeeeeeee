package interstellarTikiTiks.apexx.client.module.implementation.combat;

import interstellarTikiTiks.apexx.client.event.EventBus;
import interstellarTikiTiks.apexx.client.event.EventTarget;
import interstellarTikiTiks.apexx.client.event.impl.CombatSwapLockEvent;
import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.ModeSetting;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import interstellarTikiTiks.apexx.client.module.SubCategory;
import interstellarTikiTiks.apexx.client.util.PlaceResolver;
import interstellarTikiTiks.apexx.client.util.PlaceSolution;
import interstellarTikiTiks.apexx.client.util.RotationManager;
import interstellarTikiTiks.apexx.client.util.combat.BlockPlacementValidator;
import interstellarTikiTiks.apexx.client.util.combat.InteractSim;
import interstellarTikiTiks.apexx.client.util.combat.ModuleDelays;
import interstellarTikiTiks.apexx.client.util.combat.SlotSync;
import interstellarTikiTiks.apexx.client.util.combat.SubTickSequencer;
import interstellarTikiTiks.apexx.client.util.combat.TickActionOrder;
import net.minecraft.entity.Entity;
import net.minecraft.entity.vehicle.TntMinecartEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * CartBlocker — when a TNT minecart you did NOT place turns up within a radius around you, drops a
 * block between you and it so the blast is eaten if the cart detonates.
 *
 * <p><b>Attribution</b>: to place a TNT minecart a player has to be holding one, so any cart first
 * seen while you are holding a TNT minecart (within a short grace window) is treated as yours and
 * left alone while {@code Ignore Own Carts} is on; everything else counts as an incoming cart.
 *
 * <p><b>Action</b>: the cover block is resolved through {@link PlaceResolver} — a solid neighbour,
 * face and hit-vector for an empty cell between you and the cart. It is placed as a real same-tick
 * slot swap followed by one placement later in the tick (via {@link SubTickSequencer}), gated on the
 * live ray actually filling that cell, then the previous slot is restored on a later tick through
 * {@link SlotSync#restore}. Nothing is placed unless a block is in your hotbar and the face is
 * reachable with a vanilla-legal click.
 */
public class CartBlocker extends Module implements interstellarTikiTiks.apexx.client.module.hooks.ClickSimulating {

    private final SliderSetting radius = new SliderSetting("Radius", 5.0, 2.0, 12.0, 1)
            .setDescription("When an enemy's TNT minecart comes within this many blocks of you, a cover block is placed against the blast.");
    private final ModeSetting placement = new ModeSetting("Placement", "Block Cart", "Block Cart", "Shield Self")
            .setDescription("Block Cart: a wall right next to the cart on your side. Shield Self: a wall right next to you on the cart's side.");
    private final BooleanSetting tallWall = new BooleanSetting("Tall Wall", true)
            .setDescription("Also stacks a second block on top of the cover so a standing player is shielded, not just their feet.");
    private final BooleanSetting obsidianOnly = new BooleanSetting("Blast-Proof Only", false)
            .setDescription("Only ever covers with a high blast-resistance block (obsidian, crying obsidian, netherite, reinforced deepslate, ancient debris). Off: uses any block in your hotbar, preferring blast-proof ones.");
    private final BooleanSetting ignoreOwnCarts = new BooleanSetting("Ignore Own Carts", true)
            .setDescription("Never covers a cart you placed yourself (one first seen while you were holding a TNT minecart).");
    private final ModeSetting rotationMode = new ModeSetting("Rotation", "Smooth", "Smooth", "Silent", "None")
            .setDescription("How it turns to the placement face. Smooth: your camera visibly eases over. Silent: only the server-side rotation turns. None: never turns, and only places when your own crosshair already reaches a usable face.");
    private final SliderSetting strength = new SliderSetting("Aim Strength", 60, 1, 100, 0)
            .setDescription("How fast the aim converges on the placement face (1 = slow, 100 = fastest). Faster covers sooner; slower looks more natural.");
    private final BooleanSetting packetClicks = new BooleanSetting("Packet Clicks", false)
            .setDescription("Place through the silent interaction path (using the server-side rotation) instead of a normal crosshair click. Useful with Silent rotation; off is closer to vanilla.");
    private final BooleanSetting switchBack = new BooleanSetting("Swap Back", true)
            .setDescription("Reselect the item you were holding after the cover block is placed.");
    private final SliderSetting reach = new SliderSetting("Reach", 4.0, 1.0, 4.5, 1)
            .setDescription("Farthest block face it will click, in blocks. Capped at vanilla's 4.5 block reach.");
    private final SliderSetting cooldownMs = new SliderSetting("Cooldown (ms)", 600, 100, 3000, 0).withJitter(100)
            .setDescription("After covering a cart, ignore that same cart for this long, so one cart gets one wall instead of a stream of blocks.");
    private final SliderSetting reactionDelay = new SliderSetting("Reaction Delay (ms)", 40, 0, 300, 0).withJitter(15)
            .setSection("Delays")
            .setDescription("Wait after a dangerous cart is first spotted before the block is placed, in milliseconds. It keeps aiming meanwhile. 0 = place the moment the face is in reach.");
    private final SliderSetting swapDelay = new SliderSetting("Swap Delay (ms)", 5, 0, 40, 0).withJitter(2)
            .setSection("Delays")
            .setDescription("Gap between selecting the cover block and placing it, in milliseconds, inside the same tick. The place is dropped (and retried next tick) if it would not fit in the tick. 0 = place right after the swap.");
    private final SliderSetting swapBackDelay = new SliderSetting("Swap Back Delay (ms)", 50, 0, 500, 0).withJitter(15)
            .setSection("Delays")
            .setDescription("Wait after placing before your previous hotbar item is reselected, in milliseconds. Always at least the next tick.")
            .setDependsOn("Swap Back");

    /** A cart first seen within this window after you last held a TNT minecart is treated as yours. */
    private static final long OWN_HOLD_GRACE_MS = 1500L;

    private static final Set<net.minecraft.item.Item> BLAST_PROOF = Set.of(
            Items.OBSIDIAN, Items.CRYING_OBSIDIAN, Items.NETHERITE_BLOCK,
            Items.REINFORCED_DEEPSLATE, Items.ANCIENT_DEBRIS);

    // Slot ownership (mirrors AutoWeb's proven swap/restore shape).
    private int originalSlot = -1;
    private int expectedSlot = -1;
    private Object boundPlayer;
    private Object boundWorld;
    private boolean pendingRestore = false;
    private long restoreAtMs = 0L;
    private final Map<Integer, Long> reactAtMs = new HashMap<>();
    private boolean placePending = false;

    // Cart attribution / debounce.
    private long heldCartUntilMs = 0L;
    private final Set<Integer> seenCartIds = new HashSet<>();
    private final Set<Integer> ownCartIds = new HashSet<>();
    private final Map<Integer, Long> cooldowns = new HashMap<>();

    public CartBlocker() {
        super("CartBlocker", "Protects you from TNT minecart attacks: when an enemy's TNT minecart gets close, it instantly places a block (blast-proof if available) between the cart and you, either right beside the cart or right beside you, optionally two blocks tall. Ignores carts you placed yourself.", Category.COMBAT);
        setSubCategory(SubCategory.CART);
        strength.setVisibility(() -> !rotationMode.is("None"));
        addSetting(radius);
        addSetting(placement);
        addSetting(tallWall);
        addSetting(obsidianOnly);
        addSetting(ignoreOwnCarts);
        addSetting(rotationMode);
        addSetting(strength);
        addSetting(packetClicks);
        addSetting(switchBack);
        addSetting(reach);
        addSetting(cooldownMs);
        addSetting(reactionDelay);
        addSetting(swapDelay);
        addSetting(swapBackDelay);
    }

    @Override
    public void onEnable() {
        EventBus.register(this);
        clearAll();
    }

    @Override
    public void onDisable() {
        EventBus.unregister(this);
        // Hand the slot back before the bookkeeping is wiped: clearAll() forgets originalSlot, so
        // disabling mid-sequence would otherwise strand the cover block in hand.
        restoreSlot();
        clearAll();
    }

    @Override
    protected void onRuntimeStateReleased() {
        restoreSlot();
        clearAll();
    }

    @EventTarget
    public void onCombatSwapLock(CombatSwapLockEvent event) {
        if (event.isRequestedBy(this)) return;
        if (pendingRestore || placePending) {
            event.setItemSwapLocked(true);
            event.setAutoAttackLocked(true);
        }
    }

    @Override
    public void onTick() {
        if (mc.player == null || mc.world == null || mc.interactionManager == null || mc.currentScreen != null
                || !mc.player.isAlive() || (pendingRestore && (mc.player != boundPlayer || mc.world != boundWorld))) {
            releaseRuntimeState();
            return;
        }
        // SubTickSequencer drops unexecuted work at tick start, so a queued place whose finally never
        // ran must not keep the slot and swap lock latched.
        placePending = false;

        long now = System.currentTimeMillis();
        if (holdingTntMinecart()) heldCartUntilMs = now + OWN_HOLD_GRACE_MS;

        if (pendingRestore) {
            if (now < restoreAtMs) return;
            restoreSlot();
            return;
        }

        TntMinecartEntity cart = findIncomingCart(now);
        if (cart == null) {
            RotationManager.stop(this);
            return;
        }

        int blockSlot = findBlockSlot();
        if (blockSlot < 0) {
            RotationManager.stop(this);
            return;
        }

        PlaceSolution solution = resolveCover(cart);
        if (solution == null) {
            RotationManager.stop(this);
            return;
        }

        boolean silent = rotationMode.is("Silent");
        aim(solution.hitVec());

        // Reaction delay: aim straight away, but only place once the configured time since spotting this cart has passed.
        long readyAt = reactAtMs.computeIfAbsent(cart.getId(), id -> now + ModuleDelays.clampMs(reactionDelay.sample()));
        if (now < readyAt) return;

        if (!InteractSim.useReady(mc) || otherModuleHoldsSwap()) return;

        // Fire only when the live authoritative ray already fills the resolved cell: actual face
        // reached, not rotation tolerance reached.
        BlockHitResult live = BlockPlacementValidator.rayFromLook(mc, silent);
        if (live == null
                || !live.getBlockPos().equals(solution.against())
                || live.getSide() != solution.side()
                || !solution.placedAt().equals(BlockPlacementValidator.placementCell(mc, live))) {
            return;
        }
        fire(cart, blockSlot, live, silent);
    }

    // ── placement ─────────────────────────────────────────────────────────────────

    private void fire(TntMinecartEntity cart, int blockSlot, BlockHitResult live, boolean silentRotation) {
        int held = mc.player.getInventory().getSelectedSlot();
        if (held != blockSlot) {
            // Same-tick swap, then the single placement dispatch, both before this tick's movement
            // packet. SlotSync.select itself does not check the interval, so refuse the swap here
            // when an attack or use already left in it (the swap must lead every action).
            if (!TickActionOrder.canSwitchSlot()) return;
            if (!SlotSync.select(mc, blockSlot) || mc.player.getInventory().getSelectedSlot() != blockSlot) return;
            if (originalSlot == -1) originalSlot = held;
        }
        expectedSlot = blockSlot;
        boundPlayer = mc.player;
        boundWorld = mc.world;
        pendingRestore = true;
        restoreAtMs = System.currentTimeMillis() + ModuleDelays.clampMs(swapBackDelay.sample());
        placePending = true;

        final int cartId = cart.getId();
        final boolean dispatchSilent = silentRotation || packetClicks.isEnabled();
        SubTickSequencer.queueAction(this, () -> {
            try {
                if (mc.player == null || mc.world == null
                        || !(mc.player.getMainHandStack().getItem() instanceof BlockItem)) {
                    return;
                }
                // Re-cast at dispatch time: still the same clickable face, nothing in front of it.
                BlockHitResult now = BlockPlacementValidator.rayFromLook(mc, silentRotation);
                if (now == null || !now.getBlockPos().equals(live.getBlockPos()) || now.getSide() != live.getSide()) {
                    return;
                }
                if (InteractSim.place(mc, now, dispatchSilent)) {
                    // Keep the reached angles for the rest of this tick; a new aim starts next tick.
                    RotationManager.setPostAttackHold(this, 1, RotationManager.getFinalYaw(), RotationManager.getFinalPitch());
                    cooldowns.put(cartId, System.currentTimeMillis() + (long) cooldownMs.sample());
                    reactAtMs.remove(cartId);
                }
            } finally {
                placePending = false;
            }
        }, held != blockSlot ? (double) ModuleDelays.clampMs(swapDelay.sample()) : 0.0);
    }

    /**
     * A cover cell between the player and the cart, resolved to a clickable neighbour + face. Tries a
     * wall beside the cart (or beside the player) at foot height, then one block up for a tall wall.
     */
    private PlaceSolution resolveCover(TntMinecartEntity cart) {
        Vec3d cartCenter = cart.getBoundingBox().getCenter();
        Vec3d playerCenter = mc.player.getEntityPos();
        double dx = playerCenter.x - cartCenter.x;
        double dz = playerCenter.z - cartCenter.z;
        Direction toPlayer = Direction.getFacing(dx, 0.0, dz);

        BlockPos base;
        if (placement.is("Shield Self")) {
            // A wall right next to you, on the cart's side.
            base = BlockPos.ofFloored(playerCenter).offset(toPlayer.getOpposite());
        } else {
            // A wall right next to the cart, on your side.
            base = cart.getBlockPos().offset(toPlayer);
        }

        double r = reachLimit();
        PlaceSolution low = tryCell(base, r);
        if (tallWall.isEnabled()) {
            PlaceSolution high = tryCell(base.up(), r);
            // Prefer the foot-level block first; the upper one lands on the next tick's pass.
            if (low != null) return low;
            if (high != null) return high;
        }
        return low;
    }

    private PlaceSolution tryCell(BlockPos cell, double reach) {
        if (!safeCell(cell)) return null;
        return PlaceResolver.resolvePlacement(mc, cell, reach);
    }

    /** Never cover a cell the player is standing in or walking into. */
    private boolean safeCell(BlockPos cell) {
        Box cb = new Box(cell);
        Box self = mc.player.getBoundingBox().expand(0.1, 0.0, 0.1);
        Vec3d v = mc.player.getVelocity();
        return !cb.intersects(self) && !cb.intersects(self.offset(v.x * 2.0, Math.max(v.y, 0.0) * 2.0, v.z * 2.0));
    }

    private void aim(Vec3d point) {
        if (rotationMode.is("None")) {
            RotationManager.stop(this);
            return;
        }
        double engineStrength = RotationManager.engineStrength(strength.getValue(), RotationManager.STRENGTH_SCALE_TRACK);
        boolean silent = rotationMode.is("Silent");
        RotationManager.aimAt(this, RotationManager.Priority.HIGH, () -> point, engineStrength, 0.0, silent);
    }

    // ── cart selection ──────────────────────────────────────────────────────────────

    private TntMinecartEntity findIncomingCart(long now) {
        double rSq = radius.getValue() * radius.getValue();
        TntMinecartEntity best = null;
        double bestSq = rSq;
        for (Entity e : mc.world.getEntities()) {
            if (!(e instanceof TntMinecartEntity cart) || cart.isRemoved() || !cart.isAlive()) continue;
            // Classify the first time we meet a cart: held a TNT minecart recently ⇒ it is ours.
            if (seenCartIds.add(cart.getId()) && now < heldCartUntilMs) {
                ownCartIds.add(cart.getId());
            }
            if (ignoreOwnCarts.isEnabled() && ownCartIds.contains(cart.getId())) continue;
            Long until = cooldowns.get(cart.getId());
            if (until != null && now < until) continue;
            double sq = mc.player.squaredDistanceTo(cart);
            if (sq <= bestSq) {
                bestSq = sq;
                best = cart;
            }
        }
        return best;
    }

    private boolean holdingTntMinecart() {
        return mc.player.getMainHandStack().isOf(Items.TNT_MINECART)
                || mc.player.getOffHandStack().isOf(Items.TNT_MINECART);
    }

    // ── hotbar / helpers ─────────────────────────────────────────────────────────────

    private int findBlockSlot() {
        // First pass: a high blast-resistance block.
        for (int i = 0; i < 9; i++) {
            if (BLAST_PROOF.contains(mc.player.getInventory().getStack(i).getItem())) return i;
        }
        if (obsidianOnly.isEnabled()) return -1;
        // Second pass: any placeable block.
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).getItem() instanceof BlockItem) return i;
        }
        return -1;
    }

    private double reachLimit() {
        return Math.min(reach.getValue(), mc.player.getBlockInteractionRange());
    }

    /** Another module (shield break, mace chain, web, ...) owns the hotbar right now. */
    private boolean otherModuleHoldsSwap() {
        CombatSwapLockEvent lock = new CombatSwapLockEvent(this);
        EventBus.post(lock);
        return lock.isItemSwapLocked();
    }

    /** Restores the pre-swap slot only while this module still owns the selection in the same world. */
    private void restoreSlot() {
        RotationManager.stop(this);
        if (switchBack.isEnabled() && originalSlot >= 0 && originalSlot <= 8 && mc.player != null
                && mc.player == boundPlayer && mc.world == boundWorld
                && mc.player.getInventory().getSelectedSlot() == expectedSlot) {
            SlotSync.restore(mc, originalSlot);
        }
        originalSlot = expectedSlot = -1;
        boundPlayer = boundWorld = null;
        pendingRestore = false;
        placePending = false;
        restoreAtMs = 0L;
    }

    private void clearAll() {
        pendingRestore = false;
        placePending = false;
        restoreAtMs = 0L;
        reactAtMs.clear();
        originalSlot = expectedSlot = -1;
        boundPlayer = boundWorld = null;
        seenCartIds.clear();
        ownCartIds.clear();
        cooldowns.clear();
        heldCartUntilMs = 0L;
    }
}
