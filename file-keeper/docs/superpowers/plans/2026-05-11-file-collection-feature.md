# File Collection Feature Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement core file collection functionality allowing users to add, open, and manage favorite files and folders.

**Architecture:** Frontend uses Tauri dialog API for file selection, stores file metadata in Pinia store with persistence, and uses Tauri commands to open files with system default applications. Backend Rust commands handle file validation and opening.

**Tech Stack:** Vue 3, Pinia, Tauri 2, TypeScript, Vitest

---

## File Structure

### Files to Create
- `src/api/__tests__/files.test.ts` - Tests for file API
- `src-tauri/src/commands/__tests__/files_test.rs` - Tests for Rust commands (if test framework available)

### Files to Modify
- `src/App.vue:36` - Wire up "添加文件" button
- `src/App.vue:101,134` - Wire up file click handlers
- `src/App.vue:410` - Implement handleRemoveFile
- `src/stores/fileStore.ts:50-80` - Implement addFile, removeFile, recordOpen
- `src/api/files.ts:4-10` - Add pickFile and pickFolder functions
- `src-tauri/src/commands/files.rs:4-15` - Enhance error handling
- `src-tauri/src/main.rs` - Register new commands if needed

---

## Task 1: File Selection API (Frontend)

**Files:**
- Create: `src/api/__tests__/files.test.ts`
- Modify: `src/api/files.ts:4-10`

- [ ] **Step 1: Write failing test for pickFile**

```typescript
// src/api/__tests__/files.test.ts
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { pickFile, pickFolder } from '../files'
import { open } from '@tauri-apps/plugin-dialog'

vi.mock('@tauri-apps/plugin-dialog', () => ({
  open: vi.fn()
}))

describe('files API', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  describe('pickFile', () => {
    it('should return file path when user selects a file', async () => {
      vi.mocked(open).mockResolvedValue('/path/to/file.txt')
      
      const result = await pickFile()
      
      expect(result).toBe('/path/to/file.txt')
      expect(open).toHaveBeenCalledWith({
        multiple: false,
        directory: false
      })
    })

    it('should return null when user cancels', async () => {
      vi.mocked(open).mockResolvedValue(null)
      
    const result = await pickFile()
      
      expect(result).toBeNull()
    })
  })
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npm test -- src/api/__tests__/files.test.ts`
Expected: FAIL with "pickFile is not defined"

- [ ] **Step 3: Implement pickFile function**

```typescript
// src/api/files.ts
import { invoke } from '@tauri-apps/api/core'
import { open } from '@tauri-apps/plugin-dialog'

export async function pickFile(): Promise<string | null> {
  const selected = await open({
    multiple: false,
    directory: false
  })
  
  return selected as string | null
}

export async function openFile(path: string): Promise<void> {
  return invoke('open_file', { path })
}

export async function validatePath(path: string): Promise<boolean> {
  return invoke('validate_path', { path })
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `npm test -- src/api/__tests__/files.test.ts`
Expected: PASS

- [ ] **Step 5: Write failing test for pickFolder**

```typescript
// src/api/__tests__/files.test.ts (add to existing file)
  describe('pickFolder', () => {
    it('should return folder path when user selects a folder', async () => {
      vi.mocked(open).mockResolvedValue('/path/to/folder')
      
      const result = await pickFolder()
      
      expect(result).toBe('/path/to/folder')
      expect(open).toHaveBeenCalledWith({
        multiple: false,
        directory: true
      })
    })

    it('should return null when user cancels', async () => {
      vi.mocked(open).mockResolvedValue(null)
      
      const result = await pickFolder()
      
      expect(result).toBeNull()
    })
  })
```

- [ ] **Step 6: Run test to verify it fails**

Run: `npm test -- src/api/__tests__/files.test.ts`
Expected: FAIL with "pickFolder is not defined"

- [ ] **Step 7: Implement pickFolder function**

```typescript
// src/api/files.ts (add after pickFile)
export async function pickFolder(): Promise<string | null> {
  const selected = await open({
    multiple: false,
    directory: true
  })
  
  return selected as string | null
}
```

- [ ] **Step 8: Run test to verify it passes**

Run: `npm test -- src/api/__tests__/files.test.ts`
Expected: PASS

- [ ] **Step 9: Commit**

```bash
git add src/api/files.ts src/api/__tests__/files.test.ts
git commit -m "feat: add file and folder picker API"
```

---

## Task 2: Add File to Store

**Files:**
- Modify: `src/stores/fileStore.ts:50-80`
- Modify: `src/types/file.ts` (if needed)

- [ ] **Step 1: Write failing test for addFile**

```typescript
// src/stores/__tests__/fileStore.test.ts (create new file)
import { describe, it, expect, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useFileStore } from '../fileStore'

