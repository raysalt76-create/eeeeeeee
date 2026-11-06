package interstellarTikiTiks.apexx.client.checker;

public record AcViolation(
        int tier,
        String antiCheat,
        String checkName,
        String description,
        int tick,
        long timestampNs,
        double metricValue
) {
    @Override
    public String toString() {
        return String.format("[Tier %d | %s] %s flagged on Tick %d (metric: %.4f): %s",
                tier, antiCheat, checkName, tick, metricValue, description);
    }
}
