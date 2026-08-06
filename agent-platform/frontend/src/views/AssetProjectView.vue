<!--
  项目资产库·项目详情页（/assets/:id）  plan §S11
  - 顶 Tab 类型 × 左栏角色矩阵筛选 + 计数徽章（assetApi.countMatrix）+ 搜索
  - 资产卡片网格（AssetCard）+ 空态双引导（上传文件/新建文本）
  - 上传文件（按 mime 推断 IMAGE/VIDEO/AUDIO）+ 新建提示词/剧本弹窗
  - 内嵌 S10 AssetDetailDrawer（canEdit 按项目角色：viewer 只读）
  - 定稿/归档/一致性包保存 → drawer changed → 重载列表+矩阵（L2/L3 联动）
  - 权限三重兜底：菜单隐藏（Sidebar）+ 页内 canEdit（asset:write）+ API 403
-->
<template>
  <div class="asset-project">
    <!-- 顶栏：返回 + 项目名 + 计数 + 操作 -->
    <div class="asset-project__header">
      <div class="asset-project__title-row">
        <n-button quaternary size="small" @click="router.push('/assets')">← 返回</n-button>
        <h2 class="asset-project__title">{{ project?.name || '项目资产' }}</h2>
        <n-tag v-if="project" size="small" :type="ROLE_TYPE[project.role]" bordered>
          {{ ROLE_LABEL[project.role] }}
        </n-tag>
        <span class="asset-project__count">{{ total }} 个资产</span>
        <div class="asset-project__spacer" />
        <template v-if="canWrite">
          <n-button quaternary @click="showVocab = true">编辑分类</n-button>
          <n-button @click="triggerUpload">上传文件</n-button>
          <n-button type="primary" @click="openCreate">+ 新建提示词/剧本</n-button>
          <input
            ref="fileInput"
            type="file"
            accept="image/*,video/*,audio/*"
            hidden
            @change="onFileChange"
          />
        </template>
      </div>
      <span v-if="project?.description" class="asset-project__sub">{{ project.description }}</span>
    </div>

    <!-- 无全局 asset:write：直访兜底 -->
    <n-empty
      v-if="!canEdit"
      description="无 asset:write 权限，请联系管理员授权"
      class="asset-project__forbidden"
    />

    <template v-else-if="project">
      <AssetMatrixFilter
        v-model="filter"
        :counts="matrix"
        :roles="project.narrativeRoles ?? []"
        :media-types="project.mediaTypes ?? []"
      >
        <!-- 加载 -->
        <div v-if="loading" class="asset-project__loading"><n-spin size="large" /></div>

        <!-- 卡片网格 -->
        <div v-else-if="assets.length" class="asset-project__grid">
          <AssetCard v-for="a in assets" :key="a.id" :asset="a" @open="openDetail" />
        </div>

        <!-- 空态：双引导 -->
        <n-empty v-else-if="isFiltered" description="无匹配资产" class="asset-project__empty">
          <template #extra>
            <n-button size="small" @click="clearFilter">清除筛选</n-button>
          </template>
        </n-empty>
        <n-empty v-else class="asset-project__empty" description="项目暂无资产">
          <template v-if="canWrite" #extra>
            <n-button size="small" @click="triggerUpload">上传文件</n-button>
            <n-button size="small" type="primary" @click="openCreate">新建提示词/剧本</n-button>
          </template>
        </n-empty>
      </AssetMatrixFilter>

      <!-- 分页 -->
      <n-pagination
        v-if="total > pageSize"
        v-model:page="page"
        :page-count="pageCount"
        :page-size="pageSize"
        size="small"
        class="asset-project__pagination"
        @update:page="loadAssets"
      />
    </template>

    <!-- 新建文本资产弹窗 -->
    <n-modal v-model:show="showCreate" preset="card" title="新建提示词/剧本" style="max-width:560px">
      <n-form ref="formRef" :model="form" :rules="rules" label-placement="top">
        <n-form-item label="类型" path="mediaType">
          <n-select
            v-model:value="form.mediaType"
            :options="textTypeOptions"
            placeholder="选择文本类型"
          />
        </n-form-item>
        <n-form-item label="名称" path="name">
          <n-input v-model:value="form.name" placeholder="资产名称" :maxlength="100" show-count />
        </n-form-item>
        <n-form-item label="叙事角色">
          <n-select
            v-model:value="form.roleKeys"
            multiple
            :options="roleOptions"
            placeholder="可多选，留空=通用"
          />
        </n-form-item>
        <n-form-item label="描述">
          <n-input v-model:value="form.description" type="textarea" :rows="2" :maxlength="500" />
        </n-form-item>
        <n-form-item label="正文" path="content">
          <n-input
            v-model:value="form.content"
            type="textarea"
            :rows="6"
            :maxlength="8000"
            placeholder="提示词/剧本正文（≤8000）"
          />
        </n-form-item>
      </n-form>
      <template #action>
        <n-button @click="showCreate = false">取消</n-button>
        <n-button type="primary" :loading="saving" @click="submitCreate">创建</n-button>
      </template>
    </n-modal>

    <!-- 资产详情抽屉（S10） -->
    <AssetDetailDrawer
      v-model:show="showDetail"
      :asset-id="detailAssetId"
      :can-edit="canWrite"
      @changed="onDetailChanged"
    />

    <!-- C1a/C1b 分类管理（叙事角色桶 + 媒体类型两层，删联动迁移） -->
    <VocabEditor
      v-model:show="showVocab"
      :narrative-roles="project?.narrativeRoles ?? []"
      :media-types="project?.mediaTypes ?? []"
      :role-asset-counts="roleAssetCounts"
      :media-type-asset-counts="mediaTypeAssetCounts"
      :saving="savingVocab"
      @save="onSaveVocab"
    />

    <!-- C1b 上传类型选择（某处理类别下有多个媒体类型时，让用户选具体 type） -->
    <n-modal v-model:show="showUploadTypePicker" preset="card" title="选择媒体类型" style="max-width:420px">
      <p class="asset-project__picker-hint">
        该文件属于「{{ pendingUploadCategoryLabel }}」类别，请选择具体的媒体类型：
      </p>
      <n-select v-model:value="pendingUploadType" :options="uploadTypeOptions" placeholder="选择类型" />
      <template #action>
        <n-button @click="cancelUploadPick">取消</n-button>
        <n-button type="primary" :disabled="!pendingUploadType" @click="confirmUploadPick">上传</n-button>
      </template>
    </n-modal>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch, onMounted } from 'vue'
