import React, { useState } from 'react';
import { BookOpen, Lock, Search, Play } from 'lucide-react';
import { RuleCatalogEntry } from '../types/schemaguard';

interface RuleCatalogueViewProps {
  rules: RuleCatalogEntry[];
  onLoadRuleScenario: (ruleId: string) => void;
}

export const RuleCatalogueView: React.FC<RuleCatalogueViewProps> = ({
  rules,
  onLoadRuleScenario,
}) => {
  const [query, setQuery] = useState('');

  const filtered = rules.filter(
    (r) =>
      r.ruleId.toLowerCase().includes(query.toLowerCase()) ||
      r.title.toLowerCase().includes(query.toLowerCase()) ||
      r.category.toLowerCase().includes(query.toLowerCase())
  );

  return (
    <div className="flex flex-col h-full bg-[#0f1217] overflow-hidden">
      <div className="px-5 py-3 bg-[#151921] border-b border-[#1e2430] flex items-center justify-between gap-4">
        <div className="flex items-center gap-2">
          <BookOpen className="w-4 h-4 text-blue-400" />
          <div>
            <h2 className="text-xs font-semibold uppercase tracking-wider text-slate-200">
              SchemaGuard Risk Rule Catalogue (SG001 – SG012)
            </h2>
            <p className="text-[11px] text-slate-400">
              Deterministic compatibility, service contract, migration history, and PostgreSQL lock semantics rules.
            </p>
          </div>
        </div>

        <div className="relative w-72">
          <Search className="w-3.5 h-3.5 text-slate-500 absolute left-2.5 top-2.5" />
          <input
            type="text"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="Filter by Rule ID (e.g. SG008) or keyword..."
            className="w-full pl-8 pr-3 py-1.5 text-xs bg-[#090b0e] border border-[#2b3343] rounded text-slate-200 focus:outline-none focus:border-blue-500"
          />
        </div>
      </div>

      <div className="flex-1 overflow-y-auto p-5 grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-4 bg-[#090b0e]">
        {filtered.map((rule) => (
          <div
            key={rule.ruleId}
            className="border border-[#1e2430] bg-[#0f1217] rounded flex flex-col justify-between p-4 hover:border-[#2b3343] transition-colors"
          >
            <div>
              <div className="flex items-center justify-between gap-2 mb-2">
                <div className="flex items-center gap-2">
                  <span className="px-2 py-0.5 text-xs font-mono font-bold bg-blue-500/15 text-blue-300 border border-blue-500/30 rounded">
                    {rule.ruleId}
                  </span>
                  <span className="text-[11px] font-mono text-slate-400">{rule.category}</span>
                </div>
                <span
                  className={`px-2 py-0.5 text-[10px] font-mono font-semibold rounded border ${
                    rule.defaultSeverity === 'CRITICAL'
                      ? 'bg-red-500/10 text-red-400 border-red-500/30'
                      : rule.defaultSeverity === 'REVIEW_REQUIRED'
                      ? 'bg-purple-500/10 text-purple-300 border-purple-500/30'
                      : 'bg-amber-500/10 text-amber-300 border-amber-500/30'
                  }`}
                >
                  {rule.defaultSeverity}
                </span>
              </div>

              <h3 className="text-sm font-semibold text-slate-100 mb-2">{rule.title}</h3>

              {rule.detectionLogic && (
                <p className="text-xs text-slate-400 leading-relaxed mb-3">
                  {rule.detectionLogic}
                </p>
              )}
            </div>

            <div className="pt-3 border-t border-[#1e2430] space-y-2 text-[11px] font-mono">
              <div className="flex items-center justify-between text-slate-400">
                <span className="inline-flex items-center gap-1">
                  <Lock className="w-3 h-3 text-amber-400" />
                  PG Lock Mode:
                </span>
                <span className="text-slate-200">{rule.postgresLockMode ?? 'ACCESS EXCLUSIVE'}</span>
              </div>
              <div className="flex items-center justify-between text-slate-400">
                <span>Heap Rewrite Risk:</span>
                <span
                  className={
                    rule.tableRewriteRisk === 'HIGH'
                      ? 'text-red-400 font-semibold'
                      : rule.tableRewriteRisk === 'CONDITIONAL'
                      ? 'text-amber-300'
                      : 'text-emerald-400'
                  }
                >
                  {rule.tableRewriteRisk ?? 'NONE'}
                </span>
              </div>
              <button
                onClick={() => onLoadRuleScenario(rule.ruleId)}
                className="w-full mt-2 py-1.5 px-3 rounded bg-[#151921] hover:bg-[#1c222d] text-blue-400 border border-[#2b3343] flex items-center justify-center gap-1.5 transition-colors"
              >
                <Play className="w-3 h-3" />
                <span>Simulate in Analysis Studio</span>
              </button>
            </div>
          </div>
        ))}
      </div>
    </div>
  );
};