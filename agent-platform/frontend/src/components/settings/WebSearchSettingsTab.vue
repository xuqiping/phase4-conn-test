<template>
  <div class="web-search-settings">
    <n-form label-placement="left" label-width="160" class="web-search-settings__form">
      <n-form-item label="联网搜索（全局）">
        <n-switch v-model:value="enabled" :loading="saving" @update:value="handleSaveEnabled" />
        <span class="web-search-settings__hint">
          总开关（出问题可关不发版）。关 → 用户 🌐 开关无效，直接返空走纯聊天
        </span>
      </n-form-item>

      <n-form-item label="默认 Provider">
        <n-select
          v-model:value="activeProvider"
          :options="providerOptions"
          :loading="saving"
          :disabled="!enabled"
          style="max-width: 220px"
          @update:value="handleSaveActiveProvider"
        />
        <span class="web-search-settings__hint">
          外部无 key 或失败 → 自动降级自建 SearXNG → 全失败走纯聊天（不阻塞回复）
        </span>
      </n-form-item>

      <n-form-item label="Provider 可用性">
        <n-space>
          <n-tag v-for="(on, name) in providerAvailability" :key="name"
            :type="on ? 'success' : 'default'" size="small" round>
            {{ name }}：{{ on ? '可用' : '不可用' }}
          </n-tag>
        </n-space>
        <span class="web-search-settings__hint">
          实时自检（外部校 key 非空；自建校 SearXNG base-url 已配）
        </span>
      </n-form-item>

      <n-form-item label="默认结果数 (top-N)">
        <n-input-number
          v-model:value="maxResults"
          :min="1"
          :max="10"
          :loading="saving"
          :disabled="!enabled"
          style="width: 220px"
          @update:value="handleSaveMaxResults"
        />
        <span class="web-search-settings__hint">1~10，越大越慢（每页抽全文）</span>
      </n-form-item>

      <n-form-item label="单次超时 (ms)">
        <n-input-number
          v-model:value="timeoutMs"
          :min="1000"
          :step="1000"
          :loading="saving"
          :disabled="!enabled"
          style="width: 220px"
          @update:value="handleSaveTimeoutMs"
        />
        <span class="web-search-settings__hint">整体超时，默认 10000；外部超时 + 重试 1 次</span>
      </n-form-item>

      <n-divider>外部供应商 API Key（AES 加密存，不回显明文）</n-divider>

      <n-form-item label="Tavily Key">
        <n-input
          v-model:value="tavilyKey"
          type="password"
          show-password-on="click"
          placeholder="留空不改；输入新值覆盖；填空格清除"
          style="max-width: 360px"
          :disabled="!enabled"
        />
        <n-tag :type="hasTavilyKey ? 'success' : 'default'" size="small" round>
          {{ hasTavilyKey ? '已配置' : '未配置' }}
        </n-tag>
      </n-form-item>

      <n-form-item label="Serper Key">
        <n-input
          v-model:value="serperKey"
          type="password"
          show-password-on="click"
          placeholder="留空不改；输入新值覆盖；填空格清除"
          style="max-width: 360px"
          :disabled="!enabled"
        />
        <n-tag :type="hasSerperKey ? 'success' : 'default'" size="small" round>
          {{ hasSerperKey ? '已配置' : '未配置' }}
        </n-tag>
      </n-form-item>

      <n-form-item label="Bing Key">
        <n-input
          v-model:value="bingKey"
          type="password"
          show-password-on="click"
          placeholder="留空不改；输入新值覆盖；填空格清除"
          style="max-width: 360px"
          :disabled="!enabled"
        />
        <n-tag :type="hasBingKey ? 'success' : 'default'" size="small" round>
          {{ hasBingKey ? '已配置' : '未配置' }}
        </n-tag>
      </n-form-item>

      <n-form-item label=" ">
        <n-space>
          <n-button type="primary" :loading="saving" :disabled="!enabled" @click="handleSaveKeys">
            保存 Key
          </n-button>
          <n-button :loading="testing" :disabled="!enabled" @click="handleTest">
            测试连通
          </n-button>
        </n-space>
      </n-form-item>

      <n-alert v-if="testResult" type="info" :show-icon="true" class="web-search-settings__test">
        测试结果：命中 {{ testResult.results }} 条；
        active provider = {{ testResult.activeProvider }}；
        可用性 = {{ formatAvailability(testResult.providerAvailability) }}
      </n-alert>

      <n-alert type="warning" :show-icon="true" class="web-search-settings__deploy">
        <strong>部署依赖（人工）：</strong>
        自建 SearXNG 须 Docker 部署并配 <code>formats: [json]</code>，
        base-url 写后端 <code>application.properties</code> 的 <code>search.searxng.base-url</code>（部署期配置，非本页）。
        外部 key 申请：Tavily（免费 1000 次/月）/ Serper / Bing。
      </n-alert>
    </n-form>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useMessage } from 'naive-ui'
