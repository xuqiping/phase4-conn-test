# 计划 · 人工测试遗留问题修复IX（Chunk A 思考强度 / B 画布连线开关 / C 文档收尾+联网部署）

> Phase 2 产出，源自 [specs/人工测试遗留问题修复IX设计.md](../specs/人工测试遗留问题修复IX设计.md)（Q1~Q4 拍板已入规格 §3）。
> 硬闸门：本文件过审并获明确许可前不写实现码。

## 〇、总览与依赖序

```
A1 枚举+三跳透传(后端) → A2 ClaudeProvider 三档+clamp → A3 OpenAI声明制+能力下发 → A4 计费联动 → A5 前端选择器 → A6 A轮收口
B1 画布纯函数(crossEdges+单侧重映射) → B2 画布接线(工具条开关+副本门) → B3 B轮收口
C 文档收尾+联网搜索部署清单(依赖 A6+B3 全绿)
```

A 与 B 完全并行无依赖；C 串行在最后。A1→A2/A3 可并行（都只依赖 A1 的枚举）；A4 依赖 A1（透传到位才能把 level 传进 hold）；A5 依赖 A3（前端要吃 thinkingLevels 下发）。

**对规格的两处实现期细化**（critique 自查后回写，原因随行）：
1. §4 IX-2②③ 的 `buildCopySet(keepLinks)` 改为 **crossEdges 恒收集 + 粘贴时按当时开关取用**——否则「复制后关开关再粘贴」会粘出与所见相反的连线，所见即所得优先；收集成本一次 filter 可忽略。
2. 开关状态存 **模块级单例响应式 ref**（新 `utils/canvasPrefs.ts`）而非组件私有——CanvasBoard（工具条+粘贴）与 CanvasView（副本）两处同读同写，prop drilling/事件透传易漏同步。

---

## Chunk A · 思考强度三档（IX-1，P0）

### A1 枚举 + 三跳透传（后端地基）

- **目标**：`ThinkingLevel` 从 HTTP/WS 请求一路到 `LlmRequest`，缺省 null=现状零参数。
- **动作**（伪代码）：
  ```
  新 llm/dto/ThinkingLevel.java: enum { OFF, STANDARD, DEEP }
  LlmRequest.java: + ThinkingLevel thinkingLevel  // 可空，无 @Builder.Default（坑1）
    消费优先级注释: thinkingLevel != null 用之; 否则 disableThinking=true→OFF; 否则不发参数
  ChatRequest.java: + String thinkingLevel  @Pattern("OFF|STANDARD|DEEP") 可空
  ExecutionContext.java: + ThinkingLevel thinkingLevel
  ChatSessionService.generateReplyStream(:560-568 一带):
    context.setThinkingLevel(request.getThinkingLevel() == null ? null : ThinkingLevel.valueOf(...))
    （valueOf 前置 Pattern 已挡非法值；再 try-catch 兜 null 防御）
  DefaultChatStrategy.java:28/51 两处 builder: .thinkingLevel(context.getThinkingLevel())
  ```
- **涉及文件**：ThinkingLevel.java(新)、LlmRequest.java、chat/dto/ChatRequest.java、ExecutionContext.java、ChatSessionService.java、engine/strategy/DefaultChatStrategy.java + 对应 test
- **依赖**：无
- **验证**：单测——ChatRequest 带 OFF/STANDARD/DEEP 到 LlmRequest 三值透传；null 透传 null；非法串 Pattern 400；`disableThinking(true)+thinkingLevel=null`→OFF 语义（provider 层单测 A2 做，此处只锁字段）。

### A2 ClaudeProvider 三档 + 预算 clamp

- **目标**：Anthropic 协议吃满三档；预算/上限可配；硬约束不炸。
- **动作**（伪代码）：
  ```
  新 config/LlmThinkingProperties.java(@ConfigurationProperties "llm.thinking"):
    budgetStandard=2048; budgetDeep=8192; holdFactorStandard=2; holdFactorDeep=4
  ClaudeProvider.java(:178-181 扩展):
    resolve(req): level = req.thinkingLevel != null ? 之
                  : (TRUE.equals(req.disableThinking) ? OFF : null)
    null → 不放 thinking 键（现状）
    OFF → thinking:{type:disabled}（现状分支保留）
    STANDARD/DEEP → budget = max(1024, props.budget*)
                    body.thinking = {type:enabled, budget_tokens:budget}
                    body.max_tokens = max(现有 max_tokens, budget + 1024)   // Anthropic 硬约束
  ```
