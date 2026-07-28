<!-- ============================================================
  统一总结入口弹框（计划12 F-3c）
  · 走 memoryApi.listConsolidationTargets + triggerConsolidation
  · targets 列 {个人} ∪ 已加入项目，标 hasChange / 未覆盖数 / autoEnabled
  · scope 可选/灰选 + 自动勾选（autoEnabled 行默认勾）
  ============================================================ -->
<template>
  <n-modal
    :show="show"
    @update:show="$emit('update:show', $event)"
    preset="card"
    title="立即总结"
    style="max-width: 560px"
    :bordered="false"
  >
    <n-spin :show="loading">
      <n-empty v-if="!loading && !targets.length" size="small" description="无可总结 scope" />
      <n-space v-else vertical :size="8">
        <div
          v-for="t in targets"
          :key="t.scopeKind + (t.projectId ?? '')"
          class="consolidation-dialog__row"
        >
          <n-checkbox
            :checked="selected.has(keyOf(t))"
            :disabled="!t.hasChange"
            @update:checked="toggle(t, $event)"
          >
            <span class="consolidation-dialog__name">{{ t.displayName }}</span>
          </n-checkbox>
          <n-tag v-if="!t.hasChange" size="tiny" :bordered="false">无变化</n-tag>
          <n-tag v-else size="tiny" type="warning" :bordered="false">未总结 {{ t.uncoveredCount }}</n-tag>
          <n-tag v-if="t.autoEnabled" size="tiny" type="info" :bordered="false">自动</n-tag>
        </div>
      </n-space>
    </n-spin>

    <template #footer>
      <n-space justify="end">
        <n-button :disabled="triggering" @click="$emit('update:show', false)">取消</n-button>
        <n-button
          type="primary"
          :loading="triggering"
          :disabled="selectedScopes.length === 0"
          @click="trigger"
        >
          总结 {{ selectedScopes.length }} 个 scope
        </n-button>
      </n-space>
    </template>
  </n-modal>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { NButton, NCheckbox, NEmpty, NModal, NSpace, NSpin, NTag, useMessage } from 'naive-ui'
import {
  memoryApi,
  type MemoryConsolidationTargetView,
  type MemoryConsolidationScopeRequest
} from '@/api/memory'

interface Props { show: boolean }
const props = defineProps<Props>()
const emit = defineEmits<{
  (e: 'update:show', v: boolean): void
  (e: 'done'): void
}>()

const message = useMessage()

const targets = ref<MemoryConsolidationTargetView[]>([])
const loading = ref(false)
const triggering = ref(false)
const selected = ref(new Set<string>())

const selectedScopes = computed<MemoryConsolidationScopeRequest[]>(() =>
  targets.value
    .filter(t => selected.value.has(keyOf(t)))
    .map(t => ({ scopeKind: t.scopeKind, projectId: t.projectId ?? undefined }))
)

function keyOf(t: MemoryConsolidationTargetView): string {
  return t.scopeKind + ':' + (t.projectId ?? '')
}

function toggle(t: MemoryConsolidationTargetView, on: boolean) {
  const s = new Set(selected.value)
  if (on) s.add(keyOf(t))
  else s.delete(keyOf(t))
  selected.value = s
}

async function loadTargets() {
  loading.value = true
  try {
    const res = await memoryApi.listConsolidationTargets()
    targets.value = res.data?.data ?? []
    // 默认勾选 hasChange + autoEnabled 的 scope（自动总结的延续）
    const s = new Set<string>()
    for (const t of targets.value) {
      if (t.hasChange && t.autoEnabled) s.add(keyOf(t))
    }
    selected.value = s
  } catch (e: any) {
    message.error(e?.message || '加载 scope 失败')
  } finally {
    loading.value = false
  }
}

async function trigger() {
  if (selectedScopes.value.length === 0) return
  triggering.value = true
  try {
    const res = await memoryApi.triggerConsolidation(selectedScopes.value)
    const r = res.data?.data
    message.success(`已写 ${r?.summariesWritten ?? 0} 条总结${r?.conflictsCreated ? `，${r.conflictsCreated} 条冲突待裁` : ''}`)
    emit('done')
    emit('update:show', false)
  } catch (e: any) {
    message.error(e?.message || '总结失败')
  } finally {
    triggering.value = false
  }
}

watch(() => props.show, (s) => {
  if (s) loadTargets()
})
</script>

<style lang="scss" scoped>
.consolidation-dialog {
  &__row {
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 6px 4px;
  }
  &__name {
    font-size: 13px;
  }
}
</style>
