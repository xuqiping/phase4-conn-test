<template>
  <div>
    <n-space justify="space-between" align="center" style="margin-bottom: 16px">
      <n-h2 style="margin: 0">匿名设备运营</n-h2>
      <n-space>
        <n-input
          v-model:value="ipFilter"
          placeholder="按 IP 筛选"
          clearable
          style="width: 160px"
          @update:value="handleSearch"
        />
        <n-select
          v-model:value="statusFilter"
          :options="statusOptions"
          placeholder="全部状态"
          clearable
          style="width: 140px"
          @update:value="handleSearch"
        />
        <n-select
          v-model:value="abnormalFilter"
          :options="abnormalOptions"
          placeholder="全部设备"
          clearable
          style="width: 160px"
          @update:value="handleSearch"
        />
        <n-button @click="showIpAbuse">
          IP 滥用统计
        </n-button>
      </n-space>
    </n-space>

    <n-data-table
      :columns="columns"
      :data="devices"
      :loading="loading"
      :pagination="pagination"
      :row-key="(row: AnonymousDevice) => row.deviceId"
      @update:page="handlePageChange"
      @update:page-size="handlePageSizeChange"
    />

    <n-modal
      v-model:show="ipAbuseVisible"
      title="同一 IP 多设备统计"
      preset="card"
      style="width: 500px"
    >
      <n-data-table
        :columns="ipAbuseColumns"
        :data="ipAbuseData"
        :loading="ipAbuseLoading"
        :pagination="false"
        :row-key="(row: IpDeviceCount) => row.firstSeenIp"
      />
    </n-modal>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, h, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import {
  NDataTable, NSpace, NH2, NSelect, NButton, NTag, NInput, NModal,
  useMessage, useDialog, type DataTableColumns
} from 'naive-ui'
import * as anonymousDevicesApi from '@/api/anonymousDevices'
import type { AnonymousDevice, IpDeviceCount } from '@/types'
import { ANONYMOUS_DEVICE_STATUS_MAP, MODULE_LABEL_MAP } from '@/types'

const route = useRoute()
const message = useMessage()
const dialog = useDialog()

const loading = ref(false)
const devices = ref<AnonymousDevice[]>([])
const statusFilter = ref<string | null>(null)
const ipFilter = ref<string | null>(null)
const abnormalFilter = ref<string | null>(null)

const ipAbuseVisible = ref(false)
const ipAbuseLoading = ref(false)
const ipAbuseData = ref<IpDeviceCount[]>([])

const pagination = reactive({ page: 1, pageSize: 20, itemCount: 0, showSizePicker: true, pageSizes: [10, 20, 50] })

const statusOptions = [
  { label: '正常', value: 'active' },
  { label: '已禁用', value: 'disabled' }
]

const abnormalOptions = [
  { label: '高频重置 (≥2)', value: 'high_reset' },
  { label: '高频重置 (≥3)', value: 'very_high_reset' }
]

function formatDate(value: string | null): string {
  if (!value) return '-'
  return new Date(value).toLocaleString()
}

function minResetCountForFilter(): number | undefined {
  if (abnormalFilter.value === 'high_reset') return 2
  if (abnormalFilter.value === 'very_high_reset') return 3
  return undefined
}

function renderResetCountTag(count: number) {
  if (count >= 3) return h(NTag, { type: 'error', size: 'small' }, () => String(count))
  if (count >= 1) return h(NTag, { type: 'warning', size: 'small' }, () => String(count))
  return h('span', {}, count)
}

