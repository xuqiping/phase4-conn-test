<template>
  <div class="chat-view">
    <!-- Left: Session List -->
    <div class="chat-view__sidebar">
      <div class="chat-view__sidebar-header">
        <n-button type="primary" block @click="newSession">
          <template #icon>
            <n-icon :component="AddOutline" />
          </template>
          新建会话
        </n-button>
      </div>
      <SessionList
        :sessions="chatStore.sessions"
        :current-session-id="chatStore.currentSessionId"
        @select="handleSelectSession"
      />
    </div>

    <!-- Right: Chat Area -->
    <div class="chat-view__main">
      <template v-if="chatStore.currentSession || isComposing || hasStarted">
        <!-- Header -->
        <div class="chat-view__header">
          <h3 class="chat-view__title">
            {{ chatStore.currentSession?.title || '新会话' }}
          </h3>
          <div class="chat-view__header-actions">
            <span v-if="chatStore.wsConnected" class="chat-view__ws-status chat-view__ws-status--on">WS</span>
            <span v-else class="chat-view__ws-status">REST</span>
            <n-button size="small" quaternary @click="handleDelete">
              <template #icon>
                <n-icon :component="TrashOutline" />
              </template>
            </n-button>
          </div>
        </div>

        <!-- Messages -->
        <div ref="messagesRef" class="chat-view__messages">
          <MessageBubble
            v-for="msg in chatStore.messages"
            :key="msg.id"
            :message="msg"
          />
          <!-- Streaming thinking -->
          <div v-if="chatStore.streamingThinking" class="chat-view__streaming-thinking">
            <div class="chat-view__thinking-header">💭 思考中...</div>
            <div class="chat-view__thinking-text">{{ chatStore.streamingThinking }}</div>
          </div>
          <!-- Streaming message -->
          <div v-if="chatStore.streamingContent" class="chat-view__streaming">
            <div class="message-bubble message-bubble--assistant">
              <div class="message-bubble__avatar">
                <div class="message-bubble__avatar-icon message-bubble__avatar-icon--assistant">
                  <n-icon size="18" :component="SparklesOutline" />
                </div>
              </div>
              <div class="message-bubble__content">
                <div class="message-bubble__role">助手</div>
                <div class="message-bubble__text">
                  {{ chatStore.streamingContent }}<span class="chat-view__cursor" />
                </div>
              </div>
            </div>
          </div>
          <div v-if="chatStore.sending && !chatStore.streamingContent" class="chat-view__typing">
            <n-spin size="small" />
            <span>思考中...</span>
          </div>
        </div>

        <!-- Input -->
        <ChatInput
          :sending="chatStore.sending"
          @send="handleSend"
        >
          <template #tools>
            <TargetSelector
              :model-value="chatStore.visibleTargetValue"
              :disabled="chatStore.sending"
              @change="handleTargetChange"
            />
            <ModelSelector
              :model-value="chatStore.selectedModel"
              @change="handleModelChange"
            />
            <div class="chat-view__rag-toggle" title="开启后启用 RAG 证据 + 用户记忆（可被全局/Agent/工作流级覆盖）">
              <span class="chat-view__rag-label">记忆模式</span>
              <n-switch v-model:value="ragEnabled" :disabled="chatStore.sending" size="small" />
            </div>
            <n-button size="small" quaternary @click="showMemory = true" title="查看/管理长期记忆与冲突">
              记忆
            </n-button>
          </template>
        </ChatInput>
      </template>

      <!-- Empty State -->
      <div v-else class="chat-view__empty">
        <n-icon size="48" :component="ChatbubbleEllipsesOutline" color="var(--color-text-tertiary)" />
        <p>选择已有会话或创建新会话开始对话</p>
        <n-button type="primary" @click="newSession">开始对话</n-button>
      </div>
    </div>

    <!-- 记忆管理抽屉 -->
    <n-drawer v-model:show="showMemory" :width="720" placement="right">
      <n-drawer-content title="我的记忆" closable>
        <MemoryManagerPanel />
      </n-drawer-content>
    </n-drawer>
  </div>
</template>

