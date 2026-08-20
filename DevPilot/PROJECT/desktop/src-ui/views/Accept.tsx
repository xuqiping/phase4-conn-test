// 验收 视图：安全扫描面板（P06 S4/S5）。
// 点「扫描安全风险」→ 内核本地静态扫描 → 展示脱敏 findings；
// L2/L3 扫描通过时内核自动解锁 security 门禁（联动点：扫描→门禁）。
import { useCallback, useEffect, useState } from "react";
import { errMessage, ipc, type SecurityFindingDto, type SecurityScanDto } from "../lib/ipc";
import { useProjectStore } from "../stores/project";

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

export default function Accept() {
  const currentId = useProjectStore((s) => s.currentId);
  const projects = useProjectStore((s) => s.projects);
  const scale = projects.find((p) => p.id === currentId)?.scale ?? "";
  const [scanning, setScanning] = useState(false);
  const [result, setResult] = useState<SecurityScanDto | null>(null);
  const [error, setError] = useState<string | null>(null);

  const runScan = useCallback(async () => {
    if (currentId == null || scanning) return;
    setScanning(true);
    setError(null);
    try {
      setResult(await ipc.runSecurityScan(currentId));
    } catch (e) {
      setError(errMessage(e));
    } finally {
      setScanning(false);
    }
  }, [currentId, scanning]);

  // 进入视图时自动带出上一次结果（无则留空，等用户主动扫）。
  useEffect(() => {
    setResult(null);
    setError(null);
  }, [currentId]);

  const statusText =
    result == null
      ? "尚未扫描"
      : result.status === "pass"
        ? "通过"
        : result.status === "partial"
          ? "有提示项"
          : "未通过";

  return (
    <section
      data-testid="view-accept"
      className="panel flex-1 overflow-y-auto rounded-[14px] p-4"
    >
      <div className="flex items-center justify-between">
        <div>
          <h2 className="text-lg font-semibold">验收 · 安全检查</h2>
          <p className="mt-1 text-sm text-text-dim">
            {scale === "L2" || scale === "L3"
              ? "当前规模：扫描通过才能进入验收（强制卡点）"
              : "当前规模：安全扫描为建议项，不拦截"}
          </p>
        </div>
        <button
          type="button"
          className="rounded-[9px] border border-border px-3 py-1.5 text-xs text-text-dim hover:bg-card disabled:opacity-50"
          onClick={runScan}
          disabled={currentId == null || scanning}
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
        <div className="mt-4" data-testid="scan-result">
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

      <div className="mt-6 rounded bg-black/20 p-3 text-sm text-text-dim">
        验收清单与点一点验收将在后续版本接入（S9/S10）。
      </div>
    </section>
  );
}
