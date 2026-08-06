<!--
  剧本分场结构化渲染（plan §C3）。
  content.scenes = [{index, description}, ...]，由 AssetScriptService.breakdown 产出。
  纯展示组件，版本时间线/详情抽屉共用。
-->
<template>
  <div class="script-scenes">
    <h4 class="script-scenes__title">分场（{{ scenes.length }}）</h4>
    <ol class="script-scenes__list">
      <li v-for="s in scenes" :key="s.index" class="script-scenes__item">
        <span class="script-scenes__no">场 {{ s.index }}</span>
        <span class="script-scenes__desc">{{ s.description }}</span>
      </li>
    </ol>
  </div>
</template>

<script setup lang="ts">
import type { SceneVO } from '@/types/asset'
defineProps<{ scenes: SceneVO[] }>()
</script>

<style lang="scss" scoped>
.script-scenes {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-2);
}

.script-scenes__title {
  margin: 0;
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
}

.script-scenes__list {
  list-style: none;
  padding: 0;
  margin: 0;
  display: flex;
  flex-direction: column;
  gap: var(--spacing-2);
}

.script-scenes__item {
  display: flex;
  gap: var(--spacing-2);
  padding: var(--spacing-2);
  background: var(--color-bg);
  border-radius: var(--radius-base, 6px);
  font-size: var(--font-size-sm);
  line-height: 1.5;
}

.script-scenes__no {
  flex-shrink: 0;
  font-weight: var(--font-weight-bold, 600);
  color: var(--color-primary);
}

.script-scenes__desc {
  color: var(--color-text-secondary);
  white-space: pre-wrap;
  word-break: break-word;
}
</style>
