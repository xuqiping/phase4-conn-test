# 全局国际化与进程页滚动修复 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复进程管理页无法滚动的问题，并把现有中英文切换从进程管理扩展到整个 File Keeper 主界面。

**Architecture:** 本次改动保持最小范围。滚动问题优先从 `src/App.vue` 的根容器高度策略修复，避免继续在子组件里叠补丁。国际化继续复用 `src/composables/useI18n.ts` 与现有 locale 文件，把语言切换入口上提到 `src/App.vue`，再替换文件管理主界面与公共入口的高可见文案。

**Tech Stack:** Vue 3、TypeScript、Pinia、Tailwind CSS、现有自定义 `useI18n` composable

---

## File Structure

- Modify: `src/App.vue`
  - 负责根布局、文件管理主界面、全局标签页、顶部工具栏、批量操作文案
  - 本次同时承担滚动修复与全局语言切换入口
- Modify: `src/locales/zh-CN.ts`
  - 新增文件管理与全局 UI 文案中文翻译
- Modify: `src/locales/en.ts`
  - 新增文件管理与全局 UI 文案英文翻译
- Modify: `src/composables/useI18n.ts`
  - 如有必要，仅做最小类型或辅助函数增强；默认不重构
- Verify only: `src/components/ProcessManagement.vue`
  - 确认不再叠加无关滚动补丁
- Verify only: `src/components/ProcessList.vue`
  - 确认在根布局修复后其现有滚动容器能正常工作

---

### Task 1: 修复进程管理页根布局滚动

**Files:**
- Modify: `src/App.vue:2-5`
- Modify: `src/App.vue:427-430`
- Test: 手动运行应用并验证进程页滚动

- [ ] **Step 1: 记录失败场景并明确验收标准**

```text
失败场景：切换到“进程管理”标签页后，进程数量较多时仍然无法通过鼠标滚轮或滚动条浏览列表。
验收标准：进程列表区域出现垂直滚动条，滚轮/滚动条拖动都可用，顶部工具栏与底部状态栏保持固定。
```

- [ ] **Step 2: 在 `src/App.vue` 中把根容器从 `min-h-screen` 改为精确窗口高度布局**

将模板开头的容器类从：

```vue
:class="['min-h-screen w-full flex flex-col font-sans transition-colors duration-300',
         currentTheme === 'dark' ? 'dark bg-dark-bg text-gray-200' : 'bg-gray-50 text-gray-800']"
```

改为：

```vue
:class="['h-screen w-full flex flex-col overflow-hidden font-sans transition-colors duration-300',
         currentTheme === 'dark' ? 'dark bg-dark-bg text-gray-200' : 'bg-gray-50 text-gray-800']"
```

- [ ] **Step 3: 保持进程标签页容器继续参与剩余高度分配**

确认进程标签页区域保持如下结构：

```vue
<div v-if="currentTab === 'processes'" class="flex-1 overflow-hidden flex flex-col min-h-0">
  <ProcessManagement />
</div>
```

如果当前内容不同，恢复成上面这段。

- [ ] **Step 4: 运行应用并验证滚动是否恢复**

Run:

```bash
npm run tauri dev
```

Expected:
- 应用正常启动
- 切换到“进程管理”后，进程列表出现可用滚动条
- 顶部工具栏和底部状态栏不跟随列表内容滚动

- [ ] **Step 5: 若滚动恢复，仅提交与滚动相关的改动**

```bash
git add src/App.vue
git commit -m "fix(frontend): restore process page scrolling"
```

---

### Task 2: 在 App.vue 添加全局语言切换入口

**Files:**
- Modify: `src/App.vue:24-95`
- Modify: `src/App.vue:683-739`
- Modify: `src/locales/zh-CN.ts`
- Modify: `src/locales/en.ts`

- [ ] **Step 1: 在 locale 文件中添加全局与文件管理基础键**

在 `src/locales/zh-CN.ts` 的顶层新增：

```ts
  common: {
    language: '语言',
    switchToChinese: '切换到中文',
    switchToEnglish: 'Switch to English',
    confirm: '确定',
    close: '关闭',
    cancel: '取消',
    refresh: '刷新'
  },
  file: {
    searchPlaceholder: '搜索文件、路径或标签...',
    sortLabel: '排序',
    sortTitle: '排序方式',
    sortCustom: '自定义顺序',
    sortOpenCount: '打开次数',
    sortLastOpened: '最近打开',
    sortName: '名称',
    sortCreatedAt: '添加时间',
    iconLabel: '图标',
    iconModeTitle: '图标显示模式',
    iconReal: '真实图标',
    iconGeneric: '通用图标',
    addFolder: '添加文件夹',
    toggleTheme: '切换主题',
    filesTab: '文件管理',
    processesTab: '进程管理',
    emptySearch: '未找到匹配的文件',
    selectedItems: '已选择 {count} 项',
    open: '打开',
    move: '移动',
    addTag: '添加标签',
    delete: '删除',
    unselect: '取消',
    moveToGroup: '移动到分组',
    newGroup: '新建分组',
    manageGroups: '管理分组',
    totalItems: '共 {count} 个项目'
  }
```

