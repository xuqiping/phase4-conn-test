<template>
  <aside class="prop-panel">
    <div class="prop-panel__title">属性</div>
    <div v-if="!node" class="prop-panel__empty">选中一个节点编辑其属性</div>
    <template v-else>
      <div class="prop-panel__field">
        <label>名称</label>
        <n-input v-model:value="node.data.label" size="small" placeholder="节点名" />
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

      <!-- 文本节点：提示词 -->
      <template v-if="node.type === 'text'">
        <div class="prop-panel__field">
          <label>提示词</label>
          <n-input
            v-model:value="(node.data.prompt as string)"
            type="textarea"
            size="small"
            :autosize="{ minRows: 4, maxRows: 10 }"
            placeholder="文本节点提示词，可拉线触发下游生成"
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

      <!-- 视频节点：prompt/比例/时长/分辨率 -->
      <template v-else-if="node.type === 'video'">
        <div class="prop-panel__field">
          <label>提示词</label>
          <n-input
            v-model:value="(node.data.prompt as string)"
            type="textarea"
            size="small"
            :autosize="{ minRows: 3, maxRows: 8 }"
            placeholder="视频生成 prompt"
          />
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
          <label>参考图（图生视频；C8 连线上游图节点自动填）</label>
          <n-input
            :value="(node.data.refFileId as string) || ''"
            size="small"
            placeholder="留空 = 文生视频"
            @update:value="(v: string) => { if (node) node.data.refFileId = v.trim() || undefined }"
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
          提交视频生成
        </n-button>

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

      <!-- 脚本节点：剧本 → LLM 拆分镜 -->
      <template v-else-if="node.type === 'script'">
        <div class="prop-panel__field">
          <label>剧本</label>
          <n-input
            v-model:value="(node.data.synopsis as string)"
            type="textarea"
            size="small"
            :autosize="{ minRows: 5, maxRows: 12 }"
            placeholder="剧本输入，经 LlmGateway 拆分镜"
          />
        </div>
        <n-button
          size="small"
          type="primary"
          block
          :loading="running"
          :disabled="!(node.data.synopsis as string)?.trim()"
          @click="emit('run', node)"
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
import { computed, ref } from 'vue'
import { NButton, NIcon, NInput, NInputNumber, NSelect, NUpload } from 'naive-ui'
import {
  CloudUploadOutline, CropOutline, PlayOutline, SparklesOutline
} from '@vicons/ionicons5'
import type { CanvasNode } from '@/types/canvas'
import type { FrameMode } from '@/api/canvas'

const props = defineProps<{
  /** 选中节点（数组中的真实引用，直编 data 即时反映到画布）。 */
  node: CanvasNode | null
  /** 该节点是否运行中（按钮 loading + 防重入）。 */
  running?: boolean
}>()

const emit = defineEmits<{
  (e: 'run', node: CanvasNode): void
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
