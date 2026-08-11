import { describe, expect, it } from 'vitest'
import { resolveCanvasVideoAttachments } from './canvasVideoAttachments'
import type { CanvasVideoNodeLike } from './canvasVideoAttachments'
import type { MentionResolver } from './interpolate'

/** 造一个 image 节点（带 fileId）。 */
function img(id: string, fileId: string, label?: string): CanvasVideoNodeLike {
  return { id, type: 'image', data: { fileId, label: label ?? id } }
}

/** 造一个 text 节点（带 prompt 产出）。 */
function txt(id: string, prompt: string): CanvasVideoNodeLike {
  return { id, type: 'text', data: { prompt } }
}

/** 文本插值器：text 节点返 prompt，其余返 undefined（断链）。 */
const resolver: MentionResolver = (kind, id, ..._rest) => {
  void kind
  void id
  return undefined
}

describe('resolveCanvasVideoAttachments', () => {
  it('首尾帧与 @参考图混用时本地拒绝', () => {
    const nodes = [
      img('first', 'f.png', '首帧图'),
      img('last', 'l.png', '尾帧图'),
      img('ref1', 'r1.png', '参考1')
    ]
    expect(() => resolveCanvasVideoAttachments(
      { firstFrameNodeId: 'first', lastFrameNodeId: 'last' },
      '参考 @{{node:ref1}} 生成', nodes, resolver
    )).toThrow('首帧/尾帧不能与参考媒体同时使用')
  })

  it('首尾帧可以一起使用', () => {
    const nodes = [img('first', 'f.png'), img('last', 'l.png')]
    const { refs } = resolveCanvasVideoAttachments(
      { firstFrameNodeId: 'first', lastFrameNodeId: 'last' }, '转场', nodes, resolver
    )
    expect(refs).toEqual([
      { fileId: 'f.png', kind: 'image', frameRole: 'first_frame' },
      { fileId: 'l.png', kind: 'image', frameRole: 'last_frame' }
    ])
  })

  it('无首尾帧 → 仅参考图，图N从1起', () => {
    const nodes = [img('a', 'a.png'), img('b', 'b.png')]
    const { refs, rewrittenPrompt } = resolveCanvasVideoAttachments(
      {},
      '以 @{{node:a}} 和 @{{node:b}} 为参考',
      nodes,
      resolver
    )
    expect(refs).toEqual([
      { fileId: 'a.png', kind: 'image' },
      { fileId: 'b.png', kind: 'image' }
    ])
    expect(rewrittenPrompt).toBe('以 图1 和 图2 为参考')
  })

  it('同一图节点多次 @ → 序号稳定不重复收附件', () => {
    const nodes = [img('a', 'a.png')]
    const { refs, rewrittenPrompt } = resolveCanvasVideoAttachments(
      {},
      '@{{node:a}} 再 @{{node:a}}',
      nodes,
      resolver
    )
    expect(refs).toHaveLength(1)
    expect(rewrittenPrompt).toBe('图1 再 图1')
  })

  it('首/尾帧节点也在 prompt 被 @ → 不当参考图，走文本插值', () => {
    const nodes = [img('first', 'f.png')]
    // first 被选为首帧，prompt 里又 @ 它 → 不应重复作参考图
    const { refs, rewrittenPrompt } = resolveCanvasVideoAttachments(
      { firstFrameNodeId: 'first' },
      '帧 @{{node:first}}',
      nodes,
      resolver
    )
    expect(refs).toEqual([{ fileId: 'f.png', kind: 'image', frameRole: 'first_frame' }])
    // 首帧节点 @ 后文本插值（resolver 返 undefined → 断链标记），不当图N
    expect(rewrittenPrompt).toBe('帧 【断链】')
  })

  it('非 image 节点 @ → 文本插值，不产附件', () => {
    const nodes = [txt('t1', '人物设定：少女')]
    const textResolver: MentionResolver = (_kind, id) =>
      id === 't1' ? '人物设定：少女' : undefined
    const { refs, rewrittenPrompt } = resolveCanvasVideoAttachments(
      {},
      '扩写 @{{node:t1}}',
      nodes,
      textResolver
    )
    expect(refs).toEqual([])
    expect(rewrittenPrompt).toBe('扩写 人物设定：少女')
  })

  it('image 节点无 fileId → 文本插值降级，不计参考图', () => {
    const nodes: CanvasVideoNodeLike[] = [{ id: 'empty', type: 'image', data: {} }]
    const { refs, rewrittenPrompt } = resolveCanvasVideoAttachments(
      {},
      '用 @{{node:empty}}',
      nodes,
      resolver
    )
    expect(refs).toEqual([])
    expect(rewrittenPrompt).toBe('用 【断链】')
  })

  it('asset 占位符 → 文本插值（不收附件）', () => {
    const { refs, rewrittenPrompt } = resolveCanvasVideoAttachments(
      {},
      '资产 @{{asset:x1}}',
      [],
      resolver
    )
    expect(refs).toEqual([])
    expect(rewrittenPrompt).toBe('资产 【断链】')
  })

  it('无任何帧/参考 → 空附件 + 原文（文生视频）', () => {
    const { refs, rewrittenPrompt } = resolveCanvasVideoAttachments(
      {},
      '一只猫在跳舞',
      [],
      resolver
    )
    expect(refs).toEqual([])
    expect(rewrittenPrompt).toBe('一只猫在跳舞')
  })
})
