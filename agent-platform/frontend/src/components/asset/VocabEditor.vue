<!--
  项目分类管理弹窗（plan §C1a/C1b 共用）
  - C1a：叙事角色桶 Tab（增/重命名/删；删联动归通用，后端 reassignOnRemovedRoles L185 已就绪）
  - C1b：媒体类型 Tab（category+type 两层）占位，后续接入
  父组件传 narrativeRoles + roleAssetCounts，save 时整体覆盖（projectApi.update）。
-->
<template>
  <n-modal
    :show="show"
    preset="card"
    title="分类管理"
    style="max-width: 520px"
    @update:show="(v: boolean) => emit('update:show', v)"
  >
    <n-tabs type="line" animated>
      <!-- 叙事角色桶（C1a） -->
      <n-tab-pane name="roles" tab="叙事角色">
        <div class="vocab-editor__hint">
          叙事角色用于矩阵左栏分组资产（如「人物」「场景」）。删除角色时，其下资产自动归入「通用」。
        </div>
        <div v-for="(role, i) in draftRoles" :key="i" class="vocab-editor__row">
          <n-input
            v-model:value="draftRoles[i]"
            size="small"
            placeholder="角色名（如 人物）"
            :maxlength="30"
            @blur="dedupeRow(i)"
          />
          <n-popconfirm @positive-click="removeRole(i)">
            <template #trigger>
              <n-button size="small" quaternary type="error" :disabled="draftRoles.length <= 1">
                删除
              </n-button>
            </template>
            <span v-if="roleAssetCounts[role?.trim() ?? '']">
              {{ roleAssetCounts[role.trim()] }} 个资产将归入「通用」
            </span>
            <span v-else>确认删除该角色？</span>
          </n-popconfirm>
        </div>
        <n-button size="small" dashed block @click="draftRoles.push('')">
          + 新增角色
        </n-button>
      </n-tab-pane>

      <!-- 媒体类型（C1b：category + type 两层） -->
      <n-tab-pane name="mediaTypes" tab="媒体类型">
        <div class="vocab-editor__hint">
          媒体类型用于矩阵顶栏分组资产。<b>处理类别</b>（文本/图片/视频/音频）决定编辑器与上传链路；
          <b>类型名</b>可自定义（如「地图」归图片类）。删除类型时，其下资产自动迁到同类别首个保留类型。
        </div>
        <div v-for="(mt, i) in draftMediaTypes" :key="i" class="vocab-editor__row">
          <n-input
            v-model:value="mt.key"
            size="small"
            placeholder="类型名（如 PROMPT）"
            :maxlength="32"
            style="flex: 1.4"
            @blur="dedupeTypeKey(i)"
          />
          <n-select
            v-model:value="mt.category"
            size="small"
            :options="categoryOptions"
            style="width: 110px"
          />
          <n-popconfirm @positive-click="removeMediaType(i)">
            <template #trigger>
              <n-button size="small" quaternary type="error" :disabled="draftMediaTypes.length <= 1">
                删除
              </n-button>
            </template>
            <span v-if="mediaTypeAssetCounts[mt.key?.trim() ?? '']">
              {{ mediaTypeAssetCounts[mt.key.trim()] }} 个资产将迁到同类别首个保留类型
            </span>
            <span v-else>确认删除该媒体类型？</span>
          </n-popconfirm>
        </div>
        <n-button size="small" dashed block @click="addMediaType">
          + 新增媒体类型
        </n-button>
      </n-tab-pane>
    </n-tabs>

    <template #action>
      <n-button @click="emit('update:show', false)">取消</n-button>
      <n-button type="primary" :loading="saving" :disabled="!canSave" @click="onSave">保存</n-button>
    </template>
  </n-modal>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { NButton, NInput, NModal, NPopconfirm, NSelect, NTabPane, NTabs } from 'naive-ui'
import type { MediaCategory, MediaTypeDef } from '@/types/asset'

const props = withDefaults(defineProps<{
  show: boolean
  /** 当前叙事角色桶（开弹窗时拷贝为本地草稿，编辑不直改源）。 */
  narrativeRoles: string[]
  /** 当前媒体类型受控词汇（V60，{key,category}；开盖拷贝草稿）。 */
  mediaTypes: MediaTypeDef[]
  /** 每个角色当前资产数（删桶二次确认显迁移数；无键=0）。 */
  roleAssetCounts?: Record<string, number>
  /** 每个媒体类型当前资产数（删 type 二次确认显迁移数）。 */
  mediaTypeAssetCounts?: Record<string, number>
  saving?: boolean
}>(), {
  roleAssetCounts: () => ({}),
  mediaTypeAssetCounts: () => ({}),
  saving: false
})

