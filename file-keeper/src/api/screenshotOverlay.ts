import { WebviewWindow } from '@tauri-apps/api/webviewWindow'

const SCREENSHOT_OVERLAY_LABEL = 'screenshot-overlay'
const OVERLAY_READY_TIMEOUT_MS = 1_000
const OVERLAY_READY_POLL_MS = 50

export async function openScreenshotOverlayWindow(): Promise<void> {
  const existing = await WebviewWindow.getByLabel(SCREENSHOT_OVERLAY_LABEL)
  if (existing) {
    console.log('[ScreenshotOverlay] Reusing existing window')
    await focusOverlayWindow(existing)
    return
  }

  console.log('[ScreenshotOverlay] Creating new window...')
  const overlay = new WebviewWindow(SCREENSHOT_OVERLAY_LABEL, {
    url: '/?screenshotOverlay=1',
    title: 'Screenshot Overlay',
    fullscreen: true,
    decorations: false,
    transparent: true,
    alwaysOnTop: true,
    skipTaskbar: true,
    resizable: false,
    focus: true,
    backgroundColor: '#00000000'
  })

  const readyWindow = await waitForOverlayWindow(overlay)
  console.log('[ScreenshotOverlay] Window ready, focusing...')
  await focusOverlayWindow(readyWindow)
  console.log('[ScreenshotOverlay] Window focused and ready')
}

export async function closeScreenshotOverlayWindow(): Promise<void> {
  const overlay = await WebviewWindow.getByLabel(SCREENSHOT_OVERLAY_LABEL)
  await overlay?.destroy()
}

async function waitForOverlayWindow(createdOverlay: WebviewWindow): Promise<WebviewWindow> {
  return await new Promise<WebviewWindow>((resolve, reject) => {
    let settled = false
    let timeoutId: ReturnType<typeof setTimeout> | null = null
    let pollId: ReturnType<typeof setTimeout> | null = null

    const settle = (callback: () => void) => {
      if (settled) return
      settled = true
      if (timeoutId) clearTimeout(timeoutId)
      if (pollId) clearTimeout(pollId)
      callback()
    }

    const poll = async () => {
      const existing = await WebviewWindow.getByLabel(SCREENSHOT_OVERLAY_LABEL)
      if (existing) {
        settle(() => resolve(existing))
        return
      }
      pollId = setTimeout(() => void poll(), OVERLAY_READY_POLL_MS)
    }

    void createdOverlay.once('tauri://created', () => {
      settle(() => resolve(createdOverlay))
    })
    void createdOverlay.once('tauri://error', (event) => {
      settle(() => reject(event.payload))
    })

    timeoutId = setTimeout(() => {
      settle(() => reject(new Error('截图遮罩窗口创建超时')))
    }, OVERLAY_READY_TIMEOUT_MS)
    void poll()
  })
}

async function focusOverlayWindow(overlay: WebviewWindow): Promise<void> {
  await overlay.setAlwaysOnTop(true)
  await overlay.show()
  await overlay.setFocus()
}
