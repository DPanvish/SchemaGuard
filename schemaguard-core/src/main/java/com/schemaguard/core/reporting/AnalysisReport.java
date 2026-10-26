package com.schemaguard.core.reporting;

import com.schemaguard.core.policy.DecisionOutcome;
import com.schemaguard.core.rules.Finding;
import com.schemaguard.core.sandbox.SandboxExecutionResult;

import java.util.List;

public record AnalysisReport(
    String reportSchemaVersion,
    String runId,
    String toolVersion,
    String baseRef,
    String headRef,
    String baseSnapshotHash,
    String candidateSnapshotHash,
    DecisionOutcome decision,
    int exitCode,
    int prioritizationScore,
    String sandboxSummary,
    SandboxExecutionResult sandboxResult,
    List<String> gateRationale,
    List<Finding> findings
) {}