// 分组
export interface Group {
  id: string            // UUID
  name: string         // 分组名称
  color?: string            // 颜色标识
  icon?: string             // 图标
  order: number                 // 排序
  createdAt: number
}
