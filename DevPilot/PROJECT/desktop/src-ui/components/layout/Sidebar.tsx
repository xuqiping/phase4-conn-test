// 左栏导航：七视图入口，由视图注册表驱动（Step 5 联动点 1：点击切中栏+管道条+右栏）。
import { VIEW_REGISTRY } from "../../lib/viewRegistry";
import { useUiStore } from "../../stores/ui";

export default function Sidebar() {
  const view = useUiStore((s) => s.view);
  const setView = useUiStore((s) => s.setView);

  return (
    <nav
      data-testid="sidebar"
      className="panel flex w-48 shrink-0 flex-col gap-1 border-y-0 border-l-0 p-[var(--space-gap)]"
      aria-label="阶段导航"
    >
      {VIEW_REGISTRY.map((item) => (
        <button
          key={item.key}
          type="button"
          aria-current={view === item.key ? "page" : undefined}
          onClick={() => setView(item.key)}
          className={`flex h-[var(--space-row)] items-center gap-2.5 rounded-[9px] px-3 text-[13px] transition ${
            view === item.key
              ? "bg-card text-text shadow-[inset_2px_0_0_var(--color-brand)]"
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