describe('fileStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  describe('addFile', () => {
    it('should add a new file to the store', () => {
      const store = useFileStore()
      
      const newFile = store.addFile({
        name: 'test.txt',
        path: '/path/to/test.txt',
        type: 'file',
        tags: [],
     groupId: 'all'
      })
      
      expect(newFile).toBeDefined()
      expect(newFile.id).toBeDefined()
      expect(newFile.name).toBe('test.txt')
      expect(newFile.openCount).toBe(0)
      expect(newFile.createdAt).toBeDefined()
      expect(store.files).toHaveLength(7) // 6 mock + 1 new
    })

    it('should not add duplicate files with same path', () => {
      const store = useFileStore()
      
      store.addFile({
        name: 'test.txt',
        path: '/path/to/test.txt',
        type: 'file',
        tags: [],
        groupId: 'all'
      })
      
      const result = store.addFile({
        name: 'test.txt',
        path: '/path/to/test.txt',
        type: 'file',
        tags: [],
        groupId: 'all'
      })
      
      expect(result).toBeNull()
      expect(store.files).toHaveLength(7) // 6 mock + 1 new (not 8)
    })
  })
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npm test -- src/stores/__tests__/fileStore.test.ts`
Expected: FAIL with "addFile returns undefined"

- [ ] **Step 3: Implement addFile function**

```typescript
// src/stores/fileStore.ts (modify existing addFile function around line 50)
  function addFile(file: Omit<FileItem, 'id' | 'createdAt' | 'openCount'>): FileItem | null {
    // Check for duplicate path
    const existing = files.value.find(f => f.path === file.path)
    if (existing) {
      return null
    }

    const newFile: FileItem = {
      ...file,
      id: uuidv4(),
      openCount: 0,
      createdAt: Date.now()
    }
    files.value.push(newFile)
    return newFile
  }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `npm test -- src/stores/__tests__/fileStore.test.ts`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/stores/fileStore.ts src/stores/__tests__/fileStore.test.ts
git commit -m "feat: implement addFile with duplicate check"
```

---

## Task 3: Remove File from Store

**Files:**
- Modify: `src/stores/fileStore.ts:60-70`

- [ ] **Step 1: Write failing test for removeFile**

```typescript
// src/stores/__tests__/fileStore.test.ts (add to existing file)
  describe('removeFile', () => {
    it('should remove a file by id', () => {
      const store = useFileStore()
      const initialCount = store.files.length
      const fileToRemove = store.files[0]
      
      const result = store.removeFile(fileToRemove.id)
      
      expect(result).toBe(true)
      expect(store.files).toHaveLength(initialCount - 1)
      expect(store.files.find(f => f.id === fileToRemove.id)).toBeUndefined()
    })

    it('should return false when file not found', () => {
      const store = useFileStore()
      const initialCount = store.files.length
      
      const result = store.removeFile('non-existent-id')
      
      expect(result).toBe(false)
      expect(store.files).toHaveLength(initialCount)
    })
  })
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npm test -- src/stores/__tests__/fileStore.test.ts`
Expected: FAIL with "removeFile returns undefined"

- [ ] **Step 3: Implement removeFile function**

```typescript
// src/stores/fileStore.ts (modify existing removeFile function around line 60)
  function removeFile(id: string): boolean {
    const index = files.value.findIndex(f => f.id === id)
    if (index === -1) {
   return false
    }
    
    files.value.splice(index, 1)
    return true
  }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `npm test -- src/stores/__tests__/fileStore.test.ts`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/stores/fileStore.ts src/stores/__tests__/fileStore.test.ts
git commit -m "feat: implement removeFile function"
```

---

## Task 4: Record File Open

**Files:**
- Modify: `src/stores/fileStore.ts:70-80`

- [ ] **Step 1: Write failing test for recordOpen**

```typescript
// src/stores/__tests__/fileStore.test.ts (add to existing file)
  describe('recordOpen', () => {
    it('should increment openCount and update lastOpened', () => {
      const store = useFileStore()
    const file = store.files[0]
      const initialCount = file.openCount
      const beforeTime = Date.now()
      
      store.recordOpen(file.id)
      
      const updatedFile = store.files.find(f => f.id === file.id)
      expect(updatedFile?.openCount).toBe(initialCount + 1)
      expect(updatedFile?.lastOpened).toBeGreaterThanOrEqual(beforeTime)
      expect(updatedFile?.lastOpened).toBeLessThanOrEqual(Date.now())
    })

    it('should do nothing when file not found', () => {
      const store = useFileStore()
      const initialFiles = [...store.files]
      
      store.recordOpen('non-existent-id')
      
      expect(store.files).toEqual(initialFiles)
    })
  })
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npm test -- src/stores/__tests__/fileStore.test.ts`
Expected: FAIL with "recordOpen is not defined"

- [ ] **Step 3: Implement recordOpen function**

```typescript
// src/stores/fileStore.ts (add after removeFile function around line 70)
  function recordOpen(id: string): void {
    const file = files.value.find(f => f.id === id)
    if (file) {
      file.openCount++
      file.lastOpened = Date.now()
    }
  }
