<script setup lang="ts">
import { useAppStore } from '../stores/app'
const store = useAppStore()
</script>

<template>
  <div class="transcription">
    <div v-if="store.errorMessage" class="error">
      ⚠️ {{ store.errorMessage }}
    </div>
    <div v-if="!store.transcript && !store.partial && !store.errorMessage" class="empty">
      点击下方"开始录音"按钮…
    </div>
    <div v-else class="text">
      <span class="final">{{ store.transcript }}</span>
      <span class="partial">{{ store.partial }}</span>
    </div>
    <div v-if="(store.transcript || store.partial) && !store.recording" class="actions">
      <button class="btn-reset" @click="store.reset()">清空文字</button>
    </div>
  </div>
</template>

<style scoped>
.transcription {
  height: 100%;
  padding: 24px;
  overflow-y: auto;
  font-size: 16px;
  line-height: 1.85;
  word-break: break-word;
  position: relative;
}
.empty {
  color: #666;
  text-align: center;
  margin-top: 80px;
  font-size: 14px;
}
.final {
  color: #e0e0e0;
}
.partial {
  color: #888;
  font-style: italic;
}
.error {
  color: #ff6b6b;
  margin-bottom: 16px;
  font-size: 14px;
}
.actions {
  margin-top: 24px;
  padding-top: 16px;
  border-top: 1px solid #333;
}
.btn-reset {
  padding: 6px 16px;
  font-size: 13px;
  border-radius: 4px;
  border: 1px solid #444;
  background: transparent;
  color: #aaa;
  cursor: pointer;
}
.btn-reset:hover {
  color: #fff;
  border-color: #555;
}
</style>
