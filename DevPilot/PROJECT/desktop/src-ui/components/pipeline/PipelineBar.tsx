// 阶段管道条（顶栏中央）。
// 高亮 = 内核状态机真相（快照 phases.status）；无项目时按当前浏览视图静态高亮。
// 规模变体（L0/L1 跳阶段）由快照 phases 自动呈现——缺哪些阶段就不渲染哪些（联动点 2）。
import { Fragment } from "react";
import { STAGES, type ViewKey } from "../../lib/viewRegistry";
import { useProjectStore } from "../../stores/project";
import { useUiStore } from "../../stores/ui";
import StageChip, { type StageItem } from "./StageChip";

export default function PipelineBar() {
  const snapshot = useProjectStore((s) => s.snapshot);
  const transition = useProjectStore((s) => s.transition);
  const view = useUiStore((s) => s.view);
  const setView = useUiStore((s) => s.setView);

  let items: StageItem[];
  if (snapshot) {
    items = snapshot.phases.map((p) => ({
      key: p.key as ViewKey,
      label: STAGES.find((s) => s.key === p.key)?.label ?? p.label,
      status: p.status,
    }));
  } else {
    const order = STAGES.findIndex((s) => s.key === view);
    items = STAGES.map((s, i) => ({
      key: s.key,
      label: s.label,
      status: order >= 0 && i < order ? "done" : i === order ? "active" : "todo",
    }));
  }

  const onClick = (key: ViewKey) => {
    if (snapshot) {
      void transition(key); // 内核裁决；被拒走 toast
    } else {
      setView(key);
    }
  };

  return (
    <div
      className="pipeline"
      data-testid="pipeline"
      role="navigation"
      aria-label="阶段管道条"
    >
      {items.map((item, i) => (
        <Fragment key={item.key}>
          {i > 0 && (
            <span className="stage-sep" aria-hidden>
              ›
            </span>
          )}
          <StageChip item={item} onClick={onClick} />
        </Fragment>
      ))}
    </div>
  );
}
