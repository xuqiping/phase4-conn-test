# File Keeper 数据持久化 - 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 File Keeper 实现 Pinia store 自动持久化到 Tauri Store 的完整方案，覆盖文件、分组、设置三类数据。

**Architecture:** 创建 Pinia 插件，使用 `@tauri-apps/plugin-store` 的 `autoSave` 机制（带防抖）。重要操作（addFile/removeFile/addGroup）通过手动 `save()` 立即落盘；次要操作（recordOpen）依赖 autoSave 防抖批处理。

**Tech Stack:** Vue 3, Pinia, Tauri 2 (`@tauri-apps/plugin-store@2.4.3`), Vitest（测试）, TypeScript

---

## 文件结构

**新建：**
- `src/api/persist.ts` - Tauri Store 的薄封装
- `src/plugins/persistPlugin.ts` - Pinia 持久化插件
- `src/plugins/__tests__/persistPlugin.test.ts` - 插件单元测试
- `src/api/__tests__/persist.test.ts` - API 单元测试
- `vitest.config.ts` - Vitest 配置

**修改：**
- `src/main.ts` - 注册插件，等待加载完成后再 mount
- `src/stores/fileStore.ts` - 添加 persist 配置
- `src/stores/groupStore.ts` - 添加 persist 配置
- `src/stores/settingsStore.ts` - 添加 persist 配置
- `src/App.vue` - 移除 mock 数据加载（改为依赖持久化加载）
- `package.json` - 添加 vitest、@vue/test-utils
- `开发进度.md` - 更新进度

---

## Task 1: 验证项目可启动

**Files:** 无修改，仅验证

- [ ] **Step 1: 运行开发服务器**

```bash
cd "c:\AI Projects\file-keeper"
pnpm tauri:dev
```

预期：Tauri 窗口打开，看到 File Keeper 应用界面，3 条 mock 文件显示

- [ ] **Step 2: 关闭窗口，记录任何报错**

如有报错，先解决再继续。常见问题：
- `Cargo not found` → 安装 Rust toolchain
- `pnpm not found` → 安装 pnpm
- 端口冲突 → 检查 1420 端口

- [ ] **Step 3: 提交（如有修复）**

```bash
git add -A
git commit -m "chore: 验证开发环境可正常启动"
```

如无修复则跳过此步。

---

## Task 2: 安装 Vitest 测试框架

**Files:**
- 修改：`c:\AI Projects\file-keeper\package.json`
- 创建：`c:\AI Projects\file-keeper\vitest.config.ts`

- [ ] **Step 1: 安装 Vitest 和 Vue Test Utils**

```bash
cd "c:\AI Projects\file-keeper"
pnpm add -D vitest @vue/test-utils @vitest/ui jsdom
```

- [ ] **Step 2: 创建 vitest.config.ts**

文件路径：`c:\AI Projects\file-keeper\vitest.config.ts`

```typescript
import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'
import { resolve } from 'path'

export default defineConfig({
  plugins: [vue()],
  test: {
    environment: 'jsdom',
    globals: true,
    include: ['src/**/__tests__/**/*.test.ts']
  },
  resolve: {
    alias: {
      '@': resolve(__dirname, 'src')
    }
  }
})
```

- [ ] **Step 3: 添加 test 脚本到 package.json**

修改 `package.json` 的 `scripts` 字段，添加：

```json
"test": "vitest run",
"test:watch": "vitest",
"test:ui": "vitest --ui"
```

完整 scripts 段示例：

```json
"scripts": {
  "dev": "vite",
  "build": "vue-tsc && vite build",
  "preview": "vite preview",
  "tauri": "tauri",
  "tauri:dev": "tauri dev",
  "tauri:build": "tauri build",
  "test": "vitest run",
  "test:watch": "vitest",
  "test:ui": "vitest --ui"
}
```

- [ ] **Step 4: 验证测试框架可用**

```bash
pnpm test
```

预期：`No test files found` 或类似提示（因为还没写测试）

- [ ] **Step 5: 提交**

