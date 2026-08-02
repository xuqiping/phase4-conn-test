import { describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createTestingPinia } from '@pinia/testing'
import EntitlementStatus from '../EntitlementStatus.vue'
import { useAuthStore } from '../../stores/authStore'
import { useCommercialAuthStore } from '../../stores/commercialAuthStore'
import type { ModuleCode } from '../../api/commercialAuth'

const MODULES: ModuleCode[] = ['files', 'processes', 'clipboard']

function mountStatus() {
  const wrapper = mount(EntitlementStatus, {
    global: {
      plugins: [createTestingPinia({ createSpy: vi.fn, stubActions: true })]
    }
  })
  return {
    wrapper,
    authStore: useAuthStore(),
    commercialAuthStore: useCommercialAuthStore()
  }
}

function signIn(status = 'active') {
  const authStore = useAuthStore()
  authStore.user = {
    id: 1,
    email: 'user@example.com',
    phone: null,
    role: 'USER',
    status,
    emailVerified: true,
    phoneVerified: false,
    deviceLimit: 1,
    offlineCacheMinutes: 60
  }
  authStore.accessToken = 'access-token'
  authStore.refreshToken = 'refresh-token'
}

function authenticatedAuthorization(options: {
  accountStatus?: string
  offlineUsableUntil?: string | null
  modules?: Array<{ moduleCode: ModuleCode; allowed: boolean; reason: string | null }>
} = {}) {
  const commercialAuthStore = useCommercialAuthStore()
  commercialAuthStore.clientAuthorization = {
    mode: 'authenticated',
    userId: 1,
    accountStatus: options.accountStatus ?? 'active',
    deviceLimit: 1,
    onlineRequired: false,
    offlineUsableUntil: options.offlineUsableUntil ?? null,
    deviceBinding: {
      deviceId: 'device-1',
      bound: true,
      active: true
    },
    modules: (options.modules ?? MODULES.map(moduleCode => ({ moduleCode, allowed: true, reason: null }))).map(module => ({
      ...module,
      expiresAt: null
    }))
  }
}

