<script setup lang="ts">
import { ref } from 'vue'
import { NIcon } from 'naive-ui'
import { AttachOutline, SendOutline } from '@vicons/ionicons5'

const emit = defineEmits<{ send: [string] }>()

// sending=true 时禁用输入（联动 L5 边界：发送中再按 Enter 忽略）
const sending = ref(false)
const text = ref('')

function onSend() {
  const v = text.value.trim()
  if (!v || sending.value) return
  sending.value = true
  emit('send', v)
  text.value = ''
}

/** 流式结束由父组件调（松耦合，mock 定时器在页面层） */
function finish() {
  sending.value = false
}
defineExpose({ finish })
</script>

<template>
  <div class="chat-input">
    <button class="chat-input__attach" aria-label="附件（占位）" title="附件（预览版占位）">
      <n-icon :size="18"><AttachOutline /></n-icon>
    </button>
    <textarea
      v-model="text"
      class="chat-input__field"
      :class="{ 'chat-input__field--disabled': sending }"
      :disabled="sending"
      rows="2"
      placeholder="输入消息，Enter 发送（Shift+Enter 换行）"
      @keydown.enter.exact.prevent="onSend"
    />
    <button
      class="chat-input__send"
      :disabled="sending || !text.trim()"
      aria-label="发送"
      @click="onSend"
    >
      <n-icon :size="18"><SendOutline /></n-icon>
    </button>
  </div>
</template>

<style lang="scss" scoped>
.chat-input {
  display: flex;
  align-items: flex-end;
  gap: var(--sp-3);
  padding: var(--sp-3) var(--sp-6) var(--sp-4);
  border-top: 1px solid var(--line-1);
  background: var(--sf-1);

  &__attach {
    width: 34px;
    height: 34px;
    display: grid;
    place-items: center;
    border: none;
    border-radius: var(--r-md);
    background: transparent;
    color: var(--tx-3);
    cursor: pointer;

    &:hover {
      color: var(--tx-1);
      background: var(--sf-2);
    }
  }

  &__field {
    flex: 1;
    resize: none;
    border: 1px solid var(--line-1);
    border-radius: var(--r-md);
    background: var(--sf-2);
    color: var(--tx-1);
    font-family: var(--font-ui);
    font-size: var(--fs-md);
    line-height: 1.5;
    padding: var(--sp-2) var(--sp-3);
    outline: none;
    transition: border-color var(--d-fast) var(--ease);

    &:focus {
      border-color: var(--accent);
    }

    &::placeholder {
      color: var(--tx-3);
    }

    &--disabled {
      opacity: 0.6;
    }
  }

  &__send {
    width: 34px;
    height: 34px;
    display: grid;
    place-items: center;
    border: none;
    border-radius: var(--r-md);
    background: var(--accent);
    color: var(--sf-0);
    cursor: pointer;
    transition: opacity var(--d-fast) var(--ease);

    &:disabled {
      opacity: 0.4;
      cursor: not-allowed;
    }
  }
}
</style>
