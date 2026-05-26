<!-- ============================================================
  工作流卡片 — 列表页中的单个工作流卡片
  ============================================================ -->
<template>
  <div class="workflow-card" @click="emit('edit', workflow.id)">
    <div class="workflow-card__header">
      <div class="workflow-card__icon">
        <n-icon size="20" :component="GitBranchOutline" color="var(--color-primary)" />
      </div>
      <n-tag :type="statusTagType" size="small" round>
        {{ statusLabel }}
      </n-tag>
    </div>

    <div class="workflow-card__body">
      <h3 class="workflow-card__name">{{ workflow.name }}</h3>
      <p v-if="workflow.description" class="workflow-card__desc">{{ workflow.description }}</p>
    </div>

    <div class="workflow-card__footer">
      <div class="workflow-card__meta">
        <span class="workflow-card__stat">
          <n-icon size="14" :component="GitCommitOutline" />
          {{ workflow.nodeCount }} 个节点
        </span>
        <span class="workflow-card__time">{{ formatTime(workflow.updatedAt) }}</span>
      </div>

      <div class="workflow-card__actions" @click.stop>
        <n-tooltip trigger="hover" placement="top">
          <template #trigger>
            <n-button quaternary size="tiny" @click="emit('edit', workflow.id)">
              <template #icon>
                <n-icon :component="CreateOutline" />
              </template>
            </n-button>
          </template>
          编辑
        </n-tooltip>

        <n-tooltip trigger="hover" placement="top">
          <template #trigger>
            <n-button quaternary size="tiny" @click="emit('duplicate', workflow.id)">
              <template #icon>
                <n-icon :component="CopyOutline" />
              </template>
            </n-button>
          </template>
          复制
        </n-tooltip>

        <n-tooltip trigger="hover" placement="top">
          <template #trigger>
            <n-button quaternary size="tiny" @click="emit('export', workflow.id)">
              <template #icon>
                <n-icon :component="DownloadOutline" />
              </template>
            </n-button>
          </template>
          导出
        </n-tooltip>

        <n-tooltip trigger="hover" placement="top">
          <template #trigger>
            <n-button quaternary size="tiny" @click="handleDelete">
              <template #icon>
                <n-icon :component="TrashOutline" />
              </template>
            </n-button>
          </template>
          删除
        </n-tooltip>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { NIcon, NButton, NTag, NTooltip, useDialog, useMessage } from 'naive-ui'
import {
  GitBranchOutline,
  GitCommitOutline,
  CreateOutline,
  CopyOutline,
  DownloadOutline,
  TrashOutline
} from '@vicons/ionicons5'
import type { WorkflowListItem, WorkflowStatus } from '@/types/workflow'

const props = defineProps<{
  workflow: WorkflowListItem
}>()

const emit = defineEmits<{
  (e: 'edit', id: number): void
  (e: 'duplicate', id: number): void
  (e: 'export', id: number): void
  (e: 'delete', id: number): void
}>()

const dialog = useDialog()
const message = useMessage()

const statusTagType = (() => {
  const map: Record<string, string> = {
    draft: 'default',
    published: 'success',
    archived: 'warning'
  }
  return map[props.workflow.status] || 'default'
})()

const statusLabel = (() => {
  const map: Record<string, string> = {
    draft: '草稿',
    published: '已发布',
    archived: '已归档'
  }
  return map[props.workflow.status] || '未知'
})()

/** 格式化时间 */
function formatTime(dateStr: string): string {
  const date = new Date(dateStr)
  const now = new Date()
  const diff = now.getTime() - date.getTime()
  const minutes = Math.floor(diff / 60000)
  const hours = Math.floor(diff / 3600000)
  const days = Math.floor(diff / 86400000)

  if (minutes < 1) return '刚刚'
  if (minutes < 60) return `${minutes}分钟前`
  if (hours < 24) return `${hours}小时前`
  if (days < 30) return `${days}天前`
  return date.toLocaleDateString('zh-CN')
}

/** 删除确认 */
function handleDelete() {
  dialog.warning({
    title: '确认删除',
    content: `确定要删除工作流「${props.workflow.name}」吗？此操作不可恢复。`,
    positiveText: '删除',
    negativeText: '取消',
    onPositiveClick: () => {
      emit('delete', props.workflow.id)
    }
  })
}
</script>

<style lang="scss" scoped>
.workflow-card {
  background: var(--color-card);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  padding: var(--spacing-4);
  cursor: pointer;
  transition: border-color var(--duration-fast) var(--ease-in-out),
              box-shadow var(--duration-fast) var(--ease-in-out),
              transform var(--duration-fast) var(--ease-in-out);

  &:hover {
    border-color: var(--color-primary);
    box-shadow: var(--shadow-primary);
    transform: translateY(-2px);
  }
}

.workflow-card__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--spacing-3);
}

.workflow-card__icon {
  width: 36px;
  height: 36px;
  border-radius: var(--radius-md);
  background: var(--color-elevated);
  display: flex;
  align-items: center;
  justify-content: center;
}

.workflow-card__body {
  margin-bottom: var(--spacing-3);
}

.workflow-card__name {
  font-size: var(--font-size-md);
  font-weight: var(--font-weight-semibold);
  color: var(--color-text-primary);
  margin: 0 0 var(--spacing-1) 0;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.workflow-card__desc {
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
  margin: 0;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.workflow-card__footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.workflow-card__meta {
  display: flex;
  align-items: center;
  gap: var(--spacing-3);
}

.workflow-card__stat {
  display: flex;
  align-items: center;
  gap: var(--spacing-1);
  font-size: var(--font-size-xs);
  color: var(--color-text-tertiary);
}

.workflow-card__time {
  font-size: var(--font-size-xs);
  color: var(--color-text-tertiary);
}

.workflow-card__actions {
  display: flex;
  align-items: center;
  gap: 2px;
  opacity: 0;
  transition: opacity var(--duration-fast) var(--ease-in-out);

  .workflow-card:hover & {
    opacity: 1;
  }
}
</style>
