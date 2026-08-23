<template>
  <!-- 安全设置页（Chunk G）：当前用户多通道凭证管理（绑/解绑邮箱）+ 修改密码。
       手机/微信绑定需独立验证码/OAuth 回路，本期未实现，按钮置灰提示「即将上线」。 -->
  <div class="security-tab">
    <!-- 凭证列表区 -->
    <n-card title="我的登录方式" size="small" class="security-tab__card">
      <template #header-extra>
        <span class="security-tab__hint">identifier 脱敏展示</span>
      </template>
      <div v-if="loading" class="security-tab__loading">
        <n-spin size="small" />
        <span>加载中…</span>
      </div>
      <n-empty v-else-if="credentials.length === 0" description="暂无凭证数据" />
      <n-space v-else vertical :size="12">
        <div
          v-for="item in credentials"
          :key="item.credentialType"
          class="security-tab__cred-row"
        >
          <div class="security-tab__cred-info">
            <span class="security-tab__cred-type">{{ typeLabel(item.credentialType) }}</span>
            <span class="security-tab__cred-id">{{ item.identifier }}</span>
            <n-tag
              :type="item.verified ? 'success' : 'warning'"
              size="small"
              round
              :bordered="false"
            >
              {{ item.verified ? '已验证' : '未验证' }}
            </n-tag>
          </div>
          <n-button
            v-if="item.credentialType !== 'PASSWORD'"
            size="small"
            :disabled="isLastUsable"
            quaternary
            type="error"
            @click="handleUnbind(item.credentialType)"
          >
            {{ isLastUsable ? '不可解绑' : '解绑' }}
          </n-button>
        </div>
      </n-space>

      <n-alert v-if="isLastUsable" type="info" :show-icon="true" class="security-tab__alert">
        仅剩一种登录方式时不可解绑，请先绑定其他登录方式以防账号失联。
      </n-alert>

      <!-- 绑定入口 -->
      <div class="security-tab__bind-section">
        <n-button
          v-if="!hasEmail && channels.emailEnabled"
          size="small"
          tertiary
          @click="showBindEmail = true"
        >
          + 绑定邮箱
        </n-button>
        <n-button size="small" tertiary disabled title="手机绑定即将上线">
          + 绑定手机（即将上线）
        </n-button>
        <n-button size="small" tertiary disabled title="微信绑定即将上线">
          + 绑定微信（即将上线）
        </n-button>
      </div>
    </n-card>

    <!-- 修改密码区 -->
    <n-card title="修改密码" size="small" class="security-tab__card">
      <n-form ref="pwdFormRef" :model="pwdForm" :rules="pwdRules" label-placement="left" label-width="100">
        <n-form-item path="oldPassword" label="当前密码">
          <n-input
            v-model:value="pwdForm.oldPassword"
            type="password"
            show-password-on="click"
            placeholder="请输入当前密码"
          />
        </n-form-item>
        <n-form-item path="newPassword" label="新密码">
          <n-input
            v-model:value="pwdForm.newPassword"
            type="password"
            show-password-on="click"
            placeholder="6-100位，含大小写字母/数字/特殊字符"
          />
        </n-form-item>
        <n-form-item path="confirmPassword" label="确认新密码">
          <n-input
            v-model:value="pwdForm.confirmPassword"
            type="password"
            show-password-on="click"
            placeholder="请再次输入新密码"
          />
        </n-form-item>
        <n-button type="primary" :loading="changing" @click="handleChangePassword">
          修改密码
        </n-button>
      </n-form>
      <p class="security-tab__notice">
        修改密码后将自动退出所有设备，需用新密码重新登录。
      </p>
    </n-card>

    <!-- 安全体系 S5 · A6：TOTP 两步验证（绑定/解绑/恢复码） -->
    <TotpSettingsCard />

    <!-- 安全体系 S5 · J2：注销账号（危险区，密码确认 + 二次弹窗） -->
    <n-card title="注销账号" size="small" class="security-tab__card">
      <div class="security-tab__danger-text">
        注销后账号将无法恢复：用户名/邮箱/手机号等身份信息将被匿名化清除，
        所有设备立即退出登录。计费流水与审计日志将按法定要求脱敏保留。
        详细口径见
        <router-link to="/privacy" target="_blank" class="security-tab__danger-link">
          《隐私政策》
        </router-link>
        。
      </div>
      <n-button type="error" ghost :loading="deleting" @click="showDeleteAccount = true">
        注销我的账号
      </n-button>
    </n-card>

    <!-- 注销确认弹窗：密码确认（后端最后一道闸） -->
    <n-modal
      v-model:show="showDeleteAccount"
      preset="dialog"
      title="确认注销账号"
      :show-icon="false"
      :mask-closable="false"
      style="max-width: 440px"
    >
      <n-alert type="warning" :show-icon="true" style="margin-bottom: 12px">
        此操作不可恢复！请确认你了解《隐私政策》中关于数据删除与法定保留的口径。
      </n-alert>
      <n-form ref="delFormRef" :model="delForm" :rules="delRules" label-placement="top">
        <n-form-item path="password" label="输入当前密码确认">
          <n-input
            v-model:value="delForm.password"
            type="password"
            show-password-on="click"
            placeholder="请输入当前密码"
          />
        </n-form-item>
        <n-form-item path="confirmText" label="输入「注销」二字再次确认">
          <n-input v-model:value="delForm.confirmText" placeholder="注销" />
        </n-form-item>
      </n-form>
      <template #action>
        <n-space>
          <n-button @click="showDeleteAccount = false">取消</n-button>
          <n-button type="error" :loading="deleting" @click="handleDeleteAccount">
            确认注销
          </n-button>
        </n-space>
      </template>
    </n-modal>

    <!-- 绑定邮箱弹窗 -->
    <n-modal
      v-model:show="showBindEmail"
      preset="dialog"
      title="绑定邮箱"
      :show-icon="false"
      :mask-closable="false"
      style="max-width: 420px"
    >
      <n-form ref="bindFormRef" :model="bindForm" :rules="bindRules" label-placement="top">
        <n-form-item path="email" label="邮箱地址">
          <n-input
            v-model:value="bindForm.email"
            placeholder="请输入要绑定的邮箱"
            :input-props="{ autocomplete: 'email' }"
          />
        </n-form-item>
        <!-- 12x B4：账号已开 TOTP 时改绑/绑定邮箱必须过两步码（防会话劫持偷换找回邮箱） -->
        <n-form-item path="totpCode" label="两步验证码（已开启两步验证的账号必填）">
          <n-input
            v-model:value="bindForm.totpCode"
            placeholder="身份验证器 6 位数字（未开启可留空）"
            :input-props="{ autocomplete: 'one-time-code', inputmode: 'numeric' }"
          />
        </n-form-item>
        <n-alert type="info" :show-icon="true" style="margin-top: 4px">
          绑定后将发送激活邮件，激活后该邮箱方可用于找回密码。
        </n-alert>
      </n-form>
      <template #action>
        <n-space>
          <n-button @click="showBindEmail = false">取消</n-button>
          <n-button type="primary" :loading="binding" @click="confirmBindEmail">确认绑定</n-button>
        </n-space>
      </template>
    </n-modal>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import type { FormInst, FormRules } from 'naive-ui'
