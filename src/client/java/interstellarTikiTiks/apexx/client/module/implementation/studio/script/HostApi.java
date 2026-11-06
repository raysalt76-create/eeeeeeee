package interstellarTikiTiks.apexx.client.module.implementation.studio.script;

import interstellarTikiTiks.apexx.client.friend.FriendManager;
import interstellarTikiTiks.apexx.client.manager.TargetManager;
import interstellarTikiTiks.apexx.client.util.RotationManager;
import interstellarTikiTiks.apexx.client.util.SystemInputSimulator;
import interstellarTikiTiks.apexx.client.util.combat.BlockPlacementValidator;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * The fixed host-API table — the ONLY callable surface for Apexx scripts
 * (docs §2.8). Built once at class-init into an immutable name → index map plus a
 * parallel {@link HostFn}[] dispatch array. The compiler resolves every call name
 * to its index (unknown name = compile error), so runtime dispatch is one array
 * index with no string matching.
 *
 * <p>Each function wraps an existing client util and is itself safe: it guards
 * for null {@code mc.player}/{@code mc.world}, clamps args at the boundary, and a
 * script can do nothing a native module cannot.</p>
 */
public final class HostApi {

    private HostApi() {}

    private static final MinecraftClient mc = MinecraftClient.getInstance();

    // name → dispatch index
    private static final Map<String, Integer> INDEX = new HashMap<>();
    // index → function
    private static final HostFn[] TABLE;

    // Strength/humanization mapping mirrors AimAssist's convention so a script's
    // strength 0..100 feels identical to native aim modules.
    private static double mappedStrength(double s) { return 1.0 + MathHelper.clamp(s, 0, 100) / 100.0 * 24.0; }
    private static double mappedHuman(double s) { return MathHelper.clamp(s, 0, 100) / 100.0; }

    public static int indexOf(String name) {
        Integer i = INDEX.get(name);
        return i == null ? -1 : i;
    }

    public static boolean isHostFunction(String name) {
        return INDEX.containsKey(name);
    }

    public static HostFn[] table() {
        return TABLE;
    }

    // ── builder ──────────────────────────────────────────────────────────────

    private static final Map<String, HostFn> BUILDER = new HashMap<>();
    private static void def(String name, HostFn fn) { BUILDER.put(name, fn); }

    static {
        buildInput();
        buildAim();
        buildQueries();
        buildWorld();
        buildUtil();
        buildData();
        buildEntities();
        buildActions();
        buildTiming();
        buildDraw();
        Evaluator.setEntityFields(ScriptFields::get);

        // Freeze into a stable index order.
        TABLE = new HostFn[BUILDER.size()];
        int i = 0;
        for (Map.Entry<String, HostFn> e : BUILDER.entrySet()) {
            INDEX.put(e.getKey(), i);
            TABLE[i] = e.getValue();
            i++;
        }
    }

    // ── arg helpers ────────────────────────────────────────────────────────

    private static double n(Value[] a, int i) { return i < a.length ? a[i].asNumber() : 0.0; }
    private static boolean b(Value[] a, int i) { return i < a.length && a[i].asBool(); }
    private static String s(Value[] a, int i) { return i < a.length ? a[i].asString() : ""; }
    private static Entity ent(Value[] a, int i) {
        if (i >= a.length) return null;
        Object o = a[i].asEntity();
        return (o instanceof Entity) ? (Entity) o : null;
    }

    // ── INPUT (→ SystemInputSimulator) ───────────────────────────────────────

    private static void buildInput() {
        def("attack", (a, self) -> { if (paced("attack", 1)) SystemInputSimulator.tapAttack(); return Value.NULL; });
        def("attackHold", (a, self) -> { boolean h = b(a,0); if (h) SystemInputSimulator.pressAttack(); else SystemInputSimulator.releaseAttack(); self.noteAttack(h); return Value.NULL; });
        def("use", (a, self) -> { if (paced("use", 1)) SystemInputSimulator.tapUse(); return Value.NULL; });
        def("useHold", (a, self) -> { boolean h = b(a,0); if (h) SystemInputSimulator.pressUse(); else SystemInputSimulator.releaseUse(); self.noteUse(h); return Value.NULL; });
        def("click", (a, self) -> { if (paced("click", 1)) SystemInputSimulator.tapMouseButton((int) MathHelper.clamp(n(a,0), 0, 7)); return Value.NULL; });
        def("jump", (a, self) -> { if (paced("jump", 1)) SystemInputSimulator.tapJump(); return Value.NULL; });
        def("sneak", (a, self) -> { boolean h = b(a,0); if (h) SystemInputSimulator.pressSneak(); else SystemInputSimulator.releaseSneak(); self.noteSneak(h); return Value.NULL; });
        def("sprint", (a, self) -> { boolean h = b(a,0); if (h) SystemInputSimulator.pressSprint(); else SystemInputSimulator.releaseSprint(); self.noteSprint(h); return Value.NULL; });
        def("keyPress", (a, self) -> { int k = (int) n(a,0); SystemInputSimulator.pressKey(k); self.noteKeyPress(k); return Value.NULL; });
        def("keyRelease", (a, self) -> { int k = (int) n(a,0); SystemInputSimulator.releaseKey(k); self.noteKeyRelease(k); return Value.NULL; });
        def("keyTap", (a, self) -> { if (paced("keyTap", 1)) SystemInputSimulator.tapKey((int) n(a,0)); return Value.NULL; });
        def("swapToSlot", (a, self) -> { SystemInputSimulator.setHotbarSlot((int) MathHelper.clamp(n(a,0), 0, 8)); return Value.NULL; });
        def("tapSlot", (a, self) -> { if (paced("tapSlot", 1)) SystemInputSimulator.tapHotbarSlot((int) MathHelper.clamp(n(a,0), 0, 8)); return Value.NULL; });
        def("swapToOffhand", (a, self) -> {
            int slot = (int) n(a,0);
            if (validSlot(slot) && paced("swapToOffhand", 1)) SystemInputSimulator.swapSlot(slot, 40);
            return Value.NULL;
        });
        def("swapHands", (a, self) -> { if (paced("swapHands", 1)) SystemInputSimulator.tapSwapHands(); return Value.NULL; });
        def("clickSlot", (a, self) -> { int slot = (int) n(a,0); if (validSlot(slot) && paced("clickSlot", 1)) SystemInputSimulator.pickupSlot(slot); return Value.NULL; });
        def("dropItem", (a, self) -> { if (paced("dropItem", 1)) SystemInputSimulator.tapDrop(); return Value.NULL; });
        def("dropSlot", (a, self) -> { int slot = (int) n(a,0); if (validSlot(slot) && paced("dropSlot", 1)) SystemInputSimulator.throwSlot(slot); return Value.NULL; });
    }

