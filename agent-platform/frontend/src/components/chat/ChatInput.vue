<template>
  <div class="chat-input">
    <div v-if="modeLabel" class="chat-input__mode-badge">
      {{ modeLabel }}
      <button class="chat-input__mode-clear" @click="$emit('clearMode')">&times;</button>
    </div>
    <div class="chat-input__row">
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
import { NInput, NButton, NIcon } from 'naive-ui'
import { SendOutline } from '@vicons/ionicons5'

const props = defineProps<{
  sending: boolean
  agentName?: string
  workflowName?: string
}>()

const emit = defineEmits<{
  send: [message: string]
  clearMode: []
}>()

const text = ref('')

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

function handleSend() {
  const msg = text.value.trim()
  if (!msg || props.sending) return
  emit('send', msg)
  text.value = ''
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

.chat-input__row {
  display: flex;
  gap: 8px;
  align-items: flex-end;
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
