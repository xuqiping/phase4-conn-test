# LLM Technology Stack Workflow

## Purpose

基于大模型技术体系知识，为用户提供大模型预训练原理（Transformer/Scaling Law/MoE）、Prompt工程（上下文工程）、RAG检索增强生成（Agentic RAG）、Agent智能体架构（LLM+工具+记忆+规划）、模型微调（LoRA/QLoRA）或多模态统一架构的技术方案支持。覆盖DeepSeek V3 $5.57M训练671B模型、MCP协议统一Agent工具调用、消费级GPU+QLoRA降低微调门槛100倍等关键洞察。

## Prerequisites

- 用户已明确大模型技术场景或问题
- 知识库文件 `05_人工智能与机器学习.md` 及子目录文件可访问

## Steps

### Step 1: 识别大模型技术需求场景

**Goal**: 明确用户的大模型技术需求类型、模型规模和资源约束
**Completion criterion**: 已确定场景标签、目标模型规模、资源预算和团队能力

1. 读取用户消息，提取以下信息：
   - 场景类型：预训练原理学习 / Prompt工程优化 / RAG架构设计 / Agent智能体开发 / 模型微调（LoRA/QLoRA/全量） / 多模态模型 / 模型部署与推理优化
   - 目标模型规模：7B（消费级GPU可微调） / 13B-30B（多卡服务器） / 70B+（企业级集群/A100/H100） / 千亿参数（MoE架构）
   - 资源预算：消费级GPU（RTX 4090/3090，24GB显存） / 云GPU（A100/V100按需） / 自有集群 / 纯CPU推理
   - 团队能力：有无模型训练经验、有无CUDA优化经验、有无分布式训练经验
   - 具体目标：如"用QLoRA微调Llama 3 8B做法律问答"、"设计Agentic RAG处理企业知识库"、"理解MoE稀疏化原理"

2. 对照知识库中的关键洞察初步判断技术路径：
   - 预训练原理学习 → Transformer架构+Scaling Law+MoE+分布式训练
   - Prompt优化 → 上下文工程（非简单提示词）、推理模型（o1/DeepSeek-R1）思维链
   - 企业知识库/私有数据 → RAG（Agentic RAG 2026主流）而非微调
   - 多步骤任务自动化 → Agent智能体（LLM+工具+记忆+规划+MCP协议）
   - 领域定制（7B-30B） → LoRA/QLoRA微调（消费级GPU可完成）
   - 千亿参数+高性能 → MoE稀疏化（DeepSeek V3路径）

3. 如有信息缺失，向用户追问不超过2个澄清问题。

**Knowledge Base Reference**:
- [参考: Agents知识库/0_超级编程行业知识库/05_人工智能与机器学习.md > 大模型技术体系]
- [参考: Agents知识库/0_超级编程行业知识库/05_人工智能与机器学习.md > 各L2摘要 > 大模型技术体系]

### Step 2: 输出大模型技术方案

**Goal**: 产出针对性的大模型技术选型、架构设计或实现方案
**Completion criterion**: 输出包含技术选型、架构设计、实施步骤、资源估算

根据Step 1确定的场景，按以下分支处理：

**分支A — 预训练原理与架构选型**：
1. 输出Transformer架构深度解析：
   - 自注意力机制：Q/K/V矩阵计算、多头注意力（Multi-Head）、注意力可视化分析
   - 位置编码：绝对位置编码（正弦/学习式）→ 相对位置编码（RoPE/ALiBi）→ 无位置编码（Mamba/RWKV状态空间）
   - 改进架构：RMSNorm（替代LayerNorm）、SwiGLU（替代FFN）、Grouped-Query Attention（GQA，减少KV缓存）
2. 给出Scaling Law应用：
   - Chinchilla Scaling Law：最优训练token数 ≈ 20 × 参数量（避免欠训练）
   - Kaplan Scaling Law：模型大小和数据量同时缩放，计算预算C=6ND（N参数，D数据）
   - 训练成本估算：DeepSeek V3以$5.57M训练671B参数MoE模型（专家并行+流水线并行+ZeRO优化）
3. 输出MoE稀疏化架构：
   - 路由机制：Top-K门控（选择2个专家）、负载均衡损失（避免路由崩溃）、辅助损失设计
   - 训练策略：专家并行（Expert Parallelism）+ 数据并行 + 流水线并行
   - 推理优化：动态专家加载（按需激活）、KV缓存共享（跨专家）、投机解码（Draft-then-Verify）
   - 与Dense模型对比：同参数量下训练成本降低、推理吞吐量提升、但内存占用更高
4. 附数据工程要点：数据清洗（去重/质量过滤/毒性检测）、数据配比（代码/网页/书籍/对话）、Tokenization（BPE/SentencePiece/Unigram）。

