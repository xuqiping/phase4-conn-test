import { describe, expect, it } from 'vitest'
import { cloneEdgesForDuplicate, cloneNodeForDuplicate } from './nodeClone'
import type { CanvasEdge, CanvasNode } from '@/types/canvas'

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

// 修复VI（2x 未解决③，决策「连线克隆一份」）：副本连线克隆纯函数。
function ek(src: Partial<CanvasEdge> & { source: string; target: string }): CanvasEdge {
  return { id: `e-${src.source}-${src.target}`, ...src } as CanvasEdge
}

describe('cloneEdgesForDuplicate（修复VI 2x#3）', () => {
  const A = 'node-a', B = 'node-b', C = 'node-c'

  it('入边+出边各克隆一条指向/发自副本；原边不动（引用层面不篡改原数组）', () => {
    const inEdge = ek({ source: A, target: B, sourceHandle: 'out-1', style: { stroke: '#fff' } })
    const outEdge = ek({ source: B, target: C, targetHandle: 'in-2' })
    const untouched = ek({ source: A, target: C })
    const edges = [inEdge, outEdge, untouched]
    const cloned = cloneEdgesForDuplicate(B, 'copy-b', edges)
    expect(cloned).toHaveLength(2)
    const clonedIn = cloned.find(e => e.source === A)!
    expect(clonedIn.target).toBe('copy-b')
    expect(clonedIn.sourceHandle).toBe('out-1') // handles 随展开保留
    expect(clonedIn.style).toEqual({ stroke: '#fff' }) // 样式保留
    const clonedOut = cloned.find(e => e.target === C)!
    expect(clonedOut.source).toBe('copy-b')
    expect(clonedOut.targetHandle).toBe('in-2')
    // 原数组三边原样（原边不动）
    expect(edges).toEqual([inEdge, outEdge, untouched])
    expect(edges[0].target).toBe(B)
  })

  it('自环边（source==target==原）→ 克隆成副本自环', () => {
    const self = ek({ source: B, target: B })
    const cloned = cloneEdgesForDuplicate(B, 'copy-b', [self])
    expect(cloned).toHaveLength(1)
    expect(cloned[0].source).toBe('copy-b')
    expect(cloned[0].target).toBe('copy-b')
  })

  it('与原节点无关的边不带；无任何相关边返回空数组', () => {
    expect(cloneEdgesForDuplicate(B, 'copy-b', [ek({ source: A, target: C })])).toEqual([])
  })

  it('新边 id 唯一（同批多条不撞）', () => {
    const edges = [ek({ source: A, target: B }), ek({ source: C, target: B }), ek({ source: B, target: A })]
    const ids = cloneEdgesForDuplicate(B, 'copy-b', edges).map(e => e.id)
    expect(new Set(ids).size).toBe(3)
  })

  it('修复VIII（VIII-1 ⑧）：组边（伪 id 端点）不带出创建副本——显式兜底过滤', () => {
    const edges = [
      ek({ source: 'group:g1', target: B }), // 组→原节点：伪 id source，过滤
      ek({ source: B, target: 'group:g2' }), // 原节点→组：伪 id target，过滤
      ek({ source: A, target: B })           // 普通边：保留克隆
    ]
    const cloned = cloneEdgesForDuplicate(B, 'copy-b', edges)
    expect(cloned).toHaveLength(1)
    expect(cloned[0].source).toBe(A)
    expect(cloned[0].target).toBe('copy-b')
  })
})