```

- [ ] **Step 4: Update return statement to export recordOpen**

```typescript
// src/stores/fileStore.ts (modify return statement around line 90)
  return {
    // State
    files,
    searchQuery,
    currentGroupId,
    // Getters
    filteredFiles,
    recentFiles,
    // Actions
    addFile,
    removeFile,
    recordOpen,  // Add this line
    updateFile,
    loadFiles
  }
```

- [ ] **Step 5: Run test to verify it passes**

Run: `npm test -- src/stores/__tests__/fileStore.test.ts`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add src/stores/fileStore.ts src/stores/__tests__/fileStore.test.ts
git commit -m "feat: implement recordOpen to track file usage"
```

---

## Task 5: Wire Up Add File Button

**Files:**
- Modify: `src/App.vue:263-266`
- Modify: `src/App.vue:36`

- [ ] **Step 1: Implement handleAddFile function**

```typescript
// src/App.vue (replace existing handleAddFile around line 263)
import { pickFile, pickFolder } from './api/files'

// ... existing code ...

async function handleAddFile() {
  try {
    // Show file picker
    const filePath = await pickFile()
    
    if (!filePath) {
      return // User cancelled
    }
    
    // Validate path
    const isValid = await validatePath(filePath)
    if (!isValid) {
      console.error('文件不存在或无法访问')
      return
    }
    
    // Extract file name from path
    const fileName = filePath.split(/[/\\]/).pop() || filePath
    
    // Determine file type from extension
    const ext = fileName.split('.').pop()?.toLowerCase() || ''
    let icon = 'file'
  if (['doc', 'docx'].includes(ext)) icon = 'word'
    else if (['xls', 'xlsx'].includes(ext)) icon = 'excel'
    else if (['png', 'jpg', 'jpeg', 'gif'].includes(ext)) icon = 'image'
    else if (['js', 'ts', 'py', 'java'].includes(ext)) icon = 'code'
    
    // Add to store
    const newFile = fileStore.addFile({
      name: fileName,
      path: filePath,
      type: 'file',
      icon,
      tags: [],
      groupId: groupStore.currentGroupId
    })
    
    if (!newFile) {
      console.error('文件已存在')
      return
    }
    
    console.log(`已添加文件: ${fileName}`)
  } catch (error) {
    console.error('添加文件失败:', error)
  }
}
```

- [ ] **Step 2: Add import for validatePath**

```typescript
// src/App.vue (add to imports around line 343)
import { validatePath } from './api/files'
```

- [ ] **Step 3: Test manually**

Run: `npm run dev`
1. Click "添加文件" button
2. Select a file
3. Verify file appears in the list
4. Try adding the same file again
5. Verify duplicate is rejected

Expected: File is added successfully, duplicate is rejected

- [ ] **Step 4: Commit**

```bash
git add src/App.vue
git commit -m "feat: wire up add file button with file picker"
```

---

## Task 6: Wire Up File Click to Open

**Files:**
- Modify: `src/App.vue:268-272`

- [ ] **Step 1: Implement handleFileClick function**

```typescript
// src/App.vue (replace existing handleFileClick around line 268)
import { openFile } from './api/files'

// ... existing code ...

async function handleFileClick(file: FileItem) {
  try {
    // Record the open action
    fileStore.recordOpen(file.id)
    
    // Open the file with system default application
    await openFile(file.path)
    
    console.log(`已打开文件: ${file.name}`)
  } catch (error) {
    console.error(`打开文件失败: ${error}`)
  }
}
```

- [ ] **Step 2: Add import for openFile**

```typescript
// src/App.vue (add to imports around line 343)
import { openFile, validatePath } from './api/files'
```

- [ ] **Step 3: Test manually**

Run: `npm run dev`
1. Click on a file card
2. Verify file opens in default application
3. Verify openCount increments
4. Verify lastOpened updates

Expected: File opens successfully, stats update

