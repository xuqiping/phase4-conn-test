// 一键安装向导 UI（P03 Step5 FR-005/AC-006）。
// 目前为 MVP：列出缺失运行时/依赖 + 安装命令 + 一键执行。

import { useEffect, useState } from "react";
import { invoke } from "@tauri-apps/api/core";

interface InstallStepVo {
  name: string;
  command: string;
  estimated_seconds: number;
  risk_note: string;
}

interface InstallResultVo {
  step: string;
  success: boolean;
  stdout: string;
  stderr: string;
}

interface Props {
  projectId: number;
}

export default function InstallWizard({ projectId }: Props) {
  const [plan, setPlan] = useState<{ missing: string[]; steps: InstallStepVo[] } | null>(null);
  const [loading, setLoading] = useState(false);
  const [results, setResults] = useState<InstallResultVo[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    setError(null);
    invoke<{ missing: string[]; steps: InstallStepVo[] }>("install_plan", { projectId })
      .then(setPlan)
      .catch((e) => setError(e?.message ?? "读取安装计划失败"));
  }, [projectId]);

  const run = async () => {
    setLoading(true);
    setResults(null);
    setError(null);
    try {
      const res = await invoke<InstallResultVo[]>("install_runtime", { projectId });
      setResults(res);
    } catch (e: unknown) {
      setError((e as Error)?.message ?? "安装失败");
    } finally {
      setLoading(false);
    }
  };

  if (!plan) return <div className="text-text-dim">正在探测环境…</div>;

  return (
    <div className="panel space-y-3 rounded-[9px] p-4">
      <h3 className="text-sm font-semibold">环境一键安装</h3>
      {plan.missing.length === 0 && plan.steps.length === 0 && (
        <p className="text-xs text-text-dim">✅ 已识别运行时/依赖齐备，无需安装。</p>
      )}
      {plan.missing.length > 0 && (
        <div className="text-xs text-yellow-400">
          检测到缺失：{plan.missing.join("、")}
        </div>
      )}
      <ul className="space-y-2">
        {plan.steps.map((s) => (
          <li key={s.name} className="rounded-md border border-border bg-card p-2 text-xs">
            <div className="font-medium">{s.name}</div>
            <div className="font-mono text-text-dim">{s.command}</div>
            <div className="mt-1 text-text-faint">⏱ ~{s.estimated_seconds}s · {s.risk_note}</div>
          </li>
        ))}
      </ul>
      {plan.steps.length > 0 && (
        <button
          type="button"
          disabled={loading}
          onClick={run}
          className="w-full rounded-[9px] bg-brand px-3 py-2 text-sm font-medium text-white transition hover:bg-brand2 disabled:opacity-50"
        >
          {loading ? "安装中…" : "一键安装"}
        </button>
      )}
      {error && <div className="text-xs text-red-400">{error}</div>}
      {results && (
        <div className="space-y-2">
          {results.map((r) => (
            <div
              key={r.step}
              className={`rounded-md p-2 text-xs ${r.success ? "border border-green-500/30 bg-green-500/10 text-green-400" : "border border-red-500/30 bg-red-500/10 text-red-400"}`}
            >
              {r.success ? "✅" : "❌"} {r.step}
              {r.stderr && <pre className="mt-1 max-h-32 overflow-auto whitespace-pre-wrap font-mono text-text-dim">{r.stderr}</pre>}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
