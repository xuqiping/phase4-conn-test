<template>
  <div class="knowledge-view">
    <!-- 雾中浮岛场景层（ART-DIR-0002R 方向二：源头活水·层峦藏书，仅 ink 主题渲染） -->
    <ModuleScene scene="knowledge" />
    <!-- 高山流水批次C：统一页头（ART-DIR-0002 P3，ink 主题文楷+发丝线，旧主题零变化） -->
    <PageHeader title="知识库">
      <template #actions>
        <n-button v-if="canWrite" type="primary" @click="openCreate">
          <template #icon><n-icon :component="AddOutline" /></template>
          新建知识库
        </n-button>
      </template>
    </PageHeader>

    <n-tabs type="line" animated>
      <!-- Tab 1：KB 管理 -->
      <n-tab-pane name="bases" tab="知识库管理">
        <n-data-table
          :columns="columns"
          :data="store.bases"
          :loading="store.loadingBases"
          :pagination="{ pageSize: 10 }"
          :scroll-x="900"
          striped
        />

        <!-- 文档抽屉 -->
        <n-drawer v-model:show="showDocDrawer" :width="docDrawerWidth" placement="right">
          <n-drawer-content :title="`文档管理 · ${docKb?.name || ''}`" closable>
            <DocumentManager v-if="docKb" :kb-id="docKb.id" :can-write="docKb.canWrite" :can-manage="docKb.canManage" />
          </n-drawer-content>
        </n-drawer>

        <!-- KB 表单弹窗 -->
        <KbFormModal v-model:show="showFormModal" :edit-data="editingKb" @saved="onSaved" />

        <!-- 权限弹窗 -->
        <KbPermissionModal
          v-if="permKb"
          :show="showPermModal"
          :kb-id="permKb.id"
          @update:show="showPermModal = $event"
        />
      </n-tab-pane>

      <!-- Tab 2：检索调试 -->
      <n-tab-pane name="debug" tab="检索调试">
        <RetrievalDebugPanel />
      </n-tab-pane>

      <!-- Tab 3：RAG 流式问答 -->
      <n-tab-pane name="ask" tab="RAG 问答">
        <RagAskPanel />
      </n-tab-pane>

      <!-- Tab 3：检索审计（管理员 knowledge:manage）-->
      <n-tab-pane v-if="canManage" name="audit" tab="检索审计">
        <RetrievalAuditPanel />
      </n-tab-pane>
      <n-tab-pane v-if="canManage" name="index-ops" tab="索引运维">
        <IndexOperationsPanel :bases="store.bases" />
      </n-tab-pane>
      <n-tab-pane v-if="canManage" name="evaluation" tab="RAG 评测">
        <KnowledgeEvaluationView />
      </n-tab-pane>
    </n-tabs>
  </div>
</template>

<script setup lang="ts">
import { h, onMounted, ref, computed } from 'vue'
import {
  NButton, NDataTable, NDrawer, NDrawerContent, NIcon, NSpace, NTabPane, NTabs, NTag,
  useDialog, useMessage
} from 'naive-ui'
import type { DataTableColumns } from 'naive-ui'
import { AddOutline } from '@vicons/ionicons5'
import { useAuthStore } from '@/stores/auth'
import { useKnowledgeStore } from '@/stores/knowledge'
import { knowledgeApi, type KnowledgeBase } from '@/api/knowledge'
import KbFormModal from '@/components/knowledge/KbFormModal.vue'
import KbPermissionModal from '@/components/knowledge/KbPermissionModal.vue'
import DocumentManager from '@/components/knowledge/DocumentManager.vue'
import RetrievalDebugPanel from '@/components/knowledge/RetrievalDebugPanel.vue'
import RetrievalAuditPanel from '@/components/knowledge/RetrievalAuditPanel.vue'
import RagAskPanel from '@/components/knowledge/RagAskPanel.vue'
import IndexOperationsPanel from '@/components/knowledge/IndexOperationsPanel.vue'
import KnowledgeEvaluationView from '@/views/KnowledgeEvaluationView.vue'
import PageHeader from '@/components/PageHeader.vue'
import ModuleScene from '@/components/ModuleScene.vue'
import { useBreakpoints } from '@/composables/useBreakpoints'

const authStore = useAuthStore()
const store = useKnowledgeStore()
const message = useMessage()
const dialog = useDialog()
const { isMobile } = useBreakpoints()

const docDrawerWidth = computed(() => (isMobile.value ? '100%' : 640))

const canWrite = authStore.hasPermission('knowledge:write')
const canManage = authStore.hasPermission('knowledge:manage')