    /**
     * Guard a script-controlled slot id against the live screen handler before it
     * reaches interactionManager.clickSlot — an unclamped negative or out-of-range
     * id would otherwise AIOOBE/crash the interaction. No-op (false) if there is no
     * player/handler or the id is outside [0, slots.size()).
     */
    private static boolean validSlot(int slot) {
        if (mc.player == null || mc.player.currentScreenHandler == null) return false;
        return slot >= 0 && slot < mc.player.currentScreenHandler.slots.size();
    }

    // ── AIM (→ RotationManager; owner = the ScriptModule) ────────────────────

    private static void buildAim() {
        // aimAtEntity(e, point): point 0=body, 1=head
        def("aimAtEntity", (a, self) -> aimAtEntity(a, self, false));
        def("silentAimEntity", (a, self) -> aimAtEntity(a, self, true));

        // aimAt(x, y, z, strength): live world coords
        def("aimAt", (a, self) -> {
            if (mc.player == null) return Value.NULL;
            final double x = n(a,0), y = n(a,1), z = n(a,2);
            double strength = n(a,3);
            Supplier<Vec3d> supplier = () -> new Vec3d(x, y, z);
            RotationManager.aimAt(self, RotationManager.Priority.NORMAL, supplier,
                    mappedStrength(strength), 0.0, false);
            return Value.NULL;
        });

        // setRotation(yaw, pitch, strength, silent)
        def("setRotation", (a, self) -> {
            if (mc.player == null) return Value.NULL;
            final float yaw = (float) n(a,0);
            final float pitch = (float) MathHelper.clamp(n(a,1), -90, 90);
            double strength = n(a,2);
            boolean silent = b(a,3);
            Supplier<Vec3d> supplier = () -> rotationToPoint(yaw, pitch);
            if (strength <= 0) {
                RotationManager.setRotationSupplier(self, RotationManager.Priority.NORMAL, supplier,
                        mappedStrength(strength), RotationManager.RotationMode.INSTANT, 0.0, 0L, silent, false);
            } else {
                RotationManager.setRotationSupplier(self, RotationManager.Priority.NORMAL, supplier,
                        mappedStrength(strength), RotationManager.RotationMode.SMOOTH,
                        0.0, 0L, silent, false);
            }
            return Value.NULL;
        });

        def("clearAim", (a, self) -> { RotationManager.clearTarget(self); return Value.NULL; });
        def("aimDone", (a, self) -> Value.bool(RotationManager.isRotationComplete((float) n(a,0))));

        def("yawTo", (a, self) -> {
            Entity e = ent(a,0);
            if (e == null || mc.player == null) return Value.number(mc.player == null ? 0 : mc.player.getYaw());
            float[] r = RotationManager.calculateRotationsToPos(aimPoint(e, 0), mc.player.getYaw());
            return Value.number(r[0]);
        });
        def("pitchTo", (a, self) -> {
            Entity e = ent(a,0);
            if (e == null || mc.player == null) return Value.number(mc.player == null ? 0 : mc.player.getPitch());
            float[] r = RotationManager.calculateRotationsToPos(aimPoint(e, 0), mc.player.getYaw());
            return Value.number(r[1]);
        });
    }

    private static Value aimAtEntity(Value[] a, ScriptModule self, boolean silent) {
        if (mc.player == null) return Value.NULL;
        final Entity e = ent(a,0);
        if (e == null) return Value.NULL;
        final int point = (int) n(a,1);
        Supplier<Vec3d> supplier = () -> {
            if (e.isRemoved() || !e.isAlive()) return null;
            return aimPoint(e, point);
        };
        RotationManager.aimAt(self, RotationManager.Priority.NORMAL, supplier,
                mappedStrength(self.getAimStrength()), 0.0, silent);
        return Value.NULL;
    }

    /** Body (point 0) = entity center; Head (point 1) = eye height. */
    private static Vec3d aimPoint(Entity e, int point) {
        double cx = e.getX();
        double cz = e.getZ();
        double cy;
        if (point == 1 && e instanceof LivingEntity le) {
            cy = e.getY() + le.getStandingEyeHeight();
        } else {
            cy = e.getY() + e.getHeight() * 0.5;
        }
        return new Vec3d(cx, cy, cz);
    }

    /** A point 100 blocks out from the player's eye along (yaw,pitch). */
    private static Vec3d rotationToPoint(float yaw, float pitch) {
        if (mc.player == null) return Vec3d.ZERO;
        Vec3d eye = mc.player.getEyePos();
        float yawRad = (float) Math.toRadians(yaw);
        float pitchRad = (float) Math.toRadians(pitch);
        double xDir = -Math.sin(yawRad) * Math.cos(pitchRad);
        double yDir = -Math.sin(pitchRad);
        double zDir = Math.cos(yawRad) * Math.cos(pitchRad);
        return eye.add(new Vec3d(xDir, yDir, zDir).normalize().multiply(100));
    }

    // ── QUERIES (→ mc.player / mc.world / TargetManager) ─────────────────────

