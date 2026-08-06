<!--
  分镜实体引用列表（S18 字段2/4 复用）：每行 key 输入 + 资产 NSelect + 删除。
  - assetId 取不到（被删/失权，L16）→ 显「资产已删」红标不崩
  - canEdit=false 只读（值/标签）
-->
<template>
  <div class="sb-ref-list">
    <div v-for="(r, idx) in refs" :key="idx" class="sb-ref-list__row">
      <n-input
        :value="r.key"
        :readonly="!canEdit"
        size="small"
        :placeholder="keyPlaceholder"
        style="flex: 0 0 38%"
        @update:value="(v: string) => patch(idx, { key: v })"
      />
      <!-- 资产已删降级：assetId 存在但不在候选目录 -->
      <template v-if="r.assetId != null && !optionMap.has(r.assetId)">
        <span class="sb-ref-list__deleted">资产已删（#{{ r.assetId }}）</span>
      </template>
      <template v-else>
        <n-select
          :value="r.assetId ?? null"
          :options="options"
          :disabled="!canEdit"
          size="small"
          clearable
          placeholder="选择资产"
          style="flex: 1"
          @update:value="(v: number | null) => patch(idx, { assetId: v ?? null })"
        />
      </template>
      <n-button v-if="canEdit" size="tiny" quaternary type="error" @click="remove(idx)">删</n-button>
    </div>
    <n-button v-if="canEdit" size="tiny" dashed block @click="add">+ 添加</n-button>
    <n-empty v-if="!refs.length && !canEdit" size="small" description="无引用" />
  </div>
</template>

<script setup lang="ts">
import { NButton, NEmpty, NInput, NSelect } from 'naive-ui'
import type { SelectOption } from 'naive-ui'
import type { AssetVO, StoryboardEntityRef } from '@/types/asset'

const props = defineProps<{
  refs: StoryboardEntityRef[]
  options: SelectOption[]
  optionMap: Map<number, AssetVO>
  canEdit?: boolean
  keyPlaceholder?: string
}>()
const emit = defineEmits<{ (e: 'update:refs', v: StoryboardEntityRef[]): void }>()

function emitNext(next: StoryboardEntityRef[]) {
  emit('update:refs', next)
}
function patch(idx: number, over: Partial<StoryboardEntityRef>) {
  const next = props.refs.map((r, i) => (i === idx ? { ...r, ...over } : r))
  emitNext(next)
}
function remove(idx: number) {
  emitNext(props.refs.filter((_, i) => i !== idx))
}
function add() {
  emitNext([...props.refs, { key: '', assetId: null }])
}
</script>

<style lang="scss" scoped>
.sb-ref-list {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-2);

  &__row {
    display: flex;
    align-items: center;
    gap: var(--spacing-2);
  }

  &__deleted {
    flex: 1;
    font-size: var(--font-size-xs);
    color: var(--color-error, #d03050);
  }
}
</style>
