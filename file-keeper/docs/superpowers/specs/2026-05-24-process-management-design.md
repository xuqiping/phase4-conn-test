# File Keeper 应用管理功能设计文档

**版本**: v1.0  
**日期**: 2026-05-24  
**作者**: File Keeper Team  
**状态**: 设计阶段

---

## 1. 概述

### 1.1 功能目标

在 File Keeper 中新增应用管理功能，允许用户监控和管理所有正在运行的 Windows 应用程序。该功能独立于文件管理功能，提供类似任务管理器的体验，但更加轻量和用户友好。

### 1.2 核心特性

- **全面监控**: 支持 13+ 类别的应用程序（浏览器、Office、文件夹、终端、压缩包、文档、媒体、图像、通讯、下载、游戏、系统工具等）
- **灵活展示**: 混合视图（全部应用列表 + 分类筛选器），支持自定义显示列
- **批量操作**: 支持多选、全选、反选，批量关闭应用
- **智能刷新**: 手动刷新 + 可选的自动刷新（可配置间隔）
- **安全机制**: 可配置的关闭确认（始终确认/批量确认/从不确认），支持重要应用白名单
- **性能优化**: 虚拟滚动、页面不可见时停止监控、合理的刷新间隔

### 1.3 设计原则

- **独立性**: 应用管理和文件管理功能完全独立，互不干扰
- **渐进式**: 采用混合架构方案，分阶段实现功能
- **用户友好**: 直观的操作方式，清晰的信息展示
- **性能优先**: 标准监控级别，平衡功能和性能

---

## 2. 系统架构

### 2.1 整体架构

采用三层架构设计：

```
┌────────────────────────────────────┐
│                  前端层 (Vue 3)            │
├───────────────────────────────────────┤
│  App.vue (主应用)                                    │
│    ├─ FileManagement (文件管理 - 现有)             │
│    └─ ProcessManagement (应用管理 - 新增)           │
│         ├─ ProcessList (应用列表组件)                   │
│       ├─ ProcessFilter (分类筛选器)              │
│         ├─ ProcessToolbar (工具栏)                 │
│         └─ ProcessSettings (设置面板)                 │
├───────────────────────────────┤
│                   状态管理层 (Pinia)                     │
├───────────────────────────────┤
│  processStore.ts (应用状态管理)                      │
│  processSettingsStore.ts (设置状态管理)         │
├──────────────────────────────┤
│                  API层 (Tauri Commands)                │
├───────────────────────────────────────────┤
│  process.ts (前端API封装)                           │
├──────────────────────────────────────────────┤
│         后端层 (Rust/Tauri)                │
├───────────────────────────────────────────┤
│  commands/process.rs (Tauri命令)                  │
│  platform/windows/process_monitor.rs (进程监控核心)      │
│  platform/windows/process_mappings.rs (进程分类映射)     │
└──────────────────────────────────────┘
```

### 2.2 数据流设计

**应用列表获取流程**:
```
用户点击刷新
    ↓
ProcessList组件调用 processStore.refresh()
    ↓
processStore调用 API.getRunningProcesses()
    ↓
Tauri Command: enumerate_processes()
  ↓
Rust后端: ProcessMonitor.enumerate()
    ↓
Windows API: EnumWindows + sysinfo
    ↓
返回ProcessInfo[]数组
    ↓
processStore更新状态
    ↓
Vue响应式更新UI
```

**应用关闭流程**:
```
用户点击关闭按钮
    ↓
检查确认设置 (processSettingsStore)
    ↓
如需确认 → 显示确认对话框 → 用户确认
    ↓
ProcessList调用 processStore.closeProcess(id)
    ↓
processStore调用 API.closeProcess(id)
    ↓
Tauri Command: close_process(id)
    ↓
Rust后端: 发送WM_CLOSE消息
    ↓
返回成功/失败
    ↓
自动刷新列表
    ↓
显示操作结果提示
```

---

## 3. UI/UX 设计

### 3.1 应用管理标签页布局

