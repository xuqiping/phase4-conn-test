# LangGraph + Agent + 工作流运行说明

## 1. 总体定位

平台中三类核心对象的职责如下：

| 对象 | 作用 |
|---|---|
| 工作流 Workflow | 定义整体流程，决定节点顺序、分支、并行、审批和结束条件 |
| Agent | 承接某类任务，根据用户输入选择合适能力 |
| 能力 Skill | Agent 下的具体任务能力，由一个或多个步骤组成 |
| 步骤 SkillStep | 能力的实际执行单元，例如调用大模型 `LLM_CALL` |
| LangGraph | 工作流运行时，负责编译和执行流程图，产生执行事件 |

一句话概括：

```text
LangGraph 管流程怎么走，Agent 管谁来处理，能力管做什么，步骤管怎么做。
```

## 2. 整体运行链路

```text
用户
  -> 前端工作流画布
  -> Java 后端保存工作流
  -> WorkflowDefinitionAssembler 转换标准运行定义
  -> RuntimeGateway 调用运行时
  -> Python sidecar / LangGraph 执行图
  -> ExecutionEvent 事件流
  -> Java 后端写入 execution_logs
  -> 前端执行监控展示结果
```

## 3. 工作流如何进入 LangGraph

### 3.1 前端保存工作流

用户在工作流画布中配置节点和连线。

常见节点类型：

```text
START
END
SKILL
AGENT_REF
WORKFLOW_REF
CONDITION
ROUTER
LLM_ROUTER
PARALLEL
JOIN
HUMAN_APPROVAL
```

其中：

- `AGENT_REF` 表示引用某个 Agent。
- `WORKFLOW_REF` 表示引用另一个工作流。
- `SKILL` 表示引用某个具体能力。

### 3.2 Java 后端转换运行定义

后端读取工作流详情后，通过 `WorkflowDefinitionAssembler` 转为标准 `WorkflowDefinition`。

转换后的结构包含：

```text
workflowId
name
nodes
edges
```

节点配置会被解析成 JSON Map。例如 `AGENT_REF` 节点需要包含：

```json
{
  "agentId": 3,
  "agentName": "需求分析Agent"
}
```

### 3.3 Java 创建执行记录

运行前，Java 后端会创建一条 `execution_logs`：

```text
status = RUNNING
sourceType = WORKFLOW
sourceId = workflowId
traceId = trace-xxx
```

随后构造 `ExecutionRequest` 发送给 RuntimeGateway。

## 4. LangGraph 如何执行工作流

Python sidecar 接收 `ExecutionRequest` 后，会把 `WorkflowDefinition` 编译为 LangGraph `StateGraph`。

运行状态主要包含：

```text
input：流程输入
visited：已执行节点
outputs：每个节点的输出
```

执行过程中，LangGraph 会按节点类型处理：

| 节点 | 执行方式 |
|---|---|
| START | 记录入口和输入 |
| 普通节点 | 执行节点并写入输出 |
| CONDITION | 根据条件选择下一条路径 |
| ROUTER | 根据规则选择路径 |
| LLM_ROUTER | 当前为 mock 路由，后续可接真实 LLM |
| PARALLEL | 扇出多个分支 |
| JOIN | 汇总多个分支输出 |
| HUMAN_APPROVAL | 暂停流程，等待人工审批 |
| END | 流程结束 |

## 5. Agent 如何参与工作流

### 5.1 AGENT_REF 节点

工作流中的 `AGENT_REF` 节点用于引用平台中的 Agent。

节点配置示例：

```json
{
  "agentId": 3,
  "agentName": "产品需求分析Agent"
}
```

当前 LangGraph 会识别该节点，并在事件中标记：

```json
{
  "sourceType": "AGENT",
  "sourceId": 3
}
```

这样执行监控可以知道当前节点对应哪个 Agent。

### 5.2 当前真实 Agent 调用链

当前 `AGENT_REF` 已通过 sidecar -> Java runtime callback 接入真实执行链路：

```text
LangGraph 执行 AGENT_REF 节点
  -> 读取 agentId
  -> 调 Java Agent 执行接口
  -> AgentRouter 根据输入选择能力
  -> SkillExecutor 执行能力步骤
  -> 返回 Agent 输出
  -> LangGraph 写入节点 output
  -> 继续执行后续节点
```

执行结果会写入 `NODE_COMPLETED.output`，包含 `agentId`、`agentName`、`selectedSkillIds`、`stepOutputs` 和最终输出文本。执行前会校验目标 Agent 是否可访问：`PUBLISHED` Agent 可执行，未发布 Agent 仅允许创建者执行。

## 6. Agent 如何选择能力

Agent 内部通过 `AgentRouter` 选择能力。

优先级：

1. 先根据 Agent 配置中的 `routingRules` 做规则匹配。
2. 规则未命中时，调用 LLM 根据能力名称和能力描述选择。

### 6.1 规则路由

Agent 配置示例：

