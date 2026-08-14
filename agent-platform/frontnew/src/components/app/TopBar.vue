<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { NIcon, NBadge } from 'naive-ui'
import { NotificationsOutline } from '@vicons/ionicons5'
import ThemeSwitcher from './ThemeSwitcher.vue'

const route = useRoute()
const title = computed(() => (route.meta.title as string) ?? '')
</script>

<template>
  <header class="topbar">
    <div class="topbar__crumb">
      <span class="topbar__root">Agent Platform</span>
      <span class="topbar__sep">/</span>
      <span class="topbar__page">{{ title }}</span>
    </div>

    <div class="topbar__actions">
      <ThemeSwitcher />
      <n-badge :value="3" :max="9" class="topbar__bell">
        <n-icon :size="18" aria-label="通知"><NotificationsOutline /></n-icon>
      </n-badge>
      <div class="topbar__avatar" aria-label="用户">A</div>
    </div>
  </header>
</template>

<style lang="scss" scoped>
.topbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 52px;
  padding: 0 var(--sp-5);
  background: var(--sf-1);
  backdrop-filter: var(--glass-blur);
  border-bottom: 1px solid var(--line-1);
  flex-shrink: 0;

  &__crumb {
    display: flex;
    align-items: center;
    gap: var(--sp-2);
    font-size: var(--fs-sm);
  }

  &__root {
    color: var(--tx-3);
  }

  &__sep {
    color: var(--tx-3);
  }

  &__page {
    color: var(--tx-1);
    font-weight: 500;
  }

  &__actions {
    display: flex;
    align-items: center;
    gap: var(--sp-4);
  }

  &__bell {
    color: var(--tx-2);
    cursor: pointer;

    &:hover {
      color: var(--tx-1);
    }
  }

  &__avatar {
    width: 28px;
    height: 28px;
    border-radius: 50%;
    display: grid;
    place-items: center;
    font-size: var(--fs-sm);
    font-weight: 600;
    color: var(--sf-0);
    background: var(--accent);
  }
}
</style>
