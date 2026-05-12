# Phase 2 MVP 收尾冲刺 — 设计规格

> **状态：** 设计已确认  
> **日期：** 2026-05-11  
> **范围：** 完成 Phase 2 MVP 剩余 6 项功能（进程管理器后端推迟至 Phase 3）

---

## 概述

Phase 2 MVP 当前约 80% 完成。本冲刺补齐剩余 6 项待办，按依赖顺序串行推进：

| # | 任务 | 涉及文件 |
|---|------|---------|
| 1 | GroupManager 对话框 | 新建 `src/components/GroupManager.vue` |
| 2 | AddFileButton 组件 | 新建 `src/components/AddFileButton.vue`，修改 `src/App.vue` |
| 3 | 文件分组间移动 | 修改 `src/App.vue`（右键菜单子菜单） |
| 4 | 编辑收藏项信息 | 新建 `src/components/EditFileDialog.vue`，修改 `src/App.vue` |
| 5 | 拖拽添加文件 | 修改 `src/App.vue`（主内容区 drag/drop） |
| 6 | 搜索结果高亮 | 新建 `src/utils/highlight.ts`，修改 `src/components/FileCard.vue`、`src/App.vue` |

---

## 任务 1：GroupManager 对话框

### 目标

创建集中管理所有分组的对话框，支持查看、重命名、删除和新建分组。

### 新文件

`src/components/GroupManager.vue`

### 组件布局

```
┌─────────────────────────────────────┐
│ 分组管理                    [✕]     │
├─────────────────────────────────────┤
│                                     │
│  🔒 全部          6 个文件          │
│  🔒 最近打开       3 个文件          │
│  ─────────────────────────          │
│  📁 工作项目      4 个文件  [✎][🗑] │
│  📁 设计素材      2 个文件  [✎][🗑] │
│                                     │
├─────────────────────────────────────┤
│  [+ 新建分组]                       │
│  [关闭]                             │
└─────────────────────────────────────┘
```

### 交互规格

| 操作 | 行为 |
|------|------|
| 打开 | 点击分组标签栏右侧管理图标（FolderCog 图标） |
| 查看 | 列出所有分组，显示每个分组的文件数量 |
| 默认分组 | "全部"和"最近打开"显示 🔒 图标，不可删除/重命名 |
| 重命名 | 点击 ✎ → 分组名变为内联输入框 → 回车或失焦保存 → 调用 `groupStore.updateGroup(id, { name })` |
| 删除 | 点击 🗑 → 确认对话框："该分组下有 N 个文件，删除后它们将移至「全部」" → 确认 → `groupStore.removeGroup(id)` → 分组下所有文件 `groupId` 改为 `'all'` |
| 新建 | 点击"新建分组" → 复用现有 `showAddGroupDialog` 逻辑 |
| 关闭 | 点击 ✕ 或背景 → emit `close` |

### Store 依赖

- `useGroupStore()` — `groups`, `removeGroup`, `updateGroup`, `addGroup`
- `useFileStore()` — `files`（计算每个分组文件数 + 删除分组时批量更新 groupId）

### 组件接口

```typescript
// No props — 直接使用 stores
defineEmits<{ close: [] }>()
```

### 触发入口

在分组标签栏右侧（`GroupTabs.vue` 或 App.vue 的分组栏区域）添加 FolderCog 图标按钮，点击打开 GroupManager。

---

## 任务 2：AddFileButton 组件

### 目标

将 App.vue 中内联的"添加文件"逻辑抽离为独立组件（~58 行），封装文件选择、路径验证、类型推导和重复检测。

### 新文件

`src/components/AddFileButton.vue`

### 修改文件

`src/App.vue` — 移除 `handleAddFile` 函数及相关 import，工具栏中替换为 `<AddFileButton />`

### 组件结构

```
┌──────────────────────────────────────┐
│  [+ 添加文件]  ← 按钮                │
└──────────────────────────────────────┘
  点击 ↓
  confirm("添加文件？\n确定 = 文件\n取消 = 文件夹")
   ↓ 确定              ↓ 取消
  pickFile()          pickFolder()
   ↓                   ↓
  validatePath()  ← 统一验证
   ↓
  推导 icon / type（基于扩展名映射）
   ↓
  确定 groupId（当前分组 或 首个自定义分组）
   ↓
  fileStore.addFile(...)
   ↓ 返回 null？
  alert("该项目已存在")
```

### 扩展名 → icon 映射

```typescript
const extIconMap: Record<string, string> = {
  doc: 'word', docx: 'word',
  xls: 'excel', xlsx: 'excel',
  png: 'image', jpg: 'image', jpeg: 'image', gif: 'image',
  js: 'code', ts: 'code', py: 'code', java: 'code'
}
```

### 分组分配规则

