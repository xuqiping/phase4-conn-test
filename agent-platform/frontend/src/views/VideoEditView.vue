<template>
  <div class="video-edit">
    <!-- 雾中浮岛场景层（ART-DIR-0002R 方向二，仅 ink 主题渲染） -->
    <ModuleScene scene="video-edit" />
    <!-- 高山流水批次C：统一页头（ART-DIR-0002 P3，ink 主题文楷+发丝线，旧主题零变化） -->
    <PageHeader title="视频剪辑" sub="多轨时间线 · 视频 / 音轨 / 字幕 · FFmpeg 渲染 + 剪映草稿导出" />

    <!-- 无权限兜底 -->
    <InkEmptyState v-if="!canEdit" type="forbidden" description="无 media:edit 权限，请联系管理员授权" class="video-edit__forbidden" />

    <div v-else class="video-edit__grid" :class="{ 'video-edit__grid--mobile': isMobile }">
      <!-- 左：剪辑台 -->
      <n-card class="video-edit__stage u-ink-card" title="剪辑台" size="small">
        <!-- 素材库 -->
        <div class="video-edit__section">
          <div class="video-edit__section-title">素材库</div>
          <n-space vertical size="small">
            <n-upload :max="5" multiple accept="video/*" :show-file-list="false" :custom-request="handleVideoUpload">
              <n-button size="small">上传视频片段</n-button>
            </n-upload>
            <n-upload :max="5" multiple accept="audio/*" :show-file-list="false" :custom-request="handleAudioUpload">
              <n-button size="small">上传音频</n-button>
            </n-upload>
            <div v-if="assets.length" class="video-edit__assets">
              <div v-for="a in assets" :key="a.fileId" class="video-edit__asset" @click="addGenerated(a)">
                <span class="video-edit__asset-name">{{ a.name }}</span>
                <span class="video-edit__asset-meta">
                  {{ a.durationSeconds ? a.durationSeconds + 's' : '-' }} · 点击加入视频轨
                </span>
              </div>
            </div>
            <span v-else class="video-edit__hint">暂无已生成视频，可上传素材</span>
          </n-space>
        </div>

        <!-- 时间线（多轨） -->
        <div class="video-edit__section">
          <Timeline
            :tracks="tracks"
            :clip-info="clipInfo"
            :max-audio-tracks="MAX_AUDIO_TRACKS"
            @select="onTimelineSelect"
            @remove-block="onRemoveBlock"
            @remove-track="onRemoveTrack"
            @add-audio-track="onAddAudioTrack"
            @add-text="onAddText"
          />
        </div>

        <!-- 属性面板（选中段编辑） -->
        <div class="video-edit__section" v-if="selSeg">
          <div class="video-edit__section-title">
            属性 · {{ selIsText ? '字幕' : (selTrackType === 'AUDIO' ? '音频' : '视频') }}段
          </div>
          <n-space align="center" size="small" wrap>
            <template v-if="selIsText">
              <n-input v-model:value="(selSeg as TextSegmentSpec).content" size="small" placeholder="字幕文本" style="width: 200px" />
              <n-select v-model:value="(selSeg as TextSegmentSpec).position" size="small" :options="positionOptions" style="width: 96px" />
              <span class="video-edit__hint">字号</span>
              <n-input-number v-model:value="(selSeg as TextSegmentSpec).fontSize" :min="10" :max="120" size="small" style="width: 90px" />
            </template>
            <template v-else>
              <span class="video-edit__hint">起点</span>
              <n-input-number :value="(selSeg as SegmentSpec).targetStart" :min="0" :step="0.5" size="small" style="width: 90px" @update:value="v => onTargetChange('start', v)" />
              <span class="video-edit__hint">止点</span>
              <n-input-number :value="(selSeg as SegmentSpec).targetEnd" :min="0" :step="0.5" size="small" style="width: 90px" @update:value="v => onTargetChange('end', v)" />
              <template v-if="selTrackType === 'AUDIO'">
                <span class="video-edit__hint">音量</span>
                <n-input-number v-model:value="(selSeg as SegmentSpec).volume" :min="0" :max="1" :step="0.1" size="small" style="width: 80px" />
              </template>
              <span v-if="infoOf((selSeg as SegmentSpec).fileId).duration" class="video-edit__hint">
                片长 {{ infoOf((selSeg as SegmentSpec).fileId).duration }}s
              </span>
            </template>
          </n-space>
        </div>

        <!-- 输出 -->
        <div class="video-edit__section">
          <div class="video-edit__section-title">输出</div>
          <n-space align="center" size="small">
            <n-select v-model:value="output.resolution" :options="resolutionOptions" size="small" style="width: 140px" />
            <span class="video-edit__hint">fps</span>
            <n-input-number v-model:value="output.fps" :min="1" :max="60" size="small" style="width: 90px" />
          </n-space>
        </div>

        <!-- 操作 -->
        <n-space>
          <n-button type="primary" :loading="submitting" :disabled="!canSubmit" @click="onSubmit">提交渲染</n-button>
          <n-button :loading="exporting" :disabled="!canSubmit" @click="onExportDraft">导出剪映草稿</n-button>
          <span v-if="!canSubmit" class="video-edit__hint">视频轨至少 1 段</span>
        </n-space>
      </n-card>

      <!-- 右：活动任务 + 历史 -->
      <div class="video-edit__result">
        <n-card class="video-edit__active u-ink-card" size="small">
          <template #header>
            <n-space align="center" size="small">
              <span>当前任务</span>
              <n-tag v-if="activeTask" size="small" :type="EDIT_STATUS_TYPE[activeTask.status]" :bordered="false">
                {{ EDIT_STATUS_LABEL[activeTask.status] }}
              </n-tag>
            </n-space>
          </template>
          <div v-if="!activeTask" class="video-edit__placeholder">提交渲染后在此查看结果</div>
          <template v-else>
            <div v-if="activeTask.status === 'PENDING' || activeTask.status === 'RUNNING'" class="video-edit__loading">
              <n-spin size="large" />
              <p>{{ EDIT_STATUS_LABEL[activeTask.status] }}…后端 FFmpeg 渲染中，请耐心等待</p>
            </div>
            <div v-else-if="activeTask.status === 'SUCCEEDED'" class="video-edit__player">
              <video v-if="resultObjectUrl" :src="resultObjectUrl" controls playsinline class="video-edit__video" />
              <n-button v-if="resultObjectUrl" size="small" tag="a" :href="resultObjectUrl" download @click.stop>下载成片</n-button>
            </div>
            <div v-else class="video-edit__error">
              <p>{{ EDIT_STATUS_LABEL[activeTask.status] }}</p>
              <p v-if="activeTask.errorMsg" class="video-edit__error-msg">{{ activeTask.errorMsg }}</p>
            </div>
            <div class="video-edit__meta-preview">
              {{ activeTask.clipsCount }} 段
              <span v-if="activeTask.hasBgm"> · 含音频轨</span>
              <span v-if="activeTask.subtitlesCount"> · {{ activeTask.subtitlesCount }} 条字幕</span>
            </div>
          </template>
        </n-card>

        <n-card class="video-edit__history u-ink-card" title="历史任务" size="small">
          <n-data-table :columns="historyColumns" :data="history" :loading="loadingHistory" size="small"
            :pagination="{ pageSize: 8 }" :max-height="320" striped />
        </n-card>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, h, onMounted, reactive, ref } from 'vue'
