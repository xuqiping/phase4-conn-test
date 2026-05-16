# Phase 5: 打包、测试与优化 - 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 完成 File Keeper v0.1.0 性能优化、Windows 平台测试和打包发布，使其能够流畅处理几百上千个文件并以 .msi 安装包形式小范围分享。

**Architecture:** 使用 @vueuse/core 的 useVirtualList 实现虚拟滚动，watchDebounced 实现搜索防抖，Intersection Observer 实现图标懒加载。测试覆盖所有核心功能和性能指标。使用 Tauri 构建无签名 .msi 安装包。

**Tech Stack:** Vue 3, TypeScript, @vueuse/core, Tauri 2, Vitest

---

## 文件结构规划

### 新增文件
- `src/composables/useVirtualScroll.ts` - 虚拟滚动组合式函数
- `src/composables/useIconLazyLoad.ts` - 图标懒加载组合式函数
- `docs/testing/v0.1.0-test-report.md` - 测试报告
- `docs/USE_CASES.md` - 应用场景文档
- `icons/32x32.png` - 应用图标（32x32）
- `icons/128x128.png` - 应用图标（128x128）
- `icons/icon.ico` - Windows 图标

### 修改文件
- `src/stores/fileStore.ts` - 添加搜索防抖
- `src/App.vue` - 集成虚拟滚动和图标懒加载
- `src-tauri/tauri.conf.json` - 配置打包参数
- `README.md` - 更新为 v0.1.0 说明
- `CHANGELOG.md` - 添加 v0.1.0 版本记录

---

## Task 1: 搜索防抖优化

**Files:**
- Modify: `src/stores/fileStore.ts:88-130`

- [ ] **Step 1: 导入 watchDebounced**

在 `src/stores/fileStore.ts` 顶部添加导入：

```typescript
import { watchDebounced } from '@vueuse/core'
```

- [ ] **Step 2: 移除 searchQuery 的直接使用，改为内部状态**

将 `searchQuery` 改为内部状态，新增 `debouncedSearchQuery`：

```typescript
// 在 fileStore 中，找到 const searchQuery = ref('') 这行
const searchQuery = ref('')
const debouncedSearchQuery = ref('')

// 添加 watchDebounced
watchDebounced(
  searchQuery,
  (newQuery) => {
    debouncedSearchQuery.value = newQuery
  },
  { debounce: 300 }
)
```

- [ ] **Step 3: 更新 filteredFiles 使用 debouncedSearchQuery**

在 `filteredFiles` computed 中，将 `searchQuery.value` 替换为 `debouncedSearchQuery.value`：

```typescript
// Filter by search query
if (debouncedSearchQuery.value) {
  const query = debouncedSearchQuery.value.toLowerCase()
  // ... 其余搜索逻辑保持不变
}
```

- [ ] **Step 4: 更新 setSearchQuery 方法**

保持 `setSearchQuery` 方法不变，它仍然设置 `searchQuery.value`：

```typescript
function setSearchQuery(query: string) {
  searchQuery.value = query
}
```

- [ ] **Step 5: 测试搜索防抖**

手动测试：
1. 运行 `npm run tauri:dev`
2. 在搜索框快速输入 "test"
3. 观察控制台或界面，确认搜索在输入停止 300ms 后才执行
4. 预期：输入流畅，无卡顿

- [ ] **Step 6: Commit**

```bash
git add src/stores/fileStore.ts
git commit -m "feat: add search debounce (300ms delay)"
```

---

## Task 2: 虚拟滚动组合式函数

**Files:**
- Create: `src/composables/useVirtualScroll.ts`

- [ ] **Step 1: 创建 useVirtualScroll 组合式函数文件**

创建 `src/composables/useVirtualScroll.ts`：

