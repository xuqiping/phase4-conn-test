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
            <n-alert v-if="restoredUnavailableModel" type="warning" :show-icon="false" class="video-gen__model-warning">
              历史模型 {{ restoredUnavailableModel }} 已下线，仅可回看参数，不能直接重新提交。
            </n-alert>
          </n-form-item>

          <n-form-item label="提示词">
            <MentionTextarea
              v-model="form.prompt"
              :candidates="attachmentCandidates"
              :broken-mentions="brokenAttachmentMentions"
              :kind-labels="ATTACH_KIND_LABELS"
              empty-hint="无附件可引用（先上传或从资产库选参考素材）"
              :maxlength="8000"
              :rows="4"
              :placeholder="hasAnyAttachment
                ? '描述如何运用参考素材；输入 @ 引用图1/视频1/音频1，如：以 @图1 为产品参考…'
                : '描述你要生成的视频内容，如：一只橘猫在窗台上晒太阳，阳光柔和（输入 @ 可引用参考附件）'"
              @mention-click="onAttachmentMentionClick"
            />
          </n-form-item>

          <!-- 多模态参考附件（按模型能力动态渲染；不上传即文生视频） -->
          <div ref="attachAreaRef"></div>
          <template v-if="capability">
            <!-- F2 首帧/尾帧独立槽位（可选，占参考图名额；SeedDance 2.0 role:first_frame/last_frame） -->
            <div v-if="capability.maxImages > 0" class="video-gen__frame-row">
              <div class="video-gen__frame-slot">
                <label>首帧（可选，图作开头）</label>
                <div class="video-gen__frame-tile">
                  <template v-if="firstFrame">
                    <img v-if="firstFrame.url" :src="firstFrame.url" class="video-gen__frame-media" :alt="firstFrame.name" />
                    <span v-else class="video-gen__frame-media video-gen__frame-media--ph">{{ firstFrame.name }}</span>
                    <n-button class="video-gen__tile-del" size="tiny" quaternary circle @click="removeFrame('first')">×</n-button>
                    <n-input :value="firstFrame.name" size="tiny" class="video-gen__tile-name"
                      @update:value="(v: string) => { if (firstFrame) firstFrame.name = v }" />
                  </template>
                  <template v-else>
                    <n-upload :show-file-list="false" accept="image/*"
                      :custom-request="(o: UploadCustomRequestOptions) => handleFrameUpload(o, 'first')">
                      <n-button size="tiny" :disabled="imageSlotsLeft <= 0 || referenceMediaCount > 0">上传</n-button>
                    </n-upload>
                    <n-button size="tiny" quaternary :disabled="imageSlotsLeft <= 0 || referenceMediaCount > 0" @click="openAssetPicker('first')">从资产库</n-button>
                  </template>
                </div>
              </div>
              <div class="video-gen__frame-slot">
                <label>尾帧（可选，图作结尾）</label>
                <div class="video-gen__frame-tile">
                  <template v-if="lastFrame">
                    <img v-if="lastFrame.url" :src="lastFrame.url" class="video-gen__frame-media" :alt="lastFrame.name" />
                    <span v-else class="video-gen__frame-media video-gen__frame-media--ph">{{ lastFrame.name }}</span>
                    <n-button class="video-gen__tile-del" size="tiny" quaternary circle @click="removeFrame('last')">×</n-button>
                    <n-input :value="lastFrame.name" size="tiny" class="video-gen__tile-name"
                      @update:value="(v: string) => { if (lastFrame) lastFrame.name = v }" />
                  </template>
                  <template v-else>
                    <n-upload :show-file-list="false" accept="image/*"
                      :custom-request="(o: UploadCustomRequestOptions) => handleFrameUpload(o, 'last')">
                      <n-button size="tiny" :disabled="imageSlotsLeft <= 0 || referenceMediaCount > 0">上传</n-button>
                    </n-upload>
                    <n-button size="tiny" quaternary :disabled="imageSlotsLeft <= 0 || referenceMediaCount > 0" @click="openAssetPicker('last')">从资产库</n-button>
                  </template>
                </div>
              </div>
            </div>
            <n-alert v-if="frameCount > 0" type="info" :show-icon="false" style="margin-bottom: 12px">
              当前为首尾帧模式：参考图、参考视频和参考音频已禁用。
            </n-alert>
            <n-alert v-else-if="referenceMediaCount > 0" type="info" :show-icon="false" style="margin-bottom: 12px">
              当前为参考媒体模式：首帧和尾帧已禁用。
            </n-alert>

            <!-- 参考图（F1 统一横排瓦片：上传+资产库同源，序号图N，名称可改） -->
            <n-form-item v-if="capability.maxImages > 0">
              <template #label>
                参考图
                <span class="video-gen__hint">（{{ images.length }}/{{ frameCount > 0 ? 0 : capability.maxImages }}，≤8MB/张）</span>
              </template>
              <div class="video-gen__tiles">
                <div v-for="(a, i) in images" :key="a.id" class="video-gen__tile">
                  <span class="video-gen__tile-idx">图{{ i + 1 }}</span>
                  <img v-if="a.url" :src="a.url" :alt="a.name" class="video-gen__tile-media" />
                  <span v-else class="video-gen__tile-media video-gen__tile-media--ph">{{ a.name }}</span>
                  <n-button class="video-gen__tile-del" size="tiny" quaternary circle @click="removeAttachment(a.id, 'image')">×</n-button>
                  <n-input :value="a.name" size="tiny" class="video-gen__tile-name"
                    @update:value="(v: string) => { a.name = v }" />
                </div>
                <n-upload :show-file-list="false" accept="image/*" :disabled="referenceImageSlotsLeft <= 0"
                  :custom-request="(o: UploadCustomRequestOptions) => handleUpload(o, 'image')">
                  <div class="video-gen__tile-add" :class="{ 'is-disabled': referenceImageSlotsLeft <= 0 }">+</div>
                </n-upload>
              </div>
              <n-button quaternary size="small" :disabled="referenceImageSlotsLeft <= 0" @click="openAssetPicker('image')">从资产库选</n-button>
            </n-form-item>

            <!-- 参考视频（F1 统一瓦片） -->
            <n-form-item v-if="capability.maxVideos > 0">
              <template #label>
                参考视频
                <span class="video-gen__hint">（{{ videos.length }}/{{ capability.maxVideos }}，≤50MB/个，运镜/动作参考）</span>
              </template>
              <n-alert v-if="!referenceVideoUsable" type="warning" :show-icon="false" style="margin-bottom: 10px">
                当前环境未开放参考视频：需要配置 Ark 可访问的公网 HTTPS 地址和签名密钥后才能上传或从资产库选择。
              </n-alert>
              <div class="video-gen__tiles">
                <div v-for="(a, i) in videos" :key="a.id" class="video-gen__tile">
                  <span class="video-gen__tile-idx">视频{{ i + 1 }}</span>
                  <video v-if="a.url" :src="a.url" class="video-gen__tile-media" muted />
                  <span v-else class="video-gen__tile-media video-gen__tile-media--ph">{{ a.name }}</span>
                  <n-button class="video-gen__tile-del" size="tiny" quaternary circle @click="removeAttachment(a.id, 'video')">×</n-button>
                  <n-input :value="a.name" size="tiny" class="video-gen__tile-name"
                    @update:value="(v: string) => { a.name = v }" />
                </div>
                <n-upload :show-file-list="false" accept="video/*" :disabled="videoSlotsLeft <= 0"
                  :custom-request="(o: UploadCustomRequestOptions) => handleUpload(o, 'video')">
                  <div class="video-gen__tile-add" :class="{ 'is-disabled': videoSlotsLeft <= 0 }">上传视频</div>
                </n-upload>
              </div>
              <n-button quaternary size="small" :disabled="videoSlotsLeft <= 0" @click="openAssetPicker('video')">从资产库选</n-button>
            </n-form-item>

            <!-- 参考音频（F1 统一瓦片） -->
            <n-form-item v-if="capability.maxAudios > 0">
              <template #label>
                参考音频
                <span class="video-gen__hint">（{{ audios.length }}/{{ capability.maxAudios }}，≤15MB/个，音色/BGM 参考）</span>
              </template>
              <div class="video-gen__tiles video-gen__tiles--audio">
                <div v-for="(a, i) in audios" :key="a.id" class="video-gen__tile video-gen__tile--audio">
                  <span class="video-gen__tile-idx">音频{{ i + 1 }}</span>
                  <audio v-if="a.url" :src="a.url" controls class="video-gen__tile-audio" />
                  <span v-else class="video-gen__tile-name video-gen__tile-name--ph">{{ a.name }}</span>
                  <n-button class="video-gen__tile-del" size="tiny" quaternary circle @click="removeAttachment(a.id, 'audio')">×</n-button>
                  <n-input :value="a.name" size="tiny" class="video-gen__tile-name"
                    @update:value="(v: string) => { a.name = v }" />
                </div>
                <n-upload :show-file-list="false" accept="audio/*" :disabled="audioSlotsLeft <= 0"
                  :custom-request="(o: UploadCustomRequestOptions) => handleUpload(o, 'audio')">
                  <div class="video-gen__tile-add" :class="{ 'is-disabled': audioSlotsLeft <= 0 }">上传音频</div>
                </n-upload>
              </div>
              <n-button quaternary size="small" :disabled="audioSlotsLeft <= 0" @click="openAssetPicker('audio')">从资产库选</n-button>
            </n-form-item>

            <n-form-item v-if="capability.maxAttachments > 0">
              <span class="video-gen__hint">
                附件总计 {{ totalAttachments }}/{{ capability.maxAttachments }}
                （提示词里按「图1/图2…、视频1…、音频1…」顺序引用参考素材；首/尾帧不参与序号）
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
              <!-- 7x-4：明确标注是否有参考视频（供审查定价是否按「有参考」命中） -->
              <n-tag v-if="activeTask?.hasReference" size="small" type="info" :bordered="false">
                有参考视频
              </n-tag>
              <n-tag v-else-if="activeTask && activeTask.hasReference === false" size="small" :bordered="false" type="default">
                无参考
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
              <!-- 2x-1：下载/入库按钮只按任务成功门控——视频 blob 拉取失败时仍要能入库
                   （入库走后端 file_id 引用，不依赖前端 blob；下载在无 blob 时退回任务原始 URL） -->
              <div class="video-gen__player-actions">
                <n-button
                  v-if="videoObjectUrl"
                  size="small" tag="a" :href="videoObjectUrl" download @click.stop
                >
                  下载视频
                </n-button>
                <!-- 4x-2：成功后入库资产库 -->
                <n-button size="small" type="primary" secondary @click="openSaveToAsset()">
                  入库到资产库
                </n-button>
              </div>
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
            <div class="video-gen__request-actions">
              <MediaTaskRequestDetails
                :submitted-request="activeTask.submittedRequest"
                :provider-request-snapshot="activeTask.providerRequestSnapshot"
              />
            </div>
          </template>
        </n-card>

        <!-- 历史列表 -->
        <n-card class="video-gen__history" title="历史任务" size="small">
          <div class="video-gen__history-filters">
            <n-input
              v-model:value="historyQuery"
              clearable
              placeholder="筛选提示词"
              aria-label="筛选历史提示词"
            />
            <n-date-picker
              v-model:value="historyTimeRange"
              type="datetimerange"
              clearable
              :actions="['clear', 'confirm']"
              aria-label="筛选历史时间范围"
            />
            <n-button size="small" @click="clearHistoryFilters">清空筛选</n-button>
          </div>
          <n-data-table
            remote
            :columns="historyColumns"
            :data="history"
            :loading="loadingHistory"
            size="small"
            :scroll-x="1080"
            :pagination="historyPagination"
            :max-height="320"
            striped
          />
        </n-card>
      </div>
    </div>

    <AssetFilePicker
      :show="showAssetPicker"
      :media-type="pickerMediaType"
      :max="assetPickerMax"
      :exclude-asset-ids="assetPickerExcludeIds"
      @update:show="showAssetPicker = $event"
      @picked="onAssetPicked"
    />

    <!-- 4x-2：视频任务结果入库资产库 -->
    <SaveVideoToAssetDialog
      :show="saveDialog.show"
      :task-id="saveDialog.taskId"
      :default-name="saveDialog.defaultName"
      @update:show="saveDialog.show = $event"
      @imported="onVideoImported"
    />
  </div>
