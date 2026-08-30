<script setup lang="ts">
// Step 11 (FR-108): 学习区 —— 左：章节时间轴；右：视频 + 要点 + 课件帧。
// 要点/帧点击跳回视频时刻（15min 切片按 ts 定位分片 + 片内偏移）。
// 帧图与视频走 asset:// 协议（convertFileSrc），不走 base64 IPC。
import { computed, onMounted, ref } from 'vue'
import { convertFileSrc, invoke } from '@tauri-apps/api/core'
import { useSessionStore, type TimelineChapter } from '../stores/session'

// 选中章节由 App 持有，与 SummaryPanel 共享（编辑区跟 Study 联动）。
const props = defineProps<{ chapter: number }>()
const emit = defineEmits<{ 'update:chapter': [number] }>()

const store = useSessionStore()

const selectedIdx = computed({
  get: () => props.chapter,
  set: (v: number) => emit('update:chapter', v),
})
const videoRef = ref<HTMLVideoElement | null>(null)
const audioRef = ref<HTMLAudioElement | null>(null)
const currentSlice = ref('')
const pendingSeekSec = ref<number | null>(null)
const ocrCache = ref<Record<string, string | null>>({})
const exporting = ref(false)
const exportError = ref('')
const reprocessing = ref(false)
const consolidating = ref(false)

/** 汇总定稿：去重兜底汇总 + 最终真实章节标题（2026-08-21）。 */
async function doConsolidate() {
  consolidating.value = true
  exportError.value = ''
  try {
    await store.consolidate()
  } catch (e) {
    exportError.value = `${e}`
  } finally {
    consolidating.value = false
  }
}

/** 毫秒 → m:ss 展示（汇总定稿章节时间区间用）。 */
function fmtMs(ms: number): string {
  const s = Math.max(0, Math.floor(ms / 1000))
  const m = Math.floor(s / 60)
  return `${Math.floor(m / 60) > 0 ? `${Math.floor(m / 60)}:` : ''}${String(m % 60).padStart(2, '0')}:${String(s % 60).padStart(2, '0')}`
}

onMounted(async () => {
  await Promise.all([store.loadTimeline(), store.loadSlices()])
})

const chapters = computed(() => store.timeline?.chapters ?? [])
const current = computed<TimelineChapter | null>(
  () => chapters.value[selectedIdx.value] ?? null
)

function frameSrc(frameRef: string): string {
  return convertFileSrc(`${store.sessionPath}/${frameRef}`)
}

const videoSrc = computed(() =>
  currentSlice.value
    ? convertFileSrc(`${store.sessionPath}/video/${currentSlice.value}`)
    : ''
)

// 2026-08-08 Phase4 手测缺陷修复：视频分轨落盘无音轨（FR-103 设计），
// 学习区播放切片时用隐藏 <audio> 同步播放 audio.wav 对应时间段。
const audioSrc = computed(() =>
  store.slices.has_audio ? convertFileSrc(`${store.sessionPath}/audio.wav`) : ''
)

/** 当前切片起点在整节课里的秒数（audio.wav 是全课一条音轨）。 */
const sliceBaseSec = computed(() => {
  const idx = store.slices.files.indexOf(currentSlice.value)
  return idx < 0 ? 0 : (idx * store.slices.slice_ms) / 1000
})

/** 把音轨对齐到画面对应的全课时间点。 */
function syncAudio() {
  const v = videoRef.value
  const a = audioRef.value
  if (!v || !a) return
  a.currentTime = sliceBaseSec.value + v.currentTime
  a.playbackRate = v.playbackRate
  a.volume = v.volume
  a.muted = v.muted
}

function onVideoPlay() {
  syncAudio()
  audioRef.value?.play().catch(() => {})
}

function onVideoPause() {
  audioRef.value?.pause()
}

function onVideoSeeked() {
  syncAudio()
}

