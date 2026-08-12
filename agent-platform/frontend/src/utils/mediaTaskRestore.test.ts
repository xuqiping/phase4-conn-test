import { describe, expect, it } from 'vitest'
import { bucketRestoredAttachments } from './mediaTaskRestore'

describe('bucketRestoredAttachments', () => {
  it('AC-V3-03 按首尾帧和普通媒体恢复，并为旧任务补名称', () => {
    let id = 0
    const result = bucketRestoredAttachments([
      { fileId: 'first', kind: 'image', frameRole: 'first_frame', name: '开场.png', previewUrl: '/api/files/first' },
      { fileId: 'last', kind: 'image', frameRole: 'last_frame', name: null, previewUrl: '/api/files/last' },
      { fileId: 'img', kind: 'image', frameRole: null, name: null, previewUrl: '/api/files/img' },
      { fileId: 'vid', kind: 'video', frameRole: null, name: '动作.mp4', previewUrl: '/api/files/vid' },
      { fileId: 'aud', kind: 'audio', frameRole: null, name: null, previewUrl: '/api/files/aud' }
    ], () => `restored-${++id}`)

    expect(result.firstFrame?.name).toBe('开场.png')
    expect(result.lastFrame?.name).toBe('尾帧')
    expect(result.images[0].name).toBe('参考图1')
    expect(result.videos[0].name).toBe('动作.mp4')
    expect(result.audios[0].name).toBe('参考音频1')
    expect(result.audios[0].id).toBe('restored-5')
  })
})