</template>

<script setup lang="ts">
import { h, computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import {
  NAlert, NButton, NCard, NDataTable, NDatePicker, NEmpty, NForm, NFormItem, NInput,
  NSelect, NSpace, NSpin, NSwitch, NTag, NUpload,
  useDialog, useMessage
} from 'naive-ui'
import type { DataTableColumns, SelectGroupOption, SelectOption, UploadCustomRequestOptions } from 'naive-ui'
import { useRouter } from 'vue-router'
import MentionTextarea from '@/components/canvas/MentionTextarea.vue'
import { useAuthStore } from '@/stores/auth'
import { useBreakpoints } from '@/composables/useBreakpoints'
import {
  mediaApi, fetchVideoBlob, buildHistoryQuery,
  MEDIA_STATUS_LABEL, MEDIA_STATUS_TYPE, isTerminal,
  type MediaTaskVO, type MediaResolution, type MediaRatio,
  type MediaModelVO, type AttachmentKind, type AttachmentRef
} from '@/api/media'
import AssetFilePicker from '@/components/asset/AssetFilePicker.vue'
import MediaTaskVideoPreview from '@/components/media/MediaTaskVideoPreview.vue'
import MediaTaskRequestDetails from '@/components/media/MediaTaskRequestDetails.vue'
import SaveVideoToAssetDialog from '@/components/media/SaveVideoToAssetDialog.vue'
import { MEDIA_TYPE } from '@/types/asset'
import type { AssetFilePicked } from '@/types/asset'
import type { MentionCandidate } from '@/types/canvas'
import { fetchFilePreview } from '@/api/file'
import { interpolateAttachmentPrompt } from '@/utils/attachmentMention'
import { bucketRestoredAttachments } from '@/utils/mediaTaskRestore'
import { canAddVideoAttachment, type VideoAttachmentTarget } from '@/utils/videoAttachmentMode'

const authStore = useAuthStore()
const message = useMessage()
const dialog = useDialog()
const router = useRouter()
const { isMobile } = useBreakpoints()

/** 4 层权限显隐①：菜单入口；②此处页内提交（canGen）；③后端 @RequirePermission 403 兜底；④路由 meta 仅 requiresAuth。 */
const canGen = authStore.hasPermission('media:gen')

// === 模型目录（模型驱动动态表单：能力画像决定上传区/选项/开关） ===
const models = ref<MediaModelVO[]>([])
const modelsLoaded = ref(false)
const restoredUnavailableModel = ref<string | null>(null)

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
  const options: (SelectOption | SelectGroupOption)[] = groups.size === 1
    ? [...groups.values()][0]
    : [...groups.entries()].map(([provider, children]) => ({
    type: 'group' as const, label: provider, key: provider, children
  }))
  if (restoredUnavailableModel.value) {
    options.unshift({
      label: `${restoredUnavailableModel.value}（已下线，仅回看）`,
      value: restoredUnavailableModel.value,
      disabled: true
    })
  }
  return options
})

