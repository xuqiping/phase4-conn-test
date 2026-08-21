// ============================================================
// 反馈中心 API（19x 三合一「反馈与帮助」）
// 对应后端 /api/feedback/**
//   用户侧：建议提交/我的建议、提问/我的提问/FAQ、帮助文章阅读、站内通知
//   admin：建议审核、提问回答/关闭（feedback:manage）、帮助文章 CRUD（help:manage）
// ============================================================

import request from './request'
import type { ApiResponse } from './request'
import type { PageResult } from './admin'

// === 类型定义 ===

export type SuggestionStatus = 'PENDING' | 'ADOPTED' | 'REJECTED' | 'CLOSED'
export type QuestionStatus = 'OPEN' | 'ANSWERED' | 'CLOSED'

/** 用户侧建议行（含 admin 回复） */
export interface SuggestionVO {
  id: number
  createdAt: string
  title: string
  content: string
  attachmentFileIds: string[]
  status: SuggestionStatus
  reply: string | null
  reviewedAt: string | null
}

/** admin 建议行（多 userId/username 快照） */
export interface AdminSuggestionVO extends SuggestionVO {
  userId: number
  username: string
}

/** 用户侧提问行（含 admin markdown 答案） */
export interface QuestionVO {
  id: number
  createdAt: string
  title: string
  content: string
  status: QuestionStatus
  answer: string | null
  answeredAt: string | null
}

/** admin 提问行 */
export interface AdminQuestionVO extends QuestionVO {
  userId: number
  username: string
  isPublic: boolean
}

/** FAQ 公开行——无 username/userId（脱敏在字段不存在层，后端 VO 刻意不含） */
export interface FaqVO {
  id: number
  title: string
  content: string
  answer: string
  answeredAt: string
}

/** 帮助文章目录行（无正文大字段） */
export interface ArticleListItemVO {
  slug: string
  title: string
  category: string
  sortOrder: number
  publishedAt: string | null
}

/** 帮助文章详情（markdown 原文，前端 renderMarkdown 渲染） */
export interface ArticleDetailVO extends ArticleListItemVO {
  contentMd: string
}

/** admin 文章行（含正文+发布状态） */
export interface AdminArticleVO extends ArticleDetailVO {
  id: number
  published: boolean
  createdAt: string
  updatedAt: string | null
}

export type FeedbackNotificationType = 'SUGGESTION_REVIEWED' | 'QUESTION_ANSWERED'

/** 站内通知行（message 纯文本） */
export interface FeedbackNotificationVO {
  id: number
  type: FeedbackNotificationType
  refId: number
  message: string
  readAt: string | null
  createdAt: string
}

// === 标签映射 ===

export const SUGGESTION_STATUS_LABEL: Record<SuggestionStatus, string> = {
  PENDING: '待审核',
  ADOPTED: '已采纳',
  REJECTED: '未采纳',
  CLOSED: '已关闭'
}

export const SUGGESTION_STATUS_TAG_TYPE: Record<SuggestionStatus, 'info' | 'success' | 'error' | 'warning'> = {
  PENDING: 'info',
  ADOPTED: 'success',
  REJECTED: 'error',
  CLOSED: 'warning'
}

export const QUESTION_STATUS_LABEL: Record<QuestionStatus, string> = {
  OPEN: '待回答',
  ANSWERED: '已回答',
  CLOSED: '已关闭'
}

export const QUESTION_STATUS_TAG_TYPE: Record<QuestionStatus, 'info' | 'success' | 'warning'> = {
  OPEN: 'info',
  ANSWERED: 'success',
  CLOSED: 'warning'
}

// === API 函数 ===

/** 通用文件上传（建议附件走 /files/upload，返回 fileId；属主=当前用户，提交时后端再校验） */
export function uploadFeedbackFile(file: File) {
  const form = new FormData()
  form.append('file', file)
  return request.post<ApiResponse<{ fileId: string; url: string; name: string }>>('/files/upload', form, {
    headers: { 'Content-Type': 'multipart/form-data' }
  })
}