/** 点击要点/帧跳回视频时刻：ts → 切片序号 + 片内偏移秒。 */
function seekTo(tsMs: number) {
  const { slice_ms, files } = store.slices
  if (!files.length) return
  const idx = Math.min(Math.floor(tsMs / slice_ms), files.length - 1)
  const offset = (tsMs % slice_ms) / 1000
  if (files[idx] !== currentSlice.value) {
    currentSlice.value = files[idx]
    pendingSeekSec.value = offset // 换片后等 loadedmetadata 再 seek
  } else if (videoRef.value) {
    videoRef.value.currentTime = offset
    videoRef.value.play().catch(() => {})
  }
}

function onVideoLoaded() {
  if (pendingSeekSec.value != null && videoRef.value) {
    videoRef.value.currentTime = pendingSeekSec.value
    pendingSeekSec.value = null
    videoRef.value.play().catch(() => {})
  }
}

/** OCR 原文懒加载（展开时才查 frames.json）。 */
function loadOcr(frameRef: string) {
  if (!(frameRef in ocrCache.value)) {
    ocrCache.value[frameRef] = null
    invoke<string | null>('get_ocr_text', {
      sessionId: store.sessionId,
      frameRef,
    })
      .then((t) => (ocrCache.value[frameRef] = t))
      .catch(() => (ocrCache.value[frameRef] = null))
  }
}

async function doExport() {
  exporting.value = true
  exportError.value = ''
  try {
    await store.exportMarkdown()
  } catch (e) {
    exportError.value = `导出失败: ${e}`
  } finally {
    exporting.value = false
  }
}

async function doReprocess() {
  const ok = window.confirm(
    '确定要重新分析本会话吗？这会删除当前的抽帧、OCR、对齐和总结结果（旧总结会先备份），然后重新从视频抽帧开始处理。'
  )
  if (!ok) return
  reprocessing.value = true
  try {
    await store.reprocessFromScratch()
  } catch (e) {
    exportError.value = `重新分析失败: ${e}`
  } finally {
    reprocessing.value = false
  }
}
</script>

