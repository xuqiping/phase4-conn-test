<script setup lang="ts">
// Step 10 (FR-101/102): 录制区 —— 选窗口 + 录制控制 + 实时字幕预览。
import { onMounted, ref, watch, nextTick } from 'vue'
import { useSessionStore } from '../stores/session'

const store = useSessionStore()
const subtitleRef = ref<HTMLElement | null>(null)
const selectedHistory = ref('')

onMounted(() => {
  store.refreshWindows()
  store.refreshAudioDevices()
  store.refreshSessions()
})

// 新课录完/处理完后刷新历史列表，让刚结束的会话能立刻被打开。
watch(
  () => store.phase,
  (p) => {
    if (p === 'done' || p === 'processing') store.refreshSessions()
  }
)

const STATE_LABELS: Record<string, string> = {
  idle: '未录制',
  recording: '录制中',
  processing: '待处理',
  done: '已完成',
}

function stateLabel(s: string): string {
  return STATE_LABELS[s] ?? s
}

function onHistorySelect(e: Event) {
  selectedHistory.value = (e.target as HTMLSelectElement).value
}

async function openHistory() {
  const info = store.historySessions.find((s) => s.id === selectedHistory.value)
  if (!info) return
  await store.openSession(info)
  selectedHistory.value = ''
}

// 字幕自动滚到底（新 final 句或 partial 变化时）。
watch(
  () => [store.entries.length, store.partial],
  async () => {
    await nextTick()
    subtitleRef.value?.scrollTo({ top: subtitleRef.value.scrollHeight })
  }
)

function fmt(ms: number): string {
  const s = Math.floor(ms / 1000)
  const mm = String(Math.floor(s / 60)).padStart(2, '0')
  const ss = String(s % 60).padStart(2, '0')
  return `${mm}:${ss}`
}

function onSelect(e: Event) {
  const v = (e.target as HTMLSelectElement).value
  store.selectWindow(v === '' ? null : Number(v))
}

function onAudioSelect(e: Event) {
  store.selectedAudioDevice = (e.target as HTMLSelectElement).value
}
</script>

<template>
  <div class="recorder">
    <div class="row">
      <select
        class="window-select"
        aria-label="选择要录制的窗口"
        :value="store.selectedHwnd ?? ''"
        :disabled="store.recording"
        @change="onSelect"
        @focus="store.refreshWindows"
      >
        <option value="">选择要录制的窗口…</option>
        <option v-for="w in store.windows" :key="w.hwnd" :value="w.hwnd">
          {{ w.title }}
        </option>
      </select>

      <select
        class="window-select audio-select"
        aria-label="选择声音来源"
        :value="store.selectedAudioDevice"
        :disabled="store.recording"
        @change="onAudioSelect"
        @focus="store.refreshAudioDevices"
      >
        <option value="">默认麦克风（不收系统声音）</option>
        <option v-for="d in store.audioDevices" :key="d" :value="d">{{ d }}</option>
      </select>

      <button
        class="region-btn"
        disabled
        aria-label="区域框选（后续版本提供）"
        title="区域框选（后续版本提供）"
      >
        区域框选
      </button>

      <button
        class="record-btn"
        :class="{ recording: store.recording }"
        :disabled="!store.recording && store.selectedHwnd == null"
        :aria-label="store.recording ? '停止录制' : '开始录制'"
        @click="store.recording ? store.stop() : store.start()"
      >
        <span class="dot" />
        {{ store.recording ? '停止录制' : '开始录制' }}
      </button>

      <span v-if="store.recording" class="timer" aria-live="off">
        {{ fmt(store.elapsedMs) }}
      </span>
    </div>

    <div class="row">
      <select
        class="window-select"
        aria-label="选择历史会话查看总结"
        :disabled="store.recording || store.pipelineRunning"
        :value="selectedHistory"
        @change="onHistorySelect"
        @focus="store.refreshSessions"
      >
        <option value="">查看历史会话总结…</option>
        <option v-for="s in store.historySessions" :key="s.id" :value="s.id">
          {{ s.id }}（{{ stateLabel(s.state) }}）
        </option>
      </select>
      <button
        class="open-btn"
        :disabled="!selectedHistory || store.recording || store.pipelineRunning"
        aria-label="打开选中的历史会话"
        @click="openHistory"
      >
        打开
      </button>
    </div>

    <p v-if="store.errorMessage" class="error" role="alert">{{ store.errorMessage }}</p>

    <div
      v-if="store.phase === 'processing'"
      class="processing-banner"
      role="status"
    >
      录制已停止，录后处理进行中（见下方进度）。
    </div>

    <div
      ref="subtitleRef"
      class="subtitles"
      aria-label="实时字幕"
      aria-live="polite"
    >
      <p v-for="(t, i) in store.entries" :key="i" class="line">
        <span class="ts">[{{ fmt(t.start_ms) }}]</span> {{ t.text }}
      </p>
      <p v-if="store.partial" class="line partial">{{ store.partial }}</p>
      <p v-if="!store.entries.length && !store.partial" class="placeholder">
        {{ store.recording ? '等待语音…' : '选择窗口后开始录制，字幕将实时显示在这里' }}
      </p>
    </div>
  </div>
</template>

<style scoped>
.recorder {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.row {
  display: flex;
  align-items: center;
  gap: 12px;
}
.window-select {
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
.window-select:focus-visible,
.record-btn:focus-visible,
.region-btn:focus-visible,
.link-btn:focus-visible {
  outline: 2px solid #2563eb;
  outline-offset: 1px;
}
.window-select:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.audio-select {
  max-width: 240px;
}
.window-select option {
  background: #222;
  color: #e0e0e0;
}
.region-btn {
  padding: 8px 14px;
  font-size: 13px;
  background: #222;
  color: #555;
  border: 1px solid #333;
  border-radius: 6px;
  cursor: not-allowed;
  white-space: nowrap;
}
.open-btn {
  padding: 8px 18px;
  font-size: 13px;
  background: #2563eb;
  color: #fff;
  border: none;
  border-radius: 6px;
  cursor: pointer;
  white-space: nowrap;
}
.open-btn:hover:not(:disabled) {
  background: #1d4ed8;
}
.open-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.open-btn:focus-visible {
  outline: 2px solid #60a5fa;
  outline-offset: 1px;
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
.record-btn:hover:not(:disabled) {
  background: #1d4ed8;
}
.record-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
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
.timer {
  font-size: 13px;
  font-variant-numeric: tabular-nums;
  color: #aaa;
}
.error {
  font-size: 13px;
  color: #f87171;
}
.processing-banner {
  font-size: 13px;
  color: #fbbf24;
  background: #2a230f;
  border: 1px solid #4a3d17;
  border-radius: 6px;
  padding: 8px 12px;
}
.link-btn {
  background: none;
  border: none;
  color: #60a5fa;
  cursor: pointer;
  font-size: 13px;
  text-decoration: underline;
}
.subtitles {
  height: 260px;
  overflow-y: auto;
  background: #161616;
  border: 1px solid #2a2a2a;
  border-radius: 6px;
  padding: 12px 16px;
  font-size: 14px;
  line-height: 1.8;
}
.line {
  color: #e0e0e0;
}
.line .ts {
  color: #666;
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}
.line.partial {
  color: #888;
}
.placeholder {
  color: #555;
  font-size: 13px;
}
</style>
