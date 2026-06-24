<template>
  <div class="input-node" :class="{ 'input-node--selected': selected }">
    <Handle type="target" :position="Position.Top" />
    <div class="input-node__accent" />
    <div class="input-node__content">
      <div class="input-node__icon">
        <n-icon size="15" color="#08111f">
          <TextOutline />
        </n-icon>
      </div>
      <div class="input-node__copy">
        <div class="input-node__topline">
          <span class="input-node__kind">{{ inputTypeLabel }}</span>
          <span v-if="data.required" class="input-node__required">Required</span>
        </div>
        <span class="input-node__name">{{ data.label }}</span>
        <span class="input-node__meta">{{ data.inputKey || 'input' }}</span>
      </div>
    </div>
    <Handle type="source" :position="Position.Bottom" />
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { Handle, Position } from '@vue-flow/core'
import { NIcon } from 'naive-ui'
import { TextOutline } from '@vicons/ionicons5'

const props = defineProps<{
  data: {
    label: string
    inputKey?: string
    inputType?: string
    required?: boolean
  }
  selected?: boolean
}>()

const inputTypeLabel = computed(() => {
  const map: Record<string, string> = {
    text: 'Text',
    textarea: 'Prompt',
    image: 'Image',
    video: 'Video',
    file: 'File'
  }
  return map[props.data.inputType || 'text'] || props.data.inputType || 'Text'
})
</script>

<style lang="scss" scoped>
.input-node {
  position: relative;
  width: 188px;
  min-height: 76px;
  background: linear-gradient(145deg, rgba(8, 18, 34, 0.98), rgba(17, 28, 47, 0.96));
  border: 1px solid rgba(56, 189, 248, 0.28);
  border-radius: var(--radius-md);
  overflow: hidden;
  cursor: pointer;
  box-shadow: 0 12px 26px rgba(0, 0, 0, 0.28),
              inset 0 1px 0 rgba(255, 255, 255, 0.06);
  transition: border-color var(--duration-fast) var(--ease-in-out),
              box-shadow var(--duration-fast) var(--ease-in-out),
              transform var(--duration-fast) var(--ease-in-out);

  &:hover,
  &--selected {
    border-color: #38bdf8;
    box-shadow: 0 14px 30px rgba(0, 0, 0, 0.34),
                0 0 0 1px rgba(56, 189, 248, 0.3),
                0 0 22px rgba(56, 189, 248, 0.2);
    transform: translateY(-1px);
  }
}

.input-node__accent {
  height: 3px;
  background: linear-gradient(90deg, #38bdf8, #22d3ee 46%, #a3e635);
}

.input-node__content {
  display: flex;
  align-items: flex-start;
  gap: var(--spacing-2);
  padding: 11px 12px 12px;
}

.input-node__icon {
  width: 26px;
  height: 26px;
  flex: 0 0 26px;
  border-radius: var(--radius-sm);
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(145deg, #7dd3fc, #22d3ee);
  box-shadow: 0 0 16px rgba(34, 211, 238, 0.28);
}

.input-node__copy {
  min-width: 0;
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 3px;
}

.input-node__topline {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}

.input-node__kind,
.input-node__required {
  font-size: 10px;
  line-height: 1;
  color: #8bdcf8;
  text-transform: uppercase;
}

.input-node__required {
  padding: 3px 5px;
  border: 1px solid rgba(163, 230, 53, 0.32);
  border-radius: var(--radius-sm);
  color: #bef264;
  background: rgba(163, 230, 53, 0.08);
}

.input-node__name {
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
  line-height: 1.25;
}

.input-node__meta {
  font-size: var(--font-size-xs);
  color: var(--color-text-tertiary);
}

.input-node__name,
.input-node__meta,
.input-node__kind {
  max-width: 100%;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
</style>
