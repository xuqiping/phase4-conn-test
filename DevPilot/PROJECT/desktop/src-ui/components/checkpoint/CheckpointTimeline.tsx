// 存档点时间轴 + 回滚（P05 S5 FR-037）。

import { useEffect, useState } from "react";
import { ipc, type CheckpointDto } from "../../lib/ipc";
import PlainText from "../plain/PlainText";

interface Props {
  projectId: number;
}

export default function CheckpointTimeline({ projectId }: Props) {
  const [checkpoints, setCheckpoints] = useState<CheckpointDto[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [confirmId, setConfirmId] = useState<number | null>(null);

  const load = async () => {
    setLoading(true);
    setError(null);
    try {
      const rows = await ipc.listCheckpoints(projectId);
      setCheckpoints(rows);
    } catch (e: unknown) {
      setError((e as Error)?.message ?? "加载存档点失败");
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    if (!projectId) return;
    void load();
  }, [projectId]);

  const rollback = async (id: number) => {
    setError(null);
    try {
      await ipc.rollbackToCheckpoint(id);
      setConfirmId(null);
      await load();
    } catch (e: unknown) {
      setError((e as Error)?.message ?? "回滚失败");
    }
  };

  if (loading && checkpoints.length === 0) {
    return <div className="text-xs text-text-dim">加载存档点…</div>;
  }

  if (checkpoints.length === 0) {
    return <div className="text-xs text-text-dim">暂无存档点，执行任务后会自动生成。</div>;
  }

  return (
    <div className="panel space-y-3 rounded-[9px] p-4">
      <div className="flex items-center justify-between">
        <h3 className="text-sm font-semibold">存档点时间轴</h3>
        <button
          type="button"
          onClick={() => void load()}
          className="text-xs text-text-dim hover:text-text"
        >
          刷新
        </button>
      </div>

      {error && <div className="text-xs text-red-400">{error}</div>}

      <div className="relative space-y-4 pl-3">
        <div className="absolute bottom-2 left-[11px] top-2 w-px bg-border" />
        {checkpoints.map((cp) => (
          <div key={cp.id} className="relative pl-5">
            <div
              className={`absolute left-0 top-1 h-2.5 w-2.5 rounded-full border-2 ${
                cp.status === "success"
                  ? "border-success bg-success"
                  : "border-coral bg-coral"
              }`}
            />
            <div className="space-y-1">
              <div className="flex items-center justify-between">
                <div className="text-xs font-medium text-text">
                  #{cp.chunk_no} {cp.title || "未命名 task"}
                </div>
                {confirmId === cp.id ? (
                  <div className="flex items-center gap-1">
                    <button
                      type="button"
                      onClick={() => void rollback(cp.id)}
                      className="rounded-[4px] bg-coral px-1.5 py-0.5 text-[10px] text-white"
                    >
                      确认
                    </button>
                    <button
                      type="button"
                      onClick={() => setConfirmId(null)}
                      className="rounded-[4px] border border-border px-1.5 py-0.5 text-[10px] text-text-dim"
                    >
                      取消
                    </button>
                  </div>
                ) : (
                  <button
                    type="button"
                    onClick={() => setConfirmId(cp.id)}
                    className="rounded-[4px] border border-border px-1.5 py-0.5 text-[10px] text-text-dim hover:bg-card"
                  >
                    回滚
                  </button>
                )}
              </div>
              <div className="text-[11px] text-text-dim">
                {cp.git_commit.slice(0, 8)} · {cp.status === "success" ? "成功" : "失败"} ·{" "}
                {cp.created_at}
              </div>
              {cp.summary_plain && (
                <div className="text-[11px] text-text-dim">
                  <PlainText
                    text={cp.summary_plain}
                    context="存档点摘要"
                    className="line-clamp-2"
                  />
                </div>
              )}
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}
