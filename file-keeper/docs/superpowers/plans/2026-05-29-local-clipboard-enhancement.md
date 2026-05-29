# Local Clipboard Enhancement Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 File Keeper 增加本地复制粘贴强化模块，包含剪贴板历史、安全拦截、主窗口管理页、快捷面板、Windows 剪贴板采集和本地缓存规则。

**Architecture:** 采用 Rust 剪贴板内核 + Vue 双入口界面。Rust 负责系统剪贴板读取/写入、敏感检测、SQLite 元数据、缓存和 Windows 自动粘贴；Vue/Pinia 负责主窗口管理页、快捷面板、设置、搜索、预览和操作反馈。

**Tech Stack:** Tauri 2、Rust 2021、Windows API、rusqlite、sha2、regex、url、image、Vue 3、TypeScript、Pinia、Vitest、Vue Test Utils。

---

## 范围切分

规格覆盖后端内核、存储、安全、前端页面、快捷面板、Windows 平台采集、图片/文件/富文本增强能力。实施时按可验证垂直切片推进：

1. 先建立共享类型、API、Store 和空页面，让前端可以编译和测试。
2. 再建立 Rust 纯逻辑：安全规则、保存规则、SQLite 存储。
3. 再接 Tauri 命令，支持手动插入和查询历史，形成不依赖系统剪贴板的基础闭环。
4. 再接 Windows 文本剪贴板监听和写回，形成真实复制/找回/粘贴闭环。
5. 再补文件、图片、HTML、URL、OCR、格式转换和缓存清理。
6. 最后做 UI 完整度、手动验证和文档。

---

## 文件结构规划

### 后端新增文件

- `file-keeper/src-tauri/src/clipboard/mod.rs`：剪贴板服务入口、状态对象、共享业务方法。
- `file-keeper/src-tauri/src/clipboard/types.rs`：Rust 剪贴板类型、设置、查询、存储用 DTO。
- `file-keeper/src-tauri/src/clipboard/security.rs`：敏感内容检测、Luhn、熵值、来源应用排除。
- `file-keeper/src-tauri/src/clipboard/storage.rs`：SQLite 初始化、迁移、增删查改、设置持久化。
- `file-keeper/src-tauri/src/clipboard/cache.rs`：缓存目录、空间统计、空间清理、文件副本策略。
- `file-keeper/src-tauri/src/clipboard/search.rs`：规范化搜索文本、URL 标准化、颜色识别辅助。
- `file-keeper/src-tauri/src/commands/clipboard.rs`：Tauri 命令入口。
- `file-keeper/src-tauri/src/platform/windows/clipboard.rs`：Windows 剪贴板读取、写入、监听。
- `file-keeper/src-tauri/src/platform/windows/foreground.rs`：前台窗口记录、恢复、模拟 Ctrl+V。
- `file-keeper/src-tauri/src/platform/macos/clipboard.rs`：macOS 接口骨架，仅用于保持平台边界清晰。

### 后端修改文件

- `file-keeper/src-tauri/Cargo.toml`：新增 SQLite、hash、正则、URL、图片处理依赖和 Windows API features。
- `file-keeper/src-tauri/src/main.rs`：注册剪贴板 state、启动监听、注册 Tauri 命令。
- `file-keeper/src-tauri/src/commands/mod.rs`：导出 clipboard 命令模块。
- `file-keeper/src-tauri/src/platform/windows/mod.rs`：导出 windows clipboard/foreground 模块。

### 前端新增文件

- `file-keeper/src/types/clipboard.ts`：前端剪贴板类型。
- `file-keeper/src/api/clipboard.ts`：Tauri invoke wrapper。
- `file-keeper/src/api/__tests__/clipboard.test.ts`：API wrapper 测试。
- `file-keeper/src/stores/clipboardStore.ts`：Pinia 剪贴板状态。
- `file-keeper/src/stores/__tests__/clipboardStore.test.ts`：Store 测试。
- `file-keeper/src/components/ClipboardManagement.vue`：剪贴板主页面容器。
- `file-keeper/src/components/ClipboardToolbar.vue`：搜索、筛选、批量操作。
- `file-keeper/src/components/ClipboardList.vue`：历史列表。
- `file-keeper/src/components/ClipboardItemRow.vue`：单条历史渲染。
- `file-keeper/src/components/ClipboardPreview.vue`：右侧预览与操作。
- `file-keeper/src/components/ClipboardQuickPanel.vue`：快捷面板。
- `file-keeper/src/components/ClipboardSettings.vue`：剪贴板设置。
- `file-keeper/src/components/ClipboardStorageUsage.vue`：缓存空间展示。
- `file-keeper/src/components/ClipboardSecurityEvents.vue`：安全事件列表。
- `file-keeper/src/components/__tests__/clipboardComponents.test.ts`：核心组件渲染和交互测试。
- `file-keeper/src/components/__tests__/clipboardQuickPanel.test.ts`：快捷面板键盘测试。

### 前端修改文件

- `file-keeper/src/App.vue`：新增剪贴板标签页和快捷面板挂载，避免写入剪贴板业务逻辑。
- `file-keeper/src/types/settings.ts`：新增剪贴板快捷键字段。
- `file-keeper/src/stores/settingsStore.ts`：新增默认剪贴板快捷键并持久化。
- `file-keeper/src/components/SettingsDialog.vue`：区分主窗口快捷键和剪贴板快捷键。
- `file-keeper/src/locales/zh-CN.ts`：新增 clipboard 文案。
- `file-keeper/src/locales/en.ts`：新增 clipboard 文案。
- `file-keeper/docs/manual-testing-guide.md`：新增剪贴板手动验证清单。

---

### Task 1: 前端剪贴板类型和 API wrapper

**Files:**
- Create: `file-keeper/src/types/clipboard.ts`
- Create: `file-keeper/src/api/clipboard.ts`
- Create: `file-keeper/src/api/__tests__/clipboard.test.ts`

- [ ] **Step 1: 写 API wrapper 的失败测试**

创建 `file-keeper/src/api/__tests__/clipboard.test.ts`：

```ts
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { invoke } from '@tauri-apps/api/core'
import {
  copyClipboardItem,
  deleteClipboardItem,
  getClipboardItemDetail,
  getClipboardItems,
  getClipboardSettings,
  getClipboardStorageUsage,
  pasteClipboardItem,
  searchClipboardItems,
  startClipboardMonitor,
  stopClipboardMonitor,
  updateClipboardSettings
} from '../clipboard'
import type { ClipboardSettings } from '../../types/clipboard'

vi.mock('@tauri-apps/api/core', () => ({
  invoke: vi.fn()
}))

const mockedInvoke = vi.mocked(invoke)

describe('clipboard api', () => {
  beforeEach(() => {
    mockedInvoke.mockReset()
  })

  it('loads clipboard items with query payload', async () => {
    mockedInvoke.mockResolvedValueOnce([{ id: 'item-1', kind: 'text', title: 'hello' }])

    const result = await getClipboardItems({ query: 'hel', kind: 'text', limit: 20, offset: 0 })

    expect(mockedInvoke).toHaveBeenCalledWith('get_clipboard_items', {
      query: { query: 'hel', kind: 'text', limit: 20, offset: 0 }
    })
    expect(result[0].id).toBe('item-1')
  })

  it('searches clipboard items', async () => {
    mockedInvoke.mockResolvedValueOnce([])

    await searchClipboardItems({ query: 'ocr', limit: 10, offset: 0 })

    expect(mockedInvoke).toHaveBeenCalledWith('search_clipboard_items', {
      query: { query: 'ocr', limit: 10, offset: 0 }
    })
  })

  it('loads clipboard item detail', async () => {
    mockedInvoke.mockResolvedValueOnce({ id: 'item-1', kind: 'text', title: 'hello', text: 'hello' })

    await getClipboardItemDetail('item-1')

    expect(mockedInvoke).toHaveBeenCalledWith('get_clipboard_item_detail', { id: 'item-1' })
  })

  it('copies and pastes items with format', async () => {
    mockedInvoke.mockResolvedValue(undefined)

    await copyClipboardItem('item-1', 'plain_text')
    await pasteClipboardItem('item-1', 'markdown')

    expect(mockedInvoke).toHaveBeenNthCalledWith(1, 'copy_clipboard_item', {
      id: 'item-1',
      format: 'plain_text'
    })
    expect(mockedInvoke).toHaveBeenNthCalledWith(2, 'paste_clipboard_item', {
      id: 'item-1',
      format: 'markdown'
    })
  })

  it('deletes clipboard items', async () => {
    mockedInvoke.mockResolvedValue(undefined)

    await deleteClipboardItem('item-1')

    expect(mockedInvoke).toHaveBeenCalledWith('delete_clipboard_item', { id: 'item-1' })
  })

  it('starts and stops monitor', async () => {
    mockedInvoke.mockResolvedValue(undefined)

    await startClipboardMonitor()
    await stopClipboardMonitor()

    expect(mockedInvoke).toHaveBeenNthCalledWith(1, 'start_clipboard_monitor')
    expect(mockedInvoke).toHaveBeenNthCalledWith(2, 'stop_clipboard_monitor')
  })

  it('loads and updates settings', async () => {
    const settings: ClipboardSettings = {
      monitorEnabled: true,
      quickPanelShortcut: 'CommandOrControl+Shift+V',
      autoPaste: false,
      protectSensitiveContent: true,
      enableOcr: true,
      enableLinkPreview: false,
      totalNonTextLimitMb: 2048,
      itemSizeLimitMb: 200,
      typeLimitsMb: { image: 1024, file: 2048, html: 500, linkPreview: 200 },
      fileExtensionMode: 'allow_all',
      fileExtensions: [],
      excludedApps: []
    }
    mockedInvoke.mockResolvedValueOnce(settings).mockResolvedValueOnce(settings)

    await getClipboardSettings()
    await updateClipboardSettings(settings)

    expect(mockedInvoke).toHaveBeenNthCalledWith(1, 'get_clipboard_settings')
    expect(mockedInvoke).toHaveBeenNthCalledWith(2, 'update_clipboard_settings', { settings })
  })

  it('loads storage usage', async () => {
    mockedInvoke.mockResolvedValueOnce({ totalBytes: 10, limitBytes: 20, byType: [] })

    const usage = await getClipboardStorageUsage()

    expect(mockedInvoke).toHaveBeenCalledWith('get_clipboard_storage_usage')
    expect(usage.totalBytes).toBe(10)
  })
})
```

- [ ] **Step 2: 运行测试确认失败**

Run:

```bash
npm run test -- src/api/__tests__/clipboard.test.ts
```

Expected: FAIL，错误包含 `Cannot find module '../clipboard'` 或缺少导出函数。

- [ ] **Step 3: 创建前端类型文件**

创建 `file-keeper/src/types/clipboard.ts`：

```ts
export type ClipboardKind =
  | 'text'
  | 'html'
  | 'image'
  | 'file'
  | 'url'
  | 'color'
  | 'mixed'
  | 'security_event'

export type ClipboardPasteFormat =
  | 'original'
  | 'plain_text'
  | 'html'
  | 'markdown'
  | 'image_png'
  | 'image_jpeg'
  | 'file_copy'

export type FileExtensionMode = 'allow_all' | 'allow_list' | 'block_list'

export interface ClipboardSourceApp {
  processName: string
  windowTitle: string
  pid?: number
}

export interface ClipboardItemSummary {
  id: string
  kind: ClipboardKind
  title: string
  summary: string
  sourceApp?: ClipboardSourceApp
  createdAt: number
  lastUsedAt?: number
  useCount: number
  isFavorite: boolean
  isPinned: boolean
  thumbnailPath?: string
  cacheBytes: number
  cacheState: 'none' | 'cached' | 'reference_only' | 'cleaned'
}

export interface ClipboardFileEntry {
  name: string
  originalPath: string
  cachedPath?: string
  sizeBytes: number
  modifiedAt?: number
  hash?: string
  isDirectory: boolean
  copyState: 'cached' | 'reference_only' | 'skipped'
}

export interface ClipboardItemDetail extends ClipboardItemSummary {
  text?: string
  html?: string
  sanitizedHtml?: string
  markdown?: string
  imagePath?: string
  imageWidth?: number
  imageHeight?: number
  imageFormat?: string
  ocrText?: string
  files?: ClipboardFileEntry[]
  url?: string
  urlTitle?: string
  urlDescription?: string
  urlThumbnailPath?: string
  colorHex?: string
  colorRgb?: string
  securityReason?: string
  availableFormats: ClipboardPasteFormat[]
}

export interface ClipboardQuery {
  query?: string
  kind?: ClipboardKind | 'all'
  favoriteOnly?: boolean
  sourceApp?: string
  limit: number
  offset: number
}

export interface ClipboardTypeLimitMb {
  image: number
  file: number
  html: number
  linkPreview: number
}

export interface ClipboardSettings {
  monitorEnabled: boolean
  quickPanelShortcut: string
  autoPaste: boolean
  protectSensitiveContent: boolean
  enableOcr: boolean
  enableLinkPreview: boolean
  totalNonTextLimitMb: number
  itemSizeLimitMb: number
  typeLimitsMb: ClipboardTypeLimitMb
  fileExtensionMode: FileExtensionMode
  fileExtensions: string[]
  excludedApps: string[]
}

export interface ClipboardStorageTypeUsage {
  kind: Exclude<ClipboardKind, 'security_event' | 'mixed'> | 'linkPreview'
  bytes: number
  limitBytes?: number
}

export interface ClipboardStorageUsage {
  totalBytes: number
  limitBytes: number
  byType: ClipboardStorageTypeUsage[]
}
```

- [ ] **Step 4: 创建 API wrapper**

创建 `file-keeper/src/api/clipboard.ts`：

```ts
import { invoke } from '@tauri-apps/api/core'
import type {
  ClipboardItemDetail,
  ClipboardItemSummary,
  ClipboardPasteFormat,
  ClipboardQuery,
  ClipboardSettings,
  ClipboardStorageUsage
} from '../types/clipboard'

export async function startClipboardMonitor(): Promise<void> {
  await invoke('start_clipboard_monitor')
}

export async function stopClipboardMonitor(): Promise<void> {
  await invoke('stop_clipboard_monitor')
}

export async function getClipboardItems(query: ClipboardQuery): Promise<ClipboardItemSummary[]> {
  return await invoke<ClipboardItemSummary[]>('get_clipboard_items', { query })
}

export async function searchClipboardItems(query: ClipboardQuery): Promise<ClipboardItemSummary[]> {
  return await invoke<ClipboardItemSummary[]>('search_clipboard_items', { query })
}

export async function getClipboardItemDetail(id: string): Promise<ClipboardItemDetail> {
  return await invoke<ClipboardItemDetail>('get_clipboard_item_detail', { id })
}

export async function copyClipboardItem(id: string, format: ClipboardPasteFormat): Promise<void> {
  await invoke('copy_clipboard_item', { id, format })
}

export async function pasteClipboardItem(id: string, format: ClipboardPasteFormat): Promise<void> {
  await invoke('paste_clipboard_item', { id, format })
}

export async function deleteClipboardItem(id: string): Promise<void> {
  await invoke('delete_clipboard_item', { id })
}

export async function clearClipboardHistory(scope: 'all' | 'non_text_cache' | 'security_events'): Promise<void> {
  await invoke('clear_clipboard_history', { scope })
}

export async function getClipboardSettings(): Promise<ClipboardSettings> {
  return await invoke<ClipboardSettings>('get_clipboard_settings')
}

export async function updateClipboardSettings(settings: ClipboardSettings): Promise<ClipboardSettings> {
  return await invoke<ClipboardSettings>('update_clipboard_settings', { settings })
}

export async function getClipboardStorageUsage(): Promise<ClipboardStorageUsage> {
  return await invoke<ClipboardStorageUsage>('get_clipboard_storage_usage')
}

export async function rebuildClipboardIndex(): Promise<void> {
  await invoke('rebuild_clipboard_index')
}

export async function retryLinkPreview(id: string): Promise<void> {
  await invoke('retry_link_preview', { id })
}
```

- [ ] **Step 5: 运行 API 测试确认通过**

Run:

```bash
npm run test -- src/api/__tests__/clipboard.test.ts
```

Expected: PASS。

- [ ] **Step 6: 提交 Task 1**

```bash
git add file-keeper/src/types/clipboard.ts file-keeper/src/api/clipboard.ts file-keeper/src/api/__tests__/clipboard.test.ts
git commit -m "feat(frontend): add clipboard api types"
```

---

### Task 2: Rust 剪贴板领域类型与安全规则

**Files:**
- Modify: `file-keeper/src-tauri/Cargo.toml`
- Create: `file-keeper/src-tauri/src/clipboard/mod.rs`
- Create: `file-keeper/src-tauri/src/clipboard/types.rs`
- Create: `file-keeper/src-tauri/src/clipboard/security.rs`
- Modify: `file-keeper/src-tauri/src/main.rs`

- [ ] **Step 1: 添加 Rust 依赖**

修改 `file-keeper/src-tauri/Cargo.toml` 的 `[dependencies]`：

```toml
regex = "1.10"
sha2 = "0.10"
url = "2.5"
rusqlite = { version = "0.31", features = ["bundled"] }
image = { version = "0.25", default-features = false, features = ["png", "jpeg", "webp"] }
```

在 `[target.'cfg(windows)'.dependencies].windows.features` 中追加：

```toml
"Win32_System_DataExchange",
"Win32_System_Memory",
"Win32_UI_Input_KeyboardAndMouse"
```

- [ ] **Step 2: 创建 Rust 模块入口**

创建 `file-keeper/src-tauri/src/clipboard/mod.rs`：

```rust
pub mod security;
pub mod types;

pub use types::*;
```

修改 `file-keeper/src-tauri/src/main.rs`，在现有 `mod commands;` 附近添加：

```rust
mod clipboard;
```

- [ ] **Step 3: 创建 Rust 共享类型**

创建 `file-keeper/src-tauri/src/clipboard/types.rs`：

```rust
use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum ClipboardKind {
    Text,
    Html,
    Image,
    File,
    Url,
    Color,
    Mixed,
    SecurityEvent,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum ClipboardPasteFormat {
    Original,
    PlainText,
    Html,
    Markdown,
    ImagePng,
    ImageJpeg,
    FileCopy,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum CacheState {
    None,
    Cached,
    ReferenceOnly,
    Cleaned,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum FileExtensionMode {
    AllowAll,
    AllowList,
    BlockList,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct ClipboardSourceApp {
    pub process_name: String,
    pub window_title: String,
    pub pid: Option<u32>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ClipboardItemSummary {
    pub id: String,
    pub kind: ClipboardKind,
    pub title: String,
    pub summary: String,
    pub source_app: Option<ClipboardSourceApp>,
    pub created_at: i64,
    pub last_used_at: Option<i64>,
    pub use_count: i64,
    pub is_favorite: bool,
    pub is_pinned: bool,
    pub thumbnail_path: Option<String>,
    pub cache_bytes: i64,
    pub cache_state: CacheState,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ClipboardFileEntry {
    pub name: String,
    pub original_path: String,
    pub cached_path: Option<String>,
    pub size_bytes: i64,
    pub modified_at: Option<i64>,
    pub hash: Option<String>,
    pub is_directory: bool,
    pub copy_state: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ClipboardItemDetail {
    #[serde(flatten)]
    pub summary: ClipboardItemSummary,
    pub text: Option<String>,
    pub html: Option<String>,
    pub sanitized_html: Option<String>,
    pub markdown: Option<String>,
    pub image_path: Option<String>,
    pub image_width: Option<i64>,
    pub image_height: Option<i64>,
    pub image_format: Option<String>,
    pub ocr_text: Option<String>,
    pub files: Option<Vec<ClipboardFileEntry>>,
    pub url: Option<String>,
    pub url_title: Option<String>,
    pub url_description: Option<String>,
    pub url_thumbnail_path: Option<String>,
    pub color_hex: Option<String>,
    pub color_rgb: Option<String>,
    pub security_reason: Option<String>,
    pub available_formats: Vec<ClipboardPasteFormat>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ClipboardQuery {
    pub query: Option<String>,
    pub kind: Option<String>,
    pub favorite_only: Option<bool>,
    pub source_app: Option<String>,
    pub limit: i64,
    pub offset: i64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ClipboardTypeLimitMb {
    pub image: i64,
    pub file: i64,
    pub html: i64,
    pub link_preview: i64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ClipboardSettings {
    pub monitor_enabled: bool,
    pub quick_panel_shortcut: String,
    pub auto_paste: bool,
    pub protect_sensitive_content: bool,
    pub enable_ocr: bool,
    pub enable_link_preview: bool,
    pub total_non_text_limit_mb: i64,
    pub item_size_limit_mb: i64,
    pub type_limits_mb: ClipboardTypeLimitMb,
    pub file_extension_mode: FileExtensionMode,
    pub file_extensions: Vec<String>,
    pub excluded_apps: Vec<String>,
}

impl Default for ClipboardSettings {
    fn default() -> Self {
        Self {
            monitor_enabled: true,
            quick_panel_shortcut: "CommandOrControl+Shift+V".to_string(),
            auto_paste: false,
            protect_sensitive_content: true,
            enable_ocr: true,
            enable_link_preview: false,
            total_non_text_limit_mb: 2048,
            item_size_limit_mb: 200,
            type_limits_mb: ClipboardTypeLimitMb {
                image: 1024,
                file: 2048,
                html: 500,
                link_preview: 200,
            },
            file_extension_mode: FileExtensionMode::AllowAll,
            file_extensions: Vec::new(),
            excluded_apps: Vec::new(),
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ClipboardStorageTypeUsage {
    pub kind: String,
    pub bytes: i64,
    pub limit_bytes: Option<i64>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ClipboardStorageUsage {
    pub total_bytes: i64,
    pub limit_bytes: i64,
    pub by_type: Vec<ClipboardStorageTypeUsage>,
}
```

- [ ] **Step 4: 写安全规则失败测试**

在 `file-keeper/src-tauri/src/clipboard/security.rs` 先写测试模块和函数签名调用：

