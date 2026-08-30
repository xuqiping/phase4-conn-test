/**
 * driver/mock.ts —— 内存 MockDriver：单测用（FR-003 AC-005/006 验证定位逻辑）
 * 提供一棵固定假树（记事本样例），动作全部返回 via:"uia" 成功。
 */
import type { PlatformDriver } from "./PlatformDriver.js";
import { DriverError, type ActionResult, type UiNode } from "./types.js";

/** 内存假树：两个同名按钮制造 AMBIGUOUS_MATCH，一个唯一名按钮 */
export const mockTree: UiNode[] = [
  {
    index: 0,
    role: "window",
    name: "无标题 - 记事本",
    automationId: "NotepadWindow",
    bounds: [0, 0, 800, 600],
    actions: [],
    children: [
      { index: 1, role: "menu", name: "文件", automationId: "menuFile", bounds: [10, 5, 50, 25], actions: ["Expand"] },
      { index: 2, role: "button", name: "确定", automationId: "btnOk", bounds: [100, 100, 160, 130], actions: ["Invoke"] },
      { index: 3, role: "button", name: "确定", automationId: "btnOk2", bounds: [200, 100, 260, 130], actions: ["Invoke"] },
      { index: 4, role: "edit", name: "文本框", automationId: "editMain", bounds: [0, 30, 800, 500], actions: ["SetValue"] },
    ],
  },
];

export class MockDriver implements PlatformDriver {
  /** 记录动作调用以便断言 */
  readonly calls: string[] = [];
  /** findElement 实际使用的定位方式（AC-005 断言用） */
  lastMatchedBy: string | null = null;

  private flatten(nodes: UiNode[] = mockTree, out: UiNode[] = []): UiNode[] {
    for (const n of nodes) {
      out.push(n);
      if (n.children) this.flatten(n.children, out);
    }
    return out;
  }

  private flat(): UiNode[] {
    return this.flatten();
  }

  async screenshot() {
    this.calls.push("screenshot");
    // 1x1 假 PNG（非测试重点）
    return { pngBase64: "iVBORw0KGgo=", width: 800, height: 600, elapsedMs: 1 };
  }

  async getTree(opts: { maxDepth?: number }) {
    this.calls.push("getTree");
    const depth = opts.maxDepth ?? 4;
    const prune = (nodes: UiNode[], level: number): UiNode[] =>
      nodes.map((n) => (level >= depth ? { ...n, children: undefined } : { ...n, children: n.children ? prune(n.children, level + 1) : undefined }));
    return { nodes: prune(mockTree, 1), truncated: false, elapsedMs: 1 };
  }

  async findElement(locator: { app: string; by: string; value: unknown }) {
    this.calls.push("findElement");
    const all = this.flat();
    let found: UiNode[] = [];
    let matchedBy = "";
    if (locator.by === "name") {
      found = all.filter((n) => n.name === locator.value);
      matchedBy = "name";
    } else if (locator.by === "automationId") {
      found = all.filter((n) => n.automationId === locator.value);
      matchedBy = "automationId";
    } else if (locator.by === "index") {
      const idx = Number(locator.value);
      found = all.filter((n) => n.index === idx);
      matchedBy = "index";
    } else {
      throw new DriverError("INVALID_ARGUMENT", `xy 定位需坐标对，收到 ${String(locator.value)}`);
    }
    if (found.length === 0) throw new DriverError("ELEMENT_NOT_FOUND", `未找到元素 ${String(locator.value)}`);
    if (found.length > 1) {
      throw new DriverError(
        "AMBIGUOUS_MATCH",
        `名称 ${String(locator.value)} 匹配 ${found.length} 个元素`,
        found.map((f) => ({ index: f.index, automationId: f.automationId, bounds: f.bounds }))
      );
    }
    this.lastMatchedBy = matchedBy;
    return { node: found[0], matchedBy: matchedBy as "name" };
  }

  private ok(): ActionResult {
    return { ok: true, via: "uia", elapsedMs: 1 };
  }

  async click(el: { by: string; value: unknown }) {
    await this.findElement(el as never);
    this.calls.push("click");
    return this.ok();
  }
  async type() {
    this.calls.push("type");
    return this.ok();
  }
  async key() {
    this.calls.push("key");
    return this.ok();
  }
  async scroll() {
    this.calls.push("scroll");
    return this.ok();
  }
  async drag() {
    this.calls.push("drag");
    return this.ok();
  }
  async move() {
    this.calls.push("move");
    return this.ok();
  }
  async wait() {
    this.calls.push("wait");
    return this.ok();
  }
}
