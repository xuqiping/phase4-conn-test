<!--
  项目资产库·分享/成员管理弹窗  plan §S9 / 设计 §七 7.2 / L1
  - 成员表：owner 行角色锁定（NTag 所有者）+ 不可移除；成员行可改角色(VIEWER/EDITOR) + 移除 + 转让 owner
  - 邀请：多选用户（filterable，已成员过滤）+ 角色选择（默认 VIEWER）→ 逐个 invite
  - 移除成员（L1）：移除后列表即刻消失；移除自己=退出（owner 不可移除）
  - 转让 owner：二次确认 → projectApi.transfer → 旧 owner 降 editor（emit changed 触发父列表重载，项目可能从「我的」迁「共享」）
  - 逐操作即时调 API + reload，非批量保存（设计 §七 成员操作为离散 owner 操作）
  - MemberVO 无 username 字段，靠 adminApi.listUsers 建 userId→username 映射联显
-->
<template>
  <n-modal :show="show" preset="card" :title="`分享 · ${projectName}`" style="max-width:680px" @update:show="emit('update:show', $event)">
    <div class="share-dialog">
      <!-- 邀请栏 -->
      <div class="share-dialog__invite">
        <n-select
          v-model:value="selectedUserIds"
          class="share-dialog__user-select"
          :options="userOptions"
          :loading="loadingUsers"
          multiple
          filterable
          clearable
          placeholder="选择用户"
        />
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
import { adminApi, type UserVO } from '@/api/admin'
import type { MemberVO, ProjectRole } from '@/types/asset'

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

const users = ref<UserVO[]>([])
const members = ref<MemberVO[]>([])
const selectedUserIds = ref<number[]>([])
const inviteRole = ref<'VIEWER' | 'EDITOR'>('VIEWER')
const loadingUsers = ref(false)
const loadingMembers = ref(false)

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

const usernameMap = computed(() => {
  const m = new Map<number, string>()
  users.value.forEach((u) => m.set(u.id, u.username))
  return m
})

function displayName(userId: number): string {
  return usernameMap.value.get(userId) || `用户 ${userId}`
}

const rows = computed(() =>
  members.value.map((m) => ({
    userId: m.userId,
    username: displayName(m.userId),
    role: m.role,
    isOwner: m.isOwner
  }))
)

/** 候选用户 = 全量用户 - 已成员 */
const userOptions = computed<SelectOption[]>(() =>
  users.value
    .filter((u) => !members.value.some((m) => m.userId === u.id))
    .map((u) => ({ label: u.username, value: u.id }))
)

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
    if (show && props.projectId) void loadAll()
  },
  { immediate: true }
)

async function loadAll() {
  loadingUsers.value = true
  loadingMembers.value = true
  try {
    const [usersRes, membersRes] = await Promise.all([
      adminApi.listUsers(1, 200),
      memberApi.list(props.projectId)
    ])
    users.value = usersRes.data.data.records || []
    members.value = membersRes.data.data || []
  } catch {
    message.error('加载成员数据失败')
  } finally {
    loadingUsers.value = false
    loadingMembers.value = false
  }
}

async function reloadMembers() {
  loadingMembers.value = true
  try {
    const res = await memberApi.list(props.projectId)
    members.value = res.data.data || []
  } catch {
    message.error('刷新成员列表失败')
  } finally {
    loadingMembers.value = false
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

.share-dialog__user-select {
  flex: 1;
}

@media (max-width: 768px) {
  .share-dialog__invite {
    flex-wrap: wrap;
  }

  .share-dialog__user-select {
    flex: 1 1 100%;
  }
}
</style>
