import type { ThemeKey } from '@/stores/theme'

/** 主题元信息：切换器 UI 展示用（名称/预览色/一句话描述） */
export interface ThemeMeta {
  key: ThemeKey
  name: string
  /** 预览色点（主强调色 + 辅助色 + 底色） */
  swatches: [string, string, string]
  desc: string
}

export const THEMES: ThemeMeta[] = [
  {
    key: 'neon-pulse',
    name: '霓虹 AI',
    swatches: ['#7C5CFF', '#22D3EE', '#070B14'],
    desc: '深空底 + 玻璃拟态 + 辉光，AI 在发光'
  },
  {
    key: 'calm-slate',
    name: '冷静极简',
    swatches: ['#5E6AD2', '#8A8F98', '#0F0F10'],
    desc: 'Linear 式灰阶层级，零装饰专业工具感'
  },
  {
    key: 'hybrid-glow',
    name: '混合',
    swatches: ['#5E6AD2', '#7C5CFF', '#101013'],
    desc: '页面克制 + 画布卡片出彩'
  },
  {
    key: 'cineon',
    name: '影像工坊',
    swatches: ['#F59E0B', '#EC4899', '#12100E'],
    desc: 'AI 视频工作站：暖黑 + 胶片橙金 + 时间轴语言'
  }
]
