# Screenshot and Optional Local OCR Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add File Keeper's own region screenshot workflow, screenshot shortcut, and optional local RapidOCR/ONNX Runtime sidecar OCR with Windows OCR fallback.

**Architecture:** The frontend owns screenshot selection UI and shortcut triggering; Rust owns native screenshot capture, PNG cache storage, OCR provider selection, and clipboard history insertion. Local enhanced OCR is an optional package under the installation directory's `ocr/` subdirectory; when absent, Windows falls back to the existing system OCR provider.

**Tech Stack:** Vue 3, TypeScript, Pinia, Vitest, Tauri 2, Rust, Win32 GDI, existing SQLite clipboard storage, optional Python RapidOCR/ONNX Runtime sidecar.

---

## File Structure

### Frontend

- Modify `src/types/settings.ts`
  - Add `screenshotShortcut` to app settings.

- Modify `src/stores/settingsStore.ts`
  - Add default screenshot shortcut: `CommandOrControl+Shift+X`.
  - Preserve persisted settings compatibility.

- Modify `src/components/SettingsDialog.vue`
  - Add screenshot shortcut input near existing shortcut controls.
  - Emit `screenshotShortcut` from save payload.

- Modify `src/components/__tests__/settingsDialog.test.ts`
  - Assert screenshot shortcut renders and saves.

- Create `src/api/screenshot.ts`
  - Wrap Tauri screenshot commands.

- Create `src/types/screenshot.ts`
  - Define frontend screenshot region and response types.

- Create `src/components/ScreenshotOverlay.vue`
  - Full-screen selection overlay for drag-to-select screenshot.

- Create `src/components/__tests__/screenshotOverlay.test.ts`
  - Test drag selection, tiny selection cancellation, and `Esc` cancellation.

- Modify `src/App.vue`
  - Register screenshot shortcut in the same lifecycle as existing global and clipboard shortcuts.
  - Open/close screenshot overlay mode.
  - Call screenshot API when selection completes.

- Modify `src/locales/zh-CN.ts` and `src/locales/en.ts`
  - Add screenshot labels, notices, and settings text.

### Rust backend

- Create `src-tauri/src/commands/screenshot.rs`
  - Tauri commands for capturing selected region and checking OCR provider status.

- Modify `src-tauri/src/commands/mod.rs`
  - Export screenshot commands.

- Modify `src-tauri/src/main.rs`
  - Register screenshot commands.

- Create `src-tauri/src/platform/windows/screenshot.rs`
  - Windows virtual-screen region capture using Win32 GDI.

- Modify `src-tauri/src/platform/windows/mod.rs`
  - Export screenshot module.

- Create `src-tauri/src/clipboard/ocr_provider.rs`
  - Provider selection and OCR result model.
  - Prefer optional sidecar, fallback to Windows OCR, then empty result.

- Modify `src-tauri/src/clipboard/ocr.rs`
  - Keep Windows OCR as a provider function rather than the default direct path.

- Modify `src-tauri/src/clipboard/mod.rs`
  - Add `capture_screenshot_region` flow that saves PNG bytes, runs OCR, inserts image history, updates note/search text, and returns the item id.

- Modify `src-tauri/src/clipboard/storage.rs`
  - Reuse existing `insert_image_item` and `update_ocr_text`; add small helper only if needed to keep screenshot insertion focused.

- Modify `src-tauri/src/clipboard/types.rs`
  - Add OCR provider mode/result structs if they need serialization.

- Modify `src-tauri/Cargo.toml`
  - Add only missing Windows API features if the compiler requires them for screenshot capture.

### Optional OCR package

- Create `tools/ocr-sidecar/file_keeper_ocr.py`
  - Reads request JSON from stdin and writes OCR result JSON to stdout.

- Create `tools/ocr-sidecar/requirements.txt`
  - Pins sidecar dependencies.

- Create `tools/ocr-sidecar/build-windows.ps1`
  - Builds `file-keeper-ocr.exe` for the optional OCR package.

- Create `tools/ocr-sidecar/README.md`
  - Documents placing output under `File Keeper install directory/ocr/`.

---

## Task 1: App Settings for Screenshot Shortcut

**Files:**
- Modify: `src/types/settings.ts`
- Modify: `src/stores/settingsStore.ts`
- Modify: `src/components/SettingsDialog.vue`
- Test: `src/components/__tests__/settingsDialog.test.ts`

- [ ] **Step 1: Write the failing settings test**

Add this test to `src/components/__tests__/settingsDialog.test.ts`:

```ts
it('renders and saves screenshot shortcut', async () => {
  const wrapper = mount(SettingsDialog, {
    props: {
      show: true,
      settings: {
        ...defaultSettings(),
        screenshotShortcut: 'CommandOrControl+Shift+X'
      }
    }
  })

  expect(wrapper.text()).toContain('截图快捷键')
  const input = wrapper.get('[data-test="screenshot-shortcut"]')
  expect((input.element as HTMLInputElement).value).toBe('CommandOrControl+Shift+X')

  await input.setValue('CommandOrControl+Alt+S')
  await wrapper.get('[data-test="save-settings"]').trigger('click')

  const payload = wrapper.emitted('save')?.[0]?.[0]
  expect(payload.screenshotShortcut).toBe('CommandOrControl+Alt+S')
})
```

If `defaultSettings()` does not exist in that test file, add this local helper using the current settings shape:

```ts
function defaultSettings() {
  return {
    globalShortcut: 'CommandOrControl+Alt+K',
    clipboardShortcut: 'CommandOrControl+Shift+V',
    screenshotShortcut: 'CommandOrControl+Shift+X',
    minimizeToTray: true,
    theme: 'dark' as const,
    language: 'zh-CN' as const,
    defaultView: 'grid' as const,
    iconMode: 'real' as const,
    modules: [
      { id: 'files' as const, visible: true, order: 0 },
      { id: 'processes' as const, visible: true, order: 1 },
      { id: 'clipboard' as const, visible: true, order: 2 }
    ]
  }
}
```

- [ ] **Step 2: Run the failing test**

Run:

```bash
cd "/c/AI Projects/file-keeper" && npm test -- src/components/__tests__/settingsDialog.test.ts
```

Expected: FAIL because `screenshotShortcut` is not part of settings and the screenshot shortcut input is missing.

- [ ] **Step 3: Add `screenshotShortcut` to the app settings type**

In `src/types/settings.ts`, update `Settings`:

