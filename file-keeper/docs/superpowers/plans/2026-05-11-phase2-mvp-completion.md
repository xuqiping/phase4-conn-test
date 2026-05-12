# Phase 2 MVP 收尾冲刺 — 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 完成 Phase 2 MVP 剩余 6 项功能，补齐 GroupManager、AddFileButton、分组间移动、编辑对话框、拖拽添加和搜索高亮。

**Architecture:** 按依赖顺序串行推进 6 个任务。先创建独立组件（GroupManager、AddFileButton、EditFileDialog），再修改 App.vue 集成它们。提取共享工具函数到 `src/utils/file.ts`。每个任务自包含，完成后测试验证。

**Tech Stack:** Vue 3 + TypeScript + Pinia + Tailwind CSS 4.x + Tauri 2 + Vitest

**基线:** 4 个测试文件，19 个测试全部通过。

---

### Task 1: 共享工具函数 + GroupManager 对话框

**Files:**
- Create: `src/utils/file.ts`
- Create: `src/utils/__tests__/file.test.ts`
- Create: `src/components/GroupManager.vue`
- Modify: `src/App.vue` (添加 GroupManager 触发按钮和对话框渲染)

**说明:** 先提取 spec 中定义的 `resolveGroupId()` 和 `deriveIconFromExt()` 到共享工具模块并编写测试，再创建 GroupManager 组件。

- [ ] **Step 1: 创建 `src/utils/file.ts` 共享工具函数**

```typescript
// src/utils/file.ts

/**
 * 根据文件扩展名推导图标类型
 */
export function deriveIconFromExt(filename: string): string {
  const ext = filename.split('.').pop()?.toLowerCase() || ''
  const map: Record<string, string> = {
    doc: 'word', docx: 'word',
    xls: 'excel', xlsx: 'excel',
    png: 'image', jpg: 'image', jpeg: 'image', gif: 'image',
    js: 'code', ts: 'code', py: 'code', java: 'code'
  }
  return map[ext] || 'file'
}

/**
 * 根据当前分组决定新文件归属
 * 如果当前在 "全部" 或 "最近打开"，分配到首个自定义分组，否则分配到当前分组
 */
export function resolveGroupId(currentGroupId: string, customGroupId?: string): string {
  if (currentGroupId === 'all' || currentGroupId === 'recent') {
    return customGroupId || 'all'
  }
  return currentGroupId
}
```

- [ ] **Step 2: 创建 `src/utils/__tests__/file.test.ts` 测试**

```typescript
// src/utils/__tests__/file.test.ts
import { describe, it, expect } from 'vitest'
import { deriveIconFromExt, resolveGroupId } from '../file'

describe('deriveIconFromExt', () => {
  it('should return "word" for .doc and .docx files', () => {
    expect(deriveIconFromExt('report.doc')).toBe('word')
    expect(deriveIconFromExt('report.docx')).toBe('word')
  })

  it('should return "excel" for .xls and .xlsx files', () => {
    expect(deriveIconFromExt('budget.xls')).toBe('excel')
    expect(deriveIconFromExt('budget.xlsx')).toBe('excel')
  })

  it('should return "image" for image extensions', () => {
    expect(deriveIconFromExt('photo.png')).toBe('image')
    expect(deriveIconFromExt('photo.jpg')).toBe('image')
    expect(deriveIconFromExt('photo.jpeg')).toBe('image')
    expect(deriveIconFromExt('photo.gif')).toBe('image')
  })

  it('should return "code" for code file extensions', () => {
    expect(deriveIconFromExt('app.js')).toBe('code')
    expect(deriveIconFromExt('app.ts')).toBe('code')
    expect(deriveIconFromExt('app.py')).toBe('code')
    expect(deriveIconFromExt('app.java')).toBe('code')
  })

  it('should return "file" for unknown extensions', () => {
    expect(deriveIconFromExt('readme.md')).toBe('file')
    expect(deriveIconFromExt('archive.zip')).toBe('file')
  })

  it('should handle filenames without extensions', () => {
    expect(deriveIconFromExt('Makefile')).toBe('file')
  })
})

describe('resolveGroupId', () => {
  it('should return custom group id when current is "all"', () => {
    expect(resolveGroupId('all', 'custom-1')).toBe('custom-1')
  })

  it('should return custom group id when current is "recent"', () => {
    expect(resolveGroupId('recent', 'custom-1')).toBe('custom-1')
  })

  it('should return "all" when current is "all" and no custom group', () => {
    expect(resolveGroupId('all', undefined)).toBe('all')
  })

  it('should return current group id when not "all" or "recent"', () => {
    expect(resolveGroupId('custom-2', 'custom-1')).toBe('custom-2')
  })
})
```