```typescript
import { ref, computed, watch, type Ref } from 'vue'
import { useElementSize } from '@vueuse/core'

export interface VirtualScrollOptions {
  itemHeight: number      // 单个项目高度（列表视图）
  itemsPerRow?: number    // 每行项目数（网格视图，默认 1）
  overscan?: number       // 缓冲区大小（默认 5）
}

export function useVirtualScroll<T>(
  containerRef: Ref<HTMLElement | null>,
  items: Ref<T[]>,
  options: VirtualScrollOptions
) {
  const { itemHeight, itemsPerRow = 1, overscan = 5 } = options
  
  const scrollTop = ref(0)
  const { height: containerHeight } = useElementSize(containerRef)
  
  // 计算总行数
  const totalRows = computed(() => Math.ceil(items.value.length / itemsPerRow))
  
  // 计算可见行范围
  const visibleRange = computed(() => {
    const start = Math.floor(scrollTop.value / itemHeight)
    const end = Math.ceil((scrollTop.value + containerHeight.value) / itemHeight)
    
    return {
      start: Math.max(0, start - overscan),
      end: Math.min(totalRows.value, end + overscan)
    }
  })
  
  // 计算可见项目
  const visibleItems = computed(() => {
    const { start, end } = visibleRange.value
    const startIndex = start * itemsPerRow
    const endIndex = end * itemsPerRow
    
    return items.value.slice(startIndex, endIndex).map((item, index) => ({
      item,
      index: startIndex + index,
      offsetTop: Math.floor((startIndex + index) / itemsPerRow) * itemHeight
    }))
  })
  
  // 总高度
  const totalHeight = computed(() => totalRows.value * itemHeight)
  
  // 监听滚动
  const handleScroll = (event: Event) => {
    const target = event.target as HTMLElement
    scrollTop.value = target.scrollTop
  }
  
  return {
    visibleItems,
    totalHeight,
    handleScroll
  }
}
```

- [ ] **Step 2: Commit**

```bash
git add src/composables/useVirtualScroll.ts
git commit -m "feat: add useVirtualScroll composable"
```

---

## Task 3: 图标懒加载组合式函数

**Files:**
- Create: `src/composables/useIconLazyLoad.ts`

- [ ] **Step 1: 创建 useIconLazyLoad 组合式函数**

创建 `src/composables/useIconLazyLoad.ts`：

```typescript
import { ref, onMounted, onUnmounted, type Ref } from 'vue'
import { getFileIcon } from '../api/icons'
import type { FileItem } from '../types/file'

// 图标提取队列
const iconQueue: Array<{ file: FileItem; callback: (icon: string) => void }> = []
let isProcessing = false
const MAX_CONCURRENT = 5
let activeCount = 0

async function processQueue() {
  if (isProcessing || iconQueue.length === 0) return
  
  isProcessing = true
  
  while (iconQueue.length > 0 && activeCount < MAX_CONCURRENT) {
    const task = iconQueue.shift()
    if (!task) break
    
    activeCount++
    
    getFileIcon(task.file.path)
      .then(icon => {
        task.callback(icon)
      })
      .catch(() => {
     // 提取失败，使用扩展名图标
    task.callback('')
      })
      .finally(() => {
      activeCount--
        processQueue()
      })
  }
  
  isProcessing = false
}

export function useIconLazyLoad(
  elementRef: Ref<HTMLElement | null>,
  file: Ref<FileItem>,
  onIconLoaded: (icon: string) => void
) {
  const observer = ref<IntersectionObserver | null>(null)
  
  onMounted(() => {
    if (!elementRef.value) return
    
    observer.value = new IntersectionObserver(
      (entries) => {
        entries.forEach(entry => {
          if (entry.isIntersecting && !file.value.icon) {
            // 添加到队列
            iconQueue.push({
              file: file.value,
              callback: onIconLoaded
            })
            processQueue()
         
            // 停止观察
        observer.value?.unobserve(entry.target)
          }
        })
      },
      { threshold: 0.1 }
    )
    
    observer.value.observe(elementRef.value)
  })
  
  onUnmounted(() => {
    if (observer.value && elementRef.value) {
      observer.value.unobserve(elementRef.value)
    }
  })
}
```

- [ ] **Step 2: Commit**

```bash
git add src/composables/useIconLazyLoad.ts
git commit -m "feat: add useIconLazyLoad composable with queue"
```

---

## Task 4: 集成虚拟滚动到 App.vue（网格视图）

