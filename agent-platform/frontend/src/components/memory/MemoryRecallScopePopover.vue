<!-- ============================================================
  召回 scope 编辑器（计划12 F-6）— ChatView 底栏 popover
  · 走 memoryApi.getRecallScope / putRecallScope（新栈 /recall/scope）
  · 字段：personalOn / projectIds / direction / relativeDays / includeDeparted
  · 双栈期：legacy 读控件（chatStore.memIncludeGlobal/memReadProjectIds）仍驱动
    旧 user_memories chat 召回，H 收尾 DROP 旧表后移除；本组件管新栈 scope 偏好，
    供新栈 recall preview + 未来 chat 切新栈使用。
  · 改动即时 PUT（debounce 400ms），跨会话沿用。
  ============================================================ -->
<template>
  <n-popover trigger="click" placement="top" :width="300" :show-arrow="true">
    <template #trigger>
      <n-button size="small" quaternary :type="dirty ? 'primary' : 'default'" title="召回范围：新栈记忆读取范围（个人/项目/方向/时间窗/离职）">
        <template #icon>
          <n-icon :component="FilterOutline" />
        </template>
        召回范围
      </n-button>
    </template>

    <div class="recall-scope">
      <div class="recall-scope__title">新栈召回范围</div>
      <div v-if="loading" class="recall-scope__loading"><n-spin size="small" /></div>

      <template v-else>
        <div class="recall-scope__row">
          <span class="recall-scope__label">个人记忆</span>
          <n-switch :value="form.personalOn" @update:value="(v: boolean) => set('personalOn', v)" />
        </div>

        <div class="recall-scope__row recall-scope__row--col">
          <span class="recall-scope__label">项目记忆</span>
          <n-select
            :value="form.projectIds"
            multiple
            :options="projectOptions"
            placeholder="选项目"
            size="small"
            :consistent-menu-width="false"
            max-tag-count="responsive"
            @update:value="(v: number[]) => set('projectIds', v)"
          />
        </div>

        <div class="recall-scope__row">
          <span class="recall-scope__label">方向</span>
          <n-select
            :value="form.direction"
            :options="directionOptions"
            size="small"
            style="width: 120px"
            :consistent-menu-width="false"
            @update:value="(v) => set('direction', v)"
          />
        </div>

        <div class="recall-scope__row">
          <span class="recall-scope__label">时间窗</span>
          <n-select
            :value="relativeDaysKey"
            :options="timeWindowOptions"
            size="small"
            style="width: 140px"
            :consistent-menu-width="false"
            @update:value="onSelectTimeWindow"
          />
        </div>

        <div class="recall-scope__row">
          <n-tooltip placement="top">
            <template #trigger>
              <span class="recall-scope__label recall-scope__label--hint">已离开人员</span>
            </template>
            关闭后召回不包含已离开项目成员的记忆（开关优先级高于项目勾选）
          </n-tooltip>
          <n-switch :value="form.includeDeparted" @update:value="(v: boolean) => set('includeDeparted', v)" />
        </div>

        <div class="recall-scope__footer">
          <n-tag v-if="dirty" size="tiny" type="warning" :bordered="false">保存中…</n-tag>
          <n-tag v-else-if="saved" size="tiny" type="success" :bordered="false">已保存</n-tag>
          <n-button size="tiny" quaternary @click="resetDefaults">恢复默认</n-button>
        </div>
      </template>
    </div>
  </n-popover>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { NButton, NIcon, NPopover, NSelect, NSpin, NSwitch, NTag, NTooltip } from 'naive-ui'
import { FilterOutline } from '@vicons/ionicons5'
import { memoryApi, type MemoryRecallScopeRequest, type MemoryRecallScopeView } from '@/api/memory'

type Direction = 'INPUT' | 'OUTPUT' | 'BOTH' | null

interface ScopeForm {
  personalOn: boolean
  projectIds: number[]
  direction: Direction
  relativeDays: number | null
  includeDeparted: boolean
}

const DEFAULT_FORM: ScopeForm = {
  personalOn: true,
  projectIds: [],
  direction: 'BOTH',
  relativeDays: null,
  includeDeparted: true
}

