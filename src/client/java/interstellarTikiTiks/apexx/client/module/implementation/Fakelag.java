package interstellarTikiTiks.apexx.client.module.implementation;

import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import interstellarTikiTiks.apexx.client.module.hooks.AttackHandleAware;
import interstellarTikiTiks.apexx.client.module.hooks.OutgoingPacketInterceptor;
import interstellarTikiTiks.apexx.client.module.hooks.PacketQueueFlusher;
import interstellarTikiTiks.apexx.client.theme.AccentState;
import interstellarTikiTiks.apexx.client.util.render.RenderUtils;
import net.minecraft.client.util.BufferAllocator;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractItemC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.awt.Color;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Random;

/**
 * FakeLag — stalls your outgoing movement so the server sees you at a stale position.
 *
 * <h2>What was actually wrong before</h2>
 * The previous implementation was not merely ineffective, it was <b>destructive</b>:
 * {@code handleOutgoingPacket} returned {@code true} — which {@code ClientConnectionMixin}
 * turns into {@code ci.cancel()}, dropping the packet — but <b>never added the packet to the
 * queue</b>. {@code packetQueue} was only ever drained, never filled. So every intercepted
 * interaction (attacks, item use, block place, slot changes, inventory clicks) was silently
 * deleted and never reached the server. It also:
 * <ul>
 *   <li>held <i>interaction</i> packets rather than movement, which is not what fake lag is;</li>
 *   <li>used {@code mc.player.age} (a tick counter that only ever increases) as a "damage
 *       tracker", so Flush On Damage could never fire;</li>
 *   <li>had {@code setHoldActive(boolean)} ignore its argument and always set
 *       {@code isHolding = false}, so calling {@code setHoldActive(true)} turned holding OFF;</li>
 *   <li>shipped a "Show Box" setting that was explicitly ignored.</li>
 * </ul>
 *
 * <h2>How this one works</h2>
 * It holds only {@link PlayerMoveC2SPacket} — the classic blink behaviour, and the safe choice:
 * holding keep-alives, chat or teleport confirmations would break or desync the connection
 * outright. Held packets are really queued and really re-sent, in order, on release. A re-entrancy
 * guard stops the flush from feeding itself back through the interceptor. The box shows where the
 * server currently thinks you are, and follows the ClickGUI accent.
 */
public class Fakelag extends Module implements OutgoingPacketInterceptor, PacketQueueFlusher, AttackHandleAware {

    private static Fakelag instance;

    private final SliderSetting startDistance = new SliderSetting("Start Distance", 6.0, 1.0, 12.0, 1)
            .setDescription("Starts holding back your movement updates once an enemy comes within this many blocks.");
    private final SliderSetting minMs = new SliderSetting("Min Hold (ms)", 200, 0, 1000, 0)
            .setDescription("Shortest time your movement updates are held back before being released. Each hold is drawn randomly between Min and Max Hold.");
    private final SliderSetting maxMs = new SliderSetting("Max Hold (ms)", 400, 0, 1000, 0)
            .setDescription("Longest time your movement updates are held back before being released.");
    private final BooleanSetting flushOnDamage = new BooleanSetting("Flush On Damage", true)
            .setDescription("Releases every held update immediately when you take a hit, so knockback is applied from your real position.");
    private final BooleanSetting flushOnAttack = new BooleanSetting("Flush On Attack", true)
            .setDescription("Releases every held update right before you attack so the server sees you at your true position when the hit lands.");
    private final SliderSetting maxQueued = new SliderSetting("Max Queued", 6, 3, 40, 0)
            .setDescription("Safety cap on held movement packets. Kept low so the gradual release reads as a brief latency spike, not a teleport back.");
    private final SliderSetting catchUpRate = new SliderSetting("Catch-Up Rate", 2, 1, 8, 0)
            .setDescription("Held movement packets released per tick while catching up after a release. 1 = slowest, most latency-like recovery; higher catches up faster (less natural). Your call.");
    private final BooleanSetting showBox = new BooleanSetting("Show Box", true)
            .setDescription("Draw a box where the server currently thinks you are.");

    /** Held packets, in send order. Guarded by {@code this} — the network thread also touches it. */
    private final Deque<Packet<?>> packetQueue = new ArrayDeque<>();
    private final Random random = new Random();

    private boolean isHolding = false;
    /**
     * After a hold ends we do not dump the whole queue at once (that is a rubber-band teleport and
     * the obvious tell). Instead we drain it a couple of packets per tick while still buffering new
     * movement behind it, so the server sees a short, even latency recovery.
     */
    private boolean draining = false;
    /**
     * Re-entrancy guard. {@link #flush()} re-sends through the same {@code ClientConnection.send}
     * path this class intercepts, so without this the flush would immediately re-queue everything
     * it just released and spin.
     */
    private volatile boolean flushing = false;

