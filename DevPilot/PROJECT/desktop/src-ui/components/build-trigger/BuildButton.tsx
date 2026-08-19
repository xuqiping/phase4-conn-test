// 建造阶段「一键执行」按钮（P03 Step8 状态机集成）。
// 仅在当前阶段为 build 时显示；调用 execute_build 后自动推进到 accept 或留在 build。

import { useState } from "react";
import { invoke } from "@tauri-apps/api/core";

interface StateVo {
  project_id: number;
  phase: string;
  warning?: string | null;
}

interface Props {
  projectId: number;
  phase: string;
  onStateChange?: (s: StateVo) => void;
}

export default function BuildButton({ projectId, phase, onStateChange }: Props) {
  const [running, setRunning] = useState(false);
  const [error, setError] = useState<string | null>(null);

  if (phase !== "build") {
    return null;
  }

  const run = async () => {
    setRunning(true);
    setError(null);
    try {
      const s = await invoke<StateVo>("execute_build", { projectId });
      onStateChange?.(s);
    } catch (e: unknown) {
      setError((e as Error)?.message ?? "构建执行失败");
    } finally {
      setRunning(false);
    }
  };

  return (
    <div className="space-y-2">
      <button
        type="button"
        disabled={running}
        onClick={run}
        className="w-full rounded-[9px] bg-brand px-3 py-2 text-sm font-medium text-white transition hover:bg-brand2 disabled:opacity-50"
      >
        {running ? "构建执行中…" : "🚀 执行构建并推进到验收"}
      </button>
      {error && <div className="text-xs text-red-400">{error}</div>}
    </div>
  );
}
