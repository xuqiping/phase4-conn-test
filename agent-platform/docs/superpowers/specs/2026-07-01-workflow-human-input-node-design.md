# 工作流人机交互节点（HUMAN_INPUT）设计

> 速查表 11「待增修改」第 1 项。日期 2026-07-01。分阶段：Phase 1 = 方案 A（HUMAN_INPUT 节点），Phase 2 = 方案 C（sidecar 环支持，动态多轮）。

## 1. 背景与问题

工作流（`mode=WORKFLOW`）当前一次性把整张图下发给 Runtime Sidecar（Python/LangGraph，`:8090`）执行到底，中途不能向用户提问。需求：系统提示词类如「先向用户搜集几个问题」需要中途暂停 → 问用户 → 收集答案 → 绑变量 → 续跑，且可能多轮。

## 2. 现状（已勘验）

- 流式执行走 sidecar：`ChatSessionService.streamWorkflow` → `RuntimeExecutionService.runWorkflowFromChat` → `SidecarRuntimeGateway.run`（SSE）。`engine/strategy/WorkflowStrategy` 是死代码（同步路径，不走 chat）。
- sidecar 已有 **伪暂停** 机制用于 `HUMAN_APPROVAL`：跑完整图 → 存 checkpoint → `iter_events` 把事件切片到该节点前 → 补发 `WAITING_APPROVAL` 事件 → Java 落 `WAITING_APPROVAL` 状态。恢复时从 checkpoint 重跑全图、过滤已访问节点重发；`approvalDecision=="approved"` 时跳过暂停（`runtime_executor.py:138`）。
- 但 `WAITING_APPROVAL` 事件在 chat WS 流里被**静默丢弃**（`ChatSessionService.workflowStreamEvents` 只映射 `NODE_COMPLETED/EXECUTION_FAILED/EXECUTION_COMPLETED`）；审批恢复仅走 REST `/api/executions/{id}/approve`，不在对话流内。
- sidecar **不支持环**：`graph_compiler.py:376 topological_node_ids` 检测环即 `raise`。条件分支可用（`CONDITION/ROUTER/LLM_ROUTER` + `evaluate_condition_group`）。
- sidecar **无 `{{var}}` 模板**：变量靠 key 查找，`state["outputs"][nodeId]` 经 `merge_outputs` 合并、callback 时并入 `input` dict。Java 侧 `VariableStore` 有 `{{var}}` 渲染。
- 最近现成的「下条消息当答案」范式 = 记忆冲突 `interceptConflict`（`ChatSessionService:317`）。

## 3. 关键决策（已与用户对齐）

1. sidecar 可改 → 复刻 HUMAN_APPROVAL 模板加 HUMAN_INPUT。
2. 交互形态：固定多问 + 动态多轮都要。固定多问 = 图里串 N 个 HUMAN_INPUT（DAG，Phase 1 即支持）；动态多轮 = 需要环（Phase 2）。
3. 答案回传：**混合**。`inputType=text|textarea` 走聊天拦截（下条用户消息当答案，仿 `interceptConflict`）；`inputType=select` 升级为专用 `INPUT_REQUIRED` WS 帧 + 前端选项卡片 + `INPUT_REPLY` 回复。

---

# Phase 1 — HUMAN_INPUT 节点（方案 A）

## 4. 节点定义

`workflow_nodes.type = 'HUMAN_INPUT'`，`config`（JSON，透传到 sidecar `RuntimeNode.config`）：

```json
{
  "inputKey": "userBudget",
  "questionTemplate": "请告诉我你的预算范围（如需引用上一步结果：{{askLLM.output}}）",
  "inputType": "text",            // text | textarea | select
  "options": ["低","中","高"],      // 仅 select
  "required": true,
  "placeholder": "选填",
  "outputKey": "answer"           // 默认同 inputKey
}
```

- `questionTemplate` 由 **sidecar** 在命中节点时用 `{{nodeAlias}}` / `{{nodeAlias.outputKey}}` 对 `state["outputs"]` 渲染（sidecar 新增 ~10 行渲染器，复用 callback 的 merge map）。支持引用前置 LLM 节点的输出 → 满足「LLM 决定问什么」。
- 答案绑定：恢复时把答案写入 `state["outputs"][humanInputNodeId][outputKey]` 并并入 `state["input"][inputKey]`，下游 callback 节点（Java `LlmCallHandler` 等）经 `{{inputKey}}` 或 `{{nodeAlias.outputKey}}` 引用。

## 5. Sidecar 改动（Python，`runtime-sidecar/app/`）

