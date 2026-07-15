# 流式输出与思考过程展示 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在智能对话页面实现 SSE 流式输出和思考过程展示（自动检测 reasoning_content/thinking）

**Architecture:** 后端新增 StreamEvent DTO 贯穿 Provider → Gateway → Engine → Controller 全链路，返回 `Flux<ServerSentEvent>`；前端用 `fetch + ReadableStream` 读取 SSE，chat store 区分 streamingContent/streamingThinking 状态，MessageBubble 添加可折叠思考面板。

**Tech Stack:** Spring WebFlux (已有), Flux/ServerSentEvent, fetch ReadableStream, Naive UI

---

## File Map

### Backend — Create
- `backend/src/main/java/com/superprogrammer/chat/dto/StreamEvent.java` — 流式事件 DTO

### Backend — Modify
- `backend/src/main/java/com/superprogrammer/llm/provider/LlmProviderInterface.java:10` — chatStream 返回 Flux<StreamEvent>
- `backend/src/main/java/com/superprogrammer/llm/dto/StreamEvent.java` — 复用 chat 模块的 StreamEvent（LlmProviderInterface 引用 chat.dto.StreamEvent）
- `backend/src/main/java/com/superprogrammer/llm/provider/OpenAICompatibleProvider.java:59-73,127-131` — 解析 reasoning_content
- `backend/src/main/java/com/superprogrammer/llm/provider/ClaudeProvider.java:58-72,122-133` — 解析 thinking blocks
- `backend/src/main/java/com/superprogrammer/llm/LlmGateway.java:41-51` — 透传 Flux<StreamEvent>
- `backend/src/main/java/com/superprogrammer/engine/strategy/ExecutionStrategy.java:9-11` — stream() 返回 Flux<StreamEvent>
- `backend/src/main/java/com/superprogrammer/engine/strategy/DefaultChatStrategy.java:41-54` — 适配新签名
- `backend/src/main/java/com/superprogrammer/engine/OrchestrationEngine.java:34-47` — executeStream 返回 Flux<StreamEvent>
- `backend/src/main/java/com/superprogrammer/chat/service/ChatSessionService.java:161-215` — sendMessageStream 返回 Flux<StreamEvent>
- `backend/src/main/java/com/superprogrammer/chat/controller/ChatController.java` — 新增 SSE 端点

### Frontend — Modify
- `frontend/src/api/chat.ts` — 新增 streamMessage/streamNewMessage
- `frontend/src/stores/chat.ts` — 新增 streamingThinking, sendStreamingMessage
- `frontend/src/views/ChatView.vue:47-65` — 渲染 thinking + content
- `frontend/src/components/chat/MessageBubble.vue` — 可折叠思考面板

---

## Task 1: StreamEvent DTO

**Files:**
- Create: `backend/src/main/java/com/superprogrammer/chat/dto/StreamEvent.java`

- [ ] **Step 1: Create StreamEvent DTO**

```java
package com.superprogrammer.chat.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StreamEvent {
    private String type;    // CHUNK | THINKING | DONE | ERROR
    private String content;

    public static StreamEvent chunk(String content) {
        return StreamEvent.builder().type("CHUNK").content(content).build();
    }
    public static StreamEvent thinking(String content) {
        return StreamEvent.builder().type("THINKING").content(content).build();
    }
    public static StreamEvent done() {
        return StreamEvent.builder().type("DONE").build();
    }
    public static StreamEvent error(String message) {
        return StreamEvent.builder().type("ERROR").content(message).build();
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add backend/src/main/java/com/superprogrammer/chat/dto/StreamEvent.java
git commit -m "feat: add StreamEvent DTO for SSE streaming"
```

---

## Task 2: LlmProviderInterface — 改 chatStream 返回类型

**Files:**
- Modify: `backend/src/main/java/com/superprogrammer/llm/provider/LlmProviderInterface.java`