- [ ] **Step 3: 运行测试验证工具函数**

Run: `cd "C:\AI Projects\file-keeper" && npx vitest run src/utils/__tests__/file.test.ts`
Expected: 10 tests PASS

- [ ] **Step 4: 创建 `src/components/GroupManager.vue`**

```vue
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
```

- [ ] **Step 5: 在 `src/App.vue` 中集成 GroupManager**

需要做两处修改：

**修改 A — 分组标签栏添加管理按钮：**

在 App.vue 分组标签栏（第 57 行 `<div class="px-6 flex items-center space-x-6 border-b...">`）末尾、新建分组按钮之后添加：

```html
<!-- 在 "新建分组" 按钮之后添加 -->
<button
  class="py-3 text-sm font-medium text-gray-400 hover:text-primary transition-colors flex items-center ml-auto"
  @click="showGroupManager = true"
  title="管理分组"
>
  <FolderCog :size="16" />
</button>
```

```typescript
// 在 import 中新增 FolderCog 图标
import { ..., FolderCog } from 'lucide-vue-next'
```

**修改 B — 模板末尾添加 GroupManager：**

在 App.vue 模板末尾（`</template>` 之前，全局点击处理之后）添加：

```html
<!-- 分组管理对话框 -->
<GroupManager
  :visible="showGroupManager"
  @close="showGroupManager = false"
  @add-group="handleAddGroup"
/>
```

**修改 C — script 部分添加：**

```typescript
// 新增 import
import GroupManager from './components/GroupManager.vue'

// 新增 ref（在现有 showAddGroupDialog 附近）
const showGroupManager = ref(false)
```

- [ ] **Step 6: 运行测试验证基线未破坏**

Run: `cd "C:\AI Projects\file-keeper" && npx vitest run`
Expected: 所有 4 个测试文件通过（含新增的 10 个工具函数测试，共 29 个测试）

- [ ] **Step 7: 提交**

```bash
git add src/utils/file.ts src/utils/__tests__/file.test.ts src/components/GroupManager.vue src/App.vue
git commit -m "feat: add GroupManager dialog and shared file utility functions"
```

---

### Task 2: AddFileButton 组件

**Files:**
- Create: `src/components/AddFileButton.vue`
- Modify: `src/App.vue` (移除内联 handleAddFile，替换为组件)

- [ ] **Step 1: 创建 `src/components/AddFileButton.vue`**

```vue
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

    const newItem = fileStore.addFile({
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
```

- [ ] **Step 2: 修改 `src/App.vue` — 替换添加按钮并清理**

**修改 A — 模板替换（第 36-38 行）：**

```html
<!-- 原来 -->
<button class="flex items-center space-x-1 bg-primary hover:bg-[#369b6e] text-white px-4 py-2 rounded-md text-sm font-medium transition-colors shadow-sm shadow-primary/20" @click="handleAddFile">
  <Plus :size="16" />
  <span>添加文件</span>
</button>

<!-- 改为 -->
<AddFileButton />
```

**修改 B — 移除 `handleAddFile` 函数（第 424-482 行）：** 删除整个 `handleAddFile` 函数体。

