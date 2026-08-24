import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, mount } from '@vue/test-utils'
import { createTestingPinia } from '@pinia/testing'
import App from '../../App.vue'
import * as shortcutApi from '../../api/shortcuts'
import * as screenshotApi from '../../api/screenshot'
import * as screenshotOverlayApi from '../../api/screenshotOverlay'
import * as commercialAuthApi from '../../api/commercialAuth'
import { useClipboardStore } from '../../stores/clipboardStore'
import { useSettingsStore } from '../../stores/settingsStore'
import type { ScreenshotRegion } from '../../types/screenshot'

const eventHandlers = vi.hoisted(() => new Map<string, (event: { payload: unknown }) => void | Promise<void>>())

const appWindowMock = vi.hoisted(() => ({
  minimize: vi.fn().mockResolvedValue(undefined),
  isMaximized: vi.fn().mockResolvedValue(false),
  maximize: vi.fn().mockResolvedValue(undefined),
  unmaximize: vi.fn().mockResolvedValue(undefined),
  close: vi.fn().mockResolvedValue(undefined),
  hide: vi.fn().mockResolvedValue(undefined),
  show: vi.fn().mockResolvedValue(undefined),
  setFocus: vi.fn().mockResolvedValue(undefined),
  isFullscreen: vi.fn().mockResolvedValue(false),
  setFullscreen: vi.fn().mockResolvedValue(undefined),
  isVisible: vi.fn().mockResolvedValue(true),
  onCloseRequested: vi.fn().mockResolvedValue(() => undefined),
  onDragDropEvent: vi.fn().mockResolvedValue(() => undefined)
}))

vi.mock('@tauri-apps/api/window', () => ({
  getCurrentWindow: () => appWindowMock
}))

vi.mock('@tauri-apps/api/event', () => ({
  listen: vi.fn((event: string, handler: (event: { payload: unknown }) => void | Promise<void>) => {
    eventHandlers.set(event, handler)
    return Promise.resolve(() => eventHandlers.delete(event))
  })
}))

vi.mock('../../api/shortcuts', () => ({
  registerGlobalShortcut: vi.fn().mockResolvedValue(undefined),
  unregisterGlobalShortcut: vi.fn().mockResolvedValue(undefined)
}))

vi.mock('../../api/screenshotOverlay', () => ({
  openScreenshotOverlayWindow: vi.fn().mockResolvedValue(undefined),
  closeScreenshotOverlayWindow: vi.fn().mockResolvedValue(undefined)
}))

vi.mock('../../api/screenshot', () => ({
  captureScreenshotRegion: vi.fn().mockResolvedValue({ itemId: 'shot-1' })
}))

vi.mock('../../api/clipboard', () => ({
  startClipboardMonitor: vi.fn().mockResolvedValue(undefined),
  stopClipboardMonitor: vi.fn().mockResolvedValue(undefined),
  listenClipboardChanged: vi.fn().mockResolvedValue(() => {})
}))

vi.mock('../../api/files', () => ({
  openFile: vi.fn(),
  showInFolder: vi.fn(),
  validatePath: vi.fn().mockResolvedValue(true)
}))

vi.mock('../../api/processes', () => ({
  findFileProcesses: vi.fn().mockResolvedValue([]),
  closeProcess: vi.fn()
}))

vi.mock('@tauri-apps/plugin-store', () => ({
  Store: {
    load: vi.fn().mockResolvedValue({
      get: vi.fn().mockResolvedValue({
        deviceId: 'test-device',
        fingerprintHash: 'test-fp',
        deviceName: 'Test'
      }),
      set: vi.fn(),
      save: vi.fn()
    })
  }
}))

vi.mock('../../api/commercialAuth', () => ({
  getOrCreateDeviceIdentity: vi.fn().mockResolvedValue({
    deviceId: 'test-device',
    fingerprintHash: 'test-fp',
    deviceName: 'Test'
  }),
  startAnonymousTrial: vi.fn().mockResolvedValue({
    deviceId: 'test-device',
    inFullTrial: true,
    trialExpired: false,
    allowedModuleCodes: ['files', 'processes', 'clipboard']
  }),
  getAnonymousAuthorization: vi.fn().mockResolvedValue({
    mode: 'anonymous' as const,
    onlineRequired: true,
    deviceId: 'test-device',
    modules: [
      { moduleCode: 'files' as const, allowed: true, reason: null, expiresAt: null },
      { moduleCode: 'processes' as const, allowed: true, reason: null, expiresAt: null },
      { moduleCode: 'clipboard' as const, allowed: true, reason: null, expiresAt: null }
    ]
  }),
  isCommercialAuthApiError: vi.fn().mockReturnValue(false),
  CommercialAuthApiError: class extends Error {}
}))

class MockIntersectionObserver {
  observe = vi.fn()
  unobserve = vi.fn()
  disconnect = vi.fn()
}

Object.defineProperty(globalThis, 'IntersectionObserver', {
  writable: true,
  configurable: true,
  value: MockIntersectionObserver
})

enableAutoUnmount(afterEach)

