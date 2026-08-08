<template>
  <CanvasNodeBase kind="storyboard" kind-label="分镜" :label="data.label" :status="data.status" :selected="selected" :asset-badge="assetBadge">
    <template #icon><FilmOutline /></template>
    <div v-if="data.description" class="storyboard-node__desc">{{ data.description }}</div>
    <div v-else class="storyboard-node__empty">（空分镜，面板填写画面描述）</div>
    <div v-if="data.index" class="storyboard-node__meta">分镜 {{ data.index }}</div>
  </CanvasNodeBase>
</template>

<script setup lang="ts">
import { FilmOutline } from '@vicons/ionicons5'
import CanvasNodeBase from './CanvasNodeBase.vue'
import { useNodeAssetBadge } from './useNodeAssetBadge'
import type { CanvasNodeStatus } from '@/types/canvas'

// 分镜节点（脚本拆分镜后由前端扇出生成，每条 scene 一节点）。
// 存单条分镜画面描述 description + 序号 index；可被下游图/视频节点 @引用（解析为 description）。
// 见 VideoNode：关 vue-flow $attrs 透传，防 `label:undefined` 覆盖显式 :label。
defineOptions({ inheritAttrs: false })

const props = defineProps<{
  data: {
    label?: string
    /** 分镜画面描述（拆分产出 / 面板可编辑）。 */
    description?: string
    /** 分镜序号（自脚本拆分顺序，1-based）。 */
    index?: number
    /** 源脚本节点 id（自动生成标记，重拆替换用）。 */
    sourceScriptId?: string
    status?: CanvasNodeStatus
  } & Record<string, unknown>
  selected?: boolean
}>()

const assetBadge = useNodeAssetBadge(props.data)
</script>

<style lang="scss" scoped>
.storyboard-node__desc {
  color: var(--color-text-secondary);
  font-size: var(--font-size-xs);
  line-height: 1.5;
  display: -webkit-box;
  -webkit-line-clamp: 4;
  line-clamp: 4;
  -webkit-box-orient: vertical;
  overflow: hidden;
  white-space: pre-wrap;
  min-height: 18px;
}
.storyboard-node__empty {
  color: var(--color-text-tertiary);
  font-size: var(--font-size-xs);
  font-style: italic;
}
.storyboard-node__meta {
  margin-top: var(--spacing-1);
  font-size: 10px;
  color: var(--color-text-tertiary);
}
</style>
