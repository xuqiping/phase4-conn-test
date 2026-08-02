# 企业级 RAG 知识库 — 集成脱离分析报告

> 创建：2026-06-23
> 范围：RAG 知识库已开发完成（阶段 0–7 + 8 项必做收口全绿），但作为「多 Agent 智能体平台」的一个子能力，它在**用户真实使用**层面，存在若干与平台其它部分（工作流编排 / Agent / 对话 / 权限模型）衔接不顺、或"做了一半"的地方。本文逐一列出，**保留全部专业名词**，同时用「大白话 + 案例」讲清楚每一处对用户意味着什么。
> 配套文档：`企业级RAG知识库-功能调试手册.md`（每个功能怎么在真实环境测）。

---

## 怎么读这份报告

- **现象**：用户实际看到 / 遇到什么（大白话）。
- **根因**：技术层面为什么会这样（带 `文件:行号`，可点击）。
- **案例**：一个具体场景，复刻用户会踩的坑。
- **影响**：多严重，分 🔴 高 / 🟡 中 / 🟢 低。
- **修向**：要修大概怎么修（不展开实现）。

> 严重度口径：🔴 = 功能不可用 / 误导用户 / 安全风险；🟡 = 体验割裂、需绕路但能跑；🟢 = 已知 Phase1 取舍或边角。

---

## 脱离点速览（按严重度）

| # | 脱离点 | 严重度 | 一句话 |
|---|--------|--------|--------|
| 1 | ✅ **[已修 2026-06-23]** 工作流检索节点 query 接不到 START 节点的入参 | 🔴→✅ | 后端 `renderTemplate` + 前端 `/` 变量菜单，`{{上游别名.输出变量}}` 真生效 |
| 2 | ⚠️ **[chat/Agent 侧已修 2026-06-24，工作流侧待修]** 检索回的证据没人变成"人话答案" | 🔴 | START→检索→END 只吐原始证据；chat/Agent 的 abstain 死句子已拆（工作流仍缺生成节点）|
| 3 | 绑了知识库但 RAG 不跑——开关和绑定是两套独立的东西 | 🔴 | 用户以为"绑库=生效"，其实还要单独开"记忆模式" |
| 4 | RAG 证据 和 用户长期记忆 被绑死在同一个开关上 | 🟡 | 想要知识库问答但不想被抽记忆？做不到 |
| 5 | 会话级开关不回读，切换会话后界面显示与实际不符 | 🟡 | 开关状态"失忆"，用户看不出当前到底开没开 |
| 6 | 工作流执行时，会话级开关对检索节点是"无效操作" | 🟡 | 在对话里开了开关，跑工作流检索照样跳过 |
| 7 | ✅ **[已修 2026-06-23]** 文档目录树/全文查看已接前端 | 🟡→✅ | VO 加 content + DocumentManager 可展开行渲染 L0 摘要/L2 原文 |
| 8 | ⚠️ **[短期缓解 2026-06-24]** Agent / 工作流模式的 RAG 答案不是流式输出 | 🟡 | 前端 SSE 超时 10s→60s 避免双跑；AGENT 真流式未做 |
| 9 | 运行时回调端点 `permitAll`，无鉴权 | 🟡 | 谁都能打 `/api/runtime/callbacks/**`，留了 HMAC 没做 |
| 10 | 多知识库选择会被"静默丢弃" | 🟡 | 选了 3 个库，实际可能只用 1 个，不告诉你 |
| 11 | 文档目录结构对检索完全不起作用 | 🟢 | 怎么分目录都一样，检索永远全库扫 |
| 22 | ✅ **[已修 2026-06-24]** 流式对话每发一条新建会话（新发现） | 🟡→✅ | 流式不回读 sessionId；后端事件带 sessionId + 前端捕获回填 |
| — | **以下为「个人记忆知识库（含冲突解决）」的矛盾点** | — | 见下文专节 |
| 20 | ✅ **[已不成立 2026-06-24]** 记忆模式开了完全不记忆（抽取 LLM 静默失败） | 🔴→✅ | 根因 chat provider 已配（doubao 含 doubao-seed-2.0-code），实测发消息→4 条记忆落库 |
| 12 | 记忆模式一开，每轮回复合同步阻塞 20–60 秒 | 🟡 | 开了记忆，每条消息都回得巨慢 |
| 13 | 记忆冲突检测只在同一「块」内做，跨块矛盾漏判 | 🟡 | 「职业」块和「基本信息」块里的矛盾永远抓不到 |
| 14 | 已标记冲突（FLAGGED）的记忆仍被注入给大模型 | 🟡 | 模型收到自相矛盾的上下文，可能答非所问 |
| 15 | 会话锁忙时，新冲突被「降级」当干净记忆存，不打标记 | 🟡 | 矛盾被悄悄当事实存了，无任何提示 |
| 16 | FLAGGED 冲突无主动通知，用户不开抽屉不知道 | 🟡 | 待解决的冲突静悄悄躺着，永远没人理 |
| 17 | 记忆归块阈值 0.6 硬编码，doubao 相似度偏低 | 🟡 | 本该归一块的相关事实可能分不到一起 |
| 18 | 一个会话同时只允许 1 个待解决冲突，多余的降级 | 🟢 | 一轮里多个冲突，只有第一个会问你 |
| 19 | 记忆全是 AI 推断（INFERRED），无用户主动录入入口 | 🟢 | 没法手动告诉它"记住这条" |

---

## 🔴 脱离点 1：工作流检索节点接不到 START 节点的入参

> ✅ **已修复 2026-06-23**。后端 `executeRetrieval` 的 query 现在过 `renderQuery()`（基于 `request.input` 建 `VariableStore`，复用 SKILL/AGENT_REF 同款 `renderTemplate` 正则 `\{\{\s*[\w.]+\s*\}\}`）；前端 [PropertyPanel.vue](frontend/src/components/workflow/PropertyPanel.vue) 检索「查询」框加了 `/` 触发的上游变量菜单（复用 skill promptTemplate 那套，点变量插 `{{别名.输出键}}`）。新增 2 个单测（模板渲染 + 空查询回退）全绿。下方保留原始问题描述供溯源。

### 现象（大白话）

在工作流画布上拖一个「知识检索」节点，它的「查询」输入框里，**占位提示写着"支持 `{{上游别名.输出变量}}` 模板"**——意思是你可以引用前面节点（比如 START 节点用户输入的问题）的内容当检索词。

但你真去填 `{{start.message}}`，系统会**把这个字符串原样当成检索词**去知识库里搜，根本不会替换成用户实际输入的内容。结果就是：检索永远搜的是字面量 `{{start.message}}`，而不是用户的问题。

### 根因

