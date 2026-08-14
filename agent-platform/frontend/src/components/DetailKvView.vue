<template>
  <!-- 8x-2/13x-1：detailJson 键值中文渲染，替代原始 JSON 堆砌 -->
  <div class="kv-detail">
    <template v-if="entries.length">
      <div v-for="e in entries" :key="e.key" class="kv-detail__row">
        <span class="kv-detail__label">{{ e.label }}</span>
        <span class="kv-detail__value">{{ e.value }}</span>
      </div>
    </template>
    <div v-else class="kv-detail__raw">{{ raw || '(无详情)' }}</div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { detailKeyCn, detailValueCnForKey } from '@/utils/detailLabels'

const props = defineProps<{ raw: string | null | undefined }>()

interface Entry { key: string; label: string; value: string }

const entries = computed<Entry[]>(() => {
  if (!props.raw) return []
  try {
    const obj = JSON.parse(props.raw)
    if (obj === null || typeof obj !== 'object' || Array.isArray(obj)) return []
    // 空对象 {} 显示"无详情"；value 翻译带 key 上下文（action 等 "module:action" 码组合翻译，13x-1）
    return Object.entries(obj as Record<string, unknown>).map(([k, v]) => ({
      key: k,
      label: detailKeyCn(k),
      value: detailValueCnForKey(k, v)
    }))
  } catch {
    return []
  }
})
</script>

<style lang="scss" scoped>
.kv-detail {
  &__row {
    display: flex;
    gap: 12px;
    padding: 6px 0;
    font-size: 13px;
    line-height: 1.6;

    & + & {
      border-top: 1px dashed var(--color-border, rgba(255, 255, 255, 0.08));
    }
  }

  &__label {
    flex: 0 0 130px;
    color: var(--color-text-secondary);
    white-space: nowrap;
  }

  &__value {
    flex: 1;
    word-break: break-all;
    color: var(--color-text-primary);
  }

  &__raw {
    padding: 12px;
    background: var(--color-bg-secondary, rgba(255, 255, 255, 0.04));
    border-radius: 6px;
    font-size: 12px;
    white-space: pre-wrap;
    word-break: break-all;
    max-height: 50vh;
    overflow: auto;
  }
}
</style>
