<script setup lang="ts">
import type { ChatMessage } from '@/mocks/types'

defineProps<{ message: ChatMessage; streaming?: boolean }>()
</script>

<template>
  <div class="bubble-row" :class="`bubble-row--${message.role}`">
    <div v-if="message.role === 'ai'" class="bubble-row__avatar" aria-hidden="true">AI</div>
    <div class="bubble" :class="`bubble--${message.role}`">
      <!-- 代码块 -->
      <pre v-if="message.kind === 'code'" class="bubble__code"><code>{{ message.content }}</code></pre>
      <!-- 引用卡 -->
      <div v-else-if="message.kind === 'quote'" class="bubble__quote">
        <span class="bubble__quote-from">{{ message.quoteFrom }}</span>
        {{ message.content }}
      </div>
      <!-- 纯文本 -->
      <p v-else class="bubble__text">{{ message.content }}<span v-if="streaming" class="bubble__caret" /></p>
      <time class="bubble__time">{{ message.time }}</time>
    </div>
  </div>
</template>

<style lang="scss" scoped>
.bubble-row {
  display: flex;
  gap: var(--sp-3);
  align-items: flex-start;

  &--user {
    justify-content: flex-end;
  }

  &__avatar {
    width: 30px;
    height: 30px;
    border-radius: var(--r-sm);
    display: grid;
    place-items: center;
    flex-shrink: 0;
    font-size: 11px;
    font-weight: 700;
    color: var(--sf-0);
    background: var(--accent-2, var(--accent));
  }
}

.bubble {
  max-width: 68%;
  padding: var(--sp-3) var(--sp-4);
  border-radius: var(--r-lg);
  font-size: var(--fs-md);
  line-height: 1.6;

  &--ai {
    background: var(--sf-2);
    border: 1px solid var(--line-1);
    border-top-left-radius: var(--r-sm);
    color: var(--tx-1);
  }

  &--user {
    background: color-mix(in srgb, var(--accent) 22%, transparent);
    border: 1px solid color-mix(in srgb, var(--accent) 40%, transparent);
    border-top-right-radius: var(--r-sm);
    color: var(--tx-1);
  }

  &__text {
    margin: 0;
    white-space: pre-wrap;
  }

  &__caret {
    display: inline-block;
    width: 8px;
    height: 1em;
    margin-left: 2px;
    vertical-align: text-bottom;
    background: var(--accent);
    animation: breathe 0.8s step-end infinite;
  }

  &__code {
    margin: 0;
    padding: var(--sp-3);
    border-radius: var(--r-md);
    background: var(--sf-0);
    border: 1px solid var(--line-1);
    font-family: var(--font-mono);
    font-size: var(--fs-sm);
    color: var(--tx-2);
    overflow-x: auto;
  }

  &__quote {
    border-left: 3px solid var(--accent);
    padding-left: var(--sp-3);
    color: var(--tx-2);
    font-size: var(--fs-sm);
  }

  &__quote-from {
    display: block;
    font-size: var(--fs-xs);
    color: var(--accent);
    margin-bottom: 2px;
  }

  &__time {
    display: block;
    margin-top: var(--sp-1);
    font-size: 10px;
    color: var(--tx-3);
  }
}
</style>