```
┌────────────────────────────────┐
│  File Keeper                           [-] [□] [×]     │
├────────────────────────────────────────────────────┤
│  [🔍 搜索...]  [排序▼] [图标▼] [📁最近] [+文件] [+文件夹]      │
│  [☀️] [⚙️]  →  新增: [📊 应用管理]  ←                     │
├────────────────────────────────────────────────┤
│  ┌────────────────────────────────────────────┐  │
│  │ 应用管理页面                           │  │
│  ├─────────────────────────────────────────┤  │
│  │ 工具栏区域                              │  │
│  │ [🔄 刷新] [自动刷新: 关] [全选] [反选] [关闭选中(0)]     │  │
│  │                                      │  │
│  │ 筛选器: [全部应用 ▼] [浏览器] [Office] [文件夹] ...      │  │
│  ├───────────────────────────────────┤  │
│  │ 应用列表区域 (虚拟滚动)                             │  │
│  │                                      │  │
│  │ ┌─┬───────┬──────┬────────┬──────┬──────┬────────┐ │  │
│  │ │☐│应用名称     │类型  │进程名  │内存  │CPU   │操作    │ │  │
│  │ ├─┼────────┼──────┼────────┼──────┼──────┼────────┤ │  │
│  │ │☐│Google Chr..│浏览器│chrome..│256MB │2.3%  │[×关闭] │ │  │
│  │ │☐│文档 - Wor..│文档  │winword │128MB │0.5%  │[×关闭] │ │  │
│  │ │☐│下载      │文件夹│explor..│45MB  │0.1%  │[×关闭] │ │  │
│  │ │☐│PowerShell  │终端  │pwsh.exe│32MB  │0.0%  │[×关闭] │ │  │
│  │ └─┴────────────┴──────┴────────┴──────┴──────┴────────┘ │  │
│  │                                      │  │
│  │ 共 156 个应用 | 已选中 0 个                            │  │
│  └──────────────────────────────────────────────────┘  │
└────────────────────────────┘
```

### 3.2 关键UI组件

**工具栏 (ProcessToolbar)**:
- 刷新按钮: 手动刷新应用列表
- 自动刷新开关: 开启/关闭自动刷新，显示倒计时
- 全选按钮: 选中所有可见应用
- 反选按钮: 反转当前选择状态
- 关闭选中按钮: 批量关闭选中的应用（显示选中数量）

**分类筛选器 (ProcessFilter)**:
- 下拉框: 显示当前筛选类别
- 快速标签: 常用类别的快速切换按钮
- 支持的类别:
  - 全部应用
  - 浏览器 (Browser)
  - Office文档 (Excel, Word, PPT)
  - 文件夹 (Explorer)
  - 终端 (Terminal)
  - 压缩包 (Archive)
  - 文档编辑器 (Document)
  - 媒体播放器 (Media)
  - 图像工具 (Image)
  - 通讯工具 (Communication)
  - 下载工具 (Download)
  - 游戏 (Game)
  - 系统工具 (System)

**应用列表 (ProcessList)**:
- 使用虚拟滚动技术（复用现有的 useVirtualScroll）
- 默认显示列:
  - 复选框（多选）
  - 应用名称（窗口标题，最长100字符）
  - 应用类型（分类标签）
  - 进程名（可执行文件名）
  - 内存占用（MB格式）
  - CPU占用（百分比）
  - 运行时长（可选，默认隐藏）
  - PID（可选，默认隐藏）
  - 操作按钮（关闭）

**列自定义面板**:
- 在设置中提供"自定义显示列"选项
- 可以勾选/取消勾选各个列
- 可以拖拽调整列的顺序
- 保存到 processSettingsStore

### 3.3 交互设计

**选择交互**:
- 点击复选框: 选中/取消选中单个应用
- Ctrl + 点击行: 选中/取消选中
- Shift + 点击行: 范围选择
- 全选/反选按钮: 批量操作

**关闭交互**:
- 单个关闭: 点击行末的"关闭"按钮
- 批量关闭: 选中多个后点击"关闭选中"按钮
- 根据设置决定是否显示确认对话框

