// src/utils/highlight.ts

function escapeHtml(text: string): string {
  const map: Record<string, string> = {
    '&': '&amp;',
    '<': '&lt;',
    '>': '&gt;',
    '"': '&quot;',
    "'": '&#039;'
  }
  return text.replace(/[&<>"']/g, c => map[c])
}

/**
 * 高亮文本中的搜索关键词
 * @param text 原始文本
 * @param query 搜索词（空字符串返回原文本）
 * @returns 包含 <mark> 标签的 HTML 字符串
 */
export function highlightText(text: string, query: string): string {
  const safe = escapeHtml(text)
  if (!query.trim()) return safe

  const escapedQuery = escapeHtml(query).replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  const regex = new RegExp(`(${escapedQuery})`, 'gi')

  return safe.replace(
    regex,
    '<mark class="bg-yellow-200 dark:bg-yellow-800 text-inherit rounded-sm px-0.5">$1</mark>'
  )
}