```rust
use crate::clipboard::types::ClipboardSourceApp;

pub fn is_sensitive_content(_text: &str) -> Option<String> {
    None
}

pub fn is_sensitive_source(_source: &ClipboardSourceApp, _excluded_apps: &[String]) -> Option<String> {
    None
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn detects_luhn_valid_card_number() {
        let reason = is_sensitive_content("4111 1111 1111 1111");
        assert_eq!(reason.as_deref(), Some("credit_card"));
    }

    #[test]
    fn ignores_non_card_number() {
        let reason = is_sensitive_content("订单编号 4111-2026-0000");
        assert_eq!(reason, None);
    }

    #[test]
    fn detects_jwt_token() {
        let token = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0In0.signature";
        let reason = is_sensitive_content(token);
        assert_eq!(reason.as_deref(), Some("token"));
    }

    #[test]
    fn detects_private_key_marker() {
        let reason = is_sensitive_content("-----BEGIN OPENSSH PRIVATE KEY-----\nabc");
        assert_eq!(reason.as_deref(), Some("private_key"));
    }

    #[test]
    fn detects_high_entropy_secret() {
        let reason = is_sensitive_content("X7qP9mK2vB8nR4sT6wY1zA3cD5eF7gH9");
        assert_eq!(reason.as_deref(), Some("high_entropy"));
    }

    #[test]
    fn detects_password_manager_source() {
        let source = ClipboardSourceApp {
            process_name: "Bitwarden.exe".to_string(),
            window_title: "Bitwarden".to_string(),
            pid: Some(10),
        };
        let reason = is_sensitive_source(&source, &[]);
        assert_eq!(reason.as_deref(), Some("sensitive_app"));
    }

    #[test]
    fn detects_user_excluded_source() {
        let source = ClipboardSourceApp {
            process_name: "notes.exe".to_string(),
            window_title: "Private Notes".to_string(),
            pid: Some(10),
        };
        let reason = is_sensitive_source(&source, &["notes.exe".to_string()]);
        assert_eq!(reason.as_deref(), Some("excluded_app"));
    }
}
```

- [ ] **Step 5: 运行 Rust 测试确认失败**

Run:

```bash
cd file-keeper/src-tauri && cargo test clipboard::security
```

Expected: FAIL，至少 `detects_luhn_valid_card_number`、`detects_jwt_token`、`detects_private_key_marker`、`detects_high_entropy_secret` 失败。

- [ ] **Step 6: 实现安全规则**

替换 `file-keeper/src-tauri/src/clipboard/security.rs` 内容：

```rust
use regex::Regex;
use crate::clipboard::types::ClipboardSourceApp;

const SENSITIVE_APPS: &[&str] = &[
    "1password",
    "bitwarden",
    "keepass",
    "keepassxc",
    "enpass",
    "dashlane",
    "lastpass",
];

pub fn is_sensitive_content(text: &str) -> Option<String> {
    let trimmed = text.trim();
    if trimmed.is_empty() {
        return None;
    }

    if contains_private_key(trimmed) {
        return Some("private_key".to_string());
    }
    if contains_token(trimmed) {
        return Some("token".to_string());
    }
    if contains_credit_card(trimmed) {
        return Some("credit_card".to_string());
    }
    if looks_like_high_entropy_secret(trimmed) {
        return Some("high_entropy".to_string());
    }

    None
}

pub fn is_sensitive_source(source: &ClipboardSourceApp, excluded_apps: &[String]) -> Option<String> {
    let process = source.process_name.to_lowercase();
    let title = source.window_title.to_lowercase();

    if excluded_apps.iter().any(|app| process.contains(&app.to_lowercase())) {
        return Some("excluded_app".to_string());
    }

    if SENSITIVE_APPS.iter().any(|app| process.contains(app) || title.contains(app)) {
        return Some("sensitive_app".to_string());
    }

    if title.contains("password") || title.contains("密码") || title.contains("passkey") {
        return Some("sensitive_window".to_string());
    }

    None
}

fn contains_private_key(text: &str) -> bool {
    text.contains("-----BEGIN OPENSSH PRIVATE KEY-----")
        || text.contains("-----BEGIN RSA PRIVATE KEY-----")
        || text.contains("-----BEGIN PRIVATE KEY-----")
}

fn contains_token(text: &str) -> bool {
    let jwt = Regex::new(r"^[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$").unwrap();
    let api_key = Regex::new(r"(?i)(api[_-]?key|access[_-]?token|secret)[=:]\s*[A-Za-z0-9_\-]{16,}").unwrap();
    jwt.is_match(text) || api_key.is_match(text)
}

fn contains_credit_card(text: &str) -> bool {
    let digits: String = text.chars().filter(|c| c.is_ascii_digit()).collect();
    if digits.len() < 13 || digits.len() > 19 {
        return false;
    }
    luhn_valid(&digits)
}

fn luhn_valid(digits: &str) -> bool {
    let mut sum = 0;
    let mut double = false;

    for ch in digits.chars().rev() {
        let Some(mut digit) = ch.to_digit(10) else { return false };
        if double {
            digit *= 2;
            if digit > 9 {
                digit -= 9;
            }
        }
        sum += digit;
        double = !double;
    }

    sum % 10 == 0
}

fn looks_like_high_entropy_secret(text: &str) -> bool {
    if text.len() < 24 || text.len() > 256 || text.contains(' ') {
        return false;
    }

    let has_lower = text.chars().any(|c| c.is_ascii_lowercase());
    let has_upper = text.chars().any(|c| c.is_ascii_uppercase());
    let has_digit = text.chars().any(|c| c.is_ascii_digit());
    if !(has_lower && has_upper && has_digit) {
        return false;
    }

    shannon_entropy(text) >= 4.0
}

fn shannon_entropy(text: &str) -> f64 {
    let mut counts = std::collections::HashMap::new();
    for ch in text.chars() {
        *counts.entry(ch).or_insert(0usize) += 1;
    }

    let len = text.chars().count() as f64;
    counts.values().fold(0.0, |entropy, count| {
        let p = *count as f64 / len;
        entropy - p * p.log2()
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn detects_luhn_valid_card_number() {
        let reason = is_sensitive_content("4111 1111 1111 1111");
        assert_eq!(reason.as_deref(), Some("credit_card"));
    }

    #[test]
    fn ignores_non_card_number() {
        let reason = is_sensitive_content("订单编号 4111-2026-0000");
        assert_eq!(reason, None);
    }

    #[test]
    fn detects_jwt_token() {
        let token = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0In0.signature";
        let reason = is_sensitive_content(token);
        assert_eq!(reason.as_deref(), Some("token"));
    }

    #[test]
    fn detects_private_key_marker() {
        let reason = is_sensitive_content("-----BEGIN OPENSSH PRIVATE KEY-----\nabc");
        assert_eq!(reason.as_deref(), Some("private_key"));
    }

    #[test]
    fn detects_high_entropy_secret() {
        let reason = is_sensitive_content("X7qP9mK2vB8nR4sT6wY1zA3cD5eF7gH9");
        assert_eq!(reason.as_deref(), Some("high_entropy"));
    }

    #[test]
    fn detects_password_manager_source() {
        let source = ClipboardSourceApp {
            process_name: "Bitwarden.exe".to_string(),
            window_title: "Bitwarden".to_string(),
            pid: Some(10),
        };
        let reason = is_sensitive_source(&source, &[]);
        assert_eq!(reason.as_deref(), Some("sensitive_app"));
    }

    #[test]
    fn detects_user_excluded_source() {
        let source = ClipboardSourceApp {
            process_name: "notes.exe".to_string(),
            window_title: "Private Notes".to_string(),
            pid: Some(10),
        };
        let reason = is_sensitive_source(&source, &["notes.exe".to_string()]);
        assert_eq!(reason.as_deref(), Some("excluded_app"));
    }
}
```

- [ ] **Step 7: 运行安全规则测试确认通过**

Run:

```bash
cd file-keeper/src-tauri && cargo test clipboard::security
```

Expected: PASS。

- [ ] **Step 8: 提交 Task 2**

```bash
git add file-keeper/src-tauri/Cargo.toml file-keeper/src-tauri/Cargo.lock file-keeper/src-tauri/src/main.rs file-keeper/src-tauri/src/clipboard/mod.rs file-keeper/src-tauri/src/clipboard/types.rs file-keeper/src-tauri/src/clipboard/security.rs
git commit -m "feat(backend): add clipboard domain and security rules"
```

---

### Task 3: Rust 搜索、URL、颜色和缓存规则纯逻辑

**Files:**
- Create: `file-keeper/src-tauri/src/clipboard/search.rs`
- Create: `file-keeper/src-tauri/src/clipboard/cache.rs`
- Modify: `file-keeper/src-tauri/src/clipboard/mod.rs`

- [ ] **Step 1: 写 search/cache 失败测试**

创建 `file-keeper/src-tauri/src/clipboard/search.rs`：

```rust
pub fn normalize_search_text(input: &str) -> String {
    input.to_string()
}

pub fn normalize_url(input: &str) -> Option<String> {
    Some(input.to_string())
}

pub fn detect_color(input: &str) -> Option<(String, String)> {
    let _ = input;
    None
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn normalizes_text_for_search() {
        assert_eq!(normalize_search_text("  Hello\nWorld  "), "hello world");
    }

    #[test]
    fn normalizes_url_by_lowering_host_and_dropping_fragment() {
        assert_eq!(
            normalize_url("HTTPS://Example.COM/path?a=1#section").as_deref(),
            Some("https://example.com/path?a=1")
        );
    }

    #[test]
    fn detects_hex_color() {
        assert_eq!(detect_color("#22c55e"), Some(("#22C55E".to_string(), "rgb(34, 197, 94)".to_string())));
    }

    #[test]
    fn detects_rgb_color() {
        assert_eq!(detect_color("rgb(34, 197, 94)"), Some(("#22C55E".to_string(), "rgb(34, 197, 94)".to_string())));
    }
}
```

创建 `file-keeper/src-tauri/src/clipboard/cache.rs`：

```rust
use crate::clipboard::types::FileExtensionMode;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CacheCandidate {
    pub id: String,
    pub bytes: i64,
    pub last_used_at: Option<i64>,
    pub created_at: i64,
    pub is_favorite: bool,
    pub is_pinned: bool,
}

pub fn extension_allowed(_path: &str, _mode: &FileExtensionMode, _extensions: &[String]) -> bool {
    true
}

pub fn cleanup_candidates(mut candidates: Vec<CacheCandidate>, _bytes_to_free: i64) -> Vec<String> {
    candidates.sort_by_key(|candidate| candidate.created_at);
    candidates.into_iter().map(|candidate| candidate.id).collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn allows_all_extensions_when_unconfigured() {
        assert!(extension_allowed("report.exe", &FileExtensionMode::AllowAll, &[]));
    }

    #[test]
    fn allow_list_only_allows_configured_extension() {
        assert!(extension_allowed("report.pdf", &FileExtensionMode::AllowList, &["pdf".to_string()]));
        assert!(!extension_allowed("report.exe", &FileExtensionMode::AllowList, &["pdf".to_string()]));
    }

    #[test]
    fn block_list_blocks_configured_extension() {
        assert!(!extension_allowed("secret.key", &FileExtensionMode::BlockList, &["key".to_string()]));
        assert!(extension_allowed("report.docx", &FileExtensionMode::BlockList, &["key".to_string()]));
    }

    #[test]
    fn cleanup_prefers_unpinned_unfavorite_oldest_unused_items() {
        let candidates = vec![
            CacheCandidate { id: "pinned".to_string(), bytes: 10, last_used_at: None, created_at: 1, is_favorite: false, is_pinned: true },
            CacheCandidate { id: "favorite".to_string(), bytes: 10, last_used_at: None, created_at: 2, is_favorite: true, is_pinned: false },
            CacheCandidate { id: "new".to_string(), bytes: 10, last_used_at: Some(100), created_at: 3, is_favorite: false, is_pinned: false },
            CacheCandidate { id: "old".to_string(), bytes: 10, last_used_at: None, created_at: 0, is_favorite: false, is_pinned: false },
        ];

        assert_eq!(cleanup_candidates(candidates, 20), vec!["old".to_string(), "new".to_string()]);
    }
}
```

修改 `file-keeper/src-tauri/src/clipboard/mod.rs`：

```rust
pub mod cache;
pub mod search;
pub mod security;
pub mod types;

pub use types::*;
```

- [ ] **Step 2: 运行测试确认失败**

Run:

```bash
cd file-keeper/src-tauri && cargo test clipboard::search clipboard::cache
```

Expected: FAIL，颜色识别、URL 规范化和 cleanup 排序测试失败。

- [ ] **Step 3: 实现搜索、URL、颜色和缓存规则**

替换 `file-keeper/src-tauri/src/clipboard/search.rs`：

```rust
use regex::Regex;
use url::Url;

pub fn normalize_search_text(input: &str) -> String {
    input
        .split_whitespace()
        .collect::<Vec<_>>()
        .join(" ")
        .to_lowercase()
}

pub fn normalize_url(input: &str) -> Option<String> {
    let mut url = Url::parse(input.trim()).ok()?;
    url.set_fragment(None);
    Some(url.to_string())
}

pub fn detect_color(input: &str) -> Option<(String, String)> {
    let trimmed = input.trim();
    detect_hex_color(trimmed).or_else(|| detect_rgb_color(trimmed))
}

fn detect_hex_color(input: &str) -> Option<(String, String)> {
    let hex = input.strip_prefix('#')?;
    if hex.len() != 6 || !hex.chars().all(|c| c.is_ascii_hexdigit()) {
        return None;
    }

    let normalized = format!("#{}", hex.to_uppercase());
    let r = u8::from_str_radix(&hex[0..2], 16).ok()?;
    let g = u8::from_str_radix(&hex[2..4], 16).ok()?;
    let b = u8::from_str_radix(&hex[4..6], 16).ok()?;
    Some((normalized, format!("rgb({}, {}, {})", r, g, b)))
}

fn detect_rgb_color(input: &str) -> Option<(String, String)> {
    let re = Regex::new(r"^rgb\(\s*(\d{1,3})\s*,\s*(\d{1,3})\s*,\s*(\d{1,3})\s*\)$").unwrap();
    let captures = re.captures(input)?;
    let r = captures.get(1)?.as_str().parse::<u8>().ok()?;
    let g = captures.get(2)?.as_str().parse::<u8>().ok()?;
    let b = captures.get(3)?.as_str().parse::<u8>().ok()?;
    Some((format!("#{:02X}{:02X}{:02X}", r, g, b), format!("rgb({}, {}, {})", r, g, b)))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn normalizes_text_for_search() {
        assert_eq!(normalize_search_text("  Hello\nWorld  "), "hello world");
    }

    #[test]
    fn normalizes_url_by_lowering_host_and_dropping_fragment() {
        assert_eq!(
            normalize_url("HTTPS://Example.COM/path?a=1#section").as_deref(),
            Some("https://example.com/path?a=1")
        );
    }

    #[test]
    fn detects_hex_color() {
        assert_eq!(detect_color("#22c55e"), Some(("#22C55E".to_string(), "rgb(34, 197, 94)".to_string())));
    }

    #[test]
    fn detects_rgb_color() {
        assert_eq!(detect_color("rgb(34, 197, 94)"), Some(("#22C55E".to_string(), "rgb(34, 197, 94)".to_string())));
    }
}
```

替换 `file-keeper/src-tauri/src/clipboard/cache.rs`：

```rust
use std::path::Path;
use crate::clipboard::types::FileExtensionMode;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CacheCandidate {
    pub id: String,
    pub bytes: i64,
    pub last_used_at: Option<i64>,
    pub created_at: i64,
    pub is_favorite: bool,
    pub is_pinned: bool,
}

pub fn extension_allowed(path: &str, mode: &FileExtensionMode, extensions: &[String]) -> bool {
    if matches!(mode, FileExtensionMode::AllowAll) || extensions.is_empty() {
        return true;
    }

    let extension = Path::new(path)
        .extension()
        .and_then(|value| value.to_str())
        .unwrap_or("")
        .to_lowercase();

    let configured = extensions.iter().any(|item| item.trim_start_matches('.').eq_ignore_ascii_case(&extension));

    match mode {
        FileExtensionMode::AllowAll => true,
        FileExtensionMode::AllowList => configured,
        FileExtensionMode::BlockList => !configured,
    }
}

pub fn cleanup_candidates(mut candidates: Vec<CacheCandidate>, bytes_to_free: i64) -> Vec<String> {
    candidates.sort_by(|left, right| {
        left.is_pinned.cmp(&right.is_pinned)
            .then(left.is_favorite.cmp(&right.is_favorite))
            .then(left.last_used_at.unwrap_or(left.created_at).cmp(&right.last_used_at.unwrap_or(right.created_at)))
            .then(left.created_at.cmp(&right.created_at))
    });

    let mut freed = 0;
    let mut ids = Vec::new();
    for candidate in candidates {
        if candidate.is_pinned || candidate.is_favorite {
            continue;
        }
        ids.push(candidate.id);
        freed += candidate.bytes;
        if freed >= bytes_to_free {
            break;
        }
    }
    ids
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn allows_all_extensions_when_unconfigured() {
        assert!(extension_allowed("report.exe", &FileExtensionMode::AllowAll, &[]));
    }

    #[test]
    fn allow_list_only_allows_configured_extension() {
        assert!(extension_allowed("report.pdf", &FileExtensionMode::AllowList, &["pdf".to_string()]));
        assert!(!extension_allowed("report.exe", &FileExtensionMode::AllowList, &["pdf".to_string()]));
    }

    #[test]
    fn block_list_blocks_configured_extension() {
        assert!(!extension_allowed("secret.key", &FileExtensionMode::BlockList, &["key".to_string()]));
        assert!(extension_allowed("report.docx", &FileExtensionMode::BlockList, &["key".to_string()]));
    }

    #[test]
    fn cleanup_prefers_unpinned_unfavorite_oldest_unused_items() {
        let candidates = vec![
            CacheCandidate { id: "pinned".to_string(), bytes: 10, last_used_at: None, created_at: 1, is_favorite: false, is_pinned: true },
            CacheCandidate { id: "favorite".to_string(), bytes: 10, last_used_at: None, created_at: 2, is_favorite: true, is_pinned: false },
            CacheCandidate { id: "new".to_string(), bytes: 10, last_used_at: Some(100), created_at: 3, is_favorite: false, is_pinned: false },
            CacheCandidate { id: "old".to_string(), bytes: 10, last_used_at: None, created_at: 0, is_favorite: false, is_pinned: false },
        ];

        assert_eq!(cleanup_candidates(candidates, 20), vec!["old".to_string(), "new".to_string()]);
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run:

```bash
cd file-keeper/src-tauri && cargo test clipboard::search clipboard::cache
```

Expected: PASS。

- [ ] **Step 5: 提交 Task 3**

```bash
git add file-keeper/src-tauri/src/clipboard/mod.rs file-keeper/src-tauri/src/clipboard/search.rs file-keeper/src-tauri/src/clipboard/cache.rs
git commit -m "feat(backend): add clipboard search and cache rules"
```

---

### Task 4: SQLite 存储层和设置持久化

**Files:**
- Create: `file-keeper/src-tauri/src/clipboard/storage.rs`
- Modify: `file-keeper/src-tauri/src/clipboard/mod.rs`

- [ ] **Step 1: 写 SQLite 存储失败测试**

创建 `file-keeper/src-tauri/src/clipboard/storage.rs`：

```rust
use rusqlite::Connection;
use crate::clipboard::types::{ClipboardItemSummary, ClipboardQuery, ClipboardSettings};

pub struct ClipboardStorage {
    connection: Connection,
}

impl ClipboardStorage {
    pub fn in_memory() -> Result<Self, String> {
        Ok(Self { connection: Connection::open_in_memory().map_err(|err| err.to_string())? })
    }

    pub fn init(&self) -> Result<(), String> {
        Ok(())
    }

    pub fn save_settings(&self, _settings: &ClipboardSettings) -> Result<(), String> {
        Ok(())
    }

    pub fn load_settings(&self) -> Result<ClipboardSettings, String> {
        Ok(ClipboardSettings::default())
    }

    pub fn insert_text_item(&self, _text: &str, _title: &str, _source_process: Option<&str>) -> Result<String, String> {
        Ok("stub".to_string())
    }

    pub fn list_items(&self, _query: &ClipboardQuery) -> Result<Vec<ClipboardItemSummary>, String> {
        Ok(Vec::new())
    }

    pub fn delete_item(&self, _id: &str) -> Result<(), String> {
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn query() -> ClipboardQuery {
        ClipboardQuery {
            query: None,
            kind: None,
            favorite_only: None,
            source_app: None,
            limit: 20,
            offset: 0,
        }
    }

    #[test]
    fn persists_settings() {
        let storage = ClipboardStorage::in_memory().unwrap();
        storage.init().unwrap();
        let mut settings = ClipboardSettings::default();
        settings.auto_paste = true;
        settings.total_non_text_limit_mb = 4096;

        storage.save_settings(&settings).unwrap();
        let loaded = storage.load_settings().unwrap();

        assert!(loaded.auto_paste);
        assert_eq!(loaded.total_non_text_limit_mb, 4096);
    }

    #[test]
    fn inserts_and_lists_text_items_newest_first() {
        let storage = ClipboardStorage::in_memory().unwrap();
        storage.init().unwrap();

        let first_id = storage.insert_text_item("hello", "hello", Some("notepad.exe")).unwrap();
        let second_id = storage.insert_text_item("world", "world", Some("code.exe")).unwrap();
        let items = storage.list_items(&query()).unwrap();

        assert_eq!(items.len(), 2);
        assert_eq!(items[0].id, second_id);
        assert_eq!(items[1].id, first_id);
        assert_eq!(items[0].title, "world");
    }

    #[test]
    fn filters_by_search_text() {
        let storage = ClipboardStorage::in_memory().unwrap();
        storage.init().unwrap();

        storage.insert_text_item("hello alpha", "hello alpha", Some("notepad.exe")).unwrap();
        storage.insert_text_item("beta", "beta", Some("code.exe")).unwrap();
        let mut query = query();
        query.query = Some("alpha".to_string());

        let items = storage.list_items(&query).unwrap();

        assert_eq!(items.len(), 1);
        assert_eq!(items[0].title, "hello alpha");
    }

    #[test]
    fn deletes_items() {
        let storage = ClipboardStorage::in_memory().unwrap();
        storage.init().unwrap();
        let id = storage.insert_text_item("hello", "hello", None).unwrap();

        storage.delete_item(&id).unwrap();

        assert!(storage.list_items(&query()).unwrap().is_empty());
    }
}
```

修改 `file-keeper/src-tauri/src/clipboard/mod.rs`：

```rust
pub mod cache;
pub mod search;
pub mod security;
pub mod storage;
pub mod types;

pub use types::*;
```

- [ ] **Step 2: 运行测试确认失败**

Run:

```bash
cd file-keeper/src-tauri && cargo test clipboard::storage
```

Expected: FAIL，插入列表、搜索和删除测试失败。

- [ ] **Step 3: 实现 SQLite 存储层**

替换 `file-keeper/src-tauri/src/clipboard/storage.rs`：

```rust
use rusqlite::{params, Connection};
use uuid::Uuid;
use crate::clipboard::search::normalize_search_text;
use crate::clipboard::types::{CacheState, ClipboardItemSummary, ClipboardKind, ClipboardQuery, ClipboardSettings, ClipboardSourceApp};

pub struct ClipboardStorage {
    connection: Connection,
}

impl ClipboardStorage {
    pub fn in_memory() -> Result<Self, String> {
        Ok(Self { connection: Connection::open_in_memory().map_err(|err| err.to_string())? })
    }

