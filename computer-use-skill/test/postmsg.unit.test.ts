/**
 * postmsg.unit.test.ts —— 层2 PostMessage 消息序列单测（FR-100/101，mock FFI）
 */
import { describe, it, expect, vi } from "vitest";
import { __injectForTest, mkLParam, postClick, postScroll, postType, WM } from "../src/driver/win/postmsg.js";

function recorder() {
  const calls: { msg: number; w: number | bigint; l: number | bigint }[] = [];
  __injectForTest({
    post: (_h, msg, w, l) => {
      calls.push({ msg, w: Number(w), l: Number(l) });
      return true;
    },
    toScreen: (_h, x, y) => ({ x: x + 100, y: y + 200 }), // 模拟客户区→屏幕偏移
  });
  return calls;
}

describe("mkLParam 坐标编码", () => {
  it("低16位x 高16位y", () => {
    expect(mkLParam(30, 50)).toBe((50 << 16) | 30);
    expect(mkLParam(877, 100)).toBe((100 << 16) | 877);
  });
});

describe("postClick 消息序列（FR-100）", () => {
  it("单击：MOVE→DOWN(w=MK_LBUTTON)→UP，lParam 为客户区坐标", () => {
    const calls = recorder();
    postClick("hwnd", 30, 50);
    expect(calls.map((c) => c.msg)).toEqual([WM.MOUSEMOVE, WM.LBUTTONDOWN, WM.LBUTTONUP]);
    expect(calls[1].w).toBe(0x0001); // MK_LBUTTON
    expect(calls[1].l).toBe(mkLParam(30, 50));
  });

  it("双击 = 4 条 DOWN/UP；右键用 RBUTTON 消息", () => {
    const calls = recorder();
    postClick("hwnd", 1, 2, { count: 2 });
    expect(calls.filter((c) => c.msg === WM.LBUTTONDOWN)).toHaveLength(2);
    postClick("hwnd", 1, 2, { button: "right" });
    expect(calls.some((c) => c.msg === WM.RBUTTONDOWN)).toBe(true);
  });

  it("via 标注 postmessage（AC 降级链可辨层）", () => {
    recorder();
    expect(postClick("h", 0, 0).via).toBe("postmessage");
  });
});

describe("postType 键入（FR-101）", () => {
  it("纯 ASCII：逐字符 WM_CHAR 码点", () => {
    const calls = recorder();
    postType("h", "ab1");
    expect(calls.map((c) => c.msg)).toEqual([WM.CHAR, WM.CHAR, WM.CHAR]);
    expect(calls.map((c) => c.w)).toEqual([97, 98, 49]);
  });

  it("中文且提供剪贴板通道：Ctrl+V 消息序列", () => {
    const calls = recorder();
    const clip = vi.fn();
    postType("h", "你好", (t) => clip(t));
    expect(clip).toHaveBeenCalledWith("你好");
    expect(calls.map((c) => c.msg)).toEqual([WM.KEYDOWN, WM.CHAR, WM.KEYUP]);
    expect(calls[0].w).toBe(0x11); // VK_CONTROL
    expect(calls[1].w).toBe(0x56); // 'V'
  });

  it("中文且无剪贴板通道：退化为 UTF-16 码点逐发", () => {
    const calls = recorder();
    postType("h", "你");
    expect(calls[0].msg).toBe(WM.CHAR);
    expect(calls[0].w).toBe("你".codePointAt(0));
  });
});

describe("postScroll 滚轮（坑点：lParam 用屏幕坐标）", () => {
  it("客户区(30,50) → 屏幕(130,250) 编入 lParam；delta 在 wParam 高16位", () => {
    const calls = recorder();
    postScroll("h", 30, 50, "down", 2);
    expect(calls).toHaveLength(1);
    expect(calls[0].msg).toBe(WM.MOUSEWHEEL);
    expect(calls[0].l).toBe(mkLParam(130, 250)); // 屏幕坐标而非 30,50
    expect((calls[0].w >> 16) & 0xffff).toBe(((-240) & 0xffff)); // down = -120×2
  });
});