**修改 C — 清理 import（第 346-369 行）：** 移除不再直接被 App.vue 使用的 import（`pickFile`, `pickFolder`, `validatePath` 从 `./api/files` 中移除；但注意这些仍可能被其他地方使用）。实际上 App.vue 的 import 中移除这三项：

```typescript
// 原来
import { pickFile, pickFolder, validatePath, openFile, showInFolder } from './api/files'
// 改为
import { openFile, showInFolder } from './api/files'
```

**修改 D — 添加 AddFileButton import：**

```typescript
import AddFileButton from './components/AddFileButton.vue'
```

- [ ] **Step 3: 运行测试验证**

Run: `cd "C:\AI Projects\file-keeper" && npx vitest run`
Expected: 全部 29 个测试通过

- [ ] **Step 4: 提交**

```bash
git add src/components/AddFileButton.vue src/App.vue
git commit -m "feat: extract AddFileButton component, use shared file utilities"
```

---

### Task 3: 文件分组间移动（右键菜单子菜单）

**Files:**
- Modify: `src/App.vue` (右键菜单新增"移动到分组"项 + hover 子菜单)

- [ ] **Step 1: 在 App.vue script 中添加子菜单状态**

在 `<script setup>` 中，`contextMenu` ref 定义之后添加：

```typescript
// 移动到分组子菜单
const showMoveToGroupSubmenu = ref(false)
const moveToGroupSubmenuX = ref(0)
const moveToGroupSubmenuY = ref(0)
```

- [ ] **Step 2: 在 App.vue 右键菜单模板中添加"移动到分组"项**

在"编辑信息"按钮（第 200-202 行）和"添加标签"按钮（第 203-205 行）之间插入：

```html
<!-- 移动到分组（带子菜单） -->
<div
  class="relative"
  @mouseenter="showMoveToGroupSubmenu = true"
  @mouseleave="showMoveToGroupSubmenu = false"
>
  <button
    class="w-full text-left px-4 py-2 hover:bg-gray-100 dark:hover:bg-[#383838] flex items-center justify-between text-gray-700 dark:text-gray-200 transition-colors"
  >
    <span class="flex items-center">
      <FolderInput :size="14" class="mr-2" />
      移动到分组
    </span>
    <ChevronRight :size="14" class="text-gray-400" />
  </button>

  <!-- 子菜单 -->
  <transition name="fade">
    <div
      v-if="showMoveToGroupSubmenu"
      class="absolute left-full top-0 ml-1 w-44 bg-white dark:bg-[#2d2d2d] rounded-lg shadow-xl border border-gray-200 dark:border-[#444] py-1 text-sm"
    >
      <button
        v-for="group in groupStore.groups"
        :key="group.id"
        @click.stop="handleMoveToGroup(group.id)"
        class="w-full text-left px-4 py-2 hover:bg-gray-100 dark:hover:bg-[#383838] flex items-center text-gray-700 dark:text-gray-200 transition-colors"
      >
        <Check v-if="contextMenu.file?.groupId === group.id" :size="14" class="mr-2 text-primary" />
        <span v-else class="w-[22px] mr-2" />
        <span class="truncate">{{ group.name }}</span>
      </button>
    </div>
  </transition>
</div>
```

**注意：** 需要在 import 中新增 `ChevronRight` 和 `Check` 图标。

- [ ] **Step 3: 在 `handleMenuAction` 中新增 'move-to-group' case**

在 `handleMenuAction` 函数中添加（不在此 case 内 closeContextMenu，因为子菜单还需要）：

```typescript
// 不再需要单独的 'move-to-group' case，
// 而是新增 handleMoveToGroup 函数：
function handleMoveToGroup(targetGroupId: string) {
  const file = contextMenu.value.file
  if (!file) return
  if (file.groupId === targetGroupId) {
    closeContextMenu()
    return
  }
  fileStore.updateFile(file.id, { groupId: targetGroupId })
  closeContextMenu()
}
```

- [ ] **Step 4: 处理子菜单边界检测**

