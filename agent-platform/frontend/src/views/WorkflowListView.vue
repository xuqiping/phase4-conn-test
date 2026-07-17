<!-- ============================================================
  工作流列表页 — 卡片网格+新建/编辑/复制/删除/导出操作
  ============================================================ -->
<template>
  <div class="workflow-list">
    <!-- 页面头部 -->
    <div class="workflow-list__header">
      <div class="workflow-list__header-left">
        <h1 class="workflow-list__title">工作流管理</h1>
        <span class="workflow-list__count">共 {{ workflows.length }} 个工作流</span>
      </div>
      <div class="workflow-list__header-right">
        <n-input
          v-model:value="searchKeyword"
          placeholder="搜索工作流..."
          clearable
          size="small"
          class="workflow-list__search"
        >
          <template #prefix>
            <n-icon :component="SearchOutline" />
          </template>
        </n-input>
        <n-button size="small" @click="showImportModal = true">
          <template #icon>
            <n-icon :component="CloudUploadOutline" />
          </template>
          导入
        </n-button>
        <n-button size="small" type="primary" @click="createNew">
          <template #icon>
            <n-icon :component="AddOutline" />
          </template>
          新建工作流
        </n-button>
      </div>
    </div>

    <!-- 加载状态 -->
    <div v-if="loading" class="workflow-list__loading">
      <n-spin size="large" />
    </div>

    <!-- 空状态 -->
    <div v-else-if="workflows.length === 0" class="workflow-list__empty">
      <n-icon size="48" :component="GitBranchOutline" color="var(--color-text-tertiary)" />
      <h3>暂无工作流</h3>
      <p>点击「新建工作流」开始创建</p>
      <n-button type="primary" @click="createNew">
        <template #icon>
          <n-icon :component="AddOutline" />
        </template>
        新建工作流
      </n-button>
    </div>

    <!-- 卡片网格 -->
    <div v-else class="workflow-list__grid">
      <WorkflowCard
        v-for="wf in filteredWorkflows"
        :key="wf.id"
        :workflow="wf"
        @edit="goToEditor"
        @duplicate="handleDuplicate"
        @export="handleExport"
        @delete="handleDelete"
      />
    </div>

    <!-- 导入弹窗 -->
    <n-modal
      v-model:show="showImportModal"
      preset="dialog"
      title="导入工作流"
      positive-text="导入"
      negative-text="取消"
      :positive-button-props="{ disabled: !importJson }"
      @positive-click="handleImport"
    >
      <n-upload
        :max="1"
        accept=".json"
        :default-upload="false"
        @change="onImportFileChange"
      >
        <n-upload-dragger>
          <div style="padding: 20px; text-align: center;">
            <n-icon size="32" :component="CloudUploadOutline" color="var(--color-text-tertiary)" />
            <p style="margin-top: 8px; color: var(--color-text-secondary);">
              点击或拖拽JSON文件到此处
            </p>
          </div>
        </n-upload-dragger>
      </n-upload>
    </n-modal>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useMessage } from 'naive-ui'
import {
  NButton,
  NIcon,
  NInput,
  NSpin,
  NModal,
  NUpload,
  NUploadDragger
} from 'naive-ui'
import {
  SearchOutline,
  AddOutline,
  CloudUploadOutline,
  GitBranchOutline
} from '@vicons/ionicons5'
import WorkflowCard from '@/components/workflow/WorkflowCard.vue'
import { workflowApi } from '@/api/workflow'
import type { WorkflowListItem, Workflow } from '@/types/workflow'
import type { UploadFileInfo } from 'naive-ui'

const router = useRouter()
const message = useMessage()

const loading = ref(false)
const searchKeyword = ref('')
const workflows = ref<WorkflowListItem[]>([])
const showImportModal = ref(false)
const importJson = ref<Workflow | null>(null)

