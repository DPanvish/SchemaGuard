package com.schemaguard.core.policy;

import com.schemaguard.core.policy.PolicyGate.PolicyEvaluationResult;
import com.schemaguard.core.reporting.AnalysisReport;
import com.schemaguard.core.reporting.ReportGenerator;
import com.schemaguard.core.rules.Finding;
import com.schemaguard.core.rules.Finding.SourceLocation;
import com.schemaguard.core.rules.RemediationTemplates;
import com.schemaguard.core.rules.Severity;
import com.schemaguard.core.sandbox.SandboxExecutionResult;
import com.schemaguard.core.sandbox.SandboxExecutionResult.Status;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class Phase6PolicyAndReportingTest {

    private PolicyLoader policyLoader;
    private PolicyGate policyGate;
    private ReportGenerator reportGenerator;

    @BeforeEach
    void setUp() {
        policyLoader = new PolicyLoader();
        policyGate = new PolicyGate();
        reportGenerator = new ReportGenerator();
    }

    @Test
    void shouldValidatePolicyYamlAndRejectInvalidRuleIdsOrActions() {
        String validYaml = """
                version: 1
                dialect: postgresql
                policy:
                  unsupportedSql: require-review
                  destructiveChanges: block
                  breakingContracts: block
                  migrationHistoryChanges: block
                rules:
                  SG001:
                    enabled: true
                    severity: CRITICAL
                  SG005:
                    enabled: true
                    severity: HIGH
                    requireLargeTableProfile: true
                  SG010:
                    enabled: true
                    severity: REVIEW_REQUIRED
                reports:
                  formats: [markdown, json, sarif]
                """;

        PolicyManifest manifest = policyLoader.loadAndValidateFromString("policy.yaml", validYaml);
        assertEquals(1, manifest.version());
        assertEquals("postgresql", manifest.dialect());

        String invalidRuleYaml = validYaml.replace("SG001:", "SG999:");
        assertThrows(IllegalArgumentException.class, () ->
                policyLoader.loadAndValidateFromString("bad-rule.yaml", invalidRuleYaml));
    }

    @Test
    void shouldEnforceBlockReviewRequiredWarnAndPassDecisionsWithCorrectExitCodes() {
        PolicyManifest policy = PolicyManifest.defaultPolicy();
        SandboxExecutionResult okSandbox = new SandboxExecutionResult(
                Status.COMPLETED, 45L, "16.2", null, null, null, List.of("Sandbox completed")
        );

        // 1. PASS -> Exit Code 0
        PolicyEvaluationResult passResult = policyGate.evaluate(policy, List.of(), okSandbox, false, true);
        assertEquals(DecisionOutcome.PASS, passResult.decision());
        assertEquals(0, passResult.exitCode());

        // 2. REVIEW_REQUIRED (SG010 Unsupported SQL) -> Exit Code 3 in strict mode
        Finding sg010 = new Finding(
                "SG010", Severity.REVIEW_REQUIRED, "Unsupported SQL", "VACUUM FULL is unsupported",
                "public.customers", new SourceLocation("V99__unsupported.sql", 1),
                List.of("Parser status: UNSUPPORTED"), "Request DBA review", "INCOMPLETE_ANALYSIS_COVERAGE"
        );
        PolicyEvaluationResult reviewResult = policyGate.evaluate(policy, List.of(sg010), okSandbox, false, true);
        assertEquals(DecisionOutcome.REVIEW_REQUIRED, reviewResult.decision());
        assertEquals(3, reviewResult.exitCode());

        // 3. BLOCK (SG008 Breaking Contract) -> Exit Code 1
        Finding sg008 = new Finding(
                "SG008", Severity.CRITICAL, "Breaking database contract",
                "Proposed migration removes public.customers.email",
                "public.customers.email", new SourceLocation("db/migration/V12__remove_email.sql", 1),
                List.of("candidate schema removes the column", "billing-api declares a read dependency", "notification-worker declares a read dependency"),
                RemediationTemplates.expandMigrateContractForColumn("public.customers.email"),
                "CONFIRMED_CONTRACT_BREAK"
        );
        PolicyEvaluationResult blockResult = policyGate.evaluate(policy, List.of(sg008), okSandbox, false, true);
        assertEquals(DecisionOutcome.BLOCK, blockResult.decision());
        assertEquals(1, blockResult.exitCode());
    }

    @Test
    void shouldRenderAppendixAMarkdownJsonAndSarif210Reports() {
        Finding sg008 = new Finding(
                "SG008", Severity.CRITICAL, "Breaking database contract",
                "Proposed migration removes public.customers.email",
                "public.customers.email", new SourceLocation("db/migration/V12__remove_email.sql", 1),
                List.of("candidate schema omits column", "billing-api declares a read dependency", "notification-worker declares a read dependency"),
                RemediationTemplates.expandMigrateContractForColumn("public.customers.email"),
                "CONFIRMED_CONTRACT_BREAK"
        );

        AnalysisReport report = new AnalysisReport(
                "1.0",
                "sg-run-2026-10-12-001",
                "1.0-SNAPSHOT",
                "main@base-sha",
                "feature/remove-email@head-sha",
                "base_hash_123",
                "cand_hash_456",
                DecisionOutcome.BLOCK,
                1,
                100,
                "Completed against synthetic fixture; no persistent database used.",
                null,
                List.of("Blocked by breakingContracts policy (SG008 on public.customers.email)"),
                List.of(sg008)
        );

        String markdown = reportGenerator.toMarkdown(report);
        assertTrue(markdown.contains("SCHEMAGUARD: POLICY CHECK FAILED (BLOCK)"));
        assertTrue(markdown.contains("sg-run-2026-10-12-001"));
        assertTrue(markdown.contains("db/migration/V12__remove_email.sql:1"));
        assertTrue(markdown.contains("public.customers.email"));

        String json = reportGenerator.toJson(report);
        assertTrue(json.contains("\"ruleId\" : \"SG008\""));
        assertTrue(json.contains("\"decision\" : \"BLOCK\""));

        String sarif = reportGenerator.toSarif(report);
        assertTrue(sarif.contains("\"version\" : \"2.1.0\""));
        assertTrue(sarif.contains("\"ruleId\" : \"SG008\""));
        assertTrue(sarif.contains("\"uri\" : \"db/migration/V12__remove_email.sql\""));
        assertTrue(sarif.contains("\"startLine\" : 1"));
    }
}