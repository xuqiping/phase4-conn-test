<template>
  <div class="video-gen">
    <div class="video-gen__header">
      <h2>视频生成</h2>
      <span class="video-gen__sub">文生视频 / 图+视频+音频 多模态参考生视频</span>
    </div>

    <!-- 无权限：gated 前端落地（菜单已隐藏入口，此处兜底直访 URL 场景） -->
    <n-empty
      v-if="!canGen"
      description="无 media:gen 权限，请联系管理员授权"
      class="video-gen__forbidden"
    />

    <n-empty
      v-else-if="modelsLoaded && models.length === 0"
      description="暂无可用视频模型，请联系管理员在「全局模型供应商」配置 VIDEO 类供应商"
      class="video-gen__forbidden"
    />

    <div v-else class="video-gen__grid" :class="{ 'video-gen__grid--mobile': isMobile }">
      <!-- 左：生成表单 -->
      <n-card class="video-gen__form" title="生成参数" size="small">
        <n-form label-placement="top">
          <n-form-item label="视频模型">
            <n-select
              v-model:value="form.model"
              :options="modelOptions"
              :loading="!modelsLoaded"
              placeholder="选择视频生成模型"
              @update:value="onModelChange"
            />
          </n-form-item>

          <n-form-item label="提示词">
            <n-input
              v-model:value="form.prompt"
              type="textarea"
              :rows="4"
              :maxlength="8000"
              show-count
              :placeholder="hasAnyAttachment
                ? '描述如何运用参考素材，如：以图1为产品参考，视频1为运镜参考，音频1作背景音乐…'
                : '描述你要生成的视频内容，如：一只橘猫在窗台上晒太阳，阳光柔和'"
            />
          </n-form-item>

          <!-- 多模态参考附件（按模型能力动态渲染；不上传即文生视频） -->
          <template v-if="capability">
            <n-form-item v-if="capability.maxImages > 0">
              <template #label>
                参考图
                <span class="video-gen__hint">（{{ images.length }}/{{ capability.maxImages }}，≤8MB/张）</span>
              </template>
              <n-upload
                v-model:file-list="imageFileList"
                :max="Math.max(0, capability.maxImages - assetImages.length)"
                accept="image/*"
                list-type="image-card"
                :custom-request="(o: UploadCustomRequestOptions) => handleUpload(o, 'image')"
                @remove="(o) => onAttachmentRemove(o, 'image')"
              />
              <div class="video-gen__asset-row">
                <n-button quaternary size="small" :disabled="imageSlotsLeft <= 0" @click="openAssetPicker('image')">
                  从资产库选
                </n-button>
              </div>
              <div v-if="assetImages.length" class="video-gen__asset-previews">
                <div v-for="a in assetImages" :key="a.id" class="video-gen__asset-tile">
                  <img v-if="a.url" :src="a.url" :alt="a.name" class="video-gen__asset-media" />
                  <span v-else class="video-gen__asset-media video-gen__asset-media--placeholder">{{ a.name }}</span>
                  <n-button class="video-gen__asset-del" size="tiny" quaternary circle @click="onAssetChipRemove(a.id, 'image')">×</n-button>
                  <span class="video-gen__asset-name">{{ a.name }}</span>
                </div>
              </div>
            </n-form-item>

            <n-form-item v-if="capability.maxVideos > 0 && capability.videoDataUri">
              <template #label>
                参考视频
                <span class="video-gen__hint">（{{ videos.length }}/{{ capability.maxVideos }}，≤50MB/个，运镜/动作参考）</span>
              </template>
              <n-upload
                v-model:file-list="videoFileList"
                :max="Math.max(0, capability.maxVideos - assetVideos.length)"
                accept="video/*"
                :custom-request="(o: UploadCustomRequestOptions) => handleUpload(o, 'video')"
                @remove="(o) => onAttachmentRemove(o, 'video')"
              >
                <n-button size="small">上传视频</n-button>
              </n-upload>
              <div class="video-gen__asset-row">
                <n-button quaternary size="small" :disabled="videoSlotsLeft <= 0" @click="openAssetPicker('video')">
                  从资产库选
                </n-button>
              </div>
              <div v-if="assetVideos.length" class="video-gen__asset-previews">
                <div v-for="a in assetVideos" :key="a.id" class="video-gen__asset-tile">
                  <video v-if="a.url" :src="a.url" class="video-gen__asset-media" muted />
                  <span v-else class="video-gen__asset-media video-gen__asset-media--placeholder">{{ a.name }}</span>
                  <n-button class="video-gen__asset-del" size="tiny" quaternary circle @click="onAssetChipRemove(a.id, 'video')">×</n-button>
                  <span class="video-gen__asset-name">{{ a.name }}</span>
                </div>
              </div>
            </n-form-item>

            <n-form-item v-if="capability.maxAudios > 0">
              <template #label>
                参考音频
                <span class="video-gen__hint">（{{ audios.length }}/{{ capability.maxAudios }}，≤15MB/个，音色/BGM 参考）</span>
              </template>
              <n-upload
                v-model:file-list="audioFileList"
                :max="Math.max(0, capability.maxAudios - assetAudios.length)"
                accept="audio/*"
                :custom-request="(o: UploadCustomRequestOptions) => handleUpload(o, 'audio')"
                @remove="(o) => onAttachmentRemove(o, 'audio')"
              >
                <n-button size="small">上传音频</n-button>
              </n-upload>
              <div class="video-gen__asset-row">
                <n-button quaternary size="small" :disabled="audioSlotsLeft <= 0" @click="openAssetPicker('audio')">
                  从资产库选
                </n-button>
              </div>
              <div v-if="assetAudios.length" class="video-gen__asset-previews video-gen__asset-previews--col">
                <div v-for="a in assetAudios" :key="a.id" class="video-gen__asset-audio-row">
                  <audio v-if="a.url" :src="a.url" controls />
                  <span v-else class="video-gen__asset-name">{{ a.name }}</span>
                  <n-button size="tiny" quaternary circle @click="onAssetChipRemove(a.id, 'audio')">×</n-button>
                  <span class="video-gen__asset-name video-gen__asset-name--inline">{{ a.name }}</span>
                </div>
              </div>
            </n-form-item>

            <n-form-item v-if="capability.maxAttachments > 0">
              <span class="video-gen__hint">
                附件总计 {{ totalAttachments }}/{{ capability.maxAttachments }}
                （提示词里按「图1/图2…、视频1…、音频1…」顺序引用素材）
              </span>
            </n-form-item>
          </template>

          <n-form-item label="画面比例">
            <n-select
              v-model:value="form.ratio"
              :options="ratioOptions"
            />
          </n-form-item>

          <n-form-item label="时长（秒）">
            <n-select
              v-model:value="form.duration"
              :options="durationOptions"
            />
          </n-form-item>

          <n-form-item label="分辨率">
            <n-select
              v-model:value="form.resolution"
              :options="resolutionOptions"
            />
          </n-form-item>

          <n-form-item label="水印">
            <n-space align="center">
              <n-switch v-model:value="form.watermark" />
              <span class="video-gen__hint">开启后视频带官方水印</span>
            </n-space>
          </n-form-item>

          <n-form-item v-if="capability?.supportsGenerateAudio" label="生成音频">
            <n-space align="center">
              <n-switch v-model:value="form.generateAudio" />
              <span class="video-gen__hint">同步生成原生音频（2.0 特色）</span>
            </n-space>
          </n-form-item>

          <n-space>
            <n-button
              type="primary"
              :loading="submitting"
              :disabled="!canSubmit"
              @click="onSubmit"
            >
              提交生成
            </n-button>
            <span v-if="uploadingCount > 0" class="video-gen__hint">
              附件上传中（{{ uploadingCount }}）…
            </span>
          </n-space>
        </n-form>
      </n-card>

      <!-- 右：活动任务 + 历史 -->
      <div class="video-gen__result">
        <!-- 活动任务 -->
        <n-card class="video-gen__active" size="small">
          <template #header>
            <n-space align="center" size="small">
              <span>当前任务</span>
              <n-tag
                v-if="activeTask"
                size="small"
                :type="MEDIA_STATUS_TYPE[activeTask.status]"
                :bordered="false"
              >
                {{ MEDIA_STATUS_LABEL[activeTask.status] }}
              </n-tag>
              <n-tag v-if="activeTask?.statusFlag" size="small" type="warning" :bordered="false">
                用量估算
              </n-tag>
            </n-space>
          </template>

          <div v-if="!activeTask" class="video-gen__placeholder">
            提交生成后在此查看结果
          </div>

          <template v-else>
            <!-- 生成中 -->
            <div v-if="activeTask.status === 'PENDING' || activeTask.status === 'RUNNING'" class="video-gen__loading">
              <n-spin size="large" />
              <p>{{ MEDIA_STATUS_LABEL[activeTask.status] }}…通常需 1-3 分钟，请勿离开本页</p>
            </div>

            <!-- 完成：播放 + 下载 -->
            <div v-else-if="activeTask.status === 'SUCCEEDED'" class="video-gen__player">
              <video
                v-if="videoObjectUrl"
                :src="videoObjectUrl"
                controls
                playsinline
                class="video-gen__video"
              />
              <n-button v-if="videoObjectUrl" size="small" tag="a" :href="videoObjectUrl" download @click.stop>
                下载视频
              </n-button>
              <div v-if="activeTask.tokensCost" class="video-gen__usage">
                用量：{{ activeTask.tokensCost.toLocaleString() }} tokens
              </div>
            </div>

            <!-- 失败 -->
            <div v-else class="video-gen__error">
              <p>{{ MEDIA_STATUS_LABEL[activeTask.status] }}</p>
              <p v-if="activeTask.errorMsg" class="video-gen__error-msg">{{ activeTask.errorMsg }}</p>
            </div>

            <div class="video-gen__prompt-preview">
              {{ activeTask.prompt }}
              <span class="video-gen__meta">
                {{ activeTask.model || '-' }} · {{ activeTask.ratio || '-' }} · {{ activeTask.duration }}s · {{ activeTask.resolution }}
              </span>
            </div>
          </template>
        </n-card>

        <!-- 历史列表 -->
        <n-card class="video-gen__history" title="历史任务" size="small">
          <n-data-table
            :columns="historyColumns"
            :data="history"
            :loading="loadingHistory"
            size="small"
            :pagination="{ pageSize: 8 }"
            :max-height="320"
            striped
          />
        </n-card>
      </div>
    </div>

    <AssetFilePicker
      :show="showAssetPicker"
      :media-type="assetPickerKind ? ASSET_MEDIATYPE[assetPickerKind] : MEDIA_TYPE.IMAGE"
      :max="assetPickerMax"
      :exclude-asset-ids="assetPickerExcludeIds"
      @update:show="showAssetPicker = $event"
      @picked="onAssetPicked"
    />
  </div>
