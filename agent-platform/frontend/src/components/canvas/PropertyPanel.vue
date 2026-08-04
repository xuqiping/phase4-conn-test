<template>
  <aside class="prop-panel">
    <div class="prop-panel__title">属性</div>
    <div v-if="!node" class="prop-panel__empty">选中一个节点编辑其属性</div>
    <template v-else>
      <div class="prop-panel__field">
        <label>名称</label>
        <n-input v-model:value="node.data.label" size="small" placeholder="节点名" />
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
          @change="(opts: { file: { file: File | null } }) => onPickFile(opts)"
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
      </template>

      <!-- 音频节点：模式 -->
      <template v-else-if="node.type === 'audio'">
        <div class="prop-panel__field">
          <label>来源</label>
          <n-select v-model:value="(node.data.audioMode as string)" size="small" :options="audioModeOpts" />
        </div>
      </template>

      <!-- 脚本节点：剧本概要 -->
      <template v-else-if="node.type === 'script'">
        <div class="prop-panel__field">
          <label>剧本</label>
          <n-input
            v-model:value="(node.data.synopsis as string)"
            type="textarea"
            size="small"
            :autosize="{ minRows: 5, maxRows: 12 }"
            placeholder="剧本输入，C7 经 LlmGateway 拆分镜"
          />
        </div>
      </template>
    </template>
  </aside>
</template>

<script setup lang="ts">
import { NButton, NIcon, NInput, NInputNumber, NSelect, NUpload } from 'naive-ui'
import {
  CloudUploadOutline, PlayOutline, SparklesOutline
} from '@vicons/ionicons5'
import type { CanvasNode } from '@/types/canvas'

const props = defineProps<{
  /** 选中节点（数组中的真实引用，直编 data 即时反映到画布）。 */
  node: CanvasNode | null
  /** 该节点是否运行中（按钮 loading + 防重入）。 */
  running?: boolean
}>()

const emit = defineEmits<{
  (e: 'run', node: CanvasNode): void
  (e: 'upload', payload: { node: CanvasNode; file: File }): void
}>()

/** n-upload 文件选中回调：取真实 File 抛给父组件上传（不走 n-upload 默认 XHR）。 */
function onPickFile(opts: { file: { file: File | null } }) {
  const file = opts?.file?.file
  if (file && props.node) {
    emit('upload', { node: props.node, file })
  }
}

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
}
</style>