- 当前分组是 `'all'` 或 `'recent'` → 分配到首个自定义分组（`groupStore.customGroups[0]?.id`），否则 `'all'`
- 其他情况 → 分配到当前分组

### 组件接口

```typescript
// No props — 直接使用 stores
// No emits — 所有状态变更通过 store
```

### 按钮样式

与当前 App.vue 中完全一致：`bg-primary` + `hover:bg-[#369b6e]` + `Plus` 图标 + "添加文件"文字。

### App.vue 改动清单

- 移除 `handleAddFile` 函数体（约 58 行，第 424-482 行）
- 移除 `pickFile`, `pickFolder`, `validatePath` 的 import
- 工具栏模板中替换为 `<AddFileButton />`
- 新增 import：`import AddFileButton from './components/AddFileButton.vue'`

---

## 任务 3：文件分组间移动

### 目标

允许用户通过右键菜单将文件移动到其他分组。

### 修改文件

`src/App.vue` — 右键菜单新增"移动到分组"项 + hover 子菜单

### 右键菜单改动

在"编辑信息"和"添加标签"之间插入"移动到分组"：

```
┌─────────────────────────┐
│  📄 <文件名>             │
├─────────────────────────┤
│  ▶ 打开                  │
│  📂 在文件夹中显示        │
│  ─────────────────────  │
│  ✎ 编辑信息              │
│  📁 移动到分组  ▸        │  ← 新增
│  🏷 添加标签              │
│  ─────────────────────  │
│  ⚡ 查看已打开的进程      │
│  ─────────────────────  │
│  🗑 移除收藏              │
└─────────────────────────┘
```

### 子菜单规格

```
                    ↓ hover "移动到分组"
          ┌──────────────────┐
          │  📁 全部          │
          │  🕐 最近打开      │
          │  ──────────────  │
          │  📁 工作项目  ✓  │  ← 当前分组打勾
          │  📁 设计素材      │
          └──────────────────┘
```

### 交互行为

| 操作 | 行为 |
|------|------|
| 点击当前分组 | 不做任何操作 |
| 点击其他分组 | 调用 `fileStore.updateFile(file.id, { groupId: targetGroupId })` → 关闭右键菜单 |
| 子菜单定位 | 父菜单右侧 + 边界检测（避免超出窗口） |

### 实现方式

- `handleMenuAction` 中新增 `'move-to-group'` case
- 子菜单显隐通过 `@mouseenter` / `@mouseleave` 控制（`showMoveToGroupSubmenu` ref）
- 子菜单使用绝对定位，计算 `left` 基于父菜单宽度
- `importantActions` 列表中已包含 `updateFile`，自动立即落盘

---

## 任务 4：编辑收藏项信息

### 目标

允许用户修改已收藏文件/文件夹的名称、标签、图标和分组。

### 新文件

`src/components/EditFileDialog.vue`

### 修改文件

`src/App.vue` — `handleMenuAction('edit')` 改为打开 EditFileDialog

### 对话框布局

```
┌──────────────────────────────────────┐
│  编辑文件信息                    [✕]  │
├──────────────────────────────────────┤
│                                      │
│  📄 图标                              │
│  [文件] [文件夹] [图片] [代码] [文档] │  ← 6 按钮图标选择器
│                                      │
│  名称                                 │
│  ┌────────────────────────────────┐  │
│  │ <预填充文件名>                  │  │  ← 可编辑
│  └────────────────────────────────┘  │
│                                      │
│  路径（只读）                         │
│  ┌────────────────────────────────┐  │
│  │ <预填充路径>              (🔒)  │  │  ← 灰色只读
│  └────────────────────────────────┘  │
│                                      │
│  标签                                 │
│  ┌────────────────────────────────┐  │
│  │ [标签1 ×] [标签2 ×] [+ 添加]   │  │  ← 标签编辑器
│  └────────────────────────────────┘  │
│                                      │
│  所属分组                             │
│  ┌────────────────────────────────┐  │
│  │ <当前分组>                ▾    │  │  ← 下拉选择
│  └────────────────────────────────┘  │
│                                      │
├──────────────────────────────────────┤
│              [取消]    [保存]         │
└──────────────────────────────────────┘
```

### 字段规格

| 字段 | 控件 | 验证规则 |
|------|------|---------|
| 图标 | 6 按钮选择器（word/excel/design/folder/image/code） | 必选，当前选中高亮边框 |
| 名称 | `<input>` 文本 | 不能为空，≤ 255 字符 |
| 路径 | 只读 `<input>`（`disabled` + 灰色） | 不可编辑 |
| 标签 | 标签编辑器 | 单个 ≤ 20 字符，最多 10 个 |
| 分组 | `<select>` 下拉 | 列出所有分组 |

### 标签编辑器交互

