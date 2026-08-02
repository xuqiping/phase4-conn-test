import { describe, expect, it, vi } from 'vitest'
import { invoke } from '@tauri-apps/api/core'
import { captureScreenshotRegion, getScreenshotOcrStatus } from '../screenshot'

vi.mock('@tauri-apps/api/core', () => ({
  invoke: vi.fn()
}))

describe('screenshot api', () => {
  it('captures a selected screenshot region', async () => {
    vi.mocked(invoke).mockResolvedValueOnce({ itemId: 'shot-1' })

    const result = await captureScreenshotRegion({ x: 10, y: 20, width: 300, height: 160, scaleFactor: 1 })

    expect(invoke).toHaveBeenCalledWith('capture_screenshot_region', {
      region: { x: 10, y: 20, width: 300, height: 160, scaleFactor: 1 }
    })
    expect(result.itemId).toBe('shot-1')
  })

  it('reads OCR provider status', async () => {
    vi.mocked(invoke).mockResolvedValueOnce({ provider: 'windows_system', available: true })

    const result = await getScreenshotOcrStatus()

    expect(invoke).toHaveBeenCalledWith('get_screenshot_ocr_status')
    expect(result.provider).toBe('windows_system')
  })
})