```bash
git add package.json pnpm-lock.yaml vitest.config.ts
git commit -m "chore: 添加 Vitest 测试框架"
```

---

## Task 3: 创建持久化 API（TDD）

**Files:**
- 创建测试：`c:\AI Projects\file-keeper\src\api\__tests__\persist.test.ts`
- 创建实现：`c:\AI Projects\file-keeper\src\api\persist.ts`

- [ ] **Step 1: 编写失败的测试**

文件路径：`c:\AI Projects\file-keeper\src\api\__tests__\persist.test.ts`

```typescript
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { createPersistAPI } from '../persist'

// Mock @tauri-apps/plugin-store
const mockSet = vi.fn()
const mockGet = vi.fn()
const mockSave = vi.fn()
const mockLoad = vi.fn()

vi.mock('@tauri-apps/plugin-store', () => ({
  Store: {
    load: vi.fn(() => Promise.resolve({
      set: mockSet,
      get: mockGet,
      save: mockSave
    }))
  }
}))

describe('PersistAPI', () => {
  beforeEach(() => {
    mockSet.mockClear()
    mockGet.mockClear()
    mockSave.mockClear()
  })

  it('load 从存储读取数据', async () => {
    mockGet.mockResolvedValueOnce({ files: [{ id: '1', name: 'test' }] })
    const api = await createPersistAPI('test.json')
    const result = await api.load('files', [])
    expect(result).toEqual({ files: [{ id: '1', name: 'test' }] })
  })

  it('load 数据不存在时返回默认值', async () => {
    mockGet.mockResolvedValueOnce(undefined)
    const api = await createPersistAPI('test.json')
    const result = await api.load('files', [])
    expect(result).toEqual([])
  })

  it('save 写入数据到存储', async () => {
    const api = await createPersistAPI('test.json')
    await api.save('files', [{ id: '1' }])
    expect(mockSet).toHaveBeenCalledWith('files', [{ id: '1' }])
  })

  it('flush 立即调用 save 强制落盘', async () => {
    const api = await createPersistAPI('test.json')
    await api.flush()
    expect(mockSave).toHaveBeenCalled()
  })

  it('load 在加载失败时返回默认值并记录错误', async () => {
    mockGet.mockRejectedValueOnce(new Error('disk error'))
    const consoleSpy = vi.spyOn(console, 'error').mockImplementation(() => {})
    const api = await createPersistAPI('test.json')
    const result = await api.load('files', ['default'])
    expect(result).toEqual(['default'])
    expect(consoleSpy).toHaveBeenCalled()
    consoleSpy.mockRestore()
  })
})
```

- [ ] **Step 2: 运行测试验证失败**

```bash
pnpm test src/api/__tests__/persist.test.ts
```

预期：FAIL，错误为 "Cannot find module '../persist'"

- [ ] **Step 3: 实现 persist.ts**

文件路径：`c:\AI Projects\file-keeper\src\api\persist.ts`

```typescript
import { Store } from '@tauri-apps/plugin-store'

export interface PersistAPI {
  load<T>(key: string, defaultValue: T): Promise<T>
  save<T>(key: string, value: T): Promise<void>
  flush(): Promise<void>
}

export interface PersistOptions {
  autoSaveDebounceMs?: number
}

export async function createPersistAPI(
  path: string,
  options: PersistOptions = {}
): Promise<PersistAPI> {
  const { autoSaveDebounceMs = 500 } = options

  const store = await Store.load(path, {
    defaults: {},
    autoSave: autoSaveDebounceMs
  })

  return {
    async load<T>(key: string, defaultValue: T): Promise<T> {
      try {
        const value = await store.get<T>(key)
        return value !== undefined ? value : defaultValue
      } catch (error) {
        console.error(`[PersistAPI] 加载 "${key}" 失败:`, error)
        return defaultValue
      }
    },

    async save<T>(key: string, value: T): Promise<void> {
      try {
        await store.set(key, value)
      } catch (error) {
        console.error(`[PersistAPI] 保存 "${key}" 失败:`, error)
        throw error
      }
    },

    async flush(): Promise<void> {
      try {
        await store.save()
      } catch (error) {
        console.error('[PersistAPI] flush 失败:', error)
        throw error
      }
    }
  }
}
```

