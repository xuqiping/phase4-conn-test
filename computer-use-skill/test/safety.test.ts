// FR-013/014 安全层单测（AC-017/018）
import { describe, it, expect, beforeEach, afterEach } from "vitest";
import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { loadConfig, configPath } from "../src/safety/config.js";
import { requireAllowed, confirmApp, isAllowed } from "../src/safety/whitelist.js";
import { requireNotBlocked, isBlocked } from "../src/safety/blacklist.js";

let home: string;

beforeEach(() => {
  home = mkdtempSync(join(tmpdir(), "cu-skill-"));
  process.env.CU_SKILL_HOME = home;
});
afterEach(() => {
  rmSync(home, { recursive: true, force: true });
  delete process.env.CU_SKILL_HOME;
});

describe("白名单流 FR-013（AC-017）", () => {
  it("白名单外首次操作返回 CONFIRMATION_REQUIRED 带 appId", () => {
    expect(() => requireAllowed("notepad.exe")).toThrowError(
      expect.objectContaining({ code: "CONFIRMATION_REQUIRED" })
    );
  });

  it("AC-017: confirm_app(remember=true) 后重试成功且 config.toml 已记忆", () => {
    expect(() => requireAllowed("notepad.exe")).toThrow();
    const r = confirmApp("notepad.exe", true);
    expect(r.remembered).toBe(true);
    // 文件已落盘
    const cfg = loadConfig();
    expect(cfg.alwaysAllowedAppIds).toContain("notepad.exe");
    // 重试不再抛
    expect(() => requireAllowed("notepad.exe")).not.toThrow();
    // 第二次 confirm 不产生重复条目
    confirmApp("notepad.exe", true);
    expect(loadConfig().alwaysAllowedAppIds.filter((x) => x === "notepad.exe")).toHaveLength(1);
  });

  it("联动-反向: remember=false 只本次放行不落盘", () => {
    confirmApp("mspaint.exe", false);
    expect(isAllowed("mspaint.exe")).toBe(true);
    expect(loadConfig().alwaysAllowedAppIds).not.toContain("mspaint.exe");
  });

  it("联动-边界: config.toml 被手动清空回到全确认（即时读取不缓存）", () => {
    confirmApp("calc.exe", true);
    expect(isAllowed("calc.exe")).toBe(true);
    writeFileSync(configPath(), "", "utf-8");
    expect(isAllowed("calc.exe")).toBe(false);
  });

  it("配置损坏不致命：视为空白名单", () => {
    writeFileSync(configPath(), "!!!broken[[[", "utf-8");
    expect(loadConfig().alwaysAllowedAppIds).toEqual([]);
  });
});

describe("黑名单 FR-014（AC-018）", () => {
  it("AC-018: 对 WindowsTerminal 操作抛 TARGET_BLOCKED 且不执行输入", () => {
    for (const p of ["WindowsTerminal.exe", "cmd.exe", "powershell.exe", "conhost.exe"]) {
      expect(() => requireNotBlocked(p)).toThrowError(
        expect.objectContaining({ code: "TARGET_BLOCKED" })
      );
    }
  });

  it("宿主自身进程被拦截（CU_SKILL_HOST_PROCESS 注入）", () => {
    process.env.CU_SKILL_HOST_PROCESS = "claude.exe";
    expect(isBlocked("Claude.exe")).toBe(true);
    delete process.env.CU_SKILL_HOST_PROCESS;
  });

  it("普通 App 不被拦截", () => {
    expect(isBlocked("notepad.exe")).toBe(false);
  });
});