async function loadModels() {
  try {
    const { data } = await mediaApi.listModels()
    models.value = data.data
    if (models.value.length > 0 && !form.model) {
      form.model = models.value[0].modelId
      applyCapabilityConstraints()
    }
  } catch {
    /* 拦截器已提示 */
  } finally {
    modelsLoaded.value = true
  }
}

/** 切换模型：能力可能变化 → 清空附件 + 收敛参数到新能力区间。释放资产预览 objectURL。 */
function onModelChange() {
  restoredUnavailableModel.value = null
  ;[images, videos, audios].forEach(l => l.value.forEach(revokeAttachmentUrl))
  revokeFrame(firstFrame.value)
  revokeFrame(lastFrame.value)
  images.value = []
  videos.value = []
  audios.value = []
  firstFrame.value = null
  lastFrame.value = null
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
interface UploadedAttachment { id: string; fileId: string; name: string; assetId?: number; url?: string; reusable?: boolean }
const images = ref<UploadedAttachment[]>([])
const videos = ref<UploadedAttachment[]>([])
const audios = ref<UploadedAttachment[]>([])
// F2 首帧/尾帧：各最多 1 张图（占参考图名额），SeedDance 2.0 role:first_frame/last_frame
const firstFrame = ref<UploadedAttachment | null>(null)
const lastFrame = ref<UploadedAttachment | null>(null)
/** A1：附件 @chip 点击 → 滚动到附件上传区（同页锚点，画布跳节点在此页无对应实体）。 */
const attachAreaRef = ref<HTMLElement | null>(null)
function onAttachmentMentionClick() {
  attachAreaRef.value?.scrollIntoView({ behavior: 'smooth', block: 'center' })
}
const uploadingCount = ref(0)

/** F2 首尾帧已占名额（0/1/2）—— 参考图可用槽 = maxImages - frameCount。 */
const frameCount = computed(() => (firstFrame.value ? 1 : 0) + (lastFrame.value ? 1 : 0))
const referenceMediaCount = computed(() => images.value.length + videos.value.length + audios.value.length)
const referenceVideoUsable = computed(() => Boolean(
  capability.value && capability.value.maxVideos > 0 && capability.value.referenceVideoEnabled
))

function modeAllows(target: VideoAttachmentTarget, notify = false) {
  const allowed = canAddVideoAttachment(target, {
    frameCount: frameCount.value,
    referenceMediaCount: referenceMediaCount.value
  })
  if (!allowed && notify) {
    message.warning(target === 'first' || target === 'last'
      ? '首帧/尾帧不能与参考图、参考视频或参考音频同时使用，请先清空参考媒体'
      : '参考媒体不能与首帧/尾帧同时使用，请先清空首帧和尾帧')
  }
  return allowed
}

/** 客户端预检上限（与后端 MediaStorageService 一致；base64 前原始大小） */
const KIND_MAX_BYTES: Record<AttachmentKind, number> = {
  image: 8 * 1024 * 1024,
  video: 50 * 1024 * 1024,
  audio: 15 * 1024 * 1024
}
const KIND_LABEL: Record<AttachmentKind, string> = { image: '参考图', video: '参考视频', audio: '参考音频' }

const totalAttachments = computed(() => images.value.length + videos.value.length + audios.value.length + frameCount.value)
const hasAnyAttachment = computed(() => totalAttachments.value > 0)

// === H（4.5）：提示词 @ 引用本会话附件（图1/视频1/音频1），对齐无限画布 @ 体验 ===
// chip/候选 kind→短标签（图/视频/音频）；提交时 @{{image:<id>}} 序号化为「图N」给 Ark。
const ATTACH_KIND_LABELS: Record<AttachmentKind, string> = { image: '图', video: '视频', audio: '音频' }

/**
 * @ 候选 = 本会话已添加附件（上传 + 资产库选）。
 * id 用附件稳定 uuid（重排/删除不断链）；label 用当前列表序号「图N/视频N/音频N」（重排后跟随）。
 */
const attachmentCandidates = computed<MentionCandidate[]>(() => {
  const out: MentionCandidate[] = []
  // chip 显附件真名（用户在瓦片改的名，如「抽帧(LAST)」），改名后 chip 实时同步；
  // 无名时回退 图N/视频N/音频N。提交时 interpolateAttachmentPrompt 仍按序号转「图N」送后端，与此无关。
  images.value.forEach((a, i) => out.push({ kind: 'image', id: a.id, label: a.name?.trim() || `图${i + 1}` }))
  videos.value.forEach((a, i) => out.push({ kind: 'video', id: a.id, label: a.name?.trim() || `视频${i + 1}` }))
  audios.value.forEach((a, i) => out.push({ kind: 'audio', id: a.id, label: a.name?.trim() || `音频${i + 1}` }))
  return out
})

/** 断链 @（引用了已被删除/不存在的附件）→ mirror 染黄提醒，与画布断链语义同源。 */
const brokenAttachmentMentions = computed<string[]>(() => {
  const present = new Set(attachmentCandidates.value.map(c => `@{{${c.kind}:${c.id}}}`))
  const out: string[] = []
  const re = /@\{\{(image|video|audio):([^}]+)\}\}/g
  let m: RegExpExecArray | null
  while ((m = re.exec(form.prompt)) !== null) {
    if (!present.has(m[0])) out.push(m[0])
  }
  return out
})

