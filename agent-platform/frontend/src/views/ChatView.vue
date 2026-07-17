<template>
  <div class="chat-view" :class="{ 'chat-view--mobile': isMobile, 'chat-view--drawer-open': isMobile && sessionDrawerOpen }">
    <!-- Left: Session List（桌面：固定侧栏；移动：抽屉） -->
    <div class="chat-view__sidebar" :class="{ 'chat-view__sidebar--open': sessionDrawerOpen }">
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
        @batch-delete="handleBatchDeleteSessions"
      />
    </div>

    <!-- 移动端会话抽屉遮罩 -->
    <div
      v-if="isMobile && sessionDrawerOpen"
      class="chat-view__overlay"
      @click="sessionDrawerOpen = false"
    ></div>

    <!-- Right: Chat Area -->
    <div class="chat-view__main">
      <template v-if="chatStore.currentSession || isComposing || hasStarted">
        <!-- Header -->
        <div class="chat-view__header">
          <h3 class="chat-view__title">
            {{ chatStore.currentSession?.title || '新会话' }}
          </h3>
          <div class="chat-view__header-actions">
            <n-button v-if="isMobile" size="small" quaternary title="会话列表" @click="sessionDrawerOpen = true">
              <template #icon>
                <n-icon :component="MenuOutline" />
              </template>
            </n-button>
            <span v-if="chatStore.wsConnected" class="chat-view__ws-status chat-view__ws-status--on">WS</span>
            <span v-else class="chat-view__ws-status">REST</span>
            <n-button size="small" quaternary @click="handleDelete">
              <template #icon>
                <n-icon :component="TrashOutline" />
              </template>
            </n-button>
          </div>
        </div>

        <!-- 记忆状态条（低调：抽取进行中 / 冲突待处理，任一>0 才显）-->
        <div
          v-if="chatStore.memoryProcessing > 0 || chatStore.activeConflictCount > 0"
          class="chat-view__memory-status"
        >
          <span v-if="chatStore.memoryProcessing > 0" class="chat-view__memory-status-item chat-view__memory-status-item--processing">
            <n-spin size="small" />
            记忆记录中…
          </span>
          <span
            v-if="chatStore.activeConflictCount > 0"
            class="chat-view__memory-status-item chat-view__memory-status-item--conflict"
            @click="showMemory = true"
          >
            {{ chatStore.activeConflictCount }} 个记忆产生冲突，点击处理
          </span>
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
          <!-- HUMAN_INPUT select 型：内联选项按钮（点选=当答案发送，后端拦截恢复执行）-->
          <div v-if="pendingSelect" class="chat-view__input-options">
            <div class="chat-view__input-options-label">👆 请选择一项作答</div>
            <div class="chat-view__input-options-list">
              <n-button
                v-for="opt in pendingSelect.options"
                :key="String(opt)"
                :disabled="chatStore.sending"
                size="small"
                secondary
                class="chat-view__input-option"
                @click="handleSend(String(opt))"
              >{{ opt }}</n-button>
            </div>
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
            <div class="chat-view__rag-toggle" :title="ragPref === null
              ? `记忆模式：跟随全局（当前 ${globalRag ? '开' : '关'}），点击覆盖`
              : `记忆模式：本会话 ${ragPref ? '开' : '关'}（覆盖全局），点击「跟随」恢复继承`">
              <span class="chat-view__rag-label">记忆模式</span>
              <n-switch :value="ragEffective" :disabled="chatStore.sending" size="small" @update:value="onRagToggle" />
              <n-button
                v-if="ragPref !== null"
                size="tiny"
                quaternary
                :disabled="chatStore.sending"
                title="清除本会话覆盖，恢复跟随全局"
                @click="resetRagToGlobal"
              >跟随</n-button>
            </div>
            <div class="chat-view__mem-scope" title="项目记忆 scope：写目标（新事实落点）+ 读开关（总记忆/项目）">
              <span class="chat-view__rag-label">记忆范围</span>
              <ProjectSelector
                :model-value="chatStore.memProjectId"
                :disabled="chatStore.sending"
                @update:model-value="chatStore.memProjectId = $event"
              />
              <n-switch v-model:value="chatStore.memIncludeGlobal" :disabled="chatStore.sending" size="small" />
              <span class="chat-view__scope-label">总记忆</span>
              <n-select
                v-model:value="chatStore.memReadProjectIds"
                multiple
                :options="projectOptions"
                :disabled="chatStore.sending"
                size="small"
                placeholder="项目记忆"
                style="width: 160px"
                :consistent-menu-width="false"
              />
              <n-button size="small" quaternary title="管理项目（新建/删除/共享）" @click="showProjectManager = true">项目</n-button>
            </div>
            <n-badge :value="chatStore.activeConflictCount" :max="99" :show="chatStore.activeConflictCount > 0" type="error">
              <n-button size="small" quaternary @click="showMemory = true" title="查看/管理长期记忆与冲突">
                记忆
              </n-button>
            </n-badge>
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
    <n-drawer v-model:show="showMemory" :width="memoryDrawerWidth" placement="right">
      <n-drawer-content title="我的记忆" closable>
        <MemoryManagerPanel />
      </n-drawer-content>
    </n-drawer>

    <!-- 项目管理（V33）-->
    <ProjectManagerModal v-model:show="showProjectManager" @changed="onProjectsChanged" />
  </div>
