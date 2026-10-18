package com.schemaguard.core.rules;

public enum Severity {
    CRITICAL(1),
    HIGH(2),
    REVIEW_REQUIRED(3),
    MEDIUM(4),
    WARN(5),
    LOW(6);

    private final int rank;

    Severity(int rank) {
        this.rank = rank;
    }

    public int rank() {
        return rank;
    }
}