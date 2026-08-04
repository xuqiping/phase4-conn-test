// ============================================================
// 无限画布类型定义（本地类型，规避 vue-flow Node/Edge 默认泛型深递归 TS2589）
// 范式同 types/workflow.ts 的 WorkflowNode/WorkflowEdge
// ============================================================

/** 画布节点位置 */
export interface CanvasPosition {
  x: number
  y: number
}

/** 节点运行态（C4+ 产出触发用；C3 骨架先展示状态） */
export type CanvasNodeStatus = 'idle' | 'running' | 'success' | 'failed'

/** 焦点编辑框选矩形（C10，相对 stage px；提取质量依赖后续生图/分割模型）。 */
export interface CropRect {
  x: number
  y: number
  w: number
  h: number
}

/** 画布节点数据（C3 起按节点类型扩展各属性） */
export interface CanvasNodeData {
  /** 节点标签 */
  label: string
  /** 节点类型（text/image/video/audio/script） */
  nodeKind?: 'text' | 'image' | 'video' | 'audio' | 'script' | string
  /** 运行态（C4+ 产出触发用） */
  status?: CanvasNodeStatus
  [key: string]: unknown
}

/** 画布节点（结构兼容 vue-flow Node，但用本地扁平类型避免深递归） */
export interface CanvasNode {
  id: string
  type: string
  position: CanvasPosition
  data: CanvasNodeData
  /** 运行态状态（C4+ 节点产出触发用） */
  class?: string
}

/** 画布连线 */
export interface CanvasEdge {
  id: string
  source: string
  target: string
  sourceHandle?: string | null
  targetHandle?: string | null
  type?: string
  animated?: boolean
  style?: Record<string, string | number>
}

/** 画布视口（缩放/平移） */
export interface CanvasViewport {
  x: number
  y: number
  zoom: number
}

/** 画布快照（持久化结构，对应后端 canvases.snapshot JSONB） */
export interface CanvasSnapshot {
  nodes: CanvasNode[]
  edges: CanvasEdge[]
  viewport?: CanvasViewport
}
