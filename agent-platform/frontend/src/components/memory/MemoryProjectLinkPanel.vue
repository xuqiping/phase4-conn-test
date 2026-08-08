<!-- ============================================================
  项目授权面板（记忆二期 P2 · FR-101/102）— 授权链管理
  · 「我授权出去的」：我 owner 的项目作为 child 发出的链（发起/取消/撤销）
  · 「待我审批的」：我 owner/admin 的项目作为 parent 收到的链（通过/拒绝/撤销）
  · 语义：child 条目授权给 parent 成员召回（单级不传递）；REJECTED 30 天防刷；
    双方均可撤销 ACTIVE；授权即时影响召回（无缓存）
  ============================================================ -->
<template>
  <div class="memory-link-panel">
    <n-alert type="info" :bordered="false" size="small" class="memory-link-panel__top">
      项目授权：把我项目的记忆条目授权给另一个项目的成员召回（对方成员只读摘要，原文不出个人域）。
      双向确认——你发起后对方 owner/admin 审批通过才生效；任一方可随时撤销，撤销即时生效。
    </n-alert>

    <!-- ============ 我授权出去的（child 侧） ============ -->
    <n-card size="small" :bordered="true" class="memory-link-panel__block">
      <template #header>我授权出去的</template>
      <n-space :size="8" align="center" class="memory-link-panel__grant" wrap>
        <n-select
          v-model:value="grantChildId"
          :options="ownedProjectOptions"
          size="small"
          placeholder="授权方（我 owner 的项目）"
          class="memory-link-panel__select"
        />
        <span class="memory-link-panel__arrow">→ 授权给 →</span>
        <n-select
          v-model:value="grantParentId"
          :options="grantParentOptions"
          size="small"
          placeholder="被授权方项目"
          class="memory-link-panel__select"
        />
        <n-button
          size="small"
          type="primary"
          :disabled="!grantChildId || !grantParentId"
          :loading="granting"
          @click="grant"
        >
          发起授权
        </n-button>
      </n-space>

      <n-empty v-if="!outgoing.length" size="small" description="暂无发出的授权" />
      <div v-for="l in outgoing" :key="l.id" class="memory-link-panel__row">
        <div class="memory-link-panel__desc">
          <b>{{ l.childProjectName }}</b> → <b>{{ l.parentProjectName }}</b>
          <n-tag size="tiny" :type="statusTagType(l.status)" :bordered="false">{{ statusLabel(l.status) }}</n-tag>
        </div>
        <div class="memory-link-panel__actions">
          <span class="memory-link-panel__meta">{{ l.createdAt }}</span>
          <n-button
            v-if="l.status === 'PENDING'"
            size="tiny"
            ghost
            :loading="busyId === l.id"
            @click="revoke(l, '取消该申请？')"
          >
            取消申请
          </n-button>
          <n-button
            v-if="l.status === 'ACTIVE'"
            size="tiny"
            type="error"
            ghost
            :loading="busyId === l.id"
            @click="revoke(l, '撤销后对方成员立即召回不到你项目的条目。')"
          >
            撤销
          </n-button>
        </div>
      </div>
    </n-card>

    <!-- ============ 待我审批的（parent 侧） ============ -->
    <n-card size="small" :bordered="true" class="memory-link-panel__block">
      <template #header>待我审批的 / 我管理的被授权</template>
      <n-empty v-if="!incoming.length" size="small" description="暂无收到的授权申请" />
      <div v-for="l in incoming" :key="l.id" class="memory-link-panel__row">
        <div class="memory-link-panel__desc">
          <b>{{ l.childProjectName }}</b>（{{ l.grantedByName || '发起人' }}）→ 授权给我的 <b>{{ l.parentProjectName }}</b>
          <n-tag size="tiny" :type="statusTagType(l.status)" :bordered="false">{{ statusLabel(l.status) }}</n-tag>
        </div>
        <div class="memory-link-panel__actions">
          <template v-if="l.status === 'PENDING'">
            <n-button size="tiny" type="primary" :loading="busyId === l.id" @click="approve(l)">通过</n-button>
            <n-button size="tiny" type="error" ghost :loading="busyId === l.id" @click="reject(l)">拒绝</n-button>
          </template>
          <n-button
            v-if="l.status === 'ACTIVE'"
            size="tiny"
            type="error"
            ghost
            :loading="busyId === l.id"
            @click="revoke(l, '撤销后你项目成员立即召回不到对方条目。')"
          >
            撤销
          </n-button>
          <span class="memory-link-panel__meta">{{ l.createdAt }}</span>
        </div>
      </div>
    </n-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import {
  NAlert, NButton, NCard, NEmpty, NSelect, NSpace, NTag, useDialog, useMessage
} from 'naive-ui'
import {
  memoryApi,
  type MemoryGenMatrixItemVO,
  type MemoryProjectLinkVO
} from '@/api/memory'

const message = useMessage()
const dialog = useDialog()

