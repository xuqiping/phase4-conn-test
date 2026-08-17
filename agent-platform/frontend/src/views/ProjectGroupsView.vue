<template>
  <div class="pg-view">
    <!-- 列表模式：我的组卡片 -->
    <div v-if="!selected" class="pg-view__list">
      <div class="pg-view__header">
        <h2 class="pg-view__title">项目组</h2>
        <NButton type="primary" :loading="creating" @click="showCreate = true">
          <template #icon><NIcon :component="AddOutline" /></template>
          新建项目组
        </NButton>
      </div>
      <NSpin :show="loading">
        <NEmpty v-if="!groups.length" description="还没有项目组。组长可建组、划拨积分，成员消耗入组池。" />
        <div v-else class="pg-view__grid">
          <div v-for="g in groups" :key="g.id" class="pg-card" @click="openGroup(g)">
            <div class="pg-card__head">
              <span class="pg-card__name" :title="g.name">{{ g.name }}</span>
              <NTag size="small" :type="g.myRole === 'OWNER' ? 'primary' : 'default'" :bordered="false">
                {{ g.myRole === 'OWNER' ? '组长' : '成员' }}
              </NTag>
            </div>
            <div class="pg-card__balance">组池 {{ fmt(g.balancePoints) }} 分</div>
            <div class="pg-card__meta">
              {{ g.memberCount }} 成员
              <template v-if="g.myRole === 'MEMBER'">
                · 我的限额 {{ g.myQuota === null ? '不限' : fmt(g.myQuota) }}
                · 已用 {{ fmt(g.myUsed) }}
              </template>
            </div>
          </div>
        </div>
      </NSpin>
    </div>

    <!-- 详情模式 -->
    <div v-else class="pg-view__detail">
      <div class="pg-view__detail-header">
        <NButton quaternary @click="backToList">
          <NIcon :component="ArrowBackOutline" /> 返回
        </NButton>
        <span class="pg-view__detail-name">{{ selected.name }}</span>
        <NTag size="small" :type="isOwner ? 'primary' : 'default'" :bordered="false">
          {{ isOwner ? '组长' : '成员' }}
        </NTag>
        <!-- 组长资金操作（成员不可见） -->
        <template v-if="isOwner">
          <NButton size="small" tertiary type="primary" @click="openAllocate('allocate')">划拨</NButton>
          <NButton size="small" tertiary @click="openAllocate('reclaim')">回收</NButton>
          <span v-if="overview" class="pg-view__balance-chip">
            组池 {{ fmt(overview.group.balancePoints) }} 分
            <template v-if="Number(overview.group.inflightPoints) > 0">
              · 在途 {{ fmt(overview.group.inflightPoints) }}
            </template>
          </span>
        </template>
        <span v-else class="pg-view__balance-chip">
          组池 {{ fmt(selected.balancePoints) }} 分
        </span>
      </div>

      <NTabs type="line" :value="tab" @update:value="(v: string) => { tab = v; onTabChange() }">
        <!-- 成员/流水：组长专属（成员视角只有产出 tab） -->
        <template v-if="isOwner">
          <NTabPane name="members" tab="成员">
            <div class="pg-members">
              <div class="pg-members__toolbar">
                <NButton size="small" type="primary" @click="openAddMember">加成员</NButton>
                <span class="pg-members__hint">
                  限额=成员累计消耗上限（空=不限）；调低不追溯，只约束后续消耗
                </span>
              </div>
              <NDataTable
                size="small"
                :columns="memberColumns"
                :data="overview?.group.members ?? []"
                :loading="loadingOverview"
                :row-key="(r: ProjectGroupMemberVO) => r.userId"
                :max-height="420"
              />
            </div>
          </NTabPane>
          <NTabPane name="ledger" tab="组池流水">
            <NDataTable
              remote
              size="small"
              :columns="ledgerColumns"
              :data="overview?.ledger.records ?? []"
              :loading="loadingOverview"
              :pagination="ledgerPagination"
              :max-height="420"
            />
          </NTabPane>
        </template>
        <NTabPane name="outputs" tab="产出">
          <div class="pg-outputs">
            <div class="pg-outputs__filters">
              <NSelect
                v-if="isOwner"
                v-model:value="outputFilter.memberUserId"
                size="small"
                clearable
                placeholder="全部成员"
                :options="memberFilterOptions"
                class="pg-outputs__member"
                @update:value="onFilterChange"
              />
              <NSelect
                v-model:value="outputFilter.kind"
                size="small"
                clearable
                placeholder="全部类型"
                :options="kindOptions"
                class="pg-outputs__kind"
                @update:value="onFilterChange"
              />
              <NDatePicker
                v-model:value="outputFilter.range"
                type="daterange"
                size="small"
                clearable
                :actions="['clear']"
                close-on-select
                update-value-on-close
                class="pg-outputs__range"
                @update:value="onFilterChange"
              />
              <NButton size="small" quaternary :disabled="!hasFilters" @click="clearFilters">清空</NButton>
            </div>
            <span v-if="!isOwner" class="pg-outputs__hint">成员视角仅显示自己的消耗行</span>
            <NDataTable
              remote
              size="small"
              :columns="outputColumns"
              :data="outputs?.records ?? []"
              :loading="loadingOutputs"
              :pagination="outputPagination"
              :scroll-x="900"
              :max-height="420"
            />
          </div>
        </NTabPane>
      </NTabs>
    </div>

    <!-- 建组弹窗 -->
    <NModal v-model:show="showCreate" preset="card" title="新建项目组" style="max-width: 400px">
      <NInput v-model:value="createName" placeholder="组名（≤64字）" maxlength="64" @keydown.enter="confirmCreate" />
      <NInput
        v-model:value="createDesc"
        placeholder="描述（可选）"
        style="margin-top: 8px"
        maxlength="200"
      />
      <template #footer>
        <div class="pg-view__modal-footer">
          <NButton size="small" quaternary @click="showCreate = false">取消</NButton>
          <NButton size="small" type="primary" :loading="creating" @click="confirmCreate">建组</NButton>
        </div>
      </template>
    </NModal>

    <!-- 划拨/回收弹窗 -->
    <NModal
      v-model:show="showAllocate"
      preset="card"
      :title="allocateMode === 'allocate' ? '划拨（个人→组池）' : '回收（组池→个人）'"
      style="max-width: 400px"
    >
      <NInputNumber
        v-model:value="allocatePoints"
        :min="0.01"
        :step="10"
        placeholder="积分"
        style="width: 100%"
      />
      <NInput v-model:value="allocateRemark" placeholder="备注（可选）" maxlength="100" style="margin-top: 8px" />
      <div v-if="allocateMode === 'reclaim'" class="pg-view__allocate-hint">
        回收按「组池余额−在途占用」封顶（在途任务结算后余额可能不足）。
      </div>
      <template #footer>
        <div class="pg-view__modal-footer">
          <NButton size="small" quaternary @click="showAllocate = false">取消</NButton>
          <NButton size="small" type="primary" :loading="allocating" @click="confirmAllocate">
            {{ allocateMode === 'allocate' ? '划拨' : '回收' }}
          </NButton>
        </div>
      </template>
    </NModal>

    <!-- 加成员弹窗（候选搜索 + 限额） -->
    <NModal v-model:show="showAddMember" preset="card" title="加成员" style="max-width: 400px">
      <NSelect
        v-model:value="addMemberId"
        filterable
        remote
        clearable
        placeholder="搜索用户名"
        :options="candidateOptions"
        :loading="loadingCandidates"
        @search="onSearchCandidates"
      />
      <NInputNumber
        v-model:value="addMemberQuota"
        :min="0"
        placeholder="积分限额（空=不限）"
        style="width: 100%; margin-top: 8px"
      />
      <template #footer>
        <div class="pg-view__modal-footer">
          <NButton size="small" quaternary @click="showAddMember = false">取消</NButton>
          <NButton size="small" type="primary" :disabled="!addMemberId" @click="confirmAddMember">添加</NButton>
        </div>
      </template>
    </NModal>
  </div>
