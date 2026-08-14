<script setup lang="ts">
import { NTag } from 'naive-ui'
import type { AgentItem } from '@/mocks/types'

defineProps<{ agent: AgentItem }>()
</script>

<template>
  <article class="agent-card">
    <header class="agent-card__head">
      <!-- 渐变首字头像块 -->
      <span class="agent-card__avatar">{{ agent.name[0] }}</span>
      <div class="agent-card__title">
        <h3 class="agent-card__name">{{ agent.name }}</h3>
        <span class="agent-card__rating">★ {{ agent.rating.toFixed(1) }}</span>
      </div>
    </header>
    <p class="agent-card__desc">{{ agent.desc }}</p>
    <div class="agent-card__tags">
      <n-tag v-for="t in agent.tags" :key="t" size="small" :bordered="false">{{ t }}</n-tag>
    </div>
    <footer class="agent-card__foot">
      <span class="agent-card__runs">{{ agent.runs.toLocaleString() }} 次运行</span>
      <button class="agent-card__use">使用</button>
    </footer>
  </article>
</template>

<style lang="scss" scoped>
.agent-card {
  display: flex;
  flex-direction: column;
  gap: var(--sp-3);
  padding: var(--sp-4);
  border-radius: var(--r-lg);
  background: var(--sf-2);
  border: 1px solid var(--line-1);
  transition: transform var(--d-fast) var(--ease), border-color var(--d-fast) var(--ease),
    box-shadow var(--d-fast) var(--ease);

  &:hover {
    transform: translateY(-2px);
    border-color: var(--line-2);
    box-shadow: var(--shadow-pop);
  }

  &__head {
    display: flex;
    align-items: center;
    gap: var(--sp-3);
  }

  &__avatar {
    width: 40px;
    height: 40px;
    border-radius: var(--r-md);
    display: grid;
    place-items: center;
    font-size: var(--fs-lg);
    font-weight: 700;
    color: var(--sf-0);
    background: linear-gradient(135deg, var(--accent), var(--accent-2, var(--accent)));
    flex-shrink: 0;
  }

  &__title {
    display: flex;
    flex-direction: column;
    min-width: 0;
  }

  &__name {
    margin: 0;
    font-size: var(--fs-md);
    color: var(--tx-1);
  }

  &__rating {
    font-size: var(--fs-xs);
    color: var(--warn);
    font-family: var(--font-mono);
  }

  &__desc {
    margin: 0;
    font-size: var(--fs-sm);
    color: var(--tx-2);
    line-height: 1.55;
    display: -webkit-box;
    -webkit-line-clamp: 2;
    line-clamp: 2;
    -webkit-box-orient: vertical;
    overflow: hidden;
    min-height: 2.6em;
  }

  &__tags {
    display: flex;
    flex-wrap: wrap;
    gap: var(--sp-1);
  }

  &__foot {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-top: auto;
  }

  &__runs {
    font-family: var(--font-mono);
    font-size: var(--fs-xs);
    color: var(--tx-3);
  }

  &__use {
    padding: 4px 14px;
    border: none;
    border-radius: var(--r-md);
    background: var(--accent);
    color: var(--sf-0);
    font-size: var(--fs-sm);
    cursor: pointer;

    &:hover {
      filter: brightness(1.12);
    }
  }
}
</style>