const showFormModal = ref(false)
const editingKb = ref<KnowledgeBase | null>(null)
const showDocDrawer = ref(false)
const docKb = ref<KnowledgeBase | null>(null)
const showPermModal = ref(false)
const permKb = ref<KnowledgeBase | null>(null)

const visibilityLabel: Record<string, string> = {
  PRIVATE: '私有', TEAM: '团队', PUBLIC: '公开'
}

const columns: DataTableColumns<KnowledgeBase> = [
  { title: 'ID', key: 'id', width: 60 },
  { title: '名称', key: 'name', ellipsis: { tooltip: true } },
  {
    title: '可见性', key: 'visibility', width: 130,
    render: r => [
      h(NTag, { size: 'small', bordered: false }, () => visibilityLabel[r.visibility] || r.visibility),
      // 14x#3：保密库锁标（owner/admin 视角=状态提示；成员视角=解释为何原件/调试不可用）
      r.confidential && h(NTag, {
        size: 'small', bordered: false, type: 'warning', round: true,
        title: '保密库：成员仅可经问答召回内容，原件/切片/检索调试均受限（库创建者与管理员不受限）'
      }, () => '🔒 保密')
    ].filter(Boolean)
  },
  { title: 'Embedding', key: 'embeddingModel', ellipsis: { tooltip: true }, render: r => r.embeddingModel || '-' },
  { title: '摘要策略', key: 'summaryStrategy', width: 130, render: r => r.summaryStrategy || '-' },
  { title: '状态', key: 'status', width: 90 },
  {
    title: '创建时间', key: 'createdAt', width: 160,
    render: r => new Date(r.createdAt).toLocaleString('zh-CN')
  },
  {
    title: '操作', key: 'actions', width: 240, fixed: 'right',
    render: r => h(NSpace, { size: 4 }, () => [
      h(NButton, { size: 'small', onClick: () => openDocs(r) }, () => '文档'),
      // 14x#2：编辑/删除仅 owner/admin（per-KB，canManage 授予位不含销毁库）；全局 knowledge:write 不再放行他人库
      canDestroy(r) && h(NButton, { size: 'small', onClick: () => openEdit(r) }, () => '编辑'),
      r.canManage && h(NButton, { size: 'small', onClick: () => openPerm(r) }, () => '授权'),
      canDestroy(r) && h(NButton, {
        size: 'small', quaternary: true, type: 'error', onClick: () => confirmDelete(r)
      }, () => '删除')
    ].filter(Boolean))
  }
]

/** 库级销毁/改名按钮判定：库创建者或 admin（与后端 isOwnerOrAdmin 对齐）。 */
function canDestroy(kb: KnowledgeBase): boolean {
  return authStore.isAdmin || kb.createdBy === authStore.userInfo?.id
}

function openCreate() {
  editingKb.value = null
  showFormModal.value = true
}
function openEdit(kb: KnowledgeBase) {
  editingKb.value = kb
  showFormModal.value = true
}
function openDocs(kb: KnowledgeBase) {
  docKb.value = kb
  showDocDrawer.value = true
}
function openPerm(kb: KnowledgeBase) {
  permKb.value = kb
  showPermModal.value = true
}

function confirmDelete(kb: KnowledgeBase) {
  dialog.warning({
    title: '确认删除',
    content: `确定删除知识库「${kb.name}」？其下文档与向量将一并清理。`,
    positiveText: '删除',
    negativeText: '取消',
    onPositiveClick: async () => {
      try {
        await knowledgeApi.deleteBase(kb.id)
        message.success('删除成功')
        await store.loadBases()
      } catch {
        message.error('删除失败')
      }
    }
  })
}

async function onSaved() {
  await store.loadBases()
}

onMounted(() => {
  void store.loadBases()
})
</script>

<style lang="scss" scoped>
.knowledge-view {
  padding: var(--spacing-6);
  height: 100%;
  overflow-y: auto;
}
// 页头已由 PageHeader 组件承担（批次C）

// 高山流水批次C · 层峦藏书：各 Tab 面板标题文楷化（仅 ink 主题，旧三主题零变化）
// 覆盖：Tab 标签、面板内 n-card 卡题（索引运维/RAG 评测等）、检索调试 h4 小节标题
[data-theme="ye-mo"],
[data-theme="xuan-zhi"] {
  .knowledge-view {
    :deep(.n-tabs-tab__label),
    :deep(.n-card-header__main),
    :deep(h4) {
      font-family: var(--font-display);
      font-weight: 400;
    }
  }
}

@media (max-width: 768px) {
  .knowledge-view {
    padding: var(--spacing-3);
  }
}
</style>
