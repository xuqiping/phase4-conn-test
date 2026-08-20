// 驾驶舱静态版（Step 8）：HUD 四指标 + 轮次时间线 + 任务列表（虚拟滚动）。
// 进度读状态机快照（真实）；缺陷/覆盖率/消耗占位「待接通」（plan 备注：P02/P05 接通）。
import HudTile from "../../components/hud/HudTile";
import PlainText from "../../components/plain/PlainText";
import { useProjectStore } from "../../stores/project";
import RoundTimeline from "./RoundTimeline";
import TaskList from "./TaskList";

export default function Dashboard() {
  const snapshot = useProjectStore((s) => s.snapshot);

  // 进度 = 已过阶段数 / 总阶段数（状态机真实数据，AC-043 数据源部分）
  const progress = snapshot
    ? Math.round(
        (snapshot.phases.filter((p) => p.status === "done").length /
          snapshot.phases.length) *
          100,
      )
    : 0;

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
          value="—"
          hint={<PlainText text="待 P06 验收接通" context="驾驶舱指标" />}
          tone="coral"
        />
        <HudTile
          label="覆盖率"
          value="—"
          hint={<PlainText text="待 P05 任务接通" context="驾驶舱指标" />}
          tone="success"
        />
        <HudTile
          label="消耗"
          value="—"
          hint={<PlainText text="待 P02 计量接通" context="驾驶舱指标" />}
          tone="amber"
        />
      </div>
      <RoundTimeline />
      <TaskList />
    </section>
  );
}
