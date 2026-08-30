<template>
  <div class="settings-view">
    <!-- 雾中浮岛场景层（ART-DIR-0002R 方向二，仅 ink 主题渲染） -->
    <ModuleScene scene="settings" />
    <!-- 高山流水 P3：统一页头（无 actions） -->
    <PageHeader title="设置" />
    <n-tabs type="line" animated>
      <!-- 17x：所有登录用户——个人信息（昵称/姓名） -->
      <n-tab-pane name="profile" tab="个人信息">
        <div class="settings-view__panel u-ink-card"><ProfileSettingsTab /></div>
      </n-tab-pane>
      <!-- 所有登录用户：安全设置（绑定/解绑凭证、修改密码），认证系统增强 Chunk F/G -->
      <n-tab-pane name="security" tab="安全设置">
        <div class="settings-view__panel u-ink-card"><SecuritySettingsTab /></div>
      </n-tab-pane>
      <!-- 10x-1：不再开放「我的模型」个人配置大模型，移除该 Tab 入口。
           UserProviderTab.vue / UserLlmController / user_llm_providers 表保留不删（备用）。 -->
      <!-- 16x：全局模型供应商 tab 仅「大模型配置员」(llm:config) 可见；
           admin 失去该权限（后端端点同步改挂 llm:config，admin 调即 403）——
           admin 需配模型时，先给自己/他人加挂 llm_config 角色。其余 tab 仍仅 admin。 -->
      <n-tab-pane v-if="canConfigLlm" name="global" tab="全局模型供应商">
        <div class="settings-view__panel u-ink-card"><ProviderManageTab /></div>
      </n-tab-pane>
      <n-tab-pane v-if="authStore.isAdmin" name="auth" tab="认证设置">
        <div class="settings-view__panel u-ink-card"><AuthSettingsTab /></div>
      </n-tab-pane>
      <n-tab-pane v-if="authStore.isAdmin" name="auth-channels" tab="认证通道">
        <div class="settings-view__panel u-ink-card"><AuthChannelSettingsTab /></div>
      </n-tab-pane>
      <n-tab-pane v-if="authStore.isAdmin" name="billing" tab="计费设置">
        <div class="settings-view__panel u-ink-card"><BillingSettingsTab /></div>
      </n-tab-pane>
      <!-- 计划12 H'-4：旧「RAG/记忆」页签移除（legacy user_memories 读控件）；新栈 gen 配置走记忆抽屉 MemoryGenMatrixPanel。 -->
      <n-tab-pane v-if="authStore.isAdmin" name="rag-recall" tab="RAG/召回">
        <div class="settings-view__panel u-ink-card"><RagRecallSettingsTab /></div>
      </n-tab-pane>
      <n-tab-pane v-if="authStore.isAdmin" name="web-search" tab="联网搜索">
        <div class="settings-view__panel u-ink-card"><WebSearchSettingsTab /></div>
      </n-tab-pane>
    </n-tabs>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { NTabs, NTabPane } from 'naive-ui'
import { useAuthStore } from '@/stores/auth'
import PageHeader from '@/components/PageHeader.vue'
import ModuleScene from '@/components/ModuleScene.vue'
import ProviderManageTab from '@/components/settings/ProviderManageTab.vue'
import AuthSettingsTab from '@/components/settings/AuthSettingsTab.vue'
import AuthChannelSettingsTab from '@/components/settings/AuthChannelSettingsTab.vue'
import BillingSettingsTab from '@/components/settings/BillingSettingsTab.vue'
import RagRecallSettingsTab from '@/components/settings/RagRecallSettingsTab.vue'
import WebSearchSettingsTab from '@/components/settings/WebSearchSettingsTab.vue'
import SecuritySettingsTab from '@/components/settings/SecuritySettingsTab.vue'
import ProfileSettingsTab from '@/components/settings/ProfileSettingsTab.vue'

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

// 高山流水·静室（ART-DIR-0002）：各 Tab 表单区绢本卡包裹；仅 ink 主题，旧三主题零变化
// （u-ink-card 自带主题门控，此处 padding 也须门控，否则旧主题会多出内边距）
[data-theme="ye-mo"],
[data-theme="xuan-zhi"] {
  .settings-view__panel {
    padding: var(--spacing-4) var(--spacing-5);
  }
}
</style>
