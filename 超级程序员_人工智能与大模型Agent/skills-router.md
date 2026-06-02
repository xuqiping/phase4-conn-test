# skills-router.md — Skill Router

## Top-Level Skill
- **Name**: `super_programmer_ai_ml_agent_skill`
- **Purpose**: 执行人工智能与大模型领域的具体任务。
- **Skill File Path**: `all_agents/超级程序员_人工智能与大模型Agent/skills-router.md`

## Derivative Skills
| Skill Name | Purpose | Workflow File | Notes |
|------------|---------|---------------|-------|
| `super_programmer_ai_ml_agent_skill___ai_fundamentals` | 基础AI理论执行 | workflow/ai_fundamentals_workflow.md | AI基础理论：机器学习、深度学习、神经网络、概率论与统计 |
| `super_programmer_ai_ml_agent_skill___llm_technology` | 大模型技术体系执行 | workflow/llm_technology_workflow.md | 大模型技术体系：预训练、Prompt工程、RAG、Agent、微调、多模态 |
| `super_programmer_ai_ml_agent_skill___ai_application` | AI应用落地执行 | workflow/ai_application_workflow.md | AI应用落地：企业知识库、AI办公、行业大模型定制、AI创业商业化 |
| `super_programmer_ai_ml_agent_skill___cv_speech` | 计算机视觉与语音执行 | workflow/cv_speech_workflow.md | 计算机视觉与语音：YOLO、图像分割、语音识别、AIGC绘画视频生成 |

## Knowledge Base Link
- **Base Path**: `Agents知识库/0_超级编程行业知识库`
- **Main Index**: `Agents知识库/0_超级编程行业知识库/00_总索引.md`
- **Module Main File**: `Agents知识库/0_超级编程行业知识库/05_人工智能与机器学习.md`
- **Detail Directory**: `Agents知识库/0_超级编程行业知识库/人工智能与机器学习/`

## Evolution Rules
1. 新增能力时先检查是否已有同名衍生技能；如有则更新，如无则新建。
2. 工作流中禁止嵌入知识库全文，一律使用 `[参考: <path>]` 引用。
3. 若知识库原文更新，子Agent下次执行时自动读取最新内容。