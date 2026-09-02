<!--
  项目分类管理弹窗（plan §C1a/C1b 共用；修复XI XI-3 两级化）
  - C1a：叙事角色两级 Tab（一级增/重命名/删 + 每级下子类增删；删一级子随删资产归「通用」，
        仅删子类资产归父级，后端 reassignOnRemovedRoles 两级口径已就绪）
  - C1b：媒体类型 Tab（category+type 两层）
  父组件传 narrativeRoles（两级）+ roleAssetCounts，save 时整体覆盖（projectApi.update）。
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
      <!-- 叙事角色两级（C1a / 修复XI XI-3） -->
      <n-tab-pane name="roles" tab="叙事角色">
        <div class="vocab-editor__hint">
          叙事角色为两级：一级是大类（如「人物」），子类可细分（如「老人」）。删除一级时子类随删、其下资产归入「通用」；
          仅删子类时其资产归入父级。一级与子类共用一个命名空间，不可重名。
        </div>
        <div v-for="(role, i) in draftRoles" :key="i" class="vocab-editor__group">
          <div class="vocab-editor__row">
            <n-input
              v-model:value="role.key"
              size="small"
              placeholder="一级角色名（如 人物）"
              :maxlength="30"
              :status="rowError[i] ? 'error' : undefined"
              @blur="dedupeRow(i)"
            />
            <n-popconfirm @positive-click="removeRole(i)">
              <template #trigger>
                <n-button size="small" quaternary type="error" :disabled="draftRoles.length <= 1">
                  删除
                </n-button>
              </template>
              <span v-if="roleCount(i) > 0">
                {{ roleCount(i) }} 个资产将归入「通用」（子类随删）
              </span>
              <span v-else>确认删除该一级角色？（子类随删）</span>
            </n-popconfirm>
          </div>
          <div class="vocab-editor__children">
            <n-tag
              v-for="(c, j) in role.children"
              :key="j"
              size="small"
              closable
              @close="removeChild(i, j)"
            >
              {{ c }}
            </n-tag>
            <n-input
              v-model:value="childDrafts[i]"
              size="tiny"
              class="vocab-editor__child-add"
              placeholder="+ 子类名，回车添加"
              :maxlength="30"
              :status="childError[i] ? 'error' : undefined"
              @keydown.enter.prevent="addChild(i)"
              @blur="addChild(i)"
            />
          </div>
          <div v-if="rowError[i]" class="vocab-editor__child-error">{{ rowError[i] }}</div>
          <div v-if="childError[i]" class="vocab-editor__child-error">{{ childError[i] }}</div>
        </div>
        <n-button size="small" dashed block @click="addRole">
          + 新增一级角色
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
            placeholder="类型名（如 提示词）"
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
import { NButton, NInput, NModal, NPopconfirm, NSelect, NTabPane, NTabs, NTag } from 'naive-ui'
import type { MediaCategory, MediaTypeDef, NarrativeRoleVocab } from '@/types/asset'

