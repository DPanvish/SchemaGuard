# SchemaGuard — Architectural Decision Records (ADRs)

This document records the foundational engineering decisions and trade-offs made for SchemaGuard v1.0.

---

## ADR-001: PostgreSQL-First Engine Scope
* **Status:** Accepted
* **Context:** Database engines (PostgreSQL, MySQL, Oracle, SQL Server, CockroachDB) have vastly different DDL lock semantics, transactional DDL behavior, system catalogs, and type alias rules.
* **Decision:** Constrain v1.0 exclusively to PostgreSQL (`postgres:16-alpine` pinned container baseline).
* **Consequences:** Allows SchemaGuard to encode accurate, PostgreSQL-specific operational rules (`CREATE INDEX CONCURRENTLY`, `ALTER COLUMN SET NOT NULL` row validation, `information_schema` + `pg_catalog` inspection, and PostgreSQL type alias normalization) instead of shallow, generic keyword matching.

---

## ADR-002: Modular Monolith & CLI-First Execution Model
* **Status:** Accepted
* **Context:** Teams need a deterministic migration gate that runs locally on a developer laptop for ₹0 and inside GitHub Actions pull requests without requiring hosted cloud infrastructure.
* **Decision:** Package SchemaGuard as a single multi-module Maven repository (`schemaguard-core`, `schemaguard-cli`, `schemaguard-api`) with a CLI-first execution contract.
* **Consequences:** Developers and CI pipelines can run a single `java -jar schemaguard-cli.jar check` command with zero external service dependencies, while `schemaguard-api` reuses the exact same `SchemaGuardAnalysisOrchestrator` for REST and Web UI workflows.

---

## ADR-003: SQL Parser Adapter & Explicit `UNSUPPORTED` State
* **Status:** Accepted
* **Context:** Open-source Java SQL parsers (`JSqlParser`) can parse standard DDL into an AST, but may fail or partially parse vendor-specific PostgreSQL extensions (`VACUUM FULL`, `DO $$` blocks, complex partitioning clauses).
* **Decision:** Isolate `JSqlParser` behind the `MigrationParser` interface (`JSqlParserAdapter`) and classify every operation as `SUPPORTED`, `PARTIAL`, or `UNSUPPORTED`.
* **Consequences:** "The parser returned an AST" is never equated with "the platform fully understands the statement." Unrecognized syntax triggers rule `SG010` (`REVIEW_REQUIRED`, Exit Code `3` in strict mode) so SchemaGuard never creates false confidence.

---

## ADR-004: Canonical Schema Normalization & SHA-256 Fingerprinting
* **Status:** Accepted
* **Context:** Raw catalog queries or DDL statements can order tables, columns, and constraints differently or use equivalent PostgreSQL type aliases (`int4` vs `integer`, `varchar(255)` vs `character varying(255)`).
* **Decision:** Normalize all PostgreSQL type aliases, sort all schemas, tables, columns, constraints, indexes, views, and sequences alphabetically, serialize via Jackson with sorted keys, and compute a SHA-256 `canonicalHash`.
* **Consequences:** Identical schemas always yield the exact same `canonicalHash` across Windows, macOS, and Linux, enabling fast equality checks and drift detection (`SG011`).

---

## ADR-005: Relational / In-Memory Directed Dependency Graph
* **Status:** Accepted
* **Context:** Blast-radius analysis requires traversing relationships between services, tables, columns, views, foreign keys, and migrations.
* **Decision:** Represent the dependency graph in memory (`DependencyGraph` and `DependencyGraphBuilder`) backed by version-controlled YAML manifests (`service-contracts.yaml`) and relational metadata tables rather than requiring Neo4j or an external graph database.
* **Consequences:** Keeps local setup at ₹0 with sub-millisecond reverse-edge traversal while explicitly tracking table contract coverage ratios.

---

## ADR-006: Hard Policy Gates Over Aggregate Risk Scores
* **Status:** Accepted
* **Context:** Relying on a single numeric risk score (e.g., "Score 35/100 = Pass") can allow a single catastrophic change (such as dropping a column used by `billing-api`) to pass if no other warnings exist.
* **Decision:** Use `PolicyGate` to enforce discrete outcomes (`PASS`, `WARN`, `BLOCK`, `REVIEW_REQUIRED`). Compute a numeric `prioritizationScore` strictly for sorting/triage, never to override a hard-block rule.
* **Consequences:** Every `BLOCK` or `REVIEW_REQUIRED` decision is directly traceable to specific rule IDs, contract evidence, or sandbox failures.