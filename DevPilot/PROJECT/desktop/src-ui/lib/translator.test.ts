// 大白话翻译层测试（FR-036 / AC-015）。
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("./cloudApi", () => ({
  chatComplete: vi.fn(),
}));

import { chatComplete } from "./cloudApi";
import { clearTranslateCache, explainTerm, GLOSSARY, translate } from "./translator";

beforeEach(() => {
  vi.mocked(chatComplete).mockReset();
  clearTranslateCache();
});
afterEach(() => {
  clearTranslateCache();
});

describe("translator（FR-036）", () => {
  it("explainTerm 本地术语表大小写不敏感", () => {
    expect(explainTerm("LLM")).toBe(GLOSSARY.llm);
    expect(explainTerm("checkpoint")).toBe(GLOSSARY.checkpoint);
    expect(explainTerm("未知术语")).toBeNull();
  });

  it("空文本直接返回", async () => {
    expect(await translate("")).toBe("");
    expect(await translate("   ")).toBe("   ");
    expect(chatComplete).not.toHaveBeenCalled();
  });

  it("命中缓存不重复调用模型", async () => {
    vi.mocked(chatComplete).mockResolvedValueOnce({ content: "  人话  ", cost_cents: 1, capped: false });
    const first = await translate("error: timeout");
    const second = await translate("error: timeout");
    expect(first).toBe("人话");
    expect(second).toBe("人话");
    expect(chatComplete).toHaveBeenCalledTimes(1);
  });

  it("未命中缓存时走 cheap 模型并返回整理后内容", async () => {
    vi.mocked(chatComplete).mockResolvedValueOnce({ content: "  这是人话  ", cost_cents: 1, capped: false });
    const out = await translate("Segmentation fault", "报错");
    expect(out).toBe("这是人话");
    expect(chatComplete).toHaveBeenCalledWith(
      expect.objectContaining({
        model: "claude-haiku-4",
        messages: expect.arrayContaining([
          expect.objectContaining({ role: "user" }),
        ]),
      }),
    );
  });

  it("模型失败时回退原文，不抛错", async () => {
    vi.mocked(chatComplete).mockRejectedValueOnce(new Error("net"));
    const out = await translate("fatal: repository not found");
    expect(out).toBe("fatal: repository not found");
  });
});