```ts
export interface Settings {
  theme: 'light' | 'dark' | 'auto'
  language: 'zh-CN' | 'en'
  globalShortcut: string
  clipboardShortcut: string
  screenshotShortcut: string
  autoStart: boolean
  minimizeToTray: boolean
  defaultView: 'grid' | 'list'
  itemsPerPage: number
  iconMode: 'real' | 'generic'
  modules: AppModuleSetting[]
}
```

- [ ] **Step 4: Add the default setting**

In `src/stores/settingsStore.ts`, add the default:

```ts
const settings = ref<Settings>({
  theme: 'dark',
  defaultView: 'grid',
  globalShortcut: 'CommandOrControl+Alt+K',
  clipboardShortcut: 'CommandOrControl+Shift+V',
  screenshotShortcut: 'CommandOrControl+Shift+X',
  minimizeToTray: true,
  autoStart: false,
  language: 'zh-CN',
  itemsPerPage: 50,
  iconMode: 'real',
  modules: normalizeModules()
})
```

Keep `loadSettings()` merging into the existing defaults so older persisted settings automatically receive the new key.

- [ ] **Step 5: Add the SettingsDialog input**

In `src/components/SettingsDialog.vue`, add a screenshot shortcut field next to existing shortcut fields:

```vue
<label class="block text-sm">
  {{ t('settings.screenshotShortcut') }}
  <input
    data-test="screenshot-shortcut"
    v-model="local.screenshotShortcut"
    class="mt-1 w-full rounded border px-3 py-2 dark:bg-dark-hover"
    placeholder="CommandOrControl+Shift+X"
  />
</label>
```

Ensure the emitted save payload includes:

```ts
screenshotShortcut: local.value.screenshotShortcut,
```

- [ ] **Step 6: Add i18n keys**

Add to `src/locales/zh-CN.ts` under the settings section used by `SettingsDialog.vue`:

```ts
screenshotShortcut: '截图快捷键'
```

Add to `src/locales/en.ts`:

```ts
screenshotShortcut: 'Screenshot Shortcut'
```

- [ ] **Step 7: Verify the test passes**

Run:

```bash
cd "/c/AI Projects/file-keeper" && npm test -- src/components/__tests__/settingsDialog.test.ts
```

Expected: PASS.

- [ ] **Step 8: Commit checkpoint only if explicitly requested**

Do not commit by default. If the user explicitly requests commits, use:

```bash
git add src/types/settings.ts src/stores/settingsStore.ts src/components/SettingsDialog.vue src/components/__tests__/settingsDialog.test.ts src/locales/zh-CN.ts src/locales/en.ts
git commit -m "feat: add screenshot shortcut setting"
```

---

## Task 2: Screenshot API Types and Frontend Command Wrapper

**Files:**
- Create: `src/types/screenshot.ts`
- Create: `src/api/screenshot.ts`
- Test: `src/api/__tests__/screenshot.test.ts`

- [ ] **Step 1: Write the failing API test**

Create `src/api/__tests__/screenshot.test.ts`:

```ts
import { describe, expect, it, vi } from 'vitest'
import { invoke } from '@tauri-apps/api/core'
import { captureScreenshotRegion, getScreenshotOcrStatus } from '../screenshot'

vi.mock('@tauri-apps/api/core', () => ({
  invoke: vi.fn()
}))

describe('screenshot api', () => {
  it('captures a selected screenshot region', async () => {
    vi.mocked(invoke).mockResolvedValueOnce({ itemId: 'shot-1' })

    const result = await captureScreenshotRegion({ x: 10, y: 20, width: 300, height: 160, scaleFactor: 1 })

    expect(invoke).toHaveBeenCalledWith('capture_screenshot_region', {
      region: { x: 10, y: 20, width: 300, height: 160, scaleFactor: 1 }
    })
    expect(result.itemId).toBe('shot-1')
  })

  it('reads OCR provider status', async () => {
    vi.mocked(invoke).mockResolvedValueOnce({ provider: 'windows_system', available: true })

    const result = await getScreenshotOcrStatus()

    expect(invoke).toHaveBeenCalledWith('get_screenshot_ocr_status')
    expect(result.provider).toBe('windows_system')
  })
})
```

- [ ] **Step 2: Run the failing API test**

Run:

```bash
cd "/c/AI Projects/file-keeper" && npm test -- src/api/__tests__/screenshot.test.ts
```

Expected: FAIL because `src/api/screenshot.ts` does not exist.

- [ ] **Step 3: Create screenshot frontend types**

Create `src/types/screenshot.ts`:

```ts
export interface ScreenshotRegion {
  x: number
  y: number
  width: number
  height: number
  scaleFactor: number
}

export interface ScreenshotCaptureResult {
  itemId: string
}

export type ScreenshotOcrProvider = 'local_sidecar' | 'windows_system' | 'disabled'

export interface ScreenshotOcrStatus {
  provider: ScreenshotOcrProvider
  available: boolean
}
```

- [ ] **Step 4: Create the API wrapper**

Create `src/api/screenshot.ts`:

```ts
import { invoke } from '@tauri-apps/api/core'
import type { ScreenshotCaptureResult, ScreenshotOcrStatus, ScreenshotRegion } from '../types/screenshot'

export async function captureScreenshotRegion(region: ScreenshotRegion): Promise<ScreenshotCaptureResult> {
  return await invoke<ScreenshotCaptureResult>('capture_screenshot_region', { region })
}

export async function getScreenshotOcrStatus(): Promise<ScreenshotOcrStatus> {
  return await invoke<ScreenshotOcrStatus>('get_screenshot_ocr_status')
}
```

- [ ] **Step 5: Verify the API test passes**

Run:

```bash
cd "/c/AI Projects/file-keeper" && npm test -- src/api/__tests__/screenshot.test.ts
```

Expected: PASS.

---

## Task 3: Screenshot Overlay Component

**Files:**
- Create: `src/components/ScreenshotOverlay.vue`
- Test: `src/components/__tests__/screenshotOverlay.test.ts`

- [ ] **Step 1: Write failing overlay tests**

Create `src/components/__tests__/screenshotOverlay.test.ts`:

