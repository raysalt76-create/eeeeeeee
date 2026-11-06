package interstellarTikiTiks.apexx.client.checker;

import java.util.List;

public interface AcValidator {
    int tier();
    String name();

    void onTickStart(int tick, long timestampNs);
    void onAction(WireFrame frame);
    void onTickEnd(int tick);

    List<AcViolation> getViolations();
    void reset();
}
