# SchemaGuard — Architecture and Data Model Specification

## 1. Architectural Overview
SchemaGuard is structured as a **modular monolith** in Java 21 and Spring Boot. The core analysis engine (`schemaguard-core`), the command-line interface (`schemaguard-cli`), and the REST API / Web UI (`schemaguard-api`) reside in a single multi-module Maven repository. This design eliminates premature microservice networking and authentication overhead while preserving strict package boundaries for future worker extraction.

---

## 2. Logical Architecture & Module Responsibilities

| Module | Package / Component | Responsibilities | Implementation Notes |
| :--- | :--- | :--- | :--- |
| **CLI / CI Adapter** | `com.schemaguard.cli.commands` | Resolve CLI flags and revisions, load configuration, invoke the analysis orchestrator, and return stable exit codes (`0`, `1`, `2`, `3`). | Implemented via Spring Boot `CommandLineRunner` and `ExitCodeGenerator`. |
| **Migration Parser** | `com.schemaguard.core.parser` | Parse supported SQL DDL into `NormalizedOperation` records, extract source file and line numbers, compute normalized SHA-256 checksums, and detect tampered historical files. | Isolates `JSqlParser` behind the `MigrationParser` interface; flags unknown grammar as `ParserStatus.UNSUPPORTED`. |
| **Schema Snapshotter** | `com.schemaguard.core.schema` | Reconstruct baseline and candidate schemas via deterministic DDL replay or isolated PostgreSQL catalog inspection (`information_schema` and `pg_catalog`). | Normalizes PostgreSQL type aliases, sorts all collections alphabetically, and computes a SHA-256 `canonicalHash`. |
| **Diff Engine** | `com.schemaguard.core.diff` | Compare canonical `SchemaSnapshot` objects, detect additions/removals/modifications, propose heuristic rename candidates, and map changes to SQL source locations. | Never infers a column rename as certain from snapshots alone (`ChangeType.RENAME_CANDIDATE`). |
| **Dependency Graph** | `com.schemaguard.core.dependencies` | Validate YAML service contracts and build a directed graph linking services, tables, columns, views, foreign keys, and migration operations. | Traverses reverse edges to compute blast radius and tracks table-level contract coverage ratios. |
| **Rule Engine** | `com.schemaguard.core.rules` | Evaluate versioned rules (`SG001`–`SG012`) against an immutable `AnalysisContext` and attach deterministic Expand–Migrate–Verify–Contract remediation templates. | Sorts findings deterministically by severity rank, rule ID, object reference, file, and line number. |
| **Sandbox Runner** | `com.schemaguard.core.sandbox` | Execute baseline migrations, synthetic seed fixtures, and candidate migrations in disposable PostgreSQL containers (`postgres:16-alpine`). | Enforces 512 MB container memory cap, JDBC statement timeouts, wall-clock job limits, and secret redaction. |
| **Policy Gate** | `com.schemaguard.core.policy` | Combine rule findings, sandbox status, and contract coverage state into a final decision (`PASS`, `WARN`, `BLOCK`, `REVIEW_REQUIRED`). | Hard policy blocks can never be outweighed by a low aggregate prioritization score. |
| **Reporting Engine** | `com.schemaguard.core.reporting` | Render human-readable Markdown, machine-readable JSON, and GitHub Code Scanning SARIF 2.1.0 reports. | Writes versioned artifacts to `.schemaguard/runs/<run-id>/`. |
| **REST API & Web UI** | `com.schemaguard.api` | Persist project metadata, run histories, snapshots, contracts, and policies; expose `/api/v1/*` endpoints and interactive web dashboard. | Separates platform metadata storage from disposable target databases. |

---

## 3. End-to-End Execution Sequence

```text
[1. Inputs] ---> [2. Pre-checks] ---> [3. Schema Reconstruction] ---> [4. Canonical Diff]
                                                                             |
[8. Delivery] <-- [7. Policy Gate] <-- [6. Sandbox Validation] <-- [5. Impact & Risk Rules]
```

| Step | Stage | Input Artifacts | Processing Logic | Output Artifacts |
| :--- | :--- | :--- | :--- | :--- |
| **1** | **Inputs** | Git base/head refs, SQL directories, `service-contracts.yaml`, `policy.yaml`. | Validate YAML schemas strictly (`FAIL_ON_UNKNOWN_PROPERTIES`) and sort migration files numerically (`V1` < `V2` < `V12`). | Validated `PolicyManifest`, `ServiceContractManifest`, and ordered file lists. |
| **2** | **Pre-checks** | Baseline & candidate `.sql` files. | Normalize `CRLF` to `LF`, compute SHA-256 checksums, compare baseline vs candidate history, and parse SQL into `NormalizedOperation` records. | `MigrationArtifact` list, `TamperedMigration` list (`SG007`), parser coverage markers. |
| **3** | **Schema Reconstruction** | Ordered baseline and full candidate migration chains. | Replay DDL chain or execute inside isolated Testcontainers PostgreSQL (`postgres:16-alpine`) and inspect system catalogs. | Base `SchemaSnapshot` and Candidate `SchemaSnapshot` with SHA-256 `canonicalHash`. |
| **4** | **Comparison** | Base `SchemaSnapshot`, Candidate `SchemaSnapshot`, `NormalizedOperation` list. | Index objects by fully qualified name, compute key-set and attribute diffs, propose rename candidates, and attach SQL source lines. | `SchemaDiffResult` containing `SchemaChange` records and unmapped change counts. |
| **5** | **Impact & Risk** | `SchemaDiffResult`, `DependencyGraph`, `TableProfile` map, `TamperedMigration` list. | Traverse reverse dependency edges for affected services/objects and evaluate rules `SG001`–`SG012`. | Deterministically sorted `Finding` list with evidence and remediation guidance. |
| **6** | **Validation** | Base SQL, synthetic seed SQL, candidate SQL. | Execute migrations in disposable sandbox with statement and wall-clock timeouts; capture SQLState errors and redact credentials. | `SandboxExecutionResult` (`COMPLETED`, `FAILED`, `TIMED_OUT`, or `SKIPPED`). |
| **7** | **Decision** | `PolicyManifest`, `Finding` list, `SandboxExecutionResult`, contract coverage flag. | Apply rule overrides and evaluate hard blocks (`BLOCK`), incomplete coverage/unsupported SQL (`REVIEW_REQUIRED`), warnings (`WARN`), or pass (`PASS`). | `PolicyEvaluationResult` with `DecisionOutcome`, exit code (`0`–`3`), and rationale. |
| **8** | **Delivery** | `AnalysisReport` model. | Render `report.md`, `report.json`, and `report.sarif`; return exit code to CLI / CI runner. | Persisted report files and process exit code. |