**刷新交互**:
- 手动刷新: 点击刷新按钮
- 自动刷新: 开关打开后，每N秒自动刷新（默认5秒）
- 显示刷新倒计时: "下次刷新: 3秒"
- 页面不可见时暂停自动刷新

**筛选交互**:
- 下拉框选择: 切换到指定类别
- 快速标签点击: 快速切换常用类别
- 筛选后显示: "浏览器 (12个应用)"

---

## 4. 数据结构设计

### 4.1 前端 TypeScript 类型

**src/types/process.ts**:

```typescript
// 应用分类枚举
export type ProcessCategory = 
  | 'browser'      // 浏览器
  | 'office'       // Office文档
  | 'explorer'     // 文件夹
  | 'terminal'     // 终端
  | 'archive'    // 压缩包
  | 'document'     // 文档编辑器
  | 'media'        // 媒体播放器
  | 'image'        // 图像工具
  | 'communication'// 通讯工具
  | 'download'     // 下载工具
  | 'game'     // 游戏
  | 'system'       // 系统工具
  | 'other'      // 其他

// 进程信息
export interface ProcessInfo {
  id: string                 // 唯一标识 (hwnd或pid)
  name: string             // 窗口标题或进程名
  category: ProcessCategory     // 应用分类
  categoryLabel: string         // 分类显示名称（如"Google Chrome - 浏览器"）
  processName: string           // 进程可执行文件名 (如 chrome.exe)
  pid: number                 // 进程ID
  memoryUsage: number           // 内存占用 (字节)
  cpuUsage: number              // CPU占用 (百分比 0-100)
  startTime: number             // 启动时间戳
  windowHandle?: number         // 窗口句柄 (Windows)
}

// 显示列配置
export interface ColumnConfig {
  key: string                   // 列标识
  label: string          // 列显示名称
  visible: boolean              // 是否显示
  width: number         // 列宽度
  sortable: boolean           // 是否可排序
  order: number           // 显示顺序
}

// 确认设置
export type ConfirmMode = 'always' | 'batch' | 'never'

// 应用管理设置
export interface ProcessSettings {
  // 显示设置
  columns: ColumnConfig[]       // 列配置
  
  // 刷新设置
  autoRefresh: boolean          // 是否自动刷新
  refreshInterval: number       // 刷新间隔(秒) 默认5
  
  // 确认设置
  confirmMode: ConfirmMode      // 确认模式
  whitelist: string[]           // 重要应用白名单(进程名)
  
  // 筛选设置
  lastCategory: ProcessCategory | 'all' // 上次选择的分类
}
```

**默认列配置**:

```typescript
function getDefaultColumns(): ColumnConfig[] {
  return [
    { key: 'checkbox', label: '', visible: true, width: 50, sortable: false, order: 0 },
    { key: 'name', label: '应用名称', visible: true, width: 300, sortable: true, order: 1 },
    { key: 'category', label: '类型', visible: true, width: 120, sortable: true, order: 2 },
    { key: 'processName', label: '进程名', visible: true, width: 150, sortable: true, order: 3 },
    { key: 'memoryUsage', label: '内存', visible: true, width: 100, sortable: true, order: 4 },
    { key: 'cpuUsage', label: 'CPU', visible: true, width: 80, sortable: true, order: 5 },
    { key: 'pid', label: 'PID', visible: false, width: 80, sortable: true, order: 6 },
    { key: 'startTime', label: '运行时长', visible: false, width: 120, sortable: true, order: 7 },
    { key: 'actions', label: '操作', visible: true, width: 100, sortable: false, order: 8 },
  ]
}
```

### 4.2 后端 Rust 类型

**src-tauri/src/types/process.rs**:

