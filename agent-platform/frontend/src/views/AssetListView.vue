<template>
  <div class="asset-list">
    <div class="asset-list__header">
      <div class="asset-list__title-row">
        <h2 class="asset-list__title">资产库</h2>
        <span class="asset-list__count">{{ projects.length }} 个项目</span>
        <div class="asset-list__spacer" />
        <n-button v-if="canEdit" type="primary" @click="openCreate">+ 新建项目</n-button>
      </div>
      <span class="asset-list__sub">项目级资产中枢 · 五类资产 × 叙事角色双轴矩阵</span>
    </div>

    <n-empty
      v-if="!canEdit"
      description="无 asset:write 权限，请联系管理员授权"
      class="asset-list__forbidden"
    />

    <template v-else>
      <n-tabs v-model:value="activeTab" type="line" animated class="asset-list__tabs">
        <n-tab-pane name="mine" :tab="`我的项目（${mineProjects.length}）`">
          <project-grid
            :loading="loading"
            :projects="mineProjects"
            :busy-project-ids="unpublishingIds"
            @open="openProject"
            @share="openShare"
            @delete="confirmDelete"
            @publish="openPublish"
            @unpublish="confirmUnpublish"
            @access="openAccess"
          />
        </n-tab-pane>
        <n-tab-pane name="shared" :tab="`共享给我（${sharedProjects.length}）`">
          <project-grid
            :loading="loading"
            :projects="sharedProjects"
            :busy-project-ids="unpublishingIds"
            @open="openProject"
            @share="openShare"
            @delete="confirmDelete"
            @publish="openPublish"
            @unpublish="confirmUnpublish"
            @access="openAccess"
          />
        </n-tab-pane>
        <n-tab-pane name="public" :tab="`公共池（${publicProjects.length}）`">
          <div v-if="publicError" class="asset-list__public-error" role="alert">
            <span>{{ publicError }}</span>
            <n-button size="small" secondary @click="loadPublicData">重新加载</n-button>
          </div>
          <public-project-grid
            v-else
            :loading="publicLoading"
            :projects="publicProjects"
            :requesting-project-ids="requestingIds"
            @open="openPublicProject"
            @request="requestPublicAccess"
          />
        </n-tab-pane>
      </n-tabs>
    </template>

    <n-modal v-model:show="showCreate" preset="card" title="新建项目" style="max-width:480px">
      <n-form ref="formRef" :model="form" :rules="rules" label-placement="top">
        <n-form-item label="项目名称" path="name">
          <n-input v-model:value="form.name" placeholder="如：我的短剧第一季" :maxlength="100" show-count />
        </n-form-item>
        <n-form-item label="描述" path="description">
          <n-input v-model:value="form.description" type="textarea" placeholder="可选，项目简介" :rows="3" :maxlength="500" />
        </n-form-item>
      </n-form>
      <template #action>
        <n-button @click="showCreate = false">取消</n-button>
        <n-button type="primary" :loading="saving" @click="submitCreate">创建</n-button>
      </template>
    </n-modal>

    <ShareDialog
      v-model:show="showShare"
      :project-id="shareProject?.id ?? 0"
      :project-name="shareProject?.name ?? ''"
      @changed="loadLocalData"
    />
    <PublicPublishDialog
      v-model:show="showPublish"
      :project="publishProject"
      :is-admin="authStore.isAdmin"
      @published="onPublished"
    />
    <PublicAccessDialog
      v-model:show="showAccess"
      :project-id="accessProject?.id ?? 0"
      :project-name="accessProject?.name ?? ''"
      @changed="loadPublicData"
    />
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, h } from 'vue'
import {
  NButton,
  NEmpty,
  NForm,
  NFormItem,
  NInput,
  NModal,
  NSpin,
  NTabs,
  NTabPane,
  NTag,
  useMessage,
  useDialog
} from 'naive-ui'
import type { FormInst, FormRules } from 'naive-ui'
import { useRouter } from 'vue-router'
import { projectApi, publicPoolApi } from '@/api/assets'
import { useAuthStore } from '@/stores/auth'
import ShareDialog from '@/components/asset/ShareDialog.vue'
import PublicPublishDialog from '@/components/asset/PublicPublishDialog.vue'
import PublicAccessDialog from '@/components/asset/PublicAccessDialog.vue'
import type { AssetProjectVO, ProjectRole, PublicProjectSummaryVO } from '@/types/asset'