```json
{
  "routingRules": [
    {
      "keywords": ["需求", "产品", "功能"],
      "skillIds": [1]
    },
    {
      "keywords": ["测试", "用例"],
      "skillIds": [2]
    }
  ]
}
```

当用户输入包含关键词时，直接选择对应能力。

### 6.2 LLM 路由

如果规则未命中，系统会把能力列表整理给 LLM：

```text
ID:1 名称:需求分析 描述:分析产品需求并输出结构化说明
ID:2 名称:测试设计 描述:生成测试用例和测试计划
ID:3 名称:代码生成 描述:根据需求生成代码
```

LLM 返回能力 ID 数组：

```json
[1]
```

因此，能力描述非常重要。它决定 Agent 是否会选中这个能力。

## 7. 能力如何执行

能力由多个步骤组成。

```text
Skill
  -> SkillStep 1
  -> SkillStep 2
  -> SkillStep 3
```

Java 侧 `SkillExecutor` 会：

1. 根据 `skillId` 查询步骤。
2. 按 `stepOrder` 排序。
3. 根据步骤 `action` 找处理器。
4. 执行步骤配置 JSON。
5. 把结果写入上下文。

### 7.1 LLM_CALL 步骤

当前常用步骤动作：

```text
LLM_CALL
```

配置示例：

```json
{
  "promptTemplate": "请根据用户输入，分析其真实需求，输出目标、用户角色、核心功能、约束条件和验收标准。",
  "model": "doubao-seed-2.0-code",
  "temperature": 0.7,
  "outputKey": "requirement_analysis"
}
```

字段说明：

| 字段 | 作用 |
|---|---|
| `promptTemplate` | 步骤提示词 |
| `model` | 调用的模型 |
| `temperature` | 输出发散程度 |
| `outputKey` | 当前步骤输出保存到上下文的变量名 |

## 8. 工作流引用如何联动

### 8.1 WORKFLOW_REF 节点

`WORKFLOW_REF` 节点用于在一个工作流中引用另一个工作流。

配置示例：

```json
{
  "workflowId": 8,
  "workflowName": "需求分析子流程"
}
```

当前系统会校验：

- 必须配置 `workflowId`
- 不能引用当前工作流自身

后续真实递归执行目标：

```text
LangGraph 执行 WORKFLOW_REF
  -> Java 加载子工作流
  -> 转换为 WorkflowDefinition
  -> 新建 parent/root execution 关联
  -> 执行子工作流
  -> 把子工作流输出返回父工作流
```

## 9. 分支、路由、并行和审批

### 9.1 CONDITION

`CONDITION` 根据输入选择路径。

示例：

```json
{
  "inputPath": "type",
  "defaultTarget": "normal_path"
}
```

如果输入为：

```json
{
  "type": "urgent"
}
```

则可路由到紧急处理分支。

### 9.2 ROUTER

`ROUTER` 适合多规则路由。

示例：

```json
{
  "routes": [
    {
      "name": "需求",
      "condition": "category == requirement",
      "target": "requirement_node"
    }
  ],
  "defaultTarget": "default_node"
}
```

### 9.3 LLM_ROUTER

`LLM_ROUTER` 用于让 LLM 决定路径。

当前 sidecar 为 mock 实现，主要使用：

```json
{
  "mockSelectedRoute": "需求",
  "mockConfidence": 0.9
}
```

后续可增强为真实模型路由。

### 9.4 PARALLEL / JOIN

`PARALLEL` 用于并行展开多个分支。

`JOIN` 用于汇总多个分支输出。

运行效果：

```text
PARALLEL
  -> 分支 A
  -> 分支 B
  -> 分支 C
JOIN
  -> 汇总 A/B/C 输出
```

### 9.5 HUMAN_APPROVAL

`HUMAN_APPROVAL` 用于人工审批。

执行到该节点时：

1. LangGraph 产生 `WAITING_APPROVAL` 事件。
2. Java 后端把执行状态改为 `WAITING_APPROVAL`。
3. 前端执行监控显示待审批。
4. 用户点击通过审批后，后端从 checkpoint 恢复执行。

## 10. 事件如何回写平台

LangGraph 执行过程中会产生事件：

```text
EXECUTION_STARTED
NODE_STARTED
NODE_COMPLETED
WAITING_APPROVAL
EXECUTION_FAILED
EXECUTION_COMPLETED
```

Java 后端收到事件后：

1. 写入 `execution_logs.node_logs`
2. 更新 `external_thread_id`
3. 更新 `checkpoint_ref`
4. 成功时标记 `SUCCESS`
5. 失败时标记 `FAILED`
6. 等待审批时标记 `WAITING_APPROVAL`

前端执行监控页读取这些数据，展示执行过程。

## 11. Checkpoint、恢复和重试

### 11.1 Checkpoint

运行时默认开启 checkpoint。

当失败或等待审批时，sidecar 会保存运行状态：

```text
checkpoint-{executionId}
```

Java 后端保存该引用到：

```text
execution_logs.checkpoint_ref
```

### 11.2 失败恢复

