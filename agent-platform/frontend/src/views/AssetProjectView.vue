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
    <!-- 雾中浮岛场景层（ART-DIR-0002R 方向二，仅 ink 主题渲染） -->
    <ModuleScene scene="assets" />
    <!-- 顶栏：返回 + 项目名 + 计数 + 操作 -->
    <div class="asset-project__header">
      <div class="asset-project__title-row">
        <n-button quaternary size="small" @click="router.push('/assets')">← 返回</n-button>
        <h2 class="asset-project__title">{{ project?.name || '项目资产' }}</h2>
        <n-tag v-if="project" size="small" :type="ROLE_TYPE[project.role]" bordered>
          {{ ROLE_LABEL[project.role] }}
        </n-tag>
        <n-tag v-if="project?.publicPool" size="small" bordered type="info">公共项目</n-tag>
        <n-tag v-if="project?.publishedByAdmin" size="small" bordered type="warning">官方发布</n-tag>
        <span class="asset-project__count">{{ total }} 个资产</span>
        <div class="asset-project__spacer" />
        <template v-if="canWrite">
          <!-- C7 OWNER 项目设置（成员打分开关/内容模式） -->
          <n-button v-if="isOwner" quaternary @click="showSettings = true">项目设置</n-button>
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
    <InkEmptyState
      v-if="!canEdit"
      type="forbidden"
      description="无 asset:write 权限，请联系管理员授权"
      class="asset-project__forbidden"
    />

    <template v-else-if="project">
      <AssetMatrixFilter
        v-model="filter"
        :counts="matrix"
        :roles="(project.narrativeRoles ?? []).map((r) => r.key)"
        :media-types="project.mediaTypes ?? []"
        :project-id="projectId"
      >
        <!-- 加载 -->
        <div v-if="loading" class="asset-project__loading"><n-spin size="large" /></div>

        <!-- 卡片网格 -->
        <div v-else-if="assets.length" class="asset-project__grid">
          <div v-for="a in assets" :key="a.id" class="asset-project__asset-item">
            <AssetCard :asset="a" @open="openDetail" />
            <n-button
              v-if="canCopyAsset"
              class="asset-project__copy-button"
              size="small"
              secondary
              type="primary"
              @click.stop="openCopy(a)"
            >
              复制到我的项目
            </n-button>
          </div>
        </div>

        <!-- 空态：双引导 -->
        <InkEmptyState v-else-if="isFiltered" type="data" description="无匹配资产" class="asset-project__empty">
          <n-button size="small" @click="clearFilter">清除筛选</n-button>
        </InkEmptyState>
        <InkEmptyState v-else type="data" class="asset-project__empty" description="项目暂无资产">
          <template v-if="canWrite">
            <n-button size="small" @click="triggerUpload">上传文件</n-button>
            <n-button size="small" type="primary" @click="openCreate">新建提示词/剧本</n-button>
          </template>
        </InkEmptyState>
      </AssetMatrixFilter>

      <!-- 分页 -->
      <n-pagination
        v-if="total > pageSize"
        v-model:page="page"
        :page-count="pageCount"
        :page-size="pageSize"
        size="small"
        class="asset-project__pagination"
        @update:page="() => loadAssets()"
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

    <!-- 资产详情抽屉（S10 + C7 评分/PERSONAL 门控） -->
    <AssetDetailDrawer
      v-model:show="showDetail"
      :asset-id="detailAssetId"
      :can-edit="canWrite"
      :can-score="canScore"
      :personal-mode="personalMode"
      :current-user-id="currentUserId"
      @changed="onDetailChanged"
    />

    <!-- C7 项目设置（OWNER：成员打分开关 + 内容模式） -->
    <ProjectSettingsDialog
      v-model:show="showSettings"
      :project="project"
      @saved="onSettingsSaved"
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

    <n-modal
      :show="showCopy"
      preset="card"
      title="复制到我的项目"
      style="max-width:480px"
      @update:show="onCopyVisibilityChange"
    >
      <p class="asset-project__copy-hint">
        将「{{ copyAsset?.name || '当前资产' }}」复制为目标项目中的独立资产，源项目不会被修改。
      </p>
      <div v-if="copyTargetsLoading" class="asset-project__copy-loading"><n-spin size="small" /> 正在加载可写项目…</div>
      <div v-else-if="copyTargetsError" class="asset-project__copy-error" role="alert">{{ copyTargetsError }}</div>
      <n-empty v-else-if="writableTargets.length === 0" description="暂无可写的目标项目" class="asset-project__copy-empty" />
      <n-select
        v-else
        v-model:value="selectedTargetProjectId"
        :options="copyTargetOptions"
        placeholder="选择目标项目"
        :disabled="copySubmitting"
      />
      <div v-if="copyError" class="asset-project__copy-error" role="alert">{{ copyError }}</div>
      <template #action>
        <n-button :disabled="copySubmitting" @click="closeCopy">取消</n-button>
        <n-button
          type="primary"
          :loading="copySubmitting"
          :disabled="copyTargetsLoading || !!copyTargetsError || !selectedTargetProjectId || writableTargets.length === 0"
          :title="!selectedTargetProjectId ? '请先选择目标项目' : undefined"
          @click="submitCopy"
        >
          复制
        </n-button>
      </template>
    </n-modal>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch } from 'vue'
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
import ProjectSettingsDialog from '@/components/asset/ProjectSettingsDialog.vue'
import VocabEditor from '@/components/asset/VocabEditor.vue'
import InkEmptyState from '@/components/InkEmptyState.vue'
import ModuleScene from '@/components/ModuleScene.vue'
import type {
  AssetProjectVO,
  AssetVO,
  MatrixCountVO,
  MediaCategory,
  MediaTypeDef,
  ProjectRole
} from '@/types/asset'
import { MEDIA_TYPE } from '@/types/asset'
import type { NarrativeRoleVocab } from '@/types/asset'
import { buildRoleGroupOptions } from '@/utils/assetVocab'

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

