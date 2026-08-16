<template>
  <n-modal
    :show="show"
    preset="card"
    title="一键关联（按名称自动匹配上游引用）"
    style="max-width: 640px"
    @update:show="(v: boolean) => emit('update:show', v)"
  >
    <div class="assoc__hint">
      以下文本中的名称与上游节点匹配，确认后将替换为 <code>@引用</code>（与手动 @ 效果一致，运行时注入上游产出）。
    </div>

    <!-- 跳过说明（非文本类/无上游/无匹配） -->
    <div v-if="skipped.length" class="assoc__skipped">
      跳过 {{ skipped.length }} 个节点：{{ skippedSummary }}
    </div>

    <div class="assoc__toolbar">
      <n-checkbox :checked="allChecked" :indeterminate="someChecked" @update:checked="toggleAll">
        全选（{{ checkedSet.size }}/{{ proposals.length }}）
      </n-checkbox>
    </div>

    <div class="assoc__list" role="list">
      <label
        v-for="(p, i) in proposals"
        :key="`${p.targetId}:${p.start}`"
        class="assoc__row"
        :class="{ 'is-checked': checkedSet.has(i) }"
      >
        <n-checkbox :checked="checkedSet.has(i)" @update:checked="(v: boolean) => toggle(i, v)" />
        <div class="assoc__cell assoc__cell--target" :title="p.targetLabel">{{ p.targetLabel }}</div>
        <div class="assoc__cell assoc__cell--cand">
          <span class="assoc__arrow">@</span>{{ p.candName }}
        </div>
        <div class="assoc__cell assoc__cell--preview">…{{ p.before }}<mark>{{ p.match }}</mark>{{ p.after }}…</div>
      </label>
    </div>

    <template #footer>
      <div class="assoc__footer">
        <n-button size="small" @click="emit('update:show', false)">取消</n-button>
        <n-button size="small" type="primary" :disabled="!checkedSet.size" @click="confirm">
          应用 {{ checkedSet.size }} 处关联
        </n-button>
      </div>
    </template>
  </n-modal>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { NButton, NCheckbox, NModal } from 'naive-ui'
import type { AssociationProposal, SkippedNode } from '@/utils/autoAssociate'

const props = defineProps<{
  show: boolean
  proposals: AssociationProposal[]
  skipped: SkippedNode[]
}>()

const emit = defineEmits<{
  (e: 'update:show', v: boolean): void
  (e: 'apply', checked: AssociationProposal[]): void
}>()

/** 勾选索引集（按 proposals 下标）。弹窗每次打开重置为全选。 */
const checkedSet = ref(new Set<number>())
watch(() => props.show, (v) => {
  if (v) checkedSet.value = new Set(props.proposals.map((_, i) => i))
})

const allChecked = computed(() => props.proposals.length > 0 && checkedSet.value.size === props.proposals.length)
const someChecked = computed(() => checkedSet.value.size > 0 && checkedSet.value.size < props.proposals.length)

function toggle(i: number, v: boolean) {
  const next = new Set(checkedSet.value)
  if (v) next.add(i)
  else next.delete(i)
  checkedSet.value = next
}

function toggleAll(v: boolean) {
  checkedSet.value = v ? new Set(props.proposals.map((_, i) => i)) : new Set()
}

function confirm() {
  emit('apply', props.proposals.filter((_, i) => checkedSet.value.has(i)))
  emit('update:show', false)
}

const skippedSummary = computed(() =>
  props.skipped.slice(0, 5).map(s => `${s.label}（${s.reason}）`).join('、')
    + (props.skipped.length > 5 ? ` 等 ${props.skipped.length} 个` : '')
)
</script>

<style lang="scss" scoped>
.assoc__hint {
  font-size: var(--font-size-xs);
  color: var(--color-text-tertiary);
  margin-bottom: var(--spacing-2);

  code {
    color: var(--color-primary);
  }
}

.assoc__skipped {
  font-size: var(--font-size-xs);
  color: var(--color-text-tertiary);
  margin-bottom: var(--spacing-2);
}

.assoc__toolbar {
  padding: var(--spacing-1) 0;
  border-bottom: 1px solid var(--color-border-light);
  margin-bottom: var(--spacing-1);
}

.assoc__list {
  max-height: 320px;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.assoc__row {
  display: grid;
  grid-template-columns: auto 110px 140px 1fr;
  align-items: center;
  gap: var(--spacing-2);
  padding: var(--spacing-1) var(--spacing-2);
  border-radius: var(--radius-base);
  cursor: pointer;

  &:hover {
    background: var(--color-bg-hover, rgba(255, 255, 255, 0.04));
  }

  &.is-checked {
    background: rgba(24, 160, 88, 0.08);
  }
}

.assoc__cell {
  font-size: var(--font-size-xs);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;

  &--target,
  &--cand {
    color: var(--color-text-secondary);
  }

  &--preview {
    color: var(--color-text-tertiary);

    mark {
      background: rgba(24, 160, 88, 0.25);
      color: var(--color-primary);
      border-radius: 2px;
      padding: 0 2px;
    }
  }
}

.assoc__arrow {
  color: var(--color-primary);
  margin-right: 2px;
}

.assoc__footer {
  display: flex;
  justify-content: flex-end;
  gap: var(--spacing-2);
}
</style>
