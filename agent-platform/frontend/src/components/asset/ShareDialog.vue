<!--
  项目资产库·分享/成员管理弹窗  plan §S9 / 设计 §七 7.2 / L1
  - 成员表：owner 行角色锁定（NTag 所有者）+ 不可移除；成员行可改角色(VIEWER/EDITOR) + 移除 + 转让 owner
  - 邀请：资产域远程搜索最小候选（不读取管理员全量用户）+ 角色选择（默认 VIEWER）→ 逐个 invite
  - 移除成员（L1）：移除后列表即刻消失；移除自己=退出（owner 不可移除）
  - 转让 owner：二次确认 → projectApi.transfer → 旧 owner 降 editor（emit changed 触发父列表重载，项目可能从「我的」迁「共享」）
  - 逐操作即时调 API + reload，非批量保存（设计 §七 成员操作为离散 owner 操作）
  - MemberVO 直接返回 username；成员加载与候选搜索错误互不影响
-->
<template>
  <n-modal :show="show" preset="card" :title="`分享 · ${projectName}`" style="max-width:680px" @update:show="emit('update:show', $event)">
    <div class="share-dialog">
      <!-- 邀请栏 -->
      <div class="share-dialog__invite">
        <div class="share-dialog__candidate-search">
          <n-select
            v-model:value="selectedUserIds"
            class="share-dialog__user-select"
            :options="candidateOptions"
            :loading="loadingCandidates"
            multiple
            remote
            filterable
            clearable
            aria-label="搜索可邀请的项目成员"
            placeholder="输入用户名搜索"
            @search="searchCandidates"
          />
          <div
            class="share-dialog__candidate-status"
            :class="{ 'share-dialog__candidate-status--error': candidateError }"
            role="status"
          >
            {{ candidateStatusText }}
          </div>
        </div>
        <n-select
          v-model:value="inviteRole"
          class="share-dialog__role-select"
          :options="MEMBER_ROLE_OPTIONS"
          style="width:130px"
        />
        <n-button type="primary" :disabled="selectedUserIds.length === 0" @click="inviteSelected">
          邀请
        </n-button>
      </div>

      <!-- 成员表 -->
      <div v-if="memberError" class="share-dialog__member-error" role="alert">{{ memberError }}</div>
      <n-data-table
        :columns="columns"
        :data="rows"
        :loading="loadingMembers"
        :pagination="false"
        :scroll-x="520"
        size="small"
      />
      <n-empty v-if="!loadingMembers && rows.length === 0" description="暂无成员" />
    </div>

    <template #action>
      <n-space justify="end">
        <n-button type="primary" @click="emit('update:show', false)">完成</n-button>
      </n-space>
    </template>
  </n-modal>
</template>

<script setup lang="ts">
import { computed, h, ref, watch } from 'vue'
import {
  NButton,
  NDataTable,
  NEmpty,
  NModal,
  NSelect,
  NSpace,
  NTag,
  useMessage,
  useDialog
} from 'naive-ui'
import type { DataTableColumns, SelectOption } from 'naive-ui'
import { memberApi, projectApi } from '@/api/assets'
import type { MemberCandidateVO, MemberVO, ProjectRole } from '@/types/asset'

const props = defineProps<{
  show: boolean
  projectId: number
  projectName: string
}>()

const emit = defineEmits<{
  (e: 'update:show', v: boolean): void
  /** 任何成员变更（邀请/改角色/移除/转让）→ 父列表重载（角色/归属可能变） */
  (e: 'changed'): void
}>()

const message = useMessage()
const dialog = useDialog()

const members = ref<MemberVO[]>([])
const candidates = ref<MemberCandidateVO[]>([])
const selectedUserIds = ref<number[]>([])
const inviteRole = ref<'VIEWER' | 'EDITOR'>('VIEWER')
const loadingMembers = ref(false)
const loadingCandidates = ref(false)
const memberError = ref('')
const candidateError = ref('')
const candidateKeyword = ref('')
let candidateSearchVersion = 0

/** 角色 label/type（owner 锁定；成员可选 VIEWER/EDITOR，设计 §七 7.2） */
const ROLE_LABEL: Record<ProjectRole, string> = { OWNER: '所有者', EDITOR: '编辑者', VIEWER: '浏览者' }
const ROLE_TYPE: Record<ProjectRole, 'success' | 'info' | 'default'> = {
  OWNER: 'success',
  EDITOR: 'info',
  VIEWER: 'default'
}
const MEMBER_ROLE_OPTIONS: SelectOption[] = [
  { label: '浏览者', value: 'VIEWER' },
  { label: '编辑者', value: 'EDITOR' }
]

const rows = computed(() =>
  members.value.map((m) => ({
    userId: m.userId,
    username: m.username,
    role: m.role,
    isOwner: m.isOwner
  }))
)

const candidateOptions = computed<SelectOption[]>(() =>
  candidates.value.map((candidate) => ({ label: candidate.username, value: candidate.id }))
)

const candidateStatusText = computed(() => {
  if (candidateError.value) return candidateError.value
  if (!candidateKeyword.value) return '输入用户名搜索候选成员'
  if (!loadingCandidates.value && candidateOptions.value.length === 0) return '未找到匹配的候选成员'
  return ''
})

