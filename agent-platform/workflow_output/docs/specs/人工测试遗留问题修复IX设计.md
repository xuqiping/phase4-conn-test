# 规格 · 人工测试遗留问题修复IX（9x 思考强度 + 2x 粘贴保留连线 + 9x 联网搜索收尾）

> SDD 特性级规格（Phase 1 产出）。实现须与本文件对齐；冲突时改实现或改本文档（注明原因）。
> 来源：[9x_智能对话.md](../../人工测试问题/9x_智能对话.md)「未解决」2 项；[2x. 资产库和无限画布.md](../../人工测试问题/2x. 资产库和无限画布.md)「未解决」1 项（2026-08-31）。
> 用户拍板见 §3。前序：修复 III~VIII 已收（VIII `4cd830eb`/`cf1327df`，M/N 用例待人工）。

## 1. 背景与代码现状事实（2026-08-31 两路探查）

### 1a. 思考强度（9x①「是否能选择思考强度？」）

| # | 现状事实 | 位置 |
|---|---|---|
| ①唯一思考字段 | `LlmRequest.disableThinking`（Boolean 默认 false，二值「关/不关」无档位）；注释自述「Anthropic 协议 thinking.type=disabled；glm 忽略、kimi 尊重」 | LlmRequest.java:29-30 |
| ②唯一消费点 | `ClaudeProvider` 仅 disabled 分支：`body.put("thinking", {"type":"disabled"})`——**无 enabled/budget_tokens 分支** | ClaudeProvider.java:178-181 |
| ③OpenAI 系零参数 | `OpenAICompatibleProvider.buildRequestBody` 只放 `model/temperature/max_tokens/stream/messages`——glm/minimax/doubao/k3 全走此适配器，**从不传任何思考参数**（纯靠模型默认行为） | OpenAICompatibleProvider.java:327-351 |
| ④适配器格局 | 仅两个 Java 适配器（ANTHROPIC→ClaudeProvider，其余→OpenAICompatibleProvider）；glm 等是 `llm_providers` 表数据行靠 protocol 分流。chat 链路只解析 `models` 列，**`config` jsonb 对 CHAT 完全未消费**（capabilities 机制仅媒体域有，可参照） | LlmConfig.java:94-125、LlmProviderEntity.java:17-18、MediaModelCapabilityService.java:52-95 |
| ⑤内部调用 | `disableThinking(true)` 写死在 10+ 个记忆内部 JSON 蒸馏调用（蒸馏/压缩/判定/打标等）——本轮不可破坏 | MemoryAssetIngestService.java:290 等 |
| ⑥请求链无载体 | `ChatRequest`（HTTP/SSE/WS 三路共用）无 thinking 字段；`ChatSessionService.generateReplyStream` 只透传 model/projectGroupId 进 ExecutionContext；`DefaultChatStrategy` 组 LlmRequest 不设思考参数 | ChatRequest.java:16-49、ChatSessionService.java:560-568、DefaultChatStrategy.java:28-59 |
| ⑦回显已有 | THINKING 流事件→前端「💭 思考中」面板+消息 metadata.thinking 折叠块——展示侧齐，**只缺控制侧** | StreamEvent.java:25-27、ChatView.vue:80-83、MessageBubble.vue:37-43 |
| ⑧计费口径 | 思考 token 计入 completionTokens 按输出价走（价表/usage 无 thinking 腿，无需动）；HOLD 预扣出量估算 = `min(maxTokens, billing.chat.hold-est-max-tokens=2048)` ×出价，**不感知思考** | LlmBillingService.java:164-196、TokenUsage.java:25-29 |
| ⑨已知遗留 | glm-5.1/5.3 忽略 `thinking:{"type":"disabled"}` 与 budget_tokens（9x 遗留观察）——声明机制正好治此：不声明就不发参数 | 9x_智能对话.md:27 |

### 1b. 画布粘贴保留连线（2x「粘贴出来的节点应该保留原有的节点连接关系」）

