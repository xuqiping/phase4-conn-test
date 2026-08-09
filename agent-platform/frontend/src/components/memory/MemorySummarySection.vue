<!-- ============================================================
  总结页签（计划12 F-3b + 二期 P4 FR-301）— 列本人总结（按 scope）+ provenance + 状态徽标
  · 走 memoryApi.listSummaries（/memory/summaries?projectId=）
  · 项目 scope 下并列「项目共享总结」（memoryApi.listProjectSharedSummaries，全员可读）
  · status 徽标：CLEAN / PENDING_CONFLICT / STALE
  · 顶部「立即总结」入口开 MemoryConsolidationDialog
  ============================================================ -->
<template>
  <div class="memory-summary-section">
    <n-space :size="8" align="center" class="memory-summary-section__toolbar">
      <n-radio-group :value="scopeKey" size="small" @update:value="onScopeChange">
        <n-radio-button value="personal">个人</n-radio-button>
        <n-radio-button v-for="p in scopeItems" :key="p.id" :value="String(p.id)">
          {{ p.name }}<template v-if="p.granted">（授权）</template>
        </n-radio-button>
      </n-radio-group>
      <!-- Req1/第二轮#4：授权只读项目非 owner → 隐总结入口，仅查看与召回 -->
      <n-button v-if="!currentGranted" size="small" type="primary" ghost @click="instantShow = true">立即总结</n-button>
      <n-button v-if="!currentGranted" size="small" type="warning" ghost @click="resummarizeShow = true">重新总结</n-button>
      <n-button size="small" :loading="loading" @click="load">刷新</n-button>
      <span class="memory-summary-section__hint">{{ rows.length + sharedRows.length }} 条</span>
    </n-space>

    <!-- 二期 P4（FR-301）：项目 scope 下「项目共享总结」区（项目资产，全员可读） -->
    <template v-if="scope !== null">
      <div class="memory-summary-section__group">项目共享总结</div>
      <n-empty v-if="!loading && !sharedRows.length" size="small"
        description="暂无共享总结（项目 owner/admin 可在「立即总结」中压到共享）" />
      <n-card v-for="s in sharedRows" :key="'shared-' + s.id" size="small" :bordered="true" style="margin-bottom: 8px">
        <div class="memory-summary-section__head">
          <n-tag size="tiny" type="info" :bordered="false">共享</n-tag>
          <n-tag size="tiny" :bordered="false">{{ s.subject }} : {{ s.topic }}</n-tag>
          <n-tag v-if="s.direction === 'INPUT' || s.direction === 'OUTPUT'" size="tiny" :type="s.direction === 'INPUT' ? 'info' : 'success'" :bordered="false">{{ directionLabel(s.direction) }}</n-tag>
          <n-tag size="tiny" :type="statusType(s.status)" :bordered="false">{{ statusLabel(s.status) }}</n-tag>
          <span class="memory-summary-section__time">{{ s.summarizedAt || s.createdAt }}</span>
        </div>
        <div class="memory-summary-section__l1">{{ s.l1Summary }}</div>
        <div v-if="s.l2Detail" class="memory-summary-section__l2">{{ s.l2Detail }}</div>
        <div class="memory-summary-section__prov">
          来源 {{ (s.sourceEntryIds ?? []).length }} 条项目条目
        </div>
      </n-card>
      <div class="memory-summary-section__group">我的总结</div>
    </template>

    <n-empty v-if="!loading && !rows.length" size="small" description="暂无总结（周期总结 worker 自动生成，或点「立即总结」）" />

    <n-card v-for="s in rows" :key="s.id" size="small" :bordered="true" style="margin-bottom: 8px">
      <div class="memory-summary-section__head">
        <!-- 第二轮 #3：所属项目名（projectId 非空=该总结关联到某项目，直显归属） -->
        <n-tag v-if="s.projectName" size="tiny" type="info" :bordered="false">{{ s.projectName }}</n-tag>
        <n-tag size="tiny" :bordered="false">{{ s.subject }} : {{ s.topic }}</n-tag>
        <n-tag v-if="s.direction === 'INPUT' || s.direction === 'OUTPUT'" size="tiny" :type="s.direction === 'INPUT' ? 'info' : 'success'" :bordered="false">{{ directionLabel(s.direction) }}</n-tag>
        <n-tag size="tiny" :type="statusType(s.status)" :bordered="false">{{ statusLabel(s.status) }}</n-tag>
        <span class="memory-summary-section__time">{{ s.summarizedAt || s.createdAt }}</span>
      </div>
      <div class="memory-summary-section__l1">{{ s.l1Summary }}</div>
      <div v-if="s.l2Detail" class="memory-summary-section__l2">{{ s.l2Detail }}</div>
      <div class="memory-summary-section__prov">
        <template v-if="(s.sourceEntryIds ?? []).length">来源 {{ (s.sourceEntryIds ?? []).length }} 条项目条目</template>
        <template v-else>来源 {{ s.sourceTurnIds.length }} 条流水账</template>
        <span v-if="s.sourceSummaryId">· 链式压缩自 #{{ s.sourceSummaryId }}</span>
      </div>
    </n-card>

    <!-- Req2：立即总结（仅压新增，无筛选）/ 重新总结（强制重压 + 标签大类/时间/方向筛选）分两个入口 -->
    <MemoryConsolidationDialog v-model:show="instantShow" mode="instant" @done="load" />
    <MemoryConsolidationDialog v-model:show="resummarizeShow" mode="resummarize" @done="load" />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { NButton, NCard, NEmpty, NRadioButton, NRadioGroup, NSpace, NTag, useMessage } from 'naive-ui'