---

## 4. Canonical Schema Model (`v1.0`)

Equivalent database schemas serialize to the exact same canonical JSON structure regardless of the order in which objects were created or which PostgreSQL type aliases were used:

```text
SchemaSnapshot
  schemaModelVersion: "v1.0"
  engine: "PostgreSQL"
  engineVersion: "16.x"
  schemas[] (sorted by schema name)
    name
    tables[] (sorted by table name)
      name
      columns[] (sorted by column name)
        name, type (normalized), nullable, defaultExpression, identityOrGenerated
      primaryKey
        name, columns[]
      uniqueConstraints[] (sorted by constraint name)
        name, columns[]
      foreignKeys[] (sorted by constraint name)
        name, localColumns[], referencedSchema, referencedTable, referencedColumns[], onDeleteAction, onUpdateAction
      checkConstraints[] (sorted by constraint name)
        name, expression
      indexes[] (sorted by index name)
        name, columns[], unique, indexMethod
    views[] (sorted by view name)
      name, normalizedDefinition
    sequences[] (sorted by sequence name)
      name, dataType, startValue, incrementBy
  canonicalHash: SHA-256(canonical sorted JSON serialization)
```

---

## 5. Platform Metadata Persistence Schema

SchemaGuard stores its operational metadata in an isolated persistence schema (separate from any target database under analysis):

| Table Name | Purpose | Key Columns | Recommended Indexes |
| :--- | :--- | :--- | :--- |
| **`projects`** | Project and repository registration. | `id`, `name`, `dialect`, `repository_url`, `created_at` | `PRIMARY KEY (id)`, `UNIQUE (name)` |
| **`analysis_runs`** | Append-only immutable record of every analysis run. | `id`, `project_id`, `base_commit`, `head_commit`, `status`, `decision`, `exit_code`, `started_at`, `completed_at`, `tool_version` | `(project_id, started_at DESC)` |
| **`schema_snapshots`** | Canonical schema representations by commit. | `id`, `project_id`, `commit_sha`, `snapshot_hash`, `schema_model_version`, `schema_json` | `(project_id, commit_sha)`, `(project_id, snapshot_hash)` |
| **`migration_artifacts`** | SQL files and checksums analyzed during a run. | `id`, `run_id`, `file_path`, `checksum`, `parser_status`, `source_metadata_json` | `(run_id, file_path)`, `(run_id, checksum)` |
| **`findings`** | Rule outcomes, source locations, evidence, and remediation. | `id`, `run_id`, `rule_id`, `severity`, `object_name`, `source_location_json`, `evidence_json`, `remediation` | `(run_id, severity)`, `(run_id, rule_id)` |
| **`service_contracts`** | Declared application read dependencies. | `id`, `project_id`, `service_name`, `contract_version`, `contract_yaml`, `checksum` | `(project_id, service_name, contract_version)` |
| **`policy_versions`** | Versioned policy configurations and rule settings. | `id`, `project_id`, `version`, `policy_json`, `created_at` | `(project_id, version DESC)` |
| **`sandbox_results`** | Disposable container execution and fixture test outcomes. | `id`, `run_id`, `status`, `duration_ms`, `postgres_version`, `result_json` | `(run_id)` |

---

## 6. Trust Boundaries

1. **Untrusted Repository Input Boundary (`CLI` / `Parser` / `YAML Loaders`):**
   * Pull request SQL and YAML files are treated as untrusted input.
   * YAML loaders enforce `FAIL_ON_UNKNOWN_PROPERTIES` and explicit version/rule ID validation.
   * The SQL parser catches all `JSQLParserException` errors and unrecognized AST nodes, converting them into `ParserStatus.UNSUPPORTED` operations so unknown SQL never passes silently.
2. **Sandbox Execution Boundary (`SandboxRunner` / `PostgresCatalogSnapshotter`):**
   * Untrusted SQL runs exclusively against disposable containers with ephemeral credentials (`sg_sandbox_runner`), a 512 MB container memory cap, per-statement JDBC query timeouts (`15s`), and wall-clock job timeouts (`60s`).
3. **Logging & Report Output Boundary (`SandboxRunner.redactSecrets` / `logback-spring.xml`):**
   * Connection strings (`jdbc:postgresql://...`) and sensitive key-value pairs (`password=...`, `secret=...`, `token=...`) are scrubbed prior to logging or report serialization.