</template>

<script setup lang="ts">
import { h, computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import {
  NButton, NCard, NDataTable, NEmpty, NForm, NFormItem, NInput,
  NSelect, NSpace, NSpin, NSwitch, NTag, NUpload,
  useMessage
} from 'naive-ui'
import type { DataTableColumns, SelectGroupOption, SelectOption, UploadCustomRequestOptions, UploadFileInfo } from 'naive-ui'
import { useAuthStore } from '@/stores/auth'
import { useBreakpoints } from '@/composables/useBreakpoints'
import {
  mediaApi, fetchVideoBlob,
  MEDIA_STATUS_LABEL, MEDIA_STATUS_TYPE, isTerminal,
  type MediaTaskVO, type MediaResolution, type MediaRatio,
  type MediaModelVO, type AttachmentKind, type AttachmentRef
} from '@/api/media'
import AssetFilePicker from '@/components/asset/AssetFilePicker.vue'
import { MEDIA_TYPE } from '@/types/asset'
import type { AssetFilePicked } from '@/types/asset'

const authStore = useAuthStore()
const message = useMessage()
const { isMobile } = useBreakpoints()

/** 4 层权限显隐①：菜单入口；②此处页内提交（canGen）；③后端 @RequirePermission 403 兜底；④路由 meta 仅 requiresAuth。 */
const canGen = authStore.hasPermission('media:gen')

// === 模型目录（模型驱动动态表单：能力画像决定上传区/选项/开关） ===
const models = ref<MediaModelVO[]>([])
const modelsLoaded = ref(false)

const form = reactive({
  model: '' as string,
  prompt: '',
  ratio: '16:9' as MediaRatio,
  duration: 5,
  resolution: '720p' as MediaResolution,
  watermark: false,
  generateAudio: false
})

/** 当前选中模型的能力画像 */
const capability = computed<MediaModelVO | null>(
  () => models.value.find(m => m.modelId === form.model) ?? null
)

/** 模型下拉（按 providerName 分组，照抄 chat ModelSelector 分组模式） */
const modelOptions = computed<(SelectOption | SelectGroupOption)[]>(() => {
  const groups = new Map<string, SelectOption[]>()
  for (const m of models.value) {
    const list = groups.get(m.providerName) ?? []
    list.push({ label: m.displayName, value: m.modelId })
    groups.set(m.providerName, list)
  }
  if (groups.size === 1) {
    return [...groups.values()][0]
  }
  return [...groups.entries()].map(([provider, children]) => ({
    type: 'group' as const, label: provider, key: provider, children
  }))
})

async function loadModels() {
  try {
    const { data } = await mediaApi.listModels()
    models.value = data.data
    if (models.value.length > 0) {
      form.model = models.value[0].modelId
      applyCapabilityConstraints()
    }
  } catch {
    /* 拦截器已提示 */
  } finally {
    modelsLoaded.value = true
  }
}

/** 切换模型：能力可能变化 → 清空附件 + 收敛参数到新能力区间。 */
function onModelChange() {
  images.value = []
  videos.value = []
  audios.value = []
  applyCapabilityConstraints()
}

/** 把 ratio/duration/resolution 收敛到当前模型能力范围内（越界则回退默认）。 */
function applyCapabilityConstraints() {
  const cap = capability.value
  if (!cap) return
  if (!cap.supportedRatios.includes(form.ratio)) form.ratio = '16:9'
  if (!cap.supportedResolutions.includes(form.resolution)) form.resolution = '720p'
  if (form.duration < cap.minDuration || form.duration > cap.maxDuration) {
    form.duration = Math.min(5, cap.maxDuration)
  }
  if (!cap.supportsGenerateAudio) form.generateAudio = false
}

// === 选项（按能力过滤） ===
const RATIO_LABELS: Record<string, string> = {
  '16:9': '16:9 横屏（推荐）', '9:16': '9:16 竖屏', '1:1': '1:1 方形',
  '4:3': '4:3', '3:4': '3:4', '21:9': '21:9 超宽', 'adaptive': 'adaptive（沿用参考素材比例）'
}
const RES_LABELS: Record<string, string> = {
  '480p': '480p（省额度）', '720p': '720p（推荐）', '1080p': '1080p（高清）', '4K': '4K（超高清，2.0 全版）'
}

const ratioOptions = computed(() =>
  (capability.value?.supportedRatios ?? []).map(v => ({ label: RATIO_LABELS[v] ?? v, value: v }))
)
const resolutionOptions = computed(() =>
  (capability.value?.supportedResolutions ?? []).map(v => ({ label: RES_LABELS[v] ?? v, value: v }))
)
const durationOptions = computed(() => {
  const cap = capability.value
  const min = cap?.minDuration ?? 4
  const max = cap?.maxDuration ?? 15
  return Array.from({ length: Math.max(0, max - min + 1) }, (_, i) => ({
    label: `${min + i} 秒`, value: min + i
  }))
})

// === 多模态参考附件（复用 /api/files/upload 单一咽喉点） ===
// F1 修复：n-upload 受控化（v-model:file-list），显示与提交载荷同源；
// 关联键用 UploadFileInfo.id（上传期唯一），不用文件名（同名会错位）。
interface UploadedAttachment { id: string; fileId: string; name: string; assetId?: number; url?: string }
const images = ref<UploadedAttachment[]>([])
const videos = ref<UploadedAttachment[]>([])
const audios = ref<UploadedAttachment[]>([])
const imageFileList = ref<UploadFileInfo[]>([])
const videoFileList = ref<UploadFileInfo[]>([])
const audioFileList = ref<UploadFileInfo[]>([])
const uploadingCount = ref(0)

/** 客户端预检上限（与后端 MediaStorageService 一致；base64 前原始大小） */
const KIND_MAX_BYTES: Record<AttachmentKind, number> = {
  image: 8 * 1024 * 1024,
  video: 50 * 1024 * 1024,
  audio: 15 * 1024 * 1024
}
const KIND_LABEL: Record<AttachmentKind, string> = { image: '参考图', video: '参考视频', audio: '参考音频' }

const totalAttachments = computed(() => images.value.length + videos.value.length + audios.value.length)
const hasAnyAttachment = computed(() => totalAttachments.value > 0)

function kindList(kind: AttachmentKind) {
  return kind === 'image' ? images : kind === 'video' ? videos : audios
}

/** 附件上传：类型/大小预检 → /api/files/upload 拿 fileId。 */
async function handleUpload({ file, onFinish, onError }: UploadCustomRequestOptions, kind: AttachmentKind) {
  const raw = file.file as File | null
  if (!raw) {
    onError()
    return
  }
  if (raw.size > KIND_MAX_BYTES[kind]) {
    message.error(`${KIND_LABEL[kind]}过大（>${KIND_MAX_BYTES[kind] / 1024 / 1024}MB）：${raw.name}`)
    onError()
    return
  }
  uploadingCount.value++
  try {
    const { data } = await mediaApi.uploadAttachment(raw)
    kindList(kind).value.push({ id: file.id, fileId: data.data.fileId, name: raw.name })
    onFinish()
  } catch {
    onError()
    message.error(`${KIND_LABEL[kind]}上传失败`)
  } finally {
    uploadingCount.value--
  }
}

/** n-upload remove → 按 UploadFileInfo.id 移除对应 fileId（同名文件不错位）。 */
function onAttachmentRemove({ file }: { file: { id: string } }, kind: AttachmentKind) {
  const list = kindList(kind)
  const idx = list.value.findIndex(a => a.id === file.id)
  if (idx >= 0) list.value.splice(idx, 1)
  return true
}

// === 资产库选取（图/视频/音频 复用项目资产，免去重复上传） ===
// 单个 picker 实例复用三类：mediaType/max/exclude 随 assetPickerKind 动态切换。
const showAssetPicker = ref(false)
const assetPickerKind = ref<AttachmentKind | null>(null)
const ASSET_MEDIATYPE: Record<AttachmentKind, string> = {
  image: MEDIA_TYPE.IMAGE, video: MEDIA_TYPE.VIDEO, audio: MEDIA_TYPE.AUDIO
}
const assetImages = computed(() => images.value.filter(a => a.assetId != null))
const assetVideos = computed(() => videos.value.filter(a => a.assetId != null))
const assetAudios = computed(() => audios.value.filter(a => a.assetId != null))
const imageSlotsLeft = computed(() => (capability.value?.maxImages ?? 0) - images.value.length)
const videoSlotsLeft = computed(() => (capability.value?.maxVideos ?? 0) - videos.value.length)
const audioSlotsLeft = computed(() => (capability.value?.maxAudios ?? 0) - audios.value.length)

function openAssetPicker(kind: AttachmentKind) {
  assetPickerKind.value = kind
  showAssetPicker.value = true
}

/** picker 剩余可选槽位 = 模型能力上限 - 当前已选（负数兜底 0）。 */
const assetPickerMax = computed(() => {
  const k = assetPickerKind.value
  const cap = capability.value
  if (!k || !cap) return 0
  if (k === 'image') return Math.max(0, cap.maxImages - images.value.length)
  if (k === 'video') return Math.max(0, cap.maxVideos - videos.value.length)
  return Math.max(0, cap.maxAudios - audios.value.length)
})

/** 已添加的同类资产 id（picker 内去重置灰）。 */
const assetPickerExcludeIds = computed<number[]>(() => {
  const k = assetPickerKind.value
  if (!k) return []
  return kindList(k).value.map(a => a.assetId).filter((x): x is number => x != null)
})

/** picker 确认：逐项 push 进对应 kindList（与上传项同源，submit 透明）。 */
function onAssetPicked(payload: AssetFilePicked[]) {
  const k = assetPickerKind.value
  if (!k) return
  const list = kindList(k)
  for (const p of payload) {
    if (list.value.some(a => a.assetId === p.assetId)) continue
    list.value.push({ id: crypto.randomUUID(), fileId: p.fileId, name: p.name, assetId: p.assetId, url: p.url })
  }
}

/** 资产 chip × 移除（与 n-upload 上传项状态隔离，按 id 摘 kindList）。 */
function onAssetChipRemove(id: string, kind: AttachmentKind) {
  const list = kindList(kind)
  const idx = list.value.findIndex(a => a.id === id)
  if (idx >= 0) list.value.splice(idx, 1)
}

// === 提交 ===
const submitting = ref(false)
/** 提示词非空 + 无附件上传中 + 附件总数未超模型上限 */
const canSubmit = computed(
  () => form.prompt.trim().length > 0
    && uploadingCount.value === 0
    && !!form.model
    && totalAttachments.value <= (capability.value?.maxAttachments ?? 0)
)

async function onSubmit() {
  const attachments: AttachmentRef[] = [
    ...images.value.map(a => ({ fileId: a.fileId, kind: 'image' as const })),
    ...videos.value.map(a => ({ fileId: a.fileId, kind: 'video' as const })),
    ...audios.value.map(a => ({ fileId: a.fileId, kind: 'audio' as const }))
  ]
  submitting.value = true
  try {
    const { data } = await mediaApi.submitVideo({
      prompt: form.prompt.trim(),
      ratio: form.ratio,
      duration: form.duration,
      resolution: form.resolution,
      watermark: form.watermark,
      generateAudio: form.generateAudio,
      model: form.model,
      attachments: attachments.length > 0 ? attachments : undefined
    })
    message.success('任务已提交，正在生成…')
    // 启动轮询
    startPolling(data.data.id)
    // 刷新历史
    void loadHistory()
  } catch {
    /* 拦截器已提示 */
  } finally {
    submitting.value = false
  }
}

// === 轮询 + 视频播放 ===
const activeTask = ref<MediaTaskVO | null>(null)
const videoObjectUrl = ref<string | null>(null)
const loadingVideo = ref(false)
let pollTimer: ReturnType<typeof setInterval> | null = null

function clearPolling() {
  if (pollTimer !== null) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

function revokeVideo() {
  if (videoObjectUrl.value) {
    URL.revokeObjectURL(videoObjectUrl.value)
    videoObjectUrl.value = null
  }
}

/** 加载活动任务视频（SUCCEEDED 时）。 */
async function ensureVideo(task: MediaTaskVO) {
  if (!task.videoUrl) return
  revokeVideo()
  loadingVideo.value = true
  try {
    videoObjectUrl.value = await fetchVideoBlob(task.videoUrl)
  } catch {
    message.error('视频加载失败')
  } finally {
    loadingVideo.value = false
  }
}

/** 设置活动任务（含切换视频释放）。 */
function setActiveTask(task: MediaTaskVO) {
  activeTask.value = task
  revokeVideo()
  if (task.status === 'SUCCEEDED' && task.videoUrl) {
    void ensureVideo(task)
  }
}

async function pollOnce(taskId: number) {
  try {
    const { data } = await mediaApi.getTask(taskId)
    activeTask.value = data.data
    if (data.data.status === 'SUCCEEDED' && data.data.videoUrl && !videoObjectUrl.value) {
      void ensureVideo(data.data)
    }
    if (isTerminal(data.data.status)) {
      clearPolling()
      void loadHistory()
    }
  } catch {
    /* 网络错误拦截器处理（轮询风暴熔断见 request.ts） */
  }
}

function startPolling(taskId: number) {
  clearPolling()
  // 先取一次建活动任务，再 3s 间隔轮询
  void pollOnce(taskId)
  pollTimer = setInterval(() => void pollOnce(taskId), 3000)
}

// === 历史 ===
const history = ref<MediaTaskVO[]>([])
const loadingHistory = ref(false)

async function loadHistory() {
  loadingHistory.value = true
  try {
    const { data } = await mediaApi.listTasks(50)
    history.value = data.data
  } catch {
    /* 拦截器提示 */
  } finally {
    loadingHistory.value = false
  }
}

const historyColumns: DataTableColumns<MediaTaskVO> = [
  { title: 'ID', key: 'id', width: 60 },
  {
    title: '提示词', key: 'prompt', ellipsis: { tooltip: true },
    render: r => r.prompt || '-'
  },
  {
    title: '模型', key: 'model', width: 150, ellipsis: { tooltip: true },
    render: r => r.model || '-'
  },
  {
    title: '状态', key: 'status', width: 90,
    render: r => h(NTag, { size: 'small', type: MEDIA_STATUS_TYPE[r.status], bordered: false },
      () => MEDIA_STATUS_LABEL[r.status])
  },
  { title: '时长', key: 'duration', width: 60, render: r => r.duration ? `${r.duration}s` : '-' },
  { title: '分辨率', key: 'resolution', width: 80, render: r => r.resolution || '-' },
  {
    title: '创建时间', key: 'createdAt', width: 150,
    render: r => new Date(r.createdAt).toLocaleString('zh-CN')
  },
  {
    title: '操作', key: 'actions', width: 90,
    render: r => h(NButton, {
      size: 'small', quaternary: true,
      onClick: () => setActiveTask(r)
    }, () => '查看')
  }
]

onMounted(() => {
  void loadModels()
  void loadHistory()
})

onUnmounted(() => {
  clearPolling()
  revokeVideo()
})
</script>

<style lang="scss" scoped>
.video-gen {
  padding: var(--spacing-6);
  height: 100%;
  overflow-y: auto;

  &__header {
    display: flex;
    align-items: baseline;
    gap: var(--spacing-3);
    margin-bottom: var(--spacing-4);

    h2 {
      margin: 0;
      font-size: 20px;
      color: var(--color-text-primary);
    }
  }

  &__sub {
    font-size: 13px;
    color: var(--color-text-secondary);
  }

  &__forbidden {
    padding: var(--spacing-8) 0;
  }

  &__grid {
    display: grid;
    grid-template-columns: 380px 1fr;
    gap: var(--spacing-4);
    align-items: start;

    &--mobile {
      grid-template-columns: 1fr;
    }
  }

  &__result {
    display: flex;
    flex-direction: column;
    gap: var(--spacing-4);
  }

  &__placeholder,
  &__loading {
    padding: var(--spacing-6);
    text-align: center;
    color: var(--color-text-secondary);
    p {
      margin: var(--spacing-3) 0 0;
      font-size: 13px;
    }
  }

  &__video {
    width: 100%;
    max-height: 360px;
    background: #000;
    border-radius: var(--radius-base);
    margin-bottom: var(--spacing-2);
  }

  &__usage {
    margin-top: var(--spacing-2);
    font-size: 12px;
    color: var(--color-text-secondary);
  }

  &__error {
    color: var(--color-error, #d03050);
    p {
      margin: 0 0 var(--spacing-1);
    }
    &-msg {
      font-size: 13px;
      color: var(--color-text-secondary);
      word-break: break-all;
    }
  }

  &__prompt-preview {
    margin-top: var(--spacing-3);
    padding-top: var(--spacing-2);
    border-top: 1px solid var(--color-border-light);
    font-size: 13px;
    color: var(--color-text-secondary);
    line-height: 1.5;
  }

  &__meta {
    display: inline-block;
    margin-left: var(--spacing-2);
    color: var(--color-text-tertiary, var(--color-text-secondary));
  }

  &__hint {
    font-size: 12px;
    color: var(--color-text-secondary);
    line-height: 32px;
  }

  &__asset-row {
    display: flex;
    align-items: center;
    flex-wrap: wrap;
    gap: var(--spacing-1);
    margin-top: var(--spacing-2);
  }

  &__asset-previews {
    display: flex;
    flex-wrap: wrap;
    gap: var(--spacing-2);
    margin-top: var(--spacing-2);

    &--col {
      flex-direction: column;
      gap: var(--spacing-1);
    }
  }

  &__asset-tile {
    position: relative;
    width: 80px;
    display: flex;
    flex-direction: column;
    align-items: center;
  }

  &__asset-media {
    width: 80px;
    height: 80px;
    object-fit: cover;
    border-radius: var(--radius-base);
    border: 1px solid var(--color-border-light);
    background: #000;
    display: block;

    &--placeholder {
      display: flex;
      align-items: center;
      justify-content: center;
      padding: var(--spacing-1);
      font-size: 10px;
      color: var(--color-text-tertiary);
      text-align: center;
      line-height: 1.3;
      overflow: hidden;
      background: var(--color-bg-secondary, var(--color-border-light));
    }
  }

  &__asset-del {
    position: absolute;
    top: -6px;
    right: -6px;
    z-index: 1;
    width: 20px;
    height: 20px;
    min-width: 20px;
    padding: 0;
    line-height: 1;
  }

  &__asset-name {
    margin-top: 2px;
    font-size: 11px;
    color: var(--color-text-secondary);
    max-width: 80px;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    text-align: center;

    &--inline {
      max-width: 140px;
      text-align: left;
    }
  }

  &__asset-audio-row {
    display: flex;
    align-items: center;
    gap: var(--spacing-2);
    width: 100%;

    audio {
      height: 32px;
      max-width: 260px;
    }
  }
}

@media (max-width: 768px) {
  .video-gen {
    padding: var(--spacing-3);
  }
  .video-gen__header {
    flex-wrap: wrap;
  }
}
</style>