import {
  NButton, NCard, NDataTable, NInput, NInputNumber, NSelect,
  NSpace, NSpin, NTag, NUpload, useMessage
} from 'naive-ui'
import type { DataTableColumns, UploadCustomRequestOptions } from 'naive-ui'
import { useAuthStore } from '@/stores/auth'
import { useBreakpoints } from '@/composables/useBreakpoints'
import Timeline from '@/components/video-edit/Timeline.vue'
import InkEmptyState from '@/components/InkEmptyState.vue'
import PageHeader from '@/components/PageHeader.vue'
import ModuleScene from '@/components/ModuleScene.vue'
import {
  mediaEditApi, fetchResultBlob,
  EDIT_STATUS_LABEL, EDIT_STATUS_TYPE, isTerminalEdit,
  type MediaEditTaskVO, type MediaAssetVO,
  type TrackSpec, type SegmentSpec, type TextSegmentSpec, type EditSpec, type EditResolution
} from '@/api/mediaEdit'

const authStore = useAuthStore()
const message = useMessage()
const { isMobile } = useBreakpoints()
const canEdit = authStore.hasPermission('media:edit')

const MAX_AUDIO_TRACKS = 4
let idSeq = 1
const genId = () => `t${idSeq++}`

// === 多轨状态 ===
// 初始：1 视频轨 + 1 字幕轨；音轨按需加（上限 MAX_AUDIO_TRACKS）
const tracks = ref<TrackSpec[]>([
  { id: 'video', type: 'VIDEO', segments: [] },
  { id: 'text', type: 'TEXT', texts: [] }
])
const output = reactive<{ resolution: EditResolution; fps: number }>({ resolution: '720p', fps: 24 })