- **涉及文件**：config/LlmThinkingProperties.java(新)、ClaudeProvider.java、ClaudeProviderTest.java
- **依赖**：A1
- **验证**：单测——STANDARD→enabled+2048、DEEP→enabled+8192、配置 512→clamp 1024、max_tokens 4000+DEEP→clamp 9216、max_tokens 20000+DEEP→不降、OFF/disableThinking→disabled、null→无 thinking 键（现状基线 ：207-241 扩展）。

### A3 OpenAI 系声明制 + 能力下发

- **目标**：toggle/effort 两风格按 provider config 声明发参数；模型列表带 thinkingLevels；无声明零参数零 UI。
- **动作**（伪代码）：
  ```
  新 llm/dto/ThinkingSpec.java(record): { style: TOGGLE|EFFORT, models: Set<String>|null }
  LlmConfig.java: parseThinkingSpec(configJson) → ThinkingSpec|null
    （jsonb 坏/缺 → warn 一行 + null，不炸 provider 创建；构造时解析一次随 provider 缓存，坑3/4）
    createProvider: OPENAI_COMPATIBLE 分支把 spec 传入构造
  OpenAICompatibleProvider.java(:327-351 buildRequestBody):
    spec == null 或 level == null 或 (spec.models 非空 && !spec.models.contains(model)) → 不发（现状）
    TOGGLE: OFF→thinking:{type:disabled}; STANDARD/DEEP→thinking:{type:enabled}
    EFFORT: OFF/STANDARD/DEEP→reasoning_effort:"low"/"medium"/"high"
  模型列表组装(LlmGateway.listAvailableModels 一带，实现轮精确定位):
    ANTHROPIC 协议 → thinkingLevels=[OFF,STANDARD,DEEP]（协议原生，零声明）
    其他 → spec 按 models 过滤后: TOGGLE→[OFF,STANDARD]; EFFORT→三档; 无→null
  AvailableModelVO.java: + List<String> thinkingLevels（可空，老消费端忽略）
  ```
- **涉及文件**：ThinkingSpec.java(新)、LlmConfig.java、OpenAICompatibleProvider.java、LlmGateway.java、llm/dto/AvailableModelVO.java + 对应 test
- **依赖**：A1
- **验证**：单测——TOGGLE 两档请求体断言、EFFORT 三档、无声明请求体零思考字段（锁死）、models 过滤命中/不命中、坏 JSON 容错回落 null、ANTHROPIC 协议三档下发、VO 组装。

### A4 计费联动（HOLD 预扣档位放大）

- **目标**：深度思考不把用户挂成常客 DEBT（Q2 拍板）。
- **动作**（伪代码）：
  ```
  LlmBillingService.holdChat(:164-196): + ThinkingLevel level 参数（重载保旧签名=现状）
    estOutputCap = min(maxTokens, props.holdEstMaxTokens/*=2048 既有配置*/)
    level == STANDARD → cap × holdFactorStandard; DEEP → × holdFactorDeep; null/OFF → 原样
  ChatSessionService 发起流式前 hold 调用点: 传 context.getThinkingLevel()
  核对(实现轮): PROGRESS 流式周期估算若独立计算，同口径跟随；结算/取消(settleChatCancelled 按已产 token)零改动
  ```
- **涉及文件**：LlmBillingService.java、ChatSessionService.java、LlmThinkingProperties.java(复用)、LlmBillingServiceTest.java
- **依赖**：A1
- **验证**：单测——null/OFF=2048、STANDARD=4096、DEEP=8192、系数改 3/5 生效、旧签名重载回归不变；取消路径按已产 token 多退少补既有用例不红。

### A5 前端选择器

- **目标**：模型声明驱动显隐；会话级持久；切模型档位回落；payload 三处带字段。
- **动作**（伪代码）：
  ```
  api/llm.ts: 模型类型 + thinkingLevels?: string[]
  api/chat.ts: ChatSendRequest + thinkingLevel?: 'OFF'|'STANDARD'|'DEEP'
  stores/chat.ts: 三处 payload(:212、:217-226、:280-298) 带上（SSE+WS 同源 sendMessage 组装，双路天然一致）
  ChatView.vue 工具行(记忆/联网下拉旁 :146-170 范式):
    <n-select> 「🧠 思考」 options=selectedModel.thinkingLevels 映射{关/标准/深度}
    显隐: thinkingLevels 非空才渲染（无声明=现状布局）
    持久: STORAGE_KEYS.CHAT_THINKING_LEVEL，默认 STANDARD（仅对有声明的模型生效）
    watch(selectedModel): 新模型 levels 不含当前档 → 回落 levels[0]（坑5/14）
    aria-label="思考强度"; 键盘操作走 n-select 原生
  ```
