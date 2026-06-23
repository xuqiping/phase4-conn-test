<!-- ============================================================
  记忆管理面板 — 用户长期记忆 + 冲突解决（自服务，current userId 隔离）
  两区：我的记忆（查/删/清空）+ 记忆冲突（FLAGGED 分组，KEEP_NEW/OLD/BOTH/DISCARD）
  ============================================================ -->
<template>
  <div class="memory-manager">
    <!-- 记忆冲突区（有冲突才显）-->
    <n-card v-if="conflicts.length" size="small" class="memory-manager__card" title="待解决的记忆冲突">
      <div v-for="c in conflicts" :key="c.conflictId" class="memory-manager__conflict">
        <div class="memory-manager__conflict-head">
          <n-tag size="small" type="warning" bordered>{{ c.block || '同组' }}</n-tag>
          <span class="memory-manager__conflict-time">{{ formatTime(c.createdAt) }}</span>
        </div>
        <div v-if="c.askText" class="memory-manager__conflict-ask">{{ c.askText }}</div>
        <div class="memory-manager__candidates">
          <div v-for="(cand, idx) in c.candidates" :key="idx" class="memory-manager__candidate">
            <n-tag size="tiny" :bordered="false">{{ cand.category || '-' }}</n-tag>
            <span class="memory-manager__candidate-key">{{ cand.memoryKey }}</span>
            <span class="memory-manager__candidate-val">{{ cand.memoryValue }}</span>
          </div>
        </div>
        <n-space size="small">
          <n-button size="small" type="primary" :loading="resolving === c.conflictId" @click="resolve(c, 'KEEP_NEW')">保留新</n-button>
          <n-button size="small" :loading="resolving === c.conflictId" @click="resolve(c, 'KEEP_OLD')">保留旧</n-button>
          <n-button size="small" :loading="resolving === c.conflictId" @click="resolve(c, 'KEEP_BOTH')">都保留</n-button>
          <n-button size="small" quaternary type="error" :loading="resolving === c.conflictId" @click="resolve(c, 'DISCARD')">全删</n-button>
        </n-space>
      </div>
    </n-card>

    <!-- 我记忆区 -->
    <n-card size="small" class="memory-manager__card">
      <template #header>
        <span>我的记忆</span>
        <n-tag size="small" round :bordered="false">{{ memories.length }} 条</n-tag>
      </template>
      <template #header-extra>
        <n-button size="small" quaternary type="error" :disabled="!memories.length" @click="confirmClear">清空全部</n-button>
      </template>
      <n-data-table
        v-if="memories.length"
        :columns="columns"
        :data="memories"
        :pagination="{ pageSize: 10 }"
        size="small"
        striped
      />
      <n-empty v-else description="暂无记忆。开启记忆模式对话后，AI 会自动抽取长期记忆。" />
    </n-card>
  </div>
</template>

<script setup lang="ts">
import { h, onMounted, ref } from 'vue'
import {
  NButton, NCard, NDataTable, NEmpty, NSpace, NTag, useDialog, useMessage
} from 'naive-ui'
import type { DataTableColumns } from 'naive-ui'
import { chatApi, type UserMemory, type MemoryConflict } from '@/api/chat'

const message = useMessage()
const dialog = useDialog()

const memories = ref<UserMemory[]>([])
const conflicts = ref<MemoryConflict[]>([])
const loading = ref(false)
const resolving = ref<number | null>(null)

const categoryType: Record<string, 'success' | 'info' | 'warning'> = {
  PREFERENCE: 'success', FACT: 'info', FEEDBACK: 'warning'
}