| # | 现状事实 | 位置 |
|---|---|---|
| ①数据源已具备 | `onCopyKeydown` 复制时刻把**画布全量边**传进 `buildCopySet`——非选中邻居边信息本就拿到手 | CanvasBoard.vue:910-927 |
| ②双端 filter 丢弃 | 诱导边口径：`edges.filter(两端都在选中集)` ——跨集边（一端在集内一端在外）被丢；快照接口 `CanvasClipboard{items,innerEdges,bbox,pasteCount}` 无外邻边字段 | canvasClipboard.ts:25-33、:64-67 |
| ③重映射整边丢弃 | `remapEdges`：`keyToNewId.get` 查不到端点（外端点）→ 整边 return null 丢弃——只保留内部边的第二道闸 | canvasClipboard.ts:115-135（:123） |
| ④副本已有目标语义 | 「创建副本」`cloneEdgesForDuplicate` 克隆原节点**全部入边+出边**，只换克隆侧端点、另一端保持原节点 id——粘贴要的「单侧重映射」语义现成可抄 | nodeClone.ts:50-61 |
| ⑤data 层引用现状 | 粘贴体 data 里 `@{{node:原id}}` 占位符与 `firstFrameNodeId` **本就不重写**（指向原节点）——边连回原节点后与 data 引用语义一致 | canvasClipboard.ts 模块头注释、nodeClone.ts:11 |
| ⑥组边排除 | 两条路径均显式排除组端点边（`isGroupEndpoint`）——保持不变 | canvasClipboard.ts:64、nodeClone.ts:55 |
| ⑦落库/撤回自动覆盖 | 粘贴主函数 `pasteSubgraph`：pushHistory('paste')→批量节点+边→structure-changed 防抖落库，一步撤回——新增边自动进链 | CanvasBoard.vue:947-980 |
| ⑧上游面板边驱动 | 面板=BFS 沿边上行——粘贴体 A' 连回原 B/C 后面板自然显示，断链灰显口径随之自洽 | upstream.ts:24-63、CanvasView.vue:751-755 |

### 1c. 联网搜索（9x②「可以开始准备联网搜索接入了」）

| # | 现状事实 | 位置 |
|---|---|---|
| ①开发已全量完成 | 2026-07-19 七步全 ✅（search 模块+双引擎+降级链+CHAT 流接入+前端开关+运维配置页），commit `b9a6580a`~`7b607bab` | 联网搜索开发进度总览.md |
| ②前端已接线 | ChatView 🌐 开关（CHAT 模式会话级 localStorage 持久）+ web CITATION 外链回显 | ChatView.vue:160-170、303-345 |
| ③依赖已入 | jsoup 1.18.3 已进 pom（注释：2026-07 当前稳定线无未修复 critical，升级时再核） | pom.xml:183-189 |
| ④仅剩部署/人工依赖 | SearXNG Docker 部署（`formats:[json]`）、Tavily key（免费 1000 次/月，可选）、端到端人工验证——**零开发量** | 进度总览「部署/人工依赖」 |

## 2. 外部协议调研结论（思考档位映射依据）

- **Anthropic 协议**：`thinking:{type:"enabled", budget_tokens:N}` / `{type:"disabled"}`；budget 下限 1024，且 **max_tokens 必须大于 budget_tokens**（发送前需 clamp）。三档天然映射：关=disabled、标准/深度=enabled+不同预算。
- **OpenAI 兼容系两家口径**（config 声明制，未声明不发，管理员按各家文档自行开启）：
  - `toggle` 风格：请求体加 `thinking:{"type":"enabled"|"disabled"}`——智谱 GLM-4.5+、火山 doubao-seed、Moonshot K2 的 OpenAI 兼容端均此形状（glm-5.1/5.3 实测忽略，属「发了无害但无效」，声明与否由运维定）。仅关/开两态，无深度细分。
  - `effort` 风格：请求体加 `reasoning_effort:"low"|"medium"|"high"`——OpenAI o 系/gpt-5 形状（gpt-5 另有 minimal，为 o 系兼容统一从 low 起）。三档天然映射：关=low、标准=medium、深度=high。