import {
  NCard, NForm, NFormItem, NInput, NButton, NEmpty, NTag,
  NAlert, NSpace, NSpin, NModal, useMessage, useDialog
} from 'naive-ui'
import { authApi, type CredentialItem, type AuthChannels } from '@/api/auth'
import { useAuthStore } from '@/stores/auth'
import TotpSettingsCard from './TotpSettingsCard.vue'

const message = useMessage()
const dialog = useDialog()
const router = useRouter()
const authStore = useAuthStore()

// 凭证列表
const credentials = ref<CredentialItem[]>([])
const channels = ref<AuthChannels>({ passwordEnabled: true, emailEnabled: false, smsEnabled: false, wechatEnabled: false, registerEmailCodeRequired: false })
const loading = ref(false)

const hasEmail = computed(() => credentials.value.some(c => c.credentialType === 'EMAIL'))
/** 非密码凭证数 ≤1 → 解绑会归零，禁用解绑（防账号失联）。 */
const isLastUsable = computed(() => {
  const nonPassword = credentials.value.filter(c => c.credentialType !== 'PASSWORD')
  return nonPassword.length <= 1
})

async function loadCredentials() {
  loading.value = true
  try {
    const [credRes, chRes] = await Promise.all([authApi.getCredentials(), authApi.getChannels()])
    credentials.value = credRes.data.data
    channels.value = chRes.data.data
  } catch {
    message.error('加载凭证信息失败')
  } finally {
    loading.value = false
  }
}

function typeLabel(type: string): string {
  switch (type) {
    case 'PASSWORD': return '账号密码'
    case 'EMAIL': return '邮箱'
    case 'PHONE': return '手机号'
    case 'WECHAT': return '微信'
    case 'DINGTALK': return '钉钉'
    default: return type
  }
}