    private static void buildQueries() {
        def("nearestEnemy", (a, self) -> Value.entity(nearestEnemy(n(a,0))));
        def("lockedTarget", (a, self) -> Value.entity(TargetManager.getLockedTarget()));
        def("crosshairEntity", (a, self) -> {
            if (mc.crosshairTarget instanceof EntityHitResult ehr) return Value.entity(ehr.getEntity());
            return Value.NULL;
        });

        def("health", (a, self) -> Value.number(mc.player == null ? 0 : mc.player.getHealth()));
        def("maxHealth", (a, self) -> Value.number(mc.player == null ? 0 : mc.player.getMaxHealth()));
        def("food", (a, self) -> Value.number(mc.player == null ? 0 : mc.player.getHungerManager().getFoodLevel()));
        def("absorption", (a, self) -> Value.number(mc.player == null ? 0 : mc.player.getAbsorptionAmount()));

        def("posX", (a, self) -> Value.number(mc.player == null ? 0 : mc.player.getX()));
        def("posY", (a, self) -> Value.number(mc.player == null ? 0 : mc.player.getY()));
        def("posZ", (a, self) -> Value.number(mc.player == null ? 0 : mc.player.getZ()));
        def("velX", (a, self) -> Value.number(mc.player == null ? 0 : mc.player.getVelocity().x));
        def("velY", (a, self) -> Value.number(mc.player == null ? 0 : mc.player.getVelocity().y));
        def("velZ", (a, self) -> Value.number(mc.player == null ? 0 : mc.player.getVelocity().z));
        def("yaw", (a, self) -> Value.number(mc.player == null ? 0 : mc.player.getYaw()));
        def("pitch", (a, self) -> Value.number(mc.player == null ? 0 : mc.player.getPitch()));

        def("onGround", (a, self) -> Value.bool(mc.player != null && mc.player.isOnGround()));
        def("isUsing", (a, self) -> Value.bool(mc.player != null && mc.player.isUsingItem()));
        def("isBlocking", (a, self) -> Value.bool(mc.player != null && mc.player.isBlocking()));
        def("inGui", (a, self) -> Value.bool(mc.currentScreen != null));

        def("heldItem", (a, self) -> Value.string(mc.player == null ? "air" : itemId(mc.player.getMainHandStack())));
        def("offhandItem", (a, self) -> Value.string(mc.player == null ? "air" : itemId(mc.player.getOffHandStack())));
        def("offhandIsTotem", (a, self) -> Value.bool(mc.player != null && mc.player.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING)));
        def("isHoldingWeapon", (a, self) -> Value.bool(mc.player != null && isWeapon(mc.player.getMainHandStack())));

        def("findItemSlot", (a, self) -> Value.number(findItemSlot(s(a,0))));
        def("slotCount", (a, self) -> Value.number(slotCount(s(a,0))));
        def("selectedSlot", (a, self) -> Value.number(mc.player == null ? 0 : mc.player.getInventory().getSelectedSlot()));