在 `src/locales/en.ts` 中新增对应英文：

```ts
  common: {
    language: 'Language',
    switchToChinese: '切换到中文',
    switchToEnglish: 'Switch to English',
    confirm: 'Confirm',
    close: 'Close',
    cancel: 'Cancel',
    refresh: 'Refresh'
  },
  file: {
    searchPlaceholder: 'Search files, paths, or tags...',
    sortLabel: 'Sort',
    sortTitle: 'Sort by',
    sortCustom: 'Custom Order',
    sortOpenCount: 'Open Count',
    sortLastOpened: 'Recently Opened',
    sortName: 'Name',
    sortCreatedAt: 'Added Time',
    iconLabel: 'Icons',
    iconModeTitle: 'Icon display mode',
    iconReal: 'Real Icons',
    iconGeneric: 'Generic Icons',
    addFolder: 'Add Folder',
    toggleTheme: 'Toggle Theme',
    filesTab: 'File Management',
    processesTab: 'Process Management',
    emptySearch: 'No matching files found',
    selectedItems: '{count} selected',
    open: 'Open',
    move: 'Move',
    addTag: 'Add Tag',
    delete: 'Delete',
    unselect: 'Clear',
    moveToGroup: 'Move to Group',
    newGroup: 'New Group',
    manageGroups: 'Manage Groups',
    totalItems: '{count} items total'
  }
```

- [ ] **Step 2: 在 `src/App.vue` 中接入 `useI18n`**

在脚本导入区保留：

```ts
import { useI18n } from './composables/useI18n'
```

并在 store 初始化后增加：

```ts
const { t, locale, toggleLocale } = useI18n()
```

- [ ] **Step 3: 在顶部工具栏新增全局语言切换按钮**

在 `src/App.vue` 顶部工具栏按钮区，放在主题按钮附近，插入：

```vue
<button
  @click="toggleLocale"
  class="flex items-center space-x-1.5 px-3 py-2 rounded-md bg-gray-100 dark:bg-dark-hover hover:bg-gray-200 dark:hover:bg-[#383838] transition-colors text-sm"
  :title="locale === 'zh-CN' ? t('common.switchToEnglish') : t('common.switchToChinese')"
>
  <Languages :size="16" />
  <span>{{ locale === 'zh-CN' ? 'EN' : '中' }}</span>
</button>
```

- [ ] **Step 4: 先替换顶部工具栏与主标签页的高可见硬编码文案**

把以下位置替换为 `t()`：

```vue
<input
  type="text"
  :placeholder="t('file.searchPlaceholder')"
  v-model="fileStore.searchQuery"
/>

<span class="text-xs text-gray-500 dark:text-gray-400 select-none">{{ t('file.sortLabel') }}</span>
<select v-model="fileStore.sortBy" :title="t('file.sortTitle')">
  <option value="custom">{{ t('file.sortCustom') }}</option>
  <option value="openCount">{{ t('file.sortOpenCount') }}</option>
  <option value="lastOpened">{{ t('file.sortLastOpened') }}</option>
  <option value="name">{{ t('file.sortName') }}</option>
  <option value="createdAt">{{ t('file.sortCreatedAt') }}</option>
</select>

<span class="text-xs text-gray-500 dark:text-gray-400 select-none">{{ t('file.iconLabel') }}</span>
<select v-model="settingsStore.settings.iconMode" :title="t('file.iconModeTitle')">
  <option value="real">{{ t('file.iconReal') }}</option>
  <option value="generic">{{ t('file.iconGeneric') }}</option>
</select>
```

并替换：

```vue
<span>{{ t('file.addFolder') }}</span>
<span>{{ t('file.filesTab') }}</span>
<span>{{ t('file.processesTab') }}</span>
```

- [ ] **Step 5: 提交全局入口与基础文案改动**

```bash
git add src/App.vue src/locales/zh-CN.ts src/locales/en.ts
git commit -m "feat(frontend): add global language switch entry"
```

---

### Task 3: 扩展文件管理主界面主要文案国际化

**Files:**
- Modify: `src/App.vue:97-679`
- Modify: `src/locales/zh-CN.ts`
- Modify: `src/locales/en.ts`

- [ ] **Step 1: 为批量操作、空状态、分组入口补翻译键**

在 `file` 命名空间中继续补充：

```ts
batchOpen: '打开'
batchMove: '移动'
batchAddTag: '添加标签'
batchDelete: '删除'
groupMoveTitle: '移动到分组'
noMatchFiles: '未找到匹配的文件'
```

英文分别对应：

```ts
batchOpen: 'Open'
batchMove: 'Move'
batchAddTag: 'Add Tag'
batchDelete: 'Delete'
groupMoveTitle: 'Move to Group'
noMatchFiles: 'No matching files found'
```

- [ ] **Step 2: 替换批量操作浮层与空状态文案**

在 `src/App.vue` 中将：