</template>

<script setup lang="ts">
import { computed, h, onMounted, ref, watch } from 'vue'
import {
  NButton, NDataTable, NDatePicker, NEmpty, NIcon, NInput, NInputNumber, NModal, NSelect,
  NSpin, NTabs, NTabPane, NTag, useDialog, useMessage,
  type DataTableColumns
} from 'naive-ui'
import { AddOutline, ArrowBackOutline } from '@vicons/ionicons5'
import {
  projectGroupApi,
  type ProjectGroupMemberVO,
  type ProjectGroupMineVO,
  type ProjectGroupOutputVO,
  type ProjectGroupLedgerRowVO
} from '@/api/projectGroup'

/**
 * 计划5 Step7：项目组推进页。
 * 我的组卡片 → 组详情：组长（成员管理/组池流水/划拨回收/全员产出）+ 成员（仅自己产出行，拍板边界）。
 * 权限：菜单/接口 gated project-group:manage（后端再 requireOwner/成员校验兜底）。
 */
const message = useMessage()
const dialog = useDialog()

const groups = ref<ProjectGroupMineVO[]>([])
const loading = ref(false)
const selected = ref<ProjectGroupMineVO | null>(null)
const tab = ref('members')

// ---- 组长总览（overview = 详情+流水分页） ----
const overview = ref<Awaited<ReturnType<typeof projectGroupApi.overview>>['data']['data'] | null>(null)
const loadingOverview = ref(false)
const ledgerPage = ref(1)
const ledgerSize = ref(10)

