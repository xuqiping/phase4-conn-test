// 文件工具函数
// 共享图标推导和分组映射逻辑

/**
 * 根据文件扩展名推导图标类型
 */
export function deriveIconFromExt(filename: string): string {
  const ext = filename.split('.').pop()?.toLowerCase() || ''
  const map: Record<string, string> = {
    doc: 'word', docx: 'word',
    xls: 'excel', xlsx: 'excel',
    png: 'image', jpg: 'image', jpeg: 'image', gif: 'image',
    js: 'code', ts: 'code', py: 'code', java: 'code'
  }
  return map[ext] || 'file'
}

/**
 * 根据当前分组决定新文件归属
 * 如果当前在 "全部" 或 "最近打开"，分配到首个自定义分组，否则分配到当前分组
 */
export function resolveGroupId(currentGroupId: string, customGroupId?: string): string {
  if (currentGroupId === 'all' || currentGroupId === 'recent') {
    return customGroupId || 'all'
  }
  return currentGroupId
}