```vue
<p>未找到匹配的文件</p>
已选择 <strong class="text-primary">{{ selectionStore.selectedCount }}</strong> 项
<span>打开</span>
<span>移动</span>
<span>添加标签</span>
<span>删除</span>
取消
<h3 class="text-sm font-semibold mb-3 text-gray-800 dark:text-gray-100">移动到分组</h3>
```

替换为：

```vue
<p>{{ t('file.noMatchFiles') }}</p>
{{ t('file.selectedItems', { count: selectionStore.selectedCount }) }}
<span>{{ t('file.open') }}</span>
<span>{{ t('file.move') }}</span>
<span>{{ t('file.addTag') }}</span>
<span>{{ t('file.delete') }}</span>
{{ t('file.unselect') }}
<h3 class="text-sm font-semibold mb-3 text-gray-800 dark:text-gray-100">{{ t('file.moveToGroup') }}</h3>
```

- [ ] **Step 3: 替换分组入口和底部状态栏文案**

将以下文案替换为翻译：

```vue
<Plus :size="14" class="mr-1" /> {{ t('file.newGroup') }}
:title="t('file.manageGroups')"
<div>{{ t('file.totalItems', { count: fileStore.filteredFiles.length }) }}</div>
```

- [ ] **Step 4: 运行应用并手动验证全局切换**

Run:

```bash
npm run tauri dev
```

Expected:
- 顶部出现语言切换按钮
- 文件管理与进程管理主界面都能随切换更新主要文案
- 刷新应用后仍记住上次语言选择

- [ ] **Step 5: 提交文件管理主界面国际化改动**

```bash
git add src/App.vue src/locales/zh-CN.ts src/locales/en.ts
git commit -m "feat(frontend): localize main file management UI"
```

---

### Task 4: 清理进程页剩余国际化缺口并完成回归验证

**Files:**
- Modify: `src/components/ProcessToolbar.vue`
- Modify: `src/components/ProcessList.vue`
- Modify: `src/locales/zh-CN.ts`
- Modify: `src/locales/en.ts`

- [ ] **Step 1: 替换进程页仍硬编码的 toast 与时间文案**

在 `src/locales/zh-CN.ts` 与 `src/locales/en.ts` 中补充：

```ts
process: {
  ...
  never: '从未'
  secondsAgo: '{count}秒前'
  minutesAgo: '{count}分钟前'
  hoursAgo: '{count}小时前'
  closeSingleSuccess: '成功关闭进程 {name} (PID: {pid})'
  closeSingleFailed: '关闭进程 {name} (PID: {pid}) 失败'
}
```

英文对应：

```ts
process: {
  ...
  never: 'Never'
  secondsAgo: '{count}s ago'
  minutesAgo: '{count}m ago'
  hoursAgo: '{count}h ago'
  closeSingleSuccess: 'Process {name} (PID: {pid}) closed successfully'
  closeSingleFailed: 'Failed to close process {name} (PID: {pid})'
}
```

- [ ] **Step 2: 在 `src/components/ProcessToolbar.vue` 中替换时间文案与批量关闭 toast**

将：

```ts
if (processStore.lastRefreshTime === 0) return 'Never'
if (seconds < 60) return `${seconds}s ago`
if (minutes < 60) return `${minutes}m ago`
return `${hours}h ago`
```

替换为：

```ts
if (processStore.lastRefreshTime === 0) return t('process.never')
if (seconds < 60) return t('process.secondsAgo', { count: seconds })
if (minutes < 60) return t('process.minutesAgo', { count: minutes })
return t('process.hoursAgo', { count: hours })
```

并将批量关闭结果 toast 改为使用：

```ts
t('process.batchCloseSuccess', { count: result.success })
t('process.batchCloseFailed', { count: result.failed })
```

- [ ] **Step 3: 在 `src/components/ProcessList.vue` 中替换单进程关闭 toast**

将以下模板替换为翻译：

```ts
toast?.success(t('process.closeSingleSuccess', { name: process.name, pid }))
toast?.error(t('process.closeSingleFailed', { name: process.name, pid }))
```

- [ ] **Step 4: 运行回归验证**

Run:

```bash
npm run tauri dev
```

Expected:
- 进程页可以滚动
- 文件管理与进程管理都能切换语言
- 关闭单个/多个进程后的提示文案跟随当前语言
- 不出现新的布局错乱或明显未翻译主文案

- [ ] **Step 5: 提交最后的清理与回归结果**

```bash
git add src/components/ProcessToolbar.vue src/components/ProcessList.vue src/locales/zh-CN.ts src/locales/en.ts
git commit -m "fix(frontend): complete process UI localization"
```

---

## Self-Review

- Spec coverage: 已覆盖两个目标——进程页滚动修复、全局语言切换与主界面国际化扩展。
- Placeholder scan: 无 TBD/TODO；每一步都给出具体文件、代码片段或命令。
- Type consistency: 使用的 `t()`、`locale`、`toggleLocale()` 与现有 `useI18n.ts` 返回值一致；翻译键命名统一使用 `common/file/process`。