# 智能对话 + Agent / 工作流选择设计

## 1. 背景与目标

当前智能对话页已经支持模型选择，后端 `ChatRequest` 也已经包含 `agentId`、`workflowId`、`model` 字段。后端 `ChatSessionService` 会根据新会话请求中的 `workflowId` / `agentId` 自动把会话归类为：

- `CHAT`：普通模型对话。
- `AGENT`：指定 Agent 对话，由 `AgentRoutingStrategy` 处理。
- `WORKFLOW`：指定工作流对话，由 `WorkflowStrategy` 处理。

本设计目标是在智能对话模块中，在模型选择器旁边新增“执行目标”选择器。用户可以选择：

- 无：普通智能对话。
- Agent：当前用户角色权限下可用的智能体。
- 工作流：当前用户角色权限下可用的工作流。

本设计采用 **方案 B：新增后端“对话目标”聚合接口**。前端不再分别调用 Agent 列表和工作流列表来拼装下拉项，而是统一调用后端聚合接口，由后端负责权限过滤、类型归一、排序和后续扩展。

## 2. 范围

本期包含：

- 新增后端聚合接口 `GET /api/chat/targets`。
- 智能对话页新增目标选择器。
- 目标选项支持“无 / Agent / 工作流”三类。
- 目标列表由后端按当前登录用户权限与资源可用性过滤。
- 新会话发送时携带 `agentId` 或 `workflowId`。
- 当前目标本地持久化，刷新或重新进入对话页后保留。
- 进入已有会话后展示该会话绑定的 Agent / 工作流，并禁用切换。
- 后端对发送请求中的目标做互斥校验与资源可用性校验。

本期不包含：

- 复杂资源共享权限表的完整落库实现。
- 目标收藏、最近使用、置顶排序。
- 在同一个已有会话中动态切换执行目标。
- 工作流运行过程的节点级可视化监控嵌入聊天消息区。

## 3. 推荐方案

采用“后端目标聚合接口 + 前端目标选择器 + 现有会话执行模式”的方案。

### 3.1 为什么选择方案 B

方案 B 的核心是新增：

```http
GET /api/chat/targets
```

前端只调用一个接口拿到所有可选择的对话目标，包括普通对话、Agent、工作流。这样可以把权限、资源状态、排序、不可用原因集中在后端处理。

优势：

- 前端逻辑更干净，不需要同时理解 Agent 列表和工作流列表的权限差异。
- 后端可以统一做资源级校验，避免用户手写 `agentId/workflowId` 绕过前端。
- 后续支持“共享给我的 Agent / 工作流”“最近使用”“收藏目标”时，不需要改前端数据结构。
- 选择器只关心统一的 `ChatTarget` DTO，组件更稳定。

代价：

- 首期需要新增 DTO、Service、Controller 和测试。
- 聚合接口会与现有 `GET /api/agents`、`GET /api/workflows` 有部分数据来源重叠。

这个代价是值得的，因为智能对话入口后续会成为 Agent / 工作流使用的统一入口，目标选择不应长期散落在前端拼装。

## 4. 后端设计

### 4.1 新增接口

```http
GET /api/chat/targets
Authorization: Bearer <token>
```

权限：

- 登录用户可访问。
- 可复用现有认证拦截。
- 接口内部按用户权限决定返回哪些目标。

返回：

```json
{
  "code": 200,
  "message": "success",
  "data": [
    {
      "type": "NONE",
      "targetKey": "none",
      "id": null,
      "name": "普通对话",
      "description": "直接使用所选模型对话",
      "available": true,
      "disabledReason": null,
      "metadata": {}
    },
    {
      "type": "AGENT",
      "targetKey": "agent:4",
      "id": 4,
      "name": "计划8联调测试Agent",
      "description": "用于验证 AgentRouter 与 SkillExecutor 的联调 Agent",
      "available": true,
      "disabledReason": null,
      "metadata": {
        "status": "PUBLISHED",
        "groupName": "通用助手"
      }
    },
    {
      "type": "WORKFLOW",
      "targetKey": "workflow:8",
      "id": 8,
      "name": "未命名工作流（副本）",
      "description": null,
      "available": true,
      "disabledReason": null,
      "metadata": {
        "status": "DRAFT",
        "ownerId": 1
      }
    }
  ]
}
```

### 4.2 DTO

新增 `ChatTargetVO`：

```java
public class ChatTargetVO {
    private String type;          // NONE / AGENT / WORKFLOW
    private String targetKey;     // none / agent:<id> / workflow:<id>
    private Long id;
    private String name;
    private String description;
    private Boolean available;
    private String disabledReason;
    private Map<String, Object> metadata;
}
```

