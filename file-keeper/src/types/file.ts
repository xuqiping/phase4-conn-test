// 文件收藏项
export interface FileItem {
  id: string                // UUID
  name: string            // 显示名称
  path: string            // 文件绝对路径
  type: 'file' | 'folder'       // 类型
  icon?: string               // Base64图标缓存
  tags: string[]              // 标签
  groupId: string               // 所属分组ID
  description?: string          // 备注
  createdAt: number             // 创建时间戳
  lastOpened?: number           // 最后打开时间
  openCount: number          // 打开次数
  orderIndex: number            // 排序索引
}