type ProjectGridEvent = 'open' | 'share' | 'delete' | 'publish' | 'unpublish' | 'access'

const stopAndEmit = <T,>(emit: (event: T, project: AssetProjectVO) => void, event: T, project: AssetProjectVO) =>
  (e: Event) => {
    e.stopPropagation()
    emit(event, project)
  }

const ProjectGrid = (
  props: { loading: boolean; projects: AssetProjectVO[]; busyProjectIds: Set<number> },
  { emit }: { emit: (e: ProjectGridEvent, p: AssetProjectVO) => void }
) => {
  if (props.loading) return h('div', { class: 'asset-list__loading' }, [h(NSpin, { size: 'large' })])
  if (props.projects.length === 0) return h(NEmpty, { description: '暂无项目', class: 'asset-list__empty' })
  return h('div', { class: 'asset-list__grid' }, props.projects.map((p) =>
    h('div', { class: 'project-card', key: p.id, onClick: () => emit('open', p) }, [
      h('div', { class: 'project-card__cover' }, p.name.slice(0, 1) || '项'),
      h('div', { class: 'project-card__body' }, [
        h('div', { class: 'project-card__name-row' }, [
          h('span', { class: 'project-card__name' }, p.name),
          h(NTag, { size: 'small', bordered: false, type: ROLE_TYPE[p.role] }, () => ROLE_LABEL[p.role])
        ]),
        h('div', { class: 'project-card__desc' }, p.description || '暂无描述'),
        h('div', { class: 'project-card__meta' }, `${p.narrativeRoles?.length ?? 0} 个叙事角色`)
      ]),
      p.role === 'OWNER'
        ? h('div', { class: 'project-card__actions' }, [
            p.publicPool
              ? h(NButton, {
                  size: 'tiny', quaternary: true, type: 'warning',
                  loading: props.busyProjectIds.has(p.id),
                  disabled: props.busyProjectIds.has(p.id),
                  title: props.busyProjectIds.has(p.id) ? '正在移出公共池' : '停止公开此项目',
                  onClick: stopAndEmit(emit, 'unpublish', p)
                }, () => '移出公共池')
              : h(NButton, {
                  size: 'tiny', quaternary: true,
                  onClick: stopAndEmit(emit, 'publish', p)
                }, () => '发布到公共池'),
            p.publicPool && p.publicAccessMode === 'APPROVAL_REQUIRED'
              ? h(NButton, { size: 'tiny', quaternary: true, onClick: stopAndEmit(emit, 'access', p) }, () => '审批管理')
              : null,
            h(NButton, { size: 'tiny', quaternary: true, onClick: stopAndEmit(emit, 'share', p) }, () => '分享'),
            h(NButton, { size: 'tiny', quaternary: true, type: 'error', onClick: stopAndEmit(emit, 'delete', p) }, () => '删除')
          ])
        : null
    ])
  ))
}
ProjectGrid.props = ['loading', 'projects', 'busyProjectIds']
ProjectGrid.emits = ['open', 'share', 'delete', 'publish', 'unpublish', 'access']

const requestLabel = (p: PublicProjectSummaryVO) => {
  if (p.usable) return '使用'
  if (p.publicAccessMode !== 'APPROVAL_REQUIRED') return '暂不可用'
  if (p.myRequestStatus === 'PENDING') return '等待审批'
  if (p.myRequestStatus === 'REJECTED' || p.myRequestStatus === 'REVOKED') return '重新申请'
  return '申请使用'
}

