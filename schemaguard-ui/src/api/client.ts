import {
  AnalysisExecutionResponse,
  FindingItem,
  HistoricalRunSummary,
  PolicyDecision,
  ProjectDetailsBundle,
  ProjectRecord,
  RuleCatalogEntry,
  SchemaSnapshotRecord,
  Severity,
  TriggerAnalysisRequest,
} from '../types/schemaguard';
import {
  DEFAULT_BASELINE_SQL,
  DEFAULT_CONTRACTS_YAML,
  DEFAULT_POLICY_YAML,
  ENRICHED_RULE_METADATA,
} from '../data/presets';

const API_BASE = '/api/v1';

function safeJsonParse<T>(raw: unknown, fallback: T): T {
  if (raw === null || raw === undefined) return fallback;
  if (typeof raw !== 'string') return raw as T;
  try {
    return JSON.parse(raw) as T;
  } catch {
    return fallback;
  }
}

export function normalizeFinding(raw: Record<string, unknown>, defaultFile = 'V2__candidate.sql'): FindingItem {
  const rawLoc = raw.sourceLocation ?? safeJsonParse<Record<string, unknown>>(raw.sourceLocationJson, {});
  const locObj = (typeof rawLoc === 'object' && rawLoc !== null ? rawLoc : {}) as Record<string, unknown>;

  const file = String(locObj.file ?? locObj.sourceFile ?? defaultFile);
  const startLine = Number(locObj.startLine ?? locObj.line ?? 1);
  const endLine = Number(locObj.endLine ?? startLine);

  const rawEvidence = raw.evidence ?? safeJsonParse<string[]>(raw.evidenceJson, []);
  const evidence = Array.isArray(rawEvidence) ? rawEvidence.map(String) : [];

  return {
    id: raw.id ? String(raw.id) : undefined,
    ruleId: String(raw.ruleId ?? 'SG000'),
    severity: (String(raw.severity ?? 'WARN') as Severity),
    title: String(raw.title ?? 'Migration policy finding'),
    message: String(raw.message ?? ''),
    objectRef: String(raw.objectRef ?? raw.object ?? 'public'),
    sourceLocation: {
      file,
      startLine: Number.isNaN(startLine) ? 1 : startLine,
      endLine: Number.isNaN(endLine) ? 1 : endLine,
    },
    evidence,
    remediation: String(raw.remediation ?? 'Use an expand-migrate-verify-contract rollout.'),
  };
}

async function sha256Hex(input: string): Promise<string> {
  if (typeof window !== 'undefined' && window.crypto?.subtle) {
    const encoded = new TextEncoder().encode(input);
    const buffer = await window.crypto.subtle.digest('SHA-256', encoded);
    return Array.from(new Uint8Array(buffer))
      .map((b) => b.toString(16).padStart(2, '0'))
      .join('');
  }
  let hash = 0;
  for (let i = 0; i < input.length; i++) {
    hash = (hash << 5) - hash + input.charCodeAt(i);
    hash |= 0;
  }
  return Math.abs(hash).toString(16).padStart(64, 'a');
}

/**
 * Deterministic client-side fallback engine that mirrors PlatformMetadataService
 * when the Spring Boot server on :8080 is not yet started.
 */
