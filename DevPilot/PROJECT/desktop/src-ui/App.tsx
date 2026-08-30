// 应用外壳：三栏骨架 + 内核初始化（拉快照/订阅事件）+ 创建向导 + 大白话 toast。
import { useEffect } from "react";
import Center from "./components/layout/Center";
import Rightbar from "./components/layout/Rightbar";
import Sidebar from "./components/layout/Sidebar";
import Topbar from "./components/layout/Topbar";
import PhaseBar from "./components/PhaseBar";
import { resetProjectStore, useProjectStore } from "./stores/project";
import { useUiStore } from "./stores/ui";
import CreateProject from "./views/onboarding/CreateProject";

export default function App() {
  const density = useUiStore((s) => s.density);
  const init = useProjectStore((s) => s.init);
  const wizardOpen = useProjectStore((s) => s.wizardOpen);
  const error = useProjectStore((s) => s.error);
  const dismissError = useProjectStore((s) => s.dismissError);

  useEffect(() => {
    void init();
    // 测试间共享模块级 store，卸载时重置避免串扰
    return () => resetProjectStore();
  }, [init]);

  // toast 8 秒自动消失：固定弹层不能长期拦截右栏点击（BUG-P01-02）
  useEffect(() => {
    if (!error) return;
    const t = setTimeout(dismissError, 8000);
    return () => clearTimeout(t);
  }, [error, dismissError]);

  return (
    <div className="flex h-screen flex-col" data-density={density}>
      <Topbar />
      <div className="flex min-h-0 flex-1">
        <Sidebar />
        <div className="relative flex min-w-0 flex-1">
          <Center />
          <PhaseBar />
        </div>
        <Rightbar />
      </div>

      {/* 大白话错误 toast（门禁拦截/路径冲突等） */}
      {error && (
        <div
          role="alert"
          className="panel fixed top-16 right-4 z-50 flex max-w-sm items-start gap-3 rounded-[14px] border-coral/40 p-4 text-sm"
        >
          <span className="flex-1 text-text">{error}</span>
          <button
            type="button"
            onClick={dismissError}
            className="text-xs text-text-dim hover:text-text"
          >
            知道了
          </button>
        </div>
      )}

      {wizardOpen && <CreateProject />}
    </div>
  );
}
