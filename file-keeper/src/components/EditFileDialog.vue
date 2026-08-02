<template>
  <transition name="fade">
    <div
      v-if="visible"
      class="fixed inset-0 z-50 flex items-center justify-center bg-black/40 backdrop-blur-sm p-4"
      @click="handleCancel"
    >
      <div
        class="bg-white dark:bg-dark-panel w-full max-w-md rounded-xl shadow-2xl border border-gray-200 dark:border-dark-border overflow-hidden"
        @click.stop
      >
        <!-- Header -->
        <div class="px-6 py-4 border-b border-gray-200 dark:border-dark-border flex items-center justify-between bg-gray-50 dark:bg-dark-hover">
          <h2 class="text-base font-semibold text-gray-800 dark:text-gray-100">编辑文件信息</h2>
          <button
            @click="handleCancel"
            class="text-gray-400 hover:text-gray-700 dark:hover:text-gray-200 transition-colors p-1 rounded-md hover:bg-gray-200 dark:hover:bg-[#3d3d3d]"
          >
            <X :size="18" />
          </button>
        </div>

        <!-- Content -->
        <div class="p-6 space-y-4">
          <!-- Icon Selector -->
          <div>
            <label class="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-2">图标</label>
            <div class="flex flex-wrap gap-2">
              <button
                v-for="opt in iconOptions"
                :key="opt.value"
                @click="selectedIcon = opt.value"
                :class="[
                  'flex items-center space-x-1 px-3 py-1.5 rounded-md text-xs font-medium transition-colors border',
                  selectedIcon === opt.value
                    ? 'border-primary bg-primary/10 text-primary'
                    : 'border-gray-200 dark:border-dark-border text-gray-600 dark:text-gray-400 hover:border-gray-300 dark:hover:border-[#555]'
                ]"
              >
                <component :is="opt.icon" :size="14" />
                <span>{{ opt.label }}</span>
              </button>
            </div>
          </div>

          <!-- Name -->
          <div>
            <label class="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-2">名称</label>
            <input
              v-model="editName"
              type="text"
              maxlength="255"
              class="w-full px-3 py-2 bg-gray-100 dark:bg-dark-hover border border-gray-200 dark:border-dark-border focus:border-primary focus:bg-white dark:focus:bg-dark-bg rounded-md outline-none text-sm transition-all"
            />
          </div>

          <!-- Path (read-only) -->
          <div>
            <label class="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-2">路径</label>
            <div class="relative">
              <input
                :value="file.path"
                type="text"
                disabled
                class="w-full px-3 py-2 pr-8 bg-gray-200 dark:bg-[#333] border border-gray-200 dark:border-dark-border rounded-md text-sm text-gray-500 cursor-not-allowed"
              />
              <Lock :size="14" class="absolute right-3 top-1/2 -translate-y-1/2 text-gray-400" />
            </div>
          </div>

          <!-- Tags -->
          <div>
            <label class="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-2">标签</label>
            <div class="flex flex-wrap gap-1.5 mb-2">
              <span
                v-for="(tag, index) in editTags"
                :key="index"
                class="inline-flex items-center space-x-1 px-2 py-1 bg-primary/10 text-primary text-xs rounded-full border border-primary/20"
              >
                <span>{{ tag }}</span>
                <button @click="removeTag(index)" class="hover:text-red-500 transition-colors">
                  <X :size="12" />
                </button>
              </span>
              <button
                v-if="editTags.length < 10 && !showTagInput"
                @click="showTagInput = true"
                class="inline-flex items-center space-x-1 px-2 py-1 bg-gray-100 dark:bg-dark-hover text-gray-500 text-xs rounded-full border border-dashed border-gray-300 dark:border-[#555] hover:border-primary hover:text-primary transition-colors"
              >
                <Plus :size="12" />
                <span>添加</span>
              </button>
            </div>
            <input
              v-if="showTagInput"
              ref="tagInputRef"
              v-model="newTagValue"
              type="text"
              maxlength="20"
              placeholder="输入标签名，回车确认"
              class="w-full px-3 py-1.5 text-xs bg-gray-100 dark:bg-dark-hover border border-primary rounded-md outline-none"
              @keyup.enter="addTag"
              @keyup.escape="cancelTagInput"
              @blur="addTag"
            />
          </div>

          <!-- Group -->
          <div>
            <label class="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-2">所属分组</label>
            <select
              v-model="selectedGroupId"
              class="w-full px-3 py-2 bg-gray-100 dark:bg-dark-hover border border-gray-200 dark:border-dark-border focus:border-primary rounded-md outline-none text-sm transition-all"
            >
              <option v-for="group in groupStore.groups" :key="group.id" :value="group.id">
                {{ group.name }}
              </option>
            </select>
          </div>
        </div>

        <!-- Footer -->
        <div class="px-6 py-4 border-t border-gray-200 dark:border-dark-border bg-gray-50 dark:bg-dark-hover flex justify-end space-x-3">
          <button
            @click="handleCancel"
            class="px-4 py-2 text-sm text-gray-600 dark:text-gray-300 hover:bg-gray-200 dark:hover:bg-[#3d3d3d] rounded-md transition-colors font-medium"
          >
            取消
          </button>
          <button
            @click="handleSave"
            class="px-4 py-2 text-sm bg-primary hover:bg-[#369b6e] text-white rounded-md transition-colors font-medium shadow-sm shadow-primary/20"
          >
            保存
          </button>
        </div>
      </div>
    </div>
  </transition>