interface RouteLoadContext {
  session: number
  projectId: number
}

let routeLoadSession = 0
let activeRouteContext: RouteLoadContext | null = null

function isCurrentRouteContext(context: RouteLoadContext) {
  return activeRouteContext?.session === context.session
    && activeRouteContext.projectId === context.projectId
    && projectId.value === context.projectId
}

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

/** 公共池 VIEWER 可浏览并复制，但仍不能编辑源项目。 */
const isPublicViewer = computed(() => project.value?.publicPool === true && project.value.role === 'VIEWER')
/**
 * 2x V100：复制按钮显隐——公共 VIEWER 且项目允许复制（不渲染非置灰，C2）；
 * 发布方关闭后重进/刷新即消失（L2）；后端另有 403 兜底（直调 API）。
 */
const canCopyAsset = computed(() => isPublicViewer.value && project.value?.allowPublicCopy !== false)

// === C7 评分/PERSONAL 门控（对齐后端 C6：OWNER 恒可评独立轨；EDITOR 随开关均分轨；VIEWER 不可） ===
const isOwner = computed(() => project.value?.role === 'OWNER')
const currentUserId = computed(() => authStore.userInfo?.id ?? null)
const canScore = computed(
  () => canWrite.value && (isOwner.value || project.value?.memberScoringEnabled === true)
)
/** PERSONAL 仅约束非 OWNER 编辑者（OWNER/admin 旁路在父层排除，与后端 requireAssetOperate 对齐）。 */
const personalMode = computed(
  () => project.value?.contentMode === 'PERSONAL' && !isOwner.value && !isPublicViewer.value
)

const showSettings = ref(false)
/** 设置保存成功 → 重拉 project（开关/模式即时生效）+ 列表（myScore 等展示字段刷新，L6）。 */
async function onSettingsSaved() {
  await loadProject()
  await reload()
}

/** 叙事角色下拉分组选项（修复XI 两级：每级一组，「不细分」=挂一级本身，子类各为选项）。 */
const roleOptions = computed(() => buildRoleGroupOptions(project.value?.narrativeRoles ?? []))

