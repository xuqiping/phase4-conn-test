---
description: "无限画布增强（小地图 + 撤销重做 + 导入导出 + 画布助手对话）的实现计划"
created-date: 2026-08-07
---

# Implementation Plan for 无限画布增强（MiniMap / 撤销重做 / 导入导出 / 画布助手）

> Phase 2 产出。Phase 3 逐步勾选执行。只含伪代码，不含真代码。
> 来源规格：[../feature-map/无限画布创作页.feature-map.md](../feature-map/无限画布创作页.feature-map.md)（现有 MVP 代码地图）+ 对标 basketikun/infinite-canvas 的差距分析。无独立 PRD，非功能需求沿用 CLAUDE.md（JWT/ownership/计费/R<T>）。
> 安全策略：沿用 canvas 模块既有策略（`@RequirePermission("canvas:write")` + `loadOwned` 归属咽喉点 + LlmGateway 自动计费归户）。
> 性能目标：撤销栈 ≤50 帧；助手单轮 prompt ≤8000 字符（复用 `PROMPT_MAX_LEN`）；导入 snapshot ≤5MB（沿用后端上限）。
> **文档规模**：≤5000 tokens。

## 背景与目标

现有无限画布 MVP（C1-C13 + S12/S13 已落地，见 feature-map）在**工程化/视频处理/编排**上已超出对标项目，但 **UI 交互细节**与**AI 对话辅助**两类体验落后。本 plan 补两块（对标差距分析的 第 1、3 项）：

- **Chunk A — UI 追平**：小地图（MiniMap）、撤销/重做、导入/导出。纯前端，不碰后端表。
- **Chunk B — 画布助手**：选中节点 + 上游产物作上下文 → 多轮对话 → 回复可「插入画布」产新文本节点。薄后端 relay 端点，复用既有计费/归属基建。

不新增节点类型（助手插入的是既有 `text` 节点），**零 DB 变更、零 Flyway**。

## 技术实现坑点预判与规避措施

| 技术点/功能块 | 可能的坑 | 规避措施 | 验证方式 |
|---|---|---|---|
| `@vue-flow/minimap` 依赖 | 与 `@vue-flow/core` 版本不齐 → peer 冲突 / 视口不同步 | 装与现有 core 同主版本线；lockfile 锁定 | 装后 `vue-tsc` 过 + 平移主画布看 minimap 视口框跟随 |
| 撤销栈 vs Vue Flow 拖动状态 | 拖动中 Vue Flow 内部已有未提交 position，undo 直接换数组会"回弹/抖动" | 仅在 `@node-drag-stop`（拖动结束）push 栈；栈存 `structuredClone` 深拷贝快照 | 拖动节点→松手→Ctrl+Z 位置精确回弹，无抖动 |
| （性能坑）深拷贝开销 | 百节点画布每次操作 `structuredClone` 整 snapshot 卡顿 | 栈硬上限 50（超丢最旧）；连续文本输入防抖 800ms 合并成一帧（复用 `scheduleSave` 节流范式） | 50+ 节点画布连打字 → 栈只增 1 帧；Ctrl+Z 连按流畅 |
| 导入节点 id 冲突 | 导入 JSON 的 `node-xxx` id 与当前会话已存在 id 撞 → Vue Flow 渲染异常 | MVP 用**替换模式**（导入前清空当前，confirm 提示"覆盖"），天然无冲突；未来改合并模式时再做 id 重映射 | 同一导出文件二次导入不报错；导入后 undo 回到导入前 |
| 助手 context 注入重复 | 多轮对话每轮重拼 context → token 翻倍、回复重复 | 每轮重拼但**历史只保留最近 N 轮**（MVP N=10）；context 截断每条上游 output ≤2000 字 | 连续问 5 轮，第 5 轮 prompt 不含第 1 轮完整 context 重复 |
| 助手历史持久化膨胀 | 历史存 `node.data` 随 snapshot 入库 → 撑爆 5MB JSONB | MVP 历史存**组件 ref（会话级，刷新丢）**，不入快照；持久化留后续 | 保存画布后重进，助手历史清空（预期）；snapshot 体积不增长 |
| MiniMap 暗色主题 | 默认白底 minimap 在 3 套暗主题下刺眼 | 用 `--color-*` CSS 变量定制 node/mask/bg 色 | 切换 deep-space/dark-pro/cyber-glow 三主题 minimap 均协调 |
| （计费坑）助手忘传 userId | 端点漏传 userId → 漏扣费 | 透传 `getCurrentUserId()` 给 `llmGateway.chat(req, userId)`；BillingContext 兜底已就位（05dee79b） | 助手调用后 `llm_usage_log` 有该 userId 行 |