import {
  NButton,
  NEmpty,
  NForm,
  NFormItem,
  NInput,
  NModal,
  NPagination,
  NSelect,
  NSpin,
  NTag,
  useMessage
} from 'naive-ui'
import type { FormInst, FormRules } from 'naive-ui'
import { useRoute, useRouter } from 'vue-router'
import { projectApi, assetApi } from '@/api/assets'
import { useAuthStore } from '@/stores/auth'
import AssetCard from '@/components/asset/AssetCard.vue'
import AssetMatrixFilter, { type AssetFilter } from '@/components/asset/AssetMatrixFilter.vue'
import AssetDetailDrawer from '@/components/asset/AssetDetailDrawer.vue'
import VocabEditor from '@/components/asset/VocabEditor.vue'
import type {
  AssetProjectVO,
  AssetVO,
  MatrixCountVO,
  MediaCategory,
  MediaTypeDef,
  ProjectRole
} from '@/types/asset'

const authStore = useAuthStore()
const message = useMessage()
const route = useRoute()
const router = useRouter()

const projectId = computed(() => Number(route.params.id))

/** 全局 asset:write（菜单/403 兜底）；canWrite=项目数据级写权（viewer 只读） */
const canEdit = computed(() => authStore.hasPermission('asset:write'))

const project = ref<AssetProjectVO | null>(null)
const assets = ref<AssetVO[]>([])
const matrix = ref<MatrixCountVO>({ cells: [], typeTotals: [] })
const total = ref(0)
const loading = ref(false)

