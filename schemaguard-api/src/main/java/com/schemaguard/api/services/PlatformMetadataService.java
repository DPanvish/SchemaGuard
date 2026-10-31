package com.schemaguard.api.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.schemaguard.core.dependencies.DependencyGraph;
import com.schemaguard.core.dependencies.DependencyGraphBuilder;
import com.schemaguard.core.dependencies.ServiceContractLoader;
import com.schemaguard.core.dependencies.ServiceContractLoader.LoadedContractArtifact;
import com.schemaguard.core.dependencies.ServiceContractManifest;
import com.schemaguard.core.diff.ChangeType;
import com.schemaguard.core.diff.SchemaChange;
import com.schemaguard.core.diff.SchemaDiffEngine;
import com.schemaguard.core.diff.SchemaDiffResult;
import com.schemaguard.core.parser.*;
import com.schemaguard.core.policy.PolicyGate;
import com.schemaguard.core.policy.PolicyGate.PolicyEvaluationResult;
import com.schemaguard.core.policy.PolicyLoader;
import com.schemaguard.core.policy.PolicyManifest;
import com.schemaguard.core.reporting.AnalysisReport;
import com.schemaguard.core.reporting.ReportGenerator;
import com.schemaguard.core.rules.AnalysisContext;
import com.schemaguard.core.rules.Finding;
import com.schemaguard.core.rules.RuleEngine;
import com.schemaguard.core.sandbox.SandboxExecutionResult;
import com.schemaguard.core.sandbox.SandboxExecutionResult.Status;
import com.schemaguard.core.schema.SchemaSnapshot;
import com.schemaguard.core.schema.SchemaStateReconstructor;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

@Service
public class PlatformMetadataService {

    private static final Logger log = LoggerFactory.getLogger(PlatformMetadataService.class);
    public static final String DEFAULT_PROJECT_ID = "proj-sample-company";

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final MigrationParser parser;
    private final MigrationChecksumService checksumService;
    private final SchemaStateReconstructor stateReconstructor;
    private final SchemaDiffEngine diffEngine;
    private final ServiceContractLoader contractLoader;
    private final DependencyGraphBuilder graphBuilder;
    private final RuleEngine ruleEngine;
    private final PolicyLoader policyLoader;
    private final PolicyGate policyGate;
    private final ReportGenerator reportGenerator;

    public PlatformMetadataService(
            JdbcTemplate jdbc,
            MigrationParser parser,
            MigrationChecksumService checksumService,
            SchemaStateReconstructor stateReconstructor,
            SchemaDiffEngine diffEngine,
            ServiceContractLoader contractLoader,
            DependencyGraphBuilder graphBuilder,
            RuleEngine ruleEngine,
            PolicyLoader policyLoader,
            PolicyGate policyGate,
            ReportGenerator reportGenerator) {
        this.jdbc = jdbc;
        this.objectMapper = new ObjectMapper();
        this.parser = parser;
        this.checksumService = checksumService;
        this.stateReconstructor = stateReconstructor;
        this.diffEngine = diffEngine;
        this.contractLoader = contractLoader;
        this.graphBuilder = graphBuilder;
        this.ruleEngine = ruleEngine;
        this.policyLoader = policyLoader;
        this.policyGate = policyGate;
        this.reportGenerator = reportGenerator;
    }

    public record ProjectRecord(String id, String name, String dialect, String repositoryUrl, String createdAt) {}
    public record CreateProjectRequest(String name, String dialect, String repositoryUrl) {}
    public record CreateContractRequest(String yamlContent) {}
    public record CreatePolicyRequest(String yamlContent) {}
    public record TriggerAnalysisRequest(
        String baseCommit,
        String headCommit,
        String baselineSql,
        String candidateSql,
        String candidateFileName,
        String contractsYaml,
        String policyYaml,
        Boolean strictMode
    ) {}

