import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { mount } from '@vue/test-utils'
import EditFileDialog from '../EditFileDialog.vue'
import type { FileItem } from '../../types/file'

const file: FileItem = {
  id: 'file-1',
  name: '季度计划',
  path: 'C:/docs/plan.pptx',
  type: 'file',
  tags: [],
  groupId: 'all',
  createdAt: 1,
  openCount: 0
}

describe('EditFileDialog favorite shortcut', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('captures a shortcut and waits for the parent to close after successful save', async () => {
    const wrapper = mount(EditFileDialog, {
      props: { visible: true, file, saving: false, shortcutError: '', pathInvalid: false }
    })

    const input = wrapper.get('[data-test="favorite-shortcut"]')
    await input.trigger('keydown', { ctrlKey: true, altKey: true, key: 'p' })
    await wrapper.get('[data-test="save-file"]').trigger('click')

    expect(wrapper.emitted('saved')?.[0]?.[0]).toMatchObject({
      shortcut: 'CommandOrControl+Alt+P'
    })
    expect(wrapper.emitted('close')).toBeUndefined()
  })

  it('shows a registration or conflict error without discarding the form', () => {
    const wrapper = mount(EditFileDialog, {
      props: { visible: true, file, saving: false, shortcutError: '该快捷键已被主窗口占用', pathInvalid: false }
    })

    expect(wrapper.get('[role="alert"]').text()).toContain('该快捷键已被主窗口占用')
  })

  it('offers relocation for an invalid favorite without changing other metadata', async () => {
    const wrapper = mount(EditFileDialog, {
      props: { visible: true, file, saving: false, shortcutError: '', pathInvalid: true }
    })

    expect(wrapper.get('[data-test="invalid-path-alert"]').text()).toContain('路径失效')
    await wrapper.get('[data-test="relocate-file"]').trigger('click')

    expect(wrapper.emitted('relocate')?.[0]?.[0]).toEqual(file)
  })
})
