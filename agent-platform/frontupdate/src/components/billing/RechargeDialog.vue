<template>
  <n-modal
    :show="show"
    preset="card"
    :title="stage === 'form' ? '积分充值' : '收银台'"
    style="width: 440px"
    :mask-closable="stage === 'form'"
    @update:show="onClose"
  >
    <!-- 阶段①：金额+渠道表单 -->
    <template v-if="stage === 'form'">
      <n-form label-placement="left" label-width="80">
        <n-form-item label="充值金额">
          <n-input-number
            v-model:value="amountYuan"
            :min="0.01"
            :max="99999.99"
            :precision="2"
            placeholder="¥0.01 ~ ¥99999.99"
            style="width: 100%"
          >
            <template #prefix>¥</template>
          </n-input-number>
        </n-form-item>
        <n-form-item label="支付渠道">
          <n-select v-model:value="channel" :options="channelOptions" placeholder="选择支付渠道" />
        </n-form-item>
      </n-form>
      <n-alert v-if="formError" type="error" :bordered="false" style="margin-bottom: 12px">
        {{ formError }}
        <!-- 12x B5：邮箱门槛被拒 → 给出直达安全设置的引导 -->
        <router-link
          v-if="emailGateBlocked"
          to="/settings"
          style="display: block; margin-top: 6px; color: inherit; text-decoration: underline"
          @click="onClose"
        >
          去「设置 → 安全设置」绑定邮箱 →
        </router-link>
      </n-alert>
      <n-space justify="end">
        <n-button @click="onClose">取消</n-button>
        <n-button type="primary" :loading="creating" :disabled="!canSubmit" @click="submit">
          去支付
        </n-button>
      </n-space>
    </template>

    <!-- 阶段②：收银台（mock 渠道=模拟按钮；真实渠道=扫码/跳转占位） -->
    <template v-else>
      <div class="cashier">
        <n-statistic label="应付金额" :value="order?.amountYuan ?? 0">
          <template #prefix>¥</template>
        </n-statistic>
        <div class="cashier__meta">
          订单号 #{{ order?.id }} · 可得 {{ order?.pointsGranted }} 积分
        </div>

        <template v-if="order?.channel === 'MOCK'">
          <n-alert type="info" :bordered="false" style="margin: 12px 0">
            模拟收银台（测试环境）：点击下方按钮模拟支付结果，走真实回调链路入账。
          </n-alert>
          <n-space justify="center">
            <n-button type="primary" :loading="mockPaying" @click="mockPay(true)">模拟支付成功</n-button>
            <n-button type="error" secondary :loading="mockPaying" @click="mockPay(false)">模拟支付失败</n-button>
          </n-space>
        </template>
        <template v-else>
          <n-alert type="info" :bordered="false" style="margin: 12px 0">
            请使用{{ channelLabel(order?.channel) }}完成支付，支付结果将自动入账（本页每 2 秒自动查询）。
          </n-alert>
        </template>

        <div class="cashier__status">
          <n-spin v-if="polling" size="small" />
          <span>{{ statusText }}</span>
        </div>
        <n-space justify="center" style="margin-top: 12px">
          <n-button size="small" quaternary @click="cancelOrder">取消订单</n-button>
        </n-space>
      </div>
    </template>
  </n-modal>
</template>

<script setup lang="ts">
import { uuid } from '@/utils/uuid'
import { computed, ref, watch } from 'vue'
import {
  NModal, NForm, NFormItem, NInputNumber, NSelect, NButton, NSpace, NAlert,
  NStatistic, NSpin
} from 'naive-ui'
import { billingApi, PAYMENT_CHANNEL_LABEL } from '@/api/billing'
import type { PaymentOrderVO } from '@/api/billing'

const props = defineProps<{
  show: boolean
  /** 可用渠道（父组件拉取；空数组时父组件不应打开本对话框） */
  channels: string[]
  /** mock 收银台是否可用（channels 含 MOCK 即可模拟） */
}>()

const emit = defineEmits<{
  (e: 'update:show', v: boolean): void
  /** 支付成功入账（父组件刷新余额/记录） */
  (e: 'paid', order: PaymentOrderVO): void
  /** 订单终结但非成功（FAILED/CLOSED/超时） */
  (e: 'settled', order: PaymentOrderVO | null): void
}>()

type Stage = 'form' | 'cashier'
const stage = ref<Stage>('form')
const amountYuan = ref<number | null>(100)
const channel = ref<string | null>(null)
const creating = ref(false)
const formError = ref('')
/** 12x B5：下单被「未验证邮箱」门槛拦截 → 错误框内给去绑定引导链接 */
const emailGateBlocked = ref(false)
const order = ref<PaymentOrderVO | null>(null)
const mockPaying = ref(false)
const polling = ref(false)
const statusText = ref('')

