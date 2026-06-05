# 流式输出与思考过程展示 — 设计文档

## 目标

在智能对话页面实现：
1. **流式输出** — 消息逐字/逐块实时展示，而非等待完整响应
2. **思考过程展示** — 自动检测并展示供应商返回的思考/推理过程（deepseek `reasoning_content`、Claude `thinking` 等），有的就展示，没有就跳过

## 传输方式：SSE

采用 Server-Sent Events (SSE) 而非 WebSocket。原因：
- SSE 基于 HTTP，与现有 Vite `/api` 代理兼容，无需额外配置
- 单向流（服务器→客户端）天然匹配 LLM 流式响应场景
- 现有 WebSocket 基础设施保留不删，SSE 作为主要流式通道

## 数据流

```
前端 (fetch + ReadableStream)
  → POST /api/chat/sessions/{id}/messages/stream
    → ChatController SSE endpoint
      → ChatSessionService.sendMessageStream()
        → OrchestrationEngine → LlmGateway.chatStream()
          → Provider.chatStream() 解析上游 SSE
            → 提取 content + reasoning_content/thinking
  ← SSE events:
      data: {"type":"THINKING","content":"让我想想..."}
      data: {"type":"CHUNK","content":"你好"}
      data: {"type":"DONE"}
      data: {"type":"ERROR","message":"..."}
    → 前端 store 更新 streamingThinking / streamingContent
      → MessageBubble 渲染
```

## 后端改动

### 1. StreamEvent DTO

新建 `com.superprogrammer.chat.dto.StreamEvent`：

```java
@Data @Builder
public class StreamEvent {
    private String type;    // CHUNK | THINKING | DONE | ERROR
    private String content; // chunk/thinking 内容，ERROR 时为错误消息
}
```

### 2. OpenAICompatibleProvider.chatStream() 改造

当前 `chatStream()` 返回 `Flux<String>`（纯 content 文本）。改造为：

- 解析上游 SSE 的每个 `data:` 行为 JSON
- 检查 `choices[0].delta.reasoning_content`（deepseek R1 系列）
- 检查 `choices[0].delta.content`（标准 content）
- 返回 `Flux<StreamEvent>`，按内容类型标记 `CHUNK` 或 `THINKING`

对不支持思考过程的供应商，只会有 `CHUNK` 事件，`THINKING` 事件不会出现。

### 3. ClaudeProvider.chatStream() 改造

Claude Messages API 流式格式不同：
- `type: "content_block_delta"` + `delta.type: "thinking_delta"` → thinking
- `type: "content_block_delta"` + `delta.type: "text_delta"` → content

同样返回 `Flux<StreamEvent>`。

### 4. LlmProviderInterface 接口变更

`chatStream()` 返回类型从 `Flux<String>` 改为 `Flux<StreamEvent>`。

### 5. LlmGateway.chatStream() 透传

直接透传 provider 返回的 `Flux<StreamEvent>`。

### 6. ChatSessionService.sendMessageStream() 改造

返回类型从 `Flux<String>` 改为 `Flux<StreamEvent>`。
- `doOnComplete` 中保存完整 response 时需区分 thinking 和 content
- 数据库存储：thinking 内容存入 `ChatMessage.metadata` JSON 字段（`{"thinking": "..."}`)

### 7. ChatController 新 SSE 端点

```
POST /api/chat/sessions/{id}/messages/stream
Content-Type: application/json
Body: { "message": "...", "model": "k2.6" }

Response: text/event-stream
data: {"type":"THINKING","content":"..."}
data: {"type":"CHUNK","content":"..."}
data: {"type":"DONE"}
```

无 session 时用 `POST /api/chat/messages/stream`，自动创建 session。

使用 Spring 的 `SseEmitter` 或直接返回 `Flux<ServerSentEvent<StreamEvent>>`（WebFlux 方式）。鉴于项目已用 WebFlux（WebClient/Flux），采用 `Flux<ServerSentEvent>` 方式更自然。

### 8. OrchestrationEngine / DefaultChatStrategy

`executeStream()` 返回类型改为 `Flux<StreamEvent>`，透传 LLM 的流式事件。

## 前端改动

### 1. chat.ts store

新增状态：
```ts
const streamingThinking = ref('')  // 思考过程累积文本
```

新增 `sendStreamingMessage()` 方法：
- 使用 `fetch` POST 请求 SSE 端点（EventSource 只支持 GET）
- 读取 `response.body` 的 `ReadableStream`
- 解析 SSE `data:` 行，按 type 更新 `streamingContent` / `streamingThinking`
- 完成后将 thinking 存入消息的 metadata

### 2. chat API

新增：
```ts
streamMessage(sessionId: number, data: { message: string; model?: string }): Promise<Response>
streamNewMessage(data: { message: string; model?: string }): Promise<Response>
```

### 3. ChatView.vue

流式消息区域同时渲染：
- thinking 区（如果有 `streamingThinking`）
- content 区（`streamingContent`）

`handleSend` 优先使用 `sendStreamingMessage`，SSE 失败回退 REST。

### 4. MessageBubble.vue

添加可折叠思考过程区域：
```
┌─────────────────────────────┐
│ 💭 思考过程          [▼ 收起] │
│ 让我分析一下这个问题...       │
│ 首先需要考虑...              │
├─────────────────────────────┤
│ 你好！我是AI助手...           │
└─────────────────────────────┘
```

- 默认展开 thinking，点击标题栏可折叠
- 流式输出时 thinking 实时追加
- 无 thinking 时不显示该区域
- 使用暗色背景区分 thinking 和正式回复

### 5. 安全降级

- SSE 连接失败 → 回退 REST 非流式模式（现有逻辑）
- thinking 解析失败 → 忽略，只展示 content
- 不支持思考过程的供应商 → 只看到 CHUNK 事件，无 THINKING

## 不做的事

- ❌ 不删除现有 WebSocket 基础设施
- ❌ 不做 Markdown 渲染（后续独立任务）
- ❌ 不做代码高亮（后续独立任务）
- ❌ 不做流式中断/取消（后续独立任务）
