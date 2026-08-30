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
        <!-- 高山流水 · 晨昏云雾背景层（仅夜墨/宣纸主题可见） -->
        <div
          class="main-layout__mist"
          :class="`main-layout__mist--${mistPeriod}`"
          :style="{ '--mist-img': `url(${mistImage})` }"
          aria-hidden="true"
        ></div>
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
import { ref, computed, watch, onMounted } from 'vue'
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

// 晨昏判定：6:00–17:59 为晨，其余为暮（驱动云雾背景层两版）
const mistPeriod = computed(() => {
  const h = new Date().getHours()
  return h >= 6 && h < 18 ? 'dawn' : 'dusk'
})

// 晨昏云雾美术资产（ART-ASSET-0002，已验收回填）
import mistDawn from '@/assets/art/workbench/mist-dawn.webp'
import mistDusk from '@/assets/art/workbench/mist-dusk.webp'
const mistImage = computed(() => (mistPeriod.value === 'dawn' ? mistDawn : mistDusk))

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

<!-- ============================================================
     高山流水 · 主内容区云雾背景层（仅 ye-mo / xuan-zhi 生效）
     现为纯 CSS 渐变占位；ART-ASSET-0002 回填后把 background-image
     换成 mist-dawn/mist-dusk 图片即可（--mist-img 钩子已留）
     ============================================================ -->
<style lang="scss">
.main-layout__mist {
  display: none; // 旧主题不出现
}

[data-theme="ye-mo"],
[data-theme="xuan-zhi"] {
  .main-layout__content {
    position: relative;
  }

  .main-layout__mist {
    display: block;
    position: absolute;
    inset: 0;
    z-index: 0;
    pointer-events: none;
    opacity: 0.18;
    background-image: var(--mist-img, none);
    background-size: cover;
    background-position: center top;
    // 占位渐变：柔光自顶部弥漫 + 底部远山剪影
    background-color: transparent;

    &--dawn {
      background-image: var(--mist-img, linear-gradient(180deg,
        rgba(var(--color-primary-rgb), 0.10) 0%,
        transparent 45%));
    }

    &--dusk {
      background-image: var(--mist-img, linear-gradient(180deg,
        rgba(138, 128, 163, 0.12) 0%,
        transparent 45%));
    }
  }

  // 内容压在云雾层之上
  .main-layout__content > *:not(.main-layout__mist) {
    position: relative;
    z-index: 1;
  }
}

@media (prefers-reduced-motion: reduce) {
  .main-layout__mist { opacity: 0.06 !important; }
}
</style>