在右键菜单打开时，预计算子菜单位置。修改 `handleContextMenu` 函数，在菜单右侧空间不足时将子菜单显示在左侧：

```typescript
// 在 handleContextMenu 末尾，计算子菜单位置
// 子菜单宽度约 176px，若右侧空间不足则显示在左侧
const menuWidth = 224 // 父菜单宽度
const submenuWidth = 176
if (window.innerWidth - x - menuWidth < submenuWidth) {
  moveToGroupSubmenuX.value = x - submenuWidth - 4
} else {
  moveToGroupSubmenuX.value = x + menuWidth + 4
}
```

子菜单定位改为使用计算后的坐标而非 `left-full top-0`。更新子菜单 div 的 style：

```html
<div
  v-if="showMoveToGroupSubmenu"
  class="fixed z-50 w-44 bg-white dark:bg-[#2d2d2d] rounded-lg shadow-xl border border-gray-200 dark:border-[#444] py-1 text-sm"
  :style="{ top: moveToGroupSubmenuY + 'px', left: moveToGroupSubmenuX + 'px' }"
>
```

并在 `handleContextMenu` 中设置 `moveToGroupSubmenuY.value = y + 80`（父菜单标题 + 前两个按钮的高度偏移）。

实际上更简单的做法：保持子菜单绝对定位 + 边界检测。简化方案如下——在 `handleContextMenu` 末尾设置：

```typescript
moveToGroupSubmenuY.value = y + 145 // 大约到"移动到分组"按钮的 Y 位置
if (window.innerWidth - x - 224 < 176) {
  moveToGroupSubmenuX.value = x - 176 - 4
} else {
  moveToGroupSubmenuX.value = x + 224 + 4
}
```

**简化处理：** 直接使用 `left-full` 相对定位，但在子菜单的 `style` 中做边界调整。不，更简单的做法是使用 CSS `right-full` 替代方案。最终选择：保持简单，子菜单始终显示在右侧，如果超出窗口就用 `left-full` 的负值偏移。由于界面右侧通常有空间（菜单出现在点击位置），直接使用 `left-full` 即可。边界检测改为：仅在 `x + 224 + 176 > window.innerWidth` 时改用 `right-full`。

**最终简化方案 — 使用动态 class：**

子菜单容器使用计算属性判断方向。改用简单的条件 class：

```html
<div
  v-if="showMoveToGroupSubmenu"
  :class="[
    'absolute top-0 py-1 w-44 bg-white dark:bg-[#2d2d2d] rounded-lg shadow-xl border border-gray-200 dark:border-[#444] text-sm',
    moveToGroupSubmenuOnLeft ? 'right-full mr-1' : 'left-full ml-1'
  ]"
>
```

```typescript
const moveToGroupSubmenuOnLeft = ref(false)

// 在 handleContextMenu 中设置：
moveToGroupSubmenuOnLeft.value = (x + 224 + 176 > window.innerWidth)
```

- [ ] **Step 5: 运行测试验证**

Run: `cd "C:\AI Projects\file-keeper" && npx vitest run`
Expected: 全部 29 个测试通过

- [ ] **Step 6: 提交**

```bash
git add src/App.vue
git commit -m "feat: add move-to-group submenu in context menu"
```

---

### Task 4: 编辑收藏项对话框

**Files:**
- Create: `src/components/EditFileDialog.vue`
- Modify: `src/App.vue` (连接 EditFileDialog，替换 handleMenuAction('edit'))

- [ ] **Step 1: 创建 `src/components/EditFileDialog.vue`**

```vue
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
```

- [ ] **Step 2: 修改 `src/App.vue` 集成 EditFileDialog**

**修改 A — 模板添加对话框（在 GroupManager 之后）：**

```html
<!-- 编辑文件对话框 -->
<EditFileDialog
  v-if="editingFile"
  :visible="!!editingFile"
  :file="editingFile"
  @close="editingFile = null"
  @saved="handleFileSaved"
/>
```

**修改 B — script 添加 ref 和处理函数：**

