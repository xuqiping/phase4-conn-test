import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import { defineComponent, ref } from 'vue'
import MentionTextarea from './MentionTextarea.vue'
import type { MentionCandidate } from '@/types/canvas'

const cands: MentionCandidate[] = [
  { kind: 'node', id: 'n1', label: '人物设定' },
  { kind: 'node', id: 'n2', label: '场景描述' }
]

/**
 * 受控 v-model 宿主：父组件回写 modelValue（prop ↔ emit 闭环）。
 * 不回写会导致 watch 把 prop 旧值 render 回去，测不出真实交互。
 */
function mountHost(initial = '', candidates: MentionCandidate[] = cands) {
  const Host = defineComponent({
    components: { MentionTextarea },
    setup() {
      const text = ref(initial)
      return { text, candidates }
    },
    template: `<MentionTextarea v-model="text" :candidates="candidates" />`
  })
  return mount(Host)
}

/** contenteditable 模拟输入：写 textContent + 用 Range 设光标 + dispatch input。 */
async function typeInto(wrapper: ReturnType<typeof mountHost>, value: string, caret = value.length) {
  const el = wrapper.find('.mention-ta__input').element as HTMLElement
  el.textContent = value
  el.focus()
  const sel = window.getSelection()
  sel?.removeAllRanges()
  const range = document.createRange()
  const tn = el.firstChild
  if (tn && tn.nodeType === Node.TEXT_NODE) {
    range.setStart(tn, Math.min(caret, (tn.nodeValue ?? '').length))
    range.setEnd(tn, Math.min(caret, (tn.nodeValue ?? '').length))
  } else {
    range.selectNodeContents(el)
    range.collapse(false)
  }
  sel?.addRange(range)
  el.dispatchEvent(new Event('input', { bubbles: true }))
  await wrapper.vm.$nextTick()
}

describe('MentionTextarea · A1 chip 显节点名（contenteditable）', () => {
  it('AC-A1-1 占位符渲染为 chip，显节点名 label（非 raw token）', () => {
    const wrapper = mount(MentionTextarea, {
      props: { modelValue: '扩写 @{{node:n1}} 继续', candidates: cands }
    })
    const chips = wrapper.findAll('.mention-ta__chip')
    expect(chips).toHaveLength(1)
    expect(chips[0].text()).toBe('人物设定') // 显人话，非 @{{node:n1}}
    expect(chips[0].attributes('contenteditable')).toBe('false')
    expect(chips[0].attributes('data-mention')).toBe('@{{node:n1}}') // 底层存 token
  })

  it('AC-A1-2 点击 chip → emit mention-click {kind,id,raw}', async () => {
    const wrapper = mount(MentionTextarea, {
      props: { modelValue: '@{{node:n1}}', candidates: cands }
    })
    await wrapper.find('.mention-ta__chip').trigger('click')
    const events = wrapper.emitted('mention-click')
    expect(events).toHaveLength(1)
    expect(events![0][0]).toEqual({ kind: 'node', id: 'n1', raw: '@{{node:n1}}' })
  })

  it('AC-A1-3 断链 chip 加 is-broken（黄）+ 点击不 emit mention-click', async () => {
    const wrapper = mount(MentionTextarea, {
      props: { modelValue: '@{{node:n1}}', candidates: cands, brokenMentions: ['@{{node:n1}}'] }
    })
    const chip = wrapper.find('.mention-ta__chip')
    expect(chip.classes()).toContain('is-broken')
    await chip.trigger('click')
    expect(wrapper.emitted('mention-click')).toBeUndefined()
  })

  it('AC-A1-4 纯文本无占位符 → 不产 chip', () => {
    const wrapper = mount(MentionTextarea, {
      props: { modelValue: '普通文本无引用', candidates: cands }
    })
    expect(wrapper.findAll('.mention-ta__chip')).toHaveLength(0)
  })

  it('AC-A1-5 多占位符逐个显名（断链与非断链混排）', () => {
    const wrapper = mount(MentionTextarea, {
      props: {
        modelValue: '@{{node:n1}} 和 @{{asset:a1}}',
        candidates: [...cands, { kind: 'asset', id: 'a1', label: '主视觉图' }],
        brokenMentions: ['@{{asset:a1}}']
      }
    })
    const chips = wrapper.findAll('.mention-ta__chip')
    expect(chips).toHaveLength(2)
    expect(chips[0].text()).toBe('人物设定')
    expect(chips[0].classes()).not.toContain('is-broken')
    expect(chips[1].text()).toBe('主视觉图')
    expect(chips[1].classes()).toContain('is-broken')
  })

  it('AC-A1-6 候选 label 变化 → chip 显名响应式同步', async () => {
    const wrapper = mount(MentionTextarea, {
      props: { modelValue: '@{{node:n1}}', candidates: cands }
    })
    expect(wrapper.find('.mention-ta__chip').text()).toBe('人物设定')
    await wrapper.setProps({ candidates: [{ kind: 'node', id: 'n1', label: '新名字' }] })
    expect(wrapper.find('.mention-ta__chip').text()).toBe('新名字')
  })

  it('AC-A1-7 断链且候选已无 → chip 回退显 raw token（黄底可见）', () => {
    const wrapper = mount(MentionTextarea, {
      props: { modelValue: '@{{node:gone}}', candidates: cands, brokenMentions: ['@{{node:gone}}'] }
    })
    expect(wrapper.find('.mention-ta__chip').text()).toBe('@{{node:gone}}')
  })
})

