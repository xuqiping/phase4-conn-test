// 阶段管道条（顶栏中央）：想法→需求→计划→建造→验收→部署。
// Step 5 静态版：高亮 = 当前视图；Step 7 起由状态机事件驱动真实阶段。
import { Fragment } from "react";
import { STAGES } from "../../lib/viewRegistry";
import { useUiStore } from "../../stores/ui";
import StageChip from "./StageChip";

export default function PipelineBar() {
  const view = useUiStore((s) => s.view);
  const currentOrder = STAGES.findIndex((s) => s.key === view); // 驾驶舱 = -1

  return (
    <div
      className="pipeline"
      data-testid="pipeline"
      role="navigation"
      aria-label="阶段管道条"
    >
      {STAGES.map((s, i) => (
        <Fragment key={s.key}>
          {i > 0 && (
            <span className="stage-sep" aria-hidden>
              ›
            </span>
          )}
          <StageChip def={s} currentOrder={currentOrder} />
        </Fragment>
      ))}
    </div>
  );
}