```text
节点失败
  -> sidecar 保存 checkpoint
  -> Java 保存 checkpoint_ref
  -> 用户点击恢复
  -> Java 调 sidecar 并传 resumeFromCheckpointRef
  -> LangGraph 读取 checkpoint
  -> 继续执行
```

### 11.3 人工审批恢复

```text
执行到 HUMAN_APPROVAL
  -> WAITING_APPROVAL
  -> 用户点击通过审批
  -> Java 传 approvalDecision=approved
  -> LangGraph 从 checkpoint 恢复
  -> 跳过等待节点继续执行
```

### 11.4 重试

```text
用户点击重试
  -> Java 读取原 execution
  -> 重新加载 workflow
  -> 新建 execution_log
  -> 重新执行整个工作流
```

## 12. 权限如何参与

当前权限主要在 Java 后端控制。

| 功能 | 权限 |
|---|---|
| 查看 Agent / 能力 | `agent:read` |
| 创建 Agent | `agent:create` |
| 编辑 Agent | `agent:update` |
| 删除 Agent | `agent:delete` |
| 发布 Agent | `agent:publish` |
| 管理能力 | `skill:manage` |
| 查看工作流 | `workflow:read` |
| 创建工作流 | `workflow:create` |
| 编辑工作流 | `workflow:update` |
| 删除工作流 | `workflow:delete` |
| 执行工作流 | `execution:run` |
| 查看执行日志 | `execution:read` |

`skill:manage` 控制能力的创建、编辑和删除。

后续真实跨对象执行时，还需要增强：

- AGENT_REF 是否允许调用目标 Agent
- WORKFLOW_REF 是否允许调用目标工作流
- 子工作流是否继承父工作流权限
- 执行恢复是否只能由有权限用户操作

## 13. 当前实现状态

### 13.1 已实现

- 工作流保存和加载
- 工作流转 `WorkflowDefinition`
- Java RuntimeExecutionService
- Python sidecar / LangGraph 基础执行
- CONDITION 路由
- ROUTER 路由
- LLM_ROUTER mock 路由
- PARALLEL / JOIN
- Checkpoint / resume
- HUMAN_APPROVAL 最小闭环
- ExecutionEvent 回写执行日志
- AGENT_REF / WORKFLOW_REF 节点保存和回显
- AGENT_REF 真实触发 AgentRouter 并执行选中 Skill
- SKILL 节点真实触发 SkillExecutor
- sidecar -> Java runtime callback 错误传播
- Agent 能力管理
- 能力管理权限 `skill:manage`

### 13.2 待增强

- WORKFLOW_REF 真实递归执行子工作流
- LLM_ROUTER 真实模型路由
- 跨 Workflow 的对象级权限校验和递归调用治理
- 分支超时控制
- 并发限制
- 循环和递归深度保护

## 14. 最终目标闭环

最终平台运行闭环如下：

```text
用户输入
  -> Workflow 决定整体流程
  -> LangGraph 控制流程状态
  -> AGENT_REF 调用 Agent
  -> AgentRouter 选择能力
  -> SkillExecutor 执行步骤
  -> LLM_CALL / 工具调用产生结果
  -> LangGraph 根据结果继续分支、路由、并行或审批
  -> Java 持久化事件和日志
  -> 前端展示完整运行过程
```

## 15. 操作级全链路说明

本章按用户在前端的每一个关键动作，说明它会调用哪个 API、触发哪个后端模块、写入或更新哪些数据库表，以及最终如何影响 LangGraph、Agent、能力和执行日志。

## 16. 创建 Agent 的完整流程

### 16.1 前端动作

用户进入 Agent 大厅，点击“新建 Agent”，填写：

```text
名称
描述
分组
头像 URL
```

前端组件：

```text
AgentHallView.vue
  -> AgentFormModal.vue
```

前端 API：

```text
agentApi.createAgent()
```

HTTP 请求：

```http
POST /api/agents
```

请求体示例：

```json
{
  "name": "产品需求分析Agent",
  "description": "负责分析产品需求、业务流程和验收标准",
  "groupId": 1,
  "avatar": ""
}
```

### 16.2 后端处理

后端入口：

```text
AgentController.createAgent()
```

权限要求：

```text
agent:create
```

后端会创建 `Agent` 实体：

```text
name        <- 前端填写
description <- 前端填写
avatar      <- 前端填写
group_id    <- 前端选择
status      <- DRAFT
created_by  <- 当前用户 ID
updated_by  <- 当前用户 ID
```

### 16.3 数据库变化

写入表：

```text
agents
```

新增记录示例：

| 字段 | 值 |
|---|---|
| `id` | 自动生成 |
| `name` | 产品需求分析Agent |
| `description` | 负责分析产品需求、业务流程和验收标准 |
| `group_id` | 1 |
| `status` | DRAFT |
| `config` | null 或 `{}` |
| `created_by` | 当前用户 ID |
| `deleted` | 0 |

### 16.4 对 LangGraph 的影响

创建 Agent 本身不会触发 LangGraph。

它的影响发生在后续：

