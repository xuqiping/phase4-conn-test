// FR-015/016 单测（AC-019/020）
import { describe, it, expect, beforeEach, afterEach } from "vitest";
import { mkdtempSync, rmSync, existsSync, readdirSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { audit, redact, auditLineCount } from "../src/safety/audit.js";

let home: string;
beforeEach(() => {
  home = mkdtempSync(join(tmpdir(), "cu-audit-"));
  process.env.CU_SKILL_HOME = home;
});
afterEach(() => rmSync(home, { recursive: true, force: true }));

describe("脱敏（FR-015，AC-019）", () => {
  it("role=Password 节点脱敏为 ***", () => {
    expect(redact("用户密码输入框", "Password")).toBe("***");
  });
  it("名称含 password/密码/验证码 脱敏", () => {
    expect(redact("password field")).toBe("***");
    expect(redact("密码框")).toBe("***");
    expect(redact("验证码")).toBe("***");
  });
  it("普通名称不脱敏", () => {
    expect(redact("确定按钮")).toBe("确定按钮");
  });
});

describe("审计（FR-016，AC-020）", () => {
  it("审计只写字符串字段；连续 10 次截图类调用后 logs 目录无图片文件", () => {
    for (let i = 0; i < 10; i++) {
      audit({ ts: new Date().toISOString(), tool: "skyshot", targetApp: "a.exe", ok: true, elapsedMs: 40 });
    }
    expect(auditLineCount()).toBe(10);
    const files = readdirSync(join(home, "logs"));
    expect(files.every((f) => f.endsWith(".jsonl"))).toBe(true); // 无图片
    expect(existsSync(join(home, "logs", "audit.jsonl"))).toBe(true);
    // 日志内容不含 base64 图像前缀
    const { readFileSync } = require("node:fs");
    expect(readFileSync(join(home, "logs", "audit.jsonl"), "utf-8")).not.toContain("iVBORw0KGgo");
  });
});
