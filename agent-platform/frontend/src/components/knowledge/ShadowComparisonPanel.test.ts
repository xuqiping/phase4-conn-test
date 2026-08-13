import { mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { NMessageProvider } from 'naive-ui'
import ShadowComparisonPanel from './ShadowComparisonPanel.vue'
import { knowledgeApi } from '@/api/knowledge'

vi.mock('@/api/knowledge', () => ({
  knowledgeApi: { listShadowComparisons: vi.fn() }
}))

describe('ShadowComparisonPanel', () => {
  beforeEach(() => vi.clearAllMocks())

  it('loads real traceable comparisons for a knowledge base', async () => {
    vi.mocked(knowledgeApi.listShadowComparisons).mockResolvedValue({
      data: { code: 200, data: [{
        id: 1, kbId: 9, championTraceId: 'trace-c', challengerTraceId: 'trace-x',
        championVersion: 'rc-old', challengerVersion: 'rc-new', status: 'SUCCEEDED',
        rankedChunkIds: ['11', '12'], cost: 2.5, createdAt: '2026-08-14T00:00:00Z'
      }] }
    } as never)

    const wrapper = mount(NMessageProvider, { slots: { default: ShadowComparisonPanel } })
    await wrapper.get('[data-test="shadow-kb-id"] input').setValue('9')
    await wrapper.get('[data-test="load-shadows"]').trigger('click')

    await vi.waitFor(() => expect(knowledgeApi.listShadowComparisons).toHaveBeenCalledWith(9, undefined, 50))
    expect(wrapper.text()).toContain('trace-c')
    expect(wrapper.text()).toContain('rc-new')
    expect(wrapper.text()).toContain('11, 12')
  })
})
