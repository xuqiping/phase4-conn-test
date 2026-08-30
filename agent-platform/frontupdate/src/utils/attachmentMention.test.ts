import { describe, it, expect } from 'vitest'
import { interpolateAttachmentPrompt, BROKEN_ATTACHMENT_MARKER } from './attachmentMention'

describe('interpolateAttachmentPrompt', () => {
  const imgs = [{ id: 'i1' }, { id: 'i2' }]
  const vids = [{ id: 'v1' }]
  const auds = [{ id: 'a1' }, { id: 'a2' }, { id: 'a3' }]

  it('序号化为 图N/视频N/音频N', () => {
    const p = '以 @{{image:i1}} 为参考，@{{video:v1}} 运镜，@{{audio:a2}} 作 BGM'
    expect(interpolateAttachmentPrompt(p, imgs, vids, auds))
      .toBe('以 图1 为参考，视频1 运镜，音频2 作 BGM')
  })

  it('序号按当前列表顺序（重排跟随，id 稳定）', () => {
    // i2 现在排第 1 → 图1；i1 排第 2 → 图2
    const reordered = [{ id: 'i2' }, { id: 'i1' }]
    expect(interpolateAttachmentPrompt('@{{image:i1}}', reordered, vids, auds)).toBe('图2')
    expect(interpolateAttachmentPrompt('@{{image:i2}}', reordered, vids, auds)).toBe('图1')
  })

  it('断链（附件已删）降级标记，不送裸 token', () => {
    expect(interpolateAttachmentPrompt('用 @{{image:gone}}', imgs, vids, auds))
      .toBe(`用 ${BROKEN_ATTACHMENT_MARKER}`)
  })

  it('无 @ 占位符 → 原文返回', () => {
    const p = '一只橘猫在窗台上晒太阳'
    expect(interpolateAttachmentPrompt(p, imgs, vids, auds)).toBe(p)
  })

  it('空串/null 安全', () => {
    expect(interpolateAttachmentPrompt('', imgs, vids, auds)).toBe('')
  })

  it('画布 node/asset token 不被误处理（透传）', () => {
    const p = '@{{node:n1}} 与 @{{asset:a1}} 不受视频页插值影响'
    expect(interpolateAttachmentPrompt(p, imgs, vids, auds)).toBe(p)
  })

  it('多个同类附件全序号化', () => {
    const p = '@{{audio:a1}} @{{audio:a2}} @{{audio:a3}}'
    expect(interpolateAttachmentPrompt(p, imgs, vids, auds)).toBe('音频1 音频2 音频3')
  })

  it('混合：正常 + 断链同串', () => {
    const p = '@{{image:i1}} 和 @{{image:gone}}'
    expect(interpolateAttachmentPrompt(p, imgs, vids, auds)).toBe(`图1 和 ${BROKEN_ATTACHMENT_MARKER}`)
  })
})
