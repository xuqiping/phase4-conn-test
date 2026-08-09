<template>
  <div class="image-gen">
    <div class="image-gen__layout" :class="{ 'is-mobile': isMobile }">
      <!-- 左：参数表单 -->
      <NCard class="image-gen__form" title="图片生成" size="small">
        <NSpin :show="submitting">
          <NForm label-placement="top" :show-feedback="false">
            <!-- 模型选择 -->
            <NFormItem label="模型" class="form-item">
              <NSelect
                v-model:value="form.model"
                :options="modelOptions"
                placeholder="选择生图模型"
                @update:value="onModelChange"
              />
            </NFormItem>
            <div v-if="!form.model" class="hint">请先在「设置 → 全局模型供应商」建一条 IMAGE 类 provider 并配置模型。</div>

            <template v-if="cap">
              <!-- 提示词 -->
              <NFormItem label="提示词" class="form-item">
                <NInput
                  v-model:value="form.prompt"
                  type="textarea"
                  :autosize="{ minRows: 3, maxRows: 8 }"
                  placeholder="描述你想生成的画面"
                  maxlength="8000"
                />
              </NFormItem>

              <!-- 参考图（资产库选取） -->
              <NFormItem v-if="cap.refImageMax > 0" class="form-item" :label="`参考图（≤${cap.refImageMax}）`">
                <div class="refs">
                  <div v-for="(r, i) in refImages" :key="r.fileId" class="refs__chip">
                    <img :src="r.url" class="refs__thumb" :alt="r.name" />
                    <NButton size="tiny" quaternary circle @click="refImages.splice(i, 1)">✕</NButton>
                  </div>
                  <NButton
                    v-if="refImages.length < cap.refImageMax"
                    size="small"
                    dashed
                    @click="openPicker"
                  >
                    + 从资产库选取
                  </NButton>
                </div>
                <div class="hint">支持格式：{{ cap.refImageFormats.join(' / ') }}</div>
              </NFormItem>

              <!-- 尺寸（预设下拉 + 自定义宽x高） -->
              <NFormItem label="尺寸" class="form-item">
                <NSpace>
                  <NSelect
                    v-model:value="form.size"
                    :options="sizeOptions"
                    placeholder="尺寸"
                    style="width: 160px"
                    @update:value="onSizePresetChange"
                  />
                  <template v-if="cap.supportsWhSize && form.size === '__custom__'">
                    <NInput
                      v-model:value="customSize"
                      placeholder="宽x高 如 1024x1024"
                      style="width: 180px"
                      @update:value="syncCustomSize"
                    />
                  </template>
                </NSpace>
              </NFormItem>

              <!-- 输出格式 -->
              <NFormItem v-if="cap.outputFormats.length > 1" label="输出格式" class="form-item">
                <NSelect
                  v-model:value="form.outputFormat"
                  :options="toOptions(cap.outputFormats)"
                  style="width: 140px"
                />
              </NFormItem>

              <!-- 提示词优化模式 -->
              <NFormItem v-if="cap.optimizeModes.length > 1" label="提示词优化" class="form-item">
                <NSelect
                  v-model:value="form.optimizeMode"
                  :options="toOptions(cap.optimizeModes)"
                  style="width: 160px"
                />
              </NFormItem>

              <!-- 引导尺度（pro 独有） -->
              <NFormItem v-if="cap.supportsGuidanceScale" label="引导尺度" class="form-item">
                <NSpace align="center">
                  <NSlider
                    v-model:value="form.guidanceScale"
                    :min="cap.guidanceMin"
                    :max="cap.guidanceMax"
                    :step="0.5"
                    style="width: 200px"
                  />
                  <span class="hint">{{ form.guidanceScale }}</span>
                </NSpace>
              </NFormItem>

              <!-- 组图（lite 独有） -->
              <NFormItem v-if="cap.supportsSequential" label="组图" class="form-item">
                <NSpace align="center">
                  <NSelect
                    v-model:value="form.sequential"
                    :options="sequentialOptions"
                    style="width: 140px"
                  />
                  <template v-if="form.sequential === 'auto'">
                    <span class="hint">张数</span>
                    <NInputNumber
                      v-model:value="form.maxImages"
                      :min="1"
                      :max="cap.maxSequentialImages"
                      size="small"
                      style="width: 110px"
                    />
                  </template>
                </NSpace>
              </NFormItem>

              <!-- 联网搜索（lite 独有） -->
              <NFormItem v-if="cap.supportsWebSearch" label="联网搜索" class="form-item">
                <NSwitch v-model:value="form.webSearch" />
              </NFormItem>

              <!-- 水印 -->
              <NFormItem label="水印" class="form-item">
                <NSwitch v-model:value="form.watermark" />
              </NFormItem>

              <NButton
                type="primary"
                block
                :disabled="!canSubmit"
                :loading="submitting"
                @click="onSubmit"
              >
                生成图片
              </NButton>
              <div v-if="errorMsg" class="error">{{ errorMsg }}</div>
            </template>
          </NForm>
        </NSpin>
      </NCard>

      <!-- 右：结果区 -->
      <NCard class="image-gen__result" title="生成结果" size="small">
        <div v-if="activeTask" class="result">
          <div class="result__status">
            <NTag :type="MEDIA_STATUS_TYPE[activeTask.status]">
              {{ MEDIA_STATUS_LABEL[activeTask.status] }}
            </NTag>
            <span v-if="activeTask.status === 'RUNNING'" class="hint">同步生图中…（4K/组图可能需数十秒）</span>
            <span v-if="activeTask.generatedImages" class="hint">{{ activeTask.generatedImages }} 张</span>
            <span v-if="activeTask.errorMsg" class="error">{{ activeTask.errorMsg }}</span>
          </div>
          <div v-if="images.length" class="result__grid">
            <div v-for="(img, i) in images" :key="i" class="result__cell">
              <img v-if="img.url" :src="img.url" :alt="`生成图 ${i + 1}`" />
              <NSpin v-else size="small" />
              <div class="result__actions">
                <NButton size="tiny" tertiary @click="downloadImage(img.url, i)">下载</NButton>
                <NButton size="tiny" tertiary type="primary" @click="openSaveDialog(i)">入库</NButton>
              </div>
            </div>
          </div>
          <NEmpty v-else-if="isTerminal(activeTask.status)" description="无图片产出" />
        </div>
        <NEmpty v-else description="提交后将在此展示生成结果" />

        <NDivider v-if="history.length" />
        <div v-if="history.length" class="history">
          <div class="history__title">历史</div>
          <div v-for="h in history" :key="h.id" class="history__row" @click="viewHistory(h)">
            <NTag size="small" :type="MEDIA_STATUS_TYPE[h.status]">{{ MEDIA_STATUS_LABEL[h.status] }}</NTag>
            <span class="history__model">{{ h.model }}</span>
            <span class="history__prompt">{{ truncate(h.prompt) }}</span>
            <span class="history__time">{{ fmtTime(h.createdAt) }}</span>
          </div>
        </div>
      </NCard>
    </div>

    <!-- 资产库参考图选取（复用 AssetFilePicker，零改动） -->
    <AssetFilePicker
      :show="showPicker"
      media-type="图片"
      :max="pickerMax"
      :exclude-asset-ids="[]"
      @update:show="showPicker = $event"
      @picked="onPicked"
    />

    <!-- 一键入库弹窗（生成→库，复用 SOURCE_MEDIA fileId） -->
    <SaveImageToAssetDialog
      :show="saveDialog.show"
      :task-id="saveDialog.taskId"
      :image-idx="saveDialog.imageIdx"
      :default-name="saveDialog.defaultName"
      @update:show="saveDialog.show = $event"
      @imported="onImported"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import {
  NButton, NCard, NDivider, NEmpty, NForm, NFormItem,
  NInput, NInputNumber, NSelect, NSlider, NSpace, NSpin, NSwitch, NTag, useMessage
} from 'naive-ui'
import {
  mediaApi, fetchMediaBlob,
  MEDIA_STATUS_LABEL, MEDIA_STATUS_TYPE, isTerminal,
  type ImageModelVO, type ImageModelCapability,
  type ImageSubmitRequest, type MediaTaskVO
} from '@/api/media'
import AssetFilePicker from '@/components/asset/AssetFilePicker.vue'
import SaveImageToAssetDialog from '@/components/imagegen/SaveImageToAssetDialog.vue'
import { useAuthStore } from '@/stores/auth'
import { useBreakpoints } from '@/composables/useBreakpoints'
import type { AssetFilePicked } from '@/types/asset'

