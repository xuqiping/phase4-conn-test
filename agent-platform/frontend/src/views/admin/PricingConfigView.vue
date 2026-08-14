<template>
  <div class="pricing-config">
    <div v-if="!canManage" class="pricing-config__noperm"><n-empty description="无 pricing:manage 权限" /></div>
    <template v-else>
      <n-card title="模型价表">
        <template #header-extra>
          <n-space>
            <n-button @click="handleExport" :loading="exporting">导出</n-button>
            <n-button @click="handleDownloadTemplate" :loading="downloadingTemplate">下载模板</n-button>
            <n-button @click="triggerImport" :loading="importing">导入</n-button>
            <n-button type="primary" @click="openPricingModal()">新增价表</n-button>
          </n-space>
          <input
            ref="importFileInput"
            type="file"
            accept=".json,application/json"
            style="display: none"
            @change="onImportFileChange"
          />
        </template>
        <n-data-table :columns="pricingColumns" :data="pricingRules" :loading="loading" size="small" />
      </n-card>

      <n-card title="阶梯比例（¥ 区间 → 积分比）" style="margin-top: 16px">
        <template #header-extra>
          <n-button type="primary" @click="openRatioModal()">新增阶梯</n-button>
        </template>
        <n-data-table :columns="ratioColumns" :data="ratioTiers" :loading="loading" size="small" />
        <p class="pricing-config__hint">
          区间 [min, max)，max 空 = ∞；改价写新行不覆盖旧行（effective_from 生效，历史流水不动）。
        </p>
      </n-card>
    </template>

    <!-- 价表表单 -->
    <n-modal v-model:show="pricingShow" preset="card" title="价表" style="width: 560px">
      <n-form ref="pricingFormRef" :model="pricingForm" label-placement="left" :label-width="150">
        <template v-if="pricingEditId == null">
          <n-form-item label="全局模型" path="model">
            <n-select
              v-model:value="selectedCandidateKey"
              :options="candidateOptions"
              :loading="candidateLoading"
              filterable
              clearable
              placeholder="搜索供应商或模型"
              @update:value="onCandidateChange"
            />
          </n-form-item>
          <n-alert v-if="candidateError" type="error" :show-icon="false" class="pricing-config__identity-note">
            {{ candidateError }}，请关闭后重试。
          </n-alert>
          <n-alert
            v-else-if="!candidateLoading && availableModels.length === 0"
            type="info"
            :show-icon="false"
            class="pricing-config__identity-note"
          >
            所有全局模型均已配置价表。
          </n-alert>
        </template>
        <template v-else>
          <n-alert type="info" :show-icon="false" class="pricing-config__identity-note">
            模型身份已锁定。编辑时只能调整价格、计费模式和生效参数。
          </n-alert>
          <n-form-item label="类型 kind" path="kind">
            <n-select v-model:value="pricingForm.kind" :options="kindOptions" disabled />
          </n-form-item>
          <n-form-item label="providerId">
            <n-input-number v-model:value="pricingForm.providerId" disabled />
          </n-form-item>
          <n-form-item label="model">
            <n-input v-model:value="pricingForm.model" disabled />
          </n-form-item>
        </template>
        <n-form-item label="输入价 ¥/百万" v-if="pricingForm.kind === 'CHAT' || pricingForm.kind === 'EMBED' || pricingForm.kind === 'RERANK'"><n-input-number v-model:value="pricingForm.priceInputPerMillion" :precision="6" /></n-form-item>
        <n-form-item label="输出价 ¥/百万" v-if="pricingForm.kind === 'CHAT'"><n-input-number v-model:value="pricingForm.priceOutputPerMillion" :precision="6" /></n-form-item>
        <n-form-item label="视频计费模式" v-if="pricingForm.kind === 'VIDEO'">
          <n-select v-model:value="pricingForm.videoBillingMode" :options="modeOptions" />
        </n-form-item>
        <n-form-item label="是否含参考视频" v-if="pricingForm.kind === 'VIDEO'">
          <n-switch v-model:value="pricingForm.hasReference" />
          <span class="pricing-config__hint" style="margin-left: 8px">同一视频模型可分别配「无参考」和「有参考」两行价</span>
        </n-form-item>
        <n-form-item label="视频秒价 ¥" v-if="pricingForm.kind === 'VIDEO'"><n-input-number v-model:value="pricingForm.pricePerSecond" :precision="6" /></n-form-item>
        <n-form-item label="图片单价 ¥" v-if="pricingForm.kind === 'IMAGE'"><n-input-number v-model:value="pricingForm.pricePerImage" :precision="6" /></n-form-item>
      </n-form>
      <template #footer>
        <n-space justify="end">
          <n-button @click="pricingShow = false">取消</n-button>
          <n-button type="primary" :loading="saving" @click="savePricing">保存</n-button>
        </n-space>
      </template>
    </n-modal>

    <!-- 阶梯表单 -->
    <n-modal v-model:show="ratioShow" preset="card" title="阶梯比例" style="width: 480px">
      <n-form :model="ratioForm" label-placement="left" :label-width="120">
        <n-form-item label="区间下限 ¥"><n-input-number v-model:value="ratioForm.minAmount" :precision="6" /></n-form-item>
        <n-form-item label="区间上限 ¥"><n-input-number v-model:value="ratioForm.maxAmount" :precision="6" placeholder="空=∞" /></n-form-item>
        <n-form-item label="积分比"><n-input-number v-model:value="ratioForm.ratio" :precision="6" /></n-form-item>
      </n-form>
      <template #footer>
        <n-space justify="end">
          <n-button @click="ratioShow = false">取消</n-button>
          <n-button type="primary" :loading="saving" @click="saveRatio">保存</n-button>
        </n-space>
      </template>
    </n-modal>
  </div>
