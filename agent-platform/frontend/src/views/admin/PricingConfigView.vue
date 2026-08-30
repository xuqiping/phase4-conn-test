<template>
  <div class="pricing-config">
    <!-- 雾中浮岛场景层（ART-DIR-0002R 方向二 admin 淡版，仅 ink 主题渲染） -->
    <ModuleScene scene="admin" lite />
    <div v-if="!canManage" class="pricing-config__noperm"><InkEmptyState type="forbidden" description="无 pricing:manage 权限" /></div>
    <template v-else>
      <PageHeader title="价表配置">
        <template #actions>
          <n-button @click="handleExport" :loading="exporting">导出</n-button>
          <n-button @click="handleDownloadTemplate" :loading="downloadingTemplate">下载模板</n-button>
          <n-button @click="triggerImport" :loading="importing">导入</n-button>
          <n-button type="primary" @click="openPricingModal()">新增价表</n-button>
          <input
            ref="importFileInput"
            type="file"
            accept=".json,application/json"
            style="display: none"
            @change="onImportFileChange"
          />
        </template>
      </PageHeader>
      <n-card title="模型价表">
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
        <!-- V162：VIDEO TOKEN 输入价=通用每百万价（未单列分辨率档时的兜底扣费价），与下方分档价配合 -->
        <n-form-item label="通用每百万价 ¥/百万" v-if="pricingForm.kind === 'VIDEO' && pricingForm.videoBillingMode === 'TOKEN'"><n-input-number v-model:value="pricingForm.priceInputPerMillion" :precision="6" /></n-form-item>
        <n-form-item label="输出价 ¥/百万" v-if="pricingForm.kind === 'CHAT'"><n-input-number v-model:value="pricingForm.priceOutputPerMillion" :precision="6" /></n-form-item>
        <!-- D（V160）：闲时/缓存价——仅文本类；留空=同忙时/同输入价（计费侧回落，老价表行为不变） -->
        <template v-if="pricingForm.kind === 'CHAT' || pricingForm.kind === 'EMBED' || pricingForm.kind === 'RERANK'">
          <n-form-item label="闲时输入价 ¥/百万">
            <n-input-number v-model:value="pricingForm.offPeakInputPerMillion" :precision="6" clearable placeholder="留空=同忙时" />
          </n-form-item>
          <n-form-item label="闲时输出价 ¥/百万" v-if="pricingForm.kind === 'CHAT'">
            <n-input-number v-model:value="pricingForm.offPeakOutputPerMillion" :precision="6" clearable placeholder="留空=同忙时" />
          </n-form-item>
        </template>
        <template v-if="pricingForm.kind === 'CHAT'">
          <n-form-item label="缓存命中价 ¥/百万">
            <n-input-number v-model:value="pricingForm.priceCachedPerMillion" :precision="6" clearable placeholder="留空=同输入价" />
          </n-form-item>
          <n-form-item label="闲时缓存价 ¥/百万">
            <n-input-number v-model:value="pricingForm.offPeakCachedPerMillion" :precision="6" clearable placeholder="留空=同忙时缓存价" />
          </n-form-item>
          <n-form-item label=" ">
            <span class="pricing-config__hint">缓存命中部分按「缓存价」单列计价；闲时段（管理后台可配）内调用按闲时三价计费</span>
          </n-form-item>
        </template>
        <n-form-item label="视频计费模式" v-if="pricingForm.kind === 'VIDEO'">
          <n-select v-model:value="pricingForm.videoBillingMode" :options="modeOptions" />
        </n-form-item>
        <n-form-item label="是否含参考视频" v-if="pricingForm.kind === 'VIDEO'">
          <n-switch v-model:value="pricingForm.hasReference" :disabled="pricingEditId != null" />
          <span class="pricing-config__hint" style="margin-left: 8px">同一视频模型可分别配「无参考」和「有参考」两行价；身份字段编辑时不可改</span>
        </n-form-item>
        <!-- D6（V160）：SECOND 已去分辨率档（历史行合并为通用行），不再提供分辨率下拉 -->
        <n-form-item label="视频秒价 ¥" v-if="pricingForm.kind === 'VIDEO' && pricingForm.videoBillingMode === 'SECOND'"><n-input-number v-model:value="pricingForm.pricePerSecond" :precision="6" /></n-form-item>
        <!-- V164（MVR-3）：SECOND 秒价分档（真实扣费/估价同口径）；留空档=按上方通用秒价；无「通用」档（通用秒价即 pricePerSecond） -->
        <template v-if="pricingForm.kind === 'VIDEO' && pricingForm.videoBillingMode === 'SECOND'">
          <n-form-item
            v-for="slot in secondSlots"
            :key="'sp-' + slot.key"
            :label="'秒价 ¥/秒 · ' + slot.label"
          >
            <n-input-number v-model:value="secondPriceForm[slot.key]" :precision="6" clearable placeholder="留空=按通用秒价" />
          </n-form-item>
          <n-form-item label=" ">
            <span class="pricing-config__hint">按任务分辨率取对应档秒价扣费/估价，未单列档位按上方通用秒价</span>
          </n-form-item>
        </template>
        <!-- 7x-2（V153）：TOKEN 模式提交期无 token 维度，预估秒价按分辨率一行配齐，供余额预检（不参与真实扣费） -->
        <template v-if="pricingForm.kind === 'VIDEO' && pricingForm.videoBillingMode === 'TOKEN'">
          <!-- V162：TOKEN 每百万价按分辨率分档（真实扣费价）；留空档=按上方通用价计；无「通用」档（通用价即输入价） -->
          <n-form-item
            v-for="slot in tokenSlots"
            :key="'tp-' + slot.key"
            :label="'每百万价 ¥/百万 · ' + slot.label"
          >
            <n-input-number v-model:value="tokenPriceForm[slot.key]" :precision="6" clearable placeholder="留空=按通用价" />
          </n-form-item>
          <n-form-item
            v-for="slot in estSlots"
            :key="slot.key"
            :label="'预估秒价 ¥/秒 · ' + slot.label"
          >
            <n-input-number v-model:value="estForm[slot.key]" :precision="6" clearable placeholder="留空=不预估该档" />
          </n-form-item>
          <n-form-item label=" ">
            <span class="pricing-config__hint">上方分档价=真实扣费价（按任务分辨率取对应档每百万价，未单列档位按通用价）；预估秒价仅提交前余额预检用（未单列的按「通用」估）</span>
          </n-form-item>
          <!-- D9（V160）：近 7 天实耗 vs 预估偏差提示——校准 est 槽位收窄多退少补幅度；无数据/偏差<5% 隐藏 -->
          <n-form-item v-if="estDeviationTag" label=" ">
            <n-tag :type="estDeviationTag.type" size="small">{{ estDeviationTag.text }}</n-tag>
            <span class="pricing-config__hint" style="margin-left: 8px">
              样本 {{ estDeviationTag.count }} 个任务；按偏差校准上方预估秒价可收窄多退少补幅度
            </span>
          </n-form-item>
        </template>
        <n-form-item label="图片单价 ¥" v-if="pricingForm.kind === 'IMAGE'"><n-input-number v-model:value="pricingForm.pricePerImage" :precision="6" /></n-form-item>
        <!-- 7x（V155）：图片预估价说明——预估=张价×张数派生，无需单独配预估列；提交按所选张数预估管控，完工按实际张数多退少补 -->
        <n-form-item v-if="pricingForm.kind === 'IMAGE'" label="预估价">
          <span class="pricing-config__hint">预估 = 图片单价 × 张数（按实际产出张数结算，多退少补）。提交任务时按所选张数预估并预扣，无需单独配置预估价。</span>
        </n-form-item>
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
  NAlert, NCard, NDataTable, NButton, NModal, NForm, NFormItem, NInput, NInputNumber, NSelect, NSpace, NPopconfirm, NTag, useMessage, useDialog
} from 'naive-ui'
import type { DataTableColumns } from 'naive-ui'
import { billingApi, KIND_LABEL, PRICING_RESOLUTION_SLOTS, pricingResolutionLabel } from '@/api/billing'
import type { AvailablePricingModelVO, PricingRuleVO, PricingRuleRequest, PricingRuleExportItem, RatioTierVO, RatioTierRequest, BillingKind, VideoBillingMode, EstDeviationVO } from '@/api/billing'
import { useAuthStore } from '@/stores/auth'
import InkEmptyState from '@/components/InkEmptyState.vue'
import PageHeader from '@/components/PageHeader.vue'
import ModuleScene from '@/components/ModuleScene.vue'

