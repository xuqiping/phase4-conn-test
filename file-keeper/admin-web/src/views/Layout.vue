<template>
  <n-layout has-sider style="height: 100vh">
    <n-layout-sider bordered collapse-mode="width" :collapsed-width="64" :width="200" show-trigger>
      <div class="p-4 text-center font-bold text-sm" style="color: var(--n-text-color)">
        File Keeper 管理后台
      </div>
      <n-menu :options="menuOptions" :value="currentRoute" @update:value="handleMenuSelect" />
    </n-layout-sider>
    <n-layout>
      <n-layout-header bordered style="height: 48px; display: flex; align-items: center; justify-content: flex-end; padding: 0 16px">
        <n-button text @click="handleLogout">
          退出登录
        </n-button>
      </n-layout-header>
      <n-layout-content style="padding: 16px">
        <router-view />
      </n-layout-content>
    </n-layout>
  </n-layout>
</template>

<script setup lang="ts">
import { computed, h } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { NLayout, NLayoutSider, NLayoutHeader, NLayoutContent, NMenu, NButton } from 'naive-ui'
import { PeopleOutline, StatsChartOutline } from '@vicons/ionicons5'
import { useAuthStore } from '@/stores/auth'

const router = useRouter()
const route = useRoute()
const authStore = useAuthStore()

const currentRoute = computed(() => {
  if (route.name === 'user-detail') return 'users'
  return (route.name as string) || 'dashboard'
})

const menuOptions = [
  {
    label: 'Dashboard',
    key: 'dashboard',
    icon: () => h(StatsChartOutline)
  },
  {
    label: '用户管理',
    key: 'users',
    icon: () => h(PeopleOutline)
  }
]

function handleMenuSelect(key: string) {
  router.push({ name: key })
}

async function handleLogout() {
  await authStore.logout()
  router.push({ name: 'login' })
}
</script>
