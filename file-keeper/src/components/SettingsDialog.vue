<template>
  <transition name="fade">
    <div
      v-if="show"
      class="fixed inset-0 z-50 flex items-center justify-center bg-black/40 backdrop-blur-sm p-4"
      @click="$emit('close')"
    >
      <div
        class="bg-white dark:bg-dark-panel w-full max-w-md rounded-xl shadow-2xl border border-gray-200 dark:border-dark-border overflow-hidden"
        @click.stop
      >
        <!-- Header -->
        <div class="px-6 py-4 border-b border-gray-200 dark:border-dark-border flex items-center justify-between bg-gray-50 dark:bg-dark-hover">
          <h2 class="text-base font-semibold text-gray-800 dark:text-gray-100">设置</h2>
          <button
            @click="$emit('close')"
            class="text-gray-400 hover:text-gray-700 dark:hover:text-gray-200 transition-colors p-1 rounded-md hover:bg-gray-200 dark:hover:bg-[#3d3d3d]"
          >
            <X :size="18" />
          </button>
        </div>

        <!-- Tabs -->
        <div class="flex border-b border-gray-200 dark:border-dark-border bg-gray-50 dark:bg-dark-hover">
          <button
            v-for="tab in visibleTabs"
            :key="tab.key"
            @click="activeTab = tab.key"
            :class="[
              'flex-1 px-4 py-2 text-sm font-medium transition-colors',
              activeTab === tab.key
                ? 'text-primary border-b-2 border-primary bg-white dark:bg-dark-panel'
                : 'text-gray-600 dark:text-gray-400 hover:text-gray-800 dark:hover:text-gray-200'
            ]"
          >
            {{ tab.label }}
          </button>
        </div>

        <!-- Content -->
        <div class="p-6 space-y-6 max-h-[70vh] overflow-y-auto">
          <!-- 通用设置 -->
          <template v-if="activeTab === 'general'">
          <!-- Global Shortcut -->
          <div>
            <label class="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-2">
              全局快捷键
            </label>
          <div class="flex items-center space-x-2">
           <input
            v-model="localShortcut"
              @keydown="handleMainShortcutKeydown"
                placeholder="按下快捷键组合..."
        class="flex-1 px-3 py-2 bg-gray-100 dark:bg-dark-hover border border-gray-300 dark:border-dark-border rounded-md outline-none focus:border-primary text-sm"
                readonly
              />
            <button
                @click="clearShortcut"
              class="px-3 py-2 text-sm text-gray-600 dark:text-gray-300 hover:bg-gray-100 dark:hover:bg-dark-hover rounded-md transition-colors"
              >
                清除
              </button>
      </div>
            <p class="text-xs text-gray-500 mt-1">
              点击输入框后按下快捷键组合（如 Ctrl+Shift+K）
            </p>
          </div>

          <!-- Clipboard Shortcut -->
          <div>
            <label class="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-2">
              剪贴板面板快捷键
            </label>
            <div class="flex items-center space-x-2">
              <input
                v-model="localClipboardShortcut"
                @keydown="handleClipboardShortcutKeydown"
                placeholder="按下快捷键组合..."
                class="flex-1 px-3 py-2 bg-gray-100 dark:bg-dark-hover border border-gray-300 dark:border-dark-border rounded-md outline-none focus:border-primary text-sm"
                readonly
              />
              <button
                @click="clearClipboardShortcut"
                class="px-3 py-2 text-sm text-gray-600 dark:text-gray-300 hover:bg-gray-100 dark:hover:bg-dark-hover rounded-md transition-colors"
              >
                清除
              </button>
            </div>
            <p class="text-xs text-gray-500 mt-1">
              用于快速打开剪贴板历史面板（默认 Ctrl+Shift+V）
            </p>
          </div>

          <!-- Screenshot Shortcut -->
          <div>
            <label class="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-2">
              截图快捷键
            </label>
            <div class="flex items-center space-x-2">
              <input
                data-test="screenshot-shortcut"
                v-model="localScreenshotShortcut"
                @keydown="handleScreenshotShortcutKeydown"
                placeholder="按下快捷键组合..."
                class="flex-1 px-3 py-2 bg-gray-100 dark:bg-dark-hover border border-gray-300 dark:border-dark-border rounded-md outline-none focus:border-primary text-sm"
                readonly
              />
              <button
                @click="clearScreenshotShortcut"
                class="px-3 py-2 text-sm text-gray-600 dark:text-gray-300 hover:bg-gray-100 dark:hover:bg-dark-hover rounded-md transition-colors"
              >
                清除
              </button>
            </div>
            <p class="text-xs text-gray-500 mt-1">
              用于快速进入框选截图模式（默认 Ctrl+Shift+X）
            </p>
          </div>

          <!-- Minimize to Tray -->
          <div class="flex items-center justify-between">
        <div>
              <label class="text-sm font-medium text-gray-700 dark:text-gray-300">
                最小化到托盘
            </label>
        <p class="text-xs text-gray-500 mt-1">
                关闭窗口时隐藏到系统托盘而不是退出
              </p>
            </div>
        <button
           @click="localMinimizeToTray = !localMinimizeToTray"
              :class="[
           'relative inline-flex h-6 w-11 items-center rounded-full transition-colors',
                localMinimizeToTray ? 'bg-primary' : 'bg-gray-300 dark:bg-gray-600'
              ]"
            >
          <span
                :class="[
               'inline-block h-4 w-4 transform rounded-full bg-white transition-transform',
                  localMinimizeToTray ? 'translate-x-6' : 'translate-x-1'
                ]"
              />
            </button>
          </div>

          <!-- Theme -->
          <div>
            <label class="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-2">
            主题
            </label>
            <div class="flex space-x-2">
              <button
                @click="localTheme = 'light'"
                :class="[
                  'flex-1 px-3 py-2 text-sm rounded-md transition-colors',
               localTheme === 'light'
                ? 'bg-primary text-white'
             : 'bg-gray-100 dark:bg-dark-hover text-gray-700 dark:text-gray-300 hover:bg-gray-200 dark:hover:bg-[#383838]'
         ]"
              >
                浅色
              </button>
              <button
              @click="localTheme = 'dark'"
                :class="[
               'flex-1 px-3 py-2 text-sm rounded-md transition-colors',
               localTheme === 'dark'
                    ? 'bg-primary text-white'
                 : 'bg-gray-100 dark:bg-dark-hover text-gray-700 dark:text-gray-300 hover:bg-gray-200 dark:hover:bg-[#383838]'
                ]"
              >
                深色
              </button>
              <button
                @click="localTheme = 'auto'" 
                :class="[
                  'flex-1 px-3 py-2 text-sm rounded-md transition-colors',
           localTheme === 'auto'
                 ? 'bg-primary text-white'
                : 'bg-gray-100 dark:bg-dark-hover text-gray-700 dark:text-gray-300 hover:bg-gray-200 dark:hover:bg-[#383838]'
                ]"
       >
              跟随系统
              </button>
            </div>
            </div>
          </template>

          <!-- AI 模型设置 -->
          <template v-else-if="activeTab === 'ai'">
            <AiConfigSettings v-if="authStore.isAuthenticated" />
            <div
              v-else
              data-test="ai-login-prompt"
              class="flex flex-col items-center gap-3 py-8 text-center"
            >
              <p class="text-sm font-medium text-gray-800 dark:text-gray-100">{{ t('access.loginRequiredTitle') }}</p>
              <p class="text-xs text-gray-500 dark:text-gray-400">{{ t('access.aiLoginDescription') }}</p>
              <button
                data-test="ai-login-button"
                @click="$emit('login')"
                class="px-4 py-2 rounded-md bg-primary text-white hover:bg-[#369b6e] transition-colors text-sm font-medium"
              >
                {{ t('access.loginAction') }}
              </button>
            </div>
          </template>
        </div>

        <!-- Footer -->
        <div class="px-6 py-4 border-t border-gray-200 dark:border-dark-border bg-gray-50 dark:bg-dark-hover flex justify-end space-x-3">
          <button
            v-if="activeTab === 'general'"
            @click="$emit('close')"
            class="px-4 py-2 text-sm text-gray-600 dark:text-gray-300 hover:bg-gray-200 dark:hover:bg-[#3d3d3d] rounded-md transition-colors font-medium"
          >
            取消
          </button>
          <button
            v-if="activeTab === 'general'"
            data-test="save-settings"
            @click="handleSave"
            class="px-4 py-2 text-sm bg-primary hover:bg-[#369b6e] text-white rounded-md transition-colors font-medium shadow-sm shadow-primary/20"
          >
            保存
          </button>
          <button
            v-if="activeTab === 'ai'"
            @click="$emit('close')"
            class="px-4 py-2 text-sm text-gray-600 dark:text-gray-300 hover:bg-gray-200 dark:hover:bg-[#3d3d3d] rounded-md transition-colors font-medium"
          >
            关闭
          </button>
        </div>
      </div>
    </div>
  </transition>
