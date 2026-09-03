import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import DocumentOptionsModal from './DocumentOptionsModal.vue'

// ModelSelector 依赖 store/模型列表，测试用哑组件替换
vi.mock('@/components/chat/ModelSelector.vue', () => ({
  default: { name: 'ModelSelector', props: ['modelValue'], template: '<div class="model-selector-stub" />' }
}))

/** n-modal teleport 到 body——统一从 document.body 断言（版本弹窗测试先例） */
function bodyText(): string {
  return document.body.textContent || ''
}

function bodyRadio(label: string): HTMLElement | undefined {
  return Array.from(document.body.querySelectorAll<HTMLElement>('.n-radio'))
    .find(el => (el.textContent || '').includes(label))
}

function bodyButton(label: string): HTMLButtonElement | undefined {
  return Array.from(document.body.querySelectorAll('button'))
    .find(b => (b.textContent || '').trim() === label)
}

function bodyTextarea(): HTMLTextAreaElement | undefined {
  return document.body.querySelector('textarea') || undefined
}

async function open(props: Record<string, unknown> = {}) {
  document.body.innerHTML = ''
  const { show, ...rest } = { show: true, fileName: '架构图.png', sheetNames: [], loading: false, ...props }
  const wrapper = mount(DocumentOptionsModal, {
    props: { show: false, ...rest },
    attachTo: document.body
  })
  // 真实用法：先挂载隐藏再开——watch(show) 在 open 时重算推断/重置表单（直挂 show=true 不触发）
  await wrapper.setProps({ show: show as boolean })
  await Promise.resolve()
  return wrapper
}

/** 切索引方式（点 radio 触发 n-radio-group v-model） */
async function pickMode(label: string) {
  const radio = bodyRadio(label)
  radio?.querySelector('input')?.click()
  await Promise.resolve()
}

describe('DocumentOptionsModal · C2 上传三选（WP1 Step7）', () => {
  beforeEach(() => {
    document.body.innerHTML = ''
  })

  it('三选齐备：AUTO / MANUAL / 附件模式', async () => {
    await open()
    expect(bodyText()).toContain('AUTO 自动抽取')
    expect(bodyText()).toContain('MANUAL 手动给索引文本')
    expect(bodyText()).toContain('附件模式')
  })

  it('附件模式：描述+关键词表单出现，空描述禁存', async () => {
    await open({ fileName: '部署手册.txt' })
    await pickMode('附件模式')

    expect(bodyText()).toContain('附件描述')
    expect(bodyText()).toContain('关键词（可选，逗号分隔')
    const btn = bodyButton('确认上传')!
    expect(btn.disabled).toBe(true)

    const ta = bodyTextarea()!
    ta.value = '部署架构：网关+三微服务+PG主从'
    ta.dispatchEvent(new Event('input'))
    await Promise.resolve()
    expect(bodyButton('确认上传')!.disabled).toBe(false)
  })

  it('附件模式确认：payload 带 indexMode/manualIndexText/attachmentKeywords', async () => {
    const wrapper = await open({ fileName: '部署手册.txt' })
    await pickMode('附件模式')
    const ta = bodyTextarea()!
    ta.value = '部署架构描述'
    ta.dispatchEvent(new Event('input'))
    await Promise.resolve()
    bodyButton('确认上传')!.click()
    await Promise.resolve()

    const emitted = wrapper.emitted('confirm')
    expect(emitted).toBeTruthy()
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const payload = emitted![0][0] as any
    expect(payload.indexMode).toBe('ATTACHMENT')
    expect(payload.manualIndexText).toBe('部署架构描述')
    expect(payload.docType).toBe('TEXT')
  })

  it('MANUAL 模式不显关键词输入', async () => {
    await open({ fileName: '部署手册.txt' })
    await pickMode('MANUAL 手动给索引文本')
    expect(bodyText()).toContain('索引文本')
    expect(bodyText()).not.toContain('关键词（可选')
  })

  it('图片+附件模式：视觉模型必选（检索时实时识图）', async () => {
    await open({ fileName: '架构图.png' })
    await pickMode('附件模式')

    expect(bodyText()).toContain('视觉模型')
    expect(bodyText()).toContain('检索命中时实时识图注入内容')
    // 未选模型 → 禁存（描述填了也不行）
    const ta = bodyTextarea()!
    ta.value = '产品架构图'
    ta.dispatchEvent(new Event('input'))
    await Promise.resolve()
    expect(bodyButton('确认上传')!.disabled).toBe(true)
  })

  it('文本+附件模式：无视觉模型要求', async () => {
    await open({ fileName: '部署手册.txt' })
    await pickMode('附件模式')
    expect(bodyText()).not.toContain('视觉模型')
  })
})