</template>

<script setup lang="ts">
import { ref, nextTick, onMounted, onUnmounted, watch, computed } from 'vue'
import { useRoute } from 'vue-router'
import { NButton, NIcon, NSpin, NSwitch, NDrawer, NDrawerContent, NBadge, NSelect, useMessage } from 'naive-ui'
import {
  AddOutline,
  TrashOutline,
  ChatbubbleEllipsesOutline,
  SparklesOutline,
  MenuOutline
} from '@vicons/ionicons5'
import { useChatStore } from '@/stores/chat'
import { chatApi } from '@/api/chat'
import { getStorage, setStorage, removeStorage, STORAGE_KEYS } from '@/utils/storage'
import SessionList from '@/components/chat/SessionList.vue'
import MessageBubble from '@/components/chat/MessageBubble.vue'
import ChatInput from '@/components/chat/ChatInput.vue'
import ModelSelector from '@/components/chat/ModelSelector.vue'
import TargetSelector from '@/components/chat/TargetSelector.vue'
import ProjectSelector from '@/components/chat/ProjectSelector.vue'
import ProjectManagerModal from '@/components/chat/ProjectManagerModal.vue'
import MemoryManagerPanel from '@/components/chat/MemoryManagerPanel.vue'
import { projectApi } from '@/api/project'
import { useBreakpoints } from '@/composables/useBreakpoints'

const route = useRoute()
const chatStore = useChatStore()
const message = useMessage()
const messagesRef = ref<HTMLElement | null>(null)
const { isMobile } = useBreakpoints()

// 移动端会话抽屉开关
const sessionDrawerOpen = ref(false)
const memoryDrawerWidth = computed(() => (isMobile.value ? '100%' : 720))

// 记忆写入异常（后端不再静默吞）：轮询拿到即弹一次
watch(() => chatStore.memoryIncident, (msg) => {
  if (msg) {
    message.error(msg, { duration: 6000 })
    chatStore.memoryIncident = null
  }
})

const isComposing = ref(false)
/**
 * 记忆模式开关（三态，联动全局）：
 *   ragPref = null  → 继承全局（globalRag），随全局刷新变化；发请求时 ragEnabled 字段省略 → 后端 session.ragEnabled 不改 → RagModeResolver 继承 global。
 *   ragPref = true/false → 本会话覆盖（localStorage 持久化），随每条消息写入 session.rag_enabled。
 * 故"刷新重置为关闭"= 之前默认发 false（显式关）盖掉全局；现 null=继承修之。
 */
const ragPref = ref<boolean | null>(getStorage<boolean>(STORAGE_KEYS.CHAT_RAG_ENABLED))
const globalRag = ref(false)
const ragEffective = computed(() => ragPref.value ?? globalRag.value)
const showMemory = ref(false)

// 项目记忆 scope（V33）：读开关多选用的项目选项
const projectOptions = ref<Array<{ label: string; value: number }>>([])
async function loadProjectOptions() {
  try {
    const res = await projectApi.list()
    projectOptions.value = (res.data.data || []).map(p => ({ label: p.name, value: p.id }))
  } catch {
    projectOptions.value = []
  }
}

const showProjectManager = ref(false)
function onProjectsChanged() {
  void loadProjectOptions()
}

async function loadGlobalRag() {
  try {
    const res = await chatApi.getChatRagMode()
    globalRag.value = !!res.data.data.globalEnabled
  } catch {
    // 非 admin 或失败：保持 opt-in false，不阻塞聊天
  }
}

function onRagToggle(v: boolean) {
  ragPref.value = v
  setStorage(STORAGE_KEYS.CHAT_RAG_ENABLED, v)
}

function resetRagToGlobal() {
  ragPref.value = null
  removeStorage(STORAGE_KEYS.CHAT_RAG_ENABLED)
}

