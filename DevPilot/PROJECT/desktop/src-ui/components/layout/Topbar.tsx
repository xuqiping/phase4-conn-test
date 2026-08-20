// 顶栏：Logo + 项目切换（下拉） + 阶段管道条 + 密度开关。
import { Fragment, useState } from "react";
import { useProjectStore } from "../../stores/project";
import { useUiStore } from "../../stores/ui";
import AgentConfigModal from "../agent/AgentConfigModal";
import PipelineBar from "../pipeline/PipelineBar";
import BalanceRing from "../topbar/BalanceRing";

export default function Topbar() {
  const density = useUiStore((s) => s.density);
  const toggleDensity = useUiStore((s) => s.toggleDensity);
  const projects = useProjectStore((s) => s.projects);
  const currentId = useProjectStore((s) => s.currentId);
  const select = useProjectStore((s) => s.select);
  const openWizard = useProjectStore((s) => s.openWizard);
  const [menuOpen, setMenuOpen] = useState(false);

  const current = projects.find((p) => p.id === currentId);
  const [agentsOpen, setAgentsOpen] = useState(false);

  return (
    <Fragment>
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

      {/* 项目切换（联动点 3：切换 = 整体换快照） */}
      <div className="relative">
        <button
          type="button"
          onClick={() => setMenuOpen((o) => !o)}
          className="flex items-center gap-2 rounded-[9px] border border-border bg-card px-3 py-1.5 text-[13px] text-text-dim transition hover:border-border-strong hover:text-text"
        >
          {current ? current.name : "未选择项目"}
          <span className="text-[10px]">▾</span>
        </button>
        {menuOpen && (
          <>
            <div
              className="fixed inset-0 z-40"
              onClick={() => setMenuOpen(false)}
              aria-hidden
            />
            <div
              role="menu"
              className="panel absolute top-full left-0 z-50 mt-1 w-56 rounded-[9px] p-1.5"
            >
              {projects.map((p) => (
                <button
                  key={p.id}
                  type="button"
                  role="menuitem"
                  onClick={() => {
                    setMenuOpen(false);
                    void select(p.id);
                  }}
                  className={`flex w-full items-center justify-between rounded-md px-3 py-1.5 text-[13px] transition hover:bg-card-hover ${
                    p.id === currentId ? "text-text" : "text-text-dim"
                  }`}
                >
                  {p.name}
                  <span className="font-mono text-[10px] text-text-faint">
                    {p.scale}
                  </span>
                </button>
              ))}
              <button
                type="button"
                role="menuitem"
                onClick={() => {
                  setMenuOpen(false);
                  openWizard();
                }}
                className="mt-0.5 w-full rounded-md border-t border-border px-3 py-1.5 text-left text-[13px] text-brand2 transition hover:bg-card-hover"
              >
                ＋ 新建项目
              </button>
            </div>
          </>
        )}
      </div>

      {/* 阶段管道条（内核快照驱动） */}
      <PipelineBar />

      {/* 余额环（云端计费联动，AC-045） */}
      <div className="ml-auto">
        <BalanceRing />
      </div>

      {/* 项目约定入口（P04 S1 FR-008） */}
      <button
        type="button"
        onClick={() => setAgentsOpen(true)}
        className="rounded-[9px] border border-border bg-card px-3 py-1.5 text-xs text-text-dim transition hover:border-border-strong hover:text-text"
        aria-label="项目约定"
      >
        项目约定
      </button>

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
      {agentsOpen && <AgentConfigModal onClose={() => setAgentsOpen(false)} />}
    </Fragment>
  );
}
