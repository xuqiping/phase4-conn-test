/**
 * driver/win/postmsg.ts —— 层2 PostMessage 后台执行（FR-100/101，ADR-003）
 * 向目标 hwnd 直接投递 Windows 消息，不激活窗口、不抢前台焦点。
 * 已知局限（plan 坑点预案）：Chromium/WebView2 大概率忽略消息——由 verify.ts 截图验证 + 层3 降级兜底。
 * 安全：参数全为整数消息号/坐标/字符码，无字符串拼 shell 面（security_strategy §1）。
 */
import { ClientToScreen, PostMessageW } from "./ffi.js";
import type { ActionResult } from "../types.js";

// 消息号（WinUser.h 常用子集）
const WM = {
  MOUSEMOVE: 0x0200,
  LBUTTONDOWN: 0x0201,
  LBUTTONUP: 0x0202,
  LBUTTONDBLCLK: 0x0203,
  RBUTTONDOWN: 0x0204,
  RBUTTONUP: 0x0205,
  MOUSEWHEEL: 0x020a,
  KEYDOWN: 0x0100,
  KEYUP: 0x0101,
  CHAR: 0x0102,
  SETTEXT: 0x000c,
} as const;
const MK_LBUTTON = 0x0001;
const MK_RBUTTON = 0x0002;
const WHEEL_DELTA = 120;
const VK_CONTROL = 0x11;
const VK_V = 0x56;

/** x,y → lParam（低16位x 高16位y，客户区坐标编码） */
export function mkLParam(x: number, y: number): number {
  return ((y & 0xffff) << 16) | (x & 0xffff);
}

type PostFn = (hwnd: unknown, msg: number, wparam: bigint | number, lparam: bigint | number) => unknown;
// 可注入的发送函数（单测 mock 用；默认真实 FFI）
let post: PostFn = (h, m, w, l) => PostMessageW(h as never, m, w as never, l as never);
let toScreen: (hwnd: unknown, x: number, y: number) => { x: number; y: number } = (hwnd, x, y) => {
  const pt = { x, y };
  ClientToScreen(hwnd as never, pt as never);
  return pt;
};

/** 单测注入口（mock FFI 断言消息序列） */
export function __injectForTest(fns: { post?: PostFn; toScreen?: typeof toScreen }): void {
  if (fns.post) post = fns.post;
  if (fns.toScreen) toScreen = fns.toScreen;
}

/** 后台点击（客户区坐标，FR-100）。双击 = DOWN/UP 两次（WM_LBUTTONDBLCLK 需窗口自行合成，不可靠） */
export function postClick(
  hwnd: unknown,
  cx: number,
  cy: number,
  opts: { button?: "left" | "right"; count?: number } = {}
): ActionResult {
  const { button = "left", count = 1 } = opts;
  const lp = mkLParam(cx, cy);
  const down = button === "left" ? WM.LBUTTONDOWN : WM.RBUTTONDOWN;
  const up = button === "left" ? WM.LBUTTONUP : WM.RBUTTONUP;
  const mk = button === "left" ? MK_LBUTTON : MK_RBUTTON;
  post(hwnd, WM.MOUSEMOVE, 0, lp);
  for (let i = 0; i < count; i++) {
    post(hwnd, down, mk, lp);
    post(hwnd, up, 0, lp);
  }
  return { via: "postmessage", detail: `postmsg click ${button}x${count} @${cx},${cy}` };
}

/** 后台键入（FR-101）：ASCII 走 WM_CHAR；中文先试 WM_SETTEXT（仅 Edit 控件，防清空其他窗口文本），失败退 Ctrl+V 消息 */
export function postType(hwnd: unknown, text: string, clipPaste?: (text: string) => void): ActionResult {
  const isAscii = [...text].every((ch) => ch.charCodeAt(0) < 0x80);
  if (isAscii) {
    for (const ch of text) post(hwnd, WM.CHAR, ch.charCodeAt(0), 0);
    return { via: "postmessage", detail: `postmsg WM_CHAR x${text.length}` };
  }
  // 非纯 ASCII（中文等）：WM_SETTEXT 需构造原生字符串指针（不稳，弃用）；走剪贴板 + Ctrl+V 消息
  if (clipPaste) {
    clipPaste(text); // 调用方负责把文本放入剪贴板（复用 v1 Base64 通道）
    post(hwnd, WM.KEYDOWN, VK_CONTROL, 0);
    post(hwnd, WM.CHAR, VK_V, 0); // Ctrl+V 经 WM_CHAR 发 'V'（带 Ctrl 状态由 KEYDOWN 声明）
    post(hwnd, WM.KEYUP, VK_CONTROL, 0);
    return { via: "postmessage", detail: "postmsg clipboard+Ctrl+V messages" };
  }
  // 无剪贴板通道可用时逐字符发 UTF-16 码点（部分控件接受）
  for (const ch of text) post(hwnd, WM.CHAR, ch.codePointAt(0) ?? 0, 0);
  return { via: "postmessage", detail: `postmsg WM_CHAR(utf16) x${text.length}` };
}

/** 后台滚动（FR 由层2 通用能力覆盖）：注意 WM_MOUSEWHEEL 的 lParam 是屏幕坐标（与点击相反，坑点预案） */
export function postScroll(hwnd: unknown, cx: number, cy: number, dir: "up" | "down", pages = 1): ActionResult {
  const pt = toScreen(hwnd, cx, cy);
  const lp = mkLParam(pt.x, pt.y);
  const delta = (dir === "up" ? WHEEL_DELTA : -WHEEL_DELTA) * pages;
  post(hwnd, WM.MOUSEWHEEL, (delta & 0xffff) << 16, lp);
  return { via: "postmessage", detail: `postmsg wheel ${dir}x${pages}` };
}

export { WM };
