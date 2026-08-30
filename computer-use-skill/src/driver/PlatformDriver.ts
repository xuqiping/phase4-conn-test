/**
 * driver/PlatformDriver.ts —— 平台抽象接口（architecture §4.1.1）
 * 唯一平台抽象点：Windows 实现在 src/driver/win/，测试用 src/driver/mock.ts。
 * FFI 调用绝不允许出现在本文件之下以外的层。
 */
import type {
  ActionResult,
  ClickOpts,
  FindResult,
  Locator,
  Screenshot,
  ScreenshotOpts,
  TreeOpts,
  TreeResult,
} from "./types.js";

export interface PlatformDriver {
  /** FR-001 */
  screenshot(opts: ScreenshotOpts): Promise<Screenshot>;
  /** FR-002 */
  getTree(opts: TreeOpts): Promise<TreeResult>;
  /** FR-003 */
  findElement(locator: Locator): Promise<FindResult>;
  /** FR-004/005 */
  click(el: Locator, opts?: ClickOpts): Promise<ActionResult>;
  /** FR-006 */
  type(el: Locator | null, text: string): Promise<ActionResult>;
  /** FR-007：xdotool 风格组合键，如 "ctrl+shift+a" */
  key(combo: string): Promise<ActionResult>;
  /** FR-008 */
  scroll(el: Locator | null, dir: "up" | "down" | "left" | "right", pages?: number): Promise<ActionResult>;
  /** FR-009 */
  drag(path: Array<{ x: number; y: number }>): Promise<ActionResult>;
  /** FR-010 */
  move(x: number, y: number): Promise<ActionResult>;
  /** FR-011 */
  wait(seconds: number): Promise<ActionResult>;
}
