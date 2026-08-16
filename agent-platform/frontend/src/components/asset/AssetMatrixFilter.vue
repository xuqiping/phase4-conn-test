<!--
  项目资产库·矩阵筛选器  plan §S11 / 设计 §2.2
  - 顶栏：内容类型分段（全部/提示词/剧本/图片/视频/音频）+ 计数徽章
  - 左栏：叙事角色分段（全部角色 + 项目受控词汇桶）+ 计数徽章
  - 搜索框（q≤50）
  - 计数=下钻交集：选类型后角色徽标=该类型下各角色计数；选角色后类型徽标=该角色下各类型计数
  - 受控组件：v-model:modelValue={type?,role?,q?}，change 触发父拉列表
-->
<template>
  <div class="matrix-filter">
    <n-input
      class="matrix-filter__search"
      :value="modelValue.q ?? ''"
      placeholder="搜索资产名称/标签"
      :maxlength="50"
      clearable
      @update:value="onQ"
    />

    <!-- C7 精细筛选：上传者（远程候选+可手输任意用户名）+ 分数来源 + 分数区间 -->
    <div class="matrix-filter__extra">
      <n-select
        class="matrix-filter__creator"
        :value="modelValue.creatorUsername ?? null"
        :options="creatorOptions"
        :loading="creatorLoading"
        filterable
        remote
        clearable
        tag
        placeholder="上传者"
        aria-label="按上传者筛选"
        @search="onCreatorSearch"
        @update:value="onCreator"
      />
      <n-select
        class="matrix-filter__source"
        :value="modelValue.scoreSource ?? null"
        :options="SCORE_SOURCE_OPTIONS"
        clearable
        placeholder="分数来源"
        aria-label="按分数来源筛选"
        @update:value="onScoreSource"
      />
      <div class="matrix-filter__range">
        <n-input-number
          :value="modelValue.scoreMin ?? null"
          :min="0"
          :max="100"
          size="small"
          placeholder="分数≥"
          clearable
          aria-label="分数下限 0 到 100"
          @update:value="(v: number | null) => onScoreBound('min', v)"
        />
        <span class="matrix-filter__range-sep">—</span>
        <n-input-number
          :value="modelValue.scoreMax ?? null"
          :min="0"
          :max="100"
          size="small"
          placeholder="≤"
          clearable
          aria-label="分数上限 0 到 100"
          @update:value="(v: number | null) => onScoreBound('max', v)"
        />
      </div>
    </div>

    <!-- 顶栏：类型分段 -->
    <div class="matrix-filter__types">
      <button
        v-for="t in typeOptions"
        :key="t.key"
        type="button"
        class="matrix-filter__chip"
        :class="{ 'matrix-filter__chip--active': activeType === t.key }"
        @click="selectType(t.key)"
      >
        <span class="matrix-filter__chip-label">{{ t.label }}</span>
        <span class="matrix-filter__badge">{{ typeBadge(t.key) }}</span>
      </button>
    </div>

    <div class="matrix-filter__body">
      <!-- 左栏：角色分段 -->
      <aside class="matrix-filter__roles">
        <button
          type="button"
          class="matrix-filter__role"
          :class="{ 'matrix-filter__role--active': activeRole === '' }"
          @click="selectRole('')"
        >
          <span class="matrix-filter__chip-label">全部角色</span>
          <span class="matrix-filter__badge">{{ allRoleBadge }}</span>
        </button>
        <button
          v-for="r in roles"
          :key="r"
          type="button"
          class="matrix-filter__role"
          :class="{ 'matrix-filter__role--active': activeRole === r }"
          @click="selectRole(r)"
        >
          <span class="matrix-filter__chip-label">{{ r }}</span>
          <span class="matrix-filter__badge">{{ roleBadge(r) }}</span>
        </button>
      </aside>

      <!-- 主区：卡片网格（父传入） -->
      <div class="matrix-filter__main">
        <slot />
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import { NInput, NInputNumber, NSelect } from 'naive-ui'
import { projectApi } from '@/api/assets'
import type { MatrixCountVO, MediaTypeDef } from '@/types/asset'