// 解绑
async function handleUnbind(credentialType: string) {
  dialog.warning({
    title: '确认解绑',
    content: `确定要解绑「${typeLabel(credentialType)}」吗？解绑后该方式将无法用于登录。`,
    positiveText: '确认解绑',
    negativeText: '取消',
    onPositiveClick: async () => {
      try {
        await authApi.unbindCredential(credentialType)
        message.success('解绑成功')
        await loadCredentials()
      } catch {
        // axios 拦截器已统一提示，此处仅兜底
      }
    }
  })
}

// 绑定邮箱
const showBindEmail = ref(false)
const binding = ref(false)
const bindFormRef = ref<FormInst | null>(null)
const bindForm = reactive({ email: '', totpCode: '' })
const bindRules: FormRules = {
  email: [
    { required: true, message: '请输入邮箱', trigger: 'blur' },
    {
      validator: (_rule, value: string) => {
        if (!/^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$/.test(value || '')) {
          return new Error('邮箱格式不正确')
        }
        return true
      },
      trigger: 'blur'
    }
  ]
}

async function confirmBindEmail() {
  try {
    await bindFormRef.value?.validate()
  } catch {
    return
  }
  binding.value = true
  try {
    await authApi.bindEmail(bindForm.email.trim(), bindForm.totpCode.trim() || undefined)
    message.success('绑定成功，请查收激活邮件完成验证')
    showBindEmail.value = false
    bindForm.email = ''
    bindForm.totpCode = ''
    await loadCredentials()
  } catch {
    // 拦截器已提示
  } finally {
    binding.value = false
  }
}

// 修改密码
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
  try {
    await pwdFormRef.value?.validate()
  } catch {
    return
  }
  changing.value = true
  try {
    await authApi.changePassword(pwdForm.oldPassword, pwdForm.newPassword)
    // 改密成功 → 后端已踢所有会话，当前 token 失效 → 本地登出 + 跳登录页
    message.success('密码修改成功，请使用新密码重新登录')
    await authStore.logout()
    await router.push('/login')
  } catch {
    // 拦截器已提示（旧密码错/策略不过等）
  } finally {
    changing.value = false
  }
}

// 安全体系 S5 · J2：注销账号（密码确认 + 「注销」二字二次确认 → 软删匿名化）
const delFormRef = ref<FormInst | null>(null)
const showDeleteAccount = ref(false)
const deleting = ref(false)
const delForm = reactive({ password: '', confirmText: '' })
const delRules: FormRules = {
  password: [{ required: true, message: '请输入当前密码', trigger: 'blur' }],
  confirmText: [
    {
      validator: (_rule, value) => {
        if (value !== '注销') return new Error('请输入「注销」二字以确认')
        return true
      },
      trigger: 'blur'
    }
  ]
}

async function handleDeleteAccount() {
  try {
    await delFormRef.value?.validate()
  } catch {
    return
  }
  deleting.value = true
  try {
    await authApi.deleteAccount(delForm.password)
    // 注销成功 → 后端已踢全部会话+拉黑当前 token → 本地清态跳登录页
    // （logout 内部 API 调用会因 token 已拉黑而 401，但 catch 保证本地清理必执行）
    message.success('账号已注销，感谢你的使用')
    await authStore.logout()
    await router.push('/login')
  } catch {
    // 拦截器已提示（密码错/状态不允许等）
  } finally {
    deleting.value = false
  }
}

onMounted(loadCredentials)
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
.security-tab__hint {
  font-size: 12px;
  color: var(--color-text-tertiary);
}
.security-tab__loading {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--color-text-tertiary);
  font-size: 13px;
  padding: 8px 0;
}
.security-tab__cred-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 12px;
  border-radius: 6px;
  background: var(--color-fill-light, rgba(0, 0, 0, 0.03));
}
.security-tab__cred-info {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.security-tab__cred-type {
  font-weight: 500;
  min-width: 56px;
}
.security-tab__cred-id {
  color: var(--color-text-secondary);
  font-size: 13px;
}
.security-tab__alert {
  margin-top: 12px;
}
.security-tab__bind-section {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  margin-top: 14px;
  padding-top: 14px;
  border-top: 1px dashed var(--color-border, rgba(0, 0, 0, 0.09));
}
.security-tab__danger-text {
  font-size: 13px;
  line-height: 1.7;
  color: var(--color-text-secondary);
  margin-bottom: 12px;
}
.security-tab__danger-link {
  color: var(--color-error, #d03050);
}
.security-tab__notice {
  margin: 12px 0 0;
  font-size: 12px;
  color: var(--color-text-tertiary);
}
</style>
