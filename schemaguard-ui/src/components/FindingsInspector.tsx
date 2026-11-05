import React, { useState } from 'react';
import {
  AlertTriangle,
  ShieldAlert,
  FileCode2,
  CheckCircle2,
  HelpCircle,
  ExternalLink,
  Layers,
  Info,
} from 'lucide-react';
import { FindingItem, Severity } from '../types/schemaguard';

interface FindingsInspectorProps {
  findings: FindingItem[];
  onJumpToLine: (line: number) => void;
}

const SEVERITY_BADGE: Record<Severity, { bg: string; text: string; border: string }> = {
  CRITICAL: {
    bg: 'bg-red-500/10',
    text: 'text-red-400',
    border: 'border-red-500/30',
  },
  HIGH: {
    bg: 'bg-amber-500/10',
    text: 'text-amber-400',
    border: 'border-amber-500/30',
  },
  REVIEW_REQUIRED: {
    bg: 'bg-purple-500/10',
    text: 'text-purple-300',
    border: 'border-purple-500/30',
  },
  WARN: {
    bg: 'bg-yellow-500/10',
    text: 'text-yellow-300',
    border: 'border-yellow-500/30',
  },
  INFO: {
    bg: 'bg-blue-500/10',
    text: 'text-blue-300',
    border: 'border-blue-500/30',
  },
};