const videoTrack = computed(() => tracks.value.find(t => t.type === 'VIDEO')!)
const textTrack = computed(() => tracks.value.find(t => t.type === 'TEXT'))

/** fileId → name/duration 展示元信息，与 segments 平行维护，不入提交体。 */
const clipInfo = reactive<Record<string, { name: string; duration: number | null }>>({})
function infoOf(fileId: string) {
  return clipInfo[fileId] || { name: '素材', duration: null as number | null }
}

// === 选中段（属性面板） ===
const sel = ref<{ trackId: string; index: number; isText: boolean } | null>(null)
const selTrack = computed(() => (sel.value ? tracks.value.find(t => t.id === sel.value!.trackId) : null))
const selSeg = computed(() => {
  if (!sel.value || !selTrack.value) return null
  const t = selTrack.value
  return sel.value.isText ? (t.texts?.[sel.value.index] ?? null) : (t.segments?.[sel.value.index] ?? null)
})
const selIsText = computed(() => sel.value?.isText ?? false)
const selTrackType = computed(() => selTrack.value?.type)

// === 素材库 ===
const assets = ref<MediaAssetVO[]>([])
const loadingAssets = ref(false)
async function loadAssets() {
  loadingAssets.value = true
  try {
    const { data } = await mediaEditApi.listAssets()
    assets.value = data.data
  } catch { /* 拦截器提示 */ } finally { loadingAssets.value = false }
}

function round1(x: number) { return Math.round(x * 10) / 10 }

/** 把素材加到视频轨末尾（首尾相接，默认不裁剪）。 */
function addToVideoTrack(fileId: string, name: string, sourceType: 'GEN' | 'UPLOAD', duration: number | null) {
  const vt = videoTrack.value
  vt.segments = vt.segments || []
  const start = vt.segments.length ? Math.max(...vt.segments.map(s => s.targetEnd)) : 0
  const dur = duration && duration > 0 ? duration : 5
  vt.segments.push({
    fileId, sourceType, trimStart: null, trimEnd: null,
    targetStart: round1(start), targetEnd: round1(start + dur)
  })
  clipInfo[fileId] = { name, duration }
}
function addGenerated(a: MediaAssetVO) {
  addToVideoTrack(a.fileId, a.name, 'GEN', a.durationSeconds)
}

async function handleVideoUpload({ file, onFinish, onError }: UploadCustomRequestOptions) {
  const raw = file.file as File | null
  if (!raw) return onError()
  try {
    const { data } = await mediaEditApi.uploadAsset(raw)
    addToVideoTrack(data.data.fileId, raw.name, 'UPLOAD', null)
    onFinish()
  } catch { onError(); message.error('视频上传失败') }
}

async function handleAudioUpload({ file, onFinish, onError }: UploadCustomRequestOptions) {
  const raw = file.file as File | null
  if (!raw) return onError()
  try {
    const { data } = await mediaEditApi.uploadAsset(raw)
    // 加到「最后一条音轨」，没有则新建
    let at = tracks.value.filter(t => t.type === 'AUDIO').pop()
    if (!at) {
      at = { id: genId(), type: 'AUDIO', name: '音频轨', volume: 0.5, segments: [] }
      tracks.value.push(at)
    }
    at.segments = at.segments || []
    const start = at.segments.length ? Math.max(...at.segments.map(s => s.targetEnd)) : 0
    at.segments.push({ fileId: data.data.fileId, trimStart: null, trimEnd: null, targetStart: round1(start), targetEnd: round1(start + 5), volume: 0.5 })
    clipInfo[data.data.fileId] = { name: raw.name, duration: null }
    onFinish()
  } catch { onError(); message.error('音频上传失败') }
}

