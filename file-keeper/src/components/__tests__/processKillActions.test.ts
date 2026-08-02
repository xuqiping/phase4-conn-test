import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ProcessList from '../ProcessList.vue'
import ProcessToolbar from '../ProcessToolbar.vue'
import { useProcessStore } from '../../stores/processStore'
import type { ProcessInfo } from '../../types/process'

function createProcess(overrides: Partial<ProcessInfo> = {}): ProcessInfo {
  return {
    pid: 4242,
    name: 'File Keeper',
    window_title: 'Main Window',
    category: 'Other',
    memory_mb: 128,
    cpu_usage: 1.5,
    window_handle: 1001,
    ...overrides
  }
}

describe('process kill actions', () => {
  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
  })

  it('kills one process from the context menu without asking for confirmation', async () => {
    const processStore = useProcessStore()
    const requestConfirmation = vi.fn()
    const toast = {
      success: vi.fn(),
      error: vi.fn(),
      warning: vi.fn()
    }

    processStore.processes = [createProcess()]
    processStore.killProcess = vi.fn(async () => true)

    const wrapper = mount(ProcessList, {
      global: {
        provide: {
          requestConfirmation,
          toast
        }
      }
    })

    await wrapper.findComponent({ name: 'ProcessRow' }).vm.$emit('context-menu', new MouseEvent('contextmenu', { clientX: 20, clientY: 20 }))
    const killButton = wrapper.findAll('.process-context-menu__item').find(button => button.text() === '结束进程')

    expect(killButton).toBeTruthy()
    await killButton!.trigger('click')
    await flushPromises()

    expect(requestConfirmation).not.toHaveBeenCalled()
    expect(processStore.killProcess).toHaveBeenCalledWith(4242)
    expect(toast.success).toHaveBeenCalledWith('已结束进程 File Keeper (PID: 4242)')
  })

  it('requires confirmation before killing selected processes from the toolbar', async () => {
    const processStore = useProcessStore()
    const requestKillConfirmation = vi.fn()
    const toast = {
      success: vi.fn(),
      error: vi.fn(),
      warning: vi.fn()
    }

    processStore.processes = [createProcess(), createProcess({ pid: 5252, window_handle: 2001, name: 'Explorer' })]
    processStore.selectedIds = new Set([1001, 2001])
    processStore.killSelected = vi.fn(async () => ({ success: 2, failed: 0 }))

    const wrapper = mount(ProcessToolbar, {
      global: {
        provide: {
          requestKillConfirmation,
          toast
        }
      }
    })

    const killButton = wrapper.findAll('button').find(button => button.text().includes('结束选中进程'))

    expect(killButton).toBeTruthy()
    await killButton!.trigger('click')

    expect(requestKillConfirmation).toHaveBeenCalledWith(processStore.selectedProcesses, expect.any(Function))
    expect(processStore.killSelected).not.toHaveBeenCalled()

    const onConfirm = requestKillConfirmation.mock.calls[0][1]
    await onConfirm()
    await flushPromises()

    expect(processStore.killSelected).toHaveBeenCalled()
    expect(toast.success).toHaveBeenCalledWith('成功结束 2 个进程')
  })
})
