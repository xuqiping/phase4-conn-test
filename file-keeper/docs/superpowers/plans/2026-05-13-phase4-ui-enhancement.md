# Phase 4: UI Enhancement Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Enhance File Keeper's UI with real file icons, drag-reorder, recent files access, and system theme following.

**Architecture:** Add Rust backend icon extraction (using platform APIs), extend FileItem with orderIndex field for sorting persistence, add recentFiles store that tracks opens, and enhance theme system to follow OS preference.

**Tech Stack:** Vue 3, Pinia, Tailwind CSS, Tauri 2 (Rust side), @vueuse/core for drag-and-drop (useSortable) and theme detection (usePreferredDark, useMediaControls).

---

## File Structure Changes

**New files:**
- `src-tauri/src/commands/icons.rs` - Icon extraction Tauri commands
- `src/api/icons.ts` - Frontend API for icon extraction
- `src/stores/recentStore.ts` - Store for recently opened files
- `src/components/RecentFiles.vue` - Quick access dropdown/panel
- `src/composables/useSortableFiles.ts` - Drag-reorder logic with persistence

**Modified files:**
- `src/types/file.ts` - Add `orderIndex: number` to FileItem
- `src/stores/fileStore.ts` - Add `updateOrder` action, sort files by orderIndex
- `src/stores/settingsStore.ts` - Add `followSystemTheme` setting
- `src/App.vue` - Integrate RecentFiles component, theme system detection
- `src/components/FileCard.vue` - Add drag handle or make card draggable
- `src-tauri/src/main.rs` - Register icon commands
- `src-tauri/Cargo.toml` - Add platform-specific icon dependencies

---

## Task 1: File Icon Extraction Backend (Rust)

**Goal:** Extract real file icons from the operating system and return them as base64 data URLs.

**Approach:** Use Windows `SHGetFileInfoW`, macOS `NSWorkspace` (via Objective-C or swift-rs), Linux `gio` or fallback to extension-based icons.

**Files:**
- Create: `src-tauri/src/commands/icons.rs`
- Modify: `src-tauri/src/commands/mod.rs`
- Modify: `src-tauri/src/main.rs`
- Modify: `src-tauri/Cargo.toml`

- [ ] **Step 1: Add dependencies to Cargo.toml**

```toml
# Under [dependencies]
# Windows icon extraction
[target.'cfg(windows)'.dependencies]
windows = { version = "0.58", features = ["Win32_UI_Shell", "Win32_Graphics_Gdi"] }

# macOS icon extraction (using Objective-C runtime)
[target.'cfg(target_os = "macos")'.dependencies]
objc = "0.2"
core-foundation = "0.9"

# Linux: use existing sysinfo for fallback, no extra deps needed
```

- [ ] **Step 2: Create icons.rs with platform modules**

```rust
// src-tauri/src/commands/icons.rs
use tauri::command;

#[cfg(windows)]
mod windows_impl {
    use windows::Win32::UI::Shell::{SHGetFileInfoW, SHGFI_ICON, SHGFI_SMALLICON, SHGFI_USEFILEATTRIBUTES};
    use windows::Win32::UI::WindowsAndMessaging::{DestroyIcon, HICON};
    use windows::core::PWSTR;
    use std::ptr;
    use base64::{engine::general_purpose::STANDARD, Engine};

    pub fn extract_icon(path: &str) -> Option<String> {
        let wide_path: Vec<u16> = path.encode_utf16().chain(Some(0)).collect();
        let mut info = std::mem::zeroed();
        unsafe {
            let hicon = SHGetFileInfoW(
                PWSTR(wide_path.as_ptr() as *mut u16),
                0x80, // FILE_ATTRIBUTE_NORMAL
                &mut info,
                std::mem::size_of_val(&info) as u32,
                SHGFI_ICON | SHGFI_SMALLICON | SHGFI_USEFILEATTRIBUTES,
            );
            if hicon != 0 {
                // Get icon data as PNG via HICON -> bitmap -> PNG
                // Simplified: for MVP, just return empty and use extension fallback
                // Full implementation would require bitmap conversion.
                DestroyIcon(hicon);
            }
        }
        None // Fallback to extension-based
    }
}

#[cfg(target_os = "macos")]
mod macos_impl {
    pub fn extract_icon(path: &str) -> Option<String> {
        // Use NSWorkspace to get icon as TIFF, then convert to base64 PNG
        // For MVP, return None and rely on extension fallback
        None
    }
}

#[cfg(target_os = "linux")]
mod linux_impl {
    pub fn extract_icon(path: &str) -> Option<String> {
        // Use `gio info` or fallback
        None
    }
}

#[command]
pub async fn get_file_icon(path: String) -> Result<String, String> {
    #[cfg(windows)]
    let icon_data = windows_impl::extract_icon(&path);
    #[cfg(target_os = "macos")]
    let icon_data = macos_impl::extract_icon(&path);
    #[cfg(target_os = "linux")]
    let icon_data = linux_impl::extract_icon(&path);
    
    match icon_data {
        Some(data) => Ok(data),
        None => Ok(String::new()), // Empty means use extension fallback
    }
}

// Fallback function for extension-based icons (already exists in file.ts)
```

