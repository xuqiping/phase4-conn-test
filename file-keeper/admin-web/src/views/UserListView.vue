<template>
  <div>
    <n-space justify="space-between" align="center" style="margin-bottom: 16px">
      <n-h2 style="margin: 0">用户管理</n-h2>
      <n-space>
        <n-select
          v-model:value="statusFilter"
          :options="statusOptions"
          placeholder="全部状态"
          clearable
          style="width: 140px"
          @update:value="handleSearch"
        />
      </n-space>
    </n-space>

    <n-data-table
      :columns="columns"
      :data="users"
      :loading="loading"
      :pagination="pagination"
      :row-key="(row: UserSummary) => row.id"
      @update:page="handlePageChange"
      @update:page-size="handlePageSizeChange"
    />
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, h, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import {
  NDataTable, NSpace, NH2, NSelect, NButton, NTag, useMessage, useDialog,
  type DataTableColumns
} from 'naive-ui'
import * as usersApi from '@/api/users'
import type { UserSummary } from '@/types'
import { USER_STATUS_MAP } from '@/types'

const router = useRouter()
const message = useMessage()
const dialog = useDialog()

const loading = ref(false)
const users = ref<UserSummary[]>([])
const statusFilter = ref<string | null>(null)

const pagination = reactive({ page: 1, pageSize: 20, itemCount: 0, showSizePicker: true, pageSizes: [10, 20, 50] })

const statusOptions = [
  { label: '正常', value: 'active' },
  { label: '已禁用', value: 'disabled' }
]

const columns: DataTableColumns<UserSummary> = [
  { title: 'ID', key: 'id', width: 60 },
  { title: '邮箱', key: 'email', ellipsis: { tooltip: true } },
  { title: '手机', key: 'phone', ellipsis: { tooltip: true } },
  { title: '角色', key: 'role', width: 100 },
  {
    title: '状态', key: 'status', width: 90,
    render: (row) => {
      const info = USER_STATUS_MAP[row.status] || { label: row.status, type: 'default' as const }
      return h(NTag, { type: info.type, size: 'small' }, () => info.label)
    }
  },
  {
    title: '操作', key: 'actions', width: 200,
    render: (row) =>
      h(NSpace, { size: 'small' }, () => [
        h(NButton, { text: true, type: 'primary', onClick: () => router.push({ name: 'user-detail', params: { id: row.id } }) }, () => '详情'),
        row.status === 'active'
          ? h(NButton, { text: true, type: 'warning', onClick: () => handleAction(row.id, 'disable') }, () => '禁用')
          : null,
        row.status === 'disabled'
          ? h(NButton, { text: true, type: 'info', onClick: () => handleAction(row.id, 'enable') }, () => '启用')
          : null
      ].filter(Boolean))
  }
]

async function loadUsers() {
  loading.value = true
  try {
    const res = await usersApi.listUsers({
      page: pagination.page,
      size: pagination.pageSize,
      status: statusFilter.value || undefined
    })
    users.value = res.records
    pagination.itemCount = res.total
  } catch (err) {
    message.error(err instanceof Error ? err.message : '加载用户列表失败')
  } finally {
    loading.value = false
  }
}

function handlePageChange(page: number) { pagination.page = page; loadUsers() }
function handlePageSizeChange(size: number) { pagination.pageSize = size; pagination.page = 1; loadUsers() }
function handleSearch() { pagination.page = 1; loadUsers() }

function handleAction(id: number, action: 'disable' | 'enable') {
  const actionLabel = { disable: '禁用', enable: '启用' }[action]
  dialog.warning({
    title: '确认操作',
    content: `确定要${actionLabel}该用户吗？`,
    positiveText: '确定',
    negativeText: '取消',
    onPositiveClick: async () => {
      try {
        const fn = { disable: usersApi.disableUser, enable: usersApi.enableUser }[action]
        await fn(id, `管理员${actionLabel}`)
        message.success('操作成功')
        loadUsers()
      } catch (err) {
        message.error(err instanceof Error ? err.message : '操作失败')
      }
    }
  })
}

onMounted(loadUsers)
</script>