- [ ] **Step 4: 运行测试验证通过**

```bash
pnpm test src/api/__tests__/persist.test.ts
```

预期：5 个测试全部 PASS

- [ ] **Step 5: 提交**

```bash
git add src/api/persist.ts src/api/__tests__/persist.test.ts
git commit -m "feat(persist): 实现 Tauri Store 持久化 API 封装"
```

---

## Task 4: 创建 Pinia 持久化插件（TDD）

**Files:**
- 创建测试：`c:\AI Projects\file-keeper\src\plugins\__tests__\persistPlugin.test.ts`
- 创建实现：`c:\AI Projects\file-keeper\src\plugins\persistPlugin.ts`

- [ ] **Step 1: 编写失败的测试**

文件路径：`c:\AI Projects\file-keeper\src\plugins\__tests__\persistPlugin.test.ts`

```typescript
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { createPinia, defineStore, setActivePinia } from 'pinia'
import { createPersistPlugin } from '../persistPlugin'

const mockSave = vi.fn()
const mockFlush = vi.fn()
const mockLoad = vi.fn()

vi.mock('../../api/persist', () => ({
  createPersistAPI: vi.fn(() => Promise.resolve({
    load: mockLoad,
    save: mockSave,
    flush: mockFlush
  }))
}))

describe('createPersistPlugin', () => {
  beforeEach(() => {
    mockSave.mockClear()
    mockFlush.mockClear()
    mockLoad.mockClear()
    mockLoad.mockResolvedValue(undefined)
  })

  it('未配置 persist 选项的 store 不应触发持久化', async () => {
    const plugin = createPersistPlugin()
    const pinia = createPinia()
    pinia.use(plugin)
    setActivePinia(pinia)

    const useStore = defineStore('plain', {
      state: () => ({ count: 0 }),
      actions: {
        increment() { this.count++ }
      }
    })
    const store = useStore()
    store.increment()

    await new Promise(resolve => setTimeout(resolve, 50))
    expect(mockSave).not.toHaveBeenCalled()
  })

  it('配置了 persist 的 store 在变化时调用 save', async () => {
    const plugin = createPersistPlugin()
    const pinia = createPinia()
    pinia.use(plugin)
    setActivePinia(pinia)

    const useStore = defineStore('test', {
      state: () => ({ count: 0 }),
      actions: {
        increment() { this.count++ }
      },
      persist: { key: 'test' }
    })

    const store = useStore()
    await store.$persistReady
    store.increment()

    await new Promise(resolve => setTimeout(resolve, 50))
    expect(mockSave).toHaveBeenCalled()
  })

  it('action 在 importantActions 列表中时调用 flush 立即落盘', async () => {
    const plugin = createPersistPlugin()
    const pinia = createPinia()
    pinia.use(plugin)
    setActivePinia(pinia)

    const useStore = defineStore('test2', {
      state: () => ({ items: [] as string[] }),
      actions: {
        addItem(s: string) { this.items.push(s) },
        touchItem() { /* noop */ }
      },
      persist: {
        key: 'test2',
        importantActions: ['addItem']
      }
    })

    const store = useStore()
    await store.$persistReady
    store.addItem('hello')

    await new Promise(resolve => setTimeout(resolve, 50))
    expect(mockFlush).toHaveBeenCalled()
  })

  it('启动时从存储加载数据到 store state', async () => {
    mockLoad.mockResolvedValueOnce({ count: 42 })
    const plugin = createPersistPlugin()
    const pinia = createPinia()
    pinia.use(plugin)
    setActivePinia(pinia)

    const useStore = defineStore('test3', {
      state: () => ({ count: 0 }),
      persist: { key: 'test3' }
    })

    const store = useStore()
    await store.$persistReady
    expect(store.count).toBe(42)
  })
})
```

- [ ] **Step 2: 运行测试验证失败**

```bash
pnpm test src/plugins/__tests__/persistPlugin.test.ts
```

