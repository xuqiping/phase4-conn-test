# Phase 3 Enhanced Features Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement Phase 3 enhanced features including batch operations, process management (close opened files), and global shortcuts with system tray.

**Architecture:** Three independent feature modules built sequentially. Batch operations extend existing file management UI with multi-select state. Process management implements cross-platform process enumeration and termination with file path matching. Global shortcuts and tray integrate system-level features using Tauri plugins.

**Tech Stack:** Vue 3 + TypeScript + Pinia + Tailwind CSS 4.x + Tauri 2 + Rust (sysinfo, Windows API, macOS Cocoa)

**Baseline:** Phase 2 MVP complete with 35 passing tests, all core UI components functional.

---

## File Structure Overview

### New Files to Create

**Frontend:**
- `src/stores/selectionStore.ts` - Multi-select state management
- `src/api/processes.ts` - Process management API wrapper
- `src/api/shortcuts.ts` - Global shortcut API wrapper
- `src/api/tray.ts` - System tray API wrapper
- `src/utils/batch.ts` - Batch operation utilities
- `src/utils/__tests__/batch.test.ts` - Batch utilities tests

**Backend:**
- `src-tauri/src/commands/processes.rs` - Process enumeration and termination
- `src-tauri/src/platform/mod.rs` - Platform abstraction layer
- `src-tauri/src/platform/windows.rs` - Windows-specific process matching
- `src-tauri/src/platform/macos.rs` - macOS-specific process matching
- `src-tauri/src/platform/linux.rs` - Linux-specific process matching
- `src-tauri/src/utils/mod.rs` - Utility modules
- `src-tauri/src/utils/process_matcher.rs` - Cross-platform process matching logic

### Files to Modify

**Frontend:**
- `src/App.vue` - Add batch operation UI, integrate process manager, handle shortcuts
- `src/stores/fileStore.ts` - Add batch operation actions
- `src/stores/settingsStore.ts` - Add shortcut and tray settings

**Backend:**
- `src-tauri/src/main.rs` - Register new commands, setup tray and shortcuts
- `src-tauri/src/commands/mod.rs` - Export new process commands
- `src-tauri/Cargo.toml` - Already has required dependencies

---

## Task 1: Batch Operations - Multi-Select State Management

**Files:**
- Create: `src/stores/selectionStore.ts`
- Create: `src/utils/batch.ts`
- Create: `src/utils/__tests__/batch.test.ts`
- Modify: `src/App.vue` (add selection UI)

- [ ] **Step 1: Write test for batch utilities**

```typescript
// src/utils/__tests__/batch.test.ts
import { describe, it, expect } from 'vitest'
import { canBatchOpen, canBatchDelete, canBatchMove } from '../batch'
import type { FileItem } from '../../types/file'

describe('batch utilities', () => {
  const mockFiles: FileItem[] = [
    {
      id: '1',
      name: 'test1.txt',
      path: '/path/to/test1.txt',
      type: 'file',
      tags: [],
      groupId: 'all',
   createdAt: Date.now(),
      openCount: 0
    },
    {
      id: '2',
      name: 'test2.txt',
      path: '/path/to/test2.txt',
      type: 'file',
      tags: [],
      groupId: 'all',
      createdAt: Date.now(),
   openCount: 0
    }
  ]

  it('should allow batch open for any files', () => {
    expect(canBatchOpen(mockFiles)).toBe(true)
    expect(canBatchOpen([])).toBe(false)
  })

  it('should allow batch delete for any files', () => {
    expect(canBatchDelete(mockFiles)).toBe(true)
    expect(canBatchDelete([])).toBe(false)
  })

  it('should allow batch move for any files', () => {
    expect(canBatchMove(mockFiles)).toBe(true)
    expect(canBatchMove([])).toBe(false)
  })
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd "C:\AI Projects\file-keeper" && npx vitest run src/utils/__tests__/batch.test.ts`
Expected: FAIL with "cannot find module '../batch'"

- [ ] **Step 3: Implement batch utilities**

```typescript
// src/utils/batch.ts
import type { FileItem } from '../types/file'

/**
 * Check if batch open is allowed
 */
export function canBatchOpen(files: FileItem[]): boolean {
  return files.length > 0
}

/**
 * Check if batch delete is allowed
 */
export function canBatchDelete(files: FileItem[]): boolean {
  return files.length > 0
}

/**
 * Check if batch move is allowed
 */
export function canBatchMove(files: FileItem[]): boolean {
  return files.length > 0
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd "C:\AI Projects\file-keeper" && npx vitest run src/utils/__tests__/batch.test.ts`
Expected: PASS (3 tests)

