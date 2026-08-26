/**
 * verify.test.ts —— 层2 截图比对单测（FR-102 / AC-102）
 * 用 pngjs 构造三组 PNG：完全相同 / 大块变化 / 微小噪点（阈值以下）。
 */
import { describe, it, expect } from "vitest";
import { PNG } from "pngjs";
import { changed } from "../src/driver/win/verify.js";

function makePng(mutate?: (png: PNG) => void): string {
  const png = new PNG({ width: 100, height: 100 });
  for (let i = 0; i < png.data.length; i += 4) {
    png.data[i] = 200; png.data[i + 1] = 200; png.data[i + 2] = 200; png.data[i + 3] = 255;
  }
  mutate?.(png);
  return PNG.sync.write(png).toString("base64");
}

describe("changed 比对（FR-102）", () => {
  it("AC-102 完全相同 → verified=false（点击无效果）", () => {
    const a = makePng();
    expect(changed(a, makePng()).verified).toBe(false);
  });

  it("AC-102 大块变化（10% 像素）→ verified=true", () => {
    const a = makePng();
    const b = makePng((p) => {
      for (let y = 0; y < 10; y++) for (let x = 0; x < 100; x++) {
        const i = (100 * y + x) * 4;
        p.data[i] = 0; p.data[i + 1] = 0; p.data[i + 2] = 255;
      }
    });
    const r = changed(a, b);
    expect(r.verified).toBe(true);
    expect(r.changedRatio).toBeGreaterThanOrEqual(0.1);
  });

  it("微小噪点（<阈值 且 <50像素）→ verified=false（抗光标闪烁）", () => {
    const a = makePng();
    const b = makePng((p) => {
      for (let k = 0; k < 30; k++) p.data[k * 4] = 0; // 30 个像素小闪变
    });
    expect(changed(a, b).verified).toBe(false);
  });

  it("尺寸不一致 → verified=true（界面切换/弹窗）", () => {
    const a = makePng();
    const b = PNG.sync.write(new PNG({ width: 50, height: 100 })).toString("base64");
    expect(changed(a, b).verified).toBe(true);
  });

  it("低于通道阈值的亮度微调不算变化（>24 灰阶才计）", () => {
    const a = makePng();
    const b = makePng((p) => {
      for (let i = 0; i < p.data.length; i += 4) p.data[i] = 210; // +10 灰阶
    });
    expect(changed(a, b).verified).toBe(false);
  });
});
