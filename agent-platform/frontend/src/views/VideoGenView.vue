<template>
  <div class="video-gen">
    <div class="video-gen__header">
      <h2>视频生成</h2>
      <span class="video-gen__sub">SeedDance 2.0 · 文生视频 / 图生视频</span>
    </div>

    <!-- 无权限：gated 前端落地（菜单已隐藏入口，此处兜底直访 URL 场景） -->
    <n-empty
      v-if="!canGen"
      description="无 media:gen 权限，请联系管理员授权"
      class="video-gen__forbidden"
    />

    <div v-else class="video-gen__grid" :class="{ 'video-gen__grid--mobile': isMobile }">
      <!-- 左：生成表单 -->
      <n-card class="video-gen__form" title="生成参数" size="small">
        <n-form label-placement="top">
          <n-form-item label="生成方式">
            <n-radio-group v-model:value="form.taskType">
              <n-radio-button value="TEXT2VIDEO">文生视频</n-radio-button>
              <n-radio-button value="IMAGE2VIDEO">图生视频</n-radio-button>
            </n-radio-group>
          </n-form-item>

          <n-form-item label="提示词">
            <n-input
              v-model:value="form.prompt"
              type="textarea"
              :rows="4"
              :maxlength="2000"
              show-count
              placeholder="描述你要生成的视频内容，如：一只橘猫在窗台上晒太阳，阳光柔和"
            />
          </n-form-item>

          <!-- 图生视频：参考图上传（复用 /api/files/upload 单一咽喉点） -->
          <n-form-item v-if="form.taskType === 'IMAGE2VIDEO'" label="参考图（首帧）">
            <n-upload
              :max="1"
              accept="image/*"
              list-type="image-card"
              :custom-request="handleRefUpload"
              @remove="onRefRemove"
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

          <n-space>
            <n-button
              type="primary"
              :loading="submitting"
              :disabled="!canSubmit"
              @click="onSubmit"
            >
              提交生成
            </n-button>
            <span v-if="form.taskType === 'IMAGE2VIDEO' && !form.refFileId" class="video-gen__hint">
              图生视频需上传参考图
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
                {{ activeTask.duration }}s · {{ activeTask.resolution }}
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
  </div>
</template>

<script setup lang="ts">
import { h, computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import {
  NButton, NCard, NDataTable, NEmpty, NForm, NFormItem, NInput,
  NRadioButton, NRadioGroup, NSelect, NSpace, NSpin, NTag, NUpload,
  useMessage
} from 'naive-ui'
import type { DataTableColumns, UploadCustomRequestOptions } from 'naive-ui'
import { useAuthStore } from '@/stores/auth'
import { useBreakpoints } from '@/composables/useBreakpoints'
import {
  mediaApi, fetchVideoBlob,
  MEDIA_STATUS_LABEL, MEDIA_STATUS_TYPE, isTerminal,
  type MediaTaskVO, type MediaTaskType, type MediaResolution
} from '@/api/media'

const authStore = useAuthStore()
const message = useMessage()
const { isMobile } = useBreakpoints()

/** 4 层权限显隐①：菜单入口；②此处页内提交（canGen）；③后端 @RequirePermission 403 兜底；④路由 meta 仅 requiresAuth。 */
const canGen = authStore.hasPermission('media:gen')

// === 表单 ===
const form = reactive({
  taskType: 'TEXT2VIDEO' as MediaTaskType,
  prompt: '',
  duration: 5,
  resolution: '720p' as MediaResolution,
  refFileId: '' as string
})

const durationOptions = Array.from({ length: 10 }, (_, i) => ({
  label: `${i + 1} 秒`, value: i + 1
}))
const resolutionOptions: { label: string; value: MediaResolution }[] = [
  { label: '480p（省额度）', value: '480p' },
  { label: '720p（推荐）', value: '720p' },
  { label: '1080p（高质量）', value: '1080p' }
]

const submitting = ref(false)
/** 图生视频必须有参考图 + 提示词非空 */
const canSubmit = computed(
  () => form.prompt.trim().length > 0
    && (form.taskType !== 'IMAGE2VIDEO' || !!form.refFileId)
)

/** 参考图上传：复用 /api/files/upload 单一咽喉点，拿 fileId 填入 refFileId。 */
async function handleRefUpload({ file, onFinish, onError }: UploadCustomRequestOptions) {
  const raw = file.file as File | null
  if (!raw) {
    onError()
    return
  }
  try {
    const { data } = await mediaApi.uploadRefImage(raw)
    form.refFileId = data.data.fileId
    onFinish()
  } catch {
    onError()
    message.error('参考图上传失败')
  }
}
function onRefRemove() {
  form.refFileId = ''
  return true
}

// === 提交 ===
async function onSubmit() {
  submitting.value = true
  try {
    const { data } = await mediaApi.submitVideo({
      prompt: form.prompt.trim(),
      duration: form.duration,
      resolution: form.resolution,
      taskType: form.taskType,
      refFileId: form.taskType === 'IMAGE2VIDEO' ? form.refFileId : undefined
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
