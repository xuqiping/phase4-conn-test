// 设置
export interface Settings {
  theme: 'light' | 'dark' | 'auto'
  language: 'zh-CN' | 'en-US'
  globalShortcut: string        // 全局快捷键
  autoStart: boolean          // 开机自启
  minimizeToTray: boolean       // 最小化到托盘
  defaultView: 'grid' | 'list'  // 默认视图
  itemsPerPage: number          // 每页显示数量
}
