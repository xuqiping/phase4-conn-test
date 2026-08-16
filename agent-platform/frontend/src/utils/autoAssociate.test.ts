import { describe, expect, it } from 'vitest'
import { buildProposals, applyProposals, textLikeFieldOf } from './autoAssociate'
import type { CanvasEdge, CanvasNode } from '@/types/canvas'

function node(id: string, type: string, data: Record<string, unknown>): CanvasNode {
  return { id, type, position: { x: 0, y: 0 }, data: { label: id, ...data } }
}

function edge(source: string, target: string): CanvasEdge {
  return { id: `e-${source}-${target}`, source, target }
}

function makeBoard(nodes: CanvasNode[], edges: CanvasEdge[], selectedIds?: string[]) {
  return {
    selectedIds: selectedIds ?? nodes.map(n => n.id),
    getNodes: () => nodes,
    getEdges: () => edges
  }
}

describe('textLikeFieldOf', () => {
  it('text/script/storyboard 映射主文本字段，其余 null', () => {
    expect(textLikeFieldOf(node('a', 'text', {}))).toBe('prompt')
    expect(textLikeFieldOf(node('b', 'script', {}))).toBe('synopsis')
    expect(textLikeFieldOf(node('c', 'storyboard', {}))).toBe('description')
    expect(textLikeFieldOf(node('d', 'image', {}))).toBeNull()
    expect(textLikeFieldOf(node('e', 'video', {}))).toBeNull()
  })
})

describe('buildProposals', () => {
  it('L2-1 正向：分镜文本含上游节点名 → 生成替换提案', () => {
    const nodes = [
      node('hero', 'text', { label: '主角1', outputText: '设定' }),
      node('sb', 'storyboard', { label: '分镜1', description: '主角1拿起了剑' })
    ]
    const { proposals, skipped } = buildProposals(makeBoard(nodes, [edge('hero', 'sb')], ['sb']))
    expect(proposals).toHaveLength(1)
    expect(proposals[0]).toMatchObject({ targetId: 'sb', candNodeId: 'hero', candName: '主角1', start: 0, end: 3 })
    expect(skipped).toHaveLength(0)
  })

  it('L2 长名优先：主角1设定 与 主角1 并存，长名先占区间不被短名吞', () => {
    const nodes = [
      node('heroFull', 'text', { label: '主角1设定' }),
      node('hero', 'text', { label: '主角1' }),
      node('sb', 'storyboard', { label: '分镜1', description: '主角1设定先出场，然后主角1拿剑' })
    ]
    const { proposals } = buildProposals({
      selectedIds: ['sb'],
      getNodes: () => nodes,
      getEdges: () => [edge('heroFull', 'sb'), edge('hero', 'sb')]
    })
    // 长名「主角1设定」命中首段；短名「主角1」只能匹配第二处（首处已被占）
    const heroProposals = proposals.filter(p => p.candNodeId === 'heroFull')
    expect(heroProposals).toHaveLength(1)
    expect(heroProposals[0].start).toBe(0)
    const short = proposals.filter(p => p.candNodeId === 'hero')
    expect(short).toHaveLength(1)
    expect(short[0].start).toBeGreaterThan(0)
  })

  it('L2-4 不二次包装：已有 @{{node:x}} 区间视为占用', () => {
    const nodes = [
      node('hero', 'text', { label: '主角1' }),
      node('t', 'text', { label: '提示词', prompt: '@{{node:hero}} 拿起了剑，主角1很帅' })
    ]
    const { proposals } = buildProposals(makeBoard(nodes, [edge('hero', 't')]))
    // 第一处主角1在占位符内被跳过，第二处可命中
    expect(proposals).toHaveLength(1)
    expect(String(nodes[1].data.prompt).startsWith('@{{node:hero}}')).toBe(true)
    expect(proposals[0].start).toBeGreaterThan(10)
  })

  it('assetName 徽标也参与候选', () => {
    const nodes = [
      node('img', 'image', { label: '图片1', assetName: '橘猫形象' }),
      node('t', 'text', { label: '提示词', prompt: '橘猫形象趴在窗台' })
    ]
    const { proposals } = buildProposals(makeBoard(nodes, [edge('img', 't')]))
    expect(proposals).toHaveLength(1)
    expect(proposals[0].candName).toBe('橘猫形象')
  })

  it('L2-5 非文本类/无上游/名字未出现 → skipped 各自原因', () => {
    const nodes = [
      node('img', 'image', { label: '图片1' }),
      node('orphan', 'text', { label: '孤儿', prompt: '无上游文本' }),
      node('hero', 'text', { label: '主角1' }),
      node('nomatch', 'text', { label: '笔记', prompt: '完全不相关的文本' })
    ]
    const { proposals, skipped } = buildProposals({
      selectedIds: ['img', 'orphan', 'nomatch'],
      getNodes: () => nodes,
      getEdges: () => [edge('hero', 'nomatch')]
    })
    expect(proposals).toHaveLength(0)
    const reasons = Object.fromEntries(skipped.map(s => [s.id, s.reason]))
    expect(reasons['img']).toContain('非文本类')
    expect(reasons['orphan']).toContain('无连线可达')
    expect(nomatchReason(skipped, 'nomatch')).toBeTruthy()
  })

  function nomatchReason(skipped: { id: string; reason: string }[], id: string) {
    return skipped.find(s => s.id === id)
  }

  it('多个出现位置只取首个空闲处（同名单次提案）', () => {
    const nodes = [
      node('hero', 'text', { label: '主角1' }),
      node('t', 'text', { label: '提示词', prompt: '主角1走，主角1跑，主角1跳' })
    ]
    const { proposals } = buildProposals(makeBoard(nodes, [edge('hero', 't')]))
    expect(proposals).toHaveLength(1)
    expect(proposals[0].start).toBe(0)
  })
})

