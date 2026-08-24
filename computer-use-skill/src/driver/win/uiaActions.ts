/**
 * driver/win/uiaActions.ts —— UIA 零激活直控（FR-004/005/006/012，ADR-3 层1）
 * 直控失败/不可达时抛 NeedsFallback，由工具层降级 SendInput（层2）。
 */
import type { ActionResult, UiNode } from "../types.js";
import { uiaAct } from "./uia.js";

/** 层1 不可达信号：调用方应降级 SendInput（不是错误，是路由） */
export class NeedsFallback extends Error {
  constructor(public readonly reason: string) {
    super(`UIA 直控不可达：${reason}，降级 SendInput`);
    this.name = "NeedsFallback";
  }
}

const ok = (ms: number): ActionResult => ({ ok: true, via: "uia", elapsedMs: ms });

/** FR-004 单击：按节点 actions[] 可用性选直控原语 */
export async function uiaClick(app: string, el: UiNode, opts: { button?: string; count?: number; keys?: string[] }): Promise<ActionResult> {
  if (opts.button && opts.button !== "left") throw new NeedsFallback("右键无 UIA 直控原语");
  if ((opts.count ?? 1) > 1) throw new NeedsFallback("双击/多击无 UIA 直控原语");
  if (opts.keys && opts.keys.length > 0) throw new NeedsFallback("修饰键点击需真实输入");
  const t0 = Date.now();

  // 修饰键不需要 → 依次尝试可用的直控模式
  for (const pattern of ["Invoke", "Select", "Toggle", "Expand"] as const) {
    if (el.actions.includes(pattern)) {
      await uiaAct(app, "name", el.name || String(el.index), pattern);
      return ok(Date.now() - t0);
    }
  }
  throw new NeedsFallback(`元素 [${el.index}]${el.name} 无可直控模式（actions=${el.actions.join(",") || "空"}）`);
}

/** FR-006 输入：优先 ValuePattern.SetValue（零激活） */
export async function uiaType(app: string, el: UiNode, text: string): Promise<ActionResult> {
  const t0 = Date.now();
  if (el.actions.includes("SetValue")) {
    await uiaAct(app, "name", el.name || String(el.index), "SetValue", text);
    return ok(Date.now() - t0);
  }
  throw new NeedsFallback(`元素 [${el.index}]${el.name} 不支持 SetValue`);
}
