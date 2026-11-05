import React, { useState } from 'react';
import { GitCompare, Hash, Database, FileCode } from 'lucide-react';
import { SchemaChangeItem, SchemaSnapshotRecord } from '../types/schemaguard';

interface StructuralDiffViewProps {
  baseSnapshotHash: string;
  candidateSnapshotHash: string;
  changes: SchemaChangeItem[];
  snapshots: SchemaSnapshotRecord[];
  onJumpToLine: (line: number) => void;
}

export const StructuralDiffView: React.FC<StructuralDiffViewProps> = ({
  baseSnapshotHash,
  candidateSnapshotHash,
  changes,
  snapshots,
  onJumpToLine,
}) => {
  const [showSnapshotsModal, setShowSnapshotsModal] = useState(false);

  const getChangeBadge = (type: string) => {
    if (type.includes('DROPPED')) {
      return 'bg-red-500/10 text-red-400 border-red-500/30';
    }
    if (type.includes('ADDED')) {
      return 'bg-emerald-500/10 text-emerald-400 border-emerald-500/30';
    }
    if (type.includes('RENAME')) {
      return 'bg-purple-500/10 text-purple-300 border-purple-500/30';
    }
    return 'bg-amber-500/10 text-amber-300 border-amber-500/30';
  };

  return (
    <div className="flex flex-col h-full bg-[#0f1217] overflow-hidden">
      {/* Top Canonical Hash Comparison Bar */}
      <div className="px-4 py-2.5 bg-[#151921] border-b border-[#1e2430] flex flex-wrap items-center justify-between gap-4">
        <div className="flex items-center gap-6">
          <div className="flex items-center gap-2">
            <Hash className="w-3.5 h-3.5 text-slate-400" />
            <span className="text-[11px] uppercase tracking-wider text-slate-400 font-semibold">
              Base Canonical SHA-256:
            </span>
            <code className="px-2 py-0.5 text-xs bg-[#090b0e] border border-[#1e2430] rounded text-slate-300">
              {baseSnapshotHash ? `${baseSnapshotHash.slice(0, 20)}...` : 'N/A'}
            </code>
          </div>
          <div className="flex items-center gap-2">
            <GitCompare className="w-3.5 h-3.5 text-blue-400" />
            <span className="text-[11px] uppercase tracking-wider text-slate-400 font-semibold">
              Candidate Canonical SHA-256:
            </span>
            <code className="px-2 py-0.5 text-xs bg-[#090b0e] border border-[#1e2430] rounded text-blue-300">
              {candidateSnapshotHash ? `${candidateSnapshotHash.slice(0, 20)}...` : 'N/A'}
            </code>
          </div>
        </div>

        <button
          onClick={() => setShowSnapshotsModal(!showSnapshotsModal)}
          className="px-2.5 py-1 text-xs font-mono bg-[#0f1217] hover:bg-[#1c222d] text-slate-300 border border-[#2b3343] rounded flex items-center gap-1.5"
        >
          <Database className="w-3.5 h-3.5 text-blue-400" />
          <span>{showSnapshotsModal ? 'View Structural Diff Table' : `Recorded Snapshots (${snapshots.length})`}</span>
        </button>
      </div>

      {showSnapshotsModal ? (
        <div className="flex-1 overflow-y-auto p-4 space-y-3 bg-[#090b0e]">
          <div className="text-xs font-semibold uppercase tracking-wider text-slate-400 mb-2">
            Persisted Canonical Schema Snapshots (PostgreSQL Catalog Reconstructions)
          </div>
          {snapshots.map((snap) => (
            <div key={snap.id} className="border border-[#1e2430] bg-[#0f1217] rounded p-3">
              <div className="flex items-center justify-between text-xs font-mono mb-2">
                <span className="text-blue-400 font-semibold">
                  {snap.id} — Commit: {snap.commitSha}
                </span>
                <span className="text-slate-400">
                  Model v{snap.schemaModelVersion} | SHA-256: {snap.snapshotHash.slice(0, 24)}...
                </span>
              </div>
              <pre className="text-[11px] font-mono text-slate-300 bg-[#090b0e] p-2.5 rounded border border-[#1e2430] overflow-x-auto max-h-48">
                {snap.schemaJson}
              </pre>
            </div>
          ))}
        </div>
      ) : (
        <div className="flex-1 overflow-y-auto">
          <table className="w-full text-left border-collapse">
            <thead>
              <tr className="bg-[#12161f] border-b border-[#1e2430] text-[11px] font-semibold uppercase tracking-wider text-slate-400">
                <th className="py-2.5 px-4">Operation / Change Type</th>
                <th className="py-2.5 px-4">Qualified Object</th>
                <th className="py-2.5 px-4">Baseline State</th>
                <th className="py-2.5 px-4">Candidate State</th>
                <th className="py-2.5 px-4">AST Source Mapping</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-[#1e2430] text-xs font-mono">
              {changes.length === 0 ? (
                <tr>
                  <td colSpan={5} className="py-8 text-center text-slate-500 font-sans">
                    No structural catalog differences detected between base and candidate schemas (or SQL statement was procedural/unsupported).
                  </td>
                </tr>
              ) : (
                changes.map((chg, idx) => (
                  <tr key={idx} className="hover:bg-[#141821]">
                    <td className="py-2.5 px-4">
                      <span
                        className={`px-2 py-0.5 text-[10px] font-semibold rounded border ${getChangeBadge(
                          chg.changeType
                        )}`}
                      >
                        {chg.changeType}
                      </span>
                    </td>
                    <td className="py-2.5 px-4 text-slate-200">
                      {chg.schemaName}.{chg.tableName}
                      {chg.objectName ? <span className="text-blue-400">.{chg.objectName}</span> : ''}
                    </td>
                    <td className="py-2.5 px-4 text-red-300/90">
                      {chg.beforeValue ?? <span className="text-slate-600">—</span>}
                    </td>
                    <td className="py-2.5 px-4 text-emerald-300/90">
                      {chg.afterValue ?? <span className="text-slate-600">DROPPED</span>}
                    </td>
                    <td className="py-2.5 px-4">
                      {chg.sourceFile ? (
                        <button
                          onClick={() => onJumpToLine(chg.sourceLine || 1)}
                          className="inline-flex items-center gap-1 text-blue-400 hover:underline"
                        >
                          <FileCode className="w-3.5 h-3.5" />
                          <span>
                            {chg.sourceFile}:{chg.sourceLine}
                          </span>
                        </button>
                      ) : (
                        <span className="text-slate-500">Catalog Inferred</span>
                      )}
                    </td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
};