- [ ] **Step 5: Create selectionStore with tests**

```typescript
// src/stores/selectionStore.ts
import { defineStore } from 'pinia'
import { ref, computed } from 'vue'

export const useSelectionStore = defineStore('selection', () => {
  const selectedIds = ref<Set<string>>(new Set())

  const selectedCount = computed(() => selectedIds.value.size)
  const hasSelection = computed(() => selectedIds.value.size > 0)

  function toggleSelection(id: string) {
    if (selectedIds.value.has(id)) {
      selectedIds.value.delete(id)
    } else {
      selectedIds.value.add(id)
    }
    // Trigger reactivity
    selectedIds.value = new Set(selectedIds.value)
  }

  function selectAll(ids: string[]) {
    selectedIds.value = new Set(ids)
  }

  function clearSelection() {
    selectedIds.value.clear()
    selectedIds.value = new Set(selectedIds.value)
  }

  function isSelected(id: string): boolean {
    return selectedIds.value.has(id)
  }

  return {
    selectedIds,
    selectedCount,
    hasSelection,
    toggleSelection,
    selectAll,
    clearSelection,
    isSelected
  }
})
```

- [ ] **Step 6: Add batch actions to fileStore**

```typescript
// Modify src/stores/fileStore.ts - add these methods to the store

function batchOpen(ids: string[]) {
  const filesToOpen = files.value.filter(f => ids.includes(f.id))
  filesToOpen.forEach(file => {
    openFile(file.path).catch(err => {
      console.error(`Failed to open ${file.name}:`, err)
    })
    recordOpen(file.id)
  })
}

function batchDelete(ids: string[]) {
  ids.forEach(id => removeFile(id))
}

function batchMove(ids: string[], targetGroupId: string) {
  ids.forEach(id => {
  updateFile(id, { groupId: targetGroupId })
  })
}

function batchAddTags(ids: string[], tags: string[]) {
  ids.forEach(id => {
    const file = files.value.find(f => f.id === id)
    if (file) {
      const newTags = [...new Set([...file.tags, ...tags])]
      updateFile(id, { tags: newTags })
    }
  })
}

// Add to return statement
return {
  // ... existing exports
  batchOpen,
  batchDelete,
  batchMove,
  batchAddTags
}
```

- [ ] **Step 7: Add multi-select UI to App.vue**

Modify `src/App.vue` - add checkbox to file cards in grid view (around line 84-116):

```vue
<!-- Add this inside the file card div, before the icon -->
<div
  v-if="selectionStore.hasSelection || file.id === hoveredFileId"
  class="absolute top-2 left-2 z-10"
  @click.stop="selectionStore.toggleSelection(file.id)"
>
  <div
    :class="[
      'w-5 h-5 rounded border-2 flex items-center justify-center cursor-pointer transition-all',
      selectionStore.isSelected(file.id)
        ? 'bg-primary border-primary'
        : 'bg-white dark:bg-dark-panel border-gray-300 dark:border-gray-600 hover:border-primary'
    ]"
  >
    <Check v-if="selectionStore.isSelected(file.id)" :size="14" class="text-white" />
  </div>
</div>
```

Add hover state tracking in script:

```typescript
const hoveredFileId = ref<string | null>(null)
```

Add `@mouseenter` and `@mouseleave` to file card:

```vue
<div
  @mouseenter="hoveredFileId = file.id"
  @mouseleave="hoveredFileId = null"
  <!-- existing attributes -->
>
```

- [ ] **Step 8: Add batch operation toolbar**

Add this after the search bar in App.vue (around line 50):

```vue
<!-- Batch Operations Toolbar -->
<transition name="fade">
  <div
    v-if="selectionStore.hasSelection"
    class="fixed bottom-6 left-1/2 -translate-x-1/2 z-50 bg-white dark:bg-dark-panel rounded-xl shadow-2xl border border-gray-200 dark:border-dark-border px-6 py-3 flex items-center space-x-4"
  >
    <span class="text-sm text-gray-600 dark:text-gray-300">
      已选择 <strong class="text-primary">{{ selectionStore.selectedCount }}</strong> 项
    </span>
    <div class="h-4 w-px bg-gray-300 dark:bg-gray-600"></div>
    <button
      @click="handleBatchOpen"
      class="flex items-center space-x-1 px-3 py-1.5 text-sm text-gray-700 dark:text-gray-200 hover:bg-gray-100 dark:hover:bg-dark-hover rounded-md transition-colors"
    >
      <FolderOpen :size="14" />
      <span>打开</span>
    </button>
    <button
      @click="showBatchMoveMenu = true"
      class="flex items-center space-x-1 px-3 py-1.5 text-sm text-gray-700 dark:text-gray-200 hover:bg-gray-100 dark:hover:bg-dark-hover rounded-md transition-colors"
    >
      <FolderInput :size="14" />
      <span>移动</span>
    </button>
    <button
      @click="handleBatchDelete"
      class="flex items-center space-x-1 px-3 py-1.5 text-sm text-red-600 hover:bg-red-50 dark:hover:bg-red-900/20 rounded-md transition-colors"
    >
      <Trash2 :size="14" />
      <span>删除</span>
    </button>
    <div class="h-4 w-px bg-gray-300 dark:bg-gray-600"></div>
    <button
      @click="selectionStore.clearSelection"
      class="text-sm text-gray-400 hover:text-gray-600 dark:hover:text-gray-200 transition-colors"
    >
    取消
    </button>
  </div>
</transition>
```
Add handler functions in script:

