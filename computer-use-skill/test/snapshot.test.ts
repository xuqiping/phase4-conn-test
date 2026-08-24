// 树快照单测（FR-003 STALE_TREE 逻辑）
import { describe, it, expect } from "vitest";
import { registerSnapshot, findByIndex, snapshotAge } from "../src/driver/snapshot.js";

const tree = [
  { index: 0, role: "window", name: "A", bounds: [0, 0, 10, 10] as [number, number, number, number], actions: [],
    children: [{ index: 1, role: "button", name: "B", bounds: [1, 1, 2, 2] as [number, number, number, number], actions: ["Invoke"] }] },
];

describe("snapshot（FR-003）", () => {
  it("index 定位命中子节点", () => {
    registerSnapshot("a.exe", tree);
    expect(findByIndex({ app: "a.exe", by: "index", value: 1 }).name).toBe("B");
  });

  it("无快照 / app 不符 / 索引不存在 → STALE_TREE / ELEMENT_NOT_FOUND", () => {
    registerSnapshot("a.exe", tree);
    expect(() => findByIndex({ app: "a.exe", by: "index", value: 99 })).toThrowError(
      expect.objectContaining({ code: "ELEMENT_NOT_FOUND" })
    );
    expect(() => findByIndex({ app: "other.exe", by: "index", value: 1 })).toThrowError(
      expect.objectContaining({ code: "STALE_TREE" })
    );
  });

  it("年龄计数生效", () => {
    registerSnapshot("a.exe", tree);
    expect(snapshotAge()).toBeLessThan(1000);
  });
});
