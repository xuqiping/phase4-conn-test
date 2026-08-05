<!--
  项目资产库·项目列表页（/assets）
  plan §S8 地基：路由 + 403 兜底 + Sidebar 入口。实质列表/分享弹窗在 S9 落地。
  权限三重兜底：菜单隐藏（Sidebar hasPermission）+ 页内 canEdit + API 403。
-->
<template>
  <div class="asset-list">
    <div class="asset-list__header">
      <h2>资产库</h2>
      <span class="asset-list__sub">项目级资产中枢 · 五类资产 × 叙事角色双轴矩阵</span>
    </div>

    <!-- 无权限：直访 URL 兜底（菜单已隐藏入口） -->
    <n-empty v-if="!canEdit" description="无 asset:write 权限，请联系管理员授权" class="asset-list__forbidden" />

    <!-- S9 落地：我的项目/共享给我 Tab + 卡片网格 + 新建 + 分享弹窗 -->
    <n-empty v-else description="项目列表（S9 建设中）" class="asset-list__placeholder" />
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { NEmpty } from 'naive-ui'
import { useAuthStore } from '@/stores/auth'

const authStore = useAuthStore()
/** asset:write gated（admin 默认有，普通 user 须授权，同 canvas:write） */
const canEdit = computed(() => authStore.hasPermission('asset:write'))
</script>

<style lang="scss" scoped>
.asset-list {
  padding: var(--spacing-5);

  &__header {
    margin-bottom: var(--spacing-5);
    h2 {
      margin: 0;
      font-size: var(--font-size-xl);
    }
  }

  &__sub {
    color: var(--color-text-secondary);
    font-size: var(--font-size-sm);
  }

  &__forbidden,
  &__placeholder {
    margin-top: var(--spacing-8);
  }
}
</style>
