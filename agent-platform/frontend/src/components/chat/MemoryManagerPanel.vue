<!-- ============================================================
  记忆管理面板（计划12 · H'-4 瘦身版）
  旧栈「我的记忆 / 冲突 / 预览 / scope」首页签 + 全部 legacy 脚本已删（旧栈后端整体移除）。
  现 6 页签全是新栈（/api/chat/memory/*）：流水账 / 标签库 / 总结 / 冲突裁决 / gen 矩阵 / 生命周期。
  （记忆二期 P1：「项目 ACL」页签随一期 reader×target 矩阵下线，FR-006。）
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
    </n-tabs>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { NTabs, NTabPane } from 'naive-ui'
import MemoryTagLibrary from '@/components/memory/MemoryTagLibrary.vue'
import MemoryConflictSection from '@/components/memory/MemoryConflictSection.vue'
import MemoryTurnSection from '@/components/memory/MemoryTurnSection.vue'
import MemorySummarySection from '@/components/memory/MemorySummarySection.vue'
import MemoryGenMatrixPanel from '@/components/memory/MemoryGenMatrixPanel.vue'

// 默认页签 = 流水账（新栈入口，最贴近日常「我记了什么」）。
// 二期 P1（FR-006）：「生命周期」页签随 turns 纯个人域下线（F-4b 拉取折叠板已删）。
const activeTab = ref<'turns' | 'tags' | 'summaries' | 'conflicts' | 'gen'>('turns')
</script>

<style lang="scss" scoped>
.memory-manager {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-3);
}

// E8 移动适配：390px 等窄屏下 Naive n-tabs 单行时末 3 个 tab（gen矩阵/项目ACL/生命周期）
// 会越出视口且无法滚动到达。强制 tab 行折成多行，保证 7 页签全部可见/可点。
@media (max-width: 640px) {
  .memory-manager__tabs :deep(.n-tabs-nav-scroll-content) {
    width: 100% !important;
    min-width: auto !important;
  }

  .memory-manager__tabs :deep(.n-tabs-wrapper) {
    flex-wrap: wrap !important;
    width: 100% !important;
  }

  .memory-manager__tabs :deep(.n-tabs-tab) {
    flex: 0 0 auto;
  }

  // 折行后禁用 Naive 内部的水平滚动容器，避免截断第二行
  .memory-manager__tabs :deep(.v-x-scroll) {
    overflow: visible !important;
  }
}
</style>
