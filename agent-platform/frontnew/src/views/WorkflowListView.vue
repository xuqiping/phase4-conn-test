<script setup lang="ts">
import { h } from 'vue'
import { NDataTable, NButton } from 'naive-ui'
import type { DataTableColumns } from 'naive-ui'
import WorkflowStatusTag from '@/components/workflow/WorkflowStatusTag.vue'
import { workflows } from '@/mocks/workflows'
import type { WorkflowItem } from '@/mocks/types'

const columns: DataTableColumns<WorkflowItem> = [
  { title: '名称', key: 'name' },
  {
    title: '状态',
    key: 'status',
    render: (row) => h(WorkflowStatusTag, { status: row.status })
  },
  { title: '节点数', key: 'nodeCount', width: 90 },
  { title: '最近运行', key: 'lastRun', width: 120 },
  {
    title: '耗时',
    key: 'duration',
    width: 100,
    render: (row) => h('span', { style: 'font-family: var(--font-mono)' }, row.duration)
  },
  { title: '负责人', key: 'owner', width: 90 },
  {
    title: '操作',
    key: 'ops',
    width: 140,
    render: () =>
      h('div', { style: 'display:flex; gap:8px' }, [
        h(NButton, { size: 'tiny', quaternary: true }, () => '打开'),
        h(NButton, { size: 'tiny', quaternary: true }, () => '复制')
      ])
  }
]
</script>

<template>
  <div class="wf-list">
    <div class="wf-list__bar">
      <h2 class="wf-list__title">工作流</h2>
      <n-button type="primary">新建工作流</n-button>
    </div>
    <n-data-table :columns="columns" :data="workflows" :bordered="false" />
  </div>
</template>

<style lang="scss" scoped>
.wf-list {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: var(--sp-6);

  &__bar {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-bottom: var(--sp-4);
  }

  &__title {
    margin: 0;
    font-size: var(--fs-xl);
    color: var(--tx-1);
  }
}
</style>
