<template>
  <div class="my-wallet">
    <n-card title="我的钱包" class="my-wallet__balance">
      <template #header-extra>
        <!-- 7x#3：有可用渠道才显充值入口（渠道全未开通=商户接入中） -->
        <n-button v-if="payChannels.length > 0" type="primary" size="small" @click="showRecharge = true">
          充值
        </n-button>
      </template>
      <n-statistic label="当前积分余额" :value="wallet?.balance ?? 0">
        <template #suffix>积分</template>
      </n-statistic>
      <n-space style="margin-top: 12px">
        <n-tag :type="balanceTagType">{{ balanceHint }}</n-tag>
        <!-- B5（Q10=A）：欠款警示——充值自动冲抵，还清前消费全拦 -->
        <n-tag v-if="(wallet?.debtPoints ?? 0) > 0" type="error">
          未偿还欠款 {{ fmtNum(wallet?.debtPoints) }} 积分：充值将自动偿还，还清前暂停消费
        </n-tag>
      </n-space>
    </n-card>

    <!-- 7x#3：充值记录（六字段 + 累计条） -->
    <n-card title="充值记录" style="margin-top: 16px">
      <div class="my-wallet__recharge-summary">
        累计充值 <b>{{ fmtNum(rechargeSummary?.totalPaidAmount) }}</b> 元，
        共 <b>{{ fmtNum(rechargeSummary?.totalPaidPoints) }}</b> 积分
      </div>
      <n-data-table
        remote
        :columns="rechargeColumns"
        :data="recharges"
        :loading="rechargeLoading"
        :pagination="rechargePagination"
        :bordered="false"
        size="small"
        @update:page="loadRecharges"
      />
    </n-card>

    <recharge-dialog
      v-model:show="showRecharge"
      :channels="payChannels"
      @paid="onPaid"
      @settled="onSettled"
    />

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
import { computed, h, onMounted, reactive, ref, watch } from 'vue'
import { NCard, NDataTable, NSelect, NStatistic, NTag, NSpace, NButton, useMessage } from 'naive-ui'
import type { DataTableColumns, PaginationProps } from 'naive-ui'
import {
  billingApi, LEDGER_TYPE_LABEL, KIND_LABEL, KIND_TAG_TYPE,
  PAYMENT_STATUS_LABEL, PAYMENT_STATUS_TAG_TYPE, PAYMENT_CHANNEL_LABEL
} from '@/api/billing'
import type {
  LedgerItemVO, UserUsageVO, UserWalletVO, BillingKind,
  RechargeRecordVO, RechargePageVO, PaymentOrderVO
} from '@/api/billing'
import { projectGroupApi } from '@/api/projectGroup'
import { useProjectGroupStore } from '@/stores/projectGroup'
import RechargeDialog from '@/components/billing/RechargeDialog.vue'

const message = useMessage()

const wallet = ref<UserWalletVO | null>(null)
const usage = ref<UserUsageVO[]>([])
const loading = ref(false)
// 计划5 Step8：组筛选（我的组；mine 失败容错→空下拉=不可筛，行仍全量）
const groupFilter = ref<number | null>(null)
const groupOptions = ref<{ label: string; value: number }[]>([])

// ---------- 7x#3：自助充值 ----------
const showRecharge = ref(false)
const payChannels = ref<string[]>([])
const recharges = ref<RechargeRecordVO[]>([])
const rechargeSummary = ref<Pick<RechargePageVO, 'totalPaidAmount' | 'totalPaidPoints'> | null>(null)
const rechargeLoading = ref(false)
const rechargePagination = reactive<PaginationProps>({ page: 1, pageSize: 10, itemCount: 0 })

const balanceTagType = computed<'success' | 'warning' | 'error'>(() => {
  const b = wallet.value?.balance ?? 0
  if (b > 100) return 'success'
  if (b > 0) return 'warning'
  return 'error'
})
const balanceHint = computed(() => {
  const b = wallet.value?.balance ?? 0
  if (b <= 0) return '余额不足，点击右上角「充值」自助充值'
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

/** 7x#1 充值记录六字段（时间/渠道/付款账号/金额/积分/充值后余额）+ 状态。 */
const rechargeColumns: DataTableColumns<RechargeRecordVO> = [
  { title: '时间', key: 'createdAt', width: 165, render: r => fmt(r.createdAt) },
  { title: '渠道', key: 'channel', width: 90, render: r => PAYMENT_CHANNEL_LABEL[r.channel] ?? r.channel },
  { title: '付款账号', key: 'payerAccount', render: r => r.payerAccount || '—' },
  { title: '金额 ¥', key: 'amountYuan', width: 90, render: r => fmtNum(r.amountYuan) },
  { title: '积分', key: 'pointsGranted', width: 90, render: r => fmtNum(r.pointsGranted) },
  { title: '充值后余额', key: 'balanceAfter', width: 100, render: r => fmtNum(r.balanceAfter) },
  {
    title: '状态', key: 'status', width: 90,
    render: r => h(NTag, {
      size: 'small', round: true,
      type: PAYMENT_STATUS_TAG_TYPE[r.status] ?? 'default'
    }, { default: () => PAYMENT_STATUS_LABEL[r.status] ?? r.status })
  }
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

/** 7x#3：可用支付渠道（空=隐藏充值按钮；接口失败按无渠道处理）。 */
async function loadPayChannels() {
  try {
    const res = await billingApi.paymentChannels()
    payChannels.value = res.data.data ?? []
  } catch {
    payChannels.value = []
  }
}

/** 7x#1：充值记录分页（含累计条）。 */
async function loadRecharges(page = 1) {
  rechargeLoading.value = true
  try {
    const res = await billingApi.myRecharges({ page, size: rechargePagination.pageSize ?? 10 })
    const data = res.data.data
    recharges.value = data?.page.records ?? []
    rechargePagination.itemCount = data?.page.total ?? 0
    rechargePagination.page = page
    rechargeSummary.value = data
      ? { totalPaidAmount: data.totalPaidAmount, totalPaidPoints: data.totalPaidPoints }
      : null
  } catch {
    recharges.value = []
    rechargePagination.itemCount = 0
  } finally {
    rechargeLoading.value = false
  }
}

/** 支付成功：刷新余额+流水+充值记录，关对话框。 */
function onPaid(_order: PaymentOrderVO) {
  showRecharge.value = false
  message.success('支付成功，积分已入账')
  load()
  loadRecharges(1)
}

/** 非成功终结（失败/关闭/超时）：记录仍刷新（PENDING/FAILED/CLOSED 行可见）。 */
function onSettled(_order: PaymentOrderVO | null) {
  loadRecharges(1)
}

watch(groupFilter, () => load())

onMounted(() => {
  load()
  loadGroupOptions()
  loadPayChannels()
  loadRecharges(1)
})

// 计划 E5（7x-3）：积分事件驱动刷新——徽标已由 store 秒级更新，此处刷新余额/流水列表；
// 防抖 1s 合并 HOLD+结算连发的事件风暴；页签不可见跳过（回前台手动切查询，或下次事件触发）
{
  const pgStore = useProjectGroupStore()
  let refreshTimer: ReturnType<typeof setTimeout> | null = null
  watch(() => pgStore.lastEvent, evt => {
    if (!evt || document.visibilityState !== 'visible') return
    if (refreshTimer) clearTimeout(refreshTimer)
    refreshTimer = setTimeout(() => { void load() }, 1000)
  })
}
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

  &__recharge-summary {
    margin-bottom: var(--spacing-2);
    font-size: var(--font-size-sm);
    color: var(--color-text-secondary);
  }
}
</style>
