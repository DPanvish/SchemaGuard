# SchemaGuard — Database Change Safety Platform

SchemaGuard is a PostgreSQL-first database change safety platform for software engineering teams that need to evolve database schemas without accidentally breaking live applications. It analyzes proposed SQL migration files and migration histories, reconstructs baseline and candidate database states in isolated environments, identifies structural differences, evaluates declared service dependencies and operational risks, and produces an evidence-backed decision for Continuous Integration (`PASS`, `WARN`, `BLOCK`, or `REVIEW_REQUIRED`).

> **Core Design Principle:** Make every decision explainable, reproducible, and explicit about uncertainty.

---

## Contents
1. [Problem Statement & Positioning](#1-problem-statement--positioning)
2. [60-Second Quick Start Demo](#2-60-second-quick-start-demo)
3. [Developer Setup & Local Configuration](#3-developer-setup--local-configuration)
4. [CLI Contract & Exit Codes](#4-cli-contract--exit-codes)
5. [Configuration Manifests (Contracts & Policy)](#5-configuration-manifests-contracts--policy)
6. [Sample Analysis Report (Appendix A)](#6-sample-analysis-report-appendix-a)
7. [Known Limitations & Non-Goals (v1)](#7-known-limitations--non-goals-v1)
8. [Architecture & Documentation Index](#8-architecture--documentation-index)

---

## 1. Problem Statement & Positioning

A SQL migration can be 100% syntactically valid and still cause a severe production outage:
* **Breaking Consumer Contracts:** A dropped column (`DROP COLUMN`) may still be queried by an older service deployment (`billing-api`, `notification-worker`).
* **Data-Dependent Failures:** A new `SET NOT NULL` constraint may pass against an empty development database but fail against existing `NULL` rows in production.
* **Disruptive Locks & Table Rewrites:** A column type alteration (`ALTER COLUMN TYPE`) or an index creation without `CONCURRENTLY` on a large table can acquire an `ACCESS EXCLUSIVE` or write-blocking lock.
* **Historical Tampering & Drift:** Editing an already-applied historical migration file creates checksum drift across environments.

### How SchemaGuard Fits Into Your Workflow
SchemaGuard does not replace migration execution tools like Flyway or Liquibase. Instead, it provides an automated policy and impact-analysis safety gate before deployment:

| Existing Tool / Practice | What It Generally Does | SchemaGuard Contribution |
| :--- | :--- | :--- |
| **Migration Tool (Flyway)** | Applies versioned or repeatable migrations and tracks history. | Adds impact analysis, policy evaluation, migration-risk findings, and a CI decision. |
| **Database Changelog (Liquibase)** | Defines, tracks, and deploys database changes. | Evaluates proposed change safety across schema structure and declared service dependencies. |
| **Unit / Integration Tests** | Validate selected application paths and database behavior. | Adds consistent, migration-focused checks before deployment and records why a check passed or failed. |
| **Manual DBA Review** | Uses human expertise to assess deployment implications. | Standardizes common checks and produces structured evidence; routes uncertain or unsupported SQL to expert review. |

---

## 2. 60-Second Quick Start Demo

Run the following commands from a clean checkout to build the platform, run the automated regression suite, and execute safe, unsafe, and unsupported migration checks:

```bash
# 1. Compile all modules and run the automated JUnit 5 regression test suite
mvn clean package

# 2. Run a SAFE additive migration check -> Returns Exit Code 0 (PASS)
java -jar schemaguard-cli/target/schemaguard-cli-1.0-SNAPSHOT.jar check \
  --run-id safe-demo \
  --base origin/main \
  --head HEAD \
  --base-dir examples/sample-company/baseline-migrations \
  --candidate-dir examples/sample-company/safe-candidate \
  --contracts test-fixtures/sample-contracts/service-contracts.yaml \
  --policy test-fixtures/sample-contracts/policy.yaml

# 3. Run an UNSAFE contract-breaking migration check -> Returns Exit Code 1 (BLOCK)
java -jar schemaguard-cli/target/schemaguard-cli-1.0-SNAPSHOT.jar check \
  --run-id unsafe-demo \
  --base origin/main \
  --head HEAD \
  --base-dir examples/sample-company/baseline-migrations \
  --candidate-dir examples/sample-company/unsafe-candidate \
  --contracts test-fixtures/sample-contracts/service-contracts.yaml \
  --policy test-fixtures/sample-contracts/policy.yaml

# 4. Run an UNSUPPORTED SQL migration check -> Returns Exit Code 3 (REVIEW_REQUIRED)
java -jar schemaguard-cli/target/schemaguard-cli-1.0-SNAPSHOT.jar check \
  --run-id unsupported-demo \
  --base origin/main \
  --head HEAD \
  --base-dir examples/sample-company/baseline-migrations \
  --candidate-dir test-fixtures/invalid-migrations \
  --contracts test-fixtures/sample-contracts/service-contracts.yaml \
  --policy test-fixtures/sample-contracts/policy.yaml
```

After running these commands, inspect the generated Markdown, JSON, and SARIF 2.1.0 reports in:
* `.schemaguard/runs/safe-demo/`
* `.schemaguard/runs/unsafe-demo/`
* `.schemaguard/runs/unsupported-demo/`

---

## 3. Developer Setup & Local Configuration

### System Prerequisites
* **Java Development Kit (JDK):** Java 21 (Eclipse Temurin or compatible OpenJDK 21 distribution).
* **Build Tool:** Apache Maven 3.9+.
* **Container Runtime:** Docker Engine or Docker Desktop (used when running isolated PostgreSQL catalog snapshots and sandbox checks with `--sandbox`).
* **Resource Requirements:** Minimum 2 CPU cores and 4 GB RAM available (disposable PostgreSQL sandbox containers are capped at 512 MB RAM).

### Local Properties & Module-Separated Logging
SchemaGuard routes backend logs silently into module-specific and severity-specific log files (`cli/cli-info.log`, `cli/cli-debug.log`, `cli/cli-error.log`, and `core/core-info.log`, `core/core-debug.log`, `core/core-error.log`) rather than polluting terminal output.

1. Copy the example properties file:
   ```bash
   cp schemaguard-cli/src/main/resources/application.properties.example schemaguard-cli/src/main/resources/application.properties
   ```
2. Edit `schemaguard-cli/src/main/resources/application.properties` (which is ignored by Git) to set your desired local log directory:
   ```properties
   schemaguard.log.dir=logs
   ```

---

## 4. CLI Contract & Exit Codes

### Supported CLI Commands
```bash
# Initialize a .schemaguard workspace with default policy.yaml and service-contracts.yaml
java -jar schemaguard-cli/target/schemaguard-cli-1.0-SNAPSHOT.jar init

# Analyze baseline and candidate migrations against contracts and policy
java -jar schemaguard-cli/target/schemaguard-cli-1.0-SNAPSHOT.jar check \
  --run-id <id> \
  --base <git-base-ref> \
  --head <git-head-ref> \
  --base-dir <path-to-baseline-sql> \
  --candidate-dir <path-to-candidate-sql> \
  --contracts <path-to-service-contracts.yaml> \
  --policy <path-to-policy.yaml> \
  [--sandbox true|false] \
  [--strict true|false]

# Strictly validate service contracts and policy YAML schemas
java -jar schemaguard-cli/target/schemaguard-cli-1.0-SNAPSHOT.jar validate-contracts \
  --contracts <path-to-service-contracts.yaml> \
  --policy <path-to-policy.yaml>

# Verify and load a persisted run report in markdown, json, or sarif format
java -jar schemaguard-cli/target/schemaguard-cli-1.0-SNAPSHOT.jar report \
  --run <run-id> \
  --format markdown|json|sarif
```

### Stable Exit Codes for CI Enforcement
| Exit Code | Decision Outcome | Meaning |
| :--- | :--- | :--- |
| **`0`** | `PASS` / `WARN` | Required checks completed; no policy-blocking findings were detected. |
| **`1`** | `BLOCK` | One or more findings violate hard policy, or isolated sandbox execution failed. |
| **`2`** | `ERROR` | Execution failure, missing directory, or invalid YAML configuration. |
| **`3`** | `REVIEW_REQUIRED` | Required analysis is incomplete, contract coverage is missing, or SQL is unsupported under strict mode. |

---

## 5. Configuration Manifests (Contracts & Policy)

### Example Service Contract (`service-contracts.yaml`)
```yaml
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
```

### Example Policy Manifest (`policy.yaml`)
```yaml
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
```

---

## 6. Sample Analysis Report (Appendix A)

When `V12__remove_email.sql` (`ALTER TABLE public.customers DROP COLUMN email;`) is analyzed against the sample company contracts, SchemaGuard emits the following Markdown report:

> **# SCHEMAGUARD: POLICY CHECK FAILED (`BLOCK`)**  
> **Rule SG008 — Breaking database contract.** Proposed migration removes `public.customers.email`. The candidate schema no longer contains the column; the contract manifest declares reads by `billing-api` and `notification-worker`. Recommended action: use an expand–migrate–contract rollout and defer removal until required consumer and data checks pass.

| Field | Value |
| :--- | :--- |
| **Run ID** | `sg-run-2026-10-12-001` |
| **Base / candidate** | `main@<base-sha>` → `feature/remove-email@<head-sha>` |
| **Migration** | `db/migration/V12__remove_email.sql:1` |
| **Affected object** | `public.customers.email` |
| **Rule / severity** | `SG008 / CRITICAL` |
| **Evidence** | Candidate schema omits column; two declared consumer contracts reference it. |
| **Sandbox status** | Completed against synthetic fixture; no persistent database used. |
| **Decision** | **BLOCK** (Exit Code `1`) |

---

## 7. Known Limitations & Non-Goals (v1)

1. **No Absolute Proof of Production Safety:** Sandbox verification proves execution against synthetic fixtures and pinned PostgreSQL containers (`postgres:16-alpine`), not against production data skew, lock queues, or live traffic load.
2. **Declared Contract Boundary:** The dependency impact graph reflects declared YAML service contracts and catalog metadata (`FOREIGN KEY`, `VIEW`). Undeclared runtime SQL consumers or ad-hoc BI queries cannot be verified automatically.
3. **Documented PostgreSQL Subset:** SchemaGuard supports a documented PostgreSQL DDL subset in v1. Any SQL statement outside this scope is explicitly flagged as `UNSUPPORTED` (`SG010` — `REVIEW_REQUIRED`) and is never silently approved.
4. **Non-Goals for v1:** SchemaGuard does not replace Flyway/Liquibase, does not automatically deploy changes to production databases, and never allows a low aggregate numeric score to override a hard-block policy rule.

---

## 8. Architecture & Documentation Index

* **[Architecture & Data Model (`docs/architecture.md`)](docs/architecture.md)** — Module responsibilities, 8-stage execution sequence, canonical schema model, metadata persistence tables, and technical decisions.
* **[Supported PostgreSQL SQL Matrix (`docs/supported-sql.md`)](docs/supported-sql.md)** — Supported DDL operations, parser coverage status, PostgreSQL type alias normalization, and known grammar boundaries.
* **[Risk Rule Catalogue (`docs/rule-catalog.md`)](docs/rule-catalog.md)** — Complete specification of rules `SG001` through `SG012`, detection logic, evidence fields, and Expand–Migrate–Verify–Contract remediation templates.
* **[Security & Threat Model (`docs/threat-model.md`)](docs/threat-model.md)** — Assets, trust boundaries, untrusted PR SQL mitigations, timeout and memory limits, secret redaction, and residual risks.
* **[Architectural Decision Records (`docs/decisions.md`)](docs/decisions.md)** — Key engineering trade-offs behind the modular monolith, CLI-first workflow, parser adapter, and deterministic hashing.
* **[Benchmark & Quality Methodology (`docs/benchmark-methodology.md`)](docs/benchmark-methodology.md)** — Reproducible benchmark setup, fixture tiers, quality metrics, and evaluation procedures.
* **[End-to-End Portfolio Demo Script (`docs/demo-script.md`)](docs/demo-script.md)** — Step-by-step walkthrough for demonstrating safe, breaking, data-dependent, and unsupported SQL scenarios.