    pub fn open(path: &std::path::Path) -> Result<Self, String> {
        Ok(Self { connection: Connection::open(path).map_err(|err| err.to_string())? })
    }

    pub fn init(&self) -> Result<(), String> {
        self.connection.execute_batch(
            "
            CREATE TABLE IF NOT EXISTS clipboard_items (
                id TEXT PRIMARY KEY,
                kind TEXT NOT NULL,
                title TEXT NOT NULL,
                summary TEXT NOT NULL,
                search_text TEXT NOT NULL,
                source_process TEXT,
                source_title TEXT,
                source_pid INTEGER,
                created_at INTEGER NOT NULL,
                last_used_at INTEGER,
                use_count INTEGER NOT NULL DEFAULT 0,
                is_favorite INTEGER NOT NULL DEFAULT 0,
                is_pinned INTEGER NOT NULL DEFAULT 0,
                thumbnail_path TEXT,
                cache_bytes INTEGER NOT NULL DEFAULT 0,
                cache_state TEXT NOT NULL DEFAULT 'none',
                text TEXT,
                security_reason TEXT
            );
            CREATE INDEX IF NOT EXISTS idx_clipboard_items_created_at ON clipboard_items(created_at DESC);
            CREATE INDEX IF NOT EXISTS idx_clipboard_items_kind ON clipboard_items(kind);
            CREATE TABLE IF NOT EXISTS clipboard_settings (
                key TEXT PRIMARY KEY,
                value TEXT NOT NULL
            );
            "
        ).map_err(|err| err.to_string())?;
        Ok(())
    }

    pub fn save_settings(&self, settings: &ClipboardSettings) -> Result<(), String> {
        let value = serde_json::to_string(settings).map_err(|err| err.to_string())?;
        self.connection.execute(
            "INSERT INTO clipboard_settings (key, value) VALUES ('settings', ?1)
             ON CONFLICT(key) DO UPDATE SET value = excluded.value",
            params![value],
        ).map_err(|err| err.to_string())?;
        Ok(())
    }

    pub fn load_settings(&self) -> Result<ClipboardSettings, String> {
        let result: Result<String, rusqlite::Error> = self.connection.query_row(
            "SELECT value FROM clipboard_settings WHERE key = 'settings'",
            [],
            |row| row.get(0),
        );

        match result {
            Ok(value) => serde_json::from_str(&value).map_err(|err| err.to_string()),
            Err(rusqlite::Error::QueryReturnedNoRows) => Ok(ClipboardSettings::default()),
            Err(err) => Err(err.to_string()),
        }
    }

    pub fn insert_text_item(&self, text: &str, title: &str, source_process: Option<&str>) -> Result<String, String> {
        let id = Uuid::new_v4().to_string();
        let now = current_millis();
        let summary = text.chars().take(160).collect::<String>();
        let search_text = normalize_search_text(text);
        self.connection.execute(
            "INSERT INTO clipboard_items (
                id, kind, title, summary, search_text, source_process, source_title, source_pid,
                created_at, use_count, is_favorite, is_pinned, cache_bytes, cache_state, text
            ) VALUES (?1, 'text', ?2, ?3, ?4, ?5, NULL, NULL, ?6, 0, 0, 0, 0, 'none', ?7)",
            params![id, title, summary, search_text, source_process, now, text],
        ).map_err(|err| err.to_string())?;
        Ok(id)
    }

    pub fn insert_security_event(&self, reason: &str, source_process: Option<&str>) -> Result<String, String> {
        let id = Uuid::new_v4().to_string();
        let now = current_millis();
        self.connection.execute(
            "INSERT INTO clipboard_items (
                id, kind, title, summary, search_text, source_process, source_title, source_pid,
                created_at, use_count, is_favorite, is_pinned, cache_bytes, cache_state, security_reason
            ) VALUES (?1, 'security_event', ?2, ?3, ?4, ?5, NULL, NULL, ?6, 0, 0, 0, 0, 'none', ?7)",
            params![id, format!("已拦截敏感内容：{}", reason), reason, reason, source_process, now, reason],
        ).map_err(|err| err.to_string())?;
        Ok(id)
    }

    pub fn list_items(&self, query: &ClipboardQuery) -> Result<Vec<ClipboardItemSummary>, String> {
        let search = query.query.as_ref().map(|value| format!("%{}%", normalize_search_text(value)));
        let kind = query.kind.as_ref().filter(|value| value.as_str() != "all");
        let favorite_only = query.favorite_only.unwrap_or(false);

        let mut statement = self.connection.prepare(
            "SELECT id, kind, title, summary, source_process, source_title, source_pid, created_at,
                    last_used_at, use_count, is_favorite, is_pinned, thumbnail_path, cache_bytes, cache_state
             FROM clipboard_items
             WHERE (?1 IS NULL OR search_text LIKE ?1)
               AND (?2 IS NULL OR kind = ?2)
               AND (?3 = 0 OR is_favorite = 1)
             ORDER BY created_at DESC
             LIMIT ?4 OFFSET ?5"
        ).map_err(|err| err.to_string())?;

        let rows = statement.query_map(
            params![search, kind, if favorite_only { 1 } else { 0 }, query.limit, query.offset],
            |row| {
                let process_name: Option<String> = row.get(4)?;
                let window_title: Option<String> = row.get(5)?;
                let pid: Option<u32> = row.get(6)?;
                Ok(ClipboardItemSummary {
                    id: row.get(0)?,
                    kind: parse_kind(row.get::<_, String>(1)?.as_str()),
                    title: row.get(2)?,
                    summary: row.get(3)?,
                    source_app: process_name.map(|process_name| ClipboardSourceApp {
                        process_name,
                        window_title: window_title.unwrap_or_default(),
                        pid,
                    }),
                    created_at: row.get(7)?,
                    last_used_at: row.get(8)?,
                    use_count: row.get(9)?,
                    is_favorite: row.get::<_, i64>(10)? == 1,
                    is_pinned: row.get::<_, i64>(11)? == 1,
                    thumbnail_path: row.get(12)?,
                    cache_bytes: row.get(13)?,
                    cache_state: parse_cache_state(row.get::<_, String>(14)?.as_str()),
                })
            }
        ).map_err(|err| err.to_string())?;

        rows.collect::<Result<Vec<_>, _>>().map_err(|err| err.to_string())
    }

    pub fn delete_item(&self, id: &str) -> Result<(), String> {
        self.connection.execute("DELETE FROM clipboard_items WHERE id = ?1", params![id])
            .map_err(|err| err.to_string())?;
        Ok(())
    }
}

fn parse_kind(value: &str) -> ClipboardKind {
    match value {
        "html" => ClipboardKind::Html,
        "image" => ClipboardKind::Image,
        "file" => ClipboardKind::File,
        "url" => ClipboardKind::Url,
        "color" => ClipboardKind::Color,
        "mixed" => ClipboardKind::Mixed,
        "security_event" => ClipboardKind::SecurityEvent,
        _ => ClipboardKind::Text,
    }
}

fn parse_cache_state(value: &str) -> CacheState {
    match value {
        "cached" => CacheState::Cached,
        "reference_only" => CacheState::ReferenceOnly,
        "cleaned" => CacheState::Cleaned,
        _ => CacheState::None,
    }
}

fn current_millis() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as i64
}

#[cfg(test)]
mod tests {
    use super::*;

    fn query() -> ClipboardQuery {
        ClipboardQuery {
            query: None,
            kind: None,
            favorite_only: None,
            source_app: None,
            limit: 20,
            offset: 0,
        }
    }

    #[test]
    fn persists_settings() {
        let storage = ClipboardStorage::in_memory().unwrap();
        storage.init().unwrap();
        let mut settings = ClipboardSettings::default();
        settings.auto_paste = true;
        settings.total_non_text_limit_mb = 4096;

        storage.save_settings(&settings).unwrap();
        let loaded = storage.load_settings().unwrap();

        assert!(loaded.auto_paste);
        assert_eq!(loaded.total_non_text_limit_mb, 4096);
    }

    #[test]
    fn inserts_and_lists_text_items_newest_first() {
        let storage = ClipboardStorage::in_memory().unwrap();
        storage.init().unwrap();

        let first_id = storage.insert_text_item("hello", "hello", Some("notepad.exe")).unwrap();
        std::thread::sleep(std::time::Duration::from_millis(2));
        let second_id = storage.insert_text_item("world", "world", Some("code.exe")).unwrap();
        let items = storage.list_items(&query()).unwrap();

        assert_eq!(items.len(), 2);
        assert_eq!(items[0].id, second_id);
        assert_eq!(items[1].id, first_id);
        assert_eq!(items[0].title, "world");
    }

    #[test]
    fn filters_by_search_text() {
        let storage = ClipboardStorage::in_memory().unwrap();
        storage.init().unwrap();

        storage.insert_text_item("hello alpha", "hello alpha", Some("notepad.exe")).unwrap();
        storage.insert_text_item("beta", "beta", Some("code.exe")).unwrap();
        let mut query = query();
        query.query = Some("alpha".to_string());

        let items = storage.list_items(&query).unwrap();

        assert_eq!(items.len(), 1);
        assert_eq!(items[0].title, "hello alpha");
    }

    #[test]
    fn deletes_items() {
        let storage = ClipboardStorage::in_memory().unwrap();
        storage.init().unwrap();
        let id = storage.insert_text_item("hello", "hello", None).unwrap();

        storage.delete_item(&id).unwrap();

        assert!(storage.list_items(&query()).unwrap().is_empty());
    }
}
```

- [ ] **Step 4: 运行存储测试确认通过**

Run:

```bash
cd file-keeper/src-tauri && cargo test clipboard::storage
```

Expected: PASS。

- [ ] **Step 5: 提交 Task 4**

```bash
git add file-keeper/src-tauri/src/clipboard/mod.rs file-keeper/src-tauri/src/clipboard/storage.rs
git commit -m "feat(backend): add clipboard sqlite storage"
```

---

### Task 5: 剪贴板服务和 Tauri 命令基础闭环

**Files:**
- Modify: `file-keeper/src-tauri/src/clipboard/mod.rs`
- Create: `file-keeper/src-tauri/src/commands/clipboard.rs`
- Modify: `file-keeper/src-tauri/src/commands/mod.rs`
- Modify: `file-keeper/src-tauri/src/main.rs`

- [ ] **Step 1: 扩展剪贴板服务对象**

替换 `file-keeper/src-tauri/src/clipboard/mod.rs`：

```rust
pub mod cache;
pub mod search;
pub mod security;
pub mod storage;
pub mod types;

use std::path::PathBuf;
use std::sync::Mutex;
use sha2::{Digest, Sha256};
use storage::ClipboardStorage;

pub use types::*;

pub struct ClipboardService {
    storage: Mutex<ClipboardStorage>,
    last_written_hash: Mutex<Option<String>>,
}

impl ClipboardService {
    pub fn new(database_path: PathBuf) -> Result<Self, String> {
        if let Some(parent) = database_path.parent() {
            std::fs::create_dir_all(parent).map_err(|err| err.to_string())?;
        }
        let storage = ClipboardStorage::open(&database_path)?;
        storage.init()?;
        Ok(Self {
            storage: Mutex::new(storage),
            last_written_hash: Mutex::new(None),
        })
    }

    pub fn in_memory() -> Result<Self, String> {
        let storage = ClipboardStorage::in_memory()?;
        storage.init()?;
        Ok(Self {
            storage: Mutex::new(storage),
            last_written_hash: Mutex::new(None),
        })
    }

    pub fn list_items(&self, query: &ClipboardQuery) -> Result<Vec<ClipboardItemSummary>, String> {
        self.storage.lock().map_err(|err| err.to_string())?.list_items(query)
    }

    pub fn add_text_for_testing(&self, text: &str, source_process: Option<&str>) -> Result<String, String> {
        if let Some(reason) = security::is_sensitive_content(text) {
            return self.storage.lock().map_err(|err| err.to_string())?.insert_security_event(&reason, source_process);
        }
        let title = text.chars().take(80).collect::<String>();
        self.storage.lock().map_err(|err| err.to_string())?.insert_text_item(text, &title, source_process)
    }

    pub fn delete_item(&self, id: &str) -> Result<(), String> {
        self.storage.lock().map_err(|err| err.to_string())?.delete_item(id)
    }

    pub fn load_settings(&self) -> Result<ClipboardSettings, String> {
        self.storage.lock().map_err(|err| err.to_string())?.load_settings()
    }

    pub fn save_settings(&self, settings: &ClipboardSettings) -> Result<ClipboardSettings, String> {
        self.storage.lock().map_err(|err| err.to_string())?.save_settings(settings)?;
        Ok(settings.clone())
    }

    pub fn mark_written_text(&self, text: &str) -> Result<(), String> {
        let hash = hash_text(text);
        *self.last_written_hash.lock().map_err(|err| err.to_string())? = Some(hash);
        Ok(())
    }

    pub fn should_ignore_text(&self, text: &str) -> Result<bool, String> {
        let hash = hash_text(text);
        let last = self.last_written_hash.lock().map_err(|err| err.to_string())?;
        Ok(last.as_ref() == Some(&hash))
    }
}

fn hash_text(text: &str) -> String {
    let mut hasher = Sha256::new();
    hasher.update(text.as_bytes());
    format!("{:x}", hasher.finalize())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn sensitive_text_becomes_security_event() {
        let service = ClipboardService::in_memory().unwrap();
        service.add_text_for_testing("4111 1111 1111 1111", Some("browser.exe")).unwrap();
        let items = service.list_items(&ClipboardQuery {
            query: None,
            kind: None,
            favorite_only: None,
            source_app: None,
            limit: 20,
            offset: 0,
        }).unwrap();

        assert_eq!(items[0].kind, ClipboardKind::SecurityEvent);
    }

    #[test]
    fn written_text_hash_can_be_ignored_once() {
        let service = ClipboardService::in_memory().unwrap();
        service.mark_written_text("hello").unwrap();

        assert!(service.should_ignore_text("hello").unwrap());
        assert!(!service.should_ignore_text("world").unwrap());
    }
}
```

- [ ] **Step 2: 运行服务测试确认通过**

Run:

```bash
cd file-keeper/src-tauri && cargo test clipboard
```

Expected: PASS。

- [ ] **Step 3: 创建 Tauri 命令文件**

创建 `file-keeper/src-tauri/src/commands/clipboard.rs`：

```rust
use tauri::{AppHandle, Manager, State};
use crate::clipboard::{ClipboardItemSummary, ClipboardQuery, ClipboardService, ClipboardSettings, ClipboardStorageTypeUsage, ClipboardStorageUsage};

#[tauri::command]
pub fn start_clipboard_monitor() -> Result<(), String> {
    Ok(())
}

#[tauri::command]
pub fn stop_clipboard_monitor() -> Result<(), String> {
    Ok(())
}

#[tauri::command]
pub fn get_clipboard_items(query: ClipboardQuery, service: State<'_, ClipboardService>) -> Result<Vec<ClipboardItemSummary>, String> {
    service.list_items(&query)
}

#[tauri::command]
pub fn search_clipboard_items(query: ClipboardQuery, service: State<'_, ClipboardService>) -> Result<Vec<ClipboardItemSummary>, String> {
    service.list_items(&query)
}

#[tauri::command]
pub fn add_clipboard_text_for_testing(text: String, source_process: Option<String>, service: State<'_, ClipboardService>) -> Result<String, String> {
    service.add_text_for_testing(&text, source_process.as_deref())
}

#[tauri::command]
pub fn delete_clipboard_item(id: String, service: State<'_, ClipboardService>) -> Result<(), String> {
    service.delete_item(&id)
}

#[tauri::command]
pub fn get_clipboard_settings(service: State<'_, ClipboardService>) -> Result<ClipboardSettings, String> {
    service.load_settings()
}

#[tauri::command]
pub fn update_clipboard_settings(settings: ClipboardSettings, service: State<'_, ClipboardService>) -> Result<ClipboardSettings, String> {
    service.save_settings(&settings)
}

#[tauri::command]
pub fn get_clipboard_storage_usage(service: State<'_, ClipboardService>) -> Result<ClipboardStorageUsage, String> {
    let settings = service.load_settings()?;
    Ok(ClipboardStorageUsage {
        total_bytes: 0,
        limit_bytes: settings.total_non_text_limit_mb * 1024 * 1024,
        by_type: vec![
            ClipboardStorageTypeUsage { kind: "image".to_string(), bytes: 0, limit_bytes: Some(settings.type_limits_mb.image * 1024 * 1024) },
            ClipboardStorageTypeUsage { kind: "file".to_string(), bytes: 0, limit_bytes: Some(settings.type_limits_mb.file * 1024 * 1024) },
            ClipboardStorageTypeUsage { kind: "html".to_string(), bytes: 0, limit_bytes: Some(settings.type_limits_mb.html * 1024 * 1024) },
            ClipboardStorageTypeUsage { kind: "linkPreview".to_string(), bytes: 0, limit_bytes: Some(settings.type_limits_mb.link_preview * 1024 * 1024) },
        ],
    })
}

#[tauri::command]
pub fn get_clipboard_item_detail(id: String, service: State<'_, ClipboardService>) -> Result<crate::clipboard::ClipboardItemDetail, String> {
    let query = ClipboardQuery { query: None, kind: None, favorite_only: None, source_app: None, limit: 1, offset: 0 };
    let item = service.list_items(&query)?.into_iter().find(|item| item.id == id).ok_or_else(|| "剪贴板记录不存在".to_string())?;
    Ok(crate::clipboard::ClipboardItemDetail {
        summary: item,
        text: None,
        html: None,
        sanitized_html: None,
        markdown: None,
        image_path: None,
        image_width: None,
        image_height: None,
        image_format: None,
        ocr_text: None,
        files: None,
        url: None,
        url_title: None,
        url_description: None,
        url_thumbnail_path: None,
        color_hex: None,
        color_rgb: None,
        security_reason: None,
        available_formats: vec![crate::clipboard::ClipboardPasteFormat::Original, crate::clipboard::ClipboardPasteFormat::PlainText],
    })
}

#[tauri::command]
pub fn copy_clipboard_item(_id: String, _format: crate::clipboard::ClipboardPasteFormat) -> Result<(), String> {
    Ok(())
}

#[tauri::command]
pub fn paste_clipboard_item(_id: String, _format: crate::clipboard::ClipboardPasteFormat) -> Result<(), String> {
    Ok(())
}

#[tauri::command]
pub fn clear_clipboard_history(_scope: String) -> Result<(), String> {
    Ok(())
}

#[tauri::command]
pub fn rebuild_clipboard_index() -> Result<(), String> {
    Ok(())
}

#[tauri::command]
pub fn retry_link_preview(_id: String) -> Result<(), String> {
    Ok(())
}