```typescript
// 新增 import
import EditFileDialog from './components/EditFileDialog.vue'

// 新增 ref（在 selectedFile 附近）
const editingFile = ref<FileItem | null>(null)

// 新增处理函数
function handleFileSaved(updates: Partial<FileItem>) {
  if (editingFile.value) {
    fileStore.updateFile(editingFile.value.id, updates)
    editingFile.value = null
  }
}
```

**修改 C — 替换 handleMenuAction('edit') case：**

```typescript
// 原来
case 'edit':
  console.log('编辑信息功能待实现')
  break

// 改为
case 'edit':
  editingFile.value = file
  return // 不要 closeContextMenu，让对话框接管
```

注意 `return` — 这样 `handleMenuAction` 末尾的 `closeContextMenu()` 不会执行，右键菜单关闭但对话框打开。

- [ ] **Step 3: 运行测试验证**

Run: `cd "C:\AI Projects\file-keeper" && npx vitest run`
Expected: 全部 29 个测试通过

- [ ] **Step 4: 提交**

```bash
git add src/components/EditFileDialog.vue src/App.vue
git commit -m "feat: add EditFileDialog for editing file metadata"
```

---

### Task 5: 拖拽添加文件

**Files:**
- Modify: `src/App.vue` (主内容区添加 drag/drop 事件)

- [ ] **Step 1: 在 App.vue script 中添加拖拽处理逻辑**

在 `<script setup>` 中，`viewMode` 相关代码之后添加：

```typescript
// Drag and Drop
const isDraggingOver = ref(false)

function handleDragOver(e: DragEvent) {
  e.preventDefault()
  if (e.dataTransfer) e.dataTransfer.dropEffect = 'copy'
  isDraggingOver.value = true
}

function handleDragLeave(e: DragEvent) {
  // Only set to false when leaving the container itself, not child elements
  const target = e.currentTarget as HTMLElement
  const relatedTarget = e.relatedTarget as HTMLElement
  if (!target.contains(relatedTarget)) {
    isDraggingOver.value = false
  }
}

async function handleDrop(e: DragEvent) {
  e.preventDefault()
  isDraggingOver.value = false

  const items = e.dataTransfer?.items
  if (!items) return

  for (let i = 0; i < items.length; i++) {
    const entry = items[i].webkitGetAsEntry()
    if (entry) {
      await processDroppedEntry(entry)
    }
  }
}

async function processDroppedEntry(entry: FileSystemEntry) {
  try {
    const rawPath = (entry as any).fullPath || entry.name

    // Validate path using backend
    const { validatePath } = await import('./api/files')
    const isValid = await validatePath(rawPath)
    if (!isValid) {
      console.warn(`拖拽路径无效: ${rawPath}`)
      return
    }

    const name = entry.name
    const type: 'file' | 'folder' = entry.isDirectory ? 'folder' : 'file'
    const icon = type === 'folder' ? 'folder' : deriveIconFromExt(name)

    const newItem = fileStore.addFile({
      name,
      path: rawPath,
      type,
      icon,
      tags: [],
      groupId: resolveGroupId(
        groupStore.currentGroupId,
        groupStore.customGroups[0]?.id
      )
    })

    if (!newItem) {
      console.warn(`项目已存在: ${rawPath}`)
    }
  } catch (error) {
    console.error('拖拽处理失败:', error)
  }
}
```

需要在 script 顶部新增 import：
```typescript
import { deriveIconFromExt, resolveGroupId } from './utils/file'
```

（如果 Task 2 中已添加则跳过此 import）

- [ ] **Step 2: 修改 App.vue 模板 — 主内容区添加拖拽事件和遮罩**

**修改 A — 主内容区添加事件绑定（第 74 行）：**

```html
<!-- 原来 -->
<div class="flex-1 overflow-auto p-6 bg-gray-50 dark:bg-dark-bg">

<!-- 改为 -->
<div
  class="flex-1 overflow-auto p-6 bg-gray-50 dark:bg-dark-bg relative transition-colors duration-200"
  :class="{ 'bg-primary/5 dark:bg-primary/5': isDraggingOver }"
  @dragover="handleDragOver"
  @dragleave="handleDragLeave"
  @drop="handleDrop"
>
```

