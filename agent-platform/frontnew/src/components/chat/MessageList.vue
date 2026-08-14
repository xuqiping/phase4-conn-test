<script setup lang="ts">
import type { ChatMessage } from '@/mocks/types'

defineProps<{ messages: ChatMessage[]; streaming?: boolean }>()
</script>

<template>
  <div class="message-list">
    <slot v-for="m in messages" :key="m.id" :message="m" />
  </div>
</template>

<style lang="scss" scoped>
.message-list {
  display: flex;
  flex-direction: column;
  gap: var(--sp-4);
  padding: var(--sp-6);
  overflow-y: auto;
  flex: 1;
}
</style>