**Files:**
- Modify: `src/App.vue:181-260`

- [ ] **Step 1: 导入 useVirtualScroll**

在 `src/App.vue` 的 script setup 部分添加导入：

```typescript
import { useVirtualScroll } from './composables/useVirtualScroll'
```

- [ ] **Step 2: 创建网格容器 ref**

```typescript
const gridContainerRef = ref<HTMLElement | null>(null)
```

- [ ] **Step 3: 设置虚拟滚动（网格视图）**

```typescript
// 网格视图虚拟滚动配置
const gridVirtualScroll = useVirtualScroll(
  gridContainerRef,
  computed(() => fileStore.filteredFiles),
  {
    itemHeight: 200,  // 每个卡片高度约 200px
    itemsPerRow: 5,   // 默认每行 5 个（根据屏幕宽度调整）
    overscan: 10      // 缓冲区 10 个项目
  }
)
```

- [ ] **Step 4: 更新网格视图模板**

找到网格视图部分（约 181-260 行），替换为：

```vue
<div 
  v-if="viewMode === 'grid'" 
  ref="gridContainerRef"
  class="relative overflow-y-auto"
  style="height: calc(100vh - 120px);"
  @scroll="gridVirtualScroll.handleScroll"
>
  <div :style="{ height: `${gridVirtualScroll.totalHeight.value}px`, position: 'relative' }">
    <div
      v-for="{ item: file, index, offsetTop } in gridVirtualScroll.visibleItems.value"
      :key="file.id"
      :data-id="file.id"
    :style="{ 
        position: 'absolute',
        top: `${offsetTop}px`,
        left: `${(index % 5) * 20}%`,
        width: '18%'
      }"
      draggable="false"
      @contextmenu.prevent="handleContextMenu($event, file)"
      @click="handleCardClick($event, file)"
      @mouseenter="hoveredFileId = file.id"
      @mouseleave="hoveredFileId = null"
      class="group relative bg-white dark:bg-dark-panel border border-gray-200 dark:border-dark-border rounded-lg p-4 hover:shadow-lg dark:hover:shadow-black/40 hover:border-primary/50 transition-all duration-200 cursor-move flex flex-col select-none"
    >
      <!-- 保持原有的卡片内容不变 -->
      <!-- ... -->
    </div>
  </div>
</div>
```

- [ ] **Step 5: 测试网格视图虚拟滚动**

手动测试：
1. 运行 `npm run tauri:dev`
2. 添加 100+ 个文件
3. 在网格视图中滚动
4. 打开开发者工具，检查 DOM 节点数量（应该只有 30-50 个）
5. 预期：滚动流畅，60fps

- [ ] **Step 6: Commit**

```bash
git add src/App.vue
git commit -m "feat: integrate virtual scroll for grid view"
```

---

## Task 5: 集成虚拟滚动到 App.vue（列表视图）

**Files:**
- Modify: `src/App.vue:262-320`

- [ ] **Step 1: 创建列表容器 ref**

```typescript
const listContainerRef = ref<HTMLElement | null>(null)
```

- [ ] **Step 2: 设置虚拟滚动（列表视图）**

```typescript
// 列表视图虚拟滚动配置
const listVirtualScroll = useVirtualScroll(
  listContainerRef,
  computed(() => fileStore.filteredFiles),
  {
    itemHeight: 60,   // 每行高度 60px
    itemsPerRow: 1,   // 列表视图每行 1 个
    overscan: 5       // 缓冲区 5 个项目
  }
)
```

- [ ] **Step 3: 更新列表视图模板**

找到列表视图部分（约 262-320 行），替换为：