const emit = defineEmits<{
  (e: 'update:show', v: boolean): void
  /** 保存：整体覆盖叙事角色 + 媒体类型（后端 normalize + reassign 兜底）。 */
  (e: 'save', payload: { roles: string[]; mediaTypes: MediaTypeDef[] }): void
}>()

/** 处理类别下拉选项（系统固定四类，V60）。 */
const categoryOptions: { label: string; value: MediaCategory }[] = [
  { label: '文本', value: 'TEXT' },
  { label: '图片', value: 'IMAGE' },
  { label: '视频', value: 'VIDEO' },
  { label: '音频', value: 'AUDIO' }
]

/** 本地草稿（增/改名/删都在此，保存时整体提交）。 */
const draftRoles = ref<string[]>([])
const draftMediaTypes = ref<MediaTypeDef[]>([])

// 弹窗每次打开 → 从 prop 拷贝最新（避免上次草稿残留）；immediate 兼容 mount 时即 show=true
watch(
  () => props.show,
  (open) => {
    if (open) {
      draftRoles.value = [...props.narrativeRoles]
      // 深拷贝 mediaTypes（避免直改源；补默认 category 兜底）
      draftMediaTypes.value = props.mediaTypes.map((t) => ({
        key: t.key,
        category: t.category ?? 'TEXT'
      }))
    }
  },
  { immediate: true }
)

/** 失焦去重：若本行 trim 后与他行重复，回退为空让用户改。 */
function dedupeRow(i: number) {
  const v = (draftRoles.value[i] ?? '').trim()
  if (!v) return
  const dup = draftRoles.value.some((r, idx) => idx !== i && (r ?? '').trim() === v)
  if (dup) draftRoles.value[i] = ''
}

function dedupeTypeKey(i: number) {
  const v = (draftMediaTypes.value[i]?.key ?? '').trim()
  if (!v) return
  draftMediaTypes.value[i].key = v
  const dup = draftMediaTypes.value.some((t, idx) => idx !== i && (t.key ?? '').trim() === v)
  if (dup) draftMediaTypes.value[i].key = ''
}

function removeRole(i: number) {
  draftRoles.value.splice(i, 1)
}

function removeMediaType(i: number) {
  draftMediaTypes.value.splice(i, 1)
}

function addMediaType() {
  draftMediaTypes.value.push({ key: '', category: 'TEXT' })
}

/** 角色归一化：trim + 去空 + 去重（保序）。 */
const normalizedRoles = computed(() => {
  const seen = new Set<string>()
  const out: string[] = []
  for (const r of draftRoles.value) {
    const v = (r ?? '').trim()
    if (!v || seen.has(v)) continue
    seen.add(v)
    out.push(v)
  }
  return out
})

/** 媒体类型归一化：trim key + 类别合法 + 去重（保序）。 */
const normalizedMediaTypes = computed<MediaTypeDef[]>(() => {
  const seen = new Set<string>()
  const out: MediaTypeDef[] = []
  const validCats = new Set<MediaCategory>(['TEXT', 'IMAGE', 'VIDEO', 'AUDIO'])
  for (const t of draftMediaTypes.value) {
    const key = (t?.key ?? '').trim()
    const category = (t?.category ?? 'TEXT') as MediaCategory
    if (!key || seen.has(key) || !validCats.has(category)) continue
    seen.add(key)
    out.push({ key, category })
  }
  return out
})

const canSave = computed(
  () => normalizedRoles.value.length >= 1 && normalizedMediaTypes.value.length >= 1
)

function onSave() {
  emit('save', { roles: normalizedRoles.value, mediaTypes: normalizedMediaTypes.value })
}
</script>

<style lang="scss" scoped>
.vocab-editor__hint {
  font-size: var(--font-size-xs);
  color: var(--color-text-tertiary);
  margin-bottom: var(--spacing-2);
  line-height: 1.5;
}

.vocab-editor__row {
  display: flex;
  align-items: center;
  gap: var(--spacing-2);
  margin-bottom: var(--spacing-2);
}

.vocab-editor__placeholder {
  padding: var(--spacing-4);
  text-align: center;
  color: var(--color-text-tertiary);
  font-size: var(--font-size-sm);
}
</style>
