// 项目创建向导：名称 + 规模四选 + 父目录（可空=默认 ~/DevPilotProjects）。
// 规模语义对齐联动点 2：L0/L1 由状态机跳过对应阶段节点。
import { useState } from "react";
import { useProjectStore } from "../../stores/project";

const SCALES = [
  { key: "L0", label: "小脚本", desc: "一次性小工具，想法直建" },
  { key: "L1", label: "小工具", desc: "本机使用，无部署阶段" },
  { key: "L2", label: "标准应用", desc: "完整六阶段流程（推荐）" },
  { key: "L3", label: "复杂系统", desc: "全流程 + 更严门禁" },
] as const;

export default function CreateProject() {
  const create = useProjectStore((s) => s.create);
  const closeWizard = useProjectStore((s) => s.closeWizard);
  const hasProjects = useProjectStore((s) => s.projects.length > 0);

  const [name, setName] = useState("");
  const [scale, setScale] = useState<string>("L2");
  const [parentDir, setParentDir] = useState("");
  const [submitting, setSubmitting] = useState(false);

  const submit = async () => {
    setSubmitting(true);
    await create(name, parentDir.trim() || null, scale);
    setSubmitting(false);
  };

  return (
    <div
      data-testid="create-project-wizard"
      className="fixed inset-0 z-50 grid place-items-center bg-bg/80 backdrop-blur-sm"
    >
      <div className="panel w-[520px] rounded-[14px] p-7">
        <h2 className="text-lg font-bold">新建项目</h2>
        <p className="mt-1 text-xs text-text-dim">
          说一句大白话的项目名即可，目录和工作流我来建。
        </p>

        <label className="mt-5 block text-xs text-text-dim">项目名</label>
        <input
          value={name}
          onChange={(e) => setName(e.target.value)}
          placeholder="例如：记账小助手"
          className="mt-1 w-full rounded-[9px] border border-border bg-bg/60 px-3 py-2 text-sm text-text outline-none focus:border-border-strong"
        />

        <p className="mt-4 text-xs text-text-dim">项目规模（决定走几个阶段）</p>
        <div className="mt-2 grid grid-cols-2 gap-[var(--space-gap)]">
          {SCALES.map((s) => (
            <button
              key={s.key}
              type="button"
              onClick={() => setScale(s.key)}
              aria-pressed={scale === s.key}
              className={`rounded-[9px] border p-3 text-left transition ${
                scale === s.key
                  ? "border-brand bg-card-hover glow-brand"
                  : "border-border bg-card hover:border-border-strong"
              }`}
            >
              <p className="text-sm font-semibold">
                <span className="mr-1.5 font-mono text-brand2">{s.key}</span>
                {s.label}
              </p>
              <p className="mt-0.5 text-xs text-text-dim">{s.desc}</p>
            </button>
          ))}
        </div>

        <label className="mt-4 block text-xs text-text-dim">
          放在哪个文件夹下（留空 = 默认目录）
        </label>
        <input
          value={parentDir}
          onChange={(e) => setParentDir(e.target.value)}
          placeholder="默认：用户目录/DevPilotProjects"
          className="mt-1 w-full rounded-[9px] border border-border bg-bg/60 px-3 py-2 text-sm text-text outline-none focus:border-border-strong"
        />

        <div className="mt-6 flex justify-end gap-3">
          {hasProjects && (
            <button
              type="button"
              onClick={closeWizard}
              className="rounded-[9px] px-4 py-2 text-sm text-text-dim hover:text-text"
            >
              取消
            </button>
          )}
          <button
            type="button"
            onClick={submit}
            disabled={submitting || !name.trim()}
            className="glow-brand rounded-[9px] bg-gradient-to-br from-brand to-brand2 px-5 py-2 text-sm font-semibold text-white disabled:opacity-40"
          >
            {submitting ? "创建中…" : "创建项目"}
          </button>
        </div>
      </div>
    </div>
  );
}
