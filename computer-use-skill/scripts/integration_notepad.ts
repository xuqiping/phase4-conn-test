/**
 * 集成脚本：记事本靶子流程（AC-007/010 证据链）
 * 用法：npm run build && CU_INTEG=1 npx tsx scripts/integration_notepad.ts
 * 前置：无（脚本自启记事本）；本机交互会话、DPI 100%。
 */
import { execSync, spawn } from "node:child_process";
import { mkdirSync, writeFileSync } from "node:fs";
import { join } from "node:path";

const EV = join(process.cwd(), "workflow_output", "docs", "测试方案", "证据");
mkdirSync(EV, { recursive: true });

async function main() {
  console.log("[1] 启动记事本…");
  const child = spawn("notepad.exe", [], { detached: true, stdio: "ignore" });
  child.unref();
  await new Promise((r) => setTimeout(r, 2500));

  const { uiaTree, shutdownUia } = await import("../dist/driver/win/uia.js");
  const { capture } = await import("../dist/driver/win/capture.js");
  const { registerSnapshot } = await import("../dist/driver/snapshot.js");
  const { uiaClick } = await import("../dist/driver/win/uiaActions.js");
  const { uiaType } = await import("../dist/driver/win/uiaActions.js");

  console.log("[2] 读元素树…");
  const t = await uiaTree("记事本", 5);
  registerSnapshot("记事本", t.nodes);
  console.log(`    耗时 ${t.elapsedMs}ms truncated=${t.truncated}`);

  console.log("[3] 找 edit 元素并 UIA SetValue 输入…");
  let edit: { index: number; name: string; actions: string[] } | undefined;
  const walk = (n: any) => { if (n.role === "Edit" || n.role === "Document") edit = n; (n.children ?? []).forEach(walk); };
  t.nodes.forEach(walk);
  if (!edit) throw new Error("未找到编辑区元素");
  console.log(`    edit index=${edit.index} actions=${(edit as any).actions}`);
  const typed = await uiaType("记事本", edit as any, "computer-use-skill 集成测试 hello 123");
  console.log(`    输入完成 via=${typed.via} ${typed.elapsedMs}ms`);

  console.log("[4] 截图留证…");
  const shot = await capture({ app: "记事本", mode: "window" });
  writeFileSync(join(EV, "integ_notepad_after_type.png"), Buffer.from(shot.pngBase64, "base64"));
  console.log(`    ${shot.width}x${shot.height} ${shot.elapsedMs}ms → 证据已存`);

  shutdownUia();
  console.log("[5] 完成。记事本请手动关闭（或由 Agent 后续关闭）。");
  execSync("taskkill /IM notepad.exe /F", { stdio: "ignore" });
}

main().catch((e) => { console.error("集成失败:", e); process.exit(1); });
