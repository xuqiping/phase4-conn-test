<template>
  <div data-test="free-module-selector" class="flex-1 overflow-auto bg-gray-50 p-6 dark:bg-dark-bg">
    <div class="mx-auto max-w-3xl rounded-xl border border-amber-200 bg-white p-6 shadow-sm dark:border-amber-900/60 dark:bg-dark-panel">
      <div class="mb-5">
        <p class="text-xs font-semibold uppercase tracking-wide text-amber-600 dark:text-amber-300">
          匿名授权
        </p>
        <h2 data-test="free-module-title" class="mt-2 text-xl font-semibold text-gray-900 dark:text-gray-100">
          {{ title }}
        </h2>
        <p data-test="free-module-description" class="mt-2 text-sm text-gray-600 dark:text-gray-300">
          {{ description }}
        </p>
        <p
          v-if="currentFreeModule"
          data-test="free-module-change-note"
          class="mt-2 text-sm text-amber-700 dark:text-amber-300"
        >
          每 30 天可更换一次。
        </p>
      </div>

      <div class="grid gap-3 md:grid-cols-4">
        <button
          v-for="module in modules"
          :key="module.code"
          :data-test="`free-module-option-${module.code}`"
          type="button"
          :disabled="commercialAuthStore.loading || currentFreeModule === module.code"
          class="rounded-lg border p-4 text-left transition-colors disabled:cursor-not-allowed disabled:opacity-70"
          :class="currentFreeModule === module.code
            ? 'border-primary bg-primary/10 text-primary'
            : 'border-gray-200 bg-gray-50 hover:border-primary/60 hover:bg-primary/5 dark:border-dark-border dark:bg-dark-hover dark:hover:border-primary/60'"
          @click="handleSelect(module.code)"
        >
          <span class="block text-sm font-semibold text-gray-900 dark:text-gray-100">
            {{ module.label }}
          </span>
          <span class="mt-1 block text-xs text-gray-500 dark:text-gray-400">
            {{ module.description }}
          </span>
          <span v-if="currentFreeModule === module.code" class="mt-3 inline-block rounded-full bg-primary/15 px-2 py-0.5 text-xs text-primary">
            已选择
          </span>
        </button>
      </div>

      <p v-if="errorMessage" data-test="free-module-error" class="mt-4 text-sm text-red-600 dark:text-red-300">
        {{ errorMessage }}
      </p>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { useCommercialAuthStore } from '@/stores/commercialAuthStore'
import type { ModuleCode } from '@/api/commercialAuth'

const props = defineProps<{
  baseUrl: string
}>()

const emit = defineEmits<{
  selected: []
}>()

const commercialAuthStore = useCommercialAuthStore()
const localError = ref<string | null>(null)

const modules: Array<{ code: ModuleCode; label: string; description: string }> = [
  { code: 'files', label: '文件管理', description: '继续使用文件收藏和快速打开能力。' },
  { code: 'processes', label: '进程管理', description: '继续查看并管理文件关联进程。' },
  { code: 'clipboard', label: '剪贴板', description: '继续使用剪贴板历史和截图能力。' },
  { code: 'work-report', label: '工作汇报', description: '继续使用工作记录、日报周报能力。' }
]

const currentFreeModule = computed(() => commercialAuthStore.trialStatus?.freeModuleCode ?? null)

const title = computed(() => {
  if (currentFreeModule.value) {
    return `当前免费模块：${moduleLabel(currentFreeModule.value)}`
  }
  return '匿名试用已过期，请选择一个免费模块'
})

const description = computed(() => {
  if (currentFreeModule.value) {
    return '如果需要使用其他模块，可以在下方申请更换免费模块。'
  }
  return '你可以选择一个模块长期免费使用，选择后会立即刷新授权状态。'
})

const errorMessage = computed(() => localError.value || commercialAuthStore.error)

async function handleSelect(moduleCode: ModuleCode) {
  localError.value = null
  try {
    if (currentFreeModule.value) {
      await commercialAuthStore.changeFreeModule(props.baseUrl, moduleCode)
    } else {
      await commercialAuthStore.selectFreeModule(props.baseUrl, moduleCode)
    }
    emit('selected')
  } catch (error) {
    localError.value = error instanceof Error ? error.message : String(error)
  }
}

function moduleLabel(moduleCode: ModuleCode): string {
  return modules.find(module => module.code === moduleCode)?.label ?? moduleCode
}
</script>
