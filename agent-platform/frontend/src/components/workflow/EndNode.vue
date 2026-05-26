<!-- ============================================================
  结束节点 — 红色圆形，工作流出口
  ============================================================ -->
<template>
  <div class="end-node">
    <Handle type="target" :position="Position.Top" />
    <div class="end-node__circle">
      <span class="end-node__label">结束</span>
    </div>
  </div>
</template>

<script setup lang="ts">
import { Handle, Position } from '@vue-flow/core'
</script>

<style lang="scss" scoped>
.end-node {
  display: flex;
  align-items: center;
  justify-content: center;
}

.end-node__circle {
  width: 64px;
  height: 64px;
  border-radius: var(--radius-full);
  background: linear-gradient(135deg, #F87171, #EF4444);
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: 0 0 16px rgba(248, 113, 113, 0.3);
  cursor: pointer;
  transition: box-shadow var(--duration-fast) var(--ease-in-out);
}

.end-node__circle:hover {
  box-shadow: 0 0 24px rgba(248, 113, 113, 0.5);
}

.end-node__label {
  color: #fff;
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-semibold);
  user-select: none;
}
</style>
