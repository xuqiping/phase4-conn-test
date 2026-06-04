import { invoke } from '@tauri-apps/api/core'
import type { ScreenshotCaptureResult, ScreenshotOcrStatus, ScreenshotRegion } from '../types/screenshot'

export async function captureScreenshotRegion(region: ScreenshotRegion): Promise<ScreenshotCaptureResult> {
  return await invoke<ScreenshotCaptureResult>('capture_screenshot_region', { region })
}

export async function getScreenshotOcrStatus(): Promise<ScreenshotOcrStatus> {
  return await invoke<ScreenshotOcrStatus>('get_screenshot_ocr_status')
}
