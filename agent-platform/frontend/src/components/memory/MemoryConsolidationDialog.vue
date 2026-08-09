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
    :title="mode === 'resummarize' ? '重新总结' : '立即总结'"
    :style="{ maxWidth: '560px', width: '90vw' }"
    :bordered="false"
  >
    <n-spin :show="loading">
      <!-- 二期人工测试 Req2：仅「重新总结」模式显示筛选（标签大类/时间/方向）；「立即总结」只压新增，无需筛选 -->
      <div v-if="mode === 'resummarize'" class="consolidation-dialog__filter">
        <div class="consolidation-dialog__filter-title">重新总结筛选（可选，留空 = 全部）</div>
        <n-select
          v-model:value="tagFilter"
          multiple
          filterable
          size="small"
          placeholder="仅总结所选标签"
          :options="tagOptions"
          :max-tag-count="2"
          clearable
        />
        <n-date-picker
          v-model:value="dateRange"
          type="datetimerange"
          size="small"
          clearable
          placeholder="按创建时间范围"
        />
        <!-- 二期 P3c：方向筛选——个人/个人记忆等非项目 scope 透传，按输入/输出各生成独立总结行 -->
        <div class="consolidation-dialog__direction">
          <span class="consolidation-dialog__filter-hint">方向</span>
          <n-radio-group v-model:value="directionFilter" size="small">
            <n-radio-button value="BOTH">综合</n-radio-button>
            <n-radio-button value="INPUT">仅输入</n-radio-button>
            <n-radio-button value="OUTPUT">仅输出</n-radio-button>
          </n-radio-group>
          <span v-if="directionFilter !== 'BOTH'" class="consolidation-dialog__filter-hint">
            仅 {{ directionFilter === 'INPUT' ? '输入' : '输出' }} 流水账 → 生成独立总结行
          </span>
        </div>
      </div>
      <n-empty v-if="!loading && !visibleTargets.length" size="small" description="无可总结 scope（仅创始人可总结项目）" />
      <n-space v-else vertical :size="8">
        <div
          v-for="t in visibleTargets"
          :key="t.scopeKind + (t.projectId ?? '')"
          class="consolidation-dialog__row"
        >
          <div class="consolidation-dialog__row-head">
            <n-checkbox
              :checked="selected.has(keyOf(t))"
              :disabled="mode === 'instant' && !t.hasChange"
              @update:checked="toggle(t, $event)"
            >
              <span class="consolidation-dialog__name">{{ t.displayName }}</span>
            </n-checkbox>
            <n-tag v-if="!t.hasChange" size="tiny" :bordered="false">无变化</n-tag>
            <n-tag v-else size="tiny" type="warning" :bordered="false">未总结 {{ t.uncoveredCount }}</n-tag>
            <n-tag v-if="t.autoEnabled" size="tiny" type="info" :bordered="false">自动</n-tag>
          </div>
          <!-- 二期 P4（FR-302）：项目 scope 通道选择——owner/admin 可选共享/压到自己；普通成员仅个人通道 -->
          <div
            v-if="t.scopeKind === 'PROJECT' && selected.has(keyOf(t))"
            class="consolidation-dialog__channel"
          >
            <n-radio-group
              v-if="t.canWriteShared"
              :value="channelOf(t)"
              size="small"
              @update:value="setChannel(t, $event)"
            >
              <n-radio-button value="shared">项目共享总结（全员可见）</n-radio-button>
              <n-radio-button value="personal">压到我自己的总结（仅我可见）</n-radio-button>
            </n-radio-group>
            <n-tag v-else size="tiny" :bordered="false">普通成员：压到我自己的总结（仅我可见）</n-tag>
          </div>
          <!-- I4-3 项目 scope 勾选后展开取数选人（人员范围/方向/离职开关） -->
          <MemoryConsolidationPeoplePicker
            v-if="t.scopeKind === 'PROJECT' && t.projectId != null && selected.has(keyOf(t))"
            :project-id="t.projectId"
            v-model="peopleCfgMap[keyOf(t)]"
          />
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
import { NButton, NCheckbox, NDatePicker, NEmpty, NModal, NRadioButton, NRadioGroup, NSelect, NSpace, NSpin, NTag, useMessage } from 'naive-ui'
import {
  memoryApi,
  type MemoryConsolidationTargetView,
  type MemoryConsolidationScopeRequest
} from '@/api/memory'
import MemoryConsolidationPeoplePicker from './MemoryConsolidationPeoplePicker.vue'

interface Props { show: boolean; mode?: 'instant' | 'resummarize' }
const props = withDefaults(defineProps<Props>(), { mode: 'instant' })
const emit = defineEmits<{
  (e: 'update:show', v: boolean): void
  (e: 'done'): void
}>()

const message = useMessage()

