// errMessage 大白话翻译（BUG-P01-01）：底层原始错误不得直接给用户。
import { describe, expect, it } from "vitest";
import { errMessage } from "./ipc";

describe("errMessage 大白话翻译（Step 0 / BUG-P01-01）", () => {
  it("IPC 不可用的原始 JS 错误 → 内核通信大白话", () => {
    expect(
      errMessage({ code: "E", message: "Cannot read properties of undefined (reading 'invoke')" }),
    ).toBe("内核通信未就绪，请重启应用再试");
  });

  it("网络原始错误 → 网络大白话", () => {
    expect(errMessage("Failed to fetch")).toBe("网络连接失败，请检查网络后重试");
  });

  it("内核返回的大白话（如门禁拦截）原样透传", () => {
    expect(errMessage({ code: "TRANSITION", message: "门禁「需求确认」未通过" })).toContain(
      "需求确认",
    );
  });
});
