// ============================================================
// 项目资产库类型定义（对齐后端 com.superprogrammer.asset.dto.*）
// 设计方案 §二/§三/§五/§六/§七/§八；plan §S1-S13
// ============================================================

/** 轴A 媒体类型标签（项目受控词汇 key，可自定义；默认五类中文，可扩展如「地图」）。 */
export type AssetMediaType = string

/** 默认媒体类型 key 常量（与后端 Asset.MEDIA_* 对齐；中文 key，行为判断引此勿裸字符串）。 */
export const MEDIA_TYPE = {
  PROMPT: '提示词',
  SCRIPT: '剧本',
  STORYBOARD: '分镜',
  IMAGE: '图片',
  VIDEO: '视频',
  AUDIO: '音频'
} as const

/** 默认媒体类型 key 列表（受控词汇默认五项，label/图标兜底用）。 */
export const DEFAULT_MEDIA_TYPES = Object.values(MEDIA_TYPE)

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

/** 公众池访问方式：开放使用或先申请审批。 */
export type PublicAccessMode = 'OPEN' | 'APPROVAL_REQUIRED'

/** 内容模式（V124/C6）：SHARED=协作共享（默认，现状）；PERSONAL=成员仅能管理自己上传的内容。 */
export type ProjectContentMode = 'SHARED' | 'PERSONAL'

/** 公众池访问申请状态。 */
export type PublicAccessStatus = 'PENDING' | 'APPROVED' | 'REJECTED' | 'REVOKED'

/** 重复入库处理模式（plan §S7 / L5）。 */
export type CanvasImportMode = 'NEW_VERSION' | 'NEW_ASSET'

// ---------- 项目 ----------

/** 叙事角色两级受控词汇条目（修复XI XI-3：一级 key + 子类 children；存量 string 数组已由后端 V169 迁移）。 */
export interface NarrativeRoleVocab {
  key: string
  children: string[]
}

/** 项目视图（ProjectVO）。role=当前用户在本项目的角色。 */
export interface AssetProjectVO {
  id: number
  name: string
  description?: string
  coverFileId?: string
  ownerId: number
  /** 叙事角色两级受控词汇（修复XI：{key,children}）。 */
  narrativeRoles: NarrativeRoleVocab[]
  /** 媒体类型受控词汇桶（V60，{key,category}）。 */
  mediaTypes: MediaTypeDef[]
  role: ProjectRole
  /** 新后端必返；可选仅用于兼容尚未补公众池字段的旧前端夹具。 */
  publicPool?: boolean
  publicAccessMode?: PublicAccessMode | null
  publishedBy?: number | null
  publishedAt?: string | null
  /** 新后端必返；可选仅用于兼容尚未补公众池字段的旧前端夹具。 */
  publishedByAdmin?: boolean
  /** 2x 待决策项（V100）：是否允许公共用户复制资产（发布弹窗回显/复制按钮显隐；缺省视为 true）。 */
  allowPublicCopy?: boolean
  /** 成员打分开关（V124/C6）：OWNER 恒可评；EDITOR 评分需开关开启。 */
  memberScoringEnabled?: boolean
  /** 内容模式（V124/C6）：PERSONAL=成员仅能管理自己上传的内容。 */
  contentMode?: ProjectContentMode
  createdAt: string
  updatedAt?: string
}

/** 公众池列表的安全摘要，不包含项目词汇、资产详情、版本或文件内容。 */
export interface PublicProjectSummaryVO {
  id: number
  name: string
  description?: string | null
  coverFileId?: string | null
  publicAccessMode: PublicAccessMode
  publishedBy: number
  publisherUsername?: string | null
  publishedAt: string
  publishedByAdmin: boolean
  assetCount: number
  myRequestStatus?: PublicAccessStatus | null
  usable: boolean
  /** 2x#4：项目媒体类型（jsonb 字符串），选择器按图片/视频过滤公共池项目 */
  mediaTypes?: string | null
  /** 2x V100：是否允许公共用户复制资产（公共 VIEWER 复制按钮显隐依据）。 */
  allowPublicCopy?: boolean | null
}

