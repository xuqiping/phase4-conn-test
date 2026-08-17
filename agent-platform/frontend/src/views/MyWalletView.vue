<template>
  <div class="my-wallet">
    <n-card title="我的钱包" class="my-wallet__balance">
      <n-statistic label="当前积分余额" :value="wallet?.balance ?? 0">
        <template #suffix>积分</template>
      </n-statistic>
      <n-space style="margin-top: 12px">
        <n-tag :type="balanceTagType">{{ balanceHint }}</n-tag>
      </n-space>
    </n-card>

    <n-card title="最近流水" style="margin-top: 16px">
      <n-data-table
        :columns="ledgerColumns"
        :data="wallet?.recentLedger ?? []"
        :loading="loading"
        :bordered="false"
        size="small"
      />
    </n-card>

    <n-card title="积分消耗明细" style="margin-top: 16px">
      <!-- 计划5 Step8：组筛选（我的组下拉，空=全部含个人行；行加组名列） -->
      <div class="my-wallet__usage-filter">
        <n-select
          v-model:value="groupFilter"
          :options="groupOptions"
          placeholder="全部（个人+项目组）"
          clearable
          size="small"
          style="width: 220px"
        />
        <span class="my-wallet__usage-hint">个人行「项目组」列显「—」；组池消耗不占个人余额</span>
      </div>
      <n-data-table
        :columns="usageColumns"
        :data="usage"
        :loading="loading"
        :bordered="false"
        size="small"
      />
    </n-card>
  </div>
</template>

<script setup lang="ts">
import { computed, h, onMounted, ref, watch } from 'vue'
import { NCard, NDataTable, NSelect, NStatistic, NTag, NSpace } from 'naive-ui'
import type { DataTableColumns } from 'naive-ui'
import { billingApi, LEDGER_TYPE_LABEL, KIND_LABEL, KIND_TAG_TYPE } from '@/api/billing'
import type { LedgerItemVO, UserUsageVO, UserWalletVO, BillingKind } from '@/api/billing'
import { projectGroupApi } from '@/api/projectGroup'

const wallet = ref<UserWalletVO | null>(null)
const usage = ref<UserUsageVO[]>([])
const loading = ref(false)
// 计划5 Step8：组筛选（我的组；mine 失败容错→空下拉=不可筛，行仍全量）
const groupFilter = ref<number | null>(null)
const groupOptions = ref<{ label: string; value: number }[]>([])

const balanceTagType = computed<'success' | 'warning' | 'error'>(() => {
  const b = wallet.value?.balance ?? 0
  if (b > 100) return 'success'
  if (b > 0) return 'warning'
  return 'error'
})
const balanceHint = computed(() => {
  const b = wallet.value?.balance ?? 0
  if (b <= 0) return '余额不足，请联系管理员充值'
  if (b <= 100) return '余额较低，建议尽快充值'
  return '余额充足'
})

const ledgerColumns: DataTableColumns<LedgerItemVO> = [
  { title: '时间', key: 'createdAt', render: r => fmt(r.createdAt) },
  { title: '类型', key: 'type', render: r => LEDGER_TYPE_LABEL[r.type] ?? r.type },
  { title: '积分变动', key: 'deltaPoints', render: r => fmtDelta(r.deltaPoints) },
  { title: '变动后余额', key: 'balanceAfter', render: r => fmtNum(r.balanceAfter) },
  { title: '备注', key: 'remark' }
]

const usageColumns: DataTableColumns<UserUsageVO> = [
  { title: '时间', key: 'createdAt', render: r => fmt(r.createdAt) },
  { title: '模型', key: 'model' },
  { title: '类型', key: 'kind', render: r => h(NTag, { type: KIND_TAG_TYPE[r.kind as BillingKind] ?? 'default', size: 'small' }, { default: () => KIND_LABEL[r.kind as BillingKind] ?? r.kind }) },
  // 计划5 Step8：项目组列（组池消耗显组名，个人=「—」）
  { title: '项目组', key: 'projectGroupName', render: r => r.projectGroupName || '—' },
  { title: '消耗积分', key: 'pointsConsumed', render: r => fmtNum(r.pointsConsumed) },
  { title: '状态', key: 'status' }
]

function fmt(iso: string): string {
  return new Date(iso).toLocaleString('zh-CN', { hour12: false })
}
function fmtNum(n: number | null | undefined): string {
  return n == null ? '—' : Number(n).toFixed(2)
}
function fmtDelta(n: number): string {
  const s = Number(n).toFixed(2)
  return Number(n) >= 0 ? `+${s}` : s
}

async function load() {
  loading.value = true
  try {
    // 计划5 Step8：组筛选非空 → me/usage 带 projectGroupId（只看我在该组的消耗行）
    const [w, u] = await Promise.all([
      billingApi.myWallet(),
      billingApi.myUsage(groupFilter.value != null ? { projectGroupId: groupFilter.value } : {})
    ])
    wallet.value = w.data.data
    usage.value = u.data.data ?? []
  } finally {
    loading.value = false
  }
}

/** 组筛选选项：mine()=我建的+我在的（403/无权限容错→空下拉）。 */
async function loadGroupOptions() {
  try {
    const res = await projectGroupApi.mine()
    groupOptions.value = (res.data.data ?? []).map(g => ({ label: g.name, value: g.id }))
  } catch {
    groupOptions.value = []
  }
}

watch(groupFilter, () => load())

onMounted(() => {
  load()
  loadGroupOptions()
})
</script>

<style lang="scss" scoped>
.my-wallet {
  padding: var(--spacing-4);
  max-width: 960px;
  margin: 0 auto;

  &__usage-filter {
    display: flex;
    align-items: center;
    gap: var(--spacing-2);
    margin-bottom: var(--spacing-2);
  }

  &__usage-hint {
    font-size: var(--font-size-xs);
    color: var(--color-text-tertiary);
  }
}
</style>
