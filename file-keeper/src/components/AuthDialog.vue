<template>
  <transition name="fade">
    <div
      v-if="show"
      data-test="auth-dialog"
      class="fixed inset-0 z-50 flex items-center justify-center bg-black/40 backdrop-blur-sm p-4"
      @click="$emit('close')"
    >
      <div
        class="bg-white dark:bg-dark-panel w-full max-w-md rounded-xl shadow-2xl border border-gray-200 dark:border-dark-border overflow-hidden"
        @click.stop
      >
        <div class="px-6 py-4 border-b border-gray-200 dark:border-dark-border flex items-center justify-between bg-gray-50 dark:bg-dark-hover">
          <div>
            <h2 class="text-base font-semibold text-gray-800 dark:text-gray-100">
              {{ activeTab === 'login' ? '账号登录' : '账号注册' }}
            </h2>
            <p class="text-xs text-gray-500 mt-1">
              登录后可使用管理员授予的商业授权模块
            </p>
          </div>
          <button
            class="text-gray-400 hover:text-gray-700 dark:hover:text-gray-200 transition-colors p-1 rounded-md hover:bg-gray-200 dark:hover:bg-[#3d3d3d]"
            @click="$emit('close')"
          >
            <X :size="18" />
          </button>
        </div>

        <div class="px-6 pt-5">
          <div class="grid grid-cols-2 gap-2 rounded-lg bg-gray-100 dark:bg-dark-hover p-1 text-sm">
            <button
              type="button"
              :class="tabClass(activeTab === 'login')"
              @click="activeTab = 'login'"
            >
              登录
            </button>
            <button
              data-test="auth-register-tab"
              type="button"
              :class="tabClass(activeTab === 'register')"
              @click="activeTab = 'register'"
            >
              注册
            </button>
          </div>
        </div>

        <div class="p-6">
          <LoginForm
            v-if="activeTab === 'login'"
            :base-url="baseUrl"
            @success="$emit('authenticated')"
          />
          <RegisterForm v-else :base-url="baseUrl" />
        </div>
      </div>
    </div>
  </transition>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import { X } from 'lucide-vue-next'
import LoginForm from './LoginForm.vue'
import RegisterForm from './RegisterForm.vue'

const props = defineProps<{
  show: boolean
  baseUrl: string
}>()

defineEmits<{
  close: []
  authenticated: []
}>()

const activeTab = ref<'login' | 'register'>('login')

watch(() => props.show, (newShow) => {
  if (newShow) {
    activeTab.value = 'login'
  }
})

function tabClass(active: boolean) {
  return [
    'px-3 py-2 rounded-md transition-colors font-medium',
    active
      ? 'bg-white dark:bg-dark-panel text-primary shadow-sm'
      : 'text-gray-500 hover:text-gray-800 dark:text-gray-400 dark:hover:text-gray-200'
  ]
}
</script>
