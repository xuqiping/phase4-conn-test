<template>
  <div class="canvas-view">
    <!-- 无权限兜底（直访 /canvas 无 canvas:write） -->
    <n-card v-if="!canEdit" class="canvas-view__forbid" title="无访问权限">
      <p>无限画布需要「canvas:write」权限。请联系管理员授权后重试。</p>
    </n-card>

    <!-- 列表模式：我的画布 -->
    <div v-else-if="!editingId" class="canvas-view__list">
      <div class="canvas-view__header">
        <h2 class="canvas-view__title">我的画布</h2>
        <n-button type="primary" :loading="creating" @click="onCreate">
          <template #icon><n-icon :component="AddOutline" /></template>
          新建画布
        </n-button>
      </div>

      <n-spin :show="loadingList">
        <n-empty v-if="!canvases.length" description="还没有画布，点击「新建画布」开始创作" />
        <div v-else class="canvas-view__grid">
          <div
            v-for="c in canvases"
            :key="c.id"
            class="canvas-card"
            @click="openEditor(c.id)"
          >
            <div class="canvas-card__icon">
              <n-icon size="28" :component="AppsOutline" />
            </div>
            <div class="canvas-card__body">
              <div class="canvas-card__name" :title="c.name">{{ c.name }}</div>
              <div class="canvas-card__meta">
                {{ c.nodeCount ?? 0 }} 节点 · {{ formatTime(c.updatedAt) }}
              </div>
            </div>
            <n-button
              class="canvas-card__del"
              quaternary
              size="tiny"
              @click.stop="onDelete(c)"
            >
              <n-icon :component="TrashOutline" />
            </n-button>
          </div>
        </div>
      </n-spin>
    </div>

    <!-- 编辑模式 -->
    <div v-else class="canvas-view__editor">
      <div class="canvas-view__editor-header">
        <n-button quaternary @click="backToList">
          <n-icon :component="ArrowBackOutline" /> 返回
        </n-button>
        <n-input
          v-model:value="currentName"
          class="canvas-view__name-input"
          placeholder="画布名"
          @blur="onRename"
        />
        <n-button :loading="saving" type="primary" @click="onSave">
          <n-icon :component="SaveOutline" /> 保存
        </n-button>
        <n-button :loading="rerunning" quaternary @click="onRerunAll" title="按拓扑序重跑全部可生成节点（环检测）">
          <n-icon :component="RefreshOutline" /> 重跑全链
        </n-button>
      </div>

      <div class="canvas-view__main">
        <!-- 节点调色板（拖到画布即增节点；C3 起扩 5 类节点） -->
        <aside class="canvas-palette">
          <div class="canvas-palette__title">节点</div>
          <div
            v-for="p in palette"
            :key="p.type"
            class="canvas-palette__item"
            draggable="true"
            @dragstart="onPaletteDragStart($event, p)"
            @click="onPaletteClick(p)"
          >
            <n-icon :component="p.icon" />
            <span>{{ p.label }}</span>
          </div>
        </aside>

        <!-- 画布板 -->
        <CanvasBoard ref="boardRef" @node-selected="onNodeSelect" />

        <!-- 属性面板（选中节点编辑 + 运行/上传触发） -->
        <PropertyPanel
          :node="selectedNode"
          :running="runningNodeId === selectedNode?.id"
          @run="onRunNode"
          @upload="onUploadFile"
          @focus-edit="onFocusEdit"
          @extract-frame="onExtractFrame"
          @clip-video="onClipVideo"
        />
      </div>

      <!-- C10 焦点编辑沉浸 overlay（teleport to body） -->
      <FocusEditOverlay
        v-if="focusNode"
        :preview-url="(focusNode.data.previewUrl as string | undefined)"
        @confirm="onFocusConfirm"
        @cancel="focusNode = null"
      />
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  NButton, NCard, NEmpty, NIcon, NInput, NSpin, useMessage
} from 'naive-ui'
import {
  AddOutline, AppsOutline, ArrowBackOutline, SaveOutline, TrashOutline, RefreshOutline,
  DocumentTextOutline, ImageOutline, VideocamOutline, MusicalNotesOutline, CodeSlashOutline
} from '@vicons/ionicons5'
import { useAuthStore } from '@/stores/auth'
import { canvasApi, fetchCanvasPreview, type CanvasNodeDTO, type CanvasVO, type FrameMode } from '@/api/canvas'
import { mediaApi, fetchVideoBlob, isTerminal } from '@/api/media'
import type { MediaStatus } from '@/api/media'
import type { CanvasNode, CanvasSnapshot } from '@/types/canvas'
import CanvasBoard from '@/components/canvas/CanvasBoard.vue'
import PropertyPanel from '@/components/canvas/PropertyPanel.vue'
import FocusEditOverlay from '@/components/canvas/FocusEditOverlay.vue'
import type { CropRect } from '@/types/canvas'

