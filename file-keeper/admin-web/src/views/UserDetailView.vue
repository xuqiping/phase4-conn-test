<template>
  <div v-if="user">
    <n-page-header @back="$router.push({ name: 'users' })">
      <template #title>用户详情 #{{ user.id }}</template>
    </n-page-header>

    <n-grid :cols="1" :x-gap="16" :y-gap="16" style="margin-top: 16px">
      <!-- 基本信息 -->
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

          <n-divider />

          <n-space align="center">
            <span>设备上限：</span>
            <n-input-number v-model:value="settingsForm.deviceLimit" :min="1" size="small" style="width: 100px" />
            <span>离线缓存(分)：</span>
            <n-input-number v-model:value="settingsForm.offlineCacheMinutes" :min="0" size="small" style="width: 100px" />
            <n-button type="primary" size="small" :loading="settingsLoading" @click="handleSaveSettings">保存设置</n-button>
          </n-space>
        </n-card>
      </n-gi>

      <!-- 权益管理 -->
      <n-gi>
        <n-card title="模块权益" size="small">
          <template #header-extra>
            <n-button size="small" @click="showGrantModal = true">授予权益</n-button>
          </template>
          <n-data-table :columns="entitlementColumns" :data="entitlements" :loading="entitlementsLoading" size="small" />
        </n-card>
      </n-gi>

      <!-- 设备管理 -->
      <n-gi>
        <n-card title="设备列表" size="small">
          <n-data-table :columns="deviceColumns" :data="devices" :loading="devicesLoading" size="small" />
        </n-card>
      </n-gi>
    </n-grid>

    <!-- 授予权益弹窗 -->
    <n-modal v-model:show="showGrantModal" preset="dialog" title="授予模块权益" positive-text="授予" negative-text="取消"
      @positive-click="handleGrant">
      <n-space vertical>
        <n-select v-model:value="grantForm.moduleCode" :options="moduleOptions" placeholder="选择模块" />
        <n-date-picker v-model:value="grantForm.expiresTs" type="datetime" clearable placeholder="过期时间（可选，留空永不过期）" style="width: 100%" />
      </n-space>
    </n-modal>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, h, onMounted, watch } from 'vue'
import {
  NPageHeader, NGrid, NGi, NCard, NDescriptions, NDescriptionsItem, NDivider, NSpace,
  NInputNumber, NButton, NDataTable, NTag, NModal, NSelect, NDatePicker, useMessage, useDialog,
  type DataTableColumns
} from 'naive-ui'
import * as usersApi from '@/api/users'
import * as entitlementsApi from '@/api/entitlements'
import * as devicesApi from '@/api/devices'
import type { UserSummary, ModuleEntitlement, DeviceInfo, ModuleCode } from '@/types'
import { USER_STATUS_MAP, MODULE_LABEL_MAP } from '@/types'

const props = defineProps<{ id: string }>()
const message = useMessage()
const dialog = useDialog()

const user = ref<UserSummary | null>(null)
const entitlements = ref<ModuleEntitlement[]>([])
const devices = ref<DeviceInfo[]>([])
const entitlementsLoading = ref(false)
const devicesLoading = ref(false)
const settingsLoading = ref(false)

const settingsForm = reactive({ deviceLimit: 1, offlineCacheMinutes: 0 })
const showGrantModal = ref(false)
const grantForm = reactive<{ moduleCode: ModuleCode | null; expiresTs: number | null }>({ moduleCode: null, expiresTs: null })

const moduleOptions = [
  { label: '文件管理 (files)', value: 'files' },
  { label: '进程管理 (processes)', value: 'processes' },
  { label: '剪贴板 (clipboard)', value: 'clipboard' }
]

const entitlementColumns: DataTableColumns<ModuleEntitlement> = [
  { title: '模块', key: 'moduleCode', render: (row) => MODULE_LABEL_MAP[row.moduleCode] || row.moduleCode },
  { title: '已启用', key: 'enabled', width: 80, render: (row) => h(NTag, { type: row.enabled ? 'success' : 'error', size: 'small' }, () => row.enabled ? '是' : '否') },
  { title: '过期时间', key: 'expiresAt', render: (row) => row.expiresAt ? new Date(row.expiresAt).toLocaleString() : '永不过期' },
  {
    title: '操作', key: 'actions', width: 160,
    render: (row) => h(NSpace, { size: 'small' }, () => [
      h(NButton, { text: true, type: row.enabled ? 'warning' : 'success', onClick: () => handleToggleEntitlement(row) }, () => row.enabled ? '禁用' : '启用'),
      h(NButton, { text: true, type: 'error', onClick: () => handleRevokeEntitlement(row) }, () => '撤销')
    ])
  }
]

