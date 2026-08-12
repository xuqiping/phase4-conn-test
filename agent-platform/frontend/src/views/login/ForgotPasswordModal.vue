<template>
  <n-modal
    :show="show"
    preset="card"
    title="找回密码"
    :style="{ maxWidth: '440px', width: '90vw' }"
    :bordered="false"
    :segmented="{ content: true }"
    @update:show="(v: boolean) => emit('update:show', v)"
  >
    <n-form ref="formRef" :model="form" :rules="rules" @submit.prevent="handleForgot">
      <n-form-item label="找回方式">
        <n-radio-group v-model:value="form.channel">
          <n-radio value="EMAIL" :disabled="!emailEnabled">邮箱找回</n-radio>
          <n-radio value="SMS" :disabled="!smsEnabled">短信找回</n-radio>
        </n-radio-group>
      </n-form-item>

      <n-form-item :path="form.channel === 'SMS' ? 'phone' : 'identifier'" :label="form.channel === 'SMS' ? '手机号' : '账号'">
        <n-input
          v-if="form.channel === 'SMS'"
          v-model:value="form.phone"
          placeholder="请输入注册时的手机号"
          size="large"
          :input-props="{ maxlength: 11 }"
        >
          <template #prefix>
            <n-icon :component="CallOutline" color="var(--color-text-tertiary)" />
          </template>
        </n-input>
        <n-input
          v-else
          v-model:value="form.identifier"
          placeholder="请输入用户名或邮箱"
          size="large"
        >
          <template #prefix>
            <n-icon :component="PersonOutline" color="var(--color-text-tertiary)" />
          </template>
        </n-input>
      </n-form-item>

      <n-button
        type="primary"
        block
        size="large"
        :loading="submitting"
        attr-type="submit"
      >
        发送重置链接/验证码
      </n-button>
    </n-form>

    <n-alert type="info" :bordered="false" class="forgot-modal__notice">
      {{ form.channel === 'EMAIL'
        ? '重置链接将发送到您的验证邮箱。注意：仅已验证邮箱可用于找回密码。'
        : '重置验证码将发送到您的手机号。' }}
      无论账号是否存在，系统都会返回相同提示（安全防探测）。
    </n-alert>
  </n-modal>
</template>

<script setup lang="ts">
import { ref, reactive, computed, watch } from 'vue'
import type { FormInst, FormRules } from 'naive-ui'
import { NModal, NForm, NFormItem, NInput, NButton, NIcon, NRadioGroup, NRadio, NAlert, useMessage } from 'naive-ui'
import { PersonOutline, CallOutline } from '@vicons/ionicons5'
import { authApi } from '@/api/auth'

const props = defineProps<{
  show: boolean
  /** 邮箱通道是否开启（决定邮箱找回可选） */
  emailEnabled: boolean
  /** 短信通道是否开启（决定短信找回可选） */
  smsEnabled: boolean
}>()
const emit = defineEmits<{
  (e: 'update:show', v: boolean): void
}>()

const message = useMessage()
const submitting = ref(false)

const formRef = ref<FormInst | null>(null)
const form = reactive({
  channel: 'EMAIL' as 'EMAIL' | 'SMS',
  identifier: '',
  phone: ''
})

// 弹窗打开时按通道开关设默认渠道（邮箱优先，关则短信）
watch(
  () => props.show,
  (v) => {
    if (v) {
      form.channel = props.emailEnabled ? 'EMAIL' : 'SMS'
      form.identifier = ''
      form.phone = ''
    }
  }
)

const rules = computed<FormRules>(() => ({
  identifier: [
    { required: form.channel === 'EMAIL', message: '请输入用户名或邮箱', trigger: 'blur' }
  ],
  phone: [
    { required: form.channel === 'SMS', message: '请输入手机号', trigger: 'blur' },
    {
      validator: (_r, v: string) => form.channel !== 'SMS' || /^1[3-9]\d{9}$/.test(v),
      message: '请输入正确的手机号',
      trigger: 'blur'
    }
  ]
}))

async function handleForgot() {
  try {
    await formRef.value?.validate()
  } catch {
    return
  }
  submitting.value = true
  try {
    const identifier = form.channel === 'SMS' ? form.phone : form.identifier
    const res = await authApi.forgotPassword(identifier, form.channel)
    // 统一话术（防账号枚举）：无论账号是否存在都返回相同提示
    message.success(res.data.data || '若账号存在，重置链接/验证码已发送')
    emit('update:show', false)
  } catch (error: unknown) {
    const msg = error instanceof Error ? error.message : '操作失败，请稍后重试'
    message.error(msg)
  } finally {
    submitting.value = false
  }
}
</script>

<style lang="scss" scoped>
.forgot-modal__notice {
  margin-top: 12px;
  font-size: 13px;
}
</style>