const message = useMessage()
const authStore = useAuthStore()
const { isMobile } = useBreakpoints()
const canGen = computed(() => authStore.hasPermission('media:gen'))

// ---- 模型目录 + 能力 ----
const models = ref<ImageModelVO[]>([])
const modelOptions = computed(() =>
  models.value.map(m => ({ label: m.displayName, value: m.modelId }))
)
const cap = computed<ImageModelCapability | null>(
  () => models.value.find(m => m.modelId === form.model)?.capability ?? null
)

const form = reactive({
  model: '',
  prompt: '',
  size: '' as string,
  outputFormat: '' as string,
  optimizeMode: '' as string,
  guidanceScale: 5,
  sequential: 'disabled' as string,
  maxImages: 4,
  webSearch: false,
  watermark: true
})
const customSize = ref('')

// 参考图（资产库选取）
const refImages = ref<AssetFilePicked[]>([])
const showPicker = ref(false)
const pickerMax = computed(() => (cap.value ? cap.value.refImageMax - refImages.value.length : 0))

// 下拉候选
const sizeOptions = computed(() => {
  if (!cap.value) return []
  const opts = cap.value.sizePresets.map(s => ({ label: s, value: s }))
  if (cap.value.supportsWhSize) opts.push({ label: '自定义宽x高', value: '__custom__' })
  return opts
})
const sequentialOptions = [
  { label: '关闭', value: 'disabled' },
  { label: '自动组图', value: 'auto' }
]
function toOptions(arr: string[]) {
  return arr.map(v => ({ label: v.toUpperCase(), value: v }))
}
function onSizePresetChange(v: string) {
  if (v !== '__custom__') customSize.value = ''
}
function syncCustomSize() {
  // 自定义模式：size 实际提交值 = customSize（宽x高）
}