```ts
import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import ScreenshotOverlay from '../ScreenshotOverlay.vue'

describe('ScreenshotOverlay', () => {
  it('emits selected region after drag selection', async () => {
    const wrapper = mount(ScreenshotOverlay)

    await wrapper.trigger('mousedown', { clientX: 20, clientY: 30 })
    await wrapper.trigger('mousemove', { clientX: 220, clientY: 130 })
    await wrapper.trigger('mouseup', { clientX: 220, clientY: 130 })

    expect(wrapper.emitted('capture')?.[0]?.[0]).toMatchObject({
      x: 20,
      y: 30,
      width: 200,
      height: 100
    })
  })

  it('normalizes drag direction', async () => {
    const wrapper = mount(ScreenshotOverlay)

    await wrapper.trigger('mousedown', { clientX: 220, clientY: 130 })
    await wrapper.trigger('mousemove', { clientX: 20, clientY: 30 })
    await wrapper.trigger('mouseup', { clientX: 20, clientY: 30 })

    expect(wrapper.emitted('capture')?.[0]?.[0]).toMatchObject({
      x: 20,
      y: 30,
      width: 200,
      height: 100
    })
  })

  it('cancels tiny selections', async () => {
    const wrapper = mount(ScreenshotOverlay)

    await wrapper.trigger('mousedown', { clientX: 20, clientY: 30 })
    await wrapper.trigger('mousemove', { clientX: 23, clientY: 35 })
    await wrapper.trigger('mouseup', { clientX: 23, clientY: 35 })

    expect(wrapper.emitted('capture')).toBeUndefined()
    expect(wrapper.emitted('cancel')).toHaveLength(1)
  })

  it('cancels on Escape', async () => {
    const wrapper = mount(ScreenshotOverlay, { attachTo: document.body })

    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }))
    await wrapper.vm.$nextTick()

    expect(wrapper.emitted('cancel')).toHaveLength(1)
    wrapper.unmount()
  })
})
```

- [ ] **Step 2: Run the failing overlay tests**

Run:

```bash
cd "/c/AI Projects/file-keeper" && npm test -- src/components/__tests__/screenshotOverlay.test.ts
```

Expected: FAIL because `ScreenshotOverlay.vue` does not exist.

- [ ] **Step 3: Create the overlay component**

Create `src/components/ScreenshotOverlay.vue`:

```vue
<template>
  <div
    class="fixed inset-0 z-[120] cursor-crosshair bg-black/30"
    tabindex="0"
    @mousedown="startSelection"
    @mousemove="updateSelection"
    @mouseup="finishSelection"
  >
    <div class="pointer-events-none absolute left-4 top-4 rounded bg-black/70 px-3 py-2 text-sm text-white">
      {{ t('screenshot.dragHint') }}
    </div>
    <div
      v-if="selectionBox"
      class="pointer-events-none absolute border border-white bg-white/10 shadow-[0_0_0_9999px_rgba(0,0,0,0.35)]"
      :style="selectionStyle"
    ></div>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useI18n } from '../composables/useI18n'
import type { ScreenshotRegion } from '../types/screenshot'

const emit = defineEmits<{
  capture: [region: ScreenshotRegion]
  cancel: []
}>()

const { t } = useI18n()
const minSize = 8
const start = ref<{ x: number; y: number } | null>(null)
const current = ref<{ x: number; y: number } | null>(null)

const selectionBox = computed(() => {
  if (!start.value || !current.value) return null
  const x = Math.min(start.value.x, current.value.x)
  const y = Math.min(start.value.y, current.value.y)
  const width = Math.abs(current.value.x - start.value.x)
  const height = Math.abs(current.value.y - start.value.y)
  return { x, y, width, height }
})

const selectionStyle = computed(() => {
  const box = selectionBox.value
  if (!box) return {}
  return {
    left: `${box.x}px`,
    top: `${box.y}px`,
    width: `${box.width}px`,
    height: `${box.height}px`
  }
})

function startSelection(event: MouseEvent) {
  start.value = { x: event.clientX, y: event.clientY }
  current.value = { x: event.clientX, y: event.clientY }
}

function updateSelection(event: MouseEvent) {
  if (!start.value) return
  current.value = { x: event.clientX, y: event.clientY }
}

function finishSelection(event: MouseEvent) {
  if (!start.value) return
  current.value = { x: event.clientX, y: event.clientY }
  const box = selectionBox.value
  start.value = null
  current.value = null
  if (!box || box.width < minSize || box.height < minSize) {
    emit('cancel')
    return
  }
  emit('capture', { ...box, scaleFactor: window.devicePixelRatio || 1 })
}

function handleKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape') {
    emit('cancel')
  }
}

onMounted(() => window.addEventListener('keydown', handleKeydown))
onBeforeUnmount(() => window.removeEventListener('keydown', handleKeydown))
</script>
```

- [ ] **Step 4: Add i18n keys**

Add to `src/locales/zh-CN.ts`:

```ts
screenshot: {
  dragHint: '拖拽选择截图区域，按 Esc 取消',
  shortcut: '截图快捷键',
  captureSuccess: '截图已保存到剪贴板历史',
  captureFailed: '截图失败：{error}'
}
```

Add to `src/locales/en.ts`:

```ts
screenshot: {
  dragHint: 'Drag to select a screenshot region. Press Esc to cancel.',
  shortcut: 'Screenshot Shortcut',
  captureSuccess: 'Screenshot saved to clipboard history',
  captureFailed: 'Screenshot failed: {error}'
}
```

- [ ] **Step 5: Verify overlay tests pass**

Run:

```bash
cd "/c/AI Projects/file-keeper" && npm test -- src/components/__tests__/screenshotOverlay.test.ts
```

Expected: PASS.

---

## Task 4: Wire Screenshot Shortcut and Overlay into App

**Files:**
- Modify: `src/App.vue`
- Modify: `src/components/__tests__/appModules.test.ts` or create `src/components/__tests__/appScreenshot.test.ts`

- [ ] **Step 1: Write failing app screenshot behavior test**

Create `src/components/__tests__/appScreenshot.test.ts`:

```ts
import { describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createTestingPinia } from '@pinia/testing'
import App from '../App.vue'
import { useSettingsStore } from '../../stores/settingsStore'
import * as shortcutApi from '../../api/shortcuts'
import * as screenshotApi from '../../api/screenshot'

vi.mock('../../api/shortcuts', () => ({
  registerGlobalShortcut: vi.fn(),
  unregisterGlobalShortcut: vi.fn()
}))

vi.mock('../../api/screenshot', () => ({
  captureScreenshotRegion: vi.fn()
}))

describe('app screenshot shortcut', () => {
  it('registers screenshot shortcut from settings', async () => {
    mount(App, { global: { plugins: [createTestingPinia({ createSpy: vi.fn, stubActions: false })] } })
    const settingsStore = useSettingsStore()
    settingsStore.updateSettings({ screenshotShortcut: 'CommandOrControl+Shift+X' })
    await Promise.resolve()

    expect(shortcutApi.registerGlobalShortcut).toHaveBeenCalledWith(
      'CommandOrControl+Shift+X',
      expect.any(Function)
    )
  })

  it('captures selected overlay region', async () => {
    vi.mocked(screenshotApi.captureScreenshotRegion).mockResolvedValueOnce({ itemId: 'shot-1' })
    const wrapper = mount(App, { global: { plugins: [createTestingPinia({ createSpy: vi.fn, stubActions: false })] } })

    const registered = vi.mocked(shortcutApi.registerGlobalShortcut).mock.calls.find(call => call[0] === 'CommandOrControl+Shift+X')
    const handler = registered?.[1]
    expect(handler).toBeTypeOf('function')

    await handler?.()
    await wrapper.vm.$nextTick()
    wrapper.getComponent({ name: 'ScreenshotOverlay' }).vm.$emit('capture', { x: 1, y: 2, width: 30, height: 40, scaleFactor: 1 })
    await wrapper.vm.$nextTick()

    expect(screenshotApi.captureScreenshotRegion).toHaveBeenCalledWith({ x: 1, y: 2, width: 30, height: 40, scaleFactor: 1 })
  })
})
```

