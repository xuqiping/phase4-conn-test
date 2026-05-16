import { ref, onMounted, onUnmounted, type Ref } from 'vue'
import { getFileIcon } from '../api/icons'
import type { FileItem } from '../types/file'

// 图标提取队列
const iconQueue: Array<{ file: FileItem; callback: (icon: string) => void }> = []
let isProcessing = false
const MAX_CONCURRENT = 5
let activeCount = 0

async function processQueue() {
  if (isProcessing || iconQueue.length === 0) return

  isProcessing = true

  while (iconQueue.length > 0 && activeCount < MAX_CONCURRENT) {
    const task = iconQueue.shift()
    if (!task) break

    activeCount++

    getFileIcon(task.file.path)
      .then(icon => {
        task.callback(icon)
      })
   .catch(() => {
        // 提取失败，使用扩展名图标
        task.callback('')
      })
      .finally(() => {
        activeCount--
        processQueue()
      })
  }

  isProcessing = false
}

export function useIconLazyLoad(
  elementRef: Ref<HTMLElement | null>,
  file: Ref<FileItem>,
  onIconLoaded: (icon: string) => void
) {
  const observer = ref<IntersectionObserver | null>(null)

  onMounted(() => {
    if (!elementRef.value) return

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

    observer.value.observe(elementRef.value)
  })

  onUnmounted(() => {
    if (observer.value && elementRef.value) {
      observer.value.unobserve(elementRef.value)
    }
  })
}
