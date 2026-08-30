import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import ScriptScenes from './ScriptScenes.vue'
import type { SceneVO } from '@/types/asset'

describe('ScriptScenes C3 分场结构化渲染', () => {
  it('AC-C3-4 渲染分场列表（场号 + 描述）', () => {
    const scenes: SceneVO[] = [
      { index: 1, description: '开场：主角登场' },
      { index: 2, description: '高潮：冲突爆发' }
    ]
    const w = mount(ScriptScenes, { props: { scenes } })
    expect(w.findAll('.script-scenes__item')).toHaveLength(2)
    expect(w.text()).toContain('场 1')
    expect(w.text()).toContain('冲突爆发')
  })

  it('AC-C3-5 空分场 → 标题显 0 场', () => {
    const w = mount(ScriptScenes, { props: { scenes: [] } })
    expect(w.find('.script-scenes__title').text()).toContain('分场（0）')
  })
})