const route = useRoute()
const router = useRouter()
const authStore = useAuthStore()
const message = useMessage()

/** 4 层权限显隐兜底：菜单隐藏 + 页内 hasPermission + API 403（plan IC-16）。 */
const canEdit = computed(() => authStore.hasPermission('canvas:write'))

const canvases = ref<CanvasVO[]>([])
const loadingList = ref(false)
const creating = ref(false)
const saving = ref(false)
/** 一键重跑进行中（拓扑序串行跑可生成节点）。 */
const rerunning = ref(false)

/** 当前编辑画布 id（null=列表模式）。 */
const editingId = ref<number | null>(null)
const currentName = ref('')
const boardRef = ref<InstanceType<typeof CanvasBoard> | null>(null)
/** 当前选中节点（属性面板编辑目标；null=未选）。 */
const selectedNode = ref<CanvasNode | null>(null)
/** 正在运行的节点 id（属性面板按钮 loading + 防重入）。 */
const runningNodeId = ref<string | null>(null)
/** 焦点编辑中的图节点（null=关闭沉浸 overlay）。 */
const focusNode = ref<CanvasNode | null>(null)

function onNodeSelect(node: CanvasNode | null) {
  selectedNode.value = node
}

/** C10 焦点编辑：进入沉浸 overlay。 */
function onFocusEdit(node: CanvasNode) {
  focusNode.value = node
}

/**
 * C10 焦点编辑确认：在原图节点右侧产新 image 节点（带 cropRect + parentFileId + 描述），
 * 并自动连原图→新节点。提取质量依赖后续生图/分割模型（R-8 弱保底：链路通即可）。
 */
function onFocusConfirm(payload: { rect: CropRect; description: string }) {
  const src = focusNode.value
  if (!src || !boardRef.value) return
  const offsetX = (src.position?.x ?? 0) + 260
  const offsetY = src.position?.y ?? 0
  boardRef.value.addNode({
    type: 'image',
    position: { x: offsetX, y: offsetY },
    data: {
      label: '衍生图',
      parentFileId: (src.data as Record<string, unknown>).fileId as string | undefined,
      cropRect: payload.rect,
      prompt: payload.description,
      status: 'idle'
    }
  })
  // 取最新加入的节点 id 连边（addNode 用 Date.now id，取数组末尾）
  const nodes = boardRef.value.getNodes()
  const created = nodes[nodes.length - 1]
  if (created) boardRef.value.addEdge(src.id, created.id)
  focusNode.value = null
  message.success('已产新图节点（提取质量待生图/分割模型）')
  scheduleSave()
}

/** 运行节点（C4 文本/图片 / C5 视频）：按类型分发。视频走 media API（media:gen gated）。 */
async function onRunNode(node: CanvasNode) {
  if (!editingId.value || !node) return
  if (runningNodeId.value === node.id) return
  if (node.type === 'video') {
    await onRunVideo(node)
    return
  }
  // text/image 走画布 runner（无状态）
  runningNodeId.value = node.id
  boardRef.value?.updateNodeData(node.id, { status: 'running', errorMsg: '' })
  try {
    const payload: CanvasNodeDTO = {
      id: node.id,
      type: node.type as CanvasNodeDTO['type'],
      data: node.data as Record<string, unknown>
    }
    const res = await canvasApi.runNode(editingId.value, payload)
    const result = res.data.data
    if (result?.dataPatch) {
      boardRef.value?.updateNodeData(node.id, result.dataPatch)
    }
    message.success(result?.status === 'success' ? '生成完成' : '已处理')
    scheduleSave()
  } catch (e: unknown) {
    const msg = (e as { msg?: string })?.msg || '生成失败'
    boardRef.value?.updateNodeData(node.id, { status: 'failed', errorMsg: msg })
    message.error(msg)
  } finally {
    runningNodeId.value = null
  }
}

