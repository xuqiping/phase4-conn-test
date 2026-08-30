// FR-003 三级定位逻辑单测（AC-005/006）
import { describe, it, expect } from "vitest";
import { MockDriver } from "../src/driver/mock.js";
import { DriverError } from "../src/driver/types.js";

const d = () => new MockDriver();

describe("MockDriver.findElement 三级定位", () => {
  it("AC-005: 按名称唯一定位时不使用坐标直投，matchedBy=name", async () => {
    const drv = d();
    const r = await drv.findElement({ app: "notepad.exe", by: "name", value: "文件" });
    expect(r.matchedBy).toBe("name");
    expect(drv.lastMatchedBy).toBe("name");
  });

  it("AC-006: 名称匹配多个元素时返回 AMBIGUOUS_MATCH 并附候选列表", async () => {
    const drv = d();
    await expect(drv.findElement({ app: "notepad.exe", by: "name", value: "确定" })).rejects.toMatchObject({
      code: "AMBIGUOUS_MATCH",
      detail: expect.arrayContaining([
        expect.objectContaining({ index: 2 }),
        expect.objectContaining({ index: 3 }),
      ]),
    });
  });

  it("automationId 定位唯一元素", async () => {
    const r = await d().findElement({ app: "notepad.exe", by: "automationId", value: "btnOk" });
    expect(r.node.index).toBe(2);
  });

  it("index 定位与 ELEMENT_NOT_FOUND / INVALID_ARGUMENT 错误路径", async () => {
    const drv = d();
    const r = await drv.findElement({ app: "notepad.exe", by: "index", value: 4 });
    expect(r.node.role).toBe("edit");
    await expect(drv.findElement({ app: "notepad.exe", by: "index", value: 99 })).rejects.toBeInstanceOf(DriverError);
    await expect(drv.findElement({ app: "notepad.exe", by: "xy", value: "bad" })).rejects.toMatchObject({
      code: "INVALID_ARGUMENT",
    });
  });
});

describe("MockDriver.getTree 裁剪（AC-004 前置）", () => {
  it("maxDepth=1 时只返回根层（子节点被裁）", async () => {
    const r = await d().getTree({ app: "notepad.exe", maxDepth: 1 });
    expect(r.nodes).toHaveLength(1);
    expect(r.nodes[0].children).toBeUndefined();
  });
});
