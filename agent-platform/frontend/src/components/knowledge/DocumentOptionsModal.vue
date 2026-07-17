<template>
  <n-modal
    :show="show"
    preset="card"
    :title="`上传选项：${fileName}`"
    style="max-width: 560px"
    :close-on-esc="!loading"
    :mask-closable="!loading"
    @update:show="onUpdateShow"
  >
    <n-space vertical :size="16">
      <div>
        <p class="dom__label">文档类型</p>
        <n-select v-model:value="form.docType" :options="docTypeOptions" :disabled="loading" />
      </div>

      <div>
        <p class="dom__label">索引方式</p>
        <n-radio-group v-model:value="form.indexMode" :disabled="loading">
          <n-space>
            <n-radio value="AUTO">AUTO 自动抽取</n-radio>
            <n-radio value="MANUAL">MANUAL 手动给索引文本</n-radio>
          </n-space>
        </n-radio-group>
        <p class="dom__hint">
          <span v-if="form.indexMode === 'AUTO'">
            {{ autoHint }}
          </span>
          <span v-else>
            用你填的文字建索引向量化；原件仍保留供回显。
          </span>
        </p>
      </div>

      <div v-if="form.indexMode === 'MANUAL'">
        <p class="dom__label">索引文本<span class="dom__req">*</span>（≤4000 字，检索命中靠它）</p>
        <n-input
          v-model:value="form.manualIndexText"
          type="textarea"
          :rows="5"
          :maxlength="4000"
          show-count
          placeholder="例：这张图是产品架构图，包含接入层 / 服务层 / 数据层……"
          :disabled="loading"
        />
      </div>

      <div v-if="form.docType === 'IMAGE' && form.indexMode === 'AUTO'">
        <p class="dom__label">视觉模型<span class="dom__req">*</span>（识图生成索引文本）</p>
        <ModelSelector v-model="form.visionModel" />
        <p class="dom__hint">选支持视觉的对话模型（如 glm-5.1）；embedding 模型不可识图。</p>
      </div>

      <div v-if="sheetNames.length > 0">
        <p class="dom__label">导入 Sheet（Excel）</p>
        <n-checkbox-group v-model:value="form.selectedSheets" :disabled="loading">
          <n-space>
            <n-checkbox v-for="s in sheetNames" :key="s" :value="s" :label="s" />
          </n-space>
        </n-checkbox-group>
        <p class="dom__hint">不勾选 = 导入全部 sheet。</p>
      </div>
    </n-space>

    <template #footer>
      <n-space justify="end">
        <n-button :disabled="loading" @click="emit('cancel')">取消</n-button>
        <n-button type="primary" :loading="loading" :disabled="!canConfirm" @click="onConfirm">
          确认上传
        </n-button>
      </n-space>
    </template>
  </n-modal>
</template>

<script setup lang="ts">
import { computed, reactive, watch } from 'vue'
import { NButton, NCheckbox, NCheckboxGroup, NInput, NModal, NRadio, NRadioGroup, NSelect, NSpace } from 'naive-ui'
import type { UploadOptions } from '@/api/knowledge'
import ModelSelector from '@/components/chat/ModelSelector.vue'

const props = defineProps<{
  show: boolean
  fileName: string
  /** Excel 预读的 sheet 名（非 Excel 为空数组 → 不显勾选区） */
  sheetNames: string[]
  loading: boolean
}>()

const emit = defineEmits<{
  (e: 'confirm', payload: UploadOptions & { selectedSheets: string[] }): void
  (e: 'cancel'): void
}>()

const docTypeOptions = [
  { label: '文本（md/txt）', value: 'TEXT' },
  { label: 'PDF', value: 'PDF' },
  { label: 'DOCX', value: 'DOCX' },
  { label: 'HTML', value: 'HTML' },
  { label: 'EXCEL', value: 'EXCEL' },
  { label: '图片（IMAGE）', value: 'IMAGE' },
  { label: '文件（FILE）', value: 'FILE' }
]

const form = reactive({
  docType: 'TEXT',
  indexMode: 'AUTO' as 'AUTO' | 'MANUAL',
  manualIndexText: '',
  visionModel: '',
  selectedSheets: [] as string[]
})

/** 按文件名推断 docType（与后端 resolveDocType 同款），每次开弹窗重算。 */
function inferDocType(name: string): string {
  const n = (name || '').toLowerCase()
  if (/\.(png|jpe?g|gif|webp|bmp)$/.test(n)) return 'IMAGE'
  if (/\.(xlsx|xls)$/.test(n)) return 'EXCEL'
  if (n.endsWith('.pdf')) return 'PDF'
  if (/\.(docx|doc)$/.test(n)) return 'DOCX'
  if (/\.(html?|htm)$/.test(n)) return 'HTML'
  if (/\.(md|markdown|txt)$/.test(n)) return 'TEXT'
  return 'FILE'
}

const autoHint = computed(() => {
  switch (form.docType) {
    case 'IMAGE': return '视觉模型识图生成索引文本 → 向量化。'
    case 'FILE': return 'Tika 自动抽取正文（pdf/docx/html/txt 等）。'
    case 'EXCEL': return '每数据行 → 1 个索引节点（POI）。'
    default: return 'Tika 自动抽取正文。'
  }
})

const canConfirm = computed(() => {
  if (form.indexMode === 'MANUAL') {
    return form.manualIndexText.trim().length > 0
  }
  // AUTO：IMAGE+AUTO 需选视觉模型
  if (form.docType === 'IMAGE') {
    return form.visionModel.trim().length > 0
  }
  return true
})

watch(
  () => props.show,
  (v) => {
    if (v) {
      form.docType = inferDocType(props.fileName)
      form.indexMode = 'AUTO'
      form.manualIndexText = ''
      form.visionModel = ''
      form.selectedSheets = []
    }
  }
)

function onConfirm() {
  if (!canConfirm.value) return
  const opts: UploadOptions = { docType: form.docType, indexMode: form.indexMode }
  if (form.indexMode === 'MANUAL') opts.manualIndexText = form.manualIndexText.trim()
  if (form.visionModel.trim()) opts.visionModel = form.visionModel.trim()
  emit('confirm', { ...opts, selectedSheets: form.selectedSheets })
}

function onUpdateShow(v: boolean) {
  if (!v && !props.loading) emit('cancel')
}
</script>

<style lang="scss" scoped>
.dom__label {
  margin: 0 0 6px;
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-secondary);
}
.dom__req {
  color: var(--color-danger);
  margin-left: 2px;
}
.dom__hint {
  margin: 4px 0 0;
  font-size: var(--font-size-xs);
  color: var(--color-text-tertiary);
}
</style>
