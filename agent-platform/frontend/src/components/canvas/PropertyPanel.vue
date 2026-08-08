<template>
  <aside class="prop-panel">
    <div class="prop-panel__title">属性</div>
    <div v-if="!node" class="prop-panel__empty">选中一个节点编辑其属性</div>
    <template v-else>
      <div class="prop-panel__field">
        <label>名称</label>
        <!-- L9 重命名查重：失焦时若与同画布其他节点撞名，自动追加序号（占位符存 id 不受影响） -->
        <n-input v-model:value="(node.data.label as string)" size="small" placeholder="节点名" @blur="onRenameBlur" />
      </div>

      <!-- S12 资产库打通：入库 / 从库选择 / 已绑定徽标 + 检查更新（L5/L6，所有节点通用） -->
      <div class="prop-panel__field">
        <label>资产库</label>
        <div v-if="assetBound" class="prop-panel__asset-badge" :data-has-update="assetHasUpdate">
          来自资产 · {{ assetName }} v{{ assetVersion }}<template v-if="assetHasUpdate"> · 有新版</template>
        </div>
        <div class="prop-panel__row">
          <n-button size="tiny" tertiary @click="emit('save-to-asset', node)">存入资产库</n-button>
          <n-button size="tiny" tertiary @click="emit('pick-from-asset', node)">从库选择</n-button>
        </div>
        <div v-if="assetBound" class="prop-panel__row">
          <n-button size="tiny" tertiary @click="emit('check-update', node)">检查更新</n-button>
          <n-button
            size="tiny"
            tertiary
            type="primary"
            :disabled="!assetHasUpdate"
            @click="emit('update-asset', node)"
          >
            更新到最新版
          </n-button>
        </div>
      </div>

      <!-- 文本节点：提示词（S13 支持 @引用祖先节点产出） -->
      <template v-if="node.type === 'text'">
        <div class="prop-panel__field">
          <label>提示词</label>
          <MentionTextarea
            :model-value="(node.data.prompt as string) || ''"
            :candidates="candidates"
            :broken-mentions="brokenMentions"
            :rows="4"
            placeholder="文本节点提示词；输入 @ 引用上游节点产出"
            @update:model-value="(v: string) => { if (node) node.data.prompt = v }"
            @mention-click="onMentionClick"
          />
          <div v-if="brokenMentions.length" class="prop-panel__warn">
            断链引用：{{ brokenMentions.join(' ') }}（上游被删/断连，运行前请重连或移除）
          </div>
        </div>
        <div class="prop-panel__field">
          <label>模型</label>
          <n-select
            :value="(node.data.model as string) || null"
            :options="chatModelOptions"
            size="small"
            clearable
            placeholder="默认（后端回落）"
            @update:value="(v: string | null) => { if (node) { node.data.model = v ?? undefined; emit('data-changed') } }"
          />
        </div>
        <n-button
          size="small"
          type="primary"
          block
          :loading="running"
          :disabled="!(node.data.prompt as string)?.trim()"
          @click="emit('run', node)"
        >
          <template #icon><n-icon :component="PlayOutline" /></template>
          生成文本
        </n-button>
        <div v-if="(node.data.outputText as string)" class="prop-panel__output">
          <label>生成结果</label>
          <div class="prop-panel__output-text">{{ node.data.outputText }}</div>
        </div>
      </template>

      <!-- 图片节点：上传（MVP）/ AI 生图（R-3 待接入） -->
      <template v-else-if="node.type === 'image'">
        <n-upload
          :show-file-list="false"
          accept="image/*"
          @change="(opts) => onPickFile(opts)"
        >
          <n-button size="small" block :loading="running">
            <template #icon><n-icon :component="CloudUploadOutline" /></template>
            上传图片
          </n-button>
        </n-upload>
        <n-button size="small" block tertiary disabled title="生图 provider 未接入（R-3）">
          <template #icon><n-icon :component="SparklesOutline" /></template>
          AI 生图（待接入）
        </n-button>
        <n-button
          size="small"
          block
          tertiary
          :disabled="!node.data.fileId"
          @click="emit('focus-edit', node)"
        >
          <template #icon><n-icon :component="CropOutline" /></template>
          焦点编辑（框选提元素）
        </n-button>
        <div v-if="node.data.fileId" class="prop-panel__readonly">fileId: {{ node.data.fileId }}</div>
        <div v-if="(node.data.errorMsg as string)" class="prop-panel__error">{{ node.data.errorMsg }}</div>
      </template>

      <!-- 视频节点：prompt/比例/时长/分辨率（S13 prompt 支持 @引用） -->
      <template v-else-if="node.type === 'video'">
        <div class="prop-panel__field">
          <label>提示词</label>
          <MentionTextarea
            :model-value="(node.data.prompt as string) || ''"
            :candidates="candidates"
            :broken-mentions="brokenMentions"
            :rows="3"
            placeholder="视频生成 prompt；输入 @ 引用上游节点产出"
            @update:model-value="(v: string) => { if (node) node.data.prompt = v }"
            @mention-click="onMentionClick"
          />
          <div v-if="brokenMentions.length" class="prop-panel__warn">
            断链引用：{{ brokenMentions.join(' ') }}（上游被删/断连，运行前请重连或移除）
          </div>
        </div>
        <div class="prop-panel__row">
          <div class="prop-panel__field">
            <label>比例</label>
            <n-select v-model:value="(node.data.ratio as string)" size="small" :options="ratioOpts" />
          </div>
          <div class="prop-panel__field">
            <label>时长(秒)</label>
            <n-input-number v-model:value="(node.data.duration as number | undefined)" size="small" :min="4" :max="15" />
          </div>
        </div>
        <div class="prop-panel__field">
          <label>分辨率</label>
          <n-select v-model:value="(node.data.resolution as string)" size="small" :options="resOpts" />
        </div>
        <div class="prop-panel__field">
          <label>首帧（可选，@选上游图节点作开头）</label>
          <n-select
            :value="(node.data.firstFrameNodeId as string | null) ?? null"
            size="small"
            clearable
            :options="imageAncestorOptions"
            placeholder="不选 = 无首帧"
            @update:value="(v: string | null) => { if (node) { node.data.firstFrameNodeId = v ?? undefined; emit('data-changed') } }"
          />
        </div>
        <div class="prop-panel__field">
          <label>尾帧（可选，@选上游图节点作结尾）</label>
          <n-select
            :value="(node.data.lastFrameNodeId as string | null) ?? null"
            size="small"
            clearable
            :options="imageAncestorOptions"
            placeholder="不选 = 无尾帧"
            @update:value="(v: string | null) => { if (node) { node.data.lastFrameNodeId = v ?? undefined; emit('data-changed') } }"
          />
        </div>
        <div class="prop-panel__hint">
          提示词里 @ 的图节点作<b>参考图</b>（图N），不算首/尾帧；首/尾帧在此显式选。
        </div>
        <n-button
          size="small"
          type="primary"
          block
          :loading="running"
          :disabled="!(node.data.prompt as string)?.trim()"
          @click="emit('run', node)"
        >
          <template #icon><n-icon :component="PlayOutline" /></template>
          提交视频生成
        </n-button>
        <div class="prop-panel__field">
          <label>视频模型</label>
          <n-select
            :value="(node.data.model as string) || null"
            :options="videoModelOptions"
            size="small"
            clearable
            placeholder="默认（provider 首个视频模型）"
            @update:value="(v: string | null) => { if (node) { node.data.model = v ?? undefined; emit('data-changed') } }"
          />
        </div>

        <!-- C11 视频抽帧：首/尾/指定秒 → 新图节点（需 video 已生成，即 data.fileId 存在） -->
        <div class="prop-panel__field">
          <label>抽帧（C11，需已生成视频）</label>
          <div class="prop-panel__row">
            <n-button
              size="small"
              tertiary
              :disabled="!node.data.fileId"
              @click="emit('extract-frame', { node, mode: 'FIRST' })"
            >
              首帧
            </n-button>
            <n-button
              size="small"
              tertiary
              :disabled="!node.data.fileId"
              @click="emit('extract-frame', { node, mode: 'LAST' })"
            >
              尾帧
            </n-button>
          </div>
          <div class="prop-panel__row">
            <n-input-number
              v-model:value="frameSecond"
              size="small"
              :min="0"
              placeholder="秒"
              style="flex:1"
            />
            <n-button
              size="small"
              tertiary
              :disabled="!node.data.fileId || frameSecond == null"
              @click="emit('extract-frame', { node, mode: 'AT', second: frameSecond ?? undefined })"
            >
              第{{ frameSecond ?? 'N' }}秒
            </n-button>
          </div>
        </div>

        <!-- C12 视频截取：时间段 [起,止) 裁剪 → 新视频节点（需 video 已生成） -->
        <div class="prop-panel__field">
          <label>截取（C12，时间段裁剪）</label>
          <div class="prop-panel__row">
            <n-input-number
              v-model:value="clipStart"
              size="small"
              :min="0"
              placeholder="起(秒)"
              style="flex:1"
            />
            <n-input-number
              v-model:value="clipEnd"
              size="small"
              :min="0"
              placeholder="止(秒)"
              style="flex:1"
            />
          </div>
          <n-button
            size="small"
            block
            tertiary
            :disabled="!node.data.fileId || clipStart == null || clipEnd == null || (clipEnd ?? 0) <= (clipStart ?? 0)"
            @click="emit('clip-video', { node, startSec: clipStart ?? 0, endSec: clipEnd ?? 0 })"
          >
            <template #icon><n-icon :component="CropOutline" /></template>
            截取片段
          </n-button>
        </div>
        <div v-if="(node.data.errorMsg as string)" class="prop-panel__error">{{ node.data.errorMsg }}</div>
        <div v-if="node.data.taskId" class="prop-panel__readonly">taskId: {{ node.data.taskId }}</div>
      </template>

      <!-- 音频节点：上传（MVP）/ TTS / 音乐生成（待 provider） -->
      <template v-else-if="node.type === 'audio'">
        <div class="prop-panel__field">
          <label>来源</label>
          <n-select v-model:value="(node.data.audioMode as string)" size="small" :options="audioModeOpts" />
        </div>
        <n-upload
          v-if="(node.data.audioMode ?? 'upload') === 'upload'"
          :show-file-list="false"
          accept="audio/*"
          @change="(opts) => onPickFile(opts)"
        >
          <n-button size="small" block :loading="running">
            <template #icon><n-icon :component="CloudUploadOutline" /></template>
            上传音频
          </n-button>
        </n-upload>
        <n-button v-else size="small" block tertiary disabled title="TTS/音乐生成 provider 待接入">
          <template #icon><n-icon :component="SparklesOutline" /></template>
          {{ node.data.audioMode === 'tts' ? 'TTS 语音（待接入）' : '音乐生成（待接入）' }}
        </n-button>
        <div v-if="node.data.fileId" class="prop-panel__readonly">fileId: {{ node.data.fileId }}</div>
      </template>

      <!-- 分镜节点：脚本拆分镜后扇出生成，单条分镜画面描述（可编辑微调后下游生图/视频） -->
      <template v-else-if="node.type === 'storyboard'">
        <div v-if="(node.data.index as number)" class="prop-panel__readonly">分镜 {{ node.data.index }}</div>
        <div class="prop-panel__field">
          <label>分镜描述</label>
          <MentionTextarea
            :model-value="(node.data.description as string) || ''"
            :candidates="candidates"
            :broken-mentions="brokenMentions"
            :rows="5"
            placeholder="分镜画面描述；下游图/视频节点 @本节点即注入此描述"
            @update:model-value="(v: string) => { if (node) node.data.description = v }"
            @mention-click="onMentionClick"
          />
          <div v-if="brokenMentions.length" class="prop-panel__warn">
            断链引用：{{ brokenMentions.join(' ') }}（上游被删/断连）
          </div>
        </div>
      </template>

      <!-- 脚本节点：剧本 → LLM 拆分镜（S13 剧本支持 @引用上游产出） -->
      <template v-else-if="node.type === 'script'">
        <div class="prop-panel__field">
          <label>剧本</label>
          <MentionTextarea
            :model-value="(node.data.synopsis as string) || ''"
            :candidates="candidates"
            :broken-mentions="brokenMentions"
            :rows="5"
            placeholder="剧本输入；输入 @ 引用上游节点产出，经 LlmGateway 拆分镜"
            @update:model-value="(v: string) => { if (node) node.data.synopsis = v }"
            @mention-click="onMentionClick"
          />
          <div v-if="brokenMentions.length" class="prop-panel__warn">
            断链引用：{{ brokenMentions.join(' ') }}（上游被删/断连，运行前请重连或移除）
          </div>
        </div>
        <div class="prop-panel__field">
          <label>拆分段数 <span class="prop-panel__inline-hint">留空由大模型自定</span></label>
          <n-input-number
            :value="(node.data.segmentCount as number | null) ?? null"
            :min="1"
            :max="20"
            size="small"
            placeholder="如 6（留空=大模型自定）"
            clearable
            @update:value="(v: number | null) => { if (node) { node.data.segmentCount = v ?? undefined; emit('data-changed') } }"
          />
        </div>
        <div class="prop-panel__field">
          <label>拆分规范 <span class="prop-panel__inline-hint">留空则大模型自由发挥</span></label>
          <MentionTextarea
            :model-value="(node.data.storyboardSpec as string) || ''"
            :candidates="candidates"
            :broken-mentions="brokenMentions"
            :rows="2"
            placeholder="如：每镜含景别+运镜；留空则大模型自由发挥"
            @update:model-value="(v: string) => { if (node) node.data.storyboardSpec = v }"
            @mention-click="onMentionClick"
          />
        </div>
        <div class="prop-panel__field">
          <label>模型</label>
          <n-select
            :value="(node.data.model as string) || null"
            :options="chatModelOptions"
            size="small"
            clearable
            placeholder="默认（后端回落）"
            @update:value="(v: string | null) => { if (node) { node.data.model = v ?? undefined; emit('data-changed') } }"
          />
        </div>
        <n-button
          size="small"
          type="primary"
          block
          :loading="running"
          :disabled="!(node.data.synopsis as string)?.trim()"
          @click="emit('split-storyboard', node)"
        >
          <template #icon><n-icon :component="PlayOutline" /></template>
          拆分镜
        </n-button>
        <div v-if="(node.data.errorMsg as string)" class="prop-panel__error">{{ node.data.errorMsg }}</div>
        <div v-if="sceneCount" class="prop-panel__readonly">已拆 {{ sceneCount }} 分镜</div>
      </template>
    </template>
  </aside>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { NButton, NIcon, NInput, NInputNumber, NSelect, NUpload } from 'naive-ui'