- [ ] **Step 1: Change chatStream return type**

将文件改为：

```java
package com.superprogrammer.llm.provider;

import com.superprogrammer.chat.dto.StreamEvent;
import com.superprogrammer.llm.dto.LlmRequest;
import com.superprogrammer.llm.dto.LlmResponse;
import reactor.core.publisher.Flux;

public interface LlmProviderInterface {
    String getName();
    LlmResponse chat(LlmRequest request);
    Flux<StreamEvent> chatStream(LlmRequest request);
    boolean supports(String model);
}
```

- [ ] **Step 2: Commit**

```bash
git add backend/src/main/java/com/superprogrammer/llm/provider/LlmProviderInterface.java
git commit -m "feat: change chatStream return type to Flux<StreamEvent>"
```

---

## Task 3: OpenAICompatibleProvider — 解析 reasoning_content

**Files:**
- Modify: `backend/src/main/java/com/superprogrammer/llm/provider/OpenAICompatibleProvider.java`

- [ ] **Step 1: Rewrite chatStream() and helpers**

替换 `chatStream()` 方法（约第 59-73 行）和 `extractStreamContent()` 方法（约第 124-131 行）：

```java
@Override
public Flux<StreamEvent> chatStream(LlmRequest request) {
    Map<String, Object> body = buildRequestBody(request);
    body.put("stream", true);

    return webClient.post()
            .uri("/v1/chat/completions")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body)
            .retrieve()
            .bodyToFlux(String.class)
            .filter(line -> !line.isBlank() && line.startsWith("data: "))
            .map(line -> line.substring(6))
            .filter(data -> !"[DONE]".equals(data))
            .map(this::parseStreamChunk)
            .filter(evt -> evt.getContent() != null && !evt.getContent().isEmpty());
}

/**
 * 解析单个 SSE data chunk，同时提取 content 和 reasoning_content
 */
private StreamEvent parseStreamChunk(String data) {
    try {
        JsonNode node = objectMapper.readTree(data);
        JsonNode delta = node.at("/choices/0/delta");

        // DeepSeek R1 系列返回 reasoning_content
        if (delta.has("reasoning_content") && !delta.get("reasoning_content").isNull()) {
            String text = delta.get("reasoning_content").asText("");
            if (!text.isEmpty()) {
                return StreamEvent.thinking(text);
            }
        }

        // 标准 content
        if (delta.has("content") && !delta.get("content").isNull()) {
            String text = delta.get("content").asText("");
            if (!text.isEmpty()) {
                return StreamEvent.chunk(text);
            }
        }

        return StreamEvent.chunk(""); // will be filtered
    } catch (Exception e) {
        return StreamEvent.chunk(""); // will be filtered
    }
}
```

需要新增 import：

```java
import com.superprogrammer.chat.dto.StreamEvent;
```

- [ ] **Step 2: Commit**

```bash
git add backend/src/main/java/com/superprogrammer/llm/provider/OpenAICompatibleProvider.java
git commit -m "feat: OpenAICompatibleProvider parse reasoning_content for thinking"
```

---

## Task 4: ClaudeProvider — 解析 thinking blocks

**Files:**
- Modify: `backend/src/main/java/com/superprogrammer/llm/provider/ClaudeProvider.java`

- [ ] **Step 1: Rewrite chatStream() and extractStreamContent()**

替换 `chatStream()` 方法（约第 58-72 行）和 `extractStreamContent()` 方法（约第 122-133 行）：

