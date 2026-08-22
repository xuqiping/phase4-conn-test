// 语音听写按钮（P07 S7 FR-011/AC-013）：能力探测不可用 → 整体隐藏（降级不阻塞输入）。
// MVP：voice_probe 返回 false（Windows SAPI/WinRT 听写排队二期），组件不渲染任何东西。
import { useEffect, useState } from "react";
import { ipc } from "../../lib/ipc";

export default function VoiceDictation() {
  const [available, setAvailable] = useState(false);

  useEffect(() => {
    ipc.voiceProbe().then(setAvailable).catch(() => setAvailable(false));
  }, []);

  if (!available) return null; // 探测失败/未接入：隐藏，不占位不报错

  // 后续接入真听写时：按住空格录音 → voice_transcribe → 插入光标处
  return (
    <button
      type="button"
      data-testid="voice-dictation"
      className="rounded-full border border-border px-2 py-1 text-[11px] text-text-dim"
    >
      🎙 按住空格说话
    </button>
  );
}
