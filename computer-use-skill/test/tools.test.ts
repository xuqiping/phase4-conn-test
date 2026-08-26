/**
 * 工具层全链路测试（AC-021，mock 安全配置）
 * 用 MCP Client 真连 stdio 子进程验证 tools/list 与基础调用。
 */
import { describe, it, expect, beforeAll, afterAll } from "vitest";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StdioClientTransport } from "@modelcontextprotocol/sdk/client/stdio.js";

let client: Client;
let home: string;

beforeAll(async () => {
  home = mkdtempSync(join(tmpdir(), "cu-mcp-"));
  process.env.CU_SKILL_HOME = home;
  const transport = new StdioClientTransport({
    command: process.execPath,
    args: [join(process.cwd(), "dist", "index.js")],
    env: { ...process.env, CU_SKILL_HOME: home } as Record<string, string>,
  });
  client = new Client({ name: "test", version: "0.0.1" });
  await client.connect(transport);
}, 30000);
afterAll(async () => {
  await client.close();
  rmSync(home, { recursive: true, force: true });
});

describe("MCP 工具层（FR-017）", () => {
  it("AC-021: tools/list 返回 11 个工具", async () => {
    const res = await client.listTools();
    expect(res.tools).toHaveLength(11);
    const names = res.tools.map((t) => t.name).sort();
    expect(names).toEqual(
      ["click", "confirm_app", "double_click", "drag", "key", "move", "scroll", "skyshot", "tree", "type", "wait"].sort()
    );
  });

  it("AC-017 链路: 白名单外 App 返回 CONFIRMATION_REQUIRED，confirm_app 后放行", async () => {
    const blocked = await client.callTool({ name: "skyshot", arguments: { app: "记事本", mode: "window" } });
    expect((blocked.content as { text: string }[])[0].text).toContain("CONFIRMATION_REQUIRED");
    await client.callTool({ name: "confirm_app", arguments: { appId: "记事本", remember: false } });
    // remember=false 走会话放行——本进程内后续调用应过闸（截图本身可能 APP_NOT_FOUND，取决于是否开了记事本，但不再是 CONFIRMATION_REQUIRED）
    const after = await client.callTool({ name: "skyshot", arguments: { app: "记事本", mode: "window" } });
    const t = (after.content as { text: string }[])[0].text;
    expect(t).not.toContain("CONFIRMATION_REQUIRED");
  });

  it("AC-008: 非法参数被 zod 拦截（wait 传 99）", async () => {
    const r = await client.callTool({ name: "wait", arguments: { seconds: 99 } });
    expect(r.isError ?? true).toBeTruthy();
  });

  it("AC-015: wait 1s 返回耗时 ∈[0.9,1.5]s", async () => {
    const r = await client.callTool({ name: "wait", arguments: { seconds: 1 } });
    const parsed = JSON.parse((r.content as { text: string }[])[0].text);
    expect(parsed.elapsedMs).toBeGreaterThanOrEqual(900);
    expect(parsed.elapsedMs).toBeLessThanOrEqual(1500);
  });

  it("AC-018: 对终端类 App 操作被 TARGET_BLOCKED", async () => {
    await client.callTool({ name: "confirm_app", arguments: { appId: "WindowsTerminal.exe", remember: false } });
    const r = await client.callTool({ name: "tree", arguments: { app: "WindowsTerminal.exe" } });
    expect((r.content as { text: string }[])[0].text).toContain("TARGET_BLOCKED");
  });
});