```java
@Override
public Flux<StreamEvent> chatStream(LlmRequest request) {
    Map<String, Object> body = buildRequestBody(request);
    body.put("stream", true);

    return webClient.post()
            .uri("/v1/messages")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body)
            .retrieve()
            .bodyToFlux(String.class)
            .filter(line -> !line.isBlank() && line.startsWith("data: "))
            .map(line -> line.substring(6))
            .filter(data -> !"[DONE]".equals(data))
            .map(this::parseClaudeChunk)
            .filter(evt -> evt.getContent() != null && !evt.getContent().isEmpty());
}

/**
 * 解析 Claude Messages API 流式 chunk
 * content_block_delta:
 *   - delta.type=thinking_delta → thinking
 *   - delta.type=text_delta → content
 */
private StreamEvent parseClaudeChunk(String data) {
    try {
        JsonNode node = objectMapper.readTree(data);
        String type = node.at("/type").asText("");

        if ("content_block_delta".equals(type)) {
            String deltaType = node.at("/delta/type").asText("");
            if ("thinking_delta".equals(deltaType)) {
                String text = node.at("/delta/thinking").asText("");
                if (!text.isEmpty()) return StreamEvent.thinking(text);
            } else if ("text_delta".equals(deltaType)) {
                String text = node.at("/delta/text").asText("");
                if (!text.isEmpty()) return StreamEvent.chunk(text);
            }
        }
        return StreamEvent.chunk(""); // will be filtered
    } catch (Exception e) {
        return StreamEvent.chunk("");
    }
}
```

需要新增 import：

```java
import com.superprogrammer.chat.dto.StreamEvent;
```

- [ ] **Step 2: Commit**

```bash
git add backend/src/main/java/com/superprogrammer/llm/provider/ClaudeProvider.java
git commit -m "feat: ClaudeProvider parse thinking_delta for thinking display"
```

---

## Task 5: LlmGateway + ExecutionStrategy + Engine 链路适配

**Files:**
- Modify: `backend/src/main/java/com/superprogrammer/llm/LlmGateway.java`
- Modify: `backend/src/main/java/com/superprogrammer/engine/strategy/ExecutionStrategy.java`
- Modify: `backend/src/main/java/com/superprogrammer/engine/strategy/DefaultChatStrategy.java`
- Modify: `backend/src/main/java/com/superprogrammer/engine/OrchestrationEngine.java`

- [ ] **Step 1: Update LlmGateway.chatStream()**

`LlmGateway.java` 中两处 `chatStream` 方法，改返回类型为 `Flux<StreamEvent>`：

```java
// 无 userId 版本
public Flux<StreamEvent> chatStream(LlmRequest request) {
    LlmProviderInterface provider = findProvider(request.getModel(), null);
    log.info("LLM流式调用 model={} provider={}", request.getModel(), provider.getName());
    return provider.chatStream(request);
}

// 有 userId 版本
public Flux<StreamEvent> chatStream(LlmRequest request, Long userId) {
    LlmProviderInterface provider = findProvider(request.getModel(), userId);
    log.info("LLM流式调用 model={} provider={} userId={}", request.getModel(), provider.getName(), userId);
    return provider.chatStream(request);
}
```

添加 import: `import com.superprogrammer.chat.dto.StreamEvent;`

- [ ] **Step 2: Update ExecutionStrategy.stream()**

```java
package com.superprogrammer.engine.strategy;

import com.superprogrammer.chat.dto.StreamEvent;
import com.superprogrammer.engine.context.ExecutionContext;
import reactor.core.publisher.Flux;

public interface ExecutionStrategy {
    String execute(ExecutionContext context, String userMessage);

    default Flux<StreamEvent> stream(ExecutionContext context, String userMessage) {
        return Flux.just(StreamEvent.chunk(execute(context, userMessage)), StreamEvent.done());
    }
}
```

- [ ] **Step 3: Update DefaultChatStrategy.stream()**

```java
@Override
public Flux<StreamEvent> stream(ExecutionContext context, String userMessage) {
    log.info("默认聊天模式流式执行, session={}", context.getSessionId());

    context.addMessage("user", userMessage);

    LlmRequest request = LlmRequest.builder()
            .model(resolveModel(context))
            .messages(context.getMessageHistory())
            .stream(true)
            .build();

    return llmGateway.chatStream(request, context.getUserId());
}
```