// ---- 产出列表（组长全员/成员仅自己） ----
const outputs = ref<Awaited<ReturnType<typeof projectGroupApi.outputs>>['data']['data'] | null>(null)
const loadingOutputs = ref(false)
const outputPage = ref(1)
const outputSize = ref(10)
const outputFilter = ref<{
  memberUserId: number | null
  kind: string | null
  range: [number, number] | null
}>({ memberUserId: null, kind: null, range: null })

const isOwner = computed(() => selected.value?.myRole === 'OWNER')
const hasFilters = computed(() =>
  outputFilter.value.memberUserId != null || !!outputFilter.value.kind || !!outputFilter.value.range)

const kindOptions = [
  { label: '对话', value: 'CHAT' },
  { label: '视频', value: 'VIDEO' },
  { label: '图片', value: 'IMAGE' },
  { label: '嵌入', value: 'EMBED' },
  { label: '重排', value: 'RERANK' }
]

/** 积分显示：去尾零（后端 DECIMAL 可能带 .00）。 */
function fmt(n: number | null | undefined): string {
  if (n == null) return '0'
  return Number.isInteger(n) ? String(n) : String(n).replace(/\.?0+$/, '')
}

function fmtTime(t: string | null): string {
  if (!t) return '-'
  return t.slice(0, 16).replace('T', ' ')
}

// ==================== 列表 ====================

async function loadGroups() {
  loading.value = true
  try {
    const res = await projectGroupApi.mine()
    groups.value = res.data.data
  } catch {
    message.error('加载我的项目组失败')
  } finally {
    loading.value = false
  }
}

function openGroup(g: ProjectGroupMineVO) {
  selected.value = g
  tab.value = isOwner.value ? 'members' : 'outputs'
  onTabChange()
}

function backToList() {
  selected.value = null
  overview.value = null
  outputs.value = null
  void loadGroups()
}

// ---- 建组 ----
const showCreate = ref(false)
const creating = ref(false)
const createName = ref('')
const createDesc = ref('')