### 4.3 Service

新增 `ChatTargetService`，职责：

- 构造固定的 `NONE` 目标。
- 查询当前用户可用 Agent。
- 查询当前用户可用工作流。
- 统一转换为 `ChatTargetVO`。
- 在发送聊天消息时校验目标是否合法。

建议方法：

```java
public List<ChatTargetVO> listTargets(Long userId, Authentication authentication)
public void validateTarget(Long userId, Long agentId, Long workflowId)
```

### 4.4 Agent 目标来源

首期使用现有 Agent 数据源：

- 如果用户有 `agent:read` 权限，则返回可读 Agent。
- 首期建议只返回 `PUBLISHED` 状态 Agent，避免草稿 Agent 被普通聊天入口误用。
- 管理员可按现有管理权限看到更多 Agent，这个规则后续可细化。

后续接入“Agent开放权限以及授权设计”时，聚合接口只需要调整 `ChatTargetService` 内部过滤规则：

- 使用权限：可出现在聊天目标中。
- 可读权限：可进入详情或查看提示词。
- 可复制权限：可复制为自己的 Agent。

### 4.5 工作流目标来源

首期使用现有 `WorkflowService.listWorkflows(userId)` 规则：

- 返回 `ownerId=userId` 的工作流。
- 要求用户具备 `workflow:read`。
- 可用状态首期不强制只允许 `PUBLISHED`，因为当前工作流运行和编辑流程中已有草稿运行需求；如果产品希望更严，可以后续改为只允许 `PUBLISHED`。

### 4.6 发送消息时的目标校验

`ChatSessionService.createSession()` 或更靠前的 service 层需要增加校验：

1. `agentId` 和 `workflowId` 不能同时存在。
2. `agentId` 存在时，必须是当前用户可用的 Agent。
3. `workflowId` 存在时，必须是当前用户可用的工作流。
4. 目标不可用时返回 `403` 或业务异常。

建议错误信息：

- 同时传入：`agentId 和 workflowId 不能同时指定`
- Agent 不可用：`无权使用该智能体或智能体不存在`
- 工作流不可用：`无权使用该工作流或工作流不存在`

### 4.7 已有会话续聊规则

已有会话发送消息时，后端应继续使用 session 中保存的 `mode/agentId/workflowId`。

规则：

- `POST /api/chat/sessions/{id}/messages`
- `POST /api/chat/sessions/{id}/messages/stream`

这两个接口即使 request body 携带新的 `agentId/workflowId`，也不应覆盖已有 session 的目标。建议直接忽略这两个字段，或明确拒绝并返回错误。推荐首期忽略并保留 session 绑定目标，避免影响现有调用。

## 5. 前端设计

### 5.1 API

新增 `frontend/src/api/chatTarget.ts` 或放入 `chat.ts`：

```ts
export type ChatTargetType = 'NONE' | 'AGENT' | 'WORKFLOW'

export interface ChatTarget {
  type: ChatTargetType
  targetKey: string
  id: number | null
  name: string
  description: string | null
  available: boolean
  disabledReason: string | null
  metadata: Record<string, unknown>
}

export const chatTargetApi = {
  listTargets() {
    return request.get<ApiResponse<ChatTarget[]>>('/chat/targets')
  }
}
```

### 5.2 TargetSelector 组件

新增：

```text
frontend/src/components/chat/TargetSelector.vue
```

职责：

- 调用 `GET /api/chat/targets`。
- 按 `type` 生成 Naive UI `NSelect` 分组选项。
- 支持 `modelValue` / `update:modelValue`。
- 支持 `disabled`。
- 支持目标不可用提示。
- 当保存目标不在聚合接口返回结果中时，回退到 `none`。

组件 props：

```ts
interface Props {
  modelValue: string
  disabled?: boolean
}
```

组件 emits：

```ts
{
  'update:modelValue': [value: string]
  change: [value: string]
}
```

### 5.3 输入区布局

在 `ChatInput` 的工具区中，目标选择器放在模型选择器左侧：

```text
[目标：普通对话 / Agent / 工作流] [模型：deepseek-chat] [发送]
```

建议：

- 目标选择器宽度 220px。
- 模型选择器保留 200px。
- 目标名称过长时省略。
- hover 或下拉描述里展示目标说明。

### 5.4 Chat Store 扩展

在 `stores/chat.ts` 中增加：