- 已有标签显示为 chip（`[标签名 ×]`）
- 点击 `×` 删除该标签
- 点击 `+ 添加` → 出现内联 `<input>` → 回车确认 → 添加到列表
- 重复标签自动去重

### 保存逻辑

```typescript
function handleSave() {
  if (!editName.value.trim()) return
  
  const updates: Partial<FileItem> = {
    name: editName.value.trim(),
    icon: selectedIcon.value,
    tags: [...editTags.value],
    groupId: selectedGroupId.value
  }
  
  emit('saved', updates)
  // 父组件调用: fileStore.updateFile(props.file.id, updates)
  emit('close')
}
```

### 组件接口

```typescript
defineProps<{ file: FileItem }>()
defineEmits<{
  close: []
  saved: [updates: Partial<FileItem>]
}>()
```

### App.vue 改动

- `handleMenuAction('edit')` → 设置 `editingFile.value = file` → 打开 EditFileDialog
- 新增 `editingFile` ref
- 新增 EditFileDialog 的 `v-if` 渲染和事件处理

---

## 任务 5：拖拽添加文件

### 目标

允许用户从系统文件管理器拖拽文件/文件夹到主内容区，直接添加到收藏列表。

### 修改文件

`src/App.vue` — 主内容区添加 drag/drop 事件处理

### 实现逻辑

```typescript
const isDraggingOver = ref(false)

function handleDragOver(e: DragEvent) {
  e.preventDefault()
  if (e.dataTransfer) e.dataTransfer.dropEffect = 'copy'
  isDraggingOver.value = true
}

function handleDragLeave(e: DragEvent) {
  if ((e.currentTarget as HTMLElement) === e.target) {
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
    if (entry) await processDroppedEntry(entry)
  }
}

async function processDroppedEntry(entry: FileSystemEntry) {
  // Tauri 2 webview: webkitGetAsEntry 返回 FileSystemEntry
  // fullPath 在 Windows 上形如 "/Documents/file.txt"
  const rawPath = (entry as any).fullPath || entry.name
  const isValid = await validatePath(rawPath)
  if (!isValid) return

  const name = entry.name
  const type: 'file' | 'folder' = entry.isDirectory ? 'folder' : 'file'
  const icon = type === 'folder' ? 'folder' : deriveIconFromExt(name)

  fileStore.addFile({
    name, path: rawPath, type, icon, tags: [],
    groupId: groupStore.currentGroupId === 'all' || groupStore.currentGroupId === 'recent'
      ? (groupStore.customGroups[0]?.id || 'all')
      : groupStore.currentGroupId
  })
}
```

### 视觉反馈

拖拽悬停时，主内容区覆盖半透明遮罩：

```
┌─────────────────────────────────────┐
│  （主内容区变暗 + 2px dashed 边框）  │
│                                     │
│        📂 拖放文件到此处添加         │
│                                     │
└─────────────────────────────────────┘
```

- 拖入：`bg-primary/5` 半透明背景 + `border-2 border-dashed border-primary` 边框
- 离开/放下：恢复原样
- 遮罩文字："拖放文件到此处添加"

### 技术注意

- Tauri 2 webview 原生支持 `webkitGetAsEntry()`
- macOS 上 `fullPath` 可能以 `/` 开头，路径规范化时处理
- 降级方案：若 `webkitGetAsEntry` 不可用，提示用户改用手动添加

### HTML 模板改动

主内容区域（`<div class="flex-1 overflow-auto p-6 ...">`）添加事件绑定：

```html
<div
  class="flex-1 overflow-auto p-6 bg-gray-50 dark:bg-dark-bg relative"
  @dragover="handleDragOver"
  @dragleave="handleDragLeave"
  @drop="handleDrop"
>
  <!-- 拖拽遮罩 -->
  <div v-if="isDraggingOver" class="absolute inset-0 ...">
    ...
  </div>
  <!-- 原有内容 -->
</div>
```

---

## 任务 6：搜索结果高亮

### 目标

在文件卡片和列表视图中高亮搜索关键词的匹配文本。

### 新文件

`src/utils/highlight.ts`

### 修改文件

- `src/components/FileCard.vue` — 文件名渲染改为 `v-html` + 高亮
- `src/App.vue` — 列表视图文件名和路径渲染改为 `v-html` + 高亮

### 工具函数

```typescript
// src/utils/highlight.ts

function escapeHtml(text: string): string {
  const map: Record<string, string> = {
    '&': '&amp;', '<': '&lt;', '>': '&gt;',
    '"': '&quot;', "'": '&#039;'
  }
  return text.replace(/[&<>"']/g, c => map[c])
}

export function highlightText(text: string, query: string): string {
  const safe = escapeHtml(text)
  if (!query.trim()) return safe

  const escapedQuery = escapeHtml(query).replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  const regex = new RegExp(`(${escapedQuery})`, 'gi')

  return safe.replace(regex,
    '<mark class="bg-yellow-200 dark:bg-yellow-800 text-inherit rounded-sm px-0.5">$1</mark>'
  )
}
```

