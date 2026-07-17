use serde::{Deserialize, Serialize};
use tauri::{AppHandle, Emitter, State};
use crate::commands::auth::SignedEntitlementState;
use crate::clipboard::ClipboardService;

const MODULE_CODE: &str = "clipboard";

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ScreenshotRegion {
    pub x: f64,
    pub y: f64,
    pub width: f64,
    pub height: f64,
    pub scale_factor: f64,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ScreenshotCaptureResult {
    pub item_id: String,
}

fn physical_region(region: &ScreenshotRegion) -> Result<crate::platform::windows::screenshot::PhysicalScreenRegion, String> {
    // 检查尺寸：拒绝负数和小于最小值
    if region.width < 0.0 || region.height < 0.0 {
        return Err("截图区域尺寸无效".to_string());
    }
    if region.width < 8.0 || region.height < 8.0 {
        return Err("截图区域太小".to_string());
    }

    let scale = if region.scale_factor <= 0.0 { 1.0 } else { region.scale_factor };

    // 计算物理坐标并检查溢出
    let check_i32_range = |value: f64| -> Result<i32, String> {
        let rounded = value.round();
        if rounded < i32::MIN as f64 || rounded > i32::MAX as f64 {
        return Err("坐标超出有效范围".to_string());
        }
     Ok(rounded as i32)
    };

    Ok(crate::platform::windows::screenshot::PhysicalScreenRegion {
     x: check_i32_range(region.x * scale)?,
        y: check_i32_range(region.y * scale)?,
        width: check_i32_range(region.width * scale)?,
        height: check_i32_range(region.height * scale)?,
    })
}

#[tauri::command]
pub fn capture_screenshot_region(
    entitlement_state: State<'_, SignedEntitlementState>,
    app: AppHandle,
    region: ScreenshotRegion,
    service: State<'_, ClipboardService>,
) -> Result<ScreenshotCaptureResult, String> {
    entitlement_state
        .require_module(MODULE_CODE)
        .map_err(|e| e.user_message())?;

    let region = physical_region(&region)?;
    let png_bytes = crate::platform::windows::screenshot::capture_screen_region(&region)?;
    let app_for_ocr = app.clone();
    let item_id = service.collect_screenshot_bytes_snapshot_with_ocr_update(&png_bytes, move |updated_id| {
        let _ = app_for_ocr.emit("clipboard://changed", updated_id);
    })?;
    let _ = app.emit("clipboard://changed", item_id.clone());
    Ok(ScreenshotCaptureResult { item_id })
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ScreenshotOcrStatus {
    pub provider: String,
    pub available: bool,
}

#[tauri::command]
pub fn get_screenshot_ocr_status() -> Result<ScreenshotOcrStatus, String> {
    let status = crate::clipboard::ocr_provider::provider_status()?;
    Ok(ScreenshotOcrStatus { provider: status.provider, available: status.available })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn screenshot_region_rejects_invalid_size() {
        let result = physical_region(&ScreenshotRegion { x: 0.0, y: 0.0, width: 2.0, height: 7.0, scale_factor: 1.0 });
        assert!(result.is_err());
    }

    #[test]
    fn screenshot_region_applies_scale_factor() {
        let result = physical_region(&ScreenshotRegion { x: 10.0, y: 20.0, width: 30.0, height: 40.0, scale_factor: 1.5 }).unwrap();
        assert_eq!(result.x, 15);
        assert_eq!(result.y, 30);
        assert_eq!(result.width, 45);
        assert_eq!(result.height, 60);
    }

    #[test]
    fn screenshot_region_rejects_negative_width() {
        let result = physical_region(&ScreenshotRegion { x: 0.0, y: 0.0, width: -10.0, height: 100.0, scale_factor: 1.0 });
      assert!(result.is_err());
        assert!(result.unwrap_err().contains("尺寸无效"));
    }

    #[test]
    fn screenshot_region_rejects_negative_height() {
      let result = physical_region(&ScreenshotRegion { x: 0.0, y: 0.0, width: 100.0, height: -20.0, scale_factor: 1.0 });
        assert!(result.is_err());
        assert!(result.unwrap_err().contains("尺寸无效"));
    }

    #[test]
    fn screenshot_region_rejects_coordinate_overflow() {
        let result = physical_region(&ScreenshotRegion { x: 3e9, y: 0.0, width: 100.0, height: 100.0, scale_factor: 1.0 });
        assert!(result.is_err());
        assert!(result.unwrap_err().contains("坐标超出有效范围"));
    }

    #[test]
    fn screenshot_region_allows_negative_coordinates() {
        let result = physical_region(&ScreenshotRegion { x: -100.0, y: -200.0, width: 300.0, height: 400.0, scale_factor: 1.0 });
        assert!(result.is_ok());
        let region = result.unwrap();
        assert_eq!(region.x, -100);
        assert_eq!(region.y, -200);
    }
}
