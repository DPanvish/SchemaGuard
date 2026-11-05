import React, { useState } from 'react';
import { Network, ShieldCheck, AlertCircle } from 'lucide-react';
import { DependencyEdgeItem, FindingItem } from '../types/schemaguard';

interface BlastRadiusGraphProps {
  edges: DependencyEdgeItem[];
  contractCoverageRatio: number;
  findings: FindingItem[];
}

export const BlastRadiusGraph: React.FC<BlastRadiusGraphProps> = ({
  edges,
  contractCoverageRatio,
  findings,
}) => {
  const [selectedNode, setSelectedNode] = useState<string | null>(null);

  const impactedObjects = new Set(findings.map((f) => f.objectRef.toLowerCase()));

  // Classify nodes into 3 columns for a clean directed tripartite DAG layout:
  // Column 1: Declared Services & Migrations | Column 2: Schema Columns | Column 3: Base Tables
  const leftNodes = Array.from(
    new Set(edges.map((e) => e.fromNode))
  );
  const rightNodes = Array.from(
    new Set(edges.map((e) => e.toNode))
  );

  const isNodeImpacted = (nodeId: string) => {
    const clean = nodeId.replace(/^(service:|column:|table:|migration:)/i, '').toLowerCase();
    if (impactedObjects.has(clean)) return true;
    return edges.some(
      (e) =>
        (e.fromNode === nodeId || e.toNode === nodeId) &&
        Array.from(impactedObjects).some(
          (imp) => e.toNode.toLowerCase().includes(imp) || e.fromNode.toLowerCase().includes(imp)
        )
    );
  };

  const coveragePct = Math.round((contractCoverageRatio || 1) * 100);

  return (
    <div className="grid grid-cols-12 h-full divide-x divide-[#1e2430] bg-[#0f1217]">
      {/* Left SVG Directed Dependency Graph */}
      <div className="col-span-7 flex flex-col h-full overflow-hidden bg-[#090b0e]">
        <div className="px-4 py-2 bg-[#151921] border-b border-[#1e2430] flex items-center justify-between">
          <div className="flex items-center gap-2">
            <Network className="w-3.5 h-3.5 text-blue-400" />
            <span className="text-[11px] font-semibold uppercase tracking-wider text-slate-300">
              Directed Consumer & Catalog Impact Graph
            </span>
          </div>
          <div className="flex items-center gap-2 text-xs font-mono">
            {coveragePct === 100 ? (
              <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded bg-emerald-500/10 text-emerald-400 border border-emerald-500/30">
                <ShieldCheck className="w-3 h-3" />
                Contract Coverage: {coveragePct}%
              </span>
            ) : (
              <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded bg-amber-500/10 text-amber-300 border border-amber-500/30">
                <AlertCircle className="w-3 h-3" />
                Contract Coverage: {coveragePct}% (Incomplete → REVIEW_REQUIRED)
              </span>
            )}
          </div>
        </div>

        <div className="flex-1 flex items-center justify-center p-4 overflow-auto">
          <svg className="w-full h-full min-h-[280px] max-h-[380px]" viewBox="0 0 760 340">
            <defs>
              <marker
                id="arrow-normal"
                viewBox="0 0 10 10"
                refX="8"
                refY="5"
                markerWidth="6"
                markerHeight="6"
                orient="auto-start-reverse"
              >
                <path d="M 0 1 L 10 5 L 0 9 z" fill="#475569" />
              </marker>
              <marker
                id="arrow-impacted"
                viewBox="0 0 10 10"
                refX="8"
                refY="5"
                markerWidth="6"
                markerHeight="6"
                orient="auto-start-reverse"
              >
                <path d="M 0 1 L 10 5 L 0 9 z" fill="#f87171" />
              </marker>
            </defs>

            {/* Edges */}
            {edges.map((edge, idx) => {
              const lIdx = leftNodes.indexOf(edge.fromNode);
              const rIdx = rightNodes.indexOf(edge.toNode);
              const y1 = 45 + lIdx * Math.min(58, 260 / Math.max(1, leftNodes.length));
              const y2 = 45 + rIdx * Math.min(58, 260 / Math.max(1, rightNodes.length));
              const impacted =
                edge.edgeType === 'MIGRATION_CHANGES_OBJECT' ||
                Array.from(impactedObjects).some((obj) => edge.toNode.toLowerCase().includes(obj));

              const isHighlighted =
                !selectedNode || selectedNode === edge.fromNode || selectedNode === edge.toNode;

              return (
                <g key={idx} opacity={isHighlighted ? 1 : 0.2}>
                  <path
                    d={`M 255 ${y1} C 370 ${y1}, 390 ${y2}, 495 ${y2}`}
                    fill="none"
                    stroke={impacted ? '#f87171' : '#334155'}
                    strokeWidth={impacted ? 2 : 1.5}
                    strokeDasharray={edge.edgeType === 'MIGRATION_CHANGES_OBJECT' ? '5,4' : undefined}
                    markerEnd={impacted ? 'url(#arrow-impacted)' : 'url(#arrow-normal)'}
                  />
                  <text
                    x="375"
                    y={(y1 + y2) / 2 - 4}
                    textAnchor="middle"
                    fill={impacted ? '#fca5a5' : '#64748b'}
                    fontSize="9"
                    fontFamily="JetBrains Mono, monospace"
                  >
                    {edge.edgeType}
                  </text>
                </g>
              );
            })}

            {/* Left Nodes (Consumers / Migrations) */}
            {leftNodes.map((node, idx) => {
              const y = 45 + idx * Math.min(58, 260 / Math.max(1, leftNodes.length));
              const impacted = isNodeImpacted(node);
              const isMigration = node.startsWith('migration:');
              return (
                <g
                  key={node}
                  onClick={() => setSelectedNode(selectedNode === node ? null : node)}
                  className="cursor-pointer"
                >
                  <rect
                    x="20"
                    y={y - 18}
                    width="235"
                    height="34"
                    rx="4"
                    fill={isMigration ? '#1e1b4b' : '#111827'}
                    stroke={impacted ? '#ef4444' : '#334155'}
                    strokeWidth={selectedNode === node ? 2 : 1.2}
                  />
                  <text
                    x="32"
                    y={y + 3}
                    fill={impacted ? '#fecaca' : '#e2e8f0'}
                    fontSize="11"
                    fontFamily="JetBrains Mono, monospace"
                  >
                    {node}
                  </text>
                </g>
              );
            })}

            {/* Right Nodes (Catalog Tables / Columns) */}
            {rightNodes.map((node, idx) => {
              const y = 45 + idx * Math.min(58, 260 / Math.max(1, rightNodes.length));
              const impacted = isNodeImpacted(node);
              return (
                <g
                  key={node}
                  onClick={() => setSelectedNode(selectedNode === node ? null : node)}
                  className="cursor-pointer"
                >
                  <rect
                    x="500"
                    y={y - 18}
                    width="245"
                    height="34"
                    rx="4"
                    fill="#0f172a"
                    stroke={impacted ? '#ef4444' : '#3b82f6'}
                    strokeWidth={selectedNode === node ? 2 : 1.2}
                  />
                  <text
                    x="512"
                    y={y + 3}
                    fill={impacted ? '#fca5a5' : '#93c5fd'}
                    fontSize="11"
                    fontFamily="JetBrains Mono, monospace"
                  >
                    {node}
                  </text>
                </g>
              );
            })}
          </svg>
        </div>
      </div>

      {/* Right Pane: Directed Edge Evidence Table */}
      <div className="col-span-5 flex flex-col h-full overflow-hidden">
        <div className="px-3 py-2 bg-[#151921] border-b border-[#1e2430] flex items-center justify-between">
          <span className="text-[11px] font-semibold uppercase tracking-wider text-slate-400">
            Declared Contract & Catalog Edges ({edges.length})
          </span>
          {selectedNode && (
            <button
              onClick={() => setSelectedNode(null)}
              className="text-[11px] font-mono text-blue-400 hover:underline"
            >
              Clear Filter ({selectedNode})
            </button>
          )}
        </div>
        <div className="flex-1 overflow-y-auto divide-y divide-[#1e2430]">
          {edges
            .filter(
              (e) => !selectedNode || e.fromNode === selectedNode || e.toNode === selectedNode
            )
            .map((edge, idx) => (
              <div key={idx} className="p-3 hover:bg-[#141821] text-xs font-mono space-y-1">
                <div className="flex items-center justify-between">
                  <span className="px-1.5 py-0.5 text-[10px] rounded bg-blue-500/10 text-blue-300 border border-blue-500/20">
                    {edge.edgeType}
                  </span>
                </div>
                <div className="text-slate-200 pt-0.5">
                  {edge.fromNode} <span className="text-slate-500">→</span>{' '}
                  <span className="text-blue-300">{edge.toNode}</span>
                </div>
                <div className="text-[11px] text-slate-400 font-sans">{edge.evidence}</div>
              </div>
            ))}
        </div>
      </div>
    </div>
  );
};