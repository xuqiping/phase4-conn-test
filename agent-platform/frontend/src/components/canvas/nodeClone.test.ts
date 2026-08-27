import { describe, expect, it } from 'vitest'
import { cloneNodeForDuplicate } from './nodeClone'
import type { CanvasNode } from '@/types/canvas'

// C4（2x-4）：节点副本纯函数。修复IV C3（C-8，决策 4「副本完全独立」）：
// 产物四件保留 + 资产/任务链脱钩 + status 按产物回填。
function mk(src: Partial<CanvasNode> & { data: Record<string, unknown> }): CanvasNode {
  return { id: 'n1', type: 'image', position: { x: 100, y: 200 }, ...src } as CanvasNode
}

describe('cloneNodeForDuplicate', () => {
  it('参数深拷贝保留（prompt/model/比例/尺寸）+ 产物四件保留（决策 4：副本即显产物）', () => {
    const r = cloneNodeForDuplicate(mk({
      data: {
        label: '主视觉图', prompt: '红色屋顶小屋', model: 'seedream-4.0', ratio: '16:9',
        width: 320, height: 320,
        fileId: 'f1', previewUrl: 'blob:a', coverPreviewUrl: 'blob:c', outputText: '旧产出'
      }
    }))
    expect(r.data.prompt).toBe('红色屋顶小屋')
    expect(r.data.model).toBe('seedream-4.0')
    expect(r.data.width).toBe(320)
    expect(r.data.fileId).toBe('f1')
    expect(r.data.previewUrl).toBe('blob:a')
    expect(r.data.coverPreviewUrl).toBe('blob:c')
    expect(r.data.outputText).toBe('旧产出')
    expect(r.type).toBe('image')
  })

  it('修复IV C3：资产绑定三件套与任务链清空（入库=新资产、重生成=新任务）', () => {
    const r = cloneNodeForDuplicate(mk({
      data: {
        label: 'x', prompt: 'p', previewUrl: 'blob:a',
        assetId: 88, assetName: '老板娘', assetVersion: 2, assetHasUpdate: true,
        taskId: 't9', mediaTaskId: 'm9', startedAt: 1, finishedAt: 2,
        changeLog: [], localizeWarning: 'w', errorMsg: '上次失败原因'
      }
    }))
    for (const k of ['assetId', 'assetName', 'assetVersion', 'assetHasUpdate', 'taskId',
      'mediaTaskId', 'startedAt', 'finishedAt', 'changeLog', 'localizeWarning',
      'errorMsg'] as const) {
      expect(r.data[k]).toBeUndefined()
    }
  })

  it('修复IV C3：status 按产物回填——有 previewUrl → success（副本带成功态显示）', () => {
    const r = cloneNodeForDuplicate(mk({ data: { label: 'x', previewUrl: 'blob:a', status: 'running' } }))
    expect(r.data.status).toBe('success')
  })

  it('修复IV C3：有 outputText 无图 → success；全无产物 → idle（重新生成起点）', () => {
    expect(cloneNodeForDuplicate(mk({ data: { label: 'x', outputText: '文本产出' } })).data.status).toBe('success')
    expect(cloneNodeForDuplicate(mk({ data: { label: 'x', prompt: 'p' } })).data.status).toBe('idle')
  })

  it('修复IV C3：firstFrameNodeId 保留（结构引用指向画布内上游，非外部资源）', () => {
    const r = cloneNodeForDuplicate(mk({ data: { label: 'x', previewUrl: 'blob:a', firstFrameNodeId: 'up-1' } }))
    expect(r.data.firstFrameNodeId).toBe('up-1')
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
