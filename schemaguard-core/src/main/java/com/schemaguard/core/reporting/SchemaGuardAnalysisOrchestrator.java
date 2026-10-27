package com.schemaguard.core.reporting;

import com.schemaguard.core.dependencies.DependencyGraph;
import com.schemaguard.core.dependencies.DependencyGraphBuilder;
import com.schemaguard.core.dependencies.ServiceContractLoader;
import com.schemaguard.core.dependencies.ServiceContractManifest;
import com.schemaguard.core.diff.ChangeType;
import com.schemaguard.core.diff.SchemaChange;
import com.schemaguard.core.diff.SchemaDiffEngine;
import com.schemaguard.core.diff.SchemaDiffResult;
import com.schemaguard.core.parser.*;
import com.schemaguard.core.parser.MigrationHistoryValidator.TamperedMigration;
import com.schemaguard.core.policy.PolicyGate;
import com.schemaguard.core.policy.PolicyGate.PolicyEvaluationResult;
import com.schemaguard.core.policy.PolicyLoader;
import com.schemaguard.core.policy.PolicyManifest;
import com.schemaguard.core.rules.AnalysisContext;
import com.schemaguard.core.rules.Finding;
import com.schemaguard.core.rules.RuleEngine;
import com.schemaguard.core.sandbox.SandboxExecutionResult;
import com.schemaguard.core.sandbox.SandboxExecutionResult.Status;
import com.schemaguard.core.sandbox.SandboxRunner;
import com.schemaguard.core.schema.SchemaSnapshot;
import com.schemaguard.core.schema.SchemaStateReconstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class SchemaGuardAnalysisOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(SchemaGuardAnalysisOrchestrator.class);
    public static final String TOOL_VERSION = "1.0-SNAPSHOT";

    private static final Pattern VERSION_PREFIX_PATTERN = Pattern.compile("^[Vv](\\d+).*");

    /**
     * Sorts versioned migration filenames numerically (e.g., V1__ < V2__ < V12__ < V99__)
     * so V12 is never ordered before V1.
     */
    public static final Comparator<String> MIGRATION_VERSION_COMPARATOR = (name1, name2) -> {
        Long v1 = extractVersionNumber(name1);
        Long v2 = extractVersionNumber(name2);
        if (v1 != null && v2 != null && !v1.equals(v2)) {
            return Long.compare(v1, v2);
        }
        return name1.compareToIgnoreCase(name2);
    };

    private final MigrationParser migrationParser;
    private final MigrationChecksumService checksumService;
    private final MigrationHistoryValidator historyValidator;
    private final SchemaStateReconstructor stateReconstructor;
    private final SchemaDiffEngine diffEngine;
    private final ServiceContractLoader contractLoader;
    private final DependencyGraphBuilder graphBuilder;
    private final RuleEngine ruleEngine;
    private final SandboxRunner sandboxRunner;
    private final PolicyLoader policyLoader;
    private final PolicyGate policyGate;
    private final ReportGenerator reportGenerator;

    public SchemaGuardAnalysisOrchestrator(
            MigrationParser migrationParser,
            MigrationChecksumService checksumService,
            MigrationHistoryValidator historyValidator,
            SchemaStateReconstructor stateReconstructor,
            SchemaDiffEngine diffEngine,
            ServiceContractLoader contractLoader,
            DependencyGraphBuilder graphBuilder,
            RuleEngine ruleEngine,
            SandboxRunner sandboxRunner,
            PolicyLoader policyLoader,
            PolicyGate policyGate,
            ReportGenerator reportGenerator) {
        this.migrationParser = migrationParser;
        this.checksumService = checksumService;
        this.historyValidator = historyValidator;
        this.stateReconstructor = stateReconstructor;
        this.diffEngine = diffEngine;
        this.contractLoader = contractLoader;
        this.graphBuilder = graphBuilder;
        this.ruleEngine = ruleEngine;
        this.sandboxRunner = sandboxRunner;
        this.policyLoader = policyLoader;
        this.policyGate = policyGate;
        this.reportGenerator = reportGenerator;
    }

    public record AnalysisRequest(
        String runId,
        String baseRef,
        String headRef,
        File baseMigrationsDir,
        File candidateMigrationsDir,
        File contractFile,
        File policyFile,
        boolean executeDockerSandbox,
        boolean strictMode,
        Path reportOutputDirectory
    ) {}

    public AnalysisReport executeAnalysis(AnalysisRequest request) {
        String runId = (request.runId() != null && !request.runId().isBlank())
                ? request.runId()
                : "sg-run-" + LocalDate.now() + "-001";

        log.info("Starting end-to-end SchemaGuard analysis [runId={}, baseRef={}, headRef={}]",
                runId, request.baseRef(), request.headRef());

        // Stage 1: Resolve inputs, contracts, and policy configuration
        PolicyManifest policy = (request.policyFile() != null && request.policyFile().exists())
                ? policyLoader.loadAndValidate(request.policyFile())
                : PolicyManifest.defaultPolicy();

        ServiceContractManifest contracts = (request.contractFile() != null && request.contractFile().exists())
                ? contractLoader.loadAndValidate(request.contractFile()).manifest()
                : null;

        List<File> baseFiles = listSqlFiles(request.baseMigrationsDir());
        List<File> candidateFiles = listSqlFiles(request.candidateMigrationsDir());

        // Combine base + candidate files in true numerical migration order
        List<File> fullCandidateChain = buildFullCandidateChain(baseFiles, candidateFiles);

        // Stage 2: Pre-checks (checksums, historical migration edits, parser coverage)
        Map<String, String> baseChecksums = new LinkedHashMap<>();
        for (File bf : baseFiles) {
            baseChecksums.put(bf.getName(), checksumService.calculateChecksum(bf));
        }

        List<MigrationArtifact> candidateArtifacts = new ArrayList<>();
        for (File cf : candidateFiles) {
            String checksum = checksumService.calculateChecksum(cf);
            List<NormalizedOperation> ops = migrationParser.parse(cf);
            candidateArtifacts.add(MigrationArtifact.of(cf.getName(), cf.getPath(), checksum, ops));
        }

        List<TamperedMigration> tamperedHistory = historyValidator.detectModifiedHistory(baseChecksums, candidateArtifacts);

        // Stage 3: Schema reconstruction
        SchemaSnapshot baseSnapshot = stateReconstructor.reconstruct(baseFiles, request.executeDockerSandbox());
        SchemaSnapshot candidateSnapshot = stateReconstructor.reconstruct(fullCandidateChain, request.executeDockerSandbox());

        // Stage 4: Canonical comparison
        List<NormalizedOperation> allCandidateOps = candidateArtifacts.stream()
                .flatMap(a -> a.operations().stream())
                .toList();

        SchemaDiffResult diffResult = diffEngine.compare(baseSnapshot, candidateSnapshot, allCandidateOps);

        // Stage 5: Dependency impact graph and risk rules
        DependencyGraph dependencyGraph = graphBuilder.build(baseSnapshot, contracts, allCandidateOps);

        boolean incompleteContractCoverage = checkIncompleteContractCoverage(diffResult, dependencyGraph, contracts);

        AnalysisContext context = new AnalysisContext(
                baseSnapshot,
                candidateSnapshot,
                diffResult,
                candidateArtifacts,
                tamperedHistory,
                dependencyGraph.toDeclaredConsumersMap(),
                Map.of(),
                false,
                List.of(),
                true
        );

        List<Finding> rawFindings = ruleEngine.evaluateAll(context);

        // Stage 6: Sandbox execution (if enabled)
        SandboxExecutionResult sandboxResult;
        String sandboxSummary;
        if (request.executeDockerSandbox()) {
            sandboxResult = sandboxRunner.executeInDisposableContainer(baseFiles, List.of(), candidateFiles, 15, 60);
            sandboxSummary = "Executed in disposable PostgreSQL container (" + sandboxResult.status() + ")";
        } else {
            sandboxResult = new SandboxExecutionResult(
                    Status.SKIPPED, 0L, baseSnapshot.engineVersion(), null, null, null,
                    List.of("Completed deterministic static & catalog reconstruction; no persistent database used.")
            );
            sandboxSummary = "Completed deterministic schema reconstruction; no persistent database used.";
        }

        // Stage 7: Policy Gate Decision
        PolicyEvaluationResult gateResult = policyGate.evaluate(
                policy, rawFindings, sandboxResult, incompleteContractCoverage, request.strictMode()
        );

        // Stage 8: Build & Persist Reports
        AnalysisReport report = new AnalysisReport(
                "1.0",
                runId,
                TOOL_VERSION,
                request.baseRef() != null ? request.baseRef() : "base",
                request.headRef() != null ? request.headRef() : "candidate",
                baseSnapshot.canonicalHash(),
                candidateSnapshot.canonicalHash(),
                gateResult.decision(),
                gateResult.exitCode(),
                gateResult.prioritizationScore(),
                sandboxSummary,
                sandboxResult,
                gateResult.gateRationale(),
                gateResult.effectiveFindings()
        );

        if (request.reportOutputDirectory() != null) {
            persistReportFiles(report, request.reportOutputDirectory());
        }

        log.info("Finished SchemaGuard analysis [runId={}, decision={}, exitCode={}]",
                runId, report.decision(), report.exitCode());

        return report;
    }

    private boolean checkIncompleteContractCoverage(
            SchemaDiffResult diffResult,
            DependencyGraph graph,
            ServiceContractManifest contracts) {
        if (diffResult == null || diffResult.changes().isEmpty()) {
            return false;
        }
        for (SchemaChange change : diffResult.changes()) {
            if (change.changeType() == ChangeType.COLUMN_DROPPED
                    || change.changeType() == ChangeType.TABLE_DROPPED
                    || change.changeType() == ChangeType.COLUMN_TYPE_CHANGED) {
                if (contracts == null || !graph.isTableCovered(change.schemaName(), change.tableName())) {
                    return true;
                }
            }
        }
        return false;
    }

    private List<File> buildFullCandidateChain(List<File> baseFiles, List<File> candidateFiles) {
        Map<String, File> chainByFileName = new TreeMap<>(MIGRATION_VERSION_COMPARATOR);
        for (File bf : baseFiles) {
            chainByFileName.put(bf.getName(), bf);
        }
        for (File cf : candidateFiles) {
            chainByFileName.put(cf.getName(), cf);
        }
        return new ArrayList<>(chainByFileName.values());
    }

    private List<File> listSqlFiles(File dir) {
        if (dir == null || !dir.exists() || !dir.isDirectory()) {
            return List.of();
        }
        File[] files = dir.listFiles((d, name) -> name.toLowerCase(Locale.ROOT).endsWith(".sql"));
        if (files == null || files.length == 0) {
            return List.of();
        }
        Arrays.sort(files, (f1, f2) -> MIGRATION_VERSION_COMPARATOR.compare(f1.getName(), f2.getName()));
        return List.of(files);
    }

    private static Long extractVersionNumber(String fileName) {
        if (fileName == null) {
            return null;
        }
        Matcher matcher = VERSION_PREFIX_PATTERN.matcher(fileName);
        if (matcher.matches()) {
            try {
                return Long.parseLong(matcher.group(1));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private void persistReportFiles(AnalysisReport report, Path baseOutputDir) {
        try {
            Path runDir = baseOutputDir.resolve(report.runId());
            Files.createDirectories(runDir);

            Files.writeString(runDir.resolve("report.md"), reportGenerator.toMarkdown(report), StandardCharsets.UTF_8);
            Files.writeString(runDir.resolve("report.json"), reportGenerator.toJson(report), StandardCharsets.UTF_8);
            Files.writeString(runDir.resolve("report.sarif"), reportGenerator.toSarif(report), StandardCharsets.UTF_8);

            log.info("Persisted Markdown, JSON, and SARIF 2.1.0 reports to {}", runDir.toAbsolutePath());
        } catch (IOException e) {
            log.error("Failed to write report artifacts for runId={}", report.runId(), e);
        }
    }
}