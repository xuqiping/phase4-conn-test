// 轮次时间线骨架：轮次卡片 + chunk 光点格（Step 8 静态 mock，P05/P08 接通真实轮次）。
import ChunkDots from "../../components/hud/ChunkDots";

const MOCK_ROUNDS = [
  { seq: 1, title: "首轮：骨架搭建", status: "done", chunks: 8, done: 8 },
  { seq: 2, title: "第二轮：核心功能", status: "active", chunks: 10, done: 3 },
  { seq: 3, title: "第三轮：打磨与验收", status: "todo", chunks: 6, done: 0 },
] as const;

export default function RoundTimeline() {
  return (
    <div className="flex gap-[var(--space-gap)]">
      {MOCK_ROUNDS.map((r) => (
        <div
          key={r.seq}
          className={`panel flex-1 rounded-[14px] p-4 ${
            r.status === "active" ? "border-border-strong glow-brand" : ""
          }`}
        >
          <p className="flex items-center justify-between text-xs text-text-dim">
            第 {r.seq} 轮
            <span
              className={
                r.status === "done"
                  ? "text-success"
                  : r.status === "active"
                    ? "text-brand2"
                    : "text-text-faint"
              }
            >
              {r.status === "done" ? "已完成" : r.status === "active" ? "进行中" : "待开始"}
            </span>
          </p>
          <p className="mt-1.5 text-sm font-semibold">{r.title}</p>
          <div className="mt-2.5">
            <ChunkDots total={r.chunks} done={r.done} active={r.status === "active"} />
          </div>
        </div>
      ))}
    </div>
  );
}
