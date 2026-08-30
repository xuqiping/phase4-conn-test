<!--
  分镜字段编辑（S18，5 字段流水线，1_8.6计划第 11 点）
  - 字段1 prompt：可编辑 textarea（saveStoryboard 写 content.prompt）
  - 字段2 entityRefs：实体→@资产键值对（NSelect 筛 IMAGE 类资产；LLM 拆镜首轮自动匹配，用户可改）
  - 字段3 imageGen：占位 disabled（待图模型 R-3 接入）
  - 字段4 videoInputs：audioRefs/imageRefs 两组 kv（同 entityRefs 结构，NSelect 筛 音频/图片）
  - 字段5 videoGen：占位 disabled（待图模型接入）
  - 被引资产后删/失权 → assetId 取不到 → 降级显「资产已删」红标（L16）
  - 统一「保存」一次性提交 prompt+entityRefs+videoInputs → assetApi.saveStoryboard 产新版本
-->
<template>
  <div class="storyboard-fields">
    <div class="storyboard-fields__head">
      <n-tag v-if="content.shotIndex != null" size="small" type="info">镜头 #{{ content.shotIndex }}</n-tag>
      <n-button v-if="content.parentId" text type="primary" size="small" @click="emit('open-parent', content.parentId!)">
        源剧本 →
      </n-button>
    </div>

    <!-- 字段1 提示词 -->
    <div class="storyboard-fields__field">
      <label class="storyboard-fields__label">① 镜头提示词</label>
      <n-input
        v-model:value="prompt"
        type="textarea"
        :rows="4"
        :maxlength="8000"
        :readonly="!canEdit"
        placeholder="本镜头的提示词描述（景别/动作/氛围）"
      />
    </div>

    <!-- 字段2 实体→资产 -->
    <div class="storyboard-fields__field">
      <label class="storyboard-fields__label">② 实体 → 资产</label>
      <p class="storyboard-fields__hint">人物/道具/场景图片资产，LLM 拆镜首轮自动匹配，可手动调整。</p>
      <ref-list
        v-model:refs="entityRefs"
        :options="imageOptions"
        :option-map="imageOptionMap"
        :can-edit="canEdit"
        key-placeholder="实体（如 主角、道具·剑）"
      />
    </div>

    <!-- 字段3 批量生图（占位） -->
    <div class="storyboard-fields__field">
      <label class="storyboard-fields__label">③ 批量生图</label>
      <div class="storyboard-fields__placeholder">
        <n-button size="small" disabled>批量生图</n-button>
        <n-tooltip><template #trigger><span class="storyboard-fields__hint">ⓘ 待图模型接入</span></template>字段 3 生图阻塞于图片模型 provider（R-3），本轮仅占位。</n-tooltip>
      </div>
    </div>

    <!-- 字段4 生视频输入 -->
    <div class="storyboard-fields__field">
      <label class="storyboard-fields__label">④ 生视频输入</label>
      <div class="storyboard-fields__sub">音频参考</div>
      <ref-list
        v-model:refs="audioRefs"
        :options="audioOptions"
        :option-map="audioOptionMap"
        :can-edit="canEdit"
        key-placeholder="音频实体（如 背景音乐）"
      />
      <div class="storyboard-fields__sub">图片参考</div>
      <ref-list
        v-model:refs="imageRefs"
        :options="imageOptions"
        :option-map="imageOptionMap"
        :can-edit="canEdit"
        key-placeholder="图片实体（如 关键帧参考）"
      />
    </div>

    <!-- 字段5 生成视频（占位） -->
    <div class="storyboard-fields__field">
      <label class="storyboard-fields__label">⑤ 生成视频</label>
      <div class="storyboard-fields__placeholder">
        <n-button size="small" disabled>生成视频</n-button>
        <n-tooltip><template #trigger><span class="storyboard-fields__hint">ⓘ 待字段 3 图就绪</span></template>字段 5 生视频依赖字段 3 生成图，本轮仅占位。</n-tooltip>
      </div>
    </div>

    <div v-if="canEdit" class="storyboard-fields__save-row">
      <n-button type="primary" size="small" :loading="saving" :disabled="!dirty" @click="saveAll">保存分镜</n-button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { NButton, NInput, NTag, NTooltip, useMessage } from 'naive-ui'
import type { SelectOption } from 'naive-ui'
import { assetApi } from '@/api/assets'
import RefList from '@/components/asset/StoryboardRefList.vue'
import type { AssetVO, StoryboardContent, StoryboardEntityRef } from '@/types/asset'

const props = defineProps<{
  asset: AssetVO
  canEdit?: boolean
}>()
const emit = defineEmits<{
  (e: 'changed', assetId: number): void
  (e: 'open-parent', parentId: number): void
}>()

const message = useMessage()