添加 import: `import com.superprogrammer.chat.dto.StreamEvent;`

- [ ] **Step 4: Update OrchestrationEngine.executeStream()**

```java
public Flux<StreamEvent> executeStream(ExecutionContext context, String userMessage) {
    String mode = context.getMode();
    log.info("OrchestrationEngine流式执行, mode={}, session={}", mode, context.getSessionId());

    return switch (mode) {
        case "CHAT" -> defaultChatStrategy.stream(context, userMessage);
        case "AGENT" -> agentRoutingStrategy.stream(context, userMessage);
        case "WORKFLOW" -> workflowStrategy.stream(context, userMessage);
        default -> {
            log.warn("未知执行模式: {}", mode);
            yield Flux.just(StreamEvent.chunk("不支持的执行模式: " + mode), StreamEvent.done());
        }
    };
}
```

添加 import: `import com.superprogrammer.chat.dto.StreamEvent;`

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/superprogrammer/llm/LlmGateway.java \
        backend/src/main/java/com/superprogrammer/engine/strategy/ExecutionStrategy.java \
        backend/src/main/java/com/superprogrammer/engine/strategy/DefaultChatStrategy.java \
        backend/src/main/java/com/superprogrammer/engine/OrchestrationEngine.java
git commit -m "feat: adapt streaming chain to Flux<StreamEvent>"
```

---

## Task 6: ChatSessionService.sendMessageStream + ChatController SSE 端点

**Files:**
- Modify: `backend/src/main/java/com/superprogrammer/chat/service/ChatSessionService.java`
- Modify: `backend/src/main/java/com/superprogrammer/chat/controller/ChatController.java`

- [ ] **Step 1: Update ChatSessionService.sendMessageStream()**

将 `sendMessageStream()` 方法（第 161-215 行）改为返回 `Flux<StreamEvent>`。替换整个方法：

```java
public Flux<StreamEvent> sendMessageStream(Long userId, ChatRequest request) {
    ChatSession session;
    try {
        if (request.getSessionId() == null) {
            SessionVO vo = createSession(userId, request);
            session = sessionMapper.selectById(vo.getId());
        } else {
            session = getSessionOrFail(userId, request.getSessionId());
        }
    } catch (Exception e) {
        return Flux.just(StreamEvent.error(e.getMessage()), StreamEvent.done());
    }

    ChatMessage userMsg = new ChatMessage();
    userMsg.setSessionId(session.getId());
    userMsg.setRole("USER");
    userMsg.setContent(request.getMessage());
    messageMapper.insert(userMsg);

    ExecutionContext context = new ExecutionContext(
            session.getId(), session.getMode(), session.getAgentId(), session.getWorkflowId());
    context.setModel(request.getModel());
    context.setUserId(userId);

    List<ChatMessage> history = loadContextWindow(session.getId());
    for (ChatMessage msg : history) {
        context.addMessage(msg.getRole(), msg.getContent());
    }

    String memoryContext = memoryService.buildMemoryContext(userId);
    if (memoryContext != null && !memoryContext.isEmpty()) {
        context.addMessage("system", "用户记忆:\n" + memoryContext);
    }

    Long sessionId = session.getId();
    StringBuilder fullResponse = new StringBuilder();
    StringBuilder fullThinking = new StringBuilder();

    return orchestrationEngine.executeStream(context, request.getMessage())
            .doOnNext(evt -> {
                if ("CHUNK".equals(evt.getType()) && evt.getContent() != null) {
                    fullResponse.append(evt.getContent());
                } else if ("THINKING".equals(evt.getType()) && evt.getContent() != null) {
                    fullThinking.append(evt.getContent());
                }
            })
            .concatWith(Flux.defer(() -> {
                // After stream completes, save assistant message
                String responseText = fullResponse.toString();
                ChatMessage assistantMsg = new ChatMessage();
                assistantMsg.setSessionId(sessionId);
                assistantMsg.setRole("ASSISTANT");
                assistantMsg.setContent(responseText);
                if (fullThinking.length() > 0) {
                    try {
                        assistantMsg.setMetadata(
                                new ObjectMapper().writeValueAsString(
                                        Map.of("thinking", fullThinking.toString())));
                    } catch (Exception ignored) {}
                }
                messageMapper.insert(assistantMsg);

                memoryService.extractMemoriesAsync(userId, request.getMessage(), responseText);

                return Flux.just(StreamEvent.done());
            }))
            .doOnError(e -> log.error("流式执行失败: {}", e.getMessage()));
}
```

需要添加的 imports：

```java
import com.superprogrammer.chat.dto.StreamEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
```

- [ ] **Step 2: Add SSE endpoints to ChatController**

在 `ChatController.java` 末尾（`getCurrentUserId()` 方法前）添加两个新端点：

```java
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

