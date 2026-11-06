package interstellarTikiTiks.apexx.client.checker;

import java.util.ArrayList;
import java.util.List;

/**
 * Apexx Master Compliance Test Harness
 * Unified 6-tier anti-cheat verification engine combining:
 * 1. Grim 2.0 (Packet order FSM, transactions, rewind reach)
 * 2. Vulcan 2.9.7 (Autoclicker CPS, FastSwitch, Timer)
 * 3. Matrix 1.21.10 (80-sample rotation window, local pre-filter, reach)
 * 4. MXAc (AimFactor snap-and-freeze, AimPattern second derivatives, sensitivity GCD)
 * 5. Intave (RotationSnap, Silent Movement, ToolSwitch, NoSwing)
 * 6. AntiGamingChair (Strict tick cadence, wire-authoritative raycast, angle retention)
 */
public class ComplianceHarness {
    private final List<AcValidator> validators = new ArrayList<>();
    private long virtualClockNs = 1_000_000_000L; // 1s baseline

    private int activeTick = 0;
    private int activeForward = 0;
    private int activeStrafe = 0;
    private int activeSlot = 0;

    public ComplianceHarness() {
        validators.add(new GrimValidator());
        validators.add(new VulcanValidator());
        validators.add(new MatrixValidator());
        validators.add(new MxacValidator());
        validators.add(new IntaveValidator());
        validators.add(new AgcValidator());
    }

    public static ComplianceHarness strict() {
        return new ComplianceHarness();
    }

    public void advanceTimeMs(long ms) {
        this.virtualClockNs += ms * 1_000_000L;
    }

    public void advanceTimeNs(long ns) {
        this.virtualClockNs += ns;
    }

    public long getVirtualClockNs() {
        return virtualClockNs;
    }

    public void startTick(int tick) {
        this.activeTick = tick;
        for (AcValidator v : validators) {
            v.onTickStart(tick, virtualClockNs);
        }
    }

    public void recordKeyInput(int forward, int strafe) {
        this.activeForward = forward;
        this.activeStrafe = strafe;
        dispatch(new WireFrame(activeTick, virtualClockNs, WireAction.KEY_INPUT,
                activeSlot, 0f, 0f, 0, 0, 0, true, 0.0, -1, forward, strafe));
    }

    public void recordSlotChange(int targetSlot) {
        this.activeSlot = targetSlot;
        dispatch(new WireFrame(activeTick, virtualClockNs, WireAction.SLOT_CHANGE,
                targetSlot, 0f, 0f, 0, 0, 0, true, 0.0, -1, activeForward, activeStrafe));
    }

    public void recordSwing(float yaw, float pitch) {
        dispatch(new WireFrame(activeTick, virtualClockNs, WireAction.SWING,
                activeSlot, yaw, pitch, 0, 0, 0, true, 0.0, -1, activeForward, activeStrafe));
    }

    public void recordInteractAt(int entityId, double targetDist) {
        dispatch(new WireFrame(activeTick, virtualClockNs, WireAction.INTERACT_AT,
                activeSlot, 0f, 0f, 0, 0, 0, true, targetDist, entityId, activeForward, activeStrafe));
    }

    public void recordInteract(int entityId, double targetDist) {
        dispatch(new WireFrame(activeTick, virtualClockNs, WireAction.INTERACT,
                activeSlot, 0f, 0f, 0, 0, 0, true, targetDist, entityId, activeForward, activeStrafe));
    }

    public void recordAttack(int entityId, double targetDistance, float yaw, float pitch) {
        dispatch(new WireFrame(activeTick, virtualClockNs, WireAction.ATTACK,
                activeSlot, yaw, pitch, 0, 0, 0, true, targetDistance, entityId, activeForward, activeStrafe));
    }

    public void recordUseItem() {
        dispatch(new WireFrame(activeTick, virtualClockNs, WireAction.USE_ITEM,
                activeSlot, 0f, 0f, 0, 0, 0, true, 0.0, -1, activeForward, activeStrafe));
    }

    public void recordBlockPlace(double x, double y, double z) {
        dispatch(new WireFrame(activeTick, virtualClockNs, WireAction.BLOCK_PLACE,
                activeSlot, 0f, 0f, x, y, z, true, 0.0, -1, activeForward, activeStrafe));
    }

    public void recordBlockDigStart(double x, double y, double z) {
        dispatch(new WireFrame(activeTick, virtualClockNs, WireAction.BLOCK_DIG_START,
                activeSlot, 0f, 0f, x, y, z, true, 0.0, -1, activeForward, activeStrafe));
    }

    public void recordBlockDigStop(double x, double y, double z) {
        dispatch(new WireFrame(activeTick, virtualClockNs, WireAction.BLOCK_DIG_STOP,
                activeSlot, 0f, 0f, x, y, z, true, 0.0, -1, activeForward, activeStrafe));
    }

    public void recordMovement(double x, double y, double z, float yaw, float pitch, boolean onGround) {
        dispatch(new WireFrame(activeTick, virtualClockNs, WireAction.MOVEMENT,
                activeSlot, yaw, pitch, x, y, z, onGround, 0.0, -1, activeForward, activeStrafe));
    }

    public void endTick(int tick) {
        for (AcValidator v : validators) {
            v.onTickEnd(tick);
        }
    }

    private void dispatch(WireFrame frame) {
        for (AcValidator v : validators) {
            v.onAction(frame);
        }
    }

    public ComplianceReport evaluate() {
        List<AcViolation> allViolations = new ArrayList<>();
        for (AcValidator v : validators) {
            allViolations.addAll(v.getViolations());
        }
        return new ComplianceReport(allViolations);
    }

    public void reset() {
        virtualClockNs = 1_000_000_000L;
        activeTick = 0;
        activeForward = 0;
        activeStrafe = 0;
        activeSlot = 0;
        for (AcValidator v : validators) {
            v.reset();
        }
    }
}
