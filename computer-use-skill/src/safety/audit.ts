/**
 * safety/audit.ts + redact —— JSONL 审计与脱敏（FR-015/016）
 * 每个工具调用记一行：{ts, tool, targetApp, element, via, ok, errCode}
 * 红线：日志只收字符串，截图字节绝不进入（FR-016）；敏感字段脱敏（FR-015）。
 */
import { appendFileSync, existsSync, mkdirSync, renameSync, statSync, readFileSync, rmSync } from "node:fs";
import { join } from "node:path";
import { configDir } from "./config.js";

export interface AuditEntry {
  ts: string;
  tool: string;
  targetApp?: string;
  element?: string;
  via?: string;
  ok: boolean;
  errCode?: string;
  elapsedMs?: number;
}

/** 敏感标记：role=Password 或名称含关键词（FR-015） */
const SENSITIVE = /password|密码|验证码|passcode|token|secret/i;

export function redact(name: string | undefined, role?: string): string | undefined {
  if (name === undefined) return undefined;
  if (role === "Password" || SENSITIVE.test(name)) return "***";
  return name;
}

function logDir(): string {
  return join(configDir(), "logs");
}
function logPath(n = 0): string {
  return join(logDir(), n === 0 ? "audit.jsonl" : `audit.${n}.jsonl`);
}

/** 轮转：单文件 >10MB 时滚动（10MB × 3，见 security_strategy §3） */
function rotate(): void {
  const dir = logDir();
  mkdirSync(dir, { recursive: true });
  if (!existsSync(logPath())) return;
  if (statSync(logPath()).size < 10 * 1024 * 1024) return;
  for (let i = 2; i >= 0; i--) {
    if (existsSync(logPath(i))) renameSync(logPath(i), logPath(i + 1));
  }
  renameSync(logPath(0), logPath(1));
  // 超出 3 份即删最旧
  const oldest = logPath(3);
  if (existsSync(oldest)) rmSync(oldest);
}

/** 追加一条审计（入口收敛：调用方先 redact 敏感字段） */
export function audit(e: AuditEntry): void {
  try {
    rotate();
    appendFileSync(logPath(), JSON.stringify(e) + "\n", "utf-8");
  } catch {
    // 审计失败不阻断主流程（但也不吞错——stderr 提示）
    console.error("[audit] 写入失败", e.tool);
  }
}

/** 测试辅助：当前日志文件行数 */
export function auditLineCount(): number {
  if (!existsSync(logPath())) return 0;
  return readFileSync(logPath(), "utf-8").split("\n").filter(Boolean).length;
}