@PostMapping(value = "/sessions/{id}/messages/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public ResponseEntity<SseEmitter> sendMessageStream(
        @PathVariable Long id,
        @RequestBody ChatRequest request) {
    Long userId = getCurrentUserId();
    request.setSessionId(id);

    SseEmitter emitter = new SseEmitter(120_000L);

    chatSessionService.sendMessageStream(userId, request)
            .subscribe(
                    evt -> {
                        try {
                            emitter.send(SseEmitter.event()
                                    .data(evt));
                        } catch (Exception e) {
                            emitter.completeWithError(e);
                        }
                    },
                    emitter::completeWithError,
                    emitter::complete
            );

    return ResponseEntity.ok()
            .contentType(MediaType.TEXT_EVENT_STREAM)
            .body(emitter);
}

@PostMapping(value = "/messages/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public ResponseEntity<SseEmitter> sendMessageNewStream(@RequestBody ChatRequest request) {
    Long userId = getCurrentUserId();

    SseEmitter emitter = new SseEmitter(120_000L);

    chatSessionService.sendMessageStream(userId, request)
            .subscribe(
                    evt -> {
                        try {
                            emitter.send(SseEmitter.event()
                                    .data(evt));
                        } catch (Exception e) {
                            emitter.completeWithError(e);
                        }
                    },
                    emitter::completeWithError,
                    emitter::complete
            );

    return ResponseEntity.ok()
            .contentType(MediaType.TEXT_EVENT_STREAM)
            .body(emitter);
}
```

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/com/superprogrammer/chat/service/ChatSessionService.java \
        backend/src/main/java/com/superprogrammer/chat/controller/ChatController.java
git commit -m "feat: add SSE streaming endpoints and sendMessageStream with thinking support"
```

---

## Task 7: 前端 chat API — 新增 streaming 函数

**Files:**
- Modify: `frontend/src/api/chat.ts`

- [ ] **Step 1: Add streaming API functions**

在 `chat.ts` 文件的 `chatApi` 对象中，`sendNewMessage` 之后添加：

