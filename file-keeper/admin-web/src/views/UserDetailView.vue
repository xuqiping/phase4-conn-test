<template>
  <div v-if="user">
    <n-page-header @back="$router.push({ name: 'users' })">
      <template #title>用户详情 #{{ user.id }}</template>
    </n-page-header>

    <n-grid :cols="1" :x-gap="16" :y-gap="16" style="margin-top: 16px">
      <n-gi>
        <n-card title="基本信息" size="small">
          <n-descriptions :column="2" label-placement="left">
            <n-descriptions-item label="邮箱">{{ user.email || '-' }}</n-descriptions-item>
            <n-descriptions-item label="手机">{{ user.phone || '-' }}</n-descriptions-item>
            <n-descriptions-item label="角色">{{ user.role }}</n-descriptions-item>
            <n-descriptions-item label="状态">
              <n-tag :type="(USER_STATUS_MAP[user.status]?.type || 'default') as any" size="small">
                {{ USER_STATUS_MAP[user.status]?.label || user.status }}
              </n-tag>
            </n-descriptions-item>
          </n-descriptions>
        </n-card>
      </n-gi>

      <n-gi>
        <n-card title="设备列表" size="small">
          <n-data-table :columns="deviceColumns" :data="devices" :loading="devicesLoading" size="small" />
        </n-card>
      </n-gi>
    </n-grid>
  </div>
</template>

<script setup lang="ts">
import { ref, h, onMounted, watch } from 'vue'
import {
  NPageHeader, NGrid, NGi, NCard, NDescriptions, NDescriptionsItem,
  NButton, NDataTable, NTag, useMessage, useDialog, type DataTableColumns
} from 'naive-ui'
import * as usersApi from '@/api/users'
import * as devicesApi from '@/api/devices'
import type { UserSummary, DeviceInfo } from '@/types'
import { USER_STATUS_MAP } from '@/types'

const props = defineProps<{ id: string }>()
const message = useMessage()
const dialog = useDialog()

const user = ref<UserSummary | null>(null)
const devices = ref<DeviceInfo[]>([])
const devicesLoading = ref(false)

const deviceColumns: DataTableColumns<DeviceInfo> = [
  { title: '设备ID', key: 'deviceId', ellipsis: { tooltip: true } },
  { title: '设备名', key: 'deviceName', ellipsis: { tooltip: true } },
  {
    title: '状态',
    key: 'status',
    width: 80,
    render: (row) => h(NTag, { type: row.status === 'active' ? 'success' : 'error', size: 'small' }, () => row.status)
  },
  {
    title: '时间异常次数',
    key: 'timeSyncAnomalyCount',
    width: 120,
    render: (row) => row.timeSyncAnomalyCount > 0
      ? h(NTag, { type: 'error', size: 'small' }, () => String(row.timeSyncAnomalyCount))
      : h('span', {}, '0')
  },
  {
    title: '最后活跃',
    key: 'lastSeenAt',
    render: (row) => row.lastSeenAt ? new Date(row.lastSeenAt).toLocaleString() : '-'
  },
  {
    title: '操作',
    key: 'actions',
    width: 80,
    render: (row) => row.status === 'active'
      ? h(NButton, { text: true, type: 'error', onClick: () => handleDisableDevice(row) }, () => '禁用')
      : null
  }
]

const userId = () => Number(props.id)

async function loadUser() {
  try {
    user.value = await usersApi.getUser(userId())
  } catch (err) {
    message.error(err instanceof Error ? err.message : '加载用户失败')
  }
}

async function loadDevices() {
  devicesLoading.value = true
  try {
    devices.value = await devicesApi.listDevices(userId())
  } catch (err) {
    message.error(err instanceof Error ? err.message : '加载设备失败')
  } finally {
    devicesLoading.value = false
  }
}

function handleDisableDevice(row: DeviceInfo) {
  dialog.warning({
    title: '确认禁用设备',
    content: `确定要禁用设备 "${row.deviceName || row.deviceId}" 吗？`,
    positiveText: '确定',
    negativeText: '取消',
    onPositiveClick: async () => {
      try {
        await devicesApi.disableDevice(userId(), row.deviceId, '管理员禁用')
        message.success('设备已禁用')
        loadDevices()
      } catch (err) {
        message.error(err instanceof Error ? err.message : '禁用设备失败')
      }
    }
  })
}

function loadDetail() {
  loadUser()
  loadDevices()
}

watch(() => props.id, loadDetail)
onMounted(loadDetail)
</script>