const authStore = useAuthStore()
const message = useMessage()
const dialog = useDialog()
const canManage = computed(() => authStore.hasPermission('pricing:manage'))

const pricingRules = ref<PricingRuleVO[]>([])
const ratioTiers = ref<RatioTierVO[]>([])
const loading = ref(false)
const saving = ref(false)

// D9（V160）：近 7 天 est 偏差（无数据空数组 → tag 隐藏）
const estDeviations = ref<EstDeviationVO[]>([])
const estDeviationTag = computed<{ type: 'warning' | 'success'; text: string; count: number } | null>(() => {
  if (pricingForm.kind !== 'VIDEO' || !pricingForm.model) return null
  const hit = estDeviations.value.find(d =>
    d.model === pricingForm.model
    && !!d.hasReference === !!pricingForm.hasReference
    && (pricingForm.providerId == null || d.providerId === pricingForm.providerId))
  if (!hit || Math.abs(hit.deviationPct) < 5) return null
  return hit.deviationPct > 0
    ? { type: 'warning', text: `近7天实耗偏高 ${hit.deviationPct}%`, count: hit.sampleCount }
    : { type: 'success', text: `近7天实耗偏低 ${Math.abs(hit.deviationPct)}%`, count: hit.sampleCount }
})

const kindOptions = (Object.keys(KIND_LABEL) as BillingKind[]).map(k => ({ label: KIND_LABEL[k], value: k }))
const modeOptions: { label: string; value: VideoBillingMode }[] = [
  { label: 'TOKEN（按 token）', value: 'TOKEN' },
  { label: 'SECOND（按秒）', value: 'SECOND' }
]

