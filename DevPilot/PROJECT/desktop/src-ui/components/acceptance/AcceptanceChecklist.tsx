// 验收清单（P06 S9 / FR-033 / AC-036）：从测试方案生成，点一点改状态。
import { useCallback, useEffect, useState } from "react";
import { errMessage, ipc, type AcceptanceItemDto } from "../../lib/ipc";

const STATUS_LABEL: Record<string, string> = {
  pending: "待验收",
  pass: "通过",
  fail: "不通过",
  na: "不适用",
};

const STATUS_CLASS: Record<string, string> = {
  pending: "bg-slate-500/20 text-slate-400",
  pass: "bg-emerald-500/20 text-emerald-400",
  fail: "bg-red-500/20 text-red-400",
  na: "bg-sky-500/20 text-sky-400",
};

export default function AcceptanceChecklist({
  projectId,
  onStatusChanged,
}: {
  projectId: number | null;
  onStatusChanged?: () => void;
}) {
  const [items, setItems] = useState<AcceptanceItemDto[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [smoke, setSmoke] = useState<string | null>(null);

  const reload = useCallback(async () => {
    if (projectId == null) return;
    try {
      setItems(await ipc.getAcceptanceChecklist(projectId));
      setError(null);
    } catch (e) {
      setError(errMessage(e));
    }
  }, [projectId]);

  useEffect(() => {
    setSmoke(null);
    void reload();
  }, [reload]);

  const setStatus = async (item: AcceptanceItemDto, status: AcceptanceItemDto["status"]) => {
    if (projectId == null || busy) return;
    setBusy(true);
    try {
      await ipc.updateAcceptanceItem(item.id, { status });
      await reload();
      onStatusChanged?.();
    } catch (e) {
      setError(errMessage(e));
    } finally {
      setBusy(false);
    }
  };

  const regenerate = async () => {
    if (projectId == null || busy) return;
    // Phase4 审查修复（C7）：重新生成会重置所有验收状态，必须二次确认。
    if (
      items.length > 0 &&
      !window.confirm("重新生成将清空当前验收清单（含已通过/NA 状态），确定继续？")
    ) {
      return;
    }
    setBusy(true);
    try {
      setItems(await ipc.regenerateAcceptanceChecklist(projectId));
      setError(null);
    } catch (e) {
      setError(errMessage(e));
    } finally {
      setBusy(false);
    }
  };

  const runSmoke = async () => {
    if (projectId == null || busy) return;
    setBusy(true);
    setSmoke(null);
    try {
      const r = await ipc.runSmokeCheck(projectId);
      setSmoke(
        `自动验收：通过 ${r.passed} 项，失败 ${r.failed} 项，跳过 ${r.skipped} 项` +
          (r.warning ? `（${r.warning}）` : ""),
      );
      await reload();
      onStatusChanged?.();
    } catch (e) {
      setError(errMessage(e));
    } finally {
      setBusy(false);
    }
  };

  const allResolved = items.length > 0 && items.every((i) => i.status === "pass" || i.status === "na");

  return (
    <section data-testid="acceptance-checklist" className="rounded-[14px] border border-border p-3">
      <div className="flex items-center justify-between">
        <h3 className="text-sm font-semibold">
          验收清单
          <span className="ml-2 text-xs text-text-dim">
            {items.filter((i) => i.status === "pass" || i.status === "na").length}/{items.length} 已通过
          </span>
        </h3>
        <div className="flex gap-2">
          <button
            type="button"
            data-testid="btn-regenerate"
            className="rounded-[9px] border border-border px-2 py-1 text-xs text-text-dim hover:bg-card disabled:opacity-50"
            onClick={regenerate}
            disabled={projectId == null || busy}
          >
            重新生成
          </button>
          <button
            type="button"
            data-testid="btn-run-smoke"
            className="rounded-[9px] border border-border px-2 py-1 text-xs text-text-dim hover:bg-card disabled:opacity-50"
            onClick={runSmoke}
            disabled={projectId == null || busy || !items.some((i) => i.method === "auto")}
          >
            跑自动验收
          </button>
        </div>
      </div>

      {error && <p className="mt-2 text-xs text-red-400">{error}</p>}
      {smoke && <p data-testid="smoke-result" className="mt-2 text-xs text-yellow-400">{smoke}</p>}

      {items.length === 0 ? (
        <p className="mt-3 text-sm text-text-dim">
          暂无验收项。请先在项目里写测试方案文档，再点「重新生成」。
        </p>
      ) : (
        <ul className="mt-3">
          {items.map((item) => (
            <li
              key={item.id}
              data-testid={`acceptance-item-${item.tc_id}`}
              className="border-b border-border/50 py-2 last:border-none"
            >
              <div className="flex items-center gap-2">
                <span
                  className={`rounded px-1.5 py-0.5 text-xs ${STATUS_CLASS[item.status] ?? STATUS_CLASS.pending}`}
                >
                  {STATUS_LABEL[item.status] ?? item.status}
                </span>
                <span className="flex-1 truncate text-sm" title={`${item.title}：${item.expected}`}>
                  {item.tc_id} {item.title}
                  {item.method === "auto" && (
                    <span className="ml-1 text-xs text-text-dim">（自动）</span>
                  )}
                </span>
              </div>
              <div className="mt-1 flex gap-1">
                {(["pass", "fail", "na"] as const).map((s) => (
                  <button
                    key={s}
                    type="button"
                    data-testid={`btn-${item.tc_id}-${s}`}
                    className={`rounded border px-2 py-0.5 text-xs ${
                      item.status === s
                        ? "border-primary text-primary"
                        : "border-border text-text-dim hover:bg-card"
                    }`}
                    disabled={busy}
                    onClick={() => setStatus(item, s)}
                  >
                    {STATUS_LABEL[s]}
                  </button>
                ))}
                {item.fix_task_id != null && (
                  <span className="ml-1 self-center text-xs text-text-dim">
                    修复任务 #{item.fix_task_id}
                  </span>
                )}
              </div>
            </li>
          ))}
        </ul>
      )}

      {allResolved && (
        <p data-testid="checklist-all-resolved" className="mt-2 text-xs text-emerald-400">
          全部验收项已通过，可以发布
        </p>
      )}
    </section>
  );
}
