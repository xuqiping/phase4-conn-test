// ============================================================
// Markdown 渲染（9x#13）
// - markdown-it：html:false（原文 HTML 一律转义，v-html 注入安全）
// - linkify 自动识别裸链接；外链新窗口打开
// ============================================================
import MarkdownIt from 'markdown-it'

const md: MarkdownIt = new MarkdownIt({
  html: false,
  linkify: true,
  breaks: true
})

// 链接统一新窗口 + rel 防 tabnabbing
const defaultLinkOpen =
  md.renderer.rules.link_open ??
  ((tokens, idx, options, _env, self) => self.renderToken(tokens, idx, options))
md.renderer.rules.link_open = (tokens, idx, options, env, self) => {
  tokens[idx].attrSet('target', '_blank')
  tokens[idx].attrSet('rel', 'noopener noreferrer')
  return defaultLinkOpen(tokens, idx, options, env, self)
}

export function renderMarkdown(text: string): string {
  if (!text) return ''
  return md.render(text)
}
