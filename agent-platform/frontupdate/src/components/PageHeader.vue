<template>
  <!--
    高山流水 · 统一页面头（ART-DIR-0002 P3）
    用法：
      <PageHeader title="资产库" sub="项目级资产中枢">
        <template #actions><n-button type="primary">新建</n-button></template>
      </PageHeader>
    ink 主题：标题文楷 + 底部发丝渐变线；旧主题：普通粗体 + 无线（零变化）
  -->
  <div class="page-header">
    <div class="page-header__text">
      <h2 class="page-header__title">{{ title }}</h2>
      <span v-if="sub" class="page-header__sub">{{ sub }}</span>
    </div>
    <div v-if="$slots.actions" class="page-header__actions">
      <slot name="actions" />
    </div>
  </div>
</template>

<script setup lang="ts">
defineProps<{
  title: string
  sub?: string
}>()
</script>

<style lang="scss" scoped>
.page-header {
  display: flex;
  align-items: flex-end;
  gap: var(--spacing-4);
  padding-bottom: var(--spacing-3);
  margin-bottom: var(--spacing-5);
  position: relative;
}

.page-header__text {
  display: flex;
  align-items: baseline;
  gap: var(--spacing-3);
  min-width: 0;
}

.page-header__title {
  font-size: var(--font-size-xl);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
  margin: 0;
  white-space: nowrap;
}

.page-header__sub {
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.page-header__actions {
  margin-left: auto;
  display: flex;
  gap: var(--spacing-2);
  flex-shrink: 0;
}

// 高山流水：文楷标题 + 发丝渐变线（仅 ink 主题）
[data-theme="ye-mo"],
[data-theme="xuan-zhi"] {
  .page-header__title {
    font-family: var(--font-display);
    font-weight: 400;
    letter-spacing: 0.06em;
  }

  .page-header::after {
    content: '';
    position: absolute;
    left: 0;
    right: 0;
    bottom: 0;
    height: 1px;
    background: linear-gradient(90deg, var(--color-border) 0%, transparent 80%);
  }
}
</style>