```rust
use serde::{Deserialize, Serialize};

// 应用分类
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum ProcessCategory {
    Browser,
    Office,
    Explorer,
    Terminal,
    Archive,
    Document,
    Media,
    Image,
    Communication,
    Download,
    Game,
    System,
    Other,
}

// 进程信息
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ProcessInfo {
    pub id: String,
    pub name: String,
    pub category: ProcessCategory,
    pub category_label: String,
    pub process_name: String,
    pub pid: u32,
    pub memory_usage: u64,
    pub cpu_usage: f32,
    pub start_time: u64,
    pub window_handle: Option<isize>,
}

// 进程映射表项
pub struct ProcessMapping {
    pub process_name: String,      // 进程名 (小写)
    pub display_name: String,      // 显示名称
    pub category: ProcessCategory, // 分类
}
```

---

## 5. 状态管理设计
### 5.1 processStore.ts (应用状态管理)
```typescript
export const useProcessStore = defineStore('process', () => {
  // 状态
  const processes = ref<ProcessInfo[]>([])       // 所有应用列表
  const selectedIds = ref<Set<string>>(new Set())    // 选中的应用ID
  const currentCategory = ref<ProcessCategory | 'all'>('all') // 当前筛选类别
  const isRefreshing = ref(false)                 // 是否正在刷新
  const lastRefreshTime = ref<number>(0)         // 上次刷新时间
  const autoRefreshTimer = ref<number | null>(null)  // 自动刷新定时器
  
  // 计算属性
  const filteredProcesses = computed(() => {
    if (currentCategory.value === 'all') {
      return processes.value
    }
    return processes.value.filter(p => p.category === currentCategory.value)
  })
  
  const selectedCount = computed(() => selectedIds.value.size)
  
  const categoryCounts = computed(() => {
    // 统计各分类的应用数量
    const counts: Record<string, number> = {}
    processes.value.forEach(p => {
      counts[p.category] = (counts[p.category] || 0) + 1
    })
    return counts
  })
  
  // 方法
  async function refresh() { /* 刷新应用列表 */ }
  async function closeProcess(id: string) { /* 关闭单个应用 */ }
  async function closeSelected() { /* 关闭选中的应用 */ }
  function toggleSelect(id: string) { /* 切换选择状态 */ }
  function selectAll() { /* 全选 */ }
  function invertSelection() { /* 反选 */ }
  function clearSelection() { /* 清空选择 */ }
  function setCategory(category: ProcessCategory | 'all') { /* 设置筛选类别 */ }
  function startAutoRefresh() { /* 启动自动刷新 */ }
  function stopAutoRefresh() { /* 停止自动刷新 */ }
  
  return {
    processes,
    selectedIds,
    currentCategory,
    isRefreshing,
    lastRefreshTime,
    filteredProcesses,
    selectedCount,
    categoryCounts,
    refresh,
    closeProcess,
    closeSelected,
    toggleSelect,
    selectAll,
    invertSelection,
    clearSelection,
    setCategory,
    startAutoRefresh,
    stopAutoRefresh,
  }
})
```

### 5.2 processSettingsStore.ts (设置状态管理)

```typescript
export const useProcessSettingsStore = defineStore('processSettings', () => {
  const settings = ref<ProcessSettings>({
    columns: getDefaultColumns(),
    autoRefresh: false,
    refreshInterval: 5,
    confirmMode: 'batch',
    whitelist: ['winword.exe', 'excel.exe', 'powerpnt.exe', 'code.exe'],
    lastCategory: 'all',
  })
  
  // 从本地存储加载设置
  function loadSettings() { /* ... */ }
  
  // 保存设置到本地存储
  function saveSettings() { /* ... */ }
  
  // 更新列配置
  function updateColumns(columns: ColumnConfig[]) { /* ... */ }
  
  // 添加到白名单
  function addToWhitelist(processName: string) { /* ... */ }
  
  // 从白名单移除
  function removeFromWhitelist(processName: string) { /* ... */ }
  
  return {
    settings,
    loadSettings,
    saveSettings,
    updateColumns,
    addToWhitelist,
    removeFromWhitelist,
  }
})
```

---


## 6. 后端实现设计

### 6.1 文件结构

```
src-tauri/src/
├── commands/
│   └── process.rs          // Tauri命令定义
├── platform/
│   └── windows/
│       ├── mod.rs
│       ├── process_monitor.rs  // 进程监控核心
│       └── process_mappings.rs // 进程分类映射表
├── types/
│   └── process.rs              // 类型定义
└── main.rs                // 注册命令
```

