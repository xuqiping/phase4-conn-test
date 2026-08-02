// ============================================================
// 主题切换Composable
// 封装主题初始化和切换逻辑，供组件使用
// ============================================================

import { ref, onMounted } from 'vue'
import { useThemeStore, type ThemeName } from '@/stores/theme'

/**
 * 主题切换composable
 *
 * 使用方式：
 * ```vue
 * <script setup>
 * const { currentTheme, setTheme } = useTheme()
 * </script>
 * ```
 */
export function useTheme() {
  const themeStore = useThemeStore()

  const currentTheme = ref<ThemeName>(themeStore.currentTheme)

  /**
   * 设置主题
   * @param theme 主题名称
   */
  function setTheme(theme: ThemeName) {
    themeStore.setTheme(theme)
    currentTheme.value = theme
  }

  /**
   * 初始化主题
   * 从localStorage读取并应用到DOM
   */
  function initTheme() {
    themeStore.initTheme()
    currentTheme.value = themeStore.currentTheme
  }

  // 组件挂载时自动初始化主题
  onMounted(() => {
    initTheme()
  })

  return {
    currentTheme,
    setTheme,
    initTheme
  }
}
