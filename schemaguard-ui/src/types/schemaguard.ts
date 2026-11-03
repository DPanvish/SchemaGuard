export type PolicyDecision = 'PASS' | 'WARN' | 'BLOCK' | 'REVIEW_REQUIRED';

export type Severity = 'CRITICAL' | 'HIGH' | 'WARN' | 'REVIEW_REQUIRED' | 'INFO';

export type ChangeType =
  | 'TABLE_ADDED'
  | 'TABLE_DROPPED'
  | 'COLUMN_ADDED'
  | 'COLUMN_DROPPED'
  | 'COLUMN_TYPE_CHANGED'
  | 'NULLABILITY_CHANGED'
  | 'DEFAULT_CHANGED'
  | 'CONSTRAINT_ADDED'
  | 'CONSTRAINT_DROPPED'
  | 'INDEX_ADDED'
  | 'INDEX_DROPPED'
  | 'VIEW_ADDED'
  | 'VIEW_DROPPED'
  | 'RENAME_CANDIDATE';

export type DependencyEdgeType =
  | 'SERVICE_READS_TABLE'
  | 'SERVICE_READS_COLUMN'
  | 'VIEW_DEPENDS_ON_OBJECT'
  | 'FOREIGN_KEY_REFERENCES_TABLE'
  | 'MIGRATION_CHANGES_OBJECT';

export interface ProjectRecord {
  id: string;
  name: string;
  dialect: string;
  repositoryUrl: string;
  createdAt: string;
}

export interface CreateProjectRequest {
  name: string;
  dialect: string;
  repositoryUrl: string;
}

export interface SourceLocation {
  file: string;
  startLine: number;
  endLine?: number;
  line?: number;
}

export interface FindingItem {
  id?: string;
  ruleId: string;
  severity: Severity;
  title: string;
  message: string;
  objectRef: string;
  sourceLocation: SourceLocation;
  evidence: string[];
  remediation: string;
  confidence?: string;
}

export interface SchemaChangeItem {
  changeType: ChangeType;
  schemaName: string;
  tableName: string;
  objectName: string;
  beforeValue: string | null;
  afterValue: string | null;
  sourceFile: string | null;
  sourceLine: number;
  notes?: string;
}

export interface DependencyEdgeItem {
  fromNode: string;
  toNode: string;
  edgeType: DependencyEdgeType;
  evidence: string;
}

export interface TriggerAnalysisRequest {
  baseCommit: string;
  headCommit: string;
  baselineSql: string;
  candidateSql: string;
  candidateFileName: string;
  contractsYaml: string;
  policyYaml: string;
  strictMode: boolean;
}

export interface AnalysisExecutionResponse {
  runId: string;
  projectId: string;
  baseCommit?: string;
  headCommit?: string;
  decision: PolicyDecision;
  exitCode: number;
  prioritizationScore: number;
  baseSnapshotHash: string;
  candidateSnapshotHash: string;
  changes: SchemaChangeItem[];
  dependencyEdges: DependencyEdgeItem[];
  contractCoverageRatio: number;
  findings: FindingItem[];
  gateRationale: string;
  markdownReport: string;
  sarifReport: string;
  startedAt?: string;
  completedAt?: string;
}

export interface ContractArtifactRecord {
  id: string;
  serviceName: string;
  version: number;
  checksum: string;
  yaml: string;
}

export interface PolicyVersionRecord {
  id: string;
  version: number;
  policyJson: string;
  createdAt: string;
}

export interface SchemaSnapshotRecord {
  id: string;
  commitSha: string;
  snapshotHash: string;
  schemaModelVersion: string;
  schemaJson: string;
}

export interface HistoricalRunSummary {
  runId: string;
  projectId: string;
  baseCommit: string;
  headCommit: string;
  decision: PolicyDecision;
  prioritizationScore: number;
  startedAt: string;
  completedAt: string;
  toolVersion?: string;
  findingsCount: number;
  markdownReport?: string;
}

export interface ProjectDetailsBundle {
  project: ProjectRecord;
  policies: PolicyVersionRecord[];
  contracts: ContractArtifactRecord[];
  recentRuns: HistoricalRunSummary[];
}

export interface RuleCatalogEntry {
  ruleId: string;
  defaultSeverity: Severity;
  title: string;
  category: string;
  postgresLockMode?: string;
  tableRewriteRisk?: 'NONE' | 'CONDITIONAL' | 'HIGH';
  description?: string;
  detectionLogic?: string;
  defaultTreatment?: string;
}

export interface StudioPreset {
  id: string;
  label: string;
  badge: PolicyDecision;
  exitCode: number;
  summary: string;
  request: TriggerAnalysisRequest;
}