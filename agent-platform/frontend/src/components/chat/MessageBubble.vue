<template>
  <div class="message-bubble" :class="`message-bubble--${message.role.toLowerCase()}`">
    <div class="message-bubble__avatar">
      <div v-if="message.role === 'USER'" class="message-bubble__avatar-icon message-bubble__avatar-icon--user">
        <n-icon size="18" :component="PersonOutline" />
      </div>
      <div v-else class="message-bubble__avatar-icon message-bubble__avatar-icon--assistant">
        <n-icon size="18" :component="SparklesOutline" />
      </div>
    </div>
    <div class="message-bubble__content">
      <div class="message-bubble__role">
        <span>{{ message.role === 'USER' ? '你' : '助手' }}</span>
        <!-- 5x 四轮 U5：用户/助手气泡各自独立复制按钮（复制正文，不含思考块） -->
        <button
          v-if="message.content"
          class="message-bubble__copy"
          :title="copied ? '已复制' : '复制全文'"
          @click="copyContent"
        >
          <n-icon size="13" :component="copied ? CheckmarkOutline : CopyOutline" />
          <span>{{ copied ? '已复制' : '复制' }}</span>
        </button>
      </div>
      <!-- Thinking section -->
      <div v-if="thinkingText" class="message-bubble__thinking">
        <div class="message-bubble__thinking-toggle" @click="showThinking = !showThinking">
          <span>💭 思考过程</span>
          <span class="message-bubble__thinking-action">{{ showThinking ? '收起' : '展开' }}</span>
        </div>
        <div v-show="showThinking" class="message-bubble__thinking-body">{{ thinkingText }}</div>
      </div>
      <!-- Content -->
      <div class="message-bubble__text">{{ message.content }}</div>
      <!-- 5x #7：收录确认点选（PENDING → 需要回答/不用了；已点选 → 静态状态标） -->
      <div v-if="inclusionConfirm" class="message-bubble__inclusion">
        <template v-if="inclusionConfirm.status === 'PENDING'">
          <button
            class="message-bubble__inclusion-btn message-bubble__inclusion-btn--primary"
            @click="emit('inclusion-choice', { messageId: message.id, choice: 'ANSWER' })"
          >✅ 需要回答</button>
          <button
            class="message-bubble__inclusion-btn"
            @click="emit('inclusion-choice', { messageId: message.id, choice: 'DECLINE' })"
          >🚫 不用了</button>
        </template>
        <span v-else class="message-bubble__inclusion-done">
          {{ inclusionConfirm.status === 'ANSWERED' ? '✅ 已选择回答' : '已选择不回答' }}
        </span>
      </div>
      <!-- 二期 P3（FR-201）：用户消息附件 chips（本地回显带名；历史仅 fileId → 通用「附件」标） -->
      <div v-if="userAttachments.length" class="message-bubble__attachments">
        <button
          v-for="a in userAttachments"
          :key="a.fileId"
          class="message-bubble__attachment"
          :title="`下载 ${a.name}`"
          @click="downloadAttachment(a)"
        >📎 {{ a.name }}</button>
      </div>
      <!-- 二期 P3（FR-203）：召回命中的文件记忆卡片（下载 / 展开分块页码锚点） -->
      <div v-if="fileCards.length" class="message-bubble__file-cards">
        <div class="message-bubble__file-cards-title">🗂 相关文件记忆</div>
        <MessageFileCard v-for="c in fileCards" :key="c.memoryId ?? c.fileId" :card="c" />
      </div>
      <!-- P3：RAG 引用回显（文本中 [n] 对应底部第 n 条；IMAGE 缩略图 / FILE 下载链 / 联网外链） -->
      <div v-if="citations.length" class="message-bubble__citations">
        <div class="message-bubble__citations-title">📎 引用来源</div>
        <div v-for="c in citations" :key="c.index" class="message-bubble__citation">
          <span class="message-bubble__citation-index">[{{ c.index }}]</span>
          <!-- 联网搜索 web citation：有 url 无 documentId → 可点击外链 -->
          <a
            v-if="c.url"
            class="message-bubble__citation-link"
            :href="c.url"
            target="_blank"
            rel="noopener noreferrer"
            :title="c.url"
          >🌐 {{ c.title || c.url }}</a>
          <span v-else class="message-bubble__citation-title">{{ c.title || c.originalName || `文档 ${c.documentId}` }}</span>
          <!-- IMAGE：内联缩略图 -->
          <img
            v-if="!c.url && c.docType === 'IMAGE' && c.fileRef && c.documentId"
            class="message-bubble__citation-thumb"
            :src="knowledgeApi.documentAssetUrl(c.documentId)"
            :alt="c.originalName || '图片引用'"
            loading="lazy"
          />
          <!-- FILE：下载 chip -->
          <a
            v-else-if="!c.url && c.docType === 'FILE' && c.fileRef && c.documentId"
            class="message-bubble__citation-download"
            :href="knowledgeApi.documentAssetUrl(c.documentId)"
            :download="c.originalName || ''"
          >
            ⬇ 下载原件
          </a>
          <!-- 联网搜索 snippet 副标题 -->
          <span v-if="c.url && c.snippet" class="message-bubble__citation-snippet">{{ c.snippet }}</span>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { NIcon, useMessage } from 'naive-ui'
