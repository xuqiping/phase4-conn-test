<template>
  <!-- 安全体系 S5 · A6 TOTP 两步验证卡片：绑定（secret/URI → 验码 → 恢复码一次性展示）/解绑 -->
  <n-card title="两步验证（TOTP）" size="small" class="totp-card">
    <div v-if="loading" class="totp-card__loading"><n-spin size="small" /></div>

    <template v-else>
      <!-- 已绑定 -->
      <div v-if="status.bound" class="totp-card__state">
        <n-tag type="success" size="small">已开启</n-tag>
        <span class="totp-card__desc">登录需密码 + 验证器动态码双重确认</span>
        <n-button size="small" quaternary type="error" @click="unbindModalShow = true">
          解绑
        </n-button>
      </div>

      <!-- 未绑定 -->
      <div v-else class="totp-card__state">
        <n-tag size="small" :type="status.required ? 'warning' : 'default'">未开启</n-tag>
        <span class="totp-card__desc">
          {{ status.required ? '平台建议管理员开启两步验证' : '开启后登录需第二重动态码' }}
        </span>
        <n-button size="small" type="primary" @click="startBind">绑定验证器</n-button>
      </div>
    </template>

    <!-- 绑定弹窗：secret/URI + 验码确认 + 恢复码展示三段 -->
    <n-modal
      v-model:show="bindModalShow"
      preset="card"
      title="绑定验证器"
      class="totp-card__modal"
      :mask-closable="false"
    >
      <!-- 阶段1：展示 secret / otpauth URI -->
      <template v-if="bindStage === 'secret'">
        <n-steps :current="1" size="small" class="totp-card__steps">
          <n-step title="加入验证器" />
          <n-step title="输入动态码确认" />
          <n-step title="保存恢复码" />
        </n-steps>
        <n-alert type="info" :bordered="false" class="totp-card__gap">
          在手机验证器 App（Google Authenticator / Microsoft Authenticator 等）中「手动添加账户」，
          粘贴下方密钥；或复制第二行的绑定链接
        </n-alert>
        <n-input-group class="totp-card__gap">
          <n-input :value="bindSecret" readonly />
          <n-button @click="copy(bindSecret)">复制</n-button>
        </n-input-group>
        <n-input-group class="totp-card__gap">
          <n-input :value="bindUri" readonly />
          <n-button @click="copy(bindUri)">复制</n-button>
        </n-input-group>
        <n-input
          v-model:value="confirmCode"
          placeholder="输入验证器当前 6 位动态码确认绑定"
          size="large"
          @keyup.enter="confirmBind"
        />
        <div class="totp-card__actions">
          <n-button type="primary" :loading="submitting" @click="confirmBind">确认绑定</n-button>
        </div>
      </template>

      <!-- 阶段2：恢复码一次性展示 -->
      <template v-else-if="bindStage === 'recovery'">
        <n-steps :current="3" size="small" class="totp-card__steps">
          <n-step title="加入验证器" />
          <n-step title="输入动态码确认" />
          <n-step title="保存恢复码" />
        </n-steps>
        <n-alert type="warning" :bordered="false" class="totp-card__gap">
          绑定成功！以下 8 组恢复码仅在本次展示——手机丢失时用来救场，每张只能用一次。请抄写或复制保存。
        </n-alert>
        <div class="totp-card__codes">
          <code v-for="c in recoveryCodes" :key="c">{{ c }}</code>
        </div>
        <div class="totp-card__actions">
          <n-button @click="copy(recoveryCodes.join('\n'))">复制全部</n-button>
          <n-button type="primary" @click="finishBind">我已保存，完成</n-button>
        </div>
      </template>
    </n-modal>

    <!-- 解绑弹窗 -->
    <n-modal v-model:show="unbindModalShow" preset="card" title="解绑两步验证" :mask-closable="false">
      <n-alert type="warning" :bordered="false" class="totp-card__gap">
        解绑后登录仅需密码，安全性降低。需输入当前有效动态码或恢复码确认本人操作
      </n-alert>
      <n-input
        v-model:value="unbindCode"
        placeholder="6 位动态码或恢复码"
        size="large"
        @keyup.enter="doUnbind"
      />
      <div class="totp-card__actions">
        <n-button type="error" :loading="submitting" @click="doUnbind">确认解绑</n-button>
      </div>
    </n-modal>
  </n-card>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import {
  NCard, NTag, NButton, NModal, NAlert, NInput, NInputGroup,
  NSpin, NSteps, NStep, useMessage
} from 'naive-ui'
import { authApi, type MfaStatus } from '@/api/auth'

