<!-- ============================================================
  冲突裁决四选项 Dialog（计划12 F-2）
  · KEEP_BOTH（提示自动排序）/ KEEP_NEW / KEEP_OLD / DISCARD（二次确认：将连带删除相关流水账）
  · DISCARD 失败 403 → 提示「他人已引用超 12h，建议 KEEP_OLD」
  · 走新栈 memoryApi.resolveConflict（POST /memory/conflicts/{id}/resolve）
  ============================================================ -->
<template>
  <n-modal
    :show="show"
    @update:show="$emit('update:show', $event)"
    preset="card"
    title="记忆冲突裁决"
    style="max-width: 560px"
    :bordered="false"
  >
    <n-space vertical :size="12">
      <div class="conflict-judge__ask">{{ conflict?.askText || '（无描述）' }}</div>
      <n-alert type="warning" :bordered="false" size="small">
        同主体同主题出现时序互斥的两条总结，需选择保留策略。
      </n-alert>

      <div class="conflict-judge__options">
        <div
          v-for="opt in OPTIONS"
          :key="opt.value"
          class="conflict-judge__option"
          :class="{ 'is-active': decision === opt.value }"
          @click="decision = opt.value"
        >
          <div class="conflict-judge__option-head">
            <n-tag :type="opt.tagType" size="small" :bordered="false">{{ opt.label }}</n-tag>
            <span v-if="decision === opt.value" class="conflict-judge__check">✓</span>
          </div>
          <div class="conflict-judge__option-desc">{{ opt.desc }}</div>
        </div>
      </div>

      <n-alert v-if="decision === 'KEEP_BOTH'" type="info" :bordered="false" size="small">
        两条都留，系统按时间自动排序（不丢信息）。
      </n-alert>
    </n-space>

    <template #footer>
      <n-space justify="end">
        <n-button :disabled="resolving" @click="$emit('update:show', false)">取消</n-button>
        <n-button
          type="primary"
          :loading="resolving"
          :disabled="!decision"
          @click="confirm"
        >
          确认裁决
        </n-button>
      </n-space>
    </template>
  </n-modal>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import { NAlert, NButton, NModal, NSpace, NTag, useDialog, useMessage } from 'naive-ui'
import { memoryApi, type MemoryPendingConflictVO } from '@/api/memory'

interface Props {
  show: boolean
  conflict: MemoryPendingConflictVO | null
}
const props = defineProps<Props>()
const emit = defineEmits<{
  (e: 'update:show', v: boolean): void
  (e: 'resolved', conflictId: number, decision: string): void
}>()

const message = useMessage()
const dialog = useDialog()

type Decision = 'KEEP_BOTH' | 'KEEP_NEW' | 'KEEP_OLD' | 'DISCARD'
const OPTIONS: { value: Decision; label: string; desc: string; tagType: 'success' | 'info' | 'warning' | 'error' }[] = [
  { value: 'KEEP_BOTH', label: '都留', desc: '两条总结都保留，按时间自动排序', tagType: 'success' },
  { value: 'KEEP_NEW', label: '留新', desc: '保留新总结，丢弃旧总结', tagType: 'info' },
  { value: 'KEEP_OLD', label: '留旧', desc: '保留旧总结，丢弃新总结', tagType: 'info' },
  { value: 'DISCARD', label: '全删', desc: '两条都删，并连带删除相关流水账', tagType: 'error' }
]

const decision = ref<Decision | null>(null)
const resolving = ref(false)

// 每次打开重置选项
watch(() => props.show, (s) => {
  if (s) decision.value = null
})

function confirm() {
  if (!props.conflict || !decision.value) return
  if (decision.value === 'DISCARD') {
    // 二次确认：连带删 turns
    dialog.warning({
      title: '确认全删？',
      content: '将删除这两条总结，并连带软删相关流水账（不可恢复）。继续？',
      positiveText: '全删',
      negativeText: '取消',
      onPositiveClick: () => doResolve(decision.value!)
    })
  } else {
    doResolve(decision.value)
  }
}

async function doResolve(d: Decision) {
  if (!props.conflict) return
  resolving.value = true
  try {
    await memoryApi.resolveConflict(props.conflict.conflictId, d)
    message.success('已裁决')
    emit('resolved', props.conflict.conflictId, d)
    emit('update:show', false)
  } catch (e: any) {
    // DISCARD 403：他人引用超 12h
    const code = e?.response?.data?.code ?? e?.code
    const msg = e?.response?.data?.msg ?? e?.message ?? ''
    if (code === 403 || /12|引用|FORBIDDEN/i.test(msg)) {
      message.error('删除被拒：他人已引用该总结超 12h。建议改选「留旧」')
    } else {
      message.error(msg || '裁决失败')
    }
  } finally {
    resolving.value = false
  }
}
</script>

<style lang="scss" scoped>
.conflict-judge {
  &__ask {
    font-size: 14px;
    line-height: 1.6;
    padding: 8px 12px;
    border-radius: 6px;
    background: var(--card-color, rgba(255, 255, 255, 0.04));
  }
  &__options {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 8px;
  }
  &__option {
    padding: 10px 12px;
    border: 1px solid var(--divider-color, rgba(255, 255, 255, 0.09));
    border-radius: 6px;
    cursor: pointer;
    transition: border-color 0.15s, background 0.15s;
    &:hover {
      border-color: var(--primary-color, #63e2b7);
    }
    &.is-active {
      border-color: var(--primary-color, #63e2b7);
      background: rgba(99, 226, 183, 0.08);
    }
  }
  &__option-head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-bottom: 4px;
  }
  &__check {
    color: var(--primary-color, #63e2b7);
    font-weight: 600;
  }
  &__option-desc {
    font-size: 12px;
    opacity: 0.7;
    line-height: 1.5;
  }
}
</style>
