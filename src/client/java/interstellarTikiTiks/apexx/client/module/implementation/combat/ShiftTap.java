package interstellarTikiTiks.apexx.client.module.implementation.combat;

import interstellarTikiTiks.apexx.client.friend.FriendManager;
import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import interstellarTikiTiks.apexx.client.module.hooks.IncomingPacketInterceptor;
import interstellarTikiTiks.apexx.client.module.hooks.RenderFrameAware;
import interstellarTikiTiks.apexx.client.util.SystemInputSimulator;
import interstellarTikiTiks.apexx.client.util.combat.MeleeMechanics;
import interstellarTikiTiks.apexx.client.util.combat.ModuleDelays;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.AttributeModifiersComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.EntityAnimationS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityDamageS2CPacket;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Taps sneak in the tick you take a full-strength melee hit from a player.
 *
 * <p><b>What counts as a full hit.</b> The server's damage packet is the only proof a hit landed (a
 * spam click inside your hurt-invulnerability that adds nothing sends none). On top of it, a hit is
 * treated as full only when:
 * <ul>
 *   <li>it is melee: the damage cause and the direct source are the same entity (no projectiles);</li>
 *   <li>the attacker's attack had recharged: the time since that attacker's previous swing (or previous
 *       hit on you) is at least Min Charge % of the cooldown of the weapon they hold (20 / attack speed
 *       ticks). The swing animation of the hit itself arrives after its damage packet, so the recorded
 *       swing is the one before it;</li>
 *   <li>your health + absorption dropped by at least Min Damage between the last tick and this one;</li>
 *   <li>it is not inside the invulnerability window (10 ticks) of the previous counted hit.</li>
 * </ul>
 *
 * <p>Packets are read on the network thread and only queued; they are evaluated at tick HEAD, after the
 * client handled them and before the player's movement tick, so a 0 ms reaction sneaks in that same tick.
 * Sneak is a simulated key hold owned by this module and released by the shared owner cleanup.
 */
public class ShiftTap extends Module implements IncomingPacketInterceptor, RenderFrameAware, interstellarTikiTiks.apexx.client.module.hooks.ClickSimulating {

    private static final int HURT_INVULNERABLE_TICKS = 10;
    private static final int MAX_QUEUED = 64;

    private final BooleanSetting playersOnly = new BooleanSetting("Players Only", true)
            .setDescription("Only react to hits from players. Off: mob melee hits count too.");
    private final BooleanSetting ignoreFriends = new BooleanSetting("Ignore Friends", true)
            .setDescription("Do not react to hits from players on your friend list.");
    private final SliderSetting minDamage = new SliderSetting("Min Damage", 2.5, 0.0, 20.0, 1)
            .setDescription("Least health (incl. absorption) a hit must remove to count. 0 = any damaging hit.");
    private final SliderSetting minCharge = new SliderSetting("Min Charge %", 90, 0, 100, 0)
            .setDescription("How recharged the attacker's weapon must be, estimated from the time since their previous swing. Spam clicks fall below this and are ignored.");
    private final BooleanSetting onlyGround = new BooleanSetting("Only On Ground", false)
            .setDescription("Only tap while standing on the ground.");
    private final BooleanSetting disableInLiquids = new BooleanSetting("Disable In Liquids", false)
            .setDescription("Never shift-tap while you are in water or lava; a tap still held when you enter them is released. Only On Ground is the matching air gate.");

    private final SliderSetting reactionDelay = new SliderSetting("Reaction Delay (ms)", 5, 0, 300, 0).withJitter(3)
            .setDescription("Wait after the hit before pressing sneak. 0 sneaks in the same tick the hit is processed.")
            .setSection("Delays");
    private final SliderSetting holdDuration = new SliderSetting("Hold Duration (ms)", 90, 0, 1000, 0).withJitter(20)
            .setDescription("How long sneak is held for each tap.")
            .setSection("Delays");
    private final SliderSetting tapCooldown = new SliderSetting("Tap Cooldown (ms)", 350, 0, 2000, 0).withJitter(50)
            .setDescription("Least time between two taps, so a burst of hits does not chain crouches.")
            .setSection("Delays");

    private record NetEvent(boolean damage, int entityId, long timeMs) { }

    private final ConcurrentLinkedQueue<NetEvent> events = new ConcurrentLinkedQueue<>();
    private final Map<Integer, Long> lastSwingMs = new HashMap<>();
    private final Map<Integer, Long> lastHitMs = new HashMap<>();

    private float lastHealthPool = -1f;
    private int lastCountedHitAge = Integer.MIN_VALUE;
    private long pressAtMs = -1L;
    private long releaseAtMs = -1L;
    private long nextTapAllowedMs;
    private boolean holding;

    public ShiftTap() {
        super("ShiftTap", "Crouches for a moment in the same tick you take a full-strength melee hit from a player, the shift-tap technique for reducing knockback. It ignores spam-click hits that are not fully charged, hits during invulnerability frames and anything below the minimum damage, so you never crouch-spam.", Category.COMBAT);
        addSetting(playersOnly);
        addSetting(ignoreFriends);
        addSetting(minDamage);
        addSetting(minCharge);
        addSetting(onlyGround);
        addSetting(disableInLiquids);
        addSetting(reactionDelay);
        addSetting(holdDuration);
        addSetting(tapCooldown);
    }

    @Override
    public void onEnable() {
        clearState();
    }

    @Override
    public void onDisable() {
        // Let go of a tap still being held before forgetting it, or sneak would stay pressed.
        if (holding) SystemInputSimulator.releaseSneak();
        clearState();
    }

    @Override
    protected void onRuntimeStateReleased() {
        if (holding) SystemInputSimulator.releaseSneak();
        clearState();
    }

