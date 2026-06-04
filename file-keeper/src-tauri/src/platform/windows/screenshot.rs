#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PhysicalScreenRegion {
    pub x: i32,
    pub y: i32,
    pub width: i32,
    pub height: i32,
}

pub fn validate_region(region: &PhysicalScreenRegion) -> Result<(), String> {
    if region.width <= 0 || region.height <= 0 {
        return Err("截图区域无效".to_string());
    }
    Ok(())
}

#[cfg(target_os = "windows")]
pub fn capture_screen_region(region: &PhysicalScreenRegion) -> Result<Vec<u8>, String> {
    validate_region(region)?;
    capture_screen_region_windows(region)
}

#[cfg(not(target_os = "windows"))]
pub fn capture_screen_region(region: &PhysicalScreenRegion) -> Result<Vec<u8>, String> {
    let _ = region;
    Err("当前平台尚未实现截图".to_string())
}

#[cfg(target_os = "windows")]
fn capture_screen_region_windows(region: &PhysicalScreenRegion) -> Result<Vec<u8>, String> {
    use image::{ImageBuffer, ImageFormat, Rgba};
    use std::io::Cursor;
    use windows::Win32::Foundation::HWND;
    use windows::Win32::Graphics::Gdi::{
        BitBlt, CreateCompatibleBitmap, CreateCompatibleDC, DeleteDC, DeleteObject, GetDC, GetDIBits,
        ReleaseDC, SelectObject, BITMAPINFO, BITMAPINFOHEADER, BI_RGB, DIB_RGB_COLORS, HBITMAP,
        SRCCOPY,
    };

    unsafe {
        let screen_dc = GetDC(HWND(std::ptr::null_mut()));
        if screen_dc.0 == std::ptr::null_mut() {
            return Err("无法获取屏幕 DC".to_string());
        }
        let memory_dc = CreateCompatibleDC(screen_dc);
        if memory_dc.0 == std::ptr::null_mut() {
            let _ = ReleaseDC(HWND(std::ptr::null_mut()), screen_dc);
            return Err("无法创建截图 DC".to_string());
      }
        let bitmap = CreateCompatibleBitmap(screen_dc, region.width, region.height);
        if bitmap.0 == std::ptr::null_mut() {
            let _ = DeleteDC(memory_dc);
            let _ = ReleaseDC(HWND(std::ptr::null_mut()), screen_dc);
            return Err("无法创建截图位图".to_string());
        }
        let old_object = SelectObject(memory_dc, bitmap);
        let blt_result = BitBlt(memory_dc, 0, 0, region.width, region.height, screen_dc, region.x, region.y, SRCCOPY);
        if blt_result.is_err() {
            let _ = SelectObject(memory_dc, old_object);
            let _ = DeleteObject(bitmap);
            let _ = DeleteDC(memory_dc);
            let _ = ReleaseDC(HWND(std::ptr::null_mut()), screen_dc);
            return Err("屏幕截图失败".to_string());
        }

        let mut info = BITMAPINFO {
            bmiHeader: BITMAPINFOHEADER {
           biSize: std::mem::size_of::<BITMAPINFOHEADER>() as u32,
                biWidth: region.width,
             biHeight: -region.height,
           biPlanes: 1,
                biBitCount: 32,
                biCompression: BI_RGB.0,
              ..Default::default()
            },
          ..Default::default()
        };
        let mut pixels = vec![0u8; (region.width * region.height * 4) as usize];
        let rows = GetDIBits(
            memory_dc,
            HBITMAP(bitmap.0),
            0,
            region.height as u32,
            Some(pixels.as_mut_ptr() as *mut _),
          &mut info,
            DIB_RGB_COLORS,
        );

        let _ = SelectObject(memory_dc, old_object);
        let _ = DeleteObject(bitmap);
        let _ = DeleteDC(memory_dc);
        let _ = ReleaseDC(HWND(std::ptr::null_mut()), screen_dc);

        if rows == 0 {
            return Err("读取截图像素失败".to_string());
        }

        for chunk in pixels.chunks_exact_mut(4) {
          chunk.swap(0, 2);
            chunk[3] = 255;
        }

        let image = ImageBuffer::<Rgba<u8>, _>::from_raw(region.width as u32, region.height as u32, pixels)
      .ok_or_else(|| "截图像素格式无效".to_string())?;
        let mut bytes = Vec::new();
     image.write_to(&mut Cursor::new(&mut bytes), ImageFormat::Png).map_err(|err| err.to_string())?;
        Ok(bytes)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rejects_empty_physical_region() {
        let region = PhysicalScreenRegion { x: 0, y: 0, width: 0, height: 10 };
        assert!(validate_region(&region).is_err());
    }
}
