<template>
  <div class="settings-view">
    <div class="settings-view__header">
      <h2>设置</h2>
    </div>
    <n-tabs type="line" animated>
      <!-- 所有登录用户：安全设置（绑定/解绑凭证、修改密码），认证系统增强 Chunk F/G -->
      <n-tab-pane name="security" tab="安全设置">
        <SecuritySettingsTab />
      </n-tab-pane>
      <!-- 10x-1：不再开放「我的模型」个人配置大模型，移除该 Tab 入口。
           UserProviderTab.vue / UserLlmController / user_llm_providers 表保留不删（备用）。 -->
      <!-- 16x：全局模型供应商 tab 仅「大模型配置员」(llm:config) 可见；
           admin 失去该权限（后端端点同步改挂 llm:config，admin 调即 403）——
           admin 需配模型时，先给自己/他人加挂 llm_config 角色。其余 tab 仍仅 admin。 -->
      <n-tab-pane v-if="canConfigLlm" name="global" tab="全局模型供应商">
        <ProviderManageTab />
      </n-tab-pane>
      <n-tab-pane v-if="authStore.isAdmin" name="auth" tab="认证设置">
        <AuthSettingsTab />
      </n-tab-pane>
      <n-tab-pane v-if="authStore.isAdmin" name="auth-channels" tab="认证通道">
        <AuthChannelSettingsTab />
      </n-tab-pane>
      <n-tab-pane v-if="authStore.isAdmin" name="billing" tab="计费设置">
        <BillingSettingsTab />
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
import { computed } from 'vue'
import { NTabs, NTabPane } from 'naive-ui'
import { useAuthStore } from '@/stores/auth'
import ProviderManageTab from '@/components/settings/ProviderManageTab.vue'
import AuthSettingsTab from '@/components/settings/AuthSettingsTab.vue'
import AuthChannelSettingsTab from '@/components/settings/AuthChannelSettingsTab.vue'
import BillingSettingsTab from '@/components/settings/BillingSettingsTab.vue'
import RagRecallSettingsTab from '@/components/settings/RagRecallSettingsTab.vue'
import WebSearchSettingsTab from '@/components/settings/WebSearchSettingsTab.vue'
import SecuritySettingsTab from '@/components/settings/SecuritySettingsTab.vue'

const authStore = useAuthStore()
/** 16x：仅持 llm:config 的大模型配置员可见「全局模型供应商」tab（admin 刻意不授该码） */
const canConfigLlm = computed(() => authStore.hasPermission('llm:config'))
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