const ROLE_LABEL: Record<ProjectRole, string> = { OWNER: '所有者', EDITOR: '编辑者', VIEWER: '浏览者' }
const ROLE_TYPE: Record<ProjectRole, 'success' | 'info' | 'default'> = {
  OWNER: 'success',
  EDITOR: 'info',
  VIEWER: 'default'
}

/** 项目数据级写权：OWNER/EDITOR 可写，VIEWER 只读（设计 §7.2） */
const canWrite = computed(() => {
  const r = project.value?.role
  return r === 'OWNER' || r === 'EDITOR'
})

const roleOptions = computed(() =>
  (project.value?.narrativeRoles ?? []).map((r) => ({ label: r, value: r }))
)

/** 文本类（TEXT 类别）媒体类型下拉选项（新建文本资产用；V60 从受控词汇派生）。 */
const textTypeOptions = computed(() => {
  const types = (project.value?.mediaTypes ?? []).filter((t) => t.category === 'TEXT')
  const labelFor = (key: string) =>
    ({ PROMPT: '提示词', SCRIPT: '剧本' } as Record<string, string>)[key] ?? key
  return types.map((t) => ({ label: labelFor(t.key), value: t.key }))
})

/** 每个媒体类型当前资产数（由矩阵 cells 按 mediaType 聚合；删 type 二次确认显迁移数）。 */
const mediaTypeAssetCounts = computed<Record<string, number>>(() => {
  const m: Record<string, number> = {}
  for (const c of matrix.value.cells) {
    m[c.mediaType] = (m[c.mediaType] ?? 0) + c.count
  }
  return m
})

// === 筛选态 ===
const filter = ref<AssetFilter>({})
const page = ref(1)
const pageSize = 24
const pageCount = computed(() => Math.max(1, Math.ceil(total.value / pageSize)))

const isFiltered = computed(
  () => !!(filter.value.type || filter.value.role || filter.value.q)
)

function clearFilter() {
  filter.value = {}
  page.value = 1
}

// === 新建文本资产 ===
const showCreate = ref(false)
const saving = ref(false)
const formRef = ref<FormInst | null>(null)
const form = ref({
  mediaType: 'PROMPT' as string,
  name: '',
  description: '',
  content: '',
  roleKeys: [] as string[]
})
const rules: FormRules = {
  name: [{ required: true, message: '请输入名称', trigger: 'blur' }],
  content: [{ required: true, message: '请输入正文', trigger: 'blur' }]
}

function openCreate() {
  // 默认首个 TEXT 类别类型（无则回落 PROMPT）
  const firstText = (project.value?.mediaTypes ?? []).find((t) => t.category === 'TEXT')
  form.value = {
    mediaType: firstText?.key ?? 'PROMPT',
    name: '',
    description: '',
    content: '',
    roleKeys: []
  }
  showCreate.value = true
}

async function submitCreate() {
  try {
    await formRef.value?.validate()
  } catch {
    return
  }
  saving.value = true
  try {
    // 正文按类型包成规范 JSON（后端 content 是 JSONB，抽屉/版本时间线 JSON.parse 同此结构；
    // 与 AssetCanvasBridgeService.extractTextContent 契约对齐：PROMPT→{body}，SCRIPT→{synopsis}）
    const rawBody = form.value.content.trim()
    const contentJson = form.value.mediaType === 'SCRIPT'
      ? JSON.stringify({ synopsis: rawBody })
      : JSON.stringify({ body: rawBody })
    await assetApi.create(projectId.value, {
      mediaType: form.value.mediaType,
      name: form.value.name.trim(),
      description: form.value.description.trim() || undefined,
      content: contentJson,
      roleKeys: form.value.roleKeys.length ? form.value.roleKeys : undefined
    })
    message.success('已创建')
    showCreate.value = false
    await reload()
  } catch {
    message.error('创建失败')
  } finally {
    saving.value = false
  }
}

// === 上传文件（V60：按 mime 推断处理类别 → 候选受控词汇；多候选弹选择） ===
const fileInput = ref<HTMLInputElement | null>(null)
const showUploadTypePicker = ref(false)
const pendingUploadFile = ref<File | null>(null)
const pendingUploadCategory = ref<MediaCategory | null>(null)
const pendingUploadType = ref<string>('')

