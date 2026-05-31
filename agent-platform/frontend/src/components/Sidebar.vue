<template>
  <aside class="sidebar" :class="{ 'sidebar--collapsed': collapsed }">
    <!-- Logo区域 -->
    <div class="sidebar__logo" @click="$router.push('/agents')">
      <div class="sidebar__logo-icon">
        <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 32 32" width="28" height="28">
          <defs>
            <linearGradient id="logo-g" x1="0%" y1="0%" x2="100%" y2="100%">
              <stop offset="0%" style="stop-color:var(--color-gradient-start)"/>
              <stop offset="100%" style="stop-color:var(--color-gradient-end)"/>
            </linearGradient>
          </defs>
          <rect width="32" height="32" rx="6" fill="url(#logo-g)"/>
          <text x="16" y="22" text-anchor="middle" fill="white" font-size="18" font-weight="bold" font-family="sans-serif">A</text>
        </svg>
      </div>
      <span v-show="!collapsed" class="sidebar__logo-text">Agent平台</span>
    </div>

    <!-- 导航列表 -->
    <nav class="sidebar__nav">
      <router-link
        v-for="item in navItems"
        :key="item.path"
        :to="item.path"
        class="sidebar__nav-item"
        :class="{ 'sidebar__nav-item--active': isNavItemActive(item.path) }"
      >
        <n-icon size="20" :component="item.icon" />
        <span v-show="!collapsed" class="sidebar__nav-label">{{ item.label }}</span>
      </router-link>
    </nav>

    <!-- 底部折叠按钮 -->
    <div class="sidebar__footer">
      <button class="sidebar__toggle" @click="$emit('toggle')">
        <n-icon size="18">
          <ChevronBackOutline v-if="!collapsed" />
          <ChevronForwardOutline v-else />
        </n-icon>
      </button>
    </div>
  </aside>
</template>

<script setup lang="ts">
import { h } from 'vue'
import { useRoute } from 'vue-router'
import { NIcon } from 'naive-ui'
import {
  HomeOutline,
  GridOutline,
  GitBranchOutline,
  PulseOutline,
  SettingsOutline,
  ChevronBackOutline,
  ChevronForwardOutline
} from '@vicons/ionicons5'

defineProps<{
  collapsed: boolean
}>()

defineEmits<{
  toggle: []
}>()

const route = useRoute()

/** 导航项配置 */
const navItems = [
  { path: '/agents', label: 'Agent大厅', icon: GridOutline },
  { path: '/workflow', label: '工作流', icon: GitBranchOutline },
  { path: '/executions', label: '执行监控', icon: PulseOutline },
  { path: '/settings', label: '设置', icon: SettingsOutline }
]

/** 判断导航项是否处于激活状态 */
function isNavItemActive(path: string): boolean {
  return route.path.startsWith(path)
}
</script>

<style lang="scss" scoped>
.sidebar {
  position: fixed;
  left: 0;
  top: 0;
  bottom: 0;
  width: var(--sidebar-width);
  background: var(--color-surface);
  border-right: 1px solid var(--color-border-light);
  display: flex;
  flex-direction: column;
  transition: width var(--duration-normal) var(--ease-in-out);
  z-index: 50;

  &--collapsed {
    width: var(--sidebar-collapsed-width);
  }
}

// Logo区域
.sidebar__logo {
  display: flex;
  align-items: center;
  gap: var(--spacing-3);
  padding: var(--spacing-4) var(--spacing-4);
  height: var(--header-height);
  cursor: pointer;
  border-bottom: 1px solid var(--color-border-light);
}

.sidebar__logo-icon {
  flex-shrink: 0;
}

.sidebar__logo-text {
  font-size: var(--font-size-lg);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
  white-space: nowrap;
  overflow: hidden;
}

// 导航列表
.sidebar__nav {
  flex: 1;
  padding: var(--spacing-2);
  overflow-y: auto;
}

.sidebar__nav-item {
  display: flex;
  align-items: center;
  gap: var(--spacing-3);
  padding: var(--spacing-2) var(--spacing-3);
  margin-bottom: 2px;
  border-radius: var(--radius-base);
  color: var(--color-text-secondary);
  text-decoration: none;
  transition: all var(--duration-instant) var(--ease-in-out);
  cursor: pointer;
  white-space: nowrap;
  overflow: hidden;

  &:hover {
    color: var(--color-text-primary);
    background: var(--color-primary-light);
  }

  &--active {
    color: var(--color-primary);
    background: var(--color-primary-light);
    font-weight: var(--font-weight-medium);
  }
}

.sidebar__nav-label {
  font-size: var(--font-size-base);
  overflow: hidden;
  text-overflow: ellipsis;
}

// 底部折叠按钮
.sidebar__footer {
  padding: var(--spacing-2);
  border-top: 1px solid var(--color-border-light);
}

.sidebar__toggle {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  padding: var(--spacing-2);
  border: none;
  border-radius: var(--radius-base);
  background: transparent;
  color: var(--color-text-secondary);
  cursor: pointer;
  transition: all var(--duration-instant) var(--ease-in-out);

  &:hover {
    color: var(--color-text-primary);
    background: var(--color-primary-light);
  }
}
</style>
