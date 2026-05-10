// 进程信息
export interface ProcessInfo {
  pid: number            // 进程ID
  name: string          // 进程名
  path?: string                 // 可执行文件路径
  windowTitle?: string          // 窗口标题
  associatedFile?: string       // 关联的文件路径
}