- [ ] **Step 3: Register command in mod.rs and main.rs**

```rust
// src-tauri/src/commands/mod.rs
pub mod files;
pub mod processes;
pub mod icons;  // Add this

// src-tauri/src/main.rs - add to generate_handler!
.invoke_handler(tauri::generate_handler![
    open_file,
    validate_path,
    show_in_folder,
    find_file_processes,
    close_process,
    close_file_processes,
    get_file_icon  // Add this
])
```

- [ ] **Step 4: Commit**

```bash
cd "c:\AI Projects\file-keeper"
git add src-tauri/src/commands/icons.rs src-tauri/src/commands/mod.rs src-tauri/src/main.rs src-tauri/Cargo.toml
git commit -m "feat: add backend icon extraction scaffolding (platform-specific)"
```

---

## Task 2: File Icon Extraction Frontend Integration

**Goal:** Call Rust icon extraction API and cache results in FileItem.

**Files:**
- Create: `src/api/icons.ts`
- Modify: `src/types/file.ts`
- Modify: `src/stores/fileStore.ts`
- Modify: `src/components/FileCard.vue`

- [ ] **Step 1: Create icons API wrapper**

```typescript
// src/api/icons.ts
import { invoke } from '@tauri-apps/api/core'

export async function getFileIcon(filePath: string): Promise<string | null> {
  try {
    const iconData = await invoke<string>('get_file_icon', { path: filePath })
    return iconData || null
  } catch (error) {
    console.error('Failed to get icon for', filePath, error)
    return null
  }
}
```

- [ ] **Step 2: Add icon field to FileItem type (already exists: `icon?: string`), ensure it's used**

```typescript
// src/types/file.ts - already has icon?: string
// No changes needed
```

- [ ] **Step 3: Add icon loading to fileStore when adding files**

```typescript
// src/stores/fileStore.ts - inside addFile action
import { getFileIcon } from '../api/icons'

async function addFile(filePath: string, groupId?: string) {
  // ... existing duplicate check ...
  const iconData = await getFileIcon(filePath)
  const newFile: FileItem = {
    id: uuidv4(),
    name: path.basename(filePath),
    path: filePath,
    type: 'file',
    icon: iconData || deriveIconFromExt(filePath), // fallback to extension-based
    tags: [],
    groupId: groupId || groupStore.currentGroupId,
    createdAt: Date.now(),
    openCount: 0
  }
  // ...
}
```

- [ ] **Step 4: Update FileCard to prioritize real icon over extension-based**

```vue
<!-- src/components/FileCard.vue - within template -->
<div class="file-icon">
  <img v-if="file.icon && file.icon.startsWith('data:image')" :src="file.icon" class="w-8 h-8 object-contain" />
  <component v-else :is="getIconComponent(file.icon || deriveIconFromExt(file.name))" :size="32" />
</div>
```

- [ ] **Step 5: Add icon refresh on edit (optional, later)**

- [ ] **Step 6: Commit**

```bash
git add src/api/icons.ts src/stores/fileStore.ts src/components/FileCard.vue
git commit -m "feat: integrate real file icons from system"
```

---

## Task 3: Drag-and-Drop Reorder with Persistence

**Goal:** Allow users to reorder files by dragging cards, save order to store and persist.

**Approach:** Use `@vueuse/core` useSortable composable with custom orderIndex field.

