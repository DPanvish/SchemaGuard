import { RuleCatalogEntry, StudioPreset } from '../types/schemaguard';

export const DEFAULT_BASELINE_SQL = `CREATE TABLE public.customers (
    id UUID PRIMARY KEY,
    email TEXT NOT NULL,
    name TEXT,
    status VARCHAR(32) DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE public.invoices (
    id UUID PRIMARY KEY,
    customer_id UUID NOT NULL REFERENCES public.customers(id),
    amount_cents BIGINT NOT NULL,
    issued_at TIMESTAMPTZ NOT NULL
);`;

export const DEFAULT_CONTRACTS_YAML = `version: 1
services:
  billing-api:
    reads:
      - table: public.customers
        columns: [id, email, status]
      - table: public.invoices
        columns: [id, customer_id, amount_cents]
  notification-worker:
    reads:
      - table: public.customers
        columns: [email]
  reporting-service:
    reads:
      - table: public.customers
        columns: [id, name, created_at]`;

export const DEFAULT_POLICY_YAML = `version: 1
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
  SG003:
    enabled: true
    severity: HIGH
  SG004:
    enabled: true
    severity: HIGH
  SG005:
    enabled: true
    severity: HIGH
    requireLargeTableProfile: true
  SG008:
    enabled: true
    severity: CRITICAL
  SG010:
    enabled: true
    severity: REVIEW_REQUIRED
reports:
  formats: [markdown, json, sarif]`;

export const STUDIO_PRESETS: StudioPreset[] = [
  {
    id: 'preset-breaking-contract',
    label: 'Appendix B.2 — Breaking Contract Drop (SG001 / SG008)',
    badge: 'BLOCK',
    exitCode: 1,
    summary: 'Drops public.customers.email while billing-api and notification-worker declare active read contracts.',
    request: {
      baseCommit: 'main@a1b2c3d',
      headCommit: 'feat/remove-email@99f8e7d',
      baselineSql: DEFAULT_BASELINE_SQL,
      candidateFileName: 'V12__remove_email.sql',
      candidateSql: `-- Candidate Migration: Remove deprecated customer email column
ALTER TABLE public.customers
    DROP COLUMN email;`,
      contractsYaml: DEFAULT_CONTRACTS_YAML,
      policyYaml: DEFAULT_POLICY_YAML,
      strictMode: true,
    },
  },
  {
    id: 'preset-safe-additive',
    label: 'Appendix B.1 — Safe Additive Column (Clean Pass)',
    badge: 'PASS',
    exitCode: 0,
    summary: 'Adds nullable preferred_language column with zero consumer contract violations or table rewrites.',
    request: {
      baseCommit: 'main@a1b2c3d',
      headCommit: 'feat/add-language@e4f5g6h',
      baselineSql: DEFAULT_BASELINE_SQL,
      candidateFileName: 'V2__add_preferred_language.sql',
      candidateSql: `-- Candidate Migration: Additive nullable column for localization
ALTER TABLE public.customers
    ADD COLUMN preferred_language TEXT;`,
      contractsYaml: DEFAULT_CONTRACTS_YAML,
      policyYaml: DEFAULT_POLICY_YAML,
      strictMode: true,
    },
  },
  {
    id: 'preset-not-null-and-index',
    label: 'Appendix B.3 — Unstaged NOT NULL & Blocking Index (SG004 / SG005)',
    badge: 'BLOCK',
    exitCode: 1,
    summary: 'Applies SET NOT NULL without a pre-validated CHECK constraint and creates an index without CONCURRENTLY.',
    request: {
      baseCommit: 'main@a1b2c3d',
      headCommit: 'feat/enforce-name-index@7c4d11a',
      baselineSql: DEFAULT_BASELINE_SQL,
      candidateFileName: 'V14__enforce_customer_name_not_null.sql',
      candidateSql: `-- Candidate Migration: Enforce NOT NULL and create lookup index
ALTER TABLE public.customers
    ALTER COLUMN name SET NOT NULL;

CREATE INDEX idx_customers_status_created
    ON public.customers (status, created_at);`,
      contractsYaml: DEFAULT_CONTRACTS_YAML,
      policyYaml: DEFAULT_POLICY_YAML,
      strictMode: true,
    },
  },
  {
    id: 'preset-narrowing-type-rewrite',
    label: 'Operational Risk — Narrowing Type & Table Rewrite (SG003 / SG012)',
    badge: 'BLOCK',
    exitCode: 1,
    summary: 'Alters public.customers.email from TEXT to VARCHAR(32), triggering an ACCESS EXCLUSIVE table rewrite.',
    request: {
      baseCommit: 'main@a1b2c3d',
      headCommit: 'perf/shrink-email-varchar@3b8e90f',
      baselineSql: DEFAULT_BASELINE_SQL,
      candidateFileName: 'V15__narrow_email_type.sql',
      candidateSql: `-- Candidate Migration: Narrow email type from TEXT to VARCHAR(32)
ALTER TABLE public.customers
    ALTER COLUMN email TYPE VARCHAR(32);`,
      contractsYaml: DEFAULT_CONTRACTS_YAML,
      policyYaml: DEFAULT_POLICY_YAML,
      strictMode: true,
    },
  },
  {
    id: 'preset-unsupported-sql',
    label: 'Uncertainty Boundary — Procedural PL/pgSQL Block (SG010)',
    badge: 'REVIEW_REQUIRED',
    exitCode: 3,
    summary: 'Executes an anonymous DO $$ procedural block outside deterministic static DDL analysis scope.',
    request: {
      baseCommit: 'main@a1b2c3d',
      headCommit: 'chore/dynamic-backfill@5d2a19c',
      baselineSql: DEFAULT_BASELINE_SQL,
      candidateFileName: 'V16__procedural_migration.sql',
      candidateSql: `-- Candidate Migration: Dynamic procedural block outside static DDL subset
DO $$
BEGIN
    EXECUTE 'LOCK TABLE public.customers IN ACCESS EXCLUSIVE MODE';
END $$;`,
      contractsYaml: DEFAULT_CONTRACTS_YAML,
      policyYaml: DEFAULT_POLICY_YAML,
      strictMode: true,
    },
  },
];