    private long holdStartedAtMs = 0L;
    private long holdTargetMs = 0L;
    private int lastHurtTime = 0;

    /** Where the last movement packet the server ACTUALLY received put us. */
    private double serverX, serverY, serverZ;
    private boolean serverPosValid = false;

    public Fakelag() {
        // BLATANT: packet choke that fires automatically on proximity, so you do not
        // even choose the moment it becomes visible.
        super("Fakelag", "When an enemy is close, briefly holds back your outgoing movement updates and then releases them, so the server (and the enemy) see you where you were a moment ago. Releases instantly when you attack or get hit. Blatant: servers with lag or movement checks will flag it.", Category.BLATANT);
        addSetting(startDistance);
        addSetting(minMs);
        addSetting(maxMs);
        addSetting(flushOnDamage);
        addSetting(flushOnAttack);
        addSetting(maxQueued);
        addSetting(catchUpRate);
        addSetting(showBox);
        instance = this;
    }

    public static Fakelag getInstance() {
        return instance;
    }

    @Override
    public void onEnable() {
        flush();
        clearLagState();
        lastHurtTime = mc.player != null ? mc.player.hurtTime : 0;
    }

    @Override
    public void onDisable() {
        // Never leave the player's movement stuck in a queue.
        flush();
        clearLagState();
    }

    @Override
    public void onTick() {
        if (mc.player == null || mc.world == null || mc.getNetworkHandler() == null) {
            flush();
            clearLagState();
            return;
        }

        // Real damage detection: hurtTime jumps to its max the tick damage lands, then counts
        // down. A 0 -> non-zero transition is a hit. (The old build watched player.age, which
        // only ever counts up and so never signalled anything.)
        int hurt = mc.player.hurtTime;
        boolean tookDamage = hurt > lastHurtTime;
        lastHurtTime = hurt;

        // A hit means we want our true position on the server immediately — flush now (the queue is
        // small, capped by Max Queued) rather than draining over several ticks.
        if (isHolding && flushOnDamage.isEnabled() && tookDamage) {
            releaseHoldImmediate();
            return;
        }

        if (!mc.player.isAlive()) {
            releaseHoldImmediate();
            return;
        }

        if (isHolding) {
            boolean expired = System.currentTimeMillis() - holdStartedAtMs >= holdTargetMs;
            boolean overCap = queueSize() >= (int) maxQueued.getValue();
            if (expired || overCap || findClosestTarget() == null) {
                // Normal, periodic release: bleed the backlog out gradually so it reads as latency.
                releaseHoldGradual();
            }
            return;
        }

        // Finish draining a previous hold a couple of packets per tick before holding again.
        if (draining) {
            drainStep((int) catchUpRate.getValue());
            if (queueSize() == 0) {
                draining = false;
            }
            return;
        }

        if (findClosestTarget() != null) {
            startHold();
        }
    }

    private void startHold() {
        isHolding = true;
        holdStartedAtMs = System.currentTimeMillis();
        double min = Math.min(minMs.getValue(), maxMs.getValue());
        double max = Math.max(minMs.getValue(), maxMs.getValue());
        holdTargetMs = (long) (min >= max ? min : min + (max - min) * random.nextDouble());
    }

    /** Stop accumulating and start bleeding the backlog out over the next few ticks. */
    private void releaseHoldGradual() {
        isHolding = false;
        draining = queueSize() > 0;
    }

    /** Stop and send everything at once (used for hits, death, disable and world change). */
    private void releaseHoldImmediate() {
        isHolding = false;
        draining = false;
        flush();
    }

    private PlayerEntity findClosestTarget() {
        if (mc.player == null || mc.world == null) return null;
        double maxSq = startDistance.getValue() * startDistance.getValue();
        PlayerEntity closest = null;
        for (PlayerEntity player : mc.world.getPlayers()) {
            if (player == mc.player || !player.isAlive() || player.isSpectator()) continue;
            double sq = mc.player.squaredDistanceTo(player);
            if (sq <= maxSq) {
                closest = player;
                maxSq = sq;
            }
        }
        return closest;
    }

    @Override
    public boolean handleAttack() {
        if (isEnabled() && isHolding && flushOnAttack.isEnabled()) {
            releaseHoldImmediate();
        }
        return false;
    }

    // ── Packet interception ─────────────────────────────────────────────────────

