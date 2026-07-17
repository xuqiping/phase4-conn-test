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
        {{ message.role === 'USER' ? '你' : '助手' }}
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
      <!-- P3：RAG 引用回显（文本中 [n] 对应底部第 n 条；IMAGE 缩略图 / FILE 下载链） -->
      <div v-if="citations.length" class="message-bubble__citations">
        <div class="message-bubble__citations-title">📎 引用来源</div>
        <div v-for="c in citations" :key="c.index" class="message-bubble__citation">
          <span class="message-bubble__citation-index">[{{ c.index }}]</span>
          <span class="message-bubble__citation-title">{{ c.title || c.originalName || `文档 ${c.documentId}` }}</span>
          <!-- IMAGE：内联缩略图 -->
          <img
            v-if="c.docType === 'IMAGE' && c.fileRef"
            class="message-bubble__citation-thumb"
            :src="knowledgeApi.documentAssetUrl(c.documentId)"
            :alt="c.originalName || '图片引用'"
            loading="lazy"
          />
          <!-- FILE：下载 chip -->
          <a
            v-else-if="c.docType === 'FILE' && c.fileRef"
            class="message-bubble__citation-download"
            :href="knowledgeApi.documentAssetUrl(c.documentId)"
            :download="c.originalName || ''"
          >
            ⬇ 下载原件
          </a>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { NIcon } from 'naive-ui'
import { PersonOutline, SparklesOutline } from '@vicons/ionicons5'
import type { ChatMessage } from '@/api/chat'
import { knowledgeApi } from '@/api/knowledge'

const props = defineProps<{
  message: ChatMessage
}>()

const showThinking = ref(true)

const thinkingText = computed(() => {
  if (!props.message.metadata) return null
  try {
    const meta = JSON.parse(props.message.metadata)
    return meta.thinking || null
  } catch {
    return null
  }
})

/** P3：从 metadata.citations 解析引用列表（后端 CITATION 帧存入）。 */
interface Citation {
  index: number
  documentId: number
  title?: string
  docType?: string
  fileRef?: string
  mime?: string
  originalName?: string
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
</style>
