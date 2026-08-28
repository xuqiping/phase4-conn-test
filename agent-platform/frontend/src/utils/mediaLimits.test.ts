import { describe, expect, it } from 'vitest'
import { KIND_LIMIT_BYTES, isEditableTarget, kindFromMime, sizeLimitError } from './mediaLimits'

describe('mediaLimits（修复VI 2x#1/2/5）', () => {
  describe('kindFromMime', () => {
    it('三分流：image/video/audio', () => {
      expect(kindFromMime('image/png')).toBe('image')
      expect(kindFromMime('video/mp4')).toBe('video')
      expect(kindFromMime('audio/mpeg')).toBe('audio')
    })
    it('未知/空类型返回 null（不支持建节点）', () => {
      expect(kindFromMime('text/plain')).toBeNull()
      expect(kindFromMime('application/zip')).toBeNull()
      expect(kindFromMime('')).toBeNull()
      expect(kindFromMime(undefined)).toBeNull()
    })
  })

  describe('sizeLimitError', () => {
    it('30MB 边界：等于放行、超一拒', () => {
      expect(sizeLimitError('image', KIND_LIMIT_BYTES.image, 'a.png')).toBeNull()
      expect(sizeLimitError('image', KIND_LIMIT_BYTES.image + 1, 'a.png'))
        .toBe('文件过大：a.png（image ≤30MB）')
    })
    it('audio/video 各自上限互不干扰', () => {
      expect(sizeLimitError('audio', 16 * 1024 * 1024, 'b.mp3')).toContain('audio ≤15MB')
      expect(sizeLimitError('video', 51 * 1024 * 1024, 'c.mp4')).toContain('video ≤50MB')
      expect(sizeLimitError('video', 30 * 1024 * 1024, 'c.mp4')).toBeNull()
    })
  })

  describe('isEditableTarget（粘贴守卫）', () => {
    const mk = (tag: string, attrs: Record<string, string> = {}): HTMLElement => {
      const el = document.createElement(tag)
      for (const [k, v] of Object.entries(attrs)) el.setAttribute(k, v)
      return el
    }
    it('input/textarea/contenteditable 内 → true（不拦粘贴）', () => {
      document.body.append(mk('div', { id: 'host' }))
      const host = document.getElementById('host')!
      const input = mk('input')
      const ta = mk('textarea')
      const ce = mk('div', { contenteditable: 'true' })
      host.append(input, ta, ce)
      expect(isEditableTarget(input)).toBe(true)
      expect(isEditableTarget(ta)).toBe(true)
      expect(isEditableTarget(ce)).toBe(true)
      // 嵌套：目标是小 span，祖先在 contenteditable 里
      const inner = mk('span')
      ce.append(inner)
      expect(isEditableTarget(inner)).toBe(true)
      host.remove()
    })
    it('画布普通元素/null → false（可建节点）', () => {
      expect(isEditableTarget(mk('div'))).toBe(false)
      expect(isEditableTarget(null)).toBe(false)
    })
  })
})
