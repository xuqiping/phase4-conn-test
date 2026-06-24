import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import ModelSelector from './ModelSelector.vue'
import { llmApi } from '@/api/llm'

vi.mock('@/api/llm', () => ({
  llmApi: {
    listAvailableModels: vi.fn()
  }
}))

describe('ModelSelector', () => {
  it('keeps the persisted model when it is still available', async () => {
    vi.mocked(llmApi.listAvailableModels).mockResolvedValue({
      data: {
        data: [
          { modelId: 'doubao-seed-2.0-code', displayName: 'Doubao', providerName: 'doubao' },
          { modelId: 'deepseek-chat', displayName: 'DeepSeek Chat', providerName: 'deepseek' }
        ]
      }
    } as any)

    const wrapper = mount(ModelSelector, {
      props: {
        modelValue: 'deepseek-chat'
      }
    })
    await flushPromises()

    expect(wrapper.emitted('change')).toBeUndefined()
  })
})