If mounting full `App.vue` is too heavy because of existing mocks, keep the same assertions but add the missing mocks that existing app tests already use.

- [ ] **Step 2: Run the failing app test**

Run:

```bash
cd "/c/AI Projects/file-keeper" && npm test -- src/components/__tests__/appScreenshot.test.ts
```

Expected: FAIL because screenshot shortcut registration and overlay are not wired.

- [ ] **Step 3: Import screenshot pieces in `App.vue`**

Add imports:

```ts
import ScreenshotOverlay from './components/ScreenshotOverlay.vue'
import { captureScreenshotRegion } from './api/screenshot'
import type { ScreenshotRegion } from './types/screenshot'
```

- [ ] **Step 4: Add screenshot overlay state**

In `App.vue` script setup state:

```ts
const isScreenshotOverlayOpen = ref(false)
let screenshotShortcutHandling = false
let registeredScreenshotShortcut: string | null = null
```

- [ ] **Step 5: Render the overlay**

Near existing global components in `App.vue` template:

```vue
<ScreenshotOverlay
  v-if="isScreenshotOverlayOpen"
  @capture="handleScreenshotCapture"
  @cancel="isScreenshotOverlayOpen = false"
/>
```

- [ ] **Step 6: Add screenshot shortcut handlers**

In `App.vue`:

```ts
async function handleScreenshotShortcut() {
  if (screenshotShortcutHandling) return
  screenshotShortcutHandling = true
  try {
    isScreenshotOverlayOpen.value = true
  } finally {
    setTimeout(() => { screenshotShortcutHandling = false }, 300)
  }
}

async function handleScreenshotCapture(region: ScreenshotRegion) {
  isScreenshotOverlayOpen.value = false
  try {
    await captureScreenshotRegion(region)
    await clipboardStore.loadItems()
    alert(t('screenshot.captureSuccess'))
  } catch (error) {
    alert(t('screenshot.captureFailed', { error: error instanceof Error ? error.message : String(error) }))
  }
}
```

Use `alert()` for the first version because `App.vue` already uses alert-based feedback for shortcut conflicts and file/process failures.

- [ ] **Step 7: Add shortcut registration function**

In `App.vue` next to `updateClipboardShortcut`:

```ts
async function updateScreenshotShortcut(desired: string) {
  if (desired === registeredScreenshotShortcut) {
    settingsStore.updateSettings({ screenshotShortcut: desired })
    return
  }

  if (registeredScreenshotShortcut) {
    try {
      await unregisterGlobalShortcut(registeredScreenshotShortcut)
    } catch (error) {
      console.warn(`Failed to unregister "${registeredScreenshotShortcut}", continuing:`, error)
    }
    registeredScreenshotShortcut = null
  }

  if (desired) {
    try {
      await registerGlobalShortcut(desired, handleScreenshotShortcut)
      registeredScreenshotShortcut = desired
      settingsStore.updateSettings({ screenshotShortcut: desired })
    } catch (error) {
      console.error('Failed to register screenshot shortcut:', error)
      settingsStore.updateSettings({ screenshotShortcut: desired })
      alert(`截图快捷键注册失败（可能与系统其他程序冲突）：${error}\n\n请尝试更换其他组合。`)
    }
  } else {
    settingsStore.updateSettings({ screenshotShortcut: '' })
  }
}
```

- [ ] **Step 8: Call screenshot registration on startup, save, and unmount**

In `handleSaveSettings()` add:

```ts
await updateScreenshotShortcut(settings.screenshotShortcut)
```

In `onMounted()` add after clipboard shortcut registration:

```ts
const screenshotShortcut = settingsStore.settings.screenshotShortcut
if (screenshotShortcut) {
  try {
    await registerGlobalShortcut(screenshotShortcut, handleScreenshotShortcut)
    registeredScreenshotShortcut = screenshotShortcut
  } catch (error) {
    console.error('Failed to register screenshot shortcut on startup:', error)
  }
}
```

In `onUnmounted()` add:

```ts
if (registeredScreenshotShortcut) {
  try {
    await unregisterGlobalShortcut(registeredScreenshotShortcut)
  } catch (error) {
    console.warn('Failed to unregister screenshot shortcut on unmount:', error)
  }
  registeredScreenshotShortcut = null
}
```

- [ ] **Step 9: Verify app screenshot tests pass**

Run:

```bash
cd "/c/AI Projects/file-keeper" && npm test -- src/components/__tests__/appScreenshot.test.ts
```

Expected: PASS.

---

## Task 5: Rust Screenshot Command and Types

**Files:**
- Create: `src-tauri/src/commands/screenshot.rs`
- Modify: `src-tauri/src/commands/mod.rs`
- Modify: `src-tauri/src/main.rs`
- Test: `cargo test --manifest-path "/c/AI Projects/file-keeper/src-tauri/Cargo.toml" screenshot_region_rejects_invalid_size`

- [ ] **Step 1: Write failing Rust command tests**

Create `src-tauri/src/commands/screenshot.rs` with tests first:

```rust
use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ScreenshotRegion {
    pub x: f64,
    pub y: f64,
    pub width: f64,
    pub height: f64,
    pub scale_factor: f64,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ScreenshotCaptureResult {
    pub item_id: String,
}

fn physical_region(region: &ScreenshotRegion) -> Result<crate::platform::windows::screenshot::PhysicalScreenRegion, String> {
    if region.width < 8.0 || region.height < 8.0 {
        return Err("截图区域太小".to_string());
    }
    let scale = if region.scale_factor <= 0.0 { 1.0 } else { region.scale_factor };
    Ok(crate::platform::windows::screenshot::PhysicalScreenRegion {
        x: (region.x * scale).round() as i32,
        y: (region.y * scale).round() as i32,
        width: (region.width * scale).round() as i32,
        height: (region.height * scale).round() as i32,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn screenshot_region_rejects_invalid_size() {
        let result = physical_region(&ScreenshotRegion { x: 0.0, y: 0.0, width: 2.0, height: 7.0, scale_factor: 1.0 });
        assert!(result.is_err());
    }

    #[test]
    fn screenshot_region_applies_scale_factor() {
        let result = physical_region(&ScreenshotRegion { x: 10.0, y: 20.0, width: 30.0, height: 40.0, scale_factor: 1.5 }).unwrap();
        assert_eq!(result.x, 15);
        assert_eq!(result.y, 30);
        assert_eq!(result.width, 45);
        assert_eq!(result.height, 60);
    }
}
```

