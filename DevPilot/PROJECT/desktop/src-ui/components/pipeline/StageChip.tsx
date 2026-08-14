// 管道条单个阶段节点：呼吸/流光效对齐 prototypes/shared.css。
// done/active 状态当前按当前视图静态推导（Step 7 起由状态机驱动）。
import type { ViewDef, ViewKey } from "../../lib/viewRegistry";
import { STAGES } from "../../lib/viewRegistry";
import { useUiStore } from "../../stores/ui";

interface Props {
  def: ViewDef;
  /** 当前视图在阶段序列中的次序；-1 = 驾驶舱（无高亮） */
  currentOrder: number;
}

export default function StageChip({ def, currentOrder }: Props) {
  const setView = useUiStore((s) => s.setView);
  const order = STAGES.findIndex((s) => s.key === def.key);
  const state =
    currentOrder >= 0 && order < currentOrder
      ? "done"
      : order === currentOrder
        ? "active"
        : "todo";

  return (
    <button
      type="button"
      className={`stage stage-${state}`}
      aria-current={state === "active" ? "step" : undefined}
      onClick={() => setView(def.key as ViewKey)}
    >
      <span className="dot" aria-hidden>
        {state === "done" ? "✓" : "●"}
      </span>
      {def.label}
    </button>
  );
}