const PublicProjectGrid = (
  props: { loading: boolean; projects: PublicProjectSummaryVO[]; requestingProjectIds: Set<number> },
  { emit }: { emit: (e: 'open' | 'request', p: PublicProjectSummaryVO) => void }
) => {
  if (props.loading) return h('div', { class: 'asset-list__loading' }, [h(NSpin, { size: 'large' })])
  if (props.projects.length === 0) return h(NEmpty, { description: '公共池暂无项目', class: 'asset-list__empty' })
  return h('div', { class: 'asset-list__grid' }, props.projects.map((p) => {
    const pending = p.myRequestStatus === 'PENDING'
    const requesting = props.requestingProjectIds.has(p.id)
    const disabled = pending || requesting || (!p.usable && p.publicAccessMode === 'OPEN')
    const title = pending ? '申请正在等待审批' : requesting ? '正在提交申请' : disabled ? '当前项目暂不可用' : undefined
    return h('div', {
      class: ['project-card', 'public-project-card', { 'public-project-card--locked': !p.usable }],
      key: p.id,
      role: p.usable ? 'link' : undefined,
      tabindex: p.usable ? 0 : undefined,
      title: p.usable ? `进入公共项目 ${p.name}` : '获得使用权限后可进入项目',
      onClick: () => { if (p.usable) emit('open', p) },
      onKeydown: (e: KeyboardEvent) => { if (p.usable && (e.key === 'Enter' || e.key === ' ')) emit('open', p) }
    }, [
      h('div', { class: 'project-card__cover project-card__cover--public' }, p.name.slice(0, 1) || '公'),
      h('div', { class: 'project-card__body' }, [
        h('div', { class: 'project-card__name-row' }, [
          h('span', { class: 'project-card__name' }, p.name),
          h('div', { class: 'public-project-card__badges' }, [
            p.publishedByAdmin ? h(NTag, { size: 'small', bordered: true, type: 'warning' }, () => '官方发布') : null,
            h(NTag, { size: 'small', bordered: false, type: p.publicAccessMode === 'OPEN' ? 'success' : 'warning' },
              () => p.publicAccessMode === 'OPEN' ? '直接使用' : '需审批')
          ])
        ]),
        h('div', { class: 'project-card__desc' }, p.description || '暂无描述'),
        h('div', { class: 'project-card__meta' }, [
          h('span', `${p.assetCount} 个资产`),
          h('span', `发布者：${p.publisherUsername || '未知用户'}`)
        ]),
        h('div', { class: 'public-project-card__footer' }, [
          h(NButton, {
            size: 'small', type: p.usable ? 'primary' : 'default', secondary: !p.usable,
            disabled, loading: requesting, title,
            onClick: (e: Event) => {
              e.stopPropagation()
              if (disabled) return
              emit(p.usable ? 'open' : 'request', p)
            }
          }, () => requestLabel(p))
        ])
      ])
    ])
  }))
}
PublicProjectGrid.props = ['loading', 'projects', 'requestingProjectIds']
PublicProjectGrid.emits = ['open', 'request']

const authStore = useAuthStore()
const message = useMessage()
const dialog = useDialog()
const router = useRouter()
const canEdit = computed(() => authStore.hasPermission('asset:write'))

const projects = ref<AssetProjectVO[]>([])
const publicProjects = ref<PublicProjectSummaryVO[]>([])
const loading = ref(true)
const publicLoading = ref(true)
const publicError = ref('')
const activeTab = ref<'mine' | 'shared' | 'public'>('mine')
const mineProjects = computed(() => projects.value.filter((p) => p.role === 'OWNER'))
const sharedProjects = computed(() => projects.value.filter((p) => p.role !== 'OWNER'))

const showCreate = ref(false)
const saving = ref(false)
const formRef = ref<FormInst | null>(null)
const form = ref({ name: '', description: '' })
const rules: FormRules = { name: [{ required: true, message: '请输入项目名称', trigger: 'blur' }] }

const ROLE_LABEL: Record<ProjectRole, string> = { OWNER: '所有者', EDITOR: '编辑者', VIEWER: '浏览者' }
const ROLE_TYPE: Record<ProjectRole, 'success' | 'info' | 'default'> = { OWNER: 'success', EDITOR: 'info', VIEWER: 'default' }

function openCreate() {
  form.value = { name: '', description: '' }
  showCreate.value = true
}

