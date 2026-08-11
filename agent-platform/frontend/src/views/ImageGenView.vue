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
            <NAlert v-if="restoredOfflineModel" type="warning" :show-icon="false" class="form-item">
              历史模型 {{ restoredOfflineModel }} 已下线，仅可回看参数，不能直接重新提交。
            </NAlert>
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

              <!-- 参考图（资产库选取 + 本地上传） -->
              <NFormItem v-if="cap.refImageMax > 0" class="form-item" :label="`参考图（≤${cap.refImageMax}）`">
                <div class="refs">
                  <div v-for="(r, i) in refImages" :key="r.fileId" class="refs__chip">
                    <img :src="r.url" class="refs__thumb" :alt="r.name" />
                    <NButton size="tiny" quaternary circle @click="removeRef(i)">✕</NButton>
                  </div>
                  <NButton
                    v-if="refImages.length < cap.refImageMax"
                    size="small"
                    dashed
                    @click="openPicker"
                  >
                    + 从资产库选取
                  </NButton>
                  <NUpload
                    v-if="refImages.length < cap.refImageMax"
                    :show-file-list="false"
                    :accept="refAccept"
                    :custom-request="() => {}"
                    @change="onUploadRef"
                  >
                    <NButton size="small" dashed :loading="uploadingRef">+ 上传本地图片</NButton>
                  </NUpload>
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
            <!-- 问题5：核对当时实际提交参数（含参考图 refFileIds）；图片路径暂无 Provider 快照 -->
            <MediaTaskRequestDetails
              v-if="activeTask.submittedRequest"
              title="图片生成请求参数"
              :submitted-request="activeTask.submittedRequest"
              :provider-request-snapshot="activeTask.providerRequestSnapshot"
            />
          </div>
          <div v-if="images.length" class="result__grid">
            <div v-for="(img, i) in images" :key="i" class="result__cell">
              <img v-if="img.url" :src="img.url" :alt="`生成图 ${i + 1}`" @click="previewSrc = img.url" />
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

        <NDivider v-if="history.length || hasHistoryFilters" />
        <div v-if="history.length || hasHistoryFilters" class="history">
          <div class="history__title">历史</div>
          <!-- 问题4：提示词 + 时间范围筛选（服务端 SQL 过滤，300ms 防抖） -->
          <div class="history__filters">
            <NInput
              v-model:value="historyQuery"
              size="small"
              clearable
              placeholder="筛选提示词"
              aria-label="筛选历史提示词"
              class="history__filter-q"
            />
            <NDatePicker
              v-model:value="historyTimeRange"
              size="small"
              type="daterange"
              clearable
              :actions="['clear']"
              close-on-select
              update-value-on-close
              aria-label="筛选历史时间范围"
              class="history__filter-range"
            />
            <NButton size="small" quaternary :disabled="!hasHistoryFilters" @click="clearHistoryFilters">
              清空
            </NButton>
          </div>
          <NSpin :show="loadingHistory" size="small">
            <NEmpty v-if="!history.length" description="无匹配的历史任务" />
            <div v-for="h in history" :key="h.id" class="history__row" @click="viewHistory(h)">
              <NTag size="small" :type="MEDIA_STATUS_TYPE[h.status]">{{ MEDIA_STATUS_LABEL[h.status] }}</NTag>
              <span class="history__model">{{ h.model }}</span>
              <span class="history__prompt">{{ truncate(h.prompt) }}</span>
              <span class="history__time">{{ fmtTime(h.createdAt) }}</span>
              <!-- 问题1：行右侧首图缩略（懒加载）；无图行占位 -->
              <MediaTaskImageThumb
                v-if="h.status === 'SUCCEEDED' && h.imageUrls?.length"
                :download-path="h.imageUrls[0]"
                @preview="previewSrc = $event"
              />
              <span v-else class="history__thumb-ph" aria-hidden="true">—</span>
            </div>
          </NSpin>
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

    <!-- 生成图点击放大（沉浸预览，点击空白关闭） -->
    <Teleport to="body">
      <div v-if="previewSrc" class="lightbox" @click="previewSrc = null">
        <img :src="previewSrc" class="lightbox__img" alt="预览" />
      </div>
    </Teleport>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import {
  NAlert, NButton, NCard, NDatePicker, NDivider, NEmpty, NForm, NFormItem,
  NInput, NInputNumber, NSelect, NSlider, NSpace, NSpin, NSwitch, NTag, NUpload, useMessage
} from 'naive-ui'
import {
  mediaApi, fetchMediaBlob,
  MEDIA_STATUS_LABEL, MEDIA_STATUS_TYPE, isTerminal,
  type ImageModelVO, type ImageModelCapability,
  type ImageSubmitRequest, type MediaTaskVO
} from '@/api/media'
import { fetchFilePreview } from '@/api/file'
import AssetFilePicker from '@/components/asset/AssetFilePicker.vue'
import SaveImageToAssetDialog from '@/components/imagegen/SaveImageToAssetDialog.vue'
import MediaTaskImageThumb from '@/components/media/MediaTaskImageThumb.vue'
import MediaTaskRequestDetails from '@/components/media/MediaTaskRequestDetails.vue'
import { parseImageRestore } from '@/utils/imageGenParams'
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

