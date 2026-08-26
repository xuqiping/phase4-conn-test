/**
 * driver/win/window.ts —— 窗口枚举与查找（FR-001 的 APP_NOT_FOUND 候选来源）
 */
import koffi from "koffi";
import { execFileSync } from "node:child_process";
import { Buffer } from "node:buffer";
import { DriverError } from "../types.js";
import { EnumWindows, GetClassNameW, GetForegroundWindow, GetWindowTextW, GetWindowThreadProcessId, IsWindowVisible, WNDENUMPROC } from "./ffi.js";

export interface WinInfo {
  hwnd: unknown;
  title: string;
  className: string;
  pid: number;
}

/** 当前前台窗口所属进程名（用于 key/drag 等无 app 参数动作的安全闸，FR-014） */
export function foregroundProcessName(): string | null {
  const hwnd = GetForegroundWindow();
  if (!hwnd) return null;
  const pidBuf = { value: 0 };
  GetWindowThreadProcessId(hwnd as never, pidBuf as never);
  const pid = (pidBuf as { value: number }).value;
  if (!pid) return null;
  try {
    const out = execFileSync("tasklist", ["/FI", `PID eq ${pid}`, "/FO", "CSV", "/NH"], { timeout: 3000, encoding: "utf8" }).trim();
    return out.split('","')[0]?.replace(/^"/, "") || null;
  } catch {
    return null;
  }
}

/** 枚举所有可见顶层窗口 */
export function listWindows(): WinInfo[] {
  const out: WinInfo[] = [];
  const cb = koffi.register((hwnd: unknown, _lparam: bigint) => {
    if (!IsWindowVisible(hwnd as never)) return true;
    const title = readStr(GetWindowTextW, hwnd);
    if (!title) return true; // 无标题窗口跳过
    const pidBuf = [0];
    GetWindowThreadProcessId(hwnd as never, pidBuf);
    out.push({ hwnd, title, className: readStr(GetClassNameW, hwnd), pid: pidBuf[0] });
    return true;
  }, koffi.pointer(WNDENUMPROC));
  try {
    EnumWindows(cb as never, 0);
  } finally {
    koffi.unregister(cb);
  }
  return out;
}

type StrFn = (hwnd: unknown, buf: Buffer, max: number) => number;
function readStr(fn: StrFn, hwnd: unknown): string {
  const buf = Buffer.alloc(1024); // 512 wchar
  const n = fn(hwnd, buf, 512);
  return n > 0 ? buf.toString("utf16le", 0, n * 2) : "";
}

/** 按 app 名（窗口标题/类名包含）找窗口；找不到给候选错误（FR-001 AC-002） */
export function findWindow(app: string): WinInfo {
  const lower = app.toLowerCase();
  const all = listWindows();
  const wins = all.filter(
    (w) => w.title.toLowerCase().includes(lower) || w.className.toLowerCase().includes(lower)
  );
  if (wins.length === 0) {
    throw new DriverError(
      "APP_NOT_FOUND",
      `未找到应用 ${app}，候选窗口如下`,
      all.slice(0, 15).map((w) => w.title)
    );
  }
  return wins[0];
}