### 6.2 进程监控核心

**process_monitor.rs** 核心功能:

```rust
// 进程监控器
pub struct ProcessMonitor {
    mappings: HashMap<String, ProcessMapping>, // 进程名 -> 分类映射
}

impl ProcessMonitor {
    pub fn new() -> Self {
        // 初始化时加载所有进程映射表
        let mappings = load_process_mappings();
        Self { mappings }
    }
    
    // 枚举所有正在运行的应用
    pub fn enumerate_processes(&self) -> Result<Vec<ProcessInfo>> {
        // 1. 枚举所有可见窗口 (使用 EnumWindows API)
        // 2. 获取每个窗口的进程信息
        // 3. 去重（同一进程可能有多个窗口）
    }
    
    // 枚举所有可见窗口
    fn enumerate_windows(&self) -> Result<Vec<WindowInfo>> {
        // 使用 Windows API: EnumWindows
        // 过滤条件: IsWindowVisible = true, 有窗口标题
    }
    
    // 获取窗口的进程信息
    fn get_window_process_info(&self, window: WindowInfo) -> Result<Option<ProcessInfo>> {
        // 1. 通过窗口句柄获取进程ID (GetWindowThreadProcessId)
        // 2. 通过进程ID获取进程名 (使用 sysinfo crate)
        // 3. 根据进程名匹配分类
        // 4. 获取内存、CPU等信息 (使用 sysinfo crate)
        // 5. 构造 ProcessInfo 对象
    }
    
    // 去重处理
  fn deduplicate_processes(&self, processes: Vec<ProcessInfo>) -> Vec<ProcessInfo> {
        // 策略:
        // - 浏览器: 每个窗口作为独立应用
        // - Office: 每个文档作为独立应用
        // - 其他: 同一进程只保留一个
    }
    
    // 关闭进程
    pub fn close_process(&self, id: &str) -> Result<()> {
        // 1. 解析ID（可能是窗口句柄或进程ID）
     // 2. 如果是窗口句柄，发送 WM_CLOSE 消息
        // 3. 如果是进程ID，使用 TerminateProcess (谨慎使用)
        // 4. 优先使用温和的关闭方式（WM_CLOSE）
    }
    
    // 批量关闭进程
    pub fn close_processes(&self, ids: Vec<String>) -> Result<Vec<CloseResult>> {
        // 逐个关闭，记录成功/失败状态
    }
    
    // 根据进程名匹配分类
    fn match_category(&self, process_name: &str) -> (ProcessCategory, String) {
        // 查找映射表，返回分类和显示名称
    }
}
```

### 6.3 进程分类映射表

**process_mappings.rs** - 加载所有进程映射:

```rust
pub fn load_process_mappings() -> HashMap<String, ProcessMapping> {
    let mut mappings = HashMap::new();
    
    // 浏览器 (30+ 种)
    add_browser_mappings(&mut mappings);
    
  // Office
    add_office_mappings(&mut mappings);
    
    // 文件夹
    add_explorer_mappings(&mut mappings);
    
    // 终端 (15+ 种)
    add_terminal_mappings(&mut mappings);
    
    // 压缩包 (12+ 种)
    add_archive_mappings(&mut mappings);
    
    // 文档编辑器 (100+ 种)
    add_document_mappings(&mut mappings);
    
    // 媒体播放器 (40+ 种)
    add_media_mappings(&mut mappings);
    
    // 图像工具 (40+ 种)
    add_image_mappings(&mut mappings);
    
    // 通讯工具 (40+ 种)
    add_communication_mappings(&mut mappings);
    
    // 下载工具 (40+ 种)
  add_download_mappings(&mut mappings);
    
    // 游戏 (40+ 种)
    add_game_mappings(&mut mappings);
    
    // 系统工具 (50+ 种)
    add_system_mappings(&mut mappings);
    
    mappings
}

fn add_browser_mappings(mappings: &mut HashMap<String, ProcessMapping>) {
    let browsers = vec![
        ("chrome.exe", "Google Chrome"),
      ("msedge.exe", "Microsoft Edge"),
        ("firefox.exe", "Mozilla Firefox"),
        ("opera.exe", "Opera"),
        ("brave.exe", "Brave"),
        // ... 更多浏览器（参考Python工具的完整列表）
    ];
    
    for (process_name, display_name) in browsers {
        mappings.insert(
            process_name.to_lowercase(),
            ProcessMapping {
         process_name: process_name.to_string(),
                display_name: display_name.to_string(),
                category: ProcessCategory::Browser,
          }
        );
    }
}

// 类似地实现其他分类的映射函数...
```
### 6.4 Tauri 命令定义

