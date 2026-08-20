// 结构化生成管线：把 prompt 模板 + 上下文 → JSON 产物，支持主动反问澄清（FR-030/031/032/044）。
// 默认后端走云端 /gateway/complete；测试可注入 mock 后端。

import { chatComplete, type ChatCompleteResult, type ChatMessage } from "./cloudApi";
import type { AgentConfigFields } from "./ipc";

export interface GenerationResult<T> {
  /** 解析后的结构化产物 */
  content: T;
  /** 若模型觉得信息不足，先返回追问，不继续生成 */
  clarifyingQuestions: string[];
  /** 本次调用消耗（分） */
  costCents: number;
}

export type GenerateBackend = (messages: ChatMessage[]) => Promise<ChatCompleteResult>;

const DEFAULT_MODEL_BACKEND: GenerateBackend = (messages) => chatComplete({ messages });

function systemBase(agent: AgentConfigFields): string {
  return `你是 DevPilot，一个帮用户把想法变成可运行项目的 AI 助手。\n\
项目定位：${agent.positioning}\n\
目标用户：${agent.target_users}\n\
技术栈偏好：${agent.tech_stack}\n\
命名风格：${agent.naming_style}\n\
提交规范：${agent.commit_style}\n\
安全红线：${agent.security_redlines}\n\
文档要求：${agent.doc_requirements}\n\
测试红线：${agent.testing_redlines}\n\
\n\
你必须严格按用户要求的 JSON schema 输出。\n\
如果信息不足以给出可靠结果，禁止自行假设，必须在 JSON 里输出 clarifying_questions 数组（每个元素是一个大白话追问）。\n\
如果信息足够，clarifying_questions 必须为空数组。\n\
只输出 JSON，不要 markdown 代码块包裹。`;
}

function extractJson(text: string): unknown {
  const cleaned = text.trim();
  // 尝试直接解析
  try {
    return JSON.parse(cleaned);
  } catch {
    // 尝试提取 ```json ... ``` 或第一个 { ... }
  }
  const codeBlock = /```(?:json)?\s*([\s\S]*?)```/.exec(cleaned);
  if (codeBlock) {
    try {
      return JSON.parse(codeBlock[1]);
    } catch {
      // fallthrough
    }
  }
  const firstObject = /\{[\s\S]*\}/.exec(cleaned);
  if (firstObject) {
    try {
      return JSON.parse(firstObject[0]);
    } catch {
      // fallthrough
    }
  }
  throw new Error("模型返回不是合法 JSON，请重试或换个更清晰的描述");
}

function parseWithClarify<T>(raw: string): { content: T; clarifyingQuestions: string[] } {
  const parsed = extractJson(raw) as Record<string, unknown>;
  const questions: string[] = Array.isArray(parsed.clarifying_questions)
    ? (parsed.clarifying_questions as string[]).filter((q) => typeof q === "string")
    : [];
  if (questions.length > 0) {
    return { content: {} as T, clarifyingQuestions: questions };
  }
  return { content: parsed as T, clarifyingQuestions: [] };
}

async function generate<T>(
  agent: AgentConfigFields,
  userPrompt: string,
  backend: GenerateBackend = DEFAULT_MODEL_BACKEND,
): Promise<GenerationResult<T>> {
  const messages: ChatMessage[] = [
    { role: "user", content: systemBase(agent) },
    { role: "user", content: userPrompt },
  ];
  const res = await backend(messages);
  const parsed = parseWithClarify<T>(res.content);
  return {
    content: parsed.content,
    clarifyingQuestions: parsed.clarifyingQuestions,
    costCents: res.cost_cents,
  };
}

export interface IdeaReport {
  recommendation: "worth_doing" | "narrow_down" | "rethink";
  summary: {
    target_user: string;
    pain: string;
    competitors: string;
    monetization: string;
    tech_constraint: string;
  };
  report_md: string;
}

export interface SpecCardDraft {
  title: string;
  detail: string;
  ac: string[];
}

export interface PlanChunkDraft {
  title: string;
  goal: string;
  estimated_tokens: number;
  dependencies: string[];
}

/** 想法访谈 → 项目分析报告（FR-030/AC-033） */
export function generateIdeaReport(
  qa: Record<string, string>,
  agent: AgentConfigFields,
  backend?: GenerateBackend,
) {
  const userPrompt = `请根据以下想法访谈回答，生成项目分析报告。\n\
\n\
访谈回答（JSON）：\n${JSON.stringify(qa, null, 2)}\n\
\n\
请输出 JSON，schema 如下：\n\
{\n\
  "recommendation": "worth_doing|narrow_down|rethink",\n\
  "summary": { "target_user": "...", "pain": "...", "competitors": "...", "monetization": "...", "tech_constraint": "..." },\n\
  "report_md": "项目分析报告正文（markdown，含市场/用户/竞品/技术/风险摘要）",\n\
  "clarifying_questions": []\n\
}`;
  return generate<IdeaReport>(agent, userPrompt, backend);
}

/** 分析报告 → 需求卡列表（FR-031） */
export function generateSpecCards(
  reportMd: string,
  agent: AgentConfigFields,
  backend?: GenerateBackend,
) {
  const userPrompt = `请根据以下项目分析报告，拆成需求确认卡片。\n\
\n\
报告内容：\n${reportMd}\n\
\n\
输出 JSON schema：\n\
{\n\
  "cards": [{ "title": "大白话标题", "detail": "需求细节", "ac": ["验收标准1", "验收标准2"] }],\n\
  "clarifying_questions": []\n\
}`;
  return generate<{ cards: SpecCardDraft[] }>(agent, userPrompt, backend);
}

/** 需求卡 → 施工计划 chunk（FR-032） */
export function generatePlanChunks(
  cards: SpecCardDraft[],
  agent: AgentConfigFields,
  backend?: GenerateBackend,
) {
  const userPrompt = `请根据以下已确认的需求卡，生成 chunk 级施工计划。\n\
每个 chunk 应是一个可独立完成的小任务。\n\
\n\
需求卡（JSON）：\n${JSON.stringify(cards, null, 2)}\n\
\n\
输出 JSON schema：\n\
{\n\
  "chunks": [{ "title": "chunk 标题", "goal": "目标与交付物", "estimated_tokens": 1200, "dependencies": ["依赖 chunk 标题"] }],\n\
  "clarifying_questions": []\n\
}`;
  return generate<{ chunks: PlanChunkDraft[] }>(agent, userPrompt, backend);
}

/** 导出解析工具，供测试和命令层复用 */
export const __testing = { extractJson, parseWithClarify };
