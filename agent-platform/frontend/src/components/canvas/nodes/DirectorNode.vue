<template>
  <CanvasNodeBase kind="director" kind-label="导演台" :label="data.label" :status="data.status" :selected="selected" :asset-badge="assetBadge">
    <template #icon><CubeOutline /></template>
    <template v-if="hasScene">
      <div class="director-node__summary">{{ scene.elements.length }} 元素 · {{ scene.cameras.length }} 机位</div>
      <div v-if="data.coverPreviewUrl" class="director-node__cover">
        <img :src="data.coverPreviewUrl" alt="机位截图封面" />
      </div>
      <div v-else class="director-node__nocover">进入机位视角截图后，这里显示最新封面</div>
    </template>
    <div v-else class="director-node__empty">
      <n-icon :component="CubeOutline" size="22" />
      <span>空场景——打开导演台摆放 3D 构图</span>
    </div>
    <button type="button" class="director-node__open" title="打开 3D 构图编辑器（也可双击节点）" @click.stop="onOpen">
      打开导演台
    </button>
  </CanvasNodeBase>
</template>

<script setup lang="ts">
import { computed, inject } from 'vue'
import { NIcon } from 'naive-ui'
import { CubeOutline } from '@vicons/ionicons5'
import CanvasNodeBase from './CanvasNodeBase.vue'
// 见 VideoNode：关 vue-flow $attrs 透传，防 `label:undefined` 覆盖显式 :label。
defineOptions({ inheritAttrs: false })
import { useNodeAssetBadge } from './useNodeAssetBadge'
import { DIRECTOR_BRIDGE_KEY } from '../directorBridge'
import { parseScene } from '@/director/sceneModel'
import type { CanvasNodeStatus } from '@/types/canvas'

const props = defineProps<{
  /** vue-flow 传给自定义节点的节点 id（按钮打开编辑器用）。 */
  id?: string
  data: {
    label?: string
    /** 导演台场景（sceneModel 结构；旧快照无字段 = 空场景语义）。 */
    directorScene?: unknown
    /** 最新机位截图 fileId（经既有文件预览链拉 objectURL）。 */
    coverFileId?: string
    /** 会话级封面预览（loadCanvas/截图回流时拉取注入，保存时剥离）。 */
    coverPreviewUrl?: string
    status?: CanvasNodeStatus
  } & Record<string, unknown>
  selected?: boolean
}>()

// 节点 emit 不冒泡 → inject 桥调 CanvasBoard 上抛；默认 no-op 兜底（单测裸挂/HMR 边界）
const bridge = inject(DIRECTOR_BRIDGE_KEY, { openEditor: (_nodeId: string) => {} })

/** 场景摘要：parseScene 白名单解析（未知/缺失 → 空场景，与 modal 打开时同一真相源） */
const scene = computed(() => parseScene(props.data.directorScene))
const hasScene = computed(() => scene.value.elements.length > 0 || scene.value.cameras.length > 0)

function onOpen(): void {
  if (props.id) bridge.openEditor(props.id)
}

const assetBadge = useNodeAssetBadge(props.data)
</script>

<style lang="scss" scoped>
.director-node__summary {
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

.director-node__cover img {
  width: 100%;
  border-radius: var(--radius-sm);
  display: block;
  margin-top: var(--spacing-1);
}

.director-node__nocover {
  margin-top: var(--spacing-1);
  font-size: var(--font-size-xs);
  color: var(--color-text-tertiary);
}

.director-node__empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--spacing-1);
  padding: var(--spacing-3);
  color: var(--color-text-tertiary);
  font-size: var(--font-size-xs);
}

.director-node__open {
  margin: var(--spacing-2) 0 var(--spacing-1);
  padding: 4px 10px;
  font-size: var(--font-size-xs);
  color: var(--color-primary);
  background: rgba(var(--color-primary-rgb), 0.12);
  border: 1px solid rgba(var(--color-primary-rgb), 0.4);
  border-radius: var(--radius-sm);
  cursor: pointer;

  &:hover {
    background: rgba(var(--color-primary-rgb), 0.2);
  }
}
</style>
