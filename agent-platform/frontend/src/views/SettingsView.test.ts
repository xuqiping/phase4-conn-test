import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import SettingsView from './SettingsView.vue'
import { useAuthStore } from '@/stores/auth'

// 子组件都 mock 掉，避免触发它们的 API 调用
vi.mock('@/components/settings/ProviderManageTab.vue', () => ({
  default: { template: '<div data-testid="global-tab"/>' }
}))
vi.mock('@/components/settings/AuthSettingsTab.vue', () => ({
  default: { template: '<div/>' }
}))
vi.mock('@/components/settings/AuthChannelSettingsTab.vue', () => ({
  default: { template: '<div/>' }
}))
vi.mock('@/components/settings/BillingSettingsTab.vue', () => ({
  default: { template: '<div/>' }
}))
vi.mock('@/components/settings/RagRecallSettingsTab.vue', () => ({
  default: { template: '<div/>' }
}))
vi.mock('@/components/settings/WebSearchSettingsTab.vue', () => ({
  default: { template: '<div/>' }
}))
vi.mock('@/components/settings/SecuritySettingsTab.vue', () => ({
  default: { template: '<div/>' }
}))

describe('SettingsView（问题 10x-1）', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('不再展示「我的模型」Tab（入口移除）', () => {
    const auth = useAuthStore()
    auth.userInfo = {
      id: 1, username: 'admin', email: null, avatar: null,
      roles: ['admin'], permissions: []
    }
    const wrapper = mount(SettingsView)
    const html = wrapper.html()
    expect(html).not.toContain('我的模型')
    // admin 仍可见其余 Tab
    expect(html).toContain('全局模型供应商')
    expect(html).toContain('认证设置')
    expect(html).toContain('认证通道')
  })
})