```vue
<div 
  v-else 
  ref="listContainerRef"
  class="flex flex-col bg-white dark:bg-dark-panel rounded-lg border border-gray-200 dark:border-dark-border overflow-hidden"
>
  <!-- 表头保持不变 -->
  <div class="flex items-center px-4 py-3 bg-gray-50 dark:bg-dark-hover border-b border-gray-200 dark:border-dark-border text-xs font-semibold text-gray-500 uppercase tracking-wider">
    <div class="w-8"></div>
    <div class="flex-1">名称</div>
    <div class="w-32">分组</div>
    <div class="w-24">打开次数</div>
    <div class="w-32">最近打开</div>
    <div class="w-8"></div>
  </div>
  
  <!-- 虚拟滚动列表 -->
  <div 
    class="relative overflow-y-auto"
    style="height: calc(100vh - 180px);"
    @scroll="listVirtualScroll.handleScroll"
  >
    <div :style="{ height: `${listVirtualScroll.totalHeight.value}px`, position: 'relative' }">
      <div
        v-for="{ item: file, index, offsetTop } in listVirtualScroll.visibleItems.value"
        :key="file.id"
    :style="{ 
          position: 'absolute',
          top: `${offsetTop}px`,
          left: 0,
          right: 0,
          height: '60px'
        }"
        @contextmenu.prevent="handleContextMenu($event, file)"
        @click="handleCardClick($event, file)"
        class="flex items-center px-4 py-3 hover:bg-gray-50 dark:hover:bg-dark-hover border-b border-gray-100 dark:border-[#2a2a2a] cursor-pointer transition-colors"
      >
    <!-- 保持原有的列表行内容不变 -->
        <!-- ... -->
      </div>
    </div>
  </div>
</div>
```

- [ ] **Step 4: 测试列表视图虚拟滚动**

手动测试：
1. 切换到列表视图
2. 滚动列表
3. 检查 DOM 节点数量（应该只有 15-20 个）
4. 预期：滚动流畅

- [ ] **Step 5: Commit**

```bash
git add src/App.vue
git commit -m "feat: integrate virtual scroll for list view"
```

---

## Task 6: 集成图标懒加载到 App.vue

**Files:**
- Modify: `src/App.vue:218-233`
- Modify: `src/stores/fileStore.ts:150-170`

- [ ] **Step 1: 导入 useIconLazyLoad**

在 `src/App.vue` 添加导入：

```typescript
import { useIconLazyLoad } from './composables/useIconLazyLoad'
```

- [ ] **Step 2: 在文件卡片中使用懒加载**

在网格视图的文件卡片模板中，为图标元素添加 ref 和懒加载逻辑。

由于 v-for 中需要为每个卡片创建独立的 ref，使用模板 ref 函数：

```vue
<template v-slot="{ item: file }">
  <div :ref="el => setupIconLazyLoad(el, file)">
    <!-- 图标显示逻辑 -->
    <img
      v-if="file.icon && file.icon.startsWith('data:image')"
      :src="file.icon"
      class="w-12 h-12 mb-3"
    />
    <component
      v-else
      :is="getFileIcon(file.icon || file.type)"
      :size="48"
      class="mb-3"
    />
  </div>
</template>
```

在 script 中添加：

```typescript
const iconRefs = new Map<string, HTMLElement>()

function setupIconLazyLoad(el: HTMLElement | null, file: FileItem) {
  if (!el || file.icon) return
  
  iconRefs.set(file.id, el)
  
  useIconLazyLoad(
    ref(el),
    ref(file),
    (icon) => {
      // 更新文件图标
      fileStore.updateFile(file.id, { icon })
    }
  )
}
```

- [ ] **Step 3: 更新 fileStore 的 addFile 方法**

修改 `src/stores/fileStore.ts` 中的 `addFile` 方法，不再立即提取图标：

```typescript
async function addFile(path: string, customGroupId?: string) {
  // ... 现有逻辑

  const newFile: FileItem = {
    id: uuidv4(),
    name: fileName,
    path,
    type: 'file',
    icon: deriveIconFromExt(fileName),  // 只使用扩展名图标
    tags: [],
    groupId: resolvedGroupId,
    openCount: 0,
    createdAt: Date.now(),
    orderIndex: files.value.length
  }

  files.value.push(newFile)
  
  // 移除这里的 getFileIcon 调用
  // 图标将通过懒加载提取
}
```

- [ ] **Step 4: 测试图标懒加载**

