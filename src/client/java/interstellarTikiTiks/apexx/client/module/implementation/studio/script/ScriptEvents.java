package interstellarTikiTiks.apexx.client.module.implementation.studio.script;

import interstellarTikiTiks.apexx.client.Apexx;
import interstellarTikiTiks.apexx.client.manager.ModuleManager;
import interstellarTikiTiks.apexx.client.mixin.EntityStatusS2CPacketAccessor;
import interstellarTikiTiks.apexx.client.module.Module;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.RespawnAnchorBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.thrown.EnderPearlEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityDamageS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityStatusS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityVelocityUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ExplosionS2CPacket;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Turns game state into script events. Runs on the client thread once per tick (from
 * {@link ScriptManager}'s END_CLIENT_TICK callback) and diffs snapshots of you, the players around
 * you, nearby entities and anchors. Packet-derived events (damage, totem pops, shield block/disable,
 * knockback, block updates) are captured on the network thread as plain data and dispatched here, so
 * a script never runs off the client thread. Work is only done for events some enabled script
 * listens to.
 */
public final class ScriptEvents {
    private ScriptEvents() { }

    private static final int TOTEM_POP = 35, SHIELD_BLOCK = 29, SHIELD_BREAK = 30;
    private static final int MAX_QUEUE = 512;
    private static final int ANCHOR_RADIUS = 5;
    private static final double DESPAWN_EVENT_RANGE = 48.0;

    private static volatile boolean wantPackets = false;
    private static volatile Object lastPacket;
    private static final ConcurrentLinkedQueue<Object[]> packetQueue = new ConcurrentLinkedQueue<>();
    private static final ConcurrentLinkedQueue<Object[]> chatQueue = new ConcurrentLinkedQueue<>();
    private static boolean chatRegistered = false;

    // Snapshots.
    private static ClientWorld lastWorld;
    private static float lastHealth = -1, lastHealthForHurt = -1;
    private static int lastFood = -1, lastSlot = -1;
    private static boolean lastOnGround = true;
    private static double fallStartY = Double.NaN;
    private static final Map<UUID, PState> players = new HashMap<>();
    private static final Map<Integer, EState> entities = new HashMap<>();
    private static final Map<BlockPos, Integer> anchors = new HashMap<>();
    private static final Map<BlockPos, String> blockNames = new LinkedHashMap<>(256, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<BlockPos, String> e) { return size() > 4096; }
    };

    private record PState(String name, boolean blocking, boolean using, String activeId, String mainId,
                          boolean sprinting, boolean sneaking, boolean swinging, int swingTicks) { }

    private record EState(Entity entity, String type, double x, double y, double z, Entity owner) { }

    /** Called when a script is enabled so a fresh tick rebuilds interest without stale queues. */
    static void touch() {
        wantPackets = true;
    }

    static void registerChat() {
        if (chatRegistered) return;
        chatRegistered = true;
        try {
            ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
                if (!overlay && chatQueue.size() < 128) chatQueue.add(new Object[]{message.getString(), true});
            });
            ClientReceiveMessageEvents.CHAT.register((message, signed, sender, params, ts) -> {
                if (chatQueue.size() < 128) chatQueue.add(new Object[]{message.getString(), false});
            });
        } catch (Throwable ignored) {
            // Message API missing: the chat event simply never fires.
        }
    }

    /** Network thread. Copies the fields scripts need; never touches the world. */
    static void observePacket(Packet<?> packet) {
        if (!wantPackets || packet == lastPacket) return;
        lastPacket = packet;
        if (packetQueue.size() >= MAX_QUEUE) return;
        try {
            if (wantReceiveTypes) packetQueue.add(new Object[]{"recv", packetType(packet)});
            if (packet instanceof ExplosionS2CPacket) packetQueue.add(new Object[]{"explosion"});
            if (packet instanceof EntityDamageS2CPacket d) {
                // The packet's source ids are already decoded (readOffsetVarInt): the entity id, or -1 for none.
                packetQueue.add(new Object[]{"dmg", d.entityId(), d.sourceCauseId(), d.sourceDirectId()});
            } else if (packet instanceof EntityStatusS2CPacket s) {
                byte status = s.getStatus();
                if (status == TOTEM_POP || status == SHIELD_BLOCK || status == SHIELD_BREAK) {
                    packetQueue.add(new Object[]{"status", ((EntityStatusS2CPacketAccessor) s).getEntityId(), (int) status});
                }
            } else if (packet instanceof EntityVelocityUpdateS2CPacket v) {
                packetQueue.add(new Object[]{"vel", v.getEntityId()});
            } else if (packet instanceof BlockUpdateS2CPacket b) {
                packetQueue.add(new Object[]{"block", b.getPos().toImmutable(), b.getState()});
            }
        } catch (Throwable ignored) {
        }
    }

    /** Any thread: an outgoing packet. Only its type id is queued; observe-only. */
    static void observeSent(Packet<?> packet) {
        if (!wantSendTypes || packet == lastSentPacket) return;
        lastSentPacket = packet;
        if (packetQueue.size() >= MAX_QUEUE) return;
        try {
            packetQueue.add(new Object[]{"sent", packetType(packet)});
        } catch (Throwable ignored) {
        }
    }

    private static volatile boolean wantReceiveTypes = false, wantSendTypes = false;
    private static volatile Object lastSentPacket;

    /** A stable packet id such as "player_action" (class names are obfuscated at runtime). */
    private static String packetType(Packet<?> packet) {
        try {
            return packet.getPacketType().id().getPath();
        } catch (Throwable t) {
            return "unknown";
        }
    }

    // ── per tick ────────────────────────────────────────────────────────────

    /** Client thread, END_CLIENT_TICK. */
    static void tick(MinecraftClient mc) {
        List<ScriptModule> scripts = enabledScripts();
        if (scripts.isEmpty()) {
            wantPackets = false;
            wantReceiveTypes = false;
            wantSendTypes = false;
            packetQueue.clear();
            chatQueue.clear();
            // Drop the snapshots so re-enabling a script diffs against fresh state, not stale values
            // (which would fire spurious health/hunger/slot/join/spawn/land events).
            players.clear();
            entities.clear();
            anchors.clear();
            blockNames.clear();
            lastHealth = lastHealthForHurt = -1;
            lastFood = lastSlot = -1;
            fallStartY = Double.NaN;
            lastOnGround = mc.player == null || mc.player.isOnGround();
            return;
        }
        Set<String> interest = new HashSet<>();
        for (ScriptModule s : scripts) interest.addAll(s.listenedEvents());
        wantPackets = interest.contains("hurt") || interest.contains("damage") || interest.contains("totem_pop")
                || interest.contains("shield_block") || interest.contains("shield_disabled")
                || interest.contains("knockback") || interest.contains("block_placed") || interest.contains("block_broken")
                || interest.contains("explosion") || interest.contains("packet_receive");
        wantReceiveTypes = interest.contains("packet_receive");
        wantSendTypes = interest.contains("packet_send");
        if (interest.contains("chat")) registerChat();

        if (mc.player == null || mc.world == null) {
            lastWorld = null;
            packetQueue.clear();
            return;
        }

        if (mc.world != lastWorld) {
            resetSnapshots();
            lastWorld = mc.world;
            String dim;
            try { dim = mc.world.getRegistryKey().getValue().toString(); } catch (Throwable t) { dim = ""; }
            fire(scripts, "world_change", Value.rec().str("dimension", dim).build());
            lastHealth = lastHealthForHurt = mc.player.getHealth();
            lastFood = mc.player.getHungerManager().getFoodLevel();
            lastSlot = mc.player.getInventory().getSelectedSlot();
            lastOnGround = mc.player.isOnGround();
        }

        drainPackets(mc, scripts, interest);
        drainChat(scripts, interest);
        selfDiffs(mc, scripts, interest);
        if (wantsAny(interest, "shield_raise", "shield_lower", "swing", "use_start", "use_stop", "item_switch",
                "sprint_change", "sneak_change", "player_join", "player_leave")) {
            playerDiffs(mc, scripts, interest);
        }
        if (wantsAny(interest, "entity_spawn", "entity_despawn", "pearl_thrown", "pearl_landed",
                "crystal_placed", "crystal_exploded")) {
            entityDiffs(mc, scripts, interest);
        } else {
            entities.clear();
        }
        if (wantsAny(interest, "anchor_charged", "anchor_exploded")) {
            anchorDiffs(mc, scripts);
        } else {
            anchors.clear();
        }
    }

    private static void resetSnapshots() {
        players.clear();
        entities.clear();
        anchors.clear();
        blockNames.clear();
        packetQueue.clear();
        fallStartY = Double.NaN;
    }

    private static boolean wantsAny(Set<String> interest, String... names) {
        for (String n : names) if (interest.contains(n)) return true;
        return false;
    }

    private static void drainPackets(MinecraftClient mc, List<ScriptModule> scripts, Set<String> interest) {
        int me = mc.player.getId();
        Object[] p;
        int budget = MAX_QUEUE;
        while (budget-- > 0 && (p = packetQueue.poll()) != null) {
            switch ((String) p[0]) {
                case "dmg" -> {
                    int target = (Integer) p[1], cause = (Integer) p[2], direct = (Integer) p[3];
                    Entity victim = mc.world.getEntityById(target);
                    Entity attacker = cause >= 0 ? mc.world.getEntityById(cause) : null;
                    boolean melee = cause >= 0 && cause == direct;
                    if (interest.contains("damage")) {
                        fire(scripts, "damage", Value.rec().ent("entity", victim).ent("attacker", attacker)
                                .bool("melee", melee).bool("self", target == me).build());
                    }
                    if (target == me && interest.contains("hurt")) {
                        float now = mc.player.getHealth() + mc.player.getAbsorptionAmount();
                        float amount = Math.max(0f, lastHealthForHurt - now);
                        String source = attacker == null ? "other" : attacker instanceof PlayerEntity ? "player" : "mob";
                        fire(scripts, "hurt", Value.rec().ent("attacker", attacker).num("amount", amount)
                                .str("source", source).bool("melee", melee).build());
                    }
                }
                case "status" -> {
                    int id = (Integer) p[1], status = (Integer) p[2];
                    Entity e = mc.world.getEntityById(id);
                    String event = status == TOTEM_POP ? "totem_pop" : status == SHIELD_BLOCK ? "shield_block" : "shield_disabled";
                    if (interest.contains(event)) {
                        fire(scripts, event, Value.rec().ent("player", e).bool("self", id == me).build());
                    }
                }
                case "vel" -> {
                    if ((Integer) p[1] == me && interest.contains("knockback")) {
                        var v = mc.player.getVelocity();
                        fire(scripts, "knockback", Value.rec().num("x", v.x).num("y", v.y).num("z", v.z)
                                .num("strength", Math.sqrt(v.x * v.x + v.z * v.z)).build());
                    }
                }
                case "block" -> {
                    BlockPos pos = (BlockPos) p[1];
                    BlockState state = (BlockState) p[2];
                    String name = blockId(state);
                    String previous = blockNames.put(pos, name);
                    boolean air = state.isAir();
                    String event = air ? "block_broken" : "block_placed";
                    if (interest.contains(event) && (air ? previous != null && !previous.equals("air") : !name.equals(previous))) {
                        fire(scripts, event, Value.rec().num("x", pos.getX()).num("y", pos.getY()).num("z", pos.getZ())
                                .str("block", air ? (previous == null ? "unknown" : previous) : name).build());
                    }
                }
                case "recv" -> {
                    if (interest.contains("packet_receive")) fire(scripts, "packet_receive", Value.rec().str("type", (String) p[1]).build());
                }
                case "sent" -> {
                    if (interest.contains("packet_send")) fire(scripts, "packet_send", Value.rec().str("type", (String) p[1]).build());
                }
                case "explosion" -> {
                    if (interest.contains("explosion")) fire(scripts, "explosion", Value.rec().build());
                }
                default -> { }
            }
        }
        lastHealthForHurt = mc.player.getHealth() + mc.player.getAbsorptionAmount();
    }

    private static void drainChat(List<ScriptModule> scripts, Set<String> interest) {
        Object[] c;
        int budget = 64;
        while (budget-- > 0 && (c = chatQueue.poll()) != null) {
            if (interest.contains("chat")) {
                fire(scripts, "chat", Value.rec().str("message", (String) c[0]).bool("system", (Boolean) c[1]).build());
            }
        }
    }

    private static void selfDiffs(MinecraftClient mc, List<ScriptModule> scripts, Set<String> interest) {
        float health = mc.player.getHealth();
        if (lastHealth >= 0 && health != lastHealth && interest.contains("health_change")) {
            fire(scripts, "health_change", Value.rec().num("old", lastHealth).num("health", health)
                    .num("delta", health - lastHealth).build());
        }
        lastHealth = health;

        int food = mc.player.getHungerManager().getFoodLevel();
        if (lastFood >= 0 && food != lastFood && interest.contains("hunger_change")) {
            fire(scripts, "hunger_change", Value.rec().num("old", lastFood).num("food", food).num("delta", food - lastFood).build());
        }
        lastFood = food;

        int slot = mc.player.getInventory().getSelectedSlot();
        if (lastSlot >= 0 && slot != lastSlot && interest.contains("slot_change")) {
            fire(scripts, "slot_change", Value.rec().num("from", lastSlot).num("to", slot).build());
        }
        lastSlot = slot;

        boolean onGround = mc.player.isOnGround();
        if (lastOnGround && !onGround) {
            fallStartY = mc.player.getY();
            if (interest.contains("fall_start")) fire(scripts, "fall_start", Value.rec().num("y", mc.player.getY()).build());
        } else if (!lastOnGround && onGround) {
            double fall = Double.isNaN(fallStartY) ? 0 : Math.max(0, fallStartY - mc.player.getY());
            fallStartY = Double.NaN;
            if (interest.contains("land")) fire(scripts, "land", Value.rec().num("fall", fall).num("y", mc.player.getY()).build());
        }
        lastOnGround = onGround;
    }

    private static void playerDiffs(MinecraftClient mc, List<ScriptModule> scripts, Set<String> interest) {
        Set<UUID> seen = new HashSet<>();
        boolean firstScan = players.isEmpty();
        for (PlayerEntity p : mc.world.getPlayers()) {
            if (p == null) continue;
            UUID id = p.getUuid();
            seen.add(id);
            boolean self = p == mc.player;
            PState now = new PState(p.getName().getString(), p.isBlocking(), p.isUsingItem(),
                    p.isUsingItem() ? itemId(p.getActiveItem()) : "air", itemId(p.getMainHandStack()),
                    p.isSprinting(), p.isSneaking(), p.handSwinging, p.handSwingTicks);
            PState was = players.put(id, now);
            if (was == null) {
                if (!self && !firstScan && interest.contains("player_join")) {
                    fire(scripts, "player_join", Value.rec().ent("player", p).str("name", now.name()).build());
                }
                continue;
            }
            if (now.blocking() != was.blocking()) {
                String ev = now.blocking() ? "shield_raise" : "shield_lower";
                if (interest.contains(ev)) fire(scripts, ev, Value.rec().ent("player", p).bool("self", self).build());
            }
            if (now.using() != was.using() || (now.using() && !now.activeId().equals(was.activeId()))) {
                if (was.using() && interest.contains("use_stop")) {
                    fire(scripts, "use_stop", Value.rec().ent("player", p).str("item", was.activeId()).bool("self", self).build());
                }
                if (now.using() && interest.contains("use_start")) {
                    ItemStack active = p.getActiveItem();
                    boolean eating = active.contains(DataComponentTypes.FOOD) || now.activeId().contains("potion");
                    fire(scripts, "use_start", Value.rec().ent("player", p).str("item", now.activeId())
                            .bool("self", self).bool("eating", eating).build());
                }
            }
            if (!now.mainId().equals(was.mainId()) && interest.contains("item_switch")) {
                fire(scripts, "item_switch", Value.rec().ent("player", p).str("from", was.mainId())
                        .str("to", now.mainId()).bool("self", self).build());
            }
            if (now.sprinting() != was.sprinting() && interest.contains("sprint_change")) {
                fire(scripts, "sprint_change", Value.rec().ent("player", p).bool("sprinting", now.sprinting()).bool("self", self).build());
            }
            if (now.sneaking() != was.sneaking() && interest.contains("sneak_change")) {
                fire(scripts, "sneak_change", Value.rec().ent("player", p).bool("sneaking", now.sneaking()).bool("self", self).build());
            }
            boolean swingStarted = now.swinging() && (!was.swinging() || now.swingTicks() < was.swingTicks());
            if (swingStarted && interest.contains("swing")) {
                fire(scripts, "swing", Value.rec().ent("player", p).bool("self", self).build());
            }
        }
        for (Iterator<Map.Entry<UUID, PState>> it = players.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, PState> e = it.next();
            if (seen.contains(e.getKey())) continue;
            it.remove();
            if (interest.contains("player_leave") && mc.player != null && !e.getKey().equals(mc.player.getUuid())) {
                fire(scripts, "player_leave", Value.rec().str("name", e.getValue().name()).build());
            }
        }
    }

    private static void entityDiffs(MinecraftClient mc, List<ScriptModule> scripts, Set<String> interest) {
        Set<Integer> seen = new HashSet<>();
        boolean first = entities.isEmpty();
        for (Entity e : mc.world.getEntities()) {
            if (e == null) continue;
            int id = e.getId();
            seen.add(id);
            Entity owner = e instanceof EnderPearlEntity pearl ? pearl.getOwner() : null;
            EState now = new EState(e, Registries.ENTITY_TYPE.getId(e.getType()).getPath(), e.getX(), e.getY(), e.getZ(), owner);
            EState was = entities.put(id, now);
            if (was != null || first) continue;
            if (interest.contains("entity_spawn")) {
                fire(scripts, "entity_spawn", Value.rec().ent("entity", e).str("type", now.type()).build());
            }
            if (e instanceof EnderPearlEntity && interest.contains("pearl_thrown")) {
                fire(scripts, "pearl_thrown", Value.rec().ent("pearl", e).ent("owner", owner)
                        .num("x", e.getX()).num("y", e.getY()).num("z", e.getZ()).bool("self", owner == mc.player).build());
            }
            if (e instanceof EndCrystalEntity && interest.contains("crystal_placed")) {
                fire(scripts, "crystal_placed", Value.rec().ent("crystal", e).num("x", e.getX()).num("y", e.getY())
                        .num("z", e.getZ()).num("distance", mc.player.distanceTo(e)).build());
            }
        }
        for (Iterator<Map.Entry<Integer, EState>> it = entities.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Integer, EState> entry = it.next();
            if (seen.contains(entry.getKey())) continue;
            it.remove();
            EState was = entry.getValue();
            if (interest.contains("entity_despawn")) {
                fire(scripts, "entity_despawn", Value.rec().str("type", was.type())
                        .num("x", was.x()).num("y", was.y()).num("z", was.z()).build());
            }
            // An entity that vanished far away was merely untracked (out of range), not landed/exploded.
            double ddx = was.x() - mc.player.getX(), ddy = was.y() - mc.player.getY(), ddz = was.z() - mc.player.getZ();
            boolean nearby = ddx * ddx + ddy * ddy + ddz * ddz <= DESPAWN_EVENT_RANGE * DESPAWN_EVENT_RANGE;
            if (nearby && was.entity() instanceof EnderPearlEntity && interest.contains("pearl_landed")) {
                fire(scripts, "pearl_landed", Value.rec().ent("owner", was.owner()).num("x", was.x()).num("y", was.y())
                        .num("z", was.z()).bool("self", was.owner() == mc.player).build());
            }
            if (nearby && was.entity() instanceof EndCrystalEntity && interest.contains("crystal_exploded")) {
                double dx = was.x() - mc.player.getX(), dy = was.y() - mc.player.getY(), dz = was.z() - mc.player.getZ();
                fire(scripts, "crystal_exploded", Value.rec().num("x", was.x()).num("y", was.y()).num("z", was.z())
                        .num("distance", Math.sqrt(dx * dx + dy * dy + dz * dz)).build());
            }
        }
    }

    private static void anchorDiffs(MinecraftClient mc, List<ScriptModule> scripts) {
        BlockPos center = mc.player.getBlockPos();
        Map<BlockPos, Integer> now = new HashMap<>();
        BlockPos.Mutable m = new BlockPos.Mutable();
        for (int dx = -ANCHOR_RADIUS; dx <= ANCHOR_RADIUS; dx++) {
            for (int dy = -ANCHOR_RADIUS; dy <= ANCHOR_RADIUS; dy++) {
                for (int dz = -ANCHOR_RADIUS; dz <= ANCHOR_RADIUS; dz++) {
                    m.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    BlockState s = mc.world.getBlockState(m);
                    if (s.isOf(Blocks.RESPAWN_ANCHOR)) now.put(m.toImmutable(), s.get(RespawnAnchorBlock.CHARGES));
                }
            }
        }
        for (Map.Entry<BlockPos, Integer> e : now.entrySet()) {
            Integer before = anchors.get(e.getKey());
            if (before != null && e.getValue() > before) {
                BlockPos p = e.getKey();
                fire(scripts, "anchor_charged", Value.rec().num("x", p.getX()).num("y", p.getY()).num("z", p.getZ())
                        .num("charges", e.getValue()).build());
            }
        }
        for (Map.Entry<BlockPos, Integer> e : anchors.entrySet()) {
            BlockPos p = e.getKey();
            if (now.containsKey(p) || e.getValue() <= 0) continue;
            // Only count it if the anchor's cell is still loaded and in range (not just walked away from).
            if (Math.abs(p.getX() - center.getX()) > ANCHOR_RADIUS - 1 || Math.abs(p.getY() - center.getY()) > ANCHOR_RADIUS - 1
                    || Math.abs(p.getZ() - center.getZ()) > ANCHOR_RADIUS - 1) continue;
            fire(scripts, "anchor_exploded", Value.rec().num("x", p.getX()).num("y", p.getY()).num("z", p.getZ()).build());
        }
        anchors.clear();
        anchors.putAll(now);
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static void fire(List<ScriptModule> scripts, String event, Value ev) {
        for (ScriptModule s : scripts) {
            if (s.isEnabled() && s.listens(event)) s.fire(event, ev);
        }
    }

    static List<ScriptModule> enabledScripts() {
        List<ScriptModule> out = new ArrayList<>();
        Apexx apexx = Apexx.getInstance();
        ModuleManager mm = apexx == null ? null : apexx.getModuleManager();
        if (mm == null) return out;
        for (Module m : mm.getModules()) {
            if (m instanceof ScriptModule s && s.isEnabled()) out.add(s);
        }
        return out;
    }

    static String itemId(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return "air";
        try {
            return Registries.ITEM.getId(stack.getItem()).getPath();
        } catch (Throwable t) {
            return "air";
        }
    }

    private static String blockId(BlockState state) {
        if (state == null || state.isAir()) return "air";
        try {
            return Registries.BLOCK.getId(state.getBlock()).getPath();
        } catch (Throwable t) {
            return "unknown";
        }
    }
}