async function confirmCreate() {
  const name = createName.value.trim()
  if (!name) return
  creating.value = true
  try {
    await projectGroupApi.create(name, createDesc.value.trim() || undefined)
    message.success('项目组已创建（你已是组长）')
    showCreate.value = false
    createName.value = ''
    createDesc.value = ''
    await loadGroups()
  } catch {
    /* 拦截器已提示 */
  } finally {
    creating.value = false
  }
}

// ==================== 总览加载 ====================

async function loadOverview() {
  const g = selected.value
  if (!g || !isOwner.value) return
  loadingOverview.value = true
  try {
    const res = await projectGroupApi.overview(g.id, ledgerPage.value, ledgerSize.value)
    overview.value = res.data.data
  } catch {
    message.error('组总览加载失败')
  } finally {
    loadingOverview.value = false
  }
}

async function loadOutputs() {
  const g = selected.value
  if (!g) return
  loadingOutputs.value = true
  try {
    const f = outputFilter.value
    const res = await projectGroupApi.outputs(g.id, {
      memberUserId: isOwner.value ? (f.memberUserId ?? undefined) : undefined,
      kind: f.kind ?? undefined,
      // daterange：to=尾日 23:59:59.999（含整天）
      from: f.range ? new Date(f.range[0]).toISOString() : undefined,
      to: f.range ? new Date(f.range[1] + 86399999).toISOString() : undefined,
      page: outputPage.value,
      size: outputSize.value
    })
    outputs.value = res.data.data
  } catch {
    message.error('产出列表加载失败')
  } finally {
    loadingOutputs.value = false
  }
}

function onTabChange() {
  if (tab.value === 'outputs') {
    outputPage.value = 1
    void loadOutputs()
  } else if (isOwner.value) {
    void loadOverview()
  }
}

function onFilterChange() {
  outputPage.value = 1
  void loadOutputs()
}

function clearFilters() {
  outputFilter.value = { memberUserId: null, kind: null, range: null }
  onFilterChange()
}

const memberFilterOptions = computed(() =>
  (overview.value?.group.members ?? []).map(m => ({
    label: m.displayName || m.username || `#${m.userId}`,
    value: m.userId
  })))

const ledgerPagination = computed(() => ({
  page: ledgerPage.value,
  pageSize: ledgerSize.value,
  itemCount: overview.value?.ledger.total ?? 0,
  pageSizes: [10, 20, 50],
  showSizePicker: true,
  onChange: (p: number) => { ledgerPage.value = p; void loadOverview() },
  onUpdatePageSize: (s: number) => { ledgerPage.value = 1; ledgerSize.value = s; void loadOverview() }
}))

const outputPagination = computed(() => ({
  page: outputPage.value,
  pageSize: outputSize.value,
  itemCount: outputs.value?.total ?? 0,
  pageSizes: [10, 20, 50],
  showSizePicker: true,
  onChange: (p: number) => { outputPage.value = p; void loadOutputs() },
  onUpdatePageSize: (s: number) => { outputPage.value = 1; outputSize.value = s; void loadOutputs() }
}))

// ==================== 表格列 ====================

const LEDGER_TYPE: Record<string, { label: string; type: 'default' | 'info' | 'success' | 'warning' | 'error' }> = {
  ALLOCATE: { label: '划入', type: 'success' },
  RECLAIM: { label: '回收', type: 'warning' },
  CONSUME: { label: '消耗', type: 'info' },
  REFUND: { label: '退款', type: 'default' },
  ADMIN_ADJUST: { label: '调整', type: 'default' },
  BACKSTOP: { label: '兜底', type: 'error' }
}

