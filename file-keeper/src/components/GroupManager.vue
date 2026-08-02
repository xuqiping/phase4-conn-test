<template>
  <transition name="fade">
    <div
      v-if="visible"
      class="fixed inset-0 z-50 flex items-center justify-center bg-black/40 backdrop-blur-sm p-4"
      @click="close"
    >
      <div
        class="bg-white dark:bg-dark-panel w-full max-w-md rounded-xl shadow-2xl border border-gray-200 dark:border-dark-border overflow-hidden flex flex-col"
        @click.stop
      >
        <!-- Header -->
        <div class="px-6 py-4 border-b border-gray-200 dark:border-dark-border flex items-center justify-between bg-gray-50 dark:bg-dark-hover">
          <h2 class="text-base font-semibold text-gray-800 dark:text-gray-100">分组管理</h2>
          <button
            @click="close"
            class="text-gray-400 hover:text-gray-700 dark:hover:text-gray-200 transition-colors p-1 rounded-md hover:bg-gray-200 dark:hover:bg-[#3d3d3d]"
          >
            <X :size="18" />
          </button>
        </div>

        <!-- Group List -->
        <div class="p-4 space-y-2 max-h-80 overflow-y-auto">
          <div
            v-for="group in groupStore.sortedGroups"
            :key="group.id"
            class="flex items-center justify-between p-3 rounded-lg border border-gray-200 dark:border-dark-border bg-white dark:bg-dark-panel group"
          >
            <div class="flex items-center space-x-3 min-w-0">
              <Lock v-if="group.id === 'all' || group.id === 'recent'" :size="14" class="text-gray-400 flex-shrink-0" />
              <Folder v-else :size="14" class="text-yellow-500 flex-shrink-0" />

              <!-- Inline rename or display -->
              <template v-if="renamingId === group.id">
                <input
                  ref="renameInputRef"
                  v-model="renameValue"
                  type="text"
                  class="flex-1 px-2 py-1 text-sm bg-gray-100 dark:bg-dark-hover border border-primary rounded outline-none"
                  @keyup.enter="confirmRename(group.id)"
                  @keyup.escape="cancelRename"
                  @blur="confirmRename(group.id)"
                />
              </template>
              <template v-else>
                <span class="text-sm font-medium text-gray-800 dark:text-gray-200 truncate">{{ group.name }}</span>
              </template>

              <span class="text-xs text-gray-400 flex-shrink-0">{{ getFileCount(group.id) }} 个文件</span>
            </div>

            <div v-if="group.id !== 'all' && group.id !== 'recent'" class="flex items-center space-x-1 opacity-0 group-hover:opacity-100 transition-opacity">
              <button
                @click="startRename(group)"
                class="p-1 rounded hover:bg-gray-100 dark:hover:bg-[#383838] text-gray-400 hover:text-gray-600 dark:hover:text-gray-200 transition-colors"
                title="重命名"
              >
                <Pencil :size="14" />
              </button>
              <button
                @click="handleDelete(group)"
                class="p-1 rounded hover:bg-red-50 dark:hover:bg-red-900/20 text-gray-400 hover:text-red-500 transition-colors"
                title="删除"
              >
                <Trash2 :size="14" />
              </button>
            </div>

            <Lock v-else :size="14" class="text-gray-300 flex-shrink-0" />
          </div>
        </div>

        <!-- Footer -->
        <div class="px-6 py-4 border-t border-gray-200 dark:border-dark-border bg-gray-50 dark:bg-dark-hover flex justify-between">
          <button
            @click="handleAddGroup"
            class="flex items-center space-x-1 px-3 py-2 text-sm text-primary hover:bg-primary/10 rounded-md transition-colors font-medium"
          >
            <Plus :size="14" />
            <span>新建分组</span>
          </button>
          <button
            @click="close"
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
import { ref, nextTick } from 'vue'
import { X, Lock, Folder, Pencil, Trash2, Plus } from 'lucide-vue-next'
import { useGroupStore } from '../stores/groupStore'
import { useFileStore } from '../stores/fileStore'
import type { Group } from '../types/group'

defineProps<{ visible: boolean }>()
const emit = defineEmits<{
  close: []
  addGroup: []
}>()

const groupStore = useGroupStore()
const fileStore = useFileStore()

// Rename state
const renamingId = ref<string | null>(null)
const renameValue = ref('')
const renameInputRef = ref<HTMLInputElement | null>(null)

function getFileCount(groupId: string): number {
  if (groupId === 'all') return fileStore.files.length
  if (groupId === 'recent') {
    return fileStore.files.filter(f =>
      f.openCount > 20 || (f.lastOpened && Date.now() - f.lastOpened < 7 * 24 * 60 * 60 * 1000)
    ).length
  }
  return fileStore.files.filter(f => f.groupId === groupId).length
}

function startRename(group: Group) {
  renamingId.value = group.id
  renameValue.value = group.name
  nextTick(() => {
    renameInputRef.value?.focus()
    renameInputRef.value?.select()
  })
}

function confirmRename(groupId: string) {
  const trimmed = renameValue.value.trim()
  if (trimmed && trimmed !== groupStore.groups.find(g => g.id === groupId)?.name) {
    groupStore.updateGroup(groupId, { name: trimmed })
  }
  renamingId.value = null
  renameValue.value = ''
}

function cancelRename() {
  renamingId.value = null
  renameValue.value = ''
}

function handleDelete(group: Group) {
  const fileCount = getFileCount(group.id)
  const confirmed = confirm(
    `确定删除分组「${group.name}」？\n该分组下有 ${fileCount} 个文件，删除后它们将移至「全部」。`
  )
  if (!confirmed) return

  // Move all files in this group to 'all'
  fileStore.files
    .filter(f => f.groupId === group.id)
    .forEach(f => fileStore.updateFile(f.id, { groupId: 'all' }))

  groupStore.removeGroup(group.id)

  // If we're currently viewing the deleted group, switch to 'all'
  if (groupStore.currentGroupId === group.id) {
    groupStore.setCurrentGroup('all')
  }
}

function handleAddGroup() {
  emit('addGroup')
}

function close() {
  renamingId.value = null
  emit('close')
}
</script>