// 模型切换 → 按能力重置各字段默认值
function onModelChange() {
  const c = cap.value
  if (!c) return
  form.size = c.sizePresets[0] ?? ''
  form.outputFormat = c.outputFormats[0] ?? ''
  form.optimizeMode = c.optimizeModes[0] ?? ''
  form.guidanceScale = Math.round((c.guidanceMin + c.guidanceMax) / 2)
  form.sequential = c.supportsSequential ? 'disabled' : ''
  form.maxImages = Math.min(4, c.maxSequentialImages || 4)
  form.webSearch = false
  form.watermark = c.watermarkDefault
  refImages.value = []
}

function openPicker() {
  if (pickerMax.value <= 0) {
    message.warning(`参考图已达上限（${cap.value?.refImageMax}）`)
    return
  }
  showPicker.value = true
}
function onPicked(payload: AssetFilePicked[]) {
  refImages.value.push(...payload)
}

// ---- 提交 ----
const submitting = ref(false)
const errorMsg = ref('')
const canSubmit = computed(() => canGen.value && !!form.model && !!form.prompt?.trim())

function buildRequest(): ImageSubmitRequest {
  const req: ImageSubmitRequest = {
    model: form.model,
    prompt: form.prompt.trim()
  }
  if (refImages.value.length) req.refFileIds = refImages.value.map(r => r.fileId)
  // size：预设直接用；自定义用 customSize
  if (form.size === '__custom__') {
    if (customSize.value.trim()) req.size = customSize.value.trim()
  } else if (form.size) {
    req.size = form.size
  }
  if (form.outputFormat) req.outputFormat = form.outputFormat
  if (form.optimizeMode) req.optimizeMode = form.optimizeMode
  req.watermark = form.watermark
  if (cap.value?.supportsGuidanceScale) req.guidanceScale = form.guidanceScale
  if (cap.value?.supportsSequential && form.sequential) {
    req.sequential = form.sequential
    if (form.sequential === 'auto') req.maxImages = form.maxImages
  }
  if (cap.value?.supportsWebSearch && form.webSearch) req.webSearch = true
  return req
}

async function onSubmit() {
  if (!canSubmit.value) return
  submitting.value = true
  errorMsg.value = ''
  try {
    const { data } = await mediaApi.submitImage(buildRequest())
    activeTask.value = null
    resetImages()
    startPolling(data.data.id)
    void loadHistory()
  } catch (e: any) {
    errorMsg.value = e?.response?.data?.message ?? e?.message ?? '提交失败'
  } finally {
    submitting.value = false
  }
}

// ---- 轮询 ----
const activeTask = ref<MediaTaskVO | null>(null)
const images = ref<{ url: string | null }[]>([])
let pollTimer: ReturnType<typeof setInterval> | null = null

function resetImages() {
  images.value.forEach(i => { if (i.url) URL.revokeObjectURL(i.url) })
  images.value = []
}

async function pollOnce(taskId: number) {
  const { data } = await mediaApi.getTask(taskId)
  activeTask.value = data.data
  if (data.data.status === 'SUCCEEDED' && data.data.imageUrls?.length && !images.value.length) {
    await ensureImages(data.data)
  }
  if (isTerminal(data.data.status)) {
    clearPolling()
    void loadHistory()
  }
}
function startPolling(taskId: number) {
  clearPolling()
  void pollOnce(taskId)
  pollTimer = setInterval(() => void pollOnce(taskId), 2500)
}
function clearPolling() {
  if (pollTimer) { clearInterval(pollTimer); pollTimer = null }
}