export const feedbackApi = {
  // ---- 建议台（用户） ----
  submitSuggestion(data: { title: string; content: string; attachmentFileIds?: string[] }) {
    return request.post<ApiResponse<{ id: number }>>('/feedback/suggestions', data)
  },
  mySuggestions(params: { page?: number; size?: number }) {
    return request.get<ApiResponse<PageResult<SuggestionVO>>>('/feedback/suggestions/mine', { params })
  },
  // ---- 提问台（用户） ----
  submitQuestion(data: { title: string; content: string }) {
    return request.post<ApiResponse<{ id: number }>>('/feedback/questions', data)
  },
  myQuestions(params: { page?: number; size?: number }) {
    return request.get<ApiResponse<PageResult<QuestionVO>>>('/feedback/questions/mine', { params })
  },
  /** FAQ 公开检索（标题/内容前缀） */
  faq(params: { kw?: string; page?: number; size?: number }) {
    return request.get<ApiResponse<PageResult<FaqVO>>>('/feedback/questions/faq', { params })
  },
  // ---- 说明台（用户） ----
  helpArticles(category?: string) {
    return request.get<ApiResponse<ArticleListItemVO[]>>('/feedback/help/articles',
      { params: category ? { category } : {} })
  },
  helpArticle(slug: string) {
    return request.get<ApiResponse<ArticleDetailVO>>(`/feedback/help/articles/${slug}`)
  },
  // ---- 站内通知 ----
  unreadCount() {
    return request.get<ApiResponse<{ count: number }>>('/feedback/notifications/count')
  },
  notifications(params: { page?: number; size?: number }) {
    return request.get<ApiResponse<PageResult<FeedbackNotificationVO>>>('/feedback/notifications', { params })
  },
  markNotificationRead(id: number) {
    return request.post<ApiResponse<null>>(`/feedback/notifications/${id}/read`)
  },
  markAllNotificationsRead() {
    return request.post<ApiResponse<{ count: number }>>('/feedback/notifications/read-all')
  },
  // ---- admin 建议审核（feedback:manage） ----
  adminSuggestions(params: { status?: SuggestionStatus; page?: number; size?: number }) {
    return request.get<ApiResponse<PageResult<AdminSuggestionVO>>>('/feedback/admin/suggestions', { params })
  },
  reviewSuggestion(id: number, data: { toStatus: 'ADOPTED' | 'REJECTED' | 'CLOSED'; reply?: string }) {
    return request.post<ApiResponse<null>>(`/feedback/admin/suggestions/${id}/review`, data)
  },
  // ---- admin 提问回答（feedback:manage） ----
  adminQuestions(params: { status?: QuestionStatus; page?: number; size?: number }) {
    return request.get<ApiResponse<PageResult<AdminQuestionVO>>>('/feedback/admin/questions', { params })
  },
  answerQuestion(id: number, data: { answer: string; isPublic: boolean }) {
    return request.post<ApiResponse<null>>(`/feedback/admin/questions/${id}/answer`, data)
  },
  closeQuestion(id: number) {
    return request.post<ApiResponse<null>>(`/feedback/admin/questions/${id}/close`)
  },
  // ---- admin 帮助文章（help:manage） ----
  adminArticles(params: { page?: number; size?: number }) {
    return request.get<ApiResponse<PageResult<AdminArticleVO>>>('/feedback/admin/help/articles', { params })
  },
  createArticle(data: { slug: string; title: string; category?: string; sortOrder?: number; contentMd: string }) {
    return request.post<ApiResponse<{ id: number }>>('/feedback/admin/help/articles', data)
  },
  updateArticle(id: number, data: { slug: string; title: string; category?: string; sortOrder?: number; contentMd: string }) {
    return request.put<ApiResponse<null>>(`/feedback/admin/help/articles/${id}`, data)
  },
  setArticlePublished(id: number, published: boolean) {
    return request.post<ApiResponse<null>>(`/feedback/admin/help/articles/${id}/publish`, { published })
  },
  deleteArticle(id: number) {
    return request.delete<ApiResponse<null>>(`/feedback/admin/help/articles/${id}`)
  }
}
