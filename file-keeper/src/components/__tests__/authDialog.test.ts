import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, mount } from '@vue/test-utils'
import { createTestingPinia } from '@pinia/testing'
import { nextTick } from 'vue'
import App from '../../App.vue'
import { useAuthStore } from '../../stores/authStore'
import { useCommercialAuthStore } from '../../stores/commercialAuthStore'

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

vi.mock('../../api/screenshotOverlay', () => ({
  openScreenshotOverlayWindow: vi.fn().mockResolvedValue(undefined),
  closeScreenshotOverlayWindow: vi.fn().mockResolvedValue(undefined)
}))

vi.mock('../../api/screenshot', () => ({
  captureScreenshotRegion: vi.fn().mockResolvedValue({ itemId: 'shot-1' })
}))

vi.mock('@tauri-apps/plugin-store', () => ({
  Store: {
    load: vi.fn().mockResolvedValue({
      get: vi.fn().mockResolvedValue(null),
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
  vi.stubGlobal('alert', vi.fn())
})

afterEach(() => {
  vi.unstubAllGlobals()
})

async function flushMountedWork() {
  await nextTick()
  for (let index = 0; index < 10; index += 1) {
    await Promise.resolve()
  }
}

function mountApp() {
  const pinia = createTestingPinia({ createSpy: vi.fn, stubActions: true })
  const authStore = useAuthStore()
  const commercialAuthStore = useCommercialAuthStore()
  return {
    wrapper: mount(App, {
      global: {
        plugins: [pinia],
        stubs: {
          AddFileButton: true,
          GroupManager: true,
          RecentFiles: true,
          ProcessManagement: true,
          ClipboardManagement: true,
          ClipboardQuickPanel: true,
          EditFileDialog: true,
          SettingsDialog: true
        }
      }
    }),
    authStore,
    commercialAuthStore
  }
}

describe('desktop account authentication UI', () => {
  it('shows Login in the top bar when signed out and opens the login dialog', async () => {
    const { wrapper } = mountApp()
    await flushMountedWork()

    const loginButton = wrapper.get('[data-test="account-login-button"]')
    expect(loginButton.text()).toContain('登录')

    await loginButton.trigger('click')

    expect(wrapper.get('[data-test="auth-dialog"]').text()).toContain('账号登录')
  })

  it('calls authStore.login from the login form and closes after success', async () => {
    const { wrapper, authStore } = mountApp()
    await flushMountedWork()
    vi.mocked(authStore.login).mockResolvedValue(undefined)

    await wrapper.get('[data-test="account-login-button"]').trigger('click')
    await wrapper.get('[data-test="login-identifier"]').setValue('user@example.com')
    await wrapper.get('[data-test="login-password"]').setValue('secret-pass')
    await wrapper.get('[data-test="login-submit"]').trigger('submit')
    await flushMountedWork()

    expect(authStore.login).toHaveBeenCalledWith('http://localhost:8088', 'user@example.com', 'secret-pass')
    expect(wrapper.find('[data-test="auth-dialog"]').exists()).toBe(false)
  })

  it('sends, checks, and submits registration with an email contact', async () => {
    const { wrapper, authStore } = mountApp()
    await flushMountedWork()
    vi.mocked(authStore.sendVerificationCode).mockResolvedValue(undefined)
    vi.mocked(authStore.checkVerificationCode).mockResolvedValue(true)
    vi.mocked(authStore.register).mockResolvedValue({
      id: 1,
      email: 'new@example.com',
      phone: null,
      role: 'USER',
      status: 'pending_review',
      emailVerified: true,
      phoneVerified: false,
      deviceLimit: 1,
      offlineCacheMinutes: 0
    })

    await wrapper.get('[data-test="account-login-button"]').trigger('click')
    await wrapper.get('[data-test="auth-register-tab"]').trigger('click')
    await wrapper.get('[data-test="register-contact-type"]').setValue('email')
    await wrapper.get('[data-test="register-contact"]').setValue('new@example.com')
    await wrapper.get('[data-test="register-password"]').setValue('secret-pass')
    await wrapper.get('[data-test="register-code"]').setValue('123456')

    await wrapper.get('[data-test="register-send-code"]').trigger('click')
    expect(authStore.sendVerificationCode).toHaveBeenCalledWith('http://localhost:8088', {
      contactType: 'email',
      contact: 'new@example.com'
    })

    await wrapper.get('[data-test="register-check-code"]').trigger('click')
    expect(authStore.checkVerificationCode).toHaveBeenCalledWith('http://localhost:8088', {
      contactType: 'email',
      contact: 'new@example.com',
      code: '123456'
    })

    await wrapper.get('[data-test="register-submit"]').trigger('submit')
    await flushMountedWork()

    expect(authStore.register).toHaveBeenCalledWith('http://localhost:8088', {
      email: 'new@example.com',
      phone: null,
      password: 'secret-pass'
    })
    expect(wrapper.get('[data-test="register-success-message"]').text()).toContain('注册成功，等待管理员审核')
  })

  it('requires verification code check before registration submit', async () => {
    const { wrapper, authStore } = mountApp()
    await flushMountedWork()

    await wrapper.get('[data-test="account-login-button"]').trigger('click')
    await wrapper.get('[data-test="auth-register-tab"]').trigger('click')
    await wrapper.get('[data-test="register-contact"]').setValue('new@example.com')
    await wrapper.get('[data-test="register-password"]').setValue('secret-pass')
    await wrapper.get('[data-test="register-code"]').setValue('123456')
    await wrapper.get('[data-test="register-submit"]').trigger('submit')
    await flushMountedWork()

    expect(authStore.register).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('请先校验验证码')
  })

  it('shows the account label when signed in and logs out from the top bar', async () => {
    const { wrapper, authStore } = mountApp()
    authStore.user = {
      id: 1,
      email: 'signed@example.com',
      phone: null,
      role: 'USER',
      status: 'active',
      emailVerified: true,
      phoneVerified: false,
      deviceLimit: 1,
      offlineCacheMinutes: 0
    }
    authStore.accessToken = 'access-token'
    authStore.refreshToken = 'refresh-token'
    vi.mocked(authStore.logout).mockResolvedValue(undefined)
    await flushMountedWork()

    expect(wrapper.get('[data-test="account-label"]').text()).toContain('signed@example.com')

    await wrapper.get('[data-test="account-logout-button"]').trigger('click')
    await flushMountedWork()

    expect(authStore.logout).toHaveBeenCalledWith('http://localhost:8088')
  })

  it('restores the auth session on startup instead of directly initializing anonymous commercial auth', async () => {
    const { authStore, commercialAuthStore } = mountApp()
    await flushMountedWork()

    expect(authStore.restoreSession).toHaveBeenCalledWith('http://localhost:8088')
    expect(commercialAuthStore.initializeAnonymous).not.toHaveBeenCalled()
  })
})
