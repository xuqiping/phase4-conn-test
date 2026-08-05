import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import PropertyPanel from './PropertyPanel.vue'
import type { CanvasNode } from '@/types/canvas'

function mkNode(data: Record<string, unknown>): CanvasNode {
  return { id: 'node-1', type: 'text', position: { x: 0, y: 0 }, data: { label: 'n', ...data } }
}

function mountPanel(node: CanvasNode | null) {
  return mount(PropertyPanel, { props: { node } })
}

describe('PropertyPanel (S12-c 资产库区)', () => {
  it('无节点 → 不渲染资产区', () => {
    const wrapper = mountPanel(null)
    expect(wrapper.text()).not.toContain('资产库')
  })

  it('未绑定节点 → 渲染入库/选择两按钮，无徽标无更新按钮', () => {
    const wrapper = mountPanel(mkNode({ prompt: 'x' }))
    const text = wrapper.text()
    expect(text).toContain('存入资产库')
    expect(text).toContain('从库选择')
    expect(text).not.toContain('来自资产')
    expect(text).not.toContain('检查更新')
  })

  it('已绑定节点 → 渲染徽标 + 检查更新/更新到最新版（有新版时高亮）', () => {
    const wrapper = mountPanel(mkNode({
      prompt: 'x',
      assetId: 88, assetName: '老板娘', assetVersion: 2, assetHasUpdate: true
    }))
    const text = wrapper.text()
    expect(text).toContain('来自资产 · 老板娘 v2')
    expect(text).toContain('有新版')
    expect(text).toContain('检查更新')
    expect(text).toContain('更新到最新版')
  })

  it('有新版=false → 更新到最新版 禁用', () => {
    const wrapper = mountPanel(mkNode({
      prompt: 'x', assetId: 88, assetVersion: 2, assetHasUpdate: false
    }))
    const updateBtn = wrapper.findAll('button').find((b) => b.text().includes('更新到最新版'))!
    expect(updateBtn.attributes('disabled')).toBeDefined()
  })

  it('点击「存入资产库」→ emit save-to-asset 带 node', async () => {
    const node = mkNode({ prompt: 'x' })
    const wrapper = mountPanel(node)
    const btn = wrapper.findAll('button').find((b) => b.text().includes('存入资产库'))!
    await btn.trigger('click')
    expect(wrapper.emitted('save-to-asset')).toBeTruthy()
    expect((wrapper.emitted('save-to-asset')![0][0] as CanvasNode).id).toBe('node-1')
  })

  it('点击「从库选择」→ emit pick-from-asset', async () => {
    const wrapper = mountPanel(mkNode({ prompt: 'x' }))
    const btn = wrapper.findAll('button').find((b) => b.text().includes('从库选择'))!
    await btn.trigger('click')
    expect(wrapper.emitted('pick-from-asset')).toBeTruthy()
  })

  it('点击「检查更新/更新到最新版」→ 分别 emit check-update / update-asset', async () => {
    const wrapper = mountPanel(mkNode({
      prompt: 'x', assetId: 88, assetVersion: 1, assetHasUpdate: true
    }))
    const checkBtn = wrapper.findAll('button').find((b) => b.text().includes('检查更新'))!
    await checkBtn.trigger('click')
    expect(wrapper.emitted('check-update')).toBeTruthy()

    const updateBtn = wrapper.findAll('button').find((b) => b.text().includes('更新到最新版'))!
    await updateBtn.trigger('click')
    expect(wrapper.emitted('update-asset')).toBeTruthy()
  })
})
