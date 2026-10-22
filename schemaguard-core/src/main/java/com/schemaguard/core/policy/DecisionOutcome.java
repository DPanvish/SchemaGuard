package com.schemaguard.core.policy;

public enum DecisionOutcome {
    PASS(0, "Required checks completed and no configured blocking findings exist."),
    WARN(0, "Findings exist, but active policy permits the change with a warning."),
    BLOCK(1, "One or more findings violate hard policy."),
    REVIEW_REQUIRED(3, "Critical evidence is missing or analysis is incomplete under strict mode.");

    private final int strictExitCode;
    private final String description;

    DecisionOutcome(int strictExitCode, String description) {
        this.strictExitCode = strictExitCode;
        this.description = description;
    }

    public int exitCode(boolean strictMode) {
        if (this == REVIEW_REQUIRED && !strictMode) {
            return 0; // Exploratory local mode may permit review-required while disclosing uncertainty
        }
        return strictExitCode;
    }

    public String description() {
        return description;
    }
}