```typescript
import { useSelectionStore } from './stores/selectionStore'

const selectionStore = useSelectionStore()
const showBatchMoveMenu = ref(false)

function handleBatchOpen() {
  const ids = Array.from(selectionStore.selectedIds)
  fileStore.batchOpen(ids)
  selectionStore.clearSelection()
}

function handleBatchDelete() {
  const ids = Array.from(selectionStore.selectedIds)
  const confirmed = confirm(`确定删除选中的 ${ids.length} 个项目？`)
  if (confirmed) {
    fileStore.batchDelete(ids)
    selectionStore.clearSelection()
  }
}

function handleBatchMove(targetGroupId: string) {
  const ids = Array.from(selectionStore.selectedIds)
  fileStore.batchMove(ids, targetGroupId)
  selectionStore.clearSelection()
  showBatchMoveMenu.value = false
}
```

- [ ] **Step 9: Run all tests**

Run: `cd "C:\AI Projects\file-keeper" && npx vitest run`
Expected: All tests pass (35 + 3 = 38 tests)

- [ ] **Step 10: Commit**

```bash
cd "C:\AI Projects\file-keeper"
git add src/stores/selectionStore.ts src/utils/batch.ts src/utils/__tests__/batch.test.ts src/stores/fileStore.ts src/App.vue
git commit -m "feat: add batch operations with multi-select UI"
```

---
## Task 2: Process Management - Backend Implementation

**Files:**
- Create: `src-tauri/src/commands/processes.rs`
- Create: `src-tauri/src/platform/mod.rs`
- Create: `src-tauri/src/platform/windows.rs`
- Create: `src-tauri/src/platform/macos.rs`
- Create: `src-tauri/src/platform/linux.rs`
- Create: `src-tauri/src/utils/mod.rs`
- Create: `src-tauri/src/utils/process_matcher.rs`
- Modify: `src-tauri/src/main.rs`
- Modify: `src-tauri/src/commands/mod.rs`

- [ ] **Step 1: Create platform abstraction layer**

```rust
// src-tauri/src/platform/mod.rs
#[cfg(target_os = "windows")]
pub mod windows;

#[cfg(target_os = "macos")]
pub mod macos;

#[cfg(target_os = "linux")]
pub mod linux;

use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ProcessInfo {
    pub pid: u32,
    pub name: String,
    pub path: Option<String>,
    pub window_title: Option<String>,
    pub associated_file: Option<String>,
}

pub trait ProcessMatcher {
    fn find_processes_for_file(file_path: &str) -> Result<Vec<ProcessInfo>, String>;
    fn kill_process(pid: u32) -> Result<(), String>;
}
```

- [ ] **Step 2: Implement Windows process matching**

