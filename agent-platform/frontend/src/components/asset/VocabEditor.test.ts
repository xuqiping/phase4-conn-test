import { describe, expect, it, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import VocabEditor from './VocabEditor.vue'
import type { MediaTypeDef } from '@/types/asset'

/** 默认媒体类型受控词汇（V60，与后端 DEFAULT_MEDIA_TYPES 对齐）。 */
const DEFAULT_MT: MediaTypeDef[] = [
  { key: 'PROMPT', category: 'TEXT' },
  { key: 'SCRIPT', category: 'TEXT' },
  { key: 'IMAGE', category: 'IMAGE' },
  { key: 'VIDEO', category: 'VIDEO' },
  { key: 'AUDIO', category: 'AUDIO' }
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

function mountEditor(props: Record<string, unknown>) {
  return mount(VocabEditor, {
    props: { show: true, narrativeRoles: ['人物'], mediaTypes: DEFAULT_MT, ...props },
    attachTo: document.body
  })
}

describe('VocabEditor C1a 叙事角色桶 + C1b 媒体类型两层编辑', () => {
  beforeEach(() => {
    document.body.innerHTML = ''
  })

  it('AC-C1a-1 打开时拷贝 narrativeRoles 为草稿（每角色一行）', async () => {
    mountEditor({ narrativeRoles: ['人物', '场景'] })
    await nextTick()
    // 媒体类型 Tab 也有 key 输入框；切到叙事角色 Tab 默认激活，角色行在前
    const inputs = bodyInputs().filter((i) => i.value === '人物' || i.value === '场景')
    expect(inputs.length).toBe(2)
  })

  it('AC-C1a-2 新增角色 + 保存 → emit save 含去重后的新角色集', async () => {
    const wrapper = mountEditor({ narrativeRoles: ['人物', '场景'] })
    await nextTick()
    // 点「+ 新增角色」→ 多出一空行
    ;(findBtn('新增角色') as HTMLButtonElement).click()
    await nextTick()
    // 角色行的 input：取值为空且在叙事角色 Tab 的最后一个文本输入
    const inputs = bodyInputs()
    const last = inputs[inputs.length - 1]
    last.value = '地图'
    last.dispatchEvent(new Event('input', { bubbles: true }))
    await nextTick()
    ;(findBtn('保存') as HTMLButtonElement).click()
    await nextTick()
    const saveEvt = wrapper.emitted('save')
    expect(saveEvt).toBeTruthy()
    expect(saveEvt![0][0]).toMatchObject({ roles: ['人物', '场景', '地图'] })
  })

  it('AC-C1a-3 失焦去重：与他行重名 → 该行清空', async () => {
    mountEditor({ narrativeRoles: ['人物'] })
    await nextTick()
    ;(findBtn('新增角色') as HTMLButtonElement).click()
    await nextTick()
    const inputs = bodyInputs()
    const newRow = inputs[1]
    newRow.value = '人物'
    newRow.dispatchEvent(new Event('input', { bubbles: true }))
    await nextTick()
    newRow.dispatchEvent(new Event('blur', { bubbles: true }))
    await nextTick()
    expect(newRow.value).toBe('')
  })

  it('AC-C1a-4 仅剩 1 角色 → 删除按钮禁用（防删空，后端 normalize 兜底非空）', async () => {
    mountEditor({ narrativeRoles: ['人物'] })
    await nextTick()
    // 叙事角色 Tab 第一个删除按钮
    const dels = bodyButtons().filter((b) => b.textContent?.trim() === '删除')
    expect(dels[0].disabled).toBe(true)
  })

  it('AC-C1a-5 roleAssetCounts 透传删桶迁移数（prop 被接受不崩）', async () => {
    mountEditor({ narrativeRoles: ['人物', '场景'], roleAssetCounts: { 人物: 3 } })
    await nextTick()
    const dels = bodyButtons().filter((b) => b.textContent?.trim() === '删除')
    // >1 角色：第一个删除按钮可用
    expect(dels[0].disabled).toBe(false)
  })

  it('AC-C1b-1 mediaTypes prop → 草稿 → save payload 透传（5 项原样回传，证明 prop/草稿/归一化管线通）', async () => {
    const wrapper = mountEditor({ mediaTypes: DEFAULT_MT })
    await nextTick()
    ;(findBtn('保存') as HTMLButtonElement).click()
    await nextTick()
    const saveEvt = wrapper.emitted('save')
    expect(saveEvt).toBeTruthy()
    const payload = saveEvt![0][0] as { mediaTypes: MediaTypeDef[] }
    expect(payload.mediaTypes).toEqual(DEFAULT_MT)
  })
})
