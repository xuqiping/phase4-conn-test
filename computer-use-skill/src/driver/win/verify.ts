/**
 * driver/win/verify.ts —— 层2 效果验证（FR-102，ADR-003）
 * PostMessage 执行前后各一张 PrintWindow 截图，像素级比对判 verified。
 * 坑点预案：光标闪烁/时钟像素会造成微小差异——用变化比例阈值（0.2% 起步，真机校准）。
 */
import { PNG } from "pngjs";

const CHANGE_RATIO_THRESHOLD = 0.002; // 变化像素占比 > 0.2% 视为"界面真的动了"
const MAX_DIFF_PIXELS_FLOOR = 50; // 小窗口兜底：少于 50 个差异像素也视为没变（抗噪点）

export interface VerifyResult {
  verified: boolean;
  changedRatio: number;
  changedPixels: number;
}

/** 两张 PNG base64 比对（尺寸不一致直接算有变化——界面切换/弹窗） */
export function changed(beforeB64: string, afterB64: string): VerifyResult {
  const before = PNG.sync.read(Buffer.from(beforeB64, "base64"));
  const after = PNG.sync.read(Buffer.from(afterB64, "base64"));
  if (before.width !== after.width || before.height !== after.height) {
    return { verified: true, changedRatio: 1, changedPixels: -1 };
  }
  let diff = 0;
  const total = before.width * before.height;
  const a = before.data;
  const b = after.data;
  for (let i = 0; i < a.length; i += 4) {
    if (Math.abs(a[i] - b[i]) > 24 || Math.abs(a[i + 1] - b[i + 1]) > 24 || Math.abs(a[i + 2] - b[i + 2]) > 24) diff++;
  }
  const ratio = diff / total;
  const changed_ = diff > MAX_DIFF_PIXELS_FLOOR && ratio > CHANGE_RATIO_THRESHOLD;
  return { verified: changed_, changedRatio: Math.round(ratio * 10000) / 10000, changedPixels: diff };
}