- [ ] **Step 4: Commit**

```bash
git add src/App.vue
git commit -m "feat: wire up file click to open with system app"
```

---

## Task 7: Wire Up Remove File

**Files:**
- Modify: `src/App.vue:410-413`

- [ ] **Step 1: Implement handleRemoveFile function**

```typescript
// src/App.vue (replace existing handleRemoveFile around line 410)
function handleRemoveFile(file: FileItem) {
  const success = fileStore.removeFile(file.id)
  if (success) {
    console.log(`已移除: ${file.name}`)
  } else {
    console.error(`移除失败: ${file.name}`)
  }
}
```

- [ ] **Step 2: Test manually**

Run: `npm run dev`
1. Right-click on a file card
2. Click "移除收藏"
3. Verify file is removed from list
4. Verify file is removed from store

Expected: File is removed successfully

- [ ] **Step 3: Commit**

```bash
git add src/App.vue
git commit -m "feat: wire up remove file from context menu"
```

---

## Task 8: Add Folder Support

**Files:**
- Modify: `src/App.vue:263-300`

- [ ] **Step 1: Add folder picker option to handleAddFile**

```typescript
// src/App.vue (enhance handleAddFile around line 263)
async function handleAddFile() {
  try {
    // Ask user to choose file or folder
    // For now, we'll add a simple prompt
    // In a real app, you'd show a dialog with options
    
    const choice = confirm('添加文件夹？\n确定 = 文件夹\n取消 = 文件')
  
    let selectedPath: string | null
    
    if (choice) {
      // Pick folder
      selectedPath = await pickFolder()
    } else {
      // Pick file
    selectedPath = await pickFile()
    }
    
    if (!selectedPath) {
      return // User cancelled
    }
    
    // Validate path
    const isValid = await validatePath(selectedPath)
    if (!isValid) {
      console.error('路径不存在或无法访问')
      return
    }
    
    // Extract name from path
    const name = selectedPath.split(/[/\\]/).pop() || selectedPath
    
    // Determine type and icon
    let type: 'file' | 'folder' = 'file'
    let icon = 'file'
    
    if (choice) {
      type = 'folder'
      icon = 'folder'
    } else {
      const ext = name.split('.').pop()?.toLowerCase() || ''
      if (['doc', 'docx'].includes(ext)) icon = 'word'
      else if (['xls', 'xlsx'].includes(ext)) icon = 'excel'
      else if (['png', 'jpg', 'jpeg', 'gif'].includes(ext)) icon = 'image'
   else if (['js', 'ts', 'py', 'java'].includes(ext)) icon = 'code'
    }
    
    // Add to store
    const newItem = fileStore.addFile({
      name,
      path: selectedPath,
      type,
      icon,
      tags: [],
      groupId: groupStore.currentGroupId
    })
    
    if (!newItem) {
      console.error('项目已存在')
    return
    }
    
    console.log(`已添加${type === 'folder' ? '文件夹' : '文件'}: ${name}`)
  } catch (error) {
    console.error('添加失败:', error)
  }
```

- [ ] **Step 2: Add import for pickFolder**

```typescript
// src/App.vue (modify imports around line 343)
import { openFile, validatePath, pickFile, pickFolder } from './api/files'
```

- [ ] **Step 3: Test manually**

Run: `npm run dev`
1. Click "添加文件" button
2. Choose "确定" to add folder
3. Select a folder
4. Verify folder appears in list with folder icon
5. Click on folder
6. Verify folder opens in file explorer

Expected: Folder is added and opens successfully

- [ ] **Step 4: Commit**

```bash
git add src/App.vue
git commit -m "feat: add folder support to file collection"
```

---

## Task 9: Enhance Error Handling

**Files:**
- Modify: `src-tauri/src/commands/files.rs:4-15`

- [ ] **Step 1: Enhance open_file error handling**

```rust
// src-tauri/src/commands/files.rs
use std::path::Path;

#[tauri::command]
pub async fn open_file(path: String) -> Result<(), String> {
    let path_obj = Path::new(&path);
    
    if !path_obj.exists() {
    return Err(format!("文件不存在: {}", path));
    }
    
    if path_obj.is_dir() {
        // Open folder in file explorer
        opener::open(&path).map_err(|e| format!("打开文件夹失败: {}", e))
    } else if path_obj.is_file() {
        // Open file with default application
        opener::open(&path).map_err(|e| format!("打开文件失败: {}", e))
    } else {
        Err(format!("不支持的路径类型: {}", path))
    }
}

#[tauri::command]
pub async fn validate_path(path: String) -> Result<bool, String> {
    let path_obj = Path::new(&path);
  Ok(path_obj.exists())
}
```