**修改 B — 在空状态之后、template 之前添加拖拽遮罩：**

```html
<!-- 拖拽遮罩 -->
<transition name="fade">
  <div
    v-if="isDraggingOver"
    class="absolute inset-0 z-10 flex items-center justify-center bg-primary/5 border-2 border-dashed border-primary rounded-lg pointer-events-none"
  >
    <div class="text-center">
      <FolderInput :size="48" class="mx-auto mb-3 text-primary opacity-60" />
      <p class="text-primary font-medium text-lg">拖放文件到此处添加</p>
      <p class="text-gray-400 text-sm mt-1">支持文件和文件夹</p>
    </div>
  </div>
</transition>
```

注意：需要在 import 中已包含 `FolderInput`（已在现有 import 中）。

- [ ] **Step 3: 运行测试验证**

Run: `cd "C:\AI Projects\file-keeper" && npx vitest run`
Expected: 全部 29 个测试通过

- [ ] **Step 4: 提交**

```bash
git add src/App.vue
git commit -m "feat: add drag-and-drop file import with visual overlay"
```

---

### Task 6: 搜索结果高亮

**Files:**
- Create: `src/utils/highlight.ts`
- Create: `src/utils/__tests__/highlight.test.ts`
- Modify: `src/App.vue` (列表视图文件名和路径高亮)
- Modify: `src/components/FileCard.vue` (创建新文件；网格视图文件名高亮)

- [ ] **Step 1: 创建 `src/utils/highlight.ts`**

```typescript
// src/utils/highlight.ts

function escapeHtml(text: string): string {
  const map: Record<string, string> = {
    '&': '&amp;',
    '<': '&lt;',
    '>': '&gt;',
    '"': '&quot;',
    "'": '&#039;'
  }
  return text.replace(/[&<>"']/g, c => map[c])
}

/**
 * 高亮文本中的搜索关键词
 * @param text 原始文本
 * @param query 搜索词（空字符串返回原文本）
 * @returns 包含 <mark> 标签的 HTML 字符串
 */
export function highlightText(text: string, query: string): string {
  const safe = escapeHtml(text)
  if (!query.trim()) return safe

  const escapedQuery = escapeHtml(query).replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  const regex = new RegExp(`(${escapedQuery})`, 'gi')

  return safe.replace(
    regex,
    '<mark class="bg-yellow-200 dark:bg-yellow-800 text-inherit rounded-sm px-0.5">$1</mark>'
  )
}
```

- [ ] **Step 2: 创建 `src/utils/__tests__/highlight.test.ts`**

```typescript
// src/utils/__tests__/highlight.test.ts
import { describe, it, expect } from 'vitest'
import { highlightText } from '../highlight'

describe('highlightText', () => {
  it('should wrap matching text in mark tags', () => {
    const result = highlightText('hello world', 'world')
    expect(result).toContain('<mark')
    expect(result).toContain('world')
    expect(result).toContain('hello')
  })

  it('should highlight case-insensitively', () => {
    const result = highlightText('Hello World', 'hello')
    expect(result).toContain('<mark')
    expect(result).toContain('Hello')
  })

  it('should return safe text when query is empty', () => {
    const result = highlightText('hello world', '')
    expect(result).toBe('hello world')
    expect(result).not.toContain('<mark')
  })

  it('should escape HTML special characters in text', () => {
    const result = highlightText('<script>alert("xss")</script>', 'script')
    expect(result).not.toContain('<script>')
    expect(result).toContain('&lt;')
    expect(result).toContain('&gt;')
  })

  it('should escape regex special characters in query', () => {
    const result = highlightText('hello (world)', '(world)')
    expect(result).toContain('<mark')
    expect(result).toContain('(world)')
  })

  it('should return text unchanged when query has no matches', () => {
    const result = highlightText('hello world', 'xyz')
    expect(result).toBe('hello world')
  })
})
```