预期：FAIL，错误为 "Cannot find module '../persistPlugin'"

- [ ] **Step 3: 实现 persistPlugin.ts**

文件路径：`c:\AI Projects\file-keeper\src\plugins\persistPlugin.ts`

```typescript
import type { PiniaPlugin, PiniaPluginContext } from 'pinia'
import { createPersistAPI, type PersistAPI } from '../api/persist'

export interface StorePersistOptions {
  key: string                    // 在 store 文件中的 key
  path?: string                  // 存储文件路径（默认 file-keeper-data.json）
  paths?: string[]               // 需持久化的字段名（不指定则全部）
  importantActions?: string[]    // 重要 action 名（触发立即 flush）
  debounceMs?: number            // autoSave 防抖时长（默认 500）
}

declare module 'pinia' {
  export interface DefineStoreOptionsBase<S, Store> {
    persist?: StorePersistOptions
  }
  export interface PiniaCustomProperties {
    $persistReady: Promise<void>
  }
}

const apiCache = new Map<string, Promise<PersistAPI>>()

function getAPI(path: string, debounceMs: number): Promise<PersistAPI> {
  if (!apiCache.has(path)) {
    apiCache.set(path, createPersistAPI(path, { autoSaveDebounceMs: debounceMs }))
  }
  return apiCache.get(path)!
}

function pickPaths<T extends object>(state: T, paths?: string[]): Partial<T> {
  if (!paths || paths.length === 0) return state
  const result: Partial<T> = {}
  for (const key of paths) {
    if (key in state) {
      ;(result as any)[key] = (state as any)[key]
    }
  }
  return result
}

export function createPersistPlugin(): PiniaPlugin {
  return ({ store, options }: PiniaPluginContext) => {
    const persist = options.persist
    if (!persist) return

    const {
      key,
      path = 'file-keeper-data.json',
      paths,
      importantActions = [],
      debounceMs = 500
    } = persist

    let api: PersistAPI | null = null
    let loaded = false

    const ready = (async () => {
      api = await getAPI(path, debounceMs)
      const stored = await api.load<Partial<typeof store.$state>>(key, {} as any)
      if (stored && typeof stored === 'object' && Object.keys(stored).length > 0) {
        store.$patch(stored)
      }
      loaded = true
    })()

    store.$persistReady = ready

    store.$subscribe((_mutation, state) => {
      if (!loaded || !api) return
      const dataToSave = pickPaths(state, paths)
      api.save(key, dataToSave).catch(err => {
        console.error(`[persistPlugin] save 失败 (${key}):`, err)
      })
    })

    store.$onAction(({ name, after }) => {
      if (!importantActions.includes(name)) return
      after(() => {
        if (!loaded || !api) return
        api.flush().catch(err => {
          console.error(`[persistPlugin] flush 失败 (${key}):`, err)
        })
      })
    })
  }
}
```

- [ ] **Step 4: 运行测试验证通过**

```bash
pnpm test src/plugins/__tests__/persistPlugin.test.ts
```

预期：4 个测试全部 PASS

如有失败，请检查：
- `defineStore` 第三个参数的 `persist` 字段是否被插件读取
- 异步加载的时序问题（await 时间）

- [ ] **Step 5: 提交**

```bash
git add src/plugins/persistPlugin.ts src/plugins/__tests__/persistPlugin.test.ts
git commit -m "feat(persist): 实现 Pinia 持久化插件"
```

---

## Task 5: 配置 fileStore 使用持久化

**Files:**
- 修改：`c:\AI Projects\file-keeper\src\stores\fileStore.ts`

- [ ] **Step 1: 在 fileStore 末尾添加 persist 配置（保留 setup store 风格）**

Pinia setup store 支持 `defineStore` 的第三参数传入 options。我们只需在末尾追加 options，无需重构现有代码。

文件路径：`c:\AI Projects\file-keeper\src\stores\fileStore.ts`

完整新内容：

