<template>
  <n-card title="Champion / Challenger" size="small">
    <n-descriptions bordered :column="1">
      <n-descriptions-item label="Champion">
        当前生产 Pipeline
      </n-descriptions-item>
      <n-descriptions-item label="Challenger">
        待评测 Pipeline
      </n-descriptions-item>
      <n-descriptions-item label="发布门禁">
        <n-tag type="warning">等待评测结果</n-tag>
      </n-descriptions-item>
    </n-descriptions>
  </n-card>
</template>

<script setup lang="ts">
import { NCard, NDescriptions, NDescriptionsItem, NTag } from 'naive-ui'
</script>
