import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { createTestingPinia } from '@pinia/testing'
import App from '../../App.vue'
import { useClipboardStore } from '../../stores/clipboardStore'

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
  listen: vi.fn().mockResolvedValue(() => undefined)
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
  listenClipboardChanged: vi.fn().mockResolvedValue(() => undefined)
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
  vi.stubGlobal('alert', vi.fn())
})

afterEach(() => {
  vi.unstubAllGlobals()
})

function mountLoggedOutApp() {
  return mount(App, {
    global: {
      plugins: [createTestingPinia({ createSpy: vi.fn })],
      stubs: {
        AddFileButton: { template: '<div data-test="files-module" />' },
        GroupManager: true,
        RecentFiles: true,
        ProcessManagement: { template: '<div data-test="processes-module" />' },
        ClipboardManagement: { template: '<div data-test="clipboard-module" />' },
        ClipboardQuickPanel: true,
        EditFileDialog: true,
        SettingsDialog: true,
        EntitlementStatus: true,
        FreeModuleSelector: true,
        WorkReportManagement: { template: '<div data-test="work-report-module" />' },
        AuthDialog: {
          props: ['show'],
          template: '<div v-if="show" data-test="auth-dialog" />'
        }
      }
    }
  })
}

function tabButton(wrapper: ReturnType<typeof mount>, label: string) {
  const button = wrapper.findAll('button').find(candidate => candidate.text().includes(label))
  if (!button) {
    throw new Error(`找不到标签按钮：${label}`)
  }
  return button
}

describe('App access mode', () => {
  it('keeps every local module available while logged out and commercially unauthorized', async () => {
    const wrapper = mountLoggedOutApp()

    expect(wrapper.find('[data-test="files-module"]').exists()).toBe(true)

    await tabButton(wrapper, '进程').trigger('click')
    expect(wrapper.find('[data-test="processes-module"]').exists()).toBe(true)

    await tabButton(wrapper, '剪贴板').trigger('click')
    expect(wrapper.find('[data-test="clipboard-module"]').exists()).toBe(true)
  })

  it('starts clipboard monitoring once on startup without commercial authorization', async () => {
    mountLoggedOutApp()
    const clipboardStore = useClipboardStore()

    await flushPromises()

    expect(clipboardStore.startMonitor).toHaveBeenCalledTimes(1)
  })

  it('shows a keyboard-accessible login prompt instead of mounting work reports while logged out', async () => {
    const wrapper = mountLoggedOutApp()

    await tabButton(wrapper, '工作汇报').trigger('click')

    expect(wrapper.find('[data-test="work-report-module"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="work-report-login-prompt"]').exists()).toBe(true)
    const loginButton = wrapper.get('[data-test="work-report-login-button"]')
    expect(loginButton.element.tagName).toBe('BUTTON')
    expect(loginButton.text()).toContain('登录')

    await loginButton.trigger('click')
    expect(wrapper.find('[data-test="auth-dialog"]').exists()).toBe(true)
  })
})