/**
 * 运行视频节点（C5）：复用既有 media API。
 * 提交（文生/图生二选一，按 node.data.refFileId）→ 轮询至终态 → 成功 fetch blob 转 objectURL 预览。
 * 权限：media:gen gated，无权则 submit 403 → 节点 FAILED（plan 安全清单「各自权限」）。
 */
async function onRunVideo(node: CanvasNode) {
  if (!editingId.value) return
  const data = node.data as Record<string, unknown>
  const prompt = String(data.prompt ?? '').trim()
  if (!prompt) {
    message.warning('请先填写视频提示词')
    return
  }
  runningNodeId.value = node.id
  boardRef.value?.updateNodeData(node.id, { status: 'running', errorMsg: '' })
  try {
    // C8 数据流：图→视频连线时，上游图节点 fileId 自动作参考图（手动 refFileId 优先）
    const refFileId = (data.refFileId ? String(data.refFileId) : undefined) ?? resolveUpstreamImageFileId(node.id)
    const submit = await mediaApi.submitVideo({
      prompt,
      ratio: (data.ratio as MediaRatioArg) || '16:9',
      duration: Number(data.duration ?? 5),
      resolution: (data.resolution as MediaResArg) || '720p',
      watermark: Boolean(data.watermark),
      generateAudio: Boolean(data.generateAudio),
      taskType: refFileId ? 'IMAGE2VIDEO' : 'TEXT2VIDEO',
      refFileId
    })
    const taskId = submit.data.data.id
    boardRef.value?.updateNodeData(node.id, { taskId, status: 'running' })
    message.info('视频已提交，生成中…')
    scheduleSave()
    await pollVideoTask(node.id, taskId)
  } catch (e: unknown) {
    const msg = (e as { msg?: string })?.msg || '视频提交失败'
    boardRef.value?.updateNodeData(node.id, { status: 'failed', errorMsg: msg })
    message.error(msg)
  } finally {
    runningNodeId.value = null
  }
}

/** 轮询视频任务至终态；成功 fetch blob 预览，失败标红。 */
async function pollVideoTask(nodeId: string, taskId: number) {
  const maxRounds = 120 // ~10min 上限（每 5s 一次）
  for (let i = 0; i < maxRounds; i++) {
    await new Promise<void>(r => setTimeout(r, 5000))
    let status: MediaStatus
    try {
      const res = await mediaApi.getTask(taskId)
      status = res.data.data.status
    } catch {
      continue // 瞬时网络错误继续轮询
    }
    if (!isTerminal(status)) {
      boardRef.value?.updateNodeData(nodeId, { status: 'running' })
      continue
    }
    if (status === 'SUCCEEDED') {
      // 拉带鉴权的视频流 → objectURL（下载端点需 auth header，<video src> 无法带）
      const detail = await mediaApi.getTask(taskId)
      const url = detail.data.data.videoUrl
      const objectUrl = url ? await fetchVideoBlob(url) : ''
      boardRef.value?.updateNodeData(nodeId, {
        status: 'success',
        mediaStatus: 'SUCCEEDED',
        previewUrl: objectUrl,
        // C11：存结果 fileId（stored_files），抽帧 loadPath 直读做 javacv seek
        fileId: detail.data.data.resultFileId ?? undefined,
        errorMsg: ''
      })
      message.success('视频生成完成')
    } else {
      boardRef.value?.updateNodeData(nodeId, {
        status: 'failed',
        mediaStatus: status,
        errorMsg: '视频生成失败'
      })
      message.error('视频生成失败')
    }
    scheduleSave()
    return
  }
  boardRef.value?.updateNodeData(nodeId, { status: 'failed', errorMsg: '生成超时' })
}

type MediaRatioArg = Parameters<typeof mediaApi.submitVideo>[0]['ratio']
type MediaResArg = Parameters<typeof mediaApi.submitVideo>[0]['resolution']

/**
 * C8 数据流：沿 target=nodeId 的入边找上游 image 节点已产出 fileId（图生视频参考图）。
 * 取第一个命中的（多入边场景后续可细化选择器）。无则 undefined（文生视频）。
 */