手动测试：
1. 添加 50+ 个文件
2. 观察图标加载：首屏图标应在 1 秒内加载完成
3. 滚动时，新出现的文件图标逐步加载
4. 预期：启动快速，图标加载无感知

- [ ] **Step 5: Commit**

```bash
git add src/App.vue src/stores/fileStore.ts
git commit -m "feat: integrate icon lazy loading"
```

---

## Task 7: 性能测试与验证

**Files:**
- Create: `docs/testing/v0.1.0-performance-test.md`

- [ ] **Step 1: 准备测试数据**

创建测试脚本生成 1000 个测试文件：

```bash
# 在项目根目录创建测试文件夹
mkdir -p test-files
cd test-files

# 生成 1000 个测试文件
for i in {1..1000}; do
  echo "Test file $i" > "test-file-$i.txt"
done
```

- [ ] **Step 2: 启动时间测试**

测试步骤：
1. 清空 localStorage（开发者工具 > Application > Local Storage > Clear）
2. 运行 `npm run tauri:dev`
3. 使用秒表记录从启动到窗口完全显示的时间
4. 记录结果

预期：< 500ms

- [ ] **Step 3: 大量文件滚动测试**

测试步骤：
1. 批量添加 test-files 文件夹中的 1000 个文件
2. 在网格视图中快速滚动
3. 打开开发者工具 > Performance，录制滚动过程
4. 检查帧率（FPS）

预期：≥ 55fps

- [ ] **Step 4: 搜索响应时间测试**

测试步骤：
1. 在搜索框输入 "test"
2. 使用开发者工具 > Performance 录制
3. 测量从输入完成到结果显示的时间

预期：< 100ms

- [ ] **Step 5: 内存占用测试**

测试步骤：
1. 打开任务管理器
2. 查看 File Keeper 进程的内存占用
3. 添加 1000 个文件后再次检查

预期：< 100MB

- [ ] **Step 6: 记录测试结果**

创建 `docs/testing/v0.1.0-performance-test.md`：

```markdown
# File Keeper v0.1.0 性能测试报告

**测试日期**: 2026-05-XX  
**测试环境**: Windows 11 x64  
**文件数量**: 1000

## 测试结果

| 指标 | 目标 | 实际 | 状态 |
|------|------|------|
| 启动时间 | < 500ms | XXX ms | ✅/❌ |
| 滚动帧率 | ≥ 55fps | XX fps | ✅/❌ |
| 搜索响应 | < 100ms | XX ms | ✅/❌ |
| 内存占用 | < 100MB | XX MB | ✅/❌ |

## 详细说明

### 启动时间
- 测试方法：...
- 结果：...

### 滚动性能
- 测试方法：...
- 结果：...

### 搜索性能
- 测试方法：...
- 结果：...

### 内存占用
- 测试方法：...
- 结果：...

## 结论

[总结性能测试结果]
```

- [ ] **Step 7: Commit**

```bash
git add docs/testing/v0.1.0-performance-test.md
git commit -m "docs: add v0.1.0 performance test report"
```

---

## Task 8: 编写应用场景文档

**Files:**
- Create: `docs/USE_CASES.md`

- [ ] **Step 1: 创建应用场景文档**

创建 `docs/USE_CASES.md`，内容参考设计文档第 5 节。

完整内容见设计文档，包含：
- 程序员/开发者场景
- 数据分析师/财务人员场景
- 设计师/创意工作者场景
- 学生/研究人员场景
- 团队管理者/项目经理场景
- 个人用户/效率爱好者场景
- 核心优势总结（包含"组合打开多文件"）

- [ ] **Step 2: Commit**

```bash
git add docs/USE_CASES.md
git commit -m "docs: add use cases guide for different user groups"
```

---

## Task 9: 准备应用图标

**Files:**
- Create: `icons/32x32.png`
- Create: `icons/128x128.png`
- Create: `icons/icon.ico`

- [ ] **Step 1: 设计或选择应用图标**

选项 A：使用在线工具生成简单图标
- 访问 https://www.favicon-generator.org/
- 上传一个文件夹+星标的图标
- 生成多种尺寸

选项 B：使用 Tauri 默认图标（临时方案）
- 复制 `src-tauri/icons/` 中的现有图标

