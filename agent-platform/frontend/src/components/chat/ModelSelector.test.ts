import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import { NSelect } from 'naive-ui'
import ModelSelector from './ModelSelector.vue'
import { llmApi } from '@/api/llm'

vi.mock('@/api/llm', () => ({
  llmApi: {
    listAvailableModels: vi.fn()
  }
}))

function mockModels() {
  vi.mocked(llmApi.listAvailableModels).mockResolvedValue({
    data: {
      data: [
        { modelId: 'doubao-seed-2.0-code', displayName: 'Doubao', providerName: 'doubao' },
        { modelId: 'deepseek-chat', displayName: 'DeepSeek Chat', providerName: 'deepseek' }
      ]
    }
  } as any)
}

describe('ModelSelector', () => {
  it('keeps the persisted model when it is still available', async () => {
    mockModels()

    const wrapper = mount(ModelSelector, {
      props: {
        modelValue: 'deepseek-chat'
      }
    })
    await flushPromises()

    expect(wrapper.emitted('change')).toBeUndefined()
  })

  it('clears a persisted model that is no longer available when no admin default exists', async () => {
    mockModels()
    const wrapper = mount(ModelSelector, { props: { modelValue: 'removed-model' } })
    await flushPromises()

    expect(wrapper.emitted('update:modelValue')![0]).toEqual([''])
  })

  it('uses the administrator default instead of the first available model', async () => {
    vi.mocked(llmApi.listAvailableModels).mockResolvedValue({
      data: { data: [
        { modelId: 'deepseek-chat', displayName: 'DeepSeek', providerName: 'deepseek' },
        { modelId: 'admin-default', displayName: 'Admin Default', providerName: 'global', defaultModel: true }
      ] }
    } as any)

    const wrapper = mount(ModelSelector)
    await flushPromises()

    expect(wrapper.emitted('update:modelValue')![0]).toEqual(['admin-default'])
  })
})

describe('ModelSelector (FR-006 optional 模式)', () => {
  it('optional 不自动选中、不发任何 emit（留空=不覆盖默认）', async () => {
    mockModels()

    const wrapper = mount(ModelSelector, { props: { optional: true } })
    await flushPromises()

    expect(wrapper.emitted('update:modelValue')).toBeUndefined()
    expect(wrapper.emitted('change')).toBeUndefined()
  })

  it('optional 清空（clearable → null）emit 空串', async () => {
    mockModels()

    const wrapper = mount(ModelSelector, {
      props: { optional: true, modelValue: 'deepseek-chat' }
    })
    await flushPromises()

    wrapper.findComponent(NSelect).vm.$emit('update:value', null)

    const updates = wrapper.emitted('update:modelValue')!
    const changes = wrapper.emitted('change')!
    expect(updates[updates.length - 1]).toEqual([''])
    expect(changes[changes.length - 1]).toEqual([''])
  })

  it('非 optional 无管理员默认时保持未选择，要求用户显式选择', async () => {
    mockModels()
    const wrapper = mount(ModelSelector)
    await flushPromises()
    expect(wrapper.emitted('update:modelValue')).toBeUndefined()
  })
})