function resolveUpstreamImageFileId(nodeId: string): string | undefined {
  const edges = boardRef.value?.getEdges() ?? []
  for (const e of edges) {
    if (e.target !== nodeId) continue
    const src = boardRef.value?.getNode(e.source)
    if (src?.type === 'image') {
      const fid = (src.data as Record<string, unknown>).fileId as string | undefined
      if (fid) return fid
    }
  }
  return undefined
}

/** C9 一键重跑：拓扑排序（Kahn）+ 环检测 → 按序串行跑可生成节点。 */
async function onRerunAll() {
  if (!boardRef.value || rerunning.value) return
  const nodes = boardRef.value.getNodes()
  const edges = boardRef.value.getEdges()
  if (!nodes.length) {
    message.warning('画布为空')
    return
  }
  const { order, cycle } = topoSort(nodes, edges)
  if (cycle) {
    message.error('画布存在环路，无法拓扑重跑（请检查连线）')
    return
  }
  rerunning.value = true
  try {
    let ran = 0
    for (const id of order) {
      const node = boardRef.value.getNode(id)
      if (!node) continue
      if (isRunnable(node.type)) {
        await onRunNode(node)
        ran++
      }
    }
    message.success(`重跑完成（${ran} 个节点）`)
  } finally {
    rerunning.value = false
  }
}

/** 可生成节点类型（image/audio 为上传型，跳过；image AI 生图 provider 落地后加入）。 */
function isRunnable(type: string): boolean {
  return type === 'text' || type === 'script' || type === 'video'
}

/**
 * 拓扑排序 + 环检测（Kahn 算法）。
 * 入度表 + 邻接表 → 入度为 0 的入队 → 逐出队减邻居入度。
 * order.length < 节点数 ⇒ 存在环（plan R-6 环检测报错）。
 */
function topoSort(
  nodes: CanvasNode[],
  edges: { source: string; target: string }[]
): { order: string[]; cycle: boolean } {
  const inDegree = new Map<string, number>()
  const adj = new Map<string, string[]>()
  for (const n of nodes) {
    inDegree.set(n.id, 0)
    adj.set(n.id, [])
  }
  for (const e of edges) {
    if (!inDegree.has(e.source) || !inDegree.has(e.target)) continue
    adj.get(e.source)!.push(e.target)
    inDegree.set(e.target, (inDegree.get(e.target) ?? 0) + 1)
  }
  const queue: string[] = []
  for (const [id, deg] of inDegree) if (deg === 0) queue.push(id)
  const order: string[] = []
  while (queue.length) {
    const cur = queue.shift()!
    order.push(cur)
    for (const nb of adj.get(cur) ?? []) {
      const nd = (inDegree.get(nb) ?? 1) - 1
      inDegree.set(nb, nd)
      if (nd === 0) queue.push(nb)
    }
  }
  return { order, cycle: order.length < nodes.length }
}

/** 上传产出物（C4 图片 / C6 音频 / 视频参考图）：落 SOURCE_CANVAS，写回 fileId + 会话级预览。 */
async function onUploadFile(payload: { node: CanvasNode; file: File }) {
  if (!editingId.value || !payload.node) return
  const { node, file } = payload
  runningNodeId.value = node.id
  boardRef.value?.updateNodeData(node.id, { status: 'running' })
  try {
    const res = await canvasApi.upload(editingId.value, file)
    const f = res.data.data
    // /api/files/{id} 需 auth header，<img>/<audio> src 无法带 → axios 拉 blob 转 objectURL（会话级）
    const previewUrl = await fetchCanvasPreview(f.fileId)
    boardRef.value?.updateNodeData(node.id, {
      status: 'success',
      fileId: f.fileId,
      previewUrl,
      mime: f.mimeType,
      errorMsg: ''
    })
    message.success('上传成功')
    scheduleSave()
  } catch (e: unknown) {
    const msg = (e as { msg?: string })?.msg || '上传失败'
    boardRef.value?.updateNodeData(node.id, { status: 'failed', errorMsg: msg })
    message.error(msg)
  } finally {
    runningNodeId.value = null
  }
}

/**
 * C11 视频抽帧：调后端抽首/尾/指定秒 → 返新图片 fileId → 产图节点（带 fileId+预览）+ 自动连回视频节点。
 * 失败不产空节点（后端抛 → catch 标红源视频节点，不建图节点，plan 边界）。
 */
