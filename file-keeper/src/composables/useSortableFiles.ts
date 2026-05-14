import { useSortable } from '@vueuse/integrations/useSortable'
import type { Ref } from 'vue'
import { useFileStore } from '@/stores/fileStore'

export function useSortableFiles(containerRef: Ref<HTMLElement | null>) {
  const fileStore = useFileStore()

  const sortable = useSortable(containerRef, fileStore.filteredFiles, {
    animation: 150,
    onUpdate: (evt) => {
      if (evt.oldIndex !== undefined && evt.newIndex !== undefined) {
     // Get current filtered file IDs in order
        const fileIds = fileStore.filteredFiles.map(f => f.id)
        const newOrder = [...fileIds]
        const [moved] = newOrder.splice(evt.oldIndex, 1)
        newOrder.splice(evt.newIndex, 0, moved)
        fileStore.updateOrder(newOrder)
      }
    }
  })
  return { sortable }
}