/** 筛选态：type/role 空=不限，q=搜索词；C7 加上传者/分数区间/分数来源 */
export interface AssetFilter {
  type?: string
  role?: string
  q?: string
  creatorUsername?: string
  scoreMin?: number
  scoreMax?: number
  scoreSource?: 'owner' | 'member'
}

const props = defineProps<{
  modelValue: AssetFilter
  /** 矩阵计数（type×role 每格 + 每类型总数） */
  counts: MatrixCountVO
  /** 项目受控词汇桶（叙事角色） */
  roles: string[]
  /** 媒体类型受控词汇桶（V60，顶栏类型分段从中派生，不再写死五类） */
  mediaTypes: MediaTypeDef[]
  /** 项目 id（C7 上传者远程候选搜索；缺省时输入仍可手输任意用户名） */
  projectId?: number
}>()

const emit = defineEmits<{ (e: 'update:modelValue', v: AssetFilter): void }>()

const activeType = computed(() => props.modelValue.type ?? '')
const activeRole = computed(() => props.modelValue.role ?? '')

/** 媒体类型标签（key 本身即中文显示名，自定义 key 显原文）。 */
function labelFor(key: string): string {
  return key
}

/** 顶栏类型分段：全部 + 项目受控词汇（V60 从 mediaTypes 派生）。 */
const typeOptions = computed<{ key: string; label: string }[]>(() => [
  { key: '', label: '全部' },
  ...props.mediaTypes.map((t) => ({ key: t.key, label: labelFor(t.key) }))
])

/** cells 建图：key=`${mediaType}|${roleKey ?? 'null'}` */
const cellMap = computed(() => {
  const m = new Map<string, number>()
  for (const c of props.counts.cells ?? []) {
    m.set(`${c.mediaType}|${c.roleKey ?? 'null'}`, c.count)
  }
  return m
})
const typeTotalMap = computed(() => {
  const m = new Map<string, number>()
  for (const c of props.counts.typeTotals ?? []) {
    m.set(c.mediaType, c.count)
  }
  return m
})

const grandTotal = computed(() =>
  [...typeTotalMap.value.values()].reduce((a, b) => a + b, 0)
)

/** 类型徽标：选角色→该角色下此类型计数；未选角色→类型总数 */
function typeBadge(key: string): number {
  if (key === '') {
    // 全部类型：选角色→该角色全类型计数；未选→总数
    if (activeRole.value) return roleAllTypesCount(activeRole.value)
    return grandTotal.value
  }
  if (activeRole.value) {
    return cellMap.value.get(`${key}|${activeRole.value}`) ?? 0
  }
  return typeTotalMap.value.get(key) ?? 0
}

/** 角色行徽标：选类型→该类型下此角色计数；未选类型→此角色全类型计数 */
function roleBadge(roleKey: string): number {
  if (activeType.value) {
    return cellMap.value.get(`${activeType.value}|${roleKey}`) ?? 0
  }
  return roleAllTypesCount(roleKey)
}

/** 「全部角色」行：选类型→类型总数；未选→总数 */
const allRoleBadge = computed(() => {
  if (activeType.value) return typeTotalMap.value.get(activeType.value) ?? 0
  return grandTotal.value
})

/** 某角色在所有类型下的计数之和（未选类型时角色行徽标） */
function roleAllTypesCount(roleKey: string): number {
  let sum = 0
  for (const t of typeOptions.value) {
    if (t.key === '') continue
    sum += cellMap.value.get(`${t.key}|${roleKey}`) ?? 0
  }
  return sum
}

function selectType(key: string) {
  emit('update:modelValue', {
    ...props.modelValue,
    type: key === '' ? undefined : key
  })
}
function selectRole(r: string) {
  emit('update:modelValue', {
    ...props.modelValue,
    role: r === '' ? undefined : r
  })
}
function onQ(v: string) {
  emit('update:modelValue', { ...props.modelValue, q: v || undefined })
}

// ---------- C7 上传者/分数筛选 ----------

const SCORE_SOURCE_OPTIONS = [
  { label: '拥有者评分', value: 'owner' },
  { label: '成员均分', value: 'member' }
]

