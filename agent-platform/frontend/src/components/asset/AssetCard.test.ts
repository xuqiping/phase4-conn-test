import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import AssetCard from './AssetCard.vue'
import type { AssetVO } from '@/types/asset'

function mkAsset(over: Partial<AssetVO> = {}): AssetVO {
  return {
    id: 1,
    projectId: 7,
    mediaType: 'IMAGE',
    name: '老板娘定妆',
    description: '主参考图',
    tags: [],
    status: 'DRAFT',
    content: null,
    genMeta: null,
    currentVersion: 2,
    roleKeys: ['人物'],
    createdAt: '2026-08-05',
    ...over
  }
}

describe('AssetCard (S11)', () => {
  it('渲染名称/版本/类型/角色徽标', () => {
    const wrapper = mount(AssetCard, { props: { asset: mkAsset() } })
    expect(wrapper.text()).toContain('老板娘定妆')
    expect(wrapper.text()).toContain('v2')
    expect(wrapper.text()).toContain('图片')
    expect(wrapper.text()).toContain('人物')
    expect(wrapper.text()).toContain('草稿')
  })

  it('角色超 3 个聚合 +N', () => {
    const wrapper = mount(AssetCard, {
      props: { asset: mkAsset({ roleKeys: ['人物', '道具', '场景', '风格', '通用'] }) }
    })
    expect(wrapper.text()).toContain('+2')
  })

  it('点击 emit open（带原资产）', async () => {
    const asset = mkAsset()
    const wrapper = mount(AssetCard, { props: { asset } })
    await wrapper.trigger('click')
    expect(wrapper.emitted('open')).toBeTruthy()
    expect((wrapper.emitted('open')![0][0] as AssetVO).id).toBe(asset.id)
  })
})
