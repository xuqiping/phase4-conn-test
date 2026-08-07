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

// ---- Step 11 (FR-107/108/109): 录后处理编排 + 学习区数据结构 ----

export type StageStatus = 'pending' | 'running' | 'done' | 'failed'
export interface Stage {
  key: string
  label: string
  status: StageStatus
  detail: string
}

export interface TimelinePoint {
  text: string
  ts_ms: number
  ts_label: string
  frame_ref: string | null
}
export interface TimelineChapter {
  segment_id: number
  title: string
  start_ms: number
  end_ms: number
  local_fallback: boolean
  points: TimelinePoint[]
}
export interface Timeline {
  version: number
  model: string
  outline: string[]
  fallback: boolean
  chapters: TimelineChapter[]
}
export interface VideoSlices {
  slice_ms: number
  files: string[]
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

  // ---- Step 11: 处理编排 + 学习区状态 ----
  const stages = ref<Stage[]>([
    { key: 'frames', label: '精细抽帧', status: 'pending', detail: '' },
    { key: 'ocr', label: '课件 OCR', status: 'pending', detail: '' },
    { key: 'align', label: '音字帧对齐', status: 'pending', detail: '' },
    { key: 'summary', label: '云端总结', status: 'pending', detail: '' },
  ])
  const pipelineRunning = ref(false)
  const pipelineCancelled = ref(false)
  const timeline = ref<Timeline | null>(null)
  const slices = ref<VideoSlices>({ slice_ms: 900000, files: [] })
  const sessionPath = ref('')
  /** 多模态精修开关（默认关；开启经 SummaryPanel 二次确认，FR-107 安全检查）。 */
  const vlmOn = ref(false)
  const lastExportPath = ref('')
  let cancelRequested = false

  function markStage(key: string, status: StageStatus, detail = '') {
    const st = stages.value.find((s) => s.key === key)
    if (st) {
      st.status = status
      st.detail = detail
    }
  }

  function cancelPipeline() {
    cancelRequested = true
  }

  /**
   * 录后处理流水线：抽帧 → OCR → 对齐 → 总结，串行推进。
   * 取消在「段间」生效（当前阶段在后端跑完才停，结果保留）；
   * 重试只跑未完成阶段（frames/ocr 幂等成本高，不重复跑）。
   */
  async function runPipeline() {
    if (pipelineRunning.value || !sessionId.value) return
    if (stages.value.every((s) => s.status === 'done')) {
      phase.value = 'done'
      return
    }
    pipelineRunning.value = true
    pipelineCancelled.value = false
    cancelRequested = false
    errorMessage.value = ''
    const sid = sessionId.value
    let currentKey = ''
    try {
      const steps: Array<[string, () => Promise<string>]> = [
        [
          'frames',
          async () => `抽出 ${await invoke<number>('process_frames', { sessionId: sid })} 页课件帧`,
        ],
        [
          'ocr',
          async () => {
            const [total, ok] = await invoke<[number, number]>('process_ocr', { sessionId: sid })
            return `识别 ${ok}/${total} 帧`
          },
        ],
        [
          'align',
          async () => `对齐 ${await invoke<number>('align_session', { sessionId: sid })} 个单元`,
        ],
        [
          'summary',
          async () => {
            const d = await invoke<{ segments: unknown[]; fallback: boolean }>('summarize', {
              sessionId: sid,
              vlmOn: vlmOn.value,
            })
            return `${d.segments.length} 段${d.fallback ? '（含本地兜底）' : ''}`
          },
        ],
      ]
      for (const [key, run] of steps) {
        if (cancelRequested) {
          pipelineCancelled.value = true
          return
        }
        if (stages.value.find((s) => s.key === key)?.status === 'done') continue
        currentKey = key
        markStage(key, 'running')
        markStage(key, 'done', await run())
      }
      phase.value = 'done'
      await Promise.all([loadTimeline(), loadSlices()])
    } catch (e) {
      if (currentKey) markStage(currentKey, 'failed', String(e))
      errorMessage.value = `处理失败: ${e}`
    } finally {
      pipelineRunning.value = false
    }
  }

  async function loadTimeline() {
    try {
      timeline.value = await invoke<Timeline>('get_timeline', { sessionId: sessionId.value })
    } catch {
      timeline.value = null // 尚无草稿（未总结）不算错误
    }
  }

  async function loadSlices() {
    try {
      slices.value = await invoke<VideoSlices>('get_video_slices', { sessionId: sessionId.value })
    } catch {
      slices.value = { slice_ms: 900000, files: [] }
    }
  }

  /** 可纠错：segmentId 传值只重生成该段，不传全量重生成。 */
  async function regenerate(segmentId?: number) {
    errorMessage.value = ''
    try {
      await invoke('regenerate_summary', {
        sessionId: sessionId.value,
        segmentId: segmentId ?? null,
        vlmOn: vlmOn.value,
      })
      markStage('summary', 'done', segmentId == null ? '已全量重生成' : `段 ${segmentId + 1} 已重生成`)
      await loadTimeline()
    } catch (e) {
      errorMessage.value = `重生成失败: ${e}`
      throw e
    }
  }

  /** 要点局部编辑：后端落盘后同步改本地 timeline（不整树刷新）。 */
  async function updatePoint(segmentId: number, pointIndex: number, text: string) {
    await invoke('update_summary_point', {
      sessionId: sessionId.value,
      segmentId,
      pointIndex,
      text,
    })
    const ch = timeline.value?.chapters.find((c) => c.segment_id === segmentId)
    const p = ch?.points[pointIndex]
    if (p) p.text = text.trim()
  }

  async function exportMarkdown(): Promise<string> {
    const path = await invoke<string>('export_markdown', { sessionId: sessionId.value })
    lastExportPath.value = path
    return path
  }

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
      const info = await invoke<{ id: string; path: string }>('create_session')
      sessionId.value = info.id
      sessionPath.value = info.path
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

  /** 放弃当前会话回空闲（学习区/处理区状态一并清空）。 */
  function reset() {
    phase.value = 'idle'
    sessionId.value = ''
    sessionPath.value = ''
    entries.value = []
    partial.value = ''
    errorMessage.value = ''
    timeline.value = null
    slices.value = { slice_ms: 900000, files: [] }
    lastExportPath.value = ''
    vlmOn.value = false
    pipelineRunning.value = false
    pipelineCancelled.value = false
    cancelRequested = false
    for (const st of stages.value) {
      st.status = 'pending'
      st.detail = ''
    }
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
    // Step 11: 处理编排 + 学习区
    stages,
    pipelineRunning,
    pipelineCancelled,
    timeline,
    slices,
    sessionPath,
    vlmOn,
    lastExportPath,
    runPipeline,
    cancelPipeline,
    loadTimeline,
    loadSlices,
    regenerate,
    updatePoint,
    exportMarkdown,
  }
})