```text
工作流画布添加 AGENT_REF 节点
  -> 节点 config 保存 agentId
  -> LangGraph 执行该节点时可识别 sourceType=AGENT/sourceId=agentId
```

## 17. 配置 Agent 能力的完整流程

### 17.1 前端动作

用户进入 Agent 详情页，点击“新增能力”。

前端页面：

```text
AgentDetailView.vue
```

前端弹窗：

```text
SkillFormModal.vue
```

用户填写：

```text
能力名称
能力描述
类型
能力配置 JSON
步骤列表
```

前端 API：

```text
agentApi.createSkill(agentId, payload)
```

HTTP 请求：

```http
POST /api/agents/{agentId}/skills
```

### 17.2 请求体结构

示例：

```json
{
  "name": "需求分析",
  "description": "当用户提出产品功能、业务流程或系统建设想法时，分析需求并整理成结构化说明。",
  "type": "SEQUENCE",
  "config": "{}",
  "sortOrder": 1,
  "steps": [
    {
      "stepOrder": 1,
      "name": "分析需求",
      "action": "LLM_CALL",
      "config": "{\"promptTemplate\":\"请根据用户输入，分析其真实需求，输出目标、用户角色、核心功能、约束条件和验收标准。\",\"model\":\"doubao-seed-2.0-code\",\"temperature\":0.7,\"outputKey\":\"requirement_analysis\"}"
    }
  ]
}
```

### 17.3 后端处理

后端入口：

```text
AgentController.createSkill()
  -> SkillService.createSkill()
```

权限要求：

```text
skill:manage
```

后端处理步骤：

1. 校验 `agentId` 是否存在。
2. 校验能力名称不能为空。
3. 插入 `skills` 记录。
4. 删除旧步骤不适用，因为这是新增能力。
5. 按请求中的 `steps` 插入 `skill_steps`。
6. 返回 `SkillDetailVO`。

### 17.4 数据库变化

写入表一：

```text
skills
```

新增记录示例：

| 字段 | 值 |
|---|---|
| `id` | 自动生成 |
| `agent_id` | 当前 Agent ID |
| `name` | 需求分析 |
| `description` | 能力描述 |
| `type` | SEQUENCE |
| `config` | `{}` |
| `sort_order` | 1 |
| `created_by` | 当前用户 ID |
| `deleted` | 0 |

写入表二：

```text
skill_steps
```

新增记录示例：

| 字段 | 值 |
|---|---|
| `id` | 自动生成 |
| `skill_id` | 上一步生成的 skills.id |
| `step_order` | 1 |
| `name` | 分析需求 |
| `action` | LLM_CALL |
| `config` | 包含 promptTemplate/model/temperature/outputKey 的 JSON |
| `created_by` | 当前用户 ID |
| `deleted` | 0 |

### 17.5 对 Agent 路由的影响

新增能力后，AgentRouter 后续选择能力时会读取该 Agent 下的所有能力：

```text
skills where agent_id = 当前 Agent ID and deleted = 0
```

LLM 路由时会看到：

```text
ID:能力ID 名称:需求分析 描述:当用户提出产品功能、业务流程或系统建设想法时...
```

因此：

```text
能力描述越清楚 -> Agent 越容易选中正确能力
```

### 17.6 对 LangGraph 的影响

新增能力不会直接触发 LangGraph。

它会在两种场景影响 LangGraph：

1. 工作流节点直接引用 `SKILL`。
2. 工作流节点引用 `AGENT_REF`，执行时由 AgentRouter 根据输入选择该能力。

当前已实现的 LangGraph sidecar 会在 `runtime.javaCallbackBaseUrl` 存在时回调 Java，真实执行 `AGENT_REF` 或 `SKILL` 对应能力，并把输出写入运行事件。

## 18. 编辑 Agent 能力的完整流程

### 18.1 前端动作

用户进入 Agent 详情页：

```text
选择某个能力
  -> 点击“编辑能力”
  -> 修改能力字段或步骤
  -> 点击保存
```

前端 API：

```text
agentApi.updateSkill(skillId, payload)
```

HTTP 请求：

```http
PUT /api/skills/{skillId}
```

### 18.2 后端处理

后端入口：

```text
AgentController.updateSkill()
  -> SkillService.updateSkill()
```

权限要求：

```text
skill:manage
```

处理步骤：

1. 根据 `skillId` 查询 `skills`。
2. 如果不存在，返回业务错误。
3. 更新能力名称、描述、类型、配置、排序。
4. 删除该能力原有所有步骤。
5. 重新插入前端提交的新步骤列表。
6. 返回新的能力详情。

### 18.3 数据库变化

更新表：

```text
skills
```

变化字段：

```text
name
description
type
config
sort_order
updated_by
updated_at
version
```

删除并重建步骤：

```text
skill_steps
```

当前实现方式是：

```text
删除旧步骤
  -> 插入新步骤
```

所以如果用户调整步骤顺序，数据库中会体现为新的 `step_order`。

### 18.4 对运行的影响

编辑能力后，后续执行会使用最新配置。

例如修改了步骤配置：

