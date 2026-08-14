<script setup lang="ts">
import type { WorkflowStatus } from '@/mocks/types'

const props = defineProps<{ status: WorkflowStatus }>()

const MAP: Record<WorkflowStatus, { label: string; cls: string }> = {
  draft: { label: '草稿', cls: 'wf-tag--draft' },
  running: { label: '运行中', cls: 'wf-tag--running' },
  success: { label: '完成', cls: 'wf-tag--success' },
  failed: { label: '失败', cls: 'wf-tag--failed' }
}
</script>

<template>
  <span class="wf-tag" :class="MAP[props.status].cls">
    <i class="wf-tag__dot" />{{ MAP[props.status].label }}
  </span>
</template>

<style lang="scss" scoped>
.wf-tag {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 1px 8px;
  border-radius: 99px;
  font-size: var(--fs-xs);

  &__dot {
    width: 6px;
    height: 6px;
    border-radius: 50%;
    background: currentColor;
  }

  &--draft {
    color: var(--tx-2);
    background: var(--sf-3);
  }
  &--running {
    color: var(--info);
    background: color-mix(in srgb, var(--info) 14%, transparent);

    .wf-tag__dot {
      animation: breathe 1.2s ease-in-out infinite;
    }
  }
  &--success {
    color: var(--ok);
    background: color-mix(in srgb, var(--ok) 14%, transparent);
  }
  &--failed {
    color: var(--err);
    background: color-mix(in srgb, var(--err) 14%, transparent);
  }
}
</style>
