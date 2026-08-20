// 驾驶舱真实数据版（P05 S7）：HUD 四指标读真实 tasks/rounds。

import { useEffect, useState } from "react";
import HudTile from "../../components/hud/HudTile";
import PlainText from "../../components/plain/PlainText";
import { ipc, type TaskDto } from "../../lib/ipc";
import { useProjectStore } from "../../stores/project";
import RoundTimeline from "./RoundTimeline";
import TaskList from "./TaskList";

export default function Dashboard() {
  const snapshot = useProjectStore((s) => s.snapshot);
  const projectId = useProjectStore((s) => s.currentId);
  const [tasks, setTasks] = useState<TaskDto[]>([]);

  useEffect(() => {
    if (projectId == null) return;
    ipc
      .listTasks(projectId)
      .then(setTasks)
      .catch(() => setTasks([]));
  }, [projectId, snapshot?.phase]);

  const progress = snapshot
    ? Math.round(
        (snapshot.phases.filter((p) => p.status === "done").length /
          snapshot.phases.length) *
          100,
      )
    : 0;

  const failed = tasks.filter((t) => t.status === "failed").length;
  const done = tasks.filter((t) => t.status === "done").length;
  const total = tasks.length;
  const coverage = total > 0 ? Math.round((done / total) * 100) : 0;
  const cost = tasks.reduce((sum, t) => sum + t.cost_cents, 0);

  return (
    <section
      data-testid="view-dashboard"
      className="flex flex-1 flex-col gap-[var(--space-gap)] overflow-y-auto pr-1"
    >
      <div className="grid grid-cols-4 gap-[var(--space-gap)]">
        <HudTile
          label="进度"
          value={`${progress}`}
          unit="%"
          hint={<PlainText text="来自状态机（真实）" context="驾驶舱指标" />}
          tone="brand"
        />
        <HudTile
          label="缺陷"
          value={failed > 0 ? `${failed}` : "—"}
          hint={<PlainText
            text={`当前轮次失败任务数${failed > 0 ? "（真实）" : ""}`}
            context="驾驶舱指标"
          />}
          tone="coral"
        />
        <HudTile
          label="覆盖率"
          value={`${coverage}`}
          unit="%"
          hint={<PlainText
            text="当前轮次 done 任务占比（真实）"
            context="驾驶舱指标"
          />}
          tone="success"
        />
        <HudTile
          label="消耗"
          value={`${cost}`}
          unit="¢"
          hint={<PlainText
            text="当前轮次任务 cost_cents 累计（真实）"
            context="驾驶舱指标"
          />}
          tone="amber"
        />
      </div>
      <RoundTimeline />
      <TaskList />
    </section>
  );
}
