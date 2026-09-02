import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import { NInputNumber, NSelect } from 'naive-ui'
import AssetMatrixFilter, { type AssetFilter } from './AssetMatrixFilter.vue'
import type { MatrixCountVO, MediaTypeDef, NarrativeRoleVocab } from '@/types/asset'

/** 构造矩阵计数：图片×人物=3，图片×(无角色)=1，剧本×人物=2，图片×老人(子类)=2 */
function mkCounts(): MatrixCountVO {
  return {
    cells: [
      { mediaType: '图片', roleKey: '人物', count: 3 },
      { mediaType: '图片', roleKey: null, count: 1 },
      { mediaType: '剧本', roleKey: '人物', count: 2 },
      { mediaType: '图片', roleKey: '老人', count: 2 }
    ],
    typeTotals: [
      { mediaType: '图片', roleKey: null, count: 6 },
      { mediaType: '剧本', roleKey: null, count: 2 }
    ]
  }
}

interface FilterProps {
  modelValue?: AssetFilter
  counts?: MatrixCountVO
  roles?: NarrativeRoleVocab[]
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
      roles: [
        { key: '人物', children: ['老人'] },
        { key: '道具', children: [] }
      ],
      mediaTypes: DEFAULT_MEDIA_TYPES,
      ...props
    }
  })
}

describe('AssetMatrixFilter (S11)', () => {
  it('未选筛选：全部徽标=总数(8)，图片=6；一级人物徽标聚合子类=7(5+2)，老人=2（修复XI 两级）', () => {
    const wrapper = mountFilter()
    const badges = wrapper.findAll('.matrix-filter__badge')
    const texts = badges.map((b) => b.text())
    // 顺序：[全部类型, 提示词0, 剧本2, 图片6, 视频0, 音频0, 全部角色8, 人物(聚合)=7, 老人=2, 道具0]
    expect(texts).toEqual(['8', '0', '2', '6', '0', '0', '8', '7', '2', '0'])
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

  it('选类型 图片 后角色徽标下钻：人物(聚合)=5(3+2)，老人=2，全部角色=6，道具=0', async () => {
    const wrapper = mountFilter({ modelValue: { type: '图片' } })
    const roleBadges = wrapper.findAll('.matrix-filter__role .matrix-filter__badge')
    const texts = roleBadges.map((b) => b.text())
    // [全部角色=图片总数6, 人物(聚合)=cell(图片,人物)+cell(图片,老人)=5, 老人=2, 道具=0]
    expect(texts).toEqual(['6', '5', '2', '0'])
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
    await roles[1].trigger('click') // 人物（一级）
    const emitsRole = wrapper.emitted('update:modelValue')!
    expect(emitsRole[emitsRole.length - 1][0]).toMatchObject({ role: '人物' })
    await roles[0].trigger('click') // 全部角色
    const emitsAll = wrapper.emitted('update:modelValue')!
    expect(emitsAll[emitsAll.length - 1][0]).toMatchObject({ role: undefined })
  })

  it('XI3 点击子类行 → emit role=子类 key（精确筛，active 态落子类行不落一级）', async () => {
    const wrapper = mountFilter()
    await wrapper.findAll('.matrix-filter__role--child')[0].trigger('click') // 老人
    const emits = wrapper.emitted('update:modelValue')!
    expect(emits[emits.length - 1][0]).toMatchObject({ role: '老人' })
    // 受控组件：active 态由 modelValue 驱动 → 回放 role=老人 断言落点
    const active = mountFilter({ modelValue: { role: '老人' } })
    const child = active.findAll('.matrix-filter__role--child')[0]
    const group = active.findAll('.matrix-filter__role')[1] // 人物（一级）
    expect(child.classes()).toContain('matrix-filter__role--active')
    expect(group.classes()).not.toContain('matrix-filter__role--active')
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

  // ---------- C7 上传者/分数筛选（2x第三轮；DOM 顺序：creator=第1个 NSelect，source=第2个，grade=第3个） ----------

  it('C7-1 上传者选择 → emit creatorUsername', async () => {
    const wrapper = mountFilter({ modelValue: { creatorUsername: 'zhang3' } })
    const selects = wrapper.findAllComponents(NSelect)
    await selects[0].vm.$emit('update:value', 'li4')
    const emits = wrapper.emitted('update:modelValue')!
    expect(emits[emits.length - 1][0]).toMatchObject({ creatorUsername: 'li4' })
  })

  it('C7-2 上传者清空 → creatorUsername=undefined', async () => {
    const wrapper = mountFilter({ modelValue: { creatorUsername: 'zhang3' } })
    const selects = wrapper.findAllComponents(NSelect)
    await selects[0].vm.$emit('update:value', null)
    const emits = wrapper.emitted('update:modelValue')!
    expect(emits[emits.length - 1][0]).toMatchObject({ creatorUsername: undefined })
  })

  it('C7-3 分数来源选择 → emit scoreSource=member；清空回 undefined', async () => {
    const wrapper = mountFilter()
    const selects = wrapper.findAllComponents(NSelect)
    await selects[1].vm.$emit('update:value', 'member')
    let emits = wrapper.emitted('update:modelValue')!
    expect(emits[emits.length - 1][0]).toMatchObject({ scoreSource: 'member' })
    await selects[1].vm.$emit('update:value', null)
    emits = wrapper.emitted('update:modelValue')!
    expect(emits[emits.length - 1][0]).toMatchObject({ scoreSource: undefined })
  })

  it('C7-4 分数区间输入 → emit scoreMin/scoreMax；清空回 undefined', async () => {
    const wrapper = mountFilter()
    const numbers = wrapper.findAllComponents(NInputNumber)
    await numbers[0].vm.$emit('update:value', 60)
    let emits = wrapper.emitted('update:modelValue')!
    expect(emits[emits.length - 1][0]).toMatchObject({ scoreMin: 60 })
    await numbers[1].vm.$emit('update:value', 90)
    emits = wrapper.emitted('update:modelValue')!
    expect(emits[emits.length - 1][0]).toMatchObject({ scoreMax: 90 })
    await numbers[0].vm.$emit('update:value', null)
    emits = wrapper.emitted('update:modelValue')!
    expect(emits[emits.length - 1][0]).toMatchObject({ scoreMin: undefined })
  })

  it('C7-5 等级快捷筛选：选 A+ → 覆盖区间 95-100；清除等级不动手动区间（L4）', async () => {
    const wrapper = mountFilter()
    const selects = wrapper.findAllComponents(NSelect)
    // 选 A+ → emit scoreMin/scoreMax 覆盖（等级是快捷方式，冲突时以等级为准）
    await selects[2].vm.$emit('update:value', 'A+')
    let emits = wrapper.emitted('update:modelValue')!
    expect(emits[emits.length - 1][0]).toMatchObject({ scoreMin: 95, scoreMax: 100 })
    // 选 D → [0,69]（换档即重映射）
    await selects[2].vm.$emit('update:value', 'D')
    emits = wrapper.emitted('update:modelValue')!
    expect(emits[emits.length - 1][0]).toMatchObject({ scoreMin: 0, scoreMax: 69 })
    // 清除等级 → 不再 emit（手动区间原样保留）
    const countBefore = wrapper.emitted('update:modelValue')!.length
    await selects[2].vm.$emit('update:value', null)
    expect(wrapper.emitted('update:modelValue')!.length).toBe(countBefore)
  })
})
