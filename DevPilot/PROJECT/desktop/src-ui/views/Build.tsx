// 建造 视图占位：任务流与大白话进度（P05 S4 FR-036 / S5 FR-037）。

import CheckpointTimeline from "../components/checkpoint/CheckpointTimeline";
import PlainText from "../components/plain/PlainText";
import { useProjectStore } from "../stores/project";
import { useUiStore } from "../stores/ui";

export default function Build() {
  const projectId = useProjectStore((s) => s.currentId);
  const plainMode = useUiStore((s) => s.plainMode);
  const togglePlainMode = useUiStore((s) => s.togglePlainMode);

  return (
    <section
      data-testid="view-build"
      className="flex flex-1 flex-col gap-[var(--space-gap)] overflow-y-auto pr-1"
    >
      <div className="panel flex flex-col items-center justify-center gap-4 rounded-[14px] p-6">
        <div className="text-center">
          <p className="text-lg font-semibold">建造</p>
          <p className="mt-2 text-sm text-text-dim">
            <PlainText
              text="任务流按 chunk 顺序执行：LLM 生成文件变更 → runner 跑测试/lint → 提交 checkpoint。"
              context="建造阶段说明"
            />
          </p>
        </div>

        <button
          type="button"
          onClick={togglePlainMode}
          className="rounded-[9px] border border-border px-3 py-1.5 text-xs text-text-dim hover:bg-card"
        >
          {plainMode ? "关闭大白话" : "开启大白话"}
        </button>
      </div>

      {projectId != null && <CheckpointTimeline projectId={projectId} />}
    </section>
  );
}
