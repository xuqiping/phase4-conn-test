<template>
  <header class="app-header">
    <!-- 左侧：折叠按钮 + 面包屑 -->
    <div class="app-header__left">
      <button class="app-header__menu-btn" @click="$emit('toggleSidebar')">
        <n-icon size="20" :component="MenuOutline" />
      </button>
      <span class="app-header__page-title">{{ pageTitle }}</span>
    </div>

    <!-- 右侧：搜索 + 主题切换 + 用户 -->
    <div class="app-header__right">
      <!-- 搜索框（占位） -->
      <n-input
        class="app-header__search"
        placeholder="搜索..."
        size="small"
        clearable
        round
      >
        <template #prefix>
          <n-icon :component="SearchOutline" />
        </template>
      </n-input>

      <!-- 主题切换按钮 -->
      <n-tooltip trigger="hover">
        <template #trigger>
          <button class="app-header__icon-btn" @click="showThemeSwitcher = true">
            <n-icon size="18" :component="ColorPaletteOutline" />
          </button>
        </template>
        切换主题
      </n-tooltip>

      <!-- 用户头像 + 下拉菜单 -->
      <n-dropdown :options="userMenuOptions" @select="handleUserMenu">
        <div class="app-header__user">
          <n-avatar
            :size="32"
            round
            :style="{ background: `linear-gradient(135deg, var(--color-gradient-start), var(--color-gradient-end))` }"
          >
            {{ userInitial }}
          </n-avatar>
          <span class="app-header__username">{{ authStore.userInfo?.username }}</span>
        </div>
      </n-dropdown>
    </div>

    <!-- 主题切换弹窗 -->
    <ThemeSwitcher v-model:show="showThemeSwitcher" />
  </header>
</template>

<script setup lang="ts">
import { ref, computed, h } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { NIcon, NInput, NTooltip, NDropdown, NAvatar } from 'naive-ui'
import {
  MenuOutline,
  SearchOutline,
  ColorPaletteOutline,
  PersonOutline,
  LogOutOutline
} from '@vicons/ionicons5'
import { useAuthStore } from '@/stores/auth'
import ThemeSwitcher from '@/components/ThemeSwitcher.vue'

defineEmits<{
  toggleSidebar: []
}>()

const route = useRoute()
const router = useRouter()
const authStore = useAuthStore()

const showThemeSwitcher = ref(false)

/** 页面标题（从路由meta获取） */
const pageTitle = computed(() => (route.meta.title as string) || '多Agent智能体平台')

/** 用户名首字母 */
const userInitial = computed(() => {
  const name = authStore.userInfo?.username || 'U'
  return name.charAt(0).toUpperCase()
})

/** 用户下拉菜单选项 */
const userMenuOptions = [
  {
    label: '个人信息',
    key: 'profile',
    icon: () => h(NIcon, null, { default: () => h(PersonOutline) })
  },
  {
    type: 'divider',
    key: 'd1'
  },
  {
    label: '退出登录',
    key: 'logout',
    icon: () => h(NIcon, null, { default: () => h(LogOutOutline) })
  }
]

/** 处理用户菜单选择 */
async function handleUserMenu(key: string) {
  if (key === 'logout') {
    await authStore.logout()
    router.push('/login')
  }
}
</script>

<style lang="scss" scoped>
.app-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: var(--header-height);
  padding: 0 var(--spacing-6);
  background: var(--color-surface);
  border-bottom: 1px solid var(--color-border-light);
  flex-shrink: 0;
  backdrop-filter: blur(12px);
  background: rgba(var(--color-primary-rgb), 0.03);
}

// 左侧
.app-header__left {
  display: flex;
  align-items: center;
  gap: var(--spacing-3);
}

.app-header__menu-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
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

.app-header__page-title {
  font-size: var(--font-size-md);
  font-weight: var(--font-weight-semibold);
  color: var(--color-text-primary);
}

// 右侧
.app-header__right {
  display: flex;
  align-items: center;
  gap: var(--spacing-3);
}

.app-header__search {
  width: 200px;
}

.app-header__icon-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border: none;
  border-radius: var(--radius-base);
  background: transparent;
  color: var(--color-text-secondary);
  cursor: pointer;
  transition: all var(--duration-instant) var(--ease-in-out);

  &:hover {
    color: var(--color-primary);
    background: var(--color-primary-light);
  }
}

// 用户头像
.app-header__user {
  display: flex;
  align-items: center;
  gap: var(--spacing-2);
  cursor: pointer;
  padding: var(--spacing-1) var(--spacing-2);
  border-radius: var(--radius-base);
  transition: background var(--duration-instant);

  &:hover {
    background: var(--color-primary-light);
  }
}

.app-header__username {
  font-size: var(--font-size-sm);
  color: var(--color-text-primary);
}
</style>
