<template>
  <n-modal
    :show="show"
    preset="card"
    title="Agent 使用授权"
    style="max-width: 760px"
    @update:show="emit('update:show', $event)"
  >
    <div class="agent-permission-modal">
      <div class="agent-permission-modal__toolbar">
        <n-select
          v-model:value="selectedUserIds"
          class="agent-permission-modal__user-select"
          :options="userOptions"
          :loading="loadingUsers"
          multiple
          filterable
          clearable
          placeholder="选择一个或多个用户"
        />
        <n-button type="primary" :disabled="selectedUserIds.length === 0" @click="addPermission">添加授权</n-button>
      </div>

      <n-data-table
        :columns="columns"
        :data="rows"
        :loading="loadingPermissions"
        :pagination="false"
        :scroll-x="600"
        size="small"
      />

      <n-empty v-if="!loadingPermissions && rows.length === 0" description="暂无授权用户" />
    </div>

    <template #action>
      <n-space justify="end">
        <n-button @click="emit('update:show', false)">取消</n-button>
        <n-button type="primary" :loading="saving" @click="save">保存</n-button>
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
  NSwitch,
  NTag,
  useMessage
} from 'naive-ui'
import type { DataTableColumns, SelectOption } from 'naive-ui'
import { agentApi, type AgentPermission, type AgentPermissionSaveRequest } from '@/api/agent'
import { adminApi, type UserVO } from '@/api/admin'

interface PermissionRow {
  targetUserId: number
  targetUsername: string
  canUse: boolean
  canReadPrompt: boolean
  canCopy: boolean
}

const props = defineProps<{
  show: boolean
  agentId: number
}>()

const emit = defineEmits<{
  (event: 'update:show', value: boolean): void
  (event: 'saved'): void
}>()

const message = useMessage()
const users = ref<UserVO[]>([])
const rows = ref<PermissionRow[]>([])
const selectedUserIds = ref<number[]>([])
const loadingUsers = ref(false)
const loadingPermissions = ref(false)
const saving = ref(false)

const userOptions = computed<SelectOption[]>(() =>
  users.value
    .filter(user => !rows.value.some(row => row.targetUserId === user.id))
    .map(user => ({
      label: user.username,
      value: user.id
    }))
)

const columns: DataTableColumns<PermissionRow> = [
  {
    title: '用户',
    key: 'targetUsername',
    render: row => h(NTag, { size: 'small', bordered: false }, () => row.targetUsername || `用户 ${row.targetUserId}`)
  },
  {
    title: '使用权限',
    key: 'canUse',
    width: 120,
    render: row => h(NSwitch, {
      value: row.canUse,
      'onUpdate:value': (value: boolean) => {
        row.canUse = value
        if (!value) {
          row.canReadPrompt = false
          row.canCopy = false
        }
      }
    })
  },
  {
    title: '可读提示词',
    key: 'canReadPrompt',
    width: 130,
    render: row => h(NSwitch, {
      value: row.canReadPrompt,
      'onUpdate:value': (value: boolean) => {
        row.canReadPrompt = value
        if (value) row.canUse = true
      }
    })
  },
  {
    title: '可复制',
    key: 'canCopy',
    width: 120,
    render: row => h(NSwitch, {
      value: row.canCopy,
      'onUpdate:value': (value: boolean) => {
        row.canCopy = value
        if (value) row.canUse = true
      }
    })
  },
  {
    title: '操作',
    key: 'actions',
    width: 90,
    render: row => h(NButton, {
      size: 'small',
      quaternary: true,
      type: 'error',
      onClick: () => removePermission(row.targetUserId)
    }, () => '移除')
  }
]

watch(
  () => props.show,
  (show) => {
    if (show) {
      void loadData()
    }
  },
  { immediate: true }
)

async function loadData() {
  await Promise.all([loadUsers(), loadPermissions()])
}

async function loadUsers() {
  loadingUsers.value = true
  try {
    const res = await adminApi.listUsers(1, 100)
    users.value = res.data.data.records || []
  } catch {
    message.error('加载用户列表失败')
  } finally {
    loadingUsers.value = false
  }
}

async function loadPermissions() {
  loadingPermissions.value = true
  try {
    const res = await agentApi.listAgentPermissions(props.agentId)
    rows.value = (res.data.data || []).map(toRow)
  } catch {
    message.error('加载授权列表失败')
  } finally {
    loadingPermissions.value = false
  }
}

function toRow(permission: AgentPermission): PermissionRow {
  return {
    targetUserId: permission.userId,
    targetUsername: permission.username || `用户 ${permission.userId}`,
    canUse: permission.canUse,
    canReadPrompt: permission.canReadPrompt,
    canCopy: permission.canCopy
  }
}

function addPermission() {
  const nextUserIds = selectedUserIds.value.filter(
    userId => !rows.value.some(row => row.targetUserId === userId)
  )
  if (nextUserIds.length === 0) {
    selectedUserIds.value = []
    return
  }
  rows.value.push(...nextUserIds.map(userId => {
    const user = users.value.find(item => item.id === userId)
    return {
      targetUserId: userId,
      targetUsername: user?.username || `用户 ${userId}`,
      canUse: true,
      canReadPrompt: false,
      canCopy: false
    }
  }))
  selectedUserIds.value = []
}

function removePermission(userId: number) {
  rows.value = rows.value.filter(row => row.targetUserId !== userId)
}

async function save() {
  saving.value = true
  try {
    const payload: AgentPermissionSaveRequest[] = rows.value.map(row => ({
      userId: row.targetUserId,
      canUse: row.canUse || row.canReadPrompt || row.canCopy,
      canReadPrompt: row.canReadPrompt,
      canCopy: row.canCopy
    }))
    await agentApi.saveAgentPermissions(props.agentId, payload)
    message.success('授权已保存')
    emit('saved')
    emit('update:show', false)
  } catch {
    message.error('保存授权失败')
  } finally {
    saving.value = false
  }
}

defineExpose({
  selectedUserIds,
  rows,
  addPermission,
  save
})
</script>

<style lang="scss" scoped>
.agent-permission-modal {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-3);
}

.agent-permission-modal__toolbar {
  display: flex;
  gap: var(--spacing-2);
  align-items: center;
}

.agent-permission-modal__user-select {
  flex: 1;
}

@media (max-width: 768px) {
  .agent-permission-modal__toolbar {
    flex-wrap: wrap;
  }
}
</style>
