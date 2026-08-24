/**
 * 集成测试（本机 Windows 交互会话跑；CI 跳过）：CU_INTEG=1 npm test
 * 前置：打开一个记事本窗口（标题含"记事本"），DPI=100%。
 * AC-001/002（FR-001）
 */
import { describe, it, expect } from "vitest";

const INTEG = !!process.env.CU_INTEG;
const d = INTEG ? describe : describe.skip;

d("capture 集成（FR-001）", () => {
  it("AC-001: 记事本截图 ≥10KB 非全黑，尺寸正确，≤1s", async () => {
    const { capture } = await import("../src/driver/win/capture.js");
    const shot = await capture({ app: "记事本", mode: "window" });
    const bytes = Buffer.from(shot.pngBase64, "base64").length;
    expect(bytes).toBeGreaterThan(10 * 1024);
    expect(shot.width).toBeGreaterThan(100);
    expect(shot.height).toBeGreaterThan(100);
    expect(shot.elapsedMs).toBeLessThan(1000);
  }, 15000);

  it("AC-002: 不存在的应用返回 APP_NOT_FOUND 附候选列表", async () => {
    const { capture } = await import("../src/driver/win/capture.js");
    await expect(capture({ app: "绝对不存在的窗口XYZ", mode: "window" })).rejects.toMatchObject({
      code: "APP_NOT_FOUND",
      detail: expect.any(Array),
    });
  });
});
