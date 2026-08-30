<template>
  <!-- Naive UI ConfigProvider：主题基座与 themeOverrides 随当前主题计算（高山流水双主题接入） -->
  <n-config-provider
    :theme="naiveBaseTheme"
    :theme-overrides="naiveOverrides"
    :locale="zhCN"
    :date-locale="dateZhCN"
  >
    <n-message-provider>
      <n-dialog-provider>
        <n-notification-provider>
          <router-view />
        </n-notification-provider>
      </n-dialog-provider>
    </n-message-provider>
  </n-config-provider>
</template>

<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { zhCN, dateZhCN } from 'naive-ui'
import { useThemeStore } from '@/stores/theme'
import { getNaiveBaseTheme, getNaiveOverrides } from '@/styles/naive-overrides'

const themeStore = useThemeStore()

// 启动即初始化主题（含登录页等不经 MainLayout 的路由）
onMounted(() => {
  themeStore.initTheme()
})

const naiveBaseTheme = computed(() => getNaiveBaseTheme(themeStore.currentTheme))
const naiveOverrides = computed(() => getNaiveOverrides(themeStore.currentTheme))
</script>

<style lang="scss">
// 根组件无额外样式，所有样式通过全局文件控制
</style>
