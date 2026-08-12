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
      双向确认——你发起后对方 owner/admin 审批通过才生效。撤销非对称：授权方（我 owner）发起撤销需对方项目 owner/admin 审批通过后才解除（审批前对方仍可召回）；被授权方（对方 owner/admin）撤销则即时生效并通知授权方。
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
          <template v-if="l.status === 'ACTIVE' && l.revokeRequestedBy">
            <n-tag size="tiny" type="warning" :bordered="false">撤销审批中</n-tag>
            <n-button
              size="tiny"
              ghost
              :loading="busyId === l.id"
              @click="withdrawRevoke(l)"
            >
              撤回申请
            </n-button>
          </template>
          <n-button
            v-else-if="l.status === 'ACTIVE'"
            size="tiny"
            type="error"
            ghost
            :loading="busyId === l.id"
            @click="revoke(l, '撤销需对方项目 owner/admin 审批通过后才生效，审批前对方仍可召回你项目条目。', '撤销申请已提交，待对方 owner/admin 审批')"
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
          <template v-if="l.status === 'ACTIVE' && l.revokeRequestedBy">
            <n-tag size="tiny" type="warning" :bordered="false">对方申请撤销</n-tag>
            <n-button size="tiny" type="primary" :loading="busyId === l.id" @click="approveRevoke(l)">通过撤销</n-button>
            <n-button size="tiny" type="error" ghost :loading="busyId === l.id" @click="rejectRevoke(l)">拒绝撤销</n-button>
          </template>
          <n-button
            v-else-if="l.status === 'ACTIVE'"
            size="tiny"
            type="error"
            ghost
            :loading="busyId === l.id"
            @click="revoke(l, '你侧撤销立即生效，无需对方审核；撤销后你项目成员立即召回不到对方条目，并通知对方。', '已撤销，已通知对方')"
          >
            撤销
          </n-button>
          <span class="memory-link-panel__meta">{{ l.createdAt }}</span>
        </div>
      </div>
    </n-card>

    <!-- ============ 二期 P1 · 个人授权（项目↔个人，只读召回）============ -->
    <n-divider class="memory-link-panel__divider">个人授权（项目 ↔ 个人）</n-divider>
    <n-alert type="info" :bordered="false" size="small" class="memory-link-panel__top">
      把「项目条目的召回读权」授权给某个个人（只读摘要，不写回）。双向：项目 owner/admin 主动授权个人（立即生效），
      或个人申请召回某项目（待项目 owner/admin 审批）。任一方可随时撤销，撤销即时断召回。
    </n-alert>

    <!-- 第二轮 #5：公共池管理（我 owner/admin 的项目，推入后所有人可申请召回） -->
    <n-card size="small" :bordered="true" class="memory-link-panel__block">
      <template #header>公共池管理（我 owner/admin 的项目）</template>
      <n-empty v-if="!managedProjectOptions.length" size="small" description="暂无可管理的项目" />
      <div v-for="p in managedProjectOptions" :key="p.value" class="memory-link-panel__row">
        <div class="memory-link-panel__desc"><b>{{ p.label }}</b></div>
        <n-switch
          :value="poolFlagOf(p.value)"
          :loading="poolBusyId === p.value"
          size="small"
          @update:value="togglePool(p.value, $event)"
        >
          <template #checked>已推入公共池</template>
          <template #unchecked>推入公共池</template>
        </n-switch>
      </div>
    </n-card>

    <!-- A. 项目授权给个人 -->
    <n-card size="small" :bordered="true" class="memory-link-panel__block">
      <template #header>项目授权给个人（我 owner/admin 的项目）</template>
      <n-space :size="8" align="center" class="memory-link-panel__grant" wrap>
        <n-select
          v-model:value="grantUserProjectId"
          :options="managedProjectOptions"
          size="small"
          placeholder="授权项目（我 owner/admin）"
          class="memory-link-panel__select"
        />
        <span class="memory-link-panel__arrow">→ 授权给 →</span>
        <n-select
          v-model:value="grantUserId"
          :options="userOptions"
          size="small"
          filterable
          remote
          clearable
          :loading="userSearching"
          placeholder="搜索被授权人姓名/账号"
          class="memory-link-panel__select"
          @search="onSearchUser"
          @focus="preloadUsers"
        />
        <n-button size="small" type="primary" :disabled="!grantUserProjectId || !grantUserId" :loading="granting" @click="grantUser">
          授权
        </n-button>
      </n-space>

      <n-empty v-if="!myManagedGrants.length" size="small" description="暂无我管理项目的个人授权" />
      <div v-for="g in myManagedGrants" :key="'mg-' + g.id" class="memory-link-panel__row">
        <div class="memory-link-panel__desc">
          <b>{{ g.projectName }}</b> → <b>{{ g.userName }}</b>
          <n-tag v-if="g.initiatedBy === 'USER'" size="tiny" type="warning" :bordered="false">个人申请</n-tag>
          <n-tag size="tiny" :type="statusTagType(g.status)" :bordered="false">{{ statusLabel(g.status) }}</n-tag>
        </div>
        <div class="memory-link-panel__actions">
          <template v-if="g.status === 'PENDING'">
            <n-button size="tiny" type="primary" :loading="busyId === g.id" @click="approveGrant(g)">通过</n-button>
            <n-button size="tiny" type="error" ghost :loading="busyId === g.id" @click="rejectGrant(g)">拒绝</n-button>
          </template>
          <n-button v-if="g.status === 'ACTIVE'" size="tiny" type="error" ghost :loading="busyId === g.id" @click="revokeGrant(g)">
            撤销
          </n-button>
          <span class="memory-link-panel__meta">{{ g.createdAt }}</span>
        </div>
      </div>
    </n-card>

    <!-- B. 我被授权的 + 我申请召回 -->
    <n-card size="small" :bordered="true" class="memory-link-panel__block">
      <template #header>我被授权的 / 申请召回</template>
      <n-space :size="8" align="center" class="memory-link-panel__grant" wrap>
        <n-select
          v-model:value="applyProjectId"
          :options="projOptions"
          size="small"
          filterable
          remote
          clearable
          :loading="projSearching"
          placeholder="搜索要申请召回的项目（空=公共池）"
          class="memory-link-panel__select"
          @search="onSearchProject"
          @focus="preloadProjects"
        />
        <n-button size="small" type="primary" :disabled="!applyProjectId" :loading="granting" @click="applyGrant">
          申请召回
        </n-button>
      </n-space>

      <n-empty v-if="!myGranteeGrants.length" size="small" description="暂无被授权/申请中的项目" />
      <div v-for="g in myGranteeGrants" :key="'gg-' + g.id" class="memory-link-panel__row">
        <div class="memory-link-panel__desc">
          <b>{{ g.projectName }}</b>
          <n-tag v-if="g.initiatedBy === 'USER'" size="tiny" type="warning" :bordered="false">我申请</n-tag>
          <n-tag v-else size="tiny" type="info" :bordered="false">项目授权</n-tag>
          <n-tag size="tiny" :type="statusTagType(g.status)" :bordered="false">{{ statusLabel(g.status) }}</n-tag>
        </div>
        <div class="memory-link-panel__actions">
          <n-button
            v-if="g.status === 'PENDING'"
            size="tiny"
            ghost
            :loading="busyId === g.id"
            @click="revokeGrant(g)"
          >
            取消申请
          </n-button>
          <n-button
            v-if="g.status === 'ACTIVE'"
            size="tiny"
            type="error"
            ghost
            :loading="busyId === g.id"
            @click="revokeGrant(g)"
          >
            放弃授权
          </n-button>
          <span class="memory-link-panel__meta">{{ g.createdAt }}</span>
        </div>
      </div>
    </n-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import {
  NAlert, NButton, NCard, NDivider, NEmpty, NSelect, NSpace, NSwitch, NTag, useDialog, useMessage
} from 'naive-ui'
import {
  memoryApi,
  type MemoryGenMatrixItemVO,
  type MemoryProjectLinkVO,
  type MemoryProjectUserGrantVO
} from '@/api/memory'
import { useAuthStore } from '@/stores/auth'

