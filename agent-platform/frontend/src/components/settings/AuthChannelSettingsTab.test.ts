import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import AuthChannelSettingsTab from './AuthChannelSettingsTab.vue'

// 12x：认证通道页——通道类型切换 + SMTP 字段组 + 测试发信按钮

const apiMock = vi.hoisted(() => ({
  getAuthChannelSettings: vi.fn(),
  updateAuthChannelSettings: vi.fn(),
  testMailChannel: vi.fn()
}))
vi.mock('@/api/system', async (importOriginal) => {
  const original = await importOriginal<typeof import('@/api/system')>()
  return {
    ...original,
    systemApi: {
      ...original.systemApi,
      getAuthChannelSettings: apiMock.getAuthChannelSettings,
      updateAuthChannelSettings: apiMock.updateAuthChannelSettings,
      testMailChannel: apiMock.testMailChannel
    }
  }
})
vi.mock('naive-ui', async (importOriginal) => {
  const original = await importOriginal<typeof import('naive-ui')>()
  return { ...original, useMessage: () => ({ success: vi.fn(), error: vi.fn() }) }
})

function settingsOf(provider: 'ALIYUN' | 'SMTP') {
  return {
    data: {
      code: 200, msg: 'success',
      data: {
        mail: {
          enabled: true, provider, region: 'cn-hangzhou', accessKeyId: 'ak', secretConfigured: true,
          accountName: 'noreply@t.com', verifyUrl: 'https://t.com/v', resetUrl: 'https://t.com/r',
          smtpHost: 'smtp.qq.com', smtpPort: 465, smtpSsl: true,
          smtpUsername: 'me@qq.com', smtpPasswordConfigured: true, smtpFromAlias: '平台'
        },
        sms: { enabled: false, secretConfigured: false, codeTtlMinutes: 5, limitPerPhonePerDay: 10, limitPerIpPerDay: 30 }
      }
    }
  }
}

describe('AuthChannelSettingsTab（12x 邮件通道 SMTP）', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('ALIYUN 通道：显示阿里云专属字段，不显示 SMTP 服务器', async () => {
    apiMock.getAuthChannelSettings.mockResolvedValue(settingsOf('ALIYUN'))
    const wrapper = mount(AuthChannelSettingsTab)
    await flushPromises()
    const html = wrapper.html()
    expect(html).toContain('发信地址')
    expect(html).not.toContain('SMTP 服务器')
  })

  it('SMTP 通道：显示 SMTP 字段组与授权码，隐藏阿里云专属字段', async () => {
    apiMock.getAuthChannelSettings.mockResolvedValue(settingsOf('SMTP'))
    const wrapper = mount(AuthChannelSettingsTab)
    await flushPromises()
    const html = wrapper.html()
    expect(html).toContain('SMTP 服务器')
    expect(html).toContain('密码/授权码')
    expect(html).not.toContain('发信地址')
  })

  it('保存：携带 provider/smtp 字段，smtpPassword 留空不进 payload', async () => {
    apiMock.getAuthChannelSettings.mockResolvedValue(settingsOf('SMTP'))
    apiMock.updateAuthChannelSettings.mockResolvedValue(settingsOf('SMTP'))
    const wrapper = mount(AuthChannelSettingsTab)
    await flushPromises()
    const vm = wrapper.vm as unknown as { save: () => Promise<void> }
    await vm.save()
    expect(apiMock.updateAuthChannelSettings).toHaveBeenCalledTimes(1)
    const payload = apiMock.updateAuthChannelSettings.mock.calls[0][0] as {
      mail: Record<string, unknown>
    }
    expect(payload.mail.provider).toBe('SMTP')
    expect(payload.mail.smtpHost).toBe('smtp.qq.com')
    expect(payload.mail.smtpPassword).toBeUndefined()
    expect('smtpPasswordConfigured' in payload.mail).toBe(false)
  })

  it('测试发信：调 mail-test 端点', async () => {
    apiMock.getAuthChannelSettings.mockResolvedValue(settingsOf('SMTP'))
    apiMock.testMailChannel.mockResolvedValue({ data: { code: 200, msg: 'success', data: null } })
    const wrapper = mount(AuthChannelSettingsTab)
    await flushPromises()
    const vm = wrapper.vm as unknown as { mailTestTo: string; sendMailTest: () => Promise<void> }
    vm.mailTestTo = 'target@x.com'
    await vm.sendMailTest()
    expect(apiMock.testMailChannel).toHaveBeenCalledWith('target@x.com')
  })
})