const ledgerColumns: DataTableColumns<ProjectGroupLedgerRowVO> = [
  { title: '时间', key: 'createdAt', width: 140, render: r => fmtTime(r.createdAt) },
  { title: '类型', key: 'type', width: 80, render: r => {
    const t = LEDGER_TYPE[r.type]
    return h(NTag, { size: 'small', type: t?.type ?? 'default', bordered: false }, () => t?.label ?? r.type)
  } },
  { title: '操作人', key: 'actorUsername', width: 110, render: r => r.actorUsername ?? (r.actorUserId != null ? `#${r.actorUserId}` : '-') },
  { title: '变动', key: 'deltaPoints', width: 100, render: r => {
    const v = Number(r.deltaPoints)
    const sign = r.type === 'CONSUME' ? '-' : (v > 0 ? '+' : '')
    return h('span', { class: v < 0 || r.type === 'CONSUME' ? 'pg-ledger__neg' : 'pg-ledger__pos' },
      `${sign}${fmt(r.deltaPoints)}`)
  } },
  { title: '余额', key: 'balanceAfter', width: 90, render: r => fmt(r.balanceAfter) },
  { title: '关联', key: 'ref', width: 90, render: r => r.refType ? `${r.refType}${r.refId ? '#' + r.refId : ''}` : '-' },
  { title: '备注', key: 'remark', ellipsis: { tooltip: true }, render: r => r.remark ?? '' }
]

const KIND_LABEL: Record<string, string> = { CHAT: '对话', VIDEO: '视频', IMAGE: '图片', EMBED: '嵌入', RERANK: '重排' }

const outputColumns: DataTableColumns<ProjectGroupOutputVO> = [
  { title: '时间', key: 'createdAt', width: 140, render: r => fmtTime(r.createdAt) },
  { title: '成员', key: 'username', width: 110, render: r => r.username ?? (r.userId != null ? `#${r.userId}` : '-') },
  { title: '类型', key: 'kind', width: 70, render: r => KIND_LABEL[r.kind] ?? r.kind },
  { title: '模型', key: 'model', width: 150, ellipsis: { tooltip: true }, render: r => r.model ?? '-' },
  {
    title: '内容', key: 'mediaPrompt', ellipsis: { tooltip: true },
    render: r => r.mediaPrompt ?? (r.kind === 'CHAT' ? '对话消耗' : '-')
  },
  {
    title: '任务状态', key: 'mediaStatus', width: 100,
    render: r => r.taskId != null ? (r.mediaStatus ?? '-') : '-'
  },
  { title: '积分', key: 'pointsConsumed', width: 90, render: r => fmt(r.pointsConsumed) }
]

const memberColumns = computed<DataTableColumns<ProjectGroupMemberVO>>(() => [
  { title: '用户', key: 'username', width: 140, render: r => {
    const name = r.displayName || r.username || `#${r.userId}`
    return h('span', null, [
      name,
      r.isOwner ? h(NTag, { size: 'tiny', type: 'primary', bordered: false, style: 'margin-left: 6px' }, () => '组长') : null
    ])
  } },
  { title: '限额', key: 'quotaLimitPoints', width: 110, render: r => r.quotaLimitPoints == null ? '不限' : fmt(r.quotaLimitPoints) },
  { title: '已用', key: 'usedPoints', width: 100, render: r => fmt(r.usedPoints) },
  { title: '加入时间', key: 'createdAt', width: 140, render: r => fmtTime(r.createdAt) },
  {
    title: '操作', key: 'actions', width: 220,
    render: r => {
      if (r.isOwner) return h('span', { class: 'pg-members__hint' }, '—')
      const btn = (label: string, onClick: () => void, type: 'primary' | 'default' | 'error' = 'default') =>
        h(NButton, { size: 'tiny', quaternary: true, type, onClick }, () => label)
      return h('div', { style: 'display:flex;gap:4px' }, [
        btn('调限额', () => openQuota(r), 'primary'),
        btn('重置已用', () => confirmResetUsed(r)),
        btn('移除', () => confirmRemove(r), 'error')
      ])
    }
  }
])