    @Override
    public boolean handleOutgoingPacket(Packet<?> packet) {
        // Let our own flush through untouched, or it would re-queue what it just released.
        if (flushing || !isEnabled()) {
            return false;
        }

        if (isHolding && flushOnAttack.isEnabled() && (packet instanceof PlayerInteractEntityC2SPacket || packet instanceof PlayerInteractItemC2SPacket)) {
            // Let the attack packet proceed this tick with the server still at the held position
            // (the blink), then bleed the movement backlog out a few packets per tick. Dumping the
            // whole queue in one tick is the classic single-tick position burst; a paced drain
            // reads as genuine latency recovery instead. Taking damage / death still flush at once.
            releaseHoldGradual();
            return false;
        }

        // Buffer movement both while actively holding AND while draining a previous hold, so new
        // movement always queues behind the stale backlog and the connection stays in order.
        if (!isHolding && !draining) {
            return false;
        }

        if (!(packet instanceof PlayerMoveC2SPacket move)) {
            return false; // only movement is ever held — see the class javadoc
        }

        synchronized (this) {
            if (packetQueue.size() >= (int) maxQueued.getValue()) {
                // At the cap we stop holding rather than growing without bound; onTick releases.
                return false;
            }
            // Remember the last position the server actually saw, i.e. the one from just BEFORE
            // this first held packet — that's what the box should mark.
            if (packetQueue.isEmpty() && move.changesPosition() && mc.player != null) {
                serverX = move.getX(mc.player.getX());
                serverY = move.getY(mc.player.getY());
                serverZ = move.getZ(mc.player.getZ());
                serverPosValid = true;
            }
            packetQueue.addLast(packet);
        }
        return true; // cancel the real send — we now own this packet and WILL re-send it
    }

    /**
     * Release every held packet, in the order it was produced. Also called by
     * {@code ClientPlayNetworkHandlerMixin} through {@link PacketQueueFlusher} on disconnect /
     * world change, so nothing is ever stranded in the queue.
     */
    @Override
    public void sendQueuedPackets() {
        flush();
    }

    private void flush() {
        ClientPlayNetworkHandler handler = mc.getNetworkHandler();
        Packet<?>[] pending;
        synchronized (this) {
            if (packetQueue.isEmpty()) {
                serverPosValid = false;
                return;
            }
            pending = packetQueue.toArray(new Packet<?>[0]);
            packetQueue.clear();
        }

        flushing = true;
        try {
            if (handler != null) {
                for (Packet<?> p : pending) {
                    handler.sendPacket(p);
                }
            }
        } catch (Throwable ignored) {
            // A failed re-send must not take the game down; the queue is already cleared.
        } finally {
            flushing = false;
            serverPosValid = false;
        }
    }

    /** Send at most {@code n} held packets from the front of the queue, preserving order. */
    private void drainStep(int n) {
        ClientPlayNetworkHandler handler = mc.getNetworkHandler();
        Packet<?>[] batch;
        synchronized (this) {
            if (packetQueue.isEmpty()) {
                serverPosValid = false;
                return;
            }
            int count = Math.min(Math.max(1, n), packetQueue.size());
            batch = new Packet<?>[count];
            for (int i = 0; i < count; i++) {
                batch[i] = packetQueue.pollFirst();
            }
            if (packetQueue.isEmpty()) {
                serverPosValid = false;
            }
        }

        flushing = true;
        try {
            if (handler != null) {
                for (Packet<?> p : batch) {
                    handler.sendPacket(p);
                }
            }
        } catch (Throwable ignored) {
            // A failed re-send must not take the game down; these packets are already dequeued.
        } finally {
            flushing = false;
        }
    }

    private int queueSize() {
        synchronized (this) {
            return packetQueue.size();
        }
    }

    private void clearLagState() {
        isHolding = false;
        draining = false;
        holdStartedAtMs = 0L;
        holdTargetMs = 0L;
        serverPosValid = false;
    }

    // ── Visual ──────────────────────────────────────────────────────────────────

    @Override
    public boolean rendersInWorld() {
        return isEnabled() && showBox != null && showBox.isEnabled() && isHolding && serverPosValid;
    }

    @Override
    public void onWorldRender(MatrixStack matrices, float tickDelta) {
        if (!showBox.isEnabled() || !isHolding || !serverPosValid) return;
        if (mc.player == null || mc.world == null) return;

        Vec3d cameraPos = mc.gameRenderer.getCamera().getCameraPos();
        Box local = mc.player.getBoundingBox()
                .offset(-mc.player.getX(), -mc.player.getY(), -mc.player.getZ());
        Color col = AccentState.accentColor();

        try (BufferAllocator allocator = interstellarTikiTiks.apexx.client.util.render.PooledBufferAllocator.acquire(1024)) {
            VertexConsumerProvider.Immediate immediate = VertexConsumerProvider.immediate(allocator);
            matrices.push();
            matrices.translate(serverX - cameraPos.x, serverY - cameraPos.y, serverZ - cameraPos.z);
            RenderUtils.drawFilledBox(matrices, immediate, local, col, 0.18f);
            RenderUtils.drawBoxOutlineEsp(matrices, immediate, local, col, 0.9f, true);
            matrices.pop();
            immediate.draw();
        }
    }
}
