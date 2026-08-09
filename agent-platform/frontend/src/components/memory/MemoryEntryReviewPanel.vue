<!-- ============================================================
  收录条目审核面板（记忆二期 P1 · FR-005）— 项目条目列表 + 收/弃 + 撤回
  · 项目列表复用 memoryApi.getGenMatrix（带 role）
  · owner/admin：全量条目 + PENDING_REVIEW 可「收录/弃」（弃→负例反哺规则）
  · 成员：仅见自己产生的条目；作者可「撤回」自己的条目
  · 条目为脱敏蒸馏产物（L1/L2 摘要），不含对话原文
  ============================================================ -->
<template>
  <div class="memory-entry-review">
    <n-alert type="info" :bordered="false" size="small" class="memory-entry-review__top">
      路由自动收录的项目条目：置信度 ≥0.8 直接生效，0.5~0.8 待 owner/admin 审核。
      「弃」会把该摘要追加为规则负例（最多保留 5 条），同类对话以后不再误收。
    </n-alert>

    <n-space :size="8" align="center" class="memory-entry-review__toolbar">
      <n-select
        v-model:value="currentProjectId"
        :options="projectOptions"
        :loading="projectsLoading"
        size="small"
        placeholder="选择项目"
        class="memory-entry-review__project-select"
        @update:value="loadEntries"
      />
      <n-radio-group v-model:value="statusFilter" size="small" @update:value="loadEntries">
        <n-radio-button value="PENDING_REVIEW">待审核</n-radio-button>
        <n-radio-button value="ACTIVE">已生效</n-radio-button>
        <n-radio-button value="">全部</n-radio-button>
      </n-radio-group>
      <n-button size="small" :loading="entriesLoading" @click="loadEntries">刷新</n-button>
      <span class="memory-entry-review__hint">{{ entries.length }} 条</span>
    </n-space>

    <n-empty
      v-if="!entriesLoading && !entries.length"
      size="small"
      :description="currentProjectId ? '暂无条目' : '暂无项目'"
    />

    <n-space v-else vertical :size="8">
      <n-card v-for="e in entries" :key="e.id" size="small" :bordered="true">
        <div class="memory-entry-review__row">
          <div class="memory-entry-review__main">
            <div class="memory-entry-review__l1">{{ e.l1Summary || '（无摘要）' }}</div>
            <div v-if="e.l2Detail" class="memory-entry-review__l2">{{ e.l2Detail }}</div>
          </div>
          <div v-if="showActions(e)" class="memory-entry-review__actions">
            <template v-if="e.status === 'PENDING_REVIEW' && isManager">
              <n-button
                size="small"
                type="primary"
                :loading="busyId === e.id"
                @click="review(e, 'approve')"
              >
                收录
              </n-button>
              <n-button
                size="small"
                type="error"
                ghost
                :loading="busyId === e.id"
                @click="confirmReject(e)"
              >
                弃
              </n-button>
            </template>
            <n-button
              v-if="e.authorUserId === myUserId"
              size="small"
              ghost
              :loading="busyId === e.id"
              @click="confirmWithdraw(e)"
            >
              撤回
            </n-button>
          </div>
        </div>
        <div class="memory-entry-review__meta">
          <n-tag size="tiny" :type="e.status === 'ACTIVE' ? 'success' : 'warning'" :bordered="false">
            {{ e.status === 'ACTIVE' ? '已生效' : '待审核' }}
          </n-tag>
          <span v-if="e.authorName">{{ e.authorName }}</span>
          <span v-if="e.confidence != null">置信度 {{ e.confidence.toFixed(2) }}</span>
          <span v-if="e.ruleText" class="memory-entry-review__rule">命中规则：{{ e.ruleText }}</span>
          <span v-if="e.createdAt">{{ e.createdAt }}</span>
        </div>
      </n-card>
    </n-space>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import {
  NAlert, NButton, NCard, NEmpty, NRadioButton, NRadioGroup,
  NSelect, NSpace, NTag, useDialog, useMessage
} from 'naive-ui'
import {
  memoryApi,
  type MemoryGenMatrixItemVO,
  type MemoryProjectEntryVO
} from '@/api/memory'
import { useAuthStore } from '@/stores/auth'

const message = useMessage()
const dialog = useDialog()
const authStore = useAuthStore()

