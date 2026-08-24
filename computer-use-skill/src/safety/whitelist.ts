/**
 * safety/whitelist.ts —— App 白名单校验（FR-013，AC-017）
 * 白名单外：抛 CONFIRMATION_REQUIRED（附 appId 与提示）。
 * confirmApp(appId, remember)：放行入口——remember=true 落盘记忆。
 */
import { DriverError } from "../driver/types.js";
import { loadConfig, rememberApp } from "./config.js";

/** 校验 app 是否已放行；未放行抛 CONFIRMATION_REQUIRED（config ∪ 本次会话放行） */
export function requireAllowed(appId: string): void {
  if (isAllowed(appId)) return;
  throw new DriverError(
    "CONFIRMATION_REQUIRED",
    `应用 ${appId} 未在白名单。请向用户确认后调用 confirm_app 工具放行`,
    { appId, hint: "confirm_app" }
  );
}

/** 单次放行会话（confirm_app remember=false 时用，不落盘） */
const sessionAllowed = new Set<string>();

/** FR-013：confirm_app 入口。remember=true 写入 config.toml（AC-017 断言）。 */
export function confirmApp(appId: string, remember: boolean): { ok: true; remembered: boolean } {
  if (remember) {
    rememberApp(appId);
  } else {
    sessionAllowed.add(appId);
  }
  return { ok: true, remembered: remember };
}

/** 综合校验：config.toml ∪ 本次会话放行 */
export function isAllowed(appId: string): boolean {
  return sessionAllowed.has(appId) || loadConfig().alwaysAllowedAppIds.includes(appId);
}
