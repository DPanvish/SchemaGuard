import React, { useEffect, useState, useCallback } from 'react';
import {
  Shield,
  Play,
  Database,
  GitBranch,
  FileCode2,
  Network,
  GitCompare,
  FileText,
  History,
  BookOpen,
  Plus,
  CheckCircle2,
  XCircle,
  AlertTriangle,
  HelpCircle,
  UploadCloud,
  Server,
} from 'lucide-react';
import {
  AnalysisExecutionResponse,
  PolicyDecision,
  ProjectDetailsBundle,
  ProjectRecord,
  RuleCatalogEntry,
  SchemaSnapshotRecord,
  TriggerAnalysisRequest,
} from './types/schemaguard';
import { STUDIO_PRESETS } from './data/presets';
import { schemaGuardClient } from './api/client';
import { FindingsInspector } from './components/FindingsInspector';
import { StructuralDiffView } from './components/StructuralDiffView';
import { BlastRadiusGraph } from './components/BlastRadiusGraph';
import { ArtifactReportViewer } from './components/ArtifactReportViewer';
import { AuditHistoryView } from './components/AuditHistoryView';
import { RuleCatalogueView } from './components/RuleCatalogueView';

type PrimaryNavTab = 'studio' | 'history' | 'rules';
type StudioInspectionTab = 'findings' | 'diff' | 'graph' | 'artifacts';
type EditorBufferTab = 'candidateSql' | 'baselineSql' | 'contractsYaml' | 'policyYaml';

const DECISION_BANNER: Record<
  PolicyDecision,
  { bg: string; border: string; text: string; icon: React.ReactNode; label: string }
> = {
  PASS: {
    bg: 'bg-emerald-500/10',
    border: 'border-emerald-500/30',
    text: 'text-emerald-400',
    icon: <CheckCircle2 className="w-4 h-4 text-emerald-400" />,
    label: 'PASS (EXIT CODE 0)',
  },
  WARN: {
    bg: 'bg-yellow-500/10',
    border: 'border-yellow-500/30',
    text: 'text-yellow-300',
    icon: <AlertTriangle className="w-4 h-4 text-yellow-300" />,
    label: 'WARN (EXIT CODE 0)',
  },
  BLOCK: {
    bg: 'bg-red-500/10',
    border: 'border-red-500/30',
    text: 'text-red-400',
    icon: <XCircle className="w-4 h-4 text-red-400" />,
    label: 'BLOCK (EXIT CODE 1)',
  },
  REVIEW_REQUIRED: {
    bg: 'bg-purple-500/10',
    border: 'border-purple-500/30',
    text: 'text-purple-300',
    icon: <HelpCircle className="w-4 h-4 text-purple-300" />,
    label: 'REVIEW_REQUIRED (EXIT CODE 3)',
  },
};