</template>

<script setup lang="ts">
import { computed, h, onMounted, reactive, ref } from 'vue'
import {
  NAlert, NCard, NDataTable, NButton, NModal, NForm, NFormItem, NInput, NInputNumber, NSelect, NSpace, NPopconfirm, NEmpty, useMessage, useDialog
} from 'naive-ui'
import type { DataTableColumns } from 'naive-ui'
import { billingApi, KIND_LABEL } from '@/api/billing'
import type { AvailablePricingModelVO, PricingRuleVO, PricingRuleRequest, PricingRuleExportItem, RatioTierVO, RatioTierRequest, BillingKind, VideoBillingMode } from '@/api/billing'
import { useAuthStore } from '@/stores/auth'

const authStore = useAuthStore()
const message = useMessage()
const dialog = useDialog()
const canManage = computed(() => authStore.hasPermission('pricing:manage'))

const pricingRules = ref<PricingRuleVO[]>([])
const ratioTiers = ref<RatioTierVO[]>([])
const loading = ref(false)
const saving = ref(false)

const kindOptions = (Object.keys(KIND_LABEL) as BillingKind[]).map(k => ({ label: KIND_LABEL[k], value: k }))
const modeOptions: { label: string; value: VideoBillingMode }[] = [
  { label: 'TOKEN（按 token）', value: 'TOKEN' },
  { label: 'SECOND（按秒）', value: 'SECOND' }
]

