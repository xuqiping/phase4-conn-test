import { beforeEach, describe, expect, it, vi } from 'vitest'
import { WebviewWindow } from '@tauri-apps/api/webviewWindow'
import { closeScreenshotOverlayWindow, openScreenshotOverlayWindow } from '../screenshotOverlay'

const overlayWindowMock = vi.hoisted(() => ({
  show: vi.fn().mockResolvedValue(undefined),
  setFocus: vi.fn().mockResolvedValue(undefined),
  setAlwaysOnTop: vi.fn().mockResolvedValue(undefined),
  destroy: vi.fn().mockResolvedValue(undefined),
  once: vi.fn((event: string, handler: () => void) => {
    if (event === 'tauri://created') handler()
    return Promise.resolve(() => undefined)
  })
}))

vi.mock('@tauri-apps/api/webviewWindow', () => ({
  WebviewWindow: Object.assign(
    vi.fn(function WebviewWindowMock() {
      return overlayWindowMock
    }),
    { getByLabel: vi.fn().mockResolvedValue(null) }
  )
}))

describe('screenshot overlay window api', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(WebviewWindow.getByLabel).mockResolvedValue(null)
  })
  it('opens a fullscreen transparent topmost screenshot overlay window', async () => {
    await openScreenshotOverlayWindow()

    expect(WebviewWindow).toHaveBeenCalledWith('screenshot-overlay', expect.objectContaining({
      url: '/?screenshotOverlay=1',
      fullscreen: true,
      decorations: false,
      transparent: true,
      alwaysOnTop: true,
      skipTaskbar: true,
      resizable: false,
      focus: true,
      backgroundColor: '#00000000'
    }))
  })

  it('reuses an existing overlay window instead of creating a second one', async () => {
    vi.mocked(WebviewWindow.getByLabel).mockResolvedValueOnce(overlayWindowMock as any)

    await openScreenshotOverlayWindow()

    expect(WebviewWindow).not.toHaveBeenCalled()
    expect(overlayWindowMock.show).toHaveBeenCalledOnce()
    expect(overlayWindowMock.setFocus).toHaveBeenCalledOnce()
  })

  it('does not hang when the local created event is missed but the overlay window exists', async () => {
    overlayWindowMock.once.mockResolvedValue(() => undefined)
    vi.mocked(WebviewWindow.getByLabel)
      .mockResolvedValueOnce(null)
      .mockResolvedValueOnce(overlayWindowMock as any)

    await openScreenshotOverlayWindow()

    expect(overlayWindowMock.show).toHaveBeenCalledOnce()
    expect(overlayWindowMock.setFocus).toHaveBeenCalledOnce()
    expect(overlayWindowMock.setAlwaysOnTop).toHaveBeenCalledWith(true)
  })

  it('destroys the overlay window when closing', async () => {
    vi.mocked(WebviewWindow.getByLabel).mockResolvedValueOnce(overlayWindowMock as any)

    await closeScreenshotOverlayWindow()

    expect(overlayWindowMock.destroy).toHaveBeenCalledOnce()
  })
})