// D6（V160）：分辨率下拉已删（SECOND 去分辨率档）；est 槽位标签复用此显示函数
function resolutionLabel(resolution: string | null | undefined): string {
  if (resolution == null || resolution === '') return '通用'
  return pricingResolutionLabel(resolution)
}

// MVR-2：6 档字典派生（api/billing.ts 单源）——est/TOKEN/SECOND（RC）三处槽位共用，防漂移
const resolutionSlotOptions = PRICING_RESOLUTION_SLOTS.map(key => ({
  key, label: pricingResolutionLabel(key)
}))

// 7x-2（V153）：TOKEN 预估秒价档位（一行配齐；值留空=该档不预估，回落「通用」）
const estSlots = [
  { key: 'general', label: '通用（未单列分辨率的兜底）' },
  ...resolutionSlotOptions
]
const estForm = reactive<Record<string, number | null>>(
  Object.fromEntries(estSlots.map(s => [s.key, null]))
)
function resetEstForm(map?: Record<string, number> | null) {
  for (const slot of estSlots) estForm[slot.key] = map?.[slot.key] ?? null
}
/** 列表预估列渲染：{720p:0.2,general:0.1} → "720p 0.2 / 通用 0.1" */
function fmtEst(map?: Record<string, number> | null): string {
  if (!map) return '—'
  const parts = estSlots.filter(slot => map[slot.key] != null)
    .map(slot => `${slot.key === 'general' ? '通用' : resolutionLabel(slot.key)} ${map[slot.key]}`)
  return parts.length ? parts.join(' / ') : '—'
}

// V162→MVR-2：TOKEN 每百万价档位（真实扣费价，6 档字典派生）。
// 无 general——通用/兜底价=priceInputPerMillion 列，未配档回落它
const tokenSlots = resolutionSlotOptions
const tokenPriceForm = reactive<Record<string, number | null>>(
  Object.fromEntries(tokenSlots.map(s => [s.key, null]))
)
function resetTokenPriceForm(map?: Record<string, number> | null) {
  for (const slot of tokenSlots) tokenPriceForm[slot.key] = map?.[slot.key] ?? null
}
/** V162 列表分档价列渲染：{4k:111.2,480p:6.5} → "4K 111.2 / 480p 6.5" */
function fmtTokenSlots(map?: Record<string, number> | null): string {
  if (!map) return '—'
  const parts = tokenSlots.filter(slot => map[slot.key] != null)
    .map(slot => `${resolutionLabel(slot.key)} ${map[slot.key]}`)
  return parts.length ? parts.join(' / ') : '—'
}

