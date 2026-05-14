<template>
  <div class="relative">
    <button
      @click="showDropdown = !showDropdown"
      class="p-2 rounded-md bg-gray-100 dark:bg-dark-hover hover:bg-gray-200 dark:hover:bg-[#383838] transition-colors"
      title="最近打开"
    >
      <Clock :size="18" />
    </button>
    <transition name="fade">
      <div
        v-if="showDropdown"
        class="absolute right-0 mt-2 w-80 bg-white dark:bg-dark-panel rounded-xl shadow-2xl border border-gray-200 dark:border-dark-border z-50 overflow-hidden"
      >
        <div class="px-4 py-2 border-b border-gray-200 dark:border-dark-border font-medium text-sm">
        最近打开
        </div>
        <div class="max-h-96 overflow-y-auto">
          <div
            v-for="file in recentStore.recentFiles"
            :key="file.id"
            @click="openFile(file.id)"
            class="flex items-center gap-3 px-4 py-2 hover:bg-gray-100 dark:hover:bg-dark-hover cursor-pointer transition-colors"
          >
            <component :is="getFileIcon(file.icon || file.type)" :size="20" :class="getFileColor(file.icon || file.type)" />
            <div class="flex-1 min-w-0">
           <div class="text-sm truncate">{{ file.name }}</div>
          <div class="text-xs text-gray-500 truncate">{{ file.path }}</div>
          </div>
          </div>
          <div v-if="recentStore.recentFiles.length === 0" class="px-4 py-8 text-center text-gray-500 text-sm">
            暂无最近打开的文件
        </div>
        </div>
     <div class="px-4 py-2 border-t border-gray-200 dark:border-dark-border text-right">
       <button
            @click="recentStore.clearRecents()"
            class="text-xs text-gray-500 hover:text-red-500 transition-colors"
          >
            清空历史
          </button>
        </div>
      </div>
    </transition>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { Clock, FileText, Folder, Image, Code } from 'lucide-vue-next'
import { useRecentStore } from '@/stores/recentStore'
import { useFileStore } from '@/stores/fileStore'

const recentStore = useRecentStore()
const fileStore = useFileStore()
const showDropdown = ref(false)

function openFile(id: string) {
  const file = fileStore.files.find(f => f.id === id)
  if (file) {
    // Trigger the file click handler from parent
    // We'll emit an event instead
    emit('open-file', file)
  }
  showDropdown.value = false
}
function getFileIcon(type: string) {
  const iconMap: Record<string, any> = {
    word: FileText,
    excel: FileText,
    design: FileText,
    folder: Folder,
    image: Image,
    code: Code,
    file: FileText
  }
  return iconMap[type] || FileText
}

function getFileColor(type: string): string {
  const colorMap: Record<string, string> = {
    word: 'text-blue-500',
    excel: 'text-green-600',
    design: 'text-purple-500',
    folder: 'text-yellow-500',
    image: 'text-orange-500',
    code: 'text-yellow-600',
    file: 'text-gray-500'
  }
  return colorMap[type] || 'text-gray-500'
}

const emit = defineEmits<{
  'open-file': [file: any]
}>()
</script>
