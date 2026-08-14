<script setup lang="ts">
import { useRoute } from 'vue-router'
import { NIcon } from 'naive-ui'
import {
  InfiniteOutline,
  ChatbubblesOutline,
  GridOutline,
  GitNetworkOutline,
  ChevronBackOutline,
  ChevronForwardOutline,
  SparklesOutline
} from '@vicons/ionicons5'

const props = defineProps<{ collapsed: boolean }>()
const emit = defineEmits<{ 'update:collapsed': [boolean] }>()

const route = useRoute()

const MENU = [
  { to: '/canvas', label: '无限画布', icon: InfiniteOutline },
  { to: '/chat', label: '对话', icon: ChatbubblesOutline },
  { to: '/agents', label: '智能体大厅', icon: GridOutline },
  { to: '/workflows', label: '工作流', icon: GitNetworkOutline }
]
</script>

<template>
  <nav class="sidebar" :class="{ 'sidebar--collapsed': props.collapsed }" aria-label="主导航">
    <div class="sidebar__brand">
      <n-icon :size="22" class="sidebar__logo"><SparklesOutline /></n-icon>
      <span v-if="!props.collapsed" class="sidebar__name">Agent Platform</span>
      <span v-if="!props.collapsed" class="sidebar__badge">预览版</span>
    </div>

    <ul class="sidebar__menu">
      <li v-for="m in MENU" :key="m.to">
        <router-link
          :to="m.to"
          class="sidebar__item"
          :class="{ 'sidebar__item--active': route.path.startsWith(m.to) }"
          :title="props.collapsed ? m.label : ''"
        >
          <n-icon :size="18"><component :is="m.icon" /></n-icon>
          <span v-if="!props.collapsed">{{ m.label }}</span>
        </router-link>
      </li>
    </ul>

    <button
      class="sidebar__toggle"
      :aria-label="props.collapsed ? '展开侧边栏' : '折叠侧边栏'"
      @click="emit('update:collapsed', !props.collapsed)"
    >
      <n-icon :size="16">
        <ChevronBackOutline v-if="!props.collapsed" />
        <ChevronForwardOutline v-else />
      </n-icon>
    </button>
  </nav>
</template>

<style lang="scss" scoped>
.sidebar {
  position: relative;
  width: 216px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  background: var(--sf-1);
  // T1 玻璃拟态（blur 只给这块固定面板）；其他主题为 none
  backdrop-filter: var(--glass-blur);
  border-right: 1px solid var(--line-1);
  transition: width var(--d-mid) var(--ease);

  &--collapsed {
    width: 60px;
  }

  &__brand {
    display: flex;
    align-items: center;
    gap: var(--sp-2);
    padding: var(--sp-4) var(--sp-4);
    color: var(--tx-1);
  }

  &__logo {
    color: var(--accent);
    flex-shrink: 0;
  }

  &__name {
    font-weight: 600;
    font-size: var(--fs-md);
    white-space: nowrap;
  }

  &__badge {
    font-size: 10px;
    padding: 1px 6px;
    border-radius: 99px;
    color: var(--accent);
    border: 1px solid var(--accent);
    white-space: nowrap;
  }

  &__menu {
    list-style: none;
    margin: 0;
    padding: var(--sp-2);
    display: flex;
    flex-direction: column;
    gap: 2px;
  }

  &__item {
    position: relative;
    display: flex;
    align-items: center;
    gap: var(--sp-3);
    padding: 9px var(--sp-3);
    border-radius: var(--r-md);
    color: var(--tx-2);
    text-decoration: none;
    font-size: var(--fs-md);
    transition: color var(--d-fast) var(--ease), background var(--d-fast) var(--ease);
    white-space: nowrap;

    &:hover {
      color: var(--tx-1);
      background: var(--sf-2);
    }

    &--active {
      color: var(--tx-1);
      background: var(--sf-2);

      // 激活指示条
      &::before {
        content: '';
        position: absolute;
        left: 0;
        top: 8px;
        bottom: 8px;
        width: 3px;
        border-radius: 2px;
        background: var(--accent);
      }
    }
  }

  &--collapsed &__item {
    justify-content: center;
    padding: 9px 0;
  }

  &__toggle {
    position: absolute;
    right: -12px;
    top: 52px;
    width: 24px;
    height: 24px;
    border-radius: 50%;
    border: 1px solid var(--line-1);
    background: var(--sf-3);
    color: var(--tx-2);
    cursor: pointer;
    display: grid;
    place-items: center;
    z-index: 10;

    &:hover {
      color: var(--tx-1);
      border-color: var(--line-2);
    }
  }
}
</style>