function kindList(kind: AttachmentKind) {
  return kind === 'image' ? images : kind === 'video' ? videos : audios
}

/** 附件上传：类型/大小预检 → /api/files/upload 拿 fileId。 */
async function handleUpload({ file, onFinish, onError }: UploadCustomRequestOptions, kind: AttachmentKind) {
  if (!modeAllows(kind, true)) {
    onError()
    return
  }
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

/** n-upload remove 已废弃（统一瓦片自定义渲染，remove 走 removeAttachment）。保留 kindList 供上传/picker 复用。 */

// === 资产库选取（图/视频/音频/首帧/尾帧 复用项目资产，免去重复上传） ===
// 单个 picker 实例复用多目标：mediaType/max/exclude 随 pickerTarget 动态切换。
const showAssetPicker = ref(false)
/** picker 目标：image/video/audio（参考素材，可多选）或 first/last（首尾帧，单选）。 */
type PickerTarget = AttachmentKind | 'first' | 'last'
const pickerTarget = ref<PickerTarget | null>(null)
const ASSET_MEDIATYPE: Record<AttachmentKind, string> = {
  image: MEDIA_TYPE.IMAGE, video: MEDIA_TYPE.VIDEO, audio: MEDIA_TYPE.AUDIO
}
/** 参考图可用槽 = maxImages - 已用参考图 - 首尾帧已占名额。 */
const imageSlotsLeft = computed(() => (capability.value?.maxImages ?? 0) - images.value.length - frameCount.value)
const referenceImageSlotsLeft = computed(() => frameCount.value > 0 ? 0 : imageSlotsLeft.value)
const videoSlotsLeft = computed(() => !referenceVideoUsable.value || frameCount.value > 0
  ? 0
  : (capability.value?.maxVideos ?? 0) - videos.value.length)
const audioSlotsLeft = computed(() => frameCount.value > 0 ? 0 : (capability.value?.maxAudios ?? 0) - audios.value.length)

function openAssetPicker(target: PickerTarget) {
  if (!modeAllows(target, true)) return
  pickerTarget.value = target
  showAssetPicker.value = true
}

/** picker media-type：first/last→图片；其余按 kind。 */
const pickerMediaType = computed(() => {
  const t = pickerTarget.value
  if (!t) return MEDIA_TYPE.IMAGE
  if (t === 'first' || t === 'last') return MEDIA_TYPE.IMAGE
  return ASSET_MEDIATYPE[t]
})

/** picker 剩余可选槽位 = 模型能力上限 - 当前已选（负数兜底 0）。首尾帧各按图片池算（减另一帧）。 */
const assetPickerMax = computed(() => {
  const t = pickerTarget.value
  const cap = capability.value
  if (!t || !cap) return 0
  if (t === 'image') return Math.max(0, cap.maxImages - images.value.length - frameCount.value)
  if (t === 'video') return Math.max(0, cap.maxVideos - videos.value.length)
  if (t === 'audio') return Math.max(0, cap.maxAudios - audios.value.length)
  // first/last：单选，剩余 = 图片池空槽（减另一帧已占）
  const otherFrame = (t === 'first' ? lastFrame.value : firstFrame.value) ? 1 : 0
  return Math.max(0, cap.maxImages - images.value.length - otherFrame)
})

/** 已添加的同类资产 id（picker 内去重置灰）；首尾帧的资产 id 也纳入图片去重。 */
const assetPickerExcludeIds = computed<number[]>(() => {
  const t = pickerTarget.value
  if (!t) return []
  const ids = images.value.map(a => a.assetId).filter((x): x is number => x != null)
  if (t === 'first' || t === 'image') {
    const f = firstFrame.value?.assetId; if (f != null) ids.push(f)
  }
  if (t === 'last' || t === 'image') {
    const l = lastFrame.value?.assetId; if (l != null) ids.push(l)
  }
  if (t === 'video') return videos.value.map(a => a.assetId).filter((x): x is number => x != null)
  if (t === 'audio') return audios.value.map(a => a.assetId).filter((x): x is number => x != null)
  return ids
})

/** picker 确认：first/last 单选填帧槽；image/video/audio 多选 push 进 kindList。
 *  G（4.4 资产图显示修复）：resolve 返的 url 是 `/api/files/{fileId}` 鉴权相对地址，
 *  `<img src>` 不带 JWT 拉不到 → 先无 url 占位（显文件名），异步用 fetchFilePreview
 *  拉 token blob 转 objectURL 填回（同 fetchVideoBlob / 卡片懒加载范式）。 */
function onAssetPicked(payload: AssetFilePicked[]) {
  const t = pickerTarget.value
  if (!t) return
  if (!modeAllows(t, true)) return
  // 首尾帧：单选（取首项），替换原帧槽
  if (t === 'first' || t === 'last') {
    const p = payload[0]
    if (!p) return
    const slot = t === 'first' ? firstFrame : lastFrame
    revokeFrame(slot.value)
    const id = crypto.randomUUID()
    slot.value = { id, fileId: p.fileId, name: p.name, assetId: p.assetId }
    void previewFrame(t, id, p.fileId)
    return
  }
  const list = kindList(t)
  for (const p of payload) {
    if (list.value.some(a => a.assetId === p.assetId)) continue
    const id = crypto.randomUUID()
    list.value.push({ id, fileId: p.fileId, name: p.name, assetId: p.assetId })
    // 异步拉预览：成功填 objectURL；期间已被移除则立即释放防泄漏
    void previewAsset(id, t, p.fileId)
  }
}

/** 拉取资产预览 objectURL 并回填对应附件；找不到（已删/已切）则释放刚创建的 URL。 */
async function previewAsset(id: string, kind: AttachmentKind, fileId: string) {
  try {
    const objectUrl = await fetchFilePreview(fileId)
    const list = kindList(kind)
    const idx = list.value.findIndex(a => a.id === id)
    if (idx >= 0) {
      list.value[idx] = { ...list.value[idx], url: objectUrl }
    } else {
      URL.revokeObjectURL(objectUrl)
    }
    return true
  } catch {
    /* 拉取失败保留文件名占位，不报错（无权限/已删走降级） */
    return false
  }
}

/** F2 拉取首/尾帧预览 objectURL 并回填；帧槽已被替换/清空则释放。 */
async function previewFrame(slot: 'first' | 'last', id: string, fileId: string) {
  try {
    const objectUrl = await fetchFilePreview(fileId)
    const cur = slot === 'first' ? firstFrame.value : lastFrame.value
    if (cur && cur.id === id) {
      if (slot === 'first') firstFrame.value = { ...cur, url: objectUrl }
      else lastFrame.value = { ...cur, url: objectUrl }
    } else {
      URL.revokeObjectURL(objectUrl)
    }
  } catch {
    /* 保留文件名占位 */
  }
}

/** 释放某附件的 objectURL（资产预览项专用；上传项无 url 不受影响）。 */
function revokeAttachmentUrl(att: UploadedAttachment | null) {
  if (att && att.url && att.assetId != null) URL.revokeObjectURL(att.url)
}

/** F2 释放首/尾帧 objectURL。 */
function revokeFrame(att: UploadedAttachment | null) {
  revokeAttachmentUrl(att)
}

/** F1 统一移除：按 id 摘 kindList（上传+资产同源），释放其 objectURL。 */
function removeAttachment(id: string, kind: AttachmentKind) {
  const list = kindList(kind)
  const idx = list.value.findIndex(a => a.id === id)
  if (idx >= 0) {
    revokeAttachmentUrl(list.value[idx])
    list.value.splice(idx, 1)
  }
}

/** F2 首/尾帧上传：类型/大小预检 → /api/files/upload → 填帧槽（单选，替换旧值）。 */
async function handleFrameUpload({ file, onFinish, onError }: UploadCustomRequestOptions, slot: 'first' | 'last') {
  if (!modeAllows(slot, true)) { onError(); return }
  const raw = file.file as File | null
  if (!raw) { onError(); return }
  if (raw.size > KIND_MAX_BYTES.image) {
    message.error(`首/尾帧图过大（>${KIND_MAX_BYTES.image / 1024 / 1024}MB）：${raw.name}`)
    onError(); return
  }
  uploadingCount.value++
  try {
    const { data } = await mediaApi.uploadAttachment(raw)
    const target = slot === 'first' ? firstFrame : lastFrame
    revokeFrame(target.value)
    target.value = { id: file.id, fileId: data.data.fileId, name: raw.name }
    onFinish()
  } catch {
    onError()
    message.error('首/尾帧上传失败')
  } finally {
    uploadingCount.value--
  }
}

/** F2 移除首/尾帧（清槽 + 释放 objectURL）。 */
function removeFrame(slot: 'first' | 'last') {
  const target = slot === 'first' ? firstFrame : lastFrame
  revokeFrame(target.value)
  target.value = null
}

// === 提交 ===
const submitting = ref(false)
/** 提示词非空 + 无附件上传中 + 附件总数未超模型上限 */
const canSubmit = computed(
  () => form.prompt.trim().length > 0
    && uploadingCount.value === 0
    && !!form.model
    && !restoredUnavailableModel.value
    && !(frameCount.value > 0 && referenceMediaCount.value > 0)
    && [...images.value, ...videos.value, ...audios.value, firstFrame.value, lastFrame.value]
      .filter((a): a is UploadedAttachment => a != null)
      .every(a => a.reusable !== false)
    && totalAttachments.value <= (capability.value?.maxAttachments ?? 0)
)

async function onSubmit() {
  // F2 首/尾帧作 image 附件带 frameRole（provider 路由 role:first_frame/last_frame）
  const attachments: AttachmentRef[] = []
  if (firstFrame.value) attachments.push({ fileId: firstFrame.value.fileId, kind: 'image', frameRole: 'first_frame', name: firstFrame.value.name })
  if (lastFrame.value) attachments.push({ fileId: lastFrame.value.fileId, kind: 'image', frameRole: 'last_frame', name: lastFrame.value.name })
  attachments.push(
    ...images.value.map(a => ({ fileId: a.fileId, kind: 'image' as const, name: a.name })),
    ...videos.value.map(a => ({ fileId: a.fileId, kind: 'video' as const, name: a.name })),
    ...audios.value.map(a => ({ fileId: a.fileId, kind: 'audio' as const, name: a.name }))
  )
  submitting.value = true
  try {
    const { data } = await mediaApi.submitVideo({
      prompt: interpolateAttachmentPrompt(form.prompt.trim(), images.value, videos.value, audios.value),
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

// === 历史（4x#2：remote 分页——翻页才向服务器要对应页，默认 10 可选 5/10/20/50） ===
const history = ref<MediaTaskVO[]>([])
const loadingHistory = ref(false)
const historyQuery = ref('')
const historyTimeRange = ref<[number, number] | null>(null)
const historyPage = ref(1)
const historyPageSize = ref(10)
const historyTotal = ref(0)
let historyDebounceTimer: ReturnType<typeof setTimeout> | null = null
let historyRequestSeq = 0

async function loadHistory() {
  const requestSeq = ++historyRequestSeq
  loadingHistory.value = true
  try {
    const { data } = await mediaApi.listTasks(buildHistoryQuery({
      q: historyQuery.value,
      range: historyTimeRange.value,
      kind: 'VIDEO', // 视频页只显视频任务（图片记录不混入，SQL 层过滤）
      rangeType: 'datetime', // datetimerange：to 用用户所选精确时刻
      page: historyPage.value,
      pageSize: historyPageSize.value
    }))
    if (requestSeq === historyRequestSeq) {
      history.value = data.data?.records ?? []
      historyTotal.value = data.data?.total ?? 0
    }
  } catch {
    /* 拦截器提示 */
  } finally {
    if (requestSeq === historyRequestSeq) loadingHistory.value = false
  }
}

/** n-data-table remote 分页配置：itemCount=服务端 total；切页/改条数即时触发拉取。 */
const historyPagination = computed(() => ({
  page: historyPage.value,
  pageSize: historyPageSize.value,
  itemCount: historyTotal.value,
  pageSizes: [5, 10, 20, 50],
  showSizePicker: true,
  onChange: (p: number) => {
    historyPage.value = p
    void loadHistory()
  },
  // L5：切条数后当前页可能越界（第5页×5条→50条），统一回落第 1 页
  onUpdatePageSize: (s: number) => {
    historyPage.value = 1
    historyPageSize.value = s
    void loadHistory()
  }
}))

async function openHistoryTask(summary: MediaTaskVO) {
  try {
    const { data } = await mediaApi.getTask(summary.id)
    const task = data.data
    setActiveTask(task)
    restoreTaskForm(task)
    if (!isTerminal(task.status)) startPolling(task.id)
  } catch {
    /* 拦截器提示 */
  }
}

/** 4x-2：视频入库资产库弹窗状态（成功播放区/历史行共用）。 */
const saveDialog = reactive({
  show: false,
  taskId: null as number | null,
  defaultName: ''
})

function openSaveToAsset(task?: MediaTaskVO) {
  const t = task ?? (activeTask.value && activeTask.value.status === 'SUCCEEDED' ? activeTask.value : null)
  if (!t) return
  saveDialog.taskId = t.id
  const prompt = (t.prompt ?? '').trim()
  saveDialog.defaultName = prompt ? prompt.slice(0, 30) : '视频产出'
  saveDialog.show = true
}

/** 入库成功 → 给出明确的「进资产库」入口（4x-2）。 */
function onVideoImported(payload: { assetId: number; name: string }) {
  dialog.success({
    title: '已入库资产库',
    content: `「${payload.name}」已存入目标项目。`,
    positiveText: '前往资产库',
    negativeText: '留在本页',
    onPositiveClick: () => {
      router.push('/assets')
    }
  })
}

function restoreTaskForm(task: MediaTaskVO) {
  ;[images, videos, audios].forEach(list => list.value.forEach(revokeAttachmentUrl))
  revokeFrame(firstFrame.value)
  revokeFrame(lastFrame.value)

  const availableModel = task.model ? models.value.some(m => m.modelId === task.model) : false
  restoredUnavailableModel.value = task.model && !availableModel ? task.model : null
  if (task.model) form.model = task.model
  form.prompt = task.prompt ?? ''
  form.ratio = (task.ratio ?? '16:9') as MediaRatio
  form.duration = task.duration ?? 5
  form.resolution = (task.resolution ?? '720p') as MediaResolution
  form.watermark = task.watermark ?? false
  form.generateAudio = task.generateAudio ?? false

  const restored = bucketRestoredAttachments(task.inputAttachments ?? [])
  firstFrame.value = restored.firstFrame
  lastFrame.value = restored.lastFrame
  images.value = restored.images
  videos.value = restored.videos
  audios.value = restored.audios
  ;[...images.value.map(a => [a, 'image'] as const),
    ...videos.value.map(a => [a, 'video'] as const),
    ...audios.value.map(a => [a, 'audio'] as const)]
    .forEach(([attachment, kind]) => void previewRestoredAttachment(attachment.id, kind, attachment.fileId))
  if (firstFrame.value) void previewRestoredFrame('first', firstFrame.value.id, firstFrame.value.fileId)
  if (lastFrame.value) void previewRestoredFrame('last', lastFrame.value.id, lastFrame.value.fileId)
}

async function previewRestoredAttachment(id: string, kind: AttachmentKind, fileId: string) {
  const ok = await previewAsset(id, kind, fileId)
  if (!ok) markRestoredUnavailable(id, kind)
}

async function previewRestoredFrame(slot: 'first' | 'last', id: string, fileId: string) {
  try {
    const objectUrl = await fetchFilePreview(fileId)
    const current = slot === 'first' ? firstFrame.value : lastFrame.value
    if (!current || current.id !== id) return URL.revokeObjectURL(objectUrl)
    const next = { ...current, url: objectUrl }
    if (slot === 'first') firstFrame.value = next
    else lastFrame.value = next
  } catch {
    const current = slot === 'first' ? firstFrame.value : lastFrame.value
    if (current?.id === id) current.reusable = false
  }
}

function markRestoredUnavailable(id: string, kind: AttachmentKind) {
  const item = kindList(kind).value.find(a => a.id === id)
  if (item) item.reusable = false
}

function scheduleHistoryLoad() {
  if (historyDebounceTimer !== null) clearTimeout(historyDebounceTimer)
  historyDebounceTimer = setTimeout(() => {
    historyDebounceTimer = null
    void loadHistory()
  }, 300)
}

function clearHistoryFilters() {
  historyQuery.value = ''
  historyTimeRange.value = null
}

// L1：筛选（含清空）变化重置第 1 页再防抖加载；翻页时筛选条件保持在 refs 不丢
watch([historyQuery, historyTimeRange], () => {
  historyPage.value = 1
  scheduleHistoryLoad()
})

const historyColumns: DataTableColumns<MediaTaskVO> = [
  { title: 'ID', key: 'id', width: 60, fixed: 'left' },
  {
    title: '提示词', key: 'prompt', width: 260, fixed: 'left', ellipsis: { tooltip: true },
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
    // 7x-4：参考视频标志列（null 兼容旧任务）
    title: '参考视频', key: 'hasReference', width: 90,
    render: r => r.hasReference === true
      ? h(NTag, { size: 'small', type: 'info', bordered: false }, () => '有')
      : (r.hasReference === false ? h('span', { class: 'video-gen__preview-placeholder' }, '无') : '-')
  },
  {
    title: '创建时间', key: 'createdAt', width: 150,
    render: r => new Date(r.createdAt).toLocaleString('zh-CN')
  },
  {
    title: '视频', key: 'videoPreview', width: 150,
    render: r => r.status === 'SUCCEEDED' && r.videoUrl
      ? h(MediaTaskVideoPreview, { downloadPath: r.videoUrl })
      : h('span', { class: 'video-gen__preview-placeholder' }, '-')
  },
  {
    title: '操作', key: 'actions', width: 140,
    render: r => h('div', { style: 'display:flex;gap:4px' }, [
      h(NButton, {
        size: 'small', quaternary: true,
        onClick: () => void openHistoryTask(r)
      }, () => '查看'),
      // 4x-2：历史成功任务一键入库资产库
      ...(r.status === 'SUCCEEDED' ? [h(NButton, {
        size: 'small', quaternary: true, type: 'primary',
        onClick: () => openSaveToAsset(r)
      }, () => '入库')] : [])
    ])
  }
]

onMounted(() => {
  void loadModels()
  void loadHistory()
})

onUnmounted(() => {
  if (historyDebounceTimer !== null) clearTimeout(historyDebounceTimer)
  clearPolling()
  revokeVideo()
  // 释放资产预览 objectURL（防内存泄漏）
  ;[images, videos, audios].forEach(l => l.value.forEach(revokeAttachmentUrl))
  revokeFrame(firstFrame.value)
  revokeFrame(lastFrame.value)
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

  &__player-actions {
    display: flex;
    gap: var(--spacing-2);
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

  // F2 首帧/尾帧独立槽位
  &__frame-row {
    display: flex;
    gap: var(--spacing-3);
    margin-bottom: var(--spacing-2);
  }

  &__frame-slot {
    display: flex;
    flex-direction: column;
    gap: var(--spacing-1);
    flex: 1;

    label {
      font-size: var(--font-size-xs);
      color: var(--color-text-secondary);
    }
  }

  &__frame-tile {
    display: flex;
    align-items: center;
    gap: var(--spacing-1);
    min-height: 36px;
    flex-wrap: wrap;
  }

  &__frame-media {
    width: 56px;
    height: 56px;
    object-fit: cover;
    border-radius: var(--radius-base);
    border: 1px solid var(--color-primary);
    background: #000;
    display: block;

    &--ph {
      display: flex;
      align-items: center;
      justify-content: center;
      padding: 2px;
      font-size: 10px;
      color: var(--color-text-tertiary);
      background: var(--color-bg-secondary, var(--color-border-light));
      border-color: var(--color-border-light);
    }
  }

  // F1 统一瓦片网格（上传+资产同源横排）
  &__tiles {
    display: flex;
    flex-wrap: wrap;
    gap: var(--spacing-2);
    margin-bottom: var(--spacing-1);

    &--audio {
      flex-direction: column;
      gap: var(--spacing-1);
    }
  }

  &__tile {
    position: relative;
    width: 80px;
    display: flex;
    flex-direction: column;
    align-items: center;

    &--audio {
      width: 100%;
      flex-direction: row;
      align-items: center;
      gap: var(--spacing-2);
    }
  }

  &__tile-idx {
    position: absolute;
    top: 2px;
    left: 2px;
    z-index: 1;
    font-size: 10px;
    color: #fff;
    background: rgba(var(--color-primary-rgb), 0.85);
    border-radius: var(--radius-small);
    padding: 0 4px;
    line-height: 1.4;
  }

  &__tile-media {
    width: 80px;
    height: 80px;
    object-fit: cover;
    border-radius: var(--radius-base);
    border: 1px solid var(--color-border-light);
    background: #000;
    display: block;

    &--ph {
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

  &__tile-audio {
    height: 32px;
    max-width: 260px;
  }

  &__tile-del {
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

  &__tile-name {
    margin-top: 2px;
    width: 80px;

    &--ph {
      font-size: 11px;
      color: var(--color-text-secondary);
      max-width: 140px;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
  }

  &__tile-add {
    width: 80px;
    height: 80px;
    display: flex;
    align-items: center;
    justify-content: center;
    border: 1px dashed var(--color-border-light);
    border-radius: var(--radius-base);
    color: var(--color-text-tertiary);
    font-size: 20px;
    cursor: pointer;
    transition: border-color 0.15s, color 0.15s;

    &:hover {
      border-color: var(--color-primary);
      color: var(--color-primary);
    }

    &.is-disabled {
      opacity: 0.4;
      cursor: not-allowed;
    }
  }

  &__history-filters {
    display: grid;
    grid-template-columns: minmax(160px, 1fr) minmax(260px, 1.4fr) auto;
    gap: var(--spacing-2);
    align-items: center;
    margin-bottom: var(--spacing-2);
  }

  &__request-actions {
    display: flex;
    justify-content: flex-end;
    margin-top: var(--spacing-2);
  }
}

@media (max-width: 768px) {
  .video-gen {
    padding: var(--spacing-3);

    &__history-filters {
      grid-template-columns: 1fr;
    }
  }
  .video-gen__header {
    flex-wrap: wrap;
  }
}
</style>
