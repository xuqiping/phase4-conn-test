// ============================================================
// Naive UI 主题接管层（DESIGN-TOKEN-0001 第三节状态矩阵）
// 按 [data-theme] 提供 GlobalThemeOverrides，与 CSS 变量同源同切
// 现状修复：此前 App.vue 仅 darkTheme，Naive 组件不随主题变化
// ============================================================

import { darkTheme } from 'naive-ui'
import type { GlobalThemeOverrides } from 'naive-ui'
import type { ThemeName } from '@/stores/theme'

/** 暗色主题清单（决定 Naive 内置暗色基座） */
export const DARK_THEMES: ThemeName[] = ['deep-space', 'dark-pro', 'cyber-glow', 'ye-mo']

/** Naive 内置主题基座：亮主题传 null 用默认亮色 */
export function getNaiveBaseTheme(name: ThemeName) {
  return DARK_THEMES.includes(name) ? darkTheme : null
}

interface InkScheme {
  primary: string
  primaryHover: string
  primaryPressed: string
  bodyColor: string
  cardColor: string
  modalColor: string
  popoverColor: string
  tableColor: string
  inputColor: string
  actionColor: string
  borderColor: string
  dividerColor: string
  textColorBase: string
  textColor1: string
  textColor2: string
  textColor3: string
  success: string
  warning: string
  error: string
  info: string
  /** Toast 染色底（Message 四态）：浅色主题用淡染、暗色主题用深染，正文统一 textColor1 保对比度 */
  toastInfoBg: string
  toastSuccessBg: string
  toastWarningBg: string
  toastErrorBg: string
}

/** 夜墨（暗）配色方案 */
const YE_MO: InkScheme = {
  primary: '#8FBCD4',
  primaryHover: '#A5CCE0',
  primaryPressed: '#7AACC4',
  bodyColor: '#151D29',
  cardColor: '#1C2634',
  modalColor: '#24303F',
  popoverColor: '#24303F',
  tableColor: '#1C2634',
  inputColor: 'rgba(143, 188, 212, 0.06)',
  actionColor: 'rgba(143, 188, 212, 0.08)',
  borderColor: '#2A3646',
  dividerColor: '#212C3A',
  textColorBase: '#DFE7EE',
  textColor1: '#DFE7EE',
  textColor2: '#9AABBC',
  textColor3: '#5E6E7E',
  success: '#63B98A',
  warning: '#D9A45B',
  error: '#D9564A',
  info: '#7FA3CC',
  toastInfoBg: '#26344A',
  toastSuccessBg: '#22392E',
  toastWarningBg: '#3A2F1E',
  toastErrorBg: '#3D2426'
}

/** 宣纸（明）配色方案 */
const XUAN_ZHI: InkScheme = {
  primary: '#35687F',
  primaryHover: '#2F5D72',
  primaryPressed: '#28505F',
  bodyColor: '#F5F1E6',
  cardColor: '#FDFBF4',
  modalColor: '#FFFEFA',
  popoverColor: '#FFFEFA',
  tableColor: '#FDFBF4',
  inputColor: 'rgba(53, 104, 127, 0.05)',
  actionColor: 'rgba(53, 104, 127, 0.06)',
  borderColor: '#D8D0BC',
  dividerColor: '#E4DCC8',
  textColorBase: '#26221C',
  textColor1: '#26221C',
  textColor2: '#6B655A',
  textColor3: '#9A927F',
  success: '#3E8E63',
  warning: '#B07A28',
  error: '#C03A2E',
  info: '#4A6E9E',
  toastInfoBg: '#E9EFF5',
  toastSuccessBg: '#E4F0E9',
  toastWarningBg: '#F5ECDC',
  toastErrorBg: '#F6E4E1'
}

const INK_SCHEMES: Partial<Record<ThemeName, InkScheme>> = {
  'ye-mo': YE_MO,
  'xuan-zhi': XUAN_ZHI
}