const pricingColumns: DataTableColumns<PricingRuleVO> = [
  { title: '类型', key: 'kind', render: r => KIND_LABEL[r.kind] ?? r.kind },
  { title: 'providerId', key: 'providerId', render: r => r.providerId == null ? '全局' : String(r.providerId) },
  { title: 'model', key: 'model', render: r => r.model ?? '—' },
  { title: '参考视频', key: 'hasReference', render: r => r.kind === 'VIDEO' ? (r.hasReference ? '有参考' : '无参考') : '—' },
  { title: '输入价 ¥/百万', key: 'priceInputPerMillion', render: r => fmt(r.priceInputPerMillion) },
  { title: '输出价 ¥/百万', key: 'priceOutputPerMillion', render: r => fmt(r.priceOutputPerMillion) },
  { title: '视频秒价 ¥', key: 'pricePerSecond', render: r => fmt(r.pricePerSecond) },
  { title: '图片单价 ¥', key: 'pricePerImage', render: r => fmt(r.pricePerImage) },
  { title: '生效时间', key: 'effectiveFrom', render: r => new Date(r.effectiveFrom).toLocaleString('zh-CN', { hour12: false }) },
  { title: '操作', key: 'op', render: r => h(NButton, { size: 'small', text: true, type: 'primary', onClick: () => openPricingModal(r) }, { default: () => '编辑' }) }
]

const ratioColumns: DataTableColumns<RatioTierVO> = [
  { title: '区间', key: 'minAmount', render: r => `[${fmt(r.minAmount)}, ${r.maxAmount == null ? '∞' : fmt(r.maxAmount)})` },
  { title: '积分比', key: 'ratio', render: r => fmt(r.ratio) },
  { title: '生效时间', key: 'effectiveFrom', render: r => new Date(r.effectiveFrom).toLocaleString('zh-CN', { hour12: false }) },
  {
    title: '操作', key: 'op',
    render: r => h(NSpace, {}, {
      default: () => [
        h(NButton, { size: 'small', text: true, type: 'primary', onClick: () => openRatioModal(r) }, { default: () => '编辑' }),
        h(NPopconfirm, { onPositiveClick: () => removeRatio(r.id) }, {
          trigger: () => h(NButton, { size: 'small', text: true, type: 'error' }, { default: () => '删除' }),
          default: () => '确认删除该阶梯？'
        })
      ]
    })
  }
]

function fmt(n: number | null | undefined): string {
  return n == null ? '—' : String(n)
}

// 价表表单
const pricingShow = ref(false)
const pricingEditId = ref<number | null>(null)
const pricingForm = reactive<PricingRuleRequest>({ kind: 'CHAT', providerId: null, model: null, priceInputPerMillion: null, priceOutputPerMillion: null, videoBillingMode: 'TOKEN', pricePerSecond: null, pricePerImage: null, hasReference: false })
const availableModels = ref<AvailablePricingModelVO[]>([])
const candidateLoading = ref(false)
const candidateError = ref('')
const selectedCandidateKey = ref<string | null>(null)
const candidateOptions = computed(() => availableModels.value.map(candidate => ({
  label: `${candidate.providerName} · ${candidate.model} · ${KIND_LABEL[candidate.kind]}`,
  value: candidateKey(candidate)
})))

function candidateKey(candidate: Pick<AvailablePricingModelVO, 'providerId' | 'model'>): string {
  return `${candidate.providerId}\u0000${candidate.model}`
}

async function loadAvailableModels() {
  candidateLoading.value = true
  candidateError.value = ''
  try {
    const response = await billingApi.availablePricingModels()
    availableModels.value = response.data.data ?? []
  } catch {
    availableModels.value = []
    candidateError.value = '全局模型候选加载失败'
  } finally {
    candidateLoading.value = false
  }
}

function onCandidateChange(value: string | null) {
  selectedCandidateKey.value = value
  const candidate = availableModels.value.find(item => candidateKey(item) === value)
  Object.assign(pricingForm, candidate
    ? { providerId: candidate.providerId, model: candidate.model, kind: candidate.kind }
    : { providerId: null, model: null, kind: 'CHAT' as BillingKind })
}