/** 文本类（TEXT 类别）媒体类型下拉选项（新建文本资产用；V60 从受控词汇派生）。 */
const textTypeOptions = computed(() => {
  const types = (project.value?.mediaTypes ?? []).filter((t) => t.category === 'TEXT')
  return types.map((t) => ({ label: t.key, value: t.key }))
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
  () => !!(filter.value.type || filter.value.role || filter.value.q
    || filter.value.creatorUsername || filter.value.scoreSource
    || filter.value.scoreMin != null || filter.value.scoreMax != null)
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
  mediaType: MEDIA_TYPE.PROMPT as string,
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
  // 默认首个 TEXT 类别类型（无则回落 提示词）
  const firstText = (project.value?.mediaTypes ?? []).find((t) => t.category === 'TEXT')
  form.value = {
    mediaType: firstText?.key ?? MEDIA_TYPE.PROMPT,
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
    // 与 AssetCanvasBridgeService.extractTextContent 契约对齐：提示词→{body}，剧本→{synopsis}）
    const rawBody = form.value.content.trim()
    const contentJson = form.value.mediaType === MEDIA_TYPE.SCRIPT
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

/** mime → 默认媒体类型 key（图片/视频/音频；上传 mediaType 用，须与受控词汇 key 一致）。 */
function inferMediaType(mime: string): string | null {
  if (mime.startsWith('image/')) return MEDIA_TYPE.IMAGE
  if (mime.startsWith('video/')) return MEDIA_TYPE.VIDEO
  if (mime.startsWith('audio/')) return MEDIA_TYPE.AUDIO
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

/** 2x 修复：上传前 60MB 预检（对齐后端 multipart 上限）——超限前端直接拒，省得白传半天被 413。 */
const UPLOAD_MAX_BYTES = 60 * 1024 * 1024

async function doUpload(file: File, mediaType: string) {
  if (file.size > UPLOAD_MAX_BYTES) {
    message.error(`「${file.name}」${(file.size / 1024 / 1024).toFixed(1)}MB 超过单文件 60MB 上限，请压缩或拆分后重试`)
    return
  }
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

// === 公共资产复制（每次打开均创建新上下文，避免旧列表/旧复制结果污染） ===
const showCopy = ref(false)
const copyAsset = ref<AssetVO | null>(null)
const writableTargets = ref<AssetProjectVO[]>([])
const selectedTargetProjectId = ref<number | null>(null)
const copyTargetsLoading = ref(false)
const copyTargetsError = ref('')
const copySubmitting = ref(false)
const copyError = ref('')
let copySession = 0
const copyMutationsInFlight = new Set<string>()

const copyTargetOptions = computed(() =>
  writableTargets.value.map((p) => ({ label: p.name, value: p.id }))
)

function isCurrentCopyContext(session: number, sourceProjectId: number, assetId: number) {
  return session === copySession
    && showCopy.value
    && projectId.value === sourceProjectId
    && copyAsset.value?.id === assetId
}

function clearCopyState(force = false) {
  if (copySubmitting.value && !force) return false
  copySession += 1
  showCopy.value = false
  copyAsset.value = null
  writableTargets.value = []
  selectedTargetProjectId.value = null
  copyTargetsLoading.value = false
  copyTargetsError.value = ''
  copySubmitting.value = false
  copyError.value = ''
  return true
}

async function openCopy(asset: AssetVO) {
  if (copySubmitting.value && showCopy.value) return
  copySession += 1
  const session = copySession
  const sourceProjectId = projectId.value
  const assetId = asset.id
  showCopy.value = true
  copyAsset.value = asset
  writableTargets.value = []
  selectedTargetProjectId.value = null
  copyTargetsLoading.value = true
  copyTargetsError.value = ''
  copySubmitting.value = false
  copyError.value = ''
  try {
    const res = await projectApi.list()
    if (!isCurrentCopyContext(session, sourceProjectId, assetId)) return
    writableTargets.value = (res.data.data || []).filter(
      (p) => p.id !== sourceProjectId && (p.role === 'OWNER' || p.role === 'EDITOR')
    )
  } catch {
    if (isCurrentCopyContext(session, sourceProjectId, assetId)) {
      copyTargetsError.value = '加载可写项目失败，请关闭后重试'
    }
  } finally {
    if (isCurrentCopyContext(session, sourceProjectId, assetId)) copyTargetsLoading.value = false
  }
}

function closeCopy() {
  clearCopyState()
}

function onCopyVisibilityChange(value: boolean) {
  if (!value) closeCopy()
}

async function submitCopy() {
  const asset = copyAsset.value
  const targetProjectId = selectedTargetProjectId.value
  if (!asset || !targetProjectId || copySubmitting.value) return
  const target = writableTargets.value.find((p) => p.id === targetProjectId)
  if (!target) {
    copyError.value = '请选择有效的目标项目'
    return
  }
  const mutationKey = `${projectId.value}:${asset.id}:${targetProjectId}`
  if (copyMutationsInFlight.has(mutationKey)) return
  const session = copySession
  const sourceProjectId = projectId.value
  const assetId = asset.id
  copySubmitting.value = true
  copyError.value = ''
  copyMutationsInFlight.add(mutationKey)
  try {
    await assetApi.copy(assetId, { targetProjectId })
    if (!isCurrentCopyContext(session, sourceProjectId, assetId)) return
    message.success(`已复制到「${target.name}」`)
    clearCopyState(true)
  } catch {
    if (isCurrentCopyContext(session, sourceProjectId, assetId)) {
      copyError.value = '复制失败，请重试'
      message.error('复制失败')
    }
  } finally {
    copyMutationsInFlight.delete(mutationKey)
    if (isCurrentCopyContext(session, sourceProjectId, assetId)) copySubmitting.value = false
  }
}

async function onDetailChanged() {
  // L2/L3：定稿/归档/一致性包可能改变资产态与矩阵计数
  await reload()
}

// === 加载 ===
async function loadAssets(context = activeRouteContext) {
  if (!context) return
  loading.value = true
  try {
    const res = await assetApi.list(context.projectId, {
      type: filter.value.type,
      role: filter.value.role,
      q: filter.value.q,
      creatorUsername: filter.value.creatorUsername,
      scoreMin: filter.value.scoreMin,
      scoreMax: filter.value.scoreMax,
      scoreSource: filter.value.scoreSource,
      page: page.value,
      size: pageSize
    })
    if (isCurrentRouteContext(context)) {
      assets.value = res.data.data?.records ?? []
      total.value = res.data.data?.total ?? 0
    }
  } catch {
    if (isCurrentRouteContext(context)) message.error('加载资产列表失败')
  } finally {
    if (isCurrentRouteContext(context)) loading.value = false
  }
}

async function loadMatrix(context = activeRouteContext) {
  if (!context) return
  try {
    const res = await assetApi.countMatrix(context.projectId)
    if (isCurrentRouteContext(context)) matrix.value = res.data.data ?? { cells: [], typeTotals: [] }
  } catch {
    // 矩阵失败不阻塞列表
  }
}

async function loadProject(context = activeRouteContext) {
  if (!context) return
  try {
    const res = await projectApi.get(context.projectId)
    if (isCurrentRouteContext(context)) project.value = res.data.data
  } catch {
    if (isCurrentRouteContext(context)) message.error('加载项目失败')
  }
}

async function reload() {
  const context = activeRouteContext
  if (!context) return
  await Promise.all([loadAssets(context), loadMatrix(context)])
}

async function loadRoute(nextProjectId: number) {
  const context = { session: ++routeLoadSession, projectId: nextProjectId }
  activeRouteContext = context
  project.value = null
  assets.value = []
  matrix.value = { cells: [], typeTotals: [] }
  total.value = 0
  loading.value = true
  clearCopyState(true)
  await Promise.all([loadProject(context), loadAssets(context), loadMatrix(context)])
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
/** VocabEditor 保存 → 整体覆盖两级 narrativeRoles + mediaTypes（后端 normalize + reassign 兜底）。 */
async function onSaveVocab(payload: { roles: NarrativeRoleVocab[]; mediaTypes: MediaTypeDef[] }) {
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

watch(projectId, (id) => {
  if (id) void loadRoute(id)
}, { immediate: true })

defineExpose({
  project,
  assets,
  matrix,
  filter,
  page,
  total,
  canEdit,
  canWrite,
  isPublicViewer,
  isOwner,
  canScore,
  personalMode,
  showSettings,
  onSettingsSaved,
  openCreate,
  submitCreate,
  triggerUpload,
  onFileChange,
  inferMediaType,
  openDetail,
  showCopy,
  copyAsset,
  writableTargets,
  selectedTargetProjectId,
  copyTargetsLoading,
  copyTargetsError,
  copySubmitting,
  copyError,
  openCopy,
  closeCopy,
  onCopyVisibilityChange,
  submitCopy,
  reload,
  loadAssets,
  loadMatrix
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

  &__asset-item {
    position: relative;
    min-width: 0;
  }

  &__copy-button {
    width: 100%;
    margin-top: var(--spacing-2);
  }

  &__copy-hint {
    margin: 0 0 var(--spacing-4);
    color: var(--color-text-secondary);
    font-size: var(--font-size-sm);
    line-height: 1.6;
  }

  &__copy-loading,
  &__copy-empty {
    min-height: 96px;
    display: flex;
    align-items: center;
    justify-content: center;
    gap: var(--spacing-2);
    color: var(--color-text-secondary);
  }

  &__copy-error {
    margin-top: var(--spacing-3);
    padding: var(--spacing-2) var(--spacing-3);
    border: 1px solid color-mix(in srgb, var(--color-error) 45%, transparent);
    border-radius: var(--radius-md, 8px);
    background: color-mix(in srgb, var(--color-error) 10%, transparent);
    color: var(--color-error);
    font-size: var(--font-size-sm);
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