**分支B — Prompt工程与上下文优化**：
1. 输出Prompt设计模式：
   - 基础模式：零样本（Zero-shot）、少样本（Few-shot，3-5个示例）、思维链（Chain-of-Thought，逐步推理）
   - 高级模式：自一致性（Self-Consistency，多路径投票）、思维树（Tree-of-Thoughts，探索+评估+回溯）、自动提示工程（APE/OPRO，LLM自动优化提示词）
   - 推理模型专用：DeepSeek-R1/o1类模型（强化学习训练推理能力，Prompt只需简洁问题，无需复杂CoT指令）
2. 给出上下文工程策略：
   - 上下文压缩：RAG检索结果重排序（RRF/ColBERT）、关键信息提取摘要、长上下文窗口利用（128K/1M/2M tokens）
   - 结构化Prompt：XML标签分隔（<instruction><context><example><output>）、JSON Schema约束输出、系统Prompt固化角色和行为
   - 多轮对话优化：对话历史截断策略（保留最近N轮+关键信息摘要）、用户意图跟踪、状态管理
3. 输出Prompt安全与对齐：Prompt注入防护（输入过滤/输出校验/沙箱执行）、越狱检测（敏感词/模式匹配/LLM自我评估）、对齐训练（RLHF/DPO/KTO）。

**分支C — RAG检索增强生成**：
1. 输出RAG架构演进：
   - 基础RAG：文档分块（固定长度/语义分块/递归分块）→ Embedding编码（BGE/M3E/OpenAI）→ 向量检索（Milvus/Weaviate/PG Vector）→ Top-K结果注入Prompt → LLM生成
   - 高级RAG：查询重写（Query Expansion/Decomposition/HyDE假设文档嵌入）、重排序（Cross-Encoder/ColBERT/RRF）、多路召回（向量+关键词+图检索混合）
   - Agentic RAG（2026主流）：检索决策Agent（判断是否需要检索→选择检索工具→评估检索结果→决定继续检索或生成）、多轮检索（迭代补充信息）、工具调用（搜索引擎/数据库/API/代码执行）
2. 给出向量检索优化：
   - Embedding模型选型：BGE-M3（多语言/多粒度）、GTE（阿里）、E5（微软）、OpenAI text-embedding-3（通用），附MTEB排行榜参考
   - 向量索引：HNSW（高召回、内存占用大）/ IVF（平衡、适合百万级）/ DiskANN（磁盘索引、十亿级）/ Flat（精确、小数据量）
   - 混合检索：Dense向量（语义匹配）+ Sparse向量（BM25/TF-IDF关键词匹配）+ RRF重排序融合
3. 输出RAG评估框架：
   - 检索评估：召回率@K、MRR、NDCG、命中率（Answer Relevance）
   - 生成评估：Faithfulness（忠实度）、Answer Relevance（答案相关性）、Context Precision（上下文精确率）、Context Recall（上下文召回率）
   - 端到端评估：RAGAS框架、Ares框架、人工评估（Likert量表）
4. 附RAG典型问题与解决：幻觉（增加检索密度+重排序）、知识时效性（定期重索引+增量更新）、多文档冲突（来源标注+置信度评分）。

**分支D — Agent智能体开发**：
1. 输出Agent架构设计：
   - 核心组件：LLM大脑（推理+规划）、工具箱（函数调用/API/数据库/代码执行）、记忆系统（短期对话历史+长期向量记忆）、规划模块（ReAct/Plan-and-Execute/Tree-of-Thoughts）
   - ReAct模式：Reason（推理→分析当前状态）→ Act（行动→调用工具）→ Observation（观察→获取结果）→ 循环直至完成
   - Plan-and-Execute：先制定多步计划 → 按步骤执行 → 每步评估进度 → 动态调整计划
2. 给出工具调用标准：
   - MCP协议（Model Context Protocol，Anthropic 2024推出，2026成主流标准）：统一工具定义格式（JSON Schema）、跨模型兼容（OpenAI/Anthropic/开源模型）、工具市场生态
   - Function Calling：OpenAI格式（tools参数）、Anthropic格式（tool_use/tool_result）、开源适配（Llama 3.1原生支持）
   - 工具类型：信息检索（搜索引擎/数据库查询）、计算（代码执行/计算器）、操作（邮件发送/日历创建/API调用）、多Agent协同（向其他Agent委派任务）
3. 输出记忆系统设计：
   - 短期记忆：对话历史窗口（最近N轮）、工作记忆（当前任务上下文）
   - 长期记忆：向量记忆（Milvus/Weaviate存储历史对话Embedding）、结构化记忆（知识图谱/数据库表存储实体关系）、反思记忆（Self-Reflection总结经验教训）
   - 记忆检索：相关性检索（向量相似度）、时序检索（最近发生）、重要性检索（关键事件标记）
