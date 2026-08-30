import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import TargetSelector from './TargetSelector.vue'
import { chatTargetApi } from '@/api/chatTarget'

vi.mock('@/api/chatTarget', () => ({
  chatTargetApi: {
    listTargets: vi.fn()
  }
}))

describe('TargetSelector', () => {
  it('keeps the persisted target when it is still available', async () => {
    vi.mocked(chatTargetApi.listTargets).mockResolvedValue({
      data: {
        data: [
          { type: 'NONE', targetKey: 'none', id: null, name: '无', description: null, available: true },
          { type: 'AGENT', targetKey: 'agent:10', id: 10, name: 'CodeBot', description: null, available: true }
        ]
      }
    } as any)

    const wrapper = mount(TargetSelector, {
      props: {
        modelValue: 'agent:10'
      }
    })
    await flushPromises()

    expect(wrapper.emitted('change')).toBeUndefined()
  })
})
