<!-- ============================================================
  总结页签（计划12 F-3b）— 列本人总结（按 scope）+ provenance + 状态徽标
  · 走 memoryApi.listSummaries（/memory/summaries?projectId=）
  · status 徽标：CLEAN / PENDING_CONFLICT / STALE；恒只读自己
  · 顶部「立即总结」入口开 MemoryConsolidationDialog
  ============================================================ -->
<template>
  <div class="memory-summary-section">
    <n-space :size="8" align="center" class="memory-summary-section__toolbar">
      <n-radio-group :value="scopeKey" size="small" @update:value="onScopeChange">
        <n-radio-button value="personal">个人</n-radio-button>
        <n-radio-button v-for="p in projects" :key="p.id" :value="String(p.id)">{{ p.name }}</n-radio-button>
      </n-radio-group>
      <n-button size="small" type="primary" ghost @click="consolidationShow = true">立即总结</n-button>
      <n-button size="small" :loading="loading" @click="load">刷新</n-button>
      <span class="memory-summary-section__hint">{{ rows.length }} 条</span>
    </n-space>

    <n-empty v-if="!loading && !rows.length" size="small" description="暂无总结（周期总结 worker 自动生成，或点「立即总结」）" />

    <n-card v-for="s in rows" :key="s.id" size="small" :bordered="true" style="margin-bottom: 8px">
      <div class="memory-summary-section__head">
        <n-tag size="tiny" :bordered="false">{{ s.subject }} : {{ s.topic }}</n-tag>
        <n-tag size="tiny" :type="statusType(s.status)" :bordered="false">{{ statusLabel(s.status) }}</n-tag>
        <span class="memory-summary-section__time">{{ s.summarizedAt || s.createdAt }}</span>
      </div>
      <div class="memory-summary-section__l1">{{ s.l1Summary }}</div>
      <div v-if="s.l2Detail" class="memory-summary-section__l2">{{ s.l2Detail }}</div>
      <div class="memory-summary-section__prov">
        来源 {{ s.sourceTurnIds.length }} 条流水账
        <span v-if="s.sourceSummaryId">· 链式压缩自 #{{ s.sourceSummaryId }}</span>
      </div>
    </n-card>

    <MemoryConsolidationDialog v-model:show="consolidationShow" @done="load" />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { NButton, NCard, NEmpty, NRadioButton, NRadioGroup, NSpace, NTag, useMessage } from 'naive-ui'
import { memoryApi, type MemorySummaryVO } from '@/api/memory'
import { projectApi } from '@/api/project'
import MemoryConsolidationDialog from './MemoryConsolidationDialog.vue'

const message = useMessage()

const rows = ref<MemorySummaryVO[]>([])
const projects = ref<{ id: number; name: string }[]>([])
const scope = ref<number | null>(null)
const scopeKey = computed(() => (scope.value === null ? 'personal' : String(scope.value)))
const loading = ref(false)
const consolidationShow = ref(false)

function onScopeChange(k: string | number | boolean) {
  scope.value = k === 'personal' ? null : Number(k)
  load()
}

async function load() {
  loading.value = true
  try {
    const res = await memoryApi.listSummaries(scope.value)
    rows.value = res.data?.data ?? []
  } catch (e: any) {
    message.error(e?.message || '加载总结失败')
  } finally {
    loading.value = false
  }
}

async function loadProjects() {
  try {
    const res = await projectApi.list()
    projects.value = (res.data?.data ?? []).map((p: any) => ({ id: p.id, name: p.name }))
  } catch { /* ignore */ }
}

function statusType(s: string): 'success' | 'warning' | 'error' {
  if (s === 'CLEAN') return 'success'
  if (s === 'PENDING_CONFLICT') return 'error'
  return 'warning'
}
function statusLabel(s: string): string {
  if (s === 'CLEAN') return '干净'
  if (s === 'PENDING_CONFLICT') return '冲突待裁'
  return '待重生'
}

onMounted(async () => {
  await loadProjects()
  await load()
})
defineExpose({ refresh: load })
</script>

<style lang="scss" scoped>
.memory-summary-section {
  &__toolbar {
    margin-bottom: 12px;
    flex-wrap: wrap;
  }
  &__hint {
    font-size: 12px;
    opacity: 0.65;
  }
  &__head {
    display: flex;
    align-items: center;
    gap: 6px;
    margin-bottom: 4px;
    flex-wrap: wrap;
  }
  &__time {
    font-size: 11px;
    opacity: 0.5;
    margin-left: auto;
  }
  &__l1 {
    font-size: 13px;
    line-height: 1.5;
  }
  &__l2 {
    font-size: 12px;
    opacity: 0.7;
    line-height: 1.5;
    margin-top: 2px;
  }
  &__prov {
    font-size: 11px;
    opacity: 0.55;
    margin-top: 6px;
  }
}
</style>