## 安全检查清单

- [x] **鉴权/授权**：助手端点 `@RequirePermission("canvas:write")` + `loadOwned` 归属校验（复用 runNode 范式）。MiniMap/undo/导入导出纯前端，无新端点。
- [x] **输入校验**：助手 `messages` 数量 ≤50、单条 content ≤8000 字符、model 空走默认；导入 JSON size ≤5MB + 结构校验（必须有 nodes/edges 数组）。后端 `AssistantChatRequest` 加 `@Valid`。
- [x] **数据加密**：无新增敏感数据。助手对话明文经 HTTPS 传输（沿用）。
- [x] **审计日志**：助手调用经 LlmGateway → 自动落 `llm_usage_log`（已有后台查询）。导入/导出无敏感操作，不额外审计。
- [x] **错误处理**：助手失败返固定话术，不透传 `e.getMessage()`（沿用 runText 范式）；导入解析失败报错且**不清空当前画布**。
- [x] **CORS/CSRF**：沿用全局配置，无新增。
- [x] **依赖安全**：`@vue-flow/minimap` 装后跑 `npm audit`。
- [x] **其他**：导入文件类型白名单（仅 `.json`）+ size 预检，防超大文件 DoS。

## 性能考虑与验证计划

- [x] **查询效率**：助手端点不读 snapshot（context 前端拼），无 DB 查询；`loadOwned` 单行主键查（已有索引）。无 N+1。
- [x] **缓存策略**：无需缓存。
- [x] **并发处理**：助手按钮防重入（复用 `runningNodeId` 范式，独立 `assistantBusy` ref）；undo/redo 单用户单会话无竞争。
- [x] **资源使用**：撤销栈内存上限 50 帧；导入 size 上限 5MB。
- [x] **性能验证**：Phase 4 测——50 节点画布 undo/redo 响应 <100ms；助手首轮端到端 <LlmGateway 常规延迟。

## 功能联动点清单（含正向/反向/半选/批量边界）

> 仅列正向易漏 bug 的联动。

1. **撤销/重做 ↔ 任意画布变更**（A2）
   - 触发：增/删节点、增/删连线、拖动节点（drag-stop）、属性编辑（防抖后）
   - 联动：每个**离散用户操作**进栈一帧
   - 边界（必覆盖）：
     - 拖动只 push 一次（drag-stop，非 dragstart/mousemove）✅ 防爆栈
     - 连续文本输入防抖合并成一帧 ✅
     - 批量删除（未来多选）一次一帧，非每节点一帧
     - **节点运行结果（onRunNode 异步写 outputText）不进栈**（外部产出，undo 会误导；用户可手动删节点）
     - undo 到栈底 → `canRedo=true, canUndo=false`；redo 到顶 → 反之
     - 新开/切换画布 → 栈清空（不跨会话）
     - 导入算一帧（导入后可 undo 回导入前）

2. **MiniMap ↔ 主画布视口**（A1）
   - 触发：主画布平移/缩放、minimap 点击/拖拽
   - 联动：双向同步视口框
   - 边界：节点超出 minimap 当前 bounds → minimap 自动扩展；空画布 → minimap 不报错（显示空）

