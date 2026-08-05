<!--
  项目资产库·版本时间线  plan §S10（10b）
  - 版本列表（versionApi.list，meta only，倒序）→ 点选某版 → versionApi.get 拉该版 content 做只读「回滚查看」
  - 版本快照不可变（设计 §六）；本组件仅查看历史，不做实际回滚 mutate
  - 当前版本高亮（assetCurrentVersion）
-->
<template>
  <div class="version-timeline">
    <h4 class="version-timeline__title">版本时间线（{{ versions.length }}）</h4>
    <n-empty v-if="!loading && versions.length === 0" description="暂无版本" size="small" />
    <n-spin v-else-if="loading" size="small" />
    <ul v-else class="version-timeline__list">
      <li
        v-for="v in versions"
        :key="v.id"
        class="version-timeline__item"
        :class="{ 'is-current': v.version === assetCurrentVersion, 'is-active': selectedVersion === v.version }"
        @click="viewVersion(v.version)"
      >
        <span class="version-timeline__ver">v{{ v.version }}</span>
        <span v-if="v.version === assetCurrentVersion" class="version-timeline__cur">当前</span>
        <span class="version-timeline__note">{{ v.changeNote || '—' }}</span>
        <span class="version-timeline__time">{{ v.createdAt }}</span>
      </li>
    </ul>

    <!-- 只读历史内容预览 -->
    <div v-if="detailLoading" class="version-timeline__detail-loading"><n-spin size="small" /></div>
    <div v-else-if="detailContent" class="version-timeline__detail">
      <div class="version-timeline__detail-head">v{{ selectedVersion }} 内容（只读）</div>
      <pre class="version-timeline__detail-body">{{ detailContent }}</pre>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import { NEmpty, NSpin, useMessage } from 'naive-ui'
import { versionApi } from '@/api/assets'
import type { VersionVO } from '@/types/asset'

const props = defineProps<{
  assetId: number | null
  assetCurrentVersion?: number
}>()

const message = useMessage()
const versions = ref<VersionVO[]>([])
const loading = ref(false)
const selectedVersion = ref<number | null>(null)
const detailContent = ref<string | null>(null)
const detailLoading = ref(false)

watch(
  () => props.assetId,
  (id) => {
    if (id) void loadList(id)
    else {
      versions.value = []
      selectedVersion.value = null
      detailContent.value = null
    }
  },
  { immediate: true }
)

async function loadList(id: number) {
  loading.value = true
  try {
    const res = await versionApi.list(id)
    versions.value = res.data.data || []
  } catch {
    message.error('加载版本列表失败')
  } finally {
    loading.value = false
  }
}

async function viewVersion(version: number) {
  if (!props.assetId) return
  selectedVersion.value = version
  detailLoading.value = true
  try {
    const res = await versionApi.get(props.assetId, version)
    const v: VersionVO = res.data.data
    detailContent.value = formatContent(v.content)
  } catch {
    message.error('加载版本内容失败')
    detailContent.value = null
  } finally {
    detailLoading.value = false
  }
}

function formatContent(raw?: string | null): string {
  if (!raw) return '（无正文）'
  try {
    return JSON.stringify(JSON.parse(raw), null, 2)
  } catch {
    return raw
  }
}

defineExpose({ versions, selectedVersion, detailContent, viewVersion, loadList })
</script>

<style lang="scss" scoped>
.version-timeline {
  &__title {
    margin: 0 0 var(--spacing-2);
    font-size: var(--font-size-md);
    color: var(--color-text-primary);
  }

  &__list {
    list-style: none;
    padding: 0;
    margin: 0 0 var(--spacing-3);
    display: flex;
    flex-direction: column;
    gap: var(--spacing-1);
  }

  &__item {
    display: flex;
    align-items: center;
    gap: var(--spacing-2);
    padding: var(--spacing-2);
    border-radius: var(--radius-sm, 6px);
    cursor: pointer;
    font-size: var(--font-size-sm);
    color: var(--color-text-secondary);
    border: 1px solid transparent;

    &:hover {
      background: var(--color-bg-secondary);
    }

    &.is-current {
      color: var(--color-primary);
      font-weight: var(--font-weight-bold);
    }

    &.is-active {
      border-color: var(--color-primary);
      background: var(--color-bg-secondary);
    }
  }

  &__ver {
    font-weight: var(--font-weight-bold);
    min-width: 36px;
  }

  &__cur {
    font-size: var(--font-size-xs);
    color: var(--color-primary);
    border: 1px solid var(--color-primary);
    border-radius: var(--radius-sm, 6px);
    padding: 0 4px;
  }

  &__note {
    flex: 1;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  &__time {
    color: var(--color-text-tertiary);
    font-size: var(--font-size-xs);
  }

  &__detail {
    margin-top: var(--spacing-2);
    border-top: 1px dashed var(--color-border);
    padding-top: var(--spacing-2);
  }

  &__detail-head {
    font-size: var(--font-size-sm);
    color: var(--color-text-tertiary);
    margin-bottom: var(--spacing-1);
  }

  &__detail-body {
    margin: 0;
    white-space: pre-wrap;
    font-family: var(--font-family-mono, monospace);
    font-size: var(--font-size-xs);
    color: var(--color-text-secondary);
    max-height: 200px;
    overflow: auto;
    background: var(--color-bg-secondary);
    padding: var(--spacing-2);
    border-radius: var(--radius-sm, 6px);
  }

  &__detail-loading {
    display: flex;
    justify-content: center;
    margin-top: var(--spacing-2);
  }
}
</style>
