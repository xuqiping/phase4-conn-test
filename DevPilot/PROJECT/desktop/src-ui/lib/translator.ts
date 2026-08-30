// 大白话翻译层（FR-036）。
// 先查本地术语表，未命中再走云端 cheap 模型翻译。

import { chatComplete } from "./cloudApi";

/** 本地术语表：key 大小写不敏感。 */
export const GLOSSARY: Record<string, string> = {
  commit: "Git 提交：把当前代码快照永久保存到版本库",
  diff: "代码差异：两次提交之间的改动对比",
  lint: "静态检查：自动扫描代码风格和潜在错误",
  test: "自动化测试：验证代码行为是否符合预期",
  runner: "任务执行器：按序安装依赖、跑测试、修复并提交",
  checkpoint: "存档点：一次 task 完成后生成的 git commit，可回滚",
  token: "模型计费单位；按输入+输出字数折算",
  sandbox: "沙箱：限制 AI 只能读写项目目录的安全边界",
  llm: "大语言模型，即 DevPilot 调用的 AI",
  api: "程序接口：前后端或两个服务之间的通信约定",
  stderr: "标准错误输出：程序运行时的错误日志",
  stdout: "标准输出：程序正常运行时打印的信息",
  error: "错误：程序没有按预期执行",
  warning: "警告：可能不会立即导致失败，但需要留意",
  build: "建造阶段：AI 根据审批后的计划自动写代码并跑测试",
  deploy: "部署阶段：把通过验收的代码发布到线上环境",
};

/** 解释单个术语；本地命中直接返回，否则返回 null。 */
export function explainTerm(term: string): string | null {
  const key = term.trim().toLowerCase();
  return GLOSSARY[key] ?? null;
}

const TRANSLATE_CACHE = new Map<string, string>();

function cacheKey(text: string, context?: string): string {
  return `${context ?? ""}::${text}`;
}

/**
 * 把技术文本翻译成人话。
 * - 空文本直接返回。
 * - 优先读内存缓存。
 * - 未命中时走 cheap 模型（haiku），返回翻译结果并缓存。
 */
export async function translate(text: string, context?: string): Promise<string> {
  const trimmed = text.trim();
  if (!trimmed) return text;
  const key = cacheKey(trimmed, context);
  if (TRANSLATE_CACHE.has(key)) return TRANSLATE_CACHE.get(key)!;

  const prompt = `你是 DevPilot 的大白话翻译助手。请把下面的技术文本翻译成非技术人员也能看懂的简短中文（大白话）。如果是报错信息，请说明大概原因和可尝试的解决办法。

${context ? `上下文：${context}\n` : ""}文本：
"""
${trimmed}
"""

只输出翻译结果，不要 markdown 代码块，不要解释你是如何翻译的。`;

  try {
    const res = await chatComplete({
      model: "claude-haiku-4",
      messages: [{ role: "user", content: prompt }],
    });
    const plain = res.content.trim();
    TRANSLATE_CACHE.set(key, plain);
    return plain;
  } catch {
    // 模型失败时回退原文，避免界面卡死（plan 降级路径）。
    return trimmed;
  }
}

/** 清空翻译缓存（测试/切换账号时用）。 */
export function clearTranslateCache(): void {
  TRANSLATE_CACHE.clear();
}
