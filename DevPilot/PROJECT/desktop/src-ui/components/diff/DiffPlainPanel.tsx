// diff 大白话摘要面板（P05 S3 FR-013）。
// 显示某 task 对应 checkpoint 的 diff 人话摘要，并支持切换到原始 diff。

import { useEffect, useState } from "react";
import { ipc, type DiffSummaryDto } from "../../lib/ipc";

interface Props {
  projectId: number;
  taskId: number;
  accessToken: string;
  cloudBase?: string;
}

export default function DiffPlainPanel({
  projectId,
  taskId,
  accessToken,
  cloudBase,
}: Props) {
  const [summary, setSummary] = useState<DiffSummaryDto | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [showRaw, setShowRaw] = useState(false);

  useEffect(() => {
    if (!taskId) return;
    setLoading(true);
    setError(null);
    setSummary(null);
    ipc
      .summarizeDiff(projectId, taskId, accessToken, cloudBase)
      .then(setSummary)
      .catch((e: unknown) => setError((e as Error)?.message ?? "摘要生成失败"))
      .finally(() => setLoading(false));
  }, [projectId, taskId, accessToken, cloudBase]);

  return (
    <div className="panel space-y-3 rounded-[9px] p-4">
      <div className="flex items-center justify-between">
        <h3 className="text-sm font-semibold">变更大白话</h3>
        <button
          type="button"
          onClick={() => setShowRaw((v) => !v)}
          className="rounded-[6px] border border-border px-2 py-1 text-xs text-text-dim hover:bg-card"
        >
          {showRaw ? "看摘要" : "看原始 diff"}
        </button>
      </div>

      {loading && <div className="text-xs text-text-dim">正在生成摘要…</div>}
      {error && <div className="text-xs text-red-400">{error}</div>}

      {!loading && !error && summary && (
        <>
          {showRaw ? (
            <pre className="max-h-96 overflow-auto rounded-md border border-border bg-card p-2 text-xs text-text-dim">
              {summary.raw_diff}
            </pre>
          ) : (
            <div className="space-y-2 text-xs">
              <SummaryItem label="改了什么" value={summary.what_changed} />
              <SummaryItem label="为什么改" value={summary.why} />
              <SummaryItem label="影响范围" value={summary.impact} />
              <SummaryItem label="潜在风险" value={summary.risk} />
              {summary.files.length > 0 && (
                <div>
                  <div className="mb-1 text-text-dim">涉及文件</div>
                  <ul className="list-inside list-disc text-text-dim">
                    {summary.files.map((f: string) => (
                      <li key={f}>{f}</li>
                    ))}
                  </ul>
                </div>
              )}
              {summary.truncated && (
                <div className="text-amber-400">
                  diff 过长，仅展示前 5000 行；完整内容请切到原始 diff。
                </div>
              )}
            </div>
          )}
        </>
      )}
    </div>
  );
}

function SummaryItem({ label, value }: { label: string; value: string }) {
  if (!value) return null;
  return (
    <div>
      <div className="mb-0.5 text-text-dim">{label}</div>
      <div className="whitespace-pre-wrap text-text">{value}</div>
    </div>
  );
}
