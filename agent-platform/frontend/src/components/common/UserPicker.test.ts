// agent-platform/frontend/src/components/common/UserPicker.test.ts
import { describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import UserPicker, { type PickerUser } from './UserPicker.vue'

// 修复III E3（12x#4）：统一选人组件——远程搜索渲染 / chips 移除 / 键盘导航 / 备注展示
// 修复IV A2/A3（17x-1/17x-2）：全选反选 / 程序获焦不弹、mousedown 才开
const SEED: PickerUser[] = [
  { userId: 1, username: 'zhangsan', name: '张三', remark: 'A 班' },
  { userId: 2, username: 'lisi', name: null, remark: null },
  { userId: 3, username: 'wangwu', name: '王五', remark: 'B 班组长' }
]

function mkSearch() {
  return vi.fn().mockImplementation(async (kw: string) =>
    kw ? SEED.filter(u => u.username.includes(kw) || (u.name ?? '').includes(kw) || (u.remark ?? '').includes(kw)) : SEED)
}

function mountPicker(props: Record<string, unknown> = {}) {
  return mount(UserPicker, {
    props: { modelValue: null, search: mkSearch(), ...props }
  })
}

/** 修复IV A3：打开下拉的统一姿势 = mousedown（用户交互信号），不再是 focus */
async function openByClick(wrapper: ReturnType<typeof mountPicker>) {
  await wrapper.find('input').trigger('mousedown')
  await flushPromises()
}

describe('UserPicker (E3 12x#4 / IV-A 17x-1/2)', () => {
  it('mousedown 打开 → 默认搜索渲染候选（姓名·账号·备注 tag）', async () => {
    const wrapper = mountPicker()
    await openByClick(wrapper)
    const text = wrapper.text()
    expect(text).toContain('张三')
    expect(text).toContain('zhangsan')
    expect(text).toContain('A 班')
    // 无备注行不显 tag
    const opts = wrapper.findAll('.user-picker__option')
    expect(opts).toHaveLength(3)
    expect(opts[1].find('.user-picker__remark').exists()).toBe(false)
  })

  it('修复IV A3：程序 focus 不弹候选（弹窗打开瞬间安静）', async () => {
    const wrapper = mountPicker()
    await wrapper.find('input').trigger('focus')
    await flushPromises()
    expect(wrapper.find('.user-picker__listbox').exists()).toBe(false)
  })

  it('输入关键词 debounce 后带 keyword 搜索', async () => {
    vi.useFakeTimers()
    const search = mkSearch()
    const wrapper = mount(UserPicker, { props: { modelValue: null, search } })
    // 输入本身也是开列表信号（tab 键聚焦后直接打字场景）
    await wrapper.find('input').trigger('input')
    await wrapper.find('input').setValue('A 班')
    expect(search).not.toHaveBeenCalledWith('A 班')   // 300ms 内未发
    vi.advanceTimersByTime(350)
    await flushPromises()
    expect(search).toHaveBeenCalledWith('A 班')
    expect(wrapper.findAll('.user-picker__option')).toHaveLength(1)
    vi.useRealTimers()
  })

  it('multiple：点两行 → modelValue 数组累积；chips 可移除', async () => {
    const wrapper = mountPicker({ multiple: true, modelValue: [] })
    await openByClick(wrapper)
    const opts = wrapper.findAll('.user-picker__option')
    // 受控组件：emit 后父回传 modelValue 再点下一行（模拟 v-model 双向）
    await opts[0].trigger('mousedown')
    await wrapper.setProps({ modelValue: [1] })
    await opts[1].trigger('mousedown')
    await wrapper.setProps({ modelValue: [1, 2] })
    let events = wrapper.emitted('update:modelValue')!
    expect(events[events.length - 1][0]).toEqual([1, 2])
    expect(wrapper.findAll('.user-picker__chip')).toHaveLength(2)
    expect(wrapper.text()).toContain('张三')

    // 父侧移除（回传 [2]）→ chips 随 modelValue 收缩（下拉 options 仍显张三属正常，不在此断言全文）
    await wrapper.setProps({ modelValue: [2] })
    expect(wrapper.findAll('.user-picker__chip')).toHaveLength(1)
    expect(wrapper.find('.user-picker__chips').text()).not.toContain('张三')
  })

  it('修复IV A2：全选 = 当前候选并入（既有选择不丢）+ 计数行', async () => {
    const wrapper = mountPicker({ multiple: true, modelValue: [2] })
    await openByClick(wrapper)
    await wrapper.find('.user-picker__bulk-btn').trigger('mousedown')   // 全选
    const events = wrapper.emitted('update:modelValue')!
    expect(events[events.length - 1][0]).toEqual([2, 1, 3])
    expect(wrapper.find('.user-picker__bulk-count').text()).toContain('已选 1 / 候选 3')
  })

  it('修复IV A2：反选 = 对当前候选逐个翻转（在选移出、未选加入）', async () => {
    const wrapper = mountPicker({ multiple: true, modelValue: [2] })
    await openByClick(wrapper)
    const btns = wrapper.findAll('.user-picker__bulk-btn')
    await btns[1].trigger('mousedown')   // 反选
    const events = wrapper.emitted('update:modelValue')!
    expect(events[events.length - 1][0]).toEqual([1, 3])
  })

  it('修复IV A2：全选作用于换关键词后的新候选集，既有选择保留', async () => {
    vi.useFakeTimers()
    try {
      const wrapper = mountPicker({ multiple: true, modelValue: [2] })
      await openByClick(wrapper)
      await wrapper.find('input').setValue('A 班')
      vi.advanceTimersByTime(350)
      await flushPromises()
      expect(wrapper.findAll('.user-picker__option')).toHaveLength(1)   // 只剩张三
      await wrapper.find('.user-picker__bulk-btn').trigger('mousedown')
      const events = wrapper.emitted('update:modelValue')!
      expect(events[events.length - 1][0]).toEqual([2, 1])   // 2 不丢，1 并入，3 不在新候选
    } finally {
      vi.useRealTimers()
    }
  })

  it('修复IV A2：搜索进行中全选/反选禁用', async () => {
    let resolveSearch: (v: PickerUser[]) => void = () => {}
    const pending = new Promise<PickerUser[]>(r => { resolveSearch = r })
    const wrapper = mount(UserPicker, {
      props: { modelValue: [], multiple: true, search: () => pending }
    })
    await wrapper.find('input').trigger('mousedown')
    await flushPromises()
    for (const btn of wrapper.findAll('.user-picker__bulk-btn')) {
      expect(btn.attributes('disabled')).toBeDefined()
    }
    resolveSearch(SEED)
    await flushPromises()
  })

  it('单选：键盘 Enter 选中 active 项（初始 active=第1项）', async () => {
    const wrapper = mountPicker()
    await openByClick(wrapper)
    await wrapper.find('input').trigger('keydown.enter')
    const events = wrapper.emitted('update:modelValue')!
    expect(events[0][0]).toBe(1)
  })

  it('键盘 ↓ → active=第2项；Esc 关闭下拉', async () => {
    const wrapper = mountPicker()
    await openByClick(wrapper)
    const input = wrapper.find('input')
    await input.trigger('keydown.down')
    const opts = wrapper.findAll('.user-picker__option')
    expect(opts[1].classes()).toContain('is-active')
    expect(input.attributes('aria-activedescendant')).toBe('user-picker-opt-1')
    await input.trigger('keydown.esc')
    expect(wrapper.find('.user-picker__listbox').exists()).toBe(false)
  })

  it('a11y：listbox/option 角色齐全 + aria-activedescendant 跟随', async () => {
    const wrapper = mountPicker()
    await openByClick(wrapper)
    const list = wrapper.find('.user-picker__listbox')
    expect(list.attributes('role')).toBe('listbox')
    expect(wrapper.findAll('[role="option"]')).toHaveLength(3)
    const input = wrapper.find('input')
    expect(input.attributes('aria-activedescendant')).toBe('user-picker-opt-0')
    expect(input.attributes('aria-expanded')).toBe('true')
  })
})
