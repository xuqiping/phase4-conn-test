<template>
  <div class="start-node">
    <Handle type="source" :position="Position.Bottom" />
    <div class="start-node__circle">
      <span class="start-node__label">开始</span>
      <span v-if="data.inputKey" class="start-node__input-key">{{ data.inputKey }}</span>
    </div>
  </div>
</template>

<script setup lang="ts">
import { Handle, Position } from '@vue-flow/core'

defineProps<{
  data: {
    inputKey?: string
  }
}>()
</script>

<style lang="scss" scoped>
.start-node {
  display: flex;
  align-items: center;
  justify-content: center;
}

.start-node__circle {
  width: 64px;
  height: 64px;
  border-radius: var(--radius-full);
  background: linear-gradient(135deg, #4ade80, #22c55e);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-direction: column;
  gap: 2px;
  box-shadow: 0 0 16px rgba(74, 222, 128, 0.3);
  cursor: pointer;
  transition: box-shadow var(--duration-fast) var(--ease-in-out);
}

.start-node__circle:hover {
  box-shadow: 0 0 24px rgba(74, 222, 128, 0.5);
}

.start-node__label {
  color: #fff;
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-semibold);
  user-select: none;
}

.start-node__input-key {
  max-width: 52px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: rgba(255, 255, 255, 0.82);
  font-size: 10px;
  line-height: 1;
  user-select: none;
}
</style>
