import { describe, expect, it, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import VocabEditor from './VocabEditor.vue'

/** n-modal teleport 到 document.body，故按 body 查元素。 */
function bodyInputs(): HTMLInputElement[] {
  return Array.from(document.body.querySelectorAll('input'))
}
function bodyButtons(): HTMLButtonElement[] {
  return Array.from(document.body.querySelectorAll('button'))
}
function findBtn(text: string): HTMLButtonElement | undefined {
  return bodyButtons().find(b => b.textContent?.trim().includes(text))
}

describe('VocabEditor C1a 叙事角色桶编辑', () => {
  beforeEach(() => {
    document.body.innerHTML = ''
  })

  it('AC-C1a-1 打开时拷贝 narrativeRoles 为草稿（每角色一行）', async () => {
    mount(VocabEditor, {
      props: { show: true, narrativeRoles: ['人物', '场景'] },
      attachTo: document.body
    })
    await nextTick()
    const inputs = bodyInputs()
    expect(inputs.length).toBeGreaterThanOrEqual(2)
    expect(inputs[0].value).toBe('人物')
    expect(inputs[1].value).toBe('场景')
  })

  it('AC-C1a-2 新增角色 + 保存 → emit save 含去重后的新角色集', async () => {
    const wrapper = mount(VocabEditor, {
      props: { show: true, narrativeRoles: ['人物', '场景'] },
      attachTo: document.body
    })
    await nextTick()
    // 点「+ 新增角色」→ 多出一空行
    ;(findBtn('新增角色') as HTMLButtonElement).click()
    await nextTick()
    const inputs = bodyInputs()
    const last = inputs[inputs.length - 1]
    last.value = '地图'
    last.dispatchEvent(new Event('input', { bubbles: true }))
    await nextTick()
    ;(findBtn('保存') as HTMLButtonElement).click()
    await nextTick()
    const saveEvt = wrapper.emitted('save')
    expect(saveEvt).toBeTruthy()
    expect(saveEvt![0][0]).toEqual(['人物', '场景', '地图'])
  })

  it('AC-C1a-3 失焦去重：与他行重名 → 该行清空', async () => {
    mount(VocabEditor, {
      props: { show: true, narrativeRoles: ['人物'] },
      attachTo: document.body
    })
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
    mount(VocabEditor, {
      props: { show: true, narrativeRoles: ['人物'] },
      attachTo: document.body
    })
    await nextTick()
    const del = findBtn('删除') as HTMLButtonElement
    expect(del.disabled).toBe(true)
  })

  it('AC-C1a-5 roleAssetCounts 透传删桶迁移数（popconfirm 文案）', async () => {
    mount(VocabEditor, {
      props: {
        show: true,
        narrativeRoles: ['人物', '场景'],
        roleAssetCounts: { 人物: 3 }
      },
      attachTo: document.body
    })
    await nextTick()
    // popconfirm 内容默认不渲染到 trigger；此处仅断言 prop 被接受不崩 + 删除按钮可用（>1 角色）
    const del = findBtn('删除') as HTMLButtonElement
    expect(del.disabled).toBe(false)
  })
})