pub fn clipboard_database_path(app: &AppHandle) -> Result<std::path::PathBuf, String> {
    let app_data = app.path().app_data_dir().map_err(|err| err.to_string())?;
    Ok(app_data.join("clipboard-history.sqlite"))
}
```

- [ ] **Step 4: 注册命令模块和 state**

修改 `file-keeper/src-tauri/src/commands/mod.rs`：

```rust
pub mod files;
pub mod processes;
pub mod process_management;
pub mod icons;
pub mod clipboard;
```

修改 `file-keeper/src-tauri/src/main.rs` 的 imports，添加：

```rust
use commands::clipboard::{
    add_clipboard_text_for_testing,
    clear_clipboard_history,
    clipboard_database_path,
    copy_clipboard_item,
    delete_clipboard_item,
    get_clipboard_item_detail,
    get_clipboard_items,
    get_clipboard_settings,
    get_clipboard_storage_usage,
    paste_clipboard_item,
    rebuild_clipboard_index,
    retry_link_preview,
    search_clipboard_items,
    start_clipboard_monitor,
    stop_clipboard_monitor,
    update_clipboard_settings,
};
use clipboard::ClipboardService;
```

在 `tauri::Builder::default()` 后、`.plugin(...)` 前添加 state 初始化：

```rust
        .setup(|app| {
            let database_path = clipboard_database_path(&app.handle())?;
            let clipboard_service = ClipboardService::new(database_path)
                .map_err(|err| Box::<dyn std::error::Error>::from(err))?;
            app.manage(clipboard_service);
```

如果 `main.rs` 已经有 `.setup(|app| { ... })`，不要新增第二个 `.setup`。把上面的三行插入现有 setup 闭包开头。

在 `tauri::generate_handler![...]` 中追加：

```rust
            start_clipboard_monitor,
            stop_clipboard_monitor,
            get_clipboard_items,
            search_clipboard_items,
            get_clipboard_item_detail,
            add_clipboard_text_for_testing,
            copy_clipboard_item,
            paste_clipboard_item,
            delete_clipboard_item,
            clear_clipboard_history,
            get_clipboard_settings,
            update_clipboard_settings,
            get_clipboard_storage_usage,
            rebuild_clipboard_index,
            retry_link_preview
```

- [ ] **Step 5: 运行 Rust 检查**

Run:

```bash
cd file-keeper/src-tauri && cargo check
```

Expected: PASS。

- [ ] **Step 6: 提交 Task 5**

```bash
git add file-keeper/src-tauri/src/clipboard/mod.rs file-keeper/src-tauri/src/commands/clipboard.rs file-keeper/src-tauri/src/commands/mod.rs file-keeper/src-tauri/src/main.rs
git commit -m "feat(backend): expose clipboard tauri commands"
```

---

### Task 6: Pinia clipboardStore 基础状态

**Files:**
- Create: `file-keeper/src/stores/clipboardStore.ts`
- Create: `file-keeper/src/stores/__tests__/clipboardStore.test.ts`

- [ ] **Step 1: 写 Store 失败测试**

创建 `file-keeper/src/stores/__tests__/clipboardStore.test.ts`：

```ts
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useClipboardStore } from '../clipboardStore'
import * as clipboardApi from '../../api/clipboard'
import type { ClipboardItemSummary, ClipboardSettings } from '../../types/clipboard'

vi.mock('../../api/clipboard')

const mockedApi = vi.mocked(clipboardApi)

function item(id: string, title: string): ClipboardItemSummary {
  return {
    id,
    kind: 'text',
    title,
    summary: title,
    createdAt: Date.now(),
    useCount: 0,
    isFavorite: false,
    isPinned: false,
    cacheBytes: 0,
    cacheState: 'none'
  }
}

function settings(): ClipboardSettings {
  return {
    monitorEnabled: true,
    quickPanelShortcut: 'CommandOrControl+Shift+V',
    autoPaste: false,
    protectSensitiveContent: true,
    enableOcr: true,
    enableLinkPreview: false,
    totalNonTextLimitMb: 2048,
    itemSizeLimitMb: 200,
    typeLimitsMb: { image: 1024, file: 2048, html: 500, linkPreview: 200 },
    fileExtensionMode: 'allow_all',
    fileExtensions: [],
    excludedApps: []
  }
}

describe('clipboardStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.resetAllMocks()
  })

  it('loads items with default query', async () => {
    mockedApi.getClipboardItems.mockResolvedValueOnce([item('1', 'hello')])
    const store = useClipboardStore()

    await store.loadItems()

    expect(mockedApi.getClipboardItems).toHaveBeenCalledWith({
      query: '',
      kind: 'all',
      favoriteOnly: false,
      limit: 100,
      offset: 0
    })
    expect(store.items).toHaveLength(1)
  })

  it('searches when query is set', async () => {
    mockedApi.searchClipboardItems.mockResolvedValueOnce([item('2', 'world')])
    const store = useClipboardStore()
    store.searchQuery = 'world'

    await store.searchItems()

    expect(mockedApi.searchClipboardItems).toHaveBeenCalledWith({
      query: 'world',
      kind: 'all',
      favoriteOnly: false,
      limit: 100,
      offset: 0
    })
    expect(store.items[0].title).toBe('world')
  })

  it('copies and pastes selected item', async () => {
    mockedApi.copyClipboardItem.mockResolvedValueOnce()
    mockedApi.pasteClipboardItem.mockResolvedValueOnce()
    const store = useClipboardStore()

    await store.copyItem('1', 'plain_text')
    await store.pasteItem('1', 'original')

    expect(mockedApi.copyClipboardItem).toHaveBeenCalledWith('1', 'plain_text')
    expect(mockedApi.pasteClipboardItem).toHaveBeenCalledWith('1', 'original')
  })

  it('deletes item and removes it from state', async () => {
    mockedApi.deleteClipboardItem.mockResolvedValueOnce()
    const store = useClipboardStore()
    store.items = [item('1', 'hello')]
    store.selectedItemId = '1'

    await store.deleteItem('1')

    expect(store.items).toEqual([])
    expect(store.selectedItemId).toBeNull()
  })

  it('loads and updates settings', async () => {
    const current = settings()
    const updated = { ...current, autoPaste: true }
    mockedApi.getClipboardSettings.mockResolvedValueOnce(current)
    mockedApi.updateClipboardSettings.mockResolvedValueOnce(updated)
    const store = useClipboardStore()

    await store.loadSettings()
    await store.updateSettings({ autoPaste: true })

    expect(store.settings.autoPaste).toBe(true)
    expect(mockedApi.updateClipboardSettings).toHaveBeenCalledWith(updated)
  })
})
```

- [ ] **Step 2: 运行 Store 测试确认失败**

Run:

```bash
npm run test -- src/stores/__tests__/clipboardStore.test.ts
```

Expected: FAIL，错误包含 `Cannot find module '../clipboardStore'`。

- [ ] **Step 3: 实现 clipboardStore**

创建 `file-keeper/src/stores/clipboardStore.ts`：

```ts
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import {
  copyClipboardItem,
  deleteClipboardItem,
  getClipboardItemDetail,
  getClipboardItems,
  getClipboardSettings,
  getClipboardStorageUsage,
  pasteClipboardItem,
  searchClipboardItems,
  startClipboardMonitor,
  stopClipboardMonitor,
  updateClipboardSettings
} from '../api/clipboard'
import type {
  ClipboardItemDetail,
  ClipboardItemSummary,
  ClipboardKind,
  ClipboardPasteFormat,
  ClipboardSettings,
  ClipboardStorageUsage
} from '../types/clipboard'

const defaultSettings: ClipboardSettings = {
  monitorEnabled: true,
  quickPanelShortcut: 'CommandOrControl+Shift+V',
  autoPaste: false,
  protectSensitiveContent: true,
  enableOcr: true,
  enableLinkPreview: false,
  totalNonTextLimitMb: 2048,
  itemSizeLimitMb: 200,
  typeLimitsMb: { image: 1024, file: 2048, html: 500, linkPreview: 200 },
  fileExtensionMode: 'allow_all',
  fileExtensions: [],
  excludedApps: []
}

export const useClipboardStore = defineStore('clipboard', () => {
  const items = ref<ClipboardItemSummary[]>([])
  const selectedItemId = ref<string | null>(null)
  const selectedDetail = ref<ClipboardItemDetail | null>(null)
  const searchQuery = ref('')
  const kindFilter = ref<ClipboardKind | 'all'>('all')
  const favoriteOnly = ref(false)
  const isQuickPanelOpen = ref(false)
  const loading = ref(false)
  const error = ref<string | null>(null)
  const settings = ref<ClipboardSettings>({ ...defaultSettings })
  const storageUsage = ref<ClipboardStorageUsage | null>(null)

  const selectedItem = computed(() => items.value.find(item => item.id === selectedItemId.value) ?? null)

  function buildQuery() {
    return {
      query: searchQuery.value,
      kind: kindFilter.value,
      favoriteOnly: favoriteOnly.value,
      limit: 100,
      offset: 0
    }
  }

  async function runWithLoading<T>(action: () => Promise<T>): Promise<T> {
    loading.value = true
    error.value = null
    try {
      return await action()
    } catch (err) {
      error.value = err instanceof Error ? err.message : String(err)
      throw err
    } finally {
      loading.value = false
    }
  }

  async function startMonitor() {
    await startClipboardMonitor()
  }

  async function stopMonitor() {
    await stopClipboardMonitor()
  }

  async function loadItems() {
    await runWithLoading(async () => {
      items.value = await getClipboardItems(buildQuery())
      if (!selectedItemId.value && items.value.length > 0) {
        selectedItemId.value = items.value[0].id
      }
    })
  }

  async function searchItems() {
    await runWithLoading(async () => {
      items.value = await searchClipboardItems(buildQuery())
      selectedItemId.value = items.value[0]?.id ?? null
    })
  }

  async function loadDetail(id: string) {
    selectedDetail.value = await getClipboardItemDetail(id)
    selectedItemId.value = id
  }

  async function copyItem(id: string, format: ClipboardPasteFormat = 'original') {
    await copyClipboardItem(id, format)
  }

  async function pasteItem(id: string, format: ClipboardPasteFormat = 'original') {
    await pasteClipboardItem(id, format)
  }

  async function deleteItem(id: string) {
    await deleteClipboardItem(id)
    items.value = items.value.filter(item => item.id !== id)
    if (selectedItemId.value === id) {
      selectedItemId.value = items.value[0]?.id ?? null
      selectedDetail.value = null
    }
  }

  async function loadSettings() {
    settings.value = await getClipboardSettings()
  }

  async function updateSettings(updates: Partial<ClipboardSettings>) {
    const next = { ...settings.value, ...updates }
    settings.value = await updateClipboardSettings(next)
  }

  async function refreshStorageUsage() {
    storageUsage.value = await getClipboardStorageUsage()
  }

  function openQuickPanel() {
    isQuickPanelOpen.value = true
  }

  function closeQuickPanel() {
    isQuickPanelOpen.value = false
  }

  return {
    items,
    selectedItemId,
    selectedDetail,
    selectedItem,
    searchQuery,
    kindFilter,
    favoriteOnly,
    isQuickPanelOpen,
    loading,
    error,
    settings,
    storageUsage,
    startMonitor,
    stopMonitor,
    loadItems,
    searchItems,
    loadDetail,
    copyItem,
    pasteItem,
    deleteItem,
    loadSettings,
    updateSettings,
    refreshStorageUsage,
    openQuickPanel,
    closeQuickPanel
  }
})
```

- [ ] **Step 4: 运行 Store 测试确认通过**

Run:

```bash
npm run test -- src/stores/__tests__/clipboardStore.test.ts
```

Expected: PASS。

- [ ] **Step 5: 提交 Task 6**

```bash
git add file-keeper/src/stores/clipboardStore.ts file-keeper/src/stores/__tests__/clipboardStore.test.ts
git commit -m "feat(frontend): add clipboard store"
```

---

### Task 7: 剪贴板主页面基础 UI

**Files:**
- Create: `file-keeper/src/components/ClipboardManagement.vue`
- Create: `file-keeper/src/components/ClipboardToolbar.vue`
- Create: `file-keeper/src/components/ClipboardList.vue`
- Create: `file-keeper/src/components/ClipboardItemRow.vue`
- Create: `file-keeper/src/components/ClipboardPreview.vue`
- Create: `file-keeper/src/components/ClipboardStorageUsage.vue`
- Create: `file-keeper/src/components/ClipboardSecurityEvents.vue`
- Create: `file-keeper/src/components/__tests__/clipboardComponents.test.ts`

- [ ] **Step 1: 写组件失败测试**

创建 `file-keeper/src/components/__tests__/clipboardComponents.test.ts`：

```ts
import { describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createTestingPinia } from '@pinia/testing'
import ClipboardManagement from '../ClipboardManagement.vue'
import ClipboardItemRow from '../ClipboardItemRow.vue'
import ClipboardPreview from '../ClipboardPreview.vue'
import type { ClipboardItemSummary } from '../../types/clipboard'

function item(overrides: Partial<ClipboardItemSummary> = {}): ClipboardItemSummary {
  return {
    id: 'item-1',
    kind: 'text',
    title: 'hello',
    summary: 'hello summary',
    createdAt: Date.now(),
    useCount: 0,
    isFavorite: false,
    isPinned: false,
    cacheBytes: 0,
    cacheState: 'none',
    ...overrides
  }
}

describe('clipboard components', () => {
  it('renders management shell', () => {
    const wrapper = mount(ClipboardManagement, {
      global: { plugins: [createTestingPinia({ createSpy: vi.fn })] }
    })

    expect(wrapper.text()).toContain('剪贴板')
    expect(wrapper.text()).toContain('全部')
  })

  it('renders item row by kind and source', () => {
    const wrapper = mount(ClipboardItemRow, {
      props: {
        item: item({
          kind: 'image',
          title: '截图',
          sourceApp: { processName: 'SnippingTool.exe', windowTitle: '截图工具' }
        }),
        selected: true
      }
    })

    expect(wrapper.text()).toContain('截图')
    expect(wrapper.text()).toContain('图片')
    expect(wrapper.text()).toContain('SnippingTool.exe')
  })

  it('emits select from item row', async () => {
    const wrapper = mount(ClipboardItemRow, { props: { item: item(), selected: false } })

    await wrapper.trigger('click')

    expect(wrapper.emitted('select')?.[0]).toEqual(['item-1'])
  })

  it('renders preview empty state', () => {
    const wrapper = mount(ClipboardPreview, { props: { item: null, detail: null } })

    expect(wrapper.text()).toContain('选择一条历史记录')
  })
})
```

如果项目没有 `@pinia/testing`，先安装 dev dependency：

```bash
npm install -D @pinia/testing
```

- [ ] **Step 2: 运行组件测试确认失败**

Run:

```bash
npm run test -- src/components/__tests__/clipboardComponents.test.ts
```

Expected: FAIL，错误包含组件文件不存在。

- [ ] **Step 3: 创建 ClipboardItemRow**

创建 `file-keeper/src/components/ClipboardItemRow.vue`：

```vue
<template>
  <button
    type="button"
    :class="[
      'w-full text-left rounded-lg border px-3 py-2 transition-colors',
      selected
        ? 'border-primary bg-primary/10 text-gray-900 dark:text-gray-100'
        : 'border-gray-200 dark:border-dark-border bg-white dark:bg-dark-panel hover:bg-gray-50 dark:hover:bg-dark-hover'
    ]"
    @click="$emit('select', item.id)"
  >
    <div class="flex items-start justify-between gap-3">
      <div class="min-w-0 flex-1">
        <div class="flex items-center gap-2">
          <span class="text-xs font-medium text-primary">{{ kindLabel }}</span>
          <span v-if="item.cacheState === 'cached'" class="text-[11px] text-green-600">已缓存</span>
          <span v-if="item.cacheState === 'reference_only'" class="text-[11px] text-amber-600">仅引用</span>
        </div>
        <div class="mt-1 truncate text-sm font-medium">{{ item.title }}</div>
        <div class="mt-1 line-clamp-2 text-xs text-gray-500 dark:text-gray-400">{{ item.summary }}</div>
      </div>
      <img v-if="item.thumbnailPath" :src="item.thumbnailPath" class="h-12 w-12 rounded object-cover" alt="" />
    </div>
    <div class="mt-2 flex items-center justify-between text-[11px] text-gray-400">
      <span>{{ item.sourceApp?.processName || '未知来源' }}</span>
      <span>{{ timeLabel }}</span>
    </div>
  </button>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { ClipboardItemSummary } from '../types/clipboard'

const props = defineProps<{
  item: ClipboardItemSummary
  selected: boolean
}>()

defineEmits<{
  select: [id: string]
}>()

const labels: Record<string, string> = {
  text: '文本',
  html: '富文本',
  image: '图片',
  file: '文件',
  url: '链接',
  color: '颜色',
  mixed: '混合',
  security_event: '安全'
}

const kindLabel = computed(() => labels[props.item.kind] ?? '未知')
const timeLabel = computed(() => new Date(props.item.createdAt).toLocaleString())
</script>
```

- [ ] **Step 4: 创建 ClipboardPreview**

创建 `file-keeper/src/components/ClipboardPreview.vue`：

```vue
<template>
  <aside class="flex h-full min-h-0 flex-col border-l border-gray-200 bg-white p-4 dark:border-dark-border dark:bg-dark-panel">
    <div v-if="!item" class="flex flex-1 items-center justify-center text-sm text-gray-400">
      选择一条历史记录查看预览
    </div>

    <template v-else>
      <div class="mb-4">
        <div class="text-xs text-gray-500">{{ kindLabel }}</div>
        <h3 class="mt-1 text-base font-semibold text-gray-900 dark:text-gray-100">{{ item.title }}</h3>
        <p class="mt-1 text-xs text-gray-500">{{ item.sourceApp?.processName || '未知来源' }}</p>
      </div>

      <div class="min-h-0 flex-1 overflow-auto rounded-lg bg-gray-50 p-3 text-sm dark:bg-dark-hover">
        <img v-if="detail?.imagePath" :src="detail.imagePath" class="max-w-full rounded" alt="剪贴板图片" />
        <pre v-else class="whitespace-pre-wrap break-words font-sans">{{ previewText }}</pre>
      </div>

      <div class="mt-4 flex flex-wrap gap-2">
        <button class="rounded bg-primary px-3 py-1.5 text-sm text-white" @click="$emit('copy', item.id, 'original')">复制</button>
        <button class="rounded bg-gray-100 px-3 py-1.5 text-sm dark:bg-dark-hover" @click="$emit('paste', item.id, 'original')">粘贴</button>
        <button class="rounded bg-gray-100 px-3 py-1.5 text-sm dark:bg-dark-hover" @click="$emit('copy', item.id, 'plain_text')">纯文本</button>
        <button class="rounded bg-red-50 px-3 py-1.5 text-sm text-red-600 dark:bg-red-900/20" @click="$emit('delete', item.id)">删除</button>
      </div>
    </template>
  </aside>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { ClipboardItemDetail, ClipboardItemSummary, ClipboardPasteFormat } from '../types/clipboard'

const props = defineProps<{
  item: ClipboardItemSummary | null
  detail: ClipboardItemDetail | null
}>()

defineEmits<{
  copy: [id: string, format: ClipboardPasteFormat]
  paste: [id: string, format: ClipboardPasteFormat]
  delete: [id: string]
}>()

const labels: Record<string, string> = {
  text: '文本',
  html: '富文本',
  image: '图片',
  file: '文件',
  url: '链接',
  color: '颜色',
  mixed: '混合',
  security_event: '安全事件'
}

const kindLabel = computed(() => props.item ? labels[props.item.kind] : '')
const previewText = computed(() => props.detail?.text || props.detail?.markdown || props.item?.summary || '')
</script>
```

- [ ] **Step 5: 创建 Toolbar/List/Usage/Security 子组件**

创建 `file-keeper/src/components/ClipboardToolbar.vue`：

```vue
<template>
  <div class="flex items-center gap-3 border-b border-gray-200 bg-white p-3 dark:border-dark-border dark:bg-dark-bg">
    <input
      :value="searchQuery"
      class="w-80 rounded-md border border-gray-200 bg-gray-50 px-3 py-2 text-sm outline-none focus:border-primary dark:border-dark-border dark:bg-dark-hover"
      placeholder="搜索内容、来源应用、OCR 文本..."
      @input="$emit('update:searchQuery', ($event.target as HTMLInputElement).value)"
      @keydown.enter="$emit('search')"
    />
    <select
      :value="kind"
      class="rounded-md border border-gray-200 bg-gray-50 px-3 py-2 text-sm dark:border-dark-border dark:bg-dark-hover"
      @change="$emit('update:kind', ($event.target as HTMLSelectElement).value)"
    >
      <option value="all">全部</option>
      <option value="text">文本</option>
      <option value="html">富文本</option>
      <option value="image">图片</option>
      <option value="file">文件</option>
      <option value="url">链接</option>
      <option value="color">颜色</option>
      <option value="security_event">安全事件</option>
    </select>
    <button class="rounded bg-primary px-3 py-2 text-sm text-white" @click="$emit('search')">搜索</button>
  </div>
</template>

<script setup lang="ts">
defineProps<{
  searchQuery: string
  kind: string
}>()

defineEmits<{
  'update:searchQuery': [value: string]
  'update:kind': [value: string]
  search: []
}>()
</script>
```

创建 `file-keeper/src/components/ClipboardList.vue`：

```vue
<template>
  <div class="min-h-0 flex-1 overflow-auto p-3">
    <div v-if="items.length === 0" class="flex h-full items-center justify-center text-sm text-gray-400">
      暂无剪贴板历史
    </div>
    <div v-else class="space-y-2">
      <ClipboardItemRow
        v-for="item in items"
        :key="item.id"
        :item="item"
        :selected="item.id === selectedItemId"
        @select="$emit('select', $event)"
      />
    </div>
  </div>
</template>

<script setup lang="ts">
import ClipboardItemRow from './ClipboardItemRow.vue'
import type { ClipboardItemSummary } from '../types/clipboard'

defineProps<{
  items: ClipboardItemSummary[]
  selectedItemId: string | null
}>()

defineEmits<{
  select: [id: string]
}>()
</script>
```

创建 `file-keeper/src/components/ClipboardStorageUsage.vue`：

```vue
<template>
  <div class="rounded-lg border border-gray-200 bg-white p-3 text-xs dark:border-dark-border dark:bg-dark-panel">
    <div class="flex items-center justify-between">
      <span class="font-medium">缓存空间</span>
      <span>{{ usedLabel }} / {{ limitLabel }}</span>
    </div>
    <div class="mt-2 h-2 rounded bg-gray-100 dark:bg-dark-hover">
      <div class="h-2 rounded bg-primary" :style="{ width: `${percent}%` }"></div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { ClipboardStorageUsage } from '../types/clipboard'

const props = defineProps<{
  usage: ClipboardStorageUsage | null
}>()

const usedLabel = computed(() => formatBytes(props.usage?.totalBytes ?? 0))
const limitLabel = computed(() => formatBytes(props.usage?.limitBytes ?? 0))
const percent = computed(() => {
  if (!props.usage || props.usage.limitBytes <= 0) return 0
  return Math.min(100, Math.round((props.usage.totalBytes / props.usage.limitBytes) * 100))
})

function formatBytes(bytes: number): string {
  if (bytes <= 0) return '0 MB'
  return `${Math.round(bytes / 1024 / 1024)} MB`
}
</script>
```

创建 `file-keeper/src/components/ClipboardSecurityEvents.vue`：

```vue
<template>
  <div class="rounded-lg border border-gray-200 bg-white p-3 text-xs dark:border-dark-border dark:bg-dark-panel">
    <div class="font-medium">安全防护</div>
    <p class="mt-1 text-gray-500">敏感内容会被拦截，不保存原文。</p>
  </div>
</template>
```

- [ ] **Step 6: 创建 ClipboardManagement 容器**

创建 `file-keeper/src/components/ClipboardManagement.vue`：

```vue
<template>
  <section class="flex min-h-0 flex-1 flex-col overflow-hidden bg-gray-50 dark:bg-dark-bg">
    <ClipboardToolbar
      v-model:search-query="clipboardStore.searchQuery"
      v-model:kind="clipboardStore.kindFilter"
      @search="clipboardStore.searchItems"
    />

    <div class="grid min-h-0 flex-1 grid-cols-[180px_minmax(320px,1fr)_360px] overflow-hidden">
      <aside class="space-y-3 border-r border-gray-200 p-3 dark:border-dark-border">
        <h2 class="text-base font-semibold">剪贴板</h2>
        <nav class="space-y-1 text-sm">
          <button v-for="filter in filters" :key="filter.kind" class="block w-full rounded px-2 py-1.5 text-left hover:bg-gray-100 dark:hover:bg-dark-hover" @click="setKind(filter.kind)">
            {{ filter.label }}
          </button>
        </nav>
        <ClipboardStorageUsage :usage="clipboardStore.storageUsage" />
        <ClipboardSecurityEvents />
      </aside>

      <ClipboardList
        :items="clipboardStore.items"
        :selected-item-id="clipboardStore.selectedItemId"
        @select="selectItem"
      />

      <ClipboardPreview
        :item="clipboardStore.selectedItem"
        :detail="clipboardStore.selectedDetail"
        @copy="clipboardStore.copyItem"
        @paste="clipboardStore.pasteItem"
        @delete="clipboardStore.deleteItem"
      />
    </div>
  </section>