- **档位诚实下发**：能力按模型声明，UI 只显示该模型真实支持的档位集合（toggle 模型两档、effort/Claude 协议三档）——不做「选了深度但模型无感」的假 UI。

## 3. 用户决策（2026-08-31 拍板）

| # | 问题 | 决策 |
|---|---|---|
| Q1 | 思考强度档位 | **三档：关/标准/深度**——Claude 协议吃满三档（disabled / enabled+中预算 / enabled+大预算）；OpenAI 系按声明风格映射 |
| Q2 | HOLD 预扣联动 | **档位联动抬估算**——出量估算上限 标准×2（4096）/深度×4（8192），防深度思考结算远超预扣频繁挂 DEBT；多退少补已有 |
| Q3 | 选择器显示范围 | **provider 配置声明**——chat provider `config` jsonb 增 thinking 声明，模型列表接口下发档位集合，前端只对有声明的模型显示选择器；无声明=现状 UI 零变化（参照媒体域 capabilities 先例） |
| Q4 | 粘贴/副本连线口径 | **显式可选项（要求明显）**：「保留连线」开关——**是**=粘贴带跨集边+副本克隆连线，且**允许平行重复边**（不去重）；**否**=创建副本与复制粘贴**都不保留**原节点连线 |

## 4. 功能需求

### IX-1 思考强度三档选择（9x①）P0

| 子项 | 需求 |
|---|---|
| ①档位模型 | 新枚举 `ThinkingLevel { OFF, STANDARD, DEEP }`（llm dto 包）。语义：OFF=明确关思考；STANDARD=开+中预算；DEEP=开+大预算。**请求缺省（null）=现状行为（不发思考参数，模型默认）**——老前端/未选档零影响 |
| ②LlmRequest 扩展 | 新增 `thinkingLevel`（ThinkingLevel，可空）；**保留 `disableThinking` 字段不动**（10+ 记忆内部调用零改动）。消费优先级：`thinkingLevel != null` 用之；否则 `disableThinking=true`→OFF；否则不发参数 |
| ③请求链透传（三跳） | `ChatRequest` + `thinkingLevel`（String，@Pattern OFF/STANDARD/DEEP，可空）→ `generateReplyStream` 塞 `ExecutionContext.thinkingLevel` → `DefaultChatStrategy` 两处 builder 设入 LlmRequest。SSE/WS 两入口共用 ChatRequest，一次透传双路生效 |
| ④ClaudeProvider 三档 | OFF（或 disableThinking）→ 现状 disabled；STANDARD → `thinking:{type:"enabled",budget_tokens:标准预算}`；DEEP → 同+深度预算。预算常量 `llm.thinking.budget-standard=2048` / `budget-deep=8192`（@ConfigurationProperties 带默认，运维可调）。发送前 clamp：`max_tokens = max(max_tokens, budget+1024)`（Anthropic 硬约束 max_tokens > budget_tokens） |
| ⑤OpenAICompatibleProvider 声明制 | 读 provider `config` jsonb `thinking` 节（CHAT 域首次消费 config，解析器参照 MediaModelCapabilityService）：`{"style":"toggle"|"effort","models":[可选，仅这些 modelId 启用；缺省=该 provider 全部模型]}`。映射：toggle→OFF 发 `thinking:{type:"disabled"}`、STANDARD/DEEP 发 `{type:"enabled"}`（toggle 无深度细分）；effort→OFF/STANDARD/DEEP 发 `reasoning_effort:low/medium/high`。**无声明=一个参数都不发（现状）** |
| ⑥能力下发 | `AvailableModelVO` + `thinkingLevels`（List\<String\>，可空）：ANTHROPIC 协议 provider=三档全（协议原生，无需声明）；OPENAI_COMPATIBLE 按声明（toggle→[OFF,STANDARD]，effort→三档，models 过滤后为空的模型=null）。模型列表组装点解析 config 填充 |
| ⑦前端选择器 | ChatView 工具行记忆/联网下拉旁同款 n-select「🧠 思考：关/标准/深度」（范式 ChatView.vue:146-170）：选项=当前选中模型的 thinkingLevels（切换模型时按新模型集合重算，当前档不在集合回落第一档）；模型无声明=不显示（现状布局不变）；会话级 localStorage 持久（同 webSearchPref 范式），默认 **STANDARD**（有声明的模型才生效） |
| ⑧前端参数 | `api/chat.ts` ChatSendRequest + `thinkingLevel?`；stores/chat.ts 三处 payload（:212、:217-226、:280-298）带上；api/llm.ts 模型类型 + thinkingLevels。后端 null 兼容，不传=现状 |
| ⑨计费联动（Q2） | `LlmBillingService.holdChat` 出量估算上限按档位放大：null/OFF=2048（现状）、STANDARD=4096、DEEP=8192（系数可配 `llm.thinking.hold-factor-standard=2` / `deep=4`）。调用点（ChatSessionService 发起流式前 hold）传入 level。思考 token 仍按输出价混计（⑧口径不变，价表/usage 零改动）；PROGRESS 流式预估同口径自然跟随 |
| ⑩不参与链路 | Agent/Workflow 的 Llm 节点（LlmCallHandler）不设档位（留扩展点，注释标注）；记忆内部调用 disableThinking=true 语义不变；判定期 `memory.judge.model` 不受影响 |