const hasStarted = computed(() => chatStore.messages.length > 0 || chatStore.sending || chatStore.streamingContent)

/**
 * HUMAN_INPUT select 型待答选项（INPUT_REQUIRED 帧捕获）。
 * SSE 帧字段嵌在 evt.data、WS 帧扁平，兼容两者。text/textarea 返回 null（走普通输入框）。
 * 点选 → handleSend(选项) 当答案发送，后端 interceptWorkflowInput 拦截恢复执行。
 */
const pendingSelect = computed<{ options: string[] } | null>(() => {
  const p = chatStore.pendingInput
  if (!p) return null
  const spec: any = (p && typeof p === 'object' && p.data) ? p.data : p
  if (!spec || spec.inputType !== 'select') return null
  const raw = Array.isArray(spec.options) ? spec.options : []
  const options = raw.map((o: any) => (o == null ? '' : String(o))).filter(Boolean)
  return options.length ? { options } : null
})

onMounted(async () => {
  await chatStore.fetchSessions()
  chatStore.connectWS()
  chatStore.startConflictPoll()
  void loadGlobalRag()
  void loadProjectOptions()
  const sessionId = route.params.sessionId
  if (sessionId) {
    await chatStore.selectSession(Number(sessionId))
  }
})

onUnmounted(() => {
  chatStore.disconnectWS()
  chatStore.stopConflictPoll()
})

// 记忆抽屉关闭后重拉，让角标与用户刚 resolve 的结果同步
watch(showMemory, (open) => {
  if (!open) chatStore.loadActiveConflicts()
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
  // 移动端选会话后收起抽屉
  if (isMobile.value) sessionDrawerOpen.value = false
}

function handleSend(message: string) {
  // ragPref=null → 省略 ragEnabled 字段，后端继承全局；非 null → 覆盖（写 session.rag_enabled）。
  chatStore.sendStreamingMessage(message, ragPref.value ?? undefined)
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

async function handleBatchDeleteSessions(ids: number[]) {
  if (!ids.length) return
  try {
    const deleted = await chatStore.batchDeleteSessions(ids)
    message.success(`已删除 ${deleted} 个会话`)
  } catch {
    message.error('批量删除失败，已刷新列表')
    void chatStore.fetchSessions()
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

.chat-view__mem-scope {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--color-text-secondary);
}

.chat-view__scope-label {
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

/* HUMAN_INPUT select 型内联选项按钮：低调（小字标签 + secondary 按钮），暗色主题适配 */
.chat-view__input-options {
  padding: 4px 20px 12px;
}
.chat-view__input-options-label {
  font-size: 12px;
  color: var(--color-text-tertiary);
  margin-bottom: 8px;
}
.chat-view__input-options-list {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.chat-view__input-option {
  cursor: pointer;
}

/* 记忆状态条：低调（小字 + 三级文字色 + 薄 padding，无强背景），满足"不是很明显"。
   processing=进行中（muted），conflict=冲突待处理（warn 色 + 可点开抽屉）。 */
.chat-view__memory-status {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 4px 20px;
  font-size: 12px;
  border-bottom: 1px solid var(--color-border-light);
  background: rgba(255, 255, 255, 0.02);
}
.chat-view__memory-status-item {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: var(--color-text-tertiary);
  &--conflict {
    color: var(--color-warning, #faad14);
    cursor: pointer;
    &:hover { opacity: 0.8; }
  }
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

// === 移动端：会话侧栏抽屉化 ===
.chat-view__overlay {
  position: fixed;
  inset: 0;
  background: var(--color-overlay);
  z-index: 40;
  backdrop-filter: blur(2px);
  animation: fade-in var(--duration-fast) var(--ease-out);
}

@media (max-width: 768px) {
  .chat-view__sidebar {
    position: fixed;
    left: 0;
    top: 0;
    bottom: 0;
    width: 80%;
    max-width: 300px;
    z-index: 50;
    transform: translateX(-100%);
    transition: transform var(--duration-normal) var(--ease-in-out);
    box-shadow: var(--shadow-lg);
  }

  .chat-view__sidebar--open {
    transform: translateX(0);
  }

  .chat-view__header {
    padding: 10px 12px;
  }

  .chat-view__memory-status {
    padding: 4px 12px;
    gap: 12px;
  }

  .chat-view__input-options {
    padding: 4px 12px 12px;
  }

  .chat-view__typing {
    padding: 12px;
  }

  .chat-view__streaming-thinking {
    margin: 12px;
    padding: 10px 12px;
  }
}
</style>
