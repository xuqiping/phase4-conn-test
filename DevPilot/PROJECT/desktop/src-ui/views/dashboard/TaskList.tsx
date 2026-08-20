// 任务列表：接入当前 open round 的真实 tasks（P05 S7）。

import { useEffect, useState } from "react";
import { ipc, type TaskDto } from "../../lib/ipc";
import { useProjectStore } from "../../stores/project";

export default function TaskList() {
  const projectId = useProjectStore((s) => s.currentId);
  const [tasks, setTasks] = useState<TaskDto[]>([]);

  useEffect(() => {
    if (projectId == null) return;
    ipc
      .listTasks(projectId)
      .then(setTasks)
      .catch(() => setTasks([]));
  }, [projectId]);

  const statusClass = (status: string) => {
    switch (status) {
      case "done":
        return "text-success";
      case "failed":
        return "text-coral";
      case "running":
        return "text-brand2";
      default:
        return "text-text-faint";
    }
  };

  return (
    <div
      data-testid="task-list"
      className="panel max-h-[320px] overflow-y-auto rounded-[14px]"
    >
      {tasks.length === 0 && (
        <div className="px-4 py-3 text-xs text-text-dim">当前轮次暂无任务。</div>
      )}
      {tasks.map((t) => (
        <div
          key={t.id}
          className="flex items-center gap-3 border-b border-border px-4 py-2 text-[13px] last:border-0"
        >
          <span className="font-mono text-[11px] text-text-faint">
            #{String(t.chunk_no).padStart(3, "0")}
          </span>
          <span className="flex-1 truncate text-text">{t.title}</span>
          <span className={`text-[11px] ${statusClass(t.status)}`}>
            {t.status}
          </span>
          {t.cost_cents > 0 && (
            <span className="text-[11px] text-text-dim">{t.cost_cents}¢</span>
          )}
        </div>
      ))}
    </div>
  );
}
