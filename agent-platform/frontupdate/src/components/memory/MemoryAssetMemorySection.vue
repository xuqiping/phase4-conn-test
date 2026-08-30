<!-- ============================================================
  二期 P3（FR-201~205）· 文件记忆页签
  我的文件记忆列表：状态徽标（解析中/就绪/失败可重试）+ 弱记忆标 + 一句话总结；
  操作：下载原文件（blob 鉴权）/ FAILED 重试（上限硬卡）/ 删除（项目 FILE 条目同步失效）。
  ============================================================ -->
<template>
  <div class="asset-memory">
    <div class="asset-memory__bar">
      <span class="asset-memory__hint">聊天附件自动解析为文件记忆，召回命中时随回答给出文件卡片</span>
      <n-button size="tiny" quaternary :loading="loading" @click="load">刷新</n-button>
    </div>
    <div v-if="!loading && !rows.length" class="asset-memory__empty">
      暂无文件记忆——在聊天输入框点 📎 上传附件即可
    </div>
    <div v-for="row in rows" :key="row.id" class="asset-memory__item">
      <div class="asset-memory__item-head">
        <span class="asset-memory__icon">{{ kindIcon(row.fileKind) }}</span>
        <span class="asset-memory__name" :title="row.originalName || ''">{{ row.originalName || '（未命名）' }}</span>
        <n-tag size="tiny" :type="statusTagType(row.ingestStatus)" class="asset-memory__status">
          {{ statusLabel(row) }}
        </n-tag>
        <n-tag v-if="row.weakMemory" size="tiny" type="warning" class="asset-memory__status">弱记忆</n-tag>
      </div>
      <div class="asset-memory__meta">
        {{ fileKindLabel(row.fileKind) }}
        <template v-if="row.createdAt">· {{ formatTime(row.createdAt) }}</template>
        <template v-if="row.ingestStatus === 'FAILED' && row.retryCount != null">· 已重试 {{ row.retryCount }} 次</template>
      </div>
      <div v-if="row.l1Summary" class="asset-memory__l1">{{ row.l1Summary }}</div>
      <!-- 5x 四轮 C6：收录于徽标（仅本人可见项目；未收录不显示负信息） -->
      <div v-if="row.projectNames && row.projectNames.length" class="asset-memory__projects">
        收录于：<n-tag
          v-for="name in row.projectNames"
          :key="name"
          size="small"
          :bordered="false"
          class="asset-memory__project-tag"
        >{{ name }}</n-tag>
      </div>
      <div v-if="row.ingestStatus === 'FAILED' && row.ingestError" class="asset-memory__error">{{ row.ingestError }}</div>
      <div class="asset-memory__actions">
        <n-button
          v-if="row.fileId"
          size="tiny"
          quaternary
          type="primary"
          :disabled="downloadingId === row.id"
          @click="handleDownload(row)"
        >{{ downloadingId === row.id ? '下载中…' : '下载原文件' }}</n-button>
        <n-button
          v-if="row.ingestStatus === 'FAILED'"
          size="tiny"
          quaternary
          type="warning"
          :disabled="actingId === row.id"
          @click="handleRetry(row)"
        >重试解析</n-button>
        <n-popconfirm @positive-click="handleDelete(row)">
          <template #trigger>
            <n-button size="tiny" quaternary type="error" :disabled="actingId === row.id">删除</n-button>
          </template>
          删除后原文件与项目中的引用条目一并失效，确定删除？
        </n-popconfirm>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { NButton, NTag, NPopconfirm, useMessage } from 'naive-ui'
import { memoryApi, fileKindLabel } from '@/api/memory'
import type { MemoryAssetMemoryVO } from '@/api/memory'
import { fetchFilePreview } from '@/api/file'

const message = useMessage()
const rows = ref<MemoryAssetMemoryVO[]>([])
const loading = ref(false)
const actingId = ref<number | null>(null)
const downloadingId = ref<number | null>(null)