1. **渲染器**：新增 `render_template(template, outputs)` → 替换 `{{a.b}}`/`{{a}}`。放 `graph_compiler.py` 或新 `templating.py`。
2. `runtime_executor.py`：
   - `first_waiting_input_node(visited)` —— 复刻 `first_waiting_approval_node`（:133），找首个 `HUMAN_INPUT`。
   - `iter_events`：在 approval 切片逻辑后加 input 切片——若存在 HUMAN_INPUT 且本次恢复未带对应 `userInput`，切片到该节点前，发 `WAITING_INPUT` 事件：
     ```
     {type:"WAITING_INPUT", status:"WAITING_INPUT", nodeId, input:null, output:null,
      metadata:{...base, inputKey, question(已渲染), inputType, options,
                inputCheckpointRef: f"checkpoint-{executionId}"}}
     ```
   - 恢复门控（仿 :138 `approvalDecision`）：`request.runtime.userInput` 含 `inputKey` → 跳过暂停，把答案并入 `initial_state`（input + outputs[nodeId]）。
   - `iter_events` 恢复段（:29-42）：`resumeFromCheckpointRef` 加载 checkpoint 后，读 `userInput` 合并进 `initial_state`。
3. 不动 `models.py`（type/event 均自由字符串，无枚举校验）；不动 `topological_node_ids`（Phase 2 才动）。

## 6. Java 后端改动

### 6.1 runtime 包
- `RuntimeNodeType.java`：加枚举 `HUMAN_INPUT`。
- `WorkflowDefinitionAssembler.java`：`parseNodeType`（:77-86）支持 `HUMAN_INPUT`；校验（:99-115）要求 `inputKey`。
- `RuntimeExecutionService.java`：
  - `WAITING_INPUT` 事件处理（仿 :149 `WAITING_APPROVAL`）→ `executionLogService.waitForInput(executionId, nodeId, inputKey, question, inputType, options, checkpointRef)`。
  - 新增 `resumeWorkflowWithInput(executionId, Map<String,Object> userInput)`（仿 :61 `resumeWorkflowFromCheckpoint`）：重跑带 `resumeFromCheckpointRef + userInput`。
- `ExecutionLogService.java`：
  - `waitForInput(...)`：置 `WAITING_INPUT`，存待答规格到新列 `pending_input jsonb`。
  - `findPendingInputBySourceId(sourceId)`：聊天拦截按 sessionId（=sourceId）查唯一挂起。
  - `clearPendingInput(executionId)`：恢复后清。
- `ExecutionController.java`：`POST /api/executions/{id}/input` body `{inputKey, answer}` → `resumeWorkflowWithInput`（结构化通道 REST 兜底）。

### 6.2 chat 包
- `StreamEvent.java`：加类型 `INPUT_REQUIRED` + 承载字段（executionId/nodeId/inputKey/question/inputType/options）。
- `ChatSessionService.workflowStreamEvents`（:581-596）：`WAITING_INPUT` → 发 `StreamEvent.chunk(question)`（问题作为 assistant 文本流出）+ 发 `StreamEvent.inputRequired(...)`。
- `ChatWebSocketHandler.java`（:54-83）：转发 `INPUT_REQUIRED` 帧（现仅转发 CHUNK/THINKING）。
- **聊天拦截**：`sendMessage`（:179）/`sendMessageStream`（:349）开头的 `interceptConflict` 旁加 `interceptWorkflowInput`——若 session 有 `WAITING_INPUT` 且 `inputType ∈ {text,textarea}`，把用户消息当答案：调 `resumeWorkflowWithInput`，复用 `streamWorkflow` 流回。`select` 不拦截，走 `INPUT_REPLY`。

### 6.3 DB 迁移（新文件 `V28__workflow_human_input.sql`）
- `workflow_nodes.type` CHECK 加 `'HUMAN_INPUT'`。
- `execution_logs.status` CHECK 加 `'WAITING_INPUT'`、`'WAITING_APPROVAL'`（补 V12 漏的）。
- `execution_logs` 加列 `pending_input jsonb`。

## 7. 前端改动（`frontend/src/`）

- `stores/chat.ts`：
  - WS 收 `INPUT_REQUIRED` 帧：把 `question` 作 assistant 消息渲染；记 `pendingInput={executionId,nodeId,inputKey,inputType,options}`。
  - `inputType=select`：渲染内联选项按钮，点选 → 发 `INPUT_REPLY` 帧 `{executionId,nodeId,inputKey,answer}`。
  - `inputType=text|textarea`：普通输入框即可，发送走正常消息（后端拦截恢复）。
- WS send 支持 `INPUT_REPLY` 类型。
- SSE 路径同步加 `INPUT_REQUIRED` 处理。

## 8. 数据流（Phase 1，text 型）

