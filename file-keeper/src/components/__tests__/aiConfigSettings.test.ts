import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import AiConfigSettings from '../AiConfigSettings.vue'
import { useAuthStore } from '../../stores/authStore'
import { useCommercialAuthStore } from '../../stores/commercialAuthStore'

function mountComponent() {
  setActivePinia(createPinia())
  const authStore = useAuthStore()
  authStore.accessToken = 'access-token'
  const commercialAuthStore = useCommercialAuthStore()
  commercialAuthStore.deviceIdentity = {
    deviceId: 'device-1',
    fingerprintHash: 'fingerprint-hash',
    deviceName: 'test-device',
  }
  const wrapper = mount(AiConfigSettings)
  return { wrapper, authStore, commercialAuthStore }
}

describe('AiConfigSettings', () => {
  beforeEach(() => {
    globalThis.fetch = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: vi.fn().mockResolvedValue({ code: 200, msg: 'success', data: [] }),
    } as unknown as Response)
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('renders add config button when no configs exist', async () => {
    const { wrapper } = mountComponent()
    await flushPromises()

    expect(wrapper.find('[data-test="add-ai-config"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('暂无 AI 配置')
  })

  it('shows form with test connection button after clicking add', async () => {
    const { wrapper } = mountComponent()
    await flushPromises()

    await wrapper.get('[data-test="add-ai-config"]').trigger('click')
    await flushPromises()

    expect(wrapper.find('[data-test="ai-config-form"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="test-connection"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="save-ai-config"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="cancel-ai-config"]').exists()).toBe(true)
  })

  it('supports custom provider with manual endpoint and model', async () => {
    const { wrapper } = mountComponent()
    await flushPromises()

    await wrapper.get('[data-test="add-ai-config"]').trigger('click')
    await flushPromises()

    await wrapper.get('select').setValue('custom')
    await flushPromises()

    const inputs = wrapper.findAll('input')
    // order: name, model, apiKey, endpoint, maxTokens, timeoutSeconds
    const endpointInput = inputs[3]
    const modelInput = inputs[1]

    await endpointInput.setValue('https://my-api.example.com/v1/chat/completions')
    await modelInput.setValue('my-model')
    await inputs[2].setValue('my-api-key')

    expect((endpointInput.element as HTMLInputElement).value).toBe('https://my-api.example.com/v1/chat/completions')
    expect((modelInput.element as HTMLInputElement).value).toBe('my-model')
    expect(wrapper.find('[data-test="test-connection"]').attributes('disabled')).toBeUndefined()
  })
})