const message = useMessage()

const loading = ref(true)
const submitting = ref(false)
const status = ref<MfaStatus>({ bound: false, required: false })

// 绑定流状态机：secret（展示密钥+验码）→ recovery（恢复码一次性展示）
const bindModalShow = ref(false)
const bindStage = ref<'secret' | 'recovery'>('secret')
const bindSecret = ref('')
const bindUri = ref('')
const confirmCode = ref('')
const recoveryCodes = ref<string[]>([])

// 解绑流
const unbindModalShow = ref(false)
const unbindCode = ref('')

async function loadStatus() {
  loading.value = true
  try {
    const res = await authApi.getMfaStatus()
    status.value = res.data.data
  } catch {
    // 状态拉取失败不炸设置页
  } finally {
    loading.value = false
  }
}

async function startBind() {
  try {
    const res = await authApi.mfaBind()
    bindSecret.value = res.data.data.secret
    bindUri.value = res.data.data.otpauthUri
    confirmCode.value = ''
    bindStage.value = 'secret'
    bindModalShow.value = true
  } catch (e) {
    message.error(e instanceof Error ? e.message : '发起绑定失败')
  }
}

async function confirmBind() {
  if (!confirmCode.value.trim()) {
    message.warning('请输入动态码')
    return
  }
  submitting.value = true
  try {
    const res = await authApi.mfaBindConfirm(confirmCode.value.trim())
    recoveryCodes.value = res.data.data.recoveryCodes || []
    bindStage.value = 'recovery'
    status.value.bound = true
    message.success('绑定成功')
  } catch (e) {
    message.error(e instanceof Error ? e.message : '验证码错误，绑定未完成')
  } finally {
    submitting.value = false
  }
}

function finishBind() {
  bindModalShow.value = false
}

async function doUnbind() {
  if (!unbindCode.value.trim()) {
    message.warning('请输入验证码')
    return
  }
  submitting.value = true
  try {
    await authApi.mfaUnbind(unbindCode.value.trim())
    status.value.bound = false
    unbindModalShow.value = false
    unbindCode.value = ''
    message.success('已解绑')
  } catch (e) {
    message.error(e instanceof Error ? e.message : '验证码错误，解绑失败')
  } finally {
    submitting.value = false
  }
}

function copy(text: string) {
  navigator.clipboard?.writeText(text).then(
    () => message.success('已复制'),
    () => message.error('复制失败，请手动选择复制')
  )
}

onMounted(loadStatus)
</script>

<style lang="scss" scoped>
.totp-card {
  margin-bottom: var(--spacing-4);
}
.totp-card__loading {
  display: flex;
  justify-content: center;
  padding: 16px;
}
.totp-card__state {
  display: flex;
  align-items: center;
  gap: var(--spacing-3);
}
.totp-card__desc {
  flex: 1;
  color: var(--color-text-secondary);
  font-size: var(--font-size-sm);
}
.totp-card__steps {
  margin-bottom: 16px;
}
.totp-card__gap {
  margin-bottom: 12px;
}
.totp-card__codes {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 8px;
  margin-bottom: 12px;

  code {
    padding: 8px;
    text-align: center;
    background: rgba(var(--color-primary-rgb), 0.06);
    border: 1px solid var(--color-border);
    border-radius: var(--radius-sm);
    font-size: var(--font-size-sm);
  }
}
.totp-card__actions {
  display: flex;
  justify-content: flex-end;
  gap: var(--spacing-2);
  margin-top: 16px;
}
</style>