const columns: DataTableColumns<UserMemory> = [
  { title: '分类', key: 'category', width: 100, render: r => h(NTag, { size: 'small', type: categoryType[r.category || ''] || 'default', bordered: false }, () => r.category || '-') },
  { title: '键', key: 'memoryKey', width: 160, ellipsis: { tooltip: true }, render: r => r.memoryKey || '-' },
  { title: '值', key: 'memoryValue', ellipsis: { tooltip: true }, render: r => r.memoryValue || '-' },
  { title: '置信度', key: 'confidence', width: 90, render: r => r.confidence != null ? Number(r.confidence).toFixed(2) : '-' },
  { title: '来源', key: 'source', width: 90, render: r => r.source || '-' },
  {
    title: '冲突', key: 'conflictStatus', width: 110,
    render: r => r.conflictStatus === 'FLAGGED'
      ? h(NTag, { size: 'small', type: 'warning', bordered: false }, () => `⚠ ${r.conflictWith || '冲突'}`)
      : '-'
  },
  { title: '更新', key: 'updatedAt', width: 150, render: r => formatTime(r.updatedAt) },
  {
    title: '操作', key: 'actions', width: 80, fixed: 'right',
    render: r => h(NButton, { size: 'small', quaternary: true, type: 'error', onClick: () => confirmDelete(r) }, () => '删除')
  }
]

function formatTime(iso: string): string {
  try { return new Date(iso).toLocaleString('zh-CN') } catch { return iso }
}

async function loadMemories() {
  loading.value = true
  try {
    const res = await chatApi.listMemories()
    memories.value = res.data.data
  } catch { message.error('加载记忆失败') }
  finally { loading.value = false }
}

async function loadConflicts() {
  try {
    const res = await chatApi.listMemoryConflicts()
    conflicts.value = res.data.data
  } catch { message.error('加载冲突失败') }
}

function confirmDelete(m: UserMemory) {
  dialog.warning({
    title: '删除记忆', content: `删除「${m.memoryKey}」？`, positiveText: '删除', negativeText: '取消',
    onPositiveClick: async () => {
      try { await chatApi.deleteMemory(m.id); message.success('已删除'); await loadMemories() }
      catch { message.error('删除失败') }
    }
  })
}

function confirmClear() {
  dialog.error({
    title: '清空全部记忆',
    content: '将删除当前用户全部长期记忆，不可恢复。确认？',
    positiveText: '清空', negativeText: '取消',
    onPositiveClick: async () => {
      try { const res = await chatApi.clearMemories(); message.success(`已清空 ${res.data.data} 条`); await loadMemories() }
      catch { message.error('清空失败') }
    }
  })
}

async function resolve(c: MemoryConflict, decision: string) {
  resolving.value = c.conflictId
  try {
    await chatApi.resolveMemoryConflict(c.conflictId, decision)
    message.success('已解决冲突')
    await Promise.all([loadConflicts(), loadMemories()])
  } catch { message.error('解决失败') }
  finally { resolving.value = null }
}

onMounted(() => { void loadMemories(); void loadConflicts() })
</script>

<style lang="scss" scoped>
.memory-manager {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-3);
}
.memory-manager__card {
  :deep(.n-card-header) { padding: 12px 16px; }
  :deep(.n-card-header__main) { display: flex; align-items: center; gap: 8px; }
}
.memory-manager__conflict {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-2);
  padding: var(--spacing-2) 0;
  border-bottom: 1px solid var(--color-border, rgba(255, 255, 255, 0.08));
  &:last-child { border-bottom: none; }
}
.memory-manager__conflict-head {
  display: flex; align-items: center; gap: var(--spacing-2);
}
.memory-manager__conflict-time { font-size: 12px; color: var(--color-text-tertiary, #888); }
.memory-manager__conflict-ask {
  font-size: 13px; color: var(--color-text-secondary, #ccc);
  padding: var(--spacing-1) var(--spacing-2);
  background: var(--color-bg-secondary, rgba(255, 255, 255, 0.04));
  border-radius: 6px;
}
.memory-manager__candidates {
  display: flex; flex-direction: column; gap: 4px;
}
.memory-manager__candidate {
  display: flex; align-items: center; gap: 6px; font-size: 13px;
}
.memory-manager__candidate-key { color: var(--color-text-secondary, #aaa); min-width: 100px; }
.memory-manager__candidate-val { color: var(--color-text-primary, #eee); word-break: break-all; }
</style>
