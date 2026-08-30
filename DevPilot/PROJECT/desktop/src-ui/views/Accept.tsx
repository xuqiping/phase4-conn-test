// 验收 视图（P06 S9 整合）：左清单 + 右预览，上安全面板，底部发布按钮。
// 发布可用性以内核 get_state 的 pending_gates 为准（plan 安全清单：不信前端状态）。
import { useCallback, useEffect, useState } from "react";
import AcceptanceChecklist from "../components/acceptance/AcceptanceChecklist";
import PreviewPane from "../components/acceptance/PreviewPane";
import SecurityPanel from "../components/acceptance/SecurityPanel";
import { errMessage, ipc, type AcceptanceItemDto } from "../lib/ipc";
import { useProjectStore } from "../stores/project";

export default function Accept() {
  const currentId = useProjectStore((s) => s.currentId);
  const projects = useProjectStore((s) => s.projects);
  const snapshot = useProjectStore((s) => s.snapshot);
  const requestRelease = useProjectStore((s) => s.requestRelease);
  const scale = projects.find((p) => p.id === currentId)?.scale ?? "";

  const [items, setItems] = useState<AcceptanceItemDto[]>([]);
  const [releaseError, setReleaseError] = useState<string | null>(null);
  const [releasing, setReleasing] = useState(false);

  const reload = useCallback(async () => {
    if (currentId == null) return;
    try {
      setItems(await ipc.getAcceptanceChecklist(currentId));
    } catch (e) {
      setReleaseError(errMessage(e));
    }
  }, [currentId]);

  useEffect(() => {
    setReleaseError(null);
    void reload();
  }, [reload]);

  // 发布可用：全部验收项 pass/na（Rust 侧 request_release 会再严格校验一次）。
  const allResolved =
    items.length > 0 && items.every((i) => i.status === "pass" || i.status === "na");

  const doRelease = async () => {
    setReleasing(true);
    setReleaseError(null);
    await requestRelease();
    setReleasing(false);
    // request_release 失败走 store 的 error toast；成功则快照已推进到 deploy。
  };

  return (
    <section data-testid="view-accept" className="panel flex-1 overflow-y-auto rounded-[14px] p-4">
      <div className="flex flex-col gap-4 lg:flex-row">
        <div className="flex w-full flex-col gap-4 lg:w-[420px] lg:shrink-0">
          <SecurityPanel scale={scale} />
          <AcceptanceChecklist projectId={currentId} onStatusChanged={reload} />
        </div>
        <div className="min-h-[480px] flex-1">
          <PreviewPane projectId={currentId ?? undefined} />
        </div>
      </div>

      <div className="mt-4 flex items-center justify-between border-t border-border pt-3">
        <p className="text-xs text-text-dim">
          {allResolved ? "全部验收项已通过" : "还有验收项未通过或不适用标记"}
        </p>
        <button
          type="button"
          data-testid="btn-release"
          className="rounded-[9px] border border-primary px-4 py-1.5 text-xs text-primary disabled:opacity-40"
          disabled={!allResolved || releasing}
          onClick={doRelease}
        >
          {releasing ? "发布中…" : "发布"}
        </button>
      </div>
      {releaseError && <p className="mt-1 text-xs text-red-400">{releaseError}</p>}
      {snapshot?.warning && <p className="mt-1 text-xs text-yellow-400">{snapshot.warning}</p>}
    </section>
  );
}
