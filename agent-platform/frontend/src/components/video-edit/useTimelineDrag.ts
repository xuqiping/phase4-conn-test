import { ref, type Ref } from 'vue'

/** 拖拽类型：move=平移整段；start=拖左边缘（改入点）；end=拖右边缘（改出点）。 */
export type DragKind = 'move' | 'start' | 'end'

/** 一次拖拽的快照状态（pointerdown 时建立，pointerup 清除）。 */
export interface DragState {
  trackId: string
  index: number // segments 或 texts 的下标
  kind: DragKind
  isText: boolean
  originX: number // pointerdown clientX
  origTargetStart: number
  origTargetEnd: number
  origTrimStart: number | null
  origTrimEnd: number | null
}

/**
 * 时间线拖拽 composable：封装 pointer 捕获 + 像素→秒换算（含 snap 吸附）。
 * Timeline.vue 在 pointerdown 调 begin，pointermove 调 deltaSec 应用到 segment，pointerup 调 finish。
 */
export function useTimelineDrag(pxPerSecond: Ref<number>, snap = 0.1) {
  const state = ref<DragState | null>(null)

  /** 开始拖拽：捕获指针 + 记录快照（originX 由调用方设为 e.clientX）。 */
  function begin(e: PointerEvent, s: Omit<DragState, 'originX'>) {
    e.preventDefault()
    e.stopPropagation()
    try {
      ;(e.currentTarget as Element).setPointerCapture(e.pointerId)
    } catch {
      /* 跨浏览器兜底 */
    }
    state.value = { ...s, originX: e.clientX }
  }

  /** 当前拖拽相对起点的位移（秒，已 snap 吸附）。未拖拽返回 0。 */
  function deltaSec(e: PointerEvent): number {
    if (!state.value) return 0
    const dx = e.clientX - state.value.originX
    return Math.round(dx / pxPerSecond.value / snap) * snap
  }

  function isActive(): boolean {
    return state.value !== null
  }

  function finish(): void {
    state.value = null
  }

  return { state, begin, deltaSec, isActive, finish }
}
