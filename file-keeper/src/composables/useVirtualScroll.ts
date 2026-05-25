import { ref, computed, type Ref } from 'vue'
import { useElementSize } from '@vueuse/core'

export interface VirtualScrollOptions {
  itemHeight: number      // 单个项目高度（列表视图）
  itemsPerRow?: number    // 每行项目数（网格视图，默认 1）
  overscan?: number       // 缓冲区大小（默认 5）
}

export function useVirtualScroll<T>(
  containerRef: Ref<HTMLElement | null>,
  items: Ref<T[]>,
  options: VirtualScrollOptions
) {
  const { itemHeight, itemsPerRow = 1, overscan = 5 } = options

  const scrollTop = ref(0)
  const { height: containerHeight } = useElementSize(containerRef)

  // 计算总行数
  const totalRows = computed(() => Math.ceil(items.value.length / itemsPerRow))

  // 计算可见行范围
  const visibleRange = computed(() => {
    const start = Math.floor(scrollTop.value / itemHeight)
    // 确保至少渲染一些项目，即使容器高度为 0
    const viewportHeight = containerHeight.value || 600 // 默认 600px
    const end = Math.ceil((scrollTop.value + viewportHeight) / itemHeight)

    return {
      start: Math.max(0, start - overscan),
      end: Math.min(totalRows.value, end + overscan)
    }
  })

  // 计算可见项目
  const visibleItems = computed(() => {
    const { start, end } = visibleRange.value
    const startIndex = start * itemsPerRow
    const endIndex = end * itemsPerRow

    return items.value.slice(startIndex, endIndex).map((item, index) => ({
      item,
      index: startIndex + index,
      offsetTop: Math.floor((startIndex + index) / itemsPerRow) * itemHeight
    }))
  })

  // 总高度
  const totalHeight = computed(() => totalRows.value * itemHeight)

  // 监听滚动
  const handleScroll = (event: Event) => {
    const target = event.target as HTMLElement
    scrollTop.value = target.scrollTop
  }

  return {
    visibleItems,
    totalHeight,
    handleScroll
  }
}
