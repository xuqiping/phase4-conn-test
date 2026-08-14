// 右栏：五 Tab（规格/变更/日志/预览/文件，对齐 UI 设计 §右栏）。内容为静态占位。
import { useState } from "react";

const TABS = [
  { key: "spec", label: "规格" },
  { key: "changes", label: "变更" },
  { key: "logs", label: "日志" },
  { key: "preview", label: "预览" },
  { key: "files", label: "文件" },
] as const;

type TabKey = (typeof TABS)[number]["key"];

const PLACEHOLDER: Record<TabKey, string> = {
  spec: "需求/计划产物摘要（P04 接通）",
  changes: "变更记录（P08 接通）",
  logs: "内核事件日志（Step 7 事件订阅后接通）",
  preview: "内置预览窗格（FR-052，P06 接通）",
  files: "项目文件树（FR-051，P08 接通）",
};

export default function Rightbar() {
  const [tab, setTab] = useState<TabKey>("spec");

  return (
    <aside
      data-testid="rightbar"
      className="panel flex w-72 shrink-0 flex-col border-y-0 border-r-0"
      aria-label="右侧面板"
    >
      <div role="tablist" className="flex border-b border-border">
        {TABS.map((t) => (
          <button
            key={t.key}
            role="tab"
            aria-selected={tab === t.key}
            onClick={() => setTab(t.key)}
            className={`flex-1 py-2 text-xs transition ${
              tab === t.key
                ? "border-b-2 border-brand font-semibold text-text"
                : "text-text-faint hover:text-text-dim"
            }`}
          >
            {t.label}
          </button>
        ))}
      </div>
      <div className="flex-1 p-[var(--space-pad)] text-sm text-text-dim">
        {PLACEHOLDER[tab]}
      </div>
    </aside>
  );
}
