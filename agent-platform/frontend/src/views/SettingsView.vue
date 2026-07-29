<template>
  <div class="settings-view">
    <div class="settings-view__header">
      <h2>设置</h2>
    </div>
    <n-tabs type="line" animated>
      <n-tab-pane name="my-models" tab="我的模型">
        <UserProviderTab />
      </n-tab-pane>
      <n-tab-pane v-if="authStore.isAdmin" name="global" tab="全局模型供应商">
        <ProviderManageTab />
      </n-tab-pane>
      <n-tab-pane v-if="authStore.isAdmin" name="auth" tab="认证设置">
        <AuthSettingsTab />
      </n-tab-pane>
      <!-- 计划12 H'-4：旧「RAG/记忆」页签移除（legacy user_memories 读控件）；新栈 gen 配置走记忆抽屉 MemoryGenMatrixPanel。 -->
      <n-tab-pane v-if="authStore.isAdmin" name="rag-recall" tab="RAG/召回">
        <RagRecallSettingsTab />
      </n-tab-pane>
      <n-tab-pane v-if="authStore.isAdmin" name="web-search" tab="联网搜索">
        <WebSearchSettingsTab />
      </n-tab-pane>
    </n-tabs>
  </div>
</template>

<script setup lang="ts">
import { NTabs, NTabPane } from 'naive-ui'
import { useAuthStore } from '@/stores/auth'
import UserProviderTab from '@/components/settings/UserProviderTab.vue'
import ProviderManageTab from '@/components/settings/ProviderManageTab.vue'
import AuthSettingsTab from '@/components/settings/AuthSettingsTab.vue'
import RagRecallSettingsTab from '@/components/settings/RagRecallSettingsTab.vue'
import WebSearchSettingsTab from '@/components/settings/WebSearchSettingsTab.vue'

const authStore = useAuthStore()
</script>

<style lang="scss" scoped>
.settings-view {
  padding: var(--spacing-6);
  height: 100%;
  overflow-y: auto;
}

.settings-view__header {
  margin-bottom: var(--spacing-4);

  h2 {
    margin: 0;
    font-size: 20px;
    color: var(--color-text-primary);
  }
}
</style>
