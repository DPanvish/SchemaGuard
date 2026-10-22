package com.schemaguard.core.policy;

import com.schemaguard.core.rules.Severity;

import java.util.List;
import java.util.Map;

public record PolicyManifest(
    int version,
    String dialect,
    PolicySettings policy,
    Map<String, RuleOverride> rules,
    ReportSettings reports
) {
    public record PolicySettings(
        String unsupportedSql,
        String destructiveChanges,
        String breakingContracts,
        String migrationHistoryChanges
    ) {}

    public record RuleOverride(
        boolean enabled,
        Severity severity,
        Boolean requireLargeTableProfile
    ) {}

    public record ReportSettings(
        List<String> formats
    ) {}

    public static PolicyManifest defaultPolicy() {
        return new PolicyManifest(
                1,
                "postgresql",
                new PolicySettings("require-review", "block", "block", "block"),
                Map.of(
                        "SG001", new RuleOverride(true, Severity.CRITICAL, null),
                        "SG005", new RuleOverride(true, Severity.HIGH, true),
                        "SG010", new RuleOverride(true, Severity.REVIEW_REQUIRED, null)
                ),
                new ReportSettings(List.of("markdown", "json", "sarif"))
        );
    }
}