**Files:**
- Create: `src/composables/useSortableFiles.ts`
- Modify: `src/types/file.ts` (add `orderIndex: number`)
- Modify: `src/stores/fileStore.ts` (add `updateOrder` and sort by orderIndex)
- Modify: `src/App.vue` (apply useSortable to file grid container)
- Modify: `src/components/FileCard.vue` (add drag handle or make draggable)

- [ ] **Step 1: Add orderIndex to FileItem type**

```typescript
// src/types/file.ts
export interface FileItem {
  // ... existing fields
  orderIndex: number  // Default: index in array
}
```

- [ ] **Step 2: Update fileStore to sort by orderIndex and add updateOrder action**

```typescript
// src/stores/fileStore.ts
// In state: make sure existing files get orderIndex based on current order
function initOrderIndices() {
  files.value.forEach((file, idx) => {
    if (file.orderIndex === undefined) {
      file.orderIndex = idx
    }
  })
}

// Getter: filteredFiles sorted by orderIndex
const filteredFiles = computed(() => {
  let result = files.value
    .filter(f => f.groupId === groupStore.currentGroupId || groupStore.currentGroupId === 'all')
    .sort((a, b) => (a.orderIndex ?? 0) - (b.orderIndex ?? 0))
  // ... rest of filtering (search, tags)
  return result
})

// Action: update order after drag
function updateOrder(orderedIds: string[]) {
  const newOrderMap = new Map(orderedIds.map((id, idx) => [id, idx]))
  files.value.forEach(file => {
    if (newOrderMap.has(file.id)) {
      file.orderIndex = newOrderMap.get(file.id)!
    }
  })
  // persist will happen automatically via store subscription
}
```

- [ ] **Step 3: Create useSortableFiles composable**

```typescript
// src/composables/useSortableFiles.ts
import { useSortable } from '@vueuse/core'
import { ref } from 'vue'
import { useFileStore } from '@/stores/fileStore'

export function useSortableFiles(containerRef: Ref<HTMLElement | null>) {
  const fileStore = useFileStore()
  const currentOrder = ref<string[]>([])

  const sortable = useSortable(containerRef, {
    animation: 150,
    onEnd: (evt) => {
      if (evt.oldIndex !== undefined && evt.newIndex !== undefined) {
        // Get current filtered file IDs in order
        const fileIds = fileStore.filteredFiles.map(f => f.id)
        const newOrder = [...fileIds]
        const [moved] = newOrder.splice(evt.oldIndex, 1)
        newOrder.splice(evt.newIndex, 0, moved)
        fileStore.updateOrder(newOrder)
      }
    }
  })

  return { sortable }
}
```

- [ ] **Step 4: Apply composable in App.vue**

```vue
<!-- src/App.vue -->
<template>
  <div ref="fileGridContainer" class="grid grid-cols-1 sm:grid-cols-2 md:grid-cols-3 lg:grid-cols-4 xl:grid-cols-5 gap-4 p-4">
    <FileCard v-for="file in fileStore.filteredFiles" :key="file.id" :file="file" />
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { useSortableFiles } from './composables/useSortableFiles'

const fileGridContainer = ref<HTMLElement | null>(null)
useSortableFiles(fileGridContainer)
</script>
```

- [ ] **Step 5: Update existing files migration (add default orderIndex values)**

- [ ] **Step 6: Commit**

```bash
git add src/types/file.ts src/stores/fileStore.ts src/composables/useSortableFiles.ts src/App.vue
git commit -m "feat: add drag-reorder with orderIndex persistence"
```

---

## Task 4: Recent Files Store and Quick Access

**Goal:** Track recently opened files and provide a quick access UI.

**Files:**
- Create: `src/stores/recentStore.ts`
- Create: `src/components/RecentFiles.vue`
- Modify: `src/App.vue` (add RecentFiles dropdown in toolbar)

- [ ] **Step 1: Create recentStore**

```typescript
// src/stores/recentStore.ts
import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { FileItem } from '@/types/file'

export const useRecentStore = defineStore('recent', () => {
  const recentFiles = ref<FileItem[]>([])
  const MAX_RECENT = 10

  function addRecent(file: FileItem) {
    // Remove if already exists
    const existingIndex = recentFiles.value.findIndex(f => f.id === file.id)
    if (existingIndex !== -1) {
      recentFiles.value.splice(existingIndex, 1)
    }
    // Add to front
    recentFiles.value.unshift({ ...file })
    // Trim
    if (recentFiles.value.length > MAX_RECENT) {
      recentFiles.value.pop()
    }
  }

  function clearRecents() {
    recentFiles.value = []
  }

  return { recentFiles, addRecent, clearRecents }
}, {
  persist: {
    key: 'recent',
    paths: ['recentFiles']
  }
})
```

