// Secrets 管理面板 UI（P03 Step7 FR-012/AC-014）。
// 支持增删改查；值输入框用 password 类型，列表中只显示名称。

import { useEffect, useState } from "react";
import { invoke } from "@tauri-apps/api/core";

interface SecretMetaVo {
  id: number;
  project_id: number;
  name: string;
}

interface Props {
  projectId: number;
}

export default function SecretsPanel({ projectId }: Props) {
  const [secrets, setSecrets] = useState<SecretMetaVo[]>([]);
  const [name, setName] = useState("");
  const [value, setValue] = useState("");
  const [editing, setEditing] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = async () => {
    setError(null);
    try {
      const rows = await invoke<SecretMetaVo[]>("list_secrets", { projectId });
      setSecrets(rows);
    } catch (e: unknown) {
      setError((e as Error)?.message ?? "读取失败");
    }
  };

  useEffect(() => {
    load();
  }, [projectId]);

  const save = async () => {
    if (!name.trim()) return;
    setError(null);
    try {
      await invoke("save_secret", {
        req: { project_id: projectId, name: name.trim(), value },
      });
      setName("");
      setValue("");
      setEditing(null);
      await load();
    } catch (e: unknown) {
      setError((e as Error)?.message ?? "保存失败");
    }
  };

  const startEdit = async (n: string) => {
    setEditing(n);
    setName(n);
    setValue("");
    try {
      const v = await invoke<string | null>("load_secret", { projectId, name: n });
      if (v) setValue(v);
    } catch {
      // 忽略读取失败，用户可重新输入
    }
  };

  const remove = async (n: string) => {
    if (!confirm(`确定删除 secret "${n}"？`)) return;
    setError(null);
    try {
      await invoke("delete_secret", { projectId, name: n });
      if (editing === n) {
        setEditing(null);
        setName("");
        setValue("");
      }
      await load();
    } catch (e: unknown) {
      setError((e as Error)?.message ?? "删除失败");
    }
  };

  return (
    <div className="panel space-y-3 rounded-[9px] p-4">
      <h3 className="text-sm font-semibold">Secrets 管理</h3>
      <p className="text-xs text-text-dim">
        以环境变量形式注入任务进程（DEVPILOT_SECRET_***），输出/日志自动脱敏为 ***。
      </p>

      <div className="flex gap-2">
        <input
          type="text"
          value={name}
          onChange={(e) => setName(e.target.value)}
          placeholder="名称"
          className="flex-1 rounded-md border border-border bg-card px-2 py-1 text-xs text-text"
        />
        <input
          type="password"
          value={value}
          onChange={(e) => setValue(e.target.value)}
          placeholder={editing ? "留空则保持原值" : "值"}
          className="flex-[2] rounded-md border border-border bg-card px-2 py-1 text-xs text-text"
        />
        <button
          type="button"
          onClick={save}
          className="rounded-md bg-brand px-3 py-1 text-xs font-medium text-white hover:bg-brand2"
        >
          {editing ? "更新" : "添加"}
        </button>
      </div>

      {error && <div className="text-xs text-red-400">{error}</div>}

      <ul className="space-y-1">
        {secrets.map((s) => (
          <li
            key={s.name}
            className="flex items-center justify-between rounded-md border border-border bg-card px-2 py-1 text-xs"
          >
            <span className="font-mono">{s.name}</span>
            <div className="flex gap-2">
              <button
                type="button"
                onClick={() => startEdit(s.name)}
                className="text-text-dim hover:text-text"
              >
                编辑
              </button>
              <button
                type="button"
                onClick={() => remove(s.name)}
                className="text-red-400 hover:text-red-300"
              >
                删除
              </button>
            </div>
          </li>
        ))}
      </ul>
    </div>
  );
}
