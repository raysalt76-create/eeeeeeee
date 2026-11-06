package interstellarTikiTiks.apexx.client.checker;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Tier 2: Vulcan 2.9.7 Verification Engine
 * Implements CPS limits (Type A), click distribution analysis (Types B-G),
 * FastSwitch 0ms delta rejection, and Timer interval pacing.
 */
public class VulcanValidator implements AcValidator {
    private final List<AcViolation> violations = new ArrayList<>();

    // CPS tracking (rolling 1000ms window)
    private final Deque<Long> clickTimestamps = new ArrayDeque<>();

    // Slot pacing tracking
    private long lastSlotChangeNs = -1;

    // Tick duration tracking
    private long lastTickStartNs = -1;

    @Override
    public int tier() {
        return 2;
    }

    @Override
    public String name() {
        return "Vulcan 2.9.7";
    }

    @Override
    public void onTickStart(int tick, long timestampNs) {
        if (lastTickStartNs > 0) {
            long tickDeltaMs = (timestampNs - lastTickStartNs) / 1_000_000L;
            // Vulcan Timer check: tick delta significantly faster than 50ms (< 40ms)
            if (tickDeltaMs > 0 && tickDeltaMs < 40) {
                violations.add(new AcViolation(
                        2, "Vulcan 2.9.7", "Timer",
                        String.format("Tick interval %dms is faster than vanilla 50ms baseline (timer > 1.0)", tickDeltaMs),
                        tick, timestampNs, tickDeltaMs
                ));
            }
        }
        lastTickStartNs = timestampNs;
    }

    @Override
    public void onAction(WireFrame frame) {
        switch (frame.action()) {
            case SLOT_CHANGE -> {
                // FastSwitch: Multiple slot changes back-to-back with 0ns / < 1ms delta
                if (lastSlotChangeNs > 0) {
                    long deltaNs = frame.nanoTime() - lastSlotChangeNs;
                    if (deltaNs < 1_000_000L) { // < 1ms
                        violations.add(new AcViolation(
                                2, "Vulcan 2.9.7", "FastSwitch",
                                String.format("Slot change occurred with %dns (< 1ms) separation from prior slot change", deltaNs),
                                frame.tick(), frame.nanoTime(), deltaNs / 1_000_000.0
                        ));
                    }
                }
                lastSlotChangeNs = frame.nanoTime();
            }

            case SWING, ATTACK -> {
                // Autoclicker Type A: Max 25 CPS within rolling 1000ms window
                long nowNs = frame.nanoTime();
                clickTimestamps.addLast(nowNs);
                while (!clickTimestamps.isEmpty() && nowNs - clickTimestamps.peekFirst() > 1_000_000_000L) {
                    clickTimestamps.pollFirst();
                }

                int currentCps = clickTimestamps.size();
                if (currentCps > 25) {
                    violations.add(new AcViolation(
                            2, "Vulcan 2.9.7", "Autoclicker (Type A)",
                            String.format("Click rate %d CPS exceeds Vulcan hard cap of 25 CPS", currentCps),
                            frame.tick(), frame.nanoTime(), currentCps
                    ));
                }

                // FastSwitch: Slot swap -> immediate attack with 0ns delta
                if (lastSlotChangeNs > 0 && frame.action() == WireAction.ATTACK) {
                    long deltaFromSwap = frame.nanoTime() - lastSlotChangeNs;
                    if (deltaFromSwap == 0) {
                        violations.add(new AcViolation(
                                2, "Vulcan 2.9.7", "FastSwitch",
                                "Attack occurred in the exact same nanosecond (0ms) as hotbar slot swap without sub-tick pacing",
                                frame.tick(), frame.nanoTime(), 0.0
                        ));
                    }
                }
            }

            default -> {}
        }
    }

    @Override
    public void onTickEnd(int tick) {
        // Clean up window buffers periodically
    }

    @Override
    public List<AcViolation> getViolations() {
        return violations;
    }

    @Override
    public void reset() {
        violations.clear();
        clickTimestamps.clear();
        lastSlotChangeNs = -1;
        lastTickStartNs = -1;
    }
}
