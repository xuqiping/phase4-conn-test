// 左栏导航：七视图入口（驾驶舱 + 六阶段）。Step 5 挂视图注册表后点击真正切换。
const NAV_ITEMS = [
  { key: "dashboard", icon: "▣", label: "驾驶舱" },
  { key: "idea", icon: "💡", label: "想法" },
  { key: "spec", icon: "📋", label: "需求" },
  { key: "plan", icon: "🗺️", label: "计划" },
  { key: "build", icon: "🔨", label: "建造" },
  { key: "accept", icon: "✅", label: "验收" },
  { key: "deploy", icon: "🚀", label: "部署" },
] as const;

export default function Sidebar() {
  return (
    <nav
      data-testid="sidebar"
      className="panel flex w-48 shrink-0 flex-col gap-1 border-y-0 border-l-0 p-[var(--space-gap)]"
      aria-label="阶段导航"
    >
      {NAV_ITEMS.map((item, i) => (
        <button
          key={item.key}
          type="button"
          className={`flex h-[var(--space-row)] items-center gap-2.5 rounded-[9px] px-3 text-[13px] transition ${
            i === 0
              ? "bg-card text-text"
              : "text-text-dim hover:bg-card-hover hover:text-text"
          }`}
        >
          <span aria-hidden>{item.icon}</span>
          {item.label}
        </button>
      ))}
    </nav>
  );
}
