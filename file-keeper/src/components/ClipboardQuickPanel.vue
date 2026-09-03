<template>
  <transition name="fade">
    <div v-if="clipboardStore.isQuickPanelOpen" class="fixed inset-0 z-[70] flex items-start justify-center bg-black/20 pt-24" @click="clipboardStore.closeQuickPanel">
      <div class="w-[460px] rounded-2xl border border-gray-200 bg-white p-3 shadow-2xl dark:border-dark-border dark:bg-dark-panel" @click.stop>
        <input
          ref="inputRef"
          v-model="clipboardStore.quickPanelSearchQuery"
          class="w-full rounded-lg border border-gray-200 bg-gray-50 px-3 py-2 text-sm outline-none focus:border-primary dark:border-dark-border dark:bg-dark-hover"
          :placeholder="t('clipboard.quickPanelSearchPlaceholder')"
          @input="clipboardStore.searchQuickPanelItems"
          @keydown.down.prevent="moveSelection(1)"
          @keydown.up.prevent="moveSelection(-1)"
          @keydown.enter.prevent="confirmSelection($event.shiftKey)"
          @keydown.escape.prevent="clipboardStore.closeQuickPanel"
        />

        <div class="mt-3 max-h-80 overflow-auto space-y-2">
          <button
            v-for="(item, index) in clipboardStore.quickPanelItems"
            :key="item.id"
            :class="[
              'w-full rounded-lg border px-3 py-2 text-left text-sm transition-colors',
              index === selectedIndex ? 'border-primary/30 bg-white text-gray-900 shadow-sm dark:bg-dark-panel dark:text-gray-100' : 'border-transparent hover:bg-gray-50 dark:hover:bg-dark-hover'
            ]"
            @mouseenter="selectedIndex = index"
            @click="confirmSelection(false)"
          >
            <div class="font-medium">{{ item.title }}</div>
            <div class="truncate text-xs text-gray-500">{{ item.summary }}</div>
          </button>
        </div>

        <div class="mt-3 flex justify-between border-t border-gray-100 pt-2 text-xs text-gray-400 dark:border-dark-border">
          <span>{{ t('clipboard.quickPanelHint') }}</span>
          <button @click="clipboardStore.closeQuickPanel">{{ t('clipboard.actions.close') }}</button>
        </div>
      </div>
    </div>
  </transition>
</template>

<script setup lang="ts">
import { nextTick, ref, watch } from 'vue'
import { useI18n } from '../composables/useI18n'
import { useClipboardStore } from '../stores/clipboardStore'

const clipboardStore = useClipboardStore()
const { t } = useI18n()
const selectedIndex = ref(0)
const inputRef = ref<HTMLInputElement | null>(null)

watch(() => clipboardStore.isQuickPanelOpen, async (open) => {
  if (open) {
    selectedIndex.value = 0
    await clipboardStore.loadQuickPanelItems()
    await nextTick()
    inputRef.value?.focus()
  }
})

function moveSelection(delta: number) {
  if (clipboardStore.quickPanelItems.length === 0) return
  selectedIndex.value = (selectedIndex.value + delta + clipboardStore.quickPanelItems.length) % clipboardStore.quickPanelItems.length
}

async function confirmSelection(plainText: boolean) {
  const item = clipboardStore.quickPanelItems[selectedIndex.value]
  if (!item) return
  await clipboardStore.pasteItem(item.id, plainText ? 'plain_text' : 'original')
  clipboardStore.closeQuickPanel()
}
</script>

<style scoped>
.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.15s ease;
}
.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}
</style>