<script setup lang="ts">
import { ref, nextTick, onMounted, onUnmounted, watch, computed } from 'vue'
import { useRoute } from 'vue-router'
import { NButton, NIcon, NSpin, NSwitch, NDrawer, NDrawerContent } from 'naive-ui'
import {
  AddOutline,
  TrashOutline,
  ChatbubbleEllipsesOutline,
  SparklesOutline
} from '@vicons/ionicons5'
import { useChatStore } from '@/stores/chat'
import SessionList from '@/components/chat/SessionList.vue'
import MessageBubble from '@/components/chat/MessageBubble.vue'
import ChatInput from '@/components/chat/ChatInput.vue'
import ModelSelector from '@/components/chat/ModelSelector.vue'
import TargetSelector from '@/components/chat/TargetSelector.vue'
import MemoryManagerPanel from '@/components/chat/MemoryManagerPanel.vue'

const route = useRoute()
const chatStore = useChatStore()
const messagesRef = ref<HTMLElement | null>(null)

const isComposing = ref(false)
/** 记忆模式会话级开关（V26，随每条消息持久化到 session.rag_enabled）。 */
const ragEnabled = ref(false)
const showMemory = ref(false)

const hasStarted = computed(() => chatStore.messages.length > 0 || chatStore.sending || chatStore.streamingContent)

onMounted(async () => {
  await chatStore.fetchSessions()
  chatStore.connectWS()
  const sessionId = route.params.sessionId
  if (sessionId) {
    await chatStore.selectSession(Number(sessionId))
  }
})

onUnmounted(() => {
  chatStore.disconnectWS()
})

watch(
  () => [chatStore.messages.length, chatStore.streamingContent],
  async () => {
    await nextTick()
    scrollToBottom()
  }
)

function scrollToBottom() {
  if (messagesRef.value) {
    messagesRef.value.scrollTop = messagesRef.value.scrollHeight
  }
}

function newSession() {
  chatStore.selectSession(null)
  isComposing.value = true
}

async function handleSelectSession(sessionId: number) {
  isComposing.value = false
  await chatStore.selectSession(sessionId)
}

function handleSend(message: string) {
  chatStore.sendStreamingMessage(message, ragEnabled.value)
}

function handleModelChange(model: string) {
  chatStore.setSelectedModel(model)
}

async function handleTargetChange(target: string) {
  await chatStore.updateCurrentSessionTarget(target)
}

async function handleDelete() {
  if (chatStore.currentSessionId) {
    await chatStore.deleteSession(chatStore.currentSessionId)
  }
}
</script>

<style lang="scss" scoped>
.chat-view {
  display: flex;
  height: 100%;
  background: var(--color-bg);
}

.chat-view__sidebar {
  width: 260px;
  border-right: 1px solid var(--color-border-light);
  display: flex;
  flex-direction: column;
  background: var(--color-surface);
}

.chat-view__sidebar-header {
  padding: 12px;
  border-bottom: 1px solid var(--color-border-light);
}

.chat-view__rag-toggle {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--color-text-secondary);
}

.chat-view__rag-label {
  white-space: nowrap;
}

.chat-view__main {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.chat-view__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 20px;
  border-bottom: 1px solid var(--color-border-light);
}

.chat-view__title {
  font-size: 15px;
  font-weight: 600;
  color: var(--color-text-primary);
  margin: 0;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.chat-view__header-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.chat-view__ws-status {
  font-size: 10px;
  padding: 2px 6px;
  border-radius: 4px;
  background: var(--color-border-light);
  color: var(--color-text-tertiary);

  &--on {
    background: rgba(82, 196, 26, 0.15);
    color: #52c41a;
  }
}

.chat-view__messages {
  flex: 1;
  overflow-y: auto;
}

.chat-view__streaming {
  padding: 0;
}

.chat-view__typing {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 12px 20px;
  font-size: 13px;
  color: var(--color-text-tertiary);
}

.chat-view__cursor {
  display: inline-block;
  width: 2px;
  height: 16px;
  background: var(--color-primary);
  margin-left: 2px;
  vertical-align: text-bottom;
  animation: blink 1s infinite;
}

.chat-view__empty {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 16px;
  color: var(--color-text-tertiary);
}

@keyframes blink {
  0%, 100% { opacity: 1; }
  50% { opacity: 0; }
}

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
</style>