```rust
// src-tauri/src/platform/windows.rs
use super::{ProcessInfo, ProcessMatcher};
use std::path::Path;
use sysinfo::{ProcessExt, System, SystemExt};

#[cfg(target_os = "windows")]
use windows::Win32::Foundation::HWND;
#[cfg(target_os = "windows")]
use windows::Win32::UI::WindowsAndMessaging::{
    EnumWindows, GetWindowTextW, GetWindowThreadProcessId,
};

pub struct WindowsProcessMatcher;

impl ProcessMatcher for WindowsProcessMatcher {
    fn find_processes_for_file(file_path: &str) -> Result<Vec<ProcessInfo>, String> {
        let mut system = System::new_all();
        system.refresh_all();

        let file_path_normalized = Path::new(file_path)
            .canonicalize()
            .map_err(|e| format!("Failed to normalize path: {}", e))?;

        let mut matching_processes = Vec::new();

        for (pid, process) in system.processes() {
            let process_path = process.exe();
            let process_name = process.name().to_string();

          // Get window titles for this process
            let window_titles = get_window_titles_for_pid(pid.as_u32());

            // Check if any window title contains the file name or path
            let file_name = file_path_normalized
            .file_name()
              .and_then(|n| n.to_str())
            .unwrap_or("");

            for title in window_titles {
        if title.contains(file_name) || title.contains(file_path) {
                matching_processes.push(ProcessInfo {
                    pid: pid.as_u32(),
                 name: process_name.clone(),
                        path: process_path.to_str().map(|s| s.to_string()),
                        window_title: Some(title.clone()),
              associated_file: Some(file_path.to_string()),
                    });
                    break;
                }
         }
        }

        Ok(matching_processes)
    }

    fn kill_process(pid: u32) -> Result<(), String> {
        let mut system = System::new_all();
        system.refresh_all();

        if let Some(process) = system.process(sysinfo::Pid::from_u32(pid)) {
            if process.kill() {
          Ok(())
          } else {
            Err(format!("Failed to kill process {}", pid))
            }
        } else {
            Err(format!("Process {} not found", pid))
      }
    }
}

#[cfg(target_os = "windows")]
fn get_window_titles_for_pid(target_pid: u32) -> Vec<String> {
    use std::sync::Mutex;

    let titles = Mutex::new(Vec::new());

    unsafe {
        let _ = EnumWindows(
            Some(enum_windows_callback),
            &titles as *const _ as isize,
        );
    }

    titles.into_inner().unwrap()
}

#[cfg(target_os = "windows")]
unsafe extern "system" fn enum_windows_callback(hwnd: HWND, lparam: isize) -> i32 {
    let titles = &*(lparam as *const std::sync::Mutex<Vec<String>>);

    let mut pid: u32 = 0;
    GetWindowThreadProcessId(hwnd, Some(&mut pid));

    let mut title = [0u16; 512];
    let len = GetWindowTextW(hwnd, &mut title);

  if len > 0 {
        let title_str = String::from_utf16_lossy(&title[..len as usize]);
        if !title_str.is_empty() {
            titles.lock().unwrap().push(title_str);
        }
    }

    1 // Continue enumeration
}
```

- [ ] **Step 3: Implement macOS process matching**

```rust
// src-tauri/src/platform/macos.rs
use super::{ProcessInfo, ProcessMatcher};
use std::path::Path;
use std::process::Command;
use sysinfo::{ProcessExt, System, SystemExt};

pub struct MacOSProcessMatcher;

impl ProcessMatcher for MacOSProcessMatcher {
    fn find_processes_for_file(file_path: &str) -> Result<Vec<ProcessInfo>, String> {
        let mut system = System::new_all();
        system.refresh_all();

        let file_path_normalized = Path::new(file_path)
            .canonicalize()
         .map_err(|e| format!("Failed to normalize path: {}", e))?;

        let mut matching_processes = Vec::new();

        // Use lsof to find processes with the file open
        let output = Command::new("lsof")
            .arg(file_path_normalized.to_str().unwrap())
            .output()
            .map_err(|e| format!("Failed to run lsof: {}", e))?;

        if output.status.success() {
            let stdout = String::from_utf8_lossy(&output.stdout);
         for line in stdout.lines().skip(1) {
            // Skip header
            let parts: Vec<&str> = line.split_whitespace().collect();
                if parts.len() >= 2 {
                  if let Ok(pid) = parts[1].parse::<u32>() {
                  if let Some(process) = system.process(sysinfo::Pid::from_u32(pid)) {
                    matching_processes.push(ProcessInfo {
                     pid,
              name: process.name().to_string(),
                    path: process.exe().to_str().map(|s| s.to_string()),
                    window_title: None,
                      associated_file: Some(file_path.to_string()),
                        });
                      }
                    }
                }
            }
      }

        Ok(matching_processes)
    }

    fn kill_process(pid: u32) -> Result<(), String> {
        let mut system = System::new_all();
        system.refresh_all();

      if let Some(process) = system.process(sysinfo::Pid::from_u32(pid)) {
            if process.kill() {
              Ok(())
            } else {
                Err(format!("Failed to kill process {}", pid))
      }
        } else {
     Err(format!("Process {} not found", pid))
        }
    }
}
```

- [ ] **Step 4: Implement Linux process matching**