<template>
  <section class="study" aria-label="学习区">
    <div v-if="!store.timeline" class="empty" role="status">
      尚无总结数据 —— 请先完成录后处理。
    </div>

    <template v-else>
      <div v-if="store.timeline.fallback" class="fallback-banner" role="status">
        ⚠️ 本次总结包含本地兜底内容（云端 API 失败降级），带「本地」标记的章节非 AI 生成。
      </div>

      <div v-if="store.timeline.outline.length" class="outline">
        <h3 class="outline-title">大纲</h3>
        <ul>
          <li v-for="(line, i) in store.timeline.outline" :key="i">{{ line }}</li>
        </ul>
      </div>

      <div v-if="store.timeline.final_summary" class="final">
        <h3 class="final-title">汇总定稿（去重后最终章节）</h3>
        <div
          v-for="(fc, i) in store.timeline.final_summary.chapters"
          :key="i"
          class="final-chapter"
        >
          <div class="fc-head">
            <span class="fc-title">{{ fc.title }}</span>
            <span class="fc-ts">{{ fmtMs(fc.start_ms) }} - {{ fmtMs(fc.end_ms) }}</span>
          </div>
          <p class="fc-summary">{{ fc.summary }}</p>
          <p class="fc-merged">合并自原章节：{{ fc.merged_segment_ids.map((id) => id + 1).join('、') }}</p>
        </div>
      </div>

      <div class="body">
        <nav class="chapters" aria-label="章节时间轴">
          <button
            v-for="(ch, i) in chapters"
            :key="ch.segment_id"
            class="chapter"
            :class="{ active: i === selectedIdx }"
            :aria-pressed="i === selectedIdx"
            @click="selectedIdx = i"
          >
            <span class="ch-title">{{ ch.title }}</span>
            <span class="ch-ts">{{ ch.points[0]?.ts_label ?? '' }}</span>
          </button>
        </nav>

        <div v-if="current" class="detail">
          <video
            v-if="videoSrc"
            ref="videoRef"
            class="video"
            :src="videoSrc"
            controls
            preload="metadata"
            aria-label="课程回放"
            @loadedmetadata="onVideoLoaded"
            @play="onVideoPlay"
            @pause="onVideoPause"
            @ended="onVideoPause"
            @seeked="onVideoSeeked"
            @ratechange="syncAudio"
            @volumechange="syncAudio"
          />
          <audio v-if="audioSrc" ref="audioRef" :src="audioSrc" preload="auto" />
          <p v-else-if="!store.slices.files.length" class="no-video">
            本会话无视频切片（可能只录了音频）。
          </p>

          <div v-if="current.chapter_summary?.trim()" class="chapter-summary">
            <h4 class="cs-title">章节总结</h4>
            <p class="cs-text">{{ current.chapter_summary }}</p>
          </div>

          <ul class="points">
            <li v-for="(p, pi) in current.points" :key="pi" class="point">
              <button
                class="ts-btn"
                :aria-label="`跳转到 ${p.ts_label}`"
                :disabled="!store.slices.files.length"
                @click="seekTo(p.ts_ms)"
              >
                [{{ p.ts_label }}]
              </button>
              <span class="p-text">{{ p.text }}</span>
              <template v-if="p.frame_ref">
                <button
                  class="frame-btn"
                  :aria-label="`查看课件帧并跳转到 ${p.ts_label}`"
                  @click="seekTo(p.ts_ms)"
                >
                  <img class="frame" :src="frameSrc(p.frame_ref)" alt="课件帧" loading="lazy" />
                </button>
                <details class="ocr" @toggle="loadOcr(p.frame_ref!)">
                  <summary>OCR 原文</summary>
                  <pre v-if="ocrCache[p.frame_ref!]">{{ ocrCache[p.frame_ref!] }}</pre>
                  <p v-else class="ocr-empty">
                    {{ p.frame_ref! in ocrCache ? '（该帧无 OCR 文本）' : '加载中…' }}
                  </p>
                </details>
              </template>
            </li>
          </ul>

          <div class="export-row">
            <button class="btn" :disabled="exporting" @click="doExport">
              {{ exporting ? '导出中…' : '导出 Markdown' }}
            </button>
            <button
              class="btn warning"
              :disabled="reprocessing"
              title="删除当前抽帧/OCR/对齐/总结结果，从视频重新分析"
              @click="doReprocess"
            >
              {{ reprocessing ? '重新分析中…' : '重新分析视频' }}
            </button>
            <button
              class="btn"
              :disabled="consolidating"
              title="把各章总结做最终去重汇总，剔除抽帧不准导致的重复章节，并重新拟真实章节标题；完成后导出的 Markdown 含「汇总定稿」一节"
              @click="doConsolidate"
            >
              {{ consolidating ? '汇总中…' : store.timeline.final_summary ? '重新汇总定稿' : '汇总定稿' }}
            </button>
            <button class="btn ghost" disabled title="思维导图（后续版本提供）">导图</button>
            <button class="btn ghost" disabled title="Anki 卡片（后续版本提供）">Anki</button>
          </div>
          <p v-if="store.lastExportPath" class="exported" role="status">
            已导出：{{ store.lastExportPath }}
          </p>
          <p v-if="exportError" class="error" role="alert">{{ exportError }}</p>
        </div>
      </div>
    </template>
  </section>
</template>