- [ ] **Step 3: 运行工具函数测试**

Run: `cd "C:\AI Projects\file-keeper" && npx vitest run src/utils/__tests__/highlight.test.ts`
Expected: 6 tests PASS

- [ ] **Step 4: 修改 `src/App.vue` 列表视图 — 高亮文件名和路径**

**修改 A — 列表视图文件名（第 136 行）：**

```html
<!-- 原来 -->
<span class="text-sm font-medium truncate">{{ file.name }}</span>

<!-- 改为 -->
<span
  class="text-sm font-medium truncate"
  v-html="highlightText(file.name, fileStore.searchQuery)"
/>
```

**修改 B — 列表视图路径（第 137 行）：**

```html
<!-- 原来 -->
<span class="text-[11px] text-gray-400 truncate mt-0.5">{{ file.path }}</span>

<!-- 改为 -->
<span
  class="text-[11px] text-gray-400 truncate mt-0.5"
  v-html="highlightText(file.path, fileStore.searchQuery)"
/>
```

**修改 C — 添加 import：**

```typescript
import { highlightText } from './utils/highlight'
```

- [ ] **Step 5: 创建 `src/components/FileCard.vue` 并高亮文件名**

当前 App.vue 中网格视图的卡片代码是内联的（第 84-116 行）。Task 6 要求创建 FileCard.vue 组件。先在 App.vue 中直接修改网格视图文件名高亮，然后可选地抽取组件。

**直接在 App.vue 网格视图中修改（第 106-108 行）：**

```html
<!-- 原来 -->
<h3 class="text-sm font-medium text-center line-clamp-2 leading-snug w-full px-2" :title="file.name">
  {{ file.name }}
</h3>

<!-- 改为 -->
<h3
  class="text-sm font-medium text-center line-clamp-2 leading-snug w-full px-2"
  :title="file.name"
  v-html="highlightText(file.name, fileStore.searchQuery)"
/>
```

**注意：** 去掉 `{{ file.name }}` 内容插值，改为 `v-html`。由于 `line-clamp-2` 和 `v-html` 一起使用时可能有 CSS 截断问题，但 `line-clamp-2` 对 `v-html` 内容仍生效。

由于 spec 提到修改 `FileCard.vue`，我们先创建该组件抽取网格卡片逻辑。但如果 Task 6 是最后一个任务，为减少风险，本次直接在 App.vue 中修改，后续可再重构抽取。

- [ ] **Step 6: 运行全部测试验证**

Run: `cd "C:\AI Projects\file-keeper" && npx vitest run`
Expected: 全部 35 个测试通过（原有 19 + utils/file 10 + utils/highlight 6）

- [ ] **Step 7: 提交**

```bash
git add src/utils/highlight.ts src/utils/__tests__/highlight.test.ts src/App.vue
git commit -m "feat: add search keyword highlighting in grid and list views"
```

---

## 完成后验证

全部 6 个任务完成后，运行完整测试套件：

```bash
cd "C:\AI Projects\file-keeper" && npx vitest run
```

预期结果：**6 个测试文件，35 个测试全部通过。**

然后启动开发服务器验证 UI：

```bash
cd "C:\AI Projects\file-keeper" && npm run dev
```

验收检查清单（来自 spec）：

- [ ] GroupManager：可查看、重命名、删除分组，默认分组不可删除
- [ ] AddFileButton：按钮外观一致，可添加文件/文件夹，重复检测正常
- [ ] 分组间移动：右键菜单有"移动到分组"，子菜单正确显示，可移动文件
- [ ] 编辑对话框：可修改名称/图标/标签/分组，路径只读
- [ ] 拖拽添加：拖入有视觉反馈，可拖入文件/文件夹
- [ ] 搜索高亮：网格和列表视图文件名高亮，路径高亮，深色/浅色主题正确