</template>

<script setup lang="ts">
import { ref, watch, computed } from 'vue'
import { X } from 'lucide-vue-next'
import { useSettingsStore } from '../stores/settingsStore'
import { useAuthStore } from '../stores/authStore'
import { useI18n } from '../composables/useI18n'
import AiConfigSettings from './AiConfigSettings.vue'

const props = defineProps<{
  show: boolean
}>()

const emit = defineEmits<{
  close: []
  login: []
  save: [settings: { globalShortcut: string; clipboardShortcut: string; screenshotShortcut: string; minimizeToTray: boolean; theme: 'light' | 'dark' | 'auto' }]
}>()

const settingsStore = useSettingsStore()
const authStore = useAuthStore()
const { t } = useI18n()

const activeTab = ref('general')

const visibleTabs = computed(() => {
  return [
    { key: 'general', label: '通用' },
    { key: 'ai', label: 'AI 模型' }
  ]
})

const localShortcut = ref(settingsStore.settings.globalShortcut)
const localClipboardShortcut = ref(settingsStore.settings.clipboardShortcut)
const localScreenshotShortcut = ref(settingsStore.settings.screenshotShortcut)
const localMinimizeToTray = ref(settingsStore.settings.minimizeToTray)
const localTheme = ref(settingsStore.settings.theme)