- `selectedTarget`
- `setSelectedTarget(value: string)`
- `visibleTargetValue`
- `resolveSelectedTargetPayload()`

本地持久化：

- key：`chat_selected_target`
- value：`none` / `agent:<id>` / `workflow:<id>`

发送新流式消息时：

```ts
const targetPayload = resolveSelectedTargetPayload()
chatApi.streamNewMessage({
  message: content,
  model: selectedModel.value ?? undefined,
  ...targetPayload
})
```

解析规则：

```ts
function resolveSelectedTargetPayload() {
  if (selectedTarget.value.startsWith('agent:')) {
    return { agentId: Number(selectedTarget.value.slice('agent:'.length)) }
  }
  if (selectedTarget.value.startsWith('workflow:')) {
    return { workflowId: Number(selectedTarget.value.slice('workflow:'.length)) }
  }
  return {}
}
```

### 5.5 Chat API 类型修正

统一聊天发送请求类型：

```ts
export interface ChatSendRequest {
  message: string
  model?: string
  agentId?: number
  workflowId?: number
}
```

更新：

- `sendNewMessage(data: ChatSendRequest)`
- `streamNewMessage(data: ChatSendRequest)`
- `streamMessage(sessionId, data: Pick<ChatSendRequest, 'message' | 'model'>)`

### 5.6 ChatView 集成

工具区：

```vue
<TargetSelector
  :model-value="chatStore.visibleTargetValue"
  :disabled="!!chatStore.currentSession"
  @change="chatStore.setSelectedTarget"
/>
<ModelSelector
  :model-value="chatStore.selectedModel"
  @change="chatStore.setSelectedModel"
/>
```

`visibleTargetValue` 规则：

- 当前有会话：
  - `CHAT` -> `none`
  - `AGENT` -> `agent:<agentId>`
  - `WORKFLOW` -> `workflow:<workflowId>`
- 当前无会话：
  - 显示 `selectedTarget`

## 6. 交互规则

### 6.1 新会话

当没有当前会话，或用户点击“新建会话”后：

- 目标选择器可用。
- 选择“普通对话”时，请求不带 `agentId/workflowId`。
- 选择 Agent 时，请求带 `agentId`。
- 选择工作流时，请求带 `workflowId`。
- `agentId` 和 `workflowId` 必须互斥。

### 6.2 已有会话

当用户打开已有会话后：

- 目标选择器展示该会话绑定目标。
- 目标选择器禁用。
- 用户如需换目标，应点击“新建会话”。

原因：

- 会话历史已经绑定执行模式。
- 同一上下文中切换 Agent / 工作流会造成历史消息语义混乱。
- 后端 session 已保存 `mode/agentId/workflowId`。

### 6.3 目标持久化

初始化时：

1. 读取本地 `chat_selected_target`。
2. 调用 `GET /api/chat/targets`。
3. 如果本地目标仍在接口返回列表中并可用，保留。
4. 如果本地目标不可用或不存在，回退为 `none` 并更新本地存储。

## 7. 数据流

### 7.1 加载目标

```text
进入 /chat
  -> TargetSelector mounted
  -> GET /api/chat/targets
  -> ChatTargetService 聚合 NONE / AGENT / WORKFLOW
  -> 前端生成分组选项
```

### 7.2 新建普通对话

```text
目标=none
  -> POST /api/chat/messages/stream
     body: { message, model }
  -> createSession()
  -> mode=CHAT
  -> DefaultChatStrategy.stream()
```

### 7.3 新建 Agent 对话

```text
目标=agent:4
  -> POST /api/chat/messages/stream
     body: { message, model, agentId: 4 }
  -> validateTarget(userId, 4, null)
  -> createSession()
  -> mode=AGENT, agentId=4
  -> AgentRoutingStrategy.stream()
```

### 7.4 新建工作流对话

```text
目标=workflow:8
  -> POST /api/chat/messages/stream
     body: { message, model, workflowId: 8 }
  -> validateTarget(userId, null, 8)
  -> createSession()
  -> mode=WORKFLOW, workflowId=8
  -> WorkflowStrategy.stream()
```

### 7.5 已有会话续聊

```text
打开历史会话
  -> session 已有 mode/agentId/workflowId
  -> 目标选择器展示并禁用
  -> POST /api/chat/sessions/{id}/messages/stream
     body: { message, model }
  -> 后端按 session.mode 执行
```

## 8. 异常与降级

### 8.1 目标聚合接口失败

行为：

- 目标选择器展示“普通对话”。
- Agent / 工作流分组为空。
- 普通对话仍可发送。
- 可以在控制台记录错误，页面不弹阻断型错误。