This test intentionally references `crate::platform::windows::screenshot::PhysicalScreenRegion`, which does not exist yet.

- [ ] **Step 2: Run the failing Rust test**

Run:

```bash
cargo test --manifest-path "/c/AI Projects/file-keeper/src-tauri/Cargo.toml" screenshot_region
```

Expected: FAIL because the platform screenshot module/type does not exist.

- [ ] **Step 3: Create platform screenshot type stub**

Create `src-tauri/src/platform/windows/screenshot.rs`:

```rust
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PhysicalScreenRegion {
    pub x: i32,
    pub y: i32,
    pub width: i32,
    pub height: i32,
}

pub fn capture_screen_region(_region: &PhysicalScreenRegion) -> Result<Vec<u8>, String> {
    Err("当前平台截图尚未实现".to_string())
}
```

- [ ] **Step 4: Export the module**

In `src-tauri/src/platform/windows/mod.rs` add:

```rust
pub mod screenshot;
```

- [ ] **Step 5: Verify region tests pass**

Run:

```bash
cargo test --manifest-path "/c/AI Projects/file-keeper/src-tauri/Cargo.toml" screenshot_region
```

Expected: PASS for the pure region conversion tests.

- [ ] **Step 6: Add Tauri command skeletons**

In `src-tauri/src/commands/screenshot.rs`, add:

```rust
use tauri::{AppHandle, Emitter, State};
use crate::clipboard::ClipboardService;

#[tauri::command]
pub fn capture_screenshot_region(
    app: AppHandle,
    region: ScreenshotRegion,
    service: State<'_, ClipboardService>,
) -> Result<ScreenshotCaptureResult, String> {
    let region = physical_region(&region)?;
    let png_bytes = crate::platform::windows::screenshot::capture_screen_region(&region)?;
    let item_id = service.collect_screenshot_bytes_snapshot(&png_bytes)?;
    let _ = app.emit("clipboard://changed", item_id.clone());
    Ok(ScreenshotCaptureResult { item_id })
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ScreenshotOcrStatus {
    pub provider: String,
    pub available: bool,
}

#[tauri::command]
pub fn get_screenshot_ocr_status() -> Result<ScreenshotOcrStatus, String> {
    let status = crate::clipboard::ocr_provider::provider_status()?;
    Ok(ScreenshotOcrStatus { provider: status.provider, available: status.available })
}
```

This will not compile until Task 6 adds `collect_screenshot_bytes_snapshot` and Task 7 adds `ocr_provider`.

- [ ] **Step 7: Register commands**

In `src-tauri/src/commands/mod.rs`:

```rust
pub mod screenshot;
```

In `src-tauri/src/main.rs` import:

```rust
use commands::screenshot::{capture_screenshot_region, get_screenshot_ocr_status};
```

Add to `tauri::generate_handler!`:

```rust
capture_screenshot_region,
get_screenshot_ocr_status,
```

---

## Task 6: Clipboard Service Screenshot Insertion

**Files:**
- Modify: `src-tauri/src/clipboard/mod.rs`
- Test: `src-tauri/src/clipboard/mod.rs`

- [ ] **Step 1: Write failing screenshot insertion tests**

Add tests to `src-tauri/src/clipboard/mod.rs` test module:

```rust
#[test]
fn screenshot_bytes_are_saved_as_cached_image_record() {
    let (service, root) = temp_service();
    let mut settings = service.load_settings().unwrap();
    settings.enable_ocr = false;
    service.save_settings(&settings).unwrap();

    let id = service.collect_screenshot_bytes_snapshot(&test_png_bytes()).unwrap();
    let item = service.get_item_summary(&id).unwrap().unwrap();
    let image_meta = service.get_image_meta(&id).unwrap().unwrap();

    assert_eq!(item.kind, ClipboardKind::Image);
    assert_eq!(item.cache_state, CacheState::Cached);
    assert!(PathBuf::from(&image_meta.0).starts_with(root.join("clipboard-cache").join("images")));
    assert!(PathBuf::from(&image_meta.0).exists());
}

#[test]
fn screenshot_ocr_text_is_saved_as_note() {
    let service = ClipboardService::in_memory().unwrap();
    let id = service.storage.lock().unwrap().insert_image_item("C:/shot.png", 2, 3, "png", 6).unwrap();

    service.update_ocr_text(&id, "截图文字").unwrap();
    let item = service.get_item_summary(&id).unwrap().unwrap();

    assert_eq!(item.note.as_deref(), Some("截图文字"));
}
```

The first test should fail because `collect_screenshot_bytes_snapshot` does not exist.

- [ ] **Step 2: Run the failing test**

Run:

```bash
cargo test --manifest-path "/c/AI Projects/file-keeper/src-tauri/Cargo.toml" screenshot_bytes_are_saved_as_cached_image_record
```

Expected: FAIL because the method does not exist.

- [ ] **Step 3: Implement screenshot byte insertion**

In `src-tauri/src/clipboard/mod.rs`, add:

```rust
pub fn collect_screenshot_bytes_snapshot(&self, png_bytes: &[u8]) -> Result<String, String> {
    let settings = self.load_settings()?;
    let size_bytes = png_bytes.len() as i64;
    if !cache::within_item_size_limit(size_bytes, settings.item_size_limit_mb) {
        return Err("截图超过单条记录大小上限".to_string());
    }
    let Some(cache_dir) = self.effective_cache_dir(&settings) else {
        return Err("截图缓存目录不可用".to_string());
    };
    let target_dir = cache_dir.join("images");
    std::fs::create_dir_all(&target_dir).map_err(|err| err.to_string())?;
    let target_path = target_dir.join(format!("screenshot-{}.png", Uuid::new_v4()));
    std::fs::write(&target_path, png_bytes).map_err(|err| err.to_string())?;
    let image = image::load_from_memory(png_bytes).map_err(|err| err.to_string())?;
    let image_path = target_path.to_string_lossy().to_string();
    let id = self.storage.lock().map_err(|err| err.to_string())?.insert_image_item(&image_path, image.width() as i64, image.height() as i64, "png", size_bytes)?;
    if settings.enable_ocr {
        if let Ok(result) = crate::clipboard::ocr_provider::recognize_image(&image_path) {
            if !result.text.trim().is_empty() {
                let _ = self.update_ocr_text(&id, &result.text);
            }
        }
    }
    Ok(id)
}
```