import {
  CloudUploadOutline, CropOutline, PlayOutline, SparklesOutline
} from '@vicons/ionicons5'
import type { CanvasNode, MentionCandidate } from '@/types/canvas'
import type { FrameMode } from '@/api/canvas'
import { llmApi } from '@/api/llm'
import type { AvailableModel } from '@/api/llm'
import MentionTextarea from './MentionTextarea.vue'
import { uniqueLabel } from '@/utils/interpolate'

const props = withDefaults(defineProps<{
  /** 选中节点（数组中的真实引用，直编 data 即时反映到画布）。 */
  node: CanvasNode | null
  /** 该节点是否运行中（按钮 loading + 防重入）。 */
  running?: boolean
  /** S13：@选择器候选（当前节点的祖先节点集；无连线可达则空）。 */
  candidates?: MentionCandidate[]
  /** S13：当前节点文本中的断链占位符（上游被删/断连），用于灰显提示 L7/L8。 */
  brokenMentions?: string[]
  /** S13：同画布全部节点 label（重命名查重用，L9 三入口之一）。 */
  allLabels?: string[]
  /** F3：祖先图节点选项（首/尾帧@选择器用，{label,value=nodeId}）。 */
  imageAncestorOptions?: { label: string; value: string }[]
}>(), {
  candidates: () => [],
  brokenMentions: () => [],
  allLabels: () => [],
  imageAncestorOptions: () => []
})

