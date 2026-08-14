<script setup lang="ts">
import { NConfigProvider, NMessageProvider, darkTheme } from 'naive-ui'
import { useTheme } from './theme/useTheme'

// 皮肤层唯一入口：naive 组件吃 themeOverrides，自写组件吃 CSS 变量，两者同源（theme/naive.ts ↔ tokens/*.css）
const { naiveOverrides } = useTheme()
</script>

<template>
  <n-config-provider :theme="darkTheme" :theme-overrides="naiveOverrides">
    <n-message-provider>
      <router-view />
    </n-message-provider>
  </n-config-provider>
</template>
