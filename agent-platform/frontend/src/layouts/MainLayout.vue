<template>
  <div class="main-layout">
    <!-- 左侧侧栏 -->
    <Sidebar
      :collapsed="sidebarCollapsed"
      @toggle="toggleSidebar"
    />

    <!-- 右侧主区域 -->
    <div class="main-layout__right" :class="{ 'main-layout__right--expanded': sidebarCollapsed }">
      <!-- 顶部栏 -->
      <AppHeader @toggle-sidebar="toggleSidebar" />

      <!-- 主内容区 -->
      <main class="main-layout__content">
        <router-view v-slot="{ Component }">
          <transition name="fade" mode="out-in">
            <component :is="Component" />
          </transition>
        </router-view>
      </main>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import Sidebar from '@/components/Sidebar.vue'
import AppHeader from '@/components/AppHeader.vue'
import { useAuthStore } from '@/stores/auth'
import { useThemeStore } from '@/stores/theme'
import { getStorage, setStorage, STORAGE_KEYS } from '@/utils/storage'

const authStore = useAuthStore()
const themeStore = useThemeStore()

// 侧栏折叠状态
const sidebarCollapsed = ref(getStorage<boolean>(STORAGE_KEYS.SIDEBAR_COLLAPSED) || false)

function toggleSidebar() {
  sidebarCollapsed.value = !sidebarCollapsed.value
  setStorage(STORAGE_KEYS.SIDEBAR_COLLAPSED, sidebarCollapsed.value)
}

// 初始化：获取用户信息 + 应用主题
onMounted(async () => {
  themeStore.initTheme()
  if (authStore.isLoggedIn && !authStore.userInfo) {
    await authStore.fetchUserInfo()
  }
})
</script>

<style lang="scss" scoped>
.main-layout {
  display: flex;
  width: 100vw;
  height: 100vh;
  overflow: hidden;
  background: var(--color-bg);
}

.main-layout__right {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  margin-left: var(--sidebar-width);
  transition: margin-left var(--duration-normal) var(--ease-in-out);

  &--expanded {
    margin-left: var(--sidebar-collapsed-width);
  }
}

.main-layout__content {
  flex: 1;
  overflow-y: auto;
  overflow-x: hidden;
  padding: var(--spacing-6);
  background: var(--color-bg);
}

// 页面切换过渡
.fade-enter-active,
.fade-leave-active {
  transition: opacity var(--duration-normal) var(--ease-in-out);
}

.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}
</style>