```typescript
import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import type { FileItem } from '../types/file'
import { v4 as uuidv4 } from 'uuid'

export const useFileStore = defineStore('file', () => {
  // State
  const files = ref<FileItem[]>([])
  const searchQuery = ref('')
  const currentGroupId = ref('all')

  // Getters
  const filteredFiles = computed(() => {
    let result = files.value

    if (currentGroupId.value === 'all') {
      // Show all files
    } else if (currentGroupId.value === 'recent') {
      result = result.filter(f =>
        f.openCount > 20 ||
        (f.lastOpened && Date.now() - f.lastOpened < 7 * 24 * 60 * 60 * 1000)
      )
    } else {
      result = result.filter(f => f.groupId === currentGroupId.value)
    }

    if (searchQuery.value) {
      const query = searchQuery.value.toLowerCase()
      result = result.filter(f =>
        f.name.toLowerCase().includes(query) ||
        f.path.toLowerCase().includes(query) ||
        f.tags.some(tag => tag.toLowerCase().includes(query))
      )
    }

    return result
  })

  const recentFiles = computed(() => {
    return [...files.value]
      .filter(f => f.lastOpened)
      .sort((a, b) => (b.lastOpened || 0) - (a.lastOpened || 0))
      .slice(0, 10)
  })

  // Actions
  function addFile(file: Omit<FileItem, 'id' | 'createdAt' | 'openCount'>) {
    const newFile: FileItem = {
      ...file,
      id: uuidv4(),
      createdAt: Date.now(),
      openCount: 0
    }
    files.value.push(newFile)
    return newFile
  }

  function removeFile(id: string) {
    const index = files.value.findIndex(f => f.id === id)
    if (index !== -1) {
      files.value.splice(index, 1)
    }
  }

  function updateFile(id: string, updates: Partial<FileItem>) {
    const file = files.value.find(f => f.id === id)
    if (file) {
      Object.assign(file, updates)
    }
  }

  function recordOpen(id: string) {
    const file = files.value.find(f => f.id === id)
    if (file) {
      file.openCount++
      file.lastOpened = Date.now()
    }
  }

  function setSearchQuery(query: string) {
    searchQuery.value = query
  }

  function setCurrentGroup(groupId: string) {
    currentGroupId.value = groupId
  }

  function loadFiles(data: FileItem[]) {
    files.value = data
  }

  return {
    files,
    searchQuery,
    currentGroupId,
    filteredFiles,
    recentFiles,
    addFile,
    removeFile,
    updateFile,
    recordOpen,
    setSearchQuery,
    setCurrentGroup,
    loadFiles
  }
}, {
  persist: {
    key: 'files',
    paths: ['files'],
    importantActions: ['addFile', 'removeFile', 'loadFiles']
  }
})
```

变更要点（仅尾部新增）：
- 在 `defineStore` 末尾闭合大括号 `}` 之后改为 `}, { persist: {...} })`
- 其余 setup 函数代码完全保持原状

注意：
- `searchQuery` 和 `currentGroupId` 不进入持久化（属于 UI 临时状态）
- `addFile`、`removeFile`、`loadFiles` 是重要操作（立即落盘）
- `recordOpen`、`updateFile` 不在 importantActions 中，依赖 autoSave 防抖

- [ ] **Step 2: 验证项目可编译**

```bash
pnpm build
```

预期：构建成功

如有类型错误：
- `persist` 选项类型未识别 → 确认 `persistPlugin.ts` 中的 `declare module 'pinia'` 已扩展 `DefineStoreOptionsBase`

- [ ] **Step 3: 提交**

```bash
git add src/stores/fileStore.ts
git commit -m "feat(fileStore): 启用数据持久化"
```

---

## Task 6: 配置 groupStore 使用持久化

**Files:**
- 修改：`c:\AI Projects\file-keeper\src\stores\groupStore.ts`

- [ ] **Step 1: 在 groupStore 末尾添加 persist 配置（保留 setup store 风格）**

文件路径：`c:\AI Projects\file-keeper\src\stores\groupStore.ts`

完整新内容：

