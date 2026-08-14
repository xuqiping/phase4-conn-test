import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import NodeCardBase from '@/components/canvas/NodeCardBase.vue'
import type { CanvasNodeStatus } from '@/mocks/types'

// NodeCardBase：status → 状态类名；类型 → 类型类名；选中类名
describe('NodeCardBase 状态与类型类名', () => {
  const baseProps = { kind: 'text' as const, kindLabel: '文本' }

  it.each(['idle', 'running', 'success', 'failed'] as CanvasNodeStatus[])(
    'status=%s 渲染对应类名与状态文案',
    (status) => {
      const w = mount(NodeCardBase, { props: { ...baseProps, status } })
      expect(w.classes()).toContain(`node-card--${status}`)
      const LABEL: Record<CanvasNodeStatus, string> = {
        idle: '待生成',
        running: '生成中',
        success: '完成',
        failed: '失败'
      }
      expect(w.text()).toContain(LABEL[status])
    }
  )

  it.each(['text', 'image', 'video', 'audio', 'script', 'storyboard'] as const)(
    'kind=%s 渲染类型类名',
    (kind) => {
      const w = mount(NodeCardBase, { props: { kind, kindLabel: 'x' } })
      expect(w.classes()).toContain(`node-card--${kind}`)
    }
  )

  it('selected=true 加选中类名', () => {
    const w = mount(NodeCardBase, { props: { ...baseProps, selected: true } })
    expect(w.classes()).toContain('node-card--selected')
  })

  it('label 空时显示「未命名」', () => {
    const w = mount(NodeCardBase, { props: baseProps })
    expect(w.text()).toContain('未命名')
  })

  it('sceneNo/耗时/token 徽标渲染', () => {
    const w = mount(NodeCardBase, {
      props: { ...baseProps, sceneNo: 'SC-01', durationMs: 3200, tokens: 1480 }
    })
    expect(w.text()).toContain('SC-01')
    expect(w.text()).toContain('3.2s')
    expect(w.text()).toContain('1480')
  })
})