// Reset local values when dialog opens
watch(() => props.show, (newShow) => {
  if (newShow) {
    activeTab.value = 'general'
    localShortcut.value = settingsStore.settings.globalShortcut
    localClipboardShortcut.value = settingsStore.settings.clipboardShortcut
    localScreenshotShortcut.value = settingsStore.settings.screenshotShortcut
    localMinimizeToTray.value = settingsStore.settings.minimizeToTray
    localTheme.value = settingsStore.settings.theme
  }
})

function formatShortcut(event: KeyboardEvent): string | null {
  event.preventDefault()

  const keys: string[] = []

  if (event.ctrlKey || event.metaKey) {
    keys.push(event.ctrlKey ? 'Ctrl' : 'Cmd')
  }
  if (event.altKey) {
    keys.push('Alt')
  }
  if (event.shiftKey) {
    keys.push('Shift')
  }

  if (event.key && !['Control', 'Alt', 'Shift', 'Meta'].includes(event.key)) {
    keys.push(event.key.toUpperCase())
  }

  if (keys.length < 2) return null

  return keys
    .map(k => k === 'Ctrl' || k === 'Cmd' ? 'CommandOrControl' : k)
    .join('+')
}

function handleMainShortcutKeydown(event: KeyboardEvent) {
  const shortcut = formatShortcut(event)
  if (shortcut) {
    localShortcut.value = shortcut
  }
}

function handleClipboardShortcutKeydown(event: KeyboardEvent) {
  const shortcut = formatShortcut(event)
  if (shortcut) {
    localClipboardShortcut.value = shortcut
  }
}

function handleScreenshotShortcutKeydown(event: KeyboardEvent) {
  const shortcut = formatShortcut(event)
  if (shortcut) {
    localScreenshotShortcut.value = shortcut
  }
}

function clearShortcut() {
  localShortcut.value = ''
}

function clearClipboardShortcut() {
  localClipboardShortcut.value = ''
}

function clearScreenshotShortcut() {
  localScreenshotShortcut.value = ''
}

function handleSave() {
  emit('save', {
    globalShortcut: localShortcut.value,
    clipboardShortcut: localClipboardShortcut.value,
    screenshotShortcut: localScreenshotShortcut.value,
    minimizeToTray: localMinimizeToTray.value,
    theme: localTheme.value
  })
}
</script>

<style scoped>
.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.2s ease;
}

.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}
</style>