```json
{
  "promptTemplate": "请输出更详细的 PRD...",
  "temperature": 0.3
}
```

则下一次执行该能力时，`LLM_CALL` 会使用新提示词和新温度。

已经完成的历史 execution 不会被修改。

## 19. 删除 Agent 能力的完整流程

### 19.1 前端动作

用户进入 Agent 详情页：

```text
选择能力
  -> 点击“删除能力”
  -> 确认删除
```

前端 API：

```text
agentApi.deleteSkill(skillId)
```

HTTP 请求：

```http
DELETE /api/skills/{skillId}
```

### 19.2 后端处理

后端入口：

```text
AgentController.deleteSkill()
  -> SkillService.deleteSkill()
```

权限要求：

```text
skill:manage
```

处理步骤：

1. 查询能力是否存在。
2. 删除该能力下的所有步骤。
3. 删除能力本身。

### 19.3 数据库变化

删除表：

```text
skill_steps
skills
```

因为实体继承 `BaseEntity` 并有逻辑删除字段 `deleted`，MyBatis-Plus 会按逻辑删除规则处理。

效果：

```text
skills.deleted = 1
skill_steps.deleted = 1
```

### 19.4 对运行的影响

删除能力后：

1. AgentRouter 不再把它列入候选能力。
2. 工作流画布中如果已有节点引用该能力，后续执行可能找不到有效能力。
3. 历史 execution 日志不受影响。

建议：

```text
删除能力前，先确认没有工作流节点正在引用该 skillId。
```

## 20. 创建工作流的完整流程

### 20.1 前端动作

用户进入工作流列表页，点击“新建工作流”。

前端 API：

```text
workflowApi.create()
```

HTTP 请求：

```http
POST /api/workflows
```

请求体包含：

```text
name
description
status
nodes
edges
```

### 20.2 后端处理

后端入口：

```text
WorkflowController.createWorkflow()
  -> WorkflowService.createWorkflow()
```

权限要求：

```text
workflow:create
```

### 20.3 数据库变化

写入表：

```text
workflows
workflow_nodes
workflow_edges
```

`workflows` 记录：

| 字段 | 说明 |
|---|---|
| `id` | 工作流 ID |
| `name` | 工作流名称 |
| `description` | 描述 |
| `status` | DRAFT |
| `owner_id` | 当前用户 |
| `created_by` | 当前用户 |
| `deleted` | 0 |

`workflow_nodes` 记录：

| 字段 | 说明 |
|---|---|
| `workflow_id` | 所属工作流 |
| `node_id` | 前端画布节点 ID |
| `type` | 节点类型 |
| `position_x` | 画布 X 坐标 |
| `position_y` | 画布 Y 坐标 |
| `label` | 节点显示名 |
| `config` | 节点配置 JSON |

`workflow_edges` 记录：

| 字段 | 说明 |
|---|---|
| `workflow_id` | 所属工作流 |
| `source_node_id` | 来源节点 |
| `target_node_id` | 目标节点 |
| `condition` | 条件表达式 |
| `label` | 连线名称 |

## 21. 在工作流中添加 Agent 引用节点

### 21.1 前端动作

用户在工作流编辑器中：

```text
从组件面板拖入 Agent 引用节点
  -> 选择 Agent
  -> 保存工作流
```

前端涉及：

```text
ComponentPalette.vue
FlowCanvas.vue
PropertyPanel.vue
WorkflowEditorView.vue
workflowMapper.ts
```

保存 API：

```text
workflowApi.update(workflowId, payload)
```

HTTP 请求：

```http
PUT /api/workflows/{workflowId}
```

### 21.2 前端保存的数据

AGENT_REF 节点会保存为：

```json
{
  "nodeId": "agent-ref-xxx",
  "type": "AGENT_REF",
  "label": "产品需求分析Agent",
  "positionX": 300,
  "positionY": 180,
  "config": "{\"agentId\":3,\"agentName\":\"产品需求分析Agent\"}"
}
```

### 21.3 数据库变化

更新表：

```text
workflow_nodes
```

关键字段：

| 字段 | 值 |
|---|---|
| `type` | AGENT_REF |
| `label` | Agent 名称 |
| `config` | 包含 agentId / agentName |

如果同时连接了边，还会更新：

```text
workflow_edges
```

例如：

| source_node_id | target_node_id |
|---|---|
| start | agent-ref-xxx |
| agent-ref-xxx | end |

### 21.4 对 LangGraph 的影响

运行时，`WorkflowDefinitionAssembler` 会把该节点转为：

```json
{
  "id": "agent-ref-xxx",
  "type": "AGENT_REF",
  "label": "产品需求分析Agent",
  "config": {
    "agentId": 3,
    "agentName": "产品需求分析Agent"
  }
}
```

LangGraph 执行该节点后，事件中会包含：

```json
{
  "nodeId": "agent-ref-xxx",
  "sourceType": "AGENT",
  "sourceId": 3,
  "status": "SUCCESS"
}
```

## 22. 在工作流中添加能力节点

### 22.1 前端动作

用户在组件面板中找到某个 Agent 下的能力，拖入画布。

