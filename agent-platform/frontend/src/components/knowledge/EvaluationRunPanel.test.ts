import { mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { NMessageProvider } from 'naive-ui'
import EvaluationRunPanel from './EvaluationRunPanel.vue'
import { knowledgeApi } from '@/api/knowledge'

vi.mock('@/api/knowledge', () => ({
  knowledgeApi: {
    createEvaluationDataset: vi.fn(),
    importEvaluationJsonl: vi.fn(),
    listEvaluationCases: vi.fn()
    ,startEvaluationRun: vi.fn()
    ,getEvaluationRun: vi.fn()
  }
}))

describe('EvaluationRunPanel', () => {
  beforeEach(() => vi.clearAllMocks())

  it('creates a dataset and imports jsonl without pretending a run has started', async () => {
    vi.mocked(knowledgeApi.createEvaluationDataset).mockResolvedValue({
      data: { code: 200, data: { id: 3 } }
    } as never)
    vi.mocked(knowledgeApi.importEvaluationJsonl).mockResolvedValue({
      data: { code: 200, data: { imported: 1, errors: [] } }
    } as never)
    vi.mocked(knowledgeApi.listEvaluationCases).mockResolvedValue({
      data: { code: 200, data: [{ id: 8, question: '问题' }] }
    } as never)

    const wrapper = mount(NMessageProvider, {
      slots: { default: EvaluationRunPanel }
    })
    expect(wrapper.text()).not.toContain('启动评测')

    await wrapper.get('[data-test="kb-id"] input').setValue('9')
    await wrapper.get('[data-test="dataset-name"] input').setValue('回归集')
    await wrapper.get('[data-test="create-dataset"]').trigger('click')
    await vi.waitFor(() => expect(knowledgeApi.createEvaluationDataset).toHaveBeenCalled())

    await wrapper.get('[data-test="jsonl"] textarea').setValue('{"queryType":"FACT","question":"问题"}')
    await wrapper.get('[data-test="import-jsonl"]').trigger('click')
    await vi.waitFor(() => expect(knowledgeApi.importEvaluationJsonl).toHaveBeenCalledWith(
      3, '{"queryType":"FACT","question":"问题"}'
    ))
    expect(wrapper.text()).toContain('已导入 1 条')

    vi.mocked(knowledgeApi.startEvaluationRun).mockResolvedValue({
      data: { code: 200, data: { id: 5, status: 'QUEUED', summaryMetrics: {} } }
    } as never)
    vi.mocked(knowledgeApi.getEvaluationRun).mockResolvedValue({
      data: { code: 200, data: { id: 5, status: 'COMPLETED', summaryMetrics: { recall: 1 } } }
    } as never)
    await wrapper.get('[data-test="pipeline-version"] input').setValue('pipeline-v2')
    await wrapper.get('[data-test="start-run"]').trigger('click')
    await vi.waitFor(() => expect(wrapper.text()).toContain('COMPLETED'))
    expect(wrapper.text()).toContain('100.0%')
  })
})
