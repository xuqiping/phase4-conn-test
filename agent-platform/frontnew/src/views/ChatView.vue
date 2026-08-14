<script setup lang="ts">
import { computed, ref } from 'vue'
import MessageList from '@/components/chat/MessageList.vue'
import MessageBubble from '@/components/chat/MessageBubble.vue'
import ChatInput from '@/components/chat/ChatInput.vue'
import { chatSessions, MOCK_REPLY } from '@/mocks/chat'
import type { ChatMessage, ChatSession } from '@/mocks/types'

const sessions = ref<ChatSession[]>(chatSessions)
const activeId = ref(sessions.value[0].id)

const active = computed(() => sessions.value.find((s) => s.id === activeId.value)!)

// 流式状态：streamingId 标记正在打字的消息
const streamingId = ref('')
const inputRef = ref<InstanceType<typeof ChatInput>>()

const reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches

function onSend(text: string) {
  const now = new Date()
  const time = `${String(now.getHours()).padStart(2, '0')}:${String(now.getMinutes()).padStart(2, '0')}`
  active.value.messages.push({ id: `u${Date.now()}`, role: 'user', kind: 'text', content: text, time })

  // mock 流式回复：reduced-motion 时整段直出（L5 边界）
  const aiMsg: ChatMessage = { id: `a${Date.now()}`, role: 'ai', kind: 'text', content: '', time }
  active.value.messages.push(aiMsg)

  if (reducedMotion) {
    aiMsg.content = MOCK_REPLY
    inputRef.value?.finish()
    return
  }
  streamingId.value = aiMsg.id
  let i = 0
  const timer = setInterval(() => {
    i += 2
    aiMsg.content = MOCK_REPLY.slice(0, i)
    if (i >= MOCK_REPLY.length) {
      clearInterval(timer)
      streamingId.value = ''
      inputRef.value?.finish()
    }
  }, 40)
}
</script>

<template>
  <div class="chat-view">
    <aside class="chat-view__sessions">
      <div class="chat-view__sessions-title">会话</div>
      <button
        v-for="s in sessions"
        :key="s.id"
        class="chat-view__session"
        :class="{ 'chat-view__session--active': s.id === activeId }"
        @click="activeId = s.id"
      >
        {{ s.title }}
      </button>
    </aside>

    <section class="chat-view__main">
      <MessageList :messages="active.messages">
        <template #default="{ message }">
          <MessageBubble :message="message" :streaming="message.id === streamingId" />
        </template>
      </MessageList>
      <ChatInput ref="inputRef" @send="onSend" />
    </section>
  </div>
</template>

<style lang="scss" scoped>
.chat-view {
  display: flex;
  flex: 1;
  min-height: 0;

  &__sessions {
    width: 220px;
    flex-shrink: 0;
    border-right: 1px solid var(--line-1);
    background: var(--sf-1);
    padding: var(--sp-3);
    display: flex;
    flex-direction: column;
    gap: var(--sp-1);
  }

  &__sessions-title {
    font-size: var(--fs-xs);
    color: var(--tx-3);
    padding: var(--sp-1) var(--sp-2);
  }

  &__session {
    text-align: left;
    padding: var(--sp-2) var(--sp-3);
    border: none;
    border-radius: var(--r-md);
    background: transparent;
    color: var(--tx-2);
    font-size: var(--fs-sm);
    cursor: pointer;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    transition: background var(--d-fast) var(--ease), color var(--d-fast) var(--ease);

    &:hover {
      background: var(--sf-2);
      color: var(--tx-1);
    }

    &--active {
      background: var(--sf-2);
      color: var(--tx-1);
      box-shadow: inset 2px 0 0 var(--accent);
    }
  }

  &__main {
    flex: 1;
    min-width: 0;
    display: flex;
    flex-direction: column;
  }
}
</style>
