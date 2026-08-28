import { describe, expect, it } from 'vitest'
import { buildCanvasReferenceList, resolveCanvasVideoAttachments } from './canvasVideoAttachments'
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

  // ---- 2x-4：@视频节点收为 kind=video 参考视频附件 ----

  /** 造一个 video 节点（带 fileId 产出）。 */
  function vid(id: string, fileId: string): CanvasVideoNodeLike {
    return { id, type: 'video', data: { fileId } }
  }

  it('@视频节点 → kind=video 附件 + 视频N 序号（不再拼 fileId 文本）', () => {
    const nodes = [vid('v1', 'a.mp4'), vid('v2', 'b.mp4')]
    const { refs, rewrittenPrompt } = resolveCanvasVideoAttachments(
      {},
      '以 @{{node:v1}} 和 @{{node:v2}} 的运镜',
      nodes,
      resolver
    )
    expect(refs).toEqual([
      { fileId: 'a.mp4', kind: 'video' },
      { fileId: 'b.mp4', kind: 'video' }
    ])
    expect(rewrittenPrompt).toBe('以 视频1 和 视频2 的运镜')
  })

  it('@图 + @视频混用 → 各自独立序号（图N/视频N）', () => {
    const nodes = [img('a', 'a.png'), vid('v1', 'a.mp4')]
    const { refs, rewrittenPrompt } = resolveCanvasVideoAttachments(
      {},
      '风格参考 @{{node:a}}，运镜参考 @{{node:v1}}',
      nodes,
      resolver
    )
    expect(refs).toEqual([
      { fileId: 'a.png', kind: 'image' },
      { fileId: 'a.mp4', kind: 'video' }
    ])
    expect(rewrittenPrompt).toBe('风格参考 图1，运镜参考 视频1')
  })

  it('同一视频节点多次 @ → 序号稳定不重复收附件', () => {
    const nodes = [vid('v1', 'a.mp4')]
    const { refs, rewrittenPrompt } = resolveCanvasVideoAttachments(
      {},
      '@{{node:v1}} 再 @{{node:v1}}',
      nodes,
      resolver
    )
    expect(refs).toEqual([{ fileId: 'a.mp4', kind: 'video' }])
    expect(rewrittenPrompt).toBe('视频1 再 视频1')
  })

  it('首尾帧与 @参考视频混用 → 同样拒绝', () => {
    const nodes = [img('first', 'f.png'), vid('v1', 'a.mp4')]
    expect(() => resolveCanvasVideoAttachments(
      { firstFrameNodeId: 'first' },
      '参考 @{{node:v1}} 生成', nodes, resolver
    )).toThrow('首帧/尾帧不能与参考媒体同时使用')
  })

  it('video 节点无 fileId → 文本插值降级，不产附件', () => {
    const nodes: CanvasVideoNodeLike[] = [{ id: 'empty', type: 'video', data: {} }]
    const { refs, rewrittenPrompt } = resolveCanvasVideoAttachments(
      {},
      '用 @{{node:empty}}',
      nodes,
      resolver
    )
    expect(refs).toEqual([])
    expect(rewrittenPrompt).toBe('用 【断链】')
  })
})

// ---- 2x 四轮 S8：参考预览列表（与提交序号化同源，防两处漂移） ----

describe('buildCanvasReferenceList', () => {
  function vid(id: string, fileId: string): CanvasVideoNodeLike {
    return { id, type: 'video', data: { fileId } }
  }

  it('首尾帧 → 首帧/尾帧徽标（kind=image）', () => {
    const nodes = [img('first', 'f.png'), img('last', 'l.png')]
    const list = buildCanvasReferenceList(
      { firstFrameNodeId: 'first', lastFrameNodeId: 'last' }, '转场', nodes
    )
    expect(list).toEqual([
      { fileId: 'f.png', kind: 'image', label: '首帧' },
      { fileId: 'l.png', kind: 'image', label: '尾帧' }
    ])
  })

  it('@两图一视频 → 图1/图2/视频1，徽标与提交 rewrittenPrompt 序号一致', () => {
    const nodes = [img('a', 'a.png'), img('b', 'b.png'), vid('v1', 'v.mp4')]
    const prompt = '风格 @{{node:a}}，人物 @{{node:b}}，运镜 @{{node:v1}}'
    const list = buildCanvasReferenceList({}, prompt, nodes)
    expect(list.map(x => x.label)).toEqual(['图1', '图2', '视频1'])
    expect(list.map(x => x.kind)).toEqual(['image', 'image', 'video'])
    // 同源校验：提交引擎对同一输入的序号化文本，与预览徽标一一对齐
    const { rewrittenPrompt } = resolveCanvasVideoAttachments({}, prompt, nodes, resolver)
    expect(rewrittenPrompt).toContain('图1')
    expect(rewrittenPrompt).toContain('图2')
    expect(rewrittenPrompt).toContain('视频1')
  })

  it('首尾帧+@参考互斥场景不抛（提交时才拒），两组都渲染（L7）', () => {
    const nodes = [img('first', 'f.png'), img('a', 'a.png')]
    const list = buildCanvasReferenceList(
      { firstFrameNodeId: 'first' }, '参考 @{{node:a}}', nodes
    )
    expect(list).toEqual([
      { fileId: 'f.png', kind: 'image', label: '首帧' },
      { fileId: 'a.png', kind: 'image', label: '图1' }
    ])
  })

  it('同一 fileId 去重 → 单项；多次 @ 序号稳定', () => {
    const nodes = [img('a', 'a.png')]
    const list = buildCanvasReferenceList({}, '@{{node:a}} 再 @{{node:a}}', nodes)
    expect(list).toEqual([{ fileId: 'a.png', kind: 'image', label: '图1' }])
  })

  it('断链 @（节点已删）→ 该项不产条目、不崩（断链提示由面板既有 warn 承担）', () => {
    const list = buildCanvasReferenceList({}, '参考 @{{node:gone}}', [])
    expect(list).toEqual([])
  })
})