async function onExtractFrame(payload: { node: CanvasNode; mode: FrameMode; second?: number }) {
  if (!editingId.value || !payload.node) return
  const src = payload.node
  const srcFileId = (src.data as Record<string, unknown>).fileId as string | undefined
  if (!srcFileId) {
    message.warning('视频节点无源文件，请先生成或等待视频完成')
    return
  }
  runningNodeId.value = src.id
  try {
    const res = await canvasApi.extractFrame(editingId.value, src.id, {
      mode: payload.mode,
      second: payload.second
    })
    const f = res.data.data
    const previewUrl = await fetchCanvasPreview(f.fileId)
    const offsetX = (src.position?.x ?? 0) + 260
    const offsetY = (src.position?.y ?? 0)
    const labelTail = payload.mode === 'AT' ? ` ${payload.second}s` : ''
    boardRef.value?.addNode({
      type: 'image',
      position: { x: offsetX, y: offsetY },
      data: {
        label: `抽帧(${payload.mode}${labelTail})`,
        fileId: f.fileId,
        previewUrl,
        parentFileId: srcFileId,
        sourceNodeId: src.id,
        status: 'success'
      }
    })
    const nodes = boardRef.value!.getNodes()
    const created = nodes[nodes.length - 1]
    if (created) boardRef.value!.addEdge(src.id, created.id)
    message.success('抽帧完成，已产新图节点')
    scheduleSave()
  } catch (e: unknown) {
    const msg = (e as { msg?: string })?.msg || '抽帧失败'
    boardRef.value?.updateNodeData(src.id, { errorMsg: msg })
    message.error(msg)
  } finally {
    runningNodeId.value = null
  }
}

/**
 * C12 视频截取：调后端截 [startSec,endSec) → 返新视频 fileId → 产视频节点（带 fileId+预览）+ 自动连回源视频节点。
 * 失败不产空节点（后端抛 → catch 标红源视频节点，不建视频节点，plan 边界）。
 * 预览：clip 产物落 stored_files(SOURCE_CANVAS)，/api/files/{id} 需 auth header → fetchVideoBlob 拉 blob 转 objectURL。
 */
async function onClipVideo(payload: { node: CanvasNode; startSec: number; endSec: number }) {
  if (!editingId.value || !payload.node) return
  const src = payload.node
  const srcFileId = (src.data as Record<string, unknown>).fileId as string | undefined
  if (!srcFileId) {
    message.warning('视频节点无源文件，请先生成或等待视频完成')
    return
  }
  runningNodeId.value = src.id
  try {
    const res = await canvasApi.clipVideo(editingId.value, src.id, {
      startSec: payload.startSec,
      endSec: payload.endSec
    })
    const f = res.data.data
    const previewUrl = await fetchVideoBlob(f.url)
    const offsetX = (src.position?.x ?? 0) + 260
    const offsetY = (src.position?.y ?? 0) + 140
    boardRef.value?.addNode({
      type: 'video',
      position: { x: offsetX, y: offsetY },
      data: {
        label: `截取(${payload.startSec}-${payload.endSec}s)`,
        fileId: f.fileId,
        previewUrl,
        mediaStatus: 'SUCCEEDED',
        status: 'success',
        parentFileId: srcFileId,
        sourceNodeId: src.id,
        prompt: ''
      }
    })
    const nodes = boardRef.value!.getNodes()
    const created = nodes[nodes.length - 1]
    if (created) boardRef.value!.addEdge(src.id, created.id)
    message.success('截取完成，已产新视频节点')
    scheduleSave()
  } catch (e: unknown) {
    const msg = (e as { msg?: string })?.msg || '截取失败'
    boardRef.value?.updateNodeData(src.id, { errorMsg: msg })
    message.error(msg)
  } finally {
    runningNodeId.value = null
  }
}

/** 节点产出后自动保存快照（防丢结果）；保存节流复用 saving 标志。 */
let saveTimer: ReturnType<typeof setTimeout> | null = null
function scheduleSave() {
  if (saveTimer) clearTimeout(saveTimer)
  saveTimer = setTimeout(() => { onSave() }, 800)
}

/** 节点调色板（C3 起接入各自属性面板与产出触发；MVP 先通用节点占位）。 */
const palette = [
  { type: 'text', label: '文本', icon: DocumentTextOutline },
  { type: 'image', label: '图片', icon: ImageOutline },
  { type: 'video', label: '视频', icon: VideocamOutline },
  { type: 'audio', label: '音频', icon: MusicalNotesOutline },
  { type: 'script', label: '脚本', icon: CodeSlashOutline }
]

