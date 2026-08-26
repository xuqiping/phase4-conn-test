import { describe, expect, it } from 'vitest'
import { cloneNodeForDuplicate } from './nodeClone'
import type { CanvasNode } from '@/types/canvas'

// C4（2x-4）：节点副本纯函数——参数保留、生成态清空、位置错开。
function mk(src: Partial<CanvasNode> & { data: Record<string, unknown> }): CanvasNode {
  return { id: 'n1', type: 'image', position: { x: 100, y: 200 }, ...src } as CanvasNode
}

describe('cloneNodeForDuplicate', () => {
  it('参数深拷贝保留（prompt/model/比例/尺寸/资产绑定）', () => {
    const r = cloneNodeForDuplicate(mk({
      data: {
        label: '主视觉图', prompt: '红色屋顶小屋', model: 'seedream-4.0', ratio: '16:9',
        width: 320, height: 320, assetId: 88, assetName: '老板娘', assetVersion: 2
      }
    }))
    expect(r.data.prompt).toBe('红色屋顶小屋')
    expect(r.data.model).toBe('seedream-4.0')
    expect(r.data.width).toBe(320)
    expect(r.data.assetId).toBe(88)
    expect(r.type).toBe('image')
  })

  it('生成态/会话态清空 + status 回 idle', () => {
    const r = cloneNodeForDuplicate(mk({
      data: {
        label: 'x', prompt: 'p',
        status: 'success', errorMsg: '上次失败原因', fileId: 'f1', previewUrl: 'blob:a',
        coverPreviewUrl: 'blob:c', outputText: '旧产出', taskId: 't9', mediaTaskId: 'm9',
        startedAt: 1, finishedAt: 2, assetHasUpdate: true, changeLog: [], localizeWarning: 'w',
        firstFrameNodeId: 'up-1'
      }
    }))
    for (const k of ['errorMsg', 'fileId', 'previewUrl', 'coverPreviewUrl', 'outputText', 'taskId',
      'mediaTaskId', 'startedAt', 'finishedAt', 'assetHasUpdate', 'changeLog', 'localizeWarning',
      'firstFrameNodeId'] as const) {
      expect(r.data[k]).toBeUndefined()
    }
    expect(r.data.status).toBe('idle')
  })

  it('深拷贝不共享引用（改副本 data 不影响原节点）', () => {
    const src = mk({ data: { label: 'x', prompt: 'p', ratio: '16:9' } })
    const r = cloneNodeForDuplicate(src)
    ;(r.data as Record<string, unknown>).prompt = '改了'
    expect(src.data.prompt).toBe('p')
  })

  it('位置 +40/+40 右下错开', () => {
    const r = cloneNodeForDuplicate(mk({ position: { x: 10, y: 20 }, data: { label: 'x' } }))
    expect(r.position).toEqual({ x: 50, y: 60 })
  })
})