```ts
  // Streaming (SSE)
  streamMessage(sessionId: number, data: { message: string; model?: string }) {
    const token = localStorage.getItem('access_token') || ''
    return fetch(`/api/chat/sessions/${sessionId}/messages/stream`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${token}`
      },
      body: JSON.stringify(data)
    })
  },

  streamNewMessage(data: { message: string; model?: string }) {
    const token = localStorage.getItem('access_token') || ''
    return fetch('/api/chat/messages/stream', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${token}`
      },
      body: JSON.stringify(data)
    })
  }
```

注意：需要检查项目实际 token 存储方式。查看 `frontend/src/utils/storage.ts` 获取正确的 key。

- [ ] **Step 2: Commit**

```bash
git add frontend/src/api/chat.ts
git commit -m "feat: add SSE streaming API functions"
```

---

## Task 8: 前端 chat store — streamingThinking + sendStreamingMessage

**Files:**
- Modify: `frontend/src/stores/chat.ts`

- [ ] **Step 1: Add streamingThinking state**

在 `streamingContent` 声明后（约第13行）添加：

```ts
const streamingThinking = ref('')
```

- [ ] **Step 2: Add sendStreamingMessage function**

在 `sendMessage` 函数之后（约第93行）添加新方法：

```ts
  async function sendStreamingMessage(content: string) {
    sending.value = true
    streamingContent.value = ''
    streamingThinking.value = ''

    // Add user message immediately
    messages.value.push({
      id: Date.now(),
      sessionId: currentSessionId.value ?? 0,
      role: 'USER',
      content,
      metadata: null,
      createdAt: new Date().toISOString()
    })

    try {
      const response = currentSessionId.value
        ? await chatApi.streamMessage(currentSessionId.value, {
            message: content,
            model: selectedModel.value ?? undefined
          })
        : await chatApi.streamNewMessage({
            message: content,
            model: selectedModel.value ?? undefined
          })

      if (!response.ok || !response.body) {
        // Fallback to REST
        sending.value = false
        messages.value.pop() // remove user message, REST will re-add
        return sendMessage(content)
      }

      const reader = response.body.getReader()
      const decoder = new TextDecoder()
      let buffer = ''

      while (true) {
        const { done, value } = await reader.read()
        if (done) break

        buffer += decoder.decode(value, { stream: true })

        // Parse SSE lines
        const lines = buffer.split('\n')
        buffer = lines.pop() || ''

        for (const line of lines) {
          if (line.startsWith('data:')) {
            const jsonStr = line.substring(5).trim()
            if (!jsonStr) continue
            try {
              const evt = JSON.parse(jsonStr)
              switch (evt.type) {
                case 'CHUNK':
                  streamingContent.value += evt.content || ''
                  break
                case 'THINKING':
                  streamingThinking.value += evt.content || ''
                  break
                case 'DONE':
                  // Finalize: push assistant message
                  messages.value.push({
                    id: Date.now(),
                    sessionId: currentSessionId.value ?? 0,
                    role: 'ASSISTANT',
                    content: streamingContent.value,
                    metadata: streamingThinking.value
                      ? JSON.stringify({ thinking: streamingThinking.value })
                      : null,
                    createdAt: new Date().toISOString()
                  })
                  streamingContent.value = ''
                  streamingThinking.value = ''
                  sending.value = false
                  await fetchSessions()
                  break
                case 'ERROR':
                  streamingContent.value = ''
                  streamingThinking.value = ''
                  sending.value = false
                  console.error('Stream error:', evt.content)
                  break
              }
            } catch {
              // Ignore malformed JSON
            }
          }
        }
      }
    } catch (e) {
      // Fallback to REST on any error
      console.warn('SSE failed, falling back to REST:', e)
      sending.value = false
      streamingContent.value = ''
      streamingThinking.value = ''
      messages.value.pop()
      return sendMessage(content)
    }
  }
```

- [ ] **Step 3: Export streamingThinking**

在 return 对象中添加 `streamingThinking`：

```ts
    streamingThinking,
    sendStreamingMessage,
```

- [ ] **Step 4: Commit**

```bash
git add frontend/src/stores/chat.ts
git commit -m "feat: add streamingThinking state and sendStreamingMessage with SSE parsing"
```

---

## Task 9: ChatView.vue — 渲染 thinking + content

**Files:**
- Modify: `frontend/src/views/ChatView.vue`

- [ ] **Step 1: Add streaming thinking display**

在 streaming message 区域（约第 47-61 行），在 `<div v-if="chatStore.streamingContent"` 之前插入 thinking 显示：

找到这段：
```html
          <!-- Streaming message -->
          <div v-if="chatStore.streamingContent" class="chat-view__streaming">
```

在其前面插入：
```html
          <!-- Streaming thinking -->
          <div v-if="chatStore.streamingThinking" class="chat-view__streaming-thinking">
            <div class="chat-view__thinking-header">💭 思考中...</div>
            <div class="chat-view__thinking-text">{{ chatStore.streamingThinking }}</div>
          </div>
```

- [ ] **Step 2: Update handleSend to use streaming**

将 `handleSend` 函数改为优先使用 SSE：

```ts
function handleSend(message: string) {
  chatStore.sendStreamingMessage(message)
}
```

- [ ] **Step 3: Add thinking styles**

在 `<style>` 块末尾（`@keyframes blink` 之前）添加：

```scss
.chat-view__streaming-thinking {
  margin: 12px 20px;
  padding: 12px 16px;
  background: rgba(255, 255, 255, 0.03);
  border-left: 3px solid var(--color-primary);
  border-radius: 4px;
}

.chat-view__thinking-header {
  font-size: 12px;
  color: var(--color-text-tertiary);
  margin-bottom: 6px;
}

.chat-view__thinking-text {
  font-size: 13px;
  color: var(--color-text-secondary);
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-word;
}
```

- [ ] **Step 4: Commit**

```bash
git add frontend/src/views/ChatView.vue
git commit -m "feat: render streaming thinking in ChatView with SSE-first send"
```

---

## Task 10: MessageBubble.vue — 可折叠思考面板

**Files:**
- Modify: `frontend/src/components/chat/MessageBubble.vue`

- [ ] **Step 1: Rewrite entire component**

```vue
<template>
  <div class="message-bubble" :class="`message-bubble--${message.role.toLowerCase()}`">
    <div class="message-bubble__avatar">
      <div v-if="message.role === 'USER'" class="message-bubble__avatar-icon message-bubble__avatar-icon--user">
        <n-icon size="18" :component="PersonOutline" />
      </div>
      <div v-else class="message-bubble__avatar-icon message-bubble__avatar-icon--assistant">
        <n-icon size="18" :component="SparklesOutline" />
      </div>
    </div>
    <div class="message-bubble__content">
      <div class="message-bubble__role">
        {{ message.role === 'USER' ? '你' : '助手' }}
      </div>
      <!-- Thinking section -->
      <div v-if="thinkingText" class="message-bubble__thinking">
        <div class="message-bubble__thinking-toggle" @click="showThinking = !showThinking">
          <span>💭 思考过程</span>
          <span class="message-bubble__thinking-action">{{ showThinking ? '收起' : '展开' }}</span>
        </div>
        <div v-show="showThinking" class="message-bubble__thinking-body">{{ thinkingText }}</div>
      </div>
      <!-- Content -->
      <div class="message-bubble__text">{{ message.content }}</div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { NIcon } from 'naive-ui'
import { PersonOutline, SparklesOutline } from '@vicons/ionicons5'
import type { ChatMessage } from '@/api/chat'

const props = defineProps<{
  message: ChatMessage
}>()

const showThinking = ref(true)

const thinkingText = computed(() => {
  if (!props.message.metadata) return null
  try {
    const meta = JSON.parse(props.message.metadata)
    return meta.thinking || null
  } catch {
    return null
  }
})
</script>

<style lang="scss" scoped>
.message-bubble {
  display: flex;
  gap: 12px;
  padding: 16px 20px;

  &--assistant {
    background: var(--color-surface);
  }
}

.message-bubble__avatar {
  flex-shrink: 0;
}

.message-bubble__avatar-icon {
  width: 32px;
  height: 32px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;

  &--user {
    background: var(--color-primary);
    color: white;
  }

  &--assistant {
    background: var(--color-primary-light);
    color: var(--color-primary);
  }
}

.message-bubble__content {
  flex: 1;
  min-width: 0;
}

.message-bubble__role {
  font-size: 12px;
  color: var(--color-text-tertiary);
  margin-bottom: 4px;
}

.message-bubble__thinking {
  margin-bottom: 8px;
  border: 1px solid var(--color-border-light);
  border-radius: 6px;
  overflow: hidden;
}

.message-bubble__thinking-toggle {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 8px 12px;
  background: rgba(255, 255, 255, 0.03);
  cursor: pointer;
  font-size: 12px;
  color: var(--color-text-tertiary);
  user-select: none;

  &:hover {
    background: rgba(255, 255, 255, 0.05);
  }
}

.message-bubble__thinking-action {
  font-size: 11px;
  color: var(--color-primary);
}

.message-bubble__thinking-body {
  padding: 10px 12px;
  font-size: 13px;
  color: var(--color-text-secondary);
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 300px;
  overflow-y: auto;
}

.message-bubble__text {
  font-size: 14px;
  color: var(--color-text-primary);
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
}
</style>
```

- [ ] **Step 2: Commit**

```bash
git add frontend/src/components/chat/MessageBubble.vue
git commit -m "feat: add collapsible thinking panel in MessageBubble"
```

---

## Task 11: ChatWebSocketHandler 适配

**Files:**
- Modify: `backend/src/main/java/com/superprogrammer/chat/websocket/ChatWebSocketHandler.java`

- [ ] **Step 1: Update WS handler for StreamEvent**

将 `handleTextMessage` 方法中的流式订阅逻辑（约第 53-77 行）替换为：

```java
    // Stream tokens
    chatSessionService.sendMessageStream(userId, request)
            .subscribe(
                    evt -> {
                        try {
                            String type = evt.getType();
                            if ("CHUNK".equals(type)) {
                                sendMessage(session, toJson("CHUNK", Map.of("content", evt.getContent())));
                            } else if ("THINKING".equals(type)) {
                                sendMessage(session, toJson("THINKING", Map.of("content", evt.getContent())));
                            }
                            // DONE and ERROR are handled in onComplete/onError
                        } catch (IOException e) {
                            log.error("发送CHUNK失败: {}", e.getMessage());
                        }
                    },
                    error -> {
                        log.error("流式执行失败: {}", error.getMessage(), error);
                        try {
                            sendError(session, "执行失败: " + error.getMessage());
                        } catch (Exception e) {
                            log.error("发送错误失败: {}", e.getMessage());
                        }
                    },
                    () -> {
                        try {
                            sendMessage(session, toJson("MESSAGE_COMPLETE", Map.of()));
                        } catch (IOException e) {
                            log.error("发送MESSAGE_COMPLETE失败: {}", e.getMessage());
                        }
                    }
            );
```

- [ ] **Step 2: Commit**

```bash
git add backend/src/main/java/com/superprogrammer/chat/websocket/ChatWebSocketHandler.java
git commit -m "feat: adapt ChatWebSocketHandler for StreamEvent with THINKING support"
```

---

## Task 12: 编译验证 + 端到端测试

- [ ] **Step 1: 编译后端**

```bash
cd backend && mvn compile
```

Expected: BUILD SUCCESS

- [ ] **Step 2: 运行后端测试**

```bash
cd backend && mvn test
```

Expected: All tests pass. 可能需要更新 `OpenAICompatibleProviderTest` 中的 `chatStream` 相关测试。

- [ ] **Step 3: 启动后端+前端，手动测试**

```bash
# Backend
cd backend && mvn spring-boot:run

# Frontend
cd frontend && npm run dev
```

测试场景：
1. 选 kimi (k2.6) 发消息 → 应看到流式输出
2. 选 deepseek (deepseek-chat) 发消息 → 如果模型支持思考过程，应看到思考面板
3. 选 doubao 发消息 → 应看到流式输出（无思考面板）
4. SSE 连接失败 → 应自动回退 REST 模式

- [ ] **Step 4: Final commit**

```bash
git add -A
git commit -m "feat: streaming output with thinking process display - complete"
```
