import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ProcessList from '../ProcessList.vue'
import { useProcessStore } from '../../stores/processStore'
import type { ProcessInfo } from '../../types/process'

function createProcess(): ProcessInfo {
  return {
    pid: 4242,
    name: 'File Keeper',
    window_title: 'Access denied test',
    category: 'Other',
    memory_mb: 128,
    cpu_usage: 1.5,
    window_handle: 67202
  }
}

describe('process close access denied feedback', () => {
  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
  })

  it('shows an administrator hint when Windows denies closing the process', async () => {
    const processStore = useProcessStore()
    const toast = {
      success: vi.fn(),
      error: vi.fn()
    }

    processStore.processes = [createProcess()]
    processStore.closeProcess = vi.fn(async () => {
      processStore.error = 'Failed to close process: 拒绝访问。 (0x80070005)'
      return false
    })

    const wrapper = mount(ProcessList, {
      global: {
        provide: {
          requestConfirmation: undefined,
          toast
        }
      }
    })

    await wrapper.find('.btn-close').trigger('click')
    await flushPromises()

    expect(toast.error).toHaveBeenCalledWith(
      '无法关闭进程 File Keeper (PID: 4242)：目标应用可能以更高权限运行。请尝试以管理员身份启动 File Keeper 后重试。'
    )
  })
})