</template>

<script setup lang="ts">
import { onMounted } from 'vue'
import { useClipboardStore } from '../stores/clipboardStore'
import ClipboardList from './ClipboardList.vue'
import ClipboardPreview from './ClipboardPreview.vue'
import ClipboardSecurityEvents from './ClipboardSecurityEvents.vue'
import ClipboardStorageUsage from './ClipboardStorageUsage.vue'
import ClipboardToolbar from './ClipboardToolbar.vue'
import type { ClipboardKind } from '../types/clipboard'

const clipboardStore = useClipboardStore()

const filters: Array<{ kind: ClipboardKind | 'all'; label: string }> = [
  { kind: 'all', label: '全部' },
  { kind: 'text', label: '文本' },
  { kind: 'html', label: '富文本' },
  { kind: 'image', label: '图片' },
  { kind: 'file', label: '文件' },
  { kind: 'url', label: '链接' },
  { kind: 'color', label: '颜色' },
  { kind: 'security_event', label: '安全事件' }
]

onMounted(async () => {
  await Promise.all([
    clipboardStore.loadItems(),
    clipboardStore.loadSettings(),
    clipboardStore.refreshStorageUsage()
  ])
})

function setKind(kind: ClipboardKind | 'all') {
  clipboardStore.kindFilter = kind
  clipboardStore.searchItems()
}

function selectItem(id: string) {
  clipboardStore.loadDetail(id)
}
</script>
```

- [ ] **Step 7: 运行组件测试确认通过**

Run:

```bash
npm run test -- src/components/__tests__/clipboardComponents.test.ts
```

Expected: PASS。

- [ ] **Step 8: 提交 Task 7**

```bash
git add file-keeper/src/components/ClipboardManagement.vue file-keeper/src/components/ClipboardToolbar.vue file-keeper/src/components/ClipboardList.vue file-keeper/src/components/ClipboardItemRow.vue file-keeper/src/components/ClipboardPreview.vue file-keeper/src/components/ClipboardStorageUsage.vue file-keeper/src/components/ClipboardSecurityEvents.vue file-keeper/src/components/__tests__/clipboardComponents.test.ts package.json package-lock.json
git commit -m "feat(frontend): add clipboard management view"
```

---

### Task 8: 快捷面板和键盘操作

**Files:**
- Create: `file-keeper/src/components/ClipboardQuickPanel.vue`
- Create: `file-keeper/src/components/__tests__/clipboardQuickPanel.test.ts`

- [ ] **Step 1: 写快捷面板失败测试**

创建 `file-keeper/src/components/__tests__/clipboardQuickPanel.test.ts`：

```ts
import { describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createTestingPinia } from '@pinia/testing'
import ClipboardQuickPanel from '../ClipboardQuickPanel.vue'
import { useClipboardStore } from '../../stores/clipboardStore'

describe('ClipboardQuickPanel', () => {
  it('does not render when closed', () => {
    const wrapper = mount(ClipboardQuickPanel, {
      global: { plugins: [createTestingPinia({ createSpy: vi.fn })] }
    })

    expect(wrapper.text()).toBe('')
  })

  it('renders search panel when open', async () => {
    const wrapper = mount(ClipboardQuickPanel, {
      global: { plugins: [createTestingPinia({ createSpy: vi.fn, stubActions: false })] }
    })
    const store = useClipboardStore()
    store.isQuickPanelOpen = true
    store.items = [{
      id: '1',
      kind: 'text',
      title: 'hello',
      summary: 'hello',
      createdAt: Date.now(),
      useCount: 0,
      isFavorite: false,
      isPinned: false,
      cacheBytes: 0,
      cacheState: 'none'
    }]
    await wrapper.vm.$nextTick()

    expect(wrapper.text()).toContain('hello')
    expect(wrapper.find('input').exists()).toBe(true)
  })

  it('uses Enter to paste selected item and Escape to close', async () => {
    const wrapper = mount(ClipboardQuickPanel, {
      global: { plugins: [createTestingPinia({ createSpy: vi.fn, stubActions: false })] }
    })
    const store = useClipboardStore()
    store.isQuickPanelOpen = true
    store.items = [{
      id: '1',
      kind: 'text',
      title: 'hello',
      summary: 'hello',
      createdAt: Date.now(),
      useCount: 0,
      isFavorite: false,
      isPinned: false,
      cacheBytes: 0,
      cacheState: 'none'
    }]
    const pasteSpy = vi.spyOn(store, 'pasteItem').mockResolvedValue()
    await wrapper.vm.$nextTick()

    await wrapper.find('input').trigger('keydown.enter')
    await wrapper.find('input').trigger('keydown.escape')

    expect(pasteSpy).toHaveBeenCalledWith('1', 'original')
    expect(store.isQuickPanelOpen).toBe(false)
  })

  it('uses Shift+Enter for plain text', async () => {
    const wrapper = mount(ClipboardQuickPanel, {
      global: { plugins: [createTestingPinia({ createSpy: vi.fn, stubActions: false })] }
    })
    const store = useClipboardStore()
    store.isQuickPanelOpen = true
    store.items = [{
      id: '1',
      kind: 'text',
      title: 'hello',
      summary: 'hello',
      createdAt: Date.now(),
      useCount: 0,
      isFavorite: false,
      isPinned: false,
      cacheBytes: 0,
      cacheState: 'none'
    }]
    const pasteSpy = vi.spyOn(store, 'pasteItem').mockResolvedValue()
    await wrapper.vm.$nextTick()

    await wrapper.find('input').trigger('keydown.enter', { shiftKey: true })

    expect(pasteSpy).toHaveBeenCalledWith('1', 'plain_text')
  })
})
```

- [ ] **Step 2: 运行测试确认失败**

Run:

```bash
npm run test -- src/components/__tests__/clipboardQuickPanel.test.ts
```

Expected: FAIL，组件不存在。

- [ ] **Step 3: 实现快捷面板组件**

创建 `file-keeper/src/components/ClipboardQuickPanel.vue`：

```vue
<template>
  <transition name="fade">
    <div v-if="clipboardStore.isQuickPanelOpen" class="fixed inset-0 z-[70] flex items-start justify-center bg-black/20 pt-24" @click="clipboardStore.closeQuickPanel">
      <div class="w-[460px] rounded-2xl border border-gray-200 bg-white p-3 shadow-2xl dark:border-dark-border dark:bg-dark-panel" @click.stop>
        <input
          ref="inputRef"
          v-model="clipboardStore.searchQuery"
          class="w-full rounded-lg border border-gray-200 bg-gray-50 px-3 py-2 text-sm outline-none focus:border-primary dark:border-dark-border dark:bg-dark-hover"
          placeholder="搜索剪贴板历史..."
          @input="clipboardStore.searchItems"
          @keydown.down.prevent="moveSelection(1)"
          @keydown.up.prevent="moveSelection(-1)"
          @keydown.enter.prevent="confirmSelection($event.shiftKey)"
          @keydown.escape.prevent="clipboardStore.closeQuickPanel"
        />

        <div class="mt-3 max-h-80 overflow-auto space-y-2">
          <button
            v-for="(item, index) in clipboardStore.items"
            :key="item.id"
            :class="[
              'w-full rounded-lg px-3 py-2 text-left text-sm',
              index === selectedIndex ? 'bg-primary/10 text-primary' : 'hover:bg-gray-50 dark:hover:bg-dark-hover'
            ]"
            @mouseenter="selectedIndex = index"
            @click="confirmSelection(false)"
          >
            <div class="font-medium">{{ item.title }}</div>
            <div class="truncate text-xs text-gray-500">{{ item.summary }}</div>
          </button>
        </div>

        <div class="mt-3 flex justify-between border-t border-gray-100 pt-2 text-xs text-gray-400 dark:border-dark-border">
          <span>Enter 粘贴 · Shift+Enter 纯文本 · Esc 关闭</span>
          <button @click="clipboardStore.closeQuickPanel">关闭</button>
        </div>
      </div>
    </div>
  </transition>
</template>

<script setup lang="ts">
import { nextTick, ref, watch } from 'vue'
import { useClipboardStore } from '../stores/clipboardStore'

const clipboardStore = useClipboardStore()
const selectedIndex = ref(0)
const inputRef = ref<HTMLInputElement | null>(null)

watch(() => clipboardStore.isQuickPanelOpen, async (open) => {
  if (open) {
    selectedIndex.value = 0
    await clipboardStore.loadItems()
    await nextTick()
    inputRef.value?.focus()
  }
})

function moveSelection(delta: number) {
  if (clipboardStore.items.length === 0) return
  selectedIndex.value = (selectedIndex.value + delta + clipboardStore.items.length) % clipboardStore.items.length
}

async function confirmSelection(plainText: boolean) {
  const item = clipboardStore.items[selectedIndex.value]
  if (!item) return
  await clipboardStore.pasteItem(item.id, plainText ? 'plain_text' : 'original')
  clipboardStore.closeQuickPanel()
}
</script>

<style scoped>
.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.15s ease;
}
.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}
</style>
```

- [ ] **Step 4: 运行快捷面板测试确认通过**

Run:

```bash
npm run test -- src/components/__tests__/clipboardQuickPanel.test.ts
```

Expected: PASS。

- [ ] **Step 5: 提交 Task 8**

```bash
git add file-keeper/src/components/ClipboardQuickPanel.vue file-keeper/src/components/__tests__/clipboardQuickPanel.test.ts
git commit -m "feat(frontend): add clipboard quick panel"
```

---

### Task 9: App.vue 集成剪贴板标签和快捷面板

**Files:**
- Modify: `file-keeper/src/types/settings.ts`
- Modify: `file-keeper/src/stores/settingsStore.ts`
- Modify: `file-keeper/src/components/SettingsDialog.vue`
- Modify: `file-keeper/src/App.vue`
- Modify: `file-keeper/src/locales/zh-CN.ts`
- Modify: `file-keeper/src/locales/en.ts`

- [ ] **Step 1: 修改 Settings 类型**

修改 `file-keeper/src/types/settings.ts`：

```ts
export interface Settings {
  theme: 'light' | 'dark' | 'auto'
  language: 'zh-CN' | 'en-US'
  globalShortcut: string
  clipboardShortcut: string
  autoStart: boolean
  minimizeToTray: boolean
  defaultView: 'grid' | 'list'
  itemsPerPage: number
  iconMode: 'real' | 'generic'
}
```

- [ ] **Step 2: 修改 settingsStore 默认值和持久化 action**

在 `file-keeper/src/stores/settingsStore.ts` 默认 settings 中加入：

```ts
clipboardShortcut: 'CommandOrControl+Shift+V',
```

确保 `importantActions` 仍包含 `updateSettings`，不需要新增 action。

- [ ] **Step 3: 修改 SettingsDialog 支持两个快捷键**

修改 `file-keeper/src/components/SettingsDialog.vue` 的 emit 类型：

```ts
const emit = defineEmits<{
  close: []
  save: [settings: { globalShortcut: string; clipboardShortcut: string; minimizeToTray: boolean; theme: 'light' | 'dark' | 'auto' }]
}>()
```

新增本地状态：

```ts
const localClipboardShortcut = ref(settingsStore.settings.clipboardShortcut)
```

在 watch 打开弹窗时加入：

```ts
localClipboardShortcut.value = settingsStore.settings.clipboardShortcut
```

把现有 `handleShortcutKeydown` 改成可复用：

```ts
function formatShortcut(event: KeyboardEvent): string | null {
  event.preventDefault()
  const keys: string[] = []
  if (event.ctrlKey || event.metaKey) keys.push(event.ctrlKey ? 'Ctrl' : 'Cmd')
  if (event.altKey) keys.push('Alt')
  if (event.shiftKey) keys.push('Shift')
  if (event.key && !['Control', 'Alt', 'Shift', 'Meta'].includes(event.key)) {
    keys.push(event.key.toUpperCase())
  }
  if (keys.length < 2) return null
  return keys.map(k => k === 'Ctrl' || k === 'Cmd' ? 'CommandOrControl' : k).join('+')
}

function handleMainShortcutKeydown(event: KeyboardEvent) {
  const shortcut = formatShortcut(event)
  if (shortcut) localShortcut.value = shortcut
}

function handleClipboardShortcutKeydown(event: KeyboardEvent) {
  const shortcut = formatShortcut(event)
  if (shortcut) localClipboardShortcut.value = shortcut
}
```

在模板的“全局快捷键”下方新增“剪贴板面板快捷键”输入框：

```vue
<div>
  <label class="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-2">
    剪贴板面板快捷键
  </label>
  <div class="flex items-center space-x-2">
    <input
      v-model="localClipboardShortcut"
      @keydown="handleClipboardShortcutKeydown"
      placeholder="按下快捷键组合..."
      class="flex-1 px-3 py-2 bg-gray-100 dark:bg-dark-hover border border-gray-300 dark:border-dark-border rounded-md outline-none focus:border-primary text-sm"
      readonly
    />
    <button
      @click="localClipboardShortcut = ''"
      class="px-3 py-2 text-sm text-gray-600 dark:text-gray-300 hover:bg-gray-100 dark:hover:bg-dark-hover rounded-md transition-colors"
    >
      清除
    </button>
  </div>
  <p class="text-xs text-gray-500 mt-1">
    建议使用 Ctrl+Shift+V 呼出剪贴板历史
  </p>
</div>
```

把原输入框 `@keydown="handleShortcutKeydown"` 改为：

```vue
@keydown="handleMainShortcutKeydown"
```

修改 `handleSave()`：

```ts
function handleSave() {
  emit('save', {
    globalShortcut: localShortcut.value,
    clipboardShortcut: localClipboardShortcut.value,
    minimizeToTray: localMinimizeToTray.value,
    theme: localTheme.value
  })
}
```

- [ ] **Step 4: 修改 App.vue 标签和快捷键注册**

在 `file-keeper/src/App.vue` imports 中加入：

```ts
import ClipboardManagement from './components/ClipboardManagement.vue'
import ClipboardQuickPanel from './components/ClipboardQuickPanel.vue'
import { useClipboardStore } from './stores/clipboardStore'
```

创建 store：

```ts
const clipboardStore = useClipboardStore()
```

把 `currentTab` 类型扩展为：

```ts
const currentTab = ref<'files' | 'processes' | 'clipboard'>('files')
```

在标签栏新增按钮：

```vue
<button
  @click="currentTab = 'clipboard'"
  :class="['py-3 px-4 text-sm font-medium relative transition-colors flex items-center space-x-2',
           currentTab === 'clipboard' ? 'text-primary' : 'text-gray-500 hover:text-gray-800 dark:text-gray-400 dark:hover:text-gray-200']"
>
  <Clipboard :size="16" />
  <span>{{ t('tabs.clipboard') }}</span>
  <div v-if="currentTab === 'clipboard'" class="absolute bottom-0 left-0 w-full h-0.5 bg-primary rounded-t-full"></div>
