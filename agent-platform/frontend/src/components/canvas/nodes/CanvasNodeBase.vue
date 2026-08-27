<template>
  <div
    class="canvas-node"
    :class="[
      `canvas-node--${kind}`,
      {
        'canvas-node--selected': selected,
        [`canvas-node--${status}`]: !!status,
        // 2x 四轮 S2：用户拉过高度 → 文本类解除 line-clamp 改随高度滚动（见 TextNode 样式）
        'canvas-node--resized': isResized
      }
    ]"
  >
    <!-- 2x 四轮 S2：选中时显四角拖柄。修复IV B3（C-3）：补四边 Line 单轴拖边，并把角柄热区
         外扩（8px 视觉 + ::before 隐形扩 ~14px）。边线两端各缩 16px 且 z-index 压低——
         左右边缘中点的连线 Handle（S3 左入右出）命中优先，拖连线不被拖边抢走。 -->
    <template v-if="selected">
      <NodeResizeControl
        v-for="pos in RESIZE_EDGES"
        :key="`line-${pos}`"
        :variant="ResizeControlVariant.Line"
        :position="pos"
        :min-width="160"
        :min-height="64"
        @resize-end="onResizeEnd"
      />
      <NodeResizeControl
        v-for="pos in RESIZE_CORNERS"
        :key="pos"
        :variant="ResizeControlVariant.Handle"
        :position="pos"
        :min-width="160"
        :min-height="64"
        @resize-end="onResizeEnd"
      />
    </template>
    <!-- 2x 四轮 S3：连线改左右流向（target=左入 source=右出），横向贝塞尔贴合画布从左到右的心智模型 -->
    <Handle type="target" :position="Position.Left" />
    <div class="canvas-node__accent" />
    <div class="canvas-node__header">
      <div class="canvas-node__icon"><n-icon size="14"><slot name="icon" /></n-icon></div>
      <span class="canvas-node__kind">{{ kindLabel }}</span>
      <!-- C6：节点名显在头部（类型标签旁）；空=「未命名」灰字。改名走 PropertyPanel 名称框 → 头部实时更新 -->
      <span v-if="label" class="canvas-node__label" :title="label">{{ label }}</span>
      <span v-else class="canvas-node__label canvas-node__label--empty">未命名</span>
      <span v-if="assetBadge" class="canvas-node__asset" :data-has-update="assetBadge.hasUpdate">
        {{ assetBadge.name }} v{{ assetBadge.version }}<template v-if="assetBadge.hasUpdate"> · 有新版</template>
      </span>
      <span v-if="status" class="canvas-node__status" :data-status="status">{{ statusLabel }}</span>
    </div>
    <div class="canvas-node__body">
      <slot />
    </div>
    <Handle type="source" :position="Position.Right" />
  </div>
</template>

<script setup lang="ts">
import { computed, inject } from 'vue'
import { Handle, Position, useNode } from '@vue-flow/core'
import { NodeResizeControl, ResizeControlVariant } from '@vue-flow/node-resizer'
import '@vue-flow/node-resizer/dist/style.css'
import { NIcon } from 'naive-ui'
import type { CanvasNodeStatus, AssetBadge } from '@/types/canvas'

const props = defineProps<{
  kind: 'text' | 'image' | 'video' | 'audio' | 'script' | 'storyboard' | 'director'
  kindLabel: string
  /** C6：节点名（data.label）；空显「未命名」灰字。 */
  label?: string
  status?: CanvasNodeStatus
  selected?: boolean
  /** S12：资产绑定徽标（来自资产·name vN / 有新版）。 */
  assetBadge?: AssetBadge
}>()

const STATUS_LABEL: Record<CanvasNodeStatus, string> = {
  idle: '待生成',
  running: '生成中',
  success: '完成',
  failed: '失败',
  skipped: '已跳过'
}

const statusLabel = computed(() => (props.status ? STATUS_LABEL[props.status] : ''))