const emit = defineEmits<{
  (e: 'run', node: CanvasNode): void
  /** 脚本节点拆分镜专用：后端拆 scenes → 前端扇出生成分镜子节点（替代脚本走通用 run）。 */
  (e: 'split-storyboard', node: CanvasNode): void
  (e: 'upload', payload: { node: CanvasNode; file: File }): void
  (e: 'focus-edit', node: CanvasNode): void
  (e: 'extract-frame', payload: { node: CanvasNode; mode: FrameMode; second?: number }): void
  (e: 'clip-video', payload: { node: CanvasNode; startSec: number; endSec: number }): void
  /** S12：存入资产库（开 SaveToAssetDialog，L5）。 */
  (e: 'save-to-asset', node: CanvasNode): void
  /** S12：从库选择（开 AssetPicker，L6）。 */
  (e: 'pick-from-asset', node: CanvasNode): void
  /** S12：检查资产是否有新版（asset.get 比对版本）。 */
  (e: 'check-update', node: CanvasNode): void
  /** S12：更新节点引用到资产最新版（re-resolve 写回，L6「手动更新」）。 */
  (e: 'update-asset', node: CanvasNode): void
  /** C5/FR-006：node.data 被面板改动需落库（模型选择器等离散选择），父组件 scheduleSave。 */
  (e: 'data-changed'): void
  /** A1 增强：提示词里 @chip 被点击 → 跳转聚焦被引用节点（CanvasView 居中选中该节点）。 */
  (e: 'mention-focus', payload: { kind: string; id: string }): void
}>()