async function submitCreate() {
  try { await formRef.value?.validate() } catch { return }
  saving.value = true
  try {
    await projectApi.create({ name: form.value.name.trim(), description: form.value.description.trim() || undefined })
    message.success('项目已创建')
    showCreate.value = false
    await loadLocalData()
  } catch {
    message.error('创建失败')
  } finally {
    saving.value = false
  }
}

function openProject(p: AssetProjectVO) { router.push(`/assets/${p.id}`) }
function openPublicProject(p: PublicProjectSummaryVO) { if (p.usable) router.push(`/assets/${p.id}`) }

const showShare = ref(false)
const shareProject = ref<AssetProjectVO | null>(null)
function openShare(p: AssetProjectVO) { shareProject.value = p; showShare.value = true }

const showPublish = ref(false)
const publishProject = ref<AssetProjectVO | null>(null)
function openPublish(p: AssetProjectVO) { publishProject.value = p; showPublish.value = true }
async function onPublished() {
  showPublish.value = false
  publishProject.value = null
  await Promise.all([loadLocalData(), loadPublicData()])
}

const showAccess = ref(false)
const accessProject = ref<AssetProjectVO | null>(null)
function openAccess(p: AssetProjectVO) { accessProject.value = p; showAccess.value = true }

function confirmDelete(p: AssetProjectVO) {
  dialog.warning({
    title: '确认删除项目',
    content: `确定删除项目「${p.name}」？项目内全部资产/成员/绑定将级联软删（文件保留），此操作不可恢复。`,
    positiveText: '删除',
    negativeText: '取消',
    onPositiveClick: async () => {
      try { await projectApi.remove(p.id); message.success('已删除'); await loadLocalData() }
      catch { message.error('删除失败') }
    }
  })
}

const unpublishingIds = ref(new Set<number>())
function confirmUnpublish(p: AssetProjectVO) {
  dialog.warning({
    title: '移出公共池',
    content: `确定将项目「${p.name}」移出公共池？已有公共访问将立即失效。`,
    positiveText: '移出公共池',
    negativeText: '取消',
    onPositiveClick: async () => {
      if (unpublishingIds.value.has(p.id)) return
      unpublishingIds.value = new Set(unpublishingIds.value).add(p.id)
      try {
        await publicPoolApi.unpublish(p.id)
        message.success('已移出公共池')
        await Promise.all([loadLocalData(), loadPublicData()])
      } catch {
        message.error('移出公共池失败')
      } finally {
        const next = new Set(unpublishingIds.value); next.delete(p.id); unpublishingIds.value = next
      }
    }
  })
}

const requestingIds = ref(new Set<number>())
async function requestPublicAccess(p: PublicProjectSummaryVO) {
  if (p.usable || p.myRequestStatus === 'PENDING' || requestingIds.value.has(p.id)) return
  requestingIds.value = new Set(requestingIds.value).add(p.id)
  try {
    await publicPoolApi.requestAccess(p.id)
    message.success('申请已提交')
    await loadPublicData()
  } catch {
    message.error('申请使用失败')
  } finally {
    const next = new Set(requestingIds.value); next.delete(p.id); requestingIds.value = next
  }
}

let localLoadSession = 0
async function loadLocalData() {
  const session = ++localLoadSession
  loading.value = true
  try {
    const res = await projectApi.list()
    if (session === localLoadSession) projects.value = res.data.data || []
  } catch (e) {
    console.error('加载项目列表失败:', e)
    message.error('加载项目列表失败')
  } finally {
    if (session === localLoadSession) loading.value = false
  }
}

let publicLoadSession = 0
async function loadPublicData() {
  const session = ++publicLoadSession
  publicLoading.value = true
  publicError.value = ''
  try {
    const res = await publicPoolApi.list()
    if (session === publicLoadSession) publicProjects.value = res.data.data || []
  } catch {
    if (session === publicLoadSession) publicError.value = '公共池加载失败，请重试'
  } finally {
    if (session === publicLoadSession) publicLoading.value = false
  }
}