const form = ref<ScopeForm>({ ...DEFAULT_FORM })
const availableProjects = ref<{ projectId: number; name: string; viaGrant?: boolean }[]>([])
const loading = ref(true)
const dirty = ref(false)
const saved = ref(false)

const projectOptions = computed(() =>
  availableProjects.value.map(p => ({
    // P1：经个人授权获得读权的项目标注「（授权）」，与成员项目区分
    label: p.viaGrant ? `${p.name}（授权）` : p.name,
    value: p.projectId
  }))
)

const directionOptions = [
  { label: '全部', value: 'BOTH' },
  { label: '仅输入', value: 'INPUT' },
  { label: '仅输出', value: 'OUTPUT' }
]

const timeWindowOptions = [
  { label: '全部历史', value: 'all' },
  { label: '近 7 天', value: '7' },
  { label: '近 30 天', value: '30' },
  { label: '近 90 天', value: '90' }
]

// relativeDays(null=全部) ↔ select key
const relativeDaysKey = computed(() => {
  const d = form.value.relativeDays
  if (d == null) return 'all'
  return String(d)
})
function onSelectTimeWindow(key: string) {
  const d = key === 'all' ? null : Number(key)
  set('relativeDays', d)
}

let saveTimer: ReturnType<typeof setTimeout> | null = null

function set<K extends keyof ScopeForm>(key: K, value: ScopeForm[K]) {
  form.value = { ...form.value, [key]: value }
  scheduleSave()
}

function scheduleSave() {
  dirty.value = true
  saved.value = false
  if (saveTimer) clearTimeout(saveTimer)
  saveTimer = setTimeout(persist, 400)
}

async function persist() {
  const payload: MemoryRecallScopeRequest = {
    personalOn: form.value.personalOn,
    projectIds: form.value.projectIds,
    direction: form.value.direction,
    relativeDays: form.value.relativeDays,
    includeDeparted: form.value.includeDeparted
  }
  try {
    const res = await memoryApi.putRecallScope(payload)
    const v: MemoryRecallScopeView | undefined = res.data?.data
    if (v) {
      form.value = {
        personalOn: v.personalOn,
        projectIds: [...(v.projectIds ?? [])],
        direction: v.direction ?? 'BOTH',
        relativeDays: v.relativeDays ?? null,
        includeDeparted: v.includeDeparted
      }
    }
    dirty.value = false
    saved.value = true
    setTimeout(() => { saved.value = false }, 1500)
  } catch {
    // 失败保留本地态，不弹错（底栏控件低调）；下次改动重试
    dirty.value = false
  }
}

function resetDefaults() {
  form.value = { ...DEFAULT_FORM, projectIds: [] }
  scheduleSave()
}

function applyView(v: MemoryRecallScopeView) {
  form.value = {
    personalOn: v.personalOn,
    projectIds: [...(v.projectIds ?? [])],
    direction: v.direction ?? 'BOTH',
    relativeDays: v.relativeDays ?? null,
    includeDeparted: v.includeDeparted
  }
  availableProjects.value = v.availableProjects ?? []
}

async function load() {
  loading.value = true
  try {
    const res = await memoryApi.getRecallScope()
    if (res.data?.data) applyView(res.data.data)
  } catch {
    // 静默：底栏控件不该因接口失败阻塞聊天
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<style lang="scss" scoped>
.recall-scope {
  &__title {
    font-size: 13px;
    font-weight: 600;
    margin-bottom: 10px;
  }
  &__loading {
    display: flex;
    justify-content: center;
    padding: 12px 0;
  }
  &__row {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 8px;
    padding: 6px 0;

    &--col {
      flex-direction: column;
      align-items: stretch;
      gap: 4px;
    }
  }
  &__label {
    font-size: 12px;
    color: var(--color-text-secondary);
    white-space: nowrap;

    &--hint {
      cursor: help;
      border-bottom: 1px dashed var(--color-text-tertiary);
    }
  }
  &__footer {
    display: flex;
    align-items: center;
    gap: 8px;
    margin-top: 8px;
    padding-top: 8px;
    border-top: 1px solid var(--color-border-light);
  }
}
</style>
