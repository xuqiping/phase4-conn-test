<template>
  <transition name="fade">
    <div v-if="clipboardStore.isQuickPanelOpen" class="fixed inset-0 z-[70] flex items-start justify-center bg-black/20 pt-24" @click="clipboardStore.closeQuickPanel">
      <div class="w-[460px] rounded-2xl border border-gray-200 bg-white p-3 shadow-2xl dark:border-dark-border dark:bg-dark-panel" @click.stop>
        <input
          ref="inputRef"
          v-model="clipboardStore.searchQuery"
          class="w-full rounded-lg border border-gray-200 bg-gray-50 px-3 py-2 text-sm outline-none focus:border-primary dark:border-dark-border dark:bg-dark-hover"
          placeholder="搜索剪贴板历史..."
          @input="clipboardStore.searchItems"
          @keydown.down.prevent="moveSelection(1)"
          @keydown.up.prevent="moveSelection(-1)"
          @keydown.enter.prevent="confirmSelection($event.shiftKey)"
          @keydown.escape.prevent="clipboardStore.closeQuickPanel"
        />

        <div class="mt-3 max-h-80 overflow-auto space-y-2">
          <button
            v-for="(item, index) in clipboardStore.items"
            :key="item.id"
            :class="[
              'w-full rounded-lg px-3 py-2 text-left text-sm',
              index === selectedIndex ? 'bg-primary/10 text-primary' : 'hover:bg-gray-50 dark:hover:bg-dark-hover'
            ]"
            @mouseenter="selectedIndex = index"
            @click="confirmSelection(false)"
          >
            <div class="font-medium">{{ item.title }}</div>
            <div class="truncate text-xs text-gray-500">{{ item.summary }}</div>
          </button>
        </div>

        <div class="mt-3 flex justify-between border-t border-gray-100 pt-2 text-xs text-gray-400 dark:border-dark-border">
          <span>Enter 粘贴 · Shift+Enter 纯文本 · Esc 关闭</span>
          <button @click="clipboardStore.closeQuickPanel">关闭</button>
        </div>
      </div>
    </div>
  </transition>
</template>

<script setup lang="ts">
import { nextTick, ref, watch } from 'vue'
import { useClipboardStore } from '../stores/clipboardStore'

const clipboardStore = useClipboardStore()
const selectedIndex = ref(0)
const inputRef = ref<HTMLInputElement | null>(null)

watch(() => clipboardStore.isQuickPanelOpen, async (open) => {
  if (open) {
    selectedIndex.value = 0
    await clipboardStore.loadItems()
    await nextTick()
    inputRef.value?.focus()
  }
})

function moveSelection(delta: number) {
  if (clipboardStore.items.length === 0) return
  selectedIndex.value = (selectedIndex.value + delta + clipboardStore.items.length) % clipboardStore.items.length
}

async function confirmSelection(plainText: boolean) {
  const item = clipboardStore.items[selectedIndex.value]
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