</button>
```

从 lucide import 增加 `Clipboard` 图标。

在主内容区进程管理旁边新增：

```vue
<ClipboardManagement v-if="currentTab === 'clipboard'" />
<ClipboardQuickPanel />
```

修改设置保存函数签名：

```ts
async function handleSaveSettings(settings: { globalShortcut: string; clipboardShortcut: string; minimizeToTray: boolean; theme: 'light' | 'dark' | 'auto' }) {
```

在 `settingsStore.updateSettings` 中加入：

```ts
clipboardShortcut: settings.clipboardShortcut,
```

新增剪贴板快捷键注册变量：

```ts
let registeredClipboardShortcut: string | null = null
```

新增 handler：

```ts
async function handleClipboardShortcut() {
  clipboardStore.openQuickPanel()
}
```

在 onMounted 注册主快捷键后注册剪贴板快捷键：

```ts
const clipboardShortcut = settingsStore.settings.clipboardShortcut
if (clipboardShortcut) {
  try {
    await registerGlobalShortcut(clipboardShortcut, handleClipboardShortcut)
    registeredClipboardShortcut = clipboardShortcut
  } catch (error) {
    console.error('Failed to register clipboard shortcut on startup:', error)
  }
}
```

在 onUnmounted 取消注册：

```ts
if (registeredClipboardShortcut) {
  try {
    await unregisterGlobalShortcut(registeredClipboardShortcut)
  } catch (error) {
    console.warn('Failed to unregister clipboard shortcut on unmount:', error)
  }
  registeredClipboardShortcut = null
}
```

设置保存时如果剪贴板快捷键变化，按现有 `registeredShortcut` 模式注册/反注册。不要复用主窗口快捷键变量。

- [ ] **Step 5: 修改 i18n 文案**

在 `file-keeper/src/locales/zh-CN.ts` 的 `tabs` 中加入：

```ts
clipboard: '剪贴板'
```

新增 `clipboard` namespace：

```ts
clipboard: {
  title: '剪贴板',
  searchPlaceholder: '搜索内容、来源应用、OCR 文本...',
  all: '全部',
  text: '文本',
  html: '富文本',
  image: '图片',
  file: '文件',
  url: '链接',
  color: '颜色',
  securityEvent: '安全事件',
  empty: '暂无剪贴板历史',
  copy: '复制',
  paste: '粘贴',
  plainText: '纯文本',
  delete: '删除'
}
```

在 `file-keeper/src/locales/en.ts` 的 `tabs` 中加入：

```ts
clipboard: 'Clipboard'
```

新增 `clipboard` namespace：

```ts
clipboard: {
  title: 'Clipboard',
  searchPlaceholder: 'Search content, source apps, or OCR text...',
  all: 'All',
  text: 'Text',
  html: 'Rich Text',
  image: 'Image',
  file: 'File',
  url: 'Link',
  color: 'Color',
  securityEvent: 'Security Event',
  empty: 'No clipboard history yet',
  copy: 'Copy',
  paste: 'Paste',
  plainText: 'Plain Text',
  delete: 'Delete'
}
```

- [ ] **Step 6: 运行前端测试和类型检查**

Run:

```bash
npm run test -- src/components/__tests__/clipboardComponents.test.ts src/components/__tests__/clipboardQuickPanel.test.ts src/stores/__tests__/clipboardStore.test.ts src/api/__tests__/clipboard.test.ts
npm run build
```

Expected: PASS。

- [ ] **Step 7: 提交 Task 9**

```bash
git add file-keeper/src/App.vue file-keeper/src/types/settings.ts file-keeper/src/stores/settingsStore.ts file-keeper/src/components/SettingsDialog.vue file-keeper/src/locales/zh-CN.ts file-keeper/src/locales/en.ts
git commit -m "feat(frontend): integrate clipboard navigation and shortcuts"
```

---

### Task 10: Windows 文本剪贴板读取、写入和复制命令

**Files:**
- Create: `file-keeper/src-tauri/src/platform/windows/clipboard.rs`
- Modify: `file-keeper/src-tauri/src/platform/windows/mod.rs`
- Modify: `file-keeper/src-tauri/src/commands/clipboard.rs`
- Modify: `file-keeper/src-tauri/src/clipboard/storage.rs`

- [ ] **Step 1: 导出 Windows clipboard 模块**

修改 `file-keeper/src-tauri/src/platform/windows/mod.rs`：

```rust
pub mod clipboard;
pub mod process_mappings;
pub mod process_monitor;
```

- [ ] **Step 2: 创建 Windows 文本剪贴板读写**

创建 `file-keeper/src-tauri/src/platform/windows/clipboard.rs`：

```rust
use windows::core::HSTRING;
use windows::Win32::Foundation::HWND;
use windows::Win32::System::DataExchange::{CloseClipboard, EmptyClipboard, GetClipboardData, IsClipboardFormatAvailable, OpenClipboard, SetClipboardData, CF_UNICODETEXT};
use windows::Win32::System::Memory::{GlobalAlloc, GlobalLock, GlobalUnlock, GMEM_MOVEABLE};

pub fn read_text() -> Result<Option<String>, String> {
    unsafe {
        if !IsClipboardFormatAvailable(CF_UNICODETEXT).as_bool() {
            return Ok(None);
        }
        OpenClipboard(HWND::default()).map_err(|err| err.to_string())?;
        let result = read_text_inner();
        let _ = CloseClipboard();
        result
    }
}

pub fn write_text(text: &str) -> Result<(), String> {
    unsafe {
        OpenClipboard(HWND::default()).map_err(|err| err.to_string())?;
        let result = write_text_inner(text);
        let _ = CloseClipboard();
        result
    }
}

unsafe fn read_text_inner() -> Result<Option<String>, String> {
    let handle = GetClipboardData(CF_UNICODETEXT).map_err(|err| err.to_string())?;
    if handle.is_invalid() {
        return Ok(None);
    }
    let ptr = GlobalLock(handle);
    if ptr.is_null() {
        return Ok(None);
    }
    let wide_ptr = ptr as *const u16;
    let mut len = 0usize;
    while *wide_ptr.add(len) != 0 {
        len += 1;
    }
    let slice = std::slice::from_raw_parts(wide_ptr, len);
    let text = String::from_utf16_lossy(slice);
    let _ = GlobalUnlock(handle);
    Ok(Some(text))
}

unsafe fn write_text_inner(text: &str) -> Result<(), String> {
    EmptyClipboard().map_err(|err| err.to_string())?;
    let mut wide: Vec<u16> = HSTRING::from(text).as_wide().to_vec();
    wide.push(0);
    let bytes = wide.len() * std::mem::size_of::<u16>();
    let handle = GlobalAlloc(GMEM_MOVEABLE, bytes).map_err(|err| err.to_string())?;
    let ptr = GlobalLock(handle);
    if ptr.is_null() {
        return Err("无法锁定剪贴板内存".to_string());
    }
    std::ptr::copy_nonoverlapping(wide.as_ptr() as *const u8, ptr as *mut u8, bytes);
    let _ = GlobalUnlock(handle);
    SetClipboardData(CF_UNICODETEXT, handle).map_err(|err| err.to_string())?;
    Ok(())
}
```

- [ ] **Step 3: 给 storage 增加文本详情读取**

在 `ClipboardStorage` impl 中新增：

```rust
pub fn get_text(&self, id: &str) -> Result<Option<String>, String> {
    let result: Result<Option<String>, rusqlite::Error> = self.connection.query_row(
        "SELECT text FROM clipboard_items WHERE id = ?1",
        params![id],
        |row| row.get(0),
    );
    match result {
        Ok(text) => Ok(text),
        Err(rusqlite::Error::QueryReturnedNoRows) => Ok(None),
        Err(err) => Err(err.to_string()),
    }
}
```

在 `ClipboardService` 增加：

```rust
pub fn get_text(&self, id: &str) -> Result<Option<String>, String> {
    self.storage.lock().map_err(|err| err.to_string())?.get_text(id)
}
```

- [ ] **Step 4: 实现 copy_clipboard_item 写回文本**

修改 `file-keeper/src-tauri/src/commands/clipboard.rs` 的 `copy_clipboard_item`：

```rust
#[tauri::command]
pub fn copy_clipboard_item(id: String, _format: crate::clipboard::ClipboardPasteFormat, service: State<'_, ClipboardService>) -> Result<(), String> {
    let text = service.get_text(&id)?.ok_or_else(|| "该记录没有可复制的文本内容".to_string())?;
    #[cfg(target_os = "windows")]
    {
        crate::platform::windows::clipboard::write_text(&text)?;
        service.mark_written_text(&text)?;
        Ok(())
    }
    #[cfg(not(target_os = "windows"))]
    {
        let _ = text;
        Err("当前平台尚未实现剪贴板写入".to_string())
    }
}
```

修改 `paste_clipboard_item` 暂时复用 copy：

```rust
#[tauri::command]
pub fn paste_clipboard_item(id: String, format: crate::clipboard::ClipboardPasteFormat, service: State<'_, ClipboardService>) -> Result<(), String> {
    copy_clipboard_item(id, format, service)
}
```

- [ ] **Step 5: 运行 Rust 检查**

Run:

```bash
cd file-keeper/src-tauri && cargo check
```

Expected: PASS。

- [ ] **Step 6: 提交 Task 10**

```bash
git add file-keeper/src-tauri/src/platform/windows/mod.rs file-keeper/src-tauri/src/platform/windows/clipboard.rs file-keeper/src-tauri/src/commands/clipboard.rs file-keeper/src-tauri/src/clipboard/mod.rs file-keeper/src-tauri/src/clipboard/storage.rs
git commit -m "feat(backend): add windows text clipboard IO"
```

---

### Task 11: Windows 剪贴板监听和文本历史采集

**Files:**
- Modify: `file-keeper/src-tauri/src/platform/windows/clipboard.rs`
- Modify: `file-keeper/src-tauri/src/clipboard/mod.rs`
- Modify: `file-keeper/src-tauri/src/commands/clipboard.rs`

- [ ] **Step 1: 实现轮询式监听作为首个稳定版本**

在 `ClipboardService` 中新增 monitor flag：

```rust
monitor_running: std::sync::Arc<std::sync::atomic::AtomicBool>,
```

初始化时设置：

```rust
monitor_running: std::sync::Arc::new(std::sync::atomic::AtomicBool::new(false)),
```

新增方法：

```rust
pub fn monitor_flag(&self) -> std::sync::Arc<std::sync::atomic::AtomicBool> {
    self.monitor_running.clone()
}

pub fn collect_text_snapshot(&self, text: &str, source_process: Option<&str>) -> Result<Option<String>, String> {
    if self.should_ignore_text(text)? {
        return Ok(None);
    }
    let id = self.add_text_for_testing(text, source_process)?;
    Ok(Some(id))
}
```

- [ ] **Step 2: 修改 start/stop 命令**

修改 `start_clipboard_monitor`：

```rust
#[tauri::command]
pub fn start_clipboard_monitor(service: State<'_, ClipboardService>) -> Result<(), String> {
    let running = service.monitor_flag();
    if running.swap(true, std::sync::atomic::Ordering::SeqCst) {
        return Ok(());
    }

    let service = service.inner() as *const ClipboardService as usize;
    std::thread::spawn(move || {
        let service = unsafe { &*(service as *const ClipboardService) };
        let mut last_text = String::new();
        while running.load(std::sync::atomic::Ordering::SeqCst) {
            #[cfg(target_os = "windows")]
            if let Ok(Some(text)) = crate::platform::windows::clipboard::read_text() {
                if !text.is_empty() && text != last_text {
                    last_text = text.clone();
                    let _ = service.collect_text_snapshot(&text, None);
                }
            }
            std::thread::sleep(std::time::Duration::from_millis(500));
        }
    });

    Ok(())
}
```

修改 `stop_clipboard_monitor`：

```rust
#[tauri::command]
pub fn stop_clipboard_monitor(service: State<'_, ClipboardService>) -> Result<(), String> {
    service.monitor_flag().store(false, std::sync::atomic::Ordering::SeqCst);
    Ok(())
}
```

说明：这是首个稳定轮询版本，后续可替换为 Windows AddClipboardFormatListener 事件监听。轮询间隔 500ms，避免先引入消息窗口复杂度。

- [ ] **Step 3: 运行 Rust 检查**

Run:

```bash
cd file-keeper/src-tauri && cargo check
```

Expected: PASS。

- [ ] **Step 4: 手动验证文本采集**

Run:

```bash
npm run tauri:dev
```

Manual expected:

1. 应用启动后调用 `start_clipboard_monitor`。
2. 从任意文本源复制 `hello clipboard`。
3. 打开剪贴板页。
4. 历史列表出现 `hello clipboard`。
5. 选择该记录点击复制。
6. 到记事本手动 Ctrl+V，可以粘贴出 `hello clipboard`。

- [ ] **Step 5: 提交 Task 11**

```bash
git add file-keeper/src-tauri/src/platform/windows/clipboard.rs file-keeper/src-tauri/src/clipboard/mod.rs file-keeper/src-tauri/src/commands/clipboard.rs
git commit -m "feat(backend): monitor windows text clipboard"
```

---

### Task 12: Windows 前台窗口记录与自动粘贴

**Files:**
- Create: `file-keeper/src-tauri/src/platform/windows/foreground.rs`
- Modify: `file-keeper/src-tauri/src/platform/windows/mod.rs`
- Modify: `file-keeper/src-tauri/src/clipboard/mod.rs`
- Modify: `file-keeper/src-tauri/src/commands/clipboard.rs`

- [ ] **Step 1: 导出 foreground 模块**

修改 `file-keeper/src-tauri/src/platform/windows/mod.rs`：

```rust
pub mod clipboard;
pub mod foreground;
pub mod process_mappings;
pub mod process_monitor;
```

- [ ] **Step 2: 创建 Windows 前台窗口和 Ctrl+V 实现**

创建 `file-keeper/src-tauri/src/platform/windows/foreground.rs`：

```rust
use std::thread;
use std::time::Duration;
use windows::Win32::Foundation::HWND;
use windows::Win32::UI::Input::KeyboardAndMouse::{
    SendInput, INPUT, INPUT_0, INPUT_KEYBOARD, KEYBDINPUT, KEYEVENTF_KEYUP, VIRTUAL_KEY, VK_CONTROL, VK_V,
};
use windows::Win32::UI::WindowsAndMessaging::{GetForegroundWindow, IsIconic, SetForegroundWindow, ShowWindow, SW_RESTORE};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ForegroundWindow {
    hwnd: isize,
}

impl ForegroundWindow {
    pub fn hwnd(self) -> isize {
        self.hwnd
    }
}

pub fn current_foreground_window() -> Option<ForegroundWindow> {
    unsafe {
        let hwnd = GetForegroundWindow();
        if hwnd.0 == 0 {
            None
        } else {
            Some(ForegroundWindow { hwnd: hwnd.0 as isize })
        }
    }
}

pub fn restore_and_paste(window: ForegroundWindow) -> Result<(), String> {
    unsafe {
        let hwnd = HWND(window.hwnd as *mut std::ffi::c_void);
        if IsIconic(hwnd).as_bool() {
            let _ = ShowWindow(hwnd, SW_RESTORE);
        }
        SetForegroundWindow(hwnd).map_err(|err| err.to_string())?;
    }
    thread::sleep(Duration::from_millis(80));
    send_ctrl_v()
}

fn send_ctrl_v() -> Result<(), String> {
    let inputs = [
        keyboard_input(VK_CONTROL, false),
        keyboard_input(VK_V, false),
        keyboard_input(VK_V, true),
        keyboard_input(VK_CONTROL, true),
    ];

    let sent = unsafe { SendInput(&inputs, std::mem::size_of::<INPUT>() as i32) };
    if sent != inputs.len() as u32 {
        return Err("自动粘贴快捷键发送失败".to_string());
    }
    Ok(())
}

fn keyboard_input(key: VIRTUAL_KEY, key_up: bool) -> INPUT {
    INPUT {
        r#type: INPUT_KEYBOARD,
        Anonymous: INPUT_0 {
            ki: KEYBDINPUT {
                wVk: key,
                wScan: 0,
                dwFlags: if key_up { KEYEVENTF_KEYUP } else { Default::default() },
                time: 0,
                dwExtraInfo: 0,
            },
        },
    }
}
```

- [ ] **Step 3: 在 ClipboardService 中记录目标窗口**

修改 `file-keeper/src-tauri/src/clipboard/mod.rs` 的 `ClipboardService` 字段：

```rust
#[cfg(target_os = "windows")]
last_target_window: Mutex<Option<crate::platform::windows::foreground::ForegroundWindow>>,
```

在 `new()` 和 `in_memory()` 初始化中加入：

```rust
#[cfg(target_os = "windows")]
last_target_window: Mutex::new(None),
```

在 impl 中加入：

```rust
#[cfg(target_os = "windows")]
pub fn remember_current_foreground_window(&self) -> Result<(), String> {
    let window = crate::platform::windows::foreground::current_foreground_window();
    *self.last_target_window.lock().map_err(|err| err.to_string())? = window;
    Ok(())
}

#[cfg(target_os = "windows")]
pub fn paste_to_remembered_window(&self) -> Result<(), String> {
    let window = *self.last_target_window.lock().map_err(|err| err.to_string())?;
    let window = window.ok_or_else(|| "没有可恢复的目标窗口".to_string())?;
    crate::platform::windows::foreground::restore_and_paste(window)
}
```

- [ ] **Step 4: 修改命令以支持自动粘贴**

在 `file-keeper/src-tauri/src/commands/clipboard.rs` 新增命令：

```rust
#[tauri::command]
pub fn remember_clipboard_target_window(service: State<'_, ClipboardService>) -> Result<(), String> {
    #[cfg(target_os = "windows")]
    {
        service.remember_current_foreground_window()
    }
    #[cfg(not(target_os = "windows"))]
    {
        let _ = service;
        Ok(())
    }
}
```

修改 `paste_clipboard_item`：

```rust
#[tauri::command]
pub fn paste_clipboard_item(id: String, format: crate::clipboard::ClipboardPasteFormat, service: State<'_, ClipboardService>) -> Result<(), String> {
    copy_clipboard_item(id, format, service.clone())?;
    let settings = service.load_settings()?;
    if !settings.auto_paste {
        return Ok(());
    }

    #[cfg(target_os = "windows")]
    {
        service.paste_to_remembered_window()
    }
    #[cfg(not(target_os = "windows"))]
    {
        Ok(())
    }
}
```

在 `main.rs` import 和 `generate_handler!` 中注册 `remember_clipboard_target_window`。

- [ ] **Step 5: 前端 API 增加目标窗口记录**

修改 `file-keeper/src/api/clipboard.ts`：

```ts
export async function rememberClipboardTargetWindow(): Promise<void> {
  await invoke('remember_clipboard_target_window')
}
```

修改 `file-keeper/src/stores/clipboardStore.ts`：

```ts
import { rememberClipboardTargetWindow } from '../api/clipboard'
```

在 `openQuickPanel()` 中先记录目标窗口：

```ts
async function openQuickPanel() {
  await rememberClipboardTargetWindow()
  isQuickPanelOpen.value = true
}
```

如果调用方不能 await，也允许 fire-and-forget：

```ts
void rememberClipboardTargetWindow().finally(() => {
  isQuickPanelOpen.value = true
})
```

- [ ] **Step 6: 运行检查**

Run:

```bash
cd file-keeper/src-tauri && cargo check
npm run build
```

Expected: PASS。

- [ ] **Step 7: 手动验证自动粘贴**

Run:

```bash
npm run tauri:dev
```

Manual expected:

1. 在设置里开启自动粘贴。
2. 打开记事本，把光标放在空白处。
3. 按剪贴板快捷键呼出面板。
4. 选择文本历史并按 Enter。
5. 面板关闭，记事本出现对应文本。
6. 如果目标窗口恢复失败，文本仍已写入系统剪贴板，可手动 Ctrl+V。

- [ ] **Step 8: 提交 Task 12**

```bash
git add file-keeper/src-tauri/src/platform/windows/mod.rs file-keeper/src-tauri/src/platform/windows/foreground.rs file-keeper/src-tauri/src/clipboard/mod.rs file-keeper/src-tauri/src/commands/clipboard.rs file-keeper/src-tauri/src/main.rs file-keeper/src/api/clipboard.ts file-keeper/src/stores/clipboardStore.ts
git commit -m "feat: add clipboard auto paste support"
```

---

### Task 13: 剪贴板设置页

**Files:**
- Create: `file-keeper/src/components/ClipboardSettings.vue`
- Modify: `file-keeper/src/components/ClipboardManagement.vue`
- Create: `file-keeper/src/components/__tests__/clipboardSettings.test.ts`

- [ ] **Step 1: 写设置页失败测试**

创建 `file-keeper/src/components/__tests__/clipboardSettings.test.ts`：

```ts
import { describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import ClipboardSettings from '../ClipboardSettings.vue'
import type { ClipboardSettings as ClipboardSettingsType } from '../../types/clipboard'

function settings(): ClipboardSettingsType {
  return {
    monitorEnabled: true,
    quickPanelShortcut: 'CommandOrControl+Shift+V',
    autoPaste: false,
    protectSensitiveContent: true,
    enableOcr: true,
    enableLinkPreview: false,
    totalNonTextLimitMb: 2048,
    itemSizeLimitMb: 200,
    typeLimitsMb: { image: 1024, file: 2048, html: 500, linkPreview: 200 },
    fileExtensionMode: 'allow_all',
    fileExtensions: [],
    excludedApps: []
  }
}

describe('ClipboardSettings', () => {
  it('renders storage and safety controls', () => {
    const wrapper = mount(ClipboardSettings, { props: { settings: settings() } })

    expect(wrapper.text()).toContain('安全防护')
    expect(wrapper.text()).toContain('非文本缓存上限')
    expect(wrapper.text()).toContain('后缀规则')
  })

  it('emits save with changed auto paste setting', async () => {
    const wrapper = mount(ClipboardSettings, { props: { settings: settings() } })

    await wrapper.get('[data-test="auto-paste"]').setValue(true)
    await wrapper.get('[data-test="save-settings"]').trigger('click')

    const payload = wrapper.emitted('save')?.[0]?.[0] as ClipboardSettingsType
    expect(payload.autoPaste).toBe(true)
  })

  it('parses file extensions as trimmed lower-case values', async () => {
    const wrapper = mount(ClipboardSettings, { props: { settings: settings() } })

    await wrapper.get('[data-test="extension-mode"]').setValue('allow_list')
    await wrapper.get('[data-test="extensions"]').setValue('.PDF, Docx, png')
    await wrapper.get('[data-test="save-settings"]').trigger('click')

    const payload = wrapper.emitted('save')?.[0]?.[0] as ClipboardSettingsType
    expect(payload.fileExtensionMode).toBe('allow_list')
    expect(payload.fileExtensions).toEqual(['pdf', 'docx', 'png'])
  })
})
```

- [ ] **Step 2: 运行测试确认失败**

Run:

```bash
npm run test -- src/components/__tests__/clipboardSettings.test.ts
```

Expected: FAIL，组件不存在。

- [ ] **Step 3: 实现 ClipboardSettings 组件**

创建 `file-keeper/src/components/ClipboardSettings.vue`：

```vue
<template>
  <div class="fixed inset-0 z-[80] flex items-center justify-center bg-black/40 p-4" @click="$emit('close')">
    <div class="w-full max-w-2xl rounded-xl bg-white shadow-2xl dark:bg-dark-panel" @click.stop>
      <div class="border-b border-gray-200 px-5 py-4 dark:border-dark-border">
        <h2 class="text-base font-semibold">剪贴板设置</h2>
      </div>

      <div class="max-h-[70vh] space-y-5 overflow-auto p-5">
        <section class="space-y-3">
          <h3 class="text-sm font-semibold">安全防护</h3>
          <label class="flex items-center justify-between text-sm">
            <span>默认拦截密码、密钥、银行卡等敏感内容</span>
            <input v-model="local.protectSensitiveContent" type="checkbox" />
          </label>
          <label class="flex items-center justify-between text-sm">
            <span>URL 联网预览</span>
            <input v-model="local.enableLinkPreview" type="checkbox" />
          </label>
          <p class="text-xs text-amber-600">开启 URL 预览后，应用会访问链接对应的网站以抓取标题和缩略图。</p>
        </section>

        <section class="space-y-3">
          <h3 class="text-sm font-semibold">粘贴行为</h3>
          <label class="flex items-center justify-between text-sm">
            <span>自动粘贴到原窗口</span>
            <input data-test="auto-paste" v-model="local.autoPaste" type="checkbox" />
          </label>
          <label class="flex items-center justify-between text-sm">
            <span>启用 OCR</span>
            <input v-model="local.enableOcr" type="checkbox" />
          </label>
        </section>

        <section class="space-y-3">
          <h3 class="text-sm font-semibold">空间规则</h3>
          <label class="block text-sm">
            非文本缓存上限（MB）
            <input v-model.number="local.totalNonTextLimitMb" type="number" min="128" class="mt-1 w-full rounded border px-3 py-2 dark:bg-dark-hover" />
          </label>
          <label class="block text-sm">
            单条记录大小上限（MB）
            <input v-model.number="local.itemSizeLimitMb" type="number" min="1" class="mt-1 w-full rounded border px-3 py-2 dark:bg-dark-hover" />
          </label>
        </section>

        <section class="space-y-3">
          <h3 class="text-sm font-semibold">后缀规则</h3>
          <select data-test="extension-mode" v-model="local.fileExtensionMode" class="w-full rounded border px-3 py-2 dark:bg-dark-hover">
            <option value="allow_all">不限制，默认都可以保存</option>
            <option value="allow_list">只保存这些后缀</option>
            <option value="block_list">排除这些后缀</option>
          </select>
          <label class="block text-sm">
            后缀列表，用逗号分隔
            <input data-test="extensions" v-model="extensionsText" placeholder="pdf, docx, png" class="mt-1 w-full rounded border px-3 py-2 dark:bg-dark-hover" />
          </label>
        </section>
      </div>

      <div class="flex justify-end gap-3 border-t border-gray-200 px-5 py-4 dark:border-dark-border">
        <button class="rounded px-4 py-2 text-sm hover:bg-gray-100 dark:hover:bg-dark-hover" @click="$emit('close')">取消</button>
        <button data-test="save-settings" class="rounded bg-primary px-4 py-2 text-sm text-white" @click="save">保存</button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import type { ClipboardSettings } from '../types/clipboard'

const props = defineProps<{
  settings: ClipboardSettings
}>()

const emit = defineEmits<{
  save: [settings: ClipboardSettings]
  close: []
}>()

const local = ref<ClipboardSettings>({ ...props.settings, typeLimitsMb: { ...props.settings.typeLimitsMb } })
const extensionsText = ref(props.settings.fileExtensions.join(', '))

watch(() => props.settings, (settings) => {
  local.value = { ...settings, typeLimitsMb: { ...settings.typeLimitsMb } }
  extensionsText.value = settings.fileExtensions.join(', ')
})

function save() {
  emit('save', {
    ...local.value,
    fileExtensions: extensionsText.value
      .split(',')
      .map(item => item.trim().replace(/^\./, '').toLowerCase())
      .filter(Boolean)
  })
}
</script>
```

- [ ] **Step 4: 接入 ClipboardManagement**

在 `file-keeper/src/components/ClipboardManagement.vue` import：

```ts
import ClipboardSettings from './ClipboardSettings.vue'
import { ref } from 'vue'
```

新增状态：

```ts
const showSettings = ref(false)
```

左侧 aside 中 `ClipboardSecurityEvents` 下方添加：

```vue
<button class="w-full rounded bg-gray-100 px-3 py-2 text-sm dark:bg-dark-hover" @click="showSettings = true">
  剪贴板设置
</button>
```

模板底部添加：

```vue
<ClipboardSettings
  v-if="showSettings"
  :settings="clipboardStore.settings"
  @close="showSettings = false"
  @save="async (settings) => { await clipboardStore.updateSettings(settings); showSettings = false }"
/>
```

- [ ] **Step 5: 运行测试确认通过**

Run:

```bash
npm run test -- src/components/__tests__/clipboardSettings.test.ts src/components/__tests__/clipboardComponents.test.ts
```

Expected: PASS。

- [ ] **Step 6: 提交 Task 13**

```bash
git add file-keeper/src/components/ClipboardSettings.vue file-keeper/src/components/ClipboardManagement.vue file-keeper/src/components/__tests__/clipboardSettings.test.ts
git commit -m "feat(frontend): add clipboard settings panel"
```

---

### Task 14: 文件剪贴板记录和副本规则

**Files:**
- Modify: `file-keeper/src-tauri/src/clipboard/storage.rs`
- Modify: `file-keeper/src-tauri/src/clipboard/cache.rs`
- Modify: `file-keeper/src-tauri/src/commands/clipboard.rs`
- Modify: `file-keeper/src/components/ClipboardPreview.vue`

- [ ] **Step 1: 写文件规则测试**

在 `file-keeper/src-tauri/src/clipboard/cache.rs` tests 中追加：

```rust
#[test]
fn item_size_limit_blocks_large_file_copy() {
    assert!(within_item_size_limit(100 * 1024 * 1024, 200));
    assert!(!within_item_size_limit(201 * 1024 * 1024, 200));
}
```

在文件顶部新增待实现函数：

```rust
pub fn within_item_size_limit(bytes: i64, limit_mb: i64) -> bool {
    let _ = bytes;
    let _ = limit_mb;
    true
}
```

- [ ] **Step 2: 运行测试确认失败**

Run:

```bash
cd file-keeper/src-tauri && cargo test clipboard::cache::tests::item_size_limit_blocks_large_file_copy
```

Expected: FAIL，201MB 判断为 true。

- [ ] **Step 3: 实现大小限制**

替换 `within_item_size_limit`：

```rust
pub fn within_item_size_limit(bytes: i64, limit_mb: i64) -> bool {
    if limit_mb <= 0 {
        return true;
    }
    bytes <= limit_mb * 1024 * 1024
}
```

- [ ] **Step 4: 扩展 storage 支持文件条目**

在 `init()` SQL 中新增 `files_json TEXT` 字段。如果表已存在，需要在 `execute_batch` 后执行：

```rust
let _ = self.connection.execute("ALTER TABLE clipboard_items ADD COLUMN files_json TEXT", []);
```

在 `ClipboardStorage` impl 中新增：

```rust
pub fn insert_file_item(&self, files: &[crate::clipboard::types::ClipboardFileEntry], title: &str, cache_bytes: i64) -> Result<String, String> {
    let id = Uuid::new_v4().to_string();
    let now = current_millis();
    let files_json = serde_json::to_string(files).map_err(|err| err.to_string())?;
    let summary = files.iter().map(|file| file.name.clone()).collect::<Vec<_>>().join(", ");
    let search_text = normalize_search_text(&summary);
    self.connection.execute(
        "INSERT INTO clipboard_items (
            id, kind, title, summary, search_text, created_at, use_count, is_favorite, is_pinned,
            cache_bytes, cache_state, files_json
        ) VALUES (?1, 'file', ?2, ?3, ?4, ?5, 0, 0, 0, ?6, 'cached', ?7)",
        params![id, title, summary, search_text, now, cache_bytes, files_json],
    ).map_err(|err| err.to_string())?;
    Ok(id)
}

pub fn get_files(&self, id: &str) -> Result<Option<Vec<crate::clipboard::types::ClipboardFileEntry>>, String> {
    let result: Result<Option<String>, rusqlite::Error> = self.connection.query_row(
        "SELECT files_json FROM clipboard_items WHERE id = ?1",
        params![id],
        |row| row.get(0),
    );
    match result {
        Ok(Some(value)) => serde_json::from_str(&value).map(Some).map_err(|err| err.to_string()),
        Ok(None) => Ok(None),
        Err(rusqlite::Error::QueryReturnedNoRows) => Ok(None),
        Err(err) => Err(err.to_string()),
    }
}
```

- [ ] **Step 5: 增加测试用文件插入命令**

在 `ClipboardService` 中新增：

```rust
pub fn add_file_for_testing(&self, path: &str, size_bytes: i64) -> Result<String, String> {
    let settings = self.load_settings()?;
    let copy_state = if cache::extension_allowed(path, &settings.file_extension_mode, &settings.file_extensions)
        && cache::within_item_size_limit(size_bytes, settings.item_size_limit_mb)
    {
        "cached"
    } else {
        "reference_only"
    };
    let name = std::path::Path::new(path)
        .file_name()
        .and_then(|value| value.to_str())
        .unwrap_or(path)
        .to_string();
    let entry = crate::clipboard::types::ClipboardFileEntry {
        name: name.clone(),
        original_path: path.to_string(),
        cached_path: if copy_state == "cached" { Some(path.to_string()) } else { None },
        size_bytes,
        modified_at: None,
        hash: None,
        is_directory: false,
        copy_state: copy_state.to_string(),
    };
    self.storage.lock().map_err(|err| err.to_string())?.insert_file_item(&[entry], &name, if copy_state == "cached" { size_bytes } else { 0 })
}

pub fn get_files(&self, id: &str) -> Result<Option<Vec<crate::clipboard::types::ClipboardFileEntry>>, String> {
    self.storage.lock().map_err(|err| err.to_string())?.get_files(id)
}
```

在 `commands/clipboard.rs` 新增：

```rust
#[tauri::command]
pub fn add_clipboard_file_for_testing(path: String, size_bytes: i64, service: State<'_, ClipboardService>) -> Result<String, String> {
    service.add_file_for_testing(&path, size_bytes)
}
```

注册到 `main.rs`。

- [ ] **Step 6: 详情命令返回 files**

修改 `get_clipboard_item_detail`，查询到 item 后增加：

```rust
let files = service.get_files(&id)?;
```

返回结构中填入：

```rust
files,
```

- [ ] **Step 7: 前端预览文件列表**

修改 `file-keeper/src/components/ClipboardPreview.vue` 预览区域，在图片分支后添加：

```vue
<div v-else-if="detail?.files" class="space-y-2">
  <div v-for="file in detail.files" :key="file.originalPath" class="rounded border border-gray-200 bg-white p-2 text-xs dark:border-dark-border dark:bg-dark-panel">
    <div class="font-medium">{{ file.name }}</div>
    <div class="text-gray-500">{{ file.originalPath }}</div>
    <div class="mt-1 text-gray-400">{{ file.copyState === 'cached' ? '已保存副本' : '仅保存引用' }}</div>
  </div>
</div>
```

- [ ] **Step 8: 运行测试和检查**

Run:

```bash
cd file-keeper/src-tauri && cargo test clipboard::cache
cd ../.. && npm run build
```

Expected: PASS。

- [ ] **Step 9: 提交 Task 14**

```bash
git add file-keeper/src-tauri/src/clipboard/storage.rs file-keeper/src-tauri/src/clipboard/cache.rs file-keeper/src-tauri/src/clipboard/mod.rs file-keeper/src-tauri/src/commands/clipboard.rs file-keeper/src-tauri/src/main.rs file-keeper/src/components/ClipboardPreview.vue
git commit -m "feat: add clipboard file history rules"
```

---

### Task 15: 图片缓存、缩略图和基础转换

**Files:**
- Modify: `file-keeper/src-tauri/src/clipboard/storage.rs`
- Modify: `file-keeper/src-tauri/src/commands/clipboard.rs`
- Modify: `file-keeper/src/components/ClipboardPreview.vue`

- [ ] **Step 1: 增加图片元数据字段迁移**

在 `ClipboardStorage::init()` 中创建表后追加迁移：

```rust
let _ = self.connection.execute("ALTER TABLE clipboard_items ADD COLUMN image_path TEXT", []);
let _ = self.connection.execute("ALTER TABLE clipboard_items ADD COLUMN image_width INTEGER", []);
let _ = self.connection.execute("ALTER TABLE clipboard_items ADD COLUMN image_height INTEGER", []);
let _ = self.connection.execute("ALTER TABLE clipboard_items ADD COLUMN image_format TEXT", []);
let _ = self.connection.execute("ALTER TABLE clipboard_items ADD COLUMN ocr_text TEXT", []);
```

- [ ] **Step 2: 增加图片插入和详情读取**

在 `ClipboardStorage` impl 中新增：

```rust
pub fn insert_image_item(&self, image_path: &str, width: i64, height: i64, format: &str, cache_bytes: i64) -> Result<String, String> {
    let id = Uuid::new_v4().to_string();
    let now = current_millis();
    let title = format!("图片 {}×{}", width, height);
    self.connection.execute(
        "INSERT INTO clipboard_items (
            id, kind, title, summary, search_text, created_at, use_count, is_favorite, is_pinned,
            thumbnail_path, cache_bytes, cache_state, image_path, image_width, image_height, image_format
        ) VALUES (?1, 'image', ?2, ?3, ?4, ?5, 0, 0, 0, ?6, ?7, 'cached', ?8, ?9, ?10, ?11)",
        params![id, title, title, title, now, image_path, cache_bytes, image_path, width, height, format],
    ).map_err(|err| err.to_string())?;
    Ok(id)
}

