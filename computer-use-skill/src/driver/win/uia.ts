/**
 * driver/win/uia.ts —— UIA 常驻进程宿主（FR-002/003/012，ADR-2 修订版）
 * 协议：spawn powershell -NoProfile -File uia.ps1，stdin/stdout 各一行一个 JSON。
 * 性能：常驻后单次调用 ~10-50ms；首命令含进程冷启动。
 */
import { spawn, type ChildProcess } from "node:child_process";
import { fileURLToPath } from "node:url";
import { join, dirname } from "node:path";
import { DriverError, type TreeResult, type UiNode } from "../types.js";

interface UiaResp {
  ok: boolean;
  error?: string;
  msg?: string;
  nodes?: UiNode[];
  truncated?: boolean;
  candidates?: unknown;
}

let proc: ChildProcess | null = null;
let seq = 0;
const pending = new Map<number, { resolve: (v: UiaResp) => void; reject: (e: Error) => void }>();
let buffer = "";

export function uiaProcess(): ChildProcess {
  if (proc && !proc.killed) return proc;
  const ps1 = join(dirname(fileURLToPath(import.meta.url)), "uia.ps1");
  proc = spawn("powershell.exe", ["-NoProfile", "-ExecutionPolicy", "Bypass", "-File", ps1], {
    stdio: ["pipe", "pipe", "pipe"],
  });
  proc.stdout!.on("data", (chunk: Buffer) => {
    buffer += chunk.toString("utf-8");
    let idx: number;
    while ((idx = buffer.indexOf("\n")) >= 0) {
      const line = buffer.slice(0, idx).trim();
      buffer = buffer.slice(idx + 1);
      if (!line) continue;
      try {
        const resp = JSON.parse(line) as UiaResp & { _seq?: number };
        if (resp._seq !== undefined && pending.has(resp._seq)) {
          pending.get(resp._seq)!.resolve(resp);
          pending.delete(resp._seq);
        }
      } catch {
        // 非 JSON 行忽略（PS 启动横幅等）
      }
    }
  });
  proc.on("exit", () => {
    proc = null;
    for (const [, p] of pending) p.reject(new DriverError("DRIVER_ERROR", "UIA 进程退出"));
    pending.clear();
  });
  return proc;
}

/** 发送一条命令并等待响应 */
export async function uiaCall(cmd: Record<string, unknown>): Promise<UiaResp> {
  const p = uiaProcess();
  const id = ++seq;
  const line = JSON.stringify({ ...cmd, _seq: id });
  return new Promise<UiaResp>((resolve, reject) => {
    const timer = setTimeout(() => {
      pending.delete(id);
      reject(new DriverError("DRIVER_TIMEOUT", `UIA 命令超时: ${String(cmd.op)}`));
    }, 10000);
    pending.set(id, {
      resolve: (v) => { clearTimeout(timer); resolve(v); },
      reject: (e) => { clearTimeout(timer); reject(e); },
    });
    p.stdin!.write(line + "\n", (err) => {
      if (err) { pending.delete(id); clearTimeout(timer); reject(err); }
    });
  });
}

function toDriverError(r: UiaResp): DriverError {
  const code = (r.error ?? "DRIVER_ERROR") as import("../types.js").DriverErrorCode;
  return new DriverError(code, r.msg ?? r.error ?? "UIA 失败", r.candidates);
}

/** FR-002：读元素树 */
export async function uiaTree(app: string, maxDepth = 4, _roleFilter?: string[]): Promise<TreeResult & { app: string }> {
  const t0 = Date.now();
  const r = await uiaCall({ op: "tree", app, maxDepth });
  if (!r.ok) throw toDriverError(r);
  return { nodes: r.nodes ?? [], truncated: !!r.truncated, elapsedMs: Date.now() - t0, app: (r as { app?: string }).app ?? app };
}

/** FR-012：UIA 直控（Invoke/Expand/Toggle/Select/SetValue） */
export async function uiaAct(app: string, by: string, value: unknown, pattern: string, setValue?: string): Promise<void> {
  const r = await uiaCall({ op: "act", app, by, value, pattern, value_set: setValue });
  if (!r.ok) throw toDriverError(r);
}

/** 健康检查（启动冒烟用） */
export async function uiaPing(): Promise<boolean> {
  const r = await uiaCall({ op: "ping" });
  return !!r.ok;
}

export function shutdownUia(): void {
  proc?.kill();
  proc = null;
}
