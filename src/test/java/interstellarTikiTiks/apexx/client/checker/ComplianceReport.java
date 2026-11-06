package interstellarTikiTiks.apexx.client.checker;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

public class ComplianceReport {
    private final List<AcViolation> violations;

    public ComplianceReport(List<AcViolation> violations) {
        this.violations = Collections.unmodifiableList(violations);
    }

    public boolean isClean() {
        return violations.isEmpty();
    }

    public int violationCount() {
        return violations.size();
    }

    public List<AcViolation> getViolations() {
        return violations;
    }

    public void assertClean() {
        if (!violations.isEmpty()) {
            throw new AssertionError("Compliance Harness detected anti-cheat violations:\n" + formatReport());
        }
    }

    public String formatReport() {
        if (violations.isEmpty()) {
            return "=== Apexx Compliance Report: 100% CLEAN (Zero Violations across all 6 tiers) ===";
        }

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("=== Apexx Compliance Report: %d VIOLATION(S) DETECTED ===\n", violations.size()));

        Map<Integer, List<AcViolation>> byTier = violations.stream()
                .collect(Collectors.groupingBy(AcViolation::tier, TreeMap::new, Collectors.toList()));

        for (var entry : byTier.entrySet()) {
            int tier = entry.getKey();
            List<AcViolation> tierViolations = entry.getValue();
            String acName = tierViolations.get(0).antiCheat();

            sb.append(String.format("\n[Tier %d: %s] - %d violation(s):\n", tier, acName, tierViolations.size()));
            for (AcViolation v : tierViolations) {
                sb.append(String.format("  • Tick %d [%s]: %s (metric: %.4f)\n",
                        v.tick(), v.checkName(), v.description(), v.metricValue()));
            }
        }
        sb.append("\n=========================================================================");
        return sb.toString();
    }
}