pub fn get_image_meta(&self, id: &str) -> Result<Option<(String, i64, i64, String, Option<String>)>, String> {
    let result = self.connection.query_row(
        "SELECT image_path, image_width, image_height, image_format, ocr_text FROM clipboard_items WHERE id = ?1",
        params![id],
        |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?, row.get(3)?, row.get(4)?)),
    );
    match result {
        Ok(meta) => Ok(Some(meta)),
        Err(rusqlite::Error::QueryReturnedNoRows) => Ok(None),
        Err(err) => Err(err.to_string()),
    }
}
```

- [ ] **Step 3: 增加测试图片插入命令**

在 `ClipboardService` 中新增：

```rust
pub fn add_image_for_testing(&self, image_path: &str) -> Result<String, String> {
    let reader = image::ImageReader::open(image_path).map_err(|err| err.to_string())?;
    let format = reader.format().map(|format| format.extensions_str()[0].to_string()).unwrap_or_else(|| "unknown".to_string());
    let image = reader.decode().map_err(|err| err.to_string())?;
    let width = image.width() as i64;
    let height = image.height() as i64;
    let bytes = std::fs::metadata(image_path).map_err(|err| err.to_string())?.len() as i64;
    self.storage.lock().map_err(|err| err.to_string())?.insert_image_item(image_path, width, height, &format, bytes)
}

pub fn get_image_meta(&self, id: &str) -> Result<Option<(String, i64, i64, String, Option<String>)>, String> {
    self.storage.lock().map_err(|err| err.to_string())?.get_image_meta(id)
}
```

在 `commands/clipboard.rs` 新增：

```rust
#[tauri::command]
pub fn add_clipboard_image_for_testing(path: String, service: State<'_, ClipboardService>) -> Result<String, String> {
    service.add_image_for_testing(&path)
}
```

注册到 `main.rs`。

- [ ] **Step 4: 详情命令返回图片元数据**

在 `get_clipboard_item_detail` 中加入：

```rust
let image_meta = service.get_image_meta(&id)?;
let (image_path, image_width, image_height, image_format, ocr_text) = image_meta
    .map(|(path, width, height, format, ocr)| (Some(path), Some(width), Some(height), Some(format), ocr))
    .unwrap_or((None, None, None, None, None));
```

返回结构填入这些字段。

- [ ] **Step 5: 前端预览图片元数据**

修改 `ClipboardPreview.vue` 标题区域下方添加：

```vue
<p v-if="detail?.imageWidth && detail?.imageHeight" class="mt-1 text-xs text-gray-500">
  {{ detail.imageWidth }}×{{ detail.imageHeight }} · {{ detail.imageFormat }}
</p>
```

- [ ] **Step 6: 运行检查**

Run:

```bash
cd file-keeper/src-tauri && cargo check
cd ../.. && npm run build
```

Expected: PASS。

- [ ] **Step 7: 手动验证图片记录**

Manual expected:

1. 准备一张本地 PNG 或 JPG。
2. 调用测试命令 `add_clipboard_image_for_testing` 插入图片路径。
3. 主窗口剪贴板页出现图片记录。
4. 右侧显示图片预览、尺寸和格式。

- [ ] **Step 8: 提交 Task 15**

```bash
git add file-keeper/src-tauri/src/clipboard/storage.rs file-keeper/src-tauri/src/clipboard/mod.rs file-keeper/src-tauri/src/commands/clipboard.rs file-keeper/src-tauri/src/main.rs file-keeper/src/components/ClipboardPreview.vue
git commit -m "feat: add clipboard image history metadata"
```

---

### Task 16: HTML、Markdown、URL 和颜色记录

**Files:**
- Modify: `file-keeper/src-tauri/src/clipboard/storage.rs`
- Modify: `file-keeper/src-tauri/src/clipboard/mod.rs`
- Modify: `file-keeper/src-tauri/src/commands/clipboard.rs`
- Modify: `file-keeper/src/components/ClipboardPreview.vue`

- [ ] **Step 1: 增加 HTML/URL/颜色字段迁移**

在 `ClipboardStorage::init()` 后追加：

```rust
let _ = self.connection.execute("ALTER TABLE clipboard_items ADD COLUMN html TEXT", []);
let _ = self.connection.execute("ALTER TABLE clipboard_items ADD COLUMN sanitized_html TEXT", []);
let _ = self.connection.execute("ALTER TABLE clipboard_items ADD COLUMN markdown TEXT", []);
let _ = self.connection.execute("ALTER TABLE clipboard_items ADD COLUMN url TEXT", []);
let _ = self.connection.execute("ALTER TABLE clipboard_items ADD COLUMN url_title TEXT", []);
let _ = self.connection.execute("ALTER TABLE clipboard_items ADD COLUMN url_description TEXT", []);
let _ = self.connection.execute("ALTER TABLE clipboard_items ADD COLUMN url_thumbnail_path TEXT", []);
let _ = self.connection.execute("ALTER TABLE clipboard_items ADD COLUMN color_hex TEXT", []);
let _ = self.connection.execute("ALTER TABLE clipboard_items ADD COLUMN color_rgb TEXT", []);
```

- [ ] **Step 2: 添加轻量 HTML 到 Markdown 转换**

在 `ClipboardService` impl 附近添加：

```rust
fn html_to_plain_markdown(html: &str) -> String {
    html
        .replace("<br>", "\n")
        .replace("<br/>", "\n")
        .replace("<br />", "\n")
        .replace("</p>", "\n")
        .replace("<p>", "")
        .replace("<strong>", "**")
        .replace("</strong>", "**")
        .replace("<b>", "**")
        .replace("</b>", "**")
        .replace("<em>", "*")
        .replace("</em>", "*")
}
```

- [ ] **Step 3: 添加插入 HTML/URL/颜色方法**

在 `ClipboardStorage` impl 新增：

```rust
pub fn insert_html_item(&self, html: &str, markdown: &str) -> Result<String, String> {
    let id = Uuid::new_v4().to_string();
    let now = current_millis();
    let title = markdown.chars().take(80).collect::<String>();
    self.connection.execute(
        "INSERT INTO clipboard_items (id, kind, title, summary, search_text, created_at, use_count, is_favorite, is_pinned, cache_bytes, cache_state, html, sanitized_html, markdown)
         VALUES (?1, 'html', ?2, ?3, ?4, ?5, 0, 0, 0, 0, 'none', ?6, ?6, ?7)",
        params![id, title, title, normalize_search_text(markdown), now, html, markdown],
    ).map_err(|err| err.to_string())?;
    Ok(id)
}

pub fn insert_url_item(&self, url: &str) -> Result<String, String> {
    let id = Uuid::new_v4().to_string();
    let now = current_millis();
    self.connection.execute(
        "INSERT INTO clipboard_items (id, kind, title, summary, search_text, created_at, use_count, is_favorite, is_pinned, cache_bytes, cache_state, url)
         VALUES (?1, 'url', ?2, ?2, ?3, ?4, 0, 0, 0, 0, 'none', ?2)",
        params![id, url, normalize_search_text(url), now],
    ).map_err(|err| err.to_string())?;
    Ok(id)
}

pub fn insert_color_item(&self, hex: &str, rgb: &str) -> Result<String, String> {
    let id = Uuid::new_v4().to_string();
    let now = current_millis();
    self.connection.execute(
        "INSERT INTO clipboard_items (id, kind, title, summary, search_text, created_at, use_count, is_favorite, is_pinned, cache_bytes, cache_state, color_hex, color_rgb)
         VALUES (?1, 'color', ?2, ?3, ?4, ?5, 0, 0, 0, 0, 'none', ?2, ?3)",
        params![id, hex, rgb, normalize_search_text(&format!("{} {}", hex, rgb)), now],
    ).map_err(|err| err.to_string())?;
    Ok(id)
}
```

- [ ] **Step 4: ClipboardService 自动分类文本**

修改 `add_text_for_testing`：

```rust
if let Some((hex, rgb)) = search::detect_color(text) {
    return self.storage.lock().map_err(|err| err.to_string())?.insert_color_item(&hex, &rgb);
}
if let Some(url) = search::normalize_url(text) {
    if url.starts_with("http://") || url.starts_with("https://") {
        return self.storage.lock().map_err(|err| err.to_string())?.insert_url_item(&url);
    }
}
```

新增：

```rust
pub fn add_html_for_testing(&self, html: &str) -> Result<String, String> {
    let markdown = html_to_plain_markdown(html);
    self.storage.lock().map_err(|err| err.to_string())?.insert_html_item(html, &markdown)
}
```

在 `commands/clipboard.rs` 新增：

```rust
#[tauri::command]
pub fn add_clipboard_html_for_testing(html: String, service: State<'_, ClipboardService>) -> Result<String, String> {
    service.add_html_for_testing(&html)
}
```

- [ ] **Step 5: 详情返回扩展字段**

在 storage 中新增 `get_rich_fields(id)`，返回 html/markdown/url/color 字段；在 `get_clipboard_item_detail` 填入对应字段。函数签名：

```rust
pub fn get_rich_fields(&self, id: &str) -> Result<(Option<String>, Option<String>, Option<String>, Option<String>, Option<String>), String>
```

SQL：

```sql
SELECT html, markdown, url, color_hex, color_rgb FROM clipboard_items WHERE id = ?1
```

- [ ] **Step 6: 前端预览 URL 和颜色**

在 `ClipboardPreview.vue` 预览区域添加：

```vue
<div v-else-if="detail?.url" class="space-y-2">
  <div class="text-sm font-medium">{{ detail.urlTitle || detail.url }}</div>
  <a class="break-all text-primary" :href="detail.url">{{ detail.url }}</a>
  <p class="text-xs text-gray-500">{{ detail.urlDescription || '链接预览未联网抓取' }}</p>
</div>
<div v-else-if="detail?.colorHex" class="space-y-3">
  <div class="h-24 rounded" :style="{ backgroundColor: detail.colorHex }"></div>
  <div class="font-mono text-sm">{{ detail.colorHex }}</div>
  <div class="font-mono text-sm">{{ detail.colorRgb }}</div>
</div>
```

- [ ] **Step 7: 运行检查**

Run:

```bash
cd file-keeper/src-tauri && cargo check && cargo test clipboard::search
cd ../.. && npm run build
```

Expected: PASS。

- [ ] **Step 8: 提交 Task 16**

```bash
git add file-keeper/src-tauri/src/clipboard/storage.rs file-keeper/src-tauri/src/clipboard/mod.rs file-keeper/src-tauri/src/commands/clipboard.rs file-keeper/src-tauri/src/main.rs file-keeper/src/components/ClipboardPreview.vue
git commit -m "feat: add rich clipboard item types"
```

---

### Task 17: OCR 可替换接口与图片文字搜索

**Files:**
- Modify: `file-keeper/src-tauri/src/clipboard/storage.rs`
- Create: `file-keeper/src-tauri/src/clipboard/ocr.rs`
- Modify: `file-keeper/src-tauri/src/clipboard/mod.rs`
- Modify: `file-keeper/src-tauri/src/commands/clipboard.rs`

- [ ] **Step 1: 创建 OCR 模块的可替换接口**

创建 `file-keeper/src-tauri/src/clipboard/ocr.rs`：

```rust
pub trait OcrEngine: Send + Sync {
    fn recognize(&self, image_path: &str) -> Result<String, String>;
}

pub struct DisabledOcrEngine;

