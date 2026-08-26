/**
 * safety/config.ts —— config.toml 读写（FR-013）
 * 路径：$CU_SKILL_HOME（默认项目根）。每次校验即时读文件（不缓存）——
 * 联动点：文件被手动清空 = 回到全确认状态（plan 联动清单）。
 */
import { readFileSync, writeFileSync, existsSync, mkdirSync } from "node:fs";
import { join, dirname } from "node:path";
import * as TOML from "@iarna/toml";

export interface CuConfig {
  alwaysAllowedAppIds: string[];
  /** 升级v2 运维开关：层2 PostMessage 后台执行（默认开，出问题可秒关回 v1 行为） */
  layer2Enabled: boolean;
  /** 升级v2 运维开关：学习记忆（默认开） */
  memoryEnabled: boolean;
}

interface RawConfig {
  computer_use?: { always_allowed_app_ids?: string[]; layer2_enabled?: boolean; memory_enabled?: boolean };
}

export function configDir(): string {
  return process.env.CU_SKILL_HOME ?? join(process.cwd());
}

export function configPath(): string {
  return join(configDir(), "config.toml");
}

export function loadConfig(): CuConfig {
  const p = configPath();
  if (!existsSync(p)) return { alwaysAllowedAppIds: [], layer2Enabled: true, memoryEnabled: true };
  try {
    const raw = TOML.parse(readFileSync(p, "utf-8")) as unknown as RawConfig;
    return {
      alwaysAllowedAppIds: [...(raw.computer_use?.always_allowed_app_ids ?? [])],
      layer2Enabled: raw.computer_use?.layer2_enabled ?? true,
      memoryEnabled: raw.computer_use?.memory_enabled ?? true,
    };
  } catch {
    // 配置损坏不致命：视为空白名单（回到全确认）；开关取默认开
    return { alwaysAllowedAppIds: [], layer2Enabled: true, memoryEnabled: true };
  }
}

export function saveConfig(cfg: CuConfig): void {
  const body = TOML.stringify({
    computer_use: {
      always_allowed_app_ids: cfg.alwaysAllowedAppIds,
      layer2_enabled: cfg.layer2Enabled,
      memory_enabled: cfg.memoryEnabled,
    },
  } as unknown as TOML.JsonMap);
  const p = configPath();
  mkdirSync(dirname(p), { recursive: true });
  writeFileSync(p, String(body), "utf-8");
}

/** 追加白名单（去重） */
export function rememberApp(appId: string): void {
  const cfg = loadConfig();
  if (!cfg.alwaysAllowedAppIds.includes(appId)) {
    cfg.alwaysAllowedAppIds.push(appId);
    saveConfig(cfg);
  }
}
