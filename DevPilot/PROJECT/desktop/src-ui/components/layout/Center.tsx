// 中栏主视图区：按当前视图从注册表取组件渲染（Step 5）。
import { viewDef } from "../../lib/viewRegistry";
import { useUiStore } from "../../stores/ui";

export default function Center() {
  const view = useUiStore((s) => s.view);
  const ActiveView = viewDef(view).component;

  return (
    <main
      data-testid="center"
      className="flex min-w-0 flex-1 p-[var(--space-pad)]"
    >
      <ActiveView />
    </main>
  );
}
