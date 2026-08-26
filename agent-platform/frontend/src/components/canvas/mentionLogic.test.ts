import { describe, expect, it } from 'vitest'
import { MENTION_RE, parseSegments, detectAnchor, insertMention, escapeHtml } from './mentionLogic'

describe('mentionLogic · parseSegments', () => {
  it('纯文本无占位符 → 单文本段', () => {
    expect(parseSegments('普通文本')).toEqual([{ type: 'text', value: '普通文本' }])
  })

  it('单个占位符切分 kind/id', () => {
    expect(parseSegments('扩写 @{{node:n1}} 继续')).toEqual([
      { type: 'text', value: '扩写 ' },
      { type: 'mention', raw: '@{{node:n1}}', kind: 'node', id: 'n1' },
      { type: 'text', value: ' 继续' }
    ])
  })

  it('多占位符 + 附件 kind（image/video/audio）', () => {
    const segs = parseSegments('@{{image:a1}}和@{{video:v1}}')
    expect(segs.filter((s) => s.type === 'mention')).toEqual([
      { type: 'mention', raw: '@{{image:a1}}', kind: 'image', id: 'a1' },
      { type: 'mention', raw: '@{{video:v1}}', kind: 'video', id: 'v1' }
    ])
  })

  it('行首/行尾占位符', () => {
    expect(parseSegments('@{{node:n1}}')).toHaveLength(1)
    expect(parseSegments('@{{node:n1}}')[0]).toMatchObject({ kind: 'node', id: 'n1' })
  })

  it('空串 → 空数组', () => {
    expect(parseSegments('')).toEqual([])
  })
})

describe('mentionLogic · detectAnchor', () => {
  it('独立 @ 行尾唤起', () => {
    expect(detectAnchor('扩写 @', 4)).toEqual({ at: 3, q: '' })
  })

  it('中文句末无空格 @ 唤起（修 A2）', () => {
    // "主角走进房间@" 长度：6 中文 + @ = 下标 7（每个中文算 1 char）
    expect(detectAnchor('主角走进房间@', 7)).toEqual({ at: 6, q: '' })
  })

  it('邮箱类 foo@bar 不误判（@ 前是字母）', () => {
    // "foo@bar" caret=7；回退遇 @ 时 prev='o' 是字母 → null
    expect(detectAnchor('foo@bar', 7)).toBeNull()
  })

  it('@ 后空白 → 关闭', () => {
    expect(detectAnchor('扩写 @ ', 5)).toBeNull()
  })

  it('带查询串过滤', () => {
    expect(detectAnchor('扩写 @人物', 6)).toEqual({ at: 3, q: '人物' })
  })

  it('caret<=0 → null', () => {
    expect(detectAnchor('x', 0)).toBeNull()
  })
})

describe('mentionLogic · insertMention', () => {
  it('行尾插入 token + 尾随空格', () => {
    const r = insertMention('扩写 @', 3, 4, 'node', 'n1')
    expect(r.text).toBe('扩写 @{{node:n1}} ')
    expect(r.pos).toBe('扩写 @{{node:n1}} '.length)
  })

  it('光标后紧邻空白 → 不补双空格', () => {
    // "前 @ 后" caret=3（@ 之后）
    const r = insertMention('前 @ 后', 2, 3, 'node', 'n2')
    expect(r.text).toBe('前 @{{node:n2}} 后')
  })

  it('附件 kind 插入', () => {
    const r = insertMention('@', 0, 1, 'image', 'a1')
    expect(r.text).toBe('@{{image:a1}} ')
  })

  it('2x 六轮 #1：insertSuffix 追加在 token 后、尾随空格之前（标注图子序号）', () => {
    const r = insertMention('改 @', 2, 3, 'node', 'n9', '：序号1（红色）框')
    expect(r.text).toBe('改 @{{node:n9}}：序号1（红色）框 ')
    expect(r.pos).toBe('改 @{{node:n9}}：序号1（红色）框 '.length)
  })

  it('2x 六轮 #1：insertSuffix + 光标后紧邻空白 → 不补双空格', () => {
    const r = insertMention('改 @ 完', 2, 3, 'node', 'n9', '：序号2框')
    expect(r.text).toBe('改 @{{node:n9}}：序号2框 完')
  })
})

describe('mentionLogic · escapeHtml', () => {
  it('转义 HTML 特殊字符（防 XSS）', () => {
    expect(escapeHtml('<img src=x>&"a"')).toBe('&lt;img src=x&gt;&amp;&quot;a&quot;')
  })
  it('正常文本不变', () => {
    expect(escapeHtml('人物设定')).toBe('人物设定')
  })
})

describe('mentionLogic · MENTION_RE', () => {
  it('全局匹配多占位符', () => {
    const matches = '@{{node:n1}} @{{asset:a1}}'.matchAll(MENTION_RE)
    const ids = Array.from(matches).map((m) => m[2])
    expect(ids).toEqual(['n1', 'a1'])
  })
})
