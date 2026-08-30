// 项目约定弹窗：加载/保存 AGENTS.md 大白话表单。
import { useCallback, useEffect, useState } from "react";
import { errMessage, ipc, type AgentConfigFields } from "../../lib/ipc";
import { useProjectStore } from "../../stores/project";
import AgentConfigForm from "./AgentConfigForm";

interface Props {
  onClose: () => void;
}

export default function AgentConfigModal({ onClose }: Props) {
  const projectId = useProjectStore((s) => s.currentId);
  const [fields, setFields] = useState<AgentConfigFields | undefined>();
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    if (projectId == null) return;
    try {
      const data = await ipc.loadAgentConfig(projectId);
      setFields(data);
    } catch (e) {
      setError(errMessage(e));
    }
  }, [projectId]);

  useEffect(() => {
    void load();
  }, [load]);

  const handleSave = async (data: AgentConfigFields) => {
    if (projectId == null) return;
    setLoading(true);
    setError(null);
    try {
      await ipc.saveAgentConfig(projectId, data);
      await load();
      onClose();
    } catch (e) {
      setError(errMessage(e));
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4">
      <div className="panel flex max-h-[85vh] w-full max-w-2xl flex-col rounded-[14px]">
        <div className="flex items-center justify-between border-b border-border px-6 py-4">
          <h2 className="text-base font-semibold text-text">项目约定（AGENTS.md）</h2>
          <button
            type="button"
            onClick={onClose}
            className="text-lg text-text-dim transition hover:text-text"
            aria-label="关闭"
          >
            ×
          </button>
        </div>
        <div className="overflow-y-auto px-6 py-4">
          {error && (
            <div className="mb-4 rounded-[9px] border border-coral/40 bg-coral/10 px-3 py-2 text-sm text-coral">
              {error}
            </div>
          )}
          {fields ? (
            <AgentConfigForm
              initial={fields}
              loading={loading}
              onSave={handleSave}
              onCancel={onClose}
            />
          ) : (
            <p className="text-sm text-text-dim">加载中…</p>
          )}
        </div>
      </div>
    </div>
  );
}