    private void clearState() {
        events.clear();
        lastSwingMs.clear();
        lastHitMs.clear();
        lastHealthPool = -1f;
        lastCountedHitAge = Integer.MIN_VALUE;
        pressAtMs = -1L;
        releaseAtMs = -1L;
        nextTapAllowedMs = 0L;
        holding = false;
    }

    @Override
    public boolean handleIncomingPacket(Packet<?> packet) {
        try {
            if (mc.player == null) return false;
            long now = System.currentTimeMillis();
            if (packet instanceof EntityDamageS2CPacket damage) {
                int me = mc.player.getId();
                // Source ids arrive already decoded (vanilla readOffsetVarInt): the entity id, or -1 for none.
                // Melee only: cause == direct source.
                if (damage.entityId() == me && damage.sourceCauseId() >= 0
                        && damage.sourceCauseId() == damage.sourceDirectId()) {
                    queue(new NetEvent(true, damage.sourceCauseId(), now));
                }
            } else if (packet instanceof EntityAnimationS2CPacket anim
                    && anim.getAnimationId() == EntityAnimationS2CPacket.SWING_MAIN_HAND) {
                queue(new NetEvent(false, anim.getEntityId(), now));
            }
        } catch (RuntimeException ignored) {
            // A packet observer must never disturb packet handling.
        }
        return false;
    }

    private void queue(NetEvent event) {
        while (events.size() >= MAX_QUEUED) events.poll();
        events.add(event);
    }

    @Override
    public void onTick() {
        if (mc.player == null || mc.world == null) {
            clearState();
            return;
        }
        float pool = mc.player.getHealth() + mc.player.getAbsorptionAmount();
        float lost = lastHealthPool < 0f ? 0f : lastHealthPool - pool;
        lastHealthPool = pool;

        long now = System.currentTimeMillis();
        boolean trigger = false;
        NetEvent event;
        while ((event = events.poll()) != null) {
            if (!event.damage()) {
                lastSwingMs.put(event.entityId(), event.timeMs());
                continue;
            }
            if (isFullHit(event, lost)) trigger = true;
            lastHitMs.put(event.entityId(), event.timeMs());
        }
        if (trigger) {
            lastCountedHitAge = mc.player.age;
            if (now >= nextTapAllowedMs && !holding && pressAtMs < 0L
                    && (!onlyGround.isEnabled() || mc.player.isOnGround())
                    && !isLiquidGated()) {
                pressAtMs = now + ModuleDelays.clampMs(reactionDelay.sample());
            }
        }
        if (lastSwingMs.size() > 256) lastSwingMs.clear();
        if (lastHitMs.size() > 256) lastHitMs.clear();
        runTimers(now);
    }

    @Override
    public void onRenderFrame() {
        if (mc.player == null || mc.world == null) return;
        runTimers(System.currentTimeMillis());
    }

    private boolean isLiquidGated() {
        return disableInLiquids.isEnabled() && MeleeMechanics.isInLiquid(mc.player);
    }

    private void runTimers(long now) {
        if (pressAtMs >= 0L && now >= pressAtMs) {
            pressAtMs = -1L;
            // The liquid gate is read again at the press: a tap scheduled on land is dropped in water.
            if (mc.currentScreen == null && !isLiquidGated()) {
                SystemInputSimulator.pressSneak();
                holding = true;
                releaseAtMs = now + ModuleDelays.clampMs(holdDuration.sample());
                nextTapAllowedMs = now + ModuleDelays.clampMs(tapCooldown.sample());
            }
        }
        // Entering liquid mid-hold lets go early through the same release path.
        if (holding && (now >= releaseAtMs || isLiquidGated())) {
            SystemInputSimulator.releaseSneak();
            holding = false;
            releaseAtMs = -1L;
        }
    }

    private boolean isFullHit(NetEvent hit, float healthLost) {
        Entity attacker = mc.world.getEntityById(hit.entityId());
        if (attacker == null || attacker == mc.player) return false;
        if (playersOnly.isEnabled() && !(attacker instanceof PlayerEntity)) return false;
        if (ignoreFriends.isEnabled() && attacker instanceof PlayerEntity p
                && FriendManager.getInstance().isFriend(p.getUuid())) return false;
        // MIN_VALUE = no counted hit yet; subtracting it would overflow negative and reject every hit.
        if (lastCountedHitAge != Integer.MIN_VALUE && mc.player.age >= lastCountedHitAge
                && mc.player.age - lastCountedHitAge < HURT_INVULNERABLE_TICKS) return false;
        if (healthLost < minDamage.getValue()) return false;

        long previous = Math.max(lastSwingMs.getOrDefault(hit.entityId(), Long.MIN_VALUE),
                lastHitMs.getOrDefault(hit.entityId(), Long.MIN_VALUE));
        if (previous == Long.MIN_VALUE) return true; // first exchange: nothing says it was a spam click
        double cooldownMs = weaponCooldownMs(attacker);
        return hit.timeMs() - previous >= cooldownMs * (minCharge.getValue() / 100.0);
    }

    /** Full-charge time of the attacker's held weapon: 20 / attack speed ticks (base speed 4). */
    private static double weaponCooldownMs(Entity attacker) {
        double speed = 4.0;
        if (attacker instanceof PlayerEntity player) {
            ItemStack held = player.getMainHandStack();
            AttributeModifiersComponent modifiers =
                    held.getOrDefault(DataComponentTypes.ATTRIBUTE_MODIFIERS, AttributeModifiersComponent.DEFAULT);
            speed = modifiers.applyOperations(EntityAttributes.ATTACK_SPEED, 4.0, EquipmentSlot.MAINHAND);
        }
        if (!(speed > 0.0)) speed = 4.0;
        return 20.0 / speed * 50.0;
    }
}