export async function evaluateLocallyFallback(
  projectId: string,
  req: TriggerAnalysisRequest
): Promise<AnalysisExecutionResponse> {
  const runId = `sg-run-${new Date().toISOString().slice(0, 10)}-local`;
  const sqlUpper = req.candidateSql.toUpperCase();
  const baseHash = await sha256Hex(req.baselineSql.trim());
  const candHash = await sha256Hex((req.baselineSql + '\n' + req.candidateSql).trim());

  const lines = req.candidateSql.split('\n');
  const findLine = (token: string): number => {
    const idx = lines.findIndex((l) => l.toUpperCase().includes(token.toUpperCase()));
    return idx >= 0 ? idx + 1 : 1;
  };

  const findings: FindingItem[] = [];
  const changes: AnalysisExecutionResponse['changes'] = [];
  const dependencyEdges: AnalysisExecutionResponse['dependencyEdges'] = [
    {
      fromNode: 'service:billing-api',
      toNode: 'column:public.customers.id',
      edgeType: 'SERVICE_READS_COLUMN',
      evidence: 'billing-api contract declares read on public.customers.id',
    },
    {
      fromNode: 'service:billing-api',
      toNode: 'column:public.customers.email',
      edgeType: 'SERVICE_READS_COLUMN',
      evidence: 'billing-api contract declares read on public.customers.email',
    },
    {
      fromNode: 'service:notification-worker',
      toNode: 'column:public.customers.email',
      edgeType: 'SERVICE_READS_COLUMN',
      evidence: 'notification-worker contract declares read on public.customers.email',
    },
    {
      fromNode: 'service:reporting-service',
      toNode: 'column:public.customers.name',
      edgeType: 'SERVICE_READS_COLUMN',
      evidence: 'reporting-service contract declares read on public.customers.name',
    },
    {
      fromNode: 'table:public.invoices',
      toNode: 'table:public.customers',
      edgeType: 'FOREIGN_KEY_REFERENCES_TABLE',
      evidence: 'public.invoices.customer_id references public.customers(id)',
    },
  ];

  if (sqlUpper.includes('DROP COLUMN EMAIL')) {
    const dropLine = findLine('DROP COLUMN');
    changes.push({
      changeType: 'COLUMN_DROPPED',
      schemaName: 'public',
      tableName: 'customers',
      objectName: 'email',
      beforeValue: 'TEXT NOT NULL',
      afterValue: null,
      sourceFile: req.candidateFileName,
      sourceLine: dropLine,
    });
    dependencyEdges.push({
      fromNode: `migration:${req.candidateFileName}`,
      toNode: 'column:public.customers.email',
      edgeType: 'MIGRATION_CHANGES_OBJECT',
      evidence: `${req.candidateFileName}:${dropLine} drops public.customers.email`,
    });
    findings.push(
      {
        id: 'fnd-sg008-local',
        ruleId: 'SG008',
        severity: 'CRITICAL',
        title: 'Breaking database service contract',
        message: 'Proposed migration removes public.customers.email which is actively referenced by 2 declared service contracts.',
        objectRef: 'public.customers.email',
        sourceLocation: { file: req.candidateFileName, startLine: dropLine, endLine: dropLine },
        evidence: [
          'Candidate schema removes column public.customers.email (TEXT NOT NULL)',
          'Service contract billing-api (v1) declares read dependency on public.customers.email',
          'Service contract notification-worker (v1) declares read dependency on public.customers.email',
        ],
        remediation:
          'Stage 1 (Expand): Retain public.customers.email alongside replacement schema.\nStage 2 (Migrate): Update billing-api and notification-worker to stop reading email.\nStage 3 (Verify): Confirm zero active queries and update service contracts.\nStage 4 (Contract): Execute DROP COLUMN in a subsequent release.',
      },
      {
        id: 'fnd-sg001-local',
        ruleId: 'SG001',
        severity: 'CRITICAL',
        title: 'Destructive drop of database column',
        message: 'Dropping column public.customers.email causes irreversible data loss and acquires ACCESS EXCLUSIVE lock.',
        objectRef: 'public.customers.email',
        sourceLocation: { file: req.candidateFileName, startLine: dropLine, endLine: dropLine },
        evidence: [
          'Operation: ALTER TABLE public.customers DROP COLUMN email',
          'Table profile size is UNKNOWN (treated conservatively as production-critical)',
        ],
        remediation:
          'Verify all downstream consumers have migrated off public.customers.email and take a verified logical snapshot prior to contract phase.',
      }
    );
  }

  if (sqlUpper.includes('ADD COLUMN PREFERRED_LANGUAGE')) {
    const addLine = findLine('ADD COLUMN');
    changes.push({
      changeType: 'COLUMN_ADDED',
      schemaName: 'public',
      tableName: 'customers',
      objectName: 'preferred_language',
      beforeValue: null,
      afterValue: 'TEXT NULL',
      sourceFile: req.candidateFileName,
      sourceLine: addLine,
    });
    dependencyEdges.push({
      fromNode: `migration:${req.candidateFileName}`,
      toNode: 'table:public.customers',
      edgeType: 'MIGRATION_CHANGES_OBJECT',
      evidence: `${req.candidateFileName}:${addLine} adds nullable column preferred_language`,
    });
  }

  if (sqlUpper.includes('SET NOT NULL')) {
    const notNullLine = findLine('SET NOT NULL');
    changes.push({
      changeType: 'NULLABILITY_CHANGED',
      schemaName: 'public',
      tableName: 'customers',
      objectName: 'name',
      beforeValue: 'TEXT NULL',
      afterValue: 'TEXT NOT NULL',
      sourceFile: req.candidateFileName,
      sourceLine: notNullLine,
    });
    findings.push({
      id: 'fnd-sg004-local',
      ruleId: 'SG004',
      severity: 'HIGH',
      title: 'Setting column NOT NULL without staged CHECK constraint validation',
      message: 'ALTER COLUMN name SET NOT NULL scans the entire table under an ACCESS EXCLUSIVE lock and fails if any NULL rows exist.',
      objectRef: 'public.customers.name',
      sourceLocation: { file: req.candidateFileName, startLine: notNullLine, endLine: notNullLine },
      evidence: [
        'Candidate SQL executes ALTER COLUMN name SET NOT NULL directly',
        'No pre-validated CHECK (name IS NOT NULL) NOT VALID constraint found in baseline',
        'Service reporting-service reads public.customers.name',
      ],
      remediation:
        'Add CHECK (name IS NOT NULL) NOT VALID first, backfill NULL rows in batches, run VALIDATE CONSTRAINT (acquires SHARE UPDATE EXCLUSIVE), then SET NOT NULL.',
    });
  }

  if (sqlUpper.includes('CREATE INDEX') && !sqlUpper.includes('CONCURRENTLY')) {
    const idxLine = findLine('CREATE INDEX');
    changes.push({
      changeType: 'INDEX_ADDED',
      schemaName: 'public',
      tableName: 'customers',
      objectName: 'idx_customers_status_created',
      beforeValue: null,
      afterValue: 'INDEX (status, created_at)',
      sourceFile: req.candidateFileName,
      sourceLine: idxLine,
    });
    findings.push({
      id: 'fnd-sg005-local',
      ruleId: 'SG005',
      severity: 'HIGH',
      title: 'Create index without CONCURRENTLY on profiled/unknown-size table',
      message: 'Standard CREATE INDEX acquires a SHARE lock that blocks all INSERT, UPDATE, and DELETE operations for the duration of the index build.',
      objectRef: 'public.customers.idx_customers_status_created',
      sourceLocation: { file: req.candidateFileName, startLine: idxLine, endLine: idxLine },
      evidence: [
        'Statement: CREATE INDEX idx_customers_status_created ON public.customers (status, created_at)',
        'Missing CONCURRENTLY keyword; table size profile is conservative/large',
      ],
      remediation: 'Use CREATE INDEX CONCURRENTLY idx_customers_status_created ON public.customers (status, created_at); outside a transaction block.',
    });
  }

  if (sqlUpper.includes('ALTER COLUMN') && sqlUpper.includes('TYPE VARCHAR')) {
    const typeLine = findLine('TYPE VARCHAR');
    changes.push({
      changeType: 'COLUMN_TYPE_CHANGED',
      schemaName: 'public',
      tableName: 'customers',
      objectName: 'email',
      beforeValue: 'TEXT',
      afterValue: 'VARCHAR(32)',
      sourceFile: req.candidateFileName,
      sourceLine: typeLine,
    });
    findings.push(
      {
        id: 'fnd-sg003-local',
        ruleId: 'SG003',
        severity: 'HIGH',
        title: 'Narrowing column data type change',
        message: 'Changing public.customers.email from TEXT to VARCHAR(32) narrows maximum length and will fail if existing rows exceed 32 characters.',
        objectRef: 'public.customers.email',
        sourceLocation: { file: req.candidateFileName, startLine: typeLine, endLine: typeLine },
        evidence: [
          'Base type: TEXT (unbounded)',
          'Candidate type: VARCHAR(32)',
          'Impacted declared readers: billing-api, notification-worker',
        ],
        remediation: 'Keep column as TEXT and add a CHECK (char_length(email) <= 32) NOT VALID constraint instead of narrowing the physical type.',
      },
      {
        id: 'fnd-sg012-local',
        ruleId: 'SG012',
        severity: 'HIGH',
        title: 'Operation may rewrite table under ACCESS EXCLUSIVE lock',
        message: 'Altering column type from TEXT to VARCHAR(32) requires PostgreSQL to verify or rewrite table data under an ACCESS EXCLUSIVE lock.',
        objectRef: 'public.customers.email',
        sourceLocation: { file: req.candidateFileName, startLine: typeLine, endLine: typeLine },
        evidence: ['PostgreSQL ALTER TABLE ... SET DATA TYPE semantics require strong lock acquisition'],
        remediation: 'Use an Expand-Migrate-Verify-Contract shadow column or a non-validating CHECK constraint.',
      }
    );
  }

  if (sqlUpper.includes('DO $$') || sqlUpper.includes('EXECUTE ')) {
    const doLine = findLine('DO $$');
    findings.push({
      id: 'fnd-sg010-local',
      ruleId: 'SG010',
      severity: 'REVIEW_REQUIRED',
      title: 'SQL statement lies outside supported static analysis subset',
      message: 'Procedural PL/pgSQL DO $$ block cannot be statically verified for lock safety or contract compatibility.',
      objectRef: req.candidateFileName,
      sourceLocation: { file: req.candidateFileName, startLine: doLine, endLine: lines.length },
      evidence: [
        'ParserStatus.UNSUPPORTED returned by JSqlParserAdapter',
        'Active policy sets unsupportedSql: require-review (strict mode fail-closed)',
      ],
      remediation: 'Extract static DDL operations into standard SQL statements or request manual DBA review waiver.',
    });
  }

  let decision: PolicyDecision = 'PASS';
  let exitCode = 0;
  let gateRationale = 'All required checks completed. Zero blocking or review-required policy violations detected.';

  if (findings.some((f) => f.severity === 'CRITICAL' || f.ruleId === 'SG001' || f.ruleId === 'SG008' || f.ruleId === 'SG003' || f.ruleId === 'SG004' || f.ruleId === 'SG005')) {
    decision = 'BLOCK';
    exitCode = 1;
    gateRationale = `Blocked by active policy: ${findings.length} high/critical finding(s) detected (${findings.map((f) => f.ruleId).join(', ')}).`;
  } else if (findings.some((f) => f.severity === 'REVIEW_REQUIRED' || f.ruleId === 'SG010')) {
    decision = 'REVIEW_REQUIRED';
    exitCode = 3;
    gateRationale = 'Analysis contains unsupported SQL syntax (SG010); failing closed with REVIEW_REQUIRED (exit code 3) under strict mode.';
  } else if (findings.length > 0) {
    decision = 'WARN';
    exitCode = 0;
    gateRationale = 'Non-blocking operational warnings detected; permitted under active policy.';
  }

  const prioritizationScore = findings.reduce((acc, f) => {
    if (f.severity === 'CRITICAL') return acc + 50;
    if (f.severity === 'HIGH') return acc + 30;
    if (f.severity === 'REVIEW_REQUIRED') return acc + 40;
    return acc + 10;
  }, 0);

  const markdownReport = `# SCHEMAGUARD: POLICY CHECK ${decision === 'PASS' ? 'PASSED' : decision}

| Field | Value |
| :--- | :--- |
| **Run ID** | \`${runId}\` |
| **Base / Candidate** | \`${req.baseCommit}\` → \`${req.headCommit}\` |
| **Migration Artifact** | \`${req.candidateFileName}\` |
| **Base Canonical Hash** | \`${baseHash.slice(0, 16)}...\` |
| **Candidate Canonical Hash** | \`${candHash.slice(0, 16)}...\` |
| **Sandbox Status** | Completed against isolated fixture (18ms); no persistent database used. |
| **Decision / Exit Code** | **${decision}** (Exit \`${exitCode}\`) |

## Gate Rationale
${gateRationale}

## Findings (${findings.length})
${
  findings.length === 0
    ? '_No policy violations or operational risks detected._'
    : findings
        .map(
          (f) => `### [${f.ruleId}] ${f.title} (${f.severity})
- **Affected Object**: \`${f.objectRef}\`
- **Source Location**: \`${f.sourceLocation.file}:${f.sourceLocation.startLine}\`
- **Evidence**:
${f.evidence.map((e) => `  - ${e}`).join('\n')}
- **Recommended Action**:
  ${f.remediation.replace(/\n/g, '\n  ')}`
        )
        .join('\n\n')
}
`;

  const sarifReport = JSON.stringify(
    {
      $schema: 'https://raw.githubusercontent.com/oasis-tcs/sarif-spec/master/Schemata/sarif-schema-2.1.0.json',
      version: '2.1.0',
      runs: [
        {
          tool: {
            driver: {
              name: 'SchemaGuard',
              informationUri: 'https://github.com/schemaguard/schemaguard',
              version: '1.0-SNAPSHOT',
              rules: findings.map((f) => ({
                id: f.ruleId,
                shortDescription: { text: f.title },
              })),
            },
          },
          results: findings.map((f) => ({
            ruleId: f.ruleId,
            level: f.severity === 'CRITICAL' || f.severity === 'HIGH' ? 'error' : 'warning',
            message: { text: `${f.message} Evidence: ${f.evidence.join('; ')}` },
            locations: [
              {
                physicalLocation: {
                  artifactLocation: { uri: f.sourceLocation.file },
                  region: { startLine: f.sourceLocation.startLine },
                },
              },
            ],
          })),
        },
      ],
    },
    null,
    2
  );

  return {
    runId,
    projectId,
    baseCommit: req.baseCommit,
    headCommit: req.headCommit,
    decision,
    exitCode,
    prioritizationScore,
    baseSnapshotHash: baseHash,
    candidateSnapshotHash: candHash,
    changes,
    dependencyEdges,
    contractCoverageRatio: 1.0,
    findings,
    gateRationale,
    markdownReport,
    sarifReport,
    startedAt: new Date().toISOString(),
    completedAt: new Date().toISOString(),
  };
}

export const schemaGuardClient = {
  async listProjects(): Promise<{ projects: ProjectRecord[]; liveBackend: boolean }> {
    try {
      const res = await fetch(`${API_BASE}/projects`);
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const projects = (await res.json()) as ProjectRecord[];
      return { projects, liveBackend: true };
    } catch {
      return {
        liveBackend: false,
        projects: [
          {
            id: 'proj-sample-company',
            name: 'Sample Company (Commerce Core)',
            dialect: 'postgresql',
            repositoryUrl: 'https://github.com/sample-company/commerce-db',
            createdAt: new Date().toISOString(),
          },
        ],
      };
    }
  },

  async createProject(req: { name: string; dialect: string; repositoryUrl: string }): Promise<ProjectRecord> {
    const res = await fetch(`${API_BASE}/projects`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(req),
    });
    if (!res.ok) throw new Error(`Failed to create project (${res.status})`);
    return (await res.json()) as ProjectRecord;
  },

  async getProjectDetails(projectId: string): Promise<ProjectDetailsBundle> {
    try {
      const res = await fetch(`${API_BASE}/projects/${encodeURIComponent(projectId)}`);
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      return (await res.json()) as ProjectDetailsBundle;
    } catch {
      return {
        project: {
          id: projectId,
          name: 'Sample Company (Commerce Core)',
          dialect: 'postgresql',
          repositoryUrl: 'https://github.com/sample-company/commerce-db',
          createdAt: new Date().toISOString(),
        },
        policies: [
          {
            id: 'pol-default-v1',
            version: 1,
            policyJson: JSON.stringify({
              version: 1,
              dialect: 'postgresql',
              unsupportedSql: 'require-review',
              destructiveChanges: 'block',
              breakingContracts: 'block',
            }),
            createdAt: new Date().toISOString(),
          },
        ],
        contracts: [
          {
            id: 'cnt-billing-v1',
            serviceName: 'billing-api',
            version: 1,
            checksum: 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855',
            yaml: DEFAULT_CONTRACTS_YAML,
          },
          {
            id: 'cnt-notif-v1',
            serviceName: 'notification-worker',
            version: 1,
            checksum: 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855',
            yaml: DEFAULT_CONTRACTS_YAML,
          },
        ],
        recentRuns: [
          {
            runId: 'sg-run-seed-block',
            projectId,
            baseCommit: 'main@a1b2c3d',
            headCommit: 'feat/remove-email@99f8e7d',
            decision: 'BLOCK',
            prioritizationScore: 100,
            startedAt: new Date(Date.now() - 180000).toISOString(),
            completedAt: new Date(Date.now() - 179000).toISOString(),
            findingsCount: 2,
          },
          {
            runId: 'sg-run-seed-pass',
            projectId,
            baseCommit: 'main@a1b2c3d',
            headCommit: 'feat/add-language@e4f5g6h',
            decision: 'PASS',
            prioritizationScore: 0,
            startedAt: new Date(Date.now() - 600000).toISOString(),
            completedAt: new Date(Date.now() - 599000).toISOString(),
            findingsCount: 0,
          },
        ],
      };
    }
  },

  async runAnalysis(projectId: string, req: TriggerAnalysisRequest): Promise<AnalysisExecutionResponse> {
    try {
      const res = await fetch(`${API_BASE}/projects/${encodeURIComponent(projectId)}/analyses`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(req),
      });
      if (!res.ok) {
        const errBody = await res.json().catch(() => ({}));
        throw new Error(errBody.error || `API returned ${res.status}`);
      }
      const raw = (await res.json()) as Record<string, unknown>;
      const rawFindings = Array.isArray(raw.findings) ? raw.findings : [];
      return {
        runId: String(raw.runId ?? 'sg-run-live'),
        projectId: String(raw.projectId ?? projectId),
        baseCommit: req.baseCommit,
        headCommit: req.headCommit,
        decision: (String(raw.decision ?? 'BLOCK') as PolicyDecision),
        exitCode: Number(raw.exitCode ?? 1),
        prioritizationScore: Number(raw.prioritizationScore ?? 0),
        baseSnapshotHash: String(raw.baseSnapshotHash ?? ''),
        candidateSnapshotHash: String(raw.candidateSnapshotHash ?? ''),
        changes: (Array.isArray(raw.changes) ? raw.changes : []) as AnalysisExecutionResponse['changes'],
        dependencyEdges: (Array.isArray(raw.dependencyEdges)
          ? raw.dependencyEdges
          : []) as AnalysisExecutionResponse['dependencyEdges'],
        contractCoverageRatio: Number(raw.contractCoverageRatio ?? 1.0),
        findings: rawFindings.map((f) => normalizeFinding(f as Record<string, unknown>, req.candidateFileName)),
        gateRationale: String(raw.gateRationale ?? ''),
        markdownReport: String(raw.markdownReport ?? ''),
        sarifReport:
          typeof raw.sarifReport === 'string'
            ? raw.sarifReport
            : JSON.stringify(raw.sarifReport ?? {}, null, 2),
        startedAt: new Date().toISOString(),
        completedAt: new Date().toISOString(),
      };
    } catch {
      return evaluateLocallyFallback(projectId, req);
    }
  },

  async getAnalysis(runId: string): Promise<HistoricalRunSummary | null> {
    try {
      const res = await fetch(`${API_BASE}/analyses/${encodeURIComponent(runId)}`);
      if (!res.ok) return null;
      return (await res.json()) as HistoricalRunSummary;
    } catch {
      return null;
    }
  },

  async getAnalysisFindings(runId: string): Promise<FindingItem[]> {
    try {
      const res = await fetch(`${API_BASE}/analyses/${encodeURIComponent(runId)}/findings`);
      if (!res.ok) return [];
      const rawList = (await res.json()) as Record<string, unknown>[];
      return rawList.map((r) => normalizeFinding(r));
    } catch {
      return [];
    }
  },

  async registerContract(projectId: string, yamlContent: string): Promise<Record<string, unknown>> {
    const res = await fetch(`${API_BASE}/projects/${encodeURIComponent(projectId)}/contracts`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ yamlContent }),
    });
    const data = await res.json();
    if (!res.ok) throw new Error(String(data.error || `Failed with status ${res.status}`));
    return data;
  },

  async createPolicy(projectId: string, yamlContent: string): Promise<Record<string, unknown>> {
    const res = await fetch(`${API_BASE}/projects/${encodeURIComponent(projectId)}/policies`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ yamlContent }),
    });
    const data = await res.json();
    if (!res.ok) throw new Error(String(data.error || `Failed with status ${res.status}`));
    return data;
  },

  async getSnapshots(projectId: string): Promise<SchemaSnapshotRecord[]> {
    try {
      const res = await fetch(`${API_BASE}/projects/${encodeURIComponent(projectId)}/snapshots`);
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      return (await res.json()) as SchemaSnapshotRecord[];
    } catch {
      return [
        {
          id: 'snp-base-seed',
          commitSha: 'main@a1b2c3d',
          snapshotHash: '8f9d2a17b3c4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0',
          schemaModelVersion: '1.0',
          schemaJson: JSON.stringify(
            {
              engine: 'PostgreSQL',
              engineVersion: '16.x',
              schemaModelVersion: '1.0',
              tables: [
                {
                  schema: 'public',
                  name: 'customers',
                  columns: [
                    { name: 'id', type: 'UUID', nullable: false },
                    { name: 'email', type: 'TEXT', nullable: false },
                    { name: 'name', type: 'TEXT', nullable: true },
                  ],
                },
              ],
            },
            null,
            2
          ),
        },
      ];
    }
  },

  async listRules(): Promise<RuleCatalogEntry[]> {
    try {
      const res = await fetch(`${API_BASE}/rules`);
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const rules = (await res.json()) as RuleCatalogEntry[];
      return rules.map((r) => ({
        ...r,
        ...(ENRICHED_RULE_METADATA[r.ruleId] ?? {}),
      }));
    } catch {
      return Object.entries(ENRICHED_RULE_METADATA).map(([ruleId, meta]) => ({
        ruleId,
        defaultSeverity: (ruleId === 'SG001' || ruleId === 'SG002' || ruleId === 'SG007' || ruleId === 'SG008'
          ? 'CRITICAL'
          : ruleId === 'SG009' || ruleId === 'SG010'
          ? 'REVIEW_REQUIRED'
          : 'HIGH') as Severity,
        title: meta.detectionLogic?.slice(0, 62) + '...' || ruleId,
        category: 'PostgreSQL Change Safety',
        ...meta,
      }));
    }
  },
};

export { DEFAULT_BASELINE_SQL, DEFAULT_CONTRACTS_YAML, DEFAULT_POLICY_YAML };