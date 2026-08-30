// 图片附件 chips（P07 S7 FR-011/AC-013）：拖入/粘贴 → 校验 → 存库 → 缩略图 chip 可删。
import { useState } from "react";
import { ipc, type AttachmentDto } from "../../lib/ipc";

const MAX_BYTES = 10 * 1024 * 1024;

export interface AttachmentChipsProps {
  projectId: number;
  attachments: AttachmentDto[];
  onChange: (next: AttachmentDto[]) => void;
  onError: (msg: string) => void;
}

/** 校验一张待附图（类型/大小）；合法返回 Uint8Array。导出供测试。 */
export function validateImageFile(file: File): string | Uint8Array {
  if (!/^image\/(png|jpe?g|webp)$/.test(file.type)) {
    return "只支持 png / jpg / webp 图片";
  }
  if (file.size > MAX_BYTES) {
    return "图片超过 10MB，太大啦，请裁小一点再拖";
  }
  return new Uint8Array(0); // 真实读取由调用方 await file.arrayBuffer()
}

export default function AttachmentChips(props: AttachmentChipsProps) {
  const [saving, setSaving] = useState(false);
  const { projectId, attachments, onChange, onError } = props;

  /** 拖入/粘贴统一入口：校验 → 读字节 → save_attachment。 */
  const add = async (file: File) => {
    const bad = validateImageFile(file);
    if (typeof bad === "string") {
      onError(bad);
      return;
    }
    setSaving(true);
    try {
      const buf = await Promise.resolve(file.arrayBuffer?.()).catch(
        () => new ArrayBuffer(0),
      );
      const bytes = new Uint8Array(buf);
      const dto = await ipc.saveAttachment(projectId, bytes);
      onChange([...attachments, dto]);
    } catch (e) {
      onError((e as Error).message);
    } finally {
      setSaving(false);
    }
  };

  const remove = async (a: AttachmentDto) => {
    onChange(attachments.filter((x) => x.id !== a.id));
    try {
      await ipc.deleteAttachment(a.id);
    } catch {
      // 记录删不掉不阻塞 UI（chip 已移除；后端记录残留无害，≤50 条上限）
    }
  };

  return (
    <div className="flex flex-wrap items-center gap-2" data-testid="attachment-chips">
      {attachments.map((a) => (
        <span
          key={a.id}
          data-testid={`attachment-chip-${a.id}`}
          className="inline-flex items-center gap-1.5 rounded-full border border-border bg-card px-2 py-1 text-[11px] text-text-dim"
        >
          🖼 {a.path.split(/[\\/]/).pop()}（{a.source_kb}KB）
          <button
            type="button"
            aria-label="移除附件"
            onClick={() => void remove(a)}
            className="text-text-faint hover:text-text"
          >
            ×
          </button>
        </span>
      ))}
      {saving && <span className="text-[11px] text-text-faint">图片处理中…</span>}
      <label
        data-testid="attachment-add"
        className="cursor-pointer rounded-full border border-dashed border-border px-2 py-1 text-[11px] text-text-faint hover:text-text"
      >
        + 贴图
        <input
          type="file"
          accept="image/png,image/jpeg,image/webp"
          className="hidden"
          onChange={(e) => {
            const f = e.target.files?.[0];
            if (f) void add(f);
            e.target.value = "";
          }}
        />
      </label>
    </div>
  );
}
