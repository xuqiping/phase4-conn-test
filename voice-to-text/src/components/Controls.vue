<script setup lang="ts">
import { useAppStore } from '../stores/app'
const store = useAppStore()
</script>

<template>
  <div class="controls">
    <div class="row">
      <select
        class="device-select"
        v-model="store.selectedDevice"
        :disabled="store.recording"
      >
        <option value="">默认设备</option>
        <option v-for="d in store.devices" :key="d" :value="d">{{ d }}</option>
      </select>

      <button
        class="record-btn"
        :class="{ recording: store.recording }"
        @click="store.recording ? store.stop() : store.start()"
      >
        <span class="dot" />
        {{ store.recording ? '停止录音' : '开始录音' }}
      </button>
    </div>

    <div class="row">
      <label class="save-toggle">
        <input
          type="checkbox"
          v-model="store.saveAudio"
          :disabled="store.recording"
        />
        <span>同时保存录音 (WAV)</span>
      </label>

      <span class="status" v-if="store.statusMessage">{{ store.statusMessage }}</span>
    </div>
  </div>
</template>

<style scoped>
.controls {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.row {
  display: flex;
  align-items: center;
  gap: 12px;
}
.device-select {
  flex: 1;
  padding: 8px 12px;
  font-size: 13px;
  background: #222;
  color: #e0e0e0;
  border: 1px solid #333;
  border-radius: 6px;
  cursor: pointer;
  outline: none;
}
.device-select:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.device-select option {
  background: #222;
  color: #e0e0e0;
}
.record-btn {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  padding: 9px 22px;
  font-size: 14px;
  background: #2563eb;
  color: white;
  border: none;
  border-radius: 6px;
  cursor: pointer;
  transition: background 0.15s;
  white-space: nowrap;
}
.record-btn:hover {
  background: #1d4ed8;
}
.record-btn.recording {
  background: #dc2626;
}
.record-btn.recording:hover {
  background: #b91c1c;
}
.dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #fff;
}
.recording .dot {
  animation: pulse 1.2s infinite;
}
@keyframes pulse {
  0%,
  100% {
    opacity: 1;
  }
  50% {
    opacity: 0.3;
  }
}
.save-toggle {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  color: #aaa;
  cursor: pointer;
  user-select: none;
}
.save-toggle input:disabled + span {
  color: #555;
}
.status {
  font-size: 12px;
  color: #888;
  margin-left: auto;
}
</style>