// 修复VI VE（2x#6）：@音频节点 → kind:'audio' 参考音频附件，图/视频/音频独立编号
describe('resolveCanvasVideoAttachments · 音频引用（修复VI VE 2x#6）', () => {
  function aud(id: string, fileId: string): CanvasVideoNodeLike {
    return { id, type: 'audio', data: { fileId, label: id } }
  }
  function vid(id: string, fileId: string): CanvasVideoNodeLike {
    return { id, type: 'video', data: { fileId, label: id } }
  }

  it('@音频 → 音频1 附件 kind=audio；三类各自独立编号不混排', () => {
    const nodes = [img('a', 'a.png'), vid('v', 'v.mp4'), aud('s', 's.mp3')]
    const { refs, rewrittenPrompt } = resolveCanvasVideoAttachments(
      {}, '图 @{{node:a}} 视频 @{{node:v}} 音 @{{node:s}}', nodes, resolver
    )
    expect(rewrittenPrompt).toBe('图 图1 视频 视频1 音 音频1')
    expect(refs).toEqual([
      { fileId: 'a.png', kind: 'image' },
      { fileId: 'v.mp4', kind: 'video' },
      { fileId: 's.mp3', kind: 'audio' }
    ])
  })

  it('同一音频多 @ 去重 + 同 fileId 跨类不混序', () => {
    const nodes = [aud('s1', 's.mp3'), aud('s2', 's.mp3')]
    const { refs, rewrittenPrompt } = resolveCanvasVideoAttachments(
      {}, '@{{node:s1}} 和 @{{node:s2}}', nodes, resolver
    )
    expect(rewrittenPrompt).toBe('音频1 和 音频1')
    expect(refs).toEqual([{ fileId: 's.mp3', kind: 'audio' }])
  })

  it('音频节点无 fileId → 走文本插值/断链（不产附件不崩）', () => {
    const nodes = [{ id: 'empty', type: 'audio', data: {} }]
    const { refs, rewrittenPrompt } = resolveCanvasVideoAttachments(
      {}, '音 @{{node:empty}}', nodes as CanvasVideoNodeLike[], resolver
    )
    expect(rewrittenPrompt).toBe('音 【断链】')
    expect(refs).toEqual([])
  })

  it('首尾帧与 @音频混用 → 本地拒绝（同互斥口径）', () => {
    const nodes = [img('first', 'f.png'), aud('s', 's.mp3')]
    expect(() => resolveCanvasVideoAttachments(
      { firstFrameNodeId: 'first' }, '音 @{{node:s}}', nodes, resolver
    )).toThrow('首帧/尾帧不能与参考媒体同时使用')
  })
})

describe('buildCanvasReferenceList · 音频徽标（修复VI VE 2x#6）', () => {
  it('@音频 → 音频N 徽标 kind=audio（与提交序号同源）', () => {
    const nodes = [
      { id: 's1', type: 'audio', data: { fileId: 's1.mp3' } },
      { id: 's2', type: 'audio', data: { fileId: 's2.mp3' } }
    ]
    const list = buildCanvasReferenceList({}, '@{{node:s1}} @{{node:s2}}', nodes)
    expect(list).toEqual([
      { fileId: 's1.mp3', kind: 'audio', label: '音频1' },
      { fileId: 's2.mp3', kind: 'audio', label: '音频2' }
    ])
  })
})
