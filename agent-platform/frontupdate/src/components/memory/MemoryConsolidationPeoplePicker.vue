<!-- ============================================================
  总结取数选人（计划12 I4-3）— 项目 scope 总结时选「取哪些成员的流水账」
  · 候选 = 项目花名册（memoryApi.getRoster）；后端最终 ∩ readableAuthors 校验
  · 人员范围 SELF（仅自己，默认）/ SPECIFIC（多选）/ ALL（全部可召回）
  · 方向 INPUT/OUTPUT/BOTH；L10「同步已离开人员」开关关 → 离职人员选项禁用
    （开关优先级高于勾选：即便 SPECIFIC 勾了离职人员，后端也会剔）
  · emit change({ authorFilter, authorIds, direction, includeDeparted })
  ============================================================ -->
<template>
  <div class="people-picker">
    <n-empty v-if="!loading && !roster.length" size="small" description="项目无成员" />
    <n-spin v-else-if="loading" size="small" />

    <template v-else>
      <div class="people-picker__row">
        <span class="people-picker__label">取数人员</span>
        <n-select
          :value="cfg.authorFilter"
          :options="filterOptions"
          size="small"
          style="width: 140px"
          :consistent-menu-width="false"
          @update:value="(v) => update('authorFilter', v)"
        />
      </div>

      <div v-if="cfg.authorFilter === 'SPECIFIC'" class="people-picker__row">
        <span class="people-picker__label">选成员</span>
        <n-select
          :value="cfg.authorIds"
          multiple
          :options="authorOptions"
          placeholder="选择成员"
          size="small"
          style="flex: 1; min-width: 0"
          :consistent-menu-width="false"
          max-tag-count="responsive"
          @update:value="(v: number[]) => update('authorIds', v)"
        />
      </div>

      <div class="people-picker__row">
        <span class="people-picker__label">方向</span>
        <n-select
          :value="cfg.direction"
          :options="directionOptions"
          size="small"
          style="width: 120px"
          :consistent-menu-width="false"
          @update:value="(v) => update('direction', v)"
        />
      </div>

      <div class="people-picker__row">
        <n-tooltip placement="top">
          <template #trigger>
            <span class="people-picker__label people-picker__label--hint">含已离开人员</span>
          </template>
          关闭后取数候选剔除已离开成员（优先级高于人员勾选；后端兜底同样剔）
        </n-tooltip>
        <n-switch
          :value="cfg.includeDeparted"
          @update:value="(v: boolean) => update('includeDeparted', v)"
        />
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { NEmpty, NSelect, NSpin, NSwitch, NTooltip } from 'naive-ui'
import { memoryApi, type MemoryRosterVO } from '@/api/memory'

type AuthorFilter = 'SELF' | 'SPECIFIC' | 'ALL'
type Direction = 'INPUT' | 'OUTPUT' | 'BOTH'

interface PeopleCfg {
  authorFilter: AuthorFilter
  authorIds: number[]
  direction: Direction
  includeDeparted: boolean
}

interface Props {
  projectId: number
  modelValue?: Partial<PeopleCfg>
}
const props = withDefaults(defineProps<Props>(), {
  modelValue: () => ({})
})
const emit = defineEmits<{
  (e: 'update:modelValue', v: PeopleCfg): void
}>()

const DEFAULT_CFG: PeopleCfg = {
  authorFilter: 'SELF',
  authorIds: [],
  direction: 'BOTH',
  includeDeparted: true
}

const cfg = ref<PeopleCfg>({ ...DEFAULT_CFG, ...props.modelValue })
const roster = ref<MemoryRosterVO[]>([])
const loading = ref(false)

const filterOptions = [
  { label: '仅自己', value: 'SELF' },
  { label: '选指定成员', value: 'SPECIFIC' },
  { label: '全部可召回', value: 'ALL' }
]
const directionOptions = [
  { label: '全部', value: 'BOTH' },
  { label: '仅输入', value: 'INPUT' },
  { label: '仅输出', value: 'OUTPUT' }
]

// 候选成员：开关关时离职人员仍显示但禁用（视觉明示被剔）
const authorOptions = computed(() =>
  roster.value.map(r => ({
    label: `${r.name || r.username}${r.status === 'DEPARTED' ? '（已离开）' : ''}`,
    value: r.userId,
    disabled: r.status === 'DEPARTED' && !cfg.value.includeDeparted
  }))
)

function update<K extends keyof PeopleCfg>(key: K, value: PeopleCfg[K]) {
  cfg.value = { ...cfg.value, [key]: value }
  emit('update:modelValue', { ...cfg.value })
}

async function loadRoster() {
  loading.value = true
  try {
    const res = await memoryApi.getRoster(props.projectId)
    roster.value = res.data?.data ?? []
  } catch {
    roster.value = []
  } finally {
    loading.value = false
  }
}

// 父组件重置（如切换项目）时同步
watch(
  () => props.modelValue,
  (v) => {
    cfg.value = { ...DEFAULT_CFG, ...v }
  },
  { deep: true }
)

onMounted(loadRoster)
</script>

<style lang="scss" scoped>
.people-picker {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 6px 0 6px 24px;
  border-left: 2px solid var(--color-border-light);
  margin-left: 6px;

  &__row {
    display: flex;
    align-items: center;
    gap: 8px;
  }
  &__label {
    font-size: 12px;
    color: var(--color-text-secondary);
    white-space: nowrap;
    min-width: 64px;

    &--hint {
      cursor: help;
      border-bottom: 1px dashed var(--color-text-tertiary);
    }
  }
}
</style>
