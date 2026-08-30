// 修复IV B1（C-1 两段式，决策 6）：图片/视频节点媒体区（缩略图/视频本体）两段式点击——
// 未选中第一击放行冒泡 → vue-flow node-click 只选中；已选中后再点媒体本体才拦截并经
// inject('canvasMediaPreview') 弹 Lightbox（CanvasBoard provide，同 canvasNodeResized 范式）。
import { inject, ref } from 'vue'
import { useNode } from '@vue-flow/core'

export function useMediaPreviewClick(isSelected: () => boolean) {
  // 裸挂单测无 vue-flow 节点上下文/无 provide → 两处链式守卫（同 CanvasNodeBase.notifyResized）
  const nodeCtx = useNode()
  const requestPreview = inject<((nodeId: string) => void) | null>('canvasMediaPreview', null)
  const downPt = ref<{ x: number; y: number } | null>(null)

  function onPointerDown(e: PointerEvent) {
    downPt.value = { x: e.clientX, y: e.clientY }
  }

  function onMediaClick(e: MouseEvent) {
    // 拖拽尾点击忽略：pointerdown→click 位移 >5px 视为挪节点/拉框，不是点击
    if (downPt.value && Math.hypot(e.clientX - downPt.value.x, e.clientY - downPt.value.y) > 5) return
    if (!isSelected()) return // 第一段：不拦截 → 冒泡成 node-click 仅选中
    e.stopPropagation() // 第二段：拦截媒体区点击，弹预览
    const id = nodeCtx?.node?.id
    if (id) requestPreview?.(id)
  }

  return { onPointerDown, onMediaClick }
}
