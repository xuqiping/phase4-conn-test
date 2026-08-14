import type { MockNode, MockEdge, NodeKind, CanvasNodeStatus } from './types'

/**
 * 演示工作流：12 节点，主干 脚本→分镜→图像→视频 + 文本/音频旁支。
 * 覆盖 6 类型 × 4 数据状态（idle/running/success/failed）。
 */

const KIND_LABEL: Record<NodeKind, string> = {
  text: '文本',
  image: '图像',
  video: '视频',
  audio: '音频',
  script: '脚本',
  storyboard: '分镜'
}
export { KIND_LABEL }

const DEMO: Array<[string, NodeKind, CanvasNodeStatus, number, number, Partial<MockNode['data']>]> = [
  ['n1', 'script', 'success', 0, 0, { label: '故事脚本生成', sceneNo: 'SC-01', lines: 42, firstLine: '# 赛博朋克短片：雨夜霓虹', durationMs: 3200, tokens: 1480 }],
  ['n2', 'storyboard', 'success', 320, 0, { label: '分镜拆解', sceneNo: 'SC-02', shots: 6, durationMs: 5100, tokens: 2200 }],
  ['n3', 'text', 'success', 640, -140, { label: '镜头一提示词', sceneNo: 'SC-03', prompt: '雨夜霓虹街道，赛博朋克风，电影感光影…', outputText: 'Neon-lit rainy street, cinematic lighting, 35mm…', durationMs: 1800, tokens: 640 }],
  ['n4', 'image', 'running', 640, 140, { label: '镜头一关键帧', sceneNo: 'SC-04', ratio: '16:9', durationMs: 0 }],
  ['n5', 'image', 'idle', 920, -140, { label: '镜头二关键帧', sceneNo: 'SC-05', ratio: '16:9' }],
  ['n6', 'video', 'idle', 1200, 0, { label: '镜头合成', sceneNo: 'SC-06', ratio: '1.85:1', durationSec: 8 }],
  ['n7', 'text', 'failed', 320, 220, { label: '旁白文案', sceneNo: 'SC-07', prompt: '为短片写一段冷峻旁白…', durationMs: 900, tokens: 120 }],
  ['n8', 'audio', 'idle', 640, 320, { label: '旁白配音', sceneNo: 'SC-08' }],
  ['n9', 'audio', 'success', 920, 220, { label: '背景配乐', sceneNo: 'SC-09', durationMs: 4200 }],
  ['n10', 'text', 'idle', 0, 220, { label: '标题润色', sceneNo: 'SC-10', prompt: '给短片起 3 个标题候选…' }],
  ['n11', 'script', 'idle', 1480, -140, { label: '片尾字幕脚本', sceneNo: 'SC-11', lines: 12, firstLine: '# credits roll' }],
  ['n12', 'video', 'running', 1480, 140, { label: '片尾合成', sceneNo: 'SC-12', ratio: '1.85:1', durationSec: 4 }]
]

export const demoNodes: MockNode[] = DEMO.map(([id, kind, status, x, y, extra]) => ({
  id,
  type: kind,
  position: { x, y },
  data: { kind, status, ...extra }
}))

export const demoEdges: MockEdge[] = [
  { id: 'e1-2', source: 'n1', target: 'n2' },
  { id: 'e2-3', source: 'n2', target: 'n3' },
  { id: 'e2-4', source: 'n2', target: 'n4' },
  { id: 'e3-5', source: 'n3', target: 'n5' },
  { id: 'e4-6', source: 'n4', target: 'n6' },
  { id: 'e5-6', source: 'n5', target: 'n6' },
  { id: 'e7-8', source: 'n7', target: 'n8' },
  { id: 'e8-6', source: 'n8', target: 'n6' },
  { id: 'e9-6', source: 'n9', target: 'n6' },
  { id: 'e11-12', source: 'n11', target: 'n12' }
]

/** 压测生成器：n 节点网格布局。N 非法/缺失回退演示流，>500 截断。 */
export function genStressNodes(n: number): { nodes: MockNode[]; edges: MockEdge[] } {
  const kinds: NodeKind[] = ['text', 'image', 'video', 'audio', 'script', 'storyboard']
  const statuses: CanvasNodeStatus[] = ['idle', 'running', 'success', 'failed']
  const cols = Math.ceil(Math.sqrt(n))
  const nodes: MockNode[] = []
  const edges: MockEdge[] = []
  for (let i = 0; i < n; i++) {
    const kind = kinds[i % 6]
    nodes.push({
      id: `s${i}`,
      type: kind,
      position: { x: (i % cols) * 260, y: Math.floor(i / cols) * 180 },
      data: {
        kind,
        status: statuses[i % 4],
        label: `压测节点 ${i}`,
        sceneNo: `SC-${String(i).padStart(2, '0')}`,
        prompt: '占位提示词文本…',
        lines: 20,
        shots: 4,
        ratio: '16:9'
      }
    })
    if (i > 0 && i % cols !== 0) {
      edges.push({ id: `es${i - 1}-${i}`, source: `s${i - 1}`, target: `s${i}` })
    }
  }
  return { nodes, edges }
}

/** 解析 ?nodes=N：非法/缺失 → null（用演示流）；>500 → 截 500 */
export function resolveNodeCount(raw: string | null): number | null {
  if (raw === null) return null
  const n = Number(raw)
  if (!Number.isFinite(n) || n <= 0) return null
  if (n > 500) {
    console.warn(`[frontnew] ?nodes=${n} 超上限，截断为 500`)
    return 500
  }
  return Math.floor(n)
}
