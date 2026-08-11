<template>
  <CanvasNodeBase kind="video" kind-label="视频" :label="data.label" :status="data.status" :selected="selected" :asset-badge="assetBadge">
    <template #icon><VideocamOutline /></template>
    <video
      v-if="data.previewUrl"
      class="video-node__clip"
      :src="data.previewUrl"
      controls
      muted
    />
    <div v-else class="video-node__prompt">{{ data.prompt || '视频节点（面板配置 prompt/比例/时长）' }}</div>
    <div v-if="data.ratio || data.duration" class="video-node__meta">
      <span v-if="data.ratio">{{ data.ratio }}</span>
      <span v-if="data.duration">{{ data.duration }}s</span>
      <span v-if="data.resolution">{{ data.resolution }}</span>
      <!-- 7x-4：画布上一眼区分是否有参考视频（与计费/审查口径一致） -->
      <span v-if="data.hasReference === true" class="video-node__ref-badge video-node__ref-badge--has">参考</span>
      <span v-else-if="data.hasReference === false" class="video-node__ref-badge">无参考</span>
    </div>
  </CanvasNodeBase>
</template>

<script setup lang="ts">
import { VideocamOutline } from '@vicons/ionicons5'
import CanvasNodeBase from './CanvasNodeBase.vue'
// vue-flow 把节点一等属性（label/position/dimensions 等）塞进本组件 $attrs。
// 默认 inheritAttrs:true 会透传到根子组件 CanvasNodeBase 的 vnode props，
// 其中 vue-flow 的 `label:undefined` 会覆盖显式 `:label="data.label"` → 节点头显「未命名」。
// 关闭透传：仅显式绑定进 CNB，vue-flow 垃圾 attr 不污染（定位由外层 .vue-flow__node 包裹层负责）。
defineOptions({ inheritAttrs: false })
import { useNodeAssetBadge } from './useNodeAssetBadge'
import type { CanvasNodeStatus } from '@/types/canvas'

const props = defineProps<{
  data: {
    label?: string
    prompt?: string
    ratio?: string
    duration?: number
    resolution?: string
    /** 会话级视频 objectURL（不入快照，加载时按 taskId 重新 fetch blob）。 */
    previewUrl?: string
    status?: CanvasNodeStatus
  } & Record<string, unknown>
  selected?: boolean
}>()

const assetBadge = useNodeAssetBadge(props.data)
</script>

<style lang="scss" scoped>
.video-node__prompt {
  color: var(--color-text-secondary);
  font-size: var(--font-size-xs);
  line-height: 1.5;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.video-node__clip {
  width: 100%;
  border-radius: var(--radius-sm);
  display: block;
  background: #000;
}
.video-node__meta {
  display: flex;
  gap: var(--spacing-2);
  margin-top: var(--spacing-1);
  font-size: 10px;
  color: var(--color-text-tertiary);
}
/* 7x-4：参考视频角标 */
.video-node__ref-badge {
  padding: 0 4px;
  border-radius: var(--radius-sm);
  background: var(--color-fill-secondary);
}
.video-node__ref-badge--has {
  background: var(--color-primary);
  color: #fff;
}
</style>
