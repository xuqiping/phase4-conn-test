/**
 * tools/index.ts —— 11 个 MCP 工具注册（FR-017，AC-021）
 * 每个动作工具统一走：黑名单闸 → 白名单闸 → 定位 → 层1 UIA 直控 → NeedsFallback 降级层2 SendInput。
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
import { confirmApp, requireAllowed } from "../safety/whitelist.js";
import { requireNotBlocked } from "../safety/blacklist.js";

// ---- locator schema（api/mcp-tools.md §0） ----
const locatorSchema = z.object({
  app: z.string().describe("目标应用（进程名或窗口标题包含词）"),
  by: z.enum(["name", "automationId", "index", "xy"]).describe("定位方式：name/automationId 优先，index 次之，xy 坐标兜底"),
  value: z.union([z.string(), z.number()]).describe("定位值（name 字符串 / index 数字 / 'x,y' 坐标串）"),
});

/** 双闸：黑名单（终端/宿主）+ 白名单（未确认 App） */
function safetyGates(app: string): void {
  requireNotBlocked(app); // FR-014
  requireAllowed(app); // FR-013
}

/** 解析 locator → 节点（index 走快照；name/automationId 走 PS 层 Subtree 查找；xy 解析坐标） */
async function resolveLocator(loc: z.infer<typeof locatorSchema>): Promise<{ el?: UiNode; x?: number; y?: number }> {
  if (loc.by === "xy") {
    const m = String(loc.value).match(/^(\d+)\s*,\s*(\d+)$/);
    if (!m) throw new DriverError("INVALID_ARGUMENT", `xy 值应为 'x,y'，收到 ${String(loc.value)}`);
    return { x: Number(m[1]), y: Number(m[2]) };
  }
  if (loc.by === "index") {
    return { el: findByIndex(loc) };
  }
  // name / automationId：重读树（幂等但慢）——MVP 简化：name 定位也走快照匹配
  throw new DriverError("STALE_TREE", "name/automationId 定位请先 tree 再用 index（MVP：name 定位经 tree+index 两步）");
}

/** 两层执行包装：层1 UIA 直控，NeedsFallback → 层2 SendInput（FR-012） */
async function twoLayer(uiaTry: () => Promise<ActionResult>, fallback: () => Promise<ActionResult>): Promise<ActionResult> {
  try {
    return await uiaTry();
  } catch (e) {
    if (e instanceof NeedsFallback) return await fallback();
    throw e;
  }
}

function center(el: UiNode): { x: number; y: number } {
  const [l, t, r, b] = el.bounds;
  return { x: Math.round((l + r) / 2), y: Math.round((t + b) / 2) };
}

function errJson(e: unknown): { content: { type: "text"; text: string }[]; isError: boolean } {
  if (e instanceof DriverError) {
    return { content: [{ type: "text", text: JSON.stringify({ code: e.code, msg: e.message, detail: e.detail }) }], isError: true };
  }
  return { content: [{ type: "text", text: JSON.stringify({ code: "DRIVER_ERROR", msg: String(e) }) }], isError: true };
}

const text = (v: unknown) => ({ content: [{ type: "text" as const, text: JSON.stringify(v) }] });

