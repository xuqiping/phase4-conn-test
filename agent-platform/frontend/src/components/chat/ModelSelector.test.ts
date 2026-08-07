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

  it('非 optional 保持旧行为：preferred 可用时不 emit；不可用则自动选中首个并 emit', async () => {
    mockModels()
    // preferred（doubao-seed-2.0-code）在列表里 → 初始即选中，不 emit（同既有首测口径）
    const wrapper = mount(ModelSelector)
    await flushPromises()
    expect(wrapper.emitted('update:modelValue')).toBeUndefined()

    // preferred 不在列表 → 回退首个模型并 emit
    vi.mocked(llmApi.listAvailableModels).mockResolvedValue({
      data: { data: [{ modelId: 'deepseek-chat', displayName: 'DeepSeek', providerName: 'deepseek' }] }
    } as any)
    const wrapper2 = mount(ModelSelector)
    await flushPromises()
    expect(wrapper2.emitted('update:modelValue')![0]).toEqual(['deepseek-chat'])
  })
})
