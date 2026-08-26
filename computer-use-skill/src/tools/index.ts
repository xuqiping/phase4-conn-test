/**
 * tools/index.ts —— 11 个 MCP 工具注册（FR-017，AC-021）
 * 统一流：run() 审计包装 → 黑名单闸 → 白名单闸 → 定位 → 层1 UIA → 降级层2 SendInput。
 */
import { z } from "zod";
import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import type { ActionResult, UiNode } from "../driver/types.js";
import { DriverError } from "../driver/types.js";
import { findByIndex, registerSnapshot } from "../driver/snapshot.js";
import { capture } from "../driver/win/capture.js";
import { uiaTree } from "../driver/win/uia.js";
import { NeedsFallback, uiaClick, uiaType } from "../driver/win/uiaActions.js";
import { activate, clickAt, cursorPos, dragPath, keyCombo, moveTo, scrollAt, typeText, typeViaClipboard, waitSeconds } from "../driver/win/input.js";
import { foregroundProcessName, findWindow } from "../driver/win/window.js";
import { postClick } from "../driver/win/postmsg.js";
import { ClientToScreen, GetClientRect, ScreenToClient } from "../driver/win/ffi.js";
import * as anchors from "../memory/anchors.js";
import { changed } from "../driver/win/verify.js";
import { confirmApp, requireAllowed } from "../safety/whitelist.js";
import { requireNotBlocked } from "../safety/blacklist.js";
import { audit, redact } from "../safety/audit.js";
import { loadConfig } from "../safety/config.js";

// ---- locator schema（api/mcp-tools.md §0） ----
const locatorSchema = z.object({
  app: z.string().describe("目标应用（进程名或窗口标题包含词）"),
  by: z.enum(["name", "automationId", "index", "xy"]).describe("定位方式：index 走 tree 快照，xy 坐标兜底"),
  value: z.union([z.string(), z.number()]).describe("定位值（index 数字 / 'x,y' 坐标串）"),
});

/** 双闸：黑名单（终端/宿主 FR-014）+ 白名单（未确认 App FR-013） */
function safetyGates(app: string): void {
  requireNotBlocked(app);
  requireAllowed(app);
}

/** 前台闸：key/drag/无 locator type 直打当前前台窗口，须确认前台不是终端/宿主（FR-014） */
function requireForegroundAllowed(): void {
  const proc = foregroundProcessName();
  if (proc) requireNotBlocked(proc);
}

/** 解析 locator → 节点或坐标（index 走快照；xy 解析坐标） */
async function resolveLocator(loc: z.infer<typeof locatorSchema>): Promise<{ el?: UiNode; x?: number; y?: number }> {
  if (loc.by === "xy") {
    const m = String(loc.value).match(/^(\d+)\s*,\s*(\d+)$/);
    if (!m) throw new DriverError("INVALID_ARGUMENT", `xy 值应为 'x,y'，收到 ${String(loc.value)}`);
    return { x: Number(m[1]), y: Number(m[2]) };
  }
  if (loc.by === "index") return { el: findByIndex(loc) };
  throw new DriverError("STALE_TREE", "name/automationId 定位请先 tree 再用 index 定位（MVP 两步法）");
}

/** 两层执行（FR-012）：层1 UIA 直控，NeedsFallback → 层2 SendInput */
async function twoLayer(uiaTry: () => Promise<ActionResult>, fallback: () => Promise<ActionResult>): Promise<ActionResult> {
  try {
    return await uiaTry();
  } catch (e) {
    if (e instanceof NeedsFallback) return await fallback();
    throw e;
  }
}

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

/** 窗口指纹上下文（升级v2 记忆命中/沉淀用）：标题@客户区尺寸 + hwnd + 客户区原点(屏幕系) */
function windowCtxOf(app: string): { fp: string; w: number; h: number; hwnd: unknown; origin: { x: number; y: number } } {
  const win = findWindow(app);
  const rc = { left: 0, top: 0, right: 0, bottom: 0 };
  GetClientRect(win.hwnd as never, rc as never);
  const w = rc.right - rc.left;
  const h = rc.bottom - rc.top;
  const origin = { x: 0, y: 0 };
  ClientToScreen(win.hwnd as never, origin as never);
  return { fp: anchors.fingerprint(win.title, w, h), w, h, hwnd: win.hwnd, origin };
}

