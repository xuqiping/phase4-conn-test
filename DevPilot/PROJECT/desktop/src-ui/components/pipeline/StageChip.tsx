// 管道条单个阶段节点（Step 7 起由内核快照驱动）。
// 点击语义：有项目 = 向内核发起阶段转移（被门禁/越阶段拦则 toast 大白话）；
// 无项目 = 仅静态预览视图。
import type { ViewKey } from "../../lib/viewRegistry";

export interface StageItem {
  key: ViewKey;
  label: string;
  status: "done" | "active" | "todo";
}

export default function StageChip({
  item,
  onClick,
}: {
  item: StageItem;
  onClick: (key: ViewKey) => void;
}) {
  return (
    <button
      type="button"
      className={`stage stage-${item.status}`}
      aria-current={item.status === "active" ? "step" : undefined}
      onClick={() => onClick(item.key)}
    >
      <span className="dot" aria-hidden>
        {item.status === "done" ? "✓" : "●"}
      </span>
      {item.label}
    </button>
  );
}
