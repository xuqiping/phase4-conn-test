/**
 * driver/snapshot.ts —— 树快照管理（FR-003）
 * tree 调用后缓存展平节点表（index→node），60s TTL；
 * 过期或 UI 已变 → STALE_TREE，要求 Agent 重新 tree。
 */
import { DriverError, type Locator, type UiNode } from "./types.js";

const TTL_MS = 60_000;

interface Snapshot {
  app: string;
  at: number;
  byIndex: Map<number, UiNode>;
}

let current: Snapshot | null = null;

function flatten(n: UiNode, out: Map<number, UiNode>): void {
  out.set(n.index, n);
  for (const c of n.children ?? []) flatten(c, out);
}

/** tree 成功后登记快照（联动点：新快照使旧索引全作废） */
export function registerSnapshot(app: string, nodes: UiNode[]): void {
  const byIndex = new Map<number, UiNode>();
  for (const n of nodes) flatten(n, byIndex);
  current = { app, at: Date.now(), byIndex };
}

/** index 定位（FR-003 第二级）：校验 TTL 与 app 一致 */
export function findByIndex(locator: Locator): UiNode {
  if (!current) throw new DriverError("STALE_TREE", "尚无树快照，请先调用 tree");
  if (Date.now() - current.at > TTL_MS) {
    current = null;
    throw new DriverError("STALE_TREE", "树快照已过期（60s），请重新调用 tree");
  }
  if (current.app !== locator.app) {
    throw new DriverError("STALE_TREE", `快照属于 ${current.app}，与目标 ${locator.app} 不符，请重新 tree`);
  }
  const idx = Number(locator.value);
  const node = current.byIndex.get(idx);
  if (!node) throw new DriverError("ELEMENT_NOT_FOUND", `索引 ${idx} 不在当前快照`);
  return node;
}

export function snapshotAge(): number {
  return current ? Date.now() - current.at : Infinity;
}
