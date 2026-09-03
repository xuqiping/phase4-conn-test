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
import { deleteManagedShortcut, importFavoritePath, pickFile, pickFolder } from '../api/files'
import { resolveGroupId } from '../utils/file'

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

    if (fileStore.files.some(file => file.path === selectedPath || file.sourcePath === selectedPath)) {
      alert('该项目已存在')
      return
    }
    const descriptor = await importFavoritePath(selectedPath)

    const newItem = await fileStore.addFile({
      name: descriptor.name,
      path: descriptor.path,
      sourcePath: descriptor.sourcePath,
      managedArtifact: descriptor.managedArtifact,
      shortcutTargetPath: descriptor.shortcutTargetPath,
      type: descriptor.itemType,
      icon: descriptor.itemType === 'folder' ? 'folder' : '',
      tags: [],
      groupId: resolveGroupId(
        groupStore.currentGroupId,
        groupStore.customGroups[0]?.id
      )
    })

    if (!newItem) {
      if (descriptor.managedArtifact) {
        await deleteManagedShortcut(descriptor.managedArtifact.cachePath).catch(() => undefined)
      }
      alert('该项目已存在')
      return
    }

    console.log(`已添加收藏项: ${newItem.id}`)
  } catch (error) {
    console.error('添加失败:', error)
    alert(`添加失败: ${error}`)
  }
}
</script>