const columns: DataTableColumns<AnonymousDevice> = [
  { title: 'ID', key: 'id', width: 70 },
  { title: '设备ID', key: 'deviceId', ellipsis: { tooltip: true }, width: 220 },
  { title: '设备名称', key: 'deviceName', ellipsis: { tooltip: true } },
  {
    title: '状态', key: 'status', width: 90,
    render: (row) => {
      const info = ANONYMOUS_DEVICE_STATUS_MAP[row.status] || { label: row.status, type: 'default' as const }
      return h(NTag, { type: info.type, size: 'small' }, () => info.label)
    }
  },
  { title: '来源 IP', key: 'firstSeenIp', width: 130, ellipsis: { tooltip: true } },
  {
    title: '重置次数', key: 'trialResetCount', width: 100,
    render: (row) => renderResetCountTag(row.trialResetCount)
  },
  {
    title: '试用到期', key: 'trialExpiresAt', width: 170,
    render: (row) => formatDate(row.trialExpiresAt)
  },
  {
    title: '免费模块', key: 'freeModuleCode', width: 100,
    render: (row) => row.freeModuleCode ? MODULE_LABEL_MAP[row.freeModuleCode] || row.freeModuleCode : '-'
  },
  {
    title: '最后活跃', key: 'lastSeenAt', width: 170,
    render: (row) => formatDate(row.lastSeenAt)
  },
  {
    title: '操作', key: 'actions', width: 220,
    render: (row) =>
      h(NSpace, { size: 'small' }, () => [
        h(NButton, {
          text: true,
          type: 'primary',
          disabled: row.trialResetCount >= 3,
          onClick: () => handleAction(row.deviceId, 'reset')
        }, () => '重置试用'),
        row.status === 'active'
          ? h(NButton, { text: true, type: 'warning', onClick: () => handleAction(row.deviceId, 'disable') }, () => '禁用')
          : null,
        row.status === 'disabled'
          ? h(NButton, { text: true, type: 'info', onClick: () => handleAction(row.deviceId, 'enable') }, () => '启用')
          : null
      ].filter(Boolean))
  }
]

const ipAbuseColumns: DataTableColumns<IpDeviceCount> = [
  { title: '来源 IP', key: 'firstSeenIp', ellipsis: { tooltip: true } },
  { title: '设备数', key: 'deviceCount', width: 100 },
  {
    title: '操作', key: 'actions', width: 120,
    render: (row) => h(NButton, {
      text: true,
      type: 'primary',
      onClick: () => { ipFilter.value = row.firstSeenIp; ipAbuseVisible.value = false; handleSearch() }
    }, () => '查看设备')
  }
]

async function loadDevices() {
  loading.value = true
  try {
    const res = await anonymousDevicesApi.listAnonymousDevices({
      page: pagination.page,
      size: pagination.pageSize,
      status: statusFilter.value || undefined,
      minResetCount: minResetCountForFilter(),
      firstSeenIp: ipFilter.value || undefined
    })
    devices.value = res.records
    pagination.itemCount = res.total
  } catch (err) {
    message.error(err instanceof Error ? err.message : '加载匿名设备列表失败')
  } finally {
    loading.value = false
  }
}

function handlePageChange(page: number) { pagination.page = page; loadDevices() }
function handlePageSizeChange(size: number) { pagination.pageSize = size; pagination.page = 1; loadDevices() }
function handleSearch() { pagination.page = 1; loadDevices() }

async function showIpAbuse() {
  ipAbuseVisible.value = true
  ipAbuseLoading.value = true
  try {
    ipAbuseData.value = await anonymousDevicesApi.getIpAbuse(2)
  } catch (err) {
    message.error(err instanceof Error ? err.message : '加载 IP 滥用统计失败')
  } finally {
    ipAbuseLoading.value = false
  }
}

function handleAction(deviceId: string, action: 'reset' | 'disable' | 'enable') {
  const actionLabel = { reset: '重置试用', disable: '禁用', enable: '启用' }[action]
  dialog.warning({
    title: '确认操作',
    content: `确定要${actionLabel}该设备吗？`,
    positiveText: '确定',
    negativeText: '取消',
    onPositiveClick: async () => {
      try {
        const fn = {
          reset: anonymousDevicesApi.resetTrial,
          disable: anonymousDevicesApi.disableAnonymousDevice,
          enable: anonymousDevicesApi.enableAnonymousDevice
        }[action]
        await fn(deviceId)
        message.success('操作成功')
        loadDevices()
      } catch (err) {
        message.error(err instanceof Error ? err.message : '操作失败')
      }
    }
  })
}

onMounted(() => {
  const q = route.query.status
  if (q && typeof q === 'string') {
    statusFilter.value = q
  }
  loadDevices()
})
</script>
