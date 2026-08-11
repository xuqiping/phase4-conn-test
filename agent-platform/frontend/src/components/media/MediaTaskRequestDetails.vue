<template>
  <n-button size="small" secondary @click="show = true">请求参数</n-button>

  <n-modal
    v-model:show="show"
    preset="card"
    title="视频生成请求参数"
    :style="{ width: 'min(920px, 92vw)' }"
  >
    <n-tabs v-model:value="activeTab" type="line" animated>
      <n-tab-pane name="submitted" tab="平台收到的提交参数">
        <div class="request-details__toolbar">
          <span>任务提交时平台持久化的参数。</span>
          <n-button data-testid="copy-submitted" size="tiny" @click="copyJson('submitted')">
            {{ copied === 'submitted' ? '已复制' : '复制 JSON' }}
          </n-button>
        </div>
        <pre data-testid="submitted-request" class="request-details__json">{{ submittedJson }}</pre>
      </n-tab-pane>

      <n-tab-pane name="provider" tab="实际发给模型（已脱敏）">
        <n-alert v-if="!providerRequestSnapshot" type="info" :show-icon="false">
          该历史任务未记录发送快照
        </n-alert>
        <template v-else>
          <div class="request-details__toolbar">
            <span>该快照与实际 POST body 同源；媒体正文已替换为 MIME、大小和 SHA-256 等摘要。</span>
            <n-button data-testid="copy-provider" size="tiny" @click="copyJson('provider')">
              {{ copied === 'provider' ? '已复制' : '复制 JSON' }}
            </n-button>
          </div>
          <pre data-testid="provider-request" class="request-details__json">{{ providerJson }}</pre>
        </template>
      </n-tab-pane>
    </n-tabs>
  </n-modal>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { NAlert, NButton, NModal, NTabPane, NTabs } from 'naive-ui'

type JsonObject = Record<string, unknown>

const props = defineProps<{
  submittedRequest: JsonObject | null
  providerRequestSnapshot: JsonObject | null
}>()

const show = ref(false)
const activeTab = ref<'submitted' | 'provider'>('submitted')
const copied = ref<'submitted' | 'provider' | null>(null)
const submittedJson = computed(() => prettyJson(props.submittedRequest))
const providerJson = computed(() => prettyJson(props.providerRequestSnapshot))

function prettyJson(value: JsonObject | null) {
  return JSON.stringify(value ?? {}, null, 2)
}

async function copyJson(kind: 'submitted' | 'provider') {
  const content = kind === 'submitted' ? submittedJson.value : providerJson.value
  await navigator.clipboard.writeText(content)
  copied.value = kind
  window.setTimeout(() => {
    if (copied.value === kind) copied.value = null
  }, 1600)
}
</script>

<style scoped lang="scss">
.request-details {
  &__toolbar {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: var(--spacing-3);
    margin-bottom: var(--spacing-2);
    color: var(--color-text-secondary);
    font-size: 12px;
  }

  &__json {
    max-height: 58vh;
    margin: 0;
    padding: var(--spacing-3);
    overflow: auto;
    white-space: pre-wrap;
    overflow-wrap: anywhere;
    border: 1px solid var(--color-border-light);
    border-radius: var(--radius-base);
    background: var(--color-bg-secondary, #111827);
    font: 12px/1.55 ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  }
}
</style>
