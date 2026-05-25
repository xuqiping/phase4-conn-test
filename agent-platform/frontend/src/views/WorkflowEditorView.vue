<template>
  <div class="page-placeholder">
    <n-icon size="48" :component="CreateOutline" />
    <h2>工作流编辑器</h2>
    <p>此页面将在后续Plan中实现</p>
  </div>
</template>

<script setup lang="ts">
import { NIcon } from 'naive-ui'
import { CreateOutline } from '@vicons/ionicons5'
</script>

<style lang="scss" scoped>
.page-placeholder {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  min-height: 400px;
  color: var(--color-text-tertiary);
  gap: var(--spacing-3);

  h2 {
    color: var(--color-text-secondary);
    font-size: var(--font-size-xl);
  }

  p {
    font-size: var(--font-size-sm);
  }
}
</style>
