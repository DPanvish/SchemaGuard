# SchemaGuard — Risk Rule Catalogue (`SG001`–`SG012`)

Every finding emitted by SchemaGuard includes a stable `ruleId`, `severity`, `title`, `message`, `objectRef`, `sourceLocation` (`file` and `line`), structured `evidence[]`, a deterministic `remediation` plan, and a `confidenceMarker`.

---

## 1. Complete Rule Reference Table

| Rule ID | Title | Default Severity | Detection Logic | Policy Configuration | Supporting Evidence | Automated Test Fixture |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **SG001** | Destructive drop of database object | `CRITICAL` (if consumers or critical table) / `HIGH` | Triggered when `SchemaDiffEngine` detects `TABLE_DROPPED` or `COLUMN_DROPPED`. Checks `AnalysisContext` for declared consumers and `TableProfile.productionCritical()`. | `rules.SG001.enabled`, `rules.SG001.severity`, `policy.destructiveChanges` | Candidate diff removal, list of impacted service names, and table criticality flag. | `test-fixtures/unsafe-migrations/V12__remove_email.sql` & `Phase4RiskEngineTest` |
| **SG002** | Potential breaking object rename | `CRITICAL` (if consumers) / `REVIEW_REQUIRED` | Triggered when `SchemaDiffEngine` detects a `RENAME_CANDIDATE` (a column dropped and a column of the identical normalized type added on the same table). | `rules.SG002.enabled`, `rules.SG002.severity` | Dropped column name, added column name, shared data type, and declared consumers of the old column name. | `Phase3SchemaDiffTest` & `Phase4RiskEngineTest` |
| **SG003** | Potentially incompatible or narrowing data type change | `HIGH` | Triggered when `SchemaDiffEngine` detects `COLUMN_TYPE_CHANGED` between two distinct normalized types (e.g., `bigint` → `integer`, `text` → `character varying`). | `rules.SG003.enabled`, `rules.SG003.severity` | Before normalized type, after normalized type, and migration file/line location. | `Phase3SchemaDiffTest` & `Phase4RiskEngineTest` |
| **SG004** | Setting column `NOT NULL` or adding required column | `HIGH` (`SET NOT NULL`) / `WARN` (`ADD COLUMN NOT NULL`) | Triggered when `COLUMN_NULLABILITY_CHANGED` transitions from `NULLABLE` to `NOT NULL`, or when a new column is added with `NOT NULL`. | `rules.SG004.enabled`, `rules.SG004.severity` | Nullability transition metadata and PostgreSQL row-validation semantics note. | `test-fixtures/unsafe-migrations/V13__set_email_not_null.sql` |
| **SG005** | Index creation without `CONCURRENTLY` | `HIGH` | Triggered when `NormalizedOperation` is `ADD_INDEX`, `concurrently` is `false`, and the target table's `TableProfile` is `LARGE`, `UNKNOWN`, or `productionCritical`. | `rules.SG005.enabled`, `rules.SG005.severity`, `rules.SG005.requireLargeTableProfile` | Missing `CONCURRENTLY` flag and table size profile (`UNKNOWN` is never treated as `SMALL`). | `Phase4RiskEngineTest.shouldTriggerSG005SG007AndSG010...` |
| **SG006** | Constraint addition may scan rows and acquire locks | `WARN` | Triggered when `SchemaDiffEngine` detects `FOREIGN_KEY_ADDED`, `CHECK_CONSTRAINT_ADDED`, or `UNIQUE_CONSTRAINT_ADDED`. | `rules.SG006.enabled`, `rules.SG006.severity` | Constraint type, constraint name, and target table/columns. | `Phase4RiskEngineTest` |
| **SG007** | Modified historical migration file | `CRITICAL` | Triggered when `MigrationHistoryValidator` detects a SHA-256 checksum mismatch between a baseline migration file and a candidate file of the same name. | `rules.SG007.enabled`, `rules.SG007.severity`, `policy.migrationHistoryChanges` | Expected baseline SHA-256 hash and actual candidate SHA-256 hash. | `Phase2SqlAnalysisTest` & `Phase4RiskEngineTest` |
| **SG008** | Breaking database service contract | `CRITICAL` | Triggered when `COLUMN_DROPPED`, `TABLE_DROPPED`, or `COLUMN_TYPE_CHANGED` affects an object with one or more `SERVICE_READS_COLUMN` or `SERVICE_READS_TABLE` edges in `DependencyGraph`. | `rules.SG008.enabled`, `rules.SG008.severity`, `policy.breakingContracts` | Structural change type and exact list of dependent services (`billing-api`, `notification-worker`). | `examples/sample-company/unsafe-candidate/V12__remove_email.sql` |
| **SG009** | Destructive DML without declared backup strategy | `REVIEW_REQUIRED` | Triggered when an operation carries `destructiveDml=true` and `AnalysisContext.backupStrategyDeclared()` is `false`. | `rules.SG009.enabled`, `rules.SG009.severity` | Target object name and missing backup strategy declaration in context. | `Phase4RiskEngineTest` |
| **SG010** | SQL statement outside supported analysis scope | `REVIEW_REQUIRED` | Triggered for every `NormalizedOperation` where `parserStatus == ParserStatus.UNSUPPORTED`. | `rules.SG010.enabled`, `rules.SG010.severity`, `policy.unsupportedSql` | `ParserStatus.UNSUPPORTED` marker, source file/line, and parser failure reason. | `test-fixtures/invalid-migrations/V99__unsupported_syntax.sql` |
| **SG011** | Environment schema drift detected | `HIGH` | Triggered when `AnalysisContext.observedDriftDetected()` is `true` (observed database catalog hash differs from migration-derived baseline hash). | `rules.SG011.enabled`, `rules.SG011.severity` | Drift evidence list and canonical snapshot hash comparison. | `Phase4RiskEngineTest` |
| **SG012** | Operation may rewrite table and acquire `ACCESS EXCLUSIVE` lock | `HIGH` | Triggered when `COLUMN_TYPE_CHANGED` occurs on a table whose `TableProfile` is `LARGE`, `UNKNOWN`, or `productionCritical`. | `rules.SG012.enabled`, `rules.SG012.severity` | Before/after data types and table operational profile (`sizeClass`, `productionCritical`). | `Phase4RiskEngineTest` |

