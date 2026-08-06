// ============================================================
// 项目资产库类型定义（对齐后端 com.superprogrammer.asset.dto.*）
// 设计方案 §二/§三/§五/§六/§七/§八；plan §S1-S13
// ============================================================

/** 轴A 媒体类型标签（项目受控词汇 key，可自定义；V60 前固定五类，现可扩展如「地图」）。 */
export type AssetMediaType = string

/** 默认媒体类型 key（V60 受控词汇默认五项，label/图标兜底用）。 */
export const DEFAULT_MEDIA_TYPES = ['PROMPT', 'SCRIPT', 'IMAGE', 'VIDEO', 'AUDIO'] as const

/** 处理类别（系统固定四类，V60 §C1b；决定编辑器/mime/预览/gen_meta 链路）。 */
export type MediaCategory = 'TEXT' | 'IMAGE' | 'VIDEO' | 'AUDIO'

/** 媒体类型受控词汇项 {key,category}（V60 两层设计）。 */
export interface MediaTypeDef {
  key: string
  category: MediaCategory
}

/** 生命周期状态机（设计 §六）：草稿→已定稿→归档。 */
export type AssetStatus = 'DRAFT' | 'LOCKED' | 'ARCHIVED'

/** 项目角色（设计 §七 7.2）：OWNER 不落成员表，由 owner_id 合成。 */
export type ProjectRole = 'OWNER' | 'EDITOR' | 'VIEWER'

/** 重复入库处理模式（plan §S7 / L5）。 */
export type CanvasImportMode = 'NEW_VERSION' | 'NEW_ASSET'

// ---------- 项目 ----------

/** 项目视图（ProjectVO）。role=当前用户在本项目的角色。 */
export interface AssetProjectVO {
  id: number
  name: string
  description?: string
  coverFileId?: string
  ownerId: number
  /** 叙事角色受控词汇桶（数组）。 */
  narrativeRoles: string[]
  /** 媒体类型受控词汇桶（V60，{key,category}）。 */
  mediaTypes: MediaTypeDef[]
  role: ProjectRole
  createdAt: string
  updatedAt?: string
}

export interface ProjectCreateRequest {
  name: string
  description?: string
  narrativeRoles?: string[]
}

export interface ProjectUpdateRequest {
  name?: string
  description?: string
  /** null=不改；数组=覆盖；删桶联动 L10。 */
  narrativeRoles?: string[] | null
  /** null=不改；数组=覆盖；删 type 联动同 category 迁移（V60）。 */
  mediaTypes?: MediaTypeDef[] | null
  coverFileId?: string | null
}

// ---------- 成员 ----------

/** 成员视图（MemberVO，含合成的 owner 行）。 */
export interface MemberVO {
  userId: number
  role: ProjectRole
  isOwner: boolean
  grantedBy?: number
  grantedAt?: string
}

export interface MemberAddRequest {
  userId: number
  role: 'VIEWER' | 'EDITOR'
}

export interface MemberRoleUpdateRequest {
  role: 'VIEWER' | 'EDITOR'
}

export interface TransferRequest {
  toUserId: number
}

// ---------- 资产 ----------

/** 资产视图（AssetVO）。列表省略 content；详情带 content+roleKeys+fileId。 */
export interface AssetVO {
  id: number
  projectId: number
  mediaType: AssetMediaType
  /** 处理类别（V60 TEXT/IMAGE/VIDEO/AUDIO；前端编辑器/预览据此分流）。 */
  mediaCategory?: MediaCategory
  name: string
  description?: string
  tags?: string[]
  status: AssetStatus
  /** 正文 JSON（提示词正文/剧本分场/一致性包）；列表态为 null。 */
  content?: string | null
  /** 生成谱系 JSON。 */
  genMeta?: string | null
  currentVersion: number
  /** 详情态：挂载的叙事角色 keys。 */
  roleKeys?: string[]
  /** 详情态：当前版本文件 id。 */
  fileId?: string
  createdBy?: number
  createdAt: string
  updatedAt?: string
}

