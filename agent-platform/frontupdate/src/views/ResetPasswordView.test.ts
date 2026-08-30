import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ResetPasswordView from './ResetPasswordView.vue'

// D5（Q8-2）：无 token 直达 reset 页不再落 SMS 死路表单，分支由认证通道开关驱动

const channelsMock = vi.hoisted(() => vi.fn())
const routeState = vi.hoisted(() => ({ query: {} as Record<string, string> }))
vi.mock('@/api/auth', () => ({
  authApi: { getChannels: channelsMock, resetPassword: vi.fn() }
}))
vi.mock('@/layouts/AuthLayout.vue', () => ({
  default: { name: 'AuthLayout', template: '<div><slot /></div>' }
}))
vi.mock('vue-router', () => ({
  useRoute: () => ({ query: routeState.query }),
  useRouter: () => ({ push: vi.fn() })
}))
vi.mock('naive-ui', async (importOriginal) => {
  const original = await importOriginal<typeof import('naive-ui')>()
  return { ...original, useMessage: () => ({ success: vi.fn(), error: vi.fn() }) }
})

function channels(emailEnabled: boolean, smsEnabled: boolean) {
  return {
    data: {
      data: {
        passwordEnabled: true, emailEnabled, smsEnabled,
        wechatEnabled: false, registerEmailCodeRequired: false
      }
    }
  }
}

async function mountView(query: Record<string, string>) {
  routeState.query = query
  return mount(ResetPasswordView, {
    global: { stubs: { RouterLink: true } }
  })
}

describe('ResetPasswordView（D5：通道开关驱动的分支）', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    routeState.query = {}
  })

  it('带 token → EMAIL 表单（邮件开关关也不拦——token 即凭证）', async () => {
    channelsMock.mockResolvedValue(channels(false, false))
    const wrapper = await mountView({ token: 'tok123' })
    await flushPromises()
    expect(wrapper.text()).toContain('新密码')
    expect(wrapper.text()).not.toContain('手机号')
  })

  it('channel=SMS 且短信开 → SMS 表单', async () => {
    channelsMock.mockResolvedValue(channels(true, true))
    const wrapper = await mountView({ channel: 'sms' })
    await flushPromises()
    expect(wrapper.text()).toContain('手机号')
    expect(wrapper.text()).toContain('重置验证码')
  })

  it('无 token 且邮件开 → 引导页（邮件链接进入 + 去登录页重发），不露 SMS 表单', async () => {
    channelsMock.mockResolvedValue(channels(true, false))
    const wrapper = await mountView({})
    await flushPromises()
    expect(wrapper.text()).toContain('请通过重置邮件中的链接进入')
    expect(wrapper.text()).toContain('去登录页重新发送')
    expect(wrapper.text()).not.toContain('手机号')
  })

  it('无 token 且邮件关 → 提示联系管理员', async () => {
    channelsMock.mockResolvedValue(channels(false, false))
    const wrapper = await mountView({})
    await flushPromises()
    expect(wrapper.text()).toContain('请联系管理员重置密码')
    expect(wrapper.text()).not.toContain('去登录页重新发送')
  })

  it('channels 接口失败 → 全开兜底不砖页（无 token 落引导页）', async () => {
    channelsMock.mockRejectedValue(new Error('net down'))
    const wrapper = await mountView({})
    await flushPromises()
    expect(wrapper.text()).toContain('请通过重置邮件中的链接进入')
  })
})