---

## 2. Expand–Migrate–Verify–Contract Remediation Strategy

For destructive or contract-breaking changes (`SG001`, `SG002`, `SG003`, `SG008`, `SG012`), SchemaGuard generates deterministic 4-stage remediation templates (`RemediationTemplates.java`) rather than risky auto-generated SQL:

| Stage | Engineering Action | Exit Criteria |
| :--- | :--- | :--- |
| **1. Expand** | Add the replacement column or table structure as `NULLABLE` while keeping the existing column/contract intact. | Existing service deployments continue to run without error; additive migration is deployed. |
| **2. Migrate** | Update application writers to dual-write to both old and new columns; backfill historical rows in controlled batches. | New application version is live; backfill progress and error counts are verified. |
| **3. Verify** | Validate data completeness and switch all declared consumer services (`billing-api`, `notification-worker`) to read from the new column. | Zero active readers remain on the legacy column across all deployed service versions. |
| **4. Contract** | Remove the obsolete column in a separate follow-up migration after updating `service-contracts.yaml`. | Policy check passes; backup and recovery implications are documented. |

### Explicit Disclosure of Unverifiable Preconditions
Every Expand–Migrate–Contract remediation plan includes the following mandatory disclosure:
> *Note (Unverifiable Preconditions): SchemaGuard cannot automatically verify undeclared runtime SQL consumers, ad-hoc BI queries, or whether older application deployment versions are still active in production.*