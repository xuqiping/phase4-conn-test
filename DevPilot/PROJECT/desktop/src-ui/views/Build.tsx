// Build 视图：当前任务流、事件日志、追加续跑、存档点时间轴（P05 S7）。

import { useEffect, useState } from "react";
import CheckpointTimeline from "../components/checkpoint/CheckpointTimeline";
import PlainText from "../components/plain/PlainText";
import { cloudBase, getAccessToken } from "../lib/cloudApi";
import { ipc, type TaskDto, type TaskEventDto } from "../lib/ipc";
import { onTaskEvent } from "../lib/ipc";
import { useProjectStore } from "../stores/project";
import { useUiStore } from "../stores/ui";

export default function Build() {
  const projectId = useProjectStore((s) => s.currentId);
  const snapshot = useProjectStore((s) => s.snapshot);
  const enterAcceptance = useProjectStore((s) => s.enterAcceptance);
  const plainMode = useUiStore((s) => s.plainMode);
  const togglePlainMode = useUiStore((s) => s.togglePlainMode);

  const [tasks, setTasks] = useState<TaskDto[]>([]);
  const [selectedTaskId, setSelectedTaskId] = useState<number | null>(null);
  const [events, setEvents] = useState<TaskEventDto[]>([]);
  const [instructions, setInstructions] = useState("");
  const [continuing, setContinuing] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const loadTasks = async () => {
    if (projectId == null) return;
    const rows = await ipc.listTasks(projectId);
    setTasks(rows);
    if (selectedTaskId == null && rows.length > 0) {
      setSelectedTaskId(rows[0].id);
    }
  };

  useEffect(() => {
    void loadTasks();
  }, [projectId, snapshot?.phase]);

  useEffect(() => {
    if (selectedTaskId == null) {
      setEvents([]);
      return;
    }
    ipc.listTaskEvents(selectedTaskId).then(setEvents).catch(() => setEvents([]));
  }, [selectedTaskId]);

  useEffect(() => {
    let cancel = () => {};
    onTaskEvent((ev) => {
      if (ev.task_id === selectedTaskId) {
        setEvents((prev) => [...prev, ev]);
      }
      if (ev.event_type === "checkpoint") {
        void loadTasks();
      }
    }).then((unsub) => {
      cancel = unsub;
    });
    return () => cancel();
  }, [selectedTaskId]);

  const continueTask = async () => {
    if (!projectId || !instructions.trim()) return;
    setContinuing(true);
    setError(null);
    try {
      await ipc.continueTask(
        projectId,
        instructions.trim(),
        getAccessToken() ?? "",
        cloudBase(),
      );
      setInstructions("");
    } catch (e: unknown) {
      setError((e as Error)?.message ?? "追加指令执行失败");
    } finally {
      setContinuing(false);
    }
  };

  const allDone = tasks.length > 0 && tasks.every((t) => t.status === "done");

  return (
    <section
      data-testid="view-build"
      className="flex flex-1 flex-col gap-[var(--space-gap)] overflow-y-auto pr-1"
    >
      <div className="panel flex flex-col items-center justify-center gap-4 rounded-[14px] p-6">
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

        {allDone && (
          <button
            type="button"
            onClick={() => void enterAcceptance()}
            className="rounded-[9px] bg-gradient-to-br from-brand to-brand2 px-4 py-2 text-sm font-semibold text-white transition hover:opacity-90"
          >
            进入验收
          </button>
        )}
      </div>

      <div className="grid grid-cols-2 gap-[var(--space-gap)]">
        <div className="panel space-y-2 rounded-[9px] p-4">
          <h3 className="text-sm font-semibold">当前任务流</h3>
          <div className="max-h-60 space-y-1 overflow-y-auto">
            {tasks.map((t) => (
              <button
                key={t.id}
                type="button"
                onClick={() => setSelectedTaskId(t.id)}
                className={`flex w-full items-center gap-2 rounded-[6px] px-2 py-1.5 text-left text-xs ${
                  selectedTaskId === t.id ? "bg-card" : "hover:bg-card/50"
                }`}
              >
                <span className="font-mono text-[10px] text-text-faint"
                >
                  #{String(t.chunk_no).padStart(3, "0")}
                </span>
                <span className="flex-1 truncate">{t.title}</span>
                <span
                  className={
                    t.status === "done"
                      ? "text-success"
                      : t.status === "failed"
                        ? "text-coral"
                        : t.status === "running"
                          ? "text-brand2"
                          : "text-text-faint"
                  }
                >
                  {t.status}
                </span>
              </button>
            ))}
            {tasks.length === 0 && (
              <div className="text-xs text-text-dim">暂无任务。</div>
            )}
          </div>
        </div>

        <div className="panel space-y-2 rounded-[9px] p-4">
          <h3 className="text-sm font-semibold">事件日志</h3>
          <div className="max-h-60 space-y-1 overflow-y-auto text-xs">
            {events.map((ev) => (
              <div
                key={`${ev.id ?? ev.created_at}-${ev.message.slice(0, 10)}`}
                className={`rounded-[4px] px-2 py-1 ${
                  ev.event_type === "error"
                    ? "bg-coral/10 text-coral"
                    : ev.event_type === "checkpoint"
                      ? "bg-success/10 text-success"
                      : "text-text-dim"
                }`}
              >
                <span className="mr-1 text-[10px] text-text-faint"
                >
                  [{ev.event_type}]
                </span>
                {ev.message}
              </div>
            ))}
            {events.length === 0 && (
              <div className="text-xs text-text-dim">选中任务暂无日志。</div>
            )}
          </div>
        </div>
      </div>

      <div className="panel space-y-3 rounded-[9px] p-4">
        <h3 className="text-sm font-semibold">追加指令续跑</h3>
        <textarea
          value={instructions}
          onChange={(e) => setInstructions(e.target.value)}
          placeholder="例如：再帮我加个登录表单的白底版本"
          rows={3}
          className="w-full rounded-[9px] border border-border bg-card px-3 py-2 text-xs text-text placeholder:text-text-faint"
        />
        <button
          type="button"
          disabled={continuing || !instructions.trim()}
          onClick={() => void continueTask()}
          className="w-full rounded-[9px] bg-brand px-3 py-2 text-sm font-medium text-white transition hover:bg-brand2 disabled:opacity-50"
        >
          {continuing ? "执行中…" : "追加并续跑"}
        </button>
        {error && <div className="text-xs text-red-400">{error}</div>}
      </div>

      {projectId != null && <CheckpointTimeline projectId={projectId} />}
    </section>
  );
}
