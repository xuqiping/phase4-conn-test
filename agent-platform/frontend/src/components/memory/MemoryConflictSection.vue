<!-- ============================================================
  冲突裁决区（计划12 F-2）— 列新栈待裁决冲突 + 挂四选项 Dialog
  · 走 memoryApi.listPendingConflicts（/memory/conflicts/pending）
  · 点「裁决」开 MemoryConflictJudgeDialog（KEEP_BOTH/NEW/OLD/DISCARD）
  ============================================================ -->
<template>
  <div class="memory-conflict-section">
    <n-space :size="8" align="center" class="memory-conflict-section__toolbar">
      <n-button size="small" :loading="loading" @click="load">刷新</n-button>
      <span class="memory-conflict-section__hint">
        {{ conflicts.length ? `待裁决 ${conflicts.length} 条` : '暂无待裁决冲突' }}
      </span>
    </n-space>

    <n-empty v-if="!loading && !conflicts.length" size="small" description="暂无待裁决冲突" />

    <n-space v-else vertical :size="8">
      <n-card
        v-for="c in conflicts"
        :key="c.conflictId"
        size="small"
        :bordered="true"
      >
        <div class="memory-conflict-section__row">
          <div class="memory-conflict-section__ask">{{ c.askText || '（无描述）' }}</div>
          <n-button size="small" type="primary" @click="open(c)">裁决</n-button>
        </div>
        <div class="memory-conflict-section__meta">
          <n-tag size="tiny" :bordered="false">{{ c.status }}</n-tag>
          <span v-if="c.createdAt">{{ c.createdAt }}</span>
        </div>
      </n-card>
    </n-space>

    <MemoryConflictJudgeDialog
      v-model:show="dialogShow"
      :conflict="current"
      @resolved="onResolved"
    />
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { NButton, NCard, NEmpty, NSpace, NTag, useMessage } from 'naive-ui'
import { memoryApi, type MemoryPendingConflictVO } from '@/api/memory'
import MemoryConflictJudgeDialog from './MemoryConflictJudgeDialog.vue'

const message = useMessage()

const conflicts = ref<MemoryPendingConflictVO[]>([])
const loading = ref(false)
const dialogShow = ref(false)
const current = ref<MemoryPendingConflictVO | null>(null)

async function load() {
  loading.value = true
  try {
    const res = await memoryApi.listPendingConflicts()
    conflicts.value = res.data?.data ?? []
  } catch (e: any) {
    message.error(e?.message || '加载冲突失败')
  } finally {
    loading.value = false
  }
}

function open(c: MemoryPendingConflictVO) {
  current.value = c
  dialogShow.value = true
}

function onResolved(conflictId: number) {
  conflicts.value = conflicts.value.filter(c => c.conflictId !== conflictId)
}

onMounted(load)
defineExpose({ refresh: load })
</script>

<style lang="scss" scoped>
.memory-conflict-section {
  &__toolbar {
    margin-bottom: 12px;
  }
  &__hint {
    font-size: 12px;
    opacity: 0.65;
  }
  &__row {
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    gap: 12px;
  }
  &__ask {
    font-size: 13px;
    line-height: 1.5;
    flex: 1;
  }
  &__meta {
    display: flex;
    align-items: center;
    gap: 8px;
    margin-top: 6px;
    font-size: 11px;
    opacity: 0.55;
  }
}
</style>