export interface AssetCreateRequest {
  /** 文本类（TEXT 类别）媒体类型 key（默认 PROMPT/SCRIPT，可自定义 TEXT 类型）。 */
  mediaType: AssetMediaType
  name: string
  description?: string
  tags?: string[]
  /** 文本类正文 JSON。 */
  content: string
  roleKeys?: string[]
}

export interface AssetUpdateRequest {
  name?: string
  description?: string
  tags?: string[]
  /** null=不改。 */
  roleKeys?: string[] | null
}

// ---------- 矩阵计数（设计 §2.2 每格徽标） ----------

export interface MatrixCountCell {
  mediaType: AssetMediaType
  /** null=未挂角色的资产计数。 */
  roleKey: string | null
  count: number
}

export interface MatrixCountVO {
  /** (mediaType × roleKey) 每格计数。 */
  cells: MatrixCountCell[]
  /** 每个内容类型总数（顶 Tab 徽标）。 */
  typeTotals: MatrixCountCell[]
}

// ---------- 版本 + 一致性包（plan §S5） ----------

export interface VersionVO {
  id: number
  assetId: number
  version: number
  fileId?: string
  changeNote?: string
  /** 正文快照 JSON（仅单取返回；列表省略）。 */
  content?: string
  createdBy?: number
  createdAt: string
}

export interface VersionCreateRequest {
  /** 文本类正文 JSON（文本类必填）。 */
  content?: string
  /** 文件类换版 fileId（文件类必填，复用 stored_files 不复制）。 */
  fileId?: string
  changeNote?: string
}

/** 一致性包（设计 §五）：null=不改；空列表/空串=清空。 */
export interface ConsistencyPackRequest {
  mainRefImageFileId?: string | null
  galleryFileIds?: string[] | null
  standardDescription?: string | null
  paramBaseline?: string | null
}

// ---------- 剧本分场（plan §S6 / FR-010） ----------

export interface SceneVO {
  index: number
  description: string
  [k: string]: unknown
}

export interface ScriptBreakdownVO {
  version: number
  scenes: SceneVO[]
}

// ---------- 画布双向打通（plan §S7 / FR-008/009/011） ----------

export interface CanvasImportRequest {
  canvasId: number
  nodeId: string
  projectId: number
  roleKeys?: string[]
  name?: string
  description?: string
  tags?: string[]
  /** 重复入库模式；空=自动检测（遇重复返 duplicate 提示）。 */
  mode?: CanvasImportMode
}

export interface CanvasImportVO {
  assetId?: number
  name?: string
  mediaType?: AssetMediaType
  version?: number
  /** 是否本次实际创建/建版（false=检测到重复未落）。 */
  created: boolean
  /** 重复入库时已存在的资产 id（前端提示用）。 */
  duplicateAssetId?: number
  duplicateVersion?: number
  message?: string
}

export interface ResolveRequest {
  /** 指定版本号；空=当前版本（版本快照，设计 §六）。 */
  version?: number
  /** 引用方画布 id（库→画布引用时传，落 REFERENCE 绑定；空=仅解析不记账）。 */
  canvasId?: number
  /** 引用方画布节点 id（同 canvasId 配对）。 */
  nodeId?: string
}

/** 引用解析结果（库→画布）：文件类返 fileId+url，文本类返 content。viewer 可读。 */
export interface ResolveVO {
  assetId: number
  mediaType: AssetMediaType
  version: number
  fileId?: string
  url?: string
  content?: string
  name?: string
}

/** 资产「使用记录」一行（双向追溯）。 */
export interface AssetUsageVO {
  id: number
  assetVersion?: number
  canvasId?: number
  nodeId?: string
  /** REFERENCE(被节点引用) / PRODUCED(产自节点)。 */
  bindType: 'REFERENCE' | 'PRODUCED'
  createdBy?: number
  createdAt: string
}
