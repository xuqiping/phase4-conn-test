<template>
  <div class="wallet-admin">
    <n-card title="积分充值 / 发放">
      <div v-if="!canRecharge" class="wallet-admin__noperm"><n-empty description="无 points:recharge 权限" /></div>
      <n-form v-else ref="formRef" :model="form" :rules="rules" label-placement="left" :label-width="100" style="max-width: 480px">
        <n-form-item label="用户 ID" path="userId">
          <n-input-number v-model:value="form.userId" :precision="0" placeholder="充值目标用户 id" style="width: 100%" />
        </n-form-item>
        <n-form-item label="到账积分" path="points">
          <n-input-number v-model:value="form.points" :precision="2" :min="0.01" placeholder="正数（直接到账，不走阶梯折算）" style="width: 100%" />
        </n-form-item>
        <n-form-item label="备注">
          <n-input v-model:value="form.remark" type="textarea" :autosize="{ minRows: 2 }" placeholder="落流水 remark，可空" />
        </n-form-item>
        <n-form-item :label="' '">
          <n-button type="primary" :loading="saving" @click="submit">充值</n-button>
        </n-form-item>
      </n-form>

      <n-alert v-if="lastResult" type="success" title="充值成功" style="max-width: 480px; margin-top: 12px">
        用户 {{ lastResult.userId }} 当前余额：{{ Number(lastResult.balanceAfter).toFixed(2) }} 积分
      </n-alert>

      <p class="wallet-admin__hint">
        MVP 为管理员直接填写到账积分（建 payment_order PAID + 余额涨 + 流水 ADMIN_GRANT，三者同事务）；
        用户余额总表 / 对账查询随 Phase4 补全。
      </p>
    </n-card>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { NCard, NForm, NFormItem, NInputNumber, NInput, NButton, NAlert, NEmpty, useMessage } from 'naive-ui'
import type { FormInst, FormRules } from 'naive-ui'
import { billingApi } from '@/api/billing'
import { useAuthStore } from '@/stores/auth'

const authStore = useAuthStore()
const message = useMessage()
const canRecharge = computed(() => authStore.hasPermission('points:recharge'))

const formRef = ref<FormInst | null>(null)
const form = reactive<{ userId: number | null; points: number | null; remark: string | null }>({
  userId: null, points: null, remark: null
})
const rules: FormRules = {
  userId: { required: true, type: 'number', message: '请填用户 ID', trigger: 'blur' },
  points: { required: true, type: 'number', message: '请填到账积分', trigger: 'blur' }
}
const saving = ref(false)
const lastResult = ref<{ userId: number; balanceAfter: number } | null>(null)
// SEC-FR-121：每开一轮表单生成一把幂等键——双击/网络重试同键只到账一次；成功后换键开新一笔
const idemKey = ref<string>(crypto.randomUUID())

async function submit() {
  try {
    await formRef.value?.validate()
  } catch {
    return
  }
  saving.value = true
  try {
    const res = await billingApi.recharge({
      userId: form.userId!,
      points: form.points!,
      remark: form.remark || undefined,
      idempotencyKey: idemKey.value
    })
    lastResult.value = res.data.data
    message.success('充值成功')
    form.points = null
    form.remark = null
    idemKey.value = crypto.randomUUID()
  } finally {
    saving.value = false
  }
}
</script>

<style lang="scss" scoped>
.wallet-admin {
  padding: var(--spacing-4);
  max-width: 720px;
}
.wallet-admin__noperm {
  padding: var(--spacing-5) 0;
}
.wallet-admin__hint {
  margin-top: 16px;
  color: var(--color-text-secondary);
  font-size: var(--font-size-sm);
  max-width: 480px;
}
</style>