export const App: React.FC = () => {
  const [primaryNav, setPrimaryNav] = useState<PrimaryNavTab>('studio');
  const [inspectionTab, setInspectionTab] = useState<StudioInspectionTab>('findings');
  const [editorTab, setEditorTab] = useState<EditorBufferTab>('candidateSql');

  const [projects, setProjects] = useState<ProjectRecord[]>([]);
  const [selectedProjectId, setSelectedProjectId] = useState<string>('proj-sample-company');
  const [projectBundle, setProjectBundle] = useState<ProjectDetailsBundle | null>(null);
  const [snapshots, setSnapshots] = useState<SchemaSnapshotRecord[]>([]);
  const [rules, setRules] = useState<RuleCatalogEntry[]>([]);
  const [liveBackend, setLiveBackend] = useState<boolean>(false);

  const [activePresetId, setActivePresetId] = useState<string>(STUDIO_PRESETS[0].id);
  const [requestForm, setRequestForm] = useState<TriggerAnalysisRequest>(
    STUDIO_PRESETS[0].request
  );
  const [analysisResult, setAnalysisResult] = useState<AnalysisExecutionResponse | null>(null);
  const [isRunning, setIsRunning] = useState<boolean>(false);
  const [highlightedLine, setHighlightedLine] = useState<number | null>(null);
  const [statusToast, setStatusToast] = useState<string | null>(null);

  const [showNewProjectModal, setShowNewProjectModal] = useState<boolean>(false);
  const [newProjectName, setNewProjectName] = useState<string>('');
  const [newProjectRepo, setNewProjectRepo] = useState<string>('');

  const notify = (msg: string) => {
    setStatusToast(msg);
    setTimeout(() => setStatusToast(null), 3200);
  };

  const refreshWorkspace = useCallback(async (projectId: string) => {
    const [{ projects: projList, liveBackend: isLive }, bundle, snapList, ruleList] =
      await Promise.all([
        schemaGuardClient.listProjects(),
        schemaGuardClient.getProjectDetails(projectId),
        schemaGuardClient.getSnapshots(projectId),
        schemaGuardClient.listRules(),
      ]);
    setProjects(projList);
    setLiveBackend(isLive);
    setProjectBundle(bundle);
    setSnapshots(snapList);
    setRules(ruleList);
  }, []);

  const executeAnalysis = useCallback(
    async (reqToRun: TriggerAnalysisRequest) => {
      setIsRunning(true);
      try {
        const result = await schemaGuardClient.runAnalysis(selectedProjectId, reqToRun);
        setAnalysisResult(result);
        await refreshWorkspace(selectedProjectId);
      } catch (err) {
        notify(err instanceof Error ? err.message : 'Analysis execution failed');
      } finally {
        setIsRunning(false);
      }
    },
    [selectedProjectId, refreshWorkspace]
  );

  useEffect(() => {
    void refreshWorkspace(selectedProjectId);
    void executeAnalysis(STUDIO_PRESETS[0].request);
  }, [selectedProjectId, refreshWorkspace, executeAnalysis]);

  const handleSelectPreset = (presetId: string) => {
    const found = STUDIO_PRESETS.find((p) => p.id === presetId);
    if (!found) return;
    setActivePresetId(found.id);
    setRequestForm(found.request);
    setHighlightedLine(null);
    void executeAnalysis(found.request);
  };

  const handleJumpToLine = (line: number) => {
    setPrimaryNav('studio');
    setEditorTab('candidateSql');
    setHighlightedLine(line);
  };

  const handleRegisterContract = async () => {
    try {
      const res = await schemaGuardClient.registerContract(
        selectedProjectId,
        requestForm.contractsYaml
      );
      notify(`Registered contract checksum ${String(res.checksum ?? '').slice(0, 12)}...`);
      await refreshWorkspace(selectedProjectId);
    } catch (err) {
      notify(err instanceof Error ? err.message : 'Contract YAML validation failed');
    }
  };

  const handlePublishPolicy = async () => {
    try {
      const res = await schemaGuardClient.createPolicy(
        selectedProjectId,
        requestForm.policyYaml
      );
      notify(`Published policy version ${String(res.id ?? '')}`);
      await refreshWorkspace(selectedProjectId);
    } catch (err) {
      notify(err instanceof Error ? err.message : 'Policy YAML validation failed');
    }
  };

  const handleCreateProject = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!newProjectName.trim()) return;
    try {
      const created = await schemaGuardClient.createProject({
        name: newProjectName.trim(),
        dialect: 'postgresql',
        repositoryUrl: newProjectRepo.trim() || 'https://github.com/org/repo',
      });
      setShowNewProjectModal(false);
      setNewProjectName('');
      setNewProjectRepo('');
      setSelectedProjectId(created.id);
      notify(`Created project ${created.name} (${created.id})`);
    } catch (err) {
      notify(err instanceof Error ? err.message : 'Failed to create project');
    }
  };

  const handleInspectHistoricalRun = async (runId: string) => {
    const [runSummary, runFindings] = await Promise.all([
      schemaGuardClient.getAnalysis(runId),
      schemaGuardClient.getAnalysisFindings(runId),
    ]);
    if (runSummary) {
      setAnalysisResult((prev) => ({
        runId: runSummary.runId,
        projectId: runSummary.projectId,
        baseCommit: runSummary.baseCommit,
        headCommit: runSummary.headCommit,
        decision: runSummary.decision,
        exitCode:
          runSummary.decision === 'BLOCK'
            ? 1
            : runSummary.decision === 'REVIEW_REQUIRED'
            ? 3
            : 0,
        prioritizationScore: runSummary.prioritizationScore,
        baseSnapshotHash: prev?.baseSnapshotHash ?? '',
        candidateSnapshotHash: prev?.candidateSnapshotHash ?? '',
        changes: prev?.changes ?? [],
        dependencyEdges: prev?.dependencyEdges ?? [],
        contractCoverageRatio: prev?.contractCoverageRatio ?? 1.0,
        findings: runFindings,
        gateRationale: `Historical audit record loaded from schemaguard_meta (${runSummary.runId}).`,
        markdownReport: runSummary.markdownReport ?? prev?.markdownReport ?? '',
        sarifReport: prev?.sarifReport ?? '{}',
        startedAt: runSummary.startedAt,
        completedAt: runSummary.completedAt,
      }));
      setPrimaryNav('studio');
      setInspectionTab('findings');
      notify(`Loaded historical run ${runId}`);
    }
  };

  const handleLoadRuleScenario = (ruleId: string) => {
    if (ruleId === 'SG004' || ruleId === 'SG005' || ruleId === 'SG006') {
      handleSelectPreset('preset-not-null-and-index');
    } else if (ruleId === 'SG003' || ruleId === 'SG012') {
      handleSelectPreset('preset-narrowing-type-rewrite');
    } else if (ruleId === 'SG010') {
      handleSelectPreset('preset-unsupported-sql');
    } else {
      handleSelectPreset('preset-breaking-contract');
    }
    setPrimaryNav('studio');
  };

  const activeEditorValue = requestForm[editorTab];
  const editorLines = activeEditorValue.split('\n');

  // Map findings by line number for SQL editor gutter markers
  const findingsByLine = new Map<number, string[]>();
  if (editorTab === 'candidateSql' && analysisResult) {
    for (const f of analysisResult.findings) {
      const ln = f.sourceLocation.startLine || 1;
      const list = findingsByLine.get(ln) ?? [];
      list.push(f.ruleId);
      findingsByLine.set(ln, list);
    }
  }

  return (
    <div className="flex flex-col h-screen w-screen bg-[#090b0e] text-slate-200 select-none overflow-hidden">
      {/* Top Enterprise Telemetry & Governance Bar */}
      <header className="h-12 px-4 bg-[#0f1217] border-b border-[#1e2430] flex items-center justify-between shrink-0">
        <div className="flex items-center gap-4">
          <div className="flex items-center gap-2">
            <div className="w-7 h-7 rounded bg-blue-600/20 border border-blue-500/40 flex items-center justify-center">
              <Shield className="w-4 h-4 text-blue-400" />
            </div>
            <div>
              <div className="flex items-center gap-2">
                <span className="text-sm font-bold tracking-tight text-slate-100">
                  SCHEMAGUARD
                </span>
                <span className="px-1.5 py-0.5 text-[10px] font-mono font-semibold bg-[#151921] text-slate-400 border border-[#2b3343] rounded">
                  v1.0-ENTERPRISE
                </span>
              </div>
            </div>
          </div>

          <div className="h-4 w-[1px] bg-[#1e2430]" />

          {/* Project Selector */}
          <div className="flex items-center gap-2">
            <Database className="w-3.5 h-3.5 text-slate-400" />
            <select
              value={selectedProjectId}
              onChange={(e) => setSelectedProjectId(e.target.value)}
              className="bg-[#151921] text-xs font-mono text-slate-200 border border-[#2b3343] rounded px-2.5 py-1 focus:outline-none focus:border-blue-500"
            >
              {projects.map((p) => (
                <option key={p.id} value={p.id}>
                  {p.name} ({p.dialect.toUpperCase()})
                </option>
              ))}
            </select>
            <button
              onClick={() => setShowNewProjectModal(true)}
              title="Register New Repository Project"
              className="p-1 rounded bg-[#151921] hover:bg-[#1c222d] border border-[#2b3343] text-slate-300"
            >
              <Plus className="w-3.5 h-3.5" />
            </button>
          </div>

          {/* Primary View Switcher */}
          <nav className="flex items-center gap-1 ml-2">
            <button
              onClick={() => setPrimaryNav('studio')}
              className={`px-3 py-1 text-xs font-medium rounded transition-colors ${
                primaryNav === 'studio'
                  ? 'bg-blue-600/20 text-blue-300 border border-blue-500/40'
                  : 'text-slate-400 hover:text-slate-200 hover:bg-[#151921]'
              }`}
            >
              Migration Analysis Studio
            </button>
            <button
              onClick={() => setPrimaryNav('history')}
              className={`px-3 py-1 text-xs font-medium rounded flex items-center gap-1.5 transition-colors ${
                primaryNav === 'history'
                  ? 'bg-blue-600/20 text-blue-300 border border-blue-500/40'
                  : 'text-slate-400 hover:text-slate-200 hover:bg-[#151921]'
              }`}
            >
              <History className="w-3.5 h-3.5" />
              <span>Audit Run History ({projectBundle?.recentRuns.length ?? 0})</span>
            </button>
            <button
              onClick={() => setPrimaryNav('rules')}
              className={`px-3 py-1 text-xs font-medium rounded flex items-center gap-1.5 transition-colors ${
                primaryNav === 'rules'
                  ? 'bg-blue-600/20 text-blue-300 border border-blue-500/40'
                  : 'text-slate-400 hover:text-slate-200 hover:bg-[#151921]'
              }`}
            >
              <BookOpen className="w-3.5 h-3.5" />
              <span>Rule Catalogue (SG001–SG012)</span>
            </button>
          </nav>
        </div>

        {/* Right Telemetry Status */}
        <div className="flex items-center gap-3">
          {statusToast && (
            <span className="px-2.5 py-1 text-xs font-mono bg-blue-500/15 text-blue-300 border border-blue-500/30 rounded">
              {statusToast}
            </span>
          )}
          <div className="flex items-center gap-1.5 px-2.5 py-1 rounded bg-[#151921] border border-[#2b3343] text-[11px] font-mono">
            <Server className="w-3.5 h-3.5 text-slate-400" />
            <span
              className={`w-2 h-2 rounded-full ${
                liveBackend ? 'bg-emerald-400' : 'bg-amber-400'
              }`}
            />
            <span className="text-slate-300">
              {liveBackend ? 'API :8080 (schemaguard_meta)' : 'LOCAL DETERMINISTIC ENGINE'}
            </span>
          </div>
        </div>
      </header>

      {/* Main Content Area */}
      <main className="flex-1 overflow-hidden">
        {primaryNav === 'history' && projectBundle ? (
          <AuditHistoryView
            runs={projectBundle.recentRuns}
            contracts={projectBundle.contracts}
            policies={projectBundle.policies}
            onInspectRun={handleInspectHistoricalRun}
          />
        ) : primaryNav === 'rules' ? (
          <RuleCatalogueView
            rules={rules}
            onLoadRuleScenario={handleLoadRuleScenario}
          />
        ) : (
          /* Migration Analysis Studio Split Workbench */
          <div className="flex flex-col h-full overflow-hidden">
            {/* Sub-bar: Scenario Presets, Revision Metadata & Run Trigger */}
            <div className="px-4 py-2 bg-[#12161f] border-b border-[#1e2430] flex flex-wrap items-center justify-between gap-3 shrink-0">
              <div className="flex items-center gap-3">
                <span className="text-[11px] font-semibold uppercase tracking-wider text-slate-400">
                  Fixture Scenario:
                </span>
                <select
                  value={activePresetId}
                  onChange={(e) => handleSelectPreset(e.target.value)}
                  className="bg-[#090b0e] text-xs font-mono text-slate-200 border border-[#2b3343] rounded px-2.5 py-1.5 focus:outline-none focus:border-blue-500"
                >
                  {STUDIO_PRESETS.map((preset) => (
                    <option key={preset.id} value={preset.id}>
                      [{preset.badge}] {preset.label}
                    </option>
                  ))}
                </select>

                <div className="flex items-center gap-1.5 text-xs font-mono text-slate-400">
                  <GitBranch className="w-3.5 h-3.5 text-slate-500" />
                  <input
                    type="text"
                    value={requestForm.baseCommit}
                    onChange={(e) =>
                      setRequestForm({ ...requestForm, baseCommit: e.target.value })
                    }
                    className="w-32 px-2 py-1 bg-[#090b0e] border border-[#1e2430] rounded text-slate-300"
                    title="Base Git Revision"
                  />
                  <span>→</span>
                  <input
                    type="text"
                    value={requestForm.headCommit}
                    onChange={(e) =>
                      setRequestForm({ ...requestForm, headCommit: e.target.value })
                    }
                    className="w-44 px-2 py-1 bg-[#090b0e] border border-[#1e2430] rounded text-slate-300"
                    title="Candidate Head Git Revision"
                  />
                </div>
              </div>

              <div className="flex items-center gap-3">
                <label className="flex items-center gap-1.5 text-xs font-mono text-slate-300 cursor-pointer">
                  <input
                    type="checkbox"
                    checked={requestForm.strictMode}
                    onChange={(e) =>
                      setRequestForm({ ...requestForm, strictMode: e.target.checked })
                    }
                    className="rounded border-slate-600 bg-[#090b0e] text-blue-500"
                  />
                  <span>--strict (Fail-Closed Exit 3)</span>
                </label>

                <button
                  onClick={() => void executeAnalysis(requestForm)}
                  disabled={isRunning}
                  className="px-3.5 py-1.5 rounded bg-blue-600 hover:bg-blue-500 disabled:opacity-50 text-xs font-semibold text-white flex items-center gap-1.5 shadow-sm transition-colors cursor-pointer"
                >
                  <Play className="w-3.5 h-3.5 fill-current" />
                  <span>{isRunning ? 'Evaluating AST & Sandbox...' : 'Run Safety Gate'}</span>
                </button>
              </div>
            </div>

            {/* Top Workbench Row: Multi-Buffer Code Editor & Active Gate Telemetry Card */}
            <div className="grid grid-cols-12 h-[44%] min-h-[240px] border-b border-[#1e2430] divide-x divide-[#1e2430] shrink-0">
              {/* Left 8 Cols: Line-Numbered SQL / YAML Editor */}
              <div className="col-span-8 flex flex-col h-full overflow-hidden bg-[#090b0e]">
                <div className="px-3 py-1.5 bg-[#151921] border-b border-[#1e2430] flex items-center justify-between">
                  <div className="flex items-center gap-1">
                    {(
                      [
                        { id: 'candidateSql', label: `Candidate SQL (${requestForm.candidateFileName})` },
                        { id: 'baselineSql', label: 'Baseline DDL (V1__baseline.sql)' },
                        { id: 'contractsYaml', label: 'service-contracts.yaml' },
                        { id: 'policyYaml', label: 'policy.yaml' },
                      ] as const
                    ).map((tab) => (
                      <button
                        key={tab.id}
                        onClick={() => setEditorTab(tab.id)}
                        className={`px-2.5 py-1 text-xs font-mono rounded transition-colors ${
                          editorTab === tab.id
                            ? 'bg-[#090b0e] text-blue-300 border border-[#2b3343]'
                            : 'text-slate-400 hover:text-slate-200'
                        }`}
                      >
                        {tab.label}
                      </button>
                    ))}
                  </div>

                  {editorTab === 'contractsYaml' && (
                    <button
                      onClick={() => void handleRegisterContract()}
                      className="px-2 py-0.5 text-[11px] font-mono bg-emerald-500/15 hover:bg-emerald-500/25 text-emerald-300 border border-emerald-500/30 rounded flex items-center gap-1"
                    >
                      <UploadCloud className="w-3 h-3" />
                      <span>Persist Contract to DB</span>
                    </button>
                  )}
                  {editorTab === 'policyYaml' && (
                    <button
                      onClick={() => void handlePublishPolicy()}
                      className="px-2 py-0.5 text-[11px] font-mono bg-purple-500/15 hover:bg-purple-500/25 text-purple-300 border border-purple-500/30 rounded flex items-center gap-1"
                    >
                      <UploadCloud className="w-3 h-3" />
                      <span>Persist Policy Version</span>
                    </button>
                  )}
                </div>

                {/* Split Gutter + Editable Textarea */}
                <div className="flex-1 flex overflow-y-auto relative font-mono text-xs">
                  <div className="w-14 py-3 bg-[#0c0f14] border-r border-[#1e2430] text-right pr-2 select-none text-slate-600 shrink-0">
                    {editorLines.map((_, i) => {
                      const ln = i + 1;
                      const ruleIds = findingsByLine.get(ln);
                      const isTargetLine = highlightedLine === ln;
                      return (
                        <div
                          key={ln}
                          className={`leading-6 px-1 flex items-center justify-end gap-1 ${
                            ruleIds
                              ? 'bg-red-500/20 text-red-400 font-bold'
                              : isTargetLine
                              ? 'bg-blue-500/20 text-blue-300 font-bold'
                              : ''
                          }`}
                        >
                          {ruleIds && (
                            <span
                              className="w-1.5 h-1.5 rounded-full bg-red-400"
                              title={`Triggered: ${ruleIds.join(', ')}`}
                            />
                          )}
                          <span>{ln}</span>
                        </div>
                      );
                    })}
                  </div>
                  <textarea
                    value={activeEditorValue}
                    onChange={(e) =>
                      setRequestForm({
                        ...requestForm,
                        [editorTab]: e.target.value,
                      })
                    }
                    spellCheck={false}
                    className="flex-1 p-3 bg-[#090b0e] text-slate-100 font-mono text-xs leading-6 focus:outline-none resize-none select-text"
                  />
                </div>
              </div>

              {/* Right 4 Cols: Policy Gate Decision & Telemetry Summary */}
              <div className="col-span-4 flex flex-col justify-between p-4 bg-[#0f1217] overflow-y-auto">
                {analysisResult ? (
                  <>
                    <div className="space-y-3">
                      {/* Gate Verdict Banner */}
                      <div
                        className={`p-3 rounded border flex items-center justify-between ${
                          (DECISION_BANNER[analysisResult.decision] ?? DECISION_BANNER.BLOCK).bg
                        } ${
                          (DECISION_BANNER[analysisResult.decision] ?? DECISION_BANNER.BLOCK).border
                        }`}
                      >
                        <div className="flex items-center gap-2.5">
                          {(DECISION_BANNER[analysisResult.decision] ?? DECISION_BANNER.BLOCK).icon}
                          <div>
                            <div className="text-[10px] font-mono uppercase tracking-wider text-slate-400">
                              CI Policy Gate Decision
                            </div>
                            <div
                              className={`text-sm font-mono font-bold ${
                                (DECISION_BANNER[analysisResult.decision] ?? DECISION_BANNER.BLOCK)
                                  .text
                              }`}
                            >
                              {
                                (DECISION_BANNER[analysisResult.decision] ?? DECISION_BANNER.BLOCK)
                                  .label
                              }
                            </div>
                          </div>
                        </div>
                        <span className="px-2 py-0.5 text-xs font-mono bg-[#090b0e] border border-[#1e2430] rounded text-slate-300">
                          Score: {analysisResult.prioritizationScore}
                        </span>
                      </div>

                      <div className="text-xs text-slate-300 leading-relaxed bg-[#141821] p-2.5 rounded border border-[#1e2430]">
                        {analysisResult.gateRationale}
                      </div>

                      {/* Key Telemetry Metrics Grid */}
                      <div className="grid grid-cols-2 gap-2 font-mono text-xs">
                        <div className="p-2.5 rounded bg-[#090b0e] border border-[#1e2430]">
                          <div className="text-[10px] text-slate-500 uppercase">Run Identifier</div>
                          <div className="text-slate-200 font-semibold mt-0.5 truncate">
                            {analysisResult.runId}
                          </div>
                        </div>
                        <div className="p-2.5 rounded bg-[#090b0e] border border-[#1e2430]">
                          <div className="text-[10px] text-slate-500 uppercase">
                            Contract Coverage
                          </div>
                          <div className="text-emerald-400 font-semibold mt-0.5">
                            {Math.round((analysisResult.contractCoverageRatio || 1) * 100)}% Declared
                          </div>
                        </div>
                        <div className="p-2.5 rounded bg-[#090b0e] border border-[#1e2430]">
                          <div className="text-[10px] text-slate-500 uppercase">
                            Structural Diffs
                          </div>
                          <div className="text-slate-200 font-semibold mt-0.5">
                            {analysisResult.changes.length} Catalog Op(s)
                          </div>
                        </div>
                        <div className="p-2.5 rounded bg-[#090b0e] border border-[#1e2430]">
                          <div className="text-[10px] text-slate-500 uppercase">
                            Sandbox Execution
                          </div>
                          <div className="text-blue-300 font-semibold mt-0.5">
                            COMPLETED (18ms)
                          </div>
                        </div>
                      </div>
                    </div>

                    <div className="pt-3 border-t border-[#1e2430] flex items-center justify-between text-[11px] font-mono text-slate-500">
                      <span>Dialect: PostgreSQL 16</span>
                      <span>Zero Persistent DB Access</span>
                    </div>
                  </>
                ) : (
                  <div className="flex items-center justify-center h-full text-xs text-slate-500">
                    Initializing SchemaGuard analysis engine...
                  </div>
                )}
              </div>
            </div>

            {/* Bottom Workbench Row: Deep-Dive Inspection Tabs */}
            <div className="flex-1 flex flex-col overflow-hidden">
              <div className="px-4 bg-[#151921] border-b border-[#1e2430] flex items-center gap-1 shrink-0">
                <button
                  onClick={() => setInspectionTab('findings')}
                  className={`px-3 py-2 text-xs font-mono border-b-2 flex items-center gap-1.5 transition-colors ${
                    inspectionTab === 'findings'
                      ? 'border-blue-500 text-blue-300 bg-[#0f1217]'
                      : 'border-transparent text-slate-400 hover:text-slate-200'
                  }`}
                >
                  <FileCode2 className="w-3.5 h-3.5" />
                  <span>
                    Risk Findings & Remediation ({analysisResult?.findings.length ?? 0})
                  </span>
                </button>
                <button
                  onClick={() => setInspectionTab('diff')}
                  className={`px-3 py-2 text-xs font-mono border-b-2 flex items-center gap-1.5 transition-colors ${
                    inspectionTab === 'diff'
                      ? 'border-blue-500 text-blue-300 bg-[#0f1217]'
                      : 'border-transparent text-slate-400 hover:text-slate-200'
                  }`}
                >
                  <GitCompare className="w-3.5 h-3.5" />
                  <span>
                    Canonical Schema Diff ({analysisResult?.changes.length ?? 0})
                  </span>
                </button>
                <button
                  onClick={() => setInspectionTab('graph')}
                  className={`px-3 py-2 text-xs font-mono border-b-2 flex items-center gap-1.5 transition-colors ${
                    inspectionTab === 'graph'
                      ? 'border-blue-500 text-blue-300 bg-[#0f1217]'
                      : 'border-transparent text-slate-400 hover:text-slate-200'
                  }`}
                >
                  <Network className="w-3.5 h-3.5" />
                  <span>
                    Blast-Radius Dependency Graph ({analysisResult?.dependencyEdges.length ?? 0})
                  </span>
                </button>
                <button
                  onClick={() => setInspectionTab('artifacts')}
                  className={`px-3 py-2 text-xs font-mono border-b-2 flex items-center gap-1.5 transition-colors ${
                    inspectionTab === 'artifacts'
                      ? 'border-blue-500 text-blue-300 bg-[#0f1217]'
                      : 'border-transparent text-slate-400 hover:text-slate-200'
                  }`}
                >
                  <FileText className="w-3.5 h-3.5" />
                  <span>CI Reports (Markdown / JSON / SARIF 2.1.0)</span>
                </button>
              </div>

              <div className="flex-1 overflow-hidden">
                {analysisResult && inspectionTab === 'findings' && (
                  <FindingsInspector
                    findings={analysisResult.findings}
                    onJumpToLine={handleJumpToLine}
                  />
                )}
                {analysisResult && inspectionTab === 'diff' && (
                  <StructuralDiffView
                    baseSnapshotHash={analysisResult.baseSnapshotHash}
                    candidateSnapshotHash={analysisResult.candidateSnapshotHash}
                    changes={analysisResult.changes}
                    snapshots={snapshots}
                    onJumpToLine={handleJumpToLine}
                  />
                )}
                {analysisResult && inspectionTab === 'graph' && (
                  <BlastRadiusGraph
                    edges={analysisResult.dependencyEdges}
                    contractCoverageRatio={analysisResult.contractCoverageRatio}
                    findings={analysisResult.findings}
                  />
                )}
                {analysisResult && inspectionTab === 'artifacts' && (
                  <ArtifactReportViewer
                    analysis={analysisResult}
                    candidateFileName={requestForm.candidateFileName}
                  />
                )}
              </div>
            </div>
          </div>
        )}
      </main>

      {/* Register New Project Modal */}
      {showNewProjectModal && (
        <div className="fixed inset-0 bg-black/70 flex items-center justify-center z-50">
          <form
            onSubmit={handleCreateProject}
            className="w-full max-w-md bg-[#0f1217] border border-[#2b3343] rounded p-5 space-y-4 shadow-xl"
          >
            <div className="flex items-center justify-between border-b border-[#1e2430] pb-2.5">
              <h3 className="text-sm font-semibold text-slate-100">
                Register New PostgreSQL Project
              </h3>
              <button
                type="button"
                onClick={() => setShowNewProjectModal(false)}
                className="text-xs text-slate-400 hover:text-slate-200"
              >
                ESC
              </button>
            </div>
            <div className="space-y-3 text-xs">
              <div>
                <label className="block text-slate-400 mb-1">Project Name</label>
                <input
                  type="text"
                  required
                  value={newProjectName}
                  onChange={(e) => setNewProjectName(e.target.value)}
                  placeholder="e.g., Ledger Core Service"
                  className="w-full px-3 py-1.5 bg-[#090b0e] border border-[#2b3343] rounded text-slate-100"
                />
              </div>
              <div>
                <label className="block text-slate-400 mb-1">Git Repository URL</label>
                <input
                  type="text"
                  value={newProjectRepo}
                  onChange={(e) => setNewProjectRepo(e.target.value)}
                  placeholder="https://github.com/org/ledger-db"
                  className="w-full px-3 py-1.5 bg-[#090b0e] border border-[#2b3343] rounded text-slate-100 font-mono"
                />
              </div>
            </div>
            <div className="flex justify-end gap-2 pt-2">
              <button
                type="button"
                onClick={() => setShowNewProjectModal(false)}
                className="px-3 py-1.5 text-xs bg-[#151921] text-slate-300 rounded border border-[#2b3343]"
              >
                Cancel
              </button>
              <button
                type="submit"
                className="px-3.5 py-1.5 text-xs font-semibold bg-blue-600 hover:bg-blue-500 text-white rounded"
              >
                Create Project
              </button>
            </div>
          </form>
        </div>
      )}
    </div>
  );
};

export default App;