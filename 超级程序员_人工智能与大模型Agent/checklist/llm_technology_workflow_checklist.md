# LLM Technology Workflow Checklist

在完成 `workflow/llm_technology_workflow.md` 的每一步后，使用此检查清单进行交叉验证。每个项目必须回答**是**才算完成。如果有任何项目回答**否**，修复输出并重新验证。

## Step 1: 识别大模型技术需求场景

- [ ] 已明确场景类型（预训练原理学习/Prompt工程优化/RAG架构设计/Agent智能体开发/模型微调/多模态模型/模型部署与推理优化）
- [ ] 已提取目标模型规模（7B/13B-30B/70B+/千亿参数MoE）
- [ ] 已提取资源预算（消费级GPU/云GPU/自有集群/纯CPU推理）
- [ ] 已提取团队能力（模型训练经验/CUDA优化经验/分布式训练经验）
- [ ] 已对照知识库关键洞察完成初步判断（预训练→Transformer+Scaling Law+MoE/Prompt→上下文工程/企业知识→RAG/多步骤任务→Agent/领域定制→LoRA/QLoRA）
- [ ] 如有信息缺失，已向用户追问不超过2个澄清问题

## Step 2: 输出大模型技术方案

- [ ] 如为预训练原理，Transformer架构深度解析已覆盖（自注意力/位置编码/改进架构RMSNorm/SwiGLU/GQA）
- [ ] 如为预训练原理，Scaling Law已说明（Chinchilla/Kaplan/训练成本估算）
- [ ] 如为预训练原理，MoE稀疏化架构已覆盖（路由机制/负载均衡/训练策略/推理优化）
- [ ] 如为Prompt工程，设计模式已输出（零样本/少样本/CoT/自一致性/ToT/推理模型专用）
- [ ] 如为Prompt工程，上下文工程策略已覆盖（上下文压缩/结构化Prompt/XML标签/多轮对话优化）
- [ ] 如为Prompt工程，安全与对齐已说明（Prompt注入防护/越狱检测/RLHF/DPO/KTO）
- [ ] 如为RAG，架构演进已覆盖（基础RAG/高级RAG/Agentic RAG 2026主流）
- [ ] 如为RAG，向量检索优化已输出（Embedding模型选型/BGE/GTE/E5/OpenAI/向量索引HNSW/IVF/DiskANN/混合检索）
- [ ] 如为RAG，评估框架已覆盖（检索评估/生成评估/RAGAS/Ares/人工评估）
- [ ] 如为Agent智能体，架构设计已说明（ReAct/Plan-and-Execute/LLM+工具+记忆+规划）
- [ ] 如为Agent智能体，工具调用标准已覆盖（MCP协议/Function Calling/工具类型）
- [ ] 如为Agent智能体，记忆系统设计已输出（短期记忆/长期向量记忆/结构化记忆/反思记忆）
- [ ] 如为Agent智能体，多Agent协同已覆盖（层级架构/对等架构/AutoGPT/MetaGPT/CrewAI/CAMEL）
- [ ] 如为微调，策略选型已对比（全量微调/LoRA/QLoRA/DoRA）
- [ ] 如为微调，数据准备已覆盖（数据格式/数据质量/数据配比）
- [ ] 如为微调，部署与推理优化已输出（量化部署AWQ/GPTQ/GGUF/推理引擎vLLM/TensorRT-LLM/TGI/SGLang/服务架构）
- [ ] 如为微调，评估与对齐已说明（评估指标Perplexity/BLEU/MMLU/HumanEval/对齐方法RLHF/DPO/KTO/安全性评估）
- [ ] 如为多模态，架构演进已覆盖（后期融合→早期融合→统一Transformer/代表性模型GPT-4V/Claude 3/Gemini/Qwen-VL/InternVL）
- [ ] 如为多模态，视觉-语言任务已输出（图像描述/VQA/图文检索/视觉定位/文档理解）
- [ ] 如为多模态，视频理解和音频-语音-音乐已覆盖
- [ ] 所有核心论断均能在知识库中找到支撑来源

## Step 3: 验证与交付

- [ ] 已读取对应 checklist 并逐项核对
- [ ] 模型名称、论文引用、性能数据等精确信息已核对
- [ ] 已向用户交付最终答案

## Overall

- [ ] 工作流中的所有步骤已按顺序执行，没有跳过
- [ ] 每一步都已与其检查清单部分进行交叉验证
- [ ] 没有在任何检查清单部分通过前提前进入下一步
- [ ] `task/current_task.md` 已更新完成记录
- [ ] 所有 `[参考: ...]` 标注均指向存在的知识库文件