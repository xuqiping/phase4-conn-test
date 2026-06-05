# AGENTS.md — Task Routing Table

## Agent: 超级程序员_人工智能与大模型Agent

本文件是任务路由的唯一真相来源。当用户向本子Agent提出请求时，读取此表以确定加载哪个细粒度工作流。

## Routing Table

| 任务关键词 / 意图 | 工作流文件 | 描述 |
|------------------|-----------|------|
| 机器学习,深度学习,神经网络,概率统计,AI理论,数学基础,CNN,RNN,Transformer,Diffusion,贝叶斯,A/B测试,模型评估,交叉验证,面试辅导,算法优化,生成模型 | workflow/ai_fundamentals_workflow.md | 基础AI理论：机器学习（监督/无监督/强化）、深度学习（CNN/RNN/Transformer/Diffusion）、神经网络基础（感知机→GNN/SNN）、概率统计（贝叶斯/A/B测试/概率校准） |
| 大模型,LLM,预训练,Transformer,Scaling Law,MoE,Prompt工程,上下文工程,思维链,CoT,RAG,检索增强,Agent,智能体,MCP协议,工具调用,LoRA,QLoRA,微调,多模态,GPT-4V,Claude,Gemini | workflow/llm_technology_workflow.md | 大模型技术体系：预训练原理（Transformer/MoE/分布式训练）、Prompt工程（上下文工程/推理模型）、RAG（Agentic RAG）、Agent（LLM+工具+记忆+规划+MCP）、微调（LoRA/QLoRA/全量）、多模态（VLM） |
| AI应用,企业知识库,办公自动化,Copilot,Autopilot,Computer Use Agent,行业大模型,领域定制,AI创业,商业化,SaaS,API,私有化,结果付费,出海,Cursor,PLG,PMF,变现 | workflow/ai_application_workflow.md | AI应用落地：企业知识库（RAG激活）、办公自动化（Copilot→Autopilot）、行业大模型定制（五步法）、AI创业商业化（六大模式+出海策略） |
| YOLO,目标检测,图像分割,SAM,语音识别,ASR,语音合成,TTS,AIGC,文生图,文生视频,DiT,ControlNet,可灵,即梦,Stable Diffusion,多模态VLM,计算机视觉,语音处理,内容生成,水印,GB 45438-2025 | workflow/cv_speech_workflow.md | 计算机视觉与语音：YOLO实时检测、SAM2/3万物分割、SpeechLLM端到端语音、AIGC生成（DiT/可灵/即梦）、多模态VLM、合规水印 |

## Notes

- 本子Agent处理所有与人工智能、大模型、机器学习、计算机视觉、语音处理相关的请求。
- 关键词覆盖范围要足够广以捕获同义/转述请求。
- 新增能力时在此表添加新行，并创建对应 workflow + checklist 文件对。

## Cross-Module Dependencies

- `llm_technology_workflow.md` Step 2 中的RAG向量检索可能引用 [参考: Agents知识库/0_超级编程行业知识库/03_数据库与数据存储.md > NoSQL数据库 > 向量数据库]（Milvus/Weaviate/PG Vector选型）
- `ai_application_workflow.md` Step 2 中的企业知识库部署可能引用 [参考: Agents知识库/0_超级编程行业知识库/04_前端开发与用户交互.md > 容器技术 > Kubernetes]（K8s部署RAG服务）
- `cv_speech_workflow.md` Step 2 中的端侧部署可能引用 [参考: Agents知识库/0_超级编程行业知识库/08_嵌入式与物联网.md > 嵌入式开发 > 边缘AI]（Jetson/瑞芯微NPU部署）
- `llm_technology_workflow.md` Step 2 中的模型部署推理优化可能引用 [参考: Agents知识库/0_超级编程行业知识库/04_前端开发与用户交互.md > 云原生生态 > FinOps]（GPU虚拟化MIG/vGPU+Karpenter弹性推理）
