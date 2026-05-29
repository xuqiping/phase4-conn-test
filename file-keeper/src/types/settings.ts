// 设置
export interface Settings {
  theme: 'light' | 'dark' | 'auto'
  language: 'zh-CN' | 'en-US'
  globalShortcut: string        // 全局快捷键
  clipboardShortcut: string     // 剪贴板面板快捷键
  autoStart: boolean          // 开机自启
  minimizeToTray: boolean       // 最小化到托盘
  defaultView: 'grid' | 'list'  // 默认视图
  itemsPerPage: number          // 每页显示数量
  iconMode: 'real' | 'generic'  // 图标模式：真实图标或通用图标
}