describe('MentionTextarea · @ 唤起与候选选择', () => {
  it('无 @ → 弹层不出现', async () => {
    const wrapper = mountHost('普通文本')
    await typeInto(wrapper, '普通文本')
    expect(wrapper.find('.mention-ta__popover').exists()).toBe(false)
  })

  it('输入独立 @ → 唤起候选弹层', async () => {
    const wrapper = mountHost()
    await typeInto(wrapper, '扩写 @')
    expect(wrapper.find('.mention-ta__popover').exists()).toBe(true)
    expect(wrapper.findAll('.mention-ta__item')).toHaveLength(2)
  })

  it('@ 后接查询串 → label 过滤', async () => {
    const wrapper = mountHost()
    await typeInto(wrapper, '扩写 @人物')
    const items = wrapper.findAll('.mention-ta__item')
    expect(items).toHaveLength(1)
    expect(items[0].text()).toContain('人物设定')
  })

  it('邮箱类 foo@bar 不误判（@ 前非空白）', async () => {
    const wrapper = mountHost()
    await typeInto(wrapper, '联系 foo@bar')
    expect(wrapper.find('.mention-ta__popover').exists()).toBe(false)
  })

  it('中文句末无空格 @ 也唤起', async () => {
    const wrapper = mountHost()
    await typeInto(wrapper, '主角走进房间@')
    expect(wrapper.find('.mention-ta__popover').exists()).toBe(true)
  })

  it('选中候选 → 插入 token + 尾随空格', async () => {
    const wrapper = mountHost()
    await typeInto(wrapper, '扩写 @')
    await wrapper.findAll('.mention-ta__item')[0].trigger('mousedown')
    expect((wrapper.vm as unknown as { text: string }).text).toBe('扩写 @{{node:n1}} ')
  })

  it('Escape 关闭弹层', async () => {
    const wrapper = mountHost()
    await typeInto(wrapper, '@')
    await wrapper.find('.mention-ta__input').trigger('keydown', { key: 'Escape' })
    expect(wrapper.find('.mention-ta__popover').exists()).toBe(false)
  })

  it('Enter 选中当前高亮项', async () => {
    const wrapper = mountHost()
    await typeInto(wrapper, '@')
    await wrapper.find('.mention-ta__input').trigger('keydown', { key: 'Enter' })
    expect((wrapper.vm as unknown as { text: string }).text).toContain('@{{node:n1}}')
  })

  it('@ 后输入空格 → 关闭弹层', async () => {
    const wrapper = mountHost()
    await typeInto(wrapper, '@ ')
    expect(wrapper.find('.mention-ta__popover').exists()).toBe(false)
  })

  it('候选为空 → 显空态提示', async () => {
    const wrapper = mountHost('', [])
    await typeInto(wrapper, '@')
    expect(wrapper.find('.mention-ta__empty').exists()).toBe(true)
  })

  it('AC-V3-01 插入首个 chip 后继续输入 @ 仍唤起全部候选', async () => {
    const wrapper = mountHost()
    await typeInto(wrapper, '@')
    await wrapper.findAll('.mention-ta__item')[0].trigger('mousedown')
    await wrapper.vm.$nextTick()

    const editor = wrapper.find('.mention-ta__input').element as HTMLElement
    const tail = editor.lastChild as Text
    tail.nodeValue = `${tail.nodeValue ?? ''}@`
    const range = document.createRange()
    range.setStart(tail, tail.length)
    range.collapse(true)
    const selection = window.getSelection()
    selection?.removeAllRanges()
    selection?.addRange(range)
    editor.dispatchEvent(new Event('input', { bubbles: true }))
    await wrapper.vm.$nextTick()

    expect(wrapper.find('.mention-ta__popover').exists()).toBe(true)
    expect(wrapper.findAll('.mention-ta__item')).toHaveLength(2)
  })
})

