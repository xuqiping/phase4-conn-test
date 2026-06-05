<template>
  <div class="session-list">
    <div class="session-list__header">
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
    </div>

    <div class="session-list__body">
      <template v-if="filteredSessions.length">
        <div
          v-for="session in filteredSessions"
          :key="session.id"
          class="session-list__item"
          :class="{ 'session-list__item--active': session.id === currentSessionId }"
          @click="$emit('select', session.id)"
        >
          <div class="session-list__item-title">
            {{ session.title || '新会话' }}
          </div>
          <div class="session-list__item-meta">
            <span v-if="session.mode === 'AGENT'" class="session-list__badge">{{ session.agentName }}</span>
            <span v-else-if="session.mode === 'WORKFLOW'" class="session-list__badge">{{ session.workflowName }}</span>
            <span class="session-list__time">{{ formatTime(session.updatedAt) }}</span>
          </div>
        </div>
      </template>
      <n-empty v-else description="暂无会话" style="padding: 40px 0" />
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { NInput, NIcon, NEmpty } from 'naive-ui'
import { SearchOutline } from '@vicons/ionicons5'
import type { ChatSession } from '@/api/chat'

const props = defineProps<{
  sessions: ChatSession[]
  currentSessionId: number | null
}>()

defineEmits<{
  select: [sessionId: number]
}>()

const search = ref('')

const filteredSessions = computed(() => {
  if (!search.value) return props.sessions
  const kw = search.value.toLowerCase()
  return props.sessions.filter(s =>
    (s.title ?? '').toLowerCase().includes(kw) ||
    (s.agentName ?? '').toLowerCase().includes(kw)
  )
})

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

.session-list__body {
  flex: 1;
  overflow-y: auto;
  padding: 8px;
}

.session-list__item {
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
