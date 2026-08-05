<!--
  项目资产库·项目详情页（/assets/:id）
  plan §S8 地基：路由 + 403 兜底。实质矩阵筛选/搜索/卡片网格 + 详情抽屉在 S11 落地。
-->
<template>
  <div class="asset-project">
    <div class="asset-project__header">
      <h2>项目资产</h2>
      <span class="asset-project__sub">矩阵筛选 · 搜索 · 版本 · 一致性包</span>
    </div>

    <n-empty v-if="!canEdit" description="无 asset:write 权限，请联系管理员授权" class="asset-project__forbidden" />

    <!-- S11 落地：顶 Tab 类型 × 左栏角色矩阵 + 计数徽章 + 搜索 + 卡片网格 + 内嵌 S10 抽屉 -->
    <n-empty v-else description="项目详情矩阵（S11 建设中）" class="asset-project__placeholder" />
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { NEmpty } from 'naive-ui'
import { useAuthStore } from '@/stores/auth'

const authStore = useAuthStore()
const canEdit = computed(() => authStore.hasPermission('asset:write'))
</script>

<style lang="scss" scoped>
.asset-project {
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