```rust
// src-tauri/src/platform/linux.rs
use super::{ProcessInfo, ProcessMatcher};
use std::fs;
use std::path::Path;
use sysinfo::{ProcessExt, System, SystemExt};

pub struct LinuxProcessMatcher;

impl ProcessMatcher for LinuxProcessMatcher {
    fn find_processes_for_file(file_path: &str) -> Result<Vec<ProcessInfo>, String> {
        let mut system = System::new_all();
        system.refresh_all();

        let file_path_normalized = Path::new(file_path)
            .canonicalize()
         .map_err(|e| format!("Failed to normalize path: {}", e))?;

        let mut matching_processes = Vec::new();

        for (pid, process) in system.processes() {
          // Check /proc/[pid]/fd/ for open file descriptors
            let fd_path = format!("/proc/{}/fd", pid.as_u32());
            if let Ok(entries) = fs::read_dir(&fd_path) {
                for entry in entries.flatten() {
                    if let Ok(link) = fs::read_link(entry.path()) {
                     if link == file_path_normalized {
               matching_processes.push(ProcessInfo {
                             pid: pid.as_u32(),
                            name: process.name().to_string(),
                         path: process.exe().to_str().map(|s| s.to_string()),
                     window_title: None,
                           associated_file: Some(file_path.to_string()),
                    });
                       break;
                  }
                    }
                }
            }
        }

        Ok(matching_processes)
    }

    fn kill_process(pid: u32) -> Result<(), String> {
        let mut system = System::new_all();
        system.refresh_all();

        if let Some(process) = system.process(sysinfo::Pid::from_u32(pid)) {
            if process.kill() {
                Ok(())
            } else {
                Err(format!("Failed to kill process {}", pid))
            }
        } else {
            Err(format!("Process {} not found", pid))
        }
    }
}
```

- [ ] **Step 5: Create process matcher utility**

```rust
// src-tauri/src/utils/mod.rs
pub mod process_matcher;
```

```rust
// src-tauri/src/utils/process_matcher.rs
use crate::platform::ProcessInfo;

#[cfg(target_os = "windows")]
use crate::platform::windows::WindowsProcessMatcher as PlatformMatcher;

#[cfg(target_os = "macos")]
use crate::platform::macos::MacOSProcessMatcher as PlatformMatcher;

#[cfg(target_os = "linux")]
use crate::platform::linux::LinuxProcessMatcher as PlatformMatcher;

use crate::platform::ProcessMatcher;

pub fn find_processes_for_file(file_path: &str) -> Result<Vec<ProcessInfo>, String> {
    PlatformMatcher::find_processes_for_file(file_path)
}

pub fn kill_process(pid: u32) -> Result<(), String> {
    PlatformMatcher::kill_process(pid)
}
```

- [ ] **Step 6: Create Tauri commands for process management**

```rust
// src-tauri/src/commands/processes.rs
use crate::platform::ProcessInfo;
use crate::utils::process_matcher;

#[tauri::command]
pub async fn find_file_processes(file_path: String) -> Result<Vec<ProcessInfo>, String> {
    process_matcher::find_processes_for_file(&file_path)
}

#[tauri::command]
pub async fn close_process(pid: u32) -> Result<(), String> {
    process_matcher::kill_process(pid)
}

#[tauri::command]
pub async fn close_file_processes(file_path: String) -> Result<usize, String> {
    let processes = process_matcher::find_processes_for_file(&file_path)?;
    let count = processes.len();

    for process in processes {
        process_matcher::kill_process(process.pid)?;
    }

    Ok(count)
}
```

- [ ] **Step 7: Register process commands in main.rs**

```rust
// Modify src-tauri/src/main.rs

// Add module declarations at the top
mod platform;
mod utils;

// Modify the commands module import
mod commands;
use commands::files::{open_file, show_in_folder, validate_path};
use commands::processes::{close_file_processes, close_process, find_file_processes};

// Update the invoke_handler
fn main() {
    tauri::Builder::default()
        .plugin(tauri_plugin_store::Builder::new().build())
        .plugin(tauri_plugin_fs::init())
        .plugin(tauri_plugin_dialog::init())
        .plugin(tauri_plugin_global_shortcut::Builder::new().build())
        .plugin(tauri_plugin_opener::init())
        .invoke_handler(tauri::generate_handler![
            open_file,
        validate_path,
        show_in_folder,
            find_file_processes,
            close_process,
         close_file_processes
        ])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
```

- [ ] **Step 8: Export process commands**

```rust
// Modify src-tauri/src/commands/mod.rs
pub mod files;
pub mod processes;
```

- [ ] **Step 9: Build and test Rust code**

Run: `cd "C:\AI Projects\file-keeper" && .\dev-msvc.bat`
Expected: Rust compilation succeeds, application starts

- [ ] **Step 10: Commit backend implementation**

```bash
cd "C:\AI Projects\file-keeper"
git add src-tauri/
git commit -m "feat: implement cross-platform process management backend"
```

---