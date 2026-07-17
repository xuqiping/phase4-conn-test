<template>
  <div class="main-layout" :class="{ 'main-layout--mobile': isMobile }">
    <!-- 左侧侧栏（桌面：固定；移动：抽屉） -->
    <Sidebar
      :collapsed="sidebarCollapsed"
      :mobile-open="mobileSidebarOpen"
      :is-mobile="isMobile"
      @toggle="toggleSidebar"
    />

    <!-- 移动端遮罩层 -->
    <div
      v-if="isMobile && mobileSidebarOpen"
      class="main-layout__overlay"
      @click="closeMobileSidebar"
    ></div>

    <!-- 右侧主区域 -->
    <div class="main-layout__right" :class="{ 'main-layout__right--expanded': sidebarCollapsed && !isMobile }">
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
import { ref, watch, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import Sidebar from '@/components/Sidebar.vue'
import AppHeader from '@/components/AppHeader.vue'
import { useAuthStore } from '@/stores/auth'
import { useThemeStore } from '@/stores/theme'
import { getStorage, setStorage, STORAGE_KEYS } from '@/utils/storage'
import { useBreakpoints } from '@/composables/useBreakpoints'

const authStore = useAuthStore()
const themeStore = useThemeStore()
const route = useRoute()
const { isMobile } = useBreakpoints()

// 侧栏折叠状态（桌面端）
const sidebarCollapsed = ref(getStorage<boolean>(STORAGE_KEYS.SIDEBAR_COLLAPSED) || false)

// 移动端抽屉开关
const mobileSidebarOpen = ref(false)

function toggleSidebar() {
  if (isMobile.value) {
    mobileSidebarOpen.value = !mobileSidebarOpen.value
  } else {
    sidebarCollapsed.value = !sidebarCollapsed.value
    setStorage(STORAGE_KEYS.SIDEBAR_COLLAPSED, sidebarCollapsed.value)
  }
}

function closeMobileSidebar() {
  mobileSidebarOpen.value = false
}

// 路由切换时关闭移动端抽屉（点导航项后自动收起）
watch(() => route.path, () => {
  if (isMobile.value) mobileSidebarOpen.value = false
})

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

// 移动端遮罩
.main-layout__overlay {
  position: fixed;
  inset: 0;
  background: var(--color-overlay);
  z-index: 40;
  backdrop-filter: blur(2px);
  animation: fade-in var(--duration-fast) var(--ease-out);
}

// 移动端布局
.main-layout--mobile {
  .main-layout__right {
    margin-left: 0;
  }

  .main-layout__content {
    padding: var(--spacing-3);
  }
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