- [ ] **Step 2: 准备图标文件**

确保以下文件存在：
- `icons/32x32.png`
- `icons/128x128.png`
- `icons/icon.ico`
- [ ] **Step 3: Commit**

```bash
git add icons/
git commit -m "assets: add application icons"
```

---

## Task 10: 配置 Tauri 打包参数

**Files:**
- Modify: `src-tauri/tauri.conf.json`

- [ ] **Step 1: 更新 tauri.conf.json**

修改 `src-tauri/tauri.conf.json`：

```json
{
  "productName": "File Keeper",
  "version": "0.1.0",
  "identifier": "com.filekeeper.app",
  "build": {
    "beforeDevCommand": "npm run dev",
    "beforeBuildCommand": "npm run build",
    "devUrl": "http://localhost:1420",
    "frontendDist": "../dist"
  },
  "bundle": {
    "active": true,
    "targets": ["msi"],
    "icon": [
      "icons/32x32.png",
      "icons/128x128.png",
      "icons/icon.ico"
    ],
    "windows": {
      "certificateThumbprint": null,
      "digestAlgorithm": "sha256",
      "timestampUrl": "",
      "wix": {
        "language": "zh-CN"
    }
    },
    "shortDescription": "快速访问常用文件的桌面工具",
    "longDescription": "File Keeper 是一款轻量级文件收藏管理工具，支持文件分组、批量操作、全局快捷键、系统托盘等功能。"
  }
}
```

- [ ] **Step 2: 验证配置**

运行构建测试：

```bash
npm run tauri build
```

预期：生成 `.msi` 文件在 `src-tauri/target/release/bundle/msi/`

- [ ] **Step 3: Commit**

```bash
git add src-tauri/tauri.conf.json
git commit -m "chore: configure Tauri bundle settings for v0.1.0"
```

---

## Task 11: 更新文档（README 和 CHANGELOG）

**Files:**
- Modify: `README.md`
- Modify: `CHANGELOG.md`

- [ ] **Step 1: 更新 README.md**

替换 `README.md` 内容为 v0.1.0 版本说明（参考设计文档 4.5.1 节）：

```markdown
# File Keeper v0.1.0

快速访问常用文件的桌面工具

## 功能特性
- 文件收藏与分组管理
- 一键打开文件/文件夹
- 全局快捷键快速唤起（Ctrl+Alt+K）
- 批量操作（多选打开、移动、删除）
- 进程管理（关闭占用文件的进程）
- 系统托盘常驻

## 安装说明
1. 下载 `File-Keeper_0.1.0_x64_en-US.msi`
2. 双击运行安装程序
3. 如遇到 Windows SmartScreen 警告：
   - 点击"更多信息"
   - 点击"仍要运行"
4. 按提示完成安装

## 使用说明
- **首次启动**：点击"+ 添加文件"添加常用文件
- **全局快捷键**：Ctrl+Alt+K 唤起/隐藏窗口（可在设置中自定义）
- **系统托盘**：关闭窗口后最小化到托盘，右键托盘图标可退出
- **批量操作**：按住 Ctrl 点击多个文件，或使用复选框多选
- **拖拽排序**：在网格视图中拖拽文件卡片可调整顺序

## 系统要求
- Windows 10/11 x64
- 约 50MB 磁盘空间

## 已知问题
- 进程管理功能在某些情况下可能无法准确识别进程（准确率约 95%）
- 仅支持 Windows 平台

## 反馈与支持
如有问题或建议，请联系：[你的联系方式]
```

- [ ] **Step 2: 更新 CHANGELOG.md**

在 `CHANGELOG.md` 顶部添加 v0.1.0 版本记录（参考设计文档 4.5.2 节）：

