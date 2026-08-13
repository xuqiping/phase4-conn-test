import { describe, expect, it, vi } from 'vitest'
import { knowledgeApi } from './knowledge'
import request from './request'

vi.mock('./request', () => ({
  default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() }
}))

describe('knowledge evaluation api', () => {
  it('creates datasets and imports, lists and exports cases through real endpoints', async () => {
    vi.mocked(request.post).mockResolvedValue({ data: { code: 200, data: {} } })
    vi.mocked(request.get).mockResolvedValue({ data: { code: 200, data: [] } })

    await knowledgeApi.createEvaluationDataset({ kbId: 9, name: '回归集', description: '说明' })
    await knowledgeApi.importEvaluationJsonl(3, '{"question":"问题"}')
    await knowledgeApi.listEvaluationCases(3)
    await knowledgeApi.exportEvaluationJsonl(3)

    expect(request.post).toHaveBeenCalledWith('/knowledge/admin/evaluation/datasets', {
      kbId: 9, name: '回归集', description: '说明'
    })
    expect(request.post).toHaveBeenCalledWith(
      '/knowledge/admin/evaluation/datasets/3/cases/import',
      '{"question":"问题"}',
      { headers: { 'Content-Type': 'application/x-ndjson' } }
    )
    expect(request.get).toHaveBeenCalledWith('/knowledge/admin/evaluation/datasets/3/cases')
    expect(request.get).toHaveBeenCalledWith('/knowledge/admin/evaluation/datasets/3/cases/export', {
      responseType: 'text'
    })
  })
})
