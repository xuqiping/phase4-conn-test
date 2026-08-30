<!-- agent-platform/frontend/src/views/admin/security/BanManageView.vue
     封禁管理（11x 加固 P4-C12）：上=IP 封禁区（手动封/解 + 列表）；下=用户封号入口（跳用户管理）
     权限：security:ban:manage -->
<template>
  <div class="ban-manage-view">
    <!-- 雾中浮岛场景层（ART-DIR-0002R 方向二 admin 淡版，仅 ink 主题渲染） -->
    <ModuleScene scene="admin" lite />
    <PageHeader title="封禁管理" sub="IP 封禁与用户封号（封号在「用户管理」页操作）" />
    <n-card title="IP 封禁管理" size="small">
      <n-space class="ban-manage-view__bar" wrap>
        <n-input v-model:value="blockForm.ip" placeholder="IP 地址" style="width: 180px" />
        <n-input v-model:value="blockForm.reason" placeholder="封禁原因（必填）" style="width: 260px" />
        <n-checkbox v-model:checked="blockForm.permanent">永久</n-checkbox>
        <n-button type="error" :disabled="!blockForm.ip || !blockForm.reason" @click="doBlock">封禁 IP</n-button>
        <n-button @click="reload">刷新</n-button>
      </n-space>
      <n-data-table :columns="columns" :data="rows" :loading="loading" :pagination="{ pageSize: 20 }"
                    :row-key="(r: IpBlacklistVO) => r.ip" size="small" />
    </n-card>

    <n-card title="用户封号" size="small" style="margin-top: 12px">
      <n-alert type="info" :bordered="false">
        用户封号/解封在「用户管理」页操作：状态改为 BANNED/DISABLED/LOCKED 即时踢下线（删会话 + ban 标记），
        自动锁定（LOCKED）到期由系统每分钟自动解锁。
      </n-alert>
      <n-button style="margin-top: 8px" @click="$router.push('/admin/users')">前往用户管理</n-button>
    </n-card>
  </div>
</template>

<script setup lang="ts">
import { h, onMounted, ref } from 'vue'
import { NButton, NTag, useDialog, useMessage, type DataTableColumns } from 'naive-ui'
import { blockIp, listIpBlacklist, unblockIp, type IpBlacklistVO } from '@/api/security'
import PageHeader from '@/components/PageHeader.vue'
import ModuleScene from '@/components/ModuleScene.vue'

const message = useMessage()
const dialog = useDialog()

const loading = ref(false)
const rows = ref<IpBlacklistVO[]>([])
const blockForm = ref({ ip: '', reason: '', permanent: false })

const columns: DataTableColumns<IpBlacklistVO> = [
  { title: 'IP', key: 'ip', width: 150 },
  {
    title: '来源', key: 'source', width: 90,
    render: (r) => h(NTag, { size: 'small', type: r.source === 'AUTO' ? 'warning' : 'info' },
      { default: () => (r.source === 'AUTO' ? '自动' : '人工') }),
  },
  { title: '原因', key: 'reason', ellipsis: { tooltip: true } },
  {
    title: '到期', key: 'bannedUntil', width: 170,
    render: (r) => r.bannedUntil ?? '永久',
  },
  { title: '封禁人', key: 'createdBy', width: 100, render: (r) => r.createdBy ?? '-' },
  { title: '时间', key: 'createdAt', width: 160 },
  {
    title: '操作', key: 'ops', width: 90,
    render: (r) => h(NButton, {
      size: 'tiny', type: 'primary', secondary: true,
      onClick: () => confirmUnblock(r.ip),
    }, { default: () => '解封' }),
  },
]

async function reload() {
  loading.value = true
  try {
    const resp = await listIpBlacklist(1, 100)
    rows.value = resp.data.data.records
  } catch (e: any) {
    message.error(e?.response?.data?.msg ?? '加载失败')
  } finally {
    loading.value = false
  }
}

async function doBlock() {
  try {
    await blockIp(blockForm.value.ip.trim(), blockForm.value.reason.trim(), blockForm.value.permanent)
    message.success('已封禁')
    blockForm.value = { ip: '', reason: '', permanent: false }
    reload()
  } catch (e: any) {
    message.error(e?.response?.data?.msg ?? '封禁失败')
  }
}

function confirmUnblock(ip: string) {
  dialog.warning({
    title: '解封 IP',
    content: `确定解封 ${ip}？`,
    positiveText: '解封',
    negativeText: '取消',
    onPositiveClick: async () => {
      try {
        await unblockIp(ip)
        message.success('已解封')
        reload()
      } catch (e: any) {
        message.error(e?.response?.data?.msg ?? '解封失败')
      }
    },
  })
}

onMounted(reload)
</script>

<style scoped lang="scss">
.ban-manage-view {
  padding: 12px;
  &__bar {
    margin-bottom: 12px;
  }
}
</style>