**commands/process.rs**:

```rust
use tauri::command;
use crate::platform::windows::ProcessMonitor;
use crate::types::process::ProcessInfo;

// 全局进程监控器实例
lazy_static! {
    static ref PROCESS_MONITOR: ProcessMonitor = ProcessMonitor::new();
}

// 获取所有正在运行的应用
#[command]
pub async fn get_running_processes() -> Result<Vec<ProcessInfo>, String> {
    PROCESS_MONITOR
        .enumerate_processes()
        .map_err(|e| e.to_string())
}

// 关闭单个应用
#[command]
pub async fn close_process(id: String) -> Result<(), String> {
    PROCESS_MONITOR
        .close_process(&id)
        .map_err(|e| e.to_string())
}

// 批量关闭应用
#[command]
pub async fn close_processes(ids: Vec<String>) -> Result<Vec<CloseResult>, String> {
    PROCESS_MONITOR
        .close_processes(ids)
        .map_err(|e| e.to_string())
}

// 关闭结果
#[derive(Serialize)]
pub struct CloseResult {
    pub id: String,
    pub success: bool,
    pub error: Option<String>,
}
```

### 6.5 依赖库

**Cargo.toml 新增依赖**:

```toml
[dependencies]
# 现有依赖...

# 进程管理
sysinfo = "0.30"           # 跨平台进程信息获取
windows = { version = "0.52", features = [
    "Win32_Foundation",
    "Win32_System_Threading",
    "Win32_UI_WindowsAndMessaging",
] }                   # Windows API
lazy_static = "1.4"        # 全局静态变量
```


---

## 7. 前端实现细节

### 7.1 前端 API 封装

**src/api/process.ts**:

```typescript
import { invoke } from '@tauri-apps/api/tauri'
import type { ProcessInfo } from '../types/process'

// 获取所有正在运行的应用
export async function getRunningProcesses(): Promise<ProcessInfo[]> {
  try {
    return await invoke<ProcessInfo[]>('get_running_processes')
  } catch (error) {
    console.error('Failed to get running processes:', error)
    throw new Error('获取应用列表失败')
  }
}

// 关闭单个应用
export async function closeProcess(id: string): Promise<void> {
  try {
    await invoke('close_process', { id })
  } catch (error) {
    console.error('Failed to close process:', error)
    throw new Error('关闭应用失败')
  }
}

// 批量关闭应用
export async function closeProcesses(ids: string[]): Promise<CloseResult[]> {
  try {
    return await invoke<CloseResult[]>('close_processes', { ids })
  } catch (error) {
    console.error('Failed to close processes:', error)
    throw new Error('批量关闭应用失败')
  }
}

interface CloseResult {
  id: string
  success: boolean
  error?: string
}
```

### 7.2 核心组件实现要点

**ProcessManagement.vue** (主组件):
- 组件挂载时加载设置并初始刷新
- 如果开启自动刷新，启动定时器
- 组件卸载时停止自动刷新
- 监听页面可见性，控制自动刷新

**ProcessList.vue** (应用列表):
- 使用虚拟滚动（复用 useVirtualScroll）
- 实现选择交互（复选框、Ctrl+点击、Shift+点击）
- 实现关闭操作（单个/批量）
- 判断是否需要确认（根据设置和白名单）