// ==================== 成员管理 ====================

function openQuota(m: ProjectGroupMemberVO) {
  dialog.warning({
    title: `调整 ${m.displayName || m.username || '#' + m.userId} 的限额`,
    content: '输入新限额（留空=改为不限）。调低不追溯已耗，仅约束后续消耗。',
    positiveText: '保存',
    negativeText: '取消',
    onPositiveClick: async () => {
      const input = window.prompt(
        `新限额（当前 ${m.quotaLimitPoints == null ? '不限' : fmt(m.quotaLimitPoints)}，输入数字或留空=不限）`,
        m.quotaLimitPoints == null ? '' : String(m.quotaLimitPoints))
      if (input === null) return
      const val = input.trim() === '' ? null : Number(input)
      if (val != null && (!Number.isFinite(val) || val < 0)) {
        message.error('限额须为非负数字')
        return
      }
      try {
        await projectGroupApi.updateQuota(selected.value!.id, m.userId, val)
        message.success('限额已更新')
        void loadOverview()
      } catch { /* 拦截器已提示 */ }
    }
  })
}

function confirmResetUsed(m: ProjectGroupMemberVO) {
  dialog.warning({
    title: '重置成员已用',
    content: `把 ${m.displayName || m.username || '#' + m.userId} 的已用从 ${fmt(m.usedPoints)} 归零？会记一笔「调整」流水留痕，限额不变。`,
    positiveText: '重置',
    negativeText: '取消',
    onPositiveClick: async () => {
      try {
        await projectGroupApi.resetUsed(selected.value!.id, m.userId)
        message.success('已重置')
        void loadOverview()
      } catch { /* 拦截器已提示 */ }
    }
  })
}

function confirmRemove(m: ProjectGroupMemberVO) {
  dialog.warning({
    title: '移除成员',
    content: `把 ${m.displayName || m.username || '#' + m.userId} 移出项目组？历史流水留痕不受影响。`,
    positiveText: '移除',
    negativeText: '取消',
    onPositiveClick: async () => {
      try {
        await projectGroupApi.removeMember(selected.value!.id, m.userId)
        message.success('已移除')
        void loadOverview()
      } catch { /* 拦截器已提示 */ }
    }
  })
}

// ---- 加成员 ----
const showAddMember = ref(false)
const addMemberId = ref<number | null>(null)
const addMemberQuota = ref<number | null>(null)
const candidateOptions = ref<{ label: string; value: number }[]>([])
const loadingCandidates = ref(false)
let candidateTimer: ReturnType<typeof setTimeout> | null = null

function openAddMember() {
  addMemberId.value = null
  addMemberQuota.value = null
  candidateOptions.value = []
  showAddMember.value = true
  void searchCandidates('')
}

function onSearchCandidates(q: string) {
  if (candidateTimer) clearTimeout(candidateTimer)
  candidateTimer = setTimeout(() => void searchCandidates(q), 300)
}

async function searchCandidates(q: string) {
  loadingCandidates.value = true
  try {
    const res = await projectGroupApi.candidates(selected.value!.id, q)
    candidateOptions.value = res.data.data.map(c => ({ label: c.username, value: c.userId }))
  } catch {
    /* 拦截器已提示 */
  } finally {
    loadingCandidates.value = false
  }
}

async function confirmAddMember() {
  if (!addMemberId.value) return
  try {
    await projectGroupApi.addMember(selected.value!.id, addMemberId.value, addMemberQuota.value)
    message.success('成员已添加')
    showAddMember.value = false
    void loadOverview()
  } catch { /* 拦截器已提示 */ }
}

// ==================== 划拨/回收 ====================

const showAllocate = ref(false)
const allocateMode = ref<'allocate' | 'reclaim'>('allocate')
const allocatePoints = ref<number | null>(null)
const allocateRemark = ref('')
const allocating = ref(false)

