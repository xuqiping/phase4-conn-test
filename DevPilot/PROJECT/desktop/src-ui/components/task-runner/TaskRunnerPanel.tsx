// 任务执行面板 UI（P03 Step6 FR-003/AC-004）。
// MVP：展示阶段、测试输出、diff 摘要、成功/失败状态。

import { useState } from "react";
import { invoke } from "@tauri-apps/api/core";

interface TaskResultVo {
  success: boolean;
  phase: string;
  diff_summary: string;
  fix_attempts: number;
  cost_cents: number;
  error: string | null;
  test_result?: {
    command: string;
    exit_code: number | null;
    stdout: string;
    stderr: string;
    timed_out: boolean;
  } | null;
}

interface Props {
  projectId: number;
}

export default function TaskRunnerPanel({ projectId }: Props) {
  const [running, setRunning] = useState(false);
  const [result, setResult] = useState<TaskResultVo | null>(null);
  const [logs, setLogs] = useState<string[]>([]);
  const [error, setError] = useState<string | null>(null);

  const run = async () => {
    setRunning(true);
    setResult(null);
    setLogs([]);
    setError(null);
    try {
      const res = await invoke<TaskResultVo>("run_task", {
        req: {
          project_id: projectId,
          task_id: 0,
          title: "MVP 闭环任务",
          instructions: "跑通本地测试并提交存档点",
          files: [],
          test_command: null,
          max_fix_attempts: 0,
        },
      });
      setResult(res);
    } catch (e: unknown) {
      setError((e as Error)?.message ?? "任务执行失败");
    } finally {
      setRunning(false);
    }
  };

  return (
    <div className="panel space-y-3 rounded-[9px] p-4">
      <h3 className="text-sm font-semibold">本地任务执行</h3>
      <p className="text-xs text-text-dim">
        自动探测技术栈、安装缺失环境、跑测试/lint、失败自动修复并提交存档点。
      </p>
      <button
        type="button"
        disabled={running}
        onClick={run}
        className="w-full rounded-[9px] bg-brand px-3 py-2 text-sm font-medium text-white transition hover:bg-brand2 disabled:opacity-50"
      >
        {running ? "执行中…" : "开始闭环任务"}
      </button>

      {logs.length > 0 && (
        <pre className="max-h-48 overflow-auto rounded-md border border-border bg-card p-2 text-xs text-text-dim">
          {logs.join("\n")}
        </pre>
      )}

      {error && <div className="text-xs text-red-400">{error}</div>}

      {result && (
        <div
          className={`space-y-2 rounded-md border p-3 text-xs ${
            result.success
              ? "border-green-500/30 bg-green-500/10 text-green-400"
              : "border-red-500/30 bg-red-500/10 text-red-400"
          }`}
        >
          <div className="font-medium">
            {result.success ? "✅ 任务完成" : "❌ 任务失败"} · 阶段 {result.phase}
          </div>
          {result.test_result && (
            <div className="text-text-dim">
              测试命令：{result.test_result.command}
              <br />
              exit：{result.test_result.exit_code ?? "None"}
              {result.test_result.timed_out && " · 已超时"}
            </div>
          )}
          {result.test_result?.stderr && (
            <pre className="max-h-32 overflow-auto whitespace-pre-wrap text-text-dim">
              {result.test_result.stderr}
            </pre>
          )}
          {result.diff_summary && (
            <div className="text-text-dim">
              <div className="font-medium text-text">变更摘要</div>
              <pre className="whitespace-pre-wrap">{result.diff_summary}</pre>
            </div>
          )}
          {result.fix_attempts > 0 && (
            <div className="text-text-dim">自动修复次数：{result.fix_attempts}</div>
          )}
          {result.error && <div className="text-red-400">{result.error}</div>}
        </div>
      )}
    </div>
  );
}
