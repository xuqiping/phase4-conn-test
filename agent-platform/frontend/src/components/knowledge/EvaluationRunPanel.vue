<template>
  <n-card title="RAG 评测运行" size="small">
    <n-space vertical>
      <n-input v-model:value="datasetId" placeholder="Dataset ID" />
      <n-button type="primary" :disabled="!datasetId" @click="running = true">
        启动评测
      </n-button>
      <n-progress v-if="running" type="line" :percentage="0" processing />
      <n-alert v-if="running" type="info">
        评测已创建为异步任务，可离开页面后再回来查看。
      </n-alert>
    </n-space>
  </n-card>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { NAlert, NButton, NCard, NInput, NProgress, NSpace } from 'naive-ui'

const datasetId = ref('')
const running = ref(false)
</script>
