# 需求与产品工程 Workflow

## Purpose

为产品需求全生命周期提供从用户研究、需求分析、优先级排序、PRD设计到迭代演进的完整执行路径。覆盖AI-Native用研、需求结构化拆解、组合优先级框架和变更追踪。

## Prerequisites

- 用户已明确产品类型（To B/To C/G端/AI产品）
- 用户已明确目标用户群体（角色/场景/痛点）
- 用户已明确当前阶段（概念验证/MVP/增长/规模化）

## Steps

### Step 1: 用户研究与需求洞察

**Goal**: 完成用户研究，产出用户画像、需求洞察和机会点清单。
**Completion criterion**: 用户研究报告产出，包含Persona、用户旅程图、关键发现≥5条、数据支撑。

**A. 研究方法论选择**
- Discovery阶段（定性探索）：用户访谈/焦点小组/田野调查/日志分析
- Validate阶段（混合验证）：问卷调查/Kano分析/可用性测试/A/B测试
- Optimize阶段（定量优化）：A/B测试/漏斗分析/留存分析/RFM分层
- 方法匹配：
  - 探索未知需求 → Discovery（JTBD + 5 Whys + Critical Incident）
  - 验证假设 → Validate（Kano问卷 + SUS可用性测试）
  - 优化体验 → Optimize（A/B测试 + NPS追踪）

**B. AI-Native用研（2026标配）**
- AI主持访谈：并行千场访谈，单场成本从3000元降至200元
- 合成用户：仅用于Discovery"嗅探测试"（Big-5维度相关度0.72-0.85），精细决策不可替代
- Multi-Agent研究框架：Researcher Agent + Interviewer Agent + Analyst Agent + Reporter Agent
- RAG增强用研知识库：历史访谈+用户画像+产品文档作为上下文
- 人机协同三层：AI做高频低复杂 → 半自动中等复杂 → 人工低频高复杂
- 风险：AI幻觉必须人工复核、合成用户明确标注不可当真实数据

**C. 用户画像与旅程图**
- Persona五步法：数据收集→聚类→画像凝练→可视化→团队对齐
- 五维度：人口统计+心理特征+行为模式+痛点+使用情境
- 用户旅程图：七阶段（认知→考虑→购买→ onboarding →使用→续费→推荐）+情绪曲线+痛点机会点
- B端特殊：三层用户（决策者/管理者/使用者）+八角色画像
- Anti-Persona：明确"不服务的用户"

**D. 需求洞察框架**
- JTBD：用户"雇佣"产品完成什么任务？（功能+情感+社会三层）
- 需求三层结构：功能需求→痛点→动机
- Latent Need识别六信号：语言模糊/言行不一/跨文化共性/神经科学激活/长期习惯/转换工具痛点
- 四力模型：推力（现状不满）+拉力（新产品吸引）+惯性（切换成本）+焦虑（新方案风险）

**E. 可用性与满意度**
- Jakob Nielsen 5用户法则：5位用户发现85%可用性问题
- SUS量表：业界均分68，0-50差/51-67及格/68-80良/80+优
- NPS：推荐者%-贬损者%，行业基准30-50良好/50-70优秀

**输出物**: `用户研究报告_产品名.md`（含Persona、用户旅程图、JTBD任务清单、关键发现、数据支撑）。