const projects = ref<MemoryGenMatrixItemVO[]>([])
const links = ref<MemoryProjectLinkVO[]>([])
const loading = ref(false)
const granting = ref(false)
const busyId = ref<number | null>(null)
const grantChildId = ref<number | null>(null)
const grantParentId = ref<number | null>(null)

/** 我 owner/admin 的项目 id 集（区分「我授权出去的/待我审批的」两侧归属）。 */
const managedIds = computed(() =>
  new Set(projects.value.filter(p => p.role === 'OWNER' || p.role === 'ADMIN').map(p => p.projectId))
)

/** child 侧是我管的 → 我授权出去的。 */
const outgoing = computed(() => links.value.filter(l => managedIds.value.has(l.childProjectId)))
/** parent 侧是我管的 → 待我审批/我管理的。 */
const incoming = computed(() => links.value.filter(l => managedIds.value.has(l.parentProjectId)))

/** 发起方选项：仅我 OWNER 的项目（后端发起权=child owner，FR-101）。 */
const ownedProjectOptions = computed(() =>
  projects.value.filter(p => p.role === 'OWNER').map(p => ({ label: p.projectName, value: p.projectId }))
)

/** 被授权方选项：我所在的全部项目（排除已选 child，防自环）。 */
const grantParentOptions = computed(() =>
  projects.value
    .filter(p => p.projectId !== grantChildId.value)
    .map(p => ({ label: p.projectName, value: p.projectId }))
)

function statusLabel(s: MemoryProjectLinkVO['status']): string {
  if (s === 'PENDING') return '待审批'
  if (s === 'ACTIVE') return '生效中'
  if (s === 'REJECTED') return '已拒绝'
  return '已撤销'
}

function statusTagType(s: MemoryProjectLinkVO['status']): 'success' | 'warning' | 'error' | 'default' {
  if (s === 'ACTIVE') return 'success'
  if (s === 'PENDING') return 'warning'
  if (s === 'REJECTED') return 'error'
  return 'default'
}

async function load() {
  loading.value = true
  try {
    const [pRes, lRes] = await Promise.all([memoryApi.getGenMatrix(), memoryApi.listMyLinks()])
    projects.value = pRes.data?.data ?? []
    links.value = lRes.data?.data ?? []
  } catch (e: any) {
    message.error(e?.message || '加载授权链失败')
  } finally {
    loading.value = false
  }
}

async function grant() {
  if (!grantChildId.value || !grantParentId.value) return
  granting.value = true
  try {
    await memoryApi.createLink(grantChildId.value, grantParentId.value)
    message.success('已发起，待对方审批')
    grantParentId.value = null
    await load()
  } catch (e: any) {
    message.error(e?.message || '发起失败')
  } finally {
    granting.value = false
  }
}

async function approve(l: MemoryProjectLinkVO) {
  busyId.value = l.id
  try {
    await memoryApi.approveLink(l.id)
    message.success('已通过，你项目成员现在可召回到对方条目')
    await load()
  } catch (e: any) {
    message.error(e?.message || '操作失败')
  } finally {
    busyId.value = null
  }
}

function reject(l: MemoryProjectLinkVO) {
  dialog.warning({
    title: '拒绝该授权申请？',
    content: `拒绝后 30 天内「${l.childProjectName}」不能再次向本项目发起授权。`,
    positiveText: '拒绝',
    negativeText: '取消',
    onPositiveClick: async () => {
      busyId.value = l.id
      try {
        await memoryApi.rejectLink(l.id)
        message.success('已拒绝')
        await load()
      } catch (e: any) {
        message.error(e?.message || '操作失败')
      } finally {
        busyId.value = null
      }
    }
  })
}

function revoke(l: MemoryProjectLinkVO, hint: string) {
  dialog.warning({
    title: l.status === 'PENDING' ? '取消申请？' : '撤销授权？',
    content: hint,
    positiveText: '确认',
    negativeText: '再想想',
    onPositiveClick: async () => {
      busyId.value = l.id
      try {
        await memoryApi.revokeLink(l.id)
        message.success(l.status === 'PENDING' ? '已取消' : '已撤销')
        await load()
      } catch (e: any) {
        message.error(e?.message || '操作失败')
      } finally {
        busyId.value = null
      }
    }
  })
}

onMounted(load)
defineExpose({ refresh: load })
</script>

<style lang="scss" scoped>
.memory-link-panel {
  &__top {
    margin-bottom: 12px;
  }
  &__block {
    margin-bottom: 12px;
  }
  &__grant {
    margin-bottom: 12px;
  }
  &__select {
    width: 200px;
  }
  &__arrow {
    font-size: 12px;
    opacity: 0.6;
  }
  &__row {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
    padding: 6px 0;
    border-bottom: 1px solid var(--divider-color, rgba(255, 255, 255, 0.06));
    flex-wrap: wrap;
    &:last-child {
      border-bottom: none;
    }
  }
  &__desc {
    display: flex;
    align-items: center;
    gap: 8px;
    font-size: 13px;
    flex-wrap: wrap;
  }
  &__actions {
    display: flex;
    align-items: center;
    gap: 6px;
  }
  &__meta {
    font-size: 11px;
    opacity: 0.55;
  }
}
</style>
