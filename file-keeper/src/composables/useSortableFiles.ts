import Sortable from 'sortablejs'
import { watch, onUnmounted, type Ref } from 'vue'
import { useFileStore } from '@/stores/fileStore'

export function useSortableFiles(containerRef: Ref<HTMLElement | null>) {
  const fileStore = useFileStore()
  let sortableInstance: Sortable | null = null

  const initSortable = () => {
    // Destroy existing instance
    if (sortableInstance) {
      sortableInstance.destroy()
      sortableInstance = null
    }

    // Create new instance if container exists
    if (!containerRef.value) return

    sortableInstance = new Sortable(containerRef.value, {
      animation: 200,
      easing: 'cubic-bezier(0.25, 0.8, 0.25, 1)',
      draggable: '.group',
    filter: '.absolute',
      preventOnFilter: false,
      swapThreshold: 0.65,
      direction: 'horizontal',
      forceFallback: true,
      fallbackClass: 'sortable-fallback',
      fallbackOnBody: true,
      fallbackTolerance: 3,
      ghostClass: 'sortable-ghost',
    chosenClass: 'sortable-chosen',
      dragClass: 'sortable-drag',
      onEnd: (evt) => {
        if (evt.oldIndex === evt.newIndex) return

        // Read the new order from DOM
        const elements = containerRef.value!.querySelectorAll('.group[data-id]')
        const newOrder: string[] = []
        elements.forEach((el) => {
          const id = el.getAttribute('data-id')
          if (id) newOrder.push(id)
        })

        if (newOrder.length > 0) {
          fileStore.updateOrder(newOrder)
        }
      }
    })
  }

  // Watch container ref and reinitialize when it changes
  watch(containerRef, () => {
    initSortable()
  }, { immediate: true })

  onUnmounted(() => {
    if (sortableInstance) {
      sortableInstance.destroy()
      sortableInstance = null
    }
  })

  return { sortableInstance }
}