describe('EntitlementStatus', () => {
  it('shows anonymous full trial status', async () => {
    const { wrapper, commercialAuthStore } = mountStatus()

    commercialAuthStore.trialStatus = {
      deviceId: 'device-1',
      inFullTrial: true,
      trialExpired: false,
      trialExpiresAt: '2026-06-18T00:00:00Z',
      allowedModuleCodes: ['files', 'processes', 'clipboard']
    }
    await wrapper.vm.$nextTick()

    expect(wrapper.get('[data-test="entitlement-status-title"]').text()).toContain('匿名 7 天全功能试用中')
    expect(wrapper.text()).toContain('试用截止')
  })

  it('shows anonymous expired status when no free module has been selected', async () => {
    const { wrapper, commercialAuthStore } = mountStatus()

    commercialAuthStore.trialStatus = {
      deviceId: 'device-1',
      inFullTrial: false,
      trialExpired: true,
      freeModuleCode: null,
      allowedModuleCodes: []
    }
    await wrapper.vm.$nextTick()

    expect(wrapper.get('[data-test="entitlement-status-title"]').text()).toContain('匿名试用已过期')
    expect(wrapper.text()).toContain('未选择免费模块')
  })

  it('shows selected anonymous free module', async () => {
    const { wrapper, commercialAuthStore } = mountStatus()

    commercialAuthStore.trialStatus = {
      deviceId: 'device-1',
      inFullTrial: false,
      trialExpired: true,
      freeModuleCode: 'clipboard',
      allowedModuleCodes: ['clipboard']
    }
    await wrapper.vm.$nextTick()

    expect(wrapper.get('[data-test="entitlement-status-title"]').text()).toContain('匿名免费模块')
    expect(wrapper.text()).toContain('剪贴板')
  })

  it('shows pending review for signed in users waiting for admin approval', async () => {
    const { wrapper } = mountStatus()

    signIn('pending_review')
    authenticatedAuthorization({ accountStatus: 'pending_review', modules: [] })
    await wrapper.vm.$nextTick()

    expect(wrapper.get('[data-test="entitlement-status-title"]').text()).toContain('等待管理员审核')
    expect(wrapper.text()).toContain('pending_review')
  })

  it('shows authenticated module authorization states', async () => {
    const { wrapper } = mountStatus()

    signIn()
    authenticatedAuthorization({
      modules: [
        { moduleCode: 'files', allowed: true, reason: null },
        { moduleCode: 'processes', allowed: false, reason: '未授权' },
        { moduleCode: 'clipboard', allowed: true, reason: null }
      ]
    })
    await wrapper.vm.$nextTick()

    expect(wrapper.get('[data-test="entitlement-status-title"]').text()).toContain('商业授权')
    expect(wrapper.get('[data-test="entitlement-module-files"]').text()).toContain('已授权')
    expect(wrapper.get('[data-test="entitlement-module-processes"]').text()).toContain('未授权')
    expect(wrapper.get('[data-test="entitlement-module-clipboard"]').text()).toContain('已授权')
  })

  it('shows authenticated offline cache deadline', async () => {
    const { wrapper } = mountStatus()

    signIn()
    authenticatedAuthorization({ offlineUsableUntil: '2026-06-30T12:00:00Z' })
    await wrapper.vm.$nextTick()

    expect(wrapper.text()).toContain('离线缓存至')
    expect(wrapper.text()).toContain('2026')
  })

  it('shows commercial authorization errors from account or device rejection', async () => {
    const { wrapper, commercialAuthStore } = mountStatus()

    commercialAuthStore.error = '设备已被管理员禁用'
    await wrapper.vm.$nextTick()

    expect(wrapper.get('[data-test="entitlement-status-title"]').text()).toContain('授权异常')
    expect(wrapper.text()).toContain('设备已被管理员禁用')
  })

  it('does not show stale pending review authorization after signing out', async () => {
    const { wrapper, commercialAuthStore } = mountStatus()

    authenticatedAuthorization({ accountStatus: 'pending_review', modules: [] })
    commercialAuthStore.trialStatus = {
      deviceId: 'device-1',
      inFullTrial: true,
      trialExpired: false,
      allowedModuleCodes: ['files', 'processes', 'clipboard']
    }
    await wrapper.vm.$nextTick()

    expect(wrapper.get('[data-test="entitlement-status-title"]').text()).toContain('匿名 7 天全功能试用中')
    expect(wrapper.text()).not.toContain('等待管理员审核')
  })

  it('does not fall back to anonymous status while an authenticated session is waiting for authorization', async () => {
    const { wrapper, commercialAuthStore } = mountStatus()

    signIn()
    commercialAuthStore.trialStatus = {
      deviceId: 'device-1',
      inFullTrial: true,
      trialExpired: false,
      allowedModuleCodes: ['files', 'processes', 'clipboard']
    }
    await wrapper.vm.$nextTick()

    expect(wrapper.get('[data-test="entitlement-status-title"]').text()).toContain('授权状态初始化中')
    expect(wrapper.text()).not.toContain('匿名 7 天全功能试用中')
  })

  it('does not apply frontend offlineUsableUntil expiration check', async () => {
    const { wrapper } = mountStatus()

    signIn()
    authenticatedAuthorization({ offlineUsableUntil: '2020-01-01T00:00:00Z' })
    await wrapper.vm.$nextTick()

    // 过期判定已下沉到 Rust 侧，前端 UI 不再根据 offlineUsableUntil 隐藏授权状态
    expect(wrapper.get('[data-test="entitlement-status-title"]').text()).toContain('商业授权')
    expect(wrapper.find('[data-test="entitlement-module-files"]').exists()).toBe(true)
  })
})
