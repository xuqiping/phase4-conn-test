// 工具函数 - 路径处理
export function getFileName(path: string): string {
  return path.split(/[\\/]/).pop() || path
}

export function getFileExtension(path: string): string {
  const name = getFileName(path)
  const lastDot = name.lastIndexOf('.')
  return lastDot > 0 ? name.slice(lastDot + 1) : ''
}

export function normalizePath(path: string): string {
  return path.replace(/\\/g, '/')
}