// === Timeline 事件 ===
function onTimelineSelect(p: { trackId: string; index: number; isText: boolean } | null) {
  sel.value = p
}
function onRemoveBlock(p: { trackId: string; index: number; isText: boolean }) {
  const t = tracks.value.find(tr => tr.id === p.trackId)
  if (!t) return
  if (p.isText) t.texts?.splice(p.index, 1)
  else t.segments?.splice(p.index, 1)
  sel.value = null
}
function onRemoveTrack(trackId: string) {
  tracks.value = tracks.value.filter(t => t.id !== trackId)
  if (sel.value?.trackId === trackId) sel.value = null
}
function onAddAudioTrack() {
  if (tracks.value.filter(t => t.type === 'AUDIO').length >= MAX_AUDIO_TRACKS) return
  tracks.value.push({ id: genId(), type: 'AUDIO', name: '音频轨', volume: 0.5, segments: [] })
}
function onAddText() {
  let tt = textTrack.value
  if (!tt) {
    tt = { id: 'text', type: 'TEXT', texts: [] }
    tracks.value.push(tt)
  }
  tt.texts = tt.texts || []
  const start = tt.texts.length ? Math.max(...tt.texts.map(x => x.targetEnd)) : 0
  tt.texts.push({ content: '', targetStart: round1(start), targetEnd: round1(start + 2), position: 'BOTTOM' })
}

const positionOptions = [
  { label: '底部', value: 'BOTTOM' as const },
  { label: '居中', value: 'CENTER' as const }
]
const resolutionOptions: { label: string; value: EditResolution }[] = [
  { label: '480p（省资源）', value: '480p' },
  { label: '720p（推荐）', value: '720p' },
  { label: '1080p（高清）', value: '1080p' }
]

// === 组装 EditSpec（提交渲染 / 导出草稿共用） ===
/** 属性面板改时间轴起/止 → 直接改 target（与标尺同计量），trim 跟随（无变速：trim 用量=target 占用，source 入点不变）。 */
function onTargetChange(which: 'start' | 'end', val: number | null) {
  const seg = selSeg.value as SegmentSpec | null
  if (!seg || val == null) return
  if (which === 'start') seg.targetStart = val
  else seg.targetEnd = val
  const ts = seg.trimStart ?? 0
  const occ = Math.max(0, (seg.targetEnd ?? 0) - (seg.targetStart ?? 0))
  seg.trimEnd = round1(ts + occ)
}

function buildSpec(): EditSpec {
  // 过滤空字幕内容；其余结构原样（后端 ignoreUnknown 忽略前端临时 id）
  const cleanTracks: TrackSpec[] = tracks.value.map(t => {
    if (t.type === 'TEXT') {
      return { ...t, texts: (t.texts || []).filter(x => x.content && x.content.trim()) }
    }
    return t
  })
  return { schemaVersion: 2, tracks: cleanTracks, output: { resolution: output.resolution, fps: output.fps } }
}

const canSubmit = computed(() => !!(videoTrack.value.segments && videoTrack.value.segments.length > 0))
const submitting = ref(false)
const exporting = ref(false)

async function onSubmit() {
  submitting.value = true
  try {
    const { data } = await mediaEditApi.submit(buildSpec())
    message.success('任务已提交，正在渲染…')
    startPolling(data.data.id)
    void loadHistory()
  } catch { /* 拦截器已提示（含后端校验 400） */ } finally { submitting.value = false }
}

async function onExportDraft() {
  exporting.value = true
  try {
    const { blob, filename } = await mediaEditApi.exportDraft(buildSpec())
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = filename
    document.body.appendChild(a)
    a.click()
    document.body.removeChild(a)
    URL.revokeObjectURL(url)
    message.success('剪映草稿已导出（解压后拖入剪映即可）')
  } catch { /* 拦截器提示 */ } finally { exporting.value = false }
}

