<template>
  <div class="video-reverse">
    <!-- ① 来源：上传本地视频 / 历史任务 -->
    <n-card size="small" title="① 选择视频来源" class="video-reverse__card">
      <n-radio-group v-model:value="sourceKind" size="small">
        <n-radio-button value="upload">上传本地视频</n-radio-button>
        <n-radio-button value="task">从历史任务选</n-radio-button>
      </n-radio-group>

      <div v-if="sourceKind === 'upload'" class="video-reverse__upload">
        <n-upload
          :show-file-list="false"
          accept="video/*"
          :custom-request="handleUpload"
        >
          <n-button size="small" :loading="uploading">
            <template #icon><n-icon :component="CloudUploadOutline" /></template>
            选择视频文件（≤10 分钟）
          </n-button>
        </n-upload>
        <div v-if="uploadedName" class="video-reverse__source-tag">
          已选：{{ uploadedName }}
        </div>
      </div>

      <div v-else class="video-reverse__taskpick">
        <n-select
          v-model:value="selectedTaskId"
          size="small"
          filterable
          placeholder="选择已完成的视频生成任务"
          :options="taskOptions"
          :loading="tasksLoading"
        />
        <div class="video-reverse__hint">仅列出你本人已完成的视频任务（≤最近 50 条）。</div>
      </div>
    </n-card>

    <!-- ② 产物与分析 -->
    <n-card size="small" title="② 反推产物" class="video-reverse__card">
      <n-checkbox-group v-model:value="modes">
        <n-space :size="12">
          <n-checkbox value="KEYFRAMES" label="关键帧" />
          <n-checkbox value="STORYBOARD" label="分镜表" />
          <n-checkbox value="SCRIPT" label="剧本" />
        </n-space>
      </n-checkbox-group>
      <div class="video-reverse__adv">
        <n-input-number v-model:value="maxFrames" size="small" :min="4" :max="24" placeholder="帧数(4-24)" />
        <n-input-number v-model:value="sceneThreshold" size="small" :min="0.1" :max="0.9" :step="0.05" placeholder="阈值0.1-0.9" />
      </div>
      <div class="video-reverse__hint">
        帧数默认 12 上限 24；仅勾「关键帧」不调大模型，分镜/剧本按帧计费（≤{{ maxFrames ?? 12 }} 帧多模态 token）。
      </div>
      <n-space>
        <n-button type="primary" size="small" :loading="analyzing" :disabled="!hasSource || modes.length === 0" @click="runAnalyze">
          开始反推
        </n-button>
        <n-button v-if="analyzing" size="small" tertiary type="warning" @click="abortController?.abort()">
          取消
        </n-button>
      </n-space>
    </n-card>

    <!-- ③ 结果区：关键帧时间轴条 / 分镜表 / 剧本 -->
    <n-card v-if="result" size="small" title="③ 反推结果" class="video-reverse__card">
      <div class="video-reverse__meta">
        {{ result.keyframes.length }} 帧 · 时长 {{ result.durationSeconds.toFixed(1) }}s ·
        {{ result.mode === 'SCENE' ? `场景检测（命中 ${result.sceneHits}）` : '均匀采样兜底' }}
        <n-tag v-if="result.model" size="tiny" :bordered="false">{{ result.model }}</n-tag>
      </div>

      <!-- 关键帧时间轴条：缩略横排（悬浮放大 + 点击灯箱，复用共享预览组件） -->
      <div v-if="result.keyframes.length" class="video-reverse__timeline">
        <div v-for="kf in result.keyframes" :key="kf.fileId" class="video-reverse__frame">
          <HoverPreviewImage
            v-if="frameUrls[kf.fileId]"
            :preview-src="frameUrls[kf.fileId]"
            :alt="`第${kf.shotNo}帧 ${kf.timestampSec.toFixed(1)}s`"
          >
            <img
              :src="frameUrls[kf.fileId]"
              :alt="`第${kf.shotNo}帧`"
              class="video-reverse__frame-img"
              @click="lightboxSrc = frameUrls[kf.fileId]"
            >
          </HoverPreviewImage>
          <div v-else class="video-reverse__frame-img video-reverse__frame-img--ph">…</div>
          <span class="video-reverse__frame-t">{{ kf.timestampSec.toFixed(1) }}s</span>
        </div>
      </div>

      <!-- 分镜表（n-data-table，LLM 字段开放→列取已知键容错） -->
      <template v-if="result.storyboard && result.storyboard.length">
        <div class="video-reverse__section-title">分镜表（{{ result.storyboard.length }} 镜）</div>
        <n-data-table :columns="storyboardColumns" :data="result.storyboard" size="small" :scroll-x="900" />
      </template>

      <!-- 剧本（可复制） -->
      <template v-if="result.script">
        <div class="video-reverse__section-title">
          剧本
          <n-button size="tiny" tertiary @click="copyText(scriptPretty, '剧本已复制')">复制</n-button>
        </div>
        <pre class="video-reverse__pre">{{ scriptPretty }}</pre>
      </template>
    </n-card>

    <!-- ④ 本土化转绘（需先反推出剧本） -->
    <n-card v-if="result?.script" size="small" title="④ 本土化转绘" class="video-reverse__card">
      <div class="video-reverse__adv">
        <n-input v-model:value="targetLocale" size="small" placeholder="目标国家/地区，如：美国 / 西方" style="flex: 1" />
        <n-input v-model:value="localizeNotes" size="small" placeholder="保留要求（可选），如：保留春节团圆情节" style="flex: 1" />
      </div>
      <n-space>
        <n-button
          type="primary"
          size="small"
          :loading="localizing"
          :disabled="!targetLocale.trim()"
          @click="runLocalize"
        >
          开始转绘
        </n-button>
        <n-button
          size="small"
          tertiary
          :disabled="!scriptForGen"
          @click="emit('use-script', {
            promptText: scriptForGen,
            sourceFileId: sourceFileId ?? undefined,
            sourceName: sourceName ?? undefined
          })"
        >
          用剧本生成（{{ genScriptFrom === 'original' ? '原剧本' : '本土化' }}）→ 填入生成表单
        </n-button>
        <n-radio-group v-if="localized" v-model:value="genScriptFrom" size="small">
          <n-radio value="original">原剧本</n-radio>
          <n-radio value="localized">本土化版</n-radio>
        </n-radio-group>
      </n-space>

      <template v-if="localized">
        <n-alert v-if="localized.warning" type="warning" :show-icon="false" style="margin-top: 10px">
          {{ localized.warning }}（结果仍可用，请人工核对）
        </n-alert>
        <div class="video-reverse__section-title">
          改写剧本
          <n-button size="tiny" tertiary @click="copyText(localized!.localizedScript, '改写剧本已复制')">复制</n-button>
        </div>
        <pre class="video-reverse__pre">{{ localizedPretty }}</pre>
        <div class="video-reverse__section-title">替换清单（changeLog，{{ localized.changeLog.length }} 处）</div>
        <n-data-table :columns="changeLogColumns" :data="localized.changeLog" size="small" :scroll-x="500" />
      </template>
    </n-card>

    <MediaLightbox :src="lightboxSrc" alt="关键帧" @close="lightboxSrc = null" />
  </div>
