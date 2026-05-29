<template>
  <section class="flex min-h-0 flex-1 flex-col overflow-hidden bg-gray-50 dark:bg-dark-bg">
    <ClipboardToolbar
      v-model:search-query="clipboardStore.searchQuery"
      v-model:kind="clipboardStore.kindFilter"
      @search="clipboardStore.searchItems"
    />

    <div class="grid min-h-0 flex-1 grid-cols-[180px_minmax(320px,1fr)_360px] overflow-hidden">
      <aside class="space-y-3 border-r border-gray-200 p-3 dark:border-dark-border">
        <h2 class="text-base font-semibold">剪贴板</h2>
        <nav class="space-y-1 text-sm">
          <button v-for="filter in filters" :key="filter.kind" class="block w-full rounded px-2 py-1.5 text-left hover:bg-gray-100 dark:hover:bg-dark-hover" @click="setKind(filter.kind)">
            {{ filter.label }}
          </button>
        </nav>
        <ClipboardStorageUsage :usage="clipboardStore.storageUsage" @clear-cache="clearNonTextCache" />
        <ClipboardSecurityEvents />
        <button class="w-full rounded bg-gray-100 px-3 py-2 text-sm dark:bg-dark-hover" @click="showSettings = true">
          剪贴板设置
        </button>
      </aside>

      <ClipboardList
        :items="clipboardStore.items"
        :selected-item-id="clipboardStore.selectedItemId"
        @select="selectItem"
      />

      <ClipboardPreview
        :item="clipboardStore.selectedItem"
        :detail="clipboardStore.selectedDetail"
        @copy="clipboardStore.copyItem"
        @paste="clipboardStore.pasteItem"
        @delete="clipboardStore.deleteItem"
      />
    </div>

    <ClipboardSettings
      v-if="showSettings"
      :settings="clipboardStore.settings"
      @close="showSettings = false"
      @save="saveSettings"
    />
  </section>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { clearClipboardHistory } from '../api/clipboard'
import { useClipboardStore } from '../stores/clipboardStore'
import ClipboardList from './ClipboardList.vue'
import ClipboardPreview from './ClipboardPreview.vue'
import ClipboardSecurityEvents from './ClipboardSecurityEvents.vue'
import ClipboardSettings from './ClipboardSettings.vue'
import ClipboardStorageUsage from './ClipboardStorageUsage.vue'
import ClipboardToolbar from './ClipboardToolbar.vue'
import type { ClipboardKind, ClipboardSettings as ClipboardSettingsType } from '../types/clipboard'

const clipboardStore = useClipboardStore()
const showSettings = ref(false)

const filters: Array<{ kind: ClipboardKind | 'all'; label: string }> = [
  { kind: 'all', label: '全部' },
  { kind: 'text', label: '文本' },
  { kind: 'html', label: '富文本' },
  { kind: 'image', label: '图片' },
  { kind: 'file', label: '文件' },
  { kind: 'url', label: '链接' },
  { kind: 'color', label: '颜色' },
  { kind: 'security_event', label: '安全事件' }
]

onMounted(async () => {
  await Promise.all([
    clipboardStore.loadItems(),
    clipboardStore.loadSettings(),
    clipboardStore.refreshStorageUsage()
  ])
})

function setKind(kind: ClipboardKind | 'all') {
  clipboardStore.kindFilter = kind
  clipboardStore.searchItems()
}

function selectItem(id: string) {
  clipboardStore.loadDetail(id)
}

async function saveSettings(settings: ClipboardSettingsType) {
  await clipboardStore.updateSettings(settings)
  showSettings.value = false
}

async function clearNonTextCache() {
  await clearClipboardHistory('non_text_cache')
  await Promise.all([
    clipboardStore.refreshStorageUsage(),
    clipboardStore.loadItems()
  ])
}
</script>