/** S12：当前节点已绑定资产（node.data.assetId 存在）。 */
const assetBound = computed(() => props.node?.data.assetId != null)
const assetName = computed(() => (props.node?.data.assetName as string | undefined) ?? '资产')
const assetVersion = computed(() => (props.node?.data.assetVersion as number | undefined) ?? 1)
const assetHasUpdate = computed(() => Boolean(props.node?.data.assetHasUpdate))

/** C11 抽帧「指定秒」输入值（AT 模式用）。 */
const frameSecond = ref<number | null>(null)

/** C12 截取起止秒输入值。 */
const clipStart = ref<number | null>(null)
const clipEnd = ref<number | null>(null)

/** n-upload 文件选中回调：取真实 File 抛给父组件上传（不走 n-upload 默认 XHR）。 */
function onPickFile(opts: { file?: { file?: File | null } } | undefined) {
  const file = opts?.file?.file
  if (file && props.node) {
    emit('upload', { node: props.node, file })
  }
}

/**
 * L9 重命名查重：失焦时若新 label 与同画布其他节点撞名，自动追加序号。
 * 契约：父组件传入的 allLabels **已剔除当前节点**（按节点 id 剔除，非按值——
 * 否则另一节点的同名也会被误剔导致查重漏判）。
 */
function onRenameBlur() {
  const node = props.node
  if (!node) return
  const label = (node.data.label as string | undefined)?.trim()
  if (!label) return
  const deduped = uniqueLabel(label, props.allLabels)
  if (deduped !== node.data.label) {
    node.data.label = deduped
  }
}

