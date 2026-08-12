<template>
  <!-- 安全设置页（骨架，Chunk G 填充实现）。
       管理当前用户的多通道凭证：绑/解绑邮箱·手机·微信、修改密码。 -->
  <div class="security-tab">
    <!-- 凭证列表区（Chunk G：接 GET /api/me/credentials） -->
    <n-card title="我的登录方式" size="small" class="security-tab__card">
      <template #header-extra>
        <span class="security-tab__placeholder">凭证管理（即将上线）</span>
      </template>
      <n-empty description="当前登录方式管理将在后续版本开放">
        <template #extra>
          <span class="security-tab__hint">
            将支持：绑定/解绑邮箱·手机·微信、查看已验证状态、修改密码
          </span>
        </template>
      </n-empty>
    </n-card>

    <!-- 修改密码区（Chunk G：接 POST /api/me/password/change） -->
    <n-card title="修改密码" size="small" class="security-tab__card">
      <n-form ref="pwdFormRef" :model="pwdForm" :rules="pwdRules" label-placement="left" label-width="100">
        <n-form-item path="oldPassword" label="当前密码">
          <n-input
            v-model:value="pwdForm.oldPassword"
            type="password"
            show-password-on="click"
            placeholder="请输入当前密码"
            :disabled="!credentialApiReady"
          />
        </n-form-item>
        <n-form-item path="newPassword" label="新密码">
          <n-input
            v-model:value="pwdForm.newPassword"
            type="password"
            show-password-on="click"
            placeholder="请输入新密码（6-100个字符）"
            :disabled="!credentialApiReady"
          />
        </n-form-item>
        <n-form-item path="confirmPassword" label="确认新密码">
          <n-input
            v-model:value="pwdForm.confirmPassword"
            type="password"
            show-password-on="click"
            placeholder="请再次输入新密码"
            :disabled="!credentialApiReady"
          />
        </n-form-item>
        <n-button type="primary" :disabled="!credentialApiReady" :loading="changing" @click="handleChangePassword">
          修改密码
        </n-button>
      </n-form>
      <p class="security-tab__notice">
        修改密码后将自动退出所有设备，需用新密码重新登录。
      </p>
    </n-card>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive } from 'vue'
import type { FormInst, FormRules } from 'naive-ui'
import { NCard, NForm, NFormItem, NInput, NButton, NEmpty, useMessage } from 'naive-ui'

const message = useMessage()

// Chunk G 前标记接口未就绪（禁用交互，防误触无响应）
const credentialApiReady = false

const pwdFormRef = ref<FormInst | null>(null)
const changing = ref(false)
const pwdForm = reactive({
  oldPassword: '',
  newPassword: '',
  confirmPassword: ''
})

const pwdRules: FormRules = {
  oldPassword: [{ required: true, message: '请输入当前密码', trigger: 'blur' }],
  newPassword: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    { min: 6, max: 100, message: '密码长度6-100个字符', trigger: 'blur' }
  ],
  confirmPassword: [
    { required: true, message: '请确认新密码', trigger: 'blur' },
    {
      validator: (_rule, value) => {
        if (value !== pwdForm.newPassword) return new Error('两次输入的密码不一致')
        return true
      },
      trigger: 'blur'
    }
  ]
}

async function handleChangePassword() {
  // Chunk G 实现：校验表单 → POST /api/me/password/change → 登出跳转登录
  message.info('修改密码功能将在后续版本开放')
}
</script>

<style lang="scss" scoped>
.security-tab {
  max-width: 560px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.security-tab__card {
  .n-card__content { padding: 16px; }
}
.security-tab__placeholder {
  font-size: 12px;
  color: var(--color-text-tertiary);
}
.security-tab__hint {
  font-size: 12px;
  color: var(--color-text-tertiary);
}
.security-tab__notice {
  margin: 12px 0 0;
  font-size: 12px;
  color: var(--color-text-tertiary);
}
</style>
