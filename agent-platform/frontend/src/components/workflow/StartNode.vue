<!-- ============================================================
  开始节点 — 绿色圆形，工作流入口
  ============================================================ -->
<template>
  <div class="start-node">
    <Handle type="source" :position="Position.Bottom" />
    <div class="start-node__circle">
      <span class="start-node__label">开始</span>
    </div>
  </div>
</template>

<script setup lang="ts">
import { Handle, Position } from '@vue-flow/core'
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
  background: linear-gradient(135deg, #4ADE80, #22C55E);
  display: flex;
  align-items: center;
  justify-content: center;
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
</style>