const deviceColumns: DataTableColumns<DeviceInfo> = [
  { title: '设备ID', key: 'deviceId', ellipsis: { tooltip: true } },
  { title: '设备名', key: 'deviceName', ellipsis: { tooltip: true } },
  { title: '状态', key: 'status', width: 80, render: (row) => h(NTag, { type: row.status === 'active' ? 'success' : 'error', size: 'small' }, () => row.status) },
  { title: '最后活跃', key: 'lastSeenAt', render: (row) => row.lastSeenAt ? new Date(row.lastSeenAt).toLocaleString() : '-' },
  {
    title: '操作', key: 'actions', width: 80,
    render: (row) => row.status === 'active'
      ? h(NButton, { text: true, type: 'error', onClick: () => handleDisableDevice(row) }, () => '禁用')
      : null
  }
]

const userId = () => Number(props.id)

async function loadUser() {
  try {
    user.value = await usersApi.getUser(userId())
    settingsForm.deviceLimit = user.value.deviceLimit
    settingsForm.offlineCacheMinutes = user.value.offlineCacheMinutes
  } catch (err) {
    message.error(err instanceof Error ? err.message : '加载用户失败')
  }
}

async function loadEntitlements() {
  entitlementsLoading.value = true
  try {
    entitlements.value = await entitlementsApi.listEntitlements(userId())
  } catch (err) {
    message.error(err instanceof Error ? err.message : '加载权益失败')
  } finally {
    entitlementsLoading.value = false
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

async function handleSaveSettings() {
  settingsLoading.value = true
  try {
    user.value = await usersApi.updateUserSettings(userId(), settingsForm.deviceLimit, settingsForm.offlineCacheMinutes)
    message.success('设置已保存')
  } catch (err) {
    message.error(err instanceof Error ? err.message : '保存设置失败')
  } finally {
    settingsLoading.value = false
  }
}

async function handleGrant() {
  if (!grantForm.moduleCode) { message.warning('请选择模块'); return false }
  try {
    const expiresAt = grantForm.expiresTs ? new Date(grantForm.expiresTs).toISOString() : null
    await entitlementsApi.grantEntitlement(userId(), grantForm.moduleCode, expiresAt)
    message.success('权益已授予')
    grantForm.moduleCode = null
    grantForm.expiresTs = null
    loadEntitlements()
    return true
  } catch (err) {
    message.error(err instanceof Error ? err.message : '授予权益失败')
    return false
  }
}

function handleToggleEntitlement(row: ModuleEntitlement) {
  dialog.warning({
    title: '确认操作',
    content: `确定要${row.enabled ? '禁用' : '启用'}模块 ${MODULE_LABEL_MAP[row.moduleCode] || row.moduleCode} 吗？`,
    positiveText: '确定',
    negativeText: '取消',
    onPositiveClick: async () => {
      try {
        await entitlementsApi.updateEntitlement(userId(), row.id, { enabled: !row.enabled })
        message.success('操作成功')
        loadEntitlements()
      } catch (err) {
        message.error(err instanceof Error ? err.message : '操作失败')
      }
    }
  })
}

function handleRevokeEntitlement(row: ModuleEntitlement) {
  dialog.error({
    title: '确认撤销',
    content: `确定要撤销模块 ${MODULE_LABEL_MAP[row.moduleCode] || row.moduleCode} 的权益吗？此操作不可恢复。`,
    positiveText: '撤销',
    negativeText: '取消',
    onPositiveClick: async () => {
      try {
        await entitlementsApi.revokeEntitlement(userId(), row.id)
        message.success('权益已撤销')
        loadEntitlements()
      } catch (err) {
        message.error(err instanceof Error ? err.message : '撤销失败')
      }
    }
  })
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

watch(() => props.id, () => { loadUser(); loadEntitlements(); loadDevices() })
onMounted(() => { loadUser(); loadEntitlements(); loadDevices() })
</script>
