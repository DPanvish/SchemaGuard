package com.schemaguard.core.policy;

import com.schemaguard.core.policy.PolicyManifest.RuleOverride;
import com.schemaguard.core.rules.Finding;
import com.schemaguard.core.rules.Severity;
import com.schemaguard.core.sandbox.SandboxExecutionResult;
import com.schemaguard.core.sandbox.SandboxExecutionResult.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class PolicyGate {

    private static final Logger log = LoggerFactory.getLogger(PolicyGate.class);

    public record PolicyEvaluationResult(
        DecisionOutcome decision,
        int exitCode,
        int prioritizationScore,
        List<Finding> effectiveFindings,
        List<String> gateRationale
    ) {}

    public PolicyEvaluationResult evaluate(
            PolicyManifest policyManifest,
            List<Finding> rawFindings,
            SandboxExecutionResult sandboxResult,
            boolean hasIncompleteContractCoverage,
            boolean strictMode) {

        PolicyManifest policy = policyManifest != null ? policyManifest : PolicyManifest.defaultPolicy();
        Map<String, RuleOverride> overrides = policy.rules() != null ? policy.rules() : Map.of();

        List<Finding> effectiveFindings = new ArrayList<>();
        List<String> rationale = new ArrayList<>();

        boolean hasBlock = false;
        boolean hasReviewRequired = false;
        boolean hasWarn = false;
        int prioritizationScore = 0;

        // 1. Apply rule overrides and evaluate each finding against policy settings
        if (rawFindings != null) {
            for (Finding f : rawFindings) {
                RuleOverride override = overrides.get(f.ruleId());
                if (override != null && !override.enabled()) {
                    log.debug("Rule {} is disabled by policy; omitting finding on {}", f.ruleId(), f.objectRef());
                    continue;
                }

                Severity effectiveSeverity = (override != null && override.severity() != null)
                        ? override.severity() : f.severity();

                Finding adjusted = new Finding(
                        f.ruleId(), effectiveSeverity, f.title(), f.message(),
                        f.objectRef(), f.sourceLocation(), f.evidence(), f.remediation(), f.confidenceMarker()
                );
                effectiveFindings.add(adjusted);
                prioritizationScore += scoreForSeverity(effectiveSeverity);

                // Check policy-level hard gates
                if ("SG010".equals(adjusted.ruleId())) {
                    String action = policy.policy().unsupportedSql();
                    if ("block".equalsIgnoreCase(action)) {
                        hasBlock = true;
                        rationale.add("Blocked by unsupportedSql policy (" + adjusted.ruleId() + ")");
                    } else if ("require-review".equalsIgnoreCase(action)) {
                        hasReviewRequired = true;
                        rationale.add("Review required by unsupportedSql policy (" + adjusted.ruleId() + ")");
                    }
                } else if ("SG007".equals(adjusted.ruleId()) && "block".equalsIgnoreCase(policy.policy().migrationHistoryChanges())) {
                    hasBlock = true;
                    rationale.add("Blocked by migrationHistoryChanges policy (SG007 on " + adjusted.objectRef() + ")");
                } else if ("SG008".equals(adjusted.ruleId()) && "block".equalsIgnoreCase(policy.policy().breakingContracts())) {
                    hasBlock = true;
                    rationale.add("Blocked by breakingContracts policy (SG008 on " + adjusted.objectRef() + ")");
                } else if ("SG001".equals(adjusted.ruleId()) && "block".equalsIgnoreCase(policy.policy().destructiveChanges())) {
                    hasBlock = true;
                    rationale.add("Blocked by destructiveChanges policy (SG001 on " + adjusted.objectRef() + ")");
                } else if (effectiveSeverity == Severity.CRITICAL) {
                    hasBlock = true;
                    rationale.add("Blocked due to CRITICAL finding " + adjusted.ruleId() + " on " + adjusted.objectRef());
                } else if (effectiveSeverity == Severity.REVIEW_REQUIRED) {
                    hasReviewRequired = true;
                    rationale.add("Review required due to finding " + adjusted.ruleId() + " on " + adjusted.objectRef());
                } else {
                    hasWarn = true;
                }
            }
        }

        // 2. Evaluate Sandbox Execution Result
        if (sandboxResult != null) {
            if (sandboxResult.status() == Status.FAILED) {
                hasBlock = true;
                prioritizationScore += 100;
                rationale.add("Blocked because isolated sandbox execution failed in "
                        + sandboxResult.failedMigrationFile() + ": " + sandboxResult.sanitizedErrorMessage());
            } else if (sandboxResult.status() == Status.TIMED_OUT) {
                hasReviewRequired = true;
                prioritizationScore += 50;
                rationale.add("Review required because sandbox execution timed out (" + sandboxResult.sanitizedErrorMessage() + ")");
            }
        }

        // 3. Evaluate Contract Coverage Completeness
        if (hasIncompleteContractCoverage) {
            hasReviewRequired = true;
            rationale.add("Review required because service contract coverage is incomplete for one or more modified tables");
        }

        // 4. Determine Final Decision Outcome (Hard blocks cannot be outweighed by a low aggregate score)
        DecisionOutcome finalDecision;
        if (hasBlock) {
            finalDecision = DecisionOutcome.BLOCK;
        } else if (hasReviewRequired) {
            finalDecision = DecisionOutcome.REVIEW_REQUIRED;
        } else if (hasWarn) {
            finalDecision = DecisionOutcome.WARN;
            rationale.add("Passed with non-blocking operational or schema warnings");
        } else {
            finalDecision = DecisionOutcome.PASS;
            rationale.add("All required checks completed with zero blocking or review-required findings");
        }

        int exitCode = finalDecision.exitCode(strictMode);
        log.info("Policy gate evaluation complete: decision={}, exitCode={}, findings={}, score={}",
                finalDecision, exitCode, effectiveFindings.size(), prioritizationScore);

        return new PolicyEvaluationResult(
                finalDecision,
                exitCode,
                prioritizationScore,
                List.copyOf(effectiveFindings),
                List.copyOf(rationale)
        );
    }

    private int scoreForSeverity(Severity severity) {
        return switch (severity) {
            case CRITICAL -> 100;
            case HIGH -> 50;
            case REVIEW_REQUIRED -> 40;
            case MEDIUM -> 20;
            case WARN -> 10;
            case LOW -> 5;
        };
    }
}