```typescript
import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import type { Group } from '../types/group'
import { v4 as uuidv4 } from 'uuid'

export const useGroupStore = defineStore('group', () => {
  const groups = ref<Group[]>([
    {
      id: 'all',
      name: '全部',
      order: 0,
      createdAt: Date.now()
    },
    {
      id: 'recent',
      name: '最近打开',
      order: 1,
      createdAt: Date.now()
    }
  ])

  const sortedGroups = computed(() => {
    return [...groups.value].sort((a, b) => a.order - b.order)
  })

  const customGroups = computed(() => {
    return groups.value.filter(g => g.id !== 'all' && g.id !== 'recent')
  })

  function addGroup(name: string, color?: string, icon?: string) {
    const maxOrder = Math.max(...groups.value.map(g => g.order), 1)
    const newGroup: Group = {
      id: uuidv4(),
      name,
      color,
      icon,
      order: maxOrder + 1,
      createdAt: Date.now()
    }
    groups.value.push(newGroup)
    return newGroup
  }

  function removeGroup(id: string) {
    if (id === 'all' || id === 'recent') return false
    const index = groups.value.findIndex(g => g.id === id)
    if (index !== -1) {
      groups.value.splice(index, 1)
      return true
    }
    return false
  }

  function updateGroup(id: string, updates: Partial<Group>) {
    const group = groups.value.find(g => g.id === id)
    if (group) {
      Object.assign(group, updates)
      return true
    }
    return false
  }

  function reorderGroups(newOrder: Group[]) {
    newOrder.forEach((group, index) => {
      const existing = groups.value.find(g => g.id === group.id)
      if (existing) {
        existing.order = index
      }
    })
  }

  function loadGroups(data: Group[]) {
    const defaultGroups = groups.value.filter(g => g.id === 'all' || g.id === 'recent')
    const customGroups = data.filter(g => g.id !== 'all' && g.id !== 'recent')
    groups.value = [...defaultGroups, ...customGroups]
  }

  return {
    groups,
    sortedGroups,
    customGroups,
    addGroup,
    removeGroup,
    updateGroup,
    reorderGroups,
    loadGroups
  }
}, {
  persist: {
    key: 'groups',
    paths: ['groups'],
    importantActions: ['addGroup', 'removeGroup', 'updateGroup', 'reorderGroups', 'loadGroups']
  }
})
```

- [ ] **Step 2: 验证项目可编译**

```bash
pnpm build
```

预期：构建成功

- [ ] **Step 3: 提交**

```bash
git add src/stores/groupStore.ts
git commit -m "feat(groupStore): 启用数据持久化"
```

---

## Task 7: 配置 settingsStore 使用持久化

**Files:**
- 修改：`c:\AI Projects\file-keeper\src\stores\settingsStore.ts`

- [ ] **Step 1: 在 settingsStore 末尾添加 persist 配置**

文件路径：`c:\AI Projects\file-keeper\src\stores\settingsStore.ts`

完整新内容：

```typescript
import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { Settings } from '../types/settings'

export const useSettingsStore = defineStore('settings', () => {
  const settings = ref<Settings>({
    theme: 'dark',
    defaultView: 'grid',
    globalShortcut: 'CommandOrControl+Shift+F',
    minimizeToTray: true,
    autoStart: false,
    language: 'zh-CN',
    itemsPerPage: 50
  })

  function updateSettings(updates: Partial<Settings>) {
    Object.assign(settings.value, updates)
  }

  function setTheme(theme: 'light' | 'dark' | 'auto') {
    settings.value.theme = theme
  }

  function setViewMode(mode: 'grid' | 'list') {
    settings.value.defaultView = mode
  }

  function toggleTheme() {
    settings.value.theme = settings.value.theme === 'dark' ? 'light' : 'dark'
  }

  function loadSettings(data: Settings) {
    settings.value = { ...settings.value, ...data }
  }

  return {
    settings,
    updateSettings,
    setTheme,
    setViewMode,
    toggleTheme,
    loadSettings
  }
}, {
  persist: {
    key: 'settings',
    paths: ['settings'],
    importantActions: ['updateSettings', 'setTheme', 'setViewMode', 'toggleTheme', 'loadSettings']
  }
})
```

