<template>
  <div class="qr-tab">
    <div v-if="!wechatEnabled && !dingtalkEnabled" class="qr-tab__empty">
      扫码登录通道未开启
    </div>

    <template v-else>
      <!-- 微信扫码 -->
      <n-button
        v-if="wechatEnabled"
        block
        size="large"
        :loading="wechatLoading"
        class="qr-tab__btn qr-tab__btn--wechat"
        @click="onWechatLogin"
      >
        <template #icon>
          <n-icon :component="ScanOutline" />
        </template>
        微信扫码登录
      </n-button>

      <!-- 钉钉登录（容器内免登 / 外部浏览器 OAuth2 降级） -->
      <n-button
        v-if="dingtalkEnabled"
        block
        size="large"
        class="qr-tab__btn qr-tab__btn--dingtalk"
        @click="onDingTalkLogin"
      >
        <template #icon>
          <n-icon :component="ScanOutline" />
        </template>
        钉钉登录
      </n-button>

      <p class="qr-tab__hint">
        {{ dingtalkEnabled && isDingTalkClient() ? '正在钉钉客户端内，将自动免登' : '点击按钮跳转对应平台扫码授权' }}
      </p>
    </template>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { NButton, NIcon, useMessage } from 'naive-ui'
import { ScanOutline } from '@vicons/ionicons5'
import { useAuthStore } from '@/stores/auth'
import { redirectToWechatAuth } from '@/utils/wechat'
import {
  isDingTalkEnabled, isDingTalkClient, requestDingTalkAuthCode, redirectToDingTalkAuth
} from '@/utils/dingtalk'
import { useRouter, useRoute } from 'vue-router'

defineProps<{
  /** 微信通道是否开启（父组件从 /api/auth/channels 取） */
  wechatEnabled: boolean
}>()

const emit = defineEmits<{
  /** 钉钉容器内免登成功（父组件跳转） */
  (e: 'success'): void
}>()

const message = useMessage()
const authStore = useAuthStore()
const router = useRouter()
const route = useRoute()

const dingtalkEnabled = isDingTalkEnabled()
const wechatLoading = ref(false)

async function onWechatLogin() {
  wechatLoading.value = true
  try {
    await redirectToWechatAuth()
    // redirectToWechatAuth 内部 window.location.href 跳转，下面代码正常不会执行
  } catch (e) {
    message.error((e as Error).message || '微信登录跳转失败')
  } finally {
    wechatLoading.value = false
  }
}

async function onDingTalkLogin() {
  const inClient = isDingTalkClient()
  if (inClient) {
    try {
      const authCode = await requestDingTalkAuthCode()
      await authStore.loginByDingTalk(authCode, 'jsapi')
      message.success('钉钉登录成功')
      emit('success')
      const redirect = (route.query.redirect as string) || '/chat'
      router.push(redirect)
    } catch (e) {
      message.error((e as Error).message || '钉钉免登失败')
    }
    return
  }
  // 外部浏览器：OAuth2 跳转降级
  try {
    redirectToDingTalkAuth('dt')
  } catch (e) {
    message.error((e as Error).message)
  }
}
</script>

<style lang="scss" scoped>
.qr-tab {
  padding: 16px 0;
}
.qr-tab__empty {
  text-align: center;
  color: var(--color-text-tertiary);
  padding: 40px 0;
}
.qr-tab__btn {
  height: 48px;
  margin-bottom: 12px;
  font-size: var(--font-size-md);
  font-weight: var(--font-weight-semibold);

  &--wechat {
    background: #07c160;
    color: #fff;
    border: none;
    &:hover { background: #06ad56; color: #fff; }
  }
  &--dingtalk {
    background: #1677ff;
    color: #fff;
    border: none;
    &:hover { background: #0e6bdb; color: #fff; }
  }
}
.qr-tab__hint {
  text-align: center;
  font-size: 12px;
  color: var(--color-text-tertiary);
  margin-top: 8px;
}
</style>