</template>

<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import {
  NAlert, NButton, NCard, NCheckbox, NCheckboxGroup, NDataTable, NIcon, NInput, NInputNumber,
  NRadio, NRadioButton, NRadioGroup, NSelect, NSpace, NTag, NUpload,
  useMessage
} from 'naive-ui'
import type { DataTableColumns, SelectOption, UploadCustomRequestOptions } from 'naive-ui'
import { CloudUploadOutline } from '@vicons/ionicons5'
import HoverPreviewImage from './HoverPreviewImage.vue'
import MediaLightbox from './MediaLightbox.vue'
import { mediaApi } from '@/api/media'
import type {
  LocalizeResult, MediaTaskVO, ReverseAnalyzeResult, ReverseMode, ReverseStoryboardShot
} from '@/api/media'
import { fetchFilePreview } from '@/api/file'

const emit = defineEmits<{
  /** 「用剧本生成」：把剧本文本带回生成表单预填（冲突三选由父组件处理，plan L4）。 */
  (e: 'use-script', payload: {
    promptText: string
    sourceFileId?: string
    sourceName?: string
  }): void
}>()

const message = useMessage()

// ---------- ① 来源 ----------
const sourceKind = ref<'upload' | 'task'>('upload')
const uploading = ref(false)
const uploadedFileId = ref<string | null>(null)
const uploadedName = ref<string | null>(null)
const tasksLoading = ref(false)
const taskOptions = ref<SelectOption[]>([])
/** taskId → 任务 VO（取 resultFileId 作「用剧本生成」参考视频槽预填）。 */
const taskById = ref<Record<number, MediaTaskVO>>({})
const selectedTaskId = ref<number | null>(null)

