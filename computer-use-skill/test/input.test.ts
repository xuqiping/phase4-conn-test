// keymap 单测（FR-007，AC-008/011 逻辑层）
import { describe, it, expect } from "vitest";
import { parseCombo, VK } from "../src/driver/win/keymap.js";

describe("parseCombo（FR-007）", () => {
  it("ctrl+shift+a → 3按下+3逆序释放", () => {
    const ops = parseCombo("ctrl+shift+a");
    expect(ops).toHaveLength(6);
    expect(ops.slice(0, 3).every((o) => o.down)).toBe(true);
    expect(ops.slice(3).every((o) => !o.down)).toBe(true);
    expect(ops[0].vk).toBe(VK.ctrl);
    expect(ops[2].vk).toBe(0x41); // 'A'
    // 逆序释放
    expect(ops[5].vk).toBe(VK.ctrl);
  });

  it("AC-008: 未知按键抛错", () => {
    expect(() => parseCombo("ctrl+不存在键")).toThrowError(/未知按键/);
    expect(() => parseCombo("")).toThrowError();
  });

  it("单键 return 映射 Enter VK", () => {
    expect(parseCombo("return")[0].vk).toBe(0x0d);
  });
});
