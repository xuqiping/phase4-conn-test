<template>
  <CanvasNodeBase kind="script" kind-label="脚本" :label="data.label" :status="data.status" :selected="selected" :asset-badge="assetBadge">
    <template #icon><CodeSlashOutline /></template>
    <div class="script-node__syn">{{ data.synopsis || '剧本节点（面板输入剧本→拆分镜）' }}</div>
    <div v-if="sceneCount" class="script-node__meta">{{ sceneCount }} 分镜</div>
  </CanvasNodeBase>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { CodeSlashOutline } from '@vicons/ionicons5'
import CanvasNodeBase from './CanvasNodeBase.vue'
// 见 VideoNode：关 vue-flow $attrs 透传，防 `label:undefined` 覆盖显式 :label。
defineOptions({ inheritAttrs: false })
import { useNodeAssetBadge } from './useNodeAssetBadge'
import type { CanvasNodeStatus } from '@/types/canvas'

const props = defineProps<{
  data: { label?: string; synopsis?: string; scenes?: unknown[]; status?: CanvasNodeStatus } & Record<string, unknown>
  selected?: boolean
}>()

const sceneCount = computed(() => (Array.isArray(props.data.scenes) ? props.data.scenes.length : 0))
const assetBadge = useNodeAssetBadge(props.data)
</script>

<style lang="scss" scoped>
.script-node__syn {
  color: var(--color-text-secondary);
  font-size: var(--font-size-xs);
  line-height: 1.5;
  display: -webkit-box;
  -webkit-line-clamp: 3;
  line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.script-node__meta {
  margin-top: var(--spacing-1);
  font-size: 10px;
  color: var(--color-text-tertiary);
}
</style>
