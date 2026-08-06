// ============================================================
// 文件预览通用 API（C2 抽出，资产卡片 / 画布节点 / 详情抽屉共用）
// 对应后端 /api/files/{fileId}（FileStorageService.load 归属咽喉点，防 IDOR）
// ============================================================

import request from './request'

/**
 * 带鉴权拉取文件并转 objectURL。
 *
 * `/api/files/{fileId}` GET 需 Authorization header，`<img>/<video> src` 无法带 header，
 * 故走 axios 拉 blob（拦截器自动注 JWT）再 `URL.createObjectURL`。
 * 调用方负责在不再使用时 {@link revokeFilePreview}（组合式 {@link useLazyFilePreview} 已托管）。
 */
export async function fetchFilePreview(fileId: string): Promise<string> {
  const res = await request.get<Blob>(`/files/${fileId}`, { responseType: 'blob' })
  return URL.createObjectURL(res.data)
}