export interface ProjectCreateRequest {
  name: string
  description?: string
  narrativeRoles?: NarrativeRoleVocab[]
}

export interface ProjectUpdateRequest {
  name?: string
  description?: string
  /** null=不改；数组=覆盖；删词汇联动两级重指派（删子类归父/删一级归通用，修复XI L10）。 */
  narrativeRoles?: NarrativeRoleVocab[] | null
  /** null=不改；数组=覆盖；删 type 联动同 category 迁移（V60）。 */
  mediaTypes?: MediaTypeDef[] | null
  coverFileId?: string | null
}

/** 项目设置（C6 PATCH /projects/{id}/settings）：字段缺省=不改（局部更新）。 */
export interface ProjectSettingsRequest {
  memberScoringEnabled?: boolean
  contentMode?: ProjectContentMode
}

// ---------- 成员 ----------

/** 成员视图（MemberVO，含合成的 owner 行）。 */
export interface MemberVO {
  userId: number
  username: string
  role: ProjectRole
  isOwner: boolean
  grantedBy?: number
  grantedAt?: string
}

/** 分享弹窗按关键词远程返回的最小候选字段。 */
export interface MemberCandidateVO {
  id: number
  username: string
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

export interface PublicAccessRequestVO {
  id: number
  projectId: number
  applicantId: number
  applicantUsername?: string | null
  status: PublicAccessStatus
  decidedBy?: number | null
  decidedAt?: string | null
  createdAt: string
  updatedAt: string
}

export interface PublicPublishRequest {
  accessMode: PublicAccessMode
  /** 2x V100：是否允许公共用户复制资产（缺省=沿用当前值）。 */
  allowPublicCopy?: boolean
}

export interface PublicAccessDecisionRequest {
  decision: 'APPROVED' | 'REJECTED'
}

export interface AssetCopyRequest {
  targetProjectId: number
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
  /**
   * 文本类正文片段（S16 Bug④，列表态卡片封面用）。
   * 仅 TEXT 类别填充（≤120 字去换行）；列表态独立于 content 懒加载。
   */
  textPreview?: string | null
  /** 生成谱系 JSON。 */
  genMeta?: string | null
  currentVersion: number
  /** 详情态：挂载的叙事角色 keys。 */
  roleKeys?: string[]
  /** 详情态：当前版本文件 id。 */
  fileId?: string
  createdBy?: number
  /** 上传者用户名（C6/C7 列表批装配；存量回填近似值说明见 user-ops）。 */
  createdByUsername?: string | null
  /** 拥有者评分（0-100 双轨独立轨；null=未评）。 */
  ownerScore?: number | null
  /** 成员均分（0-100 四舍五入取整；null=无人评）。 */
  memberAvgScore?: number | null
  /** 参与均分的成员评分人数。 */
  memberCount?: number
  /** 我的评分（当前用户对该资产的一票；null=未评）。 */
  myScore?: number | null
  /** 拥有者分等级（2x#7 后端 AssetGrade 派生；null=未评）。 */
  ownerGrade?: string | null
  /** 成员均分等级（2x#7 均分先取整再派生；null=无成员分）。 */
  memberAvgGrade?: string | null
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
  /** 实际使用的拆分模型（后端 ScriptBreakdownVO.model，FR-006 回显所选模型） */
  model?: string
}

// ---------- 分镜（S17-S19，5 字段流水线） ----------

/** 分镜实体→@资产引用键值对（字段 2/4 复用）。assetId 指向项目内图片/音频资产。 */
export interface StoryboardEntityRef {
  /** 实体键名（如「主角」「道具·剑」），≤32 字。 */
  key: string
  /** 被引资产 id；取不到（被删/失权）→ 渲染降级「资产已删」红标。 */
  assetId?: number | null
  /** 富化：被引资产的媒体类型/名（保存时后端从目录回填，防模型幻觉）。 */
  mediaType?: string
  name?: string
}

/**
 * 分镜 content schema（5 字段流水线，1_8.6计划 第 11 点）。
 * ①prompt 描述 ②entityRefs 实体→@资产（LLM 首轮匹配）③imageGen 批量生图(占位)
 * ④videoInputs 生视频输入(audioRefs/imageRefs) ⑤videoGen 生视频(占位)。
 * 字段 3/5 本轮占位（阻塞于图模型 R-3 接入）。
 */
export interface StoryboardContent {
  /** 镜头号（一键分镜时由 LLM index 填）。 */
  shotIndex?: number
  /** 源剧本资产 id（一键分镜填，点击可在抽屉重开源剧本）。 */
  parentId?: number | null
  /** 字段1：镜头提示词描述。 */
  prompt?: string
  /** 字段2：实体→@资产键值对（人物/道具/场景图片资产）。 */
  entityRefs?: StoryboardEntityRef[]
  /** 字段3：批量生图状态（占位，待图模型接入）。 */
  imageGen?: { status?: string }
  /** 字段4：生视频输入键值对（音频/图片参考资产，本轮可编辑录入）。 */
  videoInputs?: { audioRefs?: StoryboardEntityRef[]; imageRefs?: StoryboardEntityRef[] }
  /** 字段5：生视频状态（占位，待图模型接入）。 */
  videoGen?: { status?: string }
}

/** 分镜资产保存请求（S18，字段 1/2/4 可编辑）。 */
export interface StoryboardSaveRequest {
  prompt?: string
  entityRefs?: StoryboardEntityRef[]
  videoInputs?: { audioRefs?: StoryboardEntityRef[]; imageRefs?: StoryboardEntityRef[] }
}

/** 一键分镜请求（S19）。 */
export interface StoryboardBreakdownRequest {
  model?: string
}

/** 一键分镜结果（S19）。 */
export interface StoryboardBreakdownVO {
  count: number
  createdAssetIds: number[]
  model: string
  version: number
}

// ---------- 评分（C6/C7 双轨：拥有者分 vs 成员均分） ----------

/** 单资产评分视图（GET/POST /assets/{id}/score）：我的分 + 双轨聚合。 */
export interface AssetScoreVO {
  myScore?: number | null
  ownerScore?: number | null
  memberAvgScore?: number | null
  memberCount?: number
  /** 拥有者分等级（2x#7 派生；null=未评）。 */
  ownerGrade?: string | null
  /** 成员均分等级（2x#7 先取整再派生；null=无成员分）。 */
  memberAvgGrade?: string | null
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

// ---------- 媒体生成 → 资产库（生成→库，无画布节点） ----------

/** POST /assets/from-media — 生图逐张 / 视频任务结果入库（复用 SOURCE_MEDIA fileId，不拷贝）。 */
export interface MediaImportRequest {
  /** 生图/视频任务 id（media_gen_tasks.id）。 */
  taskId: number
  /** 产物类型：IMAGE=按 imageIdx 定位图片；VIDEO=视频任务 resultFileId。缺省 IMAGE（兼容旧调用）。 */
  mediaKind?: 'IMAGE' | 'VIDEO'
  /** 目标图下标（0-based，对应 result_meta.imageFileIds 顺序；仅 IMAGE 必填）。 */
  imageIdx?: number
  /** 目标项目 id（必填，须当前用户可写）。 */
  projectId: number
  /** 资产名（≤100；空则后端兜底「图片产出/视频产出」）。 */
  name?: string
  /** 资产描述。 */
  description?: string
}

/** 生图入库结果（无画布节点 → 无重复入库三态，created 恒 true）。 */
export interface MediaImportVO {
  created: boolean
  /** 修复III F1（17x#1）：true=同项目已入库该任务产物（created=false，复用既有 assetId）。 */
  duplicate?: boolean
  assetId?: number
  name?: string
  mediaType?: string
  version?: number
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

/** 资产库文件选取结果（AssetFilePicker emit，供 VideoGenView 多模态附件复用）。 */
export interface AssetFilePicked {
  fileId: string
  name: string
  assetId: number
  /** 预览/播放 URL（resolve 返回，图缩略/视频/音频播放用）。 */
  url?: string
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
