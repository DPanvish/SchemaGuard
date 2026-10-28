# SchemaGuard — Supported PostgreSQL SQL Matrix

* **Target Database Engine:** PostgreSQL (`postgres:16-alpine` pinned container image; compatible with PostgreSQL 15–18 DDL catalogs)
* **SQL Parser Library:** `com.github.jsqlparser:jsqlparser:4.6` (isolated behind `com.schemaguard.core.parser.MigrationParser`)
* **Canonical Schema Model Version:** `v1.0`

---

## 1. Supported DDL Statement Matrix

| Category | SQL Statement / Clause | Operation Type | Parser Status | Catalog & Diff Support | Triggered Rules |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Table Creation** | `CREATE TABLE [IF NOT EXISTS] schema.table (...)` | `CREATE_TABLE` | `SUPPORTED` | Extracts columns, normalized types, `PRIMARY KEY`, and `NOT NULL` constraints. | `SG004` |
| **Table Removal** | `DROP TABLE [IF EXISTS] schema.table` | `DROP_TABLE` | `SUPPORTED` | Emits `TABLE_DROPPED`; evaluates table-level and column-level service contracts, views, and foreign keys. | `SG001`, `SG008` |
| **Column Addition** | `ALTER TABLE schema.table ADD [COLUMN] col type [NOT NULL]` | `ADD_COLUMN` | `SUPPORTED` | Emits `COLUMN_ADDED`; checks whether a `NOT NULL` column is added without a safe backfill/default strategy. | `SG002`, `SG004` |
| **Column Removal** | `ALTER TABLE schema.table DROP [COLUMN] col` | `DROP_COLUMN` | `SUPPORTED` | Emits `COLUMN_DROPPED` and checks for simultaneous same-type column additions (`RENAME_CANDIDATE`). | `SG001`, `SG002`, `SG008` |
| **Column Type Change** | `ALTER TABLE schema.table ALTER [COLUMN] col TYPE new_type` | `ALTER_COLUMN_TYPE` | `PARTIAL` | Emits `COLUMN_TYPE_CHANGED` when normalized types differ; evaluates narrowing casts and table rewrite lock risk. | `SG003`, `SG008`, `SG012` |
| **Column Nullability** | `ALTER TABLE schema.table ALTER [COLUMN] col SET NOT NULL` | `ALTER_COLUMN_TYPE` | `PARTIAL` | Emits `COLUMN_NULLABILITY_CHANGED` (`NULLABLE` → `NOT NULL`); warns on existing-row scan and validates against synthetic NULL rows in sandbox. | `SG004` |
| **Drop Not Null** | `ALTER TABLE schema.table ALTER [COLUMN] col DROP NOT NULL` | `ALTER_COLUMN_TYPE` | `PARTIAL` | Emits `COLUMN_NULLABILITY_CHANGED` (`NOT NULL` → `NULLABLE`). | — |
| **Table Constraints** | `ALTER TABLE schema.table ADD CONSTRAINT name ...` | `ADD_CONSTRAINT` | `SUPPORTED` | Captures `PRIMARY KEY`, `UNIQUE`, `FOREIGN KEY`, and `CHECK` constraints; flags row-scan/lock implications. | `SG006` |
| **Index Creation** | `CREATE [UNIQUE] INDEX name ON schema.table (cols)` | `ADD_INDEX` | `SUPPORTED` | Emits `INDEX_ADDED`; checks for missing `CONCURRENTLY` on tables profiled as `LARGE`, `CRITICAL`, or `UNKNOWN`. | `SG005` |
| **Views & Sequences** | `CREATE VIEW`, `CREATE SEQUENCE` | `UNKNOWN_OR_UNSUPPORTED` (Static) / Supported in Catalog Snapshotter | `PARTIAL` | `PostgresCatalogSnapshotter` inspects `information_schema.views` and `information_schema.sequences` during container replay. | `SG001`, `SG002`, `SG010` |
| **Administrative / Maintenance SQL** | `VACUUM FULL`, `CLUSTER`, `REINDEX`, `ANALYZE`, `GRANT`, `REVOKE` | `UNKNOWN_OR_UNSUPPORTED` | `UNSUPPORTED` | Explicitly flagged as `ParserStatus.UNSUPPORTED`; never silently passes. | `SG010` (`REVIEW_REQUIRED`) |
| **Procedural / PLpgSQL / Triggers** | `DO $$ ... $$`, `CREATE FUNCTION`, `CREATE PROCEDURE`, `CREATE TRIGGER` | `UNKNOWN_OR_UNSUPPORTED` | `UNSUPPORTED` | Explicitly flagged as `ParserStatus.UNSUPPORTED`; requires human DBA review under strict mode. | `SG010` (`REVIEW_REQUIRED`) |

---

## 2. PostgreSQL Data Type Alias Normalization

PostgreSQL accepts multiple SQL standard and internal catalog names for identical data types. `CanonicalSchemaNormalizer` maps all aliases to a single canonical name before computing hashes or running `SchemaDiffEngine`:

| Raw SQL / Catalog Type Input | Normalized Canonical Type |
| :--- | :--- |
| `INT`, `INTEGER`, `INT4` | `integer` |
| `BIGINT`, `INT8` | `bigint` |
| `SMALLINT`, `INT2` | `smallint` |
| `VARCHAR(n)`, `CHARACTER VARYING(n)` | `character varying(n)` |
| `VARCHAR`, `CHARACTER VARYING` | `character varying` |
| `CHAR(n)`, `CHARACTER(n)`, `BPCHAR(n)` | `character(n)` |
| `BOOL`, `BOOLEAN` | `boolean` |
| `FLOAT8`, `DOUBLE PRECISION` | `double precision` |
| `FLOAT4`, `REAL` | `real` |
| `TIMESTAMPTZ`, `TIMESTAMP WITH TIME ZONE` | `timestamp with time zone` |
| `TIMETZ`, `TIME WITH TIME ZONE` | `time with time zone` |
| `DECIMAL(p,s)`, `NUMERIC(p,s)` | `numeric(p,s)` |

---

## 3. Narrowing & Risky Type Transitions (`SG003` & `SG012`)

`StandardRiskRules` treats any type change on a `LARGE`, `CRITICAL`, or `UNKNOWN` table as a potential table-rewrite lock hazard (`SG012`) and specifically flags narrowing transitions (`SG003`), including:
* `bigint` → `integer` or `smallint`
* `integer` → `smallint`
* `text` → `character varying`
* `double precision` → `real`
* `timestamp with time zone` → `timestamp without time zone`

---

## 4. Known Parser Boundaries & Fail-Closed Guarantee

1. **No Silent Approval of Unknown Syntax:** Whenever `JSqlParser` throws a `JSQLParserException` or returns a `Statement` subclass outside the supported matrix above, `JSqlParserAdapter` emits a `NormalizedOperation` with `OperationType.UNKNOWN_OR_UNSUPPORTED` and `ParserStatus.UNSUPPORTED`.
2. **Strict Mode Enforcement:** Under strict policy mode (`unsupportedSql: require-review`), rule `SG010` transitions the run decision to `REVIEW_REQUIRED` and returns CLI Exit Code `3`.