/** A1：提示词 @chip 被点击 → 上抛 mention-focus，CanvasView 居中选中被引用节点。 */
function onMentionClick(payload: { kind: string; id: string }) {
  emit('mention-focus', payload)
}

/** 脚本节点已拆分镜数（属性面板回显）。 */
const sceneCount = computed(() =>
  Array.isArray(props.node?.data.scenes) ? (props.node!.data.scenes as unknown[]).length : 0
)

const ratioOpts = ['16:9', '9:16', '1:1', '4:3', '3:4', '21:9'].map(v => ({ label: v, value: v }))
const resOpts = ['480p', '720p', '1080p', '4K'].map(v => ({ label: v, value: v }))
const audioModeOpts = [
  { label: '上传', value: 'upload' },
  { label: 'TTS 语音', value: 'tts' },
  { label: '音乐生成', value: 'music' }
]

// ---------- C5 节点选模型（text/script=chat 模型；video=MEDIA 视频模型） ----------
const chatModels = ref<AvailableModel[]>([])
const videoModels = ref<AvailableModel[]>([])
onMounted(async () => {
  try {
    const [c, v] = await Promise.all([llmApi.listAvailableModels(), llmApi.listVideoModels()])
    chatModels.value = c.data.data ?? []
    videoModels.value = v.data.data ?? []
  } catch {
    // 模型列表可选，失败静默（下拉空态不崩）
  }
})
/** 按 providerName 分组（与 chat ModelSelector 同范式）。 */
function groupModels(list: AvailableModel[]) {
  const grouped = new Map<string, { type: 'group'; label: string; key: string; children: { label: string; value: string }[] }>()
  for (const m of list) {
    if (!grouped.has(m.providerName)) {
      grouped.set(m.providerName, { type: 'group', label: m.providerName, key: m.providerName, children: [] })
    }
    grouped.get(m.providerName)!.children.push({ label: m.displayName, value: m.modelId })
  }
  return Array.from(grouped.values())
}
const chatModelOptions = computed(() => groupModels(chatModels.value))
const videoModelOptions = computed(() => groupModels(videoModels.value))
</script>

