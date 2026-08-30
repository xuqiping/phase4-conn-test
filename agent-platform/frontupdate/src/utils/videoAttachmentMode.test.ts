import { describe, expect, it } from 'vitest'
import { canAddVideoAttachment } from './videoAttachmentMode'

describe('video attachment mode', () => {
  it('rejects a frame when reference media already exists', () => {
    expect(canAddVideoAttachment('first', { frameCount: 0, referenceMediaCount: 1 })).toBe(false)
  })

  it('rejects reference media when a frame already exists', () => {
    expect(canAddVideoAttachment('video', { frameCount: 1, referenceMediaCount: 0 })).toBe(false)
  })

  it('allows first and last frames together', () => {
    expect(canAddVideoAttachment('last', { frameCount: 1, referenceMediaCount: 0 })).toBe(true)
  })

  it('allows different reference media together', () => {
    expect(canAddVideoAttachment('audio', { frameCount: 0, referenceMediaCount: 2 })).toBe(true)
  })
})