const FONT_BODY = `'Noto Sans SC', 'Inter', 'PingFang SC', 'Microsoft YaHei', -apple-system, sans-serif`

/** 由配色方案生成完整覆盖（common + 关键组件） */
function buildOverrides(s: InkScheme): GlobalThemeOverrides {
  return {
    common: {
      fontFamily: FONT_BODY,
      borderRadius: '6px',
      borderRadiusSmall: '4px',
      primaryColor: s.primary,
      primaryColorHover: s.primaryHover,
      primaryColorPressed: s.primaryPressed,
      primaryColorSuppl: s.primary,
      successColor: s.success,
      warningColor: s.warning,
      errorColor: s.error,
      infoColor: s.info,
      bodyColor: s.bodyColor,
      cardColor: s.cardColor,
      modalColor: s.modalColor,
      popoverColor: s.popoverColor,
      tableColor: s.tableColor,
      inputColor: s.inputColor,
      actionColor: s.actionColor,
      borderColor: s.borderColor,
      dividerColor: s.dividerColor,
      textColorBase: s.textColorBase,
      textColor1: s.textColor1,
      textColor2: s.textColor2,
      textColor3: s.textColor3
    },
    Button: {
      borderRadiusMedium: '6px',
      textColorPrimary: s.bodyColor === '#F5F1E6' ? '#F5F1E6' : '#151D29', // 主按钮上用反色文字
      textColorHoverPrimary: s.bodyColor === '#F5F1E6' ? '#F5F1E6' : '#151D29',
      textColorPressedPrimary: s.bodyColor === '#F5F1E6' ? '#F5F1E6' : '#151D29',
      textColorFocusPrimary: s.bodyColor === '#F5F1E6' ? '#F5F1E6' : '#151D29'
    },
    Card: {
      borderRadius: '6px',
      borderColor: s.borderColor
    },
    Menu: {
      borderRadius: '6px'
    },
    DataTable: {
      borderRadius: '6px',
      thColor: s.actionColor,
      borderColor: s.dividerColor,
      // P5 表格深化（ART-DIR-0002）：表头次级文字+中字重，行 hover 淡青
      thTextColor: s.textColor2,
      thFontWeight: '500',
      tdColorHover: s.bodyColor === '#F5F1E6' ? 'rgba(53, 104, 127, 0.05)' : 'rgba(143, 188, 212, 0.06)'
    },
    Dialog: {
      borderRadius: '8px'
    },
    Message: {
      borderRadius: '6px',
      // 四态 Toast：染色底 + 高对比正文（修复宣纸主题错误 Toast 对比度偏弱）
      colorInfo: s.toastInfoBg,
      textColorInfo: s.textColor1,
      iconColorInfo: s.info,
      colorSuccess: s.toastSuccessBg,
      textColorSuccess: s.textColor1,
      iconColorSuccess: s.success,
      colorWarning: s.toastWarningBg,
      textColorWarning: s.textColor1,
      iconColorWarning: s.warning,
      colorError: s.toastErrorBg,
      textColorError: s.textColor1,
      iconColorError: s.error
    },
    Tag: {
      borderRadius: '4px'
    }
  }
}

/** 旧三套主题的最小覆盖（只统一主色，保持其原气质） */
const LEGACY_PRIMARY: Record<string, string> = {
  'deep-space': '#4F7CFF',
  'dark-pro': '#10B981',
  'cyber-glow': '#E040FB'
}

export function getNaiveOverrides(name: ThemeName): GlobalThemeOverrides {
  const ink = INK_SCHEMES[name]
  if (ink) return buildOverrides(ink)
  // 旧主题：保持 Naive 默认暗色，仅对齐主色
  const primary = LEGACY_PRIMARY[name] || '#4F7CFF'
  return {
    common: {
      fontFamily: FONT_BODY,
      primaryColor: primary,
      primaryColorHover: primary,
      primaryColorPressed: primary,
      primaryColorSuppl: primary
    }
  }
}
