import { computed } from 'vue'
import { storeToRefs } from 'pinia'
import { useThemeStore, type ThemeKey } from '@/stores/theme'
import { NAIVE_TOKENS } from './naive'

/** 主题组合式函数：切换/当前主题/naive overrides 一处拿 */
export function useTheme() {
  const store = useThemeStore()
  const { current } = storeToRefs(store)

  const naiveOverrides = computed(() => NAIVE_TOKENS[current.value])

  return {
    current,
    naiveOverrides,
    setTheme: (name: ThemeKey) => store.setTheme(name)
  }
}
