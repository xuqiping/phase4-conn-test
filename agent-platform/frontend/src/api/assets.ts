// ============================================================
// 项目资产库模块 API（对齐后端 /api/assets/**）
// plan §S2-S7；设计方案 §九 9.2
// 双层授权：第一层 asset:write（@RequirePermission，菜单隐藏+页内+API 403 三重兜底）
//           第二层项目数据权限（service 层 AssetAclService 咽喉点）
// ============================================================

import request from './request'
import type { ApiResponse } from './request'
import type { PageResult } from '@/api/admin'
import type {
  AssetCreateRequest,
  AssetCopyRequest,
  AssetProjectVO,
  AssetUpdateRequest,
  AssetUsageVO,
  AssetVO,
  CanvasImportRequest,
  CanvasImportVO,
  ConsistencyPackRequest,
  MatrixCountVO,
  MediaImportRequest,
  MediaImportVO,
  MemberAddRequest,
  MemberCandidateVO,
  MemberRoleUpdateRequest,
  MemberVO,
  ProjectCreateRequest,
  ProjectSettingsRequest,
  ProjectUpdateRequest,
  AssetScoreVO,
  PublicAccessDecisionRequest,
  PublicAccessRequestVO,
  PublicProjectSummaryVO,
  PublicPublishRequest,
  ResolveRequest,
  ResolveVO,
  ScriptBreakdownVO,
  StoryboardBreakdownVO,
  StoryboardSaveRequest,
  TransferRequest,
  VersionCreateRequest,
  VersionVO
} from '@/types/asset'

// === 项目（FR-001） ===

export const projectApi = {
  /** POST /assets/projects — 新建项目 */
  create(data: ProjectCreateRequest) {
    return request.post<ApiResponse<AssetProjectVO>>('/assets/projects', data)
  },
  /** GET /assets/projects — 列表（owner+member 各带 role，前端分「我的/共享给我」Tab） */
  list() {
    return request.get<ApiResponse<AssetProjectVO[]>>('/assets/projects')
  },
  /** GET /assets/projects/{id} — 详情 */
  get(id: number) {
    return request.get<ApiResponse<AssetProjectVO>>(`/assets/projects/${id}`)
  },
  /** PUT /assets/projects/{id} — 更新（含 narrative_roles 维护，L10） */
  update(id: number, data: ProjectUpdateRequest) {
    return request.put<ApiResponse<AssetProjectVO>>(`/assets/projects/${id}`, data)
  },
  /** DELETE /assets/projects/{id} — 删除（级联软删，L4，owner only） */
  remove(id: number) {
    return request.delete<ApiResponse<void>>(`/assets/projects/${id}`)
  },
  /** POST /assets/projects/{id}/transfer — 转让所有者（旧 owner 降 editor） */
  transfer(id: number, data: TransferRequest) {
    return request.post<ApiResponse<void>>(`/assets/projects/${id}/transfer`, data)
  },
  /** PATCH /assets/projects/{id}/settings — 项目设置（成员打分开关/内容模式，局部更新；C6） */
  updateSettings(id: number, data: ProjectSettingsRequest) {
    return request.patch<ApiResponse<AssetProjectVO>>(`/assets/projects/${id}/settings`, data)
  },
  /**
   * GET /assets/projects/{id}/creator-candidates — 上传者筛选候选（本项目上传者去重，读门：成员/公共 VIEWER 可用）。
   * 区别于 memberApi.searchCandidates（requireManage，OWNER 邀请专用）——EDITOR 复用它 403（P4 实测修复）。
   */
  creatorCandidates(id: number, keyword: string) {
    return request.get<ApiResponse<MemberCandidateVO[]>>(`/assets/projects/${id}/creator-candidates`, {
      params: { keyword }
    })
  }
}

// === 成员授权（FR-002） ===

