import { beforeEach, describe, expect, it } from 'vitest'
import { mount, shallowMount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ProcessList from '../ProcessList.vue'
import ProcessRow from '../ProcessRow.vue'
import { useProcessStore } from '../../stores/processStore'
import { useProcessSettingsStore } from '../../stores/processSettingsStore'
import type { ColumnConfig, ProcessInfo } from '../../types/process'

function createColumns(): ColumnConfig[] {
  return [
    { key: 'windowTitle', label: 'Window Title', width: '200px', visible: true, sortable: true },
    { key: 'name', label: 'Name', width: '200px', visible: true, sortable: true },
    { key: 'pid', label: 'PID', width: '80px', visible: true, sortable: true },
    { key: 'category', label: 'Category', width: '120px', visible: true, sortable: true },
    { key: 'memory', label: 'Memory', width: '100px', visible: false, sortable: true },
    { key: 'cpu', label: 'CPU', width: '80px', visible: false, sortable: true }
  ]
}

function createProcess(): ProcessInfo {
  return {
    pid: 4242,
    name: 'File Keeper',
    window_title: 'Column order test',
    category: 'Other',
    memory_mb: 128,
    cpu_usage: 3.5,
    window_handle: 4242
  }
}

function getCellOrder(classLists: string[][]): string[] {
  return classLists.map(classes => classes.find(className => className.startsWith('cell-')) ?? '')
}

describe('process list column render order', () => {
  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
  })

  it('renders header cells in settings order', () => {
    const processStore = useProcessStore()
    const settingsStore = useProcessSettingsStore()

    processStore.processes = [createProcess()]
    settingsStore.updateColumns(createColumns())

    const wrapper = shallowMount(ProcessList, {
      global: {
        stubs: {
          ProcessRow: true
        }
      }
    })

    const order = getCellOrder(wrapper.findAll('.header-content > .header-cell').map(cell => cell.classes()))

    expect(order).toEqual([
      'cell-window-title',
      'cell-name',
      'cell-pid',
      'cell-category'
    ])
  })

  it('renders row cells in settings order', () => {
    const settingsStore = useProcessSettingsStore()
    settingsStore.updateColumns(createColumns())

    const wrapper = mount(ProcessRow, {
      props: {
        process: createProcess(),
        selected: false
      }
    })

    const order = getCellOrder(wrapper.findAll('.row-content > .row-cell').map(cell => cell.classes()))

    expect(order).toEqual([
      'cell-window-title',
      'cell-name',
      'cell-pid',
      'cell-category'
    ])
  })
})