function kindIcon(fileKind: string) {
  switch (fileKind) {
    case 'IMAGE': return '🖼'
    case 'PDF': return '📕'
    case 'PPT': return '📊'
    case 'DOC': return '📄'
    case 'AUDIO': return '🎵'
    case 'VIDEO': return '🎬'
    default: return '📎'
  }
}

function statusLabel(row: MemoryAssetMemoryVO) {
  switch (row.ingestStatus) {
    case 'PROCESSING': return '解析中'
    case 'READY': return '就绪'
    case 'FAILED': return '解析失败'
    default: return row.ingestStatus
  }
}

function statusTagType(status: MemoryAssetMemoryVO['ingestStatus']) {
  switch (status) {
    case 'PROCESSING': return 'info' as const
    case 'READY': return 'success' as const
    case 'FAILED': return 'error' as const
    default: return 'default' as const
  }
}

function formatTime(iso: string) {
  const d = new Date(iso)
  return Number.isNaN(d.getTime()) ? iso : d.toLocaleString()
}

async function load() {
  loading.value = true
  try {
    const res = await memoryApi.listAttachments()
    rows.value = res.data.data || []
  } finally {
    loading.value = false
  }
}

/** 下载：/api/files/{fileId} 需 JWT header → axios blob 转 objectURL；原文件已删 → 404 toast（拦截器）。 */
async function handleDownload(row: MemoryAssetMemoryVO) {
  if (!row.fileId || downloadingId.value) return
  downloadingId.value = row.id
  try {
    const url = await fetchFilePreview(row.fileId)
    const a = document.createElement('a')
    a.href = url
    a.download = row.originalName || 'file'
    a.click()
    setTimeout(() => URL.revokeObjectURL(url), 30_000)
  } catch {
    // 拦截器已 toast（原文件删除/无权限）
  } finally {
    downloadingId.value = null
  }
}

async function handleRetry(row: MemoryAssetMemoryVO) {
  actingId.value = row.id
  try {
    await memoryApi.retryAttachment(row.id)
    message.success('已重新排队解析')
    await load()
  } catch {
    // 拦截器已 toast（超上限/状态冲突）
  } finally {
    actingId.value = null
  }
}

async function handleDelete(row: MemoryAssetMemoryVO) {
  actingId.value = row.id
  try {
    await memoryApi.deleteAttachment(row.id)
    message.success('已删除')
    rows.value = rows.value.filter(r => r.id !== row.id)
  } catch {
    // 拦截器已 toast
  } finally {
    actingId.value = null
  }
}

onMounted(load)
</script>

<style lang="scss" scoped>
.asset-memory {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.asset-memory__bar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
}

.asset-memory__hint {
  font-size: 12px;
  color: var(--color-text-tertiary);
}

.asset-memory__empty {
  font-size: 13px;
  color: var(--color-text-tertiary);
  padding: 24px 0;
  text-align: center;
}

.asset-memory__item {
  border: 1px solid var(--color-border-light);
  border-radius: 8px;
  padding: 10px 12px;
  background: rgba(255, 255, 255, 0.02);
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.asset-memory__item-head {
  display: flex;
  align-items: center;
  gap: 8px;
}

.asset-memory__icon {
  font-size: 18px;
  flex-shrink: 0;
}

.asset-memory__name {
  flex: 1;
  min-width: 0;
  font-size: 13px;
  font-weight: 600;
  color: var(--color-text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.asset-memory__status {
  flex-shrink: 0;
}

.asset-memory__meta {
  font-size: 11px;
  color: var(--color-text-tertiary);
}

.asset-memory__l1 {
  font-size: 12px;
  color: var(--color-text-secondary);
  line-height: 1.5;
  word-break: break-word;
}

.asset-memory__projects {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 4px;
  font-size: 11px;
  color: var(--color-text-tertiary);
}

.asset-memory__project-tag {
  font-size: 11px;
}

.asset-memory__error {
  font-size: 12px;
  color: #d03050;
}

.asset-memory__actions {
  display: flex;
  gap: 4px;
}
</style>
