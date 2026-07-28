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
              ? `记忆模式：跟随全局（当前 ${globalRag ? '开' : '关'}）`
              : `记忆模式：本会话 ${ragPref ? '开' : '关'}（覆盖全局）`">
              <span class="chat-view__rag-label">记忆模式</span>
              <n-select
                :value="ragModeValue"
                :options="ragModeOptions"
                :disabled="chatStore.sending"
                size="small"
                style="width: 140px"
                :consistent-menu-width="false"
                @update:value="onSelectRagMode"
              />
            </div>
            <!-- 联网搜索开关（CHAT 模式）：开 → LLM 生成前联网检索注入 + web CITATION 外链回显 -->
            <div class="chat-view__rag-toggle" :title="webSearchPref
              ? '联网搜索：开（生成前联网检索，结果作参考引用）'
              : '联网搜索：关（纯模型作答）'">
              <n-select
                :value="webSearchPref ? 'on' : 'off'"
                :options="webSearchOptions"
                :disabled="chatStore.sending"
                size="small"
                style="width: 120px"
                :consistent-menu-width="false"
                @update:value="onSelectWebSearch"
              />
            </div>
            <!-- M4:写目标 vs 读范围显式分组,避免语义混淆 -->
            <div
              class="chat-view__mem-scope chat-view__mem-scope--write"
              title="写目标：新抽取的事实落入库的位置（选「总记忆」= 不挂任何项目；选某项目 = 归属该项目 home）"
            >
              <span class="chat-view__rag-label chat-view__rag-label--group">记忆落库于</span>
              <ProjectSelector
                :model-value="chatStore.memProjectId"
                :disabled="chatStore.sending"
                @update:model-value="chatStore.memProjectId = $event"
              />
            </div>
            <div class="chat-view__mem-divider" aria-hidden="true" />
            <div
              class="chat-view__mem-scope chat-view__mem-scope--read"
              title="读范围：召回注入 LLM 时读取哪些记忆（与写目标互相独立）。总记忆开关 + 项目多选均为「读」"
            >
              <span class="chat-view__rag-label chat-view__rag-label--group">读取记忆范围</span>
              <n-select
                :value="chatStore.memIncludeGlobal ? 'on' : 'off'"
                :options="globalToggleOptions"
                :disabled="chatStore.sending"
                size="small"
                style="width: 96px"
                :consistent-menu-width="false"
                @update:value="(v: string) => (chatStore.memIncludeGlobal = v === 'on')"
              />
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
              <n-button size="small" quaternary circle title="管理项目（新建/删除/共享）" @click="showProjectManager = true">
                <template #icon><n-icon :component="FolderOpenOutline" /></template>
              </n-button>
              <!-- F-6 新栈召回 scope（个人/项目/方向/时间窗/离职），双栈期与上 legacy 读控件并存到 H 收尾 -->
              <MemoryRecallScopePopover />
            </div>
            <n-badge :value="chatStore.activeConflictCount" :max="99" :show="chatStore.activeConflictCount > 0" type="error">
              <n-button size="small" quaternary circle @click="showMemory = true" title="查看/管理长期记忆与冲突">
                <template #icon><n-icon :component="BookmarksOutline" /></template>
              </n-button>
            </n-badge>
            <MemoryNotificationBadge />
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
import { NButton, NIcon, NSpin, NDrawer, NDrawerContent, NBadge, NSelect, useMessage } from 'naive-ui'
import {
  AddOutline,
  TrashOutline,
  ChatbubbleEllipsesOutline,
  SparklesOutline,
  MenuOutline,
  FolderOpenOutline,
  BookmarksOutline
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
import MemoryNotificationBadge from '@/components/memory/MemoryNotificationBadge.vue'
import MemoryRecallScopePopover from '@/components/memory/MemoryRecallScopePopover.vue'
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
const showMemory = ref(false)

/**
 * 联网搜索开关（CHAT 模式会话级，localStorage 持久化）：
 *   默认关（null/false）；ON → 后端 LLM 生成前联网检索注入 + 发 web CITATION。
 *   非 CHAT 模式由后端 ignore（前端仍展示，仅 CHAT 生效；Agent/Workflow 留扩展点）。
 */
const webSearchPref = ref<boolean>(!!getStorage<boolean>(STORAGE_KEYS.CHAT_WEB_SEARCH_ENABLED))

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

// 记忆模式 3 态下拉(迭代2):跟随全局 / 本会话开 / 本会话关
const ragModeOptions = [
  { label: '跟随全局', value: 'inherit' },
  { label: '本会话：开', value: 'on' },
  { label: '本会话：关', value: 'off' }
]
const ragModeValue = computed<'inherit' | 'on' | 'off'>(() => {
  if (ragPref.value === null) return 'inherit'
  return ragPref.value ? 'on' : 'off'
})
function onSelectRagMode(v: 'inherit' | 'on' | 'off') {
  if (v === 'inherit') resetRagToGlobal()
  else onRagToggle(v === 'on')
}

// 联网搜索开/关下拉
const webSearchOptions = [
  { label: '🌐 联网：关', value: 'off' },
  { label: '🌐 联网：开', value: 'on' }
]
function onSelectWebSearch(v: 'on' | 'off') {
  const on = v === 'on'
  webSearchPref.value = on
  setStorage(STORAGE_KEYS.CHAT_WEB_SEARCH_ENABLED, on)
}

// 总记忆开/关下拉(迭代2)
const globalToggleOptions = [
  { label: '总记忆：开', value: 'on' },
  { label: '总记忆：关', value: 'off' }
]

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
  // webSearchPref：显式传 true/false（null=false 默认关），写 session.web_search_enabled。
  chatStore.sendStreamingMessage(message, ragPref.value ?? undefined, webSearchPref.value)
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

/* M4:写/读分组视觉区分 */
.chat-view__rag-label--group {
  font-weight: 600;
  color: var(--color-text-primary);
}
.chat-view__mem-scope--write {
  padding: 2px 6px;
  border-radius: 4px;
  background: color-mix(in srgb, var(--color-primary) 8%, transparent);
}
.chat-view__mem-scope--read {
  padding: 2px 6px;
  border-radius: 4px;
  background: color-mix(in srgb, var(--color-success, #18a058) 8%, transparent);
}
.chat-view__mem-divider {
  width: 1px;
  align-self: stretch;
  margin: 2px 2px;
  background: var(--color-border, rgba(255, 255, 255, 0.12));
  opacity: 0.6;
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
