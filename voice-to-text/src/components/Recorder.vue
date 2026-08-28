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

const stopTimerHint = '倒计时：到点自动停（分钟，支持小数）；定点：到 HH:MM 自动停（已过点顺延到明天）。仅录制中生效。'

</script>

<template>
  <div class="recorder">
    <div v-if="store.recording && store.pictureLost" class="pic-lost-banner" role="alert">
      ⚠️ 画面丢失超过 3 秒——现在录到的是黑屏。请按顺序尝试：
      ① 还原/置顶被录窗口（最小化必黑屏）；
      ② 若窗口没最小化、只是被别的程序最大化盖住仍黑屏（部分 Win10 显卡驱动会停止绘制被完全遮挡的窗口），
      请停止后改用「区域框选」模式录制屏幕区域，遮挡不影响区域模式。
    </div>

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
        :class="{ active: store.region != null }"
        :disabled="store.recording"
        aria-label="区域框选录制范围"
        :title="store.region ? `已框选 ${store.region.width}×${store.region.height}（点击重新框选）` : '框选屏幕区域录制'"
        @click="store.beginRegionSelect"
      >
        {{ store.region ? `区域 ${store.region.width}×${store.region.height}` : '区域框选' }}
      </button>
      <span
        v-if="store.region && store.selectedHwnd != null"
        class="region-hint"
        title="已选窗口 + 已框选区域：录制该窗口并只保留框选范围，窗口被其他窗口遮挡也不影响录制"
      >窗口内框选</span>
      <button
        v-if="store.region"
        class="region-clear"
        :disabled="store.recording"
        aria-label="清除已框选区域"
        title="清除已框选区域"
        @click="store.clearRegion"
      >
        ✕
      </button>

      <button
        class="record-btn"
        :class="{ recording: store.recording }"
        :disabled="!store.recording && store.selectedHwnd == null && store.region == null"
        :aria-label="store.recording ? '停止录制' : '开始录制'"
        @click="store.recording ? store.stop() : store.start()"
      >
        <span class="dot" />
        {{ store.recording ? '停止录制' : '开始录制' }}
      </button>

      <span v-if="store.recording" class="timer" aria-live="off">
        {{ fmt(store.elapsedMs) }}
      </span>

      <!-- 定时停止（2026-08-23，手测问题）：不定时/倒计时/定点，录制前后都可设置，
           录制中修改立即生效（按新参数重算停止时刻）。 -->
      <label class="stop-timer" :title="stopTimerHint">
        <span>定时停止</span>
        <select v-model="store.stopTimerMode" aria-label="定时停止模式">
          <option value="off">关</option>
          <option value="countdown">倒计时</option>
          <option value="clock">定点</option>
        </select>
        <input
          v-if="store.stopTimerMode !== 'off'"
          v-model="store.stopTimerValue"
          class="stop-timer-input"
          :type="store.stopTimerMode === 'countdown' ? 'number' : 'text'"
          :min="store.stopTimerMode === 'countdown' ? 1 : undefined"
          :step="store.stopTimerMode === 'countdown' ? 1 : undefined"
          :placeholder="store.stopTimerMode === 'countdown' ? '分钟' : 'HH:MM'"
          :aria-label="store.stopTimerMode === 'countdown' ? '倒计时分钟数' : '定点时间 HH:MM'"
        />
        <span v-if="store.recording && store.stopCountdownMs > 0" class="stop-timer-left">
          {{ store.stopCountdownMs < 60_000 ? '⚠' : '' }}{{ fmt(store.stopCountdownMs) }} 后停
        </span>
      </label>
    </div>

    <!-- 实时视频映射（2026-08-22）：录制中每 ~500ms 刷新 live.jpg，
         黑屏了就让它黑着显示——所见即所录。2026-08-23 移到「开始录制」按钮正下方，
         并改为录制一开始就显示（首帧未到时显示占位），确保一眼可见。 -->
    <div v-if="store.recording" class="live-preview">
      <div class="live-preview-head">
        <span class="live-dot" aria-hidden="true"></span>
        <span>实时录制画面</span>
        <span class="live-stats">
          黑屏占比
          <b :class="{ bad: store.blackRatio > 0.1 }">
            {{ store.blackRatio < 0 ? '—' : (store.blackRatio * 100).toFixed(1) + '%' }}
          </b>
        </span>
      </div>
      <img v-if="store.livePreviewSrc" :src="store.livePreviewSrc" alt="录制画面实时预览" />
      <div v-else class="live-preview-waiting">等待画面…（正在连接被录窗口）</div>
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
.pic-lost-banner {
  font-size: 13px;
  color: #f87171;
  background: #2a1111;
  border: 1px solid #7f1d1d;
  border-radius: 6px;
  padding: 8px 12px;
  margin-bottom: 8px;
}
.recorder {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.live-preview {
  border: 1px solid #444;
  border-radius: 6px;
  overflow: hidden;
  background: #111;
}
.live-preview-head {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  color: #aaa;
  padding: 5px 10px;
  background: #1b1b1b;
}
.live-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #ef4444;
  animation: live-blink 1.2s infinite;
}
@keyframes live-blink {
  50% { opacity: 0.25; }
}
.live-stats {
  margin-left: auto;
}
.live-stats b {
  color: #4ade80;
}
.live-stats b.bad {
  color: #f87171;
}
.live-preview img {
  display: block;
  width: 100%;
  max-height: 260px;
  object-fit: contain;
  background: #000;
}
.live-preview-waiting {
  padding: 24px 16px;
  text-align: center;
  color: #888;
  font-size: 13px;
  background: #000;
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
  color: #e0e0e0;
  border: 1px solid #333;
  border-radius: 6px;
  cursor: pointer;
  white-space: nowrap;
}
.region-btn:hover:not(:disabled) {
  background: #2a2a2a;
}
.region-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.region-btn.active {
  border-color: #2563eb;
  color: #60a5fa;
}
.region-clear {
  padding: 8px 10px;
  font-size: 12px;
  background: #222;
  color: #888;
  border: 1px solid #333;
  border-radius: 6px;
  cursor: pointer;
}
.region-hint {
  font-size: 12px;
  color: #60a5fa;
  white-space: nowrap;
  cursor: help;
}
.region-clear:hover:not(:disabled) {
  color: #f87171;
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
.stop-timer {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: #888;
  white-space: nowrap;
}
.stop-timer select {
  padding: 6px 8px;
  font-size: 12px;
  background: #222;
  color: #e0e0e0;
  border: 1px solid #333;
  border-radius: 6px;
  cursor: pointer;
  outline: none;
}
.stop-timer-input {
  width: 72px;
  padding: 6px 8px;
  font-size: 12px;
  background: #222;
  color: #e0e0e0;
  border: 1px solid #333;
  border-radius: 6px;
  outline: none;
}
.stop-timer-left {
  color: #fbbf24;
  font-variant-numeric: tabular-nums;
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