/** 搜索过滤 */
const filteredWorkflows = computed(() => {
  if (!searchKeyword.value) return workflows.value
  const keyword = searchKeyword.value.toLowerCase()
  return workflows.value.filter(
    wf => wf.name.toLowerCase().includes(keyword) ||
         (wf.description && wf.description.toLowerCase().includes(keyword))
  )
})

/** 加载工作流列表 */
async function loadWorkflows() {
  loading.value = true
  try {
    const res = await workflowApi.list()
    workflows.value = res.data.data
  } catch {
    message.error('加载工作流列表失败')
  } finally {
    loading.value = false
  }
}

/** 新建工作流 */
function createNew() {
  router.push('/workflow/new')
}

/** 跳转到编辑器 */
function goToEditor(id: number) {
  router.push(`/workflow/${id}`)
}

/** 复制工作流 */
async function handleDuplicate(id: number) {
  try {
    await workflowApi.duplicate(id)
    message.success('复制成功')
    await loadWorkflows()
  } catch {
    message.error('复制失败')
  }
}

/** 导出工作流 */
async function handleExport(id: number) {
  try {
    const res = await workflowApi.exportJson(id)
    const json = JSON.stringify(res.data.data, null, 2)
    const blob = new Blob([json], { type: 'application/json' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    const wf = workflows.value.find(w => w.id === id)
    a.download = `${wf?.name || 'workflow'}.json`
    a.click()
    URL.revokeObjectURL(url)
    message.success('导出成功')
  } catch {
    message.error('导出失败')
  }
}

/** 删除工作流 */
async function handleDelete(id: number) {
  try {
    await workflowApi.remove(id)
    message.success('删除成功')
    await loadWorkflows()
  } catch {
    message.error('删除失败')
  }
}

/** 导入文件选择 */
function onImportFileChange({ file }: { file: UploadFileInfo }) {
  if (file.file) {
    const reader = new FileReader()
    reader.onload = (e) => {
      try {
        importJson.value = JSON.parse(e.target?.result as string) as Workflow
      } catch {
        message.error('无效的JSON文件')
        importJson.value = null
      }
    }
    reader.readAsText(file.file)
  }
}

/** 执行导入 */
async function handleImport() {
  if (!importJson.value) return false
  try {
    await workflowApi.importJson(importJson.value)
    message.success('导入成功')
    showImportModal.value = false
    importJson.value = null
    await loadWorkflows()
    return true
  } catch {
    message.error('导入失败')
    return false
  }
}

onMounted(() => {
  loadWorkflows()
})
</script>

<style lang="scss" scoped>
.workflow-list {
  min-height: 100%;
}

.workflow-list__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--spacing-6);
}

.workflow-list__header-left {
  display: flex;
  align-items: baseline;
  gap: var(--spacing-3);
}

.workflow-list__title {
  font-size: var(--font-size-2xl);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
  margin: 0;
}

.workflow-list__count {
  font-size: var(--font-size-sm);
  color: var(--color-text-tertiary);
}

.workflow-list__header-right {
  display: flex;
  align-items: center;
  gap: var(--spacing-2);
}

.workflow-list__search {
  width: 200px;
}

.workflow-list__loading {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: var(--spacing-16);
}

.workflow-list__empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: var(--spacing-16);
  gap: var(--spacing-3);

  h3 {
    color: var(--color-text-secondary);
    font-size: var(--font-size-lg);
    margin: 0;
  }

  p {
    color: var(--color-text-tertiary);
    font-size: var(--font-size-sm);
    margin: 0;
  }
}

.workflow-list__grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
  gap: var(--spacing-4);
}

@media (max-width: 768px) {
  .workflow-list__header {
    flex-direction: column;
    align-items: stretch;
    gap: var(--spacing-3);
    margin-bottom: var(--spacing-4);
  }
  .workflow-list__header-right {
    flex-wrap: wrap;
  }
  .workflow-list__search {
    width: 100%;
  }
  .workflow-list__grid {
    grid-template-columns: 1fr;
  }
}
</style>