/** 解析分镜 content → 结构化可编辑副本。 */
const content = computed<StoryboardContent>(() => {
  try {
    return props.asset.content ? JSON.parse(props.asset.content) as StoryboardContent : {}
  } catch {
    return {}
  }
})

const prompt = ref('')
const entityRefs = ref<StoryboardEntityRef[]>([])
const audioRefs = ref<StoryboardEntityRef[]>([])
const imageRefs = ref<StoryboardEntityRef[]>([])
const saving = ref(false)

/** 原始快照（dirty 检测）。 */
const original = ref({ prompt: '', entityRefs: '[]', audioRefs: '[]', imageRefs: '[]' })

function snapshotRefs(rs: StoryboardEntityRef[]): string {
  return JSON.stringify(rs ?? [])
}

/** 从 content 初始化编辑副本（asset 变化/保存后重载时）。 */
function syncFromContent() {
  const c = content.value
  prompt.value = c.prompt ?? ''
  entityRefs.value = cloneRefs(c.entityRefs)
  audioRefs.value = cloneRefs(c.videoInputs?.audioRefs)
  imageRefs.value = cloneRefs(c.videoInputs?.imageRefs)
  original.value = {
    prompt: prompt.value,
    entityRefs: snapshotRefs(entityRefs.value),
    audioRefs: snapshotRefs(audioRefs.value),
    imageRefs: snapshotRefs(imageRefs.value)
  }
}

function cloneRefs(rs?: StoryboardEntityRef[]): StoryboardEntityRef[] {
  return (rs ?? []).map((r) => ({ ...r }))
}

const dirty = computed(() =>
  prompt.value !== original.value.prompt ||
  snapshotRefs(entityRefs.value) !== original.value.entityRefs ||
  snapshotRefs(audioRefs.value) !== original.value.audioRefs ||
  snapshotRefs(imageRefs.value) !== original.value.imageRefs
)

// ---------- NSelect 候选（项目内资产，按 category 筛） ----------
const projectAssets = ref<AssetVO[]>([])

async function loadProjectAssets() {
  try {
    const res = await assetApi.list(props.asset.projectId, { size: 200 })
    projectAssets.value = res.data.data.records ?? []
  } catch {
    projectAssets.value = []
  }
}

const imageOptions = computed<SelectOption[]>(() =>
  projectAssets.value
    .filter((a) => a.mediaCategory === 'IMAGE')
    .map((a) => ({ label: `${a.name}（${a.mediaType}）`, value: a.id }))
)
const audioOptions = computed<SelectOption[]>(() =>
  projectAssets.value
    .filter((a) => a.mediaCategory === 'AUDIO')
    .map((a) => ({ label: `${a.name}（${a.mediaType}）`, value: a.id }))
)
const imageOptionMap = computed(() => new Map(projectAssets.value.filter((a) => a.mediaCategory === 'IMAGE').map((a) => [a.id, a])))
const audioOptionMap = computed(() => new Map(projectAssets.value.filter((a) => a.mediaCategory === 'AUDIO').map((a) => [a.id, a])))

watch(
  () => props.asset.id,
  () => {
    syncFromContent()
    loadProjectAssets()
  },
  { immediate: true }
)
// content 变化（保存后父重载）也重同步
watch(() => props.asset.content, () => syncFromContent())

/** 一次性提交字段 1/2/4 → saveStoryboard 产新版本。 */
async function saveAll() {
  if (!props.canEdit || !dirty.value) return
  saving.value = true
  try {
    await assetApi.saveStoryboard(props.asset.id, {
      prompt: prompt.value,
      entityRefs: entityRefs.value,
      videoInputs: { audioRefs: audioRefs.value, imageRefs: imageRefs.value }
    })
    message.success('分镜已保存')
    emit('changed', props.asset.id)
  } catch {
    message.error('保存分镜失败')
  } finally {
    saving.value = false
  }
}

defineExpose({ prompt, entityRefs, audioRefs, imageRefs, dirty, saveAll, syncFromContent })
</script>

<style lang="scss" scoped>
.storyboard-fields {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-3);
  text-align: left;

  &__head {
    display: flex;
    align-items: center;
    gap: var(--spacing-2);
  }

  &__field {
    display: flex;
    flex-direction: column;
    gap: var(--spacing-2);
  }

  &__label {
    font-size: var(--font-size-sm);
    font-weight: var(--font-weight-bold);
    color: var(--color-text-primary);
  }

  &__sub {
    font-size: var(--font-size-xs);
    color: var(--color-text-tertiary);
    margin-top: var(--spacing-1);
  }

  &__hint {
    font-size: var(--font-size-xs);
    color: var(--color-text-tertiary);
    margin: 0;
  }

  &__placeholder {
    display: flex;
    align-items: center;
    gap: var(--spacing-2);
  }

  &__save-row {
    display: flex;
    justify-content: flex-end;
  }
}
</style>