import { systemApi } from '@/api/system'
import type { WebSearchSettings, WebSearchTestResult } from '@/api/system'

const message = useMessage()

const enabled = ref(false)
const activeProvider = ref('builtin')
const maxResults = ref(5)
const timeoutMs = ref(10000)
const hasTavilyKey = ref(false)
const hasSerperKey = ref(false)
const hasBingKey = ref(false)
const providerAvailability = ref<Record<string, boolean>>({})

const tavilyKey = ref('')
const serperKey = ref('')
const bingKey = ref('')

const saving = ref(false)
const testing = ref(false)
const testResult = ref<WebSearchTestResult | null>(null)

const providerOptions = [
  { label: 'Tavily（外部）', value: 'tavily' },
  { label: 'Serper（外部）', value: 'serper' },
  { label: 'Bing（外部）', value: 'bing' },
  { label: '自建 SearXNG（兜底）', value: 'builtin' }
]

function applyVO(vo: WebSearchSettings) {
  enabled.value = vo.enabled
  activeProvider.value = vo.activeProvider
  maxResults.value = vo.maxResults
  timeoutMs.value = vo.timeoutMs
  hasTavilyKey.value = vo.hasTavilyKey
  hasSerperKey.value = vo.hasSerperKey
  hasBingKey.value = vo.hasBingKey
  providerAvailability.value = vo.providerAvailability || {}
}

async function load() {
  try {
    const res = await systemApi.getWebSearchSettings()
    applyVO(res.data.data)
  } catch {
    message.error('加载联网搜索配置失败')
  }
}

async function save(partial: Record<string, unknown>) {
  saving.value = true
  try {
    const res = await systemApi.updateWebSearchSettings(partial)
    applyVO(res.data.data)
    message.success('已保存')
  } catch {
    message.error('保存失败')
  } finally {
    saving.value = false
  }
}

function handleSaveEnabled(v: boolean) { save({ enabled: v }) }
function handleSaveActiveProvider(v: string) { save({ activeProvider: v }) }
function handleSaveMaxResults(v: number | null) { if (v != null) save({ maxResults: v }) }
function handleSaveTimeoutMs(v: number | null) { if (v != null) save({ timeoutMs: v }) }

async function handleSaveKeys() {
  const partial: Record<string, unknown> = {}
  // 空串 = 清除；非空 = 覆盖；undefined（未填）= 不改
  if (tavilyKey.value !== '') partial.tavilyKey = tavilyKey.value
  if (serperKey.value !== '') partial.serperKey = serperKey.value
  if (bingKey.value !== '') partial.bingKey = bingKey.value
  if (!Object.keys(partial).length) {
    message.info('未填写任何 key（留空 = 不改）')
    return
  }
  await save(partial)
  tavilyKey.value = ''
  serperKey.value = ''
  bingKey.value = ''
}

async function handleTest() {
  testing.value = true
  testResult.value = null
  try {
    const res = await systemApi.testWebSearch()
    testResult.value = res.data.data
  } catch {
    message.error('测试连通失败')
  } finally {
    testing.value = false
  }
}

function formatAvailability(avail: Record<string, boolean>): string {
  return Object.entries(avail).map(([k, v]) => `${k}=${v ? '可用' : '不可用'}`).join('，')
}

onMounted(load)
</script>

<style lang="scss" scoped>
.web-search-settings {
  max-width: 820px;

  &__hint {
    margin-left: 12px;
    color: var(--color-text-tertiary);
    font-size: 12px;
    line-height: 1.5;
  }

  &__test,
  &__deploy {
    margin-top: 12px;
  }

  code {
    padding: 1px 4px;
    border-radius: 3px;
    background: var(--color-surface-hover, rgba(255, 255, 255, 0.08));
    font-size: 12px;
  }
}
</style>
