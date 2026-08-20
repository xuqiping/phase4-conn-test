// 验收预览窗格（P06 S6 / FR-052 / AC-057）：
// 内嵌本地 dev server 预览 + 刷新 + 设备尺寸切换。
// 安全：只允许 localhost / 127.0.0.1 的 http(s)，防外网/本地文件被嵌入。
import { useMemo, useRef, useState } from "react";
import FixTaskDialog from "./FixTaskDialog";

/** 校验预览 URL：仅 localhost/127.0.0.1 的 http(s)（plan 安全清单）。 */
export function isAllowedPreviewUrl(raw: string): boolean {
  try {
    const u = new URL(raw);
    if (u.protocol !== "http:" && u.protocol !== "https:") return false;
    return u.hostname === "localhost" || u.hostname === "127.0.0.1";
  } catch {
    return false;
  }
}

const DEVICES = [
  { key: "mobile", label: "手机", width: 390 },
  { key: "tablet", label: "平板", width: 768 },
  { key: "desktop", label: "桌面", width: 0 },
] as const;

type DeviceKey = (typeof DEVICES)[number]["key"];

export default function PreviewPane({
  defaultUrl = "http://localhost:5173",
  projectId,
  acceptanceItemId,
}: {
  defaultUrl?: string;
  /** 传入即启用圈选修复（S7 联动） */
  projectId?: number;
  acceptanceItemId?: number;
}) {
  const [url, setUrl] = useState(defaultUrl);
  const [input, setInput] = useState(defaultUrl);
  const [device, setDevice] = useState<DeviceKey>("desktop");
  const [reloadKey, setReloadKey] = useState(0);
  const [error, setError] = useState<string | null>(null);
  const [picking, setPicking] = useState(false);
  const [fixDraft, setFixDraft] = useState<{ x: number; y: number } | null>(null);
  const [hint, setHint] = useState<string | null>(null);
  const frameWrapRef = useRef<HTMLDivElement>(null);

  const valid = useMemo(() => isAllowedPreviewUrl(url), [url]);
  const width = DEVICES.find((d) => d.key === device)?.width ?? 0;

  return (
    <div data-testid="preview-pane" className="flex h-full flex-col gap-2">
      <div className="flex items-center gap-2">
        <input
          data-testid="preview-url"
          className="flex-1 rounded-[9px] border border-border bg-transparent px-2 py-1 text-xs"
          value={input}
          onChange={(e) => setInput(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === "Enter") {
              if (isAllowedPreviewUrl(input)) {
                setUrl(input);
                setError(null);
              } else {
                setError("只允许本机地址（localhost / 127.0.0.1）");
              }
            }
          }}
          aria-label="预览地址"
        />
        <button
          type="button"
          data-testid="preview-reload"
          className="rounded-[9px] border border-border px-2 py-1 text-xs text-text-dim hover:bg-card"
          onClick={() => setReloadKey((k) => k + 1)}
        >
          刷新
        </button>
        {projectId != null && (
          <button
            type="button"
            data-testid="preview-pick"
            className={`rounded-[9px] border px-2 py-1 text-xs ${
              picking ? "border-primary text-primary" : "border-border text-text-dim hover:bg-card"
            }`}
            onClick={() => {
              setPicking((v) => !v);
              setHint(picking ? null : "已在预览上开启圈选：点击有问题的地方");
            }}
          >
            圈选
          </button>
        )}
        {DEVICES.map((d) => (
          <button
            key={d.key}
            type="button"
            data-testid={`preview-device-${d.key}`}
            className={`rounded-[9px] border px-2 py-1 text-xs ${
              device === d.key ? "border-primary text-primary" : "border-border text-text-dim hover:bg-card"
            }`}
            onClick={() => setDevice(d.key)}
          >
            {d.label}
          </button>
        ))}
      </div>

      {error && <p className="text-xs text-red-400">{error}</p>}
      {hint && <p className="text-xs text-yellow-400">{hint}</p>}

      {valid ? (
        <div
          ref={frameWrapRef}
          className="relative flex flex-1 items-start justify-center overflow-auto rounded bg-black/20 p-2"
        >
          <iframe
            key={reloadKey}
            data-testid="preview-frame"
            src={url}
            title="预览"
            className="h-full min-h-[420px] rounded border border-border bg-white"
            style={width > 0 ? { width: `${width}px` } : { width: "100%" }}
          />
          {picking && (
            <div
              data-testid="pick-overlay"
              className="absolute inset-0 cursor-crosshair"
              title="点击要圈选的元素位置"
              onClick={(e) => {
                const rect = frameWrapRef.current?.getBoundingClientRect();
                const x = rect ? e.clientX - rect.left : 0;
                const y = rect ? e.clientY - rect.top : 0;
                setPicking(false);
                setFixDraft({ x, y });
              }}
            />
          )}
        </div>
      ) : (
        <div className="flex flex-1 items-center justify-center rounded bg-black/20 p-4 text-center text-sm text-text-dim">
          预览地址不可用。请先在本机启动项目的开发服务器（如 npm run dev），再输入本机地址。
        </div>
      )}

      {fixDraft && projectId != null && (
        <FixTaskDialog
          draft={{ ...fixDraft, acceptanceItemId }}
          projectId={projectId}
          onClose={() => setFixDraft(null)}
          onCreated={(taskId) => {
            setFixDraft(null);
            setHint(`已创建修复任务 #${taskId}，可在建造视图查看`);
          }}
        />
      )}
    </div>
  );
}
