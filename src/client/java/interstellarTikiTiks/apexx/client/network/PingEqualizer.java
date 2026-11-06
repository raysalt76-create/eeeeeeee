package interstellarTikiTiks.apexx.client.network;

import net.minecraft.network.packet.s2c.query.PingResultS2CPacket;

public class PingEqualizer {

    private static final PingEqualizer INSTANCE = new PingEqualizer();

    private volatile boolean active = false;
    private volatile boolean freezeHoldActive = false;
    private volatile boolean holdInbound = false;
    private volatile boolean holdOutbound = false;

    public static PingEqualizer getInstance() {
        return INSTANCE;
    }

    public boolean isActive() {
        return active;
    }

    public boolean isFreezeHoldActive() {
        return freezeHoldActive;
    }

    public void reset(boolean immediate) {
        this.active = false;
        this.freezeHoldActive = false;
        this.holdInbound = false;
        this.holdOutbound = false;
    }

    public void start(boolean flag, PingEqualizerDelayMode mode, int delay) {
        this.active = flag;
    }

    public void setFlags(boolean holdInbound, boolean holdOutbound) {
        this.holdInbound = holdInbound;
        this.holdOutbound = holdOutbound;
    }

    public void setBypassReply(boolean bypass) {
    }

    public void setFreezeHoldActive(boolean state) {
        this.freezeHoldActive = state;
        if (state) {
            this.active = true;
        }
    }

    public void markFlushNeeded() {
    }

    public void onPingQuerySent(long startTime) {
    }

    public void onPingResult(PingResultS2CPacket pingResult) {
    }
}