**[参考: Agents知识库/0_超级编程行业知识库/软件工程与开发全流程/需求与产品工程.md > 需求收集与用户研究方法]`

---

### Step 2: 需求分析与结构化拆解

**Goal**: 将原始需求转化为结构化、可执行的用户故事集合，输出需求规格和验收标准。
**Completion criterion**: 用户故事地图完成，四级粒度拆解清晰，验收标准可测试，需求覆盖率≥95%。

**A. 需求分类框架**
- FURPS+：功能性/可用性/可靠性/性能/可支持性+设计约束/接口/物理
- Wiegers三层：业务需求→用户需求→功能需求（+NFR+系统约束）
- Kano五类：基本型/期望型/兴奋型/无差异型/反向型
- AI扩展：Prompt/Tool/Memory/Guardrails/AI合规

**B. 需求结构化**
- Story Mapping八步法（Jeff Patton）：
  1. Persona识别 → 2. 用户旅程 → 3. 骨干故事 → 4. 静默分组
  5. 命名排序 → 6. 细化故事 → 7. 规划发布 → 8. MVP拆分
- 四级粒度：Epic（数月）→ Feature（数周）→ Story（数天）→ Task（数小时）
- 工作量参考：Epic 50-200SP / Feature 20-50SP / Story 1-8SP / Task 0.5-2SP
- Walking Skeleton：确保端到端覆盖的最小可行骨架

**C. 用户故事规范**
- 3C原则：Card（卡片）+ Conversation（对话）+ Confirmation（确认）
- INVEST原则：Independent/Negotiable/Valuable/Estimable/Small/Testable
- 格式："作为[角色]，我希望[功能]，以便[价值]"
- AI辅助拆分：从需求文档自动生成结构化故事树，Human-in-Loop复核

**D. 验收标准与BDD**
- Gherkin格式：`Given [前置条件] When [触发动作] Then [预期结果]`
- Three Amigos实践：PO + 开发 + 测试共同编写
- AI产品扩展：Evals维度评估（准确性/完整性/相关性/无幻觉/合规性）
- LangSmith Evaluation Framework：12类维度评估

**E. 需求追踪矩阵（RTM）**
- 双向追踪：业务需求↔用户需求↔功能需求↔设计↔代码↔测试用例
- 覆盖率目标：需求覆盖率≥95%，测试用例覆盖率≥90%
- 变更影响分析：变更请求→影响范围评估→关联项更新

**输出物**: `需求规格文档_产品名.md`（含Story Map、用户故事清单、验收标准、RTM矩阵）。

**[参考: Agents知识库/0_超级编程行业知识库/软件工程与开发全流程/需求与产品工程.md > 需求分析与用户故事地图]`

---

### Step 3: 需求优先级与迭代规划

**Goal**: 完成需求优先级排序和迭代路线图规划，输出Now-Next-Later路线图。
**Completion criterion**: 优先级排序完成，路线图经干系人确认，变更管理流程建立。

**A. 组合优先级框架（2026推荐）**
- RICE：量化评分（Reach×Impact×Confidence/Effort），定季度Roadmap
- Kano：战略满意度盘点，每12-18个月重做
- MoSCoW：版本切片，Must不超过60%容量
- WSJF：排SAFe Epic，加权最短作业优先
- ICE：Sprint快速排序，影响力/信心度/易实现度
- 原则：不再"单一框架押宝"，按场景组合调度

**B. 变更管理**
- CCB四级审批：一级（Story级，PO决策）→二级（Feature级，PM+Tech Lead）→三级（Epic级，产品委员会）→四级（战略级，高管层）
- CIA五维影响分析：成本/进度/范围/质量/风险
- 变更追踪：变更请求→影响分析→审批→实施→验证→基线更新
- 需求基线冻结：每个迭代开始前冻结，迭代内不接受新增Must

**C. PRD与原型**
- 模块化PRD：背景/用户/范围/交互/数据/非功能/依赖/指标八段式
- 视觉化：Figma原型→设计Token→前端组件库1:1映射
- 数据驱动迭代：A/B测试需满足p<0.05+功效>0.8
- 扩展信号：留存率>30%次日+NPS>30+核心功能使用率>50%

**D. 合规红线**
- PIPL/GDPR：告知-同意原则，隐私政策+用户协议
- 生物识别："单独同意"，明确用途和保留期限
- AI Act：高风险系统风险评估，算法备案

**输出物**: `产品路线图_产品名.md`（含优先级排序表、Now-Next-Later路线图、变更管理流程、PRD链接）。

**[参考: Agents知识库/0_超级编程行业知识库/软件工程与开发全流程/需求与产品工程.md > 需求优先级与迭代规划]`

---

## Post-Workflow

1. Read `checklist/requirements_engineering_workflow_checklist.md`.
2. Cross-validate every output against every checklist item.
3. Only proceed to the next workflow or notify the user of completion after all checklist items pass.