- **涉及文件**：api/llm.ts、api/chat.ts、stores/chat.ts、views/ChatView.vue、constants(storage keys 所在文件) + vitest
- **依赖**：A3（下发字段）
- **验证**：vitest——有声明模型渲染选择器三档、无声明不渲染、切模型回落第一档、localStorage 持久+默认 STANDARD、payload 带字段（发送函数断言）、非法持久值回落 STANDARD。

### A6 A 轮收口测试

- **动作**：后端 mvn test 全量 + 前端 vitest 全量 + vue-tsc 0 错；本机手测标记——SSE 与 WS 双路各发一档断言到达（网络面板/日志）；深度档肉眼验 💭 思考变长+usage 出量增大。
- **验证**：全绿 + 手测记录入变更记录。

---

## Chunk B · 画布「连线保留」开关（IX-2，P0）

### B1 纯函数层：crossEdges + 单侧重映射

- **目标**：剪贴板快照携带跨集边；重映射外端点保原 id；悬挂边防护。
- **动作**（伪代码）：
  ```
  canvasClipboard.ts:
    CanvasClipboard 接口 + crossEdges: CanvasEdge[]
    buildCopySet(nodes, edges, selectedIds):
      innerEdges = 现状双端 filter（不动）
      crossEdges = edges.filter(非组端点).filter(恰一端在选中集).map(剥 class 浅拷贝)   // 恒收集（细化1）
    remapCrossEdges(clip, keyToNewId, aliveNodeIds):
      每条: 集内端→新 id, 集外端→原 id（nodeClone.ts:57-58 语义）
      集外端节点已不在 aliveNodeIds → 整边丢（防悬挂渲染，坑9）
      新 id: edge-{src}-{tgt}-{ts}-{remapSeq++}（同族规则）
  ```
- **涉及文件**：components/canvas/canvasClipboard.ts、canvasClipboard.test.ts
- **依赖**：无
- **验证**：单测——恰一端判定（入边/出边双向）、单侧重映射方向正确、外端点已删丢边、组端点边不进 crossEdges、剥 class、keepLinks=false 调用侧不用 crossEdges（B2 接线后回归）。

### B2 接线层：工具条开关 + 粘贴/副本双门

- **目标**：显眼开关（Q4「要求明显」）统一管粘贴与副本；平行边不去重。
- **动作**（伪代码）：
  ```
  新 utils/canvasPrefs.ts:
    const keepLinksOnCopy = ref(load('canvas.keepLinksOnCopy') ?? true)   // 非法值回落 true（坑10）
    setKeepLinksOnCopy(v) → ref + localStorage 同步
  CanvasBoard.vue:
    工具条(✨ 一键整理旁): <button class=toolbar-btn :aria-pressed>
      「🔗 连线保留」 激活态高亮 + title 双口径说明；点击 setKeepLinksOnCopy(!v)
    pasteSubgraph(:947-980):
      newEdges = remapEdges(clip, keyToNewId)
      keepLinksOnCopy.value && (newEdges += remapCrossEdges(clip, keyToNewId, 现存节点id集))  // 粘贴时点判定（细化1）
      其余链路(pushHistory/落库/pasteCount)不动
  CanvasView.vue onCloneNode(:2636-2644):
    keepLinksOnCopy.value ? cloneEdgesForDuplicate(...) : []   // 否=零边（Q4）
  ```
- **涉及文件**：utils/canvasPrefs.ts(新)、components/canvas/CanvasBoard.vue、views/CanvasView.vue、CanvasBoard.test.ts
- **依赖**：B1
- **验证**：组件测试——开关默认开、点击翻转+持久、开=粘贴带跨集边+副本带边、关=粘贴仅诱导边+副本零边、连按 Ctrl+V 平行边不去重、Ctrl+Z 一步撤含跨集边、VII 既有用例（诱导边/错开/label 去重/undo）不红。

### B3 B 轮收口测试

- **动作**：vitest 全量 + vue-tsc 0 错；手测标记——复制 A（连 B/C）→粘贴 A' 连回 B/C、上游面板显示原上游、连按出平行边可逐条删、关开关副本/粘贴均无线、刷新开关态保持。
- **验证**：全绿 + 手测记录入变更记录。

---

## Chunk C · 文档收尾 + 联网搜索部署清单（依赖 A6+B3）