async function openPricingModal(rule?: PricingRuleVO) {
  if (rule) {
    pricingEditId.value = rule.id
    Object.assign(pricingForm, {
      kind: rule.kind, providerId: rule.providerId, model: rule.model,
      priceInputPerMillion: rule.priceInputPerMillion, priceOutputPerMillion: rule.priceOutputPerMillion,
      videoBillingMode: rule.videoBillingMode ?? 'TOKEN', pricePerSecond: rule.pricePerSecond, pricePerImage: rule.pricePerImage,
      hasReference: rule.hasReference ?? false
    })
  } else {
    pricingEditId.value = null
    selectedCandidateKey.value = null
    Object.assign(pricingForm, { kind: 'CHAT', providerId: null, model: null, priceInputPerMillion: null, priceOutputPerMillion: null, videoBillingMode: 'TOKEN', pricePerSecond: null, pricePerImage: null, hasReference: false })
  }
  pricingShow.value = true
  if (!rule) await loadAvailableModels()
}

/**
 * 按 kind 归一化价表 payload：与本 kind 无关的字段一律清空，避免泄漏默认值污染存量行
 * （7x-1 修复：非 VIDEO 行不应写 videoBillingMode='TOKEN'；非 IMAGE 不应写 pricePerImage 等）。
 */
function sanitizePricingPayload(form: PricingRuleRequest): PricingRuleRequest {
  const out: PricingRuleRequest = { ...form }
  const k = out.kind
  // 非文本/embed：清掉 token 价
  if (k !== 'CHAT' && k !== 'EMBED' && k !== 'RERANK') {
    out.priceInputPerMillion = null
    out.priceOutputPerMillion = null
  }
  // 非图片：清掉按张价
  if (k !== 'IMAGE') out.pricePerImage = null
  // 非视频：清掉视频专属字段，has_reference 强制 false
  if (k !== 'VIDEO') {
    out.videoBillingMode = null
    out.pricePerSecond = null
    out.hasReference = false
  } else {
    out.hasReference = out.hasReference === true
  }
  return out
}

async function savePricing() {
  if (pricingEditId.value == null && selectedCandidateKey.value == null) {
    message.error('请先选择一个未配置的全局模型')
    return
  }
  saving.value = true
  try {
    const payload = sanitizePricingPayload(pricingForm)
    if (pricingEditId.value == null) {
      await billingApi.createPricingRule(payload)
    } else {
      await billingApi.updatePricingRule(pricingEditId.value, payload)
    }
    message.success('价表已保存')
    pricingShow.value = false
    await load()
  } catch {
    /* 拦截器已 toast */
  } finally {
    saving.value = false
  }
}

// ---------------- 7x-2：价表导出 / 导入 / 模板 ----------------
const exporting = ref(false)
const downloadingTemplate = ref(false)
const importing = ref(false)
const importFileInput = ref<HTMLInputElement | null>(null)

async function handleExport() {
  dialog.warning({
    title: '导出价表',
    content: '将导出当前全部价表为 JSON 文件，可用于备份或迁移。是否继续？',
    positiveText: '导出',
    negativeText: '取消',
    onPositiveClick: doExport
  })
}

async function doExport() {
  exporting.value = true
  try {
    const resp = await billingApi.exportPricingRules()
    const blob = resp.data as unknown as Blob
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `pricing-rules-${new Date().toISOString().slice(0, 10)}.json`
    document.body.appendChild(a)
    a.click()
    document.body.removeChild(a)
    URL.revokeObjectURL(url)
    message.success('价表已导出')
  } catch {
    /* 拦截器已 toast */
  } finally {
    exporting.value = false
  }
}

async function handleDownloadTemplate() {
  downloadingTemplate.value = true
  try {
    const resp = await billingApi.downloadPricingTemplate()
    const blob = resp.data as unknown as Blob
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `pricing-template-${new Date().toISOString().slice(0, 10)}.json`
    document.body.appendChild(a)
    a.click()
    document.body.removeChild(a)
    URL.revokeObjectURL(url)
    message.success('模板已下载，填好价格后点「导入」上传')
  } catch {
    /* 拦截器已 toast */
  } finally {
    downloadingTemplate.value = false
  }
}

function triggerImport() {
  importFileInput.value?.click()
}

