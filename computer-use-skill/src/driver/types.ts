/**
 * driver/types.ts —— 平台无关的类型定义（FR-003/012）
 * 错误码与 architecture §4.1.3 一一对应。
 */

/** 统一错误码（architecture §4.1.3） */
export type DriverErrorCode =
  | "APP_NOT_FOUND"
  | "ELEMENT_NOT_FOUND"
  | "AMBIGUOUS_MATCH"
  | "STALE_TREE"
  | "CONFIRMATION_REQUIRED"
  | "TARGET_BLOCKED"
  | "INVALID_ARGUMENT"
  | "DRIVER_ERROR"
  | "DRIVER_TIMEOUT";

export class DriverError extends Error {
  constructor(
    public readonly code: DriverErrorCode,
    message: string,
    /** 附加上下文：候选窗口/元素列表、底层错误信息（已脱敏）等 */
    public readonly detail?: unknown
  ) {
    super(`[${code}] ${message}`);
    this.name = "DriverError";
  }
}

/** UIA 元素节点（FR-002）：tree 返回的树节点结构 */
export interface UiNode {
  /** 树内自增索引，用于 index 定位（FR-003） */
  index: number;
  /** UIA 角色：button/document/text/edit/... */
  role: string;
  name: string;
  automationId?: string;
  /** 屏幕坐标 [left, top, right, bottom]（DPI 感知后） */
  bounds: [number, number, number, number];
  /** 该节点支持的直控动作（Invoke/Expand/Toggle/Select/SetValue），空=只能坐标兜底 */
  actions: string[];
  children?: UiNode[];
}

/** 截图结果（FR-001） */
export interface Screenshot {
  pngBase64: string;
  width: number;
  height: number;
  elapsedMs: number;
}

/** 树结果（FR-002）；truncated=true 表示超出子节点上限被裁剪（防深树卡死） */
export interface TreeResult {
  nodes: UiNode[];
  truncated: boolean;
  elapsedMs: number;
}

/** 元素定位（FR-003 三级优先：name/automationId > index > 坐标） */
export interface Locator {
  app: string;
  by: "name" | "automationId" | "index" | "xy";
  value: string | number;
}

/** 动作执行结果；via 标记走哪一层（FR-012；升级v2 增 postmessage/memory） */
export interface ActionResult {
  ok?: boolean;
  via: "uia" | "sendinput" | "postmessage" | "memory";
  elapsedMs?: number;
  detail?: string;
  [k: string]: unknown;
}

export interface FindResult {
  node: UiNode;
  /** 定位方式（日志/断言用，FR-003 AC-005） */
  matchedBy: "name" | "automationId" | "index" | "xy";
}

export interface ScreenshotOpts {
  /** 省略则全屏 */
  app?: string;
  mode: "window" | "full";
}

export interface TreeOpts {
  app: string;
  maxDepth?: number;
  roleFilter?: string[];
}

export interface ClickOpts {
  button?: "left" | "right";
  count?: number;
  /** 修饰键（Ctrl+click 等，FR-004） */
  keys?: string[];
}
