import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import RegisterModal from './RegisterModal.vue'

// 12x 开关回退：注册弹窗按「邮箱验证总开关」显隐验证码行 + 邮箱必填性

const registerMock = vi.hoisted(() => vi.fn())
vi.mock('@/stores/auth', async (importOriginal) => {
  const original = await importOriginal<typeof import('@/stores/auth')>()
  return {
    ...original,
    useAuthStore: () => ({ register: registerMock, loading: false })
  }
})
vi.mock('naive-ui', async (importOriginal) => {
  const original = await importOriginal<typeof import('naive-ui')>()
  return { ...original, useMessage: () => ({ success: vi.fn(), error: vi.fn(), warning: vi.fn() }) }
})

function mountModal(emailCodeRequired: boolean) {
  return mount(RegisterModal, {
    // NModal 真实组件走 teleport → 必须 attachTo body，DOM 断言用 document.body
    attachTo: document.body,
    props: { show: true, emailCodeRequired },
    global: {
      stubs: {
        // 滑块组件依赖后端 captcha 接口，打桩
        SliderCaptcha: true,
        RouterLink: true
      }
    }
  })
}

async function fillAndSubmit(wrapper: ReturnType<typeof mountModal>) {
  const vm = wrapper.vm as unknown as {
    form: { username: string; name: string; email: string; password: string; confirmPassword: string; agreeTerms: boolean }
    handleRegister: () => Promise<void>
  }
  vm.form.username = 'newbie'
  vm.form.name = '新人' // 17x：昵称/姓名必填，漏填表单校验拦截不触达 register
  vm.form.password = 'Str0ng#Pass'
  vm.form.confirmPassword = 'Str0ng#Pass'
  vm.form.agreeTerms = true
  await vm.handleRegister()
  await flushPromises()
  return vm
}

describe('RegisterModal（12x 开关回退：邮箱验证总开关）', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    registerMock.mockResolvedValue(undefined)
  })

  afterEach(() => {
    // NModal teleport 到 body——用例间清理，防上一个弹窗残留干扰 DOM 断言
    document.body.innerHTML = ''
  })

  it('开关开：验证码行显示 + 邮箱必填 + 提交携带 emailCode', async () => {
    const wrapper = mountModal(true)
    await flushPromises()
    expect(document.body.textContent).toContain('邮箱验证码')
    expect(document.body.textContent).toContain('获取验证码')

    const vm = await fillAndSubmit(wrapper)
    vm.form.email = 'a@b.com'
    // form 引用需通过 vm 拿——重新提交带邮箱
    ;(wrapper.vm as unknown as { form: { emailCode: string } }).form.emailCode = '123456'
    await (wrapper.vm as unknown as { handleRegister: () => Promise<void> }).handleRegister()
    await flushPromises()

    expect(registerMock).toHaveBeenCalled()
    const payload = registerMock.mock.calls[registerMock.mock.calls.length - 1]?.[0] as Record<string, unknown>
    expect(payload.email).toBe('a@b.com')
    expect(payload.emailCode).toBe('123456')
  })

  it('开关关：验证码行隐藏 + 邮箱可留空 + 提交不带 emailCode', async () => {
    const wrapper = mountModal(false)
    await flushPromises()
    expect(document.body.textContent).not.toContain('邮箱验证码')
    expect(document.body.textContent).toContain('可选填')

    await fillAndSubmit(wrapper) // 邮箱留空

    expect(registerMock).toHaveBeenCalled()
    const payload = registerMock.mock.calls[registerMock.mock.calls.length - 1]?.[0] as Record<string, unknown>
    expect(payload.email).toBeUndefined()
    expect(payload.emailCode).toBeUndefined()
  })

  it('开关关：邮箱填了仍提交 email（后端建未验证凭证）', async () => {
    const wrapper = mountModal(false)
    await flushPromises()
    const vm = await fillAndSubmit(wrapper)
    vm.form.email = 'opt@b.com'
    await (wrapper.vm as unknown as { handleRegister: () => Promise<void> }).handleRegister()
    await flushPromises()

    const payload = registerMock.mock.calls[registerMock.mock.calls.length - 1]?.[0] as Record<string, unknown>
    expect(payload.email).toBe('opt@b.com')
    expect(payload.emailCode).toBeUndefined()
  })
})