// 2x 四轮 S2：注入所属节点（vue-flow 对节点子树 provide；单元测试裸挂时无注入，链式守卫）
const nodeCtx = useNode()
const nodeData = computed(() => nodeCtx?.node?.data as { width?: number; height?: number } | undefined)
const isResized = computed(() => typeof nodeData.value?.height === 'number')
const RESIZE_CORNERS = ['top-left', 'top-right', 'bottom-left', 'bottom-right'] as const
// 修复IV B3（C-3）：四边单轴 resize（top/bottom=拉高，left/right=拉宽）
const RESIZE_EDGES = ['top', 'bottom', 'left', 'right'] as const
// 修复III C1 复验补缺（2x-1）：resize 后通知画布层落库（resize 不触发 node-drag-stop；
// 裸挂单测无注入，缺省 no-op 守卫）
const notifyResized = inject<(() => void) | null>('canvasNodeResized', null)

/**
 * 拖角柄松手 → 尺寸落 node.data.width/height（快照持久化真源）。
 * wrapper 的 style 由 node-resizer 实时改写（保存时剥离），data 在 resize-end 与
 * 结构变更通知（structure-changed → 防抖落库）同拍写入，不漂移。
 */
function onResizeEnd({ params }: { params: { width: number; height: number } }) {
  if (!nodeData.value) return
  nodeData.value.width = Math.round(params.width)
  nodeData.value.height = Math.round(params.height)
  notifyResized?.()
}
</script>

<style lang="scss" scoped>
// 2x 四轮 S2：宽高跟随 vue-flow wrapper（resizer 改 wrapper style，本根 100% 跟随；
// 默认宽 200 由 addNode/loadSnapshot 写进 wrapper style 兜底，此处只留最小尺寸守卫）
.canvas-node {
  position: relative;
  width: 100%;
  height: 100%;
  min-width: 160px;
  min-height: 64px;
  display: flex;
  flex-direction: column;
  background: linear-gradient(145deg, rgba(8, 18, 34, 0.98), rgba(17, 28, 47, 0.96));
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  overflow: hidden;
  cursor: pointer;
  box-shadow: 0 12px 26px rgba(0, 0, 0, 0.28), inset 0 1px 0 rgba(255, 255, 255, 0.06);
  transition: border-color var(--duration-fast) var(--ease-in-out),
    box-shadow var(--duration-fast) var(--ease-in-out);

  &:hover,
  &--selected {
    border-color: var(--color-primary);
    box-shadow: 0 14px 30px rgba(0, 0, 0, 0.34), 0 0 0 1px rgba(var(--color-primary-rgb), 0.3),
      0 0 22px rgba(var(--color-primary-rgb), 0.2);
  }
}

.canvas-node__accent {
  height: 3px;
  background: linear-gradient(90deg, var(--color-primary), var(--color-gradient-end));
}

.canvas-node--success { border-color: rgba(34, 197, 94, 0.5); }
.canvas-node--failed { border-color: rgba(239, 68, 68, 0.55); }
.canvas-node--running { border-color: rgba(56, 189, 248, 0.6); }
/* 2x 四轮 S4：依赖调度跳过态——灰化区分「任务失败」，原因见节点 errorMsg / 属性面板 */
.canvas-node--skipped {
  border-color: rgba(148, 163, 184, 0.4);
  opacity: 0.75;
}

.canvas-node__header {
  display: flex;
  flex-wrap: wrap; // 2x 四轮 S2：节点拉宽/徽标多时头部换行不溢出
  align-items: center;
  gap: var(--spacing-2);
  padding: 8px 10px 4px;
}

.canvas-node__icon {
  width: 22px;
  height: 22px;
  flex: 0 0 22px;
  border-radius: var(--radius-sm);
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(145deg, var(--color-primary), var(--color-gradient-end));
  color: #08111f;
}

