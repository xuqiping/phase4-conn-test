// 轮次时间线：接入真实 rounds + tasks 统计（P05 S7）。

import { useEffect, useState } from "react";
import ChunkDots from "../../components/hud/ChunkDots";
import { ipc, type RoundDto } from "../../lib/ipc";
import { useProjectStore } from "../../stores/project";

export default function RoundTimeline() {
  const projectId = useProjectStore((s) => s.currentId);
  const [rounds, setRounds] = useState<RoundDto[]>([]);

  useEffect(() => {
    if (projectId == null) return;
    ipc.listRounds(projectId).then(setRounds).catch(() => setRounds([]));
  }, [projectId]);

  return (
    <div className="flex gap-[var(--space-gap)]">
      {rounds.map((r) => {
        const status =
          r.status === "open"
            ? "active"
            : r.total_tasks > 0 && r.done_tasks === r.total_tasks
              ? "done"
              : "todo";
        return (
          <div
            key={r.id}
            className={`panel flex-1 rounded-[14px] p-4 ${
              status === "active" ? "border-border-strong glow-brand" : ""
            }`}
          >
            <p className="flex items-center justify-between text-xs text-text-dim">
              第 {r.seq} 轮
              <span
                className={
                  status === "done"
                    ? "text-success"
                    : status === "active"
                      ? "text-brand2"
                      : "text-text-faint"
                }
              >
                {status === "done"
                  ? "已完成"
                  : status === "active"
                    ? "进行中"
                    : "待开始"}
              </span>
            </p>
            <p className="mt-1.5 text-sm font-semibold">{r.title}</p>
            <div className="mt-2.5">
              <ChunkDots
                total={r.total_tasks || 1}
                done={r.done_tasks}
                active={status === "active"}
              />
            </div>
          </div>
        );
      })}
      {rounds.length === 0 && (
        <div className="panel flex-1 rounded-[14px] p-4 text-xs text-text-dim">
          暂无轮次
        </div>
      )}
    </div>
  );
}
