/** mock 数据类型。状态枚举对齐 frontend 的 CanvasNodeStatus，合回时零改接口。 */

export type CanvasNodeStatus = 'idle' | 'running' | 'success' | 'failed'

export type NodeKind = 'text' | 'image' | 'video' | 'audio' | 'script' | 'storyboard'

export interface MockNodeData {
  label?: string
  kind: NodeKind
  status: CanvasNodeStatus
  /** 底部元信息 */
  durationMs?: number
  tokens?: number
  /** 文本节点 */
  prompt?: string
  outputText?: string
  /** 图像/视频节点 */
  ratio?: '16:9' | '1.85:1'
  durationSec?: number
  /** 脚本节点 */
  lines?: number
  firstLine?: string
  /** 分镜节点 */
  shots?: number
  /** 场记板序号（T4 母题，其他主题也展示） */
  sceneNo?: string
}

export interface MockNode {
  id: string
  type: NodeKind
  position: { x: number; y: number }
  data: MockNodeData
}

export interface MockEdge {
  id: string
  source: string
  target: string
}

export interface ChatMessage {
  id: string
  role: 'user' | 'ai'
  /** kind: 纯文本 / 代码块 / 引用卡 */
  kind: 'text' | 'code' | 'quote'
  content: string
  /** quote 专用：引用来源名 */
  quoteFrom?: string
  time: string
}

export interface ChatSession {
  id: string
  title: string
  messages: ChatMessage[]
}

export interface AgentItem {
  id: string
  name: string
  desc: string
  tags: string[]
  runs: number
  rating: number
}

export type WorkflowStatus = 'draft' | 'running' | 'success' | 'failed'

export interface WorkflowItem {
  id: string
  name: string
  status: WorkflowStatus
  nodeCount: number
  lastRun: string
  duration: string
  owner: string
}
