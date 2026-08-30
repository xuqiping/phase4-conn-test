<!-- ============================================================
  二期 P3（FR-203）· 召回文件记忆卡片
  AI 回复下方渲染：图标 + 名称 + 类型·共N块 + 一句话总结；
  可下载（/api/files/{fileId} 鉴权 blob 下载）；「展开分块」按页码锚点列原文分块；
  原文件已删除（CLEANED）→ 置灰「原文件已删除」，禁下载/展开。
  ============================================================ -->
<template>
  <div class="file-card" :class="{ 'file-card--cleaned': card.fileCleaned }">
    <div class="file-card__head">
      <span class="file-card__icon">{{ kindIcon }}</span>
      <div class="file-card__title">
        <span class="file-card__name" :title="card.originalName">{{ card.originalName }}</span>
        <span class="file-card__meta">
          {{ fileKindLabel(card.fileKind) }}
          <template v-if="card.chunkCount > 0">· 共 {{ card.chunkCount }} 块</template>
          <span v-if="card.attached" class="file-card__attached">📎 本附件</span>
          <span v-else-if="card.attachStatus" class="file-card__attach-status"
                :class="`file-card__attach-status--${card.attachStatus.toLowerCase()}`"
          >{{ attachStatusText }}</span>
          <span v-if="card.weakMemory" class="file-card__weak">弱记忆</span>
        </span>
      </div>
      <div class="file-card__actions">
        <button
          v-if="card.downloadable && !card.fileCleaned"
          class="file-card__btn"
          :disabled="downloading"
          @click="handleDownload"
        >{{ downloading ? '下载中…' : '⬇ 下载' }}</button>
        <span v-else-if="card.fileCleaned" class="file-card__cleaned">原文件已删除</span>
        <button
          v-if="card.chunkCount > 0 && !card.fileCleaned"
          class="file-card__btn"
          @click="toggleChunks"
        >{{ expanded ? '收起分块' : '展开分块' }}</button>
      </div>
    </div>
    <div v-if="card.l1" class="file-card__l1">{{ card.l1 }}</div>
    <div v-if="expanded" class="file-card__chunks">
      <div v-if="chunksLoading" class="file-card__chunks-tip">加载分块中…</div>
      <div v-else-if="!chunks.length" class="file-card__chunks-tip">暂无分块</div>
      <div v-for="c in chunks" :key="c.chunkNo" class="file-card__chunk">
        <span v-if="c.pageRef" class="file-card__page-ref">[{{ c.pageRef }}]</span>
        <span class="file-card__chunk-text">{{ c.chunkText }}</span>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { memoryApi, fileKindLabel } from '@/api/memory'
import type { RecalledFileCard, FileChunkView } from '@/api/memory'
import { fetchFilePreview } from '@/api/file'

const props = defineProps<{
  card: RecalledFileCard
}>()

const kindIcon = computed(() => {
  switch (props.card.fileKind) {
    case 'IMAGE': return '🖼'
    case 'PDF': return '📕'
    case 'PPT': return '📊'
    case 'DOC': return '📄'
    case 'AUDIO': return '🎵'
    case 'VIDEO': return '🎬'
    default: return '📎'
  }
})

/** C5：非 READY 附件状态文案（PROCESSING=解析中不出分块；FAILED=解析失败可重试）。 */
const attachStatusText = computed(() => {
  switch (props.card.attachStatus) {
    case 'PROCESSING': return '解析中…'
    case 'FAILED': return '解析失败'
    case 'PENDING': return '待解析'
    default: return props.card.attachStatus || ''
  }
})

const downloading = ref(false)
const expanded = ref(false)
const chunksLoading = ref(false)
const chunks = ref<FileChunkView[]>([])

/** 下载：/api/files/{fileId} 需 JWT header，<a href> 带不了 → axios blob 转 objectURL 触发下载。 */
async function handleDownload() {
  if (downloading.value) return
  downloading.value = true
  try {
    const url = await fetchFilePreview(props.card.fileId)
    const a = document.createElement('a')
    a.href = url
    a.download = props.card.originalName || 'file'
    a.click()
    setTimeout(() => URL.revokeObjectURL(url), 30_000)
  } finally {
    downloading.value = false
  }
}

/** 展开分块：首点懒加载（页码锚点随块返回，回答引用可反查原文）。 */
async function toggleChunks() {
  expanded.value = !expanded.value
  // 项目收录附件下载卡 memoryId=null（无个人记忆分块，展开按钮 chunkCount>0 门控本不显示，双保险）
  if (!expanded.value || props.card.memoryId == null || chunks.value.length || chunksLoading.value) return
  chunksLoading.value = true
  try {
    const res = await memoryApi.listAttachmentChunks(props.card.memoryId)
    chunks.value = res.data.data || []
  } catch {
    chunks.value = []
  } finally {
    chunksLoading.value = false
  }
}
</script>

<style lang="scss" scoped>
.file-card {
  border: 1px solid var(--color-border-light);
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.02);
  padding: 10px 12px;

  &--cleaned {
    opacity: 0.65;
  }
}

.file-card__head {
  display: flex;
  align-items: center;
  gap: 10px;
}

.file-card__icon {
  font-size: 22px;
  flex-shrink: 0;
}

.file-card__title {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.file-card__name {
  font-size: 13px;
  color: var(--color-text-primary);
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.file-card__meta {
  font-size: 11px;
  color: var(--color-text-tertiary);
}

.file-card__weak {
  margin-left: 6px;
  padding: 0 6px;
  border-radius: 8px;
  background: rgba(230, 162, 60, 0.15);
  color: #e6a23c;
}

.file-card__attached {
  margin-left: 6px;
  padding: 0 6px;
  border-radius: 8px;
  background: rgba(103, 194, 58, 0.15);
  color: #67c23a;
}

.file-card__attach-status {
  margin-left: 6px;
  padding: 0 6px;
  border-radius: 8px;

  &--processing, &--pending {
    background: rgba(144, 147, 153, 0.15);
    color: #909399;
  }

  &--failed {
    background: rgba(245, 108, 108, 0.15);
    color: #f56c6c;
  }
}

.file-card__actions {
  display: flex;
  gap: 6px;
  flex-shrink: 0;
  align-items: center;
}

.file-card__btn {
  border: 1px solid var(--color-border-light);
  border-radius: 4px;
  background: var(--color-primary-light);
  color: var(--color-primary);
  font-size: 11px;
  padding: 3px 8px;
  cursor: pointer;

  &:hover:not(:disabled) {
    opacity: 0.85;
  }

  &:disabled {
    opacity: 0.5;
    cursor: default;
  }
}

.file-card__cleaned {
  font-size: 11px;
  color: var(--color-text-tertiary);
}

.file-card__l1 {
  margin-top: 6px;
  font-size: 12px;
  color: var(--color-text-secondary);
  line-height: 1.5;
  word-break: break-word;
}

.file-card__chunks {
  margin-top: 8px;
  border-top: 1px dashed var(--color-border-light);
  padding-top: 8px;
  max-height: 260px;
  overflow-y: auto;
}

.file-card__chunks-tip {
  font-size: 12px;
  color: var(--color-text-tertiary);
}

.file-card__chunk {
  display: flex;
  gap: 6px;
  padding: 3px 0;
  font-size: 12px;
  line-height: 1.5;
}

.file-card__page-ref {
  flex-shrink: 0;
  color: var(--color-primary);
  font-weight: 600;
}

.file-card__chunk-text {
  color: var(--color-text-secondary);
  white-space: pre-wrap;
  word-break: break-word;
}
</style>
