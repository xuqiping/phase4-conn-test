// Step 10 (FR-101/102): 网课录屏总结 —— 录制会话状态机。
// 状态机：idle(空闲) → source-selected(选源) → recording(录制中) → processing(处理中) → done(完成)
// 「处理中 → 完成」的推进依赖录后处理 UI（Step 11），本 store 先留出 done 态。
import { defineStore } from 'pinia'
import { ref, computed, onUnmounted } from 'vue'
import { listen } from '@tauri-apps/api/event'
import { invoke } from '@tauri-apps/api/core'

export type SessionPhase =
  | 'idle'
  | 'source-selected'
  | 'recording'
  | 'processing'
  | 'done'

export interface WindowInfo {
  hwnd: number
  title: string
}

interface TranscriptionEvent {
  text: string
  is_final: boolean
  partial: string
  start_ms?: number
  end_ms?: number
}

/** 结构化转写条目（带 session 时间轴时间戳，供对齐/总结用）。 */
export interface TranscriptEntry {
  start_ms: number
  end_ms: number
  text: string
}

export const useSessionStore = defineStore('session', () => {
  const phase = ref<SessionPhase>('idle')
  const sessionId = ref('')
  const windows = ref<WindowInfo[]>([])
  const selectedHwnd = ref<number | null>(null)
  const entries = ref<TranscriptEntry[]>([])
  const partial = ref('')
  const errorMessage = ref('')
  const startedAt = ref(0)
  const nowMs = ref(0)

  let stopListen: (() => void) | null = null
  let timer: ReturnType<typeof setInterval> | null = null

  const recording = computed(() => phase.value === 'recording')
  const elapsedMs = computed(() =>
    recording.value ? nowMs.value - startedAt.value : 0
  )

  async function refreshWindows() {
    try {
      windows.value = await invoke<WindowInfo[]>('list_windows')
    } catch (e) {
      errorMessage.value = `枚举窗口失败: ${e}`
    }
  }

  function selectWindow(hwnd: number | null) {
    selectedHwnd.value = hwnd
    if (phase.value === 'idle' || phase.value === 'source-selected') {
      phase.value = hwnd == null ? 'idle' : 'source-selected'
    }
  }

  async function start() {
    if (selectedHwnd.value == null) {
      errorMessage.value = '请先选择要录制的窗口'
      return
    }
    errorMessage.value = ''
    entries.value = []
    partial.value = ''
    try {
      const info = await invoke<{ id: string }>('create_session')
      sessionId.value = info.id
      await invoke('start_capture_session', {
        sessionId: sessionId.value,
        hwnd: selectedHwnd.value,
        audioDevice: null,
      })
      stopListen = await listen<TranscriptionEvent>('transcription', (e) => {
        const p = e.payload
        if (p.is_final && p.text) {
          entries.value.push({
            start_ms: p.start_ms ?? 0,
            end_ms: p.end_ms ?? 0,
            text: p.text,
          })
        }
        partial.value = p.partial
      })
      startedAt.value = Date.now()
      nowMs.value = startedAt.value
      timer = setInterval(() => (nowMs.value = Date.now()), 500)
      phase.value = 'recording'
    } catch (e) {
      errorMessage.value = `启动录制失败: ${e}`
    }
  }

  async function stop() {
    try {
      await invoke('stop_capture_session')
    } catch (e) {
      errorMessage.value = `停止录制失败: ${e}`
    }
    if (stopListen) {
      stopListen()
      stopListen = null
    }
    if (timer) {
      clearInterval(timer)
      timer = null
    }
    partial.value = ''
    // 后端 stop 后 session 进入 Processing；录后处理编排 UI 在 Step 11 落地，
    // 那时 processing → done 由处理完成事件驱动。
    phase.value = 'processing'
  }

  /** 放弃当前会话回空闲（录后处理入口在 Step 11）。 */
  function reset() {
    phase.value = 'idle'
    sessionId.value = ''
    entries.value = []
    partial.value = ''
    errorMessage.value = ''
  }

  onUnmounted(() => {
    if (stopListen) stopListen()
    if (timer) clearInterval(timer)
  })

  return {
    phase,
    sessionId,
    windows,
    selectedHwnd,
    entries,
    partial,
    errorMessage,
    recording,
    elapsedMs,
    refreshWindows,
    selectWindow,
    start,
    stop,
    reset,
  }
})
