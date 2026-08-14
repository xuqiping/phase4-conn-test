<script setup lang="ts">
import { ref, provide } from 'vue'
import AppSidebar from '@/components/app/Sidebar.vue'
import TopBar from '@/components/app/TopBar.vue'

// 侧边栏折叠态：本地 ref + provide 给 Sidebar（规模小，不进 store）
const collapsed = ref(false)
provide('sidebarCollapsed', collapsed)
</script>

<template>
  <div class="main-layout">
    <AppSidebar v-model:collapsed="collapsed" />
    <div class="main-layout__right">
      <TopBar />
      <main class="main-layout__content">
        <router-view />
      </main>
    </div>
  </div>
</template>

<style lang="scss" scoped>
.main-layout {
  display: flex;
  height: 100%;

  &__right {
    flex: 1;
    min-width: 0; // 防子内容撑破
    display: flex;
    flex-direction: column;
  }

  &__content {
    flex: 1;
    overflow: auto;
    background: var(--sf-0);
  }
}
</style>