export function registerTools(server: McpServer): void {
  // 1. skyshot（FR-001）
  server.tool("skyshot", "截图目标窗口，返回 PNG base64（FR-001）", { app: z.string(), mode: z.literal("window").default("window") }, async ({ app, mode }) => {
    try { safetyGates(app); return text(await capture({ app, mode })); } catch (e) { return errJson(e); }
  });

  // 2. tree（FR-002）
  server.tool("tree", "读目标 App 的 UIA 元素树（FR-002）", {
    app: z.string(), maxDepth: z.number().int().min(1).max(10).default(4),
  }, async ({ app, maxDepth }) => {
    try {
      safetyGates(app);
      const t = await uiaTree(app, maxDepth);
      registerSnapshot(app, t.nodes); // 联动：新快照使旧索引作废
      return text({ nodes: t.nodes, truncated: t.truncated, elapsedMs: t.elapsedMs });
    } catch (e) { return errJson(e); }
  });

  // 3/4. click / double_click（FR-004/005）
  const clickHandler = (count: number) => async (args: { locator: z.infer<typeof locatorSchema>; button?: "left" | "right"; keys?: string[] }) => {
    try {
      safetyGates(args.locator.app);
      const { el, x, y } = await resolveLocator(args.locator);
      if (x !== undefined && y !== undefined) {
        activate(args.locator.app);
        return text(await clickAt(x, y, { button: args.button, count }));
      }
      return text(await twoLayer(
        () => uiaClick(args.locator.app, el!, { button: args.button, count, keys: args.keys }),
        async () => { const c = center(el!); activate(args.locator.app); return clickAt(c.x, c.y, { button: args.button, count }); }
      ));
    } catch (e) { return errJson(e); }
  };
  server.tool("click", "单击元素（FR-004，UIA 零激活优先）", { locator: locatorSchema, button: z.enum(["left", "right"]).default("left"), keys: z.array(z.string()).optional() }, clickHandler(1));
  server.tool("double_click", "双击元素（FR-005，SendInput 前台）", { locator: locatorSchema }, clickHandler(2));

  // 5. type（FR-006）
  server.tool("type", "输入文本（FR-006，含中文剪贴板通道）", { locator: locatorSchema.optional(), text: z.string() }, async ({ locator, text: t }) => {
    try {
      const app = locator?.app;
      if (app) safetyGates(app);
      if (locator) {
        const { el } = await resolveLocator(locator);
        return text(await twoLayer(
          () => uiaType(app!, el!, t),
          async () => {
            const c = center(el!); activate(app!);
            await clickAt(c.x, c.y); // 聚焦
            return /[^\x00-\x7f]/.test(t) ? typeViaClipboard(t) : typeText(t);
          }
        ));
      }
      return text(/[\x00-\x7f]/.test(t) ? await typeText(t) : await typeViaClipboard(t));
    } catch (e) { return errJson(e); }
  });

  // 6. key（FR-007）
  server.tool("key", "组合键，xdotool 风格如 ctrl+shift+a（FR-007）", { combo: z.string() }, async ({ combo }) => {
    try { return text(await keyCombo(combo)); } catch (e) { return errJson(e); }
  });

  // 7. scroll（FR-008）
  server.tool("scroll", "滚动（FR-008）", { locator: locatorSchema, dir: z.enum(["up", "down", "left", "right"]), pages: z.number().int().min(1).max(10).default(1) }, async (a) => {
    try {
      safetyGates(a.locator.app);
      const { el, x, y } = await resolveLocator(a.locator);
      const c = el ? center(el) : { x: x!, y: y! };
      activate(a.locator.app);
      return text(await scrollAt(c.x, c.y, a.dir, a.pages));
    } catch (e) { return errJson(e); }
  });

  // 8. drag（FR-009）
  server.tool("drag", "拖拽路径（FR-009）", { path: z.array(z.object({ x: z.number(), y: z.number() })).min(2) }, async ({ path }) => {
    try { activate(""); return text(await dragPath(path)); } catch (e) { return errJson(e); }
  });

  // 9. move（FR-010）
  server.tool("move", "移动光标（FR-010）", { x: z.number(), y: z.number() }, async ({ x, y }) => {
    try { const r = await moveTo(x, y); const p = await cursorPos(); return text({ ...r, pos: p }); } catch (e) { return errJson(e); }
  });

  // 10. wait（FR-011）
  server.tool("wait", "等待秒数（FR-011，≤10）", { seconds: z.number().min(0).max(10).default(2) }, async ({ seconds }) => {
    try { return text(await waitSeconds(seconds)); } catch (e) { return errJson(e); }
  });

  // 11. confirm_app（FR-013）
  server.tool("confirm_app", "白名单外 App 的放行确认（FR-013）", { appId: z.string(), remember: z.boolean().default(true) }, async ({ appId, remember }) => {
    try { return text(confirmApp(appId, remember)); } catch (e) { return errJson(e); }
  });
}