**ProcessRow.vue** (单行应用):
- 显示应用信息（根据列配置）
- 格式化内存（MB）、CPU（百分比）、运行时长
- 截断过长的文本
- 支持选择和关闭操作

### 7.3 错误处理策略

**分层错误处理**:

1. **API层**: 捕获 Tauri invoke 错误，转换为用户友好的错误消息
2. **Store层**: 捕获 API 调用错误，更新错误状态，触发错误通知
3. **组件层**: 显示错误提示（Toast），提供重试机制

**常见错误场景**:
- 权限不足（无法关闭系统进程）: 提示"权限不足，请以管理员身份运行"
- 进程已不存在: 静默处理，自动刷新列表
- 批量操作部分失败: 显示成功和失败数量

### 7.4 性能优化

**前端优化**:
- 虚拟滚动: 复用现有的 useVirtualScroll
- 防抖刷新: 避免频繁刷新
- 懒加载: 分类筛选时只渲染可见项
- 内存优化: 及时清理定时器和事件监听

**用户体验优化**:
- 加载状态: 显示刷新进度
- 乐观更新: 关闭操作立即从列表移除，失败后恢复
- 平滑动画: 列表项添加/删除使用过渡动画
- 快捷键支持: Ctrl+A 全选，Delete 关闭选中

---

## 8. 实施计划

### 8.1 开发阶段

采用**方案C（混合架构）**，分4个阶段实施：
**第1周：架构搭建**
- 设计完整的组件架构和数据流
- 实现应用管理标签页框架
- 搭建Rust后端的进程监控基础设施
- 定义所有API接口
- 创建类型定义文件

**第2周：核心功能**
- 实现所有13+类别的应用枚举
- 基础信息展示（名称、类型、进程名）
- 手动刷新 + 单个关闭
- 分类筛选器
- 进程映射表（参考Python工具的完整列表）

**第3周：高级功能**
- 完整信息展示（内存、CPU等）
- 多选批量操作
- 自动刷新
- 自定义显示列

**第4周：完善优化**
- 可配置确认机制
- 白名单功能
- 性能优化
- 错误处理和边界情况
- 测试和文档

### 8.2 测试策略

**单元测试**:
- Store 逻辑测试（选择、筛选、排序）
- 工具函数测试（格式化、截断等）

**集成测试**:
- API 调用测试
- 组件交互测试
**手动测试场景**:
- 大量应用（100+）的性能测试
- 各种应用类型的识别准确性
- 关闭操作的成功率
- 边界情况（无应用、权限不足等）

---

## 9. 技术风险与应对

### 9.1 潜在风险

**风险1：进程识别准确性**
- **描述**: 某些应用可能无法正确识别分类
- **影响**: 用户体验下降
- **应对**: 
  - 参考Python工具的完整进程映射表（400+种应用）
  - 提供"其他"分类兜底
  - 支持用户反馈，持续更新映射表

**风险2：权限问题**
- **描述**: 某些系统进程需要管理员权限才能关闭
- **影响**: 关闭操作失败
- **应对**:
  - 友好的错误提示
  - 建议用户以管理员身份运行
  - 记录失败原因，便于调试

**风险3：性能影响**
- **描述**: 频繁枚举进程可能影响系统性能
- **影响**: 应用卡顿，系统资源占用高
- **应对**:
  - 合理的刷新间隔（默认5秒）
  - 页面不可见时停止监控
  - 使用高效的Windows API
  - 缓存进程映射表

**风险4：跨平台兼容性**
- **描述**: 当前设计仅支持Windows
- **影响**: macOS/Linux用户无法使用
- **应对**:
  - 第一版专注Windows实现
  - 预留跨平台扩展接口
  - 后续版本逐步支持其他平台

### 9.2 技术债务

- 进程映射表维护成本（需要持续更新）
- 跨平台支持的架构调整
- 性能监控数据的历史记录功能（未来扩展）

---

## 10. 成功标准

### 10.1 功能完整性