### IX-2 画布「保留连线」开关——粘贴+副本统一口径（2x）P0

| 子项 | 需求 |
|---|---|
| ①显式开关（Q4） | 画布工具条（🔗 只看关联旁）新增**醒目开关按钮 ⛓**（icon-only 与工具条范式一致，醒目性=激活态高亮+aria-label「连线保留开关」+title 双口径全文说明）：激活态高亮+tooltip 双口径说明（开=副本/粘贴保留原节点连线；关=均不保留）；**localStorage 持久**（`canvas.keepLinksOnCopy`，缺省 **true**——满足 2x 原始诉求且与副本现状一致，粘贴行为变化即本次需求本身）。开关即时生效，无确认弹窗。状态载体=**`utils/canvasPrefs.ts` 模块级 singleton reactive ref**（CanvasBoard 工具条与 CanvasView.onCloneNode 两视图共用同一实时值，切换即时生效不重挂载；实现期细化定稿） |
| ②粘贴-保留=是 | 【实现期细化】crossEdges **恒收集**：`buildCopySet` **不看开关**一律另存 **crossEdges**（恰一端在选中集的非组边，浅拷贝断响应式）；`CanvasClipboard` 接口 + `crossEdges` 字段。**粘贴时点判定**：`pasteSubgraph` 在粘贴当下读 keepLinksOnCopy——开 → 新函数 `remapCrossEdges` 跨集边**单侧重映射**（集内端换新 id、集外端保持原节点 id，语义同 nodeClone.ts:57-58），且**悬挂防护**=集外端点已不在画布存活节点集（复制后原节点被删）丢该边不产断边；remapEdges 诱导边路径签名不变。价值：复制后切开关，按粘贴当下所见生效 |
| ③粘贴-保留=否 | 粘贴时点 keepLinksOnCopy=false=VII-1 现状（仅诱导边）——crossEdges 虽在剪贴板但不消费，粘贴结果与今日分毫不差 |
| ④副本同口径 | 「创建副本」`onCloneNode` 读同一开关：是=现状（克隆全部入/出边）；**否=只克隆节点零边**（`cloneEdgesForDuplicate` 结果弃用，注释标明口径） |
| ⑤平行边口径（Q4） | **不去重**——连按 Ctrl+V / 反复副本对同一外部节点产生平行重复边属预期行为（用户拍板「允许」）；重复边可见、可选中删除（deletable 边现有交互）。组边两条路径继续排除；@占位符/firstFrameNodeId 继续不重写（§1b⑤，与边连回原节点语义一致） |
| ⑥落库/撤回 | 零额外工作：pasteSubgraph 现有 pushHistory('paste')+structure-changed 防抖链自动覆盖新增跨集边；副本走 appendEdges 现链 |
| ⑦上游面板自洽 | A' 连回原 B/C 后：上游面板（边驱动 BFS）显示原上游、断链灰显消失、运行期插值（按 id 全局查）不受影响——三处口径自动对齐，无需改 upstream.ts |

