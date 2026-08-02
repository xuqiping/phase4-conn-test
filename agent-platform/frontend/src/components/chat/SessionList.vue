<template>
  <div class="session-list">
    <div class="session-list__header">
      <!-- 多选态：操作栏；普通态：搜索框 + 多选入口 -->
      <template v-if="selectMode">
        <div class="session-list__select-bar">
          <span class="session-list__select-count">已选 {{ checked.length }}</span>
          <n-button
            size="tiny"
            type="error"
            :disabled="!checked.length"
            @click="onBatchDelete"
          >删除选中({{ checked.length }})</n-button>
          <n-button size="tiny" quaternary @click="exitSelect">退出</n-button>
        </div>
      </template>
      <template v-else>
        <n-input
          v-model:value="search"
          placeholder="搜索会话..."
          clearable
          size="small"
        >
          <template #prefix>
            <n-icon :component="SearchOutline" />
          </template>
        </n-input>
        <n-button
          v-if="sessions.length"
          class="session-list__select-btn"
          size="tiny"
          quaternary
          block
          @click="enterSelect"
        >多选</n-button>
      </template>
    </div>

    <div class="session-list__body">
      <template v-if="filteredSessions.length">
        <div
          v-for="session in filteredSessions"
          :key="session.id"
          class="session-list__item"
          :class="{
            'session-list__item--active': !selectMode && session.id === currentSessionId,
            'session-list__item--checked': selectMode && isChecked(session.id)
          }"
          @click="onItemClick(session.id)"
        >
          <n-checkbox
            v-if="selectMode"
            :checked="isChecked(session.id)"
            @click.stop
            @update:checked="toggle(session.id)"
          />
          <div class="session-list__item-main">
            <div class="session-list__item-title">
              {{ session.title || '新会话' }}
            </div>
            <div class="session-list__item-meta">
              <span v-if="session.mode === 'AGENT'" class="session-list__badge">{{ session.agentName }}</span>
              <span v-else-if="session.mode === 'WORKFLOW'" class="session-list__badge">{{ session.workflowName }}</span>
              <span class="session-list__time">{{ formatTime(session.updatedAt) }}</span>
            </div>
          </div>
        </div>
      </template>
      <n-empty v-else description="暂无会话" style="padding: 40px 0" />
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { NInput, NIcon, NEmpty, NCheckbox, NButton, useDialog } from 'naive-ui'
import { SearchOutline } from '@vicons/ionicons5'
import type { ChatSession } from '@/api/chat'

const props = defineProps<{
  sessions: ChatSession[]
  currentSessionId: number | null
}>()

const emit = defineEmits<{
  select: [sessionId: number]
  /** 批量删除：携带选中的会话 id 数组，由父组件调 store 执行。 */
  'batch-delete': [ids: number[]]
}>()

const dialog = useDialog()
const search = ref('')

// 多选模式（开关式）：进入后每项显 checkbox，点行切换选中，退出后清空。
const selectMode = ref(false)
const checked = ref<number[]>([])

const filteredSessions = computed(() => {
  if (!search.value) return props.sessions
  const kw = search.value.toLowerCase()
  return props.sessions.filter(s =>
    (s.title ?? '').toLowerCase().includes(kw) ||
    (s.agentName ?? '').toLowerCase().includes(kw)
  )
})

function isChecked(id: number): boolean {
  return checked.value.includes(id)
}

function toggle(id: number) {
  if (isChecked(id)) {
    checked.value = checked.value.filter(i => i !== id)
  } else {
    checked.value = [...checked.value, id]
  }
}

function enterSelect() {
  selectMode.value = true
  checked.value = []
}

function exitSelect() {
  selectMode.value = false
  checked.value = []
}

/** 多选态点行=切换选中；普通态点行=emit select。 */
function onItemClick(id: number) {
  if (selectMode.value) {
    toggle(id)
  } else {
    emit('select', id)
  }
}

function onBatchDelete() {
  const ids = [...checked.value]
  if (!ids.length) return
  dialog.warning({
    title: '批量删除会话',
    content: `删除选中的 ${ids.length} 个会话？不可恢复。`,
    positiveText: '删除',
    negativeText: '取消',
    onPositiveClick: () => {
      emit('batch-delete', ids)
      // 乐观退出多选：父组件异步执行删除，失败会刷新列表回滚显示
      exitSelect()
    }
  })
}

function formatTime(dateStr: string | null): string {
  if (!dateStr) return ''
  const d = new Date(dateStr)
  const now = new Date()
  const diffMs = now.getTime() - d.getTime()
  const diffDays = Math.floor(diffMs / 86400000)
  if (diffDays === 0) return d.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
  if (diffDays === 1) return '昨天'
  if (diffDays < 7) return `${diffDays}天前`
  return d.toLocaleDateString('zh-CN', { month: '2-digit', day: '2-digit' })
}
</script>

<style lang="scss" scoped>
.session-list {
  display: flex;
  flex-direction: column;
  height: 100%;
}

.session-list__header {
  padding: 12px;
  border-bottom: 1px solid var(--color-border-light);
}

.session-list__select-btn {
  margin-top: 8px;
}

.session-list__select-bar {
  display: flex;
  align-items: center;
  gap: 6px;
}

.session-list__select-count {
  font-size: 12px;
  color: var(--color-text-secondary);
  margin-right: auto;
}

.session-list__body {
  flex: 1;
  overflow-y: auto;
  padding: 8px;
}

.session-list__item {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 10px 12px;
  border-radius: var(--radius-base);
  cursor: pointer;
  margin-bottom: 2px;
  transition: background 0.15s;

  &:hover {
    background: var(--color-primary-light);
  }

  &--active {
    background: var(--color-primary-light);
    border-left: 3px solid var(--color-primary);
  }

  &--checked {
    background: var(--color-primary-light);
  }
}

.session-list__item-main {
  flex: 1;
  min-width: 0;
}

.session-list__item-title {
  font-size: 13px;
  color: var(--color-text-primary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.session-list__item-meta {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 4px;
}

.session-list__badge {
  font-size: 11px;
  color: var(--color-primary);
  background: var(--color-primary-light);
  padding: 1px 6px;
  border-radius: 3px;
}

.session-list__time {
  font-size: 11px;
  color: var(--color-text-tertiary);
}
</style>
