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
const currentSlice = ref('')
const pendingSeekSec = ref<number | null>(null)
const ocrCache = ref<Record<string, string | null>>({})
const exporting = ref(false)
const exportError = ref('')

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
          />
          <p v-else-if="!store.slices.files.length" class="no-video">
            本会话无视频切片（可能只录了音频）。
          </p>

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