- 前端占位符是"画饼"：[PropertyPanel.vue:122](frontend/src/components/workflow/PropertyPanel.vue#L122) 的 placeholder 写了"支持 `{{上游别名.输出变量}}` 模板"，但这个 `<n-input>` 就是个纯文本框，没有解析器。
- 后端检索节点取 query 是**原样读静态配置**：[RuntimeNodeCallbackService.java:73](backend/src/main/java/com/superprogrammer/runtime/service/RuntimeNodeCallbackService.java#L73) `String query = stringValue(config.get("query"));`——**没有调用模板渲染**。
- 平台其实**有**模板引擎（`VariableStore` + `renderTemplate`，正则匹配 `{{a.b}}`），但只有 **SKILL / AGENT_REF** 节点的 `inputMappings` 字段会用它（[RuntimeNodeCallbackService.java:291](backend/src/main/java/com/superprogrammer/runtime/service/RuntimeNodeCallbackService.java#L291)）；检索节点根本没接。
- 唯一的"兜底"：当 query 留空时，[RuntimeNodeCallbackService.java:74-76](backend/src/main/java/com/superprogrammer/runtime/service/RuntimeNodeCallbackService.java#L74-L76) 会回退去取上游输入里的 `input/message/prompt/text` 平铺 key。也就是说**只有把查询框留空**，且上游恰好用了 `message` 这种约定 key，数据才流得进来——而且只能用"整条输入"，不能挑某个具体变量。

> 旁证：sidecar 其实已经把上游输出按 `别名.输出键` 的形式拼好传给 Java 了（[runtime_executor.py:249-253](runtime-sidecar/app/runtime_executor.py#L249-L253)），数据"到了门口"，只是检索节点的代码没开门接。

### 案例

用户想做"客服问答工作流"：START（用户输入问题）→ 知识检索（查 FAQ）→ 回答。

1. 在检索节点查询框填 `{{start.message}}`，期望它用用户的问题去查。
2. 跑工作流，输入"怎么退款"。
3. 后端拿字面量 `{{start.message}}` 去知识库做向量检索 → 召回一堆无关内容或直接 abstain（拒答）。
4. 用户一脸懵：明明知识库里有退款说明。

**能跑通的歪路**：把检索节点的查询框**留空**，让 START 节点的输入走 `message` key 兜底进来——但这依赖隐式约定，前端没有任何提示，且只能传整条输入。

### 影响：🔴 高

这是用户**已知**的脱离点，直接让"工作流 + 知识库"这个核心组合场景无法按自然方式使用。前端还主动误导（占位符承诺了用不了的功能）。

### 修向

两条任选：(A) 让 `executeRetrieval` 的 query 走和 SKILL 一样的 `renderTemplate`，基于 `callback_input` 建一个 `VariableStore` 再渲染；(B) 给检索节点也加 `inputMappings` 字段，复用现成的 `effectiveInput`。前端占位符改为真实可用或先去掉。

---

## 🔴 脱离点 2：检索回的证据，工作流里没人把它变成"答案"

> ✅ **chat/Agent 侧部分修复 2026-06-24**。原报告讲的是"工作流"里缺 LLM 生成节点；实测发现**对话（chat）和 Agent 模式**也有同款"abstain 短路"病——检索一旦 abstain（低置信/无命中），直接把死句子"未找到可访问的相关知识。"当回复，**LLM 根本不调用**，用户问啥都回这句。已修两处短路：
> - chat 层（非流式 + 流式）：[ChatSessionService.java:190-196 / :347-351](backend/src/main/java/com/superprogrammer/chat/service/ChatSessionService.java#L190) —— abstain 不再 return 死句子，改为加一条系统提示"知识库未检索到相关内容，请基于自身能力与用户记忆作答"，照常走 `orchestrationEngine.execute` / `executeStream` 生成。
> - **Agent 层**（真凶，AGENT 模式 chat 层被绕过）：[AgentRoutingStrategy.java:45-51](backend/src/main/java/com/superprogrammer/engine/strategy/AgentRoutingStrategy.java#L45) —— Agent 自己又做一次检索 + `if (evidence.isAbstained()) return evidence.getAnswer();` 短路。改为 abstain 时丢弃证据、照常路由到 skill/LLM 执行。
> - 实测（session 111）：开记忆模式问"你好，请自我介绍"→ 不再吐死句子，Agent 路由到技能并返回真实回复。✅
> - **残留**：工作流侧（START→检索→END 仍无生成节点）未修；且 AGENT 模式记忆注入到 LLM 受限（`LlmCallHandler` 只读 `config.systemPrompt` 不读 messageHistory，chat 层加的"用户记忆"到不了 Agent 的 LLM）——问"我叫什么"仍可能答不出，需另做。下方保留原文供溯源。

### 现象（大白话）

知识检索节点干活是这样的：去知识库找相关片段 → 拼成一段带 `[1][2]` 引用标注的"证据上下文" → 作为这个节点的输出往下传。

问题来了：**工作流里没有一个"LLM 节点"能拿这段证据，用人话总结成一个回答**。

代码注释自己也写了"证据上下文……供下游 LLM 节点"（[RuntimeNodeCallbackService.java:94](backend/src/main/java/com/superprogrammer/runtime/service/RuntimeNodeCallbackService.java#L94)）——但这个"下游 LLM 节点"**根本不存在**。

### 根因

- 工作流所有节点类型（[RuntimeNodeType.java](backend/src/main/java/com/superprogrammer/runtime/dto/RuntimeNodeType.java)）：`START/END/INPUT/SKILL/AGENT_REF/WORKFLOW_REF/ROUTER/CONDITION/PARALLEL/JOIN/HUMAN_APPROVAL/TOOL_CALL/RETRIEVAL`。
- 真正会"执行"（回调 Java 干活）的只有 `SKILL / AGENT_REF / RETRIEVAL` 三个（[runtime_executor.py:78-79](runtime-sidecar/app/runtime_executor.py#L78-L79)）。
- **没有任何一个节点类型是"喂一段上游文本进 LLM 提示词，生成回答"**。`LLM_ROUTER` 是假的（mock），只做路由不生成文本。
- 所以 `START → 检索 → END` 跑完，用户拿到的是**检索节点 `output.text` 那段原始证据**（带 `[1]` 标注的拼接片段），**不是自然语言回答**。END 节点本身不产出任何合成文本。

### 案例

接脱离点 1 的客服工作流。即便修好了传参，检索节点吐出：

```
[1] 退款需在订单页点"申请退款"，3-5 个工作日原路返回...
```

这只是一段**原文证据**。用户期望的是"您好，退款请这样做：1.…2.…"这种**综合回答**——但工作流没有节点做这步综合。

**唯一能跑通的组合**：`检索 → SKILL/AGENT_REF`，并且检索节点要配 `outputKey`（让证据以某个变量名往下传），再让下游 Skill 的提示词模板引用这个变量。这条路存在，但**极其隐晦**，前端对 `outputKey` 和模板引用没有可视化引导。

### 影响：🔴 高

"工作流 + 知识库 = 自动问答"是用户最直觉的期待，现状是"能检索、不能答"。检索能力被孤立在工作流里，没法自然收口成用户要的答案。

### 修向

加一个真正的「LLM 生成节点」（或让现有的某个节点支持"系统提示词 + 上游变量"直接调 LLM 网关生成），让证据能被消费成回答。短期可补文档 + 前端引导，教用户用 `检索(outputKey) → AGENT_REF` 的组合。

---

## 🔴 脱离点 3：绑了知识库，RAG 却不跑（绑定 ≠ 生效）

### 现象（大白话）

用户做了一件很自然的事：在对话 / Agent / 工作流里**绑定了一个知识库**（选 kbIds），以为这样 AI 回答时就会查知识库了。

**结果什么都没发生**——AI 还是裸聊，不查库、不带引用。因为"绑知识库"和"开 RAG"是**两个完全独立**的开关，绑了不等于开了。

更要命的是：那个总开关（"记忆模式 / RAG 模式"）**默认是关的**，而且新建对话时，前端会把开关**钉死成关**，哪怕管理员把全局开关打开了。

### 根因

三层都"绑归绑、开归开"：

| 层 | 绑定（kbIds）存在哪 | 开关（ragEnabled）存在哪 | 真正调用检索前的门 |
|----|----|----|----|
| 对话 | `chat_sessions.kb_ids` | `chat_sessions.rag_enabled` | [ChatSessionService.java:189](backend/src/main/java/com/superprogrammer/chat/service/ChatSessionService.java#L189)：`ragOn ? 查证据 : 不查` |
| Agent | `agent_kb_bindings` 连表 | `agents.config` JSONB 的 `ragEnabled` | [AgentRoutingStrategy.java:43](backend/src/main/java/com/superprogrammer/engine/strategy/AgentRoutingStrategy.java#L43) |
| 工作流 | 检索节点 `config.kbIds` | `workflows.rag_enabled` | [RuntimeNodeCallbackService.java:64-70](backend/src/main/java/com/superprogrammer/runtime/service/RuntimeNodeCallbackService.java#L64-L70) |

两套存在完全不同的列 / JSON / 表里，**只在最后那道门前才碰头**。绑了 kbIds 但 ragEnabled 没开 → 静默跳过，不报错、不提示。

外加三重"默认关"雪上加霜：
- 全局 `rag.memory.enabled` 默认 `false`（[V26 迁移 seed](backend/src/main/resources/db/migration/V26__rag_memory_toggle.sql#L14) + [SystemSettingService.java:67-69](backend/src/main/java/com/superprogrammer/system/service/SystemSettingService.java#L67-L69)）。
- 前端对话页开关 `const ragEnabled = ref(false)`（[ChatView.vue:139-140](frontend/src/views/ChatView.vue#L139-L140)）。
- 每次发消息都把这个 `false` 写进会话（[ChatView.vue:183](frontend/src/views/ChatView.vue#L183) → chat store），所以**新对话第一条消息就把会话钉死成关**，全局开了也没用。

### 案例

管理员想给全员开知识库问答：
1. 全局设置里打开"记忆模式"。
2. 普通用户新建对话，绑了知识库，发问"怎么部署系统"。
3. 用户那边：AI 裸答，不查库。因为该用户的新会话第一条消息把 `rag_enabled` 写成了 `false`（前端默认值），覆盖了全局。
4. 管理员排查半天，以为知识库没索引成功，其实索引好好的，是开关没到。

### 影响：🔴 高

这是最容易让用户产生"RAG 坏了"错觉的脱离点。功能都在，但因"绑定与开关解耦 + 默认关 + 前端钉死"，正常使用路径下 RAG 几乎不会自动生效。

### 修向

- 前端：绑了 kbIds 的会话/Agent/工作流，自动把 ragEnabled 设为开（或至少给醒目提示）。
- 前端：`ref(false)` 改成读后端实际值 / 继承全局，而不是无脑写 false。
- 概念上：要么把"绑库"和"开 RAG"合并成一个动作，要么在 UI 上明确告知两者关系。

---

## 🟡 脱离点 4：RAG 证据 和 用户长期记忆 绑死在同一个开关

### 现象（大白话）

那个"记忆模式"总开关，**一开就同时开两件事**：
1. RAG 证据注入（回答前查知识库）；
2. 用户长期记忆抽取（把你说的话自动提炼成"你叫张三、28 岁、爱用 Java"存起来）。

用户**没法只开一个**。比如"我想要知识库问答，但别偷偷记我的个人信息"——做不到，开关一开两个都来。

### 根因

- 全局设置就一个开关（[RagMemorySettingsTab.vue:5](frontend/src/components/settings/RagMemorySettingsTab.vue#L5)），文案明说"启用 RAG 证据 + 用户长期记忆"。
- 后端 `RagModeResolver.resolve(...)` 返回的是**单个 boolean**，这个布尔同时被两条代码路径消费：查证据（`resolveRagForChat` / `resolveAgentEvidence`）和抽记忆（`processMemory`）。
- 会话 / Agent / 工作流每一层也都是这一个布尔，没有拆。

### 案例

- 场景 A：企业内部知识库问答。用户希望 AI 查公司文档答问题，但**不希望** AI 把同事在对话里聊到的私人偏好存成记忆（合规要求）。现状：开开关 = 两个都开，关开关 = 问答也没了。无解。
- 场景 B：个人助理。用户想要 AI 记住自己喜好，但这次对话不涉及知识库。一样只能两个一起开/关。

### 影响：🟡 中

属于设计层面的耦合，不是 bug，但限制了使用场景的灵活性，合规敏感场景会卡住。

### 修向

把 `ragEnabled` 拆成两个独立维度（如 `ragEvidenceEnabled` + `memoryEnabled`），各层独立存、独立解析。短期至少在 UI 上说清楚"开关同时控制两件事"。

---

## 🟡 脱离点 5：会话级开关不回读，界面与实际状态对不上

### 现象（大白话）

对话页有个"记忆模式"小开关。问题：
- 后端会话里其实存了 `rag_enabled` 的值，但**返回给前端的会话信息（SessionVO）里压根没带这个字段**。
- 所以前端永远不知道这个会话当前到底是开是关。每次切换会话、刷新页面，开关都**显示成关**，跟数据库里实际存的值没关系。

### 根因

- `SessionVO`（[ChatSessionService.java:608-631](backend/src/main/java/com/superprogrammer/chat/service/ChatSessionService.java#L608-L631) 的 `toSessionVO`）**没有 ragEnabled 字段**，不返给前端。
- 前端 `selectSession`（chat store）也不回填这个开关，`ragEnabled = ref(false)` 永远是初始 false。
- 结合脱离点 3：用户在新会话发了消息（钉死成 false），后来手动开了开关；一旦切走再切回这个会话，开关又显示关——但后端其实存的是开。**界面说谎**。

### 案例

用户在会话 A 里手动打开了记忆模式，聊了几句（记忆已抽取入库）。切到会话 B 再切回 A，发现开关是关的，以为没生效，又开一次——实际后端早就是开。或者反过来：以为关了，其实还开着，记忆还在被抽。

### 影响：🟡 中

不破坏功能，但让用户对"当前到底什么状态"完全没把握，排查问题极困难。

### 修向

`SessionVO` 补 `ragEnabled` 字段，前端 `selectSession` 回填开关。

---

## 🟡 脱离点 6：工作流执行时，会话级开关对检索节点无效

### 现象（大白话）

用户在某个"工作流模式"的对话会话里，把"记忆模式"开关打开了，以为这个会话跑工作流时检索节点会查知识库。

**实际不会**。工作流检索节点执行时，**根本不看会话级开关**，只看"工作流自身的开关"和"全局开关"。

### 根因

- 检索节点回调走的是专门的方法 `resolveForWorkflowCallback(executionId)`（[RagModeResolver.java:69-79](backend/src/main/java/com/superprogrammer/knowledge/service/RagModeResolver.java#L69-L79)），里面**硬编码 `sessionRagEnabled = null`**（[RagModeResolver.java:78](backend/src/main/java/com/superprogrammer/knowledge/service/RagModeResolver.java#L78)）。
- 因为工作流执行经 sidecar 回调 Java，这条路径上**没有会话上下文**。
- 所以会话级 ON 永远传不到检索节点；只有 `workflow.rag_enabled` 或全局开关才作数。
- 检索节点若没开 → 直接返回"记忆模式未开启，跳过检索"（[RuntimeNodeCallbackService.java:65-70](backend/src/main/java/com/superprogrammer/runtime/service/RuntimeNodeCallbackService.java#L65-L70)）。

### 案例

用户在对话里开了会话级记忆模式，跑一个带检索节点的工作流，结果检索被跳过，工作流没查到任何知识库内容。用户以为是知识库配置错了，其实是会话开关压根管不到工作流检索。

### 影响：🟡 中

用户心智模型里"我在这个会话开了开关，这个会话的所有 RAG 行为都应该开"——对纯对话成立，对工作流检索不成立。认知断层。

### 修向

要么在工作流执行链路里把会话级开关透传到回调（需 sidecar 携带 session 上下文），要么在 UI 上明确告知"工作流检索的开关在工作流/全局，不在会话"。

---

## 🟡 脱离点 7：文档目录树后端端点已建，前端没有界面用它

> ✅ **已修复 2026-06-23**。后端 [KnowledgeNodeVO](backend/src/main/java/com/superprogrammer/knowledge/dto/KnowledgeNodeVO.java) 加 `content` 字段（原"不暴露"放开）+ [KnowledgeNodeService.toVO](backend/src/main/java/com/superprogrammer/knowledge/service/KnowledgeNodeService.java) 填充；前端 [api/knowledge.ts](frontend/src/api/knowledge.ts) 加 `KnowledgeNode` 类型 + `listDocumentNodes(docId)`，[DocumentManager.vue](frontend/src/components/knowledge/DocumentManager.vue) 文档表加 `type:'expand'` 列（仅 INDEXED 行可展开），展开懒加载节点渲染 L0 摘要 + L2 原文（tag/标题/token/`pre` 内容块，L2 缩进）。`GET /documents/{docId}/nodes` 不再是孤儿。下方保留原始问题描述供溯源。

### 现象（大白话）

"必做收口 #4"做了一个后端接口：给一个文档，返回它的目录大纲（L0 摘要节点 + L2 原文子节点的树结构）。设计意图是让用户能在前端看到"这篇文档被拆成了哪些块"。

**但前端根本没有任何地方调用这个接口**。这个端点是"孤儿"——后端做好了，没人消费。

### 根因

- 后端端点 `GET /api/knowledge/documents/{docId}/nodes` 存在（`KnowledgeNodeController`）。
- 前端 `api/knowledge.ts` **没有**调用它的函数；`components/knowledge/` 下**没有任何树组件**（只有 `KbFormModal / KbPermissionModal / DocumentManager / RetrievalDebugPanel / RagAskPanel / RetrievalAuditPanel` 六个）。
- `DocumentManager`（文档抽屉）只渲染**扁平的文档列表**（ID/标题/类型/状态/错误/创建时间/删除），没有可展开的大纲、没有 L0/L2 树。
- `/knowledge` 页只有 4 个 tab：知识库管理 / 检索调试 / RAG 问答 / 检索审计——**没有目录树 tab**。

### 案例

用户上传一篇长文档，想确认"系统把它切成了几块、每块摘要是什么、原文片段对不对"——后端有数据，接口能返，但界面上**看不到**。只能去检索调试 tab 间接验证，或直接 psql 查表。

### 影响：🟡 中

不算功能缺失（接口在），但是"做了后端没做前端"的半截工程，用户感知不到这个能力，调试也不方便。

### 修向

前端 `api/knowledge.ts` 加 `getDocumentNodes(docId)`，在 `DocumentManager` 里加可展开行或一个 `n-tree` 组件渲染大纲。

---

## 🟡 脱离点 8：Agent / 工作流模式的 RAG 答案不是流式

> ⚠️ **短期缓解 2026-06-24（#1）**。AGENT 模式非真流式导致首字节晚于前端 10s SSE 超时 → 前端 catch 后**回退 REST 把整个 Agent 又跑一遍** → 用户看到"思考很久最终超时"。短期已把前端 SSE 首字节超时 10s → 60s（[chat.ts:177-183](frontend/src/stores/chat.ts#L177)），让 Agent 在窗口内跑完吐那一个 chunk，避免双跑。**根治（AGENT 接真流式）仍未做**。下方保留原文供溯源。

### 现象（大白话）

和 AI 对话时，纯对话模式（CHAT）答案是**一个字一个字往外蹦**的（流式 SSE），体验好。

但如果是 **Agent 模式或工作流模式**，哪怕背后接了 RAG，答案是**一次性整段返回**的，没有打字机效果。

### 根因

- 只有 CHAT 模式走真流式（`sendMessageStream`）。
- AGENT / WORKFLOW 模式吃的是 `interface default` / `streamWorkflow`，[B1 修复 + 阶段5 已落地]明确记了"AGENT/WORKFLOW 仍非真流式"。
- RAG 证据注入本身是同步的（`retrieveEvidence` 不含生成，约 3 秒），生成阶段在 Agent/工作流路径上没接流式。

### 案例

用户配了个带知识库的 Agent，问问题，盯着屏幕等了好几秒（Agent 在查库 + 生成），然后答案"砰"地整段出来。同样的问题在纯对话模式是流式蹦字的。体验不一致。

### 影响：🟡 中

功能正确，体验割裂。长答案时用户以为卡住了。

### 修向

把 Agent / 工作流的生成段也接到流式输出（SSE），与 CHAT 对齐。

---

## 🟡 脱离点 22（新）：流式对话每发一条就新建会话（上下文断、列表堆满）

> ✅ **已修 2026-06-24（#3）**。

### 现象（大白话）
在「智能对话」发消息，**每发一条系统就新开一个聊天窗口**。聊 5 句 = 开 5 个窗口，每个窗口只有一句话，上下文全断，左侧会话列表堆成一片。

### 根因
- 后端流式端点建新会话后，**SSE 只发 CHUNK/THINKING/DONE，不带 sessionId**（[ChatController.java:114-163](backend/src/main/java/com/superprogrammer/chat/controller/ChatController.java#L114) `doStream`）。
- 前端流式 handler DONE 时只 `fetchSessions()`，**从不 set `currentSessionId`**（[chat.ts:212-234](frontend/src/stores/chat.ts#L212)）。
- → `currentSessionId` 恒 falsy → 下一条又走 `streamNewMessage`（[chat.ts:170](frontend/src/stores/chat.ts#L170)）→ 又建新会话。
- 对比 REST 路径（[chat.ts:128-130](frontend/src/stores/chat.ts#L128)）有 `currentSessionId.value = chatRes.sessionId` → REST 不分裂。**分裂是流式路径独有**。
- 叠加脱离点 8：超时回退 REST `sendMessage(content)`（[chat.ts:263](frontend/src/stores/chat.ts#L263)）也无 sessionId → 再生一个。

### 案例
- 你：你好 → 开窗口 A。
- 你：我叫张三 → 忘了在 A，又开 B。
- 你：我叫啥？ → 又开 C，C 是空的，答不出。
- 侧栏堆一堆"一句话会话"。

### 影响：🟡 中
上下文断裂、会话列表污染、排查困难。

### 修向 + 已做
- 后端 `StreamEvent` 加 `sessionId` 字段（[StreamEvent.java](backend/src/main/java/com/superprogrammer/chat/dto/StreamEvent.java)），`sendMessageStream` 拆成 wrapper + `doSendMessageStream`，**每个事件 `.map` 打上 sessionId**（[ChatSessionService.java:288-301](backend/src/main/java/com/superprogrammer/chat/service/ChatSessionService.java#L288)）。
- 前端流式收到任意事件即 `if (evt.sessionId) currentSessionId.value = evt.sessionId`（[chat.ts](frontend/src/stores/chat.ts)）。
- 实测：session 111 连发两条消息，`sessions_since_111 = 1`，3 条消息同会话。✅

---

## 🟡 脱离点 9：运行时回调端点无鉴权（permitAll）

### 现象（大白话）

sidecar（Python 编排引擎）回调 Java 执行节点的那个接口（`/api/runtime/callbacks/**`），**没有做鉴权**——理论上谁知道这个地址，都能直接打。

### 根因

- 文档明确记了："`/api/runtime/callbacks/**` permitAll（检索节点继承信任模型，留 HMAC 后续）"。
- 即 Spring Security 配置里这条路径放行所有请求，没校验 token / 签名。
- 检索节点回调也走这条路径，等于继承了"无鉴权"的信任模型。

### 案例

在内网开发环境无所谓。但如果 backend 端口暴露到公网或不可信网络，攻击者可以直接构造回调请求，触发检索 / Skill 执行，读取知识库内容或消耗 LLM 额度。

### 影响：🟡 中（取决于部署环境）

开发/内网低风险；生产/公网部署是真实安全洞。设计上预留了 HMAC，没实现。

### 修向

给回调路径加 HMAC 签名校验（sidecar 和 Java 共享密钥），或限制为内网/localhost 来源。

---

## 🟡 脱离点 10：多知识库选择会被"静默丢弃"

### 现象（大白话）

用户在 RAG 问答 / 对话里**选了多个知识库**一起查。但实际跑的时候，有些库可能被**悄悄丢掉**不查，而且不告诉你。两种情况会丢：
1. 选的库之间**用的 embedding 模型不一样**（向量维度/模型不同没法一起算）。
2. 选的库当前用户**没权限读**。

### 根因

- `RagScopeResolver` 做 P4 求交：所选 kbIds ∩ 用户可见集 ∩ **同 embedding_model 约束**（[RagScopeResolver.java](backend/src/main/java/com/superprogrammer/knowledge/service/RagScopeResolver.java)）。
- 不满足条件的库被过滤掉，**无提示**。
- 工作流检索节点同理：`nodeConfig kbIds ∩ 用户`（[RuntimeNodeCallbackService.java:72](backend/src/main/java/com/superprogrammer/runtime/service/RuntimeNodeCallbackService.java#L72)）。
- 全丢光 → 空集 → abstain（拒答），用户只看到"无可检索范围"，不知道是权限还是模型不一致导致的。

### 案例

- 模型不一致：用户建库 A 用 doubao-embedding-vision，建库 B 用了别的 embedding 模型，多选 A+B 查询 → B 被丢，只查了 A。
- 权限：管理员把库 C 授权给"研发部"，非研发部用户多选时勾到了 C → C 被丢，结果比预期少。

### 影响：🟡 中

结果"看起来对了但其实不全"，难排查。多库场景下尤其容易踩。

### 修向

检索返回里带上"实际生效的 kbIds"和"被丢弃的 kbIds + 原因"，前端展示给用户。

---

## 🟢 脱离点 11：文档目录结构对检索完全不起作用

### 现象（大白话）

用户在知识库里精心把文档分了目录、分了类，期望检索时能"按目录精准查"。

**实际检索永远全库扫**，目录结构被无视。

### 根因

- 解析器（DocumentParserService）不产 DIRECTORY 节点，所以检索第 4 步（目录路由）**永远降级成全库召回**（v6 §4 允许的降级，记为 `DEV-dir-routing`）。
- 这是 Phase1 的已知取舍，不是 bug。

### 案例

知识库有"产品文档"和"HR 制度"两个目录。用户检索时希望只在"产品文档"里查，但系统把两个目录的内容混在一起召回，可能返回 HR 制度的片段。

### 影响：🟢 低

Phase1 设计取舍。检索结果仍相关（靠向量相似度），只是没有目录级精度。Phase2 补 DIRECTORY 节点 + 目录路由可解。

---

## 附：其它已知的非脱离性限制（供参考，非本报告重点）

- **rerank 用父 L0 cosine 代理**（无 cross-encoder，Phase2 上 bge-reranker-v2-m3）：排序质量有上限，但不是"脱离项目"。
- **doubao-embedding-vision 相似度绝对值偏低**（相关 query 仅约 0.50，abstain 阈值 0.5 处于边界）：模型特性，过度拒答可下调 `RagConfig.abstainThreshold`。
- **answer_cache CHAT/AGENT 跨模式共享**：同一用户 + 同一 query + 同一权限签名会命中同一缓存行（按 persona 重新生成答案，不省生成）。是设计特性，非 bug。
- **中文 body 经 Windows curl/Git Bash 会 GBK 报错**：调试须用 `--data-binary @file`（UTF-8 文件）。属环境坑，非功能脱离。

---

## 个人记忆知识库（含冲突解决）的矛盾点

> 依据：`当前项目开发进度-个人记忆知识库（含冲突解决）.md` + 对**当前线上代码**的核实（2026-06-23）。这组矛盾点中，**脱离点 4（RAG 与记忆绑死同开关）**已在上面讲过，下面讲记忆子系统自身的、以及它与平台其它部分衔接的问题。
>
> 背景：个人记忆 = 在 RAG 的「长期记忆抽取」之上，加了一层 **embed 聚类分块 + LLM 语义冲突判定 + 会话锁交互式解决**（V27 加 `block_label/embedding/conflict_id` + `memory_conflicts` 表；V28 删旧 `unique(user_id,key)` 供冲突共存）。核心入口是同步的 `MemoryService.processMemory(...)`，在记忆模式 ON 时、每轮回复后跑。

---

### 🔴 矛盾点 20：记忆模式开了完全不记忆（抽取静默失败，零报错）

> ✅ **已不成立 2026-06-24（实测）**。根因 = 抽取模型 `doubao-seed-2.0-code` 当时**没有任何 ACTIVE chat provider**（根因 2「迁移只 seed embedding」成立）。现已配置：`llm_providers` 中 doubao（category=CHAT，models 含 `doubao-seed-2.0-code`）= ACTIVE。实测链路：智能对话新建会话 → 开「记忆模式」→ 发「我叫王五，35 岁，前端工程师，最爱 TypeScript」→ `user_memories` 落 **4 行**（name=王五 / age=35 / occupation=前端工程师 / favorite_language=TypeScript，全 INFERRED，confidence 1.0）；UI「记忆」抽屉同步显示「我的记忆 4 条」。**抽取能跑、能落库、能在 UI 看**，本条从 🔴 降为 ✅。
>
> ⚠️ 残留隐患（与原根因 4 相关、未消除）：抽取失败仍 `log.warn + return null` 静默吞（[MemoryConflictJudge.java:226-237](backend/src/main/java/com/superprogrammer/chat/service/internal/MemoryConflictJudge.java#L226-L237)）——只是当前 provider 在位所以不触发。一旦 provider 失效/被删/模型名漂移，会**再次静默死、零报错**。原报告建议的「改可见（log.error + 用户提示）」+「加诊断端点 `/memories/probe`」仍未做，建议收口以消除复发风险（参考 `reference_rag_log_notnull` 同类静默吞坑复发 3 次的教训）。下方保留原始问题描述供溯源。

> 这是个人记忆子系统**最严重**的问题——在默认部署下，记忆模式对绝大多数用户 = **完全不可用，且无任何错误提示**。用户开了开关、说什么都不被记，新窗口问"你还记得我吗"也说没有，无从排查。实测确认（2026-06-23）。

**现象（大白话）**：在对话里把「记忆模式」打开，发"我叫张三、28 岁、爱用 Java"这种明显该被记住的话，`user_memories` 里**一条都不进**。换新聊天窗口问"你知道我是谁吗"，AI 说不知道。整个过程**没有任何报错**，用户只能以为"记忆功能坏了"或"我没用对触发词"。

**根因（三坑叠加 + 静默吞异常）**：

记忆抽取链路：`MemoryService.processMemory` → `MemoryConflictJudge.extract` → `LlmGateway.chat(model="doubao-seed-2.0-code")`。三个坑让这次 LLM 调用在默认部署下必失败，而失败被层层吞掉：

1. **只查全局 provider，忽略用户私有 provider**：抽取调 gateway 时**不传 userId**（[MemoryConflictJudge.java:228](backend/src/main/java/com/superprogrammer/chat/service/internal/MemoryConflictJudge.java#L228)），走 [LlmGateway.java:71](backend/src/main/java/com/superprogrammer/llm/LlmGateway.java#L71) 的 `findProvider(model, null)` → 用户在「设置」里配的私有 chat provider **全部被跳过**，只查管理员配的全局 provider。
2. **迁移只 seed 了 embedding provider，没 seed chat provider**：V20/V22 只建了 `doubao-embedding-vision`（[V22__configure_doubao_embedding_vision.sql](backend/src/main/resources/db/migration/V22__configure_doubao_embedding_vision.sql)），`llm_providers` 里默认**没有任何 chat provider**。
3. **模型名硬编码 `doubao-seed-2.0-code`**（[RagConfig.java:54](backend/src/main/java/com/superprogrammer/knowledge/service/RagConfig.java#L54)）：管理员就算配了 chat provider，如果其 `models` 列表不含这个串（或列表为空但 Ark 上没对应 endpoint id），[OpenAICompatibleProvider.supports](backend/src/main/java/com/superprogrammer/llm/provider/OpenAICompatibleProvider.java#L84-L88) 返 false → `findProvider` 抛 `没有找到支持模型 'doubao-seed-2.0-code' 的Provider`。

4. **异常被静默吞**：[MemoryConflictJudge.chat:226-237](backend/src/main/java/com/superprogrammer/chat/service/internal/MemoryConflictJudge.java#L226-L237) `catch(Exception) → log.warn → return null` → extract 返空 `[]` → [processMemory:40-48](backend/src/main/java/com/superprogrammer/chat/service/MemoryService.java#L40-L48) `return null` → 调用方 [ChatSessionService.java:222](backend/src/main/java/com/superprogrammer/chat/service/ChatSessionService.java#L222) 只在 `askText != null` 时动作 → **零记忆 + 零用户提示**，只有一行 `WARN` 日志。

**次要加剧因素**：配合**脱离点 5**（`SessionVO` 不返 `ragEnabled` + 前端 `ref(false)` 恒初），新开聊天窗口开关显示关，第一条消息 `ragEnabled=false` → `ragOn=false` → processMemory 压根不跑（连失败的 LLM 调用都没有）。所以用户即使"开了开关"，在新窗口里也可能根本没进抽取路径。

**案例**：
- 用户开记忆模式说"我叫张三" → `SELECT count(*) FROM user_memories WHERE user_id=<uid>` 返 0，无报错。
- 后端日志唯一线索：`WARN ... LLM 调用失败: 没有找到支持模型 'doubao-seed-2.0-code' 的Provider`（或完全没 `model=doubao-seed-2.0-code` 调用日志 = ragOn 没开）。

**影响**：🔴 高。这是"功能看似在做、实则全程静默死"的最坏情况——比报错更糟，因为用户和运维都收不到任何信号，只会得出"RAG/记忆是坏的"结论。默认部署（只配了 embedding）必中。

**诊断（快速确认）**：
```sql
-- 根因核心查询：有没有全局 chat provider 能服务抽取模型
SELECT name, api_endpoint, models, status, category FROM llm_providers
WHERE status='ACTIVE' AND models ILIKE '%doubao-seed-2.0-code%';
-- 返空 = 抽取必死（根因 1+2+3 实锤）

SELECT rag_enabled FROM chat_sessions ORDER BY id DESC LIMIT 3;          -- 开关到底开没开
SELECT setting_value FROM system_settings WHERE setting_key='rag.memory.enabled';
SELECT count(*) FROM user_memories WHERE user_id=<uid>;                  -- 发消息后是否落库
```
日志 grep：`LLM 调用失败` / `没有找到支持模型` / `记忆抽取失败`（全 `WARN`，需专门翻日志）。

**修向**（建议组合 1+2+4）：
1. **抽取改用管理员实际配的 chat model**（不再硬编码 `doubao-seed-2.0-code`）——根治根因 3，最实用。
2. **静默失败改可见**：抽取失败 `log.error`（非 warn）+ 给用户一条提示"记忆抽取失败：未配置可用的 chat 模型"，不再吞。
3. **加诊断端点** `POST /api/chat/memories/probe`：传一句话返回抽取结果或 provider 错误详情，方便排查（当前完全无此能力）。
4. **修 SessionVO 回读**（即脱离点 5）：开关持久 + 新窗口回显，消除"开关没真开"的次要因素。
5. （可选）seed 一个全局 chat provider。

---

### 🟡 矛盾点 12：记忆模式一开，每轮回复同步阻塞 20–60 秒

**现象**：打开「记忆模式」后，每发一条消息，AI 回复明显变慢（约多 20–60 秒）。轮次越多、消息里的事实越多，越慢。

**根因**：`MemoryService.processMemory(...)` 是**同步方法**（无 `@Async`），跑在请求线程上，且每轮做 `1 次抽取 LLM + N×(1 次 embed + 1 次判定 LLM)`（N = 抽出的事实条数），全串行。
- 调用点（回复生成后、持久化前）：[ChatSessionService.java:222](backend/src/main/java/com/superprogrammer/chat/service/ChatSessionService.java#L222)（非流式）、:403（流式 CHAT）、:448（流式 WORKFLOW）。
- 成本来源：[MemoryService.java:43-60](backend/src/main/java/com/superprogrammer/chat/service/MemoryService.java#L43-L60)（extract → 每条 classify 调 embed → judge 调 LLM）。

**案例**：用户开了记忆模式日常聊天，每条消息都要干等几十秒（系统在抽记忆 + 判冲突），体验像卡住。关掉记忆模式立刻恢复正常速度。

**影响**：🟡 中。功能没错，但默认开（若开）会严重拖慢所有对话；不开零成本（gate 默认关）。

**修向**：把「干净事实抽取」回异步，只把「冲突检测」留同步（只冲突轮才慢）；或加进度提示（"正在整理记忆…"）。

---

### 🟡 矛盾点 13：记忆冲突检测只在同一「块」内做，跨块矛盾漏判

**现象**：系统把记忆按语义聚类成「块」（block_label，如 家庭信息/职业/偏好）。**冲突判定只在同一个块内的记忆之间做**。如果两条矛盾的事实被分到了不同块，就**永远检测不到冲突**。

**根因**：[MemoryService.java:54-60](backend/src/main/java/com/superprogrammer/chat/service/MemoryService.java#L54-L60)——`findCleanByBlock(userId, blockLabel)` 只取同块成员喂给 judge；[MemoryConflictJudge](backend/src/main/java/com/superprogrammer/chat/service/internal/MemoryConflictJudge.java) 的 prompt 也明说"判断与【同信息块已有记忆】是否冲突"。跨块结构上不可见。

**案例**：用户说"我是后端工程师"（归到"职业"块），后来说"其实我是前端"——如果这条被归到了别的块（比如"基本信息"），两条矛盾的职业信息会**共存**，谁也不提醒你冲突。

**影响**：🟡 中。冲突解决机制的有效性依赖归块准确；归块一偏，矛盾就漏。配合矛盾点 17（阈值偏严）更易漏。

**修向**：冲突判定扩大到「同块 + 语义近邻 top-K」而不只是严格同块；或归块用更稳的特征。

---

### 🟡 矛盾点 14：已标记冲突（FLAGGED）的记忆仍被注入给大模型

**现象**：两条互相矛盾的记忆被打上 FLAGGED 标记"共存待澄清"后，**它们俩都会被塞进下一轮对话的系统提示词里**喂给大模型，只是各加个 `[⚠️冲突]` 前缀。等于**故意给模型喂自相矛盾的上下文**。

**根因**：[MemoryService.buildMemoryContext:102-116](backend/src/main/java/com/superprogrammer/chat/service/MemoryService.java#L102-L116)——查记忆时**没有过滤 `conflict_id IS NULL`**，FLAGGED 行（conflict_id 非空）照样入选，加 `[⚠️冲突] ...（与"X"冲突，待澄清）` 前缀后，作为 SYSTEM 消息注入（[ChatSessionService.java:182-186](backend/src/main/java/com/superprogrammer/chat/service/ChatSessionService.java#L182-L186)）。

**案例**：用户被标记了"喜欢 Java"和"喜欢 Python"两条冲突记忆。下次问"推荐我学啥"，模型同时收到"喜欢 Java（与 Python 冲突）"和"喜欢 Python（与 Java 冲突）"——可能懵，或随便挑一个答。

**影响**：🟡 中。设计本意是"让模型知道这里有矛盾、谨慎作答"，但实际等于把噪声/矛盾信号喂进去，可能反而降低回答质量，且用户不知道这个矛盾还在影响每次回答。

**修向**：FLAGGED 记忆默认不注入（或只注入一句"有未解决冲突"提示），强制用户先 resolve；注入时把矛盾对合并成一条"二选一"而非两条并列。

---

### 🟡 矛盾点 15：会话锁忙时，新冲突被「降级」当干净记忆存，不打标记

**现象**：一个会话同一时刻只允许有 1 个待解决冲突（PENDING）。如果已经有 1 个 PENDING 还没解决，这时又冒出新的冲突——新冲突会被**当成干净记忆直接存**（不打 FLAGGED 标记），只在后台日志里记一行 warn。

**根因**：[MemoryService.java:66-79](backend/src/main/java/com/superprogrammer/chat/service/MemoryService.java#L66-L79)——`pending != null`（锁忙）分支走 `insertClean`（clean 入库不打标）+ `log.warn("...降级 clean 入库（不打标）")`。注释自承"留阶段7 完善 flag"。

**案例**：用户一轮里说了多个相互矛盾的事实（或上一个冲突还没答），只有第一个会触发提问，后面的矛盾都被悄悄当事实存下，既不问也不标记。用户完全不知道有矛盾被吞了。

**影响**：🟡 中。冲突信号静默丢失，只有 warn 日志可查；与"绝不丢事实"的 fail-safe 哲学一致（事实没丢），但矛盾标记丢了。

**修向**：锁忙时也建 FLAGGED（共存可见，进待解决列表），而不是降级 clean。

---

### 🟡 矛盾点 16：FLAGGED 冲突无主动通知，用户不开抽屉不知道

**现象**：记忆被打 FLAGGED 后，**系统不会在对话里主动告诉用户**"有条冲突等你解决"。用户只有自己点开「记忆」抽屉、或调 `GET /memories/conflicts`，才会看到。否则这些冲突就静悄悄躺着，且还在影响每次回答（见矛盾点 14）。

**根因**：[MemoryController.java:60-63](backend/src/main/java/com/superprogrammer/chat/controller/MemoryController.java#L60-L63) 只有被动查询端点；全代码无 notify/push/主动推送。`askText`（追问）只在建 PENDING 时进回复，FLAGGED 路径**不产生任何 in-band 提示**。

**案例**：用户上月被标了个"地址冲突"FLAGGED，一直没开过记忆抽屉，于是每次对话模型都被喂这个矛盾上下文，用户却毫不知情。

**影响**：🟡 中。冲突堆积无人理，且持续污染回答；发现性差。

**修向**：FLAGGED 产生时在对话里给一条轻提示，或角标提醒"有 N 条记忆冲突待澄清"。

---

### 🟡 矛盾点 17：记忆归块阈值 0.6 硬编码，doubao 相似度偏低

**现象**：判断"新事实归到哪个块"靠 cosine 相似度 ≥ **0.6**。但 doubao-embedding-vision 的绝对相似度本来就偏低（相关内容常只有 ~0.5）。于是**本该归到同一块的相关事实，可能因为相似度不到 0.6 而被分到新块**，进而触发矛盾点 13（跨块漏判）。

**根因**：[RagConfig.java:52](backend/src/main/java/com/superprogrammer/knowledge/service/RagConfig.java#L52) `MEMORY_BLOCK_SIM_THRESHOLD = 0.6`，`public static final` **硬编码、不可配置**（无 yml/env 覆盖，类注释明说 Phase1 YAGNI 未接 `@ConfigurationProperties`）。

**案例**：用户说"我用 Java"和"我写后端"——语义相关本应归一块，但相似度可能 0.55 < 0.6，被分成两块；若后续出现矛盾，因跨块而漏判。

**影响**：🟡 中。归块不准 = 冲突检测基础不稳。且无运行时调参钩子，校准要改代码重编译。

**修向**：阈值接 yml 可配置；按 doubao 实测分布校准（可能要降到 0.4–0.5）。

---

### 🟢 矛盾点 18：一个会话同时只允许 1 个待解决冲突

**现象**：每个会话同时只能有 1 个 PENDING 冲突（DB 唯一约束保证）。一轮里若冒出多个冲突，**只有第一个会触发提问**（askText），其余走矛盾点 15 的降级。

**根因**：[V27__memory_conflict_support.sql:26](backend/src/main/resources/db/migration/V27__memory_conflict_support.sql#L26) `CREATE UNIQUE INDEX uq_memconf_session_pending ON memory_conflicts(session_id) WHERE status='PENDING'`；[MemoryService.java:75](backend/src/main/java/com/superprogrammer/chat/service/MemoryService.java#L75) `if (askText == null) askText = r.askText();`（只取首个）。

**案例**：用户一条消息里同时更正了"年龄"和"职业"两个矛盾，系统只会问其中一个，另一个降级。

**影响**：🟢 低。设计取舍（避免连环追问烦人），但多矛盾场景会丢部分提问。

**修向**：允许多 PENDING 排队，或一轮多冲突合并成一次提问。

---

### 🟢 矛盾点 19：记忆全是 AI 推断（INFERRED），无用户主动录入入口

**现象**：所有记忆都是 AI 从对话里**推断**出来的（source 恒为 INFERRED）。**用户没法手动告诉系统"请记住这条"**——没有"声明事实"的接口。

**根因**：全代码 `setSource` 只有两处，都是字面量 `"INFERRED"`（[MemoryService.java:93](backend/src/main/java/com/superprogrammer/chat/service/MemoryService.java#L93) + [MemoryConflictService.java:179](backend/src/main/java/com/superprogrammer/chat/service/MemoryConflictService.java#L179)）；`MemoryController` 无 POST 创建端点（只有 GET + DELETE + PUT resolve）。

**案例**：用户想直接录"我对花生过敏"这条关键事实，但只能想办法在对话里"说"出来，指望 AI 抽到。说得太含蓄可能抽不到或抽错。

**影响**：🟢 低。功能可用（靠对话推断），但缺主动控制，关键事实可靠性依赖抽取质量。

**修向**：加 `POST /memories`（source=DECLARED，置信度置 1.0，不参与冲突降级）。

---

### 个人记忆的其它已知非脱离性限制（供参考）

- **judge 本质是 LLM、非 100% 确定**：虽调到 temp 0.0 + Jackson 解析（[MemoryConflictJudge.java:40](backend/src/main/java/com/superprogrammer/chat/service/internal/MemoryConflictJudge.java#L40)），但 temp 0 不等于保证确定性（doubao 仍可能跨次返回不同结果）。属模型固有，非 bug。
- **记忆配置全硬编码**：`rag.memory.*` 在 yml 里**不存在**，4 个常量（embed 模型 / 块阈值 0.6 / 冲突超时 10min / judge 模型）全在 [RagConfig.java:51-54](backend/src/main/java/com/superprogrammer/knowledge/service/RagConfig.java#L51-L54)，调试调参须改代码重编译。
- **场景 4（超时懒 flag）/ 场景 5（gate OFF）未冒烟**：逻辑在，未实跑验证（见个人记忆进度文档「已知 gap」）。
- **WORKFLOW 路径未冒烟**：记忆抽取/冲突在工作流模式没验过。

---

## 总结：最该先修的三件事

> **进度（2026-06-23）**：脱离点 **1 ✅**、**7 ✅** 已修（见各节修复条）。下面"三件事"里 1 的传参已通，剩 2（证据变答案）、3、5、6 待修。

1. **脱离点 1 + 2（工作流检索传参 + 证据变答案）**：直接决定"工作流 + 知识库"这个核心场景能不能用。修了这两点，RAG 才真正接进了工作流编排。**1 已修**（query 模板渲染 + `/` 菜单）；**2 chat/Agent 侧已修 2026-06-24**（abstain 不再短路吐死句子，[ChatSessionService](backend/src/main/java/com/superprogrammer/chat/service/ChatSessionService.java#L190) + [AgentRoutingStrategy](backend/src/main/java/com/superprogrammer/engine/strategy/AgentRoutingStrategy.java#L45)），**工作流侧仍待修**（缺 LLM 生成节点把证据变成自然语言回答）。

> **2026-06-24 第二批修复（3 项，全实测通过）**：
> - **#3 / 脱离点 22 ✅**：流式对话每条新建会话 → 后端事件带 `sessionId` + 前端捕获回填（[ChatSessionService:288](backend/src/main/java/com/superprogrammer/chat/service/ChatSessionService.java#L288)、[chat.ts](frontend/src/stores/chat.ts)）。实测 session 111 连发两条同会话。
> - **#2 / 脱离点 2 chat+Agent 侧 ✅**：abstain 不当答案 → chat 层加"无命中"提示照常生成 + Agent 层丢弃证据照常路由（[AgentRoutingStrategy:45](backend/src/main/java/com/superprogrammer/engine/strategy/AgentRoutingStrategy.java#L45)）。实测不再吐"未找到可访问的相关知识"。
> - **#1 / 脱离点 8 ⚠️ 缓解**：前端 SSE 超时 10s→60s，避免 AGENT 非流式首字节晚导致的"超时→REST 双跑"。根治（AGENT 真流式）未做。
> - **遗留**：① 工作流缺 LLM 生成节点（脱离点 2 工作流侧）；② AGENT 模式记忆到不了 LLM（`LlmCallHandler` 只读 systemPrompt 不读 messageHistory）；③ AGENT 真流式（脱离点 8 根治）。
2. **脱离点 3（绑定 ≠ 生效 + 默认关 + 前端钉死）**：修了这点，普通用户"绑库即用"的直觉才成立，否则 RAG 对大多数用户等于不存在。
3. **脱离点 5 + 6（开关不回读 + 工作流回调无视会话开关）**：修了这两点，用户对"RAG 到底开没开"才有可信的认知，排查才有可能。

其余（4/8/9/10/11）可按优先级排期收口。**7 已修**（文档全文查看接前端）。

**个人记忆子系统**（矛盾点 12–20）里，**头号必须先修的是矛盾点 20（记忆模式静默失效）——现已 ✅ 不成立 2026-06-24**：chat provider（doubao / `doubao-seed-2.0-code`）已配，实测发消息即落 4 条记忆。**但「抽取失败静默吞 + 无诊断端点」的复发隐患仍在**（provider 一旦失效会再次零报错死），建议仍收口「失败可见 + `/memories/probe`」。之后再做 **12（同步阻塞）+ 14（FLAGGED 喂模型）+ 15/16（冲突静默降级 + 无通知）**——这些决定记忆模式"跑起来后好不好用、可不可信"。13/17（归块/阈值）是冲突检测准确性的地基，建议一起校准。