```
用户发消息(trigger) → streamWorkflow → sidecar 跑图 → 命中 HUMAN_INPUT
  → sidecar 切片 + 发 WAITING_INPUT{question,inputKey,...}
  → Java 落 WAITING_INPUT + pending_input
  → workflowStreamEvents 发 chunk(question) + INPUT_REQUIRED
  → WS 透出 → 前端渲染问题（text 型不弹卡片）
用户答（正常发消息）
  → sendMessageStream → interceptWorkflowInput 命中
  → resumeWorkflowWithInput(execId, {inputKey: 答案})
  → sidecar resume(userInput) → 续跑 → 流回
```

## 9. 边界与取舍

- **拦截歧义**：text 型假定「下条用户消息 = 答案」（同 `interceptConflict` 假设）。不做「取消」逃逸（YAGNI）。
- **一 session 一挂起**：同时最多一个 WAITING_INPUT；新触发工作流期间若已挂起，Phase 1 拒绝/排队（取拒绝+提示）。
- **重跑副作用**：sidecar 恢复重跑全图，LLM 节点会再调用一次（继承 approval 现状）。Phase 1 不优化。
- **结构化（select）**：必须走 INPUT_REPLY，不参与聊天拦截，避免自由文本污染选项。

---

# Phase 2 — sidecar 环支持（方案 C，动态多轮）

> Phase 1 落地后做。预算允许才动。

- 放开 `graph_compiler.py:376` 环禁令；LangGraph 1.2.2 原生支持环。
- 加 `LOOP` 节点或允许回边，配 `maxIterations` 守卫（复用 `runtime.maxDepth`/`maxIterations`）。
- checkpoint/resume 遇环：`visited` 语义改为「可重复访问」，按 (nodeId, iteration) 去重事件重发。
- 图示例：`LLM判断 → CONDITION(info_sufficient?) → [是]最终LLM→END / [否]HUMAN_INPUT → 回 LLM判断`。
- 风险独立，不堵 Phase 1。

## 10. 验证

- sidecar：扩 `tests/test_runtime_executor.py`，HUMAN_INPUT 切片 + userInput 恢复 + 模板渲染用例。
- Java：`mvn compile`；`ExecutionLogService`/`RuntimeExecutionService` 单测覆盖 WAITING_INPUT 落库与恢复。
- e2e：text 型一次问答、select 型一次问答、固定两连问。

---

## 11. 实现备注（Phase 1 落地与设计的差异）

已落地，`mvn compile` 绿、sidecar `pytest` 29 绿、前端 `vue-tsc` 绿。实现中细化的点：

1. **恢复发射修正（关键）**：approval 伪暂停在恢复时用 `restored_visited` 过滤"已访问"节点 → 对 HUMAN_INPUT 是 bug（下游依赖答案的节点永不被发射）。改为：恢复时发射"暂停节点 + 其后代"（`descendants()` BFS），并去重（`unique_ordered`，因 `operator.add` 会让 visited 累加重复）。首次运行仍切片到 HUMAN_INPUT 之前（同 approval）。
2. **暂停点持久化**：checkpoint 存 `pausedAtNodeId`（augmented graph_result），恢复时据此判 `is_input_resume` 并定位后代集。
3. **会话定位**：`execution_logs` 加 `session_id` 列（V42），对话流触发工作流时填充。拦截 `findPendingInputBySession(session_id, status=WAITING_INPUT)` 精确定位，避免按 workflowId+userId 的多会话歧义。
4. **RESUMED 状态**：用户作答后原 WAITING_INPUT 执行标 `RESUMED`（V42 CHECK 已加），防拦截重复命中；新建恢复执行沿用同一 session_id。
5. **拦截不限 inputType**：text/textarea/select 都走"下条消息当答案"拦截。select 按钮仅前端 UI 便利（store 已捕获 `pendingInput`，组件按钮 UI 待补）。
6. **模板渲染**：sidecar 新增 `render_template`（`{{alias}}`/`{{alias.field}}` 对 `state.outputs` 解析），`questionTemplate` 可引用前置 LLM 节点输出。
7. **重跑副作用**：恢复时 sidecar 重跑全图（继承 approval 现状），前置 LLM 节点会再调用一次（非确定性，但仅后代集被发射，前置不重发）。

### 改动文件清单
- sidecar：`runtime_executor.py`、`graph_compiler.py`、`tests/test_runtime_executor.py`
- Java：`RuntimeNodeType.java`、`ExecutionLog.java`、`ExecutionLogService.java`、`RuntimeExecutionService.java`、`WorkflowDefinitionAssembler.java`、`ExecutionController.java`、`StreamEvent.java`、`ChatWebSocketHandler.java`、`ChatSessionService.java`
- DB：`V42__workflow_human_input.sql`
- 前端：`stores/chat.ts`（text 路径零组件改动即可用；select 按钮组件待补）