- **速查表**：`08-智能对话与流式.md` 增思考档位口径（三档语义/预算默认/预扣联动/toggle 无深度档）。
- **feature-map/user-ops 增补**：智能对话（思考选择器显隐与档位）、无限画布创作页（连线保留开关双口径+平行边属预期+副本关=零边+占位符仍指原节点的断链灰显口径）。
- **测试方案**：`docs/测试方案/人工测试遗留问题修复IX测试方案.md`（新，O 系列思考强度 / P 系列画布开关 / Q 系列联网搜索部署验证）。
- **运维手册注记**：provider `config` jsonb `thinking` 节示例 JSON（toggle/effort/models 三例）；联网搜索部署两路清单（Tavily key 快路 / SearXNG Docker `formats:[json]` 抗封路）+ 端到端验证步骤（§4 IX-3③）。
- **问题单**：9x 两项、2x 一项挂「已实现，待人工验证（修复IX）」+commit 号；联网项口径写明「开发 2026-07-19 已收，本轮补部署+验证」。

---

## 技术坑点预判

| # | 坑 | 规避 |
|---|---|---|
| 1 | `thinkingLevel` 误加 `@Builder.Default` 非空默认 → 全局请求行为静默改变 | 字段可空无默认；单测锁「null→不发参数」基线 |
| 2 | Anthropic 硬约束：budget_tokens ≥1024 且 max_tokens 必须 > budget → 400 | budget=max(1024,配置)；max_tokens=max(现值, budget+1024) 只抬不降（A2 单测四象限） |
| 3 | config jsonb 每请求解析 → 每 token 白耗 | LlmConfig.createProvider 构造时解析一次随 provider 缓存；providers 重载自然重建 |
| 4 | thinking 声明坏 JSON → provider 创建炸 / 每 provider 刷屏 warn | try-catch 回落 null=无声明；warn 单行节流 |
| 5 | localStorage 存 DEEP，切到 toggle 模型（只有关/标准）→ n-select 显示裸值 | watch(selectedModel) 档位不在新集合回落 levels[0]（联动 L2） |
| 6 | SSE 与 WS 两路 payload 漏改一处 → 只有一路生效 | 组装集中在 stores/chat.ts 一处函数，两路同源；A6 双路手测断言 |
| 7 | holdChat 放大后 PROGRESS 估算若独立计算 → 预扣与实时显示口径漂移 | 实现轮核对 PROGRESS 估算源，同 holdChat 口径复用 |
| 8 | crossEdges 外端点节点在「复制后、粘贴前」被删 → 悬挂边 vue-flow 渲染断裂 | remapCrossEdges 过滤 aliveNodeIds，静默丢边（同 fileId 失效静默口径） |
| 9 | `canvas.keepLinksOnCopy` 非法值（手改 localStorage）→ 开关态诡异 | 解析失败回落 true（默认开）；propPanel.width 同款先例 |
| 10 | 平行边同 source/target 贝塞尔完全重叠 → 用户以为没连上/删一条还有 | 拍板接受（Q4 允许平行边）；user-ops 写明「连按粘贴=平行边属预期，可逐条删」 |
| 11 | 副本「关」改变既有用户肌肉记忆（副本带边） | 开关默认**开**=副本现状不变；只有显式关才变（联动 L7） |
| 12 | 复制后切开关再粘贴 → 行为与用户预期相反 | crossEdges 恒收集+**粘贴时点**判定（细化1），所见即所得 |
| 13 | CanvasBoard 与 CanvasView 各持开关状态 → 不同步 | 单例响应式 ref（utils/canvasPrefs.ts），两处同 import（细化2） |

## 安全检查清单（P3 逐项验）

- [x] `thinkingLevel` `@Pattern("OFF|STANDARD|DEEP")` 白名单（防任意串进 valueOf/日志/透传）——ChatRequest 已加 + resolveThinkingLevel 二道容错
- [x] provider config 解析全 try-catch 容错，坏数据不炸 provider 创建与模型列表接口——ThinkingSpec.parse warn+null，5 用例
- [x] 模型列表接口仅新增只读字段，鉴权/权限注解零变动——AvailableModelVO.thinkingLevels
- [x] 无新端点、无新依赖；thinking 声明编辑走既有供应商配置入口（`llm:config` 权限面）
- [x] 画布快照后端透传不变（边就是普通边，无新解析面）；开关纯前端 localStorage

## 功能联动点清单（只列正向，边界含反向/半选/批量）

