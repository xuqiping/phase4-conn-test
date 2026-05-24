import { ref, watch, onUnmounted, type Ref } from 'vue'
import { getFileIcon } from '../api/icons'
import type { FileItem } from '../types/file'
import { useSettingsStore } from '../stores/settingsStore'
import { deriveIconFromExt } from '../utils/file'

// 图标提取队列
const iconQueue: Array<{ file: FileItem; callback: (icon: string) => void }> = []
let isProcessing = false
const MAX_CONCURRENT = 5
let activeCount = 0

async function processQueue() {
  if (isProcessing || iconQueue.length === 0) return

  isProcessing = true
  const settingsStore = useSettingsStore()

  while (iconQueue.length > 0 && activeCount < MAX_CONCURRENT) {
    const task = iconQueue.shift()
    if (!task) break

    activeCount++

    const useRealIcon = settingsStore.settings.iconMode === 'real'

    if (!useRealIcon) {
      // 通用图标模式：直接使用扩展名推导
      const genericIcon = task.file.type === 'folder' ? 'folder' : deriveIconFromExt(task.file.name)
      task.callback(genericIcon)
      activeCount--
      // 继续处理队列中的下一个任务
      setTimeout(() => processQueue(), 0)
    } else {
      // 真实图标模式：调用后端提取
      getFileIcon(task.file.path, useRealIcon)
        .then(icon => {
          task.callback(icon || '')
        })
        .catch(() => {
          // 提取失败，使用空字符串
          task.callback('')
        })
        .finally(() => {
          activeCount--
          processQueue()
        })
    }
  }

  isProcessing = false
}

export function useIconLazyLoad(
  elementRef: Ref<HTMLElement | null>,
  file: Ref<FileItem>,
  onIconLoaded: (icon: string) => void
) {
  const observer = ref<IntersectionObserver | null>(null)

  // 使用 watch 代替 onMounted，这样可以在任何时候调用
  const stopWatch = watch(
    elementRef,
    (el) => {
      if (!el) return

      // 清理旧的 observer
      if (observer.value) {
        observer.value.disconnect()
      }

      observer.value = new IntersectionObserver(
        (entries) => {
          entries.forEach(entry => {
            if (entry.isIntersecting && !file.value.icon) {
              // 添加到队列
              iconQueue.push({
         file: file.value,
                callback: onIconLoaded
              })
              processQueue()

            // 停止观察
              observer.value?.unobserve(entry.target)
            }
          })
        },
        { threshold: 0.1 }
      )

      observer.value.observe(el)
    },
    { immediate: true }
  )

  onUnmounted(() => {
    stopWatch()
    if (observer.value) {
      observer.value.disconnect()
      observer.value = null
    }
  })
}