async function loadData() { await Promise.all([loadLocalData(), loadPublicData()]) }

defineExpose({
  projects, publicProjects, activeTab, mineProjects, sharedProjects, canEdit,
  loading, publicLoading, publicError, form, showCreate, saving, showPublish,
  publishProject, showAccess, accessProject, requestingIds, unpublishingIds,
  openCreate, submitCreate, confirmDelete, confirmUnpublish, requestPublicAccess,
  loadData, loadLocalData, loadPublicData
})

onMounted(loadData)
</script>

<style lang="scss" scoped>
.asset-list {
  padding: var(--spacing-5);
  min-height: 100%;

  &__header { margin-bottom: var(--spacing-4); }
  &__title-row { display: flex; align-items: center; gap: var(--spacing-2); }
  &__title { margin: 0; font-size: var(--font-size-xl); font-weight: var(--font-weight-bold); color: var(--color-text-primary); }
  &__count { font-size: var(--font-size-sm); color: var(--color-text-tertiary); }
  &__spacer { flex: 1; }
  &__sub { color: var(--color-text-secondary); font-size: var(--font-size-sm); }
  &__tabs { margin-top: var(--spacing-3); }
  &__loading, &__empty { display: flex; align-items: center; justify-content: center; min-height: 280px; }
  &__forbidden { margin-top: var(--spacing-8); }
  &__public-error {
    min-height: 220px;
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: var(--spacing-3);
    color: var(--color-error);
  }
}

.asset-list__grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: var(--spacing-4);
  margin-top: var(--spacing-4);
  @media (max-width: 1200px) { grid-template-columns: repeat(3, 1fr); }
  @media (max-width: 900px) { grid-template-columns: repeat(2, 1fr); }
  @media (max-width: 600px) { grid-template-columns: 1fr; }
}

.project-card {
  position: relative;
  display: flex;
  flex-direction: column;
  background: var(--color-card-bg, var(--color-bg-secondary));
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg, 12px);
  cursor: pointer;
  overflow: hidden;
  transition: border-color var(--duration-fast), transform var(--duration-fast);
  &:hover { border-color: var(--color-primary); transform: translateY(-2px); }
  &__cover {
    height: 90px; display: flex; align-items: center; justify-content: center;
    font-size: 36px; font-weight: var(--font-weight-bold); color: var(--color-text-white, #fff);
    background: linear-gradient(135deg, var(--color-primary), var(--color-primary-hover, var(--color-primary)));
    &--public { background: linear-gradient(135deg, #6f4b13, #c18a27); }
  }
  &__body { display: flex; flex-direction: column; gap: var(--spacing-2); padding: var(--spacing-3); flex: 1; }
  &__name-row { display: flex; align-items: flex-start; justify-content: space-between; gap: var(--spacing-2); }
  &__name { font-size: var(--font-size-md); font-weight: var(--font-weight-bold); color: var(--color-text-primary); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  &__desc { font-size: var(--font-size-sm); color: var(--color-text-secondary); line-height: 1.4; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
  &__meta { display: flex; flex-wrap: wrap; gap: var(--spacing-2); font-size: var(--font-size-xs); color: var(--color-text-tertiary); }
  &__actions { position: absolute; top: var(--spacing-2); right: var(--spacing-2); display: flex; flex-wrap: wrap; justify-content: flex-end; gap: 2px; max-width: calc(100% - var(--spacing-4)); opacity: 0; transition: opacity var(--duration-fast); z-index: 2; }
  &:hover &__actions { opacity: 1; }
}

.public-project-card {
  &--locked { cursor: default; }
  &--locked:hover { border-color: var(--color-border); transform: none; }
  &__badges { display: flex; flex-wrap: wrap; justify-content: flex-end; gap: 4px; }
  &__footer { display: flex; justify-content: flex-end; margin-top: auto; padding-top: var(--spacing-1); }
}

@media (hover: none) { .project-card__actions { opacity: 1; } }
@media (max-width: 768px) {
  .asset-list { padding: var(--spacing-3); }
  .asset-list__title-row { flex-wrap: wrap; }
}
</style>
