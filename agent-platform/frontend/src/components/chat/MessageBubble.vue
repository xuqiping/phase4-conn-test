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
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { NIcon } from 'naive-ui'
import { PersonOutline, SparklesOutline } from '@vicons/ionicons5'
import type { ChatMessage } from '@/api/chat'

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
</style>