| # | 触发 | 联动 | 边界 |
|---|---|---|---|
| L1 | 选思考档位 | HOLD 预扣估算放大→余额预警线/PROGRESS 显示跟随 | null/OFF=现状 2048；中途取消按已产 token 多退少补（既有） |
| L2 | 切换模型 | 选择器选项集按新模型 thinkingLevels 重算；当前档不在集合回落 levels[0] | 无声明模型→选择器隐藏+payload 停发；切回支持模型→持久档恢复 |
| L3 | Claude 协议选 STANDARD/DEEP | max_tokens 自动抬高 ≥budget+1024 | 已设更大 max_tokens 不降；OFF/null 不动 max_tokens |
| L4 | 画布「连线保留」开关切换 | 粘贴与副本两路径即时同变 | 刷新保持；**粘贴行为按粘贴时点开关**（复制后切换不影响本次粘贴，细化1） |
| L5 | 粘贴含跨集边 | 上游面板显示原上游、断链灰显消失、运行期插值不变 | 外端点已删→该边静默丢；组边永不参与 |
| L6 | 连按 Ctrl+V / 反复副本 | 平行边累积（不去重） | 可逐条选中删；Ctrl+Z 一步撤一整次粘贴 |
| L7 | 副本（开关关） | 只出节点零边 | data 内 `@{{node}}` 占位符/firstFrameNodeId 仍指原节点（不重写口径）——边驱动面板不显上游+可能断链灰显，user-ops 写明 |

## 运维考量清单

| 类 | 结论 | 落字 |
|---|---|---|
| 可观测性 | **做** | 发起流式 DEBUG 一行含 thinkingLevel；声明解析失败 warn；消耗明细既有 llm_usage_logs/PROGRESS 零新增 |
| 配置开关 | **做** | 预算/系数 @ConfigurationProperties 可调；供应商删 thinking 节即回「零参数+UI 隐藏」现状（不发版回退）；画布开关用户侧自服务 |
| 可回滚 | **做预案** | 零 DB 迁移零 schema 变更，代码 revert 即回；localStorage 残留档位/开关值旧版无害忽略 |
| 限流/熔断 | **不做** | 上游 LLM 既有超时/重试/降级链兜深度档长延迟；A6 手测核对 WS/SSE 流式空闲超时对深度档余量，不足则调配置不新开发 |
| 运维入口 | **做** | 既有供应商配置页编辑 thinking 声明+手册示例（C chunk）；联网搜索配置页既有 |
| 告警阈值 | **后续再说** | 无新指标面；思考消耗异常监控留积分/安全体系后续轮 |
| 容量/性能 | **不做** | config jsonb 微量；crossEdges=画布边量级一次 filter |

## 变更记录

| 日期 | 变更 | 原因 |
|---|---|---|
| 2026-08-31 | 建立 plan（A/B/C 三 chunk，A1-A6/B1-B3/C；critique 后两处实现期细化回写规格） | 修复IX 规格过审进入 P2 |
| 2026-08-31 | **Chunk A+B 全部完成**（A1 `2e2da33`/A2-A3 同 commit、A4 `0c6d40c7`、A5 `3d9019f6`、B1 `fb0e3981`、B2+B3 `2152c81b`）；后端 mvn 2706/2706、前端 vitest 988/988、vue-tsc 0；规格 IX-2①②③ 按实现定稿回写（⛓ icon-only 按钮、crossEdges 恒收集+粘贴时点判定、canvasPrefs singleton）；Chunk C（文档收尾+联网部署清单）待授权 | 用户令「进phase3 A+B全开」 |

## 术语表

| 术语 | 大白话 | 案例 |
|---|---|---|
| 三跳透传 | 请求字段经三层数据壳接力传到真正干活的地方 | ChatRequest→ExecutionContext→LlmRequest |
| @Pattern 白名单 | 只准填列表里的值，别的直接报 400 | thinkingLevel 只认 OFF/STANDARD/DEEP |
| clamp | 把数值夹在合法区间里 | budget 配 512 自动抬到 1024 |
| 声明制 | 供应商配置里写一句「支持」，平台才发参数 | 不声明=现状一个参数不发 |
| 单侧重映射 | 复制体只换自己那端的 id，对面保持原节点 | A'→B：B 还是原 B |
| 悬挂边 | 一端指向已删节点的断线 | 粘贴时校验存活，死了就静默丢 |
| 平行边 | 同方向连同样两点的重复线 | 连按 Ctrl+V 属预期，可逐条删 |
| 单例 ref | 全工程共用一个响应式状态 | 画布开关两组件同读同写 |
| SearXNG | 自建聚合搜索引擎（Docker 一只） | 联网搜索无 key 时的自建兜底 |