### 8.2 已保存目标不可用

场景：

- Agent 被删除。
- 工作流被删除。
- 用户权限变化。
- 后端聚合接口不再返回该目标。

行为：

- 自动回退到 `none`。
- 本地存储更新为 `none`。

### 8.3 发送时目标无权限

行为：

- 后端返回错误事件或 HTTP 错误。
- 前端在消息区追加助手错误消息。
- 不清空用户消息历史。
- 不自动切换目标，避免用户误以为请求已按普通对话执行。

### 8.4 工作流执行细节

首期在聊天区展示现有流式文本或最终文本。节点状态、执行日志和 checkpoint 仍通过执行监控页查看。后续可以把工作流运行摘要写入消息 metadata，在聊天区展示“执行了哪些节点、哪些失败、checkpoint 引用”等信息。

## 9. 测试设计

### 9.1 后端测试

新增或扩展：

- `ChatTargetControllerTest`
  - 登录用户可以获取 `NONE` 目标。
  - 有 `agent:read` 权限时返回 Agent 目标。
  - 有 `workflow:read` 权限时返回自己的工作流目标。
  - 无权限时对应分组为空或目标不可用。

- `ChatTargetServiceTest`
  - `targetKey` 格式正确。
  - Agent / Workflow 转换为统一 VO。
  - 不可用资源不返回或标记为不可用。

- `ChatSessionServiceTest`
  - 新消息带 `agentId` 时创建 `AGENT` 会话。
  - 新消息带 `workflowId` 时创建 `WORKFLOW` 会话。
  - `agentId` 和 `workflowId` 同时存在时拒绝。
  - 无权使用目标时拒绝。
  - 已有会话续聊不覆盖原目标。

### 9.2 前端测试

新增或扩展：

- `chatTargetApi.test.ts`
  - `GET /chat/targets` 调用正确。

- `TargetSelector.test.ts`
  - 渲染“普通对话”。
  - 聚合接口返回 Agent / Workflow 后生成分组选项。
  - 已保存目标可用时保留。
  - 已保存目标不可用时回退 `none`。
  - disabled 时不能触发切换。

- `chat.test.ts`
  - `selectedTarget` 从 `localStorage` 初始化。
  - `setSelectedTarget()` 写入 `localStorage`。
  - 新会话流式发送 Agent 时传 `agentId`。
  - 新会话流式发送 Workflow 时传 `workflowId`。
  - 已有会话续聊不传新的目标 ID。

- `chatApi.test.ts`
  - `streamNewMessage()` body 支持 `agentId/workflowId`。

### 9.3 手工验收

1. 登录后进入 `/chat`。
2. 目标选择器默认显示“普通对话”或上次保存目标。
3. 下拉中能看到“智能体”和“工作流”分组。
4. 选择一个 Agent，发送消息，新会话列表中显示 Agent 名称。
5. 选择一个工作流，发送消息，新会话列表中显示工作流名称。
6. 刷新页面，目标选择仍保留。
7. 打开已有 Agent 会话，目标选择器显示 Agent 并禁用。
8. 点击新建会话，目标选择器重新可用。
9. 手写无权限 `agentId/workflowId` 请求时，后端拒绝。

## 10. 实施顺序建议

1. 后端新增 `ChatTargetVO`。
2. 后端新增 `ChatTargetService`，聚合 `NONE / AGENT / WORKFLOW`。
3. 后端新增 `GET /api/chat/targets`。
4. 后端在创建聊天会话前增加目标互斥与资源可用性校验。
5. 前端新增 `chatTargetApi` 和 `ChatTarget` 类型。
6. 前端新增 `TargetSelector.vue`。
7. 前端扩展 `chatStore` 的 `selectedTarget`、持久化、payload 解析。
8. 前端扩展 `chatApi.streamNewMessage()` 类型。
9. `ChatView.vue` 工具区接入 `TargetSelector`。
10. 补齐前后端测试。
11. 手工验证普通对话、Agent 对话、工作流对话三条链路。

## 11. 后续演进

方案 B 已经把目标聚合接口作为首期能力，因此后续演进可以在接口内部扩展，不需要改变前端选择器结构：

- 增加最近使用排序。
- 增加收藏目标。
- 增加团队空间 / 多租户过滤。
- 返回不可用目标及原因，用于灰显展示。
- 接入 Agent 使用授权、可读授权、可复制授权。
- 接入工作流共享授权。
- 在目标 metadata 中返回运行统计、最近执行状态、推荐模型等辅助信息。