// 参考图（资产库选取 + 本地上传）：fileId 提交用，url 为带鉴权拉的 objectURL（缩略展示用）。
interface RefImage { fileId: string; name: string; url: string }
const refImages = ref<RefImage[]>([])
const showPicker = ref(false)
const pickerMax = computed(() => (cap.value ? cap.value.refImageMax - refImages.value.length : 0))

/** 本地上传中（按钮 loading）。 */
const uploadingRef = ref(false)
/** 本地上传 accept：按模型参考图格式白名单拼扩展名（jpg/jpeg 互通）。 */
const refAccept = computed(() => {
  if (!cap.value || !cap.value.refImageFormats.length) return 'image/*'
  const exts = new Set<string>()
  for (const f of cap.value.refImageFormats) {
    exts.add('.' + f)
    if (f === 'jpeg') exts.add('.jpg')
    if (f === 'jpg') exts.add('.jpeg')
  }
  return Array.from(exts).join(',')
})
/** 生成图点击放大的预览源（null=关闭）。 */
const previewSrc = ref<string | null>(null)

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
  restoredOfflineModel.value = ''
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
  clearRefs()
}

/** 释放参考图 objectURL 并清空。 */
function clearRefs() {
  refImages.value.forEach(r => { if (r.url.startsWith('blob:')) URL.revokeObjectURL(r.url) })
  refImages.value = []
}

function openPicker() {
  if (pickerMax.value <= 0) {
    message.warning(`参考图已达上限（${cap.value?.refImageMax}）`)
    return
  }
  showPicker.value = true
}
/** 资产库选取 → 逐张带鉴权拉 objectURL 缩略（resolve.url 直塞 <img> 无 auth header 会裂图）。 */
async function onPicked(payload: AssetFilePicked[]) {
  for (const p of payload) {
    if (!p.fileId) continue
    if (refImages.value.length >= (cap.value?.refImageMax ?? 0)) break
    try {
      const url = await fetchFilePreview(p.fileId)
      refImages.value.push({ fileId: p.fileId, name: p.name ?? '参考图', url })
    } catch { /* 单张预览失败跳过 */ }
  }
}
/** 本地上传参考图：/api/files/upload 落库 → 带鉴权拉 objectURL 展示。 */
async function onUploadRef(opts: { file?: { file?: File | null } } | undefined) {
  const file = opts?.file?.file
  if (!file) return
  if (refImages.value.length >= (cap.value?.refImageMax ?? 0)) {
    message.warning(`参考图已达上限（${cap.value?.refImageMax}）`)
    return
  }
  uploadingRef.value = true
  try {
    const up = await mediaApi.uploadAttachment(file)
    const fileId = up.data.data.fileId
    const url = await fetchFilePreview(fileId)
    refImages.value.push({ fileId, name: file.name, url })
  } catch (e: unknown) {
    message.error((e as { response?: { data?: { message?: string } } })?.response?.data?.message ?? '上传失败')
  } finally {
    uploadingRef.value = false
  }
}
/** 移除单张参考图（释放 objectURL）。 */
function removeRef(i: number) {
  const r = refImages.value[i]
  if (r?.url.startsWith('blob:')) URL.revokeObjectURL(r.url)
  refImages.value.splice(i, 1)
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
const loadingHistory = ref(false)
const historyQuery = ref('')
const historyTimeRange = ref<[number, number] | null>(null)
const hasHistoryFilters = computed(() => !!historyQuery.value.trim() || !!historyTimeRange.value)
let historyDebounceTimer: ReturnType<typeof setTimeout> | null = null
let historyRequestSeq = 0

async function loadHistory() {
  const requestSeq = ++historyRequestSeq
  loadingHistory.value = true
  try {
    const range = historyTimeRange.value
    const { data } = await mediaApi.listTasks({
      q: historyQuery.value.trim() || undefined,
      from: range ? new Date(range[0]).toISOString() : undefined,
      // daterange 结束日是当日 00:00 → +1天-1ms 含整天；否则同日区间 from==to 被后端 400
      to: range ? new Date(range[1] + 24 * 3600 * 1000 - 1).toISOString() : undefined,
      limit: 30,
      kind: 'IMAGE' // 仅图片任务（SQL 层过滤，替代原前端 filter——先 LIMIT 再内存过滤会行数不足）
    })
    if (requestSeq === historyRequestSeq) history.value = data.data ?? []
  } catch { /* 拦截器提示 */ } finally {
    if (requestSeq === historyRequestSeq) loadingHistory.value = false
  }
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
watch([historyQuery, historyTimeRange], scheduleHistoryLoad)

function truncate(s: string | null) {
  if (!s) return ''
  return s.length > 24 ? s.slice(0, 24) + '…' : s
}
function fmtTime(t: string) {
  return new Date(t).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })
}