        def("distanceTo", (a, self) -> {
            Entity e = ent(a,0);
            return Value.number((e == null || mc.player == null) ? 0 : mc.player.distanceTo(e));
        });
        def("entHealth", (a, self) -> {
            Entity e = ent(a,0);
            return Value.number((e instanceof LivingEntity le) ? le.getHealth() : 0);
        });
        def("entX", (a, self) -> { Entity e = ent(a,0); return Value.number(e == null ? 0 : e.getX()); });
        def("entY", (a, self) -> { Entity e = ent(a,0); return Value.number(e == null ? 0 : e.getY()); });
        def("entZ", (a, self) -> { Entity e = ent(a,0); return Value.number(e == null ? 0 : e.getZ()); });
        def("entIsPlayer", (a, self) -> Value.bool(ent(a,0) instanceof PlayerEntity));
        def("entName", (a, self) -> {
            Entity e = ent(a,0);
            return Value.string(e == null ? "" : e.getName().getString());
        });
        def("attackReady", (a, self) -> Value.bool(mc.player != null && mc.player.getAttackCooldownProgress((float) n(a,0)) >= 1.0f));
    }

    private static Entity nearestEnemy(double range) {
        if (mc.player == null || mc.world == null) return null;
        double maxSq = range * range;
        PlayerEntity best = null;
        double bestSq = Double.MAX_VALUE;
        for (PlayerEntity p : mc.world.getPlayers()) {
            if (p == null || p == mc.player || !p.isAlive() || p.getHealth() <= 0) continue;
            if (FriendManager.getInstance().isFriend(p.getUuid())) continue;
            double dSq = mc.player.squaredDistanceTo(p);
            if (dSq <= maxSq && dSq < bestSq) {
                bestSq = dSq;
                best = p;
            }
        }
        return best;
    }

    private static int findItemSlot(String name) {
        if (mc.player == null || name == null || name.isEmpty()) return -1;
        String needle = name.toLowerCase();
        for (int i = 0; i < 36; i++) {
            ItemStack st = mc.player.getInventory().getStack(i);
            if (!st.isEmpty() && itemId(st).contains(needle)) return i;
        }
        return -1;
    }

    private static int slotCount(String name) {
        if (mc.player == null || name == null || name.isEmpty()) return 0;
        String needle = name.toLowerCase();
        int count = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack st = mc.player.getInventory().getStack(i);
            if (!st.isEmpty() && itemId(st).contains(needle)) count += st.getCount();
        }
        return count;
    }

    private static String itemId(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return "air";
        try {
            return Registries.ITEM.getId(stack.getItem()).getPath();
        } catch (Throwable t) {
            return "air";
        }
    }

    private static boolean isWeapon(ItemStack stack) {
        // Tag-based: an id match on "axe" also caught every pickaxe.
        if (stack == null || stack.isEmpty()) return false;
        return interstellarTikiTiks.apexx.client.util.combat.CombatItemUtil.isSword(stack)
                || interstellarTikiTiks.apexx.client.util.combat.CombatItemUtil.isAxe(stack)
                || interstellarTikiTiks.apexx.client.util.combat.CombatItemUtil.isMace(stack)
                || stack.getItem() instanceof net.minecraft.item.TridentItem
                || itemId(stack).contains("spear");
    }

    // ── WORLD (→ mc.world / interaction) ─────────────────────────────────────

    private static void buildWorld() {
        def("getBlock", (a, self) -> {
            if (mc.world == null) return Value.string("air");
            BlockPos pos = new BlockPos((int) Math.floor(n(a,0)), (int) Math.floor(n(a,1)), (int) Math.floor(n(a,2)));
            try {
                if (mc.world.getBlockState(pos).isAir()) return Value.string("air");
                return Value.string(Registries.BLOCK.getId(mc.world.getBlockState(pos).getBlock()).getPath());
            } catch (Throwable t) {
                return Value.string("air");
            }
        });
        def("isSolid", (a, self) -> {
            if (mc.world == null) return Value.FALSE;
            BlockPos pos = new BlockPos((int) Math.floor(n(a,0)), (int) Math.floor(n(a,1)), (int) Math.floor(n(a,2)));
            try {
                return Value.bool(mc.world.getBlockState(pos).isSolidBlock(mc.world, pos));
            } catch (Throwable t) {
                return Value.FALSE;
            }
        });
        // placeBlock: reach-guarded right-click on the target block face, routed
        // through the vanilla interaction manager (the same path the anchor/place
        // modules use). Never a fresh interaction path.
        def("placeBlock", (a, self) -> {
            if (mc.player == null || mc.world == null || mc.interactionManager == null) return Value.FALSE;
            BlockPos pos = new BlockPos((int) Math.floor(n(a,0)), (int) Math.floor(n(a,1)), (int) Math.floor(n(a,2)));
            Vec3d center = new Vec3d(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
            if (mc.player.getEyePos().squaredDistanceTo(center) > 6.0 * 6.0) return Value.FALSE; // reach guard
            // Vanilla's item-use cooldown is 4 ticks; a script loop must not place faster.
            if (!paced("place", 4)) return Value.FALSE;
            try {
                net.minecraft.util.hit.BlockHitResult hit = new net.minecraft.util.hit.BlockHitResult(
                        center, net.minecraft.util.math.Direction.UP, pos, false);
                net.minecraft.util.hit.BlockHitResult liveHit = BlockPlacementValidator.validateHit(
                        mc, hit, RotationManager.isSilentRotationActive());
                if (liveHit == null) return Value.FALSE;
                mc.interactionManager.interactBlock(mc.player, net.minecraft.util.Hand.MAIN_HAND, liveHit);
                return Value.TRUE;
            } catch (Throwable t) {
                return Value.FALSE;
            }
        });
    }

    // ── PACING ────────────────────────────────────────────────────────────
    // Without this, `for i in 0..5000 { attack() }` queued thousands of presses in one tick and
    // vanilla sent every one as a packet. Each action kind is limited to vanilla cadence, shared
    // across all scripts.
    private static final Map<String, Integer> LAST_ACTION_AGE = new HashMap<>();

    /** True (and records the action) if {@code kind} has not run in the last {@code everyTicks} ticks. */
    private static boolean paced(String kind, int everyTicks) {
        if (mc.player == null) return false;
        int age = mc.player.age;
        Integer last = LAST_ACTION_AGE.get(kind);
        if (last != null && age >= last && age - last < everyTicks) return false;
        LAST_ACTION_AGE.put(kind, age);
        return true;
    }

    // ── UTIL ──────────────────────────────────────────────────────────────

    private static void buildUtil() {
        def("rand", (a, self) -> {
            double lo = n(a,0), hi = n(a,1);
            if (hi < lo) { double t = lo; lo = hi; hi = t; }
            if (hi == lo) return Value.number(lo);
            return Value.number(lo + ThreadLocalRandom.current().nextDouble() * (hi - lo));
        });
        def("randInt", (a, self) -> {
            int lo = (int) Math.round(n(a,0));
            int hi = (int) Math.round(n(a,1));
            if (hi < lo) { int t = lo; lo = hi; hi = t; }
            if (hi == lo) return Value.number(lo);
            // Long bounds: hi - lo + 1 overflows int for wide ranges and nextInt then throws.
            return Value.number(ThreadLocalRandom.current().nextLong(lo, (long) hi + 1L));
        });
        def("now", (a, self) -> Value.number(System.currentTimeMillis()));
        def("print", (a, self) -> { self.scriptPrint(s(a,0)); return Value.NULL; });

        def("min", (a, self) -> Value.number(Math.min(n(a,0), n(a,1))));
        def("max", (a, self) -> Value.number(Math.max(n(a,0), n(a,1))));
        def("abs", (a, self) -> Value.number(Math.abs(n(a,0))));
        def("floor", (a, self) -> Value.number(Math.floor(n(a,0))));
        def("ceil", (a, self) -> Value.number(Math.ceil(n(a,0))));
        def("round", (a, self) -> Value.number(Math.round(n(a,0))));
        def("sqrt", (a, self) -> { double v = n(a,0); return Value.number(v < 0 ? 0 : Math.sqrt(v)); });
        def("sin", (a, self) -> Value.number(Math.sin(n(a,0))));
        def("cos", (a, self) -> Value.number(Math.cos(n(a,0))));
        def("atan2", (a, self) -> Value.number(Math.atan2(n(a,0), n(a,1))));
        def("clamp", (a, self) -> Value.number(MathHelper.clamp(n(a,0), n(a,1), n(a,2))));
        def("lerp", (a, self) -> Value.number(MathHelper.lerp(n(a,0), n(a,1), n(a,2))));
        def("pow", (a, self) -> {
            double r = Math.pow(n(a,0), n(a,1));
            return Value.number(Double.isFinite(r) ? r : 0);
        });
        def("tan", (a, self) -> Value.number(Math.tan(n(a,0))));
        def("toRadians", (a, self) -> Value.number(Math.toRadians(n(a,0))));
        def("toDegrees", (a, self) -> Value.number(Math.toDegrees(n(a,0))));
        def("wrapDegrees", (a, self) -> Value.number(MathHelper.wrapDegrees(n(a,0))));
        def("dist3", (a, self) -> {
            double dx = n(a,0) - n(a,3), dy = n(a,1) - n(a,4), dz = n(a,2) - n(a,5);
            return Value.number(Math.sqrt(dx * dx + dy * dy + dz * dz));
        });
        def("notify", (a, self) -> {
            try { interstellarTikiTiks.apexx.client.util.Notifier.info(s(a,0), s(a,1)); } catch (Throwable ignored) {}
            return Value.NULL;
        });
    }

    // ── DATA (lists, strings, records — pure) ──────────────────────────────

    private static Value list(Value[] a, int i) { return i < a.length && a[i].type == Value.Type.LIST ? a[i] : null; }

    private static void buildData() {
        def("len", (a, self) -> {
            if (a.length == 0) return Value.number(0);
            Value v = a[0];
            return Value.number(switch (v.type) {
                case LIST -> v.asList().size();
                case STRING -> v.asString().length();
                case RECORD -> v.asRecord().size();
                default -> 0;
            });
        });
        def("push", (a, self) -> {
            Value l = list(a, 0);
            if (l != null && l.asList().size() < Value.MAX_LIST) l.asList().add(a.length > 1 ? a[1] : Value.NULL);
            return l == null ? Value.NULL : l;
        });
        def("pop", (a, self) -> {
            Value l = list(a, 0);
            if (l == null || l.asList().isEmpty()) return Value.NULL;
            return l.asList().remove(l.asList().size() - 1);
        });
        def("insert", (a, self) -> {
            Value l = list(a, 0);
            if (l == null || l.asList().size() >= Value.MAX_LIST) return Value.NULL;
            int i = (int) MathHelper.clamp(n(a,1), 0, l.asList().size());
            l.asList().add(i, a.length > 2 ? a[2] : Value.NULL);
            return l;
        });
        def("removeAt", (a, self) -> {
            Value l = list(a, 0);
            if (l == null) return Value.NULL;
            int i = (int) Math.floor(n(a,1));
            if (i < 0) i += l.asList().size();
            return (i >= 0 && i < l.asList().size()) ? l.asList().remove(i) : Value.NULL;
        });
        def("clear", (a, self) -> { Value l = list(a, 0); if (l != null) l.asList().clear(); return Value.NULL; });
        def("contains", (a, self) -> {
            if (a.length < 2) return Value.FALSE;
            if (a[0].type == Value.Type.STRING) return Value.bool(a[0].asString().contains(a[1].asString()));
            if (a[0].type == Value.Type.RECORD) return Value.bool(a[0].asRecord().containsKey(a[1].asString()));
            for (Value v : a[0].asList()) if (Evaluator.equalsValue(v, a[1])) return Value.TRUE;
            return Value.FALSE;
        });
        def("indexOf", (a, self) -> {
            if (a.length < 2) return Value.number(-1);
            if (a[0].type == Value.Type.STRING) return Value.number(a[0].asString().indexOf(a[1].asString()));
            List<Value> l = a[0].asList();
            for (int i = 0; i < l.size(); i++) if (Evaluator.equalsValue(l.get(i), a[1])) return Value.number(i);
            return Value.number(-1);
        });
        def("join", (a, self) -> {
            StringBuilder sb = new StringBuilder();
            String sep = a.length > 1 ? s(a,1) : ", ";
            List<Value> l = a.length > 0 ? a[0].asList() : List.of();
            for (int i = 0; i < l.size() && sb.length() < Evaluator.MAX_STRING_LEN; i++) {
                if (i > 0) sb.append(sep);
                sb.append(l.get(i).asString());
            }
            return Value.string(sb.toString());
        });
        def("range", (a, self) -> {
            long lo = (long) Math.floor(n(a,0)), hi = (long) Math.floor(n(a,1));
            List<Value> out = new ArrayList<>();
            for (long i = lo; i < hi && out.size() < Value.MAX_LIST; i++) out.add(Value.number(i));
            return Value.list(out);
        });
        def("slice", (a, self) -> {
            List<Value> l = a.length > 0 ? a[0].asList() : List.of();
            int from = (int) MathHelper.clamp(n(a,1), 0, l.size());
            int to = a.length > 2 ? (int) MathHelper.clamp(n(a,2), from, l.size()) : l.size();
            return Value.list(l.subList(from, to));
        });
        def("sort", (a, self) -> {
            Value l = list(a, 0);
            if (l == null) return Value.NULL;
            List<Value> copy = new ArrayList<>(l.asList());
            copy.sort((x, y) -> x.type == Value.Type.STRING && y.type == Value.Type.STRING
                    ? x.asString().compareTo(y.asString()) : Double.compare(x.asNumber(), y.asNumber()));
            return Value.list(copy);
        });
        def("keys", (a, self) -> {
            List<Value> out = new ArrayList<>();
            if (a.length > 0) for (String k : a[0].asRecord().keySet()) out.add(Value.string(k));
            return Value.list(out);
        });
        def("get", (a, self) -> a.length < 2 ? Value.NULL : Evaluator.evalIndex(a[0], a[1]));
        def("str", (a, self) -> Value.string(a.length > 0 ? a[0].asString() : ""));
        def("num", (a, self) -> {
            if (a.length == 0) return Value.number(0);
            if (a[0].type == Value.Type.STRING) {
                try { return Value.number(Double.parseDouble(a[0].asString().trim())); }
                catch (NumberFormatException e) { return Value.number(0); }
            }
            return Value.number(a[0].asNumber());
        });
        def("typeOf", (a, self) -> Value.string(a.length == 0 ? "null" : a[0].type.name().toLowerCase()));
        def("lower", (a, self) -> Value.string(s(a,0).toLowerCase()));
        def("upper", (a, self) -> Value.string(s(a,0).toUpperCase()));
        def("trim", (a, self) -> Value.string(s(a,0).trim()));
        def("startsWith", (a, self) -> Value.bool(s(a,0).startsWith(s(a,1))));
        def("endsWith", (a, self) -> Value.bool(s(a,0).endsWith(s(a,1))));
        def("replace", (a, self) -> {
            String r = s(a,0).replace(s(a,1), s(a,2));
            return Value.string(r.length() > Evaluator.MAX_STRING_LEN ? r.substring(0, Evaluator.MAX_STRING_LEN) : r);
        });
        def("split", (a, self) -> {
            String sep = a.length > 1 ? s(a,1) : ",";
            List<Value> out = new ArrayList<>();
            if (sep.isEmpty()) return Value.list(List.of(Value.string(s(a,0))));
            for (String part : s(a,0).split(java.util.regex.Pattern.quote(sep), -1)) {
                if (out.size() >= Value.MAX_LIST) break;
                out.add(Value.string(part));
            }
            return Value.list(out);
        });
        def("substring", (a, self) -> {
            String str = s(a,0);
            int from = (int) MathHelper.clamp(n(a,1), 0, str.length());
            int to = a.length > 2 ? (int) MathHelper.clamp(n(a,2), from, str.length()) : str.length();
            return Value.string(str.substring(from, to));
        });
    }

    // ── ENTITIES (read-only queries) ───────────────────────────────────────

    private static void buildEntities() {
        def("self", (a, self) -> Value.entity(mc.player));
        def("players", (a, self) -> Value.list(collectPlayers(n(a,0), false)));
        def("enemies", (a, self) -> Value.list(collectPlayers(n(a,0), true)));
        def("entities", (a, self) -> {
            List<Value> out = new ArrayList<>();
            if (mc.player == null || mc.world == null) return Value.list(out);
            double maxSq = n(a,0) * n(a,0);
            String type = a.length > 1 ? s(a,1) : "";
            for (Entity e : mc.world.getEntities()) {
                if (e == mc.player || e.isRemoved() || mc.player.squaredDistanceTo(e) > maxSq) continue;
                if (!type.isEmpty() && !Registries.ENTITY_TYPE.getId(e.getType()).getPath().contains(type)) continue;
                out.add(Value.entity(e));
                if (out.size() >= 256) break;
            }
            return Value.list(out);
        });
        def("isFriend", (a, self) -> {
            Entity e = ent(a,0);
            return Value.bool(e instanceof PlayerEntity && FriendManager.getInstance().isFriend(e.getUuid()));
        });
        def("canSee", (a, self) -> {
            Entity e = ent(a,0);
            return Value.bool(e != null && mc.player != null && mc.player.canSee(e));
        });
        def("hasEffect", (a, self) -> Value.bool(effectLevel(ent(a,0), s(a,1)) > 0));
        def("effectLevel", (a, self) -> Value.number(effectLevel(ent(a,0), s(a,1))));
        def("ping", (a, self) -> {
            if (mc.player == null || mc.getNetworkHandler() == null) return Value.number(0);
            var entry = mc.getNetworkHandler().getPlayerListEntry(mc.player.getUuid());
            return Value.number(entry == null ? 0 : entry.getLatency());
        });
        def("hotbarSlotOf", (a, self) -> Value.number(findHotbar(s(a,0))));
        def("crosshairBlock", (a, self) -> {
            if (!(mc.crosshairTarget instanceof net.minecraft.util.hit.BlockHitResult bh)
                    || bh.getType() != net.minecraft.util.hit.HitResult.Type.BLOCK || mc.world == null) return Value.NULL;
            BlockPos p = bh.getBlockPos();
            String block;
            try { block = Registries.BLOCK.getId(mc.world.getBlockState(p).getBlock()).getPath(); } catch (Throwable t) { block = "air"; }
            return Value.rec().num("x", p.getX()).num("y", p.getY()).num("z", p.getZ()).str("block", block)
                    .str("face", bh.getSide().asString()).build();
        });
    }

    private static List<Value> collectPlayers(double range, boolean enemiesOnly) {
        List<Value> out = new ArrayList<>();
        if (mc.player == null || mc.world == null) return out;
        double maxSq = range * range;
        List<PlayerEntity> found = new ArrayList<>();
        for (PlayerEntity p : mc.world.getPlayers()) {
            if (p == null || p == mc.player || !p.isAlive() || mc.player.squaredDistanceTo(p) > maxSq) continue;
            if (enemiesOnly && FriendManager.getInstance().isFriend(p.getUuid())) continue;
            found.add(p);
        }
        found.sort(java.util.Comparator.comparingDouble(p -> mc.player.squaredDistanceTo(p)));
        for (PlayerEntity p : found) out.add(Value.entity(p));
        return out;
    }

    private static int effectLevel(Entity e, String name) {
        if (!(e instanceof LivingEntity le) || name == null || name.isEmpty()) return 0;
        String needle = name.toLowerCase().replace(' ', '_');
        try {
            for (net.minecraft.entity.effect.StatusEffectInstance inst : le.getStatusEffects()) {
                var id = Registries.STATUS_EFFECT.getId(inst.getEffectType().value());
                if (id != null && id.getPath().equals(needle)) return inst.getAmplifier() + 1;
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    private static int findHotbar(String name) {
        if (mc.player == null || name == null || name.isEmpty()) return -1;
        String needle = name.toLowerCase();
        for (int i = 0; i < 9; i++) {
            ItemStack st = mc.player.getInventory().getStack(i);
            if (!st.isEmpty() && itemId(st).contains(needle)) return i;
        }
        return -1;
    }

    // ── ACTIONS (through the shared seams: SlotSync, AttackValidator, ClickSimController, TickActionOrder) ──

    private static void buildActions() {
        // Real, server-synced hotbar selection (never a raw slot packet).
        def("selectSlot", (a, self) -> {
            if (mc.player == null) return Value.FALSE;
            int slot = (int) MathHelper.clamp(n(a,0), 0, 8);
            if (!interstellarTikiTiks.apexx.client.util.combat.TickActionOrder.canSwitchSlot()) return Value.FALSE;
            return Value.bool(interstellarTikiTiks.apexx.client.util.combat.SlotSync.selectNow(mc, slot));
        });
        // Deferred swap-back to `slot` at the start of the next interval (never in the same frame as an action).
        def("swapBack", (a, self) -> {
            if (mc.player == null) return Value.NULL;
            int slot = (int) MathHelper.clamp(n(a,0), 0, 8);
            interstellarTikiTiks.apexx.client.util.combat.TickActionOrder.deferSlot(slot,
                    mc.player.getInventory().getSelectedSlot());
            return Value.NULL;
        });
        // Attack a specific entity: AttackValidator applies reach, cooldown and the live wire-ray gate.
        def("attackEntity", (a, self) -> {
            Entity e = ent(a,0);
            if (e == null || !paced("attackEntity", 1)) return Value.FALSE;
            return Value.bool(interstellarTikiTiks.apexx.client.util.AttackValidator.dispatchEntityAttack(mc, e, false, true));
        });
        // Attack whatever the crosshair is on, through the validated click path.
        def("hitCrosshair", (a, self) -> {
            if (!paced("hitCrosshair", 1)) return Value.FALSE;
            return Value.bool(interstellarTikiTiks.apexx.client.util.AttackValidator.tryAttack(mc, "script"));
        });
        // Use the held item through ClickSimController (honours Legit/Direct execution mode).
        def("useItem", (a, self) -> {
            if (!paced("useItem", 4)) return Value.FALSE;
            interstellarTikiTiks.apexx.client.util.ClickSimController.use(mc);
            return Value.TRUE;
        });
        def("bindHeld", (a, self) -> Value.bool(self != null && self.isBindHeld()));
        def("keyCode", (a, self) -> {
            int c = ScriptKeys.parse(s(a,0));
            return Value.number(c == ScriptKeys.UNKNOWN ? -1 : c);
        });
        def("keyName", (a, self) -> Value.string(ScriptKeys.name((int) n(a,0))));
        def("isKeyDown", (a, self) -> {
            if (mc.getWindow() == null || mc.currentScreen != null) return Value.FALSE;
            int code = a.length > 0 && a[0].type == Value.Type.STRING ? ScriptKeys.parse(s(a,0)) : (int) n(a,0);
            long window = mc.getWindow().getHandle();
            if (ScriptKeys.isMouse(code)) {
                return Value.bool(org.lwjgl.glfw.GLFW.glfwGetMouseButton(window, -code - 100) == org.lwjgl.glfw.GLFW.GLFW_PRESS);
            }
            for (int known : ScriptKeys.keyboardCodes()) {
                if (known == code) return Value.bool(org.lwjgl.glfw.GLFW.glfwGetKey(window, code) == org.lwjgl.glfw.GLFW.GLFW_PRESS);
            }
            return Value.FALSE;
        });
        // Responses for respondable events (ScriptEventCatalog.RESPONSES); ignored in any other handler.
        def("cancel", (a, self) -> {
            if (self != null) self.putResponse(a.length > 0 ? "cancel:" + s(a,0) : "cancel", Value.TRUE);
            return Value.NULL;
        });
        def("setResult", (a, self) -> {
            if (self != null) self.putResponse("result", a.length > 0 ? a[0] : Value.NULL);
            return Value.NULL;
        });
        def("setInput", (a, self) -> {
            String key = s(a,0);
            if (self != null && List.of("forward", "backward", "left", "right", "jump", "sneak", "sprint").contains(key)) {
                self.putResponse("input:" + key, Value.bool(b(a,1)));
            }
            return Value.NULL;
        });
        def("setLook", (a, self) -> {
            if (self != null) {
                self.putResponse("lookX", Value.number(MathHelper.clamp(n(a,0), -180, 180)));
                self.putResponse("lookY", Value.number(MathHelper.clamp(n(a,1), -90, 90)));
            }
            return Value.NULL;
        });
        // Turns this script off once the current handler has finished (never mid-handler).
        def("disableScript", (a, self) -> {
            if (self != null) self.requestDisable();
            return Value.NULL;
        });
    }

    // ── TIMING / RANDOMNESS ────────────────────────────────────────────────

    private static void buildTiming() {
        // Gaussian draw, truncated to +-2.5 sigma and floored at 0 (delays are never negative).
        def("gauss", (a, self) -> {
            double mean = n(a,0), sd = Math.max(0, n(a,1));
            if (sd == 0) return Value.number(Math.max(0, mean));
            double g = MathHelper.clamp(ThreadLocalRandom.current().nextGaussian(), -2.5, 2.5);
            return Value.number(Math.max(0, mean + g * sd));
        });
        // A slider setting's value with its Gaussian StDev companion applied ("setting slider d = 50 ... jitter 10").
        def("sample", (a, self) -> self == null ? Value.number(0) : self.sampleSetting(s(a,0)));
        def("chance", (a, self) -> Value.bool(ThreadLocalRandom.current().nextDouble() * 100.0 < n(a,0)));
        def("pick", (a, self) -> {
            List<Value> l = a.length > 0 ? a[0].asList() : List.of();
            return l.isEmpty() ? Value.NULL : l.get(ThreadLocalRandom.current().nextInt(l.size()));
        });
        def("uptime", (a, self) -> Value.number(self == null ? 0 : self.uptimeMs()));
        def("ticks", (a, self) -> Value.number(mc.player == null ? 0 : mc.player.age));
    }

    // ── HUD DRAWING (only inside `on render`; no-ops elsewhere) ─────────────

    private static void buildDraw() {
        def("drawText", (a, self) -> {
            net.minecraft.client.gui.DrawContext ctx = self == null || mc == null || mc.textRenderer == null ? null : self.drawContext(1);
            if (ctx == null) return Value.FALSE;
            String text = ScriptDraw.text(s(a, 0));
            if (text.isEmpty()) return Value.FALSE;
            int color = a.length > 3 ? ScriptDraw.parseColor(a[3]) : 0xFFFFFFFF;
            ctx.drawText(mc.textRenderer, text, ScriptDraw.coord(n(a, 1)), ScriptDraw.coord(n(a, 2)), color, b(a, 4));
            return Value.TRUE;
        });
        def("drawRect", (a, self) -> {
            int w = ScriptDraw.coord(n(a, 2)), h = ScriptDraw.coord(n(a, 3));
            if (w <= 0 || h <= 0) return Value.FALSE;
            net.minecraft.client.gui.DrawContext ctx = self == null ? null : self.drawContext(1);
            if (ctx == null) return Value.FALSE;
            int x = ScriptDraw.coord(n(a, 0)), y = ScriptDraw.coord(n(a, 1));
            ctx.fill(x, y, x + w, y + h, a.length > 4 ? ScriptDraw.parseColor(a[4]) : 0xFFFFFFFF);
            return Value.TRUE;
        });
        def("drawOutline", (a, self) -> {
            int w = ScriptDraw.coord(n(a, 2)), h = ScriptDraw.coord(n(a, 3));
            if (w <= 0 || h <= 0) return Value.FALSE;
            net.minecraft.client.gui.DrawContext ctx = self == null ? null : self.drawContext(4);
            if (ctx == null) return Value.FALSE;
            int x = ScriptDraw.coord(n(a, 0)), y = ScriptDraw.coord(n(a, 1));
            int color = a.length > 4 ? ScriptDraw.parseColor(a[4]) : 0xFFFFFFFF;
            int t = a.length > 5 ? (int) MathHelper.clamp(ScriptDraw.coord(n(a, 5)), 1, 16) : 1;
            t = Math.min(t, Math.max(1, Math.min(w, h) / 2));
            ctx.fill(x, y, x + w, y + t, color);
            ctx.fill(x, y + h - t, x + w, y + h, color);
            ctx.fill(x, y + t, x + t, y + h - t, color);
            ctx.fill(x + w - t, y + t, x + w, y + h - t, color);
            return Value.TRUE;
        });
        def("drawLine", (a, self) -> {
            if (self == null || !self.inRenderPass()) return Value.FALSE;
            int x1 = lineCoord(n(a, 0)), y1 = lineCoord(n(a, 1)), x2 = lineCoord(n(a, 2)), y2 = lineCoord(n(a, 3));
            int color = a.length > 4 ? ScriptDraw.parseColor(a[4]) : 0xFFFFFFFF;
            int t = a.length > 5 ? (int) MathHelper.clamp(ScriptDraw.coord(n(a, 5)), 1, 16) : 1;
            int adx = Math.abs(x2 - x1), ady = Math.abs(y2 - y1);
            int steps = Math.max(adx, ady);
            // One fill per run of pixels sharing a minor-axis coordinate, plus a charge for the walk itself.
            int ops = Math.min(adx, ady) + 1 + steps / 64;
            net.minecraft.client.gui.DrawContext ctx = self.drawContext(ops);
            if (ctx == null) return Value.FALSE;
            fillLine(ctx, x1, y1, x2, y2, t, color);
            return Value.TRUE;
        });
        def("textWidth", (a, self) -> {
            if (mc == null || mc.textRenderer == null) return Value.number(0);
            return Value.number(mc.textRenderer.getWidth(ScriptDraw.text(s(a, 0))));
        });
        def("screenWidth", (a, self) -> {
            try {
                return Value.number(mc == null || mc.getWindow() == null ? 0 : mc.getWindow().getScaledWidth());
            } catch (Throwable t) {
                return Value.number(0);
            }
        });
        def("screenHeight", (a, self) -> {
            try {
                return Value.number(mc == null || mc.getWindow() == null ? 0 : mc.getWindow().getScaledHeight());
            } catch (Throwable t) {
                return Value.number(0);
            }
        });
    }

    /** Line endpoints are clamped tighter than other coordinates so the pixel walk stays short. */
    private static int lineCoord(double d) {
        return (int) MathHelper.clamp(ScriptDraw.coord(d), -4096, 4096);
    }

    /** Draws a line as horizontal/vertical runs of {@code t}-pixel-thick fills. */
    private static void fillLine(net.minecraft.client.gui.DrawContext ctx, int x1, int y1, int x2, int y2, int t, int color) {
        int half = t / 2;
        int adx = Math.abs(x2 - x1), ady = Math.abs(y2 - y1);
        if (adx >= ady) {
            if (x1 > x2) { int tx = x1; x1 = x2; x2 = tx; int ty = y1; y1 = y2; y2 = ty; }
            int steps = x2 - x1;
            int runStart = x1, curY = y1;
            for (int i = 1; i <= steps; i++) {
                int y = (int) Math.round(y1 + (double) (y2 - y1) * i / steps);
                if (y != curY) {
                    ctx.fill(runStart, curY - half, x1 + i, curY - half + t, color);
                    runStart = x1 + i;
                    curY = y;
                }
            }
            ctx.fill(runStart, curY - half, x2 + 1, curY - half + t, color);
        } else {
            if (y1 > y2) { int tx = x1; x1 = x2; x2 = tx; int ty = y1; y1 = y2; y2 = ty; }
            int steps = y2 - y1;
            int runStart = y1, curX = x1;
            for (int i = 1; i <= steps; i++) {
                int x = (int) Math.round(x1 + (double) (x2 - x1) * i / steps);
                if (x != curX) {
                    ctx.fill(curX - half, runStart, curX - half + t, y1 + i, color);
                    runStart = y1 + i;
                    curX = x;
                }
            }
            ctx.fill(curX - half, runStart, curX - half + t, y2 + 1, color);
        }
    }
}