// V164（MVR-3）：SECOND 秒价档位（真实扣费/估价同口径，6 档字典派生）。
// 无 general——通用/兜底秒价=pricePerSecond 列，未配档回落它
const secondSlots = resolutionSlotOptions
const secondPriceForm = reactive<Record<string, number | null>>(
  Object.fromEntries(secondSlots.map(s => [s.key, null]))
)
function resetSecondPriceForm(map?: Record<string, number> | null) {
  for (const slot of secondSlots) secondPriceForm[slot.key] = map?.[slot.key] ?? null
}
/** V164 列表分档秒价列渲染：{768p:0.05,2k:0.2} → "768p 0.05 / 2K 0.2" */
function fmtSecondSlots(map?: Record<string, number> | null): string {
  if (!map) return '—'
  const parts = secondSlots.filter(slot => map[slot.key] != null)
    .map(slot => `${resolutionLabel(slot.key)} ${map[slot.key]}`)
  return parts.length ? parts.join(' / ') : '—'
}

const pricingColumns: DataTableColumns<PricingRuleVO> = [
  { title: '类型', key: 'kind', render: r => KIND_LABEL[r.kind] ?? r.kind },
  { title: 'providerId', key: 'providerId', render: r => r.providerId == null ? '全局' : String(r.providerId) },
  { title: 'model', key: 'model', render: r => r.model ?? '—' },
  { title: '参考视频', key: 'hasReference', render: r => r.kind === 'VIDEO' ? (r.hasReference ? '有参考' : '无参考') : '—' },
  // D（V160）：闲时/缓存价摘要（仅文本类；全空=—，即老价表回落语义）
  { title: '闲时/缓存价', key: 'offPeak', render: r => {
      if (r.kind !== 'CHAT' && r.kind !== 'EMBED' && r.kind !== 'RERANK') return '—'
      const parts: string[] = []
      if (r.offPeakInputPerMillion != null) parts.push(`闲时入${r.offPeakInputPerMillion}`)
      if (r.offPeakOutputPerMillion != null) parts.push(`闲时出${r.offPeakOutputPerMillion}`)
      if (r.priceCachedPerMillion != null) parts.push(`缓${r.priceCachedPerMillion}`)
      return parts.length ? parts.join(' / ') : '—'
    } },
  // 7x-2（V153）：VIDEO TOKEN 预估秒价（按分辨率参数，仅预检）
  { title: '预估秒价 ¥/秒', key: 'estPerResolution', render: r =>
      r.kind === 'VIDEO' && r.videoBillingMode === 'TOKEN' ? fmtEst(r.estPerResolution) : '—' },
  // V162：VIDEO TOKEN 分档每百万价（真实扣费价；未配档回落通用价列）
  { title: '分档价 ¥/百万', key: 'tokenPricePerResolution', render: r =>
      r.kind === 'VIDEO' && r.videoBillingMode === 'TOKEN' ? fmtTokenSlots(r.tokenPricePerResolution) : '—' },
  // 7x 反馈：价格列按归属过滤——TOKEN 行不显秒价、非 CHAT 不显输出价，防串味误导
  { title: '输入价 ¥/百万', key: 'priceInputPerMillion', render: r =>
      (r.kind !== 'VIDEO' && r.kind !== 'IMAGE') || (r.kind === 'VIDEO' && r.videoBillingMode === 'TOKEN') ? fmt(r.priceInputPerMillion) : '—' },
  { title: '输出价 ¥/百万', key: 'priceOutputPerMillion', render: r => r.kind === 'CHAT' ? fmt(r.priceOutputPerMillion) : '—' },
  { title: '视频秒价 ¥', key: 'pricePerSecond', render: r => r.kind === 'VIDEO' && r.videoBillingMode === 'SECOND' ? fmt(r.pricePerSecond) : '—' },
  // V164（MVR-3）：VIDEO SECOND 分档秒价（真实扣费/估价同口径；未配档回落通用秒价列）
  { title: '分档秒价 ¥/秒', key: 'pricePerSecondPerResolution', render: r =>
      r.kind === 'VIDEO' && r.videoBillingMode === 'SECOND' ? fmtSecondSlots(r.pricePerSecondPerResolution) : '—' },
  { title: '图片单价 ¥', key: 'pricePerImage', render: r => r.kind === 'IMAGE' ? fmt(r.pricePerImage) : '—' },
  { title: '生效时间', key: 'effectiveFrom', render: r => new Date(r.effectiveFrom).toLocaleString('zh-CN', { hour12: false }) },
  {
    title: '操作', key: 'op', width: 110,
    render: r => h(NSpace, { size: 'small' }, {
      default: () => [
        h(NButton, { size: 'small', text: true, type: 'primary', onClick: () => openPricingModal(r) }, { default: () => '编辑' }),
        // 删除价表行（7x 追加）：配错模型/价格的清理入口；历史账单金额已在扣费时落账不受影响
        h(NPopconfirm, { onPositiveClick: () => removePricing(r.id) }, {
          trigger: () => h(NButton, { size: 'small', text: true, type: 'error' }, { default: () => '删除' }),
          default: () => `确认删除 ${r.model ?? '该行'}（${r.hasReference ? '有参考' : '无参考'}）价表行？` +
            '若删后该模型无其他价行，之后调用它将因「价表缺失」失败，需重新配置。'
        })
      ]
    })
  }
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
const pricingForm = reactive<PricingRuleRequest>({ kind: 'CHAT', providerId: null, model: null, priceInputPerMillion: null, priceOutputPerMillion: null, videoBillingMode: 'TOKEN', pricePerSecond: null, pricePerImage: null, hasReference: false, resolution: null, estPerResolution: null, tokenPricePerResolution: null, pricePerSecondPerResolution: null, offPeakInputPerMillion: null, offPeakOutputPerMillion: null, offPeakCachedPerMillion: null, priceCachedPerMillion: null })
const availableModels = ref<AvailablePricingModelVO[]>([])
const candidateLoading = ref(false)
const candidateError = ref('')
const selectedCandidateKey = ref<string | null>(null)
const candidateOptions = computed(() => availableModels.value.map(candidate => ({
  // 7x-1：VIDEO 只配了一面参考维度时候选仍出现，hint 提示本次新增的是哪一面
  label: `${candidate.providerName} · ${candidate.model} · ${KIND_LABEL[candidate.kind]}${candidate.hint ? `（${candidate.hint}）` : ''}`,
  value: candidateKey(candidate)
})))

// D6（V160）：候选身份=模型+参考面（后端已去分辨率档）
function candidateKey(candidate: Pick<AvailablePricingModelVO, 'providerId' | 'model' | 'hasReference'>): string {
  return `${candidate.providerId}\u0000${candidate.model}\u0000${candidate.hasReference ? '1' : '0'}`
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
  // 7x-1：候选自带身份（参考面），切候选整体复位防上一行遗留泄漏；D6：无分辨率维度
  Object.assign(pricingForm, candidate
    ? { providerId: candidate.providerId, model: candidate.model, kind: candidate.kind,
        hasReference: candidate.hasReference === true, resolution: null }
    : { providerId: null, model: null, kind: 'CHAT' as BillingKind, hasReference: false, resolution: null })
}

async function openPricingModal(rule?: PricingRuleVO) {
  if (rule) {
    pricingEditId.value = rule.id
    Object.assign(pricingForm, {
      kind: rule.kind, providerId: rule.providerId, model: rule.model,
      priceInputPerMillion: rule.priceInputPerMillion, priceOutputPerMillion: rule.priceOutputPerMillion,
      videoBillingMode: rule.videoBillingMode ?? 'TOKEN', pricePerSecond: rule.pricePerSecond, pricePerImage: rule.pricePerImage,
      hasReference: rule.hasReference ?? false,
      resolution: null, estPerResolution: rule.estPerResolution ?? null,
      tokenPricePerResolution: rule.tokenPricePerResolution ?? null,
      pricePerSecondPerResolution: rule.pricePerSecondPerResolution ?? null,
      offPeakInputPerMillion: rule.offPeakInputPerMillion ?? null,
      offPeakOutputPerMillion: rule.offPeakOutputPerMillion ?? null,
      offPeakCachedPerMillion: rule.offPeakCachedPerMillion ?? null,
      priceCachedPerMillion: rule.priceCachedPerMillion ?? null
    })
    resetEstForm(rule.estPerResolution)
    resetTokenPriceForm(rule.tokenPricePerResolution)
    resetSecondPriceForm(rule.pricePerSecondPerResolution)
  } else {
    pricingEditId.value = null
    selectedCandidateKey.value = null
    Object.assign(pricingForm, { kind: 'CHAT', providerId: null, model: null, priceInputPerMillion: null, priceOutputPerMillion: null, videoBillingMode: 'TOKEN', pricePerSecond: null, pricePerImage: null, hasReference: false, resolution: null, estPerResolution: null, tokenPricePerResolution: null, pricePerSecondPerResolution: null, offPeakInputPerMillion: null, offPeakOutputPerMillion: null, offPeakCachedPerMillion: null, priceCachedPerMillion: null })
    resetEstForm(null)
    resetTokenPriceForm(null)
    resetSecondPriceForm(null)
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
  // 非文本/embed（且非 VIDEO——VIDEO TOKEN 模式的 token 价复用 priceInputPerMillion，下方模式块处理）：清掉 token 价
  if (k !== 'CHAT' && k !== 'EMBED' && k !== 'RERANK' && k !== 'VIDEO') {
    out.priceInputPerMillion = null
    out.priceOutputPerMillion = null
  }
  // 非图片：清掉按张价
  if (k !== 'IMAGE') out.pricePerImage = null
  // D（V160）：闲时/缓存四新列仅文本类有效（IMAGE/VIDEO 按张/秒计价无此维度）
  if (k !== 'CHAT' && k !== 'EMBED' && k !== 'RERANK') {
    out.offPeakInputPerMillion = null
    out.offPeakOutputPerMillion = null
    out.offPeakCachedPerMillion = null
    out.priceCachedPerMillion = null
  }
  // 非视频：清掉视频专属字段，has_reference 强制 false
  if (k !== 'VIDEO') {
    out.videoBillingMode = null
    out.pricePerSecond = null
    out.hasReference = false
    out.resolution = null
    out.estPerResolution = null
    out.tokenPricePerResolution = null
    out.pricePerSecondPerResolution = null
  } else {
    out.hasReference = out.hasReference === true
    // 7x-1/2 + D6（V160）+ V164（MVR-3）：模式联动——TOKEN 清秒价/秒价槽（token 价用
    // priceInputPerMillion，预估取 estForm 非空档组成 map，全空=null）；SECOND 清 token 价/
    // 预估（估价直接用秒价槽），秒价槽恒发对象（全空档={} 即显式清空，同 V162 token 槽语义）；
    // 计费行恒不带 resolution（D6 去分辨率档）
    if (out.videoBillingMode === 'TOKEN') {
      out.pricePerSecond = null
      out.pricePerSecondPerResolution = null
      out.resolution = null
      const est: Record<string, number> = {}
      for (const slot of estSlots) {
        if (estForm[slot.key] != null) est[slot.key] = estForm[slot.key] as number
      }
      out.estPerResolution = Object.keys(est).length ? est : null
      // V162：TOKEN 行恒带 tokenPricePerResolution（全空档={} 即显式清空）——
      // 若省略字段（null），后端导入/编辑的「null=不动」语义会把「清空档位」吞掉
      const tp: Record<string, number> = {}
      for (const slot of tokenSlots) {
        if (tokenPriceForm[slot.key] != null) tp[slot.key] = tokenPriceForm[slot.key] as number
      }
      out.tokenPricePerResolution = tp
    } else {
      out.priceInputPerMillion = null
      out.priceOutputPerMillion = null
      out.estPerResolution = null
      out.tokenPricePerResolution = null
      out.resolution = null
      // V164（MVR-3）：SECOND 行恒带 pricePerSecondPerResolution（全空档={} 即显式清空）——
      // 若省略字段（null），后端编辑/导入的「null=不动」语义会把「清空档位」吞掉
      const sp: Record<string, number> = {}
      for (const slot of secondSlots) {
        if (secondPriceForm[slot.key] != null) sp[slot.key] = secondPriceForm[slot.key] as number
      }
      out.pricePerSecondPerResolution = sp
    }
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

/** 删除价表行（7x 追加）：物理删，删后候选下拉会重新出现该维度。 */
async function removePricing(id: number) {
  try {
    await billingApi.deletePricingRule(id)
    message.success('价表已删除')
    await load()
    // 候选列表联动刷新（删掉的维度重新可配）
    if (!pricingShow.value) await loadAvailableModels()
  } catch {
    /* 拦截器已 toast */
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
    content: `将导入 ${items.length} 行（预计新增 ${estCreated} / 更新 ${estUpdated}）。存在的同名行价格会被覆盖。视频 TOKEN 行的「分档每百万价」按三态处理：字段缺失=保留现有档、{}=清空、非空=整体覆盖。是否继续？`,
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
    const [p, r, d] = await Promise.all([
      billingApi.listPricingRules(), billingApi.listRatioTiers(), billingApi.videoEstDeviation()
    ])
    pricingRules.value = p.data.data ?? []
    ratioTiers.value = r.data.data ?? []
    estDeviations.value = d.data.data ?? []
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