describe('MentionTextarea · 序列化往返（存 token）', () => {
  it('外部设含 token 的 modelValue → 内部 chip；用户后续输入不破坏 token', async () => {
    // 初始带 chip
    const wrapper = mountHost('前 @{{node:n1}} 后')
    expect(wrapper.find('.mention-ta__chip').text()).toBe('人物设定')
    // v-model 仍存 token（未被渲染层污染）
    expect((wrapper.vm as unknown as { text: string }).text).toBe('前 @{{node:n1}} 后')
  })
})

describe('MentionTextarea · D3 appendMention 程序化追加（上游面板双击）', () => {
  it('空文本 → 追加 token + 尾随空格', async () => {
    const wrapper = mountHost('')
    const mt = wrapper.findComponent(MentionTextarea)
    const ok = (mt.vm as unknown as { appendMention: (c: { kind: string; id: string }) => boolean })
      .appendMention({ kind: 'node', id: 'n1' })
    expect(ok).toBe(true)
    expect((wrapper.vm as unknown as { text: string }).text).toBe('@{{node:n1}} ')
  })

  it('末尾非空白 → 先补一空格再接 token', async () => {
    const wrapper = mountHost('扩写')
    const mt = wrapper.findComponent(MentionTextarea)
    ;(mt.vm as unknown as { appendMention: (c: { kind: string; id: string }) => boolean })
      .appendMention({ kind: 'node', id: 'n2' })
    expect((wrapper.vm as unknown as { text: string }).text).toBe('扩写 @{{node:n2}} ')
  })

  it('末尾已是空白 → 不双补空格', async () => {
    const wrapper = mountHost('扩写\n')
    const mt = wrapper.findComponent(MentionTextarea)
    ;(mt.vm as unknown as { appendMention: (c: { kind: string; id: string }) => boolean })
      .appendMention({ kind: 'node', id: 'n1' })
    expect((wrapper.vm as unknown as { text: string }).text).toBe('扩写\n@{{node:n1}} ')
  })

  it('追加后 chip 渲染显名（token 进入 labelMap 解析链）', async () => {
    const wrapper = mountHost('')
    const mt = wrapper.findComponent(MentionTextarea)
    ;(mt.vm as unknown as { appendMention: (c: { kind: string; id: string }) => boolean })
      .appendMention({ kind: 'node', id: 'n1' })
    await wrapper.vm.$nextTick()
    expect(wrapper.find('.mention-ta__chip').text()).toBe('人物设定')
  })

  it('禁用态（生成中）→ 返回 false 不动文本', () => {
    const wrapper = mount(MentionTextarea, {
      props: { modelValue: '原文', candidates: cands, disabled: true }
    })
    const ok = (wrapper.vm as unknown as { appendMention: (c: { kind: string; id: string }) => boolean })
      .appendMention({ kind: 'node', id: 'n1' })
    expect(ok).toBe(false)
    expect(wrapper.emitted('update:modelValue')).toBeUndefined()
  })

  it('insertSuffix 跟随 token（标注图子序号候选）', () => {
    const wrapper = mountHost('')
    const mt = wrapper.findComponent(MentionTextarea)
    ;(mt.vm as unknown as { appendMention: (c: { kind: string; id: string; insertSuffix?: string }) => boolean })
      .appendMention({ kind: 'node', id: 'n1', insertSuffix: '：序号1（红色）框' })
    expect((wrapper.vm as unknown as { text: string }).text)
      .toBe('@{{node:n1}}：序号1（红色）框 ')
  })
})

describe('MentionTextarea · D4 弹层光标锚定兜底', () => {
  it('jsdom 无布局 → 内联定位回落空对象（CSS 静态输入框下方），弹层仍渲染', async () => {
    const wrapper = mountHost()
    await typeInto(wrapper, '@')
    const pop = wrapper.find('.mention-ta__popover')
    expect(pop.exists()).toBe(true)
    expect((pop.attributes('style') ?? '').includes('left:')).toBe(false)
  })
})

/** 修复IV C1b（C-4 缺口2）：失焦（非点候选行）→ blur-committed 上抛，父级据此走自动保存。 */
describe('MentionTextarea · 修复IV C1b blur 提交', () => {
  it('失焦 → emit blur-committed 恰一次', async () => {
    const wrapper = mountHost('文本')
    await wrapper.find('.mention-ta__input').trigger('blur')
    expect(wrapper.findComponent(MentionTextarea).emitted('blur-committed')).toHaveLength(1)
  })
})