This references `ocr_provider::recognize_image`, added in Task 7.

- [ ] **Step 4: Temporarily make OCR provider compile with a stub**

If Task 7 is not done yet, create `src-tauri/src/clipboard/ocr_provider.rs` with:

```rust
#[derive(Debug, Clone, Default)]
pub struct OcrResult {
    pub text: String,
    pub engine: String,
    pub elapsed_ms: u128,
}

pub fn recognize_image(_image_path: &str) -> Result<OcrResult, String> {
    Ok(OcrResult::default())
}

pub struct OcrProviderStatus {
    pub provider: String,
    pub available: bool,
}

pub fn provider_status() -> Result<OcrProviderStatus, String> {
    Ok(OcrProviderStatus { provider: "disabled".to_string(), available: false })
}
```

Add to `src-tauri/src/clipboard/mod.rs` module declarations:

```rust
pub mod ocr_provider;
```

- [ ] **Step 5: Verify screenshot insertion test passes**

Run:

```bash
cargo test --manifest-path "/c/AI Projects/file-keeper/src-tauri/Cargo.toml" screenshot_bytes_are_saved_as_cached_image_record
```

Expected: PASS.

---

## Task 7: OCR Provider Selection and Windows Fallback

**Files:**
- Modify: `src-tauri/src/clipboard/ocr_provider.rs`
- Modify: `src-tauri/src/clipboard/ocr.rs`
- Test: `src-tauri/src/clipboard/ocr_provider.rs`

- [ ] **Step 1: Write failing provider tests**

Add tests in `src-tauri/src/clipboard/ocr_provider.rs`:

```rust
#[cfg(test)]
mod tests {
    use super::*;
    use std::path::PathBuf;

    #[test]
    fn provider_prefers_local_sidecar_when_present() {
        let root = std::env::temp_dir().join(format!("file-keeper-ocr-provider-{}", uuid::Uuid::new_v4()));
        let sidecar = root.join("ocr").join(executable_name());
        std::fs::create_dir_all(sidecar.parent().unwrap()).unwrap();
        std::fs::write(&sidecar, "stub").unwrap();

        let provider = select_provider(Some(root.clone()));

        assert_eq!(provider.provider, "local_sidecar");
        assert!(provider.available);
        let _ = std::fs::remove_dir_all(root);
    }

    #[test]
    fn provider_falls_back_when_sidecar_missing() {
        let root = PathBuf::from("C:/definitely/missing/file-keeper");
        let provider = select_provider(Some(root));

        if cfg!(target_os = "windows") {
            assert_eq!(provider.provider, "windows_system");
        } else {
            assert_eq!(provider.provider, "disabled");
        }
    }
}
```

Expected failure: `select_provider` and `executable_name` do not exist.

- [ ] **Step 2: Run failing provider tests**

Run:

```bash
cargo test --manifest-path "/c/AI Projects/file-keeper/src-tauri/Cargo.toml" provider_
```

Expected: FAIL due to missing provider selection functions.

- [ ] **Step 3: Implement provider selection**

Replace `src-tauri/src/clipboard/ocr_provider.rs` with:

```rust
use serde::{Deserialize, Serialize};
use std::io::Write;
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::time::Instant;

#[derive(Debug, Clone, Default, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct OcrResult {
    pub text: String,
    pub engine: String,
    pub elapsed_ms: u128,
    #[serde(default)]
    pub blocks: Vec<OcrBlock>,
}

#[derive(Debug, Clone, Default, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct OcrBlock {
    pub text: String,
    pub confidence: f32,
    pub box_points: Vec<[f32; 2]>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct OcrProviderStatus {
    pub provider: String,
    pub available: bool,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct SidecarRequest<'a> {
    image_path: &'a str,
    language: &'a str,
}

pub fn recognize_image(image_path: &str) -> Result<OcrResult, String> {
    let provider = select_provider(install_dir().ok());
    match provider.provider.as_str() {
        "local_sidecar" => recognize_with_sidecar(image_path),
        "windows_system" => recognize_with_windows(image_path),
        _ => Ok(OcrResult::default()),
    }
}

pub fn provider_status() -> Result<OcrProviderStatus, String> {
    Ok(select_provider(install_dir().ok()))
}

fn select_provider(install_dir: Option<PathBuf>) -> OcrProviderStatus {
    if let Some(root) = install_dir {
        if sidecar_path(&root).exists() {
            return OcrProviderStatus { provider: "local_sidecar".to_string(), available: true };
        }
    }
    if cfg!(target_os = "windows") {
        OcrProviderStatus { provider: "windows_system".to_string(), available: true }
    } else {
        OcrProviderStatus { provider: "disabled".to_string(), available: false }
    }
}

fn recognize_with_sidecar(image_path: &str) -> Result<OcrResult, String> {
    let root = install_dir()?;
    let sidecar = sidecar_path(&root);
    let request = serde_json::to_vec(&SidecarRequest { image_path, language: "zh_en" }).map_err(|err| err.to_string())?;
    let started = Instant::now();
    let mut child = Command::new(sidecar)
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .map_err(|err| err.to_string())?;
    child.stdin.as_mut().ok_or_else(|| "OCR sidecar stdin unavailable".to_string())?.write_all(&request).map_err(|err| err.to_string())?;
    let output = child.wait_with_output().map_err(|err| err.to_string())?;
    if !output.status.success() {
        return Err(String::from_utf8_lossy(&output.stderr).to_string());
    }
    let mut result: OcrResult = serde_json::from_slice(&output.stdout).map_err(|err| err.to_string())?;
    if result.elapsed_ms == 0 {
        result.elapsed_ms = started.elapsed().as_millis();
    }
    if result.engine.is_empty() {
        result.engine = "rapidocr-onnx".to_string();
    }
    Ok(result)
}

fn recognize_with_windows(image_path: &str) -> Result<OcrResult, String> {
    let started = Instant::now();
    let text = crate::clipboard::ocr::recognize_with_windows_system(image_path)?;
    Ok(OcrResult { text, engine: "windows_system".to_string(), elapsed_ms: started.elapsed().as_millis(), blocks: Vec::new() })
}

fn install_dir() -> Result<PathBuf, String> {
    let exe = std::env::current_exe().map_err(|err| err.to_string())?;
    exe.parent().map(Path::to_path_buf).ok_or_else(|| "应用安装目录不可用".to_string())
}

fn sidecar_path(install_dir: &Path) -> PathBuf {
    install_dir.join("ocr").join(executable_name())
}

fn executable_name() -> &'static str {
    if cfg!(target_os = "windows") { "file-keeper-ocr.exe" } else { "file-keeper-ocr" }
}
```