import { PersonOutline, SparklesOutline, CopyOutline, CheckmarkOutline } from '@vicons/ionicons5'
import type { ChatMessage } from '@/api/chat'
import type { RecalledFileCard } from '@/api/memory'
import { knowledgeApi } from '@/api/knowledge'
import { fetchFilePreview } from '@/api/file'
import MessageFileCard from './MessageFileCard.vue'

const props = defineProps<{
  message: ChatMessage
}>()

const emit = defineEmits<{
  (e: 'inclusion-choice', payload: { messageId: number; choice: 'ANSWER' | 'DECLINE' }): void
}>()

const showThinking = ref(true)

/** 5x 四轮 U5：复制正文（仅 content，思考块不随复制）；1.6s 图标反馈复位。 */
const copied = ref(false)
let copiedTimer: number | undefined
const messageApi = useMessage()
function copyContent() {
  navigator.clipboard?.writeText(props.message.content).then(
    () => {
      copied.value = true
      messageApi.success('已复制')
      window.clearTimeout(copiedTimer)
      copiedTimer = window.setTimeout(() => { copied.value = false }, 1600)
    },
    () => messageApi.error('复制失败，请手动选择复制')
  )
}

const thinkingText = computed(() => {
  if (!props.message.metadata) return null
  try {
    const meta = JSON.parse(props.message.metadata)
    return meta.thinking || null
  } catch {
    return null
  }
})

/** P3：从 metadata.citations 解析引用列表（后端 CITATION 帧存入；KB 引用 + 联网 web 引用合并）。 */
interface Citation {
  index: number
  documentId?: number
  title?: string
  docType?: string
  fileRef?: string
  mime?: string
  originalName?: string
  /** 联网搜索来源 URL（web citation 专有；非空 → 渲染为外链）。 */
  url?: string
  /** 联网搜索摘要副标题。 */
  snippet?: string
}
const citations = computed<Citation[]>(() => {
  if (!props.message.metadata) return []
  try {
    const meta = JSON.parse(props.message.metadata)
    const list = Array.isArray(meta.citations) ? meta.citations : []
    return list.filter((c: any) => c && typeof c.index === 'number')
  } catch {
    return []
  }
})

/** 二期 P3（FR-203）：metadata.fileCards → 文件记忆卡片列表（流式 FILE_CARDS 帧 / REST 召回随消息落库）。 */
const fileCards = computed<RecalledFileCard[]>(() => {
  if (!props.message.metadata) return []
  try {
    const meta = JSON.parse(props.message.metadata)
    const list = Array.isArray(meta.fileCards) ? meta.fileCards : []
    // 项目收录附件下载卡 memoryId=null（跨用户、仅下载不展开分块）需放行；个人文件记忆卡 memoryId 为数字。
    return list.filter((c: any) => c && c.fileId && (c.memoryId === null || typeof c.memoryId === 'number'))
  } catch {
    return []
  }
})

/** 二期 P3（FR-201）：用户附件 chips。本地回显 metadata.attachments（带名）；历史仅 attachmentFileIds（通用标）。 */
const userAttachments = computed<{ fileId: string; name: string }[]>(() => {
  if (props.message.role !== 'USER' || !props.message.metadata) return []
  try {
    const meta = JSON.parse(props.message.metadata)
    if (Array.isArray(meta.attachments)) {
      return meta.attachments.filter((a: any) => a && a.fileId)
    }
    if (Array.isArray(meta.attachmentFileIds)) {
      return meta.attachmentFileIds.map((fid: string, i: number) => ({ fileId: fid, name: `附件 ${i + 1}` }))
    }
    return []
  } catch {
    return []
  }
})

/** 5x #7：收录确认状态（metadata.inclusionConfirm：status PENDING/ANSWERED/DECLINED + hits）。 */
const inclusionConfirm = computed<{ status: string; hits?: any[] } | null>(() => {
  if (props.message.role !== 'ASSISTANT' || !props.message.metadata) return null
  try {
    const meta = JSON.parse(props.message.metadata)
    return meta.inclusionConfirm?.status ? meta.inclusionConfirm : null
  } catch {
    return null
  }
})

/** 附件下载：/api/files/{fileId} 需 JWT header → axios blob 转 objectURL 触发下载。 */
async function downloadAttachment(a: { fileId: string; name: string }) {
  try {
    const url = await fetchFilePreview(a.fileId)
    const link = document.createElement('a')
    link.href = url
    link.download = a.name
    link.click()
    setTimeout(() => URL.revokeObjectURL(url), 30_000)
  } catch {
    // 拦截器已 toast（原文件删除/无权限 → 404/403 提示）
  }
}
</script>

<style lang="scss" scoped>
.message-bubble {
  display: flex;
  gap: 12px;
  padding: 16px 20px;

  &--assistant {
    background: var(--color-surface);
  }
}