<style lang="scss" scoped>
.prop-panel {
  width: 260px;
  flex-shrink: 0;
  padding: var(--spacing-2);
  background: var(--color-surface);
  border-left: 1px solid var(--color-border-light);
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: var(--spacing-2);

  &__title {
    font-size: var(--font-size-xs);
    color: var(--color-text-tertiary);
    text-transform: uppercase;
    padding: var(--spacing-1) var(--spacing-2);
  }

  &__empty {
    color: var(--color-text-tertiary);
    font-size: var(--font-size-sm);
    padding: var(--spacing-3);
    text-align: center;
  }

  &__field {
    display: flex;
    flex-direction: column;
    gap: var(--spacing-1);

    label {
      font-size: var(--font-size-xs);
      color: var(--color-text-secondary);
    }
  }

  &__row {
    display: flex;
    gap: var(--spacing-2);
  }

  &__hint,
  &__readonly {
    font-size: var(--font-size-xs);
    color: var(--color-text-tertiary);
    padding: var(--spacing-2);
    background: var(--color-bg);
    border-radius: var(--radius-base);
    word-break: break-all;
  }

  &__inline-hint {
    margin-left: var(--spacing-1);
    font-size: var(--font-size-xs);
    color: var(--color-text-tertiary);
    font-weight: normal;
  }

  &__output {
    display: flex;
    flex-direction: column;
    gap: var(--spacing-1);
  }

  &__output-text {
    font-size: var(--font-size-xs);
    color: var(--color-text-secondary);
    padding: var(--spacing-2);
    background: var(--color-bg);
    border-radius: var(--radius-base);
    max-height: 160px;
    overflow-y: auto;
    white-space: pre-wrap;
    line-height: 1.5;
  }

  &__error {
    font-size: var(--font-size-xs);
    color: #f87171;
    padding: var(--spacing-2);
    background: rgba(239, 68, 68, 0.08);
    border-radius: var(--radius-base);
    word-break: break-all;
  }

  &__warn {
    font-size: var(--font-size-xs);
    color: #facc15;
    padding: var(--spacing-1) var(--spacing-2);
    background: rgba(250, 204, 21, 0.1);
    border-radius: var(--radius-base);
    word-break: break-all;
  }

  &__asset-badge {
    font-size: var(--font-size-xs);
    color: var(--color-primary);
    padding: var(--spacing-1) var(--spacing-2);
    background: rgba(var(--color-primary-rgb), 0.12);
    border-radius: var(--radius-base);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;

    &[data-has-update='true'] {
      color: #facc15;
      background: rgba(250, 204, 21, 0.14);
    }
  }
}
</style>
