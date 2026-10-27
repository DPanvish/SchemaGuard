package com.schemaguard.core.reporting;

import com.schemaguard.core.dependencies.DependencyGraphBuilder;
import com.schemaguard.core.dependencies.ServiceContractLoader;
import com.schemaguard.core.diff.SchemaDiffEngine;
import com.schemaguard.core.parser.JSqlParserAdapter;
import com.schemaguard.core.parser.MigrationChecksumService;
import com.schemaguard.core.parser.MigrationHistoryValidator;
import com.schemaguard.core.policy.DecisionOutcome;
import com.schemaguard.core.policy.PolicyGate;
import com.schemaguard.core.policy.PolicyLoader;
import com.schemaguard.core.reporting.SchemaGuardAnalysisOrchestrator.AnalysisRequest;
import com.schemaguard.core.rules.RuleEngine;
import com.schemaguard.core.rules.StandardRiskRules;
import com.schemaguard.core.sandbox.SandboxRunner;
import com.schemaguard.core.schema.CanonicalSchemaNormalizer;
import com.schemaguard.core.schema.PostgresCatalogSnapshotter;
import com.schemaguard.core.schema.SchemaStateReconstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class Phase6EndToEndWorkflowTest {

    private SchemaGuardAnalysisOrchestrator orchestrator;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        CanonicalSchemaNormalizer normalizer = new CanonicalSchemaNormalizer();
        StandardRiskRules rules = new StandardRiskRules();
        RuleEngine ruleEngine = new RuleEngine(List.of(
                rules.sg001DropTableOrColumnRule(),
                rules.sg002RenameCandidateRule(),
                rules.sg003IncompatibleTypeChangeRule(),
                rules.sg004UnsafeNotNullRule(),
                rules.sg005NonConcurrentIndexRule(),
                rules.sg006ConstraintLockRule(),
                rules.sg007ModifiedHistoricalMigrationRule(),
                rules.sg008BreakingServiceContractRule(),
                rules.sg009DestructiveDmlRule(),
                rules.sg010UnsupportedSqlScopeRule(),
                rules.sg011SchemaDriftRule(),
                rules.sg012TableRewriteLockRule()
        ));

        orchestrator = new SchemaGuardAnalysisOrchestrator(
                new JSqlParserAdapter(),
                new MigrationChecksumService(),
                new MigrationHistoryValidator(),
                new SchemaStateReconstructor(normalizer, new PostgresCatalogSnapshotter(normalizer)),
                new SchemaDiffEngine(),
                new ServiceContractLoader(),
                new DependencyGraphBuilder(),
                ruleEngine,
                new SandboxRunner(),
                new PolicyLoader(),
                new PolicyGate(),
                new ReportGenerator()
        );
    }

    @Test
    void shouldPassSafeAdditiveMigrationAndBlockBreakingColumnDropEndToEnd() throws IOException {
        Path baseDir = Files.createDirectories(tempDir.resolve("base"));
        Path safeCandDir = Files.createDirectories(tempDir.resolve("safe-cand"));
        Path unsafeCandDir = Files.createDirectories(tempDir.resolve("unsafe-cand"));
        Path unsupportedCandDir = Files.createDirectories(tempDir.resolve("unsupported-cand"));
        Path configDir = Files.createDirectories(tempDir.resolve("config"));
        Path reportsDir = Files.createDirectories(tempDir.resolve("reports"));

        // 1. Write baseline V1 migration
        Files.writeString(baseDir.resolve("V1__initial_schema.sql"), """
                CREATE TABLE public.customers (
                    id UUID PRIMARY KEY,
                    email TEXT NOT NULL,
                    name TEXT
                );
                """);

        // 2. Write safe additive V2 migration
        Files.writeString(safeCandDir.resolve("V2__add_preferred_language.sql"), """
                ALTER TABLE public.customers ADD COLUMN preferred_language TEXT;
                """);

        // 3. Write unsafe contract-breaking V12 migration
        Files.writeString(unsafeCandDir.resolve("V12__remove_email.sql"), """
                ALTER TABLE public.customers DROP COLUMN email;
                """);

        // 4. Write unsupported V99 migration
        Files.writeString(unsupportedCandDir.resolve("V99__unsupported.sql"), """
                VACUUM FULL public.customers;
                """);

        // 5. Write service contracts & policy YAML
        Path contractsFile = configDir.resolve("service-contracts.yaml");
        Files.writeString(contractsFile, """
                version: 1
                services:
                  billing-api:
                    reads:
                      - table: public.customers
                        columns: [id, email]
                  notification-worker:
                    reads:
                      - table: public.customers
                        columns: [email]
                """);

        Path policyFile = configDir.resolve("policy.yaml");
        Files.writeString(policyFile, """
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
                  SG010:
                    enabled: true
                    severity: REVIEW_REQUIRED
                reports:
                  formats: [markdown, json, sarif]
                """);

        // Scenario A: Safe migration -> PASS (Exit Code 0)
        AnalysisReport safeReport = orchestrator.executeAnalysis(new AnalysisRequest(
                "run-safe-001", "main", "feat/add-lang",
                baseDir.toFile(), safeCandDir.toFile(), contractsFile.toFile(), policyFile.toFile(),
                false, true, reportsDir
        ));
        assertEquals(DecisionOutcome.PASS, safeReport.decision());
        assertEquals(0, safeReport.exitCode());
        assertTrue(Files.exists(reportsDir.resolve("run-safe-001/report.md")));
        assertTrue(Files.exists(reportsDir.resolve("run-safe-001/report.json")));
        assertTrue(Files.exists(reportsDir.resolve("run-safe-001/report.sarif")));

        // Scenario B: Unsafe column drop -> BLOCK (Exit Code 1, SG001 + SG008)
        AnalysisReport unsafeReport = orchestrator.executeAnalysis(new AnalysisRequest(
                "run-unsafe-001", "main", "feat/drop-email",
                baseDir.toFile(), unsafeCandDir.toFile(), contractsFile.toFile(), policyFile.toFile(),
                false, true, reportsDir
        ));
        assertEquals(DecisionOutcome.BLOCK, unsafeReport.decision());
        assertEquals(1, unsafeReport.exitCode());
        assertTrue(unsafeReport.findings().stream().anyMatch(f -> "SG008".equals(f.ruleId())));

        // Scenario C: Unsupported SQL -> REVIEW_REQUIRED (Exit Code 3 in strict mode, SG010)
        AnalysisReport unsupportedReport = orchestrator.executeAnalysis(new AnalysisRequest(
                "run-unsupported-001", "main", "feat/vacuum",
                baseDir.toFile(), unsupportedCandDir.toFile(), contractsFile.toFile(), policyFile.toFile(),
                false, true, reportsDir
        ));
        assertEquals(DecisionOutcome.REVIEW_REQUIRED, unsupportedReport.decision());
        assertEquals(3, unsupportedReport.exitCode());
        assertTrue(unsupportedReport.findings().stream().anyMatch(f -> "SG010".equals(f.ruleId())));
    }
}