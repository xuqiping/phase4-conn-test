/**
 * driver/win/input.ts —— SendInput 前台执行层（FR-004~011，ADR-3 层2）
 * legacy mouse_event/keybd_event 注入；结果统一 via:"sendinput"（明示前台接管）。
 */
import type { ActionResult } from "../types.js";
import { DriverError } from "../types.js";
import { GetCursorPos, keybd_event, mouse_event, SetCursorPos, SetForegroundWindow } from "./ffi.js";
import { parseCombo } from "./keymap.js";
import { findWindow } from "./window.js";

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

const ok = (ms: number): ActionResult => ({ ok: true, via: "sendinput", elapsedMs: ms });

/** mouse_event flags */
const LEFT_DOWN = 0x0002, LEFT_UP = 0x0004, RIGHT_DOWN = 0x0008, RIGHT_UP = 0x0010;
const WHEEL = 0x0800, HWHEEL = 0x1000;

/** 前台激活目标窗口（坑点预案：前台锁用最小化唤醒兜底） */
export function activate(app: string): void {
  const win = findWindow(app);
  SetForegroundWindow(win.hwnd as never);
}

/** FR-004/005/010：坐标点击/双击/移动 */
export async function clickAt(x: number, y: number, opts: { button?: "left" | "right"; count?: number } = {}): Promise<ActionResult> {
  const t0 = Date.now();
  if (!Number.isFinite(x) || !Number.isFinite(y)) throw new DriverError("INVALID_ARGUMENT", `坐标非法 ${x},${y}`);
  const count = opts.count ?? 1;
  const right = opts.button === "right";
  SetCursorPos(Math.round(x), Math.round(y));
  for (let i = 0; i < count; i++) {
    mouse_event(right ? RIGHT_DOWN : LEFT_DOWN, 0, 0, 0, null);
    mouse_event(right ? RIGHT_UP : LEFT_UP, 0, 0, 0, null);
    if (count > 1) await sleep(30);
  }
  return ok(Date.now() - t0);
}

export async function moveTo(x: number, y: number): Promise<ActionResult> {
  const t0 = Date.now();
  SetCursorPos(Math.round(x), Math.round(y));
  return ok(Date.now() - t0);
}

export async function cursorPos(): Promise<{ x: number; y: number }> {
  const pt = { x: 0, y: 0 };
  if (!GetCursorPos(pt as never)) throw new DriverError("DRIVER_ERROR", "GetCursorPos 失败");
  return { x: pt.x, y: pt.y };
}

/** FR-006：逐字符键入（需要目标已有焦点） */
export async function typeText(text: string): Promise<ActionResult> {
  const t0 = Date.now();
  for (const ch of text) {
    const code = ch.charCodeAt(0);
    const upper = code >= 0x41 && code <= 0x5a; // A-Z 需要按住 Shift
    const vk = upper ? code + 0x20 : charVkLo(ch);
    if (vk === undefined) throw new DriverError("INVALID_ARGUMENT", `无法键入字符: ${JSON.stringify(ch)}`);
    if (upper) keybd_event(0x10, 0, 0, null);
    keybd_event(vk, 0, 0, null);
    keybd_event(vk, 0, 2, null); // KEYEVENTF_KEYUP
    if (upper) keybd_event(0x10, 0, 2, null);
    await sleep(5);
  }
  return ok(Date.now() - t0);
}

function charVkLo(ch: string): number | undefined {
  const c = ch.toUpperCase().charCodeAt(0);
  if ((c >= 0x30 && c <= 0x39) || (c >= 0x41 && c <= 0x5a)) return c;
  if (ch === " ") return 0x20;
  return undefined; // 中文/符号走剪贴板粘贴（typeViaClipboard）
}

/** 中文等非 VK 字符：剪贴板粘贴通道 */
export async function typeViaClipboard(text: string): Promise<ActionResult> {
  const t0 = Date.now();
  // 剪贴板写入无原生 FFI 通道时退回 PowerShell（一次性、非热路径）
  const { execFileSync } = await import("node:child_process");
  const ps = `Set-Clipboard -Value ${JSON.stringify(text).replace(/"/g, `'`)}`;
  execFileSync("powershell.exe", ["-NoProfile", "-Command", ps], { timeout: 3000 });
  const ops = parseCombo("ctrl+v");
  for (const op of ops) keybd_event(op.vk, 0, op.down ? 0 : 2, null);
  return ok(Date.now() - t0);
}

/** FR-007：组合键 */
export async function keyCombo(combo: string): Promise<ActionResult> {
  const t0 = Date.now();
  let ops;
  try {
    ops = parseCombo(combo);
  } catch (e) {
    throw new DriverError("INVALID_ARGUMENT", (e as Error).message);
  }
  for (const op of ops) keybd_event(op.vk, 0, op.down ? 0 : 2, null);
  return ok(Date.now() - t0);
}

/** FR-008：滚动（wheel 量：1 page ≈ 3 × 120 × notch） */
export async function scrollAt(x: number, y: number, dir: "up" | "down" | "left" | "right", pages = 1): Promise<ActionResult> {
  const t0 = Date.now();
  SetCursorPos(Math.round(x), Math.round(y));
  const amount = pages * 3 * 120;
  if (dir === "up") mouse_event(WHEEL, 0, 0, amount, null);
  else if (dir === "down") mouse_event(WHEEL, 0, 0, -amount, null);
  else if (dir === "left") mouse_event(HWHEEL, 0, 0, -amount, null);
  else mouse_event(HWHEEL, 0, 0, amount, null);
  return ok(Date.now() - t0);
}

/** FR-009：拖拽路径 */
export async function dragPath(path: Array<{ x: number; y: number }>): Promise<ActionResult> {
  const t0 = Date.now();
  if (!Array.isArray(path) || path.length < 2) {
    throw new DriverError("INVALID_ARGUMENT", "drag 至少需要 2 个路径点");
  }
  const [start, ...rest] = path;
  SetCursorPos(Math.round(start.x), Math.round(start.y));
  await sleep(50);
  mouse_event(LEFT_DOWN, 0, 0, 0, null);
  for (const p of rest) {
    SetCursorPos(Math.round(p.x), Math.round(p.y));
    await sleep(20);
  }
  mouse_event(LEFT_UP, 0, 0, 0, null);
  return ok(Date.now() - t0);
}

/** FR-011：等待（上限 10s） */
export async function waitSeconds(seconds: number): Promise<ActionResult> {
  if (seconds < 0 || seconds > 10) throw new DriverError("INVALID_ARGUMENT", `wait 秒数需 ∈[0,10]，收到 ${seconds}`);
  const t0 = Date.now();
  await sleep(seconds * 1000);
  return { ok: true, via: "sendinput", elapsedMs: Date.now() - t0 };
}