const creatorOptions = ref<{ label: string; value: string }[]>([])
const creatorLoading = ref(false)
let creatorSearchTimer: ReturnType<typeof setTimeout> | null = null

/** 上传者远程候选（300ms 防抖；本项目上传者去重端点——成员/公共 VIEWER 可用，P4 实测修复 403；失败静默，手输仍可用 tag 直选）。 */
function onCreatorSearch(keyword: string) {
  if (creatorSearchTimer) clearTimeout(creatorSearchTimer)
  const kw = keyword.trim()
  if (!kw || props.projectId == null) {
    creatorOptions.value = []
    return
  }
  creatorSearchTimer = setTimeout(async () => {
    creatorLoading.value = true
    try {
      const res = await projectApi.creatorCandidates(props.projectId!, kw)
      creatorOptions.value = (res.data.data ?? []).map((c) => ({ label: c.username, value: c.username }))
    } catch {
      creatorOptions.value = []
    } finally {
      creatorLoading.value = false
    }
  }, 300)
}

onBeforeUnmount(() => {
  if (creatorSearchTimer) clearTimeout(creatorSearchTimer)
})

function onCreator(v: string | null) {
  emit('update:modelValue', { ...props.modelValue, creatorUsername: v || undefined })
}

function onScoreSource(v: string | null) {
  emit('update:modelValue', { ...props.modelValue, scoreSource: (v as 'owner' | 'member') || undefined })
}

function onScoreBound(which: 'min' | 'max', v: number | null) {
  emit('update:modelValue', {
    ...props.modelValue,
    [which === 'min' ? 'scoreMin' : 'scoreMax']: v ?? undefined
  })
}
</script>

<style lang="scss" scoped>
.matrix-filter {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-3);

  &__search {
    max-width: 360px;
  }

  &__extra {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: var(--spacing-2);
  }

  &__creator {
    width: 180px;
  }

  &__source {
    width: 150px;
  }

  &__range {
    display: flex;
    align-items: center;
    gap: var(--spacing-1);
  }

  &__range-sep {
    color: var(--color-text-tertiary);
    font-size: var(--font-size-sm);
  }

  &__types {
    display: flex;
    flex-wrap: wrap;
    gap: var(--spacing-2);
  }

  &__chip,
  &__role {
    display: inline-flex;
    align-items: center;
    gap: var(--spacing-2);
    padding: 4px 12px;
    border: 1px solid var(--color-border);
    border-radius: 999px;
    background: var(--color-bg-secondary);
    color: var(--color-text-secondary);
    font-size: var(--font-size-sm);
    cursor: pointer;
    transition: all var(--duration-fast);

    &:hover {
      border-color: var(--color-primary);
      color: var(--color-text-primary);
    }

    &--active {
      border-color: var(--color-primary);
      background: var(--color-primary);
      color: var(--color-text-white, #fff);
    }
  }

  &__role {
    width: 100%;
    justify-content: space-between;
    text-align: left;
  }

  &__roles {
    display: flex;
    flex-direction: column;
    gap: var(--spacing-2);
    min-width: 140px;
  }

  &__badge {
    min-width: 20px;
    padding: 0 6px;
    border-radius: 10px;
    background: var(--color-bg-tertiary, rgba(255, 255, 255, 0.08));
    font-size: var(--font-size-xs);
    text-align: center;
  }

  &__chip--active &__badge,
  &__role--active &__badge {
    background: rgba(255, 255, 255, 0.22);
    color: var(--color-text-white, #fff);
  }

  &__body {
    display: flex;
    gap: var(--spacing-4);
    align-items: flex-start;
  }

  &__main {
    flex: 1;
    min-width: 0;
  }
}

// === 移动端：角色栏改横滑 ===
@media (max-width: 768px) {
  .matrix-filter__body {
    flex-direction: column;
  }
  .matrix-filter__roles {
    flex-direction: row;
    overflow-x: auto;
    min-width: 0;
    width: 100%;
    -webkit-overflow-scrolling: touch;
  }
  .matrix-filter__role {
    width: auto;
    flex-shrink: 0;
  }
}
</style>
