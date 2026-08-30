// ============================================================
// 文件预览通用 API（C2 抽出，资产卡片 / 画布节点 / 详情抽屉共用）
// 对应后端 /api/files/{fileId}（FileStorageService.load 归属咽喉点，防 IDOR）
// ============================================================

import request from './request'
import { createBlobLruCache } from '@/utils/blobLruCache'

/**
 * 6x#2：会话内文件预览 blob 缓存（LRU，80 条/128MB）。
 * 与 media.ts mediaBlobCache（媒体产物，6 条/256MB）分池——参考图/附件等
 * /api/files 预览走本池，两池合计 ≤384MB，不破 400MB 前端内存红线。
 * fileId 不可变（重新上传=新 fileId），会话内命中不存在过期问题；
 * 跨会话刷新由 FileController ETag/304（Step6）兜底零流量。
 */
const filePreviewCache = createBlobLruCache(80, 128 * 1024 * 1024)

/** 测试出口：清空预览缓存。 */
export function clearFilePreviewCache() {
  filePreviewCache.clear()
}

/**
 * 带鉴权拉取文件并转 objectURL。
 *
 * `/api/files/{fileId}` GET 需 Authorization header，`<img>/<video> src` 无法带 header，
 * 故走 axios 拉 blob（拦截器自动注 JWT）再 `URL.createObjectURL`。
 * 命中 LRU 直接用缓存 Blob 新建 objectURL（同会话零请求）。
 * 调用方负责在不再使用时 {@link revokeFilePreview}（组合式 {@link useLazyFilePreview} 已托管）。
 */
export async function fetchFilePreview(fileId: string): Promise<string> {
  const hit = filePreviewCache.get(fileId)
  if (hit) return URL.createObjectURL(hit)
  // 2x 四轮 Step1：预览预取属后台型（断网不弹「服务不可达」不踢会话，恢复后重拉）
  const res = await request.get<Blob>(`/files/${fileId}`, { responseType: 'blob', _background: true })
  filePreviewCache.put(fileId, res.data)
  return URL.createObjectURL(res.data)
}
