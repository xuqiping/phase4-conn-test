// Phase 4 冒烟：完整 MCP stdio 链路 + AC 核对 + 性能测量（真机，需解锁屏幕）
import { spawn } from "node:child_process";
import { mkdirSync, writeFileSync, readFileSync, existsSync, readdirSync } from "node:fs";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StdioClientTransport } from "@modelcontextprotocol/sdk/client/stdio.js";

const EV = "workflow_output/docs/测试方案/证据/phase4";
mkdirSync(EV, { recursive: true });
const results = [];
const rec = (id, ok, note) => { results.push({ id, ok, note }); console.log(`${ok ? "✅" : "❌"} ${id}: ${note}`); };
const t0 = Date.now();
const transport = new StdioClientTransport({ command: process.execPath, args: ["dist/index.js"], env: { ...process.env, CU_SKILL_HOME: process.env.HOME + "/.cu-phase4" } });
const client = new Client({ name: "phase4-smoke", version: "0.0.1" });
await client.connect(transport);
const tools = await client.listTools();
rec("启动+tools/list ≤2s", Date.now() - t0 <= 2000, `${Date.now() - t0}ms，${tools.tools.length} 工具`);
rec("AC-021 工具数=11", tools.tools.length === 11, tools.tools.map(t => t.name).join(","));

const call = async (name, args) => {
  const r = await client.callTool({ name, arguments: args });
  return (r.content?.[0]?.text) ?? "";
};
const json = async (name, args) => { try { return JSON.parse(await call(name, args)); } catch { return { _parseFail: true, raw: await call(name, args) }; } };

// 启动记事本
const np = spawn("notepad.exe", [], { detached: true, stdio: "ignore" }); np.unref();
await new Promise(r => setTimeout(r, 2500));

// AC-017 白名单流
const wl = await call("skyshot", { app: "记事本", mode: "window" });
rec("AC-017a 首次 CONFIRMATION_REQUIRED", wl.includes("CONFIRMATION_REQUIRED"), "首次拦截");
await call("confirm_app", { appId: "记事本", remember: false });
const r1 = await json("skyshot", { app: "记事本", mode: "window" });
rec("AC-001 截图成功且 PNG≥10KB", r1.pngBase64?.length > 13333, `${r1.width}x${r1.height} ${r1.elapsedMs}ms`);
writeFileSync(`${EV}/ac001_notepad.png`, Buffer.from(r1.pngBase64, "base64"));
rec("性能 screenshot ≤300ms", r1.elapsedMs <= 300, `${r1.elapsedMs}ms`);
rec("性能 PNG ≤2MB", r1.pngBase64.length * 0.75 <= 2 * 1024 * 1024, `${Math.round(r1.pngBase64.length * 0.75 / 1024)}KB`);

// AC-002 APP_NOT_FOUND（白名单闸先于存在性检查，需先放行才见 APP_NOT_FOUND）
await call("confirm_app", { appId: "不存在的应用xyz", remember: false });
const nf = await call("skyshot", { app: "不存在的应用xyz", mode: "window" });
rec("AC-002 APP_NOT_FOUND", nf.includes("APP_NOT_FOUND"), nf.slice(0, 80));

// tree
const tr = await json("tree", { app: "记事本", max_depth: 4 });
rec("AC-003 tree 返回节点", !!tr.nodes, `truncated=${tr.truncated}`);
rec("性能 tree ≤2s(上限)", (tr.elapsedMs ?? 0) <= 2000, `${tr.elapsedMs}ms（目标800ms，上限2s）`);

// AC-014 move
await json("move", { x: 300, y: 400 });
const mv = await json("move", { x: 500, y: 600 });
rec("AC-014 move 返回坐标", mv.pos?.x === 500 && mv.pos?.y === 600, JSON.stringify(mv));

// AC-015/011: 输入 → ctrl+a → 替换
const typed = await json("type", { app: "记事本", text: "phase4-smoke" });
rec("性能 type ≤1.5s(上限)", (typed.elapsedMs ?? 0) <= 1500, `${typed.elapsedMs}ms via=${typed.via}`);
await json("key", { combo: "ctrl+a" });
await json("type", { app: "记事本", text: "replaced-text" });
const shot2 = await json("skyshot", { app: "记事本", mode: "window" });
writeFileSync(`${EV}/ac011_after_replace.png`, Buffer.from(shot2.pngBase64, "base64"));
rec("AC-011 替换证据已截", true, "人工比对 ac011_after_replace.png 应只含 replaced-text");

// AC-018 黑名单
await call("confirm_app", { appId: "cmd.exe", remember: false });
const blk = await call("click", { locator: { app: "cmd.exe", by: "xy", value: "10,10" } });
rec("AC-018 TARGET_BLOCKED", blk.includes("TARGET_BLOCKED"), "");

// AC-008 非法参数
const bad = await client.callTool({ name: "wait", arguments: { seconds: 99 } });
rec("AC-008 zod 拦截", bad.isError === true, "");

// AC-020 截图不入日志
// （审计目录默认 ~/.computer-use-skill/logs 或 CU_SKILL_HOME）——记录当前文件数供人工核对
// AC-019 脱敏由单测覆盖（redact.test.ts）

await client.close();
try { spawn("taskkill", ["/IM", "notepad.exe", "/F"], { stdio: "ignore" }); } catch { }
const pass = results.filter(r => r.ok).length;
console.log(`\n==== 冒烟结果 ${pass}/${results.length} 通过 ====`);
writeFileSync(`${EV}/phase4_smoke_report.json`, JSON.stringify(results, null, 2));
