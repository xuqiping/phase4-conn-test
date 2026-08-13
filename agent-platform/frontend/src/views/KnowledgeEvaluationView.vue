<template>
  <n-space vertical>
    <EvaluationRunPanel />
    <ShadowComparisonPanel />
  </n-space>
</template>

<script setup lang="ts">
import { NSpace } from 'naive-ui'
import EvaluationRunPanel from '@/components/knowledge/EvaluationRunPanel.vue'
import ShadowComparisonPanel from '@/components/knowledge/ShadowComparisonPanel.vue'
</script>
