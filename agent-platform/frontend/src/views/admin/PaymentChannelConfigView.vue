<!-- ============================================================
  支付渠道配置（admin，7x 追加）— /admin/payment-channels，payment:config
  · 密钥整体 AES 落库；本页只见脱敏尾巴（****尾4位），永不回显明文
  · 留空=保持原值（merge）；保存二次确认
  · ⚠️ 配置齐全 ≠ 渠道上线：SDK 实现未接入前，充值页面对用户仍只显 mock（骨架期保护）
  ============================================================ -->
<template>
  <div class="pay-channel-config">
    <n-alert type="info" :bordered="false" style="margin-bottom: 12px">
      密钥保存后整体加密存储，本页永远只显示尾巴 4 位；留空的字段保持原值不变。
      <b>配置齐全不代表渠道已上线</b>——支付实现接入完成前，用户侧不会出现该渠道。
    </n-alert>

    <n-card v-for="ch in channels" :key="ch.channel" style="margin-bottom: 12px">
      <template #header>
        <n-space align="center">
          <span>{{ PAYMENT_CHANNEL_LABEL[ch.channel] ?? ch.channel }}</span>
          <n-tag :type="ch.configured ? 'success' : 'default'" size="small" round>
            {{ ch.configured ? '已配置' : '未配置' }}
          </n-tag>
          <span v-if="ch.updatedAt" class="pay-channel-config__updated">更新于 {{ fmt(ch.updatedAt) }}</span>
        </n-space>
      </template>

      <n-form label-placement="left" label-width="220">
        <n-form-item v-for="f in PAYMENT_CHANNEL_FIELDS[ch.channel]" :key="f.key" :label="f.label">
          <n-input
            v-model:value="forms[ch.channel][f.key]"
            :type="f.secret ? 'password' : 'text'"
            :placeholder="ch.tails[f.key] ? `已配置 ${ch.tails[f.key]}（留空保持不变）` : '未配置'"
            show-password-on="click"
            autocomplete="off"
          />
        </n-form-item>
      </n-form>

      <n-space justify="end">
        <n-button
          type="primary"
          size="small"
          :loading="saving === ch.channel"
          :disabled="!hasAnyInput(ch.channel)"
          @click="confirmSave(ch.channel)"
        >保存{{ ch.configured ? '变更' : '配置' }}</n-button>
      </n-space>
    </n-card>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { NAlert, NButton, NCard, NForm, NFormItem, NInput, NSpace, NTag, useDialog, useMessage } from 'naive-ui'
import {
  billingApi, PAYMENT_CHANNEL_FIELDS, PAYMENT_CHANNEL_LABEL, type PaymentChannelConfigVO
} from '@/api/billing'

const message = useMessage()
const dialog = useDialog()

const channels = ref<PaymentChannelConfigVO[]>([])
const saving = ref<string | null>(null)
/** 每渠道表单输入（只收集用户本轮敲入的非空值）。 */
const forms = reactive<Record<string, Record<string, string>>>({ ALIPAY: {}, WECHAT: {} })

function fmt(iso: string): string {
  return new Date(iso).toLocaleString('zh-CN', { hour12: false })
}

function hasAnyInput(channel: string): boolean {
  return Object.values(forms[channel] ?? {}).some(v => v.trim() !== '')
}

async function load() {
  try {
    const res = await billingApi.adminPaymentChannels()
    channels.value = res.data.data
  } catch {
    channels.value = []
  }
}

/** 组装 payload：仅非空字段（后端 merge 语义，空=保持原值）。 */
function buildPayload(channel: string): Record<string, string> {
  const out: Record<string, string> = {}
  for (const [k, v] of Object.entries(forms[channel] ?? {})) {
    if (v.trim() !== '') out[k] = v.trim()
  }
  return out
}

function confirmSave(channel: string) {
  const payload = buildPayload(channel)
  const keys = Object.keys(payload)
  if (keys.length === 0) return
  dialog.warning({
    title: '确认保存渠道密钥',
    content: `将更新 ${PAYMENT_CHANNEL_LABEL[channel]} 的 ${keys.length} 个字段（${keys.join('、')}）。密钥整体加密存储，操作记入审计。确认？`,
    positiveText: '确认保存',
    negativeText: '取消',
    onPositiveClick: () => save(channel, payload)
  })
}

async function save(channel: string, payload: Record<string, string>) {
  if (saving.value) return
  saving.value = channel
  try {
    await billingApi.savePaymentChannelConfig(channel, payload)
    message.success('已保存（加密存储）')
    forms[channel] = {}
    await load()
  } catch (e: unknown) {
    message.error((e as Error)?.message || '保存失败')
  } finally {
    saving.value = null
  }
}

onMounted(load)

// 测试探针
defineExpose({ channels, forms, hasAnyInput, buildPayload, confirmSave, save, load })
</script>

<style lang="scss" scoped>
.pay-channel-config {
  padding: var(--spacing-4);
  max-width: 860px;
  margin: 0 auto;

  &__updated {
    font-size: 12px;
    color: var(--color-text-secondary);
  }
}
</style>
