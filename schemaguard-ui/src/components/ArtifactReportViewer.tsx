import React, { useState } from 'react';
import { Copy, Check, Download, Terminal, FileText, Code2, Shield } from 'lucide-react';
import { AnalysisExecutionResponse } from '../types/schemaguard';

interface ArtifactReportViewerProps {
  analysis: AnalysisExecutionResponse;
  candidateFileName: string;
}

export const ArtifactReportViewer: React.FC<ArtifactReportViewerProps> = ({
  analysis,
  candidateFileName,
}) => {
  const [formatTab, setFormatTab] = useState<'markdown' | 'json' | 'sarif' | 'cli'>('markdown');
  const [copied, setCopied] = useState(false);

  const cliSnippet = `# 1. Run SchemaGuard Policy Check in CI/CD
./mvnw -q -pl schemaguard-cli -am exec:java \\
  -Dexec.args="check --base ${analysis.baseCommit || 'origin/main'} --head ${
    analysis.headCommit || 'HEAD'
  } --strict"

# Expected Exit Code for Run ${analysis.runId}: ${analysis.exitCode} (${analysis.decision})
#   0 = PASS / WARN (No policy-blocking findings)
#   1 = BLOCK (Hard policy or service contract violation)
#   2 = Execution or configuration error
#   3 = REVIEW_REQUIRED (Unsupported SQL or incomplete contract coverage in strict mode)

# 2. Export Multi-Format Reports
schemaguard report --run ${analysis.runId} --format markdown
schemaguard report --run ${analysis.runId} --format json
schemaguard report --run ${analysis.runId} --format sarif`;

  const getActiveContent = () => {
    if (formatTab === 'markdown') return analysis.markdownReport;
    if (formatTab === 'json') return JSON.stringify(analysis, null, 2);
    if (formatTab === 'sarif') return analysis.sarifReport;
    return cliSnippet;
  };

  const handleCopy = async () => {
    await navigator.clipboard.writeText(getActiveContent());
    setCopied(true);
    setTimeout(() => setCopied(false), 1800);
  };

  const handleDownload = () => {
    const ext =
      formatTab === 'markdown'
        ? 'md'
        : formatTab === 'json'
        ? 'json'
        : formatTab === 'sarif'
        ? 'sarif.json'
        : 'sh';
    const blob = new Blob([getActiveContent()], { type: 'text/plain;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `${analysis.runId}-report.${ext}`;
    a.click();
    URL.revokeObjectURL(url);
  };

  return (
    <div className="flex flex-col h-full bg-[#0f1217] overflow-hidden">
      <div className="px-4 py-2 bg-[#151921] border-b border-[#1e2430] flex items-center justify-between">
        <div className="flex items-center gap-1.5">
          <button
            onClick={() => setFormatTab('markdown')}
            className={`px-2.5 py-1 text-xs font-mono rounded border flex items-center gap-1.5 ${
              formatTab === 'markdown'
                ? 'bg-blue-500/20 text-blue-300 border-blue-500/40'
                : 'bg-[#0f1217] text-slate-400 border-[#1e2430] hover:text-slate-200'
            }`}
          >
            <FileText className="w-3.5 h-3.5" />
            <span>Markdown (Appendix A)</span>
          </button>
          <button
            onClick={() => setFormatTab('json')}
            className={`px-2.5 py-1 text-xs font-mono rounded border flex items-center gap-1.5 ${
              formatTab === 'json'
                ? 'bg-blue-500/20 text-blue-300 border-blue-500/40'
                : 'bg-[#0f1217] text-slate-400 border-[#1e2430] hover:text-slate-200'
            }`}
          >
            <Code2 className="w-3.5 h-3.5" />
            <span>Machine JSON</span>
          </button>
          <button
            onClick={() => setFormatTab('sarif')}
            className={`px-2.5 py-1 text-xs font-mono rounded border flex items-center gap-1.5 ${
              formatTab === 'sarif'
                ? 'bg-blue-500/20 text-blue-300 border-blue-500/40'
                : 'bg-[#0f1217] text-slate-400 border-[#1e2430] hover:text-slate-200'
            }`}
          >
            <Shield className="w-3.5 h-3.5" />
            <span>SARIF 2.1.0 (GitHub Code Scanning)</span>
          </button>
          <button
            onClick={() => setFormatTab('cli')}
            className={`px-2.5 py-1 text-xs font-mono rounded border flex items-center gap-1.5 ${
              formatTab === 'cli'
                ? 'bg-blue-500/20 text-blue-300 border-blue-500/40'
                : 'bg-[#0f1217] text-slate-400 border-[#1e2430] hover:text-slate-200'
            }`}
          >
            <Terminal className="w-3.5 h-3.5" />
            <span>CLI & Exit Code Contract</span>
          </button>
        </div>

        <div className="flex items-center gap-2">
          <span className="text-[11px] font-mono text-slate-400">
            Artifact: <strong className="text-slate-200">{candidateFileName}</strong>
          </span>
          <button
            onClick={handleCopy}
            className="px-2.5 py-1 text-xs font-mono bg-[#090b0e] hover:bg-[#1c222d] text-slate-200 border border-[#2b3343] rounded flex items-center gap-1.5"
          >
            {copied ? <Check className="w-3.5 h-3.5 text-emerald-400" /> : <Copy className="w-3.5 h-3.5" />}
            <span>{copied ? 'Copied' : 'Copy'}</span>
          </button>
          <button
            onClick={handleDownload}
            className="px-2.5 py-1 text-xs font-mono bg-[#090b0e] hover:bg-[#1c222d] text-slate-200 border border-[#2b3343] rounded flex items-center gap-1.5"
          >
            <Download className="w-3.5 h-3.5" />
            <span>Export</span>
          </button>
        </div>
      </div>

      <div className="flex-1 overflow-y-auto p-4 bg-[#090b0e]">
        <pre className="text-xs font-mono text-slate-200 leading-relaxed whitespace-pre-wrap">
          {getActiveContent()}
        </pre>
      </div>
    </div>
  );
};