<template>
  <div class="chat-input">
    <div v-if="modeLabel" class="chat-input__mode-badge">
      {{ modeLabel }}
      <button class="chat-input__mode-clear" @click="$emit('clearMode')">&times;</button>
    </div>
    <!-- 二期 P3（FR-201）：待发附件 chips（上传即建文件记忆；解析状态一次性延迟刷新） -->
    <div v-if="attachments.length" class="chat-input__attachments">
      <span
        v-for="a in attachments"
        :key="a.memoryId"
        class="chat-input__attachment"
        :class="`chat-input__attachment--${a.status.toLowerCase()}`"
        :title="attachmentTip(a)"
      >
        📎 {{ a.name }}
        <em class="chat-input__attachment-status">{{ attachmentStatusLabel(a.status) }}</em>
        <button class="chat-input__attachment-remove" title="移除" @click="removeAttachment(a.memoryId)">&times;</button>
      </span>
    </div>
    <div class="chat-input__row">
      <button
        class="chat-input__attach"
        :disabled="sending || uploading"
        :title="`添加附件（≤${MAX_ATTACHMENTS} 个，单个 ≤50MB；上传后进入「文件记忆」）`"
        @click="fileInput?.click()"
      >
        <n-icon :component="AttachOutline" />
      </button>
      <input
        ref="fileInput"
        type="file"
        multiple
        class="chat-input__file-input"
        @change="handleFileSelect"
      />
      <n-input
        v-model:value="text"
        type="textarea"
        :placeholder="placeholder"
        :autosize="{ minRows: 1, maxRows: 4 }"
        :disabled="sending"
        @keydown.enter.exact.prevent="handleSend"
      />
      <n-button
        type="primary"
        :disabled="!text.trim() || sending"
        :loading="sending"
        @click="handleSend"
      >
        <template #icon>
          <n-icon :component="SendOutline" />
        </template>
      </n-button>
    </div>
    <div class="chat-input__tools">
      <slot name="tools" />
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { NInput, NButton, NIcon, useMessage } from 'naive-ui'
import { SendOutline, AttachOutline } from '@vicons/ionicons5'
import { memoryApi } from '@/api/memory'
import type { ChatAttachmentRef } from '@/api/chat'

/** 与后端 ChatRequest.attachmentFileIds 上限一致（≤5）。 */
const MAX_ATTACHMENTS = 5
/** 与后端 MemoryAssetUploadService 预检一致（50MB）。 */
const MAX_FILE_BYTES = 50 * 1024 * 1024

interface PendingAttachment {
  memoryId: number
  fileId: string
  name: string
  status: 'UPLOADING' | 'PROCESSING' | 'READY' | 'FAILED'
}

const props = defineProps<{
  sending: boolean
  agentName?: string
  workflowName?: string
}>()

const emit = defineEmits<{
  send: [message: string, attachments: ChatAttachmentRef[]]
  clearMode: []
}>()

const message = useMessage()
const text = ref('')
const fileInput = ref<HTMLInputElement | null>(null)
const attachments = ref<PendingAttachment[]>([])
const uploading = ref(false)
/** 本地临时 id（UPLOADING 阶段尚无 memoryId，用负数占位）。 */
let tempIdSeq = -1

const modeLabel = computed(() => {
  if (props.agentName) return `Agent: ${props.agentName}`
  if (props.workflowName) return `Workflow: ${props.workflowName}`
  return ''
})

const placeholder = computed(() => {
  if (props.agentName) return `与 ${props.agentName} 对话...`
  if (props.workflowName) return `执行 ${props.workflowName}...`
  return '输入消息，Enter 发送...'
})

function attachmentStatusLabel(status: PendingAttachment['status']) {
  switch (status) {
    case 'UPLOADING': return '上传中'
    case 'PROCESSING': return '解析中'
    case 'READY': return '就绪'
    case 'FAILED': return '解析失败'
  }
}

function attachmentTip(a: PendingAttachment) {
  if (a.status === 'FAILED') return `${a.name}：解析失败（可在「文件记忆」页签重试）`
  if (a.status === 'READY') return `${a.name}：已就绪，召回可命中`
  return `${a.name}：上传后异步解析为文件记忆，不影响本条发送`
}