export const FindingsInspector: React.FC<FindingsInspectorProps> = ({ findings, onJumpToLine }) => {
  const [selectedIndex, setSelectedIndex] = useState<number>(0);
  const [severityFilter, setSeverityFilter] = useState<string>('ALL');

  const filtered = findings.filter(
    (f) => severityFilter === 'ALL' || f.severity === severityFilter
  );

  const activeFinding = filtered[selectedIndex] ?? filtered[0] ?? null;

  if (findings.length === 0) {
    return (
      <div className="flex flex-col items-center justify-center h-full p-8 text-center bg-[#0f1217]">
        <div className="w-10 h-10 rounded border border-emerald-500/30 bg-emerald-500/10 flex items-center justify-center mb-3">
          <CheckCircle2 className="w-5 h-5 text-emerald-400" />
        </div>
        <h3 className="text-sm font-semibold text-slate-200">Zero Risk Findings Emitted</h3>
        <p className="text-xs text-slate-400 max-w-md mt-1 leading-relaxed">
          All evaluated rules (<code className="text-slate-300">SG001</code>–<code className="text-slate-300">SG012</code>) passed against the reconstructed baseline and candidate PostgreSQL catalogs.
        </p>
      </div>
    );
  }

  return (
    <div className="grid grid-cols-12 h-full divide-x divide-[#1e2430] bg-[#0f1217]">
      {/* Left Pane: Findings Table */}
      <div className="col-span-5 flex flex-col h-full overflow-hidden">
        <div className="px-3 py-2 bg-[#151921] border-b border-[#1e2430] flex items-center justify-between">
          <span className="text-[11px] font-semibold uppercase tracking-wider text-slate-400">
            Detected Findings ({filtered.length})
          </span>
          <div className="flex items-center gap-1">
            {['ALL', 'CRITICAL', 'HIGH', 'REVIEW_REQUIRED'].map((sev) => (
              <button
                key={sev}
                onClick={() => {
                  setSeverityFilter(sev);
                  setSelectedIndex(0);
                }}
                className={`px-2 py-0.5 text-[10px] font-mono rounded border transition-colors ${
                  severityFilter === sev
                    ? 'bg-blue-500/20 text-blue-300 border-blue-500/40'
                    : 'bg-[#0f1217] text-slate-400 border-[#1e2430] hover:text-slate-200'
                }`}
              >
                {sev === 'REVIEW_REQUIRED' ? 'REVIEW' : sev}
              </button>
            ))}
          </div>
        </div>

        <div className="flex-1 overflow-y-auto divide-y divide-[#1e2430]">
          {filtered.map((finding, idx) => {
            const badge = SEVERITY_BADGE[finding.severity] ?? SEVERITY_BADGE.WARN;
            const isSelected = activeFinding === finding;
            return (
              <div
                key={`${finding.ruleId}-${idx}`}
                onClick={() => setSelectedIndex(idx)}
                className={`p-3 cursor-pointer transition-colors ${
                  isSelected
                    ? 'bg-[#181e29] border-l-2 border-l-blue-500'
                    : 'hover:bg-[#141821]'
                }`}
              >
                <div className="flex items-center justify-between gap-2 mb-1">
                  <div className="flex items-center gap-1.5">
                    <span className="font-mono text-xs font-semibold text-slate-100">
                      {finding.ruleId}
                    </span>
                    <span
                      className={`px-1.5 py-0.5 text-[10px] font-mono font-medium rounded border ${badge.bg} ${badge.text} ${badge.border}`}
                    >
                      {finding.severity}
                    </span>
                  </div>
                  <button
                    onClick={(e) => {
                      e.stopPropagation();
                      onJumpToLine(finding.sourceLocation.startLine);
                    }}
                    title="Highlight source line in SQL editor"
                    className="inline-flex items-center gap-1 text-[11px] font-mono text-blue-400 hover:text-blue-300"
                  >
                    <FileCode2 className="w-3 h-3" />
                    {finding.sourceLocation.file}:{finding.sourceLocation.startLine}
                  </button>
                </div>
                <div className="text-xs font-medium text-slate-200 line-clamp-1">
                  {finding.title}
                </div>
                <div className="mt-1 font-mono text-[11px] text-slate-400 truncate">
                  Object: <span className="text-slate-300">{finding.objectRef}</span>
                </div>
              </div>
            );
          })}
        </div>
      </div>

      {/* Right Pane: Evidence & Expand-Migrate-Verify-Contract Drawer */}
      <div className="col-span-7 flex flex-col h-full overflow-y-auto p-4 space-y-4 bg-[#0b0d11]">
        {activeFinding ? (
          <>
            <div className="border border-[#1e2430] bg-[#0f1217] rounded p-3.5">
              <div className="flex items-start justify-between gap-3">
                <div>
                  <div className="flex items-center gap-2">
                    <span className="px-2 py-0.5 text-xs font-mono font-semibold bg-slate-800 text-slate-100 rounded border border-slate-700">
                      {activeFinding.ruleId}
                    </span>
                    <span
                      className={`px-2 py-0.5 text-xs font-mono font-semibold rounded border ${
                        (SEVERITY_BADGE[activeFinding.severity] ?? SEVERITY_BADGE.WARN).bg
                      } ${(SEVERITY_BADGE[activeFinding.severity] ?? SEVERITY_BADGE.WARN).text} ${
                        (SEVERITY_BADGE[activeFinding.severity] ?? SEVERITY_BADGE.WARN).border
                      }`}
                    >
                      {activeFinding.severity}
                    </span>
                    <span className="text-xs font-mono text-slate-400">
                      Target: <strong className="text-slate-200">{activeFinding.objectRef}</strong>
                    </span>
                  </div>
                  <h3 className="text-sm font-semibold text-slate-100 mt-2">
                    {activeFinding.title}
                  </h3>
                </div>
                <button
                  onClick={() => onJumpToLine(activeFinding.sourceLocation.startLine)}
                  className="px-2.5 py-1 text-xs font-mono bg-[#151921] hover:bg-[#1c222d] text-blue-400 border border-[#2b3343] rounded flex items-center gap-1.5 shrink-0"
                >
                  <span>
                    {activeFinding.sourceLocation.file}:{activeFinding.sourceLocation.startLine}
                  </span>
                  <ExternalLink className="w-3 h-3" />
                </button>
              </div>
              <p className="text-xs text-slate-300 mt-2.5 leading-relaxed">
                {activeFinding.message}
              </p>
            </div>

            {/* Supporting Catalog & Contract Evidence */}
            <div className="border border-[#1e2430] bg-[#0f1217] rounded overflow-hidden">
              <div className="px-3 py-2 bg-[#151921] border-b border-[#1e2430] flex items-center gap-2">
                <ShieldAlert className="w-3.5 h-3.5 text-amber-400" />
                <span className="text-[11px] font-semibold uppercase tracking-wider text-slate-300">
                  Deterministic Evidence Chain ({activeFinding.evidence.length})
                </span>
              </div>
              <ul className="p-3 space-y-1.5">
                {activeFinding.evidence.map((ev, i) => (
                  <li key={i} className="flex items-start gap-2 text-xs font-mono text-slate-300">
                    <span className="text-slate-500 select-none">[{i + 1}]</span>
                    <span>{ev}</span>
                  </li>
                ))}
              </ul>
            </div>

            {/* Expand - Migrate - Verify - Contract Rollout Matrix (Blueprint Section 6.3) */}
            <div className="border border-[#1e2430] bg-[#0f1217] rounded overflow-hidden">
              <div className="px-3 py-2 bg-[#151921] border-b border-[#1e2430] flex items-center justify-between">
                <div className="flex items-center gap-2">
                  <Layers className="w-3.5 h-3.5 text-blue-400" />
                  <span className="text-[11px] font-semibold uppercase tracking-wider text-slate-300">
                    Expand – Migrate – Verify – Contract Remediation Plan
                  </span>
                </div>
                <span className="text-[10px] font-mono text-slate-400">
                  Blueprint §6.3 Rollout Protocol
                </span>
              </div>

              <div className="p-3 space-y-3">
                <div className="text-xs font-mono text-slate-200 bg-[#090b0e] p-2.5 rounded border border-[#1e2430] whitespace-pre-wrap leading-relaxed">
                  {activeFinding.remediation}
                </div>

                <div className="grid grid-cols-4 gap-2 text-[11px]">
                  <div className="p-2 rounded bg-[#141821] border border-[#1e2430]">
                    <div className="font-mono font-semibold text-emerald-400">1. EXPAND</div>
                    <div className="text-slate-400 mt-1">
                      Introduce additive column/constraint (NOT VALID) while preserving existing contract.
                    </div>
                  </div>
                  <div className="p-2 rounded bg-[#141821] border border-[#1e2430]">
                    <div className="font-mono font-semibold text-blue-400">2. MIGRATE</div>
                    <div className="text-slate-400 mt-1">
                      Dual-write in application services and backfill historical rows in bounded batches.
                    </div>
                  </div>
                  <div className="p-2 rounded bg-[#141821] border border-[#1e2430]">
                    <div className="font-mono font-semibold text-amber-400">3. VERIFY</div>
                    <div className="text-slate-400 mt-1">
                      Confirm zero residual reads on old column and update YAML service contracts.
                    </div>
                  </div>
                  <div className="p-2 rounded bg-[#141821] border border-[#1e2430]">
                    <div className="font-mono font-semibold text-purple-400">4. CONTRACT</div>
                    <div className="text-slate-400 mt-1">
                      Remove obsolete column or validate constraint in a subsequent release.
                    </div>
                  </div>
                </div>

                {/* Explicit Uncertainty Boundary Notice (Section 6.3 Requirement) */}
                <div className="p-2.5 rounded bg-amber-500/5 border border-amber-500/20 flex items-start gap-2">
                  <Info className="w-4 h-4 text-amber-400 shrink-0 mt-0.5" />
                  <div className="text-[11px] text-slate-300 leading-relaxed">
                    <strong className="text-amber-300 font-medium">
                      Unverifiable Preconditions Notice:
                    </strong>{' '}
                    SchemaGuard evaluates declared YAML contracts and static catalog state. Undeclared ad-hoc runtime consumers, BI queries, and live production lock-wait queues cannot be verified automatically from Git manifests alone.
                  </div>
                </div>
              </div>
            </div>
          </>
        ) : (
          <div className="flex items-center justify-center h-full text-xs text-slate-500">
            Select a finding from the list to inspect its evidence chain.
          </div>
        )}
      </div>
    </div>
  );
};