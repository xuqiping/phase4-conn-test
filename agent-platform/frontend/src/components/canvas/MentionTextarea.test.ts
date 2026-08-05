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
 * 受控 v-model 宿主：真实使用中父组件会回写 modelValue（prop ↔ emit 闭环），
 * 直接 mount 不回写会导致 textarea.value 在响应式 flush 后回退到旧 prop，测不出真实交互。
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
  const wrapper = mount(Host)
  return wrapper
}

/** 模拟用户输入：设 DOM value + 光标，dispatch input 事件（触发 onInput + detectAnchor）。 */
async function typeInto(wrapper: ReturnType<typeof mountHost>, value: string, caret = value.length) {
  const ta = wrapper.find('textarea').element as HTMLTextAreaElement
  ta.value = value
  ta.setSelectionRange(caret, caret)
  ta.dispatchEvent(new Event('input', { bubbles: true }))
  await wrapper.vm.$nextTick()
}

describe('MentionTextarea (S13 @引用输入框)', () => {
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

  it('选中候选 → 插入 @{{node:id}} 占位符 + 尾随空格', async () => {
    const wrapper = mountHost()
    await typeInto(wrapper, '扩写 @')
    await wrapper.findAll('.mention-ta__item')[0].trigger('mousedown')
    // mousedown.prevent 不失焦；selectCandidate 同步 emit + 改宿主 text
    expect((wrapper.vm as unknown as { text: string }).text).toBe('扩写 @{{node:n1}} ')
  })

  it('占位符插在 @ 锚点处（光标在 @ 之后、文本中段）', async () => {
    const wrapper = mountHost('前 @ 后')
    // 光标置于 @ 之后（下标 3），触发 input → detectAnchor 命中 anchor=2
    await typeInto(wrapper, '前 @ 后', 3)
    expect(wrapper.find('.mention-ta__popover').exists()).toBe(true)
    await wrapper.findAll('.mention-ta__item')[1].trigger('mousedown')
    expect((wrapper.vm as unknown as { text: string }).text).toBe('前 @{{node:n2}} 后')
  })

  it('候选为空 → 显「无可引用祖先节点」提示（环画布 / 无连线）', async () => {
    const wrapper = mountHost('', [])
    await typeInto(wrapper, '@')
    expect(wrapper.find('.mention-ta__empty').exists()).toBe(true)
  })

  it('Escape 关闭弹层', async () => {
    const wrapper = mountHost()
    await typeInto(wrapper, '@')
    await wrapper.find('textarea').trigger('keydown', { key: 'Escape' })
    expect(wrapper.find('.mention-ta__popover').exists()).toBe(false)
  })

  it('Enter 选中当前高亮项（默认第一项）', async () => {
    const wrapper = mountHost()
    await typeInto(wrapper, '@')
    await wrapper.find('textarea').trigger('keydown', { key: 'Enter' })
    expect((wrapper.vm as unknown as { text: string }).text).toContain('@{{node:n1}}')
  })

  it('@ 后输入空格 → 关闭弹层（@ 与查询被空格断开）', async () => {
    const wrapper = mountHost()
    await typeInto(wrapper, '@ ')
    expect(wrapper.find('.mention-ta__popover').exists()).toBe(false)
  })
})