/** 表单会话幂等键：每次打开对话框重新生成——同次会话内双击/重试同键只下一单 */
let idemKey = ''
/** 轮询定时器 */
let pollTimer: ReturnType<typeof setInterval> | null = null
/** 已轮询次数（2s×30=60s 上限） */
let pollCount = 0
const POLL_MAX = 30

const channelOptions = computed(() =>
  props.channels.map(c => ({ label: PAYMENT_CHANNEL_LABEL[c] ?? c, value: c }))
)

const canSubmit = computed(() =>
  amountYuan.value != null && amountYuan.value >= 0.01 && amountYuan.value <= 99999.99 && !!channel.value
)

function channelLabel(c: string | null | undefined): string {
  return c ? (PAYMENT_CHANNEL_LABEL[c] ?? c) : ''
}

// 每次打开重置为表单阶段 + 新幂等键（vitest 断言点：idemKey 每会话唯一）
watch(() => props.show, v => {
  if (v) {
    stage.value = 'form'
    formError.value = ''
    emailGateBlocked.value = false
    order.value = null
    idemKey = uuid()
    if (!channel.value && props.channels.length > 0) {
      channel.value = props.channels[0]
    }
  } else {
    stopPoll()
  }
})

async function submit() {
  if (!canSubmit.value || creating.value) return
  creating.value = true
  formError.value = ''
  try {
    const res = await billingApi.createPaymentOrder({
      amountYuan: amountYuan.value!,
      channel: channel.value!,
      idemKey
    })
    order.value = res.data.data
    stage.value = 'cashier'
    startPoll()
  } catch (e: unknown) {
    // 错误文案映射（限额/渠道未开通/重复提交由后端 msg 直出，axios 层已 toast）
    formError.value = (e as { response?: { data?: { msg?: string } } })?.response?.data?.msg
      ?? '下单失败，请稍后重试'
    // 12x B5：后端邮箱门槛话术命中 → 展示去绑定引导
    emailGateBlocked.value = formError.value.includes('绑定并验证邮箱')
  } finally {
    creating.value = false
  }
}

/** mock 收银台：模拟成功/失败（后端走真实 handleNotify 链路，非直接改库）。 */
async function mockPay(success: boolean) {
  if (!order.value || mockPaying.value) return
  mockPaying.value = true
  try {
    await billingApi.mockTrigger({ orderId: order.value.id, success })
    if (!success) {
      statusText.value = '支付失败，订单已关闭'
      stopPoll()
      emit('settled', { ...order.value, status: 'FAILED' })
    }
    // success=true：等轮询确认 PAID（入账在回调事务内，下一次轮询即可见）
  } catch {
    statusText.value = '模拟支付请求失败，请重试'
  } finally {
    mockPaying.value = false
  }
}

function startPoll() {
  stopPoll()
  pollCount = 0
  polling.value = true
  statusText.value = '等待支付…'
  pollTimer = setInterval(pollOnce, 2000)
}

function stopPoll() {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
  polling.value = false
}

/** 单轮查单：PAID→成功收尾；FAILED/CLOSED→终结；超 30 次→超时收尾。 */
async function pollOnce() {
  if (!order.value) return
  pollCount++
  try {
    const res = await billingApi.getPaymentOrder(order.value.id)
    const o = res.data.data
    order.value = o
    if (o.status === 'PAID') {
      stopPoll()
      statusText.value = '支付成功，积分已入账'
      emit('paid', o)
      return
    }
    if (o.status === 'FAILED' || o.status === 'CLOSED') {
      stopPoll()
      statusText.value = o.status === 'FAILED' ? '支付失败' : '订单已关闭'
      emit('settled', o)
      return
    }
    if (pollCount >= POLL_MAX) {
      stopPoll()
      statusText.value = '支付结果确认超时——若已付款，积分将在对账后到账'
      emit('settled', o)
    }
  } catch {
    if (pollCount >= POLL_MAX) {
      stopPoll()
      statusText.value = '查询超时，请稍后在充值记录中核对'
      emit('settled', null)
    }
  }
}

async function cancelOrder() {
  if (!order.value) return
  try {
    await billingApi.cancelPaymentOrder(order.value.id)
  } catch {
    // 已付/已关单由后端 409 提示；本地照常收尾
  }
  stopPoll()
  emit('settled', order.value)
  onClose()
}

function onClose() {
  stopPoll()
  emit('update:show', false)
}

// 测试可驱动入口（vitest：绕开表单组件 stub 直接推进状态机）
defineExpose({ amountYuan, channel, submit, mockPay, cancelOrder })
</script>

<style lang="scss" scoped>
.cashier {
  text-align: center;

  &__meta {
    margin-top: 4px;
    font-size: var(--font-size-xs);
    color: var(--color-text-tertiary);
  }

  &__status {
    display: flex;
    align-items: center;
    justify-content: center;
    gap: 8px;
    margin-top: 16px;
    font-size: var(--font-size-sm);
    color: var(--color-text-secondary);
  }
}
</style>