- [ ] **Step 2: Test manually**

Run: `.\dev-msvc.bat`
1. Add a file and open it
2. Add a folder and open it
3. Try to open a non-existent file
4. Verify error messages are clear

Expected: Files and folders open correctly, errors are descriptive

- [ ] **Step 3: Commit**

```bash
git add src-tauri/src/commands/files.rs
git commit -m "feat: enhance error handling for file operations"
```

---

## Task 10: Integration Test

**Files:**
- None (manual testing)

- [ ] **Step 1: Test add file flow**

Run: `.\dev-msvc.bat`
1. Click "添加文件" button
2. Cancel the dialog
3. Click "添加文件" again
4. Select a .txt file
5. Verify file appears in list
6. Try to add the same file again
7. Verify duplicate is rejected

Expected: All steps work as expected

- [ ] **Step 2: Test add folder flow**

1. Click "添加文件" button
2. Choose "确定" for folder
3. Select a folder
4. Verify folder appears with folder icon
5. Click on folder
6. Verify folder opens in file explorer

Expected: All steps work as expected

- [ ] **Step 3: Test open file flow**

1. Click on a file card
2. Verify file opens in default application
3. Verify openCount increments in UI
4. Click on the same file again
5. Verify openCount increments again

Expected: All steps work as expected

- [ ] **Step 4: Test remove file flow**

1. Right-click on a file card
2. Click "移除收藏"
3. Verify file disappears from list
4. Refresh the app
5. Verify file is still removed (persistence)

Expected: All steps work as expected

- [ ] **Step 5: Test search and filter**

1. Add multiple files
2. Type in search box
3. Verify files are filtered correctly
4. Clear search
5. Verify all files appear again

Expected: All steps work as expected

- [ ] **Step 6: Test group switching**

1. Add files to different groups
2. Click on different group tabs
3. Verify correct files appear for each group
4. Click "全部" tab
5. Verify all files appear

Expected: All steps work as expected

- [ ] **Step 7: Document test results**

Create: `docs/testing/file-collection-manual-test-results.md`

```markdown
# File Collection Feature - Manual Test Results

**Date:** 2026-05-11
**Tester:** [Your Name]
**Build:** v0.1.0-alpha

## Test Results

### Add File Flow
- [ ] Can open file picker
- [ ] Can cancel file picker
- [ ] Can select and add file
- [ ] Duplicate files are rejected
- [ ] File appears in list immediately

### Add Folder Flow
- [ ] Can choose folder option
- [ ] Can select and add folder
- [ ] Folder appears with correct icon
- [ ] Folder opens in file explorer

### Open File Flow
- [ ] Files open in default application
- [ ] openCount increments correctly
- [ ] lastOpened updates correctly

### Remove File Flow
- [ ] Can remove file from context menu
- [ ] File disappears from list
- [ ] Removal persists after refresh

### Search and Filter
- [ ] Search filters files correctly
- [ ] Clear search shows all files

### Group Switching
- [ ] Files appear in correct groups
- [ ] "全部" shows all files
- [ ] Group switching is smooth

## Issues Found

[List any issues discovered during testing]

## Notes

[Any additional observations]
```

- [ ] **Step 8: Final commit**

```bash
git add docs/testing/file-collection-manual-test-results.md
git commit -m "docs: add manual test results for file collection"
```

---

## Self-Review Checklist

### Spec Coverage
- ✅ File selection dialog - Task 1, 5, 8
- ✅ Add file to collection - Task 2, 5
- ✅ Add folder to collection - Task 8
- ✅ Open file with default app - Task 6
- ✅ Remove file from collection - Task 3, 7
- ✅ Record open count and time - Task 4, 6
- ✅ Duplicate prevention - Task 2
- ✅ Error handling - Task 9
- ✅ Integration testing - Task 10

### Placeholder Scan
- ✅ No "TBD" or "TODO" placeholders
- ✅ All code blocks are complete
- ✅ All test expectations are specific
- ✅ All commands have expected output

### Type Consistency
- ✅ FileItem type used consistently
- ✅ addFile returns FileItem | null consistently
- ✅ removeFile returns boolean consistently
- ✅ recordOpen returns void consistently
- ✅ pickFile/pickFolder return string | null consistently

---

## Execution Notes

- All tests use Vitest with Vue Test Utils
- Manual testing requires running Tauri app with `.\dev-msvc.bat`
- File persistence is handled automatically by Pinia persist plugin
- Error messages are logged to console (can be enhanced with toast notifications later)