### IX-3 联网搜索收尾（9x②）P1 · 零开发

| 子项 | 需求 |
|---|---|
| ①部署（二选一或双路） | **快路**：申请 Tavily key（免费 1000 次/月）→ 运维配置页（设置→联网搜索）选 Tavily 填 key→「测试连通」。**抗封路**：Docker 部署 SearXNG（配置 `formats:[json]`）→ 配 BuiltIn 引擎 base_url。降级链已有，双路配置则自动兜底 |
| ②jsoup CVE 复核 | 升级窗口核对 1.18.3 之后 CVE（pom 注释已挂口径）；本轮不动版本 |
| ③端到端人工验证 | 用例：开 🌐 问时效问题（如「今天的日期/最新版本」）→ 回答带引用[n]+外链回显（web CITATION）→ 关开关回归纯模型作答 → 会话级持久（刷新保持）→ 无 key 时降级链不炸（明确报错文案） |
| ④文档勾销 | 验证过后 9x「未解决-联网搜索」项移入已解决（口径：开发 2026-07-19 已收，本轮补部署+验证） |

## 5. 非功能需求

- **性能**：思考档位=每请求常数个 JSON 字段+一次 config 解析（provider 加载时缓存，同 models 解析）；budget 抬高仅影响单请求 token 上限（深度档延迟相应变长，属用户主动选择）；画布粘贴 crossEdges 收集=复制时一次 filter，边量级 <1k 无感。
- **安全**：thinking 声明走 provider config（AES 通道既有口径，key 不新增面）；档位参数白名单 @Pattern 枚举校验；无新端点。
- **兼容/回滚**：`thinkingLevel` 全链可空=老前端/未选档行为分毫不变；`disableThinking` 字段与 10+ 内部调用零改动；画布快照无 schema 变更（跨集边就是普通边）；`keepLinksOnCopy` 缺省 true 仅粘贴行为向需求靠拢、开关可关回；全部纯代码 revert 即回现状。
- **依赖**：零新增 npm/maven 依赖。

## 6. 数据模型

- `llm_providers.config`（jsonb，既有列）：CHAT 域新增可选 `thinking` 节（`{"style":"toggle"|"effort","models":[...]}`）——无迁移，运维按需在现有供应商配置入口编辑（不新增专页；示例 JSON 进运维手册）。
- `AvailableModelVO`：+ `thinkingLevels`（可空 List\<String\>），接口向下兼容（老消费端忽略新字段）。
- 画布快照/边结构：零变更。
- 价表/usage/积分表：零变更（思考 token 按输出价混计口径维持）。

## 7. 测试策略