- [ ] **Step 2: Integrate recent store into file open action**

```typescript
// src/stores/fileStore.ts - inside openFile action
import { useRecentStore } from './recentStore'

async function openFile(id: string) {
  const file = files.value.find(f => f.id === id)
  if (!file) return
  // ... existing open logic
  const recentStore = useRecentStore()
  recentStore.addRecent(file)
}
```

- [ ] **Step 3: Create RecentFiles.vue component (dropdown)**

```vue
<!-- src/components/RecentFiles.vue -->
<template>
  <div class="relative">
    <button
      @click="showDropdown = !showDropdown"
      class="p-2 rounded-md bg-gray-100 dark:bg-dark-hover hover:bg-gray-200 dark:hover:bg-[#383838] transition-colors"
      title="最近打开"
    >
      <Clock :size="18" />
    </button>
    <transition name="fade">
      <div
        v-if="showDropdown"
        class="absolute right-0 mt-2 w-80 bg-white dark:bg-dark-panel rounded-xl shadow-2xl border border-gray-200 dark:border-dark-border z-50 overflow-hidden"
      >
        <div class="px-4 py-2 border-b border-gray-200 dark:border-dark-border font-medium text-sm">
          最近打开
        </div>
        <div class="max-h-96 overflow-y-auto">
          <div
            v-for="file in recentStore.recentFiles"
            :key="file.id"
            @click="openFile(file.id)"
            class="flex items-center gap-3 px-4 py-2 hover:bg-gray-100 dark:hover:bg-dark-hover cursor-pointer transition-colors"
          >
            <FileIcon :file="file" :size="20" />
            <div class="flex-1 min-w-0">
              <div class="text-sm truncate">{{ file.name }}</div>
              <div class="text-xs text-gray-500 truncate">{{ file.path }}</div>
            </div>
          </div>
          <div v-if="recentStore.recentFiles.length === 0" class="px-4 py-8 text-center text-gray-500 text-sm">
            暂无最近打开的文件
          </div>
        </div>
        <div class="px-4 py-2 border-t border-gray-200 dark:border-dark-border text-right">
          <button
            @click="recentStore.clearRecents()"
            class="text-xs text-gray-500 hover:text-red-500 transition-colors"
          >
            清空历史
          </button>
        </div>
      </div>
    </transition>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { Clock } from 'lucide-vue-next'
import { useRecentStore } from '@/stores/recentStore'
import { useFileStore } from '@/stores/fileStore'
import FileIcon from './FileIcon.vue' // Or use existing icon logic

const recentStore = useRecentStore()
const fileStore = useFileStore()
const showDropdown = ref(false)

function openFile(id: string) {
  fileStore.openFile(id)
  showDropdown.value = false
}
</script>
```

- [ ] **Step 4: Add RecentFiles component to App.vue toolbar**

```vue
<!-- src/App.vue - inside toolbar div -->
<div class="flex items-center space-x-3">
  <RecentFiles />
  <AddFileButton />
  <!-- ... existing buttons -->
</div>
```

- [ ] **Step 5: Commit**

```bash
git add src/stores/recentStore.ts src/components/RecentFiles.vue src/App.vue src/stores/fileStore.ts
git commit -m "feat: add recent files tracking and quick access dropdown"
```

---

## Task 5: Theme System Follow System Preference

**Goal:** Add option to follow OS theme (auto) and improve theme switching.

**Files:**
- Modify: `src/types/settings.ts`
- Modify: `src/stores/settingsStore.ts`
- Modify: `src/App.vue`
- Modify: `src/components/SettingsDialog.vue`

- [ ] **Step 1: Update Settings type**

```typescript
// src/types/settings.ts
export interface Settings {
  theme: 'light' | 'dark' | 'auto'  // 'auto' means follow system
  // ... other fields
}
```

- [ ] **Step 2: Update settingsStore to handle system theme detection**

