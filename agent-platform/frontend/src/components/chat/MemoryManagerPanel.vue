<!-- ============================================================
  记忆管理面板（计划12 · H'-4 瘦身版）
  旧栈「我的记忆 / 冲突 / 预览 / scope」首页签 + 全部 legacy 脚本已删（旧栈后端整体移除）。
  现 7 页签全是新栈（/api/chat/memory/*）：流水账 / 标签库 / 总结 / 冲突裁决 / gen 矩阵 / 项目 ACL / 生命周期。
  ============================================================ -->
<template>
  <div class="memory-manager">
    <n-tabs v-model:value="activeTab" type="line" size="small" class="memory-manager__tabs" :tabs-padding="0">
      <n-tab-pane name="turns" tab="流水账" display-directive="show">
        <MemoryTurnSection />
      </n-tab-pane>
      <n-tab-pane name="tags" tab="标签库" display-directive="show">
        <MemoryTagLibrary />
      </n-tab-pane>
      <n-tab-pane name="summaries" tab="总结" display-directive="show">
        <MemorySummarySection />
      </n-tab-pane>
      <n-tab-pane name="conflicts" tab="冲突裁决" display-directive="show">
        <MemoryConflictSection />
      </n-tab-pane>
      <n-tab-pane name="gen" tab="gen 矩阵" display-directive="show">
        <MemoryGenMatrixPanel />
      </n-tab-pane>
      <n-tab-pane name="acl" tab="项目 ACL" display-directive="show">
        <MemoryProjectAclPanel />
      </n-tab-pane>
      <n-tab-pane name="lifecycle" display-directive="show">
        <template #tab>
          <n-badge :value="deletedPendingTurns" :max="99" :show="deletedPendingTurns > 0" type="error" :offset="[10, 0]">
            生命周期
          </n-badge>
        </template>
        <MemoryLifecyclePanel @update:deleted-pending-turns="(n: number) => (deletedPendingTurns = n)" />
      </n-tab-pane>
    </n-tabs>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { NTabs, NTabPane, NBadge } from 'naive-ui'
import MemoryTagLibrary from '@/components/memory/MemoryTagLibrary.vue'
import MemoryConflictSection from '@/components/memory/MemoryConflictSection.vue'
import MemoryTurnSection from '@/components/memory/MemoryTurnSection.vue'
import MemorySummarySection from '@/components/memory/MemorySummarySection.vue'
import MemoryGenMatrixPanel from '@/components/memory/MemoryGenMatrixPanel.vue'
import MemoryProjectAclPanel from '@/components/memory/MemoryProjectAclPanel.vue'
import MemoryLifecyclePanel from '@/components/memory/MemoryLifecyclePanel.vue'

// 默认页签 = 流水账（新栈入口，最贴近日常「我记了什么」）。
const activeTab = ref<'turns' | 'tags' | 'summaries' | 'conflicts' | 'gen' | 'acl' | 'lifecycle'>('turns')

// 生命周期页签徽标：已删除项目未处理流水账总数（面板 emit 联动）
const deletedPendingTurns = ref(0)
</script>

<style lang="scss" scoped>
.memory-manager {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-3);
}
</style>