const props = withDefaults(defineProps<{
  show: boolean
  /** 当前两级叙事角色词汇 {key,children}（开弹窗深拷贝为本地草稿，编辑不直改源）。 */
  narrativeRoles: NarrativeRoleVocab[]
  /** 当前媒体类型受控词汇（V60，{key,category}；开盖拷贝草稿）。 */
  mediaTypes: MediaTypeDef[]
  /** 每个角色 key（一级或子类）当前资产数（删级二次确认显迁移数；无键=0）。 */
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
  /** 保存：整体覆盖两级叙事角色 + 媒体类型（后端 normalize 两级 + reassign 兜底）。 */
  (e: 'save', payload: { roles: NarrativeRoleVocab[]; mediaTypes: MediaTypeDef[] }): void
}>()

/** 处理类别下拉选项（系统固定四类，V60）。 */
const categoryOptions: { label: string; value: MediaCategory }[] = [
  { label: '文本', value: 'TEXT' },
  { label: '图片', value: 'IMAGE' },
  { label: '视频', value: 'VIDEO' },
  { label: '音频', value: 'AUDIO' }
]

/** 一级角色草稿（子类随 draft.children 原位编辑）。 */
interface RoleDraft {
  key: string
  children: string[]
}

/** 本地草稿（增/改名/删都在此，保存时整体提交）；childDrafts/childError/rowError 与 draftRoles 按下标平行。 */
const draftRoles = ref<RoleDraft[]>([])
const childDrafts = ref<string[]>([])
const childError = ref<string[]>([])
/** P4 交叉 review 中④：一级名行内错误（撞名/空名不清空——清空会被 normalizedRoles 静默
 * 跳过整行含子类，保存即删桶+后端 reassign 资产归通用）。 */
const rowError = ref<string[]>([])
const draftMediaTypes = ref<MediaTypeDef[]>([])

// 弹窗每次打开 → 从 prop 拷贝最新（避免上次草稿残留）；immediate 兼容 mount 时即 show=true
watch(
  () => props.show,
  (open) => {
    if (open) {
      draftRoles.value = props.narrativeRoles.map((r) => ({
        key: r?.key ?? '',
        children: [...(r?.children ?? [])]
      }))
      childDrafts.value = draftRoles.value.map(() => '')
      childError.value = draftRoles.value.map(() => '')
      rowError.value = draftRoles.value.map(() => '')
      // 深拷贝 mediaTypes（避免直改源；补默认 category 兜底）
      draftMediaTypes.value = props.mediaTypes.map((t) => ({
        key: t.key,
        category: t.category ?? 'TEXT'
      }))
    }
  },
  { immediate: true }
)

/**
 * 全局重名判断（一级与所有子类同一命名空间，后端 normalize 同口径 400）：
 * - asChild=false（一级名失焦校验）：撞其他一级名或任意子类名 → 重名（自身不计）。
 * - asChild=true（新增子类校验）：撞任意一级名（含本组）或任意子类名（含同组兄弟）→ 重名。
 */
function isGlobalDup(v: string, selfIndex: number, asChild: boolean): boolean {
  return draftRoles.value.some((r, idx) => {
    if ((r?.key ?? '').trim() === v && (asChild || idx !== selfIndex)) return true
    return (r?.children ?? []).some((c) => (c ?? '').trim() === v)
  })
}

/** 一级名失焦：trim 写回；空名/与他处重名 → **保留输入+行内错误+禁存**（P4 交叉 review 中④：
 * 原回退为空会被 normalizedRoles 静默跳过整行含子类——保存即删桶+后端 reassign 资产归通用，
 * 两级化后爆炸半径从一桶扩到一桶带全部子类）。 */
function dedupeRow(i: number) {
  const row = draftRoles.value[i]
  if (!row) return
  const v = (row.key ?? '').trim()
  rowError.value[i] = ''
  if (!v) {
    rowError.value[i] = '一级角色名不能为空'
    return
  }
  row.key = v
  if (isGlobalDup(v, i, false)) rowError.value[i] = `「${v}」与已有一级或子类重名`
}

/** 新增子类（回车或失焦触发）：≤20 个、全局不重名，通过则入 children 并清输入。 */
function addChild(i: number) {
  const v = (childDrafts.value[i] ?? '').trim()
  childError.value[i] = ''
  if (!v) return
  const row = draftRoles.value[i]
  if (!row) return
  if (row.children.length >= 20) {
    childError.value[i] = '子类最多 20 个'
    return
  }
  if (isGlobalDup(v, i, true)) {
    childError.value[i] = `「${v}」与已有一级或子类重名`
    childDrafts.value[i] = ''
    return
  }
  row.children.push(v)
  childDrafts.value[i] = ''
}

function removeChild(i: number, j: number) {
  draftRoles.value[i]?.children.splice(j, 1)
}

function addRole() {
  draftRoles.value.push({ key: '', children: [] })
  childDrafts.value.push('')
  childError.value.push('')
  rowError.value.push('')
}

function removeRole(i: number) {
  draftRoles.value.splice(i, 1)
  childDrafts.value.splice(i, 1)
  childError.value.splice(i, 1)
  rowError.value.splice(i, 1)
}

function dedupeTypeKey(i: number) {
  const v = (draftMediaTypes.value[i]?.key ?? '').trim()
  if (!v) return
  draftMediaTypes.value[i].key = v
  const dup = draftMediaTypes.value.some((t, idx) => idx !== i && (t.key ?? '').trim() === v)
  if (dup) draftMediaTypes.value[i].key = ''
}

function removeMediaType(i: number) {
  draftMediaTypes.value.splice(i, 1)
}

function addMediaType() {
  draftMediaTypes.value.push({ key: '', category: 'TEXT' })
}

/** 删一级二次确认计数：一级本身 + 其全部子类的资产数合计（迁移口径=全组归「通用」）。 */
function roleCount(i: number): number {
  const row = draftRoles.value[i]
  if (!row) return 0
  let n = props.roleAssetCounts[(row.key ?? '').trim()] ?? 0
  for (const c of row.children) n += props.roleAssetCounts[(c ?? '').trim()] ?? 0
  return n
}

/** 角色归一化：trim + 去空 + 全局去重保序（一级与子类同一命名空间，静默去重；后端再兜底 400）。 */
const normalizedRoles = computed<NarrativeRoleVocab[]>(() => {
  const seen = new Set<string>()
  const out: NarrativeRoleVocab[] = []
  for (const r of draftRoles.value) {
    const key = (r?.key ?? '').trim()
    if (!key || seen.has(key)) continue
    seen.add(key)
    const children: string[] = []
    for (const c of r?.children ?? []) {
      const v = (c ?? '').trim()
      if (!v || seen.has(v)) continue
      seen.add(v)
      children.push(v)
    }
    out.push({ key, children })
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
  () => normalizedRoles.value.length >= 1
    && normalizedMediaTypes.value.length >= 1
    // P4 交叉 review 中④：一级名有行内错误（空/撞名）禁存——防静默删桶
    && rowError.value.every((e) => !e)
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

.vocab-editor__group {
  padding: var(--spacing-2);
  border: 1px solid var(--color-border, rgba(128, 128, 128, 0.3));
  border-radius: 4px;
  margin-bottom: var(--spacing-2);
}

.vocab-editor__children {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--spacing-1);
  margin-left: calc(var(--spacing-2) + 2px);
}

.vocab-editor__child-add {
  width: 170px;
}

.vocab-editor__child-error {
  font-size: var(--font-size-xs);
  color: var(--color-error, #e5484d);
  margin: var(--spacing-1) 0 0 calc(var(--spacing-2) + 2px);
}

.vocab-editor__placeholder {
  padding: var(--spacing-4);
  text-align: center;
  color: var(--color-text-tertiary);
  font-size: var(--font-size-sm);
}
</style>
