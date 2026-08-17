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

/** 故事板一段（C13，画布上视频产出物的投影：节点 id + fileId + 标签 + 时长 + 会话级预览流）。 */
export interface StoryboardSegment {
  nodeId: string
  fileId: string
  label: string
  /** 段时长（秒），未知则 undefined（生成型视频取 duration，截取型可由用户后续补）。 */
  durationSec?: number
  /** 会话级 objectURL（带鉴权 fetch 产物），顺序预览用。 */
  previewUrl?: string
}

/**
 * @引用候选（S13 节点 @引用机制，设计 §十三）。
 * `@` 唤起的选择器列出当前节点可达的祖先节点（反向 BFS 沿 edges）；
 * 选定后插入占位符 `@{{node:<id>}}`（资产占位符 `@{{asset:<id>}}` 同引擎，MVP 选择器仅列节点）。
 * label 服务于人脑消歧，占位符本体存 id（重命名不断链，L8）。
 */
export interface MentionCandidate {
  /** 占位符种类：node=祖先节点 / asset=资产库资产（设计 §十三，资产不受祖先链约束）；
   *  image/video/audio=视频生成页会话附件引用（H，序号化插值，非画布祖先链）。 */
  kind: 'node' | 'asset' | 'image' | 'video' | 'audio'
  /** node→节点 id / asset→资产 id / image|video|audio→会话附件稳定 id（占位符本体，重命名/重排不断链）。 */
  id: string
  /** 显示名（节点 label / 资产名 / 附件序号名；重命名或重排后选择器即时跟随，占位符不变）。 */
  label: string
}

/**
 * 资产绑定徽标（S12 画布↔资产库打通，L5/L6）。
 * 节点存了资产引用后显示「来自资产·name vN」；hasUpdate=true 提示「有新版」（不自动变）。
 * 平铺三字段存 node.data（assetId/assetName/assetVersion），hasUpdate 由属性面板选中时
 * 拉 asset.get 比对当前版动态算出（非持久化，会话级）。
 */
export interface AssetBadge {
  name: string
  version: number
  hasUpdate?: boolean
}

/** 画布节点数据（C3 起按节点类型扩展各属性） */
export interface CanvasNodeData {
  /** 节点标签 */
  label: string
  /** 节点类型（text/image/video/audio/script） */
  nodeKind?: 'text' | 'image' | 'video' | 'audio' | 'script' | 'storyboard' | string
  /** 运行态（C4+ 产出触发用） */
  status?: CanvasNodeStatus
  /** S12：节点已绑定的资产 id（库存→画布引用 / 画布→库入库回写）。 */
  assetId?: number
  /** S12：绑定时的资产名（徽标展示，不随资产改名自动变）。 */
  assetName?: string
  /** S12：绑定时的版本号（版本快照语义，资产升版不影响已引用方，设计 §六）。 */
  assetVersion?: number
  /** S12：会话级标记——资产已有更高版本（属性面板选中时比对算出，不入快照）。 */
  assetHasUpdate?: boolean
  /**
   * 2x 四轮 S2：手动调过的节点宽高（px，整数）。拖角柄 resize-end 时写入，
   * 快照持久化；老节点无字段 = 默认宽 200、高随内容（min 64）。
   * 渲染真源是 node.style（vue-flow wrapper），由 loadSnapshot/addNode 从本字段推导，
   * 保存时剥 style 只留 data——单一真相源，不两处漂移。
   */
  width?: number
  /** 同上；仅用户显式拉过高度才存在（未拉过 = 高度自适应内容）。 */
  height?: number
  [key: string]: unknown
}

/** 画布节点（结构兼容 vue-flow Node，但用本地扁平类型避免深递归） */
export interface CanvasNode {
  id: string
  type: string
  position: CanvasPosition
  data: CanvasNodeData
  /**
   * 会话级尺寸样式（vue-flow wrapper 上的 width/height px）。由 data.width/height 推导
   * （loadSnapshot/addNode），resize 拖动时 @vue-flow/node-resizer 实时改写；
   * 保存时剥离——持久化真源只有 data.width/height（2x 四轮 S2）。
   */
  style?: Record<string, string>
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
  /** 选中态 class（watch selectedEdgeId 注入，高亮可删边）。非持久化语义字段，存库无副作用。 */
  class?: string
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
