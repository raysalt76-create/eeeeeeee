package interstellarTikiTiks.apexx.client.util.combat;

/**
 * The shot sequence: rail, cart, then (safe mode) cover, one interaction per tick.
 *
 * <p>Only ordering lives here. Geometry, rotation and the native clicks stay in the module. A stage
 * may be retried on a later tick while the arrow still allows it; the module decides that deadline.
 * A queued use belongs to the tick that queued it and can never complete in a later one.</p>
 */
public final class AutoCartCycle {
    public enum Stage { IDLE, RAIL, CART, COVER, HOLD, TAKEBACK }

    private Stage stage = Stage.IDLE;
    private long shotTick;
    private long lastActionTick;
    private long queuedTick = Long.MIN_VALUE;
    private int railOffset = -1;
    private int cartOffset = -1;
    private boolean safe;

    public Stage stage() { return stage; }
    public long shotTick() { return shotTick; }
    public boolean safe() { return safe; }
    public int railOffset() { return railOffset; }
    public int cartOffset() { return cartOffset; }
    public boolean active() { return stage != Stage.IDLE && stage != Stage.TAKEBACK; }
    public boolean placing() { return stage == Stage.RAIL || stage == Stage.CART || stage == Stage.COVER; }

    /** Ticks since the release: the arrow has completed this many server moves before this tick's uses. */
    public int offset(long tick) {
        return (int) Math.max(0, Math.min(Integer.MAX_VALUE, tick - shotTick));
    }

    public void begin(long tick, boolean safe) {
        if (stage != Stage.IDLE) throw new IllegalStateException("Cycle already active");
        shotTick = tick;
        lastActionTick = tick;
        queuedTick = Long.MIN_VALUE;
        railOffset = cartOffset = -1;
        this.safe = safe;
        stage = Stage.RAIL;
    }

    /**
     * Tick HEAD bookkeeping. Returns false when the cycle has to end: a use queued in an earlier tick
     * never completed, or the final use's tick is over.
     */
    public boolean tick(long tick) {
        if (placing() && queuedTick != Long.MIN_VALUE && queuedTick < tick) return false;
        return stage != Stage.HOLD || tick <= lastActionTick;
    }

    /** A placement stage may queue once per tick, never in the release tick or with another use. */
    public boolean canQueue(long tick) {
        return placing() && tick > shotTick && tick > lastActionTick && queuedTick != tick;
    }

    public void queued(long tick) {
        if (!canQueue(tick)) throw new IllegalStateException("No placement may be queued at " + tick);
        queuedTick = tick;
    }

    public void accepted(long tick) {
        if (!placing() || queuedTick != tick) throw new IllegalStateException("Stale placement completion");
        queuedTick = Long.MIN_VALUE;
        lastActionTick = tick;
        switch (stage) {
            case RAIL -> {
                railOffset = offset(tick);
                stage = Stage.CART;
            }
            case CART -> {
                cartOffset = offset(tick);
                stage = safe ? Stage.COVER : Stage.HOLD;
            }
            case COVER -> stage = Stage.HOLD;
            default -> throw new IllegalStateException("No placement stage");
        }
    }

    /** A queued use that the module refused at its deadline; the stage may retry next tick. */
    public void withdrawn(long tick) {
        if (queuedTick == tick) queuedTick = Long.MIN_VALUE;
    }

    /** Offset of the previous use (the release counts as offset 0). */
    public int previousOffset() {
        return offset(lastActionTick);
    }

    public void takeback() { stage = Stage.TAKEBACK; }

    public void reset() {
        stage = Stage.IDLE;
        queuedTick = Long.MIN_VALUE;
        railOffset = cartOffset = -1;
        safe = false;
    }
}