.canvas-node__kind {
  font-size: 10px;
  text-transform: uppercase;
  letter-spacing: 0.5px;
  color: var(--color-text-tertiary);
}

// C6 节点名（头部主显位，占中间空间把资产/状态徽标推到右）
.canvas-node__label {
  flex: 1;
  min-width: 0;
  font-size: 12px;
  font-weight: 500;
  color: var(--color-text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;

  &--empty {
    color: var(--color-text-tertiary);
    font-weight: 400;
    font-style: italic;
  }
}

.canvas-node__asset {
  font-size: 10px;
  padding: 2px 6px;
  border-radius: var(--radius-sm);
  background: rgba(var(--color-primary-rgb), 0.14);
  color: var(--color-primary);
  max-width: 110px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;

  &[data-has-update='true'] {
    background: rgba(250, 204, 21, 0.16);
    color: #facc15;
  }
}

.canvas-node__status {
  font-size: 10px;
  padding: 2px 6px;
  border-radius: var(--radius-sm);
  background: var(--color-primary-light);
  color: var(--color-text-secondary);

  &[data-status='success'] { color: #4ade80; }
  &[data-status='failed'] { color: #f87171; }
  &[data-status='running'] { color: #38bdf8; }
  &[data-status='skipped'] { color: #94a3b8; }
}

.canvas-node__body {
  // 2x 四轮 S2：flex 列布局下占满剩余高；用户拉高后内容超出走滚动（文本不再截断）
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 4px 10px 10px;
  font-size: var(--font-size-sm);
  color: var(--color-text-primary);
  word-break: break-word;
}

/* 修复IV B3（C-3）：resize 命中可点性——角柄 8px 视觉 + ::before 隐形外扩（~22px 命中）；
   四边 Line 单轴拖边 = 1px 视线 + ::before 内侧 10px 热带。边线两端各缩 16px 且
   z-index 低于连线 Handle（连线命中优先，拖边不抢左右中点的连线手势——CanvasBoard
   :deep(.vue-flow__handle) 处配 z-index:4 成对出现，改一处须同步另一处）。 */
:deep(.vue-flow__resize-control.handle) {
  width: 8px;
  height: 8px;
  border: 1.5px solid #fff;
  border-radius: 2px;
  background: var(--color-primary);
  z-index: 3;
}
:deep(.vue-flow__resize-control.handle::before) {
  content: '';
  position: absolute;
  inset: -7px;
}
:deep(.vue-flow__resize-control.line) {
  border-color: rgba(var(--color-primary-rgb), 0.9);
  z-index: 2;
}
// 覆盖库 width/height:100%——两端缩进，避开角柄热区与左右中点连线 Handle
:deep(.vue-flow__resize-control.line.top),
:deep(.vue-flow__resize-control.line.bottom) {
  left: 16px;
  right: 16px;
  width: auto;
}
:deep(.vue-flow__resize-control.line.left),
:deep(.vue-flow__resize-control.line.right) {
  top: 16px;
  bottom: 16px;
  height: auto;
}
:deep(.vue-flow__resize-control.line.top::before),
:deep(.vue-flow__resize-control.line.bottom::before),
:deep(.vue-flow__resize-control.line.left::before),
:deep(.vue-flow__resize-control.line.right::before) {
  content: '';
  position: absolute;
}
:deep(.vue-flow__resize-control.line.top::before) {
  left: 0;
  right: 0;
  top: 0;
  height: 10px;
}
:deep(.vue-flow__resize-control.line.bottom::before) {
  left: 0;
  right: 0;
  bottom: 0;
  height: 10px;
}
:deep(.vue-flow__resize-control.line.left::before) {
  top: 0;
  bottom: 0;
  left: 0;
  width: 10px;
}
:deep(.vue-flow__resize-control.line.right::before) {
  top: 0;
  bottom: 0;
  right: 0;
  width: 10px;
}
</style>