export const memberApi = {
  /** GET /assets/projects/{id}/members — 成员列表（owner 行合成居首） */
  list(projectId: number) {
    return request.get<ApiResponse<MemberVO[]>>(`/assets/projects/${projectId}/members`)
  },
  /** GET /assets/projects/{id}/members/candidates — 按关键词返回最小候选字段。 */
  searchCandidates(projectId: number, keyword: string) {
    return request.get<ApiResponse<MemberCandidateVO[]>>(`/assets/projects/${projectId}/members/candidates`, {
      params: { keyword }
    })
  },
  /** POST /assets/projects/{id}/members — 邀请成员（owner） */
  invite(projectId: number, data: MemberAddRequest) {
    return request.post<ApiResponse<MemberVO>>(`/assets/projects/${projectId}/members`, data)
  },
  /** PUT /assets/projects/{id}/members/{userId} — 改角色（owner） */
  changeRole(projectId: number, userId: number, data: MemberRoleUpdateRequest) {
    return request.put<ApiResponse<void>>(`/assets/projects/${projectId}/members/${userId}`, data)
  },
  /** DELETE /assets/projects/{id}/members/{userId} — 移除成员（owner；自移除=退出，L1） */
  remove(projectId: number, userId: number) {
    return request.delete<ApiResponse<void>>(`/assets/projects/${projectId}/members/${userId}`)
  }
}

// === 资产 CRUD + 矩阵筛选/搜索 + 上传（FR-003/004/005） ===

export interface AssetListQuery {
  type?: string
  role?: string
  q?: string
  status?: string
  /** 上传者用户名精确匹配（C6/C7；0 命中=空页）。 */
  creatorUsername?: string
  /** 分数区间下/上界（0-100；C6/C7）。 */
  scoreMin?: number
  scoreMax?: number
  /** 分数来源：owner=拥有者评分轨；member=成员均分轨（AVG）。 */
  scoreSource?: 'owner' | 'member'
  page?: number
  size?: number
}

export const assetApi = {
  /** POST /assets/projects/{id}/assets — 新建文本类资产（PROMPT/SCRIPT） */
  create(projectId: number, data: AssetCreateRequest) {
    return request.post<ApiResponse<AssetVO>>(`/assets/projects/${projectId}/assets`, data)
  },
  /** GET /assets/projects/{id}/assets — 矩阵筛选/搜索列表（分页；默认隐藏 ARCHIVED，L3） */
  list(projectId: number, query: AssetListQuery = {}) {
    return request.get<ApiResponse<PageResult<AssetVO>>>(`/assets/projects/${projectId}/assets`, {
      params: query
    })
  },
  /** GET /assets/projects/{id}/assets/count — 矩阵每格计数（徽标，单条聚合） */
  countMatrix(projectId: number) {
    return request.get<ApiResponse<MatrixCountVO>>(`/assets/projects/${projectId}/assets/count`)
  },
  /**
   * POST /assets/projects/{id}/upload — 文件类资产上传（图片/视频/音频，落 SOURCE_ASSET）。
   * 类型↔资产类型匹配校验（mp4 不可入图片资产）。
   */
  upload(
    projectId: number,
    file: File,
    mediaType: string,
    extra: { name?: string; description?: string; roleKeys?: string[] } = {}
  ) {
    const form = new FormData()
    form.append('file', file)
    form.append('mediaType', mediaType)
    if (extra.name) form.append('name', extra.name)
    if (extra.description) form.append('description', extra.description)
    if (extra.roleKeys?.length) extra.roleKeys.forEach((k) => form.append('roleKeys', k))
    return request.post<ApiResponse<AssetVO>>(`/assets/projects/${projectId}/upload`, form, {
      headers: { 'Content-Type': 'multipart/form-data' },
      // 2x 修复：单文件上限 60MB，慢上行（2Mbps 传 60MB≈240s）会撞全局 15s 超时——放宽到 5min
      timeout: 300000
    })
  },
  /** GET /assets/assets/{id} — 详情（带 content+roleKeys+fileId） */
  get(assetId: number) {
    return request.get<ApiResponse<AssetVO>>(`/assets/assets/${assetId}`)
  },
  /** POST /assets/assets/{id}/copy — 将可用公众池资产复制到有写权限的目标项目。 */
  copy(assetId: number, data: AssetCopyRequest) {
    return request.post<ApiResponse<AssetVO>>(`/assets/assets/${assetId}/copy`, data)
  },
  /** PUT /assets/assets/{id} — 更新 meta+分类（正文改版走版本端点） */
  update(assetId: number, data: AssetUpdateRequest) {
    return request.put<ApiResponse<AssetVO>>(`/assets/assets/${assetId}`, data)
  },
  /** DELETE /assets/assets/{id} — 软删（role_links 硬删；bindings 留存历史） */
  remove(assetId: number) {
    return request.delete<ApiResponse<void>>(`/assets/assets/${assetId}`)
  },
  /** PUT /assets/assets/{id}/storyboard — 保存分镜字段(字段1/2/4，S18)。requireWrite + 须分镜类型。 */
  saveStoryboard(assetId: number, data: StoryboardSaveRequest) {
    return request.put<ApiResponse<AssetVO>>(`/assets/assets/${assetId}/storyboard`, data)
  }
}

