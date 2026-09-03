import { describe, expect, it, vi } from 'vitest'
import { knowledgeApi } from './knowledge'
import request from './request'

vi.mock('./request', () => ({
  default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() }
}))

describe('knowledge index operations api', () => {
  it('starts and cancels a real snapshot rebuild', async () => {
    vi.mocked(request.post).mockResolvedValue({ data: { code: 200, data: {} } })

    await knowledgeApi.rebuildIndex(7, 'snap-1', false)
    await knowledgeApi.cancelIndexRebuild(7, 'snap-1')

    expect(request.post).toHaveBeenCalledWith('/knowledge/admin/indexes/7/rebuild', {
      snapshotId: 'snap-1', dryRun: false, confirmed: false
    })
    expect(request.post).toHaveBeenCalledWith('/knowledge/admin/indexes/7/rebuild/cancel', {
      snapshotId: 'snap-1', dryRun: false, confirmed: true
    })
  })

  it('posts contextual rebuild with dryRun flag for estimate and apply', async () => {
    vi.mocked(request.post).mockResolvedValue({ data: { code: 200, data: {} } })

    await knowledgeApi.contextualRebuild(7, true)
    await knowledgeApi.contextualRebuild(7, false)

    expect(request.post).toHaveBeenCalledWith('/knowledge/admin/indexes/7/contextual-rebuild', { dryRun: true })
    expect(request.post).toHaveBeenCalledWith('/knowledge/admin/indexes/7/contextual-rebuild', { dryRun: false })
  })
})