async function loadList() {
  loadingList.value = true
  try {
    const res = await canvasApi.list()
    canvases.value = res.data.data
  } catch {
    message.error('画布列表加载失败')
  } finally {
    loadingList.value = false
  }
}

async function onCreate() {
  creating.value = true
  try {
    const res = await canvasApi.create()
    canvases.value.unshift(res.data.data)
    openEditor(res.data.data.id)
  } catch {
    message.error('新建画布失败')
  } finally {
    creating.value = false
  }
}

function openEditor(id: number) {
  router.push(`/canvas/${id}`)
}

async function loadCanvas(id: number) {
  try {
    const res = await canvasApi.get(id)
    const c = res.data.data
    editingId.value = c.id
    currentName.value = c.name
    const snap = parseSnapshot(c.snapshot)
    boardRef.value?.loadSnapshot(snap)
    // 重取会话级预览：快照只存 fileId（previewUrl blob 上次保存已剥），image/audio 节点按 fileId 拉 blob
    hydratePreviews(snap.nodes)
    // 视频节点：按 taskId 重取视频预览（若有终态任务）
    hydrateVideoPreviews(snap.nodes)
  } catch {
    message.error('画布加载失败')
    backToList()
  }
}

/** image/audio 节点按 fileId 重取 objectURL 预览（失败静默，不阻断加载）。 */
function hydratePreviews(nodes: CanvasNode[]) {
  for (const n of nodes) {
    const fileId = (n.data as Record<string, unknown>).fileId as string | undefined
    if (fileId && !(n.data as Record<string, unknown>).previewUrl && (n.type === 'image' || n.type === 'audio')) {
      fetchCanvasPreview(fileId)
        .then(url => boardRef.value?.updateNodeData(n.id, { previewUrl: url }))
        .catch(() => { /* 预览取失败不阻断 */ })
    }
  }
}

/** 视频节点按已终态 taskId 重取视频预览。 */
function hydrateVideoPreviews(nodes: CanvasNode[]) {
  for (const n of nodes) {
    if (n.type !== 'video') continue
    const taskId = (n.data as Record<string, unknown>).taskId as number | undefined
    const mediaStatus = (n.data as Record<string, unknown>).mediaStatus as string | undefined
    if (taskId && mediaStatus === 'SUCCEEDED') {
      mediaApi.getTask(taskId)
        .then(async r => {
          const url = r.data.data.videoUrl
          if (url) {
            const obj = await fetchVideoBlob(url)
            boardRef.value?.updateNodeData(n.id, {
              previewUrl: obj,
              status: 'success',
              fileId: r.data.data.resultFileId ?? undefined
            })
          }
        })
        .catch(() => { /* 静默 */ })
    }
  }
}

function parseSnapshot(raw: string | null): CanvasSnapshot {
  if (!raw) return { nodes: [], edges: [] }
  try {
    const obj = JSON.parse(raw)
    return { nodes: obj.nodes ?? [], edges: obj.edges ?? [], viewport: obj.viewport }
  } catch {
    return { nodes: [], edges: [] }
  }
}

async function onSave() {
  if (!editingId.value || !boardRef.value) return
  saving.value = true
  try {
    const snap = boardRef.value.getSnapshot()
    // 剥离会话级字段：视频 previewUrl 是 blob: objectURL（带鉴权 fetch 产物），跨会话失效，不入快照。
    // taskId/status 入快照（加载时可按 taskId 重新 fetch 预览）。
    const cleanNodes = snap.nodes.map(n => {
      const dataCopy = { ...n.data } as Record<string, unknown>
      delete dataCopy.previewUrl
      return { ...n, data: dataCopy }
    })
    await canvasApi.save(editingId.value, {
      name: currentName.value || '未命名画布',
      snapshot: JSON.stringify({ ...snap, nodes: cleanNodes })
    })
    message.success('已保存')
    await loadList()
  } catch {
    message.error('保存失败')
  } finally {
    saving.value = false
  }
}

async function onRename() {
  if (!editingId.value) return
  const name = currentName.value.trim()
  if (!name) return
  try {
    await canvasApi.rename(editingId.value, name)
  } catch {
    message.error('重命名失败')
  }
}

