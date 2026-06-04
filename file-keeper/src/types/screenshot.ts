export interface ScreenshotRegion {
  x: number
  y: number
  width: number
  height: number
  scaleFactor: number
}

export interface ScreenshotCaptureResult {
  itemId: string
}

export type ScreenshotOcrProvider = 'local_sidecar' | 'windows_system' | 'disabled'

export interface ScreenshotOcrStatus {
  provider: ScreenshotOcrProvider
  available: boolean
}
