// ============================================================
// 主题状态管理 — Pinia Store
// 管理3套暗色主题的切换和持久化
// ============================================================

import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { STORAGE_KEYS, getStorage, setStorage } from '@/utils/storage'

/** 主题名称类型 */
export type ThemeName = 'deep-space' | 'dark-pro' | 'cyber-glow'

/** 主题元信息（用于主题选择器展示）*/
export interface ThemeMeta {
  name: ThemeName
  label: string
  description: string
  /** 预览色块：主色 + 渐变起始色 + 渐变结束色 */
  colors: {
    primary: string
    gradientStart: string
    gradientEnd: string
    bg: string
  }
}

/** 所有可选主题列表 */
export const THEME_LIST: ThemeMeta[] = [
  {
    name: 'deep-space',
    label: 'Deep Space',
    description: '深邃宇宙 — 冷蓝强调，玻璃拟态',
    colors: {
      primary: '#4F7CFF',
      gradientStart: '#4F7CFF',
      gradientEnd: '#9333EA',
      bg: '#060A13'
    }
  },
  {
    name: 'dark-pro',
    label: 'Dark Pro',
    description: '暗夜专业 — 绿色强调，视觉舒适',
    colors: {
      primary: '#10B981',
      gradientStart: '#10B981',
      gradientEnd: '#06B6D4',
      bg: '#0A0A0A'
    }
  },
  {
    name: 'cyber-glow',
    label: 'Cyber Glow',
    description: '赛博辉光 — 霓虹多色，发光边框',
    colors: {
      primary: '#E040FB',
      gradientStart: '#E040FB',
      gradientEnd: '#38BDF8',
      bg: '#0A0510'
    }
  }
]

export const useThemeStore = defineStore('theme', () => {
  // === 状态 ===
  const currentTheme = ref<ThemeName>(
    getStorage<ThemeName>(STORAGE_KEYS.THEME) || 'deep-space'
  )

  // === 计算属性 ===
  /** 当前主题的元信息 */
  const currentThemeMeta = computed(() =>
    THEME_LIST.find(t => t.name === currentTheme.value) || THEME_LIST[0]
  )

  // === Actions ===

  /**
   * 设置主题
   * 更新CSS变量（通过data-theme属性）并持久化到localStorage
   */
  function setTheme(theme: ThemeName) {
    currentTheme.value = theme
    // 设置根元素的 data-theme 属性，触发CSS变量切换
    document.documentElement.setAttribute('data-theme', theme)
    // 持久化
    setStorage(STORAGE_KEYS.THEME, theme)
  }

  /**
   * 初始化主题（应用启动时调用）
   */
  function initTheme() {
    setTheme(currentTheme.value)
  }

  return {
    currentTheme,
    currentThemeMeta,
    setTheme,
    initTheme
  }
})