- [ ] **Step 2: 验证项目可编译**

```bash
pnpm build
```

预期：构建成功

- [ ] **Step 3: 提交**

```bash
git add src/stores/settingsStore.ts
git commit -m "feat(settingsStore): 启用数据持久化"
```

---

## Task 8: 在 main.ts 注册插件

**Files:**
- 修改：`c:\AI Projects\file-keeper\src\main.ts`

- [ ] **Step 1: 注册插件**

文件路径：`c:\AI Projects\file-keeper\src\main.ts`

完整新内容：

```typescript
import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import { createPersistPlugin } from './plugins/persistPlugin'

import './styles/global.css'

const app = createApp(App)
const pinia = createPinia()

pinia.use(createPersistPlugin())

app.use(pinia)
app.mount('#app')
```

- [ ] **Step 2: 验证项目可编译**

```bash
pnpm build
```

预期：构建成功

- [ ] **Step 3: 提交**

```bash
git add src/main.ts
git commit -m "feat: 注册 Pinia 持久化插件"
```

---

## Task 9: 移除 App.vue 中的 mock 数据加载

**Files:**
- 修改：`c:\AI Projects\file-keeper\src\App.vue:472-507`

- [ ] **Step 1: 删除底部 mock 数据初始化代码**

定位到 `App.vue` 第 472-507 行，找到这段：

```typescript
// Initialize with mock data
fileStore.loadFiles([
  {
    id: '1',
    name: '2026年度产品规划.docx',
    // ... 其他 mock 数据
  },
  // ...
])
```

**完整删除整段 `fileStore.loadFiles([...])` 调用及其后的所有 mock 对象**（从 `// Initialize with mock data` 注释开始，到 `])` 闭合括号结束）。

- [ ] **Step 2: 验证项目可编译**

```bash
pnpm build
```

预期：构建成功

- [ ] **Step 3: 提交**

```bash
git add src/App.vue
git commit -m "refactor(App): 移除 mock 数据，改用持久化加载"
```

---

## Task 10: 端到端手动测试

**Files:** 无修改

- [ ] **Step 1: 启动应用**

```bash
cd "c:\AI Projects\file-keeper"
pnpm tauri:dev
```

- [ ] **Step 2: 验证首次启动状态**

预期：
- 应用打开，文件列表为空（"未找到匹配的文件"）
- 默认分组"全部"和"最近打开"显示
- 主题为深色（默认）
- 视图模式为网格（默认）

- [ ] **Step 3: 测试设置持久化**

操作：
1. 点击右上角"主题切换"按钮，切换到浅色主题
2. 点击底部"列表视图"按钮
3. **关闭应用窗口**
4. 重新运行 `pnpm tauri:dev`

预期：浅色主题保持，列表视图保持

- [ ] **Step 4: 测试分组持久化**

操作：
1. 点击 "+" 添加分组按钮（GroupTabs 中）
2. 输入分组名"测试分组"，确定
3. **关闭应用**，重新运行 `pnpm tauri:dev`

预期：测试分组仍然存在

- [ ] **Step 5: 检查存储文件**

