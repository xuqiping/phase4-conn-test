import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import AssetMatrixFilter, { type AssetFilter } from './AssetMatrixFilter.vue'
import type { MatrixCountVO, MediaTypeDef } from '@/types/asset'

/** 构造矩阵计数：图片×人物=3，图片×(无角色)=1，剧本×人物=2 */
function mkCounts(): MatrixCountVO {
  return {
    cells: [
      { mediaType: '图片', roleKey: '人物', count: 3 },
      { mediaType: '图片', roleKey: null, count: 1 },
      { mediaType: '剧本', roleKey: '人物', count: 2 }
    ],
    typeTotals: [
      { mediaType: '图片', roleKey: null, count: 4 },
      { mediaType: '剧本', roleKey: null, count: 2 }
    ]
  }
}

interface FilterProps {
  modelValue?: AssetFilter
  counts?: MatrixCountVO
  roles?: string[]
  mediaTypes?: MediaTypeDef[]
}

/** 默认受控词汇五项（中文 key，与后端 Asset.MEDIA_* 对齐）。 */
const DEFAULT_MEDIA_TYPES: MediaTypeDef[] = [
  { key: '提示词', category: 'TEXT' },
  { key: '剧本', category: 'TEXT' },
  { key: '图片', category: 'IMAGE' },
  { key: '视频', category: 'VIDEO' },
  { key: '音频', category: 'AUDIO' }
]

function mountFilter(props: FilterProps = {}) {
  return mount(AssetMatrixFilter, {
    props: {
      modelValue: {},
      counts: mkCounts(),
      roles: ['人物', '道具'],
      mediaTypes: DEFAULT_MEDIA_TYPES,
      ...props
    }
  })
}

describe('AssetMatrixFilter (S11)', () => {
  it('未选筛选：全部徽标=总数(6)，图片=4，人物=5(3+2)', () => {
    const wrapper = mountFilter()
    const badges = wrapper.findAll('.matrix-filter__badge')
    const texts = badges.map((b) => b.text())
    // 顺序：[全部类型, 提示词0, 剧本2, 图片4, 视频0, 音频0, 全部角色6, 人物5, 道具0]
    expect(texts).toEqual(['6', '0', '2', '4', '0', '0', '6', '5', '0'])
  })

  it('点击图片类型 → emit type=图片', async () => {
    const wrapper = mountFilter()
    // 类型 chip：第 4 个（index 3）= 图片
    const typeChips = wrapper.findAll('.matrix-filter__chip')
    await typeChips[3].trigger('click')
    const emitted = wrapper.emitted('update:modelValue')
    expect(emitted).toBeTruthy()
    expect(emitted![0][0]).toMatchObject({ type: '图片' })
  })

  it('选类型 图片 后角色徽标下钻：人物=3，全部角色=4，道具=0', async () => {
    const wrapper = mountFilter({ modelValue: { type: '图片' } })
    const roleBadges = wrapper.findAll('.matrix-filter__role .matrix-filter__badge')
    const texts = roleBadges.map((b) => b.text())
    // [全部角色=IMAGE总数4, 人物=cell(IMAGE,人物)=3, 道具=0]
    expect(texts).toEqual(['4', '3', '0'])
  })

  it('选角色 人物 后类型徽标下钻：图片=3，剧本=2，全部=5', async () => {
    const wrapper = mountFilter({ modelValue: { role: '人物' } })
    const typeBadges = wrapper.findAll('.matrix-filter__chip .matrix-filter__badge')
    const texts = typeBadges.map((b) => b.text())
    // [全部=人物全类型5, 提示词0, 剧本2, 图片3, 视频0, 音频0]
    expect(texts).toEqual(['5', '0', '2', '3', '0', '0'])
  })

  it('点击角色 → emit role；再次点击全部角色 → role=undefined', async () => {
    const wrapper = mountFilter()
    const roles = wrapper.findAll('.matrix-filter__role')
    await roles[1].trigger('click') // 人物
    const emitsRole = wrapper.emitted('update:modelValue')!
    expect(emitsRole[emitsRole.length - 1][0]).toMatchObject({ role: '人物' })
    await roles[0].trigger('click') // 全部角色
    const emitsAll = wrapper.emitted('update:modelValue')!
    expect(emitsAll[emitsAll.length - 1][0]).toMatchObject({ role: undefined })
  })

  it('搜索输入 → emit q', async () => {
    const wrapper = mountFilter()
    await wrapper.find('input').setValue('老板娘')
    const emits = wrapper.emitted('update:modelValue')!
    expect(emits[emits.length - 1][0]).toMatchObject({ q: '老板娘' })
  })

  it('C1b 顶栏从 mediaTypes 派生（自定义「地图」类型出现）', () => {
    const wrapper = mountFilter({
      mediaTypes: [
        { key: '提示词', category: 'TEXT' },
        { key: '图片', category: 'IMAGE' },
        { key: 'MAP', category: 'IMAGE' }
      ]
    })
    const chips = wrapper.findAll('.matrix-filter__chip')
    const labels = chips.map((c) => c.find('.matrix-filter__chip-label').text())
    // 全部 + 提示词 + 图片 + MAP（自定义英文 key 显原文）
    expect(labels).toEqual(['全部', '提示词', '图片', 'MAP'])
  })
})