3. **导入 ↔ 当前画布**（A3）
   - 触发：选文件 → 解析
   - 联动：**替换**当前 nodes/edges（confirm 提示覆盖）→ record 入栈 → scheduleSave
   - 边界：非法 JSON → 报错 + **不清空**当前画布；size>5MB → 前端预检拒；导入后 undo 可回退

4. **助手 context ↔ 选中节点 + 上游**（B2）
   - 触发：选中节点变化
   - 联动：面板 context 区刷新（选中节点 label + 祖先 outputs）
   - 边界：无选中 → 退化为"通用对话"（context 空）；上游节点未运行/无产出 → 注入"[节点X 无产出]"占位，不报错；上游含断链 → 复用 `brokenMentions` 提示

5. **助手「插入画布」↔ 新节点**（B2）
   - 触发：点「插入画布」
   - 联动：`addNode(text, outputText=回复)` + 若有选中节点则 `addEdge(选中→新)` + record + scheduleSave
   - 边界：无选中 → 插入孤立节点 + 提示"未连接"；插入后可 undo

## 运维考量清单（7 类，逐条落字）

1. **可观测性**：做（复用）。助手经 LlmGateway，traceId/计费日志已就位（`billing.onSuccess/onFailure`）；节点运行日志 `CanvasNodeRunnerService` 已有。无新增埋点。
2. **配置开关**：后续再说。MiniMap/undo/导入导出无需开关；助手 MVP 不独立开关（绑 `canvas:write`），若需灰度再加 `canvas.assistant.enabled`。
3. **可回滚**：做（天然无需）。零 DB 变更、零 Flyway；助手历史不入 snapshot，`node.data` 增量字段后端不强 schema，天然向前兼容。
4. **限流/熔断/降级**：做。助手按钮防重入；provider 超时由 LlmGateway 既有 provider 配置兜底；前端失败固定话术。
5. **运维入口**：做（复用）。助手调用落 `llm_usage_log`，后台 `BillingAdminView` 可查可对账。无新增入口。
6. **告警阈值**：后续再说。助手失败率复用 LLM 既有失败告警链路（若有）。
7. **容量/性能预案**：做。撤销栈 ≤50 帧；导入 ≤5MB；助手 context 截断（每上游 output ≤2000 字、历史 ≤10 轮）。大画布（百节点）undo 性能 Phase 4 验证。

## 实现步骤

### Chunk A — UI 追平

- [ ] **Step A1：MiniMap 接入**
  - **目标**：画布右下加小地图，双向同步视口，适配 3 套暗主题
  - **动作**：装 `@vue-flow/minimap`（版本对齐 core）；`CanvasBoard.vue` 在 `<VueFlow>` 内加 `<MiniMap>`；node/mask/bg 色用 CSS 变量
  - **文件**（≤20）：
    - `frontend/package.json`：+ `@vue-flow/minimap` 依赖
    - `frontend/src/components/canvas/CanvasBoard.vue`：+ import MiniMap + template 嵌入 + 配色
  - **依赖**：无
  - **需人工介入**：无
  - **安全检查**：覆盖「依赖安全」（装后 npm audit）
  - **验证**：大画布平移/缩放时 minimap 视口框跟随；点 minimap 主画布跳转；切 3 套主题 minimap 协调

- [ ] **Step A2：撤销重做引擎（useCanvasHistory composable）**
  - **目标**：离散操作进栈，Ctrl+Z / Ctrl+Shift+Z（或 Ctrl+Y）回退/前进
  - **动作**：新建 `useCanvasHistory.ts`（past/future 栈 ≤50，存 `structuredClone(snapshot)`，提供 `record/undo/redo/canUndo/canRedo/reset`）；`CanvasBoard` 在离散变更点（addNode 后、removeNodes/removeEdges 后、onConnect 后、addEdge 后、`@node-drag-stop`）调 record；暴露 undo/redo；`CanvasView` 接键盘（boardRoot focus 时 Ctrl+Z/Y）+ 头部「撤销/重做」按钮（disabled 由 canUndo/canRedo）；属性编辑（`@data-changed`）防抖 800ms 后 record
  - **文件**（≤20）：
    - `frontend/src/components/canvas/useCanvasHistory.ts`：新建，栈 + 深拷贝 + 上限
    - `frontend/src/components/canvas/CanvasBoard.vue`：接 record 点（addNode/remove*/onConnect/addEdge/node-drag-stop）+ 暴露 undo/redo/canUndo/canRedo
    - `frontend/src/views/CanvasView.vue`：键盘监听 + 头部按钮 + data-changed 防抖 record
  - **依赖**：A1（无强依赖，可并行，但同改 CanvasBoard 建议先 A1）
  - **需人工介入**：无
  - **安全检查**：无新端点（纯前端状态）
  - **验证**：增节点→Ctrl+Z 消失→Ctrl+Shift+Z 复现；拖动松手后 undo 位置回弹无抖动；连续打字只产生 1 帧；切换画布栈清空