const projects = ref<MemoryGenMatrixItemVO[]>([])
const projectsLoading = ref(false)
const currentProjectId = ref<number | null>(null)
const entries = ref<MemoryProjectEntryVO[]>([])
const entriesLoading = ref(false)
const busyId = ref<number | null>(null)
const statusFilter = ref<string>('PENDING_REVIEW')

const myUserId = computed(() => authStore.userInfo?.id ?? null)

const projectOptions = computed(() =>
  projects.value.map(p => ({ label: `${p.projectName}（${p.role}）`, value: p.projectId }))
)

/** 当前项目角色是否 owner/admin（可审核全量条目）。 */
const isManager = computed(() => {
  const p = projects.value.find(x => x.projectId === currentProjectId.value)
  return p?.role === 'OWNER' || p?.role === 'ADMIN'
})

function showActions(e: MemoryProjectEntryVO) {
  return (e.status === 'PENDING_REVIEW' && isManager.value) || e.authorUserId === myUserId.value
}

async function loadProjects() {
  projectsLoading.value = true
  try {
    const res = await memoryApi.getGenMatrix()
    projects.value = res.data?.data ?? []
    if (!currentProjectId.value && projects.value.length) {
      const first = projects.value.find(p => p.role === 'OWNER' || p.role === 'ADMIN') ?? projects.value[0]
      currentProjectId.value = first.projectId
      await loadEntries()
    }
  } catch (e: any) {
    message.error(e?.message || '加载项目失败')
  } finally {
    projectsLoading.value = false
  }
}

async function loadEntries() {
  if (currentProjectId.value == null) {
    entries.value = []
    return
  }
  entriesLoading.value = true
  try {
    const res = await memoryApi.listEntries(currentProjectId.value, statusFilter.value || undefined)
    entries.value = res.data?.data ?? []
  } catch (e: any) {
    message.error(e?.message || '加载条目失败')
  } finally {
    entriesLoading.value = false
  }
}

async function review(e: MemoryProjectEntryVO, action: 'approve' | 'reject') {
  busyId.value = e.id
  try {
    await memoryApi.reviewEntry(e.id, action)
    if (statusFilter.value === 'PENDING_REVIEW') {
      entries.value = entries.value.filter(x => x.id !== e.id)
    } else if (action === 'approve') {
      e.status = 'ACTIVE'
    } else {
      entries.value = entries.value.filter(x => x.id !== e.id)
    }
    message.success(action === 'approve' ? '已收录' : '已弃（摘要已反哺为规则负例）')
  } catch (err: any) {
    message.error(err?.message || '操作失败')
  } finally {
    busyId.value = null
  }
}

function confirmReject(e: MemoryProjectEntryVO) {
  dialog.warning({
    title: '弃用该条目？',
    content: '条目将被移除，且其摘要会追加为规则负例（同类对话以后不再误收）。',
    positiveText: '弃',
    negativeText: '取消',
    onPositiveClick: () => review(e, 'reject')
  })
}

function confirmWithdraw(e: MemoryProjectEntryVO) {
  dialog.warning({
    title: '撤回该条目？',
    content: '撤回后项目成员不再能召回到这条记忆。',
    positiveText: '撤回',
    negativeText: '取消',
    onPositiveClick: async () => {
      busyId.value = e.id
      try {
        await memoryApi.withdrawEntry(e.id)
        entries.value = entries.value.filter(x => x.id !== e.id)
        message.success('已撤回')
      } catch (err: any) {
        message.error(err?.message || '撤回失败')
      } finally {
        busyId.value = null
      }
    }
  })
}

onMounted(loadProjects)
defineExpose({ refresh: loadProjects })
</script>

<style lang="scss" scoped>
.memory-entry-review {
  &__top {
    margin-bottom: 12px;
  }
  &__toolbar {
    margin-bottom: 12px;
    flex-wrap: wrap;
  }
  &__project-select {
    width: 220px;
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
  &__main {
    flex: 1;
    min-width: 0;
  }
  &__l1 {
    font-size: 13px;
    line-height: 1.5;
  }
  &__l2 {
    font-size: 12px;
    opacity: 0.7;
    margin-top: 4px;
    line-height: 1.5;
    white-space: pre-wrap;
  }
  &__actions {
    display: flex;
    gap: 6px;
    flex-shrink: 0;
  }
  &__meta {
    display: flex;
    align-items: center;
    gap: 8px;
    margin-top: 6px;
    font-size: 11px;
    opacity: 0.55;
    flex-wrap: wrap;
  }
  &__rule {
    // 命中规则文案较长时允许截断换行
    word-break: break-all;
  }
}
</style>