// === 评分（C6/C7 双轨：拥有者分 vs 成员均分） ===

export const scoreApi = {
  /**
   * POST /assets/assets/{id}/score — 提交/覆盖我的评分（0-100）。
   * OWNER 恒可评（独立轨）；EDITOR 需项目 memberScoringEnabled（均分轨）；VIEWER/公共 403。
   */
  submit(assetId: number, score: number) {
    return request.post<ApiResponse<AssetScoreVO>>(`/assets/assets/${assetId}/score`, { score })
  },
  /** GET /assets/assets/{id}/score — 我的评分 + 双轨聚合（ownerScore/memberAvgScore/memberCount）。 */
  mine(assetId: number) {
    return request.get<ApiResponse<AssetScoreVO>>(`/assets/assets/${assetId}/score`)
  }
}

// === 公众池发布、申请与审批（FR-F19-03/04） ===

export const publicPoolApi = {
  list() {
    return request.get<ApiResponse<PublicProjectSummaryVO[]>>('/assets/public-pool')
  },
  publish(projectId: number, data: PublicPublishRequest) {
    return request.post<ApiResponse<void>>(`/assets/public-pool/${projectId}/publish`, data)
  },
  unpublish(projectId: number) {
    return request.delete<ApiResponse<void>>(`/assets/public-pool/${projectId}/publish`)
  },
  requestAccess(projectId: number) {
    return request.post<ApiResponse<PublicAccessRequestVO>>(`/assets/public-pool/${projectId}/requests`)
  },
  getMyRequest(projectId: number) {
    return request.get<ApiResponse<PublicAccessRequestVO | null>>(`/assets/public-pool/${projectId}/requests/mine`)
  },
  listRequests(projectId: number) {
    return request.get<ApiResponse<PublicAccessRequestVO[]>>(`/assets/public-pool/${projectId}/requests`)
  },
  decideRequest(projectId: number, requestId: number, data: PublicAccessDecisionRequest) {
    return request.put<ApiResponse<void>>(`/assets/public-pool/${projectId}/requests/${requestId}/decision`, data)
  },
  revokeApproval(projectId: number, requestId: number) {
    return request.delete<ApiResponse<void>>(`/assets/public-pool/${projectId}/requests/${requestId}/approval`)
  }
}

// === 版本 + 状态机 + 一致性包（FR-006/007，L2/L3） ===