async function onDelete(c: CanvasVO) {
  if (!confirm(`确认删除画布「${c.name}」？产出物文件会保留，不级联清理。`)) return
  try {
    await canvasApi.remove(c.id)
    canvases.value = canvases.value.filter(x => x.id !== c.id)
    message.success('已删除')
  } catch {
    message.error('删除失败')
  }
}

function backToList() {
  editingId.value = null
  currentName.value = ''
  router.push('/canvas')
}

function onPaletteDragStart(event: DragEvent, p: { type: string; label: string }) {
  if (!event.dataTransfer) return
  event.dataTransfer.setData('application/vueflow', JSON.stringify({ type: p.type, label: p.label }))
  event.dataTransfer.effectAllowed = 'move'
}

function onPaletteClick(p: { type: string; label: string }) {
  boardRef.value?.addNode({ type: p.type, data: { label: p.label } })
}

function formatTime(t: string | null): string {
  if (!t) return ''
  return t.slice(0, 16).replace('T', ' ')
}

// 路由 param 驱动 列表/编辑 切换
watch(
  () => route.params.id,
  (id) => {
    if (id) loadCanvas(Number(id))
    else { editingId.value = null }
  }
)

onMounted(() => {
  loadList()
  if (route.params.id) loadCanvas(Number(route.params.id))
})
</script>

<style lang="scss" scoped>
.canvas-view {
  height: 100%;
  display: flex;
  flex-direction: column;
  padding: var(--spacing-4);
  gap: var(--spacing-3);
}

.canvas-view__forbid {
  max-width: 480px;
  margin: auto;
}

// 列表模式
.canvas-view__list {
  height: 100%;
  display: flex;
  flex-direction: column;
  gap: var(--spacing-3);
}

.canvas-view__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.canvas-view__title {
  font-size: var(--font-size-xl);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
  margin: 0;
}

.canvas-view__grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  gap: var(--spacing-3);
}

.canvas-card {
  display: flex;
  align-items: center;
  gap: var(--spacing-3);
  padding: var(--spacing-3) var(--spacing-4);
  background: var(--color-surface);
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-md);
  cursor: pointer;
  transition: all var(--duration-fast) var(--ease-in-out);
  position: relative;

  &:hover {
    border-color: var(--color-primary);
    transform: translateY(-2px);
    box-shadow: var(--shadow-md);
  }

  &__icon {
    color: var(--color-primary);
    flex-shrink: 0;
  }

  &__body {
    flex: 1;
    min-width: 0;
  }

  &__name {
    font-size: var(--font-size-base);
    font-weight: var(--font-weight-medium);
    color: var(--color-text-primary);
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  &__meta {
    font-size: var(--font-size-xs);
    color: var(--color-text-tertiary);
    margin-top: 2px;
  }

  &__del {
    opacity: 0;
    transition: opacity var(--duration-fast);
  }

  &:hover &__del {
    opacity: 1;
  }
}

// 编辑模式
.canvas-view__editor {
  height: 100%;
  display: flex;
  flex-direction: column;
  gap: var(--spacing-2);
}

.canvas-view__editor-header {
  display: flex;
  align-items: center;
  gap: var(--spacing-2);
}

.canvas-view__name-input {
  max-width: 320px;
}

.canvas-view__main {
  flex: 1;
  display: flex;
  gap: var(--spacing-2);
  min-height: 0;
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-md);
  overflow: hidden;
}

.canvas-palette {
  width: 120px;
  flex-shrink: 0;
  padding: var(--spacing-2);
  background: var(--color-surface);
  border-right: 1px solid var(--color-border-light);
  display: flex;
  flex-direction: column;
  gap: var(--spacing-1);

  &__title {
    font-size: var(--font-size-xs);
    color: var(--color-text-tertiary);
    padding: var(--spacing-1) var(--spacing-2);
    text-transform: uppercase;
  }

  &__item {
    display: flex;
    align-items: center;
    gap: var(--spacing-2);
    padding: var(--spacing-2);
    border-radius: var(--radius-base);
    color: var(--color-text-secondary);
    cursor: grab;
    font-size: var(--font-size-sm);
    transition: all var(--duration-instant) var(--ease-in-out);

    &:hover {
      background: var(--color-primary-light);
      color: var(--color-primary);
    }
  }
}
</style>