const columns = computed<DataTableColumns<(typeof rows.value)[number]>>(() => [
  {
    title: '用户',
    key: 'username',
    render: (row) => h(NTag, { size: 'small', bordered: false }, () => row.username)
  },
  {
    title: '角色',
    key: 'role',
    width: 160,
    render: (row) =>
      row.isOwner
        ? h(NTag, { size: 'small', bordered: false, type: ROLE_TYPE.OWNER }, () => ROLE_LABEL.OWNER)
        : h(
            NSelect,
            {
              value: row.role,
              size: 'small',
              options: MEMBER_ROLE_OPTIONS,
              'onUpdate:value': (v: 'VIEWER' | 'EDITOR') => changeRole(row.userId, v)
            }
          )
  },
  {
    title: '操作',
    key: 'actions',
    width: 180,
    render: (row) => {
      if (row.isOwner) return h('span', { style: 'color: var(--color-text-tertiary)' }, '—')
      return h(NSpace, { size: 4 }, () => [
        h(
          NButton,
          {
            size: 'small',
            quaternary: true,
            type: 'error',
            onClick: () => confirmRemove(row.userId, row.username)
          },
          () => '移除'
        ),
        h(
          NButton,
          {
            size: 'small',
            quaternary: true,
            onClick: () => confirmTransfer(row.userId, row.username)
          },
          () => '转让所有者'
        )
      ])
    }
  }
])

watch(
  () => props.show,
  (show) => {
    if (show && props.projectId) {
      clearCandidateSearch()
      void reloadMembers()
    }
  },
  { immediate: true }
)

async function reloadMembers() {
  loadingMembers.value = true
  memberError.value = ''
  try {
    const res = await memberApi.list(props.projectId)
    members.value = res.data.data || []
  } catch {
    memberError.value = '成员列表加载失败，请重试'
    message.error('刷新成员列表失败')
  } finally {
    loadingMembers.value = false
  }
}

function clearCandidateSearch() {
  candidateSearchVersion += 1
  candidateKeyword.value = ''
  candidates.value = []
  candidateError.value = ''
  loadingCandidates.value = false
}

/** 空关键词本地清空；非空关键词只从资产成员候选端点取最小字段。 */
async function searchCandidates(rawKeyword: string) {
  const keyword = rawKeyword.trim()
  candidateKeyword.value = keyword
  candidateError.value = ''
  const searchVersion = ++candidateSearchVersion
  if (!keyword) {
    candidates.value = []
    loadingCandidates.value = false
    return
  }

  loadingCandidates.value = true
  try {
    const res = await memberApi.searchCandidates(props.projectId, keyword)
    if (searchVersion !== candidateSearchVersion) return
    candidates.value = (res.data.data || []).map(({ id, username }) => ({ id, username }))
  } catch {
    if (searchVersion !== candidateSearchVersion) return
    candidates.value = []
    candidateError.value = '候选成员搜索失败，请重试'
  } finally {
    if (searchVersion === candidateSearchVersion) loadingCandidates.value = false
  }
}

/** 邀请选中用户（逐个 invite；空角色默认 VIEWER） */
async function inviteSelected() {
  const ids = [...selectedUserIds.value]
  if (ids.length === 0) return
  try {
    for (const userId of ids) {
      await memberApi.invite(props.projectId, { userId, role: inviteRole.value })
    }
    message.success(`已邀请 ${ids.length} 位用户`)
    selectedUserIds.value = []
    clearCandidateSearch()
    await reloadMembers()
    emit('changed')
  } catch {
    message.error('邀请失败')
  }
}

async function changeRole(userId: number, role: 'VIEWER' | 'EDITOR') {
  try {
    await memberApi.changeRole(props.projectId, userId, { role })
    message.success('角色已更新')
    await reloadMembers()
    emit('changed')
  } catch {
    message.error('更新角色失败')
  }
}

function confirmRemove(userId: number, name: string) {
  dialog.warning({
    title: '确认移除成员',
    content: `确定将「${name}」移出本项目？其将立即失去访问权（L1）。`,
    positiveText: '移除',
    negativeText: '取消',
    onPositiveClick: async () => {
      try {
        await memberApi.remove(props.projectId, userId)
        message.success('已移除')
        await reloadMembers()
        emit('changed')
      } catch {
        message.error('移除失败')
      }
    }
  })
}

function confirmTransfer(toUserId: number, name: string) {
  dialog.warning({
    title: '确认转让所有者',
    content: `将本项目所有者转让给「${name}」？你将降级为编辑者。此操作不可撤销。`,
    positiveText: '转让',
    negativeText: '取消',
    onPositiveClick: async () => {
      try {
        await projectApi.transfer(props.projectId, { toUserId })
        message.success('已转让所有者')
        await reloadMembers()
        emit('changed')
        emit('update:show', false)
      } catch {
        message.error('转让失败')
      }
    }
  })
}

defineExpose({
  rows,
  selectedUserIds,
  inviteRole,
  candidateKeyword,
  candidateOptions,
  searchCandidates,
  inviteSelected,
  changeRole,
  confirmRemove,
  confirmTransfer,
  reloadMembers
})
</script>

<style lang="scss" scoped>
.share-dialog {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-3);
}

.share-dialog__invite {
  display: flex;
  gap: var(--spacing-2);
  align-items: center;
}

.share-dialog__candidate-search {
  flex: 1;
  min-width: 0;
}

.share-dialog__user-select {
  width: 100%;
}

.share-dialog__candidate-status,
.share-dialog__member-error {
  margin-top: var(--spacing-1);
  color: var(--color-text-tertiary);
  font-size: 12px;
}

.share-dialog__candidate-status--error,
.share-dialog__member-error {
  color: var(--color-error);
}

@media (max-width: 768px) {
  .share-dialog__invite {
    flex-wrap: wrap;
  }

  .share-dialog__candidate-search {
    flex: 1 1 100%;
  }
}
</style>