export const versionApi = {
  /** GET /assets/assets/{id}/versions — 版本时间线（meta only，倒序） */
  list(assetId: number) {
    return request.get<ApiResponse<VersionVO[]>>(`/assets/assets/${assetId}/versions`)
  },
  /** GET /assets/assets/{id}/versions/{ver} — 单取版本（带 content） */
  get(assetId: number, version: number) {
    return request.get<ApiResponse<VersionVO>>(`/assets/assets/${assetId}/versions/${version}`)
  },
  /** POST /assets/assets/{id}/versions — 建新版（文本 content / 文件 fileId；乐观锁） */
  create(assetId: number, data: VersionCreateRequest) {
    return request.post<ApiResponse<number>>(`/assets/assets/${assetId}/versions`, data)
  },
  /** POST /assets/assets/{id}/lock — 定稿（DRAFT→LOCKED，L2） */
  lock(assetId: number) {
    return request.post<ApiResponse<AssetVO>>(`/assets/assets/${assetId}/lock`)
  },
  /** POST /assets/assets/{id}/unlock — 解锁回退草稿（LOCKED→DRAFT，L2） */
  unlock(assetId: number) {
    return request.post<ApiResponse<AssetVO>>(`/assets/assets/${assetId}/unlock`)
  },
  /** POST /assets/assets/{id}/archive — 归档（any→ARCHIVED，L3） */
  archive(assetId: number) {
    return request.post<ApiResponse<AssetVO>>(`/assets/assets/${assetId}/archive`)
  },
  /** POST /assets/assets/{id}/unarchive — 取消归档恢复（ARCHIVED→DRAFT，L3） */
  unarchive(assetId: number) {
    return request.post<ApiResponse<AssetVO>>(`/assets/assets/${assetId}/unarchive`)
  },
  /** PUT /assets/assets/{id}/consistency-pack — 一致性包局部更新（null=不改，产新版本） */
  saveConsistencyPack(assetId: number, data: ConsistencyPackRequest) {
    return request.put<ApiResponse<AssetVO>>(`/assets/assets/${assetId}/consistency-pack`, data)
  }
}

// === 剧本分场（FR-010） ===

export const scriptApi = {
  /** POST /assets/assets/{id}/breakdown — 剧本 AI 拆分场（scenes 入 content 产新版本） */
  breakdown(assetId: number, model?: string) {
    return request.post<ApiResponse<ScriptBreakdownVO>>(`/assets/assets/${assetId}/breakdown`, model ? { model } : {})
  },
  /** POST /assets/assets/{id}/breakdown-storyboard — 一键分镜（每镜产一个分镜资产，S19） */
  breakdownStoryboard(assetId: number, model?: string) {
    return request.post<ApiResponse<StoryboardBreakdownVO>>(
      `/assets/assets/${assetId}/breakdown-storyboard`,
      model ? { model } : {}
    )
  }
}

// === 画布双向打通（FR-008/009/011） ===

export const assetBridgeApi = {
  /**
   * POST /assets/canvas-import — 画布节点产出入库（画布→库）。
   * 按节点类型映射资产类型 + gen_meta 谱系捕获 + PRODUCED 绑定；重复入库三态。
   * requireWrite（viewer 不可入库）。
   */
  importFromCanvas(data: CanvasImportRequest) {
    return request.post<ApiResponse<CanvasImportVO>>('/assets/canvas-import', data)
  },
  /**
   * POST /assets/assets/{id}/resolve — 引用解析=版本快照（库→画布）。
   * 文件类返 fileId+url，文本类返 content。viewer 可读（只读引用，设计 §7.2）。
   */
  resolve(assetId: number, data: ResolveRequest = {}) {
    return request.post<ApiResponse<ResolveVO>>(`/assets/assets/${assetId}/resolve`, data)
  },
  /** GET /assets/assets/{id}/usages — 使用记录（双向追溯；viewer 可读） */
  usages(assetId: number) {
    return request.get<ApiResponse<AssetUsageVO[]>>(`/assets/assets/${assetId}/usages`)
  },

  /**
   * POST /assets/from-media — 生图结果某张图入库（生成→库）。
   * 复用 SOURCE_MEDIA fileId（不拷贝）；genMeta.source=MEDIA 标来源。
   * requireWrite（viewer 不可入库）。无画布节点 → 无重复检测三态，created 恒 true。
   */
  importFromMedia(data: MediaImportRequest) {
    return request.post<ApiResponse<MediaImportVO>>('/assets/from-media', data)
  }
}
