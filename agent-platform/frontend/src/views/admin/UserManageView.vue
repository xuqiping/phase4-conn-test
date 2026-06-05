<template>
  <div class="user-manage">
    <div class="user-manage__header">
      <h2>用户管理</h2>
    </div>

    <!-- 用户表格 -->
    <n-data-table
      :columns="columns"
      :data="users"
      :loading="loading"
      :pagination="pagination"
      @update:page="loadUsers"
      striped
    />

    <!-- 角色分配弹窗 -->
    <n-modal v-model:show="showRoleModal" preset="card" title="分配角色" style="max-width:440px">
      <p style="margin-bottom:12px;color:var(--color-text-secondary)">
        用户：<strong>{{ editingUser?.username }}</strong>
      </p>
      <n-checkbox-group v-model:value="selectedRoleIds">
        <n-space vertical>
          <n-checkbox v-for="role in allRoles" :key="role.id" :value="role.id" :label="`${role.name} (${role.code})`" />
        </n-space>
      </n-checkbox-group>
      <template #action>
        <n-button @click="showRoleModal = false">取消</n-button>
        <n-button type="primary" :loading="saving" @click="saveRoles">保存</n-button>
      </template>
    </n-modal>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted, h } from 'vue'
import {
  NDataTable, NButton, NModal, NCheckboxGroup, NCheckbox, NSpace, NTag, useMessage
} from 'naive-ui'
import type { DataTableColumns } from 'naive-ui'
import { adminApi, type UserVO, type Role } from '@/api/admin'

const message = useMessage()
const loading = ref(false)
const saving = ref(false)
const users = ref<UserVO[]>([])
const allRoles = ref<Role[]>([])
const showRoleModal = ref(false)
const editingUser = ref<UserVO | null>(null)
const selectedRoleIds = ref<number[]>([])

const pagination = reactive({ page: 1, pageSize: 10, itemCount: 0 })

const statusMap: Record<string, { type: 'success' | 'warning' | 'error'; label: string }> = {
  ACTIVE: { type: 'success', label: '正常' },
  DISABLED: { type: 'warning', label: '禁用' },
  LOCKED: { type: 'error', label: '锁定' }
}

const columns: DataTableColumns<UserVO> = [
  { title: 'ID', key: 'id', width: 60 },
  { title: '用户名', key: 'username', width: 120 },
  { title: '邮箱', key: 'email', width: 180, ellipsis: { tooltip: true } },
  {
    title: '状态', key: 'status', width: 80,
    render: (row) => h(NTag, { size: 'small', type: statusMap[row.status]?.type || 'default', round: true }, () => statusMap[row.status]?.label || row.status)
  },
  {
    title: '角色', key: 'roles', width: 200,
    render: (row) => h(NSpace, { size: 4 }, () => row.roles.map(r => h(NTag, { size: 'small', bordered: false }, () => r)))
  },
  {
    title: '最后登录', key: 'lastLoginAt', width: 160,
    render: (row) => row.lastLoginAt ? new Date(row.lastLoginAt).toLocaleString('zh-CN') : '-'
  },
  {
    title: '操作', key: 'actions', width: 200, fixed: 'right',
    render: (row) => h(NSpace, { size: 8 }, () => [
      h(NButton, { size: 'small', onClick: () => openRoleModal(row) }, () => '分配角色'),
      row.status === 'ACTIVE'
        ? h(NButton, { size: 'small', type: 'warning', onClick: () => toggleStatus(row, 'DISABLED') }, () => '禁用')
        : h(NButton, { size: 'small', type: 'success', onClick: () => toggleStatus(row, 'ACTIVE') }, () => '启用')
    ])
  }
]

async function loadUsers(page = 1) {
  loading.value = true
  try {
    const res = await adminApi.listUsers(page, pagination.pageSize)
    users.value = res.data.data.records
    pagination.itemCount = res.data.data.total
    pagination.page = page
  } catch {
    message.error('加载用户列表失败')
  } finally {
    loading.value = false
  }
}

async function loadRoles() {
  try {
    const res = await adminApi.listAllRoles()
    allRoles.value = res.data.data
  } catch {
    message.error('加载角色列表失败')
  }
}

function openRoleModal(user: UserVO) {
  editingUser.value = user
  // 把当前角色code转为id
  selectedRoleIds.value = allRoles.value
    .filter(r => user.roles.includes(r.code))
    .map(r => r.id)
  showRoleModal.value = true
}

async function saveRoles() {
  if (!editingUser.value) return
  saving.value = true
  try {
    await adminApi.assignRoles(editingUser.value.id, selectedRoleIds.value)
    message.success('角色分配成功')
    showRoleModal.value = false
    await loadUsers(pagination.page)
  } catch {
    message.error('角色分配失败')
  } finally {
    saving.value = false
  }
}

async function toggleStatus(user: UserVO, status: string) {
  try {
    await adminApi.updateUserStatus(user.id, status)
    message.success('状态更新成功')
    await loadUsers(pagination.page)
  } catch {
    message.error('状态更新失败')
  }
}

onMounted(() => {
  loadUsers()
  loadRoles()
})
</script>

<style lang="scss" scoped>
.user-manage {
  padding: var(--spacing-6);

  &__header {
    margin-bottom: var(--spacing-4);
    h2 {
      margin: 0;
      color: var(--color-text-primary);
    }
  }
}
</style>