function openAllocate(mode: 'allocate' | 'reclaim') {
  allocateMode.value = mode
  allocatePoints.value = null
  allocateRemark.value = ''
  showAllocate.value = true
}

async function confirmAllocate() {
  const pts = allocatePoints.value
  if (!pts || pts <= 0) {
    message.warning('请输入正数积分')
    return
  }
  allocating.value = true
  try {
    await (allocateMode.value === 'allocate'
      ? projectGroupApi.allocate(selected.value!.id, pts, allocateRemark.value.trim() || undefined)
      : projectGroupApi.reclaim(selected.value!.id, pts, allocateRemark.value.trim() || undefined))
    message.success(allocateMode.value === 'allocate' ? '划拨成功' : '回收成功')
    showAllocate.value = false
    void loadOverview()
    // 划拨动了个人钱包 → 组卡片余额也刷新
    void loadGroups()
  } catch {
    /* 拦截器已提示（余额不足/在途上限等由后端文案） */
  } finally {
    allocating.value = false
  }
}

// 划拨/回收后刷新列表态卡片（不影响详情态）
watch(showAllocate, v => { if (!v) void loadGroups() })

onMounted(() => { void loadGroups() })
</script>

<style lang="scss" scoped>
.pg-view {
  height: 100%;
  padding: var(--spacing-4);
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: var(--spacing-3);
}

.pg-view__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.pg-view__title {
  font-size: var(--font-size-xl);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
  margin: 0;
}

.pg-view__grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
  gap: var(--spacing-3);
}

.pg-card {
  padding: var(--spacing-3) var(--spacing-4);
  background: var(--color-surface);
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-md);
  cursor: pointer;
  transition: all var(--duration-fast) var(--ease-in-out);

  &:hover {
    border-color: var(--color-primary);
    transform: translateY(-2px);
    box-shadow: var(--shadow-md);
  }

  &__head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: var(--spacing-2);
  }

  &__name {
    font-size: var(--font-size-base);
    font-weight: var(--font-weight-medium);
    color: var(--color-text-primary);
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  &__balance {
    margin-top: var(--spacing-2);
    font-size: var(--font-size-lg);
    color: var(--color-primary);
    font-weight: var(--font-weight-bold);
  }

  &__meta {
    margin-top: 2px;
    font-size: var(--font-size-xs);
    color: var(--color-text-tertiary);
  }
}

.pg-view__detail-header {
  display: flex;
  align-items: center;
  gap: var(--spacing-2);
  flex-wrap: wrap;
}

.pg-view__detail-name {
  font-size: var(--font-size-lg);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
}

.pg-view__balance-chip {
  font-size: var(--font-size-sm);
  color: var(--color-primary);
}

.pg-view__modal-footer {
  display: flex;
  justify-content: flex-end;
  gap: var(--spacing-2);
}

.pg-view__allocate-hint {
  margin-top: var(--spacing-2);
  font-size: var(--font-size-xs);
  color: var(--color-text-tertiary);
  line-height: 1.5;
}

.pg-members__toolbar {
  display: flex;
  align-items: center;
  gap: var(--spacing-2);
  margin-bottom: var(--spacing-2);
}

.pg-members__hint {
  font-size: var(--font-size-xs);
  color: var(--color-text-tertiary);
}

.pg-outputs__filters {
  display: flex;
  align-items: center;
  gap: var(--spacing-2);
  margin-bottom: var(--spacing-2);
  flex-wrap: wrap;
}

.pg-outputs__member { width: 160px; }
.pg-outputs__kind { width: 120px; }
.pg-outputs__range { width: 240px; }
.pg-outputs__hint {
  display: block;
  font-size: var(--font-size-xs);
  color: var(--color-text-tertiary);
  margin-bottom: var(--spacing-2);
}

.pg-ledger__pos { color: var(--color-success, #63e2b7); }
.pg-ledger__neg { color: var(--color-error, #e88080); }
</style>
