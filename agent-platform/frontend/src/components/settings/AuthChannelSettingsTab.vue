<template>
  <div class="auth-channel-settings">
    <n-alert type="info" title="配置即时生效" class="auth-channel-settings__notice">
      页面配置优先于部署环境变量；密钥加密保存且不会回显。密钥留空表示不修改，点击“清除”后恢复环境变量兜底。
    </n-alert>
    <n-spin :show="loading">
      <n-form label-placement="left" label-width="150">
        <n-divider title-placement="left">邮件通道</n-divider>
        <n-form-item label="启用邮件"><n-switch v-model:value="form.mail.enabled" /></n-form-item>
        <!-- 12x：通道类型——阿里云 DM 或通用 SMTP（腾讯 QQ 邮箱/企业邮/网易等） -->
        <n-form-item label="通道类型">
          <n-radio-group v-model:value="form.mail.provider">
            <n-radio-button value="ALIYUN">阿里云 DirectMail</n-radio-button>
            <n-radio-button value="SMTP">SMTP 通用邮箱</n-radio-button>
          </n-radio-group>
        </n-form-item>

        <template v-if="form.mail.provider !== 'SMTP'">
          <n-form-item label="区域"><n-input v-model:value="form.mail.region" placeholder="cn-hangzhou" /></n-form-item>
          <n-form-item label="AccessKey ID"><n-input v-model:value="form.mail.accessKeyId" /></n-form-item>
          <n-form-item label="AccessKey Secret">
            <n-input v-model:value="mailSecret" type="password" show-password-on="click" placeholder="留空不修改" />
            <n-tag :type="form.mail.secretConfigured ? 'success' : 'default'">{{ form.mail.secretConfigured ? '已配置' : '未配置' }}</n-tag>
            <n-button quaternary type="error" @click="mailSecret = ' '">清除</n-button>
          </n-form-item>
          <n-form-item label="发信地址"><n-input v-model:value="form.mail.accountName" placeholder="noreply@example.com" /></n-form-item>
          <n-form-item label="发件人昵称"><n-input v-model:value="form.mail.fromAlias" /></n-form-item>
          <n-form-item label="回信地址"><n-input v-model:value="form.mail.replyToAddress" /></n-form-item>
        </template>

        <template v-else>
          <!-- SMTP：腾讯 QQ 邮箱 smtp.qq.com:465（密码=16位授权码）；腾讯企业邮 smtp.exmail.qq.com:465（密码=客户端专用密码） -->
          <n-form-item label="SMTP 服务器"><n-input v-model:value="form.mail.smtpHost" placeholder="smtp.qq.com / smtp.exmail.qq.com" /></n-form-item>
          <n-form-item label="端口">
            <n-input-number v-model:value="form.mail.smtpPort" :min="1" :max="65535" placeholder="465" style="width: 160px" />
            <span class="unit">SSL 直连一般 465；STARTTLS 一般 587</span>
          </n-form-item>
          <n-form-item label="SSL 直连"><n-switch v-model:value="form.mail.smtpSsl" /></n-form-item>
          <n-form-item label="用户名"><n-input v-model:value="form.mail.smtpUsername" placeholder="完整邮箱地址，如 12345@qq.com" /></n-form-item>
          <n-form-item label="密码/授权码">
            <n-input v-model:value="smtpSecret" type="password" show-password-on="click" placeholder="QQ 邮箱填 16 位授权码；留空不修改" />
            <n-tag :type="form.mail.smtpPasswordConfigured ? 'success' : 'default'">{{ form.mail.smtpPasswordConfigured ? '已配置' : '未配置' }}</n-tag>
            <n-button quaternary type="error" @click="smtpSecret = ' '">清除</n-button>
          </n-form-item>
          <n-form-item label="发件人昵称"><n-input v-model:value="form.mail.smtpFromAlias" placeholder="平台名（收件人看到的名字）" /></n-form-item>
        </template>

        <n-form-item label="邮箱验证 URL"><n-input v-model:value="form.mail.verifyUrl" placeholder="https://example.com/verify-email" /></n-form-item>
        <n-form-item label="密码重置 URL"><n-input v-model:value="form.mail.resetUrl" placeholder="https://example.com/reset-password" /></n-form-item>

        <!-- 12x：先测通再开开关 -->
        <n-form-item label="测试发信">
          <n-input v-model:value="mailTestTo" placeholder="输入收件邮箱，点右侧按钮发测试信" style="max-width: 320px" />
          <n-button :loading="mailTesting" :disabled="!mailTestTo" @click="sendMailTest">发送测试邮件</n-button>
        </n-form-item>

        <n-divider title-placement="left">短信（阿里云 SMS）</n-divider>
        <n-form-item label="启用短信"><n-switch v-model:value="form.sms.enabled" /></n-form-item>
        <n-form-item label="区域"><n-input v-model:value="form.sms.region" placeholder="cn-hangzhou" /></n-form-item>
        <n-form-item label="AccessKey ID"><n-input v-model:value="form.sms.accessKeyId" /></n-form-item>
        <n-form-item label="AccessKey Secret">
          <n-input v-model:value="smsSecret" type="password" show-password-on="click" placeholder="留空不修改" />
          <n-tag :type="form.sms.secretConfigured ? 'success' : 'default'">{{ form.sms.secretConfigured ? '已配置' : '未配置' }}</n-tag>
          <n-button quaternary type="error" @click="smsSecret = ' '">清除</n-button>
        </n-form-item>
        <n-form-item label="短信签名"><n-input v-model:value="form.sms.signName" /></n-form-item>
        <n-form-item label="验证码模板"><n-input v-model:value="form.sms.templateCodeVerify" placeholder="SMS_xxx" /></n-form-item>
        <n-form-item label="重置密码模板"><n-input v-model:value="form.sms.templateCodeReset" placeholder="SMS_xxx" /></n-form-item>
        <n-form-item label="验证码有效期"><n-input-number v-model:value="form.sms.codeTtlMinutes" :min="1" :max="60" /><span class="unit">分钟</span></n-form-item>
        <n-form-item label="手机号每日上限"><n-input-number v-model:value="form.sms.limitPerPhonePerDay" :min="1" :max="1000" /></n-form-item>
        <n-form-item label="IP 每日上限"><n-input-number v-model:value="form.sms.limitPerIpPerDay" :min="1" :max="10000" /></n-form-item>
        <n-button type="primary" :loading="saving" @click="save">保存认证通道配置</n-button>
      </n-form>
    </n-spin>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useMessage } from 'naive-ui'