@media (max-width: 768px) {
  .message-bubble {
    padding: 12px;
    gap: 8px;
  }
}

.message-bubble__avatar {
  flex-shrink: 0;
}

.message-bubble__avatar-icon {
  width: 32px;
  height: 32px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;

  &--user {
    background: var(--color-primary);
    color: white;
  }

  &--assistant {
    background: var(--color-primary-light);
    color: var(--color-primary);
  }
}

.message-bubble__content {
  flex: 1;
  min-width: 0;
}

.message-bubble__role {
  font-size: 12px;
  color: var(--color-text-tertiary);
  margin-bottom: 4px;
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.message-bubble__copy {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  border: none;
  background: transparent;
  color: var(--color-text-tertiary);
  font-size: 11px;
  padding: 2px 6px;
  border-radius: 4px;
  cursor: pointer;
  opacity: 0;
  transition: opacity 0.15s ease;

  &:hover {
    color: var(--color-primary);
    background: rgba(255, 255, 255, 0.05);
  }
}

.message-bubble:hover .message-bubble__copy,
.message-bubble__copy:focus-visible {
  opacity: 1;
}

/* 触屏无 hover：复制按钮常显 */
@media (hover: none) {
  .message-bubble__copy {
    opacity: 1;
  }
}

.message-bubble__thinking {
  margin-bottom: 8px;
  border: 1px solid var(--color-border-light);
  border-radius: 6px;
  overflow: hidden;
}

.message-bubble__thinking-toggle {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 8px 12px;
  background: rgba(255, 255, 255, 0.03);
  cursor: pointer;
  font-size: 12px;
  color: var(--color-text-tertiary);
  user-select: none;

  &:hover {
    background: rgba(255, 255, 255, 0.05);
  }
}

.message-bubble__thinking-action {
  font-size: 11px;
  color: var(--color-primary);
}

.message-bubble__thinking-body {
  padding: 10px 12px;
  font-size: 13px;
  color: var(--color-text-secondary);
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 300px;
  overflow-y: auto;
}

.message-bubble__text {
  font-size: 14px;
  color: var(--color-text-primary);
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
}

.message-bubble__citations {
  margin-top: 10px;
  padding: 8px 10px;
  border: 1px solid var(--color-border-light);
  border-radius: 6px;
  background: rgba(255, 255, 255, 0.02);
}

.message-bubble__citations-title {
  font-size: 12px;
  color: var(--color-text-tertiary);
  margin-bottom: 6px;
}

.message-bubble__citation {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  padding: 4px 0;
  font-size: 12px;
  color: var(--color-text-secondary);
}

.message-bubble__citation-index {
  color: var(--color-primary);
  font-weight: 600;
  flex-shrink: 0;
}

.message-bubble__citation-title {
  word-break: break-word;
}

.message-bubble__citation-link {
  color: var(--color-primary);
  text-decoration: none;
  word-break: break-all;

  &:hover {
    text-decoration: underline;
    opacity: 0.85;
  }
}

.message-bubble__citation-snippet {
  display: block;
  width: 100%;
  margin-top: 2px;
  color: var(--color-text-tertiary);
  font-size: 11px;
  line-height: 1.4;
  word-break: break-word;
}

.message-bubble__citation-thumb {
  display: block;
  max-width: 180px;
  max-height: 140px;
  border-radius: 4px;
  border: 1px solid var(--color-border-light);
  object-fit: contain;
  margin-top: 4px;
}

.message-bubble__citation-download {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 2px 8px;
  border-radius: 4px;
  background: var(--color-primary-light);
  color: var(--color-primary);
  text-decoration: none;
  font-size: 11px;
  cursor: pointer;

  &:hover {
    opacity: 0.85;
  }
}

.message-bubble__attachments {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 8px;
}

.message-bubble__attachment {
  border: 1px solid var(--color-border-light);
  border-radius: 10px;
  background: rgba(255, 255, 255, 0.04);
  color: var(--color-text-secondary);
  font-size: 11px;
  padding: 3px 10px;
  cursor: pointer;
  max-width: 240px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;

  &:hover {
    color: var(--color-primary);
    border-color: var(--color-primary);
  }
}

.message-bubble__file-cards {
  margin-top: 10px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.message-bubble__file-cards-title {
  font-size: 12px;
  color: var(--color-text-tertiary);
}

.message-bubble__inclusion {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 10px;
}

.message-bubble__inclusion-btn {
  border: 1px solid var(--color-border-light);
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.04);
  color: var(--color-text-secondary);
  font-size: 12px;
  padding: 5px 14px;
  cursor: pointer;

  &:hover {
    color: var(--color-primary);
    border-color: var(--color-primary);
  }

  &--primary {
    background: var(--color-primary-light);
    color: var(--color-primary);
    border-color: transparent;

    &:hover {
      opacity: 0.85;
    }
  }
}

.message-bubble__inclusion-done {
  font-size: 12px;
  color: var(--color-text-tertiary);
}
</style>