- ✅ 支持13+类别的应用监控
- ✅ 准确识别400+种常见应用
- ✅ 支持单个和批量关闭操作
- ✅ 提供手动和自动刷新
- ✅ 支持自定义显示列
- ✅ 实现可配置的确认机制

### 10.2 性能指标

- 枚举100个应用的时间 < 500ms
- 虚拟滚动帧率 ≥ 55fps
- 内存占用增量 < 50MB
- 自动刷新不影响主界面流畅度

### 10.3 用户体验

- 操作直观，无需学习成本
- 错误提示清晰友好
- 响应迅速，无明显卡顿
- 界面美观，与现有风格一致

---

## 11. 未来扩展

### 11.1 短期扩展（v0.2.0）

- 进程详情面板（查看更多信息）
- 进程搜索功能
- 导出应用列表
- 进程启动时间排序

### 11.2 中期扩展（v0.3.0）

- 历史数据记录和趋势图表
- 网络流量监控
- 磁盘IO监控
- 进程依赖关系图

### 11.3 长期扩展（v1.0.0）

- macOS 支持
- Linux 支持
- 进程自动化规则（定时关闭、条件触发）
- 云同步配置

---

## 12. 参考资料

- Python参考实现: `C:\AI Projects\快速管理已打开程序\快速清理工具.py`
- Windows API文档: https://learn.microsoft.com/en-us/windows/win32/api/
- sysinfo crate文档: https://docs.rs/sysinfo/
- Tauri命令文档: https://tauri.app/v1/guides/features/command

---

## 附录A：进程分类映射表示例

### 浏览器（30+种）
- chrome.exe → Google Chrome
- msedge.exe → Microsoft Edge
- firefox.exe → Mozilla Firefox
- opera.exe → Opera
- brave.exe → Brave
- vivaldi.exe → Vivaldi
- arc.exe → Arc
- 360chrome.exe → 360 极速浏览器
- sogouexplorer.exe → 搜狗浏览器
- qqbrowser.exe → QQ浏览器
- ... (完整列表参考Python工具)

### Office文档
- winword.exe → Microsoft Word
- excel.exe → Microsoft Excel
- powerpnt.exe → Microsoft PowerPoint
- wps.exe → WPS Office
- et.exe → WPS 表格
- wpp.exe → WPS 演示

### 终端（15+种）
- cmd.exe → 命令提示符 CMD
- powershell.exe → Windows PowerShell
- pwsh.exe → PowerShell Core
- windowsterminal.exe → Windows Terminal
- alacritty.exe → Alacritty
- ... (完整列表参考Python工具)

### 其他分类
- 压缩包工具（12+种）
- 文档编辑器（100+种）
- 媒体播放器（40+种）
- 图像工具（40+种）
- 通讯工具（40+种）
- 下载工具（40+种）
- 游戏（40+种）
- 系统工具（50+种）

**总计：400+ 种常见应用**

---

## 附录B：确认对话框设计

### 确认模式

**始终确认 (always)**:
```
┌─────────────────────┐
│  确认关闭应用                    │
├────────────────────────┤
│  即将关闭以下应用：           │
│  • Google Chrome             │
│               │
│  此操作无法撤销，确定继续吗？     │
├─────────┤
│       [取消]    [确定关闭]     │
└─────────────────────┘
```

**批量确认 (batch)**:
```
┌──────────────┐
│  确认批量关闭应用              │
├──────────────────┤
│  即将关闭 5 个应用：            │
│  • Google Chrome            │
│  • Microsoft Word               │
│  • PowerShell                 │
│  • ...               │
│                      │
│  此操作无法撤销，确定继续吗？     │
├─────────────────────────┤
│     [取消]    [确定关闭]     │
└────────────────────┘
```

**白名单提示**:
```
┌───────────┐
│  警告：关闭重要应用          │
├───────────────┤
│  以下应用在重要应用白名单中：     │
│  • Microsoft Word (未保存文档)   │
│                 │
│  关闭可能导致数据丢失！         │
│  确定要关闭吗？               │
├───────────────────────┤
│         [取消]  [仍然关闭]     │
└───────────────────┘
```

---

**文档结束**