- [ ] **Step 4: Expose Windows OCR fallback function**

In `src-tauri/src/clipboard/ocr.rs`, keep the trait/test, but rename the Windows-backed public function:

```rust
pub fn recognize_with_windows_system(image_path: &str) -> Result<String, String> {
    #[cfg(target_os = "windows")]
    {
        windows_ocr(image_path)
    }
    #[cfg(not(target_os = "windows"))]
    {
        let _ = image_path;
        Ok(String::new())
    }
}
```

If existing code still calls `ocr::recognize_image`, either keep this compatibility wrapper:

```rust
pub fn recognize_image(image_path: &str) -> Result<String, String> {
    recognize_with_windows_system(image_path)
}
```

or migrate those call sites to `ocr_provider::recognize_image`.

- [ ] **Step 5: Verify provider tests pass**

Run:

```bash
cargo test --manifest-path "/c/AI Projects/file-keeper/src-tauri/Cargo.toml" provider_
```

Expected: PASS.

---

## Task 8: Native Windows Screenshot Capture

**Files:**
- Modify: `src-tauri/src/platform/windows/screenshot.rs`
- Test: existing pure screenshot region tests plus manual Windows screenshot test

- [ ] **Step 1: Add an image shape test for PNG validation**

Add to `src-tauri/src/platform/windows/screenshot.rs` tests:

```rust
#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rejects_empty_physical_region() {
        let region = PhysicalScreenRegion { x: 0, y: 0, width: 0, height: 10 };
        assert!(validate_region(&region).is_err());
    }
}
```

- [ ] **Step 2: Run the failing test**

Run:

```bash
cargo test --manifest-path "/c/AI Projects/file-keeper/src-tauri/Cargo.toml" rejects_empty_physical_region
```

Expected: FAIL because `validate_region` does not exist.

- [ ] **Step 3: Implement validation and capture**

Replace `src-tauri/src/platform/windows/screenshot.rs` with a Windows implementation that keeps a non-Windows stub behind cfg:

```rust
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PhysicalScreenRegion {
    pub x: i32,
    pub y: i32,
    pub width: i32,
    pub height: i32,
}

pub fn validate_region(region: &PhysicalScreenRegion) -> Result<(), String> {
    if region.width <= 0 || region.height <= 0 {
        return Err("截图区域无效".to_string());
    }
    Ok(())
}

#[cfg(target_os = "windows")]
pub fn capture_screen_region(region: &PhysicalScreenRegion) -> Result<Vec<u8>, String> {
    validate_region(region)?;
    capture_screen_region_windows(region)
}

#[cfg(not(target_os = "windows"))]
pub fn capture_screen_region(region: &PhysicalScreenRegion) -> Result<Vec<u8>, String> {
    let _ = region;
    Err("当前平台尚未实现截图".to_string())
}

#[cfg(target_os = "windows")]
fn capture_screen_region_windows(region: &PhysicalScreenRegion) -> Result<Vec<u8>, String> {
    use image::{ImageBuffer, ImageFormat, Rgba};
    use std::io::Cursor;
    use windows::Win32::Foundation::HWND;
    use windows::Win32::Graphics::Gdi::{
        BitBlt, CreateCompatibleBitmap, CreateCompatibleDC, DeleteDC, DeleteObject, GetDC, GetDIBits,
        ReleaseDC, SelectObject, BITMAPINFO, BITMAPINFOHEADER, BI_RGB, DIB_RGB_COLORS, HBITMAP, HDC,
        SRCCOPY,
    };

    unsafe {
        let screen_dc = GetDC(HWND(0));
        if screen_dc.0 == 0 {
            return Err("无法获取屏幕 DC".to_string());
        }
        let memory_dc = CreateCompatibleDC(screen_dc);
        if memory_dc.0 == 0 {
            let _ = ReleaseDC(HWND(0), screen_dc);
            return Err("无法创建截图 DC".to_string());
        }
        let bitmap = CreateCompatibleBitmap(screen_dc, region.width, region.height);
        if bitmap.0 == 0 {
            let _ = DeleteDC(memory_dc);
            let _ = ReleaseDC(HWND(0), screen_dc);
            return Err("无法创建截图位图".to_string());
        }
        let old_object = SelectObject(memory_dc, bitmap);
        let blt_ok = BitBlt(memory_dc, 0, 0, region.width, region.height, screen_dc, region.x, region.y, SRCCOPY).as_bool();
        if !blt_ok {
            let _ = SelectObject(memory_dc, old_object);
            let _ = DeleteObject(bitmap);
            let _ = DeleteDC(memory_dc);
            let _ = ReleaseDC(HWND(0), screen_dc);
            return Err("屏幕截图失败".to_string());
        }

        let mut info = BITMAPINFO {
            bmiHeader: BITMAPINFOHEADER {
                biSize: std::mem::size_of::<BITMAPINFOHEADER>() as u32,
                biWidth: region.width,
                biHeight: -region.height,
                biPlanes: 1,
                biBitCount: 32,
                biCompression: BI_RGB.0,
                ..Default::default()
            },
            ..Default::default()
        };
        let mut pixels = vec![0u8; (region.width * region.height * 4) as usize];
        let rows = GetDIBits(
            memory_dc,
            HBITMAP(bitmap.0),
            0,
            region.height as u32,
            Some(pixels.as_mut_ptr() as *mut _),
            &mut info,
            DIB_RGB_COLORS,
        );

        let _ = SelectObject(memory_dc, old_object);
        let _ = DeleteObject(bitmap);
        let _ = DeleteDC(memory_dc);
        let _ = ReleaseDC(HWND(0), screen_dc);

        if rows == 0 {
            return Err("读取截图像素失败".to_string());
        }

        for chunk in pixels.chunks_exact_mut(4) {
            chunk.swap(0, 2);
            chunk[3] = 255;
        }

        let image = ImageBuffer::<Rgba<u8>, _>::from_raw(region.width as u32, region.height as u32, pixels)
            .ok_or_else(|| "截图像素格式无效".to_string())?;
        let mut bytes = Vec::new();
        image.write_to(&mut Cursor::new(&mut bytes), ImageFormat::Png).map_err(|err| err.to_string())?;
        Ok(bytes)
    }
}
```

