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

      <!-- 媒体类型（C1b 占位） -->
      <n-tab-pane name="mediaTypes" tab="媒体类型" disabled>
        <div class="vocab-editor__placeholder">媒体类型自定义（category + type 两层）即将支持，见 C1b。</div>
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
import { NButton, NInput, NModal, NPopconfirm, NTabPane, NTabs } from 'naive-ui'

const props = withDefaults(defineProps<{
  show: boolean
  /** 当前叙事角色桶（开弹窗时拷贝为本地草稿，编辑不直改源）。 */
  narrativeRoles: string[]
  /** 每个角色当前资产数（删桶二次确认显迁移数；无键=0）。 */
  roleAssetCounts?: Record<string, number>
  saving?: boolean
}>(), {
  roleAssetCounts: () => ({}),
  saving: false
})

const emit = defineEmits<{
  (e: 'update:show', v: boolean): void
  (e: 'save', roles: string[]): void
}>()

/** 本地草稿（增/改名/删都在此，保存时整体提交）。 */
const draftRoles = ref<string[]>([])

// 弹窗每次打开 → 从 prop 拷贝最新角色（避免上次草稿残留）；immediate 兼容 mount 时即 show=true
watch(
  () => props.show,
  (open) => {
    if (open) draftRoles.value = [...props.narrativeRoles]
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

function removeRole(i: number) {
  draftRoles.value.splice(i, 1)
}

/** 归一化：trim + 去空 + 去重（保序）；空列表禁存（后端 normalize 兜底非空）。 */
const normalized = computed(() => {
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

const canSave = computed(() => normalized.value.length >= 1)

function onSave() {
  emit('save', normalized.value)
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
