// 任务列表（1000 条 mock + 虚拟滚动，PERF-03 骨架期验证）。
import { useVirtualList } from "../../lib/useVirtualList";

const MOCK_COUNT = 1000;
const ROW_H = 34;
const VIEWPORT_H = 320;

export default function TaskList() {
  const win = useVirtualList(MOCK_COUNT, ROW_H, VIEWPORT_H);
  const rows = Array.from({ length: win.end - win.start }, (_, i) => win.start + i);

  return (
    <div
      data-testid="task-list"
      className="panel overflow-y-auto rounded-[14px]"
      style={{ height: VIEWPORT_H }}
      onScroll={win.onScroll}
    >
      <div style={{ height: win.totalHeight, position: "relative" }}>
        <div style={{ transform: `translateY(${win.offsetTop}px)` }}>
          {rows.map((i) => (
            <div
              key={i}
              className="flex items-center gap-3 border-b border-border px-4 text-[13px]"
              style={{ height: ROW_H }}
            >
              <span className="font-mono text-[11px] text-text-faint">
                #{String(i + 1).padStart(3, "0")}
              </span>
              <span className="text-text-dim">示例任务 {i + 1}（P05 接通真实任务流）</span>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}