const message = useMessage()
const dialog = useDialog()
const authStore = useAuthStore()

const projects = ref<MemoryGenMatrixItemVO[]>([])
const links = ref<MemoryProjectLinkVO[]>([])
const grants = ref<MemoryProjectUserGrantVO[]>([])
/** 三期 G1：公共池项目（被授权方下拉候选；所有人可申请，已排除自建）。 */
const poolProjects = ref<{ id: number; name: string }[]>([])
const loading = ref(false)
const granting = ref(false)
const busyId = ref<number | null>(null)
const grantChildId = ref<number | null>(null)
const grantParentId = ref<number | null>(null)

// ---- 二期 P1 · 个人授权状态 ----
const currentUserId = computed(() => authStore.userInfo?.id)
/** 我 owner/admin 的项目（项目授权个人=owner/admin 发起权）。 */
const managedProjectOptions = computed(() =>
  projects.value.filter(p => p.role === 'OWNER' || p.role === 'ADMIN').map(p => ({ label: p.projectName, value: p.projectId }))
)
const grantUserProjectId = ref<number | null>(null)
const grantUserId = ref<number | null>(null)
const userOptions = ref<{ label: string; value: number }[]>([])
const userSearching = ref(false)
const applyProjectId = ref<number | null>(null)
const projOptions = ref<{ label: string; value: number }[]>([])
const projSearching = ref(false)

