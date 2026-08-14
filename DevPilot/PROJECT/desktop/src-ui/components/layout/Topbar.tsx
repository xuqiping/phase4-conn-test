// 顶栏：Logo + 项目切换 + 阶段管道条（Step 5 静态联动）+ 密度开关。
import { useUiStore } from "../../stores/ui";
import PipelineBar from "../pipeline/PipelineBar";

export default function Topbar() {
  const density = useUiStore((s) => s.density);
  const toggleDensity = useUiStore((s) => s.toggleDensity);

  return (
    <header
      data-testid="topbar"
      className="panel relative flex h-14 shrink-0 items-center gap-4 border-x-0 border-t-0 px-[var(--space-pad)]"
    >
      {/* 品牌 */}
      <div className="flex items-center gap-2.5">
        <span className="glow-brand grid size-[26px] place-items-center rounded-lg bg-gradient-to-br from-brand to-brand2 text-[13px] font-extrabold text-white">
          D
        </span>
        <span className="text-[15px] font-bold tracking-widest">DevPilot</span>
      </div>

      {/* 项目切换（Step 7 接创建向导后启用） */}
      <button
        type="button"
        className="flex cursor-default items-center gap-2 rounded-[9px] border border-border bg-card px-3 py-1.5 text-[13px] text-text-dim"
        title="项目切换将在创建向导接通后启用"
      >
        未选择项目 <span className="text-[10px]">▾</span>
      </button>

      {/* 阶段管道条（Step 5 静态联动；Step 7 起由状态机事件驱动） */}
      <PipelineBar />

      {/* 密度切换 */}
      <button
        type="button"
        onClick={toggleDensity}
        className="rounded-[9px] border border-border bg-card px-3 py-1.5 text-xs text-text-dim transition hover:border-border-strong hover:text-text"
        aria-label="切换界面密度"
      >
        {density === "comfort" ? "舒适" : "紧凑"}
      </button>
    </header>
  );
}