If the compiler reports missing Win32 GDI imports, add the exact missing feature to `src-tauri/Cargo.toml` under the existing `windows` dependency feature list.

- [ ] **Step 4: Verify Rust screenshot tests pass**

Run:

```bash
cargo test --manifest-path "/c/AI Projects/file-keeper/src-tauri/Cargo.toml" screenshot
```

Expected: PASS for unit tests. Native capture correctness still requires manual app verification.

---

## Task 9: Optional OCR Sidecar Project

**Files:**
- Create: `tools/ocr-sidecar/file_keeper_ocr.py`
- Create: `tools/ocr-sidecar/requirements.txt`
- Create: `tools/ocr-sidecar/build-windows.ps1`
- Create: `tools/ocr-sidecar/README.md`

- [ ] **Step 1: Create the sidecar Python script**

Create `tools/ocr-sidecar/file_keeper_ocr.py`:

```python
import json
import sys
import time

from rapidocr_onnxruntime import RapidOCR


def main() -> int:
    started = time.perf_counter()
    request = json.load(sys.stdin)
    image_path = request["imagePath"]
    engine = RapidOCR()
    result, _ = engine(image_path)
    blocks = []
    texts = []
    for item in result or []:
        box, text, confidence = item
        text = str(text).strip()
        if not text:
            continue
        texts.append(text)
        blocks.append({
            "text": text,
            "confidence": float(confidence),
            "box": [[float(point[0]), float(point[1])] for point in box]
        })
    output = {
        "text": "\n".join(texts),
        "engine": "rapidocr-onnx",
        "elapsedMs": int((time.perf_counter() - started) * 1000),
        "blocks": blocks
    }
    json.dump(output, sys.stdout, ensure_ascii=False)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
```

- [ ] **Step 2: Create requirements**

Create `tools/ocr-sidecar/requirements.txt`:

```text
rapidocr_onnxruntime==1.3.24
onnxruntime==1.18.1
pyinstaller==6.10.0
```

- [ ] **Step 3: Create build script**

Create `tools/ocr-sidecar/build-windows.ps1`:

```powershell
$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$Venv = Join-Path $Root ".venv"
$Dist = Join-Path $Root "dist"

python -m venv $Venv
& "$Venv\Scripts\python.exe" -m pip install --upgrade pip
& "$Venv\Scripts\pip.exe" install -r (Join-Path $Root "requirements.txt")
& "$Venv\Scripts\pyinstaller.exe" --onefile --name file-keeper-ocr (Join-Path $Root "file_keeper_ocr.py")

Write-Host "Built OCR sidecar at $Dist\file-keeper-ocr.exe"
Write-Host "Copy it to: <File Keeper install directory>\ocr\file-keeper-ocr.exe"
```

- [ ] **Step 4: Create sidecar README**

Create `tools/ocr-sidecar/README.md`:

```markdown
# File Keeper Optional OCR Sidecar

This optional package provides local enhanced OCR for File Keeper using RapidOCR and ONNX Runtime.

## Build

```powershell
./build-windows.ps1
```

## Install

Copy the built executable to:

```text
<File Keeper install directory>/ocr/file-keeper-ocr.exe
```

When this file exists, File Keeper uses the local sidecar first. When it is missing, Windows builds fall back to Windows system OCR.
```

- [ ] **Step 5: Manual sidecar smoke test**

Run from `tools/ocr-sidecar` after building:

```powershell
'{"imagePath":"C:/path/to/test.png","language":"zh_en"}' | ./dist/file-keeper-ocr.exe
```

Expected: JSON with `text`, `engine`, `elapsedMs`, and `blocks`.

---

## Task 10: End-to-End Verification

**Files:**
- No new files required.
- Runs tests and app checks.

- [ ] **Step 1: Run frontend screenshot and settings tests**

Run:

```bash
cd "/c/AI Projects/file-keeper" && npm test -- src/api/__tests__/screenshot.test.ts src/components/__tests__/screenshotOverlay.test.ts src/components/__tests__/appScreenshot.test.ts src/components/__tests__/settingsDialog.test.ts
```

Expected: PASS.

- [ ] **Step 2: Run Rust screenshot and clipboard tests**

Run:

```bash
cargo test --manifest-path "/c/AI Projects/file-keeper/src-tauri/Cargo.toml" screenshot
cargo test --manifest-path "/c/AI Projects/file-keeper/src-tauri/Cargo.toml" clipboard
```

Expected: PASS. Existing unused warnings are allowed.

- [ ] **Step 3: Run frontend build**

Run:

```bash
cd "/c/AI Projects/file-keeper" && npm run build
```

Expected: PASS. The existing Vite dynamic/static import warning for `src/api/files.ts` is allowed.

- [ ] **Step 4: Run Rust check**

Run:

```bash
cargo check --manifest-path "/c/AI Projects/file-keeper/src-tauri/Cargo.toml"
```

Expected: PASS. Existing unused warnings are allowed.

- [ ] **Step 5: Manual app verification**

Run:

```bash
cd "/c/AI Projects/file-keeper" && npm run tauri:dev
```

Manual checks:

1. Press `CommandOrControl+Shift+X`.
2. Confirm screenshot overlay appears.
3. Drag a region with visible text.
4. Confirm a new image record appears in clipboard history.
5. Confirm the screenshot preview displays correctly.
6. Without `ocr/file-keeper-ocr.exe`, confirm Windows OCR fallback does not block screenshot saving.
7. Add `ocr/file-keeper-ocr.exe` under the install/dev executable directory's `ocr/` subdirectory and repeat.
8. Confirm OCR text appears in the image record note.
9. Search for OCR text in the clipboard page.
10. Delete the screenshot history item and confirm the cached PNG is removed.
11. Press `Esc` during selection and confirm no record is created.

---

## Self-Review Notes

- Spec coverage: region screenshot, screenshot shortcut, optional OCR sidecar under `ocr/`, Windows OCR fallback, clipboard image insertion, OCR text note/search, deletion cleanup, and verification are all covered.
- No runtime model download is planned.
- The optional OCR package is separate from the main installer; File Keeper detects it from the installation directory's `ocr/` subdirectory.
- The plan intentionally reuses existing `ClipboardSettings.enableOcr` for OCR enable/disable to avoid adding a duplicate screenshot-only OCR toggle. The new required app setting is `screenshotShortcut`; provider mode can be added later if UI selection is needed.
- Commits are listed only as explicit-user-request checkpoints because this session's Git safety rules prohibit committing unless the user asks.