/** 我被授权的（grantee=本人）。 */
const myGranteeGrants = computed(() =>
  grants.value.filter(g => g.userId === currentUserId.value)
)
/** 我管理的项目授权出去的 / 待我审批的（我 owner/admin 该项目，且被授权人非本人）。 */
const myManagedGrants = computed(() =>
  grants.value.filter(g => managedIds.value.has(g.projectId) && g.userId !== currentUserId.value)
)

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

/** 被授权方选项：我所在的全部项目 ∪ 公共池项目（排除已选 child，防自环；pool 项标「公共池」）。 */
const grantParentOptions = computed(() => {
  const opts: { label: string; value: number }[] = []
  const seen = new Set<number>()
  for (const p of projects.value) {
    if (p.projectId === grantChildId.value || seen.has(p.projectId)) continue
    seen.add(p.projectId)
    opts.push({ label: p.projectName, value: p.projectId })
  }
  for (const p of poolProjects.value) {
    if (p.id === grantChildId.value || seen.has(p.id)) continue
    seen.add(p.id)
    opts.push({ label: `${p.name}（公共池）`, value: p.id })
  }
  return opts
})

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
    const [pRes, lRes, gRes, poolRes] = await Promise.all([
      memoryApi.getGenMatrix(), memoryApi.listMyLinks(), memoryApi.listMyUserGrants(),
      memoryApi.listPoolProjects()
    ])
    projects.value = pRes.data?.data ?? []
    links.value = lRes.data?.data ?? []
    grants.value = gRes.data?.data ?? []
    poolProjects.value = poolRes.data?.data ?? []
  } catch (e: any) {
    message.error(e?.message || '加载授权链失败')
  } finally {
    loading.value = false
  }
}

// ---- 二期 P1 · 个人授权 ----

/** 第二轮 #6：空关键词也请求（后端返默认候选），下拉打开即有数据；@focus 预载。 */
async function onSearchUser(q: string) {
  userSearching.value = true
  try {
    const res = await memoryApi.searchGrantUsers(q ?? '')
    userOptions.value = (res.data?.data ?? []).map(u => ({ label: u.name, value: u.id }))
  } catch { userOptions.value = [] } finally { userSearching.value = false }
}
async function preloadUsers() {
  if (userOptions.value.length) return
  await onSearchUser('')
}

/** 第二轮 #6：空关键词返公共池默认候选（后端），下拉打开即有数据；@focus 预载。 */
async function onSearchProject(q: string) {
  projSearching.value = true
  try {
    const res = await memoryApi.searchGrantProjects(q ?? '')
    projOptions.value = (res.data?.data ?? []).map(p => ({ label: p.name, value: p.id }))
  } catch { projOptions.value = [] } finally { projSearching.value = false }
}
async function preloadProjects() {
  if (projOptions.value.length) return
  await onSearchProject('')
}

// ---- 第二轮 #5 · 公共池管理 ----
const poolBusyId = ref<number | null>(null)
/** 项目是否已推入公共池（初值来自 gen 矩阵 memoryPoolPublic）。 */
function poolFlagOf(projectId: number): boolean {
  return projects.value.find(p => p.projectId === projectId)?.memoryPoolPublic ?? false
}
async function togglePool(projectId: number, on: boolean | string | number) {
  const pub = !!on
  poolBusyId.value = projectId
  try {
    await memoryApi.toggleProjectPool(projectId, pub)
    message.success(pub ? '已推入公共池，所有人可申请召回' : '已移出公共池')
    await load()
  } catch (e: any) {
    message.error(e?.message || '切换失败')
  } finally {
    poolBusyId.value = null
  }
}

