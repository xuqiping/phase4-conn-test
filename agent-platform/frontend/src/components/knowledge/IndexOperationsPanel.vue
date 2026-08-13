<template>
  <n-card title="OpenSearch 索引运维" size="small">
    <n-space vertical>
      <n-select v-model:value="kbId" :options="kbOptions" placeholder="选择知识库" />
      <n-input v-model:value="snapshotId" placeholder="系统登记的 snapshot ID" />
      <n-space>
        <n-button :disabled="!kbId" @click="refresh">刷新状态</n-button>
        <n-button :disabled="!kbId || !snapshotId" @click="dryRun">对账 / 重建预检</n-button>
        <n-button type="primary" :disabled="!kbId || !snapshotId" @click="confirmRebuild">启动重建</n-button>
        <n-button :disabled="!kbId || !snapshotId || status?.state !== 'BUILDING'" @click="cancelRebuild">取消重建</n-button>
        <n-button type="warning" :disabled="!kbId || !snapshotId" @click="confirmSwitch">切换</n-button>
        <n-button type="error" :disabled="!kbId" @click="confirmRollback">回滚</n-button>
      </n-space>
      <n-descriptions v-if="status" bordered :column="1" size="small">
        <n-descriptions-item label="状态">{{ status.state }}</n-descriptions-item>
        <n-descriptions-item label="当前快照">{{ status.activeSnapshotId || '-' }}</n-descriptions-item>
        <n-descriptions-item label="上一快照">{{ status.previousSnapshotId || '-' }}</n-descriptions-item>
        <n-descriptions-item label="重建快照">{{ status.rebuildSnapshotId || '-' }}</n-descriptions-item>
        <n-descriptions-item label="重建进度">{{ rebuildProgress }}</n-descriptions-item>
        <n-descriptions-item label="读 Alias">{{ status.readAlias }}</n-descriptions-item>
        <n-descriptions-item label="写 Alias">{{ status.writeAlias }}</n-descriptions-item>
      </n-descriptions>

      <n-divider />
      <strong>RAG 灰度发布</strong>
      <n-select v-model:value="rolloutPercentage" :options="rolloutOptions" />
      <n-input v-model:value="configVersion" placeholder="配置版本，例如 rag-v2" />
      <n-space>
        <n-button :disabled="!kbId" @click="refreshRollout">刷新灰度状态</n-button>
        <n-button type="warning" :disabled="!kbId || !configVersion" @click="confirmRollout">应用灰度</n-button>
        <n-button type="error" :disabled="!kbId" @click="confirmRolloutRollback">回滚灰度</n-button>
      </n-space>
      <n-alert v-if="rollout" type="info">
        当前 Challenger：{{ rollout.percentage }}%，配置版本：{{ rollout.configVersion }}
      </n-alert>
    </n-space>
  </n-card>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import {
  NAlert, NButton, NCard, NDescriptions, NDescriptionsItem, NDivider,
  NInput, NSelect, NSpace, useDialog, useMessage
} from 'naive-ui'
import {
  knowledgeApi, type KnowledgeBase, type KnowledgeIndexStatus, type RagRolloutState
} from '@/api/knowledge'

const props = defineProps<{ bases: KnowledgeBase[] }>()
const kbId = ref<number | null>(null)
const snapshotId = ref('')
const status = ref<KnowledgeIndexStatus | null>(null)
const rollout = ref<RagRolloutState | null>(null)
const rolloutPercentage = ref(5)
const configVersion = ref('')
const dialog = useDialog()
const message = useMessage()
const kbOptions = computed(() => props.bases.map(kb => ({ label: kb.name, value: kb.id })))
const rolloutOptions = [5, 20, 50, 100].map(value => ({ label: `${value}%`, value }))
const rebuildProgress = computed(() => {
  if (!status.value || status.value.total == null) return '-'
  return `${status.value.completed || 0}/${status.value.total}，失败 ${status.value.failed || 0}，取消 ${status.value.cancelled || 0}`
})

async function refresh() {
  if (kbId.value) status.value = (await knowledgeApi.getIndexStatus(kbId.value)).data.data
}
async function dryRun() {
  if (!kbId.value) return
  status.value = (await knowledgeApi.rebuildIndex(kbId.value, snapshotId.value, true)).data.data
  message.success('预检完成，未修改索引')
}
function confirmRebuild() {
  dialog.warning({
    title: '确认启动重建', content: `创建并填充隔离快照 ${snapshotId.value}？`,
    positiveText: '确认', negativeText: '取消',
    onPositiveClick: async () => {
      if (kbId.value) status.value = (await knowledgeApi.rebuildIndex(kbId.value, snapshotId.value, false)).data.data
    }
  })
}
async function cancelRebuild() {
  if (!kbId.value) return
  status.value = (await knowledgeApi.cancelIndexRebuild(kbId.value, snapshotId.value)).data.data
}
function confirmSwitch() {
  dialog.warning({
    title: '确认切换', content: `切换到 ${snapshotId.value}？`, positiveText: '确认', negativeText: '取消',
    onPositiveClick: async () => {
      if (kbId.value) status.value = (await knowledgeApi.switchIndex(kbId.value, snapshotId.value)).data.data
    }
  })
}
function confirmRollback() {
  dialog.error({
    title: '确认回滚', content: '回滚到上一已登记快照？', positiveText: '确认', negativeText: '取消',
    onPositiveClick: async () => {
      if (kbId.value) status.value = (await knowledgeApi.rollbackIndex(kbId.value)).data.data
    }
  })
}
async function refreshRollout() {
  if (kbId.value) rollout.value = (await knowledgeApi.getRolloutStatus(kbId.value)).data.data
}
function confirmRollout() {
  dialog.warning({
    title: '确认应用灰度',
    content: `将 Challenger 灰度调整为 ${rolloutPercentage.value}%？系统会再次校验发布门禁、索引健康和对账。`,
    positiveText: '确认', negativeText: '取消',
    onPositiveClick: async () => {
      if (kbId.value) rollout.value = (await knowledgeApi.configureRollout(
        kbId.value, rolloutPercentage.value, configVersion.value
      )).data.data
    }
  })
}
function confirmRolloutRollback() {
  dialog.error({
    title: '确认回滚灰度', content: '恢复上一灰度路由并失效新快照答案缓存？',
    positiveText: '确认', negativeText: '取消',
    onPositiveClick: async () => {
      if (kbId.value) rollout.value = (await knowledgeApi.rollbackRollout(kbId.value)).data.data
    }
  })
}
</script>