- [ ] **Step A3：导入 / 导出**
  - **目标**：编辑器头部加「导出」「导入」按钮
  - **动作**：导出 = `getSnapshot()` + 剥 previewUrl（同 onSave 范式）+ Blob 下载 `<画布名>.json`；导入 = 隐藏 `<input type=file accept=".json">` → 读文本 → size 预检 ≤5MB → `JSON.parse` + 结构校验（nodes/edges 数组）→ confirm("导入将覆盖当前画布") → `loadSnapshot` + `record`（可 undo）+ `scheduleSave`
  - **文件**（≤20）：
    - `frontend/src/views/CanvasView.vue`：头部两按钮 + onExport/onImport + 隐藏 file input + 校验/confirm 逻辑
  - **依赖**：A2（导入后 record 依赖历史引擎）
  - **需人工介入**：无
  - **安全检查**：覆盖「输入校验」（类型白名单 .json + size 预检 + 结构校验）
  - **验证**：空画布导出得合法 JSON；导入该 JSON 复原节点/连线；导入非法 JSON 报错且画布不变；导入后 Ctrl+Z 回到导入前

### Chunk B — 画布助手对话

- [ ] **Step B1：后端助手对话端点（无状态 relay）**
  - **目标**：`POST /api/canvas/{id}/assistant/chat` 接 `{messages, model}` → `loadOwned` → `llmGateway.chat(req, userId)` → 返 `{reply, model}`
  - **动作**：`CanvasController` 加端点（`@RequirePermission` + `loadOwned` + 透传 userId）；新建 `AssistantChatRequest`（messages:List<LlmMessage> + model:String，`@Valid` + 数量/长度校验）；新建 `AssistantChatVO`（reply + model）；不读 snapshot（context 前端拼），纯 relay；失败固定话术
  - **文件**（≤20）：
    - `backend/.../canvas/controller/CanvasController.java`：+ assistantChat 端点
    - `backend/.../canvas/dto/AssistantChatRequest.java`：新建（messages + model + 校验注解）
    - `backend/.../canvas/dto/AssistantChatVO.java`：新建（reply + model）
  - **依赖**：无（复用 LlmGateway / CanvasService.loadOwned）
  - **需人工介入**：无（provider 已配）
  - **安全检查**：覆盖「鉴权/授权」（loadOwned）+「输入校验」（messages 数量/长度）+「审计」（LlmGateway 自动落 usage_log）+「计费归户」（透传 userId）
  - **验证**：curl 带 token 调用返回复 + model；他人画布 id → 403；空 messages → 400；超长 message → 400