beforeEach(() => {
  vi.clearAllMocks()
  eventHandlers.clear()
  appWindowMock.isMaximized.mockResolvedValue(false)
  appWindowMock.isFullscreen.mockResolvedValue(false)
  appWindowMock.isVisible.mockResolvedValue(true)
  vi.stubGlobal('alert', vi.fn())
})

afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

async function waitForScreenshotRegistration(shortcut: string) {
  for (let index = 0; index < 50; index += 1) {
    const registered = vi.mocked(shortcutApi.registerGlobalShortcut).mock.calls.find(call => call[0] === shortcut)
    if (registered) return registered
    await new Promise(resolve => setTimeout(resolve, 10))
  }
  return undefined
}

async function emitScreenshotCapture(region: ScreenshotRegion) {
  await eventHandlers.get('screenshot://capture')?.({ payload: region })
}

async function emitScreenshotCancel() {
  await eventHandlers.get('screenshot://cancel')?.({ payload: null })
}

function mountApp() {
  return mount(App, {
    global: {
      plugins: [createTestingPinia({ createSpy: vi.fn, stubActions: false })],
      stubs: {
        AddFileButton: true,
        GroupManager: true,
        RecentFiles: true,
        ProcessManagement: true,
        ClipboardManagement: true,
        ClipboardQuickPanel: true,
        EditFileDialog: true
      }
    }
  })
}

