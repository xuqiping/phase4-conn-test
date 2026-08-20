// 安全面板（P06 S4/S5/S9）：扫描按钮 + 结果状态 + findings 脱敏列表。
import { useState } from "react";
import { errMessage, ipc, type SecurityFindingDto, type SecurityScanDto } from "../../lib/ipc";
import { useProjectStore } from "../../stores/project";

/** 从 project store 读当前项目 id（selector 订阅，避免无关重渲染）。 */
function useProjectStoreCurrentId(): number | null {
  return useProjectStore((s) => s.currentId);
}

const SEVERITY_LABEL: Record<string, string> = {
  critical: "严重",
  high: "高危",
  medium: "中危",
  low: "低危",
  info: "提示",
};

const SEVERITY_CLASS: Record<string, string> = {
  critical: "bg-red-500/20 text-red-400",
  high: "bg-orange-500/20 text-orange-400",
  medium: "bg-yellow-500/20 text-yellow-400",
  low: "bg-sky-500/20 text-sky-400",
  info: "bg-slate-500/20 text-slate-400",
};

function SeverityBadge({ severity }: { severity: string }) {
  return (
    <span
      data-testid={`sev-${severity}`}
      className={`rounded px-1.5 py-0.5 text-xs font-medium ${SEVERITY_CLASS[severity] ?? SEVERITY_CLASS.info}`}
    >
      {SEVERITY_LABEL[severity] ?? severity}
    </span>
  );
}

function FindingRow({ f }: { f: SecurityFindingDto }) {
  const [open, setOpen] = useState(false);
  return (
    <li className="border-b border-border/50 py-2 last:border-none">
      <button
        type="button"
        className="flex w-full items-start gap-2 text-left"
        onClick={() => setOpen((v) => !v)}
        data-testid="finding-row"
      >
        <SeverityBadge severity={f.severity} />
        <span className="flex-1 text-sm">{f.message}</span>
        <span className="shrink-0 text-xs text-text-dim">
          {f.file}:{f.line}
        </span>
      </button>
      {open && (
        <div className="mt-2 space-y-1 pl-2 text-xs text-text-dim">
          <p>类别：{f.category}</p>
          <p>建议：{f.suggestion}</p>
          {f.snippet && (
            <pre className="overflow-x-auto rounded bg-black/30 p-2 font-mono">{f.snippet}</pre>
          )}
        </div>
      )}
    </li>
  );
}

export default function SecurityPanel({ scale }: { scale: string }) {
  const [scanning, setScanning] = useState(false);
  const [result, setResult] = useState<SecurityScanDto | null>(null);
  const [error, setError] = useState<string | null>(null);
  const projectId = useProjectStoreCurrentId();

  const runScan = async () => {
    if (projectId == null || scanning) return;
    setScanning(true);
    setError(null);
    try {
      setResult(await ipc.runSecurityScan(projectId));
    } catch (e) {
      setError(errMessage(e));
    } finally {
      setScanning(false);
    }
  };

  const statusText =
    result == null
      ? "尚未扫描"
      : result.status === "pass"
        ? "通过"
        : result.status === "partial"
          ? "有提示项"
          : "未通过";

  return (
    <section data-testid="security-panel" className="rounded-[14px] border border-border p-3">
      <div className="flex items-center justify-between">
        <div>
          <h3 className="text-sm font-semibold">安全检查</h3>
          <p className="mt-1 text-xs text-text-dim">
            {scale === "L2" || scale === "L3"
              ? "当前规模：扫描通过才能进入验收（强制卡点）"
              : "当前规模：安全扫描为建议项，不拦截"}
          </p>
        </div>
        <button
          type="button"
          className="rounded-[9px] border border-border px-3 py-1.5 text-xs text-text-dim hover:bg-card disabled:opacity-50"
          onClick={runScan}
          disabled={projectId == null || scanning}
          data-testid="btn-security-scan"
        >
          {scanning ? "扫描中…" : "扫描安全风险"}
        </button>
      </div>

      {error && (
        <p data-testid="scan-error" className="mt-3 rounded bg-red-500/10 p-2 text-sm text-red-400">
          {error}
        </p>
      )}

      {result && (
        <div className="mt-3" data-testid="scan-result">
          <p className="text-sm">
            扫描结果：<span className="font-semibold">{statusText}</span>
            <span className="ml-2 text-text-dim">共 {result.findings.length} 条</span>
            {result.gate_passed && (
              <span className="ml-2 rounded bg-emerald-500/20 px-1.5 py-0.5 text-xs text-emerald-400">
                安全门禁已解锁
              </span>
            )}
          </p>
          {result.warning && <p className="mt-1 text-xs text-yellow-400">{result.warning}</p>}
          {result.findings.length > 0 ? (
            <ul className="mt-3" data-testid="finding-list">
              {result.findings.map((f, i) => (
                <FindingRow key={`${f.file}:${f.line}:${i}`} f={f} />
              ))}
            </ul>
          ) : (
            <p className="mt-3 text-sm text-text-dim">没有发现风险项</p>
          )}
        </div>
      )}
    </section>
  );
}