async function ensureImages(task: MediaTaskVO) {
  if (!task.imageUrls) return
  images.value = task.imageUrls.map(() => ({ url: null }))
  await Promise.all(task.imageUrls.map(async (p, i) => {
    try {
      images.value[i].url = await fetchMediaBlob(p)
    } catch { /* 单张失败留空，不阻塞其余 */ }
  }))
}

async function downloadImage(url: string | null, idx: number) {
  if (!url) return
  const a = document.createElement('a')
  a.href = url
  a.download = `image-${activeTask.value?.id ?? 'task'}-${idx + 1}.png`
  a.click()
}

// ---- 一键入库（生成→库） ----
const saveDialog = reactive({
  show: false,
  taskId: null as number | null,
  imageIdx: null as number | null,
  defaultName: ''
})
function openSaveDialog(idx: number) {
  if (!activeTask.value?.id) return
  saveDialog.taskId = activeTask.value.id
  saveDialog.imageIdx = idx
  // 默认名取提示词截断，空则后端兜底「图片产出」
  const p = form.prompt?.trim()
  saveDialog.defaultName = p ? (p.length > 40 ? p.slice(0, 40) + '…' : p) : ''
  saveDialog.show = true
}
function onImported(payload: { assetId: number; name: string }) {
  message.success(`已入库：${payload.name}`)
}

// ---- 历史 ----
const history = ref<MediaTaskVO[]>([])
async function loadHistory() {
  try {
    const { data } = await mediaApi.listTasks(30)
    // 仅图片任务
    history.value = (data.data ?? []).filter(t =>
      t.taskType === 'TEXT2IMAGE' || t.taskType === 'IMAGE2IMAGE')
  } catch { /* ignore */ }
}
function truncate(s: string | null) {
  if (!s) return ''
  return s.length > 24 ? s.slice(0, 24) + '…' : s
}
function fmtTime(t: string) {
  return new Date(t).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })
}
async function viewHistory(h: MediaTaskVO) {
  activeTask.value = h
  resetImages()
  if (h.status === 'SUCCEEDED' && h.imageUrls?.length) {
    await ensureImages(h)
  } else if (!isTerminal(h.status)) {
    startPolling(h.id)
  }
}

onMounted(async () => {
  try {
    const { data } = await mediaApi.listImageModels()
    models.value = data.data ?? []
    if (models.value.length && !form.model) {
      form.model = models.value[0].modelId
      onModelChange()
    }
  } catch { /* ignore */ }
  void loadHistory()
})
onUnmounted(() => {
  clearPolling()
  resetImages()
})
</script>

<style scoped lang="scss">
.image-gen {
  padding: 16px;
  &__layout {
    display: grid;
    grid-template-columns: 420px 1fr;
    gap: 16px;
    align-items: start;
    &.is-mobile { grid-template-columns: 1fr; }
  }
  &__form { position: sticky; top: 16px; }
}
.form-item { margin-bottom: 14px; }
.hint { font-size: 12px; color: var(--text-color-3); margin-top: 4px; }
.error { color: var(--error-color); font-size: 13px; margin-top: 8px; }
.refs {
  display: flex; flex-wrap: wrap; gap: 8px; align-items: center;
  &__chip { position: relative; }
  &__thumb { width: 56px; height: 56px; object-fit: cover; border-radius: 6px; }
}
.result {
  &__status { display: flex; align-items: center; gap: 12px; margin-bottom: 12px; flex-wrap: wrap; }
  &__grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(220px, 1fr)); gap: 12px; }
  &__cell {
    border: 1px solid var(--border-color); border-radius: 8px; overflow: hidden;
    display: flex; flex-direction: column;
    img { width: 100%; display: block; cursor: zoom-in; }
  }
  &__actions { padding: 6px; display: flex; gap: 6px; justify-content: flex-end; }
}
.history {
  &__title { font-size: 13px; color: var(--text-color-3); margin-bottom: 8px; }
  &__row {
    display: flex; align-items: center; gap: 8px; padding: 6px 4px;
    border-radius: 6px; cursor: pointer; font-size: 13px;
    &:hover { background: var(--hover-color); }
  }
  &__model { color: var(--text-color-2); white-space: nowrap; }
  &__prompt { flex: 1; color: var(--text-color-3); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  &__time { color: var(--text-color-3); font-size: 12px; white-space: nowrap; }
}
</style>
