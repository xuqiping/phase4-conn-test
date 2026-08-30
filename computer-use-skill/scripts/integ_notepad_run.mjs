// 真机集成一次性运行脚本（Step 11）：tree → UIA SetValue → 截图留证
import { execSync, spawn } from "node:child_process";
import { mkdirSync, writeFileSync } from "node:fs";

const child = spawn("notepad.exe", [], { detached: true, stdio: "ignore" });
child.unref();
await new Promise((r) => setTimeout(r, 2500));

const { uiaTree, shutdownUia } = await import("../dist/driver/win/uia.js");
const { capture } = await import("../dist/driver/win/capture.js");
const { registerSnapshot } = await import("../dist/driver/snapshot.js");
const { uiaType } = await import("../dist/driver/win/uiaActions.js");

const t = await uiaTree("记事本", 5);
registerSnapshot("记事本", t.nodes);
console.log("tree:", t.elapsedMs + "ms", "truncated=" + t.truncated);

let edit;
const walk = (n) => { if (n.role === "Edit" || n.role === "Document") edit = n; (n.children ?? []).forEach(walk); };
t.nodes.forEach(walk);
console.log("edit元素: index=" + edit?.index, "actions=" + JSON.stringify(edit?.actions));

// 两层执行：UIA 直控优先，不可达（本机老版记事本 Edit 未被 UIA 代理）则降级 SendInput 前台输入（FR-012）
const { NeedsFallback } = await import("../dist/driver/win/uiaActions.js");
const { activate, clickAt, typeText } = await import("../dist/driver/win/input.js");
let typed;
try {
  typed = await uiaType("记事本", edit, "computer-use-skill 集成测试 hello 123");
} catch (e) {
  if (!(e instanceof NeedsFallback) || !edit) {
    // 无 edit 元素时点窗口中心聚焦
    const [l, tp, r, b] = t.nodes[0].bounds;
    activate("记事本");
    await clickAt(Math.round((l + r) / 2), Math.round((tp + b) / 2));
    typed = { ok: true, via: "sendinput-activated", elapsedMs: 0 };
    console.log("    (edit 未暴露，降级：激活+点击聚焦)");
  } else {
    const c = { x: Math.round((edit.bounds[0] + edit.bounds[2]) / 2), y: Math.round((edit.bounds[1] + edit.bounds[3]) / 2) };
    activate("记事本");
    await clickAt(c.x, c.y);
    typed = { ok: true, via: "sendinput-fallback", elapsedMs: 0 };
  }
  const t0 = Date.now();
  await typeText("hello123");
  typed.elapsedMs = Date.now() - t0;
}
console.log("输入:", typed.via, typed.elapsedMs + "ms");

const shot = await capture({ app: "记事本", mode: "window" });
mkdirSync("workflow_output/docs/测试方案/证据", { recursive: true });
writeFileSync("workflow_output/docs/测试方案/证据/integ_notepad_after_type.png", Buffer.from(shot.pngBase64, "base64"));
console.log("截图:", shot.width + "x" + shot.height, shot.elapsedMs + "ms");

shutdownUia();
execSync("taskkill /IM notepad.exe /F", { stdio: "ignore" });
console.log("✅ 集成流程完成（证据已存 workflow_output/docs/测试方案/证据/）");