### 高亮样式

- 浅色模式：`bg-yellow-200`（柔和黄色背景）
- 深色模式：`bg-yellow-800`（暗黄色背景）
- `rounded-sm px-0.5` 圆角 + 内边距
- `text-inherit` 保持文字颜色与上下文一致

### 使用位置

| 位置 | 文件 | 字段 |
|------|------|------|
| 网格视图 FileCard 文件名 | `FileCard.vue` | `file.name` |
| 列表视图文件名 | `App.vue` | `file.name` |
| 列表视图路径 | `App.vue` | `file.path` |

### 模板改动（FileCard.vue）

```html
<!-- 原来 -->
<h3 class="text-sm font-medium text-center line-clamp-2">{{ file.name }}</h3>

<!-- 改为 -->
<h3
  class="text-sm font-medium text-center line-clamp-2"
  v-html="highlightText(file.name, fileStore.searchQuery)"
/>
```

### 模板改动（App.vue 列表视图）

```html
<!-- 文件名 -->
<span
  class="text-sm font-medium truncate"
  v-html="highlightText(file.name, fileStore.searchQuery)"
/>

<!-- 路径 -->
<span
  class="text-[11px] text-gray-400 truncate mt-0.5"
  v-html="highlightText(file.path, fileStore.searchQuery)"
/>
```

### 安全说明

- 文件名可能包含 HTML 特殊字符（`<`, `>`, `&`），`escapeHtml()` 先转义原文再高亮替换
- 搜索词也经过 `escapeHtml()` 转义，防止 XSS
- `v-html` 仅用于渲染经过安全处理的高亮字符串

---

## 跨任务一致性约束

### 分组分配逻辑（Task 2、5 公用）

```typescript
function resolveGroupId(): string {
  if (groupStore.currentGroupId === 'all' || groupStore.currentGroupId === 'recent') {
    return groupStore.customGroups[0]?.id || 'all'
  }
  return groupStore.currentGroupId
}
```

### 扩展名推导（Task 2、5 公用）

```typescript
function deriveIconFromExt(filename: string): string {
  const ext = filename.split('.').pop()?.toLowerCase() || ''
  const map: Record<string, string> = {
    doc: 'word', docx: 'word',
    xls: 'excel', xlsx: 'excel',
    png: 'image', jpg: 'image', jpeg: 'image', gif: 'image',
    js: 'code', ts: 'code', py: 'code', java: 'code'
  }
  return map[ext] || 'file'
}
```

建议提取到 `src/utils/file.ts` 共享。

---

## 验收检查清单

### Task 1 — GroupManager
- [ ] 可查看全部分组及其文件数量
- [ ] 可重命名自定义分组
- [ ] 可删除自定义分组（有确认提示）
- [ ] "全部"和"最近打开"不可删除/重命名
- [ ] 可新建分组
- [ ] 所有变更自动持久化

### Task 2 — AddFileButton
- [ ] 按钮外观与现有完全一致
- [ ] 可添加文件和文件夹
- [ ] 重复添加有提示
- [ ] 路径验证正常工作
- [ ] 图标/类型自动推导

### Task 3 — 文件分组间移动
- [ ] 右键菜单有"移动到分组"选项
- [ ] hover 展开子菜单显示所有分组
- [ ] 当前分组打勾
- [ ] 点击目标分组完成移动
- [ ] 子菜单不超出窗口边界

### Task 4 — 编辑收藏项
- [ ] 可修改名称、图标、标签、分组
- [ ] 路径只读不可编辑
- [ ] 标签编辑器可添加/删除
- [ ] 保存后数据持久化
- [ ] 取消不保存

### Task 5 — 拖拽添加
- [ ] 拖入时有视觉反馈（蓝色虚线边框）
- [ ] 可拖入文件/文件夹
- [ ] 路径验证正常工作
- [ ] 重复检测正常工作

### Task 6 — 搜索结果高亮
- [ ] 网格视图文件名高亮
- [ ] 列表视图文件名高亮
- [ ] 列表视图路径高亮
- [ ] 深色/浅色主题下高亮样式正确
- [ ] 包含特殊字符的文件名安全处理（无 XSS）

---

## 不受影响的内容

- 进程管理器后端（推迟至 Phase 3）
- 批量操作（Phase 3）
- 全局快捷键与系统托盘（Phase 3）
- 文件图标提取（Phase 4）
- 拖拽排序（Phase 4，与拖拽添加不同）
- 打包发布（Phase 5）
