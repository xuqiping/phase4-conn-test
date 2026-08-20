// 圈选后的问题确认弹窗（P06 S7 / FR-033 / AC-037）：
// 展示圈选信息，让用户补一句大白话描述，确认后创建 source='fix' 修复任务。
import { useState } from "react";
import { errMessage, ipc } from "../../lib/ipc";

export interface FixDraft {
  /** 圈选相对坐标（预览容器内像素） */
  x: number;
  y: number;
  /** 可选：已关联的验收项 */
  acceptanceItemId?: number;
}

export default function FixTaskDialog({
  draft,
  projectId,
  onClose,
  onCreated,
}: {
  draft: FixDraft;
  projectId: number;
  onClose: () => void;
  onCreated: (taskId: number) => void;
}) {
  const [selector, setSelector] = useState("");
  const [description, setDescription] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const submit = async () => {
    if (busy) return;
    setBusy(true);
    setError(null);
    try {
      // 圈选兜底：iframe 跨域读不到 DOM，selector 以用户输入为准（plan 备注降级方案）。
      const sel = selector.trim() || `坐标(${Math.round(draft.x)},${Math.round(draft.y)})`;
      const id = await ipc.createFixTask({
        projectId,
        acceptanceItemId: draft.acceptanceItemId,
        selector: sel,
        description: description.trim(),
      });
      onCreated(id);
    } catch (e) {
      setError(errMessage(e));
    } finally {
      setBusy(false);
    }
  };

  const canSubmit = description.trim().length > 0 && !busy;

  return (
    <div
      data-testid="fix-task-dialog"
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4"
      onClick={onClose}
    >
      <div
        className="w-full max-w-md rounded-[14px] border border-border bg-card p-4"
        onClick={(e) => e.stopPropagation()}
      >
        <h3 className="text-base font-semibold">生成修复任务</h3>
        <p className="mt-1 text-xs text-text-dim">
          已圈选位置：预览内 ({Math.round(draft.x)}, {Math.round(draft.y)})
        </p>

        <label className="mt-3 block text-xs text-text-dim">
          元素定位（可选，优先 data-testid）
          <input
            data-testid="fix-selector"
            className="mt-1 w-full rounded-[9px] border border-border bg-transparent px-2 py-1 text-sm"
            placeholder='如 data-testid="login-btn"'
            maxLength={200}
            value={selector}
            onChange={(e) => setSelector(e.target.value)}
          />
        </label>

        <label className="mt-3 block text-xs text-text-dim">
          这里有什么问题（必填，用一句大白话）
          <textarea
            data-testid="fix-description"
            className="mt-1 h-20 w-full rounded-[9px] border border-border bg-transparent px-2 py-1 text-sm"
            maxLength={1000}
            value={description}
            onChange={(e) => setDescription(e.target.value)}
          />
        </label>

        {error && <p className="mt-2 text-xs text-red-400">{error}</p>}

        <div className="mt-4 flex justify-end gap-2">
          <button
            type="button"
            data-testid="fix-cancel"
            className="rounded-[9px] border border-border px-3 py-1.5 text-xs text-text-dim hover:bg-black/20"
            onClick={onClose}
          >
            取消
          </button>
          <button
            type="button"
            data-testid="fix-confirm"
            className="rounded-[9px] border border-primary px-3 py-1.5 text-xs text-primary disabled:opacity-50"
            disabled={!canSubmit}
            onClick={submit}
          >
            {busy ? "创建中…" : "创建修复任务"}
          </button>
        </div>
      </div>
    </div>
  );
}
