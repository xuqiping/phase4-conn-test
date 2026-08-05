<!--
  项目资产库·项目列表页（/assets）  plan §S9
  - 我的项目 / 共享给我 两 Tab（按 ProjectVO.role 拆：OWNER 归「我的」，EDITOR/VIEWER 归「共享」）
  - 卡片网格（响应式 4→3→2→1，同 AgentHall 范式）
  - 新建项目内联弹窗（name+description；narrativeRoles 走后端默认五桶）
  - 删除项目（owner only，L4 级联软删二次确认）
  - 权限三重兜底：菜单隐藏（Sidebar hasPermission）+ 页内 canEdit + API 403
-->
<template>
  <div class="asset-list">
    <!-- 顶部：标题 + 计数 + 新建 -->
    <div class="asset-list__header">
      <div class="asset-list__title-row">
        <h2 class="asset-list__title">资产库</h2>
        <span class="asset-list__count">{{ projects.length }} 个项目</span>
        <div class="asset-list__spacer" />
        <n-button v-if="canEdit" type="primary" @click="openCreate">+ 新建项目</n-button>
      </div>
      <span class="asset-list__sub">项目级资产中枢 · 五类资产 × 叙事角色双轴矩阵</span>
    </div>

    <!-- 无权限：直访 URL 兜底（菜单已隐藏入口） -->
    <n-empty
      v-if="!canEdit"
      description="无 asset:write 权限，请联系管理员授权"
      class="asset-list__forbidden"
    />

    <template v-else>
      <!-- 我的 / 共享 Tab -->
      <n-tabs v-model:value="activeTab" type="line" animated class="asset-list__tabs">
        <n-tab-pane name="mine" :tab="`我的项目（${mineProjects.length}）`">
          <project-grid
            :loading="loading"
            :projects="mineProjects"
            @open="openProject"
            @share="openShare"
            @delete="confirmDelete"
          />
        </n-tab-pane>
        <n-tab-pane name="shared" :tab="`共享给我（${sharedProjects.length}）`">
          <project-grid
            :loading="loading"
            :projects="sharedProjects"
            @open="openProject"
            @share="openShare"
            @delete="confirmDelete"
          />
        </n-tab-pane>
      </n-tabs>
    </template>

    <!-- 新建项目弹窗（内联，同 AgentFormModal 范式） -->
    <n-modal v-model:show="showCreate" preset="card" title="新建项目" style="max-width:480px">
      <n-form ref="formRef" :model="form" :rules="rules" label-placement="top">
        <n-form-item label="项目名称" path="name">
          <n-input v-model:value="form.name" placeholder="如：我的短剧第一季" :maxlength="100" show-count />
        </n-form-item>
        <n-form-item label="描述" path="description">
          <n-input
            v-model:value="form.description"
            type="textarea"
            placeholder="可选，项目简介"
            :rows="3"
            :maxlength="500"
          />
        </n-form-item>
      </n-form>
      <template #action>
        <n-button @click="showCreate = false">取消</n-button>
        <n-button type="primary" :loading="saving" @click="submitCreate">创建</n-button>
      </template>
    </n-modal>
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
import { projectApi } from '@/api/assets'
import { useAuthStore } from '@/stores/auth'
import type { AssetProjectVO, ProjectRole } from '@/types/asset'

/** 卡片网格 + 空态 + 加载（内部子组件，避免外层模板膨胀） */
const ProjectGrid = (props: { loading: boolean; projects: AssetProjectVO[] }, { emit }: { emit: (e: 'open' | 'share' | 'delete', p: AssetProjectVO) => void }) => {
  if (props.loading) {
    return h('div', { class: 'asset-list__loading' }, [h(NSpin, { size: 'large' })])
  }
  if (props.projects.length === 0) {
    return h(NEmpty, { description: '暂无项目', class: 'asset-list__empty' })
  }
  return h(
    'div',
    { class: 'asset-list__grid' },
    props.projects.map((p) =>
      h(
        'div',
        {
          class: 'project-card',
          key: p.id,
          onClick: () => emit('open', p)
        },
        [
          // 封面占位（首字母色块；coverFileId 上传 S11 引入）
          h('div', { class: 'project-card__cover' }, p.name.slice(0, 1) || '项'),
          h('div', { class: 'project-card__body' }, [
            h('div', { class: 'project-card__name-row' }, [
              h('span', { class: 'project-card__name' }, p.name),
              h(NTag, { size: 'small', bordered: false, type: ROLE_TYPE[p.role] }, () => ROLE_LABEL[p.role])
            ]),
            h(
              'div',
              { class: 'project-card__desc' },
              p.description || '暂无描述'
            ),
            h(
              'div',
              { class: 'project-card__meta' },
              `${p.narrativeRoles?.length ?? 0} 个叙事角色`
            )
          ]),
          // owner 卡片悬浮操作（分享 / 删除；点击冒泡拦截）
          p.role === 'OWNER'
            ? h('div', { class: 'project-card__actions' }, [
                h(
                  NButton,
                  {
                    size: 'tiny',
                    quaternary: true,
                    onClick: (e: Event) => {
                      e.stopPropagation()
                      emit('share', p)
                    }
                  },
                  () => '分享'
                ),
                h(
                  NButton,
                  {
                    size: 'tiny',
                    quaternary: true,
                    type: 'error',
                    onClick: (e: Event) => {
                      e.stopPropagation()
                      emit('delete', p)
                    }
                  },
                  () => '删除'
                )
              ])
            : null
        ]
      )
    )
  )
}
ProjectGrid.props = ['loading', 'projects']
ProjectGrid.emits = ['open', 'share', 'delete']

