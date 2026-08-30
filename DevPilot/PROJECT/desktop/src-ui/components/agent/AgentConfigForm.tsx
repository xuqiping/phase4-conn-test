// AGENTS.md 大白话表单：零代码用户维护项目约定，系统自动重写 AGENTS.md。
import { useEffect, useState } from "react";
import type { AgentConfigFields } from "../../lib/ipc";

const FIELD_META: { key: keyof AgentConfigFields; label: string; rows: number }[] = [
  { key: "positioning", label: "项目一句话定位", rows: 2 },
  { key: "target_users", label: "目标用户画像", rows: 2 },
  { key: "tech_stack", label: "技术栈偏好", rows: 2 },
  { key: "naming_style", label: "命名与代码风格", rows: 2 },
  { key: "commit_style", label: "提交规范", rows: 2 },
  { key: "security_redlines", label: "安全红线", rows: 3 },
  { key: "doc_requirements", label: "文档/注释要求", rows: 2 },
  { key: "testing_redlines", label: "测试红线", rows: 2 },
];

interface Props {
  initial?: AgentConfigFields;
  loading?: boolean;
  onSave: (fields: AgentConfigFields) => void;
  onCancel: () => void;
}

export default function AgentConfigForm({ initial, loading, onSave, onCancel }: Props) {
  const [fields, setFields] = useState<AgentConfigFields>(() =>
    initial
      ? { ...initial }
      : {
          positioning: "",
          target_users: "",
          tech_stack: "",
          commit_style: "",
          security_redlines: "",
          doc_requirements: "",
          testing_redlines: "",
          naming_style: "",
        }
  );

  useEffect(() => {
    if (initial) setFields({ ...initial });
  }, [initial]);

  const update = (key: keyof AgentConfigFields, value: string) => {
    setFields((prev: AgentConfigFields) => ({ ...prev, [key]: value }));
  };

  return (
    <form
      className="flex flex-col gap-4"
      onSubmit={(e) => {
        e.preventDefault();
        onSave(fields);
      }}
    >
      <p className="text-xs text-text-dim">
        修改后，客户端会自动重写项目根目录的 AGENTS.md，无需手动编辑文件。
      </p>
      {FIELD_META.map((f) => (
        <label key={String(f.key)} className="flex flex-col gap-1 text-sm">
          <span className="font-medium text-text">{f.label}</span>
          <textarea
            value={fields[f.key]}
            onChange={(e) => update(f.key, e.target.value)}
            rows={f.rows}
            className="rounded-[9px] border border-border bg-card px-3 py-2 text-text outline-none transition focus:border-brand"
            disabled={loading}
          />
        </label>
      ))}
      <div className="flex justify-end gap-2 pt-2">
        <button
          type="button"
          onClick={onCancel}
          disabled={loading}
          className="rounded-[9px] border border-border px-4 py-2 text-sm text-text-dim transition hover:bg-card-hover hover:text-text disabled:opacity-50"
        >
          取消
        </button>
        <button
          type="submit"
          disabled={loading}
          className="rounded-[9px] bg-gradient-to-br from-brand to-brand2 px-4 py-2 text-sm font-semibold text-white transition hover:opacity-90 disabled:opacity-50"
        >
          {loading ? "保存中…" : "保存并生成 AGENTS.md"}
        </button>
      </div>
    </form>
  );
}