const hasSource = computed(() =>
  sourceKind.value === 'upload' ? uploadedFileId.value != null : selectedTaskId.value != null
)
const sourceName = computed(() =>
  sourceKind.value === 'upload'
    ? uploadedName.value
    : selectedTaskId.value != null
      ? (taskOptions.value.find(o => o.value === selectedTaskId.value)?.label as string | undefined) ?? null
      : null
)
const sourceFileId = computed(() =>
  sourceKind.value === 'upload'
    ? uploadedFileId.value
    : selectedTaskId.value != null ? taskById.value[selectedTaskId.value]?.resultFileId ?? null : null
)

/** 历史任务列表懒加载标记（切到该来源才拉，一次）。 */
let tasksLoaded = false

async function loadTasks() {
  tasksLoading.value = true
  try {
    const { data } = await mediaApi.listTasks({ kind: 'VIDEO', pageSize: 50 })
    const done = data.data.records.filter((t: MediaTaskVO) => t.status === 'SUCCEEDED' && (t.resultFileId || t.videoUrl))
    taskById.value = Object.fromEntries(done.map((t: MediaTaskVO) => [t.id, t]))
    taskOptions.value = done.map((t: MediaTaskVO) => ({
      label: `#${t.id} · ${(t.model ?? '视频')} · ${(t.prompt ?? '').slice(0, 24)}`,
      value: t.id
    }))
  } catch {
    message.error('历史任务加载失败')
  } finally {
    tasksLoading.value = false
  }
}

async function handleUpload({ file, onFinish, onError }: UploadCustomRequestOptions) {
  const raw = file.file as File | null
  if (!raw) { onError(); return }
  uploading.value = true
  try {
    const { data } = await mediaApi.uploadAttachment(raw)
    uploadedFileId.value = data.data.fileId
    uploadedName.value = raw.name
    selectedTaskId.value = null
    message.success('视频已上传，可开始反推')
    onFinish()
  } catch {
    message.error('视频上传失败')
    onError()
  } finally {
    uploading.value = false
  }
}

// ---------- ② 分析 ----------
const modes = ref<ReverseMode[]>(['KEYFRAMES'])
const maxFrames = ref<number | null>(null)
const sceneThreshold = ref<number | null>(null)
const analyzing = ref(false)
const abortController = ref<AbortController | null>(null)
const result = ref<ReverseAnalyzeResult | null>(null)
/** 关键帧 fileId → 鉴权 blob objectURL（时间轴条缩略 + 灯箱）。 */
const frameUrls = ref<Record<string, string>>({})
const lightboxSrc = ref<string | null>(null)

const scriptPretty = computed(() =>
  result.value?.script ? JSON.stringify(result.value.script, null, 2) : ''
)

async function runAnalyze() {
  if (!hasSource.value) return
  abortController.value = new AbortController()
  analyzing.value = true
  result.value = null
  localized.value = null
  try {
    const { data } = await mediaApi.reverseAnalyze({
      taskId: sourceKind.value === 'task' ? selectedTaskId.value : null,
      fileId: sourceKind.value === 'upload' ? uploadedFileId.value : null,
      modes: [...modes.value],
      maxFrames: maxFrames.value ?? undefined,
      sceneThreshold: sceneThreshold.value ?? undefined
    }, abortController.value.signal)
    result.value = data.data
    // 帧预览懒加载（鉴权 blob；失败留占位不阻断）
    Object.values(frameUrls.value).forEach(u => URL.revokeObjectURL(u))
    frameUrls.value = {}
    for (const kf of data.data.keyframes) {
      try {
        frameUrls.value[kf.fileId] = await fetchFilePreview(kf.fileId)
      } catch { /* 占位 */ }
    }
    message.success(`反推完成：${data.data.keyframes.length} 帧`)
  } catch (e: unknown) {
    const err = e as { code?: string; name?: string; msg?: string }
    if (err?.code === 'ERR_CANCELED' || err?.name === 'CanceledError' || err?.name === 'AbortError') {
      message.info('已取消反推')
    } else {
      message.error(err?.msg || '反推失败')
    }
  } finally {
    analyzing.value = false
    abortController.value = null
  }
}

// ---------- ④ 转绘 ----------
const targetLocale = ref('')
const localizeNotes = ref('')
const localizing = ref(false)
const localized = ref<LocalizeResult | null>(null)
/** 「用剧本生成」取哪版剧本（转绘产出后可切换）。 */
const genScriptFrom = ref<'original' | 'localized'>('original')
const localizedPretty = computed(() => {
  if (!localized.value) return ''
  try { return JSON.stringify(JSON.parse(localized.value.localizedScript), null, 2) } catch {
    return localized.value.localizedScript
  }
})
const scriptForGen = computed(() =>
  genScriptFrom.value === 'localized' ? localizedPretty.value : scriptPretty.value
)