impl OcrEngine for DisabledOcrEngine {
    fn recognize(&self, _image_path: &str) -> Result<String, String> {
        Ok(String::new())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn disabled_ocr_returns_empty_text() {
        let engine = DisabledOcrEngine;
        assert_eq!(engine.recognize("image.png").unwrap(), "");
    }
}
```

修改 `clipboard/mod.rs` 导出：

```rust
pub mod ocr;
```

- [ ] **Step 2: storage 支持更新 OCR 文本**

在 `ClipboardStorage` impl 新增：

```rust
pub fn update_ocr_text(&self, id: &str, ocr_text: &str) -> Result<(), String> {
    let normalized = normalize_search_text(ocr_text);
    self.connection.execute(
        "UPDATE clipboard_items
         SET ocr_text = ?2,
             search_text = trim(search_text || ' ' || ?3)
         WHERE id = ?1",
        params![id, ocr_text, normalized],
    ).map_err(|err| err.to_string())?;
    Ok(())
}
```

在 `ClipboardService` 新增：

```rust
pub fn update_ocr_text(&self, id: &str, text: &str) -> Result<(), String> {
    self.storage.lock().map_err(|err| err.to_string())?.update_ocr_text(id, text)
}
```

- [ ] **Step 3: 添加测试命令模拟 OCR 完成**

在 `commands/clipboard.rs` 新增：

```rust
#[tauri::command]
pub fn set_clipboard_ocr_text_for_testing(id: String, text: String, service: State<'_, ClipboardService>) -> Result<(), String> {
    service.update_ocr_text(&id, &text)
}
```

注册到 `main.rs`。

- [ ] **Step 4: 运行搜索验证**

Run:

```bash
cd file-keeper/src-tauri && cargo test clipboard::ocr
cargo check
```

Expected: PASS。

Manual expected:

1. 插入一条图片测试记录。
2. 调用 `set_clipboard_ocr_text_for_testing(id, "错误日志")`。
3. 前端搜索“错误日志”。
4. 图片记录出现在结果中。

- [ ] **Step 5: 提交 Task 17**

```bash
git add file-keeper/src-tauri/src/clipboard/ocr.rs file-keeper/src-tauri/src/clipboard/mod.rs file-keeper/src-tauri/src/clipboard/storage.rs file-keeper/src-tauri/src/clipboard/mod.rs file-keeper/src-tauri/src/commands/clipboard.rs file-keeper/src-tauri/src/main.rs
git commit -m "feat: add clipboard ocr indexing hook"
```

---

### Task 18: URL 联网预览开关与重试命令

**Files:**
- Modify: `file-keeper/src-tauri/Cargo.toml`
- Create: `file-keeper/src-tauri/src/clipboard/link_preview.rs`
- Modify: `file-keeper/src-tauri/src/clipboard/mod.rs`
- Modify: `file-keeper/src-tauri/src/clipboard/storage.rs`
- Modify: `file-keeper/src-tauri/src/commands/clipboard.rs`

- [ ] **Step 1: 添加 reqwest 依赖**

在 `file-keeper/src-tauri/Cargo.toml` `[dependencies]` 中添加：

```toml
reqwest = { version = "0.12", default-features = false, features = ["blocking", "rustls-tls"] }
```

- [ ] **Step 2: 创建链接预览解析模块**

创建 `file-keeper/src-tauri/src/clipboard/link_preview.rs`：

```rust
use regex::Regex;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct LinkPreview {
    pub title: Option<String>,
    pub description: Option<String>,
}

pub fn parse_preview(html: &str) -> LinkPreview {
    LinkPreview {
        title: extract_title(html),
        description: extract_meta_description(html),
    }
}

fn extract_title(html: &str) -> Option<String> {
    let re = Regex::new(r"(?is)<title[^>]*>(.*?)</title>").unwrap();
    re.captures(html)
        .and_then(|captures| captures.get(1))
        .map(|value| value.as_str().trim().to_string())
        .filter(|value| !value.is_empty())
}

fn extract_meta_description(html: &str) -> Option<String> {
    let re = Regex::new(r#"(?is)<meta\s+[^>]*name=[\"']description[\"'][^>]*content=[\"']([^\"']+)[\"'][^>]*>"#).unwrap();
    re.captures(html)
        .and_then(|captures| captures.get(1))
        .map(|value| value.as_str().trim().to_string())
        .filter(|value| !value.is_empty())
}

pub fn fetch_preview(url: &str) -> Result<LinkPreview, String> {
    let body = reqwest::blocking::get(url)
        .map_err(|err| err.to_string())?
        .text()
        .map_err(|err| err.to_string())?;
    Ok(parse_preview(&body))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_title_and_description() {
        let html = r#"
          <html><head>
            <title>Example Title</title>
            <meta name="description" content="Example description">
          </head></html>
        "#;
        let preview = parse_preview(html);
        assert_eq!(preview.title.as_deref(), Some("Example Title"));
        assert_eq!(preview.description.as_deref(), Some("Example description"));
    }
}
```

修改 `clipboard/mod.rs` 导出：

```rust
pub mod link_preview;
```

- [ ] **Step 3: storage 支持更新链接预览**

在 `ClipboardStorage` impl 新增：

```rust
pub fn update_link_preview(&self, id: &str, title: Option<&str>, description: Option<&str>) -> Result<(), String> {
    self.connection.execute(
        "UPDATE clipboard_items SET url_title = ?2, url_description = ?3 WHERE id = ?1",
        params![id, title, description],
    ).map_err(|err| err.to_string())?;
    Ok(())
}

pub fn get_url(&self, id: &str) -> Result<Option<String>, String> {
    let result: Result<Option<String>, rusqlite::Error> = self.connection.query_row(
        "SELECT url FROM clipboard_items WHERE id = ?1",
        params![id],
        |row| row.get(0),
    );
    match result {
        Ok(url) => Ok(url),
        Err(rusqlite::Error::QueryReturnedNoRows) => Ok(None),
        Err(err) => Err(err.to_string()),
    }
}
```

在 `ClipboardService` 新增：

```rust
pub fn retry_link_preview(&self, id: &str) -> Result<(), String> {
    let settings = self.load_settings()?;
    if !settings.enable_link_preview {
        return Err("URL 联网预览未开启".to_string());
    }
    let url = self.storage.lock().map_err(|err| err.to_string())?.get_url(id)?
        .ok_or_else(|| "该记录不是 URL".to_string())?;
    let preview = link_preview::fetch_preview(&url)?;
    self.storage.lock().map_err(|err| err.to_string())?.update_link_preview(id, preview.title.as_deref(), preview.description.as_deref())
}
```

- [ ] **Step 4: 修改 retry_link_preview 命令**

替换命令实现：

```rust
#[tauri::command]
pub fn retry_link_preview(id: String, service: State<'_, ClipboardService>) -> Result<(), String> {
    service.retry_link_preview(&id)
}
```

- [ ] **Step 5: 运行测试和检查**

Run:

```bash
cd file-keeper/src-tauri && cargo test clipboard::link_preview && cargo check
```

Expected: PASS。

- [ ] **Step 6: 手动验证隐私开关**

Manual expected:

1. 设置中 URL 联网预览关闭。
2. 对 URL 记录点击重试预览。
3. 应提示“URL 联网预览未开启”。
4. 开启 URL 联网预览。
5. 再次重试，成功时显示标题或失败时显示预览不可用，不影响 URL 原记录。

- [ ] **Step 7: 提交 Task 18**

```bash
git add file-keeper/src-tauri/Cargo.toml file-keeper/src-tauri/Cargo.lock file-keeper/src-tauri/src/clipboard/link_preview.rs file-keeper/src-tauri/src/clipboard/mod.rs file-keeper/src-tauri/src/clipboard/storage.rs file-keeper/src-tauri/src/clipboard/mod.rs file-keeper/src-tauri/src/commands/clipboard.rs
git commit -m "feat: add clipboard link preview retry"
```

---

### Task 19: 存储用量统计和清理命令

**Files:**
- Modify: `file-keeper/src-tauri/src/clipboard/storage.rs`
- Modify: `file-keeper/src-tauri/src/clipboard/mod.rs`
- Modify: `file-keeper/src-tauri/src/commands/clipboard.rs`
- Modify: `file-keeper/src/components/ClipboardStorageUsage.vue`

- [ ] **Step 1: storage 增加用量统计**

在 `ClipboardStorage` impl 新增：

```rust
pub fn storage_usage_by_kind(&self) -> Result<Vec<(String, i64)>, String> {
    let mut statement = self.connection.prepare(
        "SELECT kind, COALESCE(SUM(cache_bytes), 0) FROM clipboard_items GROUP BY kind"
    ).map_err(|err| err.to_string())?;
    let rows = statement.query_map([], |row| Ok((row.get::<_, String>(0)?, row.get::<_, i64>(1)?)))
        .map_err(|err| err.to_string())?;
    rows.collect::<Result<Vec<_>, _>>().map_err(|err| err.to_string())
}

pub fn clear_history(&self, scope: &str) -> Result<(), String> {
    match scope {
        "non_text_cache" => {
            self.connection.execute(
                "UPDATE clipboard_items SET cache_bytes = 0, cache_state = 'cleaned', thumbnail_path = NULL WHERE kind != 'text'",
                [],
            ).map_err(|err| err.to_string())?;
        }
        "security_events" => {
            self.connection.execute("DELETE FROM clipboard_items WHERE kind = 'security_event'", [])
                .map_err(|err| err.to_string())?;
        }
        _ => {
            self.connection.execute("DELETE FROM clipboard_items", [])
                .map_err(|err| err.to_string())?;
        }
    }
    Ok(())
}
```

在 `ClipboardService` 新增：

```rust
pub fn storage_usage(&self) -> Result<Vec<(String, i64)>, String> {
    self.storage.lock().map_err(|err| err.to_string())?.storage_usage_by_kind()
}

pub fn clear_history(&self, scope: &str) -> Result<(), String> {
    self.storage.lock().map_err(|err| err.to_string())?.clear_history(scope)
}
```

- [ ] **Step 2: 修改 commands 用量和清理实现**

修改 `get_clipboard_storage_usage`：

```rust
#[tauri::command]
pub fn get_clipboard_storage_usage(service: State<'_, ClipboardService>) -> Result<ClipboardStorageUsage, String> {
    let settings = service.load_settings()?;
    let usage = service.storage_usage()?;
    let total_bytes = usage.iter().map(|(_, bytes)| *bytes).sum();
    Ok(ClipboardStorageUsage {
        total_bytes,
        limit_bytes: settings.total_non_text_limit_mb * 1024 * 1024,
        by_type: usage.into_iter().map(|(kind, bytes)| ClipboardStorageTypeUsage {
            kind,
            bytes,
            limit_bytes: None,
        }).collect(),
    })
}
```

修改 `clear_clipboard_history`：

```rust
#[tauri::command]
pub fn clear_clipboard_history(scope: String, service: State<'_, ClipboardService>) -> Result<(), String> {
    service.clear_history(&scope)
}
```

- [ ] **Step 3: 前端空间组件增加清理按钮**

修改 `ClipboardStorageUsage.vue` template 末尾：

```vue
<button class="mt-3 w-full rounded bg-gray-100 px-3 py-1.5 text-xs dark:bg-dark-hover" @click="$emit('clearCache')">
  清理非文本缓存
</button>
```

添加 emit：

```ts
defineEmits<{
  clearCache: []
}>()
```

修改 `ClipboardManagement.vue`：

```vue
<ClipboardStorageUsage
  :usage="clipboardStore.storageUsage"
  @clear-cache="async () => { await clearClipboardHistory('non_text_cache'); await clipboardStore.refreshStorageUsage(); await clipboardStore.loadItems() }"
/>
```

需要 import：

```ts
import { clearClipboardHistory } from '../api/clipboard'
```

- [ ] **Step 4: 运行检查**

Run:

```bash
cd file-keeper/src-tauri && cargo check
cd ../.. && npm run build
```

Expected: PASS。

- [ ] **Step 5: 提交 Task 19**

```bash
git add file-keeper/src-tauri/src/clipboard/storage.rs file-keeper/src-tauri/src/clipboard/mod.rs file-keeper/src-tauri/src/commands/clipboard.rs file-keeper/src/components/ClipboardStorageUsage.vue file-keeper/src/components/ClipboardManagement.vue
git commit -m "feat: add clipboard storage cleanup"
```

---

### Task 20: 主应用联调和完整自动化测试

**Files:**
- Modify: `file-keeper/src/App.vue`
- Modify: `file-keeper/src/components/ClipboardManagement.vue`
- Modify: `file-keeper/src/components/ClipboardQuickPanel.vue`
- Modify: `file-keeper/src/stores/clipboardStore.ts`
- Test: `file-keeper/src/api/__tests__/clipboard.test.ts`
- Test: `file-keeper/src/stores/__tests__/clipboardStore.test.ts`
- Test: `file-keeper/src/components/__tests__/clipboardComponents.test.ts`
- Test: `file-keeper/src/components/__tests__/clipboardQuickPanel.test.ts`

- [ ] **Step 1: 补齐端到端前端联调测试**

修改 `file-keeper/src/components/__tests__/clipboardComponents.test.ts`，保留已有测试并追加：

```ts
it('renders management page with list, preview, settings, and storage usage together', async () => {
  const wrapper = mount(ClipboardManagement, {
    global: {
      plugins: [createTestingPinia({ stubActions: false })],
      stubs: {
        ClipboardToolbar: false,
        ClipboardList: false,
        ClipboardPreview: false,
        ClipboardSettings: false,
        ClipboardStorageUsage: false,
        ClipboardSecurityEvents: false
      }
    }
  })

  expect(wrapper.text()).toContain('剪贴板')
  expect(wrapper.text()).toContain('搜索')
  expect(wrapper.text()).toContain('空间')
})
```

修改 `file-keeper/src/components/__tests__/clipboardQuickPanel.test.ts`，保留已有测试并追加：

```ts
it('closes quick panel after successful paste', async () => {
  const wrapper = mount(ClipboardQuickPanel, {
    global: {
      plugins: [createTestingPinia({ stubActions: false })]
    }
  })
  const store = useClipboardStore()
  store.items = [
    {
      id: 'item-1',
      kind: 'text',
      title: 'hello',
      preview: 'hello',
      sourceApp: null,
      sourceWindow: null,
      createdAt: '2026-05-29T00:00:00Z',
      isFavorite: false,
      sizeBytes: 5,
      availableFormats: ['original', 'plain_text']
    }
  ]
  store.isQuickPanelOpen = true
  vi.spyOn(store, 'pasteItem').mockResolvedValue(undefined)

  await wrapper.find('input').trigger('keydown.enter')

  expect(store.pasteItem).toHaveBeenCalledWith('item-1', 'original')
  expect(store.isQuickPanelOpen).toBe(false)
})
```

- [ ] **Step 2: 运行前端测试确认通过**

Run:

```bash
cd file-keeper && npm run test -- src/api/__tests__/clipboard.test.ts src/stores/__tests__/clipboardStore.test.ts src/components/__tests__/clipboardComponents.test.ts src/components/__tests__/clipboardQuickPanel.test.ts
```

Expected: PASS。

- [ ] **Step 3: 运行 Rust 剪贴板测试确认通过**

Run:

```bash
cd file-keeper/src-tauri && cargo test clipboard
```

Expected: PASS。

- [ ] **Step 4: 运行完整构建检查**

Run:

```bash
cd file-keeper && npm run build
cd src-tauri && cargo check
```

Expected: PASS。

- [ ] **Step 5: 启动 Tauri 开发环境做真实 UI 联调**

Run:

```bash
cd file-keeper && npm run tauri:dev
```

Expected: 应用启动成功，主窗口可以切换到“剪贴板”标签页，快捷键可以呼出快捷面板。

- [ ] **Step 6: 提交 Task 20**

```bash
git add file-keeper/src/App.vue file-keeper/src/components/ClipboardManagement.vue file-keeper/src/components/ClipboardQuickPanel.vue file-keeper/src/stores/clipboardStore.ts file-keeper/src/api/__tests__/clipboard.test.ts file-keeper/src/stores/__tests__/clipboardStore.test.ts file-keeper/src/components/__tests__/clipboardComponents.test.ts file-keeper/src/components/__tests__/clipboardQuickPanel.test.ts
git commit -m "test: add clipboard integration coverage"
```

---

### Task 21: 手动验证指南和用户可见文案

**Files:**
- Modify: `file-keeper/docs/manual-testing-guide.md`
- Modify: `file-keeper/src/locales/zh-CN.ts`
- Modify: `file-keeper/src/locales/en.ts`
- Modify: `file-keeper/src/components/ClipboardSettings.vue`

- [ ] **Step 1: 补充中文隐私提示文案**

修改 `file-keeper/src/locales/zh-CN.ts` 的 `clipboard` namespace，确保包含：

```ts
privacy: {
  sensitiveProtection: '默认不会保存疑似密码、令牌、银行卡、私钥等敏感内容。',
  linkPreview: '链接预览会联网读取网页标题和描述，开启前请确认你愿意访问这些链接。',
  autoPaste: '自动粘贴会尝试切回原窗口并模拟 Ctrl+V；如果失败，内容仍会复制到剪贴板。',
  fileCopy: '文件历史默认保存副本，并受到空间上限和后缀规则限制。'
}
```

- [ ] **Step 2: 补充英文隐私提示文案**

修改 `file-keeper/src/locales/en.ts` 的 `clipboard` namespace，确保包含：

```ts
privacy: {
  sensitiveProtection: 'Suspected passwords, tokens, bank cards, and private keys are not saved by default.',
  linkPreview: 'Link previews fetch page titles and descriptions from the network. Enable this only if you want these links to be visited.',
  autoPaste: 'Auto paste tries to restore the target window and send Ctrl+V. If it fails, the content remains copied to the clipboard.',
  fileCopy: 'File history stores copies by default and is limited by storage and extension rules.'
}
```

- [ ] **Step 3: 在设置页显示隐私提示**

修改 `file-keeper/src/components/ClipboardSettings.vue` 中对应设置块：

```vue
<p class="text-xs text-gray-500 dark:text-gray-400">
  {{ t('clipboard.privacy.sensitiveProtection') }}
</p>
<p class="text-xs text-gray-500 dark:text-gray-400">
  {{ t('clipboard.privacy.linkPreview') }}
</p>
<p class="text-xs text-gray-500 dark:text-gray-400">
  {{ t('clipboard.privacy.autoPaste') }}
</p>
<p class="text-xs text-gray-500 dark:text-gray-400">
  {{ t('clipboard.privacy.fileCopy') }}
</p>
```

- [ ] **Step 4: 更新手动验证指南**

在 `file-keeper/docs/manual-testing-guide.md` 新增章节：

```markdown
## 剪贴板强化功能手动验证

### 基础历史

1. 启动应用：`npm run tauri:dev`。
2. 复制一段普通文本。
3. 打开“剪贴板”标签页。
4. 确认新文本出现在历史列表中。
5. 点击该记录，确认右侧预览显示完整内容。
6. 点击“复制”，确认系统剪贴板恢复该内容。

### 快捷面板

1. 在任意输入框中复制三段不同文本。
2. 按剪贴板快捷键，默认 `Ctrl+Shift+V`。
3. 输入关键词搜索。
4. 使用上下方向键移动选择。
5. 按 Enter。
6. 如果未开启自动粘贴，确认内容已复制到系统剪贴板。
7. 如果开启自动粘贴，确认内容粘贴到原输入框；失败时确认有“已复制，请手动粘贴”的反馈。

### 安全防护

1. 复制 `4111 1111 1111 1111`。
2. 确认不会出现明文银行卡号历史。
3. 确认安全事件列表出现拦截记录。
4. 复制 JWT、私钥片段或高熵 token。
5. 确认这些内容不进入普通搜索结果。

### 文件和图片

1. 复制允许后缀的文件。
2. 确认历史中出现文件记录并显示大小。
3. 设置后缀 allow list 后复制不允许的后缀文件。
4. 确认该文件不被保存为历史副本。
5. 复制图片或截图。
6. 确认历史中出现图片缩略图和尺寸。

### 存储清理

1. 将非文本缓存空间上限设置为较小值。
2. 复制多个文件或图片。
3. 确认空间占用展示会更新。
4. 点击“清理非文本缓存”。
5. 确认文本历史保留，文件/图片缓存被清理或标记不可用。

### 链接预览

1. 确认默认关闭链接预览。
2. 复制 URL，确认不会联网抓取标题。
3. 开启链接预览后重新复制 URL。
4. 确认能显示标题或失败状态。
5. 点击重试，确认状态更新。
```

- [ ] **Step 5: 运行文案和构建检查**

Run:

```bash
cd file-keeper && npm run build
```

Expected: PASS。

- [ ] **Step 6: 提交 Task 21**

```bash
git add file-keeper/docs/manual-testing-guide.md file-keeper/src/locales/zh-CN.ts file-keeper/src/locales/en.ts file-keeper/src/components/ClipboardSettings.vue
git commit -m "docs: add clipboard manual testing guide"
```

---

### Task 22: 最终验收、回归和发布前检查

**Files:**
- Modify: `file-keeper/docs/manual-testing-guide.md`
- No code changes unless a check fails.

- [ ] **Step 1: 运行完整前端测试**

Run:

```bash
cd file-keeper && npm run test
```

Expected: PASS。

- [ ] **Step 2: 运行前端生产构建**

Run:

```bash
cd file-keeper && npm run build
```

Expected: PASS。

- [ ] **Step 3: 运行 Rust 完整测试**

Run:

```bash
cd file-keeper/src-tauri && cargo test
```

Expected: PASS。

- [ ] **Step 4: 运行 Rust 编译检查**

Run:

```bash
cd file-keeper/src-tauri && cargo check
```

Expected: PASS。

- [ ] **Step 5: 执行主窗口手动回归**

Run:

```bash
cd file-keeper && npm run tauri:dev
```

手动确认：

```text
1. 文件管理标签页仍能打开并显示原有文件功能。
2. 进程管理标签页仍能刷新、筛选、排序和显示进程详情。
3. 剪贴板标签页能搜索、筛选、预览、复制、删除历史。
4. 设置窗口能分别保存主窗口快捷键和剪贴板面板快捷键。
5. 主题、语言、托盘、最小化行为没有回归。
```

- [ ] **Step 6: 执行快捷面板手动回归**

在 Tauri dev 应用运行时确认：

```text
1. 复制普通文本后，快捷面板能显示新记录。
2. 快捷键能呼出快捷面板。
3. Esc 能关闭快捷面板。
4. 上下方向键能移动选中项。
5. Enter 默认恢复原格式。
6. Shift+Enter 使用纯文本。
7. 自动粘贴关闭时只复制回系统剪贴板。
8. 自动粘贴开启时优先粘贴到原窗口，失败时提示手动粘贴。
```

- [ ] **Step 7: 执行安全和隐私手动回归**

在 Tauri dev 应用运行时确认：

```text
1. 银行卡号不会保存为普通历史。
2. JWT 不会保存为普通历史。
3. 私钥片段不会保存为普通历史。
4. 高熵 token 不会保存为普通历史。
5. 被排除应用的复制内容不会保存为普通历史。
6. URL 预览默认不会联网。
7. 只有开启 URL 预览后才允许抓取标题和描述。
```

- [ ] **Step 8: 记录最终验证结果**

在 `file-keeper/docs/manual-testing-guide.md` 的剪贴板章节末尾新增本次验证记录模板：

```markdown
### 本次验证记录

- 日期：2026-05-29
- 平台：Windows 11
- 前端测试：通过
- 前端构建：通过
- Rust 测试：通过
- Rust 检查：通过
- 主窗口手动回归：通过
- 快捷面板手动回归：通过
- 安全隐私手动回归：通过
```

- [ ] **Step 9: 提交 Task 22**

```bash
git add file-keeper/docs/manual-testing-guide.md
git commit -m "test: record clipboard validation checklist"
```

---

## 计划自查结果

### 规格覆盖

- 本地增强范围：Task 1-22 覆盖文本、图片、文件、HTML、URL、颜色、安全、历史、快捷面板、设置和清理。
- 不做云功能：计划没有同步、账号、云存储、跨设备能力。
- Windows 第一阶段：Task 10-12 覆盖 Windows 剪贴板和自动粘贴；macOS 仅保留接口骨架。
- 默认强防护：Task 2、Task 5、Task 22 覆盖敏感检测、安全事件和回归。
- 默认复制回剪贴板、可选自动粘贴：Task 8、Task 12、Task 22 覆盖。
- 可配置存储和后缀规则：Task 3、Task 13、Task 14、Task 19 覆盖。
- 快捷面板 + 主应用管理页：Task 7-9 覆盖。
- 链接预览默认关闭：Task 18、Task 21、Task 22 覆盖。

### 模糊项处理

- OCR 首版按可替换接口实现：Task 17 明确 `DisabledOcrEngine`、测试写入命令和搜索路径。
- URL 预览按开关实现：Task 18 明确默认不联网、开启后才抓取。
- 自动粘贴失败降级：Task 12 和 Task 22 明确复制回剪贴板作为兜底。

### 类型一致性

- 前端 `ClipboardPasteFormat` 使用 snake_case 字符串；Rust enum 通过 serde rename_all 对齐。
- 前端 `ClipboardKind` 与 Rust `ClipboardKind` 对齐。
- 前端 `ClipboardSettings` camelCase 通过 Tauri serde camelCase 参数传递，Rust 结构体使用 snake_case 字段并通过 serde rename_all 对齐。
- `ClipboardStorageUsage` 前端使用 `totalBytes` / `limitBytes` / `byType`，Rust 结构体通过 serde camelCase 输出。