describe('app screenshot shortcut', () => {
  it('registers the custom screenshot shortcut from settings', async () => {
    const pinia = createTestingPinia({ createSpy: vi.fn, stubActions: false })
    const settingsStore = useSettingsStore()
    settingsStore.updateSettings({ screenshotShortcut: 'CommandOrControl+Alt+S' })

    mount(App, {
      global: {
        plugins: [pinia],
        stubs: {
          AddFileButton: true,
          GroupManager: true,
          RecentFiles: true,
          ProcessManagement: true,
          ClipboardManagement: true,
          ClipboardQuickPanel: true,
          EditFileDialog: true
        }
      }
    })
    await waitForScreenshotRegistration('CommandOrControl+Alt+S')

    expect(shortcutApi.registerGlobalShortcut).toHaveBeenCalledWith('CommandOrControl+Alt+S', expect.any(Function))
    expect(shortcutApi.registerGlobalShortcut).not.toHaveBeenCalledWith('CommandOrControl+Shift+X', expect.any(Function))
  })

  it('waits for persisted settings before registering the screenshot shortcut on startup', async () => {
    let resolvePersistReady!: () => void
    const persistReady = new Promise<void>((resolve) => {
      resolvePersistReady = resolve
    })
    const pinia = createTestingPinia({ createSpy: vi.fn, stubActions: false })
    const settingsStore = useSettingsStore()
    ;(settingsStore as unknown as { $persistReady: Promise<void> }).$persistReady = persistReady

    mount(App, {
      global: {
        plugins: [pinia],
        stubs: {
          AddFileButton: true,
          GroupManager: true,
          RecentFiles: true,
          ProcessManagement: true,
          ClipboardManagement: true,
          ClipboardQuickPanel: true,
          EditFileDialog: true
        }
      }
    })
    for (let index = 0; index < 5; index += 1) {
      await Promise.resolve()
    }
    settingsStore.updateSettings({ screenshotShortcut: 'CommandOrControl+Alt+S' })
    resolvePersistReady()
    const registered = await waitForScreenshotRegistration('CommandOrControl+Alt+S')

    expect(registered).toBeDefined()
    expect(shortcutApi.registerGlobalShortcut).not.toHaveBeenCalledWith('CommandOrControl+Shift+X', expect.any(Function))
  })

  it('listens for screenshot overlay window events on startup', async () => {
    mountApp()
    await waitForScreenshotRegistration('CommandOrControl+Shift+X')

    expect(eventHandlers.has('screenshot://capture')).toBe(true)
    expect(eventHandlers.has('screenshot://cancel')).toBe(true)
  })

  it('opens the dedicated screenshot overlay window without fullscreening the main window', async () => {
    mountApp()
    const registered = await waitForScreenshotRegistration('CommandOrControl+Shift+X')
    const handler = registered?.[1]
    expect(handler).toBeTypeOf('function')

    await handler?.()

    expect(screenshotOverlayApi.openScreenshotOverlayWindow).toHaveBeenCalledOnce()
    expect(appWindowMock.show).not.toHaveBeenCalled()
    expect(appWindowMock.setFocus).not.toHaveBeenCalled()
    expect(appWindowMock.setFullscreen).not.toHaveBeenCalled()
  })

  it('opens the screenshot overlay even when the legacy commercial snapshot denies clipboard access', async () => {
    vi.mocked(commercialAuthApi.getAnonymousAuthorization).mockResolvedValueOnce({
      mode: 'anonymous',
      onlineRequired: true,
      deviceId: 'test-device',
      modules: [
        { moduleCode: 'clipboard', allowed: false, reason: 'legacy entitlement denied', expiresAt: null }
      ]
    })
    mountApp()
    const registered = await waitForScreenshotRegistration('CommandOrControl+Shift+X')
    const handler = registered?.[1]
    expect(handler).toBeTypeOf('function')

    await handler?.()

    expect(screenshotOverlayApi.openScreenshotOverlayWindow).toHaveBeenCalledOnce()
  })

  it('closes the overlay window before capture and refreshes clipboard on screenshot success', async () => {
    mountApp()
    const clipboardStore = useClipboardStore()
    const loadItemsSpy = vi.spyOn(clipboardStore, 'loadItems').mockResolvedValue()
    const region = { x: 1, y: 2, width: 30, height: 40, scaleFactor: 1 }
    const registered = await waitForScreenshotRegistration('CommandOrControl+Shift+X')
    const handler = registered?.[1]
    expect(handler).toBeTypeOf('function')

    await handler?.()
    await emitScreenshotCapture(region)
    await vi.waitFor(() => expect(screenshotApi.captureScreenshotRegion).toHaveBeenCalled())

    expect(screenshotOverlayApi.closeScreenshotOverlayWindow).toHaveBeenCalledOnce()
    expect(screenshotApi.captureScreenshotRegion).toHaveBeenCalledWith(region)
    expect(loadItemsSpy).toHaveBeenCalledOnce()
    expect(alert).toHaveBeenCalledWith('截图已保存到剪贴板历史')
  })

  it('closes the overlay window without capture on screenshot cancel', async () => {
    mountApp()
    const registered = await waitForScreenshotRegistration('CommandOrControl+Shift+X')
    const handler = registered?.[1]
    expect(handler).toBeTypeOf('function')

    await handler?.()
    await emitScreenshotCancel()

    expect(screenshotOverlayApi.closeScreenshotOverlayWindow).toHaveBeenCalledOnce()
    expect(screenshotApi.captureScreenshotRegion).not.toHaveBeenCalled()
  })

  it('ignores repeated screenshot shortcuts while the overlay window is open', async () => {
    mountApp()
    const registered = await waitForScreenshotRegistration('CommandOrControl+Shift+X')
    const handler = registered?.[1]
    expect(handler).toBeTypeOf('function')

    await handler?.()
    await handler?.()

    expect(screenshotOverlayApi.openScreenshotOverlayWindow).toHaveBeenCalledOnce()

    await emitScreenshotCancel()
    await handler?.()

    expect(screenshotOverlayApi.openScreenshotOverlayWindow).toHaveBeenCalledTimes(2)
  })

  it('shows the captured screenshot immediately in the clipboard tab', async () => {
    const wrapper = mountApp()
    const clipboardStore = useClipboardStore()
    const loadItemsSpy = vi.spyOn(clipboardStore, 'loadItems').mockResolvedValue()
    const loadDetailSpy = vi.spyOn(clipboardStore, 'loadDetail').mockImplementation(async (id) => {
      clipboardStore.selectedItemId = id
      return { id } as any
    })
    clipboardStore.searchQuery = 'previous filter'
    clipboardStore.kindFilter = 'text'
    clipboardStore.favoriteOnly = true
    clipboardStore.datePreset = 'today'
    vi.mocked(screenshotApi.captureScreenshotRegion).mockResolvedValueOnce({ itemId: 'shot-1' })
    const registered = await waitForScreenshotRegistration('CommandOrControl+Shift+X')
    const handler = registered?.[1]
    expect(handler).toBeTypeOf('function')

    await handler?.()
    await emitScreenshotCapture({ x: 1, y: 2, width: 30, height: 40, scaleFactor: 1 })
    await vi.waitFor(() => expect(loadDetailSpy).toHaveBeenCalledWith('shot-1'))

    expect((wrapper.vm as any).currentTab).toBe('clipboard')
    expect(clipboardStore.searchQuery).toBe('')
    expect(clipboardStore.kindFilter).toBe('all')
    expect(clipboardStore.favoriteOnly).toBe(false)
    expect(clipboardStore.datePreset).toBe('all')
    expect(loadItemsSpy).toHaveBeenCalledOnce()
    expect(clipboardStore.selectedItemId).toBe('shot-1')
  })

  it('keeps screenshot success feedback when clipboard refresh fails', async () => {
    mountApp()
    const clipboardStore = useClipboardStore()
    vi.spyOn(clipboardStore, 'loadItems').mockRejectedValue(new Error('reload failed'))
    const registered = await waitForScreenshotRegistration('CommandOrControl+Shift+X')
    const handler = registered?.[1]
    expect(handler).toBeTypeOf('function')

    await handler?.()
    await emitScreenshotCapture({ x: 1, y: 2, width: 30, height: 40, scaleFactor: 1 })
    await vi.waitFor(() => expect(alert).toHaveBeenCalledWith('截图已保存到剪贴板历史'))

    expect(screenshotApi.captureScreenshotRegion).toHaveBeenCalledWith({ x: 1, y: 2, width: 30, height: 40, scaleFactor: 1 })
    expect(alert).toHaveBeenCalledWith('截图已保存到剪贴板历史')
    expect(alert).not.toHaveBeenCalledWith(expect.stringContaining('截图失败'))
  })
})