const CATEGORY_LABEL: Record<MediaCategory, string> = { TEXT: '文本', IMAGE: '图片', VIDEO: '视频', AUDIO: '音频' }
const pendingUploadCategoryLabel = computed(() =>
  pendingUploadCategory.value ? CATEGORY_LABEL[pendingUploadCategory.value] : ''
)
/** 该类别下的候选媒体类型（弹窗下拉源）。 */
const uploadTypeOptions = computed(() =>
  (project.value?.mediaTypes ?? [])
    .filter((t) => t.category === pendingUploadCategory.value)
    .map((t) => ({ label: t.key, value: t.key }))
)

function triggerUpload() {
  fileInput.value?.click()
}

/** mime → 默认媒体类型 key（IMAGE/VIDEO/AUDIO；默认词汇快路径 + inferMediaType 测试用）。 */
function inferMediaType(mime: string): 'IMAGE' | 'VIDEO' | 'AUDIO' | null {
  if (mime.startsWith('image/')) return 'IMAGE'
  if (mime.startsWith('video/')) return 'VIDEO'
  if (mime.startsWith('audio/')) return 'AUDIO'
  return null
}

/** mime → 处理类别（V60 两层：category 决定上传链路）。 */
function inferCategory(mime: string): MediaCategory | null {
  if (mime.startsWith('image/')) return 'IMAGE'
  if (mime.startsWith('video/')) return 'VIDEO'
  if (mime.startsWith('audio/')) return 'AUDIO'
  return null
}

async function onFileChange(e: Event) {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  // 重置 value 允许同名文件再次触发 change
  input.value = ''
  if (!file) return
  const category = inferCategory(file.type)
  if (!category) {
    message.error('不支持的文件类型（仅图片/视频/音频）')
    return
  }
  const candidates = (project.value?.mediaTypes ?? []).filter((t) => t.category === category)
  if (candidates.length === 0) {
    message.error(`项目无「${CATEGORY_LABEL[category]}」类别的媒体类型，请先在「编辑分类」中新增`)
    return
  }
  // 单候选直传；多候选时若默认推断 type 在候选内也直传，否则弹选择
  if (candidates.length === 1) {
    await doUpload(file, candidates[0].key)
    return
  }
  const guess = inferMediaType(file.type)
  if (guess && candidates.some((t) => t.key === guess)) {
    await doUpload(file, guess)
    return
  }
  pendingUploadFile.value = file
  pendingUploadCategory.value = category
  pendingUploadType.value = candidates[0].key
  showUploadTypePicker.value = true
}

async function doUpload(file: File, mediaType: string) {
  try {
    await assetApi.upload(projectId.value, file, mediaType, { name: file.name })
    message.success('上传成功')
    await reload()
  } catch {
    message.error('上传失败')
  }
}

function cancelUploadPick() {
  showUploadTypePicker.value = false
  pendingUploadFile.value = null
}

async function confirmUploadPick() {
  const file = pendingUploadFile.value
  const t = pendingUploadType.value
  showUploadTypePicker.value = false
  pendingUploadFile.value = null
  if (file && t) await doUpload(file, t)
}

// === 详情抽屉 ===
const showDetail = ref(false)
const detailAssetId = ref<number | null>(null)

function openDetail(a: AssetVO) {
  detailAssetId.value = a.id
  showDetail.value = true
}

async function onDetailChanged() {
  // L2/L3：定稿/归档/一致性包可能改变资产态与矩阵计数
  await reload()
}

// === 加载 ===
async function loadAssets() {
  loading.value = true
  try {
    const res = await assetApi.list(projectId.value, {
      type: filter.value.type,
      role: filter.value.role,
      q: filter.value.q,
      page: page.value,
      size: pageSize
    })
    assets.value = res.data.data?.records ?? []
    total.value = res.data.data?.total ?? 0
  } catch {
    message.error('加载资产列表失败')
  } finally {
    loading.value = false
  }
}

