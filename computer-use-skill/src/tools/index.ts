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
import { confirmApp, requireAllowed } from "../safety/whitelist.js";
import { requireNotBlocked } from "../safety/blacklist.js";
import { audit, redact } from "../safety/audit.js";

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

  // 3/4. click / double_click（FR-004/005）
  const clickHandler = (count: number) => async (a: { locator: z.infer<typeof locatorSchema>; button?: "left" | "right"; keys?: string[] }) =>
    run(count > 1 ? "double_click" : "click", a.locator.app, async () => {
      safetyGates(a.locator.app);
      const { el, x, y } = await resolveLocator(a.locator);
      if (x !== undefined && y !== undefined) {
        activate(a.locator.app);
        return clickAt(x, y, { button: a.button, count });
      }
      return twoLayer(
        () => uiaClick(a.locator.app, el!, { button: a.button, count, keys: a.keys }),
        async () => { const c = center(el!); activate(a.locator.app); return clickAt(c.x, c.y, { button: a.button, count }); }
      );
    });
  server.tool("click", "单击元素（FR-004，UIA 零激活优先）", { locator: locatorSchema, button: z.enum(["left", "right"]).default("left"), keys: z.array(z.string()).optional() }, clickHandler(1));
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
      return /[\x00-\x7f]/.test(a.text) ? typeText(a.text) : typeViaClipboard(a.text);
    }));

  // 6. key（FR-007）
  server.tool("key", "组合键，xdotool 风格如 ctrl+shift+a（FR-007）", { combo: z.string() },
    async (a) => run("key", undefined, () => keyCombo(a.combo)));

  // 7. scroll（FR-008）
  server.tool("scroll", "滚动（FR-008）", { locator: locatorSchema, dir: z.enum(["up", "down", "left", "right"]), pages: z.number().int().min(1).max(10).default(1) },
    async (a) => run("scroll", a.locator.app, async () => {
      safetyGates(a.locator.app);
      const { el, x, y } = await resolveLocator(a.locator);
      const c = el ? center(el) : { x: x!, y: y! };
      activate(a.locator.app);
      return scrollAt(c.x, c.y, a.dir, a.pages);
    }));

  // 8. drag（FR-009）
  server.tool("drag", "拖拽路径（FR-009，至少 2 点）", { path: z.array(z.object({ x: z.number(), y: z.number() })).min(2) },
    async (a) => run("drag", undefined, () => dragPath(a.path)));

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
}