async function runLocalize() {
  if (!scriptPretty.value) return
  localizing.value = true
  try {
    const { data } = await mediaApi.reverseLocalize({
      script: scriptPretty.value,
      targetLocale: targetLocale.value.trim(),
      notes: localizeNotes.value.trim() || undefined
    })
    localized.value = data.data
    genScriptFrom.value = 'localized'
    if (data.data.warning) message.warning(data.data.warning)
    else message.success(`转绘完成：替换 ${data.data.changeLog.length} 处`)
  } catch (e: unknown) {
    message.error((e as { msg?: string })?.msg || '转绘失败')
  } finally {
    localizing.value = false
  }
}

// ---------- 表格列 ----------
const storyboardColumns: DataTableColumns<ReverseStoryboardShot> = [
  { title: '#', key: 'shotNo', width: 44, render: r => String(r.shotNo ?? '-') },
  { title: '起止(s)', key: 'span', width: 100, render: r => `${fmtSec(r.startSec)}-${fmtSec(r.endSec)}` },
  { title: '景别', key: 'shotSize', width: 70, render: r => String(r.shotSize ?? '') },
  { title: '运镜', key: 'cameraMove', width: 70, render: r => String(r.cameraMove ?? '') },
  { title: '画面描述', key: 'description', ellipsis: { tooltip: true }, render: r => String(r.description ?? '') },
  { title: '台词', key: 'dialogue', ellipsis: { tooltip: true }, render: r => String(r.dialogue ?? '') }
]

const changeLogColumns: DataTableColumns<Record<string, unknown>> = [
  { title: '原元素', key: 'from', render: r => String(r.from ?? '') },
  { title: '替换为', key: 'to', render: r => String(r.to ?? '') },
  { title: '位置', key: 'scene', width: 140, render: r => String(r.scene ?? '') }
]

function fmtSec(v: unknown): string {
  return typeof v === 'number' ? v.toFixed(1) : '?'
}

async function copyText(text: string, okMsg: string) {
  try {
    await navigator.clipboard.writeText(text)
    message.success(okMsg)
  } catch {
    message.error('复制失败（浏览器未授权剪贴板）')
  }
}

/** 切到「历史任务」来源时懒加载任务列表（一次）。 */
watch(sourceKind, (k) => {
  if (k === 'task' && !tasksLoaded) {
    tasksLoaded = true
    void loadTasks()
  }
})

/** 组件卸载释放帧预览 objectURL。 */
onUnmounted(() => {
  Object.values(frameUrls.value).forEach(u => URL.revokeObjectURL(u))
})
</script>

<style scoped lang="scss">
.video-reverse {
  display: flex;
  flex-direction: column;
  gap: 12px;

  &__card { width: 100%; }

  &__upload, &__taskpick { margin-top: 10px; }

  &__source-tag {
    margin-top: 6px;
    color: var(--color-text-secondary, #aaa);
    font-size: 12px;
  }

  &__hint {
    margin: 6px 0;
    color: var(--color-text-secondary, #aaa);
    font-size: 12px;
  }

  &__adv {
    display: flex;
    gap: 8px;
    margin-top: 8px;
  }

  &__meta {
    display: flex;
    align-items: center;
    gap: 8px;
    margin-bottom: 8px;
    font-size: 12px;
    color: var(--color-text-secondary, #aaa);
  }

  &__timeline {
    display: flex;
    gap: 8px;
    overflow-x: auto;
    padding-bottom: 6px;
    margin-bottom: 10px;
  }

  &__frame {
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 2px;
    flex: 0 0 auto;
  }

  &__frame-img {
    width: 96px;
    height: 54px;
    object-fit: cover;
    border-radius: 4px;
    cursor: zoom-in;
    border: 1px solid var(--color-border, #333);

    &--ph {
      display: flex;
      align-items: center;
      justify-content: center;
      background: var(--color-surface-2, #222);
      color: #666;
    }
  }

  &__frame-t { font-size: 11px; color: var(--color-text-secondary, #aaa); }

  &__section-title {
    display: flex;
    align-items: center;
    gap: 8px;
    margin: 10px 0 6px;
    font-weight: 600;

    &::before { content: ''; }
  }

  &__pre {
    max-height: 320px;
    overflow: auto;
    padding: 10px;
    border-radius: 6px;
    background: var(--color-surface-2, #1a1a1a);
    font-size: 12px;
    line-height: 1.5;
    white-space: pre-wrap;
    word-break: break-all;
  }
}
</style>
