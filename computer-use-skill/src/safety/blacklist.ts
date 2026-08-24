/**
 * safety/blacklist.ts —— 终端/宿主黑名单硬拦截（FR-014，AC-018）
 * 拦截发生在 driver 层之前：任何动作工具先过这道闸。
 */
import { DriverError } from "../driver/types.js";

/** 终端类进程（FR-014）——大小写不敏感匹配进程名 */
const TERMINAL_PROCESSES = [
  "windowsterminal.exe",
  "cmd.exe",
  "powershell.exe",
  "pwsh.exe",
  "conhost.exe",
  "wt.exe",
];

/** 宿主 Agent 自身（MCP stdio 客户端进程） */
function hostProcess(): string | null {
  // stdio 客户端即父进程；MCP SDK 不直接暴露，经环境变量由宿主注入（可选）
  return process.env.CU_SKILL_HOST_PROCESS?.toLowerCase() ?? null;
}

export function isBlocked(processName: string): boolean {
  const p = processName.toLowerCase();
  if (TERMINAL_PROCESSES.includes(p)) return true;
  const host = hostProcess();
  if (host && p === host) return true;
  return false;
}

/** 动作前置检查：被拦截抛 TARGET_BLOCKED（AC-018） */
export function requireNotBlocked(processName: string): void {
  if (isBlocked(processName)) {
    throw new DriverError(
      "TARGET_BLOCKED",
      `目标 ${processName} 属于终端类或宿主自身，禁止自动化（安全红线：防止绕过安全策略）`
    );
  }
}