前端保存节点：

```json
{
  "nodeId": "skill-xxx",
  "type": "SKILL",
  "label": "需求分析",
  "config": "{\"skillId\":1,\"agentId\":3,\"agentName\":\"产品需求分析Agent\"}"
}
```

### 22.2 数据库变化

更新表：

```text
workflow_nodes
```

关键字段：

| 字段 | 值 |
|---|---|
| `type` | SKILL |
| `label` | 能力名称 |
| `config` | 包含 skillId / agentId / agentName |

### 22.3 当前运行效果

当前 LangGraph sidecar 可以识别 `SKILL` 节点，并在 sidecar 模式下通过 Java runtime callback 真实调用 `SkillExecutor`。

当前链路为：

```text
LangGraph 执行 SKILL 节点
  -> 读取 skillId
  -> Java SkillExecutor.executeSkill(skillId)
  -> 查询 skill_steps
  -> 执行 LLM_CALL 等步骤
  -> 返回结果给 LangGraph
```

## 23. 运行工作流的完整流程

### 23.1 前端动作

用户在工作流编辑器点击“运行”。

前端 API：

```text
workflowApi.run(workflowId, input)
```

HTTP 请求：

```http
POST /api/workflows/{workflowId}/run
```

请求体示例：

```json
{
  "message": "请分析一个文件管理系统的需求",
  "type": "requirement"
}
```

### 23.2 后端 Controller

入口：

```text
WorkflowController.runWorkflow()
```

权限要求：

```text
execution:run
```

调用：

```text
RuntimeExecutionService.runWorkflow(workflowId, userId, input)
```

### 23.3 后端服务处理

`RuntimeExecutionService.runWorkflow()` 执行：

1. `workflowService.getWorkflowDetail(workflowId)` 读取工作流、节点、边。
2. `WorkflowDefinitionAssembler.assemble(workflow)` 转换为标准运行定义。
3. `executionLogService.startRuntimeExecution()` 创建执行日志。
4. 构造 `ExecutionRequest`。
5. `runtimeGateway.run(request)` 调用运行时。
6. 每收到一个 `ExecutionEvent`，调用 `persistEvent()` 写日志。

### 23.4 数据库第一次变化：创建 execution_logs

写入表：

```text
execution_logs
```

初始字段：

| 字段 | 值 |
|---|---|
| `workflow_id` | 当前 workflowId |
| `workflow_name` | 工作流名称 |
| `root_execution_id` | 当前执行 ID |
| `source_type` | WORKFLOW |
| `source_id` | workflowId |
| `triggered_by` | 当前用户 |
| `status` | RUNNING |
| `trace_id` | trace-xxx |
| `started_at` | 当前时间 |

### 23.5 发送给 LangGraph 的 ExecutionRequest

核心结构：

```json
{
  "executionId": "100",
  "rootExecutionId": "100",
  "userId": 1,
  "sourceType": "WORKFLOW",
  "sourceId": 8,
  "workflow": {
    "workflowId": 8,
    "name": "需求分析流程",
    "nodes": [],
    "edges": []
  },
  "input": {
    "message": "请分析一个文件管理系统的需求"
  },
  "runtime": {
    "stream": true,
    "checkpoint": true,
    "maxDepth": 8,
    "traceId": "trace-xxx"
  }
}
```

### 23.6 LangGraph sidecar 处理

Python sidecar：

```text
runtime_executor.build_events()
  -> compile_workflow_graph()
  -> graph.invoke(initial_state)
  -> 生成 ExecutionEvent 列表
```

LangGraph 内部状态：

```json
{
  "input": {
    "message": "请分析一个文件管理系统的需求"
  },
  "visited": [],
  "outputs": {}
}
```

### 23.7 LangGraph 事件流

事件顺序通常为：

```text
EXECUTION_STARTED
NODE_STARTED
NODE_COMPLETED
NODE_STARTED
NODE_COMPLETED
...
EXECUTION_COMPLETED
```

如果遇到人工审批：

```text
WAITING_APPROVAL
```

如果节点失败：

```text
EXECUTION_FAILED
```

### 23.8 数据库第二次变化：写入 node_logs

每收到一个事件，Java 会调用：

```text
ExecutionLogService.appendRuntimeEventSnapshot()
```

更新：

```text
execution_logs.node_logs
```

示例内容：

```json
[
  {
    "executionId": "100",
    "nodeId": null,
    "type": "EXECUTION_STARTED",
    "status": "RUNNING",
    "metadata": {
      "traceId": "trace-xxx",
      "externalThreadId": "sidecar-thread-100",
      "runtime": "runtime-sidecar",
      "engine": "langgraph"
    }
  },
  {
    "executionId": "100",
    "nodeId": "agent-ref-xxx",
    "type": "NODE_COMPLETED",
    "status": "SUCCESS",
    "sourceType": "AGENT",
    "sourceId": 3
  }
]
```

### 23.9 数据库第三次变化：更新 checkpoint/thread

Java 从事件 metadata 中读取：

