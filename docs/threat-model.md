# SchemaGuard — Security and Threat Model

## 1. Security Context & Trust Boundaries
SchemaGuard processes SQL migrations and YAML manifests originating from developer pull requests. In a CI pipeline, pull-request inputs must be treated as **potentially untrusted code**. SchemaGuard's parser, YAML validators, and disposable PostgreSQL sandbox form a defense-in-depth security boundary designed to **fail closed**.

### Primary Assets
1. **CI Runner & Local Host Resources:** CPU, memory, disk space, and network interfaces of the host executing SchemaGuard.
2. **Database Credentials & Secrets:** Passwords, tokens, and JDBC connection URLs in environment variables or error traces.
3. **Integrity of Policy Enforcement:** Ensuring malformed SQL, unsupported PostgreSQL extensions, or incomplete service contracts never result in a false `PASS`.
4. **Platform Metadata & Audit History:** Checksums, snapshots, service contracts, policies, and generated reports.

### Trust Boundaries
* **Boundary 1 — Repository Input Ingestion:** Untrusted `.sql` migration files, `service-contracts.yaml`, and `policy.yaml` enter `schemaguard-cli` or `schemaguard-api`.
* **Boundary 2 — Static Analysis & AST Parsing:** `JSqlParserAdapter` and `Jackson` YAML mappers parse untrusted text into internal models.
* **Boundary 3 — Isolated Container Execution:** `SandboxRunner` and `PostgresCatalogSnapshotter` execute untrusted SQL inside disposable Docker containers.
* **Boundary 4 — Log & Report Emission:** `ReportGenerator` and `Logback` write findings and diagnostic logs to disk.

---

## 2. Threat Matrix, Mitigations, and Verification Evidence

| Threat ID | Threat Description | Mitigation Implemented in SchemaGuard | Verification / Automated Test Evidence |
| :--- | :--- | :--- | :--- |
| **TM-01** | **Untrusted SQL attempts destructive operations against persistent databases.** | Pull-request SQL is never executed against production or persistent databases. Container replay uses ephemeral Testcontainers instances (`postgres:16-alpine`) destroyed immediately via `try-with-resources`. | `SandboxRunner.executeInDisposableContainer` & `PostgresCatalogSnapshotter.replayAndCaptureInSandbox`. |
| **TM-02** | **Malicious or buggy SQL hangs CI indefinitely (Denial of Service).** | `SandboxRunner` enforces a per-statement JDBC timeout (`stmt.setQueryTimeout(15)`), a single-thread `Future.get()` wall-clock timeout (`60s`), and a 512 MB Docker container memory cap (`withMemory(512MB)`). | `Phase5DependencyAndSandboxTest` & `SandboxRunner.executeAgainstConnection` timeout handling (`Status.TIMED_OUT`). |
| **TM-03** | **Credentials or JDBC URLs leak in logs, console output, or PR reports.** | Console logging is disabled (`<root level="OFF" />`); module logs write only to local files. `SandboxRunner.redactSecrets()` scrubs `password=`, `secret=`, `token=`, and `jdbc:postgresql://...` strings from all error messages. | `Phase5DependencyAndSandboxTest.shouldCaptureDataDependentSandboxFailureAndRedactCredentials`. |
| **TM-04** | **Unsupported or obfuscated SQL syntax bypasses static rules and produces a false `PASS`.** | `JSqlParserAdapter` catches all parser exceptions and unrecognized statement types, emitting `ParserStatus.UNSUPPORTED`. Rule `SG010` flags these as `REVIEW_REQUIRED` (Exit Code `3` in strict mode). | `Phase2SqlAnalysisTest.shouldExplicitlyFlagUnsupportedSqlAndNeverSilentlyPass` & `Phase6EndToEndWorkflowTest`. |
| **TM-05** | **Developer modifies an already-released historical migration file.** | `MigrationChecksumService` normalizes line endings (`CRLF` → `LF`) and computes SHA-256 hashes. `MigrationHistoryValidator` detects mismatches against baseline history and triggers rule `SG007` (`CRITICAL` / `BLOCK`). | `Phase2SqlAnalysisTest.shouldDetectModifiedHistoricalMigrationViaChecksum`. |
| **TM-06** | **Malformed or injected fields in `policy.yaml` or `service-contracts.yaml` silently weaken checks.** | `PolicyLoader` and `ServiceContractLoader` enable `DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES` and strictly validate version numbers, dialects, rule IDs (`SG001`–`SG012`), and actions. | `Phase5DependencyAndSandboxTest.shouldRejectUnknownYamlFields...` & `Phase6PolicyAndReportingTest`. |
| **TM-07** | **Missing service contracts create false confidence that a column drop has zero consumers.** | `DependencyGraph` tracks which base tables have declared contracts. `SchemaGuardAnalysisOrchestrator` checks `isTableCovered()` on destructive changes and triggers `REVIEW_REQUIRED` when coverage is incomplete. | `Phase5DependencyAndSandboxTest` coverage gap assertion (`hasContractCoverage == false`). |
| **TM-08** | **Missing table size metadata causes large production tables to be treated as small.** | `TableProfile.unknown()` assigns `SizeClass.UNKNOWN` when no profile is provided. Rules `SG005` and `SG012` treat `UNKNOWN` with the same operational caution as `LARGE`. | `TableProfile.isLargeOrUnknownOrCritical()` & `Phase4RiskEngineTest`. |

---

## 3. Safe Defaults Checklist
1. **Disposable Execution Only:** Sandbox testing defaults to local ephemeral containers or static DDL reconstruction; no production JDBC URL configuration exists in the CLI check workflow.
2. **Fail-Closed Strict Mode:** `strictMode` defaults to `true` in `CliRunner`, causing `REVIEW_REQUIRED` outcomes (`SG010` or missing contract coverage) to exit with code `3`.
3. **Hard Blocks Over Scores:** In `PolicyGate`, a `BLOCK` trigger (`SG001`, `SG007`, `SG008`, or sandbox failure) unconditionally sets the outcome to `DecisionOutcome.BLOCK` (Exit Code `1`), regardless of the numeric prioritization score.
4. **No Secrets in Version Control:** `.gitignore` excludes `application.properties` and `.env`; only `application.properties.example` with non-secret placeholders is committed.

---

## 4. Residual Risks
* **Incomplete Intra-Table Contract Declarations:** If a service contract declares reads on `public.customers` (`[id]`)—marking the table as covered—but omits `email` due to human error, static contract verification cannot detect that undeclared column usage.
* **Synthetic Fixture Scale vs Production Concurrency:** Passing a sandbox check proves DDL validity against the synthetic fixture, but cannot replicate lock queuing caused by long-running production transactions.