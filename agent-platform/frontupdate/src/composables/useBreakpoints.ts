import { ref, onMounted, onUnmounted, type Ref } from 'vue'

/**
 * 响应式断点。无 @vueuse 依赖，基于 window.matchMedia。
 * mobile  ≤ 768px
 * tablet  ≤ 1024px
 *
 * 每个调用组件各自注册监听，开销可忽略；SSR 无 window 时返回 false。
 */
const MOBILE_MAX = 768
const TABLET_MAX = 1024

export interface Breakpoints {
  isMobile: Ref<boolean>
  isTablet: Ref<boolean>
}

export function useBreakpoints(): Breakpoints {
  const isMobile = ref(false)
  const isTablet = ref(false)

  let mobileMql: MediaQueryList | null = null
  let tabletMql: MediaQueryList | null = null

  const update = () => {
    if (typeof window === 'undefined') return
    isMobile.value = window.matchMedia(`(max-width: ${MOBILE_MAX}px)`).matches
    isTablet.value = window.matchMedia(`(max-width: ${TABLET_MAX}px)`).matches
  }

  const onChange = () => update()

  onMounted(() => {
    if (typeof window === 'undefined') return
    mobileMql = window.matchMedia(`(max-width: ${MOBILE_MAX}px)`)
    tabletMql = window.matchMedia(`(max-width: ${TABLET_MAX}px)`)
    update()
    // addEventListener 在现代浏览器可用；旧 Safari 用 addListener
    if (mobileMql.addEventListener) {
      mobileMql.addEventListener('change', onChange)
      tabletMql?.addEventListener('change', onChange)
    } else {
      // 兼容旧版 Safari (< 14)
      ;(mobileMql as MediaQueryList).addListener(onChange)
      ;(tabletMql as MediaQueryList).addListener(onChange)
    }
  })

  onUnmounted(() => {
    if (mobileMql?.removeEventListener) {
      mobileMql.removeEventListener('change', onChange)
    } else if (mobileMql) {
      ;(mobileMql as MediaQueryList).removeListener(onChange)
    }
    if (tabletMql?.removeEventListener) {
      tabletMql.removeEventListener('change', onChange)
    } else if (tabletMql) {
      ;(tabletMql as MediaQueryList).removeListener(onChange)
    }
  })

  return { isMobile, isTablet }
}