/** 锚点归一化坐标 → 屏幕坐标 */
function anchorToScreen(ctx: ReturnType<typeof windowCtxOf>, relX: number, relY: number): { x: number; y: number } {
  return { x: Math.round(ctx.origin.x + relX * ctx.w), y: Math.round(ctx.origin.y + relY * ctx.h) };
}

/** 屏幕坐标 → 锚点归一化坐标 */
function screenToAnchor(ctx: ReturnType<typeof windowCtxOf>, x: number, y: number): { relX: number; relY: number } {
  return { relX: (x - ctx.origin.x) / Math.max(ctx.w, 1), relY: (y - ctx.origin.y) / Math.max(ctx.h, 1) };
}

/**
 * 记忆命中执行（FR-111）：按锚点 method 直接操作 + 截图验证；失败 fail+1 返回 false（调用方走正常流程，FR-112）
 */
async function memoryClickAttempt(
  app: string, name: string,
  opts: { button?: "left" | "right"; count?: number }
): Promise<ActionResult | null> {
  if (!loadConfig().memoryEnabled) return null;
  const ctx = windowCtxOf(app);
  const anchor = anchors.hit(app, ctx.fp, name);
  if (!anchor) return null;
  const pt = anchorToScreen(ctx, anchor.relX, anchor.relY);
  // 按锚点记录的成功方式执行；记忆点击本身也要验证（截图前后比对）
  const before = await capture({ app, mode: "window" });
  let r: ActionResult;
  if (anchor.method === "postmessage") {
    const c = { x: pt.x, y: pt.y };
    ScreenToClient(ctx.hwnd as never, c as never);
    r = postClick(ctx.hwnd, c.x, c.y, opts);
  } else {
    activate(app);
    r = await clickAt(pt.x, pt.y, opts);
  }
  await sleep(300);
  const after = await capture({ app, mode: "window" });
  const v = changed(before.pngBase64 as string, after.pngBase64 as string);
  if (v.verified) {
    anchors.save(app, { windowFingerprint: ctx.fp, clientW: ctx.w, clientH: ctx.h, semanticName: name, relX: anchor.relX, relY: anchor.relY, method: anchor.method, verifyHash: `ratio:${v.changedRatio}` });
    return { ...r, via: "memory", detail: `锚点命中 ${anchor.id} okCount+1` };
  }
  anchors.fail(app, anchor.id); // FR-112：作废计数，连续2次删除
  audit({ ts: new Date().toISOString(), tool: "memory", targetApp: redact(app), ok: false, errCode: "ANCHOR_STALE", detail: name });
  return null;
}

/** 层2 后台点击（FR-100/102/103）：PostMessage→截图验证；verified=false 记审计并返回 null 交层3 降级 */
async function layer2Click(
  app: string,
  x: number,
  y: number,
  opts: { button?: "left" | "right"; count?: number } = {}
): Promise<ActionResult | null> {
  if (!loadConfig().layer2Enabled) return null;
  let r: ActionResult;
  try {
    const win = findWindow(app);
    const before = await capture({ app, mode: "window" });
    const pt = { x, y };
    ScreenToClient(win.hwnd as never, pt as never);
    r = postClick(win.hwnd, pt.x, pt.y, opts);
    await sleep(300); // 界面响应窗口
    const after = await capture({ app, mode: "window" });
    const v = changed(before.pngBase64 as string, after.pngBase64 as string);
    if (v.verified) return { ...r, verified: true, changedRatio: v.changedRatio, elapsedMs: 0 };
    audit({ ts: new Date().toISOString(), tool: "layer2", targetApp: redact(app), ok: false, errCode: "LAYER2_NO_EFFECT", detail: `changedRatio=${v.changedRatio} pixels=${v.changedPixels}` });
    return null;
  } catch (e) {
    // 层2 自身异常（窗口找不到等）不算失败，静默交层3
    audit({ ts: new Date().toISOString(), tool: "layer2", targetApp: redact(app), ok: false, errCode: "LAYER2_ERROR", detail: String(e) });
    return null;
  }
}

