import { describe, expect, it, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import VocabEditor from './VocabEditor.vue'
import type { MediaTypeDef, NarrativeRoleVocab } from '@/types/asset'

/** 默认媒体类型受控词汇（中文 key，与后端 Asset.MEDIA_* 对齐）。 */
const DEFAULT_MT: MediaTypeDef[] = [
  { key: '提示词', category: 'TEXT' },
  { key: '剧本', category: 'TEXT' },
  { key: '图片', category: 'IMAGE' },
  { key: '视频', category: 'VIDEO' },
  { key: '音频', category: 'AUDIO' }
]

/** n-modal teleport 到 document.body，故按 body 查元素。 */
function bodyInputs(): HTMLInputElement[] {
  return Array.from(document.body.querySelectorAll('input'))
}
function bodyButtons(): HTMLButtonElement[] {
  return Array.from(document.body.querySelectorAll('button'))
}
function findBtn(text: string): HTMLButtonElement | undefined {
  return bodyButtons().find((b) => b.textContent?.trim().includes(text))
}
/** 一级角色名输入框（placeholder 区分子类添加框/媒体类型 key 框）。 */
function levelInputs(): HTMLInputElement[] {
  return bodyInputs().filter((i) => i.placeholder.includes('一级角色名'))
}
/** 子类添加输入框（第 i 组）。 */
function childAddInputs(): HTMLInputElement[] {
  return bodyInputs().filter((i) => i.placeholder.includes('子类名'))
}
/** 原生设值 + 触发 v-model（n-input 监听 input 事件）。 */
function setInput(input: HTMLInputElement, value: string) {
  input.value = value
  input.dispatchEvent(new Event('input', { bubbles: true }))
}
function pressEnter(input: HTMLInputElement) {
  input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }))
}

function mountEditor(props: Record<string, unknown>) {
  return mount(VocabEditor, {
    props: {
      show: true,
      narrativeRoles: [{ key: '人物', children: [] }] as NarrativeRoleVocab[],
      mediaTypes: DEFAULT_MT,
      ...props
    },
    attachTo: document.body
  })
}

/** 读取 save 事件 payload（须已触发）。 */
function savePayload(wrapper: ReturnType<typeof mountEditor>): { roles: NarrativeRoleVocab[]; mediaTypes: MediaTypeDef[] } {
  return wrapper.emitted('save')![0][0] as { roles: NarrativeRoleVocab[]; mediaTypes: MediaTypeDef[] }
}

describe('VocabEditor 修复XI 叙事角色两级 + C1b 媒体类型两层编辑', () => {
  beforeEach(() => {
    document.body.innerHTML = ''
  })

  it('AC-XI3-1 打开时拷贝两级草稿（一级一行 + 子类 tag 原样呈现）', async () => {
    mountEditor({
      narrativeRoles: [
        { key: '人物', children: ['老人'] },
        { key: '场景', children: [] }
      ]
    })
    await nextTick()
    const level = levelInputs().map((i) => i.value)
    expect(level).toEqual(['人物', '场景'])
    expect(document.body.textContent).toContain('老人')
  })

  it('AC-XI3-2 子类输入回车添加 → save payload 含两级结构', async () => {
    const wrapper = mountEditor({
      narrativeRoles: [
        { key: '人物', children: ['老人'] },
        { key: '场景', children: [] }
      ]
    })
    await nextTick()
    setInput(childAddInputs()[0], '孩童')
    await nextTick()
    pressEnter(childAddInputs()[0])
    await nextTick()
    ;(findBtn('保存') as HTMLButtonElement).click()
    await nextTick()
    const payload = savePayload(wrapper)
    expect(payload.roles).toEqual([
      { key: '人物', children: ['老人', '孩童'] },
      { key: '场景', children: [] }
    ])
  })

  it('AC-XI3-3 子类撞已有一级 → 输入清空 + 行内错误（后端同口径 400 的前端前置）', async () => {
    mountEditor({
      narrativeRoles: [
        { key: '人物', children: [] },
        { key: '场景', children: [] }
      ]
    })
    await nextTick()
    // 在「场景」组加子类「人物」→ 撞一级名
    const sceneChild = childAddInputs()[1]
    setInput(sceneChild, '人物')
    await nextTick()
    pressEnter(sceneChild)
    await nextTick()
    expect(sceneChild.value).toBe('')
    const err = document.body.querySelector('.vocab-editor__child-error')
    expect(err?.textContent).toContain('重名')
    // 未入草稿：场景组 tag 区不出现「人物」
    expect(document.body.querySelectorAll('.n-tag').length).toBe(0)
  })

  it('AC-XI3-4 删子类 chip（tag close）→ save payload 该组 children 清空', async () => {
    const wrapper = mountEditor({
      narrativeRoles: [
        { key: '人物', children: ['老人'] },
        { key: '场景', children: [] }
      ]
    })
    await nextTick()
    const close = document.body.querySelector('.n-tag__close') as HTMLElement
    close.click()
    await nextTick()
    ;(findBtn('保存') as HTMLButtonElement).click()
    await nextTick()
    expect(savePayload(wrapper).roles[0]).toEqual({ key: '人物', children: [] })
  })

  it('AC-XI3-5 一级失焦撞已有子类（全局命名空间）→ 该行清空', async () => {
    mountEditor({
      narrativeRoles: [
        { key: '人物', children: ['老人'] },
        { key: '场景', children: [] }
      ]
    })
    await nextTick()
    ;(findBtn('新增一级角色') as HTMLButtonElement).click()
    await nextTick()
    const newRow = levelInputs()[2]
    setInput(newRow, '老人')
    await nextTick()
    newRow.dispatchEvent(new Event('blur', { bubbles: true }))
    await nextTick()
    expect(newRow.value).toBe('')
  })

  it('AC-XI3-6 仅剩 1 一级 → 删除按钮禁用（防删空，后端 normalize 兜底非空）', async () => {
    mountEditor({ narrativeRoles: [{ key: '人物', children: [] }] })
    await nextTick()
    const dels = bodyButtons().filter((b) => b.textContent?.trim() === '删除')
    expect(dels[0].disabled).toBe(true)
  })

  it('AC-C1b-1 mediaTypes prop → 草稿 → save payload 透传（5 项原样回传，证明 prop/草稿/归一化管线通）', async () => {
    const wrapper = mountEditor({ mediaTypes: DEFAULT_MT })
    await nextTick()
    ;(findBtn('保存') as HTMLButtonElement).click()
    await nextTick()
    expect(savePayload(wrapper).mediaTypes).toEqual(DEFAULT_MT)
  })
})
