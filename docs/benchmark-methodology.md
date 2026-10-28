# SchemaGuard — Benchmark and Quality Evaluation Methodology

This document defines the repeatable procedure for measuring SchemaGuard's analysis latency, detection accuracy, and reproducibility in accordance with Sections 10.3, 13.1, and 14.3 of the project blueprint.

---

## 1. Hardware & Environment Recording Template

All published performance or quality metrics must record the exact test environment:

| Parameter | Reference Environment Value |
| :--- | :--- |
| **CPU Model & Cores** | 4+ logical cores (x86_64 / ARM64) |
| **Available Memory (RAM)** | 8 GB system RAM (512 MB cap per disposable sandbox container) |
| **Operating System** | Windows 11 / Ubuntu 24.04 LTS (`ubuntu-latest` CI runner) |
| **Java Runtime** | Eclipse Temurin OpenJDK `21.0.x` |
| **Container Runtime** | Docker Engine `26.x` with pre-pulled `postgres:16-alpine` image |
| **SchemaGuard Version** | `1.0-SNAPSHOT` (Canonical Schema Model `v1.0`) |

---

## 2. Benchmark Fixture Tiers

| Fixture Tier | Migration Count | Schema Size | Service Contracts | Representative Use Case |
| :--- | :--- | :--- | :--- | :--- |
| **Tier 1 — Small (`sample-company`)** | 2–5 files | 1–5 tables, 15 columns | 3 services (`billing-api`, `notification-worker`, `reporting-service`) | Fast local developer pre-commit check and PR smoke test. |
| **Tier 2 — Medium** | 25 files | 25 tables, 200 columns, 15 FKs, 5 views | 10 services (40 read declarations) | Typical production microservice repository history. |
| **Tier 3 — Large** | 100 files | 100 tables, 800 columns, 60 FKs, 20 views | 25 services (150 read declarations) | Monolithic core schema stress test. |

---

## 3. Benchmark Execution Protocol

1. **Clean Build:**
   ```bash
   mvn -B clean package -DskipTests
   ```
2. **Pre-pull Sandbox Image (when benchmarking `--sandbox true`):**
   ```bash
   docker pull postgres:16-alpine
   ```
3. **Warm-up Phase:** Run the CLI `check` command **5 times** to warm up OS file caches before recording timings.
4. **Measurement Phase:** Execute **25 consecutive runs** for each scenario (Safe Pass, Unsafe Contract Block, Unsupported SQL) and record wall-clock runtime (`ms`), exit code, and `candidateSnapshotHash`.

---

## 4. Quality & Performance Metrics Catalogue

| Metric Name | Measurement Definition | v1.0 Release Gate Target |
| :--- | :--- | :--- |
| **1. Scenario Detection Coverage** | `(Correctly detected seeded scenarios) / (Total supported seeded scenarios in corpus)` | `100%` (`15/15` automated JUnit regression scenarios passing). |
| **2. Precision on Curated Unsafe Corpus** | `(True policy-violation BLOCK findings) / (Total BLOCK findings emitted on corpus)` | `100%` (Zero false blocks on safe additive fixtures). |
| **3. Parser Coverage Transparency** | Proportion of unsupported SQL statements explicitly marked `ParserStatus.UNSUPPORTED` and flagged by `SG010`. | `100%` (Zero silent passes on unrecognized SQL). |
| **4. Static Analysis Latency (`--sandbox false`)** | Median (`p50`) and 95th percentile (`p95`) execution time on Tier 1 fixtures. | `p50 <= 1,500 ms`, `p95 <= 3,000 ms`. |
| **5. Container Sandbox Latency (`--sandbox true`)** | Median (`p50`) and 95th percentile (`p95`) execution time including disposable PostgreSQL container startup. | `p50 <= 6,000 ms`, `p95 <= 12,000 ms`. |
| **6. Evidence Completeness** | Percentage of emitted `Finding` objects containing non-empty `ruleId`, `severity`, `objectRef`, `sourceLocation`, `evidence[]`, and `remediation`. | `100%`. |
| **7. Deterministic Reproducibility** | Percentage of repeated identical runs producing the exact same `baseSnapshotHash`, `candidateSnapshotHash`, ordered `Finding` list, and `exitCode`. | `100%` across all 25 repetitions. |

---

## 5. Curated Regression Verification Matrix

| Regression Scenario | Automated Test / Fixture | Expected Outcome | Verified Status |
| :--- | :--- | :--- | :--- |
| Safe nullable column addition (`ADD COLUMN preferred_language TEXT`) | `Phase4RiskEngineTest` & `Phase6EndToEndWorkflowTest` | `PASS` (Exit Code `0`, 0 findings) | Verified |
| Drop column referenced by two declared services (`DROP COLUMN email`) | `Phase4RiskEngineTest` & `Phase6EndToEndWorkflowTest` | `BLOCK` (Exit Code `1`, `SG001` + `SG008`) | Verified |
| Add `NOT NULL` when synthetic fixture rows contain `NULL` | `Phase5DependencyAndSandboxTest` (`V13__set_email_not_null.sql`) | `BLOCK` (Sandbox `Status.FAILED`, SQLState `23502`, credentials redacted) | Verified |
| Modify an already-released historical migration file (`V1__initial_schema.sql`) | `Phase2SqlAnalysisTest` & `Phase4RiskEngineTest` | `BLOCK` (Exit Code `1`, `SG007` checksum mismatch) | Verified |
| Execute unsupported SQL syntax (`VACUUM FULL public.customers;`) | `Phase2SqlAnalysisTest` & `Phase6EndToEndWorkflowTest` | `REVIEW_REQUIRED` (Exit Code `3` in strict mode, `SG010`) | Verified |
| Run `CREATE INDEX` without `CONCURRENTLY` on large/unknown table | `Phase4RiskEngineTest` | `HIGH` finding (`SG005`) | Verified |
| Heuristic column rename (`DROP email` + `ADD contact_email`) | `Phase3SchemaDiffTest` | `RENAME_CANDIDATE` diff + `SG002` finding | Verified |
| Equivalent PostgreSQL type aliases (`int4` vs `INTEGER`, `VARCHAR(255)` vs `character varying(255)`) | `Phase3SchemaDiffTest` | Identical SHA-256 `canonicalHash`, 0 false diffs | Verified |