async function handleFileSelect(e: Event) {
  const input = e.target as HTMLInputElement
  const files = Array.from(input.files || [])
  input.value = '' // 允许重选同名文件
  if (!files.length) return
  if (attachments.value.length + files.length > MAX_ATTACHMENTS) {
    message.error(`附件最多 ${MAX_ATTACHMENTS} 个`)
    return
  }
  for (const f of files) {
    if (f.size > MAX_FILE_BYTES) {
      message.error(`文件过大（>50MB）：${f.name}`)
      continue
    }
    const pending: PendingAttachment = { memoryId: tempIdSeq--, fileId: '', name: f.name, status: 'UPLOADING' }
    attachments.value.push(pending)
    uploading.value = true
    try {
      const res = await memoryApi.uploadAttachment(f)
      const vo = res.data.data
      pending.memoryId = vo.memoryId
      pending.fileId = vo.fileId
      pending.status = (vo.ingestStatus as PendingAttachment['status']) || 'PROCESSING'
    } catch {
      // 拦截器已 toast；移除失败占位
      attachments.value = attachments.value.filter(x => x !== pending)
    } finally {
      uploading.value = false
    }
  }
  scheduleStatusRefresh()
}

/** 上传后 ingestion 异步：延迟一次性刷新 chips 状态（不轮询，避免刷屏；页签里可手动刷新看终态）。 */
function scheduleStatusRefresh() {
  if (!attachments.value.some(a => a.status === 'PROCESSING')) return
  setTimeout(async () => {
    const processing = attachments.value.filter(a => a.status === 'PROCESSING' && a.memoryId > 0)
    if (!processing.length) return
    try {
      const res = await memoryApi.listAttachments()
      const rows = res.data.data || []
      for (const chip of processing) {
        const row = rows.find(r => r.id === chip.memoryId)
        if (row && row.ingestStatus !== 'PROCESSING') {
          chip.status = row.ingestStatus
        }
      }
    } catch {
      // 刷新失败保持「解析中」，不阻断发送
    }
  }, 5000)
}

function removeAttachment(memoryId: number) {
  attachments.value = attachments.value.filter(a => a.memoryId !== memoryId)
}

function handleSend() {
  const msg = text.value.trim()
  if (!msg || props.sending) return
  // 仅携带上传成功的附件（UPLOADING 中/失败占位不带）
  const refs: ChatAttachmentRef[] = attachments.value
    .filter(a => a.memoryId > 0 && a.fileId)
    .map(a => ({ fileId: a.fileId, name: a.name }))
  emit('send', msg, refs)
  text.value = ''
  attachments.value = []
}
</script>

<style lang="scss" scoped>
.chat-input {
  border-top: 1px solid var(--color-border-light);
  padding: 12px 20px;
  background: var(--color-bg);
}

@media (max-width: 768px) {
  .chat-input {
    padding: 8px 12px;
  }
}

.chat-input__mode-badge {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--color-primary);
  background: var(--color-primary-light);
  padding: 2px 10px;
  border-radius: 10px;
  margin-bottom: 8px;
}

.chat-input__mode-clear {
  background: none;
  border: none;
  color: var(--color-text-tertiary);
  cursor: pointer;
  font-size: 14px;
  padding: 0 2px;
  line-height: 1;

  &:hover {
    color: var(--color-text-primary);
  }
}

.chat-input__attachments {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-bottom: 8px;
}

.chat-input__attachment {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 11px;
  color: var(--color-text-secondary);
  background: rgba(255, 255, 255, 0.04);
  border: 1px solid var(--color-border-light);
  border-radius: 10px;
  padding: 3px 8px;
  max-width: 260px;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;

  &--ready {
    border-color: var(--color-primary);
  }

  &--failed {
    border-color: #d03050;
  }
}

.chat-input__attachment-status {
  font-style: normal;
  color: var(--color-text-tertiary);
  flex-shrink: 0;
}

.chat-input__attachment--ready .chat-input__attachment-status {
  color: var(--color-primary);
}

.chat-input__attachment--failed .chat-input__attachment-status {
  color: #d03050;
}

.chat-input__attachment-remove {
  background: none;
  border: none;
  color: var(--color-text-tertiary);
  cursor: pointer;
  font-size: 13px;
  padding: 0 2px;
  line-height: 1;
  flex-shrink: 0;

  &:hover {
    color: var(--color-text-primary);
  }
}

.chat-input__row {
  display: flex;
  gap: 8px;
  align-items: flex-end;
}

.chat-input__attach {
  flex-shrink: 0;
  width: 32px;
  height: 32px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: 1px solid var(--color-border-light);
  border-radius: 6px;
  background: transparent;
  color: var(--color-text-secondary);
  font-size: 16px;
  cursor: pointer;

  &:hover:not(:disabled) {
    color: var(--color-primary);
    border-color: var(--color-primary);
  }

  &:disabled {
    opacity: 0.5;
    cursor: default;
  }
}

.chat-input__file-input {
  display: none;
}

.chat-input__tools {
  display: flex;
  gap: 8px;
  margin-top: 8px;
}

@media (max-width: 768px) {
  // 工具行换行：目标/模型/记忆范围等在窄屏堆叠
  .chat-input__tools {
    flex-wrap: wrap;
  }
}
</style>