async function loadMatrix() {
  try {
    const res = await assetApi.countMatrix(projectId.value)
    matrix.value = res.data.data ?? { cells: [], typeTotals: [] }
  } catch {
    // 矩阵失败不阻塞列表
  }
}

async function loadProject() {
  try {
    const res = await projectApi.get(projectId.value)
    project.value = res.data.data
  } catch {
    message.error('加载项目失败')
  }
}

async function reload() {
  await Promise.all([loadAssets(), loadMatrix()])
}

// === C1a 分类管理（叙事角色桶） ===
const showVocab = ref(false)
const savingVocab = ref(false)
/** 每个叙事角色当前资产数（由矩阵 cells 按 roleKey 聚合；删桶二次确认显迁移数）。 */
const roleAssetCounts = computed<Record<string, number>>(() => {
  const m: Record<string, number> = {}
  for (const c of matrix.value.cells) {
    if (c.roleKey) m[c.roleKey] = (m[c.roleKey] ?? 0) + c.count
  }
  return m
})
/** VocabEditor 保存 → 整体覆盖 narrativeRoles + mediaTypes（后端 normalize + reassign 兜底）。 */
async function onSaveVocab(payload: { roles: string[]; mediaTypes: MediaTypeDef[] }) {
  savingVocab.value = true
  try {
    await projectApi.update(projectId.value, {
      narrativeRoles: payload.roles,
      mediaTypes: payload.mediaTypes
    })
    message.success('分类已更新')
    showVocab.value = false
    // 刷新 narrativeRoles + mediaTypes（矩阵顶/左栏 + 下拉同源）+ 矩阵计数（删桶/删 type 迁移反映）
    await loadProject()
    await reload()
  } catch {
    message.error('更新分类失败')
  } finally {
    savingVocab.value = false
  }
}

// 筛选/分页变化 → 重拉列表
watch(
  filter,
  () => {
    page.value = 1
    loadAssets()
  },
  { deep: true }
)

watch(projectId, async () => {
  if (projectId.value) {
    await loadProject()
    await reload()
  }
})

defineExpose({
  project,
  assets,
  matrix,
  filter,
  page,
  total,
  canEdit,
  canWrite,
  openCreate,
  submitCreate,
  triggerUpload,
  onFileChange,
  inferMediaType,
  openDetail,
  reload,
  loadAssets,
  loadMatrix
})

onMounted(async () => {
  if (projectId.value) {
    await loadProject()
    await reload()
  }
})
</script>

<style lang="scss" scoped>
.asset-project {
  padding: var(--spacing-5);
  min-height: 100%;

  &__header {
    margin-bottom: var(--spacing-4);
  }

  &__title-row {
    display: flex;
    align-items: center;
    gap: var(--spacing-2);
    flex-wrap: wrap;
  }

  &__title {
    margin: 0;
    font-size: var(--font-size-xl);
    font-weight: var(--font-weight-bold);
    color: var(--color-text-primary);
  }

  &__count {
    font-size: var(--font-size-sm);
    color: var(--color-text-tertiary);
  }

  &__spacer {
    flex: 1;
  }

  &__sub {
    color: var(--color-text-secondary);
    font-size: var(--font-size-sm);
  }

  &__picker-hint {
    font-size: var(--font-size-sm);
    color: var(--color-text-secondary);
    margin: 0 0 var(--spacing-3);
  }

  &__loading,
  &__empty {
    display: flex;
    align-items: center;
    justify-content: center;
    min-height: 280px;
  }

  &__forbidden {
    margin-top: var(--spacing-8);
  }

  &__grid {
    display: grid;
    grid-template-columns: repeat(4, 1fr);
    gap: var(--spacing-4);

    @media (max-width: 1200px) {
      grid-template-columns: repeat(3, 1fr);
    }
    @media (max-width: 900px) {
      grid-template-columns: repeat(2, 1fr);
    }
    @media (max-width: 600px) {
      grid-template-columns: 1fr;
    }
  }

  &__pagination {
    margin-top: var(--spacing-5);
    justify-content: center;
    display: flex;
  }
}

// === 移动端 ===
@media (max-width: 768px) {
  .asset-project {
    padding: var(--spacing-3);
  }
}
</style>
