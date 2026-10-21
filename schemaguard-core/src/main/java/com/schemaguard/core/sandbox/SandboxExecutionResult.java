package com.schemaguard.core.sandbox;

import java.util.List;

public record SandboxExecutionResult(
    Status status,
    long durationMs,
    String postgresVersion,
    String failedMigrationFile,
    String sqlState,
    String sanitizedErrorMessage,
    List<String> evidence
) {
    public enum Status {
        COMPLETED,
        FAILED,
        TIMED_OUT,
        SKIPPED
    }

    public boolean isSuccess() {
        return status == Status.COMPLETED;
    }
}