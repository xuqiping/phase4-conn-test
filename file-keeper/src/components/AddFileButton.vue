<template>
  <button
    class="flex items-center space-x-1 bg-primary hover:bg-[#369b6e] text-white px-4 py-2 rounded-md text-sm font-medium transition-colors shadow-sm shadow-primary/20"
    @click="handleAddFile"
  >
    <Plus :size="16" />
    <span>添加文件</span>
  </button>
</template>

<script setup lang="ts">
import { Plus } from 'lucide-vue-next'
import { useFileStore } from '../stores/fileStore'
import { useGroupStore } from '../stores/groupStore'
import { pickFile, pickFolder, validatePath } from '../api/files'
import { deriveIconFromExt, resolveGroupId } from '../utils/file'

const fileStore = useFileStore()
const groupStore = useGroupStore()

async function handleAddFile() {
  try {
    const choice = confirm('添加文件？\n确定 = 文件\n取消 = 文件夹')

    let selectedPath: string | null

    if (choice) {
      selectedPath = await pickFile()
    } else {
      selectedPath = await pickFolder()
    }

    if (!selectedPath) {
      return
    }

    const isValid = await validatePath(selectedPath)
    if (!isValid) {
      alert('路径不存在或无法访问')
      return
    }

    const name = selectedPath.split(/[/\\]/).pop() || selectedPath
    const isFile = !!choice
    const type: 'file' | 'folder' = isFile ? 'file' : 'folder'
    const icon = isFile ? deriveIconFromExt(name) : 'folder'

    const newItem = await fileStore.addFile({
      name,
      path: selectedPath,
      type,
      icon,
      tags: [],
      groupId: resolveGroupId(
        groupStore.currentGroupId,
        groupStore.customGroups[0]?.id
      )
    })

    if (!newItem) {
      alert('该项目已存在')
      return
    }

    console.log(`已添加${isFile ? '文件' : '文件夹'}: ${name}`)
  } catch (error) {
    console.error('添加失败:', error)
    alert(`添加失败: ${error}`)
  }
}
</script>