// === 轮询 + 成片播放 ===
const activeTask = ref<MediaEditTaskVO | null>(null)
const resultObjectUrl = ref<string | null>(null)
let pollTimer: ReturnType<typeof setInterval> | null = null
function clearPolling() { if (pollTimer !== null) { clearInterval(pollTimer); pollTimer = null } }
function revokeResult() { if (resultObjectUrl.value) { URL.revokeObjectURL(resultObjectUrl.value); resultObjectUrl.value = null } }
async function ensureResult(task: MediaEditTaskVO) {
  if (!task.videoUrl) return
  revokeResult()
  try { resultObjectUrl.value = await fetchResultBlob(task.videoUrl) } catch { message.error('成片加载失败') }
}
async function pollOnce(taskId: number) {
  try {
    const { data } = await mediaEditApi.getTask(taskId)
    activeTask.value = data.data
    if (data.data.status === 'SUCCEEDED' && data.data.videoUrl && !resultObjectUrl.value) void ensureResult(data.data)
    if (isTerminalEdit(data.data.status)) { clearPolling(); void loadHistory() }
  } catch { /* 网络错误依赖 request.ts 熔断防轮询风暴 */ }
}
function startPolling(taskId: number) {
  clearPolling()
  void pollOnce(taskId)
  pollTimer = setInterval(() => void pollOnce(taskId), 3000)
}

// === 历史 ===
const history = ref<MediaEditTaskVO[]>([])
const loadingHistory = ref(false)
async function loadHistory() {
  loadingHistory.value = true
  try { const { data } = await mediaEditApi.listTasks(50); history.value = data.data }
  catch { /* 拦截器提示 */ } finally { loadingHistory.value = false }
}
const historyColumns: DataTableColumns<MediaEditTaskVO> = [
  { title: 'ID', key: 'id', width: 60 },
  { title: '片段', key: 'clipsCount', width: 60, render: r => String(r.clipsCount) },
  {
    title: '状态', key: 'status', width: 90,
    render: r => h(NTag, { size: 'small', type: EDIT_STATUS_TYPE[r.status], bordered: false }, () => EDIT_STATUS_LABEL[r.status])
  },
  { title: '创建', key: 'createdAt', width: 160 }
]

onMounted(() => {
  void loadAssets()
  void loadHistory()
})
</script>

<style lang="scss" scoped>
.video-edit {
  // 页头已由 PageHeader 组件承担（批次C）
  &__forbidden { padding: 60px 0; }
  &__grid {
    display: grid; grid-template-columns: 1fr 420px; gap: 16px; align-items: start;
    &--mobile { grid-template-columns: 1fr; }
  }
  &__stage { min-width: 0; }
  &__section { margin-bottom: 16px; }
  &__section-title { font-size: 13px; font-weight: 600; margin-bottom: 8px; opacity: 0.85; }
  &__hint { font-size: 12px; opacity: 0.5; }
  &__assets { display: flex; flex-direction: column; gap: 4px; max-height: 160px; overflow-y: auto; }
  &__asset {
    padding: 6px 8px; border-radius: 4px; background: rgba(255, 255, 255, 0.04); cursor: pointer;
    &:hover { background: rgba(255, 255, 255, 0.08); }
  }
  &__asset-name { font-size: 13px; }
  &__asset-meta { font-size: 11px; opacity: 0.5; margin-left: 6px; }
  &__result { display: flex; flex-direction: column; gap: 16px; min-width: 0; }
  &__placeholder { font-size: 13px; opacity: 0.4; padding: 24px 0; text-align: center; }
  &__loading { text-align: center; padding: 24px 0; p { font-size: 13px; opacity: 0.6; margin-top: 8px; } }
  &__player { display: flex; flex-direction: column; gap: 8px; }
  &__video { width: 100%; border-radius: 6px; background: #000; }
  &__error { padding: 16px 0; p { margin: 4px 0; } }
  &__error-msg { font-size: 12px; opacity: 0.6; word-break: break-all; }
  &__meta-preview { font-size: 12px; opacity: 0.5; margin-top: 8px; }
}
</style>
