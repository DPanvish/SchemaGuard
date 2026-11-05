import React from 'react';
import { History, FileCheck2, Shield, ExternalLink } from 'lucide-react';
import {
  ContractArtifactRecord,
  HistoricalRunSummary,
  PolicyDecision,
  PolicyVersionRecord,
} from '../types/schemaguard';

interface AuditHistoryViewProps {
  runs: HistoricalRunSummary[];
  contracts: ContractArtifactRecord[];
  policies: PolicyVersionRecord[];
  onInspectRun: (runId: string) => void;
}

const DECISION_STYLE: Record<PolicyDecision, string> = {
  PASS: 'bg-emerald-500/10 text-emerald-400 border-emerald-500/30',
  WARN: 'bg-yellow-500/10 text-yellow-300 border-yellow-500/30',
  BLOCK: 'bg-red-500/10 text-red-400 border-red-500/30',
  REVIEW_REQUIRED: 'bg-purple-500/10 text-purple-300 border-purple-500/30',
};

export const AuditHistoryView: React.FC<AuditHistoryViewProps> = ({
  runs,
  contracts,
  policies,
  onInspectRun,
}) => {
  return (
    <div className="grid grid-cols-12 h-full divide-x divide-[#1e2430] bg-[#0f1217] overflow-hidden">
      {/* Append-Only Analysis Run History (Left 8 Cols) */}
      <div className="col-span-8 flex flex-col h-full overflow-hidden">
        <div className="px-4 py-3 bg-[#151921] border-b border-[#1e2430] flex items-center justify-between">
          <div className="flex items-center gap-2">
            <History className="w-4 h-4 text-blue-400" />
            <span className="text-xs font-semibold uppercase tracking-wider text-slate-200">
              Append-Only Analysis Run Audit Log ({runs.length})
            </span>
          </div>
          <span className="text-[11px] font-mono text-slate-400">
            Table: schemaguard_meta.analysis_runs
          </span>
        </div>

        <div className="flex-1 overflow-y-auto">
          <table className="w-full text-left border-collapse">
            <thead>
              <tr className="bg-[#12161f] border-b border-[#1e2430] text-[11px] font-semibold uppercase tracking-wider text-slate-400">
                <th className="py-2.5 px-4">Run ID</th>
                <th className="py-2.5 px-4">Base → Head Revision</th>
                <th className="py-2.5 px-4">Gate Decision</th>
                <th className="py-2.5 px-4">Findings</th>
                <th className="py-2.5 px-4">Priority Score</th>
                <th className="py-2.5 px-4">Completed At</th>
                <th className="py-2.5 px-4 text-right">Action</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-[#1e2430] text-xs font-mono">
              {runs.map((run) => (
                <tr key={run.runId} className="hover:bg-[#141821]">
                  <td className="py-3 px-4 font-semibold text-blue-400">{run.runId}</td>
                  <td className="py-3 px-4 text-slate-300">
                    {run.baseCommit} <span className="text-slate-500">→</span> {run.headCommit}
                  </td>
                  <td className="py-3 px-4">
                    <span
                      className={`px-2 py-0.5 text-[10px] font-semibold rounded border ${
                        DECISION_STYLE[run.decision] ?? DECISION_STYLE.WARN
                      }`}
                    >
                      {run.decision}
                    </span>
                  </td>
                  <td className="py-3 px-4 text-slate-300">{run.findingsCount}</td>
                  <td className="py-3 px-4 text-slate-400">{run.prioritizationScore}</td>
                  <td className="py-3 px-4 text-slate-400">
                    {new Date(run.completedAt).toLocaleString()}
                  </td>
                  <td className="py-3 px-4 text-right">
                    <button
                      onClick={() => onInspectRun(run.runId)}
                      className="inline-flex items-center gap-1 px-2.5 py-1 rounded bg-[#151921] hover:bg-[#1c222d] text-blue-400 border border-[#2b3343] text-[11px]"
                    >
                      <span>Load Run</span>
                      <ExternalLink className="w-3 h-3" />
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>

      {/* Registered Service Contracts & Policy Versions (Right 4 Cols) */}
      <div className="col-span-4 flex flex-col h-full divide-y divide-[#1e2430] overflow-hidden bg-[#0b0d11]">
        <div className="flex-1 flex flex-col overflow-hidden">
          <div className="px-4 py-2.5 bg-[#151921] border-b border-[#1e2430] flex items-center gap-2">
            <FileCheck2 className="w-3.5 h-3.5 text-emerald-400" />
            <span className="text-[11px] font-semibold uppercase tracking-wider text-slate-300">
              Registered Service Contracts ({contracts.length})
            </span>
          </div>
          <div className="flex-1 overflow-y-auto p-3 space-y-2">
            {contracts.map((c) => (
              <div key={c.id} className="p-2.5 rounded bg-[#0f1217] border border-[#1e2430] text-xs font-mono">
                <div className="flex items-center justify-between">
                  <span className="font-semibold text-emerald-400">{c.serviceName}</span>
                  <span className="text-[10px] text-slate-400">v{c.version} | {c.id}</span>
                </div>
                <div className="text-[11px] text-slate-500 truncate mt-1">
                  SHA-256: {c.checksum}
                </div>
              </div>
            ))}
          </div>
        </div>

        <div className="flex-1 flex flex-col overflow-hidden">
          <div className="px-4 py-2.5 bg-[#151921] border-b border-[#1e2430] flex items-center gap-2">
            <Shield className="w-3.5 h-3.5 text-purple-400" />
            <span className="text-[11px] font-semibold uppercase tracking-wider text-slate-300">
              Versioned Policy Configurations ({policies.length})
            </span>
          </div>
          <div className="flex-1 overflow-y-auto p-3 space-y-2">
            {policies.map((p) => (
              <div key={p.id} className="p-2.5 rounded bg-[#0f1217] border border-[#1e2430] text-xs font-mono">
                <div className="flex items-center justify-between mb-1">
                  <span className="font-semibold text-purple-300">Policy {p.id} (v{p.version})</span>
                  <span className="text-[10px] text-slate-500">
                    {new Date(p.createdAt).toLocaleTimeString()}
                  </span>
                </div>
                <pre className="text-[10px] text-slate-400 bg-[#090b0e] p-2 rounded border border-[#1e2430] overflow-x-auto">
                  {p.policyJson}
                </pre>
              </div>
            ))}
          </div>
        </div>
      </div>
    </div>
  );
};