```typescript
// src/stores/settingsStore.ts
import { ref, watch } from 'vue'
import { usePreferredDark } from '@vueuse/core'

export const useSettingsStore = defineStore('settings', () => {
  const settings = ref<Settings>({
    theme: 'dark', // default
    // ... other defaults
  })
  
  const isSystemDark = usePreferredDark()
  const effectiveTheme = computed(() => {
    if (settings.value.theme === 'auto') {
      return isSystemDark.value ? 'dark' : 'light'
    }
    return settings.value.theme
  })

  function setTheme(theme: 'light' | 'dark' | 'auto') {
    settings.value.theme = theme
  }

  return { settings, effectiveTheme, setTheme, ... }
})
```

- [ ] **Step 3: Update App.vue to use effectiveTheme**

```vue
<!-- src/App.vue -->
<script setup>
import { useSettingsStore } from './stores/settingsStore'
const settingsStore = useSettingsStore()
const currentTheme = computed(() => settingsStore.effectiveTheme)
</script>

<template>
  <div :class="currentTheme === 'dark' ? 'dark bg-dark-bg text-gray-200' : 'bg-gray-50 text-gray-800'">
    <!-- ... -->
  </div>
</template>
```

- [ ] **Step 4: Update SettingsDialog to add "跟随系统" option**

```vue
<!-- src/components/SettingsDialog.vue - inside theme section -->
<div class="flex space-x-2">
  <button @click="localTheme = 'light'" :class="[...]">浅色</button>
  <button @click="localTheme = 'dark'" :class="[...]">深色</button>
  <button @click="localTheme = 'auto'" :class="[...]">跟随系统</button>
</div>
```

- [ ] **Step 5: Add watch to update root class when system changes while auto mode**

```typescript
// In settingsStore
watch(isSystemDark, () => {
  // Trigger reactive update when system changes
  // effectiveTheme already recomputes
})
```

- [ ] **Step 6: Commit**

```bash
git add src/types/settings.ts src/stores/settingsStore.ts src/App.vue src/components/SettingsDialog.vue
git commit -m "feat: add system theme following (auto mode)"
```

---

## Task 6: Testing & Polish

**Goal:** Ensure all Phase 4 features work correctly.

- [ ] **Step 1: Run unit tests (existing)**

```bash
cd "c:\AI Projects\file-keeper"
npm run test
```

- [ ] **Step 2: Manual test checklist**

Test icon extraction:
- [ ] Add a .docx file - should show Word icon
- [ ] Add a .png file - should show image icon
- [ ] Add a folder - should show folder icon
- [ ] Check console for any errors

Test drag-reorder:
- [ ] Drag a file card to new position
- [ ] Verify position persists after refresh
- [ ] Verify sorting works within groups
- [ ] Verify order persists across app restarts

Test recent files:
- [ ] Open 3-5 files
- [ ] Click clock icon in toolbar - dropdown shows recent files
- [ ] Click on a recent file - it opens
- [ ] Click "清空历史" - list clears
- [ ] Verify recents persist after restart

Test theme following:
- [ ] Change system theme (Windows: Settings > Personalization > Colors)
- [ ] Set app theme to "跟随系统"
- [ ] Verify app theme changes automatically
- [ ] Switch to manual light/dark - should override system
- [ ] Switch back to "跟随系统" - should follow system again

- [ ] **Step 3: Commit any fixes**

```bash
git add .
git commit -m "fix: resolve Phase 4 testing issues"
```

---

## Completion Verification Checklist

- [ ] Icon extraction works on Windows (actual icons shown)
- [ ] Icon extraction falls back to extension-based when system icon not available
- [ ] Drag-reorder works smoothly with animation
- [ ] Order persists after page reload and app restart
- [ ] Recent files dropdown shows correctly opened files
- [ ] Recent files persist after restart
- [ ] System theme following works (auto mode)
- [ ] No regression on existing features
- [ ] All existing 38 unit tests pass
- [ ] No new TypeScript or Rust compiler errors

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-05-13-phase4-ui-enhancement.md`.

**Two execution options:**

1. **Subagent-Driven (recommended)** - I dispatch a fresh subagent per task, review between tasks, fast iteration

2. **Inline Execution** - Execute tasks in this session using executing-plans, batch execution with checkpoints

**Which approach?** (Reply with "1" or "2")