const targets = ref<MemoryConsolidationTargetView[]>([])
const loading = ref(false)
const triggering = ref(false)
const selected = ref(new Set<string>())
// I4-3：项目 scope 的取数配置（keyOf → { authorFilter, authorIds, direction, includeDeparted }）
const peopleCfgMap = ref<Record<string, any>>({})
// 二期 P4（FR-302）：项目 scope 的总结通道（keyOf → 'shared' | 'personal'，缺省按 canWriteShared）
const channelMap = ref<Record<string, 'shared' | 'personal'>>({})
// 二期 P3b：重新总结筛选（标签 + 时间范围），应用到所有勾选 scope
// 二期人工测试 Req3：标签筛选降到「主体:大类」第一层（避免子项过多）；topicKey → 该大类下所有 tagId 展开。
const tagOptions = ref<{ label: string; value: string }[]>([])
const topicToTagIds = ref<Record<string, number[]>>({})
const tagFilter = ref<string[]>([])
const dateRange = ref<[number, number] | null>(null)
// 二期 P3c：全局方向筛选（综合 / 仅输入 / 仅输出），透传给非项目 scope
const directionFilter = ref<'BOTH' | 'INPUT' | 'OUTPUT'>('BOTH')

// 二期人工测试 Req1：仅显示可总结 scope（个人 + 本人创始人项目）；非创始人项目隐去（仅查看/召回）
const visibleTargets = computed(() =>
  targets.value.filter(t => t.scopeKind !== 'PROJECT' || t.canSummarize !== false)
)

function channelOf(t: MemoryConsolidationTargetView): 'shared' | 'personal' {
  return channelMap.value[keyOf(t)] ?? (t.canWriteShared ? 'shared' : 'personal')
}

function setChannel(t: MemoryConsolidationTargetView, v: string | number | boolean) {
  channelMap.value = { ...channelMap.value, [keyOf(t)]: v === 'personal' ? 'personal' : 'shared' }
}

const selectedScopes = computed<MemoryConsolidationScopeRequest[]>(() =>
  visibleTargets.value
    .filter(t => selected.value.has(keyOf(t)))
    .map(t => {
      const base: MemoryConsolidationScopeRequest = { scopeKind: t.scopeKind, projectId: t.projectId ?? undefined }
      const k = keyOf(t)
      if (t.scopeKind === 'PROJECT') {
        // 二期 P4：个人通道 → toPersonal=true（普通成员唯一通道；owner/admin 选了「压到自己」同）
        base.toPersonal = channelOf(t) === 'personal'
      }
      const cfg = peopleCfgMap.value[k]
      if (t.scopeKind === 'PROJECT' && cfg) {
        base.authorFilter = cfg.authorFilter
        base.authorIds = cfg.authorIds
        base.direction = cfg.direction
        base.includeDeparted = cfg.includeDeparted
      }
      // P3b：全局标签/时间筛选应用到本 scope（Req3：tagFilter 是大类 topicKey，展开为该大类下全部 tagId）
      if (tagFilter.value.length) {
        const ids = new Set<number>()
        for (const key of tagFilter.value) {
          for (const id of (topicToTagIds.value[key] ?? [])) ids.add(id)
        }
        if (ids.size) base.tagIds = [...ids]
      }
      if (dateRange.value) {
        base.start = new Date(dateRange.value[0]).toISOString()
        base.end = new Date(dateRange.value[1]).toISOString()
      }
      // P3c：全局方向筛选——非项目 scope 透传（项目 scope 走 people-picker 自带 direction）
      if (t.scopeKind !== 'PROJECT' && directionFilter.value !== 'BOTH') {
        base.direction = directionFilter.value
      }
      // Req2：「重新总结」模式 force=true（后端跳过未覆盖幂等闸，强制重压）
      if (props.mode === 'resummarize') base.force = true
      return base
    })
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
    // 默认勾选：instant 模式预勾 hasChange+autoEnabled（自动总结延续）；resummarize 模式由用户显式选
    const s = new Set<string>()
    if (props.mode === 'instant') {
      for (const t of targets.value) {
        if (t.hasChange && t.autoEnabled && t.canSummarize !== false) s.add(keyOf(t))
      }
    }
    selected.value = s
    peopleCfgMap.value = {}
    channelMap.value = {}
    // P3b/P3c：重置筛选
    tagFilter.value = []
    dateRange.value = null
    directionFilter.value = 'BOTH'
    // P3b：载入本人标签做筛选候选（失败不阻塞）
    // Req3：选项降到「主体:大类」第一层去重，记录 topicKey→tagIds[] 供展开。
    try {
      const tg = await memoryApi.listTags()
      const map: Record<string, number[]> = {}
      const labels: Record<string, string> = {}
      for (const t of (tg.data?.data ?? [])) {
        const key = `${t.subject || '我'}|${t.topic}`
        ;(map[key] ||= []).push(t.id)
        labels[key] = `${t.subject || '我'} : ${t.topic}`
      }
      topicToTagIds.value = map
      tagOptions.value = Object.keys(map).map(k => ({ label: labels[k], value: k }))
    } catch {
      topicToTagIds.value = {}
      tagOptions.value = []
    }
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
    flex-direction: column;
    gap: 4px;
    padding: 6px 4px;
  }
  &__row-head {
    display: flex;
    align-items: center;
    gap: 8px;
  }
  &__name {
    font-size: 13px;
  }
  &__channel {
    padding-left: 24px;
  }
  &__filter {
    display: flex;
    flex-direction: column;
    gap: 8px;
    padding: 8px 4px 12px;
    margin-bottom: 4px;
    border-bottom: 1px solid rgba(255, 255, 255, 0.08);
  }
  &__filter-title {
    font-size: 12px;
    opacity: 0.65;
  }
  &__direction {
    display: flex;
    align-items: center;
    gap: 8px;
    flex-wrap: wrap;
  }
  &__filter-hint {
    font-size: 12px;
    opacity: 0.65;
  }
}
</style>