<style scoped>
.study {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.empty,
.no-video {
  color: #555;
  font-size: 13px;
}
.fallback-banner {
  font-size: 13px;
  color: #fbbf24;
  background: #2a230f;
  border: 1px solid #4a3d17;
  border-radius: 6px;
  padding: 8px 12px;
}
.outline {
  background: #161616;
  border: 1px solid #2a2a2a;
  border-radius: 8px;
  padding: 12px 16px;
  font-size: 13px;
}
.outline-title {
  font-size: 13px;
  color: #f0f0f0;
  margin-bottom: 6px;
}
.outline ul {
  padding-left: 18px;
  color: #bbb;
  line-height: 1.7;
}
.final {
  background: #141414;
  border: 1px solid #2f2f2f;
  border-radius: 8px;
  padding: 12px 16px;
  font-size: 13px;
}
.final-title {
  font-size: 13px;
  color: #f0f0f0;
  margin-bottom: 8px;
}
.final-chapter {
  padding: 8px 0;
  border-top: 1px solid #232323;
}
.final-chapter:first-of-type {
  border-top: none;
}
.fc-head {
  display: flex;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 4px;
}
.fc-title {
  color: #e0e0e0;
  font-weight: 600;
}
.fc-ts {
  color: #666;
  font-variant-numeric: tabular-nums;
  flex-shrink: 0;
}
.fc-summary {
  color: #bbb;
  line-height: 1.7;
  white-space: pre-wrap;
}
.fc-merged {
  margin-top: 4px;
  color: #555;
  font-size: 12px;
}
.body {
  display: flex;
  gap: 14px;
  align-items: flex-start;
}
.chapters {
  display: flex;
  flex-direction: column;
  gap: 4px;
  width: 220px;
  flex-shrink: 0;
}
.chapter {
  display: flex;
  justify-content: space-between;
  gap: 8px;
  padding: 8px 12px;
  font-size: 13px;
  text-align: left;
  background: #161616;
  color: #bbb;
  border: 1px solid #2a2a2a;
  border-radius: 6px;
  cursor: pointer;
}
.chapter:hover {
  color: #e0e0e0;
}
.chapter.active {
  border-color: #2563eb;
  color: #fff;
}
.chapter:focus-visible {
  outline: 2px solid #60a5fa;
  outline-offset: 1px;
}
.ch-ts {
  color: #666;
  font-variant-numeric: tabular-nums;
  flex-shrink: 0;
}
.detail {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.video {
  width: 100%;
  max-height: 300px;
  background: #000;
  border-radius: 6px;
}
.chapter-summary {
  background: #141a14;
  border: 1px solid #23402a;
  border-radius: 8px;
  padding: 10px 14px;
}
.cs-title {
  font-size: 12px;
  color: #4ade80;
  margin-bottom: 6px;
}
.cs-text {
  font-size: 13px;
  line-height: 1.8;
  color: #d5d5d5;
  white-space: pre-wrap;
}
.points {
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.point {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-start;
  gap: 8px;
  font-size: 13px;
  line-height: 1.6;
}
.ts-btn {
  background: none;
  border: none;
  color: #60a5fa;
  cursor: pointer;
  font-size: 12px;
  font-variant-numeric: tabular-nums;
  padding: 2px 0;
}
.ts-btn:disabled {
  color: #555;
  cursor: default;
}
.ts-btn:focus-visible,
.frame-btn:focus-visible {
  outline: 2px solid #60a5fa;
  outline-offset: 1px;
}
.p-text {
  flex: 1;
  min-width: 200px;
  color: #e0e0e0;
}
.frame-btn {
  background: none;
  border: 1px solid #2a2a2a;
  border-radius: 4px;
  padding: 2px;
  cursor: pointer;
}
.frame {
  display: block;
  width: 120px;
  border-radius: 3px;
}
.ocr {
  width: 100%;
  font-size: 12px;
  color: #999;
}
.ocr summary {
  cursor: pointer;
  color: #777;
}
.ocr pre {
  margin-top: 6px;
  padding: 8px 10px;
  background: #111;
  border-radius: 4px;
  white-space: pre-wrap;
  color: #aaa;
  max-height: 160px;
  overflow-y: auto;
}
.ocr-empty {
  margin-top: 6px;
  color: #555;
}
.export-row {
  display: flex;
  gap: 10px;
}
.btn {
  padding: 7px 18px;
  font-size: 13px;
  background: #2563eb;
  color: #fff;
  border: none;
  border-radius: 6px;
  cursor: pointer;
}
.btn:hover:not(:disabled) {
  background: #1d4ed8;
}
.btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.btn.ghost {
  background: #222;
  border: 1px solid #333;
}
.btn.warning {
  background: #78350f;
  border: 1px solid #92400e;
  color: #fbbf24;
}
.btn.warning:hover:not(:disabled) {
  background: #92400e;
}
.btn:focus-visible {
  outline: 2px solid #60a5fa;
  outline-offset: 1px;
}
.exported {
  font-size: 12px;
  color: #4ade80;
  word-break: break-all;
}
.error {
  font-size: 13px;
  color: #f87171;
}
</style>