async function onImportFileChange(e: Event) {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  // 清空 value 让同文件可重选
  input.value = ''
  if (!file) return
  let items: PricingRuleExportItem[]
  try {
    const text = await file.text()
    const parsed = JSON.parse(text)
    if (!Array.isArray(parsed)) {
      message.error('文件格式错误：应为 JSON 数组')
      return
    }
    items = parsed as PricingRuleExportItem[]
  } catch {
    message.error('文件解析失败：不是合法 JSON')
    return
  }
  // 客户端预检：按 (providerId+model+kind+hasReference) 比对本地 pricingRules，估 created/updated
  const existingKeys = new Set(pricingRules.value.map(r => `${r.providerId}\u0000${r.model}\u0000${r.kind}\u0000${r.hasReference ? 1 : 0}`))
  let estCreated = 0
  let estUpdated = 0
  for (const it of items) {
    const key = `${it.providerId}\u0000${it.model}\u0000${it.kind}\u0000${it.hasReference ? 1 : 0}`
    if (existingKeys.has(key)) estUpdated++
    else estCreated++
  }
  dialog.warning({
    title: '确认导入',
    content: `将导入 ${items.length} 行（预计新增 ${estCreated} / 更新 ${estUpdated}）。存在的同名行价格会被覆盖。是否继续？`,
    positiveText: '导入',
    negativeText: '取消',
    onPositiveClick: () => doImport(items)
  })
}

async function doImport(items: PricingRuleExportItem[]) {
  importing.value = true
  try {
    const resp = await billingApi.importPricingRules(items)
    const result = resp.data.data
    if (result.failed > 0) {
      message.warning(`导入完成：新增 ${result.created} / 更新 ${result.updated} / 失败 ${result.failed}`)
      console.warn('价表导入失败行：', result.errors)
    } else {
      message.success(`导入完成：新增 ${result.created} / 更新 ${result.updated}`)
    }
    await load()
  } catch {
    /* 拦截器已 toast */
  } finally {
    importing.value = false
  }
}

// 阶梯表单
const ratioShow = ref(false)
const ratioEditId = ref<number | null>(null)
const ratioForm = reactive<RatioTierRequest>({ minAmount: 0, maxAmount: null, ratio: 100 })

function openRatioModal(tier?: RatioTierVO) {
  if (tier) {
    ratioEditId.value = tier.id
    Object.assign(ratioForm, { minAmount: tier.minAmount, maxAmount: tier.maxAmount, ratio: tier.ratio })
  } else {
    ratioEditId.value = null
    Object.assign(ratioForm, { minAmount: 0, maxAmount: null, ratio: 100 })
  }
  ratioShow.value = true
}

async function saveRatio() {
  saving.value = true
  try {
    if (ratioEditId.value == null) {
      await billingApi.createRatioTier({ ...ratioForm })
    } else {
      await billingApi.updateRatioTier(ratioEditId.value, { ...ratioForm })
    }
    message.success('阶梯已保存')
    ratioShow.value = false
    await load()
  } finally {
    saving.value = false
  }
}

async function removeRatio(id: number) {
  await billingApi.deleteRatioTier(id)
  message.success('阶梯已删除')
  await load()
}

async function load() {
  loading.value = true
  try {
    const [p, r] = await Promise.all([billingApi.listPricingRules(), billingApi.listRatioTiers()])
    pricingRules.value = p.data.data ?? []
    ratioTiers.value = r.data.data ?? []
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<style lang="scss" scoped>
.pricing-config {
  padding: var(--spacing-4);
}
.pricing-config__noperm {
  padding: var(--spacing-5) 0;
}
.pricing-config__hint {
  margin-top: 8px;
  color: var(--color-text-secondary);
  font-size: var(--font-size-sm);
}
.pricing-config__identity-note {
  margin: 0 0 16px 150px;
  border-left: 3px solid var(--color-primary);
}
</style>