/** 历史模型已下线标记（非空=显示警告条，表单因 cap=null 自动隐藏提交控件）。 */
const restoredOfflineModel = ref('')

async function viewHistory(h: MediaTaskVO) {
  // 先拉详情：submittedRequest 仅详情透出（列表不带，防响应体膨胀）；还原/弹窗共用这次拉取
  let detail = h
  try {
    const { data } = await mediaApi.getTask(h.id)
    detail = data.data
  } catch { /* 降级用列表行（无 submittedRequest 则跳过还原） */ }
  activeTask.value = detail
  resetImages()
  restoreForm(detail)
  if (detail.status === 'SUCCEEDED' && detail.imageUrls?.length) {
    await ensureImages(detail)
  } else if (!isTerminal(detail.status)) {
    startPolling(h.id)
  }
}

// 问题3：点历史记录还原左侧参数。铁律：逐项直赋 form，**不调 onModelChange**（它会重置其余字段吞掉还原）。
function restoreForm(task: MediaTaskVO) {
  restoredOfflineModel.value = ''
  const patch = parseImageRestore(
    { model: task.model, submittedRequest: task.submittedRequest ?? null },
    models.value
  )
  if (!patch) return
  form.model = patch.model
  form.prompt = patch.prompt
  form.size = patch.size
  customSize.value = patch.customSize
  form.outputFormat = patch.outputFormat
  form.optimizeMode = patch.optimizeMode
  form.guidanceScale = patch.guidanceScale
  form.sequential = patch.sequential
  form.maxImages = patch.maxImages
  form.webSearch = patch.webSearch
  form.watermark = patch.watermark
  for (const w of patch.warnings) {
    if (w.includes('已下线')) restoredOfflineModel.value = patch.model
    message.warning(w)
  }
  void restoreRefs(patch.refFileIds)
}

/** 参考图回填：逐张带鉴权拉缩略；文件已删/失权跳过并汇总告警（后端提交时仍会再校验）。 */
async function restoreRefs(fileIds: string[]) {
  clearRefs()
  let failed = 0
  for (const [i, fid] of fileIds.entries()) {
    try {
      const url = await fetchFilePreview(fid)
      refImages.value.push({ fileId: fid, name: `参考图${i + 1}`, url })
    } catch { failed++ }
  }
  if (failed) message.warning(`${failed} 张参考图已失效（删除/无权），未还原`)
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
  if (historyDebounceTimer !== null) clearTimeout(historyDebounceTimer)
  clearPolling()
  resetImages()
  clearRefs()
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
// 生成图点击放大（沉浸预览）
.lightbox {
  position: fixed; inset: 0; z-index: 2000;
  background: rgba(0, 0, 0, 0.92);
  display: flex; align-items: center; justify-content: center;
  cursor: zoom-out;
  &__img { max-width: 94vw; max-height: 92vh; object-fit: contain; }
}
.history {
  &__title { font-size: 13px; color: var(--text-color-3); margin-bottom: 8px; }
  &__filters { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; flex-wrap: wrap; }
  &__filter-q { flex: 1; min-width: 120px; }
  &__filter-range { width: 230px; }
  &__row {
    display: flex; align-items: center; gap: 8px; padding: 6px 4px;
    border-radius: 6px; cursor: pointer; font-size: 13px;
    &:hover { background: var(--hover-color); }
  }
  &__model { color: var(--text-color-2); white-space: nowrap; }
  &__prompt { flex: 1; color: var(--text-color-3); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  &__time { color: var(--text-color-3); font-size: 12px; white-space: nowrap; }
  &__thumb-ph {
    width: 56px; height: 56px; flex: none; display: flex; align-items: center; justify-content: center;
    border-radius: 6px; background: var(--bg-color-2, #1a1a1e); color: var(--text-color-3);
  }
}
</style>
