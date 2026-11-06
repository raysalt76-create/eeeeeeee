package interstellarTikiTiks.apexx.client.network;

public enum PingEqualizerFlushBatch {
    NONE(false, false),
    INBOUND(true, false),
    OUTBOUND(false, true),
    BOTH(true, true);

    private final boolean inbound;
    private final boolean outbound;

    PingEqualizerFlushBatch(boolean inbound, boolean outbound) {
        this.inbound = inbound;
        this.outbound = outbound;
    }

    public boolean hasActionsToFlush() {
        return inbound || outbound;
    }

    public boolean shouldFlushInbound() {
        return inbound;
    }

    public boolean shouldFlushOutbound() {
        return outbound;
    }

    public static PingEqualizerFlushBatch fromFlags(boolean inbound, boolean outbound) {
        if (inbound && outbound) return BOTH;
        if (inbound) return INBOUND;
        if (outbound) return OUTBOUND;
        return NONE;
    }
}