4. 附多Agent协同架构：
   - 层级架构：Manager Agent（任务分解+分配）→ Worker Agents（各专业领域执行）→ Review Agent（结果审核）
   - 对等架构：Agent间直接通信（消息队列/共享内存）、共识机制（投票/辩论）、竞争机制（多方案生成+评估选择）
   - 应用案例：AutoGPT（自主任务执行）、MetaGPT（软件开发多Agent协作）、CrewAI（角色扮演团队）、CAMEL（对话式多Agent）

**分支E — 模型微调与部署**：
1. 输出微调策略选型：
   - 全量微调（Full Fine-tuning）：数据量大（>10万条）、计算资源充足（A100 8卡）、效果最好但易过拟合、需要学习率Warmup
   - LoRA（Low-Rank Adaptation）：秩r=8-64、只训练低秩矩阵、显存节省70%+、适合7B-30B模型、推理时合并权重
   - QLoRA（4-bit量化+LoRA）：NF4/FP4量化、Double Quantization、Paged Optimizer、RTX 4090可微调7B模型、消费级GPU门槛降低100倍
   - DoRA（Weight-Decomposed LoRA）：分解为幅度和方向、更稳定、适合医学/法律等对精度要求高的领域
2. 给出微调数据准备：
   - 数据格式：指令微调（Instruction+Input+Output）、对话微调（多轮User/Assistant）、偏好微调（Chosen/Rejected Pair）
   - 数据质量：去重（MinHash/LSH）、过滤（长度/语言/毒性）、增强（重写/翻译/合成数据Self-Instruct）
   - 数据配比：通用数据+领域数据混合比例、领域数据量估算（>1000条有效数据起效果）
3. 输出模型部署与推理优化：
   - 量化部署：AWQ（激活感知的权重量化）、GPTQ（逐层量化）、GGUF（llama.cpp格式、CPU推理）、INT8/FP16/BF16精度选择
   - 推理引擎：vLLM（PagedAttention、高吞吐）、TensorRT-LLM（NVIDIA优化、极致性能）、TGI（HuggingFace、易用性）、SGLang（结构化生成优化）
   - 服务架构：API Gateway（限流/认证/缓存）→ 推理集群（多副本负载均衡）→ 模型仓库（版本管理/灰度发布）
4. 附评估与对齐：
   - 评估指标：Perplexity（困惑度）、BLEU/ROUGE（生成质量）、HumanEval（代码能力）、MMLU/C-Eval（知识问答）、GPQA（科学推理）
   - 对齐方法：RLHF（PPO算法、奖励模型训练）→ DPO（直接偏好优化、无需奖励模型）→ KTO（Kahneman-Tversky优化、二元偏好）
   - 安全性评估：越狱测试、偏见检测、毒性生成、隐私泄露、红队测试（Red Teaming）

**分支F — 多模态统一架构**：
1. 输出多模态架构演进：
   - 后期融合（Early Approach）：独立编码器（ViT/CLIP文本编码器）→ 特征拼接 → 联合投影 → 分类/生成
   - 早期融合（Modern Approach）：统一Transformer（图像Patch+文本Token统一序列）、原生多模态预训练（图像-文本-音频-视频联合训练）
   - 代表性模型：GPT-4V（视觉理解）、Claude 3（多模态推理）、Gemini（原生多模态）、Qwen-VL（国产开源）、InternVL（国产开源）
2. 给出视觉-语言任务：图像描述（Image Captioning）、视觉问答（VQA）、图文检索（Image-Text Retrieval）、视觉定位（Grounding）、文档理解（OCR+Layout+语义理解）
3. 输出视频理解：时序建模（帧采样+时序注意力）、视频-文本对齐（CLIP4Clip/InternVid）、长视频处理（关键帧提取+摘要）
4. 附音频-语音-音乐：语音识别（Whisper/FunASR）、语音合成（CosyVoice/F5-TTS）、音乐生成（Suno/Udio）、音频事件检测。

将结果保存到 `output/llm_technology.md` 或直接在对话中呈现。

**Knowledge Base Reference**:
- [参考: Agents知识库/0_超级编程行业知识库/05_人工智能与机器学习.md > 各L2摘要 > 大模型技术体系]
- [参考: Agents知识库/0_超级编程行业知识库/人工智能与机器学习/大模型技术体系.md > 预训练原理/Prompt工程/RAG/Agent/微调/多模态]

### Step 3: 验证与交付

**Goal**: 确保大模型技术方案准确前沿、与知识库一致
**Completion criterion**: 已通过 checklist 逐项核对

1. 读取 `checklist/llm_technology_workflow_checklist.md`。
2. 逐项核对输出是否覆盖要求的知识点。
3. 确认模型名称、论文引用、性能数据等精确信息已核对。
4. 如有遗漏或偏差，补充修正。
5. 向用户交付最终答案。

## Post-Workflow

1. 记录完成状态到 `task/current_task.md`。
2. 如需深入某一具体技术（如"QLoRA 4-bit量化NF4配置详解"、"MCP协议工具定义JSON Schema示例"），在当前 Agent 内继续追问并输出。