- [ ] **Step B2：前端助手面板组件**
  - **目标**：右侧浮动面板，含 context 预览 + 对话区 + 输入框 + 「插入画布」
  - **动作**：新建 `AssistantPanel.vue`（v-model:show）；context 区显示选中节点 label + 祖先 outputs（复用 `selectedAncestors`/`buildMentionResolver`，每条截断 ≤2000 字）；对话历史会话级 ref（`AssistantMessage[]`，刷新丢）；发送 = 拼 system("你是画布助手，上下文如下…"+context) + 最近 10 轮历史 + 当前 user → 调 `canvasApi.assistantChat` → 追加回复；「插入画布」emit 给父 → 父 `addNode(text)` + `addEdge(选中→新)` + record + scheduleSave；防重入 `assistantBusy`
  - **文件**（≤20）：
    - `frontend/src/components/canvas/AssistantPanel.vue`：新建
    - `frontend/src/api/canvas.ts`：+ `assistantChat(canvasId, body)`
    - `frontend/src/types/canvas.ts`：+ `AssistantMessage` 接口
    - `frontend/src/views/CanvasView.vue`：挂面板 + `onInsertFromAssistant`（addNode + addEdge + record + scheduleSave）
  - **依赖**：B1（端点）+ A2（插入后 record）
  - **需人工介入**：无
  - **安全检查**：覆盖「错误处理」（失败固定话术）+「资源使用」（context 截断 + 历史 ≤10 轮）
  - **验证**：选中已运行文本节点→问"总结上游"→回复含上游 outputText→「插入画布」产新文本节点+连边；无选中→通用对话+插入孤立节点；上游未运行→context 显示"[X 无产出]"

- [ ] **Step B3：入口接线**
  - **目标**：编辑器头部「助手」按钮开关面板
  - **动作**：`CanvasView` 头部加按钮（Chatbubbles 图标）toggle `showAssistant`；`AssistantPanel` `v-model:show="showAssistant"`
  - **文件**（≤20）：
    - `frontend/src/views/CanvasView.vue`：+ 头部按钮 + showAssistant ref + 图标 import
  - **依赖**：B2
  - **需人工介入**：无
  - **安全检查**：无
  - **验证**：按钮开关面板；面板内完整对话流；插入后自动保存

## 整体验证（功能级）

- [ ] 前端 `vue-tsc` + `vitest` 全绿（现有 canvas 测试无回归；A2 补 useCanvasHistory 单测：栈上限/undo/redo/reset）
- [ ] 后端 `mvn test` 全绿（B1 可补 CanvasController 助手端点单测：鉴权/校验/relay）
- [ ] 关键路径手动 E2E：MiniMap 双向同步 / undo-redo 拖动回弹 / 导入导出往返 / 助手对话+插入
- [ ] 安全检查清单全部完成并验证
- [ ] 计费对账：助手调用后 `llm_usage_log` 有正确 userId + 扣费（非系统调用）
- [ ] 与 feature-map 对齐复核（不破坏既有 ownership/计费/快照契约）

## 术语表（专业术语 · 大白话 · 案例）

| 术语 | 大白话 | 简单案例 |
|---|---|---|
| MiniMap（小地图） | 画布缩略图，框出当前视口位置，点哪跳哪 | 大画布找不着节点时看右下角小图定位 |
| 撤销/重做栈（undo/redo stack） | 操作历史记录簿，往前翻回退、往后翻恢复 | 误删节点→Ctrl+Z 找回 |
| structuredClone（深拷贝） | 把对象连嵌套结构完整复制一份，改副本不影响原件 | 栈里存的是 snapshot 的副本，undo 不会改坏当前状态 |
| relay 端点（中继接口） | 后端不干逻辑，只转发给 LLM 再把回复传回前端 | 助手端点不读画布数据，context 由前端拼好整包发来 |
| 计费归户 | 调 LLM 的花费算到具体用户头上 | 助手调用透传 userId，LlmGateway 自动从该用户钱包扣积分 |
| context（上下文） | 喂给 AI 的背景信息 | "你选中了节点A，它的上游产出了XXX" |

## 备注

- 偏离计划在此注明。建议执行顺序：A1 → A2 → A3 → B1 → B2 → B3（A/B 可并行若两人；单人按序）。
- 本 plan 未注册进 `总路由.md`，Phase 3 启动时可补一行索引。
- 助手历史持久化（跨会话保留对话）留作后续，MVP 会话级即可。
- 导入"合并模式"（不覆盖而是追加）留作后续，MVP 替换模式足够且避 id 冲突。
