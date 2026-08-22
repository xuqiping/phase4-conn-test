// 任务输入框（P07 S5 FR-025）：从 Build 视图抽取的可复用输入组件。
// '/' 弹技能候选 → 选中展开为 chip → 提交时 prompt = 技能正文 + 用户文本。

import { useEffect, useState } from "react";
import { ipc, type AttachmentDto, type SkillDto } from "../../lib/ipc";
import AttachmentChips, { validateImageFile } from "./AttachmentChips";
import SkillAutocomplete, { filterSkills, slashQuery } from "./SkillAutocomplete";
import VoiceDictation from "./VoiceDictation";

export interface TaskInputBoxProps {
  placeholder?: string;
  disabled?: boolean;
  busy?: boolean;
  busyLabel?: string;
  submitLabel?: string;
  /** 图片附件归属的项目（不传则不启用拖图） */
  projectId?: number;
  /** 提交：拿到最终 prompt（技能正文已拼好） */
  onSubmit: (prompt: string) => Promise<void>;
}

export default function TaskInputBox(props: TaskInputBoxProps) {
  const [text, setText] = useState("");
  const [skills, setSkills] = useState<SkillDto[]>([]);
  const [skill, setSkill] = useState<SkillDto | null>(null);
  const [activeIndex, setActiveIndex] = useState(0);
  const [open, setOpen] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [attachments, setAttachments] = useState<AttachmentDto[]>([]);
  const canAttach = props.projectId != null;

  /** 拖入/粘贴的图片先过本地校验（快速拒绝），再交给 AttachmentChips 存。 */
  const acceptDropped = (files: FileList | null) => {
    if (!canAttach || !files || files.length === 0) return;
    for (const f of Array.from(files)) {
      const bad = validateImageFile(f);
      if (typeof bad === "string") setError(bad);
      else void addFile(f);
    }
  };

  const addFile = async (f: File) => {
    if (props.projectId == null) return;
    setError(null);
    try {
      // jsdom 等环境 File.arrayBuffer 可能缺失：兜底空字节（真浏览器不受影响）
      const buf = await Promise.resolve(f.arrayBuffer?.()).catch(
        () => new ArrayBuffer(0),
      );
      const bytes = new Uint8Array(buf);
      const dto = await ipc.saveAttachment(props.projectId, bytes);
      setAttachments((prev) => [...prev, dto]);
    } catch (e) {
      setError((e as Error).message);
    }
  };

  useEffect(() => {
    ipc.listSkills().then(setSkills).catch(() => setSkills([]));
  }, []);

  const query = slashQuery(text);
  const candidates = query == null ? [] : filterSkills(skills, query);
  const showPopup = open && query != null && candidates.length > 0;

  const pick = (s: SkillDto) => {
    setSkill(s);
    setOpen(false);
    // 清掉 "/前缀"，留下干净输入框（正文提交时才拼接）
    setText("");
  };

  const onKeyDown = (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (showPopup) {
      if (e.key === "ArrowDown") {
        e.preventDefault();
        setActiveIndex((i) => Math.min(i + 1, candidates.length - 1));
        return;
      }
      if (e.key === "ArrowUp") {
        e.preventDefault();
        setActiveIndex((i) => Math.max(i - 1, 0));
        return;
      }
      if (e.key === "Escape") {
        setOpen(false);
        return;
      }
      if (e.key === "Enter" && !e.shiftKey) {
        e.preventDefault();
        pick(candidates[activeIndex] ?? candidates[0]);
        return;
      }
    }
    if (e.key === "Enter" && !e.shiftKey) {
      e.preventDefault();
      void submit();
    }
  };

  const submit = async () => {
    const userText = text.trim();
    if (!userText && !skill) return;
    setError(null);
    try {
      let prompt = userText;
      if (skill) {
        try {
          const body = await ipc.invokeSkill(skill.name);
          prompt = `${body}\n\n${userText}`;
        } catch (e) {
          setError((e as Error)?.message ?? "技能展开失败");
          return;
        }
      }
      await props.onSubmit(prompt);
      setText("");
      setSkill(null);
      setOpen(true);
    } catch (e: unknown) {
      setError((e as Error)?.message ?? "提交失败");
    }
  };

  return (
    <div className="relative space-y-2" data-testid="task-input-box">
      {canAttach && props.projectId != null && (
        <div className="flex items-center gap-2">
          <AttachmentChips
            projectId={props.projectId}
            attachments={attachments}
            onChange={setAttachments}
            onError={setError}
          />
          <VoiceDictation />
        </div>
      )}
      {skill && (
        <div
          data-testid="skill-chip"
          className="inline-flex items-center gap-2 rounded-full border border-brand/40 bg-brand/10 px-3 py-1 text-xs text-text"
        >
          <span className="font-mono text-brand">/{skill.name}</span>
          <span className="text-text-dim">{skill.description}</span>
          <button
            type="button"
            aria-label="移除技能"
            onClick={() => setSkill(null)}
            className="text-text-faint hover:text-text"
          >
            ×
          </button>
        </div>
      )}
      <div className="relative">
        <textarea
          data-testid="task-input-textarea"
          value={text}
          onChange={(e) => {
            setText(e.target.value);
            setOpen(true);
          }}
          onKeyDown={onKeyDown}
          onDrop={(e) => {
            acceptDropped(e.dataTransfer?.files ?? null);
          }}
          onPaste={(e) => {
            const files = e.clipboardData?.files ?? null;
            if (files && files.length > 0) {
              e.preventDefault();
              acceptDropped(files);
            }
          }}
          placeholder={props.placeholder ?? "例如：再帮我加个登录表单的白底版本（输入 / 调用技能）"}
          rows={3}
          disabled={props.disabled}
          className="w-full rounded-[9px] border border-border bg-card px-3 py-2 text-xs text-text placeholder:text-text-faint"
        />
        {showPopup && (
          <SkillAutocomplete
            value={text}
            skills={skills}
            activeIndex={activeIndex}
            onActiveIndexChange={setActiveIndex}
            onPick={pick}
          />
        )}
      </div>
      <button
        type="button"
        data-testid="task-input-submit"
        disabled={props.busy || (!text.trim() && !skill)}
        onClick={() => void submit()}
        className="w-full rounded-[9px] bg-brand px-3 py-2 text-sm font-medium text-white transition hover:bg-brand2 disabled:opacity-50"
      >
        {props.busy ? (props.busyLabel ?? "执行中…") : (props.submitLabel ?? "追加并续跑")}
      </button>
      {error && <div className="text-xs text-red-400">{error}</div>}
    </div>
  );
}
