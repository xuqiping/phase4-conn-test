/**
 * 节点调色板清单（修复XI A1，2x 未解决①）——单一数据源：
 * 左侧调色板（CanvasView）与画布右键菜单（CanvasBoard）共用同一份 7 类节点定义，
 * 防两处各写一份清单漂移（新增节点类型只改这里）。
 * 修复XI A2 起 CanvasBoard 右键菜单消费同一常量。
 */
import type { Component } from 'vue'
import {
  DocumentTextOutline,
  ImageOutline,
  VideocamOutline,
  MusicalNotesOutline,
  CodeSlashOutline,
  FilmOutline,
  CubeOutline
} from '@vicons/ionicons5'

export interface PaletteItem {
  type: string
  label: string
  icon: Component
}

/** 7 类可添加节点（原 CanvasView 内联 palette 常量等价迁出，零行为变化）。 */
export const PALETTE_ITEMS: PaletteItem[] = [
  { type: 'text', label: '文本', icon: DocumentTextOutline },
  { type: 'image', label: '图片', icon: ImageOutline },
  { type: 'video', label: '视频', icon: VideocamOutline },
  { type: 'audio', label: '音频', icon: MusicalNotesOutline },
  { type: 'script', label: '脚本', icon: CodeSlashOutline },
  { type: 'storyboard', label: '分镜', icon: FilmOutline },
  { type: 'director', label: '导演台', icon: CubeOutline }
]