/** 三层执行（升级v2，ADR-003）：层1 UIA → 层2 PostMessage 后台（截图验证）→ 层3 SendInput 前台 */
async function threeLayerClick(
  app: string,
  el: UiNode | undefined,
  x: number,
  y: number,
  opts: { button?: "left" | "right"; count?: number; keys?: string[] },
  uiaTry: () => Promise<ActionResult>,
  sendinputFallback: () => Promise<ActionResult>
): Promise<ActionResult> {
  if (el) {
    try {
      return await uiaTry();
    } catch (e) {
      if (!(e instanceof NeedsFallback)) throw e;
    }
  }
  // 层2：屏幕坐标后台点击（el 场景用其中心）
  const sx = el ? Math.round((el.bounds[0] + el.bounds[2]) / 2) : x;
  const sy = el ? Math.round((el.bounds[1] + el.bounds[3]) / 2) : y;
  const r2 = await layer2Click(app, sx, sy, opts);
  if (r2) return r2;
  return sendinputFallback();
}

function center(el: UiNode): { x: number; y: number } {
  const [l, t, r, b] = el.bounds;
  return { x: Math.round((l + r) / 2), y: Math.round((t + b) / 2) };
}

type ToolResult = { content: { type: "text"; text: string }[]; isError?: boolean };

/** 审计包装：成功/失败各记一行（FR-015/016），错误统一 JSON */
async function run(tool: string, app: string | undefined, fn: () => Promise<unknown>): Promise<ToolResult> {
  const t0 = Date.now();
  try {
    const r = await fn();
    const via = (r as { via?: string }).via;
    audit({ ts: new Date().toISOString(), tool, targetApp: redact(app), via, ok: true, elapsedMs: Date.now() - t0 });
    return { content: [{ type: "text", text: JSON.stringify(r) }] };
  } catch (e) {
    const code = e instanceof DriverError ? e.code : "DRIVER_ERROR";
    audit({ ts: new Date().toISOString(), tool, targetApp: redact(app), ok: false, errCode: code, elapsedMs: Date.now() - t0 });
    const detail = e instanceof DriverError ? { code, msg: e.message, detail: e.detail } : { code, msg: String(e) };
    return { content: [{ type: "text", text: JSON.stringify(detail) }], isError: true };
  }
}