- **后端单测**：①ClaudeProvider：STANDARD/DEEP→thinking enabled+对应 budget+max_tokens clamp 生效；OFF/disableThinking→disabled（现状基线 ClaudeProviderTest.java:207-241 扩展）；②OpenAICompatibleProvider：toggle 风格 OFF/STANDARD 请求体断言、effort 风格三档、**无声明=请求体零思考字段**（锁死「不发无效参数」）、models 过滤；③AvailableModelVO 组装：ANTHROPIC 三档/声明 toggle 两档/models 排除后 null；④holdChat 档位放大：null/OFF=2048、STANDARD=4096、DEEP=8192（系数可配项各一例）；⑤ChatRequest→Context→Strategy 透传断言；⑥@Pattern 非法值 400。
- **前端 vitest**：①选择器可见性=模型 thinkingLevels 驱动（无声明不渲染）；②切模型档位回落；③localStorage 持久+默认 STANDARD；④payload 三处带 thinkingLevel；⑤画布：buildCopySet keepLinks=true 收跨集边/false 仅诱导边、remapEdges 单侧重映射（外端点保原 id）、副本开关关=零边、平行边不去重、Ctrl+Z 一步撤含跨集边；⑥canvasClipboard/nodeClone/CanvasBoard 既有用例双口径全回归。
- **人工测试标记**：①智能对话选深度档→💭 思考过程明显变长、usage 出量增大、积分消耗与预估同级；②glm（未声明）模型无选择器、Claude 协议模型三档全；③画布开关联动：开=粘贴 A（连 B/C）→ A' 连回 B/C、连按 Ctrl+V 出平行边可删、关=副本/粘贴均无线、刷新后开关态保持；④联网搜索：Tavily/SearXNG 配置连通+🌐 开关问答带引用+关闭回归。
- **回归**：记忆内部调用（蒸馏/判定）不受档位影响；SSE 与 WS 双路一致；VII 复制粘贴（诱导边/错开/label 去重）、组边排除、自动保存、undo/redo；PROGRESS 实时消耗显示。

## 8. 边界与不做

- 思考档位不做**预算滑杆**（Q1 拍板三档；数值走配置项运维可调）；不做**思考 token 单独计价腿**（按输出价混计，Q2 只抬预扣估算）；Agent/Workflow 链路不带档位（留扩展点）。
- toggle 风格模型**不显示「深度」档**（协议无此态，诚实下发）；glm-5.1/5.3 忽略思考参数属上游行为——声明开关交给运维判断（§1a⑨），平台不硬编码模型黑名单。
- 画布不做**同向去重**（Q4 拍板允许平行边）；不做「仅保留出边/仅入边」细分；组边永不参与两条链路。
- 联网搜索**零代码**——本项只产部署清单+验证用例；Search 参数（时效/深度/条数）用户面板不做（运维配置页已够）。

## 9. 变更记录

| 日期 | 变更 | 原因 |
|---|---|---|
| 2026-08-31 | 建立规格（IX-1~3，Q1~Q4 拍板；两路代码探查+联网搜索进度核实） | 9x 未解决 2 项 + 2x 未解决 1 项设计 |

## 10. 术语表

| 术语 | 大白话 | 案例 |
|---|---|---|
| 思考强度 | 让模型「想多少再答」的旋钮：关=直接答，标准=想一阵，深度=往死里想 | 深度档答数学题更准但更慢更贵 |
| budget_tokens | Claude 协议里「最多花多少 token 思考」的预算数字 | 标准 2048 / 深度 8192（配置可调） |
| toggle / effort 风格 | OpenAI 系两种「发思考参数」的形状：toggle=开关对象，effort=低中高三档 | 智谱/火山=toggle；OpenAI o 系=effort |
| 能力声明 | 供应商配置里写一句「我家模型支持思考」，平台照此显示选择器 | 不声明就不显示、参数一个不发 |
| 跨集边/诱导边 | 诱导边=复制集内部互相的线；跨集边=一头在集内一头在集外的线 | 复制 A（连着 B）→A' 连回 B 即跨集边保留 |
| 单侧重映射 | 克隆/粘贴时只把「自己这头」换成新 id，对面那头还是原节点 | A'→B：A' 是新的，B 还是原来那个 B |
| 平行边 | 同方向连两点的重复线 | 连按 Ctrl+V 粘两份，A' 和 A'' 都连 B |
| SearXNG / Tavily | 自建聚合搜索引擎 / 外部搜索 API 供应商 | 无 key 时自建兜底，有 key 走 Tavily |