打开 Tauri AppData 目录（Windows 上通常是 `%APPDATA%\com.filekeeper.app\`），应当能找到：

```
file-keeper-data.json
```

文件内容应包含 `files`、`groups`、`settings` 三个 key。

如果你不确定路径，运行：

```bash
echo $env:APPDATA   # PowerShell
# 或
echo %APPDATA%       # cmd
```

然后导航到 `<APPDATA>\com.filekeeper.app\` 查看。

- [ ] **Step 6: 测试数据损坏降级**

操作：
1. 关闭应用
2. 用文本编辑器打开 `file-keeper-data.json`，将文件改为 `{` （损坏 JSON）
3. 保存
4. 重新启动应用

预期：应用正常启动，使用默认值（控制台有错误日志）

- [ ] **Step 7: 测试完成后提交（如有问题修复）**

```bash
git add -A
git commit -m "test: 完成数据持久化端到端验证"
```

如无修复则跳过。

---

## Task 11: 更新开发进度文档

**Files:**
- 修改：`c:\AI Projects\file-keeper\开发进度.md`

- [ ] **Step 1: 更新 Phase 2.1 数据层**

在 `开发进度.md` 中找到 Phase 2.1，将以下两项从 `[ ]` 改为 `[x]`：

```markdown
- [x] 实现数据持久化 (tauri-plugin-store)
- [x] 编写 Rust store 命令
```

注：第二项（Rust store 命令）实际上不需要写——因为 `tauri-plugin-store` 已经提供了完整的命令。可以加上备注：

```markdown
- [x] 编写 Rust store 命令（使用 tauri-plugin-store 内置命令，无需自定义）
```

将"验收标准"中的两项改为 `[x]`：

```markdown
- [x] 数据可持久化存储
- [x] 数据增删改查功能完整
```

- [ ] **Step 2: 在更新日志中追加条目**

在文件末尾的 "## 🔄 更新日志" 部分，"### 2026-05-10 (下午)" 之后追加：

```markdown
### 2026-05-10 (晚间)
- ✅ 完成 Phase 2.1 数据持久化（tauri-plugin-store 集成）
- ✅ 创建 Pinia 持久化插件 (src/plugins/persistPlugin.ts)
- ✅ 创建持久化 API 封装 (src/api/persist.ts)
- ✅ 重构 fileStore/groupStore/settingsStore 为 options store + 持久化
- ✅ 添加 Vitest 测试框架
- ✅ 编写持久化模块单元测试（9 个用例）
- ✅ 移除 App.vue 中的 mock 数据
- ✅ 端到端手动测试通过
```

- [ ] **Step 3: 更新总体进度**

将顶部的"Phase 2: MVP核心功能开发"完成度从 35% 提升到适当数值（约 50%）。

具体修改：

```markdown
| Phase 2: MVP核心功能开发 | 🔄 进行中 | 50% | 3-4天 | 1.5天 |
```

并将总体进度从 27% 更新到约 35%：

```markdown
**总体进度：** 35% (2.5/8 天)
```

- [ ] **Step 4: 提交**

```bash
git add 开发进度.md
git commit -m "docs(file-keeper): 更新数据持久化完成进度"
```

---

## 验收清单

完成所有 Task 后，验证以下条目：

- [ ] 所有单元测试通过（`pnpm test`）
- [ ] `pnpm build` 构建无错误
- [ ] `pnpm tauri:dev` 启动正常
- [ ] 添加文件后重启，文件保持
- [ ] 创建分组后重启，分组保持
- [ ] 切换主题后重启，主题保持
- [ ] 切换视图模式后重启，模式保持
- [ ] 数据损坏时应用降级到默认值不崩溃
- [ ] 存储文件路径符合预期（AppData 下 file-keeper-data.json）
- [ ] 开发进度文档已更新

---

## 风险与缓解

| 风险 | 概率 | 影响 | 缓解 |
|------|------|------|------|
| Pinia options store 改写引入 bug | 中 | 高 | 单元测试 + 手动测试 |
| `Store.load` 在 SSR/测试环境报错 | 高 | 中 | 测试中 mock @tauri-apps/plugin-store |
| TypeScript `declare module 'pinia'` 类型扩展失败 | 低 | 中 | 在插件文件中导入并验证 |
| AppData 权限问题 | 低 | 高 | 错误处理 + 控制台日志 |

---

## 后续工作（不在本计划范围）

完成数据持久化后，按 [开发进度.md](../../开发进度.md) 优先级继续：

1. **Phase 2.2/2.3：文件操作** - AddFileButton + 文件选择对话框 + Rust open_file 集成
2. **窗口控制 API** - 实现最小化/最大化/关闭按钮
3. **Phase 3.1：批量操作** - 多选 + 批量打开/移动/删除
4. **Phase 3.2：进程管理** - 核心特色功能（Windows/macOS/Linux 进程匹配）

---

**计划维护者：** Claude
**最后更新：** 2026-05-10