export function registerTools(server: McpServer): void {
  // 1. skyshot（FR-001）
  server.tool("skyshot", "截图目标窗口，返回 PNG base64（FR-001）", { app: z.string(), mode: z.literal("window").default("window") },
    async (a) => run("skyshot", a.app, async () => { safetyGates(a.app); return capture({ app: a.app, mode: a.mode }); }));

  // 2. tree（FR-002）
  server.tool("tree", "读目标 App 的 UIA 元素树（FR-002；随后可用 index 定位）", { app: z.string(), maxDepth: z.number().int().min(1).max(10).default(4) },
    async (a) => run("tree", a.app, async () => {
      safetyGates(a.app);
      const t = await uiaTree(a.app, a.maxDepth);
      registerSnapshot(a.app, t.nodes);
      return { nodes: t.nodes, truncated: t.truncated, elapsedMs: t.elapsedMs };
    }));

  // 3/4. click / double_click（FR-004/005；升级v2：记忆命中→三层执行；name=语义名，成功自动沉淀锚点）
  const clickHandler = (count: number) => async (a: { locator: z.infer<typeof locatorSchema>; button?: "left" | "right"; keys?: string[]; name?: string }) =>
    run(count > 1 ? "double_click" : "click", a.locator.app, async () => {
      safetyGates(a.locator.app);
      const { el, x, y } = await resolveLocator(a.locator);
      const target = el ? center(el) : { x: x!, y: y! };
      // 记忆命中优先（FR-111）：跳过定位直接按锚点执行+验证
      if (a.name) {
        const mem = await memoryClickAttempt(a.locator.app, a.name, { button: a.button, count });
        if (mem) return mem;
      }
      const r = await threeLayerClick(
        a.locator.app, el, x!, y!,
        { button: a.button, count, keys: a.keys },
        () => uiaClick(a.locator.app, el!, { button: a.button, count, keys: a.keys }),
        async () => { activate(a.locator.app); return clickAt(target.x, target.y, { button: a.button, count }); }
      );
      // 成功即自动沉淀（FR-110）：以实际执行方式记锚点
      if (a.name) {
        try {
          const ctx = windowCtxOf(a.locator.app);
          const rel = screenToAnchor(ctx, target.x, target.y);
          anchors.save(a.locator.app, { windowFingerprint: ctx.fp, clientW: ctx.w, clientH: ctx.h, semanticName: a.name, relX: rel.relX, relY: rel.relY, method: r.via === "postmessage" ? "postmessage" : "sendinput", verifyHash: `via:${r.via}` });
        } catch { /* 沉淀失败不影响操作结果 */ }
      }
      return r;
    });
  server.tool("click", "单击元素（FR-004；升级v2：传 name 语义名可命中学习记忆秒操作）", { locator: locatorSchema, button: z.enum(["left", "right"]).default("left"), keys: z.array(z.string()).optional(), name: z.string().optional() }, clickHandler(1));
  server.tool("double_click", "双击元素（FR-005，SendInput 前台）", { locator: locatorSchema }, clickHandler(2));

  // 5. type（FR-006）
  server.tool("type", "输入文本（FR-006；中文自动走剪贴板通道）", { locator: locatorSchema.optional(), text: z.string() },
    async (a) => run("type", a.locator?.app, async () => {
      if (a.locator) {
        safetyGates(a.locator.app);
        const { el } = await resolveLocator(a.locator);
        return twoLayer(
          () => uiaType(a.locator!.app, el!, a.text),
          async () => {
            const c = center(el!); activate(a.locator!.app);
            await clickAt(c.x, c.y);
            return /[^\x00-\x7f]/.test(a.text) ? typeViaClipboard(a.text) : typeText(a.text);
          }
        );
      }
      // 无 locator = 直接打向前台窗口：先过前台黑名单闸（FR-014，防向终端/宿主注入）
      requireForegroundAllowed();
      return typeText(a.text); // typeText 内部对非 VK 字符自动降级剪贴板
    }));

  // 6. key（FR-007）——键盘注入直打前台，必须过前台黑名单闸
  server.tool("key", "组合键，xdotool 风格如 ctrl+shift+a（FR-007）", { combo: z.string() },
    async (a) => run("key", undefined, () => { requireForegroundAllowed(); return keyCombo(a.combo); }));

  // 7. scroll（FR-008）
  server.tool("scroll", "滚动（FR-008）", { locator: locatorSchema, dir: z.enum(["up", "down", "left", "right"]), pages: z.number().int().min(1).max(10).default(1) },
    async (a) => run("scroll", a.locator.app, async () => {
      safetyGates(a.locator.app);
      const { el, x, y } = await resolveLocator(a.locator);
      const c = el ? center(el) : { x: x!, y: y! };
      activate(a.locator.app);
      return scrollAt(c.x, c.y, a.dir, a.pages);
    }));

  // 8. drag（FR-009）——拖拽作用于前台，过前台黑名单闸
  server.tool("drag", "拖拽路径（FR-009，至少 2 点）", { path: z.array(z.object({ x: z.number(), y: z.number() })).min(2) },
    async (a) => run("drag", undefined, () => { requireForegroundAllowed(); return dragPath(a.path); }));

  // 9. move（FR-010）
  server.tool("move", "移动光标（FR-010）", { x: z.number(), y: z.number() },
    async (a) => run("move", undefined, async () => {
      const r = await moveTo(a.x, a.y);
      return { ...r, pos: await cursorPos() };
    }));

  // 10. wait（FR-011）
  server.tool("wait", "等待秒数（FR-011，≤10）", { seconds: z.number().min(0).max(10).default(2) },
    async (a) => run("wait", undefined, () => waitSeconds(a.seconds)));

  // 11. confirm_app（FR-013）
  server.tool("confirm_app", "白名单外 App 的放行确认（FR-013）", { appId: z.string(), remember: z.boolean().default(true) },
    async (a) => run("confirm_app", a.appId, () => Promise.resolve(confirmApp(a.appId, a.remember))));

  // 12/13. memory_list / memory_forget（升级v2 FR-113）
  server.tool("memory_list", "列出学习记忆锚点（FR-113；不传 app 列全部）", { app: z.string().optional() },
    async (a) => run("memory_list", a.app, () => Promise.resolve({ apps: anchors.list(a.app) })));
  server.tool("memory_forget", "删除记忆锚点（FR-113；id 省略或 all=true 清空该 app 全部）", { app: z.string(), id: z.string().optional(), all: z.boolean().default(false) },
    async (a) => run("memory_forget", a.app, () => Promise.resolve({ removed: anchors.forget(a.app, a.id, a.all) })));
}