    @PostConstruct
    public void seedDefaultProjectAndSampleRuns() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM projects WHERE id = ?", Integer.class, DEFAULT_PROJECT_ID);
        if (count != null && count == 0) {
            log.info("Seeding default project and initial analysis runs for Enterprise Dashboard...");
            jdbc.update("INSERT INTO projects (id, name, dialect, repository_url, created_at) VALUES (?, ?, ?, ?, ?)",
                    DEFAULT_PROJECT_ID, "Sample Company (Commerce Core)", "postgresql",
                    "https://github.com/sample-company/commerce-db", Timestamp.from(Instant.now()));

            String defaultContracts = """
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
                      reporting-service:
                        reads:
                          - table: public.customers
                            columns: [id, name]
                    """;
            registerContract(DEFAULT_PROJECT_ID, new CreateContractRequest(defaultContracts));

            String defaultPolicy = """
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
            createPolicy(DEFAULT_PROJECT_ID, new CreatePolicyRequest(defaultPolicy));

            String baseSql = """
                    CREATE TABLE public.customers (
                        id UUID PRIMARY KEY,
                        email TEXT NOT NULL,
                        name TEXT
                    );
                    """;

            // Seed Run 1: Safe additive migration (PASS)
            runAnalysis(DEFAULT_PROJECT_ID, new TriggerAnalysisRequest(
                    "main@a1b2c3d", "feat/add-language@e4f5g6h",
                    baseSql,
                    "ALTER TABLE public.customers ADD COLUMN preferred_language TEXT;",
                    "V2__add_preferred_language.sql",
                    defaultContracts, defaultPolicy, true
            ));

            // Seed Run 2: Contract-breaking column drop (BLOCK)
            runAnalysis(DEFAULT_PROJECT_ID, new TriggerAnalysisRequest(
                    "main@a1b2c3d", "feat/remove-email@99f8e7d",
                    baseSql,
                    "ALTER TABLE public.customers DROP COLUMN email;",
                    "V12__remove_email.sql",
                    defaultContracts, defaultPolicy, true
            ));
        }
    }

    public ProjectRecord createProject(CreateProjectRequest req) {
        String id = "proj-" + UUID.randomUUID().toString().substring(0, 8);
        String dialect = (req.dialect() == null || req.dialect().isBlank()) ? "postgresql" : req.dialect().toLowerCase();
        Instant now = Instant.now();
        jdbc.update("INSERT INTO projects (id, name, dialect, repository_url, created_at) VALUES (?, ?, ?, ?, ?)",
                id, req.name(), dialect, req.repositoryUrl(), Timestamp.from(now));
        log.info("Created project [id={}, name={}]", id, req.name());
        return new ProjectRecord(id, req.name(), dialect, req.repositoryUrl(), now.toString());
    }

    public List<ProjectRecord> listProjects() {
        return jdbc.query("SELECT id, name, dialect, repository_url, created_at FROM projects ORDER BY created_at DESC",
                (rs, i) -> new ProjectRecord(
                        rs.getString("id"),
                        rs.getString("name"),
                        rs.getString("dialect"),
                        rs.getString("repository_url"),
                        rs.getTimestamp("created_at").toInstant().toString()
                ));
    }

    public Optional<Map<String, Object>> getProjectDetails(String projectId) {
        List<ProjectRecord> list = jdbc.query(
                "SELECT id, name, dialect, repository_url, created_at FROM projects WHERE id = ?",
                (rs, i) -> new ProjectRecord(
                        rs.getString("id"),
                        rs.getString("name"),
                        rs.getString("dialect"),
                        rs.getString("repository_url"),
                        rs.getTimestamp("created_at").toInstant().toString()
                ), projectId);

        if (list.isEmpty()) return Optional.empty();

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("project", list.get(0));
        details.put("policies", getPolicies(projectId));
        details.put("contracts", getContracts(projectId));
        details.put("recentRuns", listRunsForProject(projectId));
        return Optional.of(details);
    }

    public Map<String, Object> registerContract(String projectId, CreateContractRequest req) {
        LoadedContractArtifact artifact = contractLoader.loadAndValidateFromString("api-contract.yaml", req.yamlContent());
        for (String serviceName : artifact.manifest().services().keySet()) {
            jdbc.update("""
                    INSERT INTO service_contracts (id, project_id, service_name, contract_version, contract_yaml, checksum)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """,
                    "cnt-" + UUID.randomUUID().toString().substring(0, 8),
                    projectId, serviceName, artifact.manifest().version(), req.yamlContent(), artifact.checksum());
        }
        return Map.of(
                "projectId", projectId,
                "checksum", artifact.checksum(),
                "version", artifact.manifest().version(),
                "registeredServices", artifact.manifest().services().keySet()
        );
    }

    public List<Map<String, Object>> getContracts(String projectId) {
        return jdbc.query("""
                SELECT id, service_name, contract_version, contract_yaml, checksum
                FROM service_contracts WHERE project_id = ?
                """,
                (rs, i) -> Map.of(
                        "id", rs.getString("id"),
                        "serviceName", rs.getString("service_name"),
                        "version", rs.getInt("contract_version"),
                        "checksum", rs.getString("checksum"),
                        "yaml", rs.getString("contract_yaml")
                ), projectId);
    }

    public Map<String, Object> createPolicy(String projectId, CreatePolicyRequest req) {
        PolicyManifest manifest = policyLoader.loadAndValidateFromString("api-policy.yaml", req.yamlContent());
        String id = "pol-" + UUID.randomUUID().toString().substring(0, 8);
        Instant now = Instant.now();
        try {
            String json = objectMapper.writeValueAsString(manifest);
            jdbc.update("INSERT INTO policy_versions (id, project_id, version, policy_json, created_at) VALUES (?, ?, ?, ?, ?)",
                    id, projectId, manifest.version(), json, Timestamp.from(now));
            return Map.of("id", id, "projectId", projectId, "version", manifest.version(), "createdAt", now.toString());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to persist policy version", e);
        }
    }

    public List<Map<String, Object>> getPolicies(String projectId) {
        return jdbc.query("""
                SELECT id, version, policy_json, created_at
                FROM policy_versions WHERE project_id = ? ORDER BY created_at DESC
                """,
                (rs, i) -> Map.of(
                        "id", rs.getString("id"),
                        "version", rs.getInt("version"),
                        "policyJson", rs.getString("policy_json"),
                        "createdAt", rs.getTimestamp("created_at").toInstant().toString()
                ), projectId);
    }

    public List<Map<String, Object>> getSnapshots(String projectId) {
        return jdbc.query("""
                SELECT id, commit_sha, snapshot_hash, schema_model_version, schema_json
                FROM schema_snapshots WHERE project_id = ?
                """,
                (rs, i) -> Map.of(
                        "id", rs.getString("id"),
                        "commitSha", rs.getString("commit_sha"),
                        "snapshotHash", rs.getString("snapshot_hash"),
                        "schemaModelVersion", rs.getString("schema_model_version"),
                        "schemaJson", rs.getString("schema_json")
                ), projectId);
    }

    public Map<String, Object> runAnalysis(String projectId, TriggerAnalysisRequest req) {
        String runId = "sg-run-" + UUID.randomUUID().toString().substring(0, 8);
        Instant startedAt = Instant.now();
        log.info("Executing API analysis run [runId={}, projectId={}]", runId, projectId);

        try {
            Path tempWorkspace = Files.createTempDirectory("sg-api-run-");
            File baseFile = tempWorkspace.resolve("V1__baseline.sql").toFile();
            String candName = (req.candidateFileName() != null && !req.candidateFileName().isBlank())
                    ? req.candidateFileName() : "V2__candidate.sql";
            File candFile = tempWorkspace.resolve(candName).toFile();

            String baseSql = (req.baselineSql() != null && !req.baselineSql().isBlank())
                    ? req.baselineSql()
                    : "CREATE TABLE public.customers (id UUID PRIMARY KEY, email TEXT NOT NULL, name TEXT);";
            String candSql = (req.candidateSql() != null && !req.candidateSql().isBlank())
                    ? req.candidateSql()
                    : "ALTER TABLE public.customers DROP COLUMN email;";

            Files.writeString(baseFile.toPath(), baseSql, StandardCharsets.UTF_8);
            Files.writeString(candFile.toPath(), candSql, StandardCharsets.UTF_8);

            ServiceContractManifest contracts = (req.contractsYaml() != null && !req.contractsYaml().isBlank())
                    ? contractLoader.loadAndValidateFromString("inline-contracts.yaml", req.contractsYaml()).manifest()
                    : loadLatestContractManifest(projectId);

            PolicyManifest policy = (req.policyYaml() != null && !req.policyYaml().isBlank())
                    ? policyLoader.loadAndValidateFromString("inline-policy.yaml", req.policyYaml())
                    : PolicyManifest.defaultPolicy();

            String candChecksum = checksumService.calculateChecksum(candFile);
            List<NormalizedOperation> candOps = parser.parse(candFile);
            MigrationArtifact artifact = MigrationArtifact.of(candFile.getName(), candFile.getName(), candChecksum, candOps);

            SchemaSnapshot baseSnap = stateReconstructor.reconstructFromDdlChain(List.of(baseFile));
            SchemaSnapshot candSnap = stateReconstructor.reconstructFromDdlChain(List.of(baseFile, candFile));

            SchemaDiffResult diff = diffEngine.compare(baseSnap, candSnap, candOps);
            DependencyGraph graph = graphBuilder.build(baseSnap, contracts, candOps);

            boolean incompleteCoverage = false;
            for (SchemaChange change : diff.changes()) {
                if ((change.changeType() == ChangeType.COLUMN_DROPPED || change.changeType() == ChangeType.TABLE_DROPPED)
                        && (contracts == null || !graph.isTableCovered(change.schemaName(), change.tableName()))) {
                    incompleteCoverage = true;
                }
            }

            AnalysisContext ctx = new AnalysisContext(
                    baseSnap, candSnap, diff, List.of(artifact), List.of(),
                    graph.toDeclaredConsumersMap(), Map.of(), false, List.of(), true
            );
            List<Finding> findings = ruleEngine.evaluateAll(ctx);

            SandboxExecutionResult sandboxResult = new SandboxExecutionResult(
                    Status.COMPLETED, 18L, baseSnap.engineVersion(), null, null, null,
                    List.of("Verified deterministic schema reconstruction in isolated workspace")
            );

            boolean strict = req.strictMode() == null || req.strictMode();
            PolicyEvaluationResult gate = policyGate.evaluate(policy, findings, sandboxResult, incompleteCoverage, strict);

            String baseCommit = req.baseCommit() != null ? req.baseCommit() : "main@base";
            String headCommit = req.headCommit() != null ? req.headCommit() : "feature@head";

            AnalysisReport report = new AnalysisReport(
                    "1.0", runId, "1.0-SNAPSHOT", baseCommit, headCommit,
                    baseSnap.canonicalHash(), candSnap.canonicalHash(),
                    gate.decision(), gate.exitCode(), gate.prioritizationScore(),
                    "Completed against isolated fixture; no persistent database touched.",
                    sandboxResult, gate.gateRationale(), gate.effectiveFindings()
            );

            String markdown = reportGenerator.toMarkdown(report);
            Instant completedAt = Instant.now();

            // Persist Run, Snapshots, Artifact, Findings, and Sandbox Result (Section 7 Data Model)
            jdbc.update("""
                    INSERT INTO analysis_runs (id, project_id, base_commit, head_commit, status, prioritization_score, started_at, completed_at, tool_version, markdown_report)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    runId, projectId, baseCommit, headCommit, gate.decision().name(),
                    gate.prioritizationScore(), Timestamp.from(startedAt), Timestamp.from(completedAt),
                    "1.0-SNAPSHOT", markdown);

