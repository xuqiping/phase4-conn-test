<!-- ============================================================
  属性面板 — 右侧300px，选中节点时显示属性编辑
  ============================================================ -->
<template>
  <div class="property-panel">
    <div class="property-panel__header">
      <span class="property-panel__title">属性面板</span>
    </div>

    <!-- 未选中状态 -->
    <div v-if="!selectedNode" class="property-panel__empty">
      <n-icon size="32" :component="InformationCircleOutline" color="var(--color-text-tertiary)" />
      <span>点击节点查看属性</span>
    </div>

    <!-- 选中节点属性 -->
    <div v-else class="property-panel__content">
      <!-- 节点类型标识 -->
      <div class="property-panel__type-badge">
        <div
          class="property-panel__type-icon"
          :class="`property-panel__type-icon--${selectedNode.type}`"
        >
          <n-icon size="16" color="#fff">
            <component :is="typeIcon" />
          </n-icon>
        </div>
        <span class="property-panel__type-name">{{ typeName }}</span>
      </div>

      <n-divider style="margin: var(--spacing-3) 0" />

      <!-- 基础属性 -->
      <div class="property-panel__section">
        <div class="property-panel__section-title">基础信息</div>
        <div class="property-panel__field">
          <label class="property-panel__label">节点ID</label>
          <span class="property-panel__value property-panel__value--mono">{{ selectedNode.id }}</span>
        </div>
        <div class="property-panel__field">
          <label class="property-panel__label">节点名称</label>
          <n-input
            :value="selectedNode.data.label"
            size="small"
            placeholder="输入节点名称"
            @update:value="(val: string) => updateNodeData('label', val)"
          />
        </div>
        <div v-if="selectedNode.type === 'skill'" class="property-panel__field">
          <label class="property-panel__label">描述</label>
          <n-input
            :value="selectedNode.data.description || ''"
            type="textarea"
            size="small"
            placeholder="输入节点描述"
            :rows="3"
            @update:value="(val: string) => updateNodeData('description', val)"
          />
        </div>
      </div>

      <!-- 技能关联信息（仅skill类型） -->
      <div v-if="selectedNode.type === 'skill'" class="property-panel__section">
        <div class="property-panel__section-title">关联信息</div>
        <div class="property-panel__field">
          <label class="property-panel__label">所属Agent</label>
          <span class="property-panel__value">{{ selectedNode.data.agentName || '未知' }}</span>
        </div>
        <div class="property-panel__field">
          <label class="property-panel__label">技能ID</label>
          <span class="property-panel__value property-panel__value--mono">{{ selectedNode.data.skillId || '-' }}</span>
        </div>
      </div>

      <!-- 位置信息 -->
      <div class="property-panel__section">
        <div class="property-panel__section-title">位置</div>
        <div class="property-panel__field">
          <label class="property-panel__label">X</label>
          <span class="property-panel__value property-panel__value--mono">{{ Math.round(selectedNode.position?.x || 0) }}</span>
        </div>
        <div class="property-panel__field">
          <label class="property-panel__label">Y</label>
          <span class="property-panel__value property-panel__value--mono">{{ Math.round(selectedNode.position?.y || 0) }}</span>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { NInput, NDivider, NIcon } from 'naive-ui'
import {
  InformationCircleOutline,
  PlayOutline,
  StopOutline,
  FlashOutline
} from '@vicons/ionicons5'
import type { GraphNode } from '@vue-flow/core'

const props = defineProps<{
  selectedNode: GraphNode | null
}>()

const emit = defineEmits<{
  (e: 'update-node-data', nodeId: string, key: string, value: string): void
}>()

/** 节点类型名称 */
const typeName = computed(() => {
  const typeMap: Record<string, string> = {
    start: '开始节点',
    end: '结束节点',
    skill: '技能节点'
  }
  return typeMap[props.selectedNode?.type || ''] || '未知节点'
})

/** 节点类型图标 */
const typeIcon = computed(() => {
  const iconMap: Record<string, ReturnType<typeof FlashOutline>> = {
    start: PlayOutline,
    end: StopOutline,
    skill: FlashOutline
  }
  return iconMap[props.selectedNode?.type || ''] || FlashOutline
})

/** 更新节点数据 */
function updateNodeData(key: string, value: string) {
  if (props.selectedNode) {
    emit('update-node-data', props.selectedNode.id, key, value)
  }
}
</script>

<style lang="scss" scoped>
.property-panel {
  width: 300px;
  height: 100%;
  display: flex;
  flex-direction: column;
  background: var(--color-surface);
  border-left: 1px solid var(--color-border);
  overflow: hidden;
}

.property-panel__header {
  padding: var(--spacing-3) var(--spacing-4);
  border-bottom: 1px solid var(--color-border);
}

.property-panel__title {
  font-size: var(--font-size-md);
  font-weight: var(--font-weight-semibold);
  color: var(--color-text-primary);
}

.property-panel__empty {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--spacing-3);
  color: var(--color-text-tertiary);
  font-size: var(--font-size-sm);
}

.property-panel__content {
  flex: 1;
  overflow-y: auto;
  padding: var(--spacing-4);
}

.property-panel__type-badge {
  display: flex;
  align-items: center;
  gap: var(--spacing-2);
}

.property-panel__type-icon {
  width: 28px;
  height: 28px;
  border-radius: var(--radius-base);
  display: flex;
  align-items: center;
  justify-content: center;

  &--start {
    background: linear-gradient(135deg, #4ADE80, #22C55E);
  }

  &--end {
    background: linear-gradient(135deg, #F87171, #EF4444);
  }

  &--skill {
    background: var(--color-primary);
  }
}

.property-panel__type-name {
  font-size: var(--font-size-base);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
}

.property-panel__section {
  margin-bottom: var(--spacing-4);
}

.property-panel__section-title {
  font-size: var(--font-size-xs);
  font-weight: var(--font-weight-semibold);
  color: var(--color-text-tertiary);
  text-transform: uppercase;
  letter-spacing: 0.5px;
  margin-bottom: var(--spacing-2);
}

.property-panel__field {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-1);
  margin-bottom: var(--spacing-3);
}

.property-panel__label {
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

.property-panel__value {
  font-size: var(--font-size-sm);
  color: var(--color-text-primary);
}

.property-panel__value--mono {
  font-family: var(--font-family-code);
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}
</style>