export const ENRICHED_RULE_METADATA: Record<string, Partial<RuleCatalogEntry>> = {
  SG001: {
    postgresLockMode: 'ACCESS EXCLUSIVE',
    tableRewriteRisk: 'NONE',
    detectionLogic: 'Detects DROP TABLE or DROP COLUMN operations and cross-references catalog objects, foreign keys, views, and declared service contracts.',
    defaultTreatment: 'BLOCK if policy declares destructive changes blocking or consumers are affected; otherwise CRITICAL finding.',
  },
  SG002: {
    postgresLockMode: 'ACCESS EXCLUSIVE',
    tableRewriteRisk: 'NONE',
    detectionLogic: 'Identifies conservative RENAME_CANDIDATE operations or sequence drops affecting declared consumers.',
    defaultTreatment: 'BLOCK when breaking service contract is confirmed.',
  },
  SG003: {
    postgresLockMode: 'ACCESS EXCLUSIVE',
    tableRewriteRisk: 'HIGH',
    detectionLogic: 'Detects ALTER COLUMN TYPE to a narrower or incompatible data type (e.g., TEXT -> VARCHAR(n), BIGINT -> INT).',
    defaultTreatment: 'HIGH risk; requires inspection of cast path, USING expression, and truncation potential.',
  },
  SG004: {
    postgresLockMode: 'ACCESS EXCLUSIVE',
    tableRewriteRisk: 'CONDITIONAL',
    detectionLogic: 'Detects ADD COLUMN ... NOT NULL without DEFAULT or ALTER COLUMN ... SET NOT NULL without prior NOT VALID constraint validation.',
    defaultTreatment: 'HIGH / BLOCK depending on fixture validation and active policy.',
  },
  SG005: {
    postgresLockMode: 'SHARE (Blocks Writes)',
    tableRewriteRisk: 'NONE',
    detectionLogic: 'Detects CREATE INDEX without the CONCURRENTLY keyword on existing tables.',
    defaultTreatment: 'HIGH operational-risk finding; recommend CREATE INDEX CONCURRENTLY outside transaction block.',
  },
  SG006: {
    postgresLockMode: 'SHARE UPDATE EXCLUSIVE / ACCESS EXCLUSIVE',
    tableRewriteRisk: 'CONDITIONAL',
    detectionLogic: 'Detects ADD CONSTRAINT (FOREIGN KEY / CHECK) without NOT VALID, which forces an immediate full-table validation scan.',
    defaultTreatment: 'WARN / HIGH operational risk informed by PostgreSQL ALTER TABLE semantics.',
  },
  SG007: {
    postgresLockMode: 'N/A (Checksum Integrity)',
    tableRewriteRisk: 'NONE',
    detectionLogic: 'Compares CRLF-normalized SHA-256 checksums of historical migrations against recorded baseline artifacts.',
    defaultTreatment: 'BLOCK by default; require a new forward versioned migration.',
  },
  SG008: {
    postgresLockMode: 'ACCESS EXCLUSIVE',
    tableRewriteRisk: 'NONE',
    detectionLogic: 'Evaluates reverse edges in the DependencyGraph (SERVICE_READS_TABLE, SERVICE_READS_COLUMN) against dropped or incompatibly altered schema objects.',
    defaultTreatment: 'BLOCK when contract break is confirmed.',
  },
  SG009: {
    postgresLockMode: 'ROW EXCLUSIVE',
    tableRewriteRisk: 'NONE',
    detectionLogic: 'Flags destructive DELETE or UPDATE statements inside schema migrations without a declared recovery strategy.',
    defaultTreatment: 'REVIEW_REQUIRED or HIGH risk.',
  },
  SG010: {
    postgresLockMode: 'UNKNOWN (Conservative Fail-Closed)',
    tableRewriteRisk: 'CONDITIONAL',
    detectionLogic: 'Flags SQL statements or procedural blocks marked ParserStatus.UNSUPPORTED by JSqlParserAdapter.',
    defaultTreatment: 'REVIEW_REQUIRED (Exit Code 3 in strict mode); never silently passes.',
  },
  SG011: {
    postgresLockMode: 'N/A (Catalog Inspection)',
    tableRewriteRisk: 'NONE',
    detectionLogic: 'Compares observed live PostgreSQL catalog snapshot against expected migration-derived canonical snapshot.',
    defaultTreatment: 'WARN or BLOCK according to environment criticality.',
  },
  SG012: {
    postgresLockMode: 'ACCESS EXCLUSIVE (Full Table Rewrite)',
    tableRewriteRisk: 'HIGH',
    detectionLogic: 'Identifies DDL operations (such as narrowing ALTER COLUMN TYPE or VACUUM FULL) that rewrite heap files under ACCESS EXCLUSIVE lock.',
    defaultTreatment: 'HIGH operational review using table size profile.',
  },
};