<template>
  <div
    data-test="entitlement-status"
    class="max-w-[360px] rounded-lg border px-3 py-2 text-xs shadow-sm"
    :class="statusClasses"
  >
    <div class="flex items-center justify-between gap-3">
      <span data-test="entitlement-status-title" class="font-medium whitespace-nowrap">
        {{ statusTitle }}
      </span>
      <span v-if="statusDetail" class="truncate text-[11px] opacity-80" :title="statusDetail">
        {{ statusDetail }}
      </span>
    </div>

    <div v-if="showModuleList" class="mt-2 flex flex-wrap gap-1.5">
      <span
        v-for="module in moduleStates"
        :key="module.code"
        :data-test="`entitlement-module-${module.code}`"
        class="rounded px-2 py-0.5"
        :class="module.allowed ? 'bg-emerald-500/15 text-emerald-600 dark:text-emerald-300' : 'bg-gray-500/15 text-gray-500 dark:text-gray-400'"
        :title="module.reason || undefined"
      >
        {{ module.label }}：{{ module.allowed ? '已授权' : '未授权' }}
      </span>
    </div>

    <div v-if="offlineCacheLabel" class="mt-1 text-[11px] opacity-80">
      {{ offlineCacheLabel }}
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useAuthStore } from '@/stores/authStore'
import { useCommercialAuthStore } from '@/stores/commercialAuthStore'
import type { ModuleCode } from '@/api/commercialAuth'

const MODULES: Array<{ code: ModuleCode; label: string }> = [
  { code: 'files', label: '文件' },
  { code: 'processes', label: '进程' },
  { code: 'clipboard', label: '剪贴板' },
  { code: 'work-report', label: '工作汇报' }
]

const authStore = useAuthStore()
const commercialAuthStore = useCommercialAuthStore()

const usableClientAuthorization = computed(() => {
  const authorization = commercialAuthStore.clientAuthorization
  if (!authStore.isAuthenticated || !authorization) {
    return null
  }
  if (!authorization.onlineRequired && authorization.offlineUsableUntil
    && new Date(authorization.offlineUsableUntil).getTime() <= Date.now()) {
    return null
  }
  return authorization
})

const statusTitle = computed(() => {
  if (commercialAuthStore.error) {
    return '授权异常'
  }
  if (authStore.isAuthenticated) {
    if (isPendingReview.value) {
      return '等待管理员审核'
    }
    return usableClientAuthorization.value ? '商业授权' : '授权状态初始化中'
  }
  const trialStatus = commercialAuthStore.trialStatus
  if (trialStatus?.inFullTrial) {
    return '匿名 7 天全功能试用中'
  }
  if (trialStatus?.freeModuleCode) {
    return '匿名免费模块'
  }
  if (trialStatus?.trialExpired) {
    return '匿名试用已过期'
  }
  return '授权状态初始化中'
})

const statusDetail = computed(() => {
  if (commercialAuthStore.error) {
    return commercialAuthStore.error
  }
  if (authStore.isAuthenticated) {
    if (isPendingReview.value) {
      return 'pending_review，等待管理员审核'
    }
    return usableClientAuthorization.value ? '' : '正在获取登录授权快照'
  }
  const trialStatus = commercialAuthStore.trialStatus
  if (trialStatus?.inFullTrial) {
    return trialStatus.trialExpiresAt ? `试用截止 ${formatDateTime(trialStatus.trialExpiresAt)}` : '试用期内三模块可用'
  }
  if (trialStatus?.freeModuleCode) {
    return moduleLabel(trialStatus.freeModuleCode)
  }
  if (trialStatus?.trialExpired) {
    return '未选择免费模块'
  }
  return ''
})

const isPendingReview = computed(() => {
  return authStore.isAuthenticated && (
    authStore.user?.status === 'pending_review'
    || usableClientAuthorization.value?.accountStatus === 'pending_review'
  )
})

const showModuleList = computed(() => Boolean(usableClientAuthorization.value && !isPendingReview.value))

const moduleStates = computed(() => {
  const modules = usableClientAuthorization.value?.modules ?? []
  return MODULES.map(module => {
    const access = modules.find(item => item.moduleCode === module.code)
    return {
      code: module.code,
      label: module.label,
      allowed: access?.allowed ?? false,
      reason: access?.reason ?? null
    }
  })
})

const offlineCacheLabel = computed(() => {
  const authorization = usableClientAuthorization.value
  if (!authorization || authorization.onlineRequired || !authorization.offlineUsableUntil) {
    return ''
  }
  return `离线缓存至 ${formatDateTime(authorization.offlineUsableUntil)}`
})

const statusClasses = computed(() => {
  if (commercialAuthStore.error) {
    return 'border-red-200 bg-red-50 text-red-700 dark:border-red-900/60 dark:bg-red-950/30 dark:text-red-300'
  }
  if (usableClientAuthorization.value && !isPendingReview.value) {
    return 'border-emerald-200 bg-emerald-50 text-emerald-700 dark:border-emerald-900/60 dark:bg-emerald-950/30 dark:text-emerald-300'
  }
  if (isPendingReview.value || (!authStore.isAuthenticated && commercialAuthStore.trialStatus?.trialExpired)) {
    return 'border-amber-200 bg-amber-50 text-amber-700 dark:border-amber-900/60 dark:bg-amber-950/30 dark:text-amber-300'
  }
  return 'border-primary/20 bg-primary/10 text-primary'
})

function moduleLabel(moduleCode: ModuleCode): string {
  return MODULES.find(module => module.code === moduleCode)?.label ?? moduleCode
}

function formatDateTime(value: string): string {
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit'
  }).format(new Date(value))
}
</script>