async function grantUser() {
  if (!grantUserProjectId.value || !grantUserId.value) return
  granting.value = true
  try {
    await memoryApi.grantUserByProject(grantUserProjectId.value, grantUserId.value)
    message.success('已授权，对方可在召回范围勾选本项目')
    grantUserId.value = null
    userOptions.value = []
    await load()
  } catch (e: any) {
    message.error(e?.message || '授权失败')
  } finally {
    granting.value = false
  }
}

async function applyGrant() {
  if (!applyProjectId.value) return
  granting.value = true
  try {
    await memoryApi.applyUserGrant(applyProjectId.value)
    message.success('已申请，待项目 owner/admin 审批')
    applyProjectId.value = null
    projOptions.value = []
    await load()
  } catch (e: any) {
    message.error(e?.message || '申请失败')
  } finally {
    granting.value = false
  }
}

async function approveGrant(g: MemoryProjectUserGrantVO) {
  busyId.value = g.id
  try {
    await memoryApi.approveUserGrant(g.id)
    message.success('已通过，对方可在召回范围勾选本项目')
    await load()
  } catch (e: any) {
    message.error(e?.message || '操作失败')
  } finally {
    busyId.value = null
  }
}

function rejectGrant(g: MemoryProjectUserGrantVO) {
  dialog.warning({
    title: '拒绝该申请？',
    content: `拒绝后 30 天内「${g.userName || '该用户'}」不能再次申请召回本项目。`,
    positiveText: '拒绝',
    negativeText: '取消',
    onPositiveClick: async () => {
      busyId.value = g.id
      try {
        await memoryApi.rejectUserGrant(g.id)
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

function revokeGrant(g: MemoryProjectUserGrantVO) {
  dialog.warning({
    title: g.status === 'PENDING' ? '取消申请？' : '撤销授权？',
    content: g.status === 'PENDING'
      ? '取消后该项目将不会收到你的申请。'
      : (g.userId === currentUserId.value
          ? '撤销后召回范围将不再包含该项目。'
          : '撤销后对方将立即召回不到本项目条目。'),
    positiveText: '确认',
    negativeText: '再想想',
    onPositiveClick: async () => {
      busyId.value = g.id
      try {
        await memoryApi.revokeUserGrant(g.id)
        message.success(g.status === 'PENDING' ? '已取消' : '已撤销')
        await load()
      } catch (e: any) {
        message.error(e?.message || '操作失败')
      } finally {
        busyId.value = null
      }
    }
  })
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

function revoke(l: MemoryProjectLinkVO, hint: string, successMsg?: string) {
  dialog.warning({
    title: l.status === 'PENDING' ? '取消申请？' : '撤销授权？',
    content: hint,
    positiveText: '确认',
    negativeText: '再想想',
    onPositiveClick: async () => {
      busyId.value = l.id
      try {
        await memoryApi.revokeLink(l.id)
        message.success(successMsg ?? (l.status === 'PENDING' ? '已取消' : '已撤销'))
        await load()
      } catch (e: any) {
        message.error(e?.message || '操作失败')
      } finally {
        busyId.value = null
      }
    }
  })
}

// ---- 三期非对称撤销：parent 审 child 的撤销申请 / child 撤回自己的申请 ----

async function approveRevoke(l: MemoryProjectLinkVO) {
  busyId.value = l.id
  try {
    await memoryApi.approveRevokeLink(l.id)
    message.success('已通过撤销，记忆授权已解除')
    await load()
  } catch (e: any) {
    message.error(e?.message || '操作失败')
  } finally {
    busyId.value = null
  }
}

async function rejectRevoke(l: MemoryProjectLinkVO) {
  busyId.value = l.id
  try {
    await memoryApi.rejectRevokeLink(l.id)
    message.success('已拒绝撤销，授权保持生效')
    await load()
  } catch (e: any) {
    message.error(e?.message || '操作失败')
  } finally {
    busyId.value = null
  }
}

async function withdrawRevoke(l: MemoryProjectLinkVO) {
  busyId.value = l.id
  try {
    await memoryApi.withdrawRevokeRequest(l.id)
    message.success('已撤回撤销申请，授权保持生效')
    await load()
  } catch (e: any) {
    message.error(e?.message || '操作失败')
  } finally {
    busyId.value = null
  }
}

onMounted(load)
defineExpose({ refresh: load })
</script>

<style lang="scss" scoped>
.memory-link-panel {
  &__top {
    margin-bottom: 12px;
  }
  &__divider {
    margin: 8px 0 4px;
    font-size: 13px;
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