const authStore = useAuthStore()
const message = useMessage()
const dialog = useDialog()
const router = useRouter()

/** asset:write gated（admin 默认有，普通 user 须授权，同 canvas:write） */
const canEdit = computed(() => authStore.hasPermission('asset:write'))

const projects = ref<AssetProjectVO[]>([])
const loading = ref(true)
const activeTab = ref<'mine' | 'shared'>('mine')

/** OWNER 归「我的」；EDITOR/VIEWER 归「共享给我」 */
const mineProjects = computed(() => projects.value.filter((p) => p.role === 'OWNER'))
const sharedProjects = computed(() => projects.value.filter((p) => p.role !== 'OWNER'))

// 新建项目表单
const showCreate = ref(false)
const saving = ref(false)
const formRef = ref<FormInst | null>(null)
const form = ref({ name: '', description: '' })
const rules: FormRules = {
  name: [{ required: true, message: '请输入项目名称', trigger: 'blur' }]
}

/** 角色中文标签 + Tag 配色（设计 §七 7.2） */
const ROLE_LABEL: Record<ProjectRole, string> = {
  OWNER: '所有者',
  EDITOR: '编辑者',
  VIEWER: '浏览者'
}
const ROLE_TYPE: Record<ProjectRole, 'success' | 'info' | 'default'> = {
  OWNER: 'success',
  EDITOR: 'info',
  VIEWER: 'default'
}

function openCreate() {
  form.value = { name: '', description: '' }
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
    await projectApi.create({
      name: form.value.name.trim(),
      description: form.value.description.trim() || undefined
    })
    message.success('项目已创建')
    showCreate.value = false
    await loadData()
  } catch {
    message.error('创建失败')
  } finally {
    saving.value = false
  }
}

function openProject(p: AssetProjectVO) {
  router.push(`/assets/${p.id}`)
}

/** 分享弹窗 S9-9b 接入；占位提示防卡死 */
function openShare(_p: AssetProjectVO) {
  message.info('分享弹窗（S9-9b 建设中）')
}

function confirmDelete(p: AssetProjectVO) {
  dialog.warning({
    title: '确认删除项目',
    content: `确定删除项目「${p.name}」？项目内全部资产/成员/绑定将级联软删（文件保留），此操作不可恢复。`,
    positiveText: '删除',
    negativeText: '取消',
    onPositiveClick: async () => {
      try {
        await projectApi.remove(p.id)
        message.success('已删除')
        await loadData()
      } catch {
        message.error('删除失败')
      }
    }
  })
}

async function loadData() {
  loading.value = true
  try {
    const res = await projectApi.list()
    projects.value = res.data.data || []
  } catch (e) {
    console.error('加载项目列表失败:', e)
    message.error('加载项目列表失败')
  } finally {
    loading.value = false
  }
}

// 暴露纯逻辑/状态供单测断言（同 AgentFormModal defineExpose 范式）
defineExpose({
  projects,
  activeTab,
  mineProjects,
  sharedProjects,
  canEdit,
  form,
  showCreate,
  saving,
  openCreate,
  submitCreate,
  confirmDelete,
  loadData
})

onMounted(loadData)
</script>

<style lang="scss" scoped>
.asset-list {
  padding: var(--spacing-5);
  min-height: 100%;

  &__header {
    margin-bottom: var(--spacing-4);
  }

  &__title-row {
    display: flex;
    align-items: center;
    gap: var(--spacing-2);
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

  &__tabs {
    margin-top: var(--spacing-3);
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
}

.asset-list__grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: var(--spacing-4);
  margin-top: var(--spacing-4);

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

  &:hover {
    border-color: var(--color-primary);
    transform: translateY(-2px);
  }

  &__cover {
    height: 90px;
    display: flex;
    align-items: center;
    justify-content: center;
    font-size: 36px;
    font-weight: var(--font-weight-bold);
    color: var(--color-text-white, #fff);
    background: linear-gradient(135deg, var(--color-primary), var(--color-primary-hover, var(--color-primary)));
  }

  &__body {
    display: flex;
    flex-direction: column;
    gap: var(--spacing-2);
    padding: var(--spacing-3);
  }

  &__name-row {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: var(--spacing-2);
  }

  &__name {
    font-size: var(--font-size-md);
    font-weight: var(--font-weight-bold);
    color: var(--color-text-primary);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  &__desc {
    font-size: var(--font-size-sm);
    color: var(--color-text-secondary);
    line-height: 1.4;
    display: -webkit-box;
    -webkit-line-clamp: 2;
    -webkit-box-orient: vertical;
    overflow: hidden;
  }

  &__meta {
    font-size: var(--font-size-xs);
    color: var(--color-text-tertiary);
  }

  &__actions {
    position: absolute;
    top: var(--spacing-2);
    right: var(--spacing-2);
    display: flex;
    gap: 2px;
    opacity: 0;
    transition: opacity var(--duration-fast);
    z-index: 2;
  }

  &:hover &__actions {
    opacity: 1;
  }
}

// 触屏设备无 hover，操作按钮常驻可见
@media (hover: none) {
  .project-card__actions {
    opacity: 1;
  }
}

// === 移动端 ===
@media (max-width: 768px) {
  .asset-list {
    padding: var(--spacing-3);
  }

  .asset-list__title-row {
    flex-wrap: wrap;
  }
}
</style>
