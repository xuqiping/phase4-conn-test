<template>
  <div class="billing-settings">
    <n-form label-placement="left" label-width="160" class="billing-settings__form">
      <n-form-item label="低余额阈值">
        <n-input-number
          v-model:value="lowBalanceThreshold"
          :min="0"
          :max="1000000"
          class="billing-settings__input"
        />
        <span class="billing-settings__unit">积分</span>
      </n-form-item>
      <p class="billing-settings__hint">
        余额低于该阈值的用户禁止多任务并行（对话/视频生成），防止欠费用户并发刷量
      </p>
      <n-form-item label="低余额最大在途任务数">
        <n-input-number
          v-model:value="lowBalanceMaxInflight"
          :min="1"
          :max="100"
          class="billing-settings__input"
        />
        <span class="billing-settings__unit">个</span>
      </n-form-item>
      <n-button type="primary" :loading="saving" @click="handleSave">保存</n-button>
    </n-form>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { NButton, NForm, NFormItem, NInputNumber, useMessage } from 'naive-ui'
import { systemApi } from '@/api/system'

const message = useMessage()
const saving = ref(false)
const lowBalanceThreshold = ref(100)
const lowBalanceMaxInflight = ref(1)

onMounted(load)

async function load() {
  const res = await systemApi.getBillingSettings()
  lowBalanceThreshold.value = res.data.data.lowBalanceThreshold ?? 100
  lowBalanceMaxInflight.value = res.data.data.lowBalanceMaxInflight ?? 1
}

async function handleSave() {
  saving.value = true
  try {
    await systemApi.updateBillingSettings({
      lowBalanceThreshold: lowBalanceThreshold.value,
      lowBalanceMaxInflight: lowBalanceMaxInflight.value
    })
    message.success('计费设置已更新，立即生效')
  } finally {
    saving.value = false
  }
}
</script>

<style lang="scss" scoped>
.billing-settings__form {
  max-width: 520px;
}

.billing-settings__input {
  width: 160px;
}

.billing-settings__unit {
  margin-left: 8px;
  color: var(--color-text-secondary);
}

.billing-settings__hint {
  margin: -8px 0 16px 160px;
  font-size: 12px;
  color: var(--color-text-secondary);
}
</style>
