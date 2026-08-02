import { ref, computed } from 'vue'
import zhCN from '../locales/zh-CN'
import en from '../locales/en'

const messages: Record<string, any> = {
  'zh-CN': zhCN,
  'en': en
}

const currentLocale = ref('zh-CN')
const STORAGE_KEY = 'app-locale'

// Load saved locale
function loadLocale() {
  try {
    const saved = localStorage.getItem(STORAGE_KEY)
    if (saved && messages[saved]) {
      currentLocale.value = saved
    }
  } catch {
    // ignore
  }
}

loadLocale()

export function useI18n() {
  const locale = computed(() => currentLocale.value)

  function t(key: string, params?: Record<string, string | number>): string {
    const keys = key.split('.')
    let value: any = messages[currentLocale.value]

    for (const k of keys) {
      if (value === undefined || value === null) return key
      value = value[k]
    }

    if (typeof value !== 'string') return key

    if (params) {
      return value.replace(/\{(\w+)\}/g, (match, paramKey) => {
        return String(params[paramKey] ?? match)
      })
    }

    return value
  }

  function setLocale(newLocale: string) {
    if (messages[newLocale]) {
      currentLocale.value = newLocale
      try {
        localStorage.setItem(STORAGE_KEY, newLocale)
      } catch {
        // ignore
      }
    }
  }

  function toggleLocale() {
    setLocale(currentLocale.value === 'zh-CN' ? 'en' : 'zh-CN')
  }

  return {
    locale,
    t,
    setLocale,
    toggleLocale
  }
}