```text
externalThreadId
checkpointRef
recoveryCheckpointRef
approvalCheckpointRef
```

更新表：

```text
execution_logs
```

字段：

```text
external_thread_id
checkpoint_ref
```

### 23.10 数据库最终状态变化

如果执行完成：

```text
status = SUCCESS
completed_at = 当前时间
duration = 耗时
node_logs = 最终事件列表
```

如果执行失败：

```text
status = FAILED
error_message = 失败原因
checkpoint_ref = 可恢复 checkpoint
```

如果等待审批：

```text
status = WAITING_APPROVAL
node_id = 等待审批节点 ID
error_message = 等待人工审批: approvalKey
checkpoint_ref = 审批恢复 checkpoint
```

## 24. 执行监控页面的完整流程

### 24.1 查询执行详情

前端动作：

```text
进入执行监控
  -> 输入 executionId
  -> 点击查询
```

前端 API：

```text
executionApi.getExecution(id)
```

HTTP：

```http
GET /api/executions/{id}
```

后端：

```text
ExecutionController.getExecution()
  -> ExecutionLogService.getExecutionLog()
```

读取表：

```text
execution_logs
```

前端展示：

```text
status
workflowId
workflowName
startedAt
completedAt
duration
nodeLogs
checkpointRef
traceId
```

### 24.2 查询待审批

前端 API：

```text
executionApi.listPendingApprovals()
```

HTTP：

```http
GET /api/executions/pending-approvals
```

后端查询：

```text
execution_logs.status = WAITING_APPROVAL
```

## 25. 失败恢复流程

### 25.1 前端动作

用户在执行监控页点击“恢复”。

前端 API：

```text
executionApi.resume(checkpointRef)
```

HTTP：

```http
POST /api/executions/resume?checkpointRef=checkpoint-100
```

### 25.2 后端处理

入口：

```text
ExecutionController.resumeExecution()
  -> RuntimeExecutionService.resumeWorkflowFromCheckpoint()
```

处理步骤：

1. 根据 `checkpointRef` 查询原 execution。
2. 读取原 execution 的 `workflow_id`。
3. 重新读取工作流详情。
4. 重新转换 `WorkflowDefinition`。
5. 创建新的 execution_logs。
6. 构造 runtime 参数：

```json
{
  "resumeFromCheckpointRef": "checkpoint-100",
  "resumeOfExecutionId": "100"
}
```

7. 调用 LangGraph sidecar。

### 25.3 数据库变化

新增一条新的：

```text
execution_logs
```

旧 execution 不会被覆盖。

新 execution 会记录：

```text
status = RUNNING / SUCCESS / FAILED
node_logs 包含 checkpointRestored 信息
```

## 26. 人工审批流程

### 26.1 LangGraph 执行到 HUMAN_APPROVAL

事件：

```text
WAITING_APPROVAL
```

事件 metadata：

```json
{
  "approvalKey": "approval-node-id",
  "approvalCheckpointRef": "checkpoint-100"
}
```

### 26.2 Java 写入审批等待状态

调用：

```text
ExecutionLogService.waitForApproval()
```

更新：

```text
execution_logs.status = WAITING_APPROVAL
execution_logs.node_id = 当前审批节点 ID
execution_logs.error_message = 等待人工审批: approvalKey
execution_logs.checkpoint_ref = approvalCheckpointRef
```

### 26.3 前端通过审批

前端 API：

```text
executionApi.approve(executionId)
```

HTTP：

```http
POST /api/executions/{id}/approve
```

后端：

```text
RuntimeExecutionService.approveWorkflowExecution()
```

runtime 参数：

```json
{
  "resumeFromCheckpointRef": "checkpoint-100",
  "approvalDecision": "approved",
  "approvalOfExecutionId": "100"
}
```

LangGraph 恢复后跳过等待审批节点，继续后续节点。

### 26.4 前端拒绝审批

前端 API：

```text
executionApi.reject(executionId, reason)
```

HTTP：

```http
POST /api/executions/{id}/reject?reason=xxx
```

数据库变化：

```text
execution_logs.status = FAILED
execution_logs.error_message = 人工审批拒绝: reason
```

## 27. 重试流程

### 27.1 前端动作

用户在执行监控页点击“重试”。

前端 API：

```text
executionApi.retry(executionId)
```

HTTP：

```http
POST /api/executions/{id}/retry
```

### 27.2 后端处理

入口：

```text
RuntimeExecutionService.retryWorkflowExecution()
```

处理步骤：

1. 读取原 execution。
2. 根据原 execution 的 `workflow_id` 重新加载工作流。
3. 重新转换 WorkflowDefinition。
4. 创建新的 execution_logs。
5. runtime 参数包含：

```json
{
  "retryOfExecutionId": "100"
}
```

6. 重新执行完整流程。

### 27.3 数据库变化

新增一条 execution_logs。

原 execution 保留不变。

新 execution 的 `node_logs.metadata` 中可看到 retry 相关信息。

## 28. 数据库表与平台对象对应关系

