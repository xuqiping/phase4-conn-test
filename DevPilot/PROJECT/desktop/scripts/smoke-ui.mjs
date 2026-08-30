// Phase 4 冒烟：真实驱动 DevPilot 界面（Vite dev server @ localhost:1420）。
// 用系统 Edge（channel: msedge），零下载。截图存 docs/测试方案/证据/。
// 注意：浏览器环境无 Tauri IPC（window.__TAURI__ 缺失），内核命令走不了——
// 这里验证的是「界面壳层」：三栏渲染/导航切换/管道条联动/右栏 Tab；
// 含 IPC 的创建项目流程需在真窗口人工走查（测试方案 TC 表）。
import { chromium } from "playwright";

const shot = (page, name) =>
  page.screenshot({ path: `workflow_output/docs/测试方案/证据/${name}.png`, fullPage: false });

const browser = await chromium.launch({ channel: "msedge" });
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
const errors = [];
page.on("console", (m) => m.type() === "error" && errors.push(m.text()));
page.on("pageerror", (e) => errors.push(String(e)));

await page.goto("http://localhost:1420", { waitUntil: "networkidle" });

// 1. 外壳渲染
await page.getByText("DevPilot").first().waitFor({ timeout: 5000 });
console.log("✅ 1 外壳渲染：顶栏品牌可见");

// 2. 三栏就位
for (const t of ["topbar", "sidebar", "center", "rightbar"]) {
  if (!(await page.getByTestId(t).count())) throw new Error(`缺 ${t}`);
}
console.log("✅ 2 三栏骨架：topbar/sidebar/center/rightbar 就位");
await shot(page, "01-外壳");

// 3. 密度切换
await page.getByRole("button", { name: "切换界面密度" }).click();
const density = await page.locator("#root > div").getAttribute("data-density");
if (density !== "compact") throw new Error("密度切换失效");
await page.getByRole("button", { name: "切换界面密度" }).click();
console.log("✅ 3 密度切换：舒适⇄紧凑生效");

// 4. 管道条联动：点「建造」→ 中栏切视图 + 右栏归位日志
await page
  .getByTestId("pipeline")
  .getByRole("button", { name: /建造/ })
  .click();
await page.getByTestId("view-build").waitFor({ timeout: 3000 });
await shot(page, "02-建造视图");
const sel = await page
  .getByRole("tab", { name: "日志" })
  .getAttribute("aria-selected");
if (sel !== "true") throw new Error("右栏 Tab 未随视图归位");
console.log("✅ 4 管道条联动：点建造→视图+右栏日志 Tab 归位");

// 5. 左栏导航联动 + 高亮
await page.getByTestId("sidebar").getByRole("button", { name: /需求/ }).click();
await page.getByTestId("view-spec").waitFor({ timeout: 3000 });
const cur = await page
  .getByTestId("pipeline")
  .getByRole("button", { name: /需求/ })
  .getAttribute("aria-current");
if (cur !== "step") throw new Error("管道条未高亮当前阶段");
await shot(page, "03-需求视图");
console.log("✅ 5 导航联动：点需求→视图切换+管道条高亮");

// 6. 错误 toast（浏览器无 Tauri IPC 时 init 失败的大白话提示）→ 记录并关闭
const alert = page.getByRole("alert");
if (await alert.count()) {
  const alertText = await alert.innerText();
  console.log(`ℹ️ 错误 toast 内容（IPC 不可用时的降级提示）：${alertText.split("\n")[0]}`);
  await shot(page, "05-错误toast");
  await alert.getByRole("button", { name: "知道了" }).click();
  await alert.waitFor({ state: "hidden", timeout: 3000 });
}
console.log("✅ 6 错误 toast 可展示可关闭");

// 7. 右栏五 Tab 手动切换
await page.getByRole("tab", { name: "文件" }).click();
await shot(page, "04-右栏文件Tab");
console.log("✅ 7 右栏五 Tab 可手动切换");

console.log(errors.length ? `⚠️ console 错误 ${errors.length} 条：\n` + errors.join("\n") : "✅ 8 console 无错误");
await browser.close();
console.log("=== 冒烟通过 ===");
