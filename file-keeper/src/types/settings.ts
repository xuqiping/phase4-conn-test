export type CloseBehavior = 'floating_ball' | 'tray' | 'exit'

export interface FloatingBallPosition {
  x: number
  y: number
  monitorId?: string
}

// 设置
export interface Settings {
  theme: 'light' | 'dark' | 'auto'
  language: 'zh-CN' | 'en-US'
  globalShortcut: string        // 全局快捷键
  clipboardShortcut: string     // 剪贴板面板快捷键
  screenshotShortcut: string    // 截图快捷键
  autoStart: boolean          // 开机自启
  closeBehavior: CloseBehavior  // 关闭主窗口后的行为
  floatingBallPosition?: FloatingBallPosition
  defaultView: 'grid' | 'list'  // 默认视图
  itemsPerPage: number          // 每页显示数量
  iconMode: 'real' | 'generic'  // 图标模式：真实图标或通用图标
}
