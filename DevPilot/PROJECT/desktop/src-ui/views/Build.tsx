// 建造 视图占位：任务流与大白话进度（P05 S4 FR-036 接入大白话翻译层）。

import PlainText from "../components/plain/PlainText";
import { useUiStore } from "../stores/ui";

export default function Build() {
  const plainMode = useUiStore((s) => s.plainMode);
  const togglePlainMode = useUiStore((s) => s.togglePlainMode);

  return (
    <section
      data-testid="view-build"
      className="panel flex flex-1 flex-col items-center justify-center gap-4 rounded-[14px]"
    >
      <div className="text-center">
        <p className="text-lg font-semibold">建造</p>
        <p className="mt-2 text-sm text-text-dim">
          <PlainText
            text="任务流按 chunk 顺序执行：LLM 生成文件变更 → runner 跑测试/lint → 提交 checkpoint。"
            context="建造阶段说明"
          />
        </p>
      </div>

      <button
        type="button"
        onClick={togglePlainMode}
        className="rounded-[9px] border border-border px-3 py-1.5 text-xs text-text-dim hover:bg-card"
      >
        {plainMode ? "关闭大白话" : "开启大白话"}
      </button>
    </section>
  );
}