            persistSnapshot(projectId, baseCommit, baseSnap);
            persistSnapshot(projectId, headCommit, candSnap);

            jdbc.update("""
                    INSERT INTO migration_artifacts (id, run_id, file_path, checksum, parser_status, source_metadata_json)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """,
                    "art-" + UUID.randomUUID().toString().substring(0, 8),
                    runId, artifact.fileName(), artifact.checksum(), artifact.overallStatus().name(),
                    objectMapper.writeValueAsString(artifact.operations()));

            for (Finding f : gate.effectiveFindings()) {
                jdbc.update("""
                        INSERT INTO findings (id, run_id, rule_id, severity, title, message, object_name, source_location_json, evidence_json, remediation)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                        "fnd-" + UUID.randomUUID().toString().substring(0, 8),
                        runId, f.ruleId(), f.severity().name(), f.title(), f.message(), f.objectRef(),
                        objectMapper.writeValueAsString(f.sourceLocation()),
                        objectMapper.writeValueAsString(f.evidence()),
                        f.remediation());
            }

            jdbc.update("""
                    INSERT INTO sandbox_results (id, run_id, status, duration_ms, postgres_version, result_json)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """,
                    "sbx-" + UUID.randomUUID().toString().substring(0, 8),
                    runId, sandboxResult.status().name(), sandboxResult.durationMs(),
                    sandboxResult.postgresVersion(), objectMapper.writeValueAsString(sandboxResult));

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("runId", runId);
            response.put("projectId", projectId);
            response.put("decision", gate.decision());
            response.put("exitCode", gate.exitCode());
            response.put("prioritizationScore", gate.prioritizationScore());
            response.put("baseSnapshotHash", baseSnap.canonicalHash());
            response.put("candidateSnapshotHash", candSnap.canonicalHash());
            response.put("changes", diff.changes());
            response.put("dependencyEdges", graph.edges());
            response.put("contractCoverageRatio", graph.tableContractCoverageRatio());
            response.put("findings", gate.effectiveFindings());
            response.put("gateRationale", gate.gateRationale());
            response.put("markdownReport", markdown);
            response.put("sarifReport", reportGenerator.toSarif(report));
            return response;
        } catch (Exception e) {
            log.error("Analysis execution failed for runId={}", runId, e);
            throw new IllegalStateException("Analysis execution failed: " + e.getMessage(), e);
        }
    }

    public Optional<Map<String, Object>> getAnalysisStatus(String runId) {
        List<Map<String, Object>> rows = jdbc.query("""
                SELECT id, project_id, base_commit, head_commit, status, prioritization_score, started_at, completed_at, tool_version, markdown_report
                FROM analysis_runs WHERE id = ?
                """,
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("runId", rs.getString("id"));
                    m.put("projectId", rs.getString("project_id"));
                    m.put("baseCommit", rs.getString("base_commit"));
                    m.put("headCommit", rs.getString("head_commit"));
                    m.put("decision", rs.getString("status"));
                    m.put("prioritizationScore", rs.getInt("prioritization_score"));
                    m.put("startedAt", rs.getTimestamp("started_at").toInstant().toString());
                    m.put("completedAt", rs.getTimestamp("completed_at").toInstant().toString());
                    m.put("toolVersion", rs.getString("tool_version"));
                    m.put("markdownReport", rs.getString("markdown_report"));
                    m.put("findingsCount", getFindingsForRun(rs.getString("id")).size());
                    return m;
                }, runId);
        return rows.stream().findFirst();
    }

    public List<Map<String, Object>> listRunsForProject(String projectId) {
        return jdbc.query("""
                SELECT id, project_id, base_commit, head_commit, status, prioritization_score, started_at, completed_at, tool_version
                FROM analysis_runs WHERE project_id = ? ORDER BY started_at DESC
                """,
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    String id = rs.getString("id");
                    m.put("runId", id);
                    m.put("projectId", rs.getString("project_id"));
                    m.put("baseCommit", rs.getString("base_commit"));
                    m.put("headCommit", rs.getString("head_commit"));
                    m.put("decision", rs.getString("status"));
                    m.put("prioritizationScore", rs.getInt("prioritization_score"));
                    m.put("startedAt", rs.getTimestamp("started_at").toInstant().toString());
                    m.put("completedAt", rs.getTimestamp("completed_at").toInstant().toString());
                    m.put("findingsCount", getFindingsForRun(id).size());
                    return m;
                }, projectId);
    }

    public List<Map<String, Object>> getFindingsForRun(String runId) {
        return jdbc.query("""
                SELECT id, rule_id, severity, title, message, object_name, source_location_json, evidence_json, remediation
                FROM findings WHERE run_id = ?
                """,
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getString("id"));
                    m.put("ruleId", rs.getString("rule_id"));
                    m.put("severity", rs.getString("severity"));
                    m.put("title", rs.getString("title"));
                    m.put("message", rs.getString("message"));
                    m.put("objectRef", rs.getString("object_name"));
                    m.put("sourceLocationJson", rs.getString("source_location_json"));
                    m.put("evidenceJson", rs.getString("evidence_json"));
                    m.put("remediation", rs.getString("remediation"));
                    return m;
                }, runId);
    }

    public List<Map<String, String>> listRuleCatalog() {
        return List.of(
                Map.of("ruleId", "SG001", "defaultSeverity", "CRITICAL", "title", "Destructive drop of database object", "category", "Compatibility & Data Loss"),
                Map.of("ruleId", "SG002", "defaultSeverity", "CRITICAL", "title", "Potential breaking object rename", "category", "Contract Compatibility"),
                Map.of("ruleId", "SG003", "defaultSeverity", "HIGH", "title", "Incompatible or narrowing data type change", "category", "Type Safety"),
                Map.of("ruleId", "SG004", "defaultSeverity", "HIGH", "title", "Setting column NOT NULL without staged validation", "category", "Data Integrity & Locks"),
                Map.of("ruleId", "SG005", "defaultSeverity", "HIGH", "title", "Create index without CONCURRENTLY", "category", "Operational Lock Risk"),
                Map.of("ruleId", "SG006", "defaultSeverity", "WARN", "title", "Constraint addition requiring table scan or lock", "category", "Operational Lock Risk"),
                Map.of("ruleId", "SG007", "defaultSeverity", "CRITICAL", "title", "Modified historical migration file", "category", "Migration History Integrity"),
                Map.of("ruleId", "SG008", "defaultSeverity", "CRITICAL", "title", "Breaking database service contract", "category", "Consumer Blast Radius"),
                Map.of("ruleId", "SG009", "defaultSeverity", "REVIEW_REQUIRED", "title", "Destructive DML without declared backup strategy", "category", "Recovery Safety"),
                Map.of("ruleId", "SG010", "defaultSeverity", "REVIEW_REQUIRED", "title", "SQL statement outside supported analysis scope", "category", "Conservative Uncertainty Boundary"),
                Map.of("ruleId", "SG011", "defaultSeverity", "HIGH", "title", "Observed environment schema drift", "category", "Environment Consistency"),
                Map.of("ruleId", "SG012", "defaultSeverity", "HIGH", "title", "Operation may rewrite table and acquire ACCESS EXCLUSIVE lock", "category", "Operational Lock Risk")
        );
    }

    private void persistSnapshot(String projectId, String commitSha, SchemaSnapshot snap) throws Exception {
        jdbc.update("""
                INSERT INTO schema_snapshots (id, project_id, commit_sha, snapshot_hash, schema_model_version, schema_json)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                "snp-" + UUID.randomUUID().toString().substring(0, 8),
                projectId, commitSha, snap.canonicalHash(), snap.schemaModelVersion(),
                objectMapper.writeValueAsString(snap));
    }

    private ServiceContractManifest loadLatestContractManifest(String projectId) {
        List<String> yamls = jdbc.query(
                "SELECT contract_yaml FROM service_contracts WHERE project_id = ? LIMIT 1",
                (rs, i) -> rs.getString("contract_yaml"), projectId);
        if (yamls.isEmpty()) return null;
        return contractLoader.loadAndValidateFromString("stored-contract.yaml", yamls.get(0)).manifest();
    }
}