</template>

<script setup lang="ts">
import { ref, watch, nextTick } from 'vue'
import { X, Lock, Plus, FileText, Folder, Image, Code, Box } from 'lucide-vue-next'
import { useGroupStore } from '../stores/groupStore'
import type { FileItem } from '../types/file'

const props = defineProps<{
  visible: boolean
  file: FileItem
}>()

const emit = defineEmits<{
  close: []
  saved: [updates: Partial<FileItem>]
}>()

const groupStore = useGroupStore()

const iconOptions = [
  { value: 'file', label: '文件', icon: FileText },
  { value: 'folder', label: '文件夹', icon: Folder },
  { value: 'image', label: '图片', icon: Image },
  { value: 'code', label: '代码', icon: Code },
  { value: 'word', label: '文档', icon: FileText },
  { value: 'design', label: '设计', icon: Box },
]

const selectedIcon = ref(props.file.icon || 'file')
const editName = ref(props.file.name)
const editTags = ref<string[]>([...props.file.tags])
const selectedGroupId = ref(props.file.groupId)

// Tag input
const showTagInput = ref(false)
const newTagValue = ref('')
const tagInputRef = ref<HTMLInputElement | null>(null)

// Reset form when file changes
watch(() => props.file.id, () => {
  selectedIcon.value = props.file.icon || 'file'
  editName.value = props.file.name
  editTags.value = [...props.file.tags]
  selectedGroupId.value = props.file.groupId
  showTagInput.value = false
  newTagValue.value = ''
})

// Focus tag input when shown
watch(showTagInput, async (show) => {
  if (show) {
    await nextTick()
    tagInputRef.value?.focus()
  }
})

function addTag() {
  const tag = newTagValue.value.trim()
  if (tag && !editTags.value.includes(tag) && editTags.value.length < 10) {
    editTags.value.push(tag)
  }
  newTagValue.value = ''
  showTagInput.value = false
}

function cancelTagInput() {
  newTagValue.value = ''
  showTagInput.value = false
}

function removeTag(index: number) {
  editTags.value.splice(index, 1)
}

function handleSave() {
  if (!editName.value.trim()) return

  emit('saved', {
    icon: selectedIcon.value,
    name: editName.value.trim(),
    tags: [...editTags.value],
    groupId: selectedGroupId.value
  })
  emit('close')
}

function handleCancel() {
  emit('close')
}
</script>