describe('applyProposals', () => {
  it('逆序替换防位移：一处文本两个不同上游名同时替换', () => {
    const nodes = [
      node('hero', 'text', { label: '主角1' }),
      node('sword', 'text', { label: '宝剑' }),
      node('t', 'text', { label: '提示词', prompt: '主角1举起宝剑砍向恶龙' })
    ]
    const edges = [edge('hero', 't'), edge('sword', 't')]
    const { proposals } = buildProposals(makeBoard(nodes, edges))
    expect(proposals).toHaveLength(2)
    const store = new Map(nodes.map(n => [n.id, n]))
    const applied = applyProposals(proposals, {
      getNode: (id) => store.get(id) ?? null,
      updateNodeData: (id, patch) => Object.assign(store.get(id)!.data, patch)
    })
    expect(applied).toMatchObject({ applied: 2, targets: 1 })
    const text = String(store.get('t')!.data.prompt)
    expect(text).toBe('@{{node:hero}} 举起@{{node:sword}} 砍向恶龙')
  })

  it('防御：应用时文本已外部改动（区间不再匹配）→ 跳过该条不误写', () => {
    const nodes = [
      node('hero', 'text', { label: '主角1' }),
      node('t', 'text', { label: '提示词', prompt: '主角1拿剑' })
    ]
    const { proposals } = buildProposals(makeBoard(nodes, [edge('hero', 't')]))
    // 模拟外部改动：文本前插了字，原区间失配
    nodes[1].data.prompt = '——主角1拿剑'
    const applied = applyProposals(proposals, {
      getNode: (id) => nodes.find(n => n.id === id) ?? null,
      updateNodeData: () => { /* no-op */ }
    })
    expect(applied.applied).toBe(0)
  })

  it('按目标分组写回各自字段（prompt/synopsis/description）', () => {
    const nodes = [
      node('hero', 'text', { label: '主角1' }),
      node('t', 'text', { label: '提示词', prompt: '主角1登场' }),
      node('s', 'script', { label: '剧本', synopsis: '主角1退场' })
    ]
    const edges = [edge('hero', 't'), edge('hero', 's')]
    const { proposals } = buildProposals({ selectedIds: ['t', 's'], getNodes: () => nodes, getEdges: () => edges })
    const store = new Map(nodes.map(n => [n.id, n]))
    const applied = applyProposals(proposals, {
      getNode: (id) => store.get(id) ?? null,
      updateNodeData: (id, patch) => Object.assign(store.get(id)!.data, patch)
    })
    expect(applied).toMatchObject({ applied: 2, targets: 2 })
    expect(store.get('t')!.data.prompt).toBe('@{{node:hero}} 登场')
    expect(store.get('s')!.data.synopsis).toBe('@{{node:hero}} 退场')
  })
})
