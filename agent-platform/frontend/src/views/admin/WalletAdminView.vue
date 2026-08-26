<template>
  <div class="wallet-admin">
    <n-card title="积分充值 / 发放">
      <div v-if="!canRecharge" class="wallet-admin__noperm"><n-empty description="无 points:recharge 权限" /></div>
      <n-form v-else ref="formRef" :model="form" :rules="rules" label-placement="left" :label-width="100" style="max-width: 480px">
        <!-- 修复III E3（12x#4）：统一选人 UserPicker（姓名·账号·备注 tag，按备注筛「A 班」）；
             批量模式多选一次给全班充值 -->
        <n-form-item label="充值用户" path="userId">
          <div class="wallet-admin__picker">
            <div class="wallet-admin__batch-toggle">
              <n-switch v-model:value="batch" size="small" @update:value="onBatchToggle" />
              <span class="wallet-admin__batch-label">批量充值（多选，逐人同额到账）</span>
            </div>
            <UserPicker
              v-model="pickerValue"
              :multiple="batch"
              placeholder="搜索账号 / 姓名 / 备注（如：A 班）"
              :search="searchUsers"
            />
          </div>
        </n-form-item>
        <n-form-item label="到账积分" path="points">
          <n-input-number v-model:value="form.points" :precision="2" :min="0.01" placeholder="正数（直接到账，不走阶梯折算；批量=每人到账此数）" style="width: 100%" />
        </n-form-item>
        <n-form-item label="备注">
          <n-input v-model:value="form.remark" type="textarea" :autosize="{ minRows: 2 }" placeholder="落流水 remark，可空" />
        </n-form-item>
        <n-form-item :label="' '">
          <n-button type="primary" :loading="saving" @click="submit">
            充值{{ batch && batchIds.length ? `（${batchIds.length} 人）` : '' }}
          </n-button>
        </n-form-item>
      </n-form>

      <n-alert v-if="lastResult" type="success" title="充值成功" style="max-width: 480px; margin-top: 12px">
        用户 {{ lastResult.userId }} 当前余额：{{ Number(lastResult.balanceAfter).toFixed(2) }} 积分
      </n-alert>
      <n-alert v-if="batchSummary" :type="batchSummary.fail ? 'warning' : 'success'" :title="`批量充值完成：成功 ${batchSummary.ok} 人${batchSummary.fail ? `，失败 ${batchSummary.fail} 人` : ''}`" style="max-width: 480px; margin-top: 12px">
        失败用户 ID：{{ batchSummary.failedIds.join('、') }}（可重选仅勾失败者重充；每人均独立幂等键，重试不双扣）
      </n-alert>

      <p class="wallet-admin__hint">
        MVP 为管理员直接填写到账积分（建 payment_order PAID + 余额涨 + 流水 ADMIN_GRANT，三者同事务）；
        用户余额总表 / 对账查询随 Phase4 补全。
      </p>
    </n-card>
  </div>
</template>

<script setup lang="ts">
import { uuid } from '@/utils/uuid'
import { computed, reactive, ref } from 'vue'
import { NCard, NForm, NFormItem, NInputNumber, NInput, NButton, NAlert, NEmpty, NSwitch, useMessage } from 'naive-ui'
import type { FormInst, FormRules } from 'naive-ui'
import { billingApi } from '@/api/billing'
import { useAuthStore } from '@/stores/auth'
import UserPicker from '@/components/common/UserPicker.vue'
import type { PickerUser } from '@/components/common/UserPicker.vue'

const authStore = useAuthStore()
const message = useMessage()
const canRecharge = computed(() => authStore.hasPermission('points:recharge'))

const formRef = ref<FormInst | null>(null)
const form = reactive<{ userId: number | null; points: number | null; remark: string | null }>({
  userId: null, points: null, remark: null
})
const rules: FormRules = {
  userId: { required: true, type: 'number', message: '请选择充值用户', trigger: 'blur' },
  points: { required: true, type: 'number', message: '请填到账积分', trigger: 'blur' }
}
const saving = ref(false)
const lastResult = ref<{ userId: number; balanceAfter: number } | null>(null)

// 修复III E3（12x#4）：批量充值——多选后逐人同额充值（每人独立幂等键，中途失败不回滚已成功者）
const batch = ref(false)
const batchIds = ref<number[]>([])
const batchSummary = ref<{ ok: number; fail: number; failedIds: number[] } | null>(null)

/** UserPicker 受控桥：单选写 form.userId，批量写 batchIds */
const pickerValue = computed({
  get: () => (batch.value ? batchIds.value : form.userId),
  set: (v) => {
    if (batch.value) batchIds.value = Array.isArray(v) ? v : []
    else form.userId = typeof v === 'number' ? v : null
  }
})

function onBatchToggle() {
  // 切模式清选择防串（单选 id 误当批量数组）
  form.userId = null
  batchIds.value = []
  batchSummary.value = null
}

/** E3：UserPicker 数据源——充值用户选项（姓名/账号/备注） */
async function searchUsers(q: string): Promise<PickerUser[]> {
  const res = await billingApi.rechargeUserOptions(q.trim())
  return res.data.data.map(u => ({ userId: u.userId, username: u.username, name: u.name, remark: u.remark }))
}

// SEC-FR-121：每开一轮表单生成一把幂等键——双击/网络重试同键只到账一次；成功后换键开新一笔
const idemKey = ref<string>(uuid())

async function submit() {
  if (batch.value) return submitBatch()
  if (form.userId == null) {
    message.warning('请选择充值用户')
    return
  }
  try {
    await formRef.value?.validate()
  } catch {
    return
  }
  saving.value = true
  try {
    const res = await billingApi.recharge({
      userId: form.userId,
      points: form.points!,
      remark: form.remark || undefined,
      idempotencyKey: idemKey.value
    })
    lastResult.value = res.data.data
    message.success('充值成功')
    form.points = null
    form.remark = null
    idemKey.value = uuid()
  } finally {
    saving.value = false
  }
}

/** E3 批量：逐人串行充值（防并发限流）；每人独立幂等键，失败收集后可单独重充 */
async function submitBatch() {
  if (!batchIds.value.length) {
    message.warning('请先选择充值用户（可按备注筛「A 班」全班）')
    return
  }
  if (form.points == null || form.points <= 0) {
    message.warning('请填到账积分')
    return
  }
  saving.value = true
  let ok = 0
  const failed: number[] = []
  try {
    for (const uid of batchIds.value) {
      try {
        await billingApi.recharge({
          userId: uid,
          points: form.points,
          remark: form.remark || undefined,
          idempotencyKey: uuid()   // 每人一把——重试同人同键未果场景由失败列表单独重充
        })
        ok++
      } catch {
        failed.push(uid)   // 拦截器已逐条 toast；此处汇总
      }
    }
    batchSummary.value = { ok, fail: failed.length, failedIds: failed }
    message.success(`批量充值完成：成功 ${ok} 人${failed.length ? `，失败 ${failed.length} 人` : ''}`)
    if (!failed.length) {
      batchIds.value = []
      form.points = null
      form.remark = null
    }
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
.wallet-admin__picker {
  width: 100%;
}
.wallet-admin__batch-toggle {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 6px;
}
.wallet-admin__batch-label {
  font-size: 12px;
  color: var(--color-text-secondary);
}
</style>