import { memoryApi, type MemoryProjectUserGrantVO, type MemorySummaryVO } from '@/api/memory'
import { projectApi } from '@/api/project'
import MemoryConsolidationDialog from './MemoryConsolidationDialog.vue'

const message = useMessage()

const rows = ref<MemorySummaryVO[]>([])
const sharedRows = ref<MemorySummaryVO[]>([])
const projects = ref<{ id: number; name: string }[]>([])
/** 第二轮 #4：我被 ACTIVE 授权只读召回的项目（scope radio 并入，标「授权」，隐总结按钮）。 */
const grants = ref<MemoryProjectUserGrantVO[]>([])
const scope = ref<number | null>(null)
const scopeKey = computed(() => (scope.value === null ? 'personal' : String(scope.value)))
const loading = ref(false)
const instantShow = ref(false)
const resummarizeShow = ref(false)

/** 第二轮 #4：ACTIVE 授权只读召回的项目 id 集（grantee=本人）。 */
const grantedProjectIds = computed(() => {
  const s = new Set<number>()
  for (const g of grants.value) {
    if (g.status === 'ACTIVE') s.add(g.projectId)
  }
  return s
})
/** scope radio 候选 = 我所在项目 ∪ ACTIVE 授权项目（去重，授权项标 granted）。 */
const scopeItems = computed(() => {
  const own = projects.value.map(p => ({ id: p.id, name: p.name, granted: false }))
  const ownIds = new Set(own.map(p => p.id))
  for (const g of grants.value) {
    if (g.status === 'ACTIVE' && !ownIds.has(g.projectId)) {
      own.push({ id: g.projectId, name: g.projectName ?? `项目#${g.projectId}`, granted: true })
    }
  }
  return own
})
/** 当前 scope 是否为授权只读项目（非 owner → 隐总结入口，呼应 Req1）。 */
const currentGranted = computed(() => scope.value !== null && grantedProjectIds.value.has(scope.value))

function onScopeChange(k: string | number | boolean) {
  scope.value = k === 'personal' ? null : Number(k)
  load()
}

async function load() {
  loading.value = true
  try {
    const res = await memoryApi.listSummaries(scope.value)
    rows.value = res.data?.data ?? []
    // 二期 P4：项目 scope 并列拉共享总结（非成员后端 403 → 视为无共享区）
    if (scope.value !== null) {
      try {
        const shared = await memoryApi.listProjectSharedSummaries(scope.value)
        sharedRows.value = shared.data?.data ?? []
      } catch {
        sharedRows.value = []
      }
    } else {
      sharedRows.value = []
    }
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

/** 第二轮 #4：拉我被授权的项目（scope radio 并入授权项目，切入只读显共享总结）。 */
async function loadGrants() {
  try {
    const res = await memoryApi.listMyUserGrants()
    grants.value = res.data?.data ?? []
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
/** P3c：总结方向徽标（INPUT=输入 / OUTPUT=输出；BOTH=综合不显示）。 */
function directionLabel(d?: string): string {
  if (d === 'INPUT') return '输入'
  if (d === 'OUTPUT') return '输出'
  return '综合'
}

onMounted(async () => {
  await loadProjects()
  await loadGrants()
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
  &__group {
    font-size: 12px;
    opacity: 0.7;
    margin: 10px 0 6px;
    font-weight: 600;
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
