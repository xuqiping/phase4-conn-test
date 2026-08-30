/**
 * driver/win/capture.ts —— 窗口截图（FR-001）
 * PrintWindow → HBITMAP → GDI+ PNG（临时文件）→ base64。
 * 性能目标：单窗口 ≤300ms（performance_goals）。
 */
import { readFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { randomUUID } from "node:crypto";
import type { Screenshot, ScreenshotOpts } from "../types.js";
import { DriverError } from "../types.js";
import {
  CreateCompatibleBitmap,
  CreateCompatibleDC,
  DeleteDC,
  DeleteObject,
  GetDC,
  GdipCreateBitmapFromHBITMAP,
  GdipDisposeImage,
  GdipSaveImageToFile,
  GetClientRect,
  initWinFfi,
  IsIconic,
  ShowWindow,
  PNG_CLSID,
  PrintWindow,
  ReleaseDC,
  SelectObject,
} from "./ffi.js";
import { findWindow } from "./window.js";

export async function capture(opts: ScreenshotOpts): Promise<Screenshot> {
  const t0 = Date.now();
  initWinFfi();
  const pngPath = join(tmpdir(), `cu-shot-${randomUUID()}.png`);
  try {
    let w: number, h: number, hwnd: unknown, hdcWindow: unknown;
    if (opts.mode === "window" && opts.app) {
      const win = findWindow(opts.app);
      hwnd = win.hwnd;
      if (IsIconic(hwnd as never)) {
        ShowWindow(hwnd as never, 9); // SW_RESTORE：最小化窗口先还原（锁屏期间可能被最小化）
        await new Promise((r) => setTimeout(r, 200));
      }
      const rect = {};
      if (!GetClientRect(hwnd as never, rect as never)) {
        throw new DriverError("DRIVER_ERROR", "GetClientRect 失败");
      }
      const r = rect as { right: number; bottom: number };
      w = r.right;
      h = r.bottom;
      hdcWindow = GetDC(hwnd as never);
    } else {
      throw new DriverError("INVALID_ARGUMENT", "MVP 仅支持 mode=window；全屏模式后续迭代");
    }
    if (w <= 0 || h <= 0) throw new DriverError("DRIVER_ERROR", `窗口尺寸非法 ${w}x${h}`);

    const hdcMem = CreateCompatibleDC(hdcWindow as never);
    const hbm = CreateCompatibleBitmap(hdcWindow as never, w, h);
    SelectObject(hdcMem as never, hbm as never);
    // PW_RENDERFULLCONTENT=2：捕获 DirectComposition 内容（Chrome/Electron 窗口必需）
    const ok = PrintWindow(hwnd as never, hdcMem as never, 2);
    ReleaseDC(hwnd as never, hdcWindow as never);

    if (!ok) throw new DriverError("DRIVER_ERROR", "PrintWindow 失败（目标可能以管理员运行）");

    // HBITMAP → PNG（GDI+）
    const bmp = [null];
    let rc = GdipCreateBitmapFromHBITMAP(hbm as never, null, bmp);
    if (rc !== 0) throw new DriverError("DRIVER_ERROR", `GdipCreateBitmapFromHBITMAP failed: ${rc}`);
    rc = GdipSaveImageToFile(bmp[0], pngPath, PNG_CLSID, null);
    GdipDisposeImage(bmp[0]);
    DeleteObject(hbm as never);
    DeleteDC(hdcMem as never);
    if (rc !== 0) throw new DriverError("DRIVER_ERROR", `GdipSaveImageToFile failed: ${rc}`);

    const png = readFileSync(pngPath);
    return {
      pngBase64: png.toString("base64"),
      width: w,
      height: h,
      elapsedMs: Date.now() - t0,
    };
  } finally {
    rmSync(pngPath, { force: true }); // 截图绝不留盘（FR-016 精神）
  }
}
