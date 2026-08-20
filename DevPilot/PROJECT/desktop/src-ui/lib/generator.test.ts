// P04 S2 验收：结构化生成管线解析逻辑（后端调用走 cloudApi，这里只测解析与追问分支）。
import { describe, expect, it } from "vitest";
import { generateIdeaReport, generatePlanChunks, generateSpecCards, __testing } from "./generator";
import type { AgentConfigFields } from "./ipc";

const agent: AgentConfigFields = {
  positioning: "测试项目",
  target_users: "测试用户",
  tech_stack: "TypeScript",
  commit_style: "中文",
  security_redlines: "无",
  doc_requirements: "无",
  testing_redlines: "无",
  naming_style: "英文",
};

const mockBackend = (content: string) => async () => ({ content, cost_cents: 100, capped: false });

describe("generator 解析（FR-030/031/032/044）", () => {
  it("extractJson 能去掉 markdown 代码块", () => {
    const raw = "```json\n{\"a\":1}\n```";
    expect(__testing.extractJson(raw)).toEqual({ a: 1 });
  });

  it("extractJson 失败时抛大白话错误", () => {
    expect(() => __testing.extractJson("不是 json")).toThrow("合法 JSON");
  });

  it("模型返回追问时，不返回 content 而返回问题列表（AC-048）", async () => {
    const res = await generateIdeaReport(
      { target: "年轻人", pain: "提高效率" },
      agent,
      mockBackend('{"clarifying_questions": ["提高效率指哪方面？"]}'),
    );
    expect(res.clarifyingQuestions).toEqual(["提高效率指哪方面？"]);
    expect(res.costCents).toBe(100);
  });

  it("想法报告生成解析正确（AC-033）", async () => {
    const res = await generateIdeaReport(
      { target: "老人", pain: "忘吃药" },
      agent,
      mockBackend(
        '{"recommendation":"worth_doing","summary":{"target_user":"老人","pain":"忘吃药","competitors":"无","monetization":"订阅","tech_constraint":"小程序"},"report_md":"# 报告"}',
      ),
    );
    expect(res.content.recommendation).toBe("worth_doing");
    expect(res.content.report_md).toContain("# 报告");
    expect(res.clarifyingQuestions).toEqual([]);
  });

  it("需求卡生成解析正确（FR-031）", async () => {
    const res = await generateSpecCards(
      "# 报告\n做提醒小程序",
      agent,
      mockBackend('{"cards":[{"title":"登录","detail":"手机号登录","ac":["验证码有效"]}]}'),
    );
    expect(res.content.cards).toHaveLength(1);
    expect(res.content.cards[0].title).toBe("登录");
  });

  it("施工计划 chunk 生成解析正确（FR-032）", async () => {
    const res = await generatePlanChunks(
      [{ title: "登录", detail: "", ac: [] }],
      agent,
      mockBackend(
        '{"chunks":[{"title":"实现登录 API","goal":"提供登录","estimated_tokens":1200,"dependencies":[]}]}',
      ),
    );
    expect(res.content.chunks).toHaveLength(1);
    expect(res.content.chunks[0].estimated_tokens).toBe(1200);
  });
});