import { systemApi } from '@/api/system'
import type { AuthChannelSettings, AuthChannelSettingsUpdate } from '@/api/system'

const message = useMessage()
const loading = ref(false)
const saving = ref(false)
const mailSecret = ref('')
const smsSecret = ref('')
const smtpSecret = ref('')
const mailTestTo = ref('')
const mailTesting = ref(false)
const form = reactive<AuthChannelSettings>({
  mail: { enabled: false, secretConfigured: false, provider: 'ALIYUN' },
  sms: { enabled: false, secretConfigured: false, codeTtlMinutes: 5, limitPerPhonePerDay: 10, limitPerIpPerDay: 30 }
})

function apply(value: AuthChannelSettings) {
  Object.assign(form.mail, value.mail)
  Object.assign(form.sms, value.sms)
}

async function load() {
  loading.value = true
  try { apply((await systemApi.getAuthChannelSettings()).data.data) }
  catch { message.error('加载认证通道配置失败') }
  finally { loading.value = false }
}

async function save() {
  saving.value = true
  try {
    const data: AuthChannelSettingsUpdate = {
      mail: { ...form.mail, accessKeySecret: mailSecret.value || undefined, smtpPassword: smtpSecret.value || undefined },
      sms: { ...form.sms, accessKeySecret: smsSecret.value || undefined }
    }
    delete data.mail?.secretConfigured
    delete data.mail?.smtpPasswordConfigured
    delete data.sms?.secretConfigured
    apply((await systemApi.updateAuthChannelSettings(data)).data.data)
    mailSecret.value = ''
    smsSecret.value = ''
    smtpSecret.value = ''
    message.success('认证通道配置已保存并即时生效')
  } catch { message.error('保存认证通道配置失败') }
  finally { saving.value = false }
}

/** 12x：测试发信——先测通再开开关（后端按当前已保存的配置快照路由）。 */
async function sendMailTest() {
  mailTesting.value = true
  try {
    await systemApi.testMailChannel(mailTestTo.value.trim())
    message.success('测试邮件已发送，请查收（含垃圾箱）')
  } catch (e: unknown) {
    message.error((e as { message?: string })?.message || '发送失败：请检查通道配置并先保存')
  } finally { mailTesting.value = false }
}

onMounted(load)

// 测试探针
defineExpose({ form, mailTestTo, mailTesting, smtpSecret, load, save, sendMailTest })
</script>

<style scoped lang="scss">
.auth-channel-settings { max-width: 820px; }
.auth-channel-settings__notice { margin-bottom: 16px; }
.unit { margin-left: 8px; color: var(--color-text-secondary); }
</style>
