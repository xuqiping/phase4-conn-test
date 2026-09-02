import { describe, expect, it } from 'vitest'
import { PALETTE_ITEMS, mediaToNodeType } from './paletteItems'

/** 修复XI A1/B3：调色板单源清单 + 官方库插入链反向映射（spec XI-2⑤）。 */
describe('paletteItems · 修复XI 单源清单与媒体类型反向映射', () => {
  it('PALETTE_ITEMS：7 类且 type 唯一（调色板/右键菜单/官方库三消费方同源）', () => {
    expect(PALETTE_ITEMS).toHaveLength(7)
    expect(new Set(PALETTE_ITEMS.map((p) => p.type)).size).toBe(7)
    expect(PALETTE_ITEMS.map((p) => p.type)).toEqual(
      ['text', 'image', 'video', 'audio', 'script', 'storyboard', 'director'])
    for (const p of PALETTE_ITEMS) {
      expect(p.label.length).toBeGreaterThan(0)
      expect(p.icon).toBeTruthy()
    }
  })

  it('mediaToNodeType：六已知词汇直映（AssetPicker NODE_TO_MEDIA 之逆）', () => {
    expect(mediaToNodeType('提示词')).toBe('text')
    expect(mediaToNodeType('剧本')).toBe('script')
    expect(mediaToNodeType('分镜')).toBe('storyboard')
    expect(mediaToNodeType('图片')).toBe('image')
    expect(mediaToNodeType('视频')).toBe('video')
    expect(mediaToNodeType('音频')).toBe('audio')
  })

  it('mediaToNodeType：自定义类型按 mediaCategory 回落；全 miss 退 text（永不 undefined）', () => {
    expect(mediaToNodeType('角色模型', 'IMAGE')).toBe('image')
    expect(mediaToNodeType('音效变体', 'AUDIO')).toBe('audio')
    expect(mediaToNodeType('无类别自定义')).toBe('text')
    expect(mediaToNodeType('无类别自定义', null)).toBe('text')
    expect(mediaToNodeType('未知', 'UNKNOWN_CATEGORY')).toBe('text')
  })
})
