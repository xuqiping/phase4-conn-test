<template>
  <n-card title="OpenSearch 索引运维" size="small">
    <n-space vertical>
      <n-select v-model:value="kbId" :options="kbOptions" placeholder="选择知识库" />
      <n-input v-model:value="snapshotId" placeholder="系统登记的 snapshot ID" />
      <n-space>
        <n-button :disabled="!kbId" @click="refresh">刷新状态</n-button>
        <n-button :disabled="!kbId || !snapshotId" @click="dryRun">对账 / 重建预检</n-button>
        <n-button type="warning" :disabled="!kbId || !snapshotId" @click="confirmSwitch">切换</n-button>
        <n-button type="error" :disabled="!kbId" @click="confirmRollback">回滚</n-button>
      </n-space>
      <n-descriptions v-if="status" bordered :column="1" size="small">
        <n-descriptions-item label="状态">{{ status.state }}</n-descriptions-item>
        <n-descriptions-item label="当前快照">{{ status.activeSnapshotId || '-' }}</n-descriptions-item>
        <n-descriptions-item label="上一快照">{{ status.previousSnapshotId || '-' }}</n-descriptions-item>
        <n-descriptions-item label="读 Alias">{{ status.readAlias }}</n-descriptions-item>
        <n-descriptions-item label="写 Alias">{{ status.writeAlias }}</n-descriptions-item>
      </n-descriptions>
    </n-space>
  </n-card>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { NButton, NCard, NDescriptions, NDescriptionsItem, NInput, NSelect, NSpace, useDialog, useMessage } from 'naive-ui'
import { knowledgeApi, type KnowledgeBase, type KnowledgeIndexStatus } from '@/api/knowledge'

const props = defineProps<{ bases: KnowledgeBase[] }>()
const kbId = ref<number | null>(null)
const snapshotId = ref('')
const status = ref<KnowledgeIndexStatus | null>(null)
const dialog = useDialog()
const message = useMessage()
const kbOptions = computed(() => props.bases.map(kb => ({ label: kb.name, value: kb.id })))

async function refresh() { if (kbId.value) status.value = (await knowledgeApi.getIndexStatus(kbId.value)).data.data }
async function dryRun() { if (kbId.value) { status.value = (await knowledgeApi.rebuildIndex(kbId.value, snapshotId.value, true)).data.data; message.success('预检完成，未修改索引') } }
function confirmSwitch() { dialog.warning({ title: '确认切换', content: `切换到 ${snapshotId.value}？`, positiveText: '确认', negativeText: '取消', onPositiveClick: async () => { if (kbId.value) status.value = (await knowledgeApi.switchIndex(kbId.value, snapshotId.value)).data.data } }) }
function confirmRollback() { dialog.error({ title: '确认回滚', content: '回滚到上一已登记快照？', positiveText: '确认', negativeText: '取消', onPositiveClick: async () => { if (kbId.value) status.value = (await knowledgeApi.rollbackIndex(kbId.value)).data.data } }) }
</script>