| 平台对象 | 数据库表 | 说明 |
|---|---|---|
| 用户 | `users` | 登录账号 |
| 角色 | `roles` | 角色定义 |
| 权限 | `permissions` | 权限码 |
| 用户角色 | `user_roles` | 用户和角色关系 |
| 角色权限 | `role_permissions` | 角色和权限关系 |
| Agent 分组 | `agent_groups` | Agent 分类 |
| Agent | `agents` | 智能体主体 |
| 能力 | `skills` | Agent 下的能力 |
| 能力步骤 | `skill_steps` | 能力执行步骤 |
| 工作流 | `workflows` | 工作流主体 |
| 工作流节点 | `workflow_nodes` | 画布节点 |
| 工作流连线 | `workflow_edges` | 节点连接关系 |
| 执行日志 | `execution_logs` | 工作流运行记录和事件 |

## 29. 前端动作与数据库变化总表

| 前端动作 | API | 后端入口 | 数据库变化 |
|---|---|---|---|
| 新建 Agent | `POST /api/agents` | `AgentController.createAgent` | 插入 `agents` |
| 编辑 Agent | `PUT /api/agents/{id}` | `AgentController.updateAgent` | 更新 `agents` |
| 删除 Agent | `DELETE /api/agents/{id}` | `AgentController.deleteAgent` | 逻辑删除 `agents`，并删除关联能力 |
| 发布/下线 Agent | `PUT /api/agents/{id}/status` | `AgentController.updateAgentStatus` | 更新 `agents.status` |
| 新增能力 | `POST /api/agents/{id}/skills` | `AgentController.createSkill` | 插入 `skills`、`skill_steps` |
| 编辑能力 | `PUT /api/skills/{id}` | `AgentController.updateSkill` | 更新 `skills`，重建 `skill_steps` |
| 删除能力 | `DELETE /api/skills/{id}` | `AgentController.deleteSkill` | 逻辑删除 `skills`、`skill_steps` |
| 新建工作流 | `POST /api/workflows` | `WorkflowController.createWorkflow` | 插入 `workflows`、`workflow_nodes`、`workflow_edges` |
| 保存工作流 | `PUT /api/workflows/{id}` | `WorkflowController.updateWorkflow` | 更新 `workflows`，重建节点/边 |
| 删除工作流 | `DELETE /api/workflows/{id}` | `WorkflowController.deleteWorkflow` | 逻辑删除 `workflows` |
| 运行工作流 | `POST /api/workflows/{id}/run` | `WorkflowController.runWorkflow` | 插入并更新 `execution_logs` |
| 查询执行 | `GET /api/executions/{id}` | `ExecutionController.getExecution` | 读取 `execution_logs` |
| 恢复执行 | `POST /api/executions/resume` | `ExecutionController.resumeExecution` | 新增 `execution_logs` |
| 审批通过 | `POST /api/executions/{id}/approve` | `ExecutionController.approveExecution` | 新增恢复执行日志 |
| 审批拒绝 | `POST /api/executions/{id}/reject` | `ExecutionController.rejectExecution` | 更新 `execution_logs.status/error_message` |
| 重试执行 | `POST /api/executions/{id}/retry` | `ExecutionController.retryExecution` | 新增 `execution_logs` |

## 30. 一个端到端案例

### 30.1 用户配置 Agent

前端：

```text
Agent 详情页 -> 新增能力
```

数据库：

```text
skills 新增：需求分析
skill_steps 新增：LLM_CALL 步骤
```

### 30.2 用户配置工作流

前端：

```text
工作流编辑器
  -> 拖入 START
  -> 拖入 AGENT_REF，选择产品需求分析Agent
  -> 拖入 HUMAN_APPROVAL
  -> 拖入 END
  -> 连接 START -> AGENT_REF -> HUMAN_APPROVAL -> END
  -> 保存
```

数据库：

```text
workflows 保存流程主体
workflow_nodes 保存 4 个节点
workflow_edges 保存 3 条边
```

### 30.3 用户运行工作流

前端：

```text
点击运行
```

API：

```http
POST /api/workflows/{id}/run
```

数据库：

```text
execution_logs 新增 RUNNING 记录
```

LangGraph：

```text
编译 StateGraph
执行 START
执行 AGENT_REF
执行 HUMAN_APPROVAL
暂停
```

事件：

```text
EXECUTION_STARTED
NODE_STARTED / START
NODE_COMPLETED / START
NODE_STARTED / AGENT_REF
NODE_COMPLETED / AGENT_REF
WAITING_APPROVAL
```

数据库：

```text
execution_logs.node_logs 追加事件
execution_logs.status = WAITING_APPROVAL
execution_logs.checkpoint_ref = checkpoint-{executionId}
```

### 30.4 用户通过审批

前端：

```text
执行监控 -> 点击通过审批
```

API：

```http
POST /api/executions/{id}/approve
```

LangGraph：

```text
读取 checkpoint
approvalDecision = approved
继续执行 END
```

数据库：

```text
新增一条恢复执行 execution_logs
最终 status = SUCCESS
node_logs 包含恢复后的节点事件
```