```markdown
# Changelog

## [0.1.0] - 2026-05-XX

### 首个发布版本

**核心功能**
- 文件收藏与分组管理
- 一键打开文件/文件夹
- 搜索与过滤（支持通配符）
- 批量操作（多选、批量打开/移动/删除/添加标签）
- 全局快捷键（可自定义）
- 系统托盘（最小化到托盘）
- 主题切换（浅色/深色/跟随系统）
- 进程管理（检测并关闭占用文件的进程）
- 拖拽重排序
- 最近打开文件快速访问

**性能优化**
- 虚拟滚动支持大量文件（1000+ 文件流畅滚动）
- 搜索防抖优化（300ms 延迟）
- 图标懒加载（按需提取文件图标）

**已知限制**
- 仅支持 Windows 平台
- 进程管理功能准确率约 95%
- 无代码签名（安装时有 SmartScreen 警告）
```

- [ ] **Step 3: Commit**

```bash
git add README.md CHANGELOG.md
git commit -m "docs: update README and CHANGELOG for v0.1.0"
```

---

## Task 12: 构建和测试安装包

**Files:**
- None (build artifacts)

- [ ] **Step 1: 清理构建缓存**

```bash
cd src-tauri
cargo clean
cd ..
rm -rf dist
```

- [ ] **Step 2: 构建生产版本**

```bash
npm run tauri build
```

预期输出：
```
Finished release [optimized] target(s) in XX.XXs
    Bundling File-Keeper_0.1.0_x64_en-US.msi
```

- [ ] **Step 3: 验证安装包**

检查文件：
```bash
ls -lh src-tauri/target/release/bundle/msi/
```

预期：`File-Keeper_0.1.0_x64_en-US.msi` 约 10-15MB

- [ ] **Step 4: 测试安装**

在干净的 Windows 环境（或虚拟机）测试：
1. 双击 `.msi` 文件
2. 点击"更多信息" → "仍要运行"（SmartScreen 警告）
3. 完成安装
4. 启动应用，测试基本功能
5. 卸载应用（Windows 设置 > 应用）

- [ ] **Step 5: 记录构建信息**

创建 `docs/testing/v0.1.0-build-info.md`：

```markdown
# File Keeper v0.1.0 构建信息

**构建日期**: 2026-05-XX  
**构建环境**: Windows 11 x64  
**Node.js**: vXX.XX.XX  
**Rust**: vX.XX.X

## 构建产物

- 文件名: `File-Keeper_0.1.0_x64_en-US.msi`
- 大小: XX.XX MB
- SHA256: [计算 hash]

## 安装测试

- [x] 安装成功
- [x] 启动正常
- [x] 基本功能可用
- [x] 卸载干净

## 已知问题

[记录测试中发现的问题]
```

- [ ] **Step 6: Commit**

```bash
git add docs/testing/v0.1.0-build-info.md
git commit -m "docs: add v0.1.0 build information"
```

- [ ] **Step 7: 创建 Git Tag**

```bash
git tag -a v0.1.0 -m "Release v0.1.0 - First B version release"
git push origin v0.1.0
```

---
## 自查清单

完成所有任务后，检查以下项目：

**性能指标**：
- [ ] 启动时间 < 500ms
- [ ] 1000 文件滚动帧率 ≥ 55fps
- [ ] 搜索响应时间 < 100ms
- [ ] 内存占用 < 100MB
- [ ] 安装包体积 < 15MB

**功能完整性**：
- [ ] 虚拟滚动（网格视图 + 列表视图）
- [ ] 搜索防抖
- [ ] 图标懒加载
- [ ] 所有核心功能正常工作

**文档完整性**：
- [ ] README.md
- [ ] CHANGELOG.md
- [ ] USE_CASES.md
- [ ] 性能测试报告
- [ ] 构建信息文档

**可分发性**：
- [ ] .msi 安装包生成成功
- [ ] 安装/卸载流程正常
- [ ] 快捷方式创建正常
- [ ] Git tag 已创建

---

## 执行建议

**预计工期**: 2-3 天

**第 1 天**：Task 1-6（性能优化）
**第 2 天**：Task 7-9（测试与文档）
**第 3 天**：Task 10-12（打包发布）

**注意事项**：
- 虚拟滚动可能需要多次调整参数（itemHeight、overscan）
- 图标懒加载队列需要测试并发控制
- 性能测试需要在真实数据量下进行
- 安装包测试建议在干净环境进行

---
