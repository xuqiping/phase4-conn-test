use tauri::command;

#[cfg(windows)]
mod windows_impl {
    use base64::{engine::general_purpose::STANDARD as BASE64, Engine as _};
    use windows::core::PWSTR;
    use windows::Win32::Foundation::HWND;
    use windows::Win32::Graphics::Gdi::{
        CreateCompatibleDC, CreateDIBSection, DeleteDC, DeleteObject, GdiFlush, GetDC, ReleaseDC,
     SelectObject, BITMAPINFO, BITMAPINFOHEADER, BI_RGB, DIB_RGB_COLORS, HBITMAP, HBRUSH, HDC,
        HGDIOBJ,
    };
    use windows::Win32::Storage::FileSystem::FILE_FLAGS_AND_ATTRIBUTES;
    use windows::Win32::UI::Shell::{
      SHGetFileInfoW, SHFILEINFOW, SHGFI_ICON, SHGFI_LARGEICON, SHGFI_USEFILEATTRIBUTES,
    };
    use windows::Win32::UI::WindowsAndMessaging::{
      DestroyIcon, DrawIconEx, GetIconInfo, DI_NORMAL, ICONINFO,
    };

  const ICON_SIZE: i32 = 32;

    pub fn extract_icon(path: &str, use_real_icon: bool) -> Option<String> {
        let wide_path: Vec<u16> = path.encode_utf16().chain(Some(0)).collect();
      unsafe {
          let mut info: SHFILEINFOW = std::mem::zeroed();

         // 根据 use_real_icon 决定是否使用 SHGFI_USEFILEATTRIBUTES
            let flags = if use_real_icon {
                SHGFI_ICON | SHGFI_LARGEICON
            } else {
           SHGFI_ICON | SHGFI_LARGEICON | SHGFI_USEFILEATTRIBUTES
        };

         let file_attrs = if use_real_icon {
              FILE_FLAGS_AND_ATTRIBUTES(0)
            } else {
            FILE_FLAGS_AND_ATTRIBUTES(0x80) // FILE_ATTRIBUTE_NORMAL
            };
            let result = SHGetFileInfoW(
          PWSTR(wide_path.as_ptr() as *mut u16),
                file_attrs,
             Some(&mut info),
            std::mem::size_of::<SHFILEINFOW>() as u32,
                flags,
            );
            if result == 0 || info.hIcon.is_invalid() {
         return None;
            }

            let png_b64 = hicon_to_png_base64(info.hIcon);
        let _ = DestroyIcon(info.hIcon);
     png_b64.map(|b64| format!("data:image/png;base64,{}", b64))
        }
    }

    /// Render an HICON to a 32-bit DIB, swap BGRA->RGBA, encode as PNG, base64 it.
    unsafe fn hicon_to_png_base64(
        hicon: windows::Win32::UI::WindowsAndMessaging::HICON,
    ) -> Option<String> {
        // Use the icon's actual dimensions if available; fall back to ICON_SIZE.
     let mut icon_info: ICONINFO = std::mem::zeroed();
        let (width, height) = if GetIconInfo(hicon, &mut icon_info).is_ok() {
            // Best-effort: query the color bitmap for its real size if present.
          // For simplicity we use ICON_SIZE; the icon will scale via DrawIconEx.
            if !icon_info.hbmColor.is_invalid() {
           let _ = DeleteObject(icon_info.hbmColor);
            }
            if !icon_info.hbmMask.is_invalid() {
          let _ = DeleteObject(icon_info.hbmMask);
          }
            (ICON_SIZE, ICON_SIZE)
        } else {
            (ICON_SIZE, ICON_SIZE)
        };

        let screen_dc: HDC = GetDC(HWND(std::ptr::null_mut()));
        if screen_dc.is_invalid() {
            return None;
      }

    let mem_dc: HDC = CreateCompatibleDC(screen_dc);
        if mem_dc.is_invalid() {
            ReleaseDC(HWND(std::ptr::null_mut()), screen_dc);
            return None;
      }

        // Top-down 32-bit BGRA DIB (negative height).
        let mut bmi: BITMAPINFO = std::mem::zeroed();
        bmi.bmiHeader = BITMAPINFOHEADER {
       biSize: std::mem::size_of::<BITMAPINFOHEADER>() as u32,
            biWidth: width,
      biHeight: -height, // top-down
       biPlanes: 1,
            biBitCount: 32,
            biCompression: BI_RGB.0,
            biSizeImage: (width * height * 4) as u32,
            biXPelsPerMeter: 0,
            biYPelsPerMeter: 0,
            biClrUsed: 0,
         biClrImportant: 0,
        };

      let mut bits_ptr: *mut core::ffi::c_void = std::ptr::null_mut();
        let dib_result = CreateDIBSection(
          mem_dc,
        &bmi,
       DIB_RGB_COLORS,
         &mut bits_ptr,
            windows::Win32::Foundation::HANDLE(std::ptr::null_mut()),
        0,
        );

    let dib: HBITMAP = match dib_result {
    Ok(h) if !h.is_invalid() && !bits_ptr.is_null() => h,
            _ => {
                let _ = DeleteDC(mem_dc);
            ReleaseDC(HWND(std::ptr::null_mut()), screen_dc);
                return None;
            }
      };

        let prev_obj: HGDIOBJ = SelectObject(mem_dc, HGDIOBJ(dib.0));

        // Zero the buffer (transparent background) before drawing.
        std::ptr::write_bytes(bits_ptr as *mut u8, 0, (width * height * 4) as usize);

        let draw_ok = DrawIconEx(
       mem_dc,
            0,
            0,
        hicon,
            width,
          height,
            0,
            HBRUSH(std::ptr::null_mut()),
       DI_NORMAL,
        )
     .is_ok();

        // Force GDI to flush before reading pixels.
      let _ = GdiFlush();

        let result = if draw_ok {
       // Read BGRA pixels and convert to RGBA.
       let pixel_count = (width * height) as usize;
     let mut rgba = vec![0u8; pixel_count * 4];
            let src = std::slice::from_raw_parts(bits_ptr as *const u8, pixel_count * 4);
          for i in 0..pixel_count {
                let b = src[i * 4];
           let g = src[i * 4 + 1];
                let r = src[i * 4 + 2];
              let a = src[i * 4 + 3];
          rgba[i * 4] = r;
                rgba[i * 4 + 1] = g;
              rgba[i * 4 + 2] = b;
                rgba[i * 4 + 3] = a;
         }
            encode_png_base64(&rgba, width as u32, height as u32)
        } else {
       None
        };

        // Cleanup
        SelectObject(mem_dc, prev_obj);
        let _ = DeleteObject(HGDIOBJ(dib.0));
        let _ = DeleteDC(mem_dc);
        ReleaseDC(HWND(std::ptr::null_mut()), screen_dc);

        result
    }

    fn encode_png_base64(rgba: &[u8], width: u32, height: u32) -> Option<String> {
        let mut buf: Vec<u8> = Vec::new();
        {
        let mut encoder = png::Encoder::new(&mut buf, width, height);
        encoder.set_color(png::ColorType::Rgba);
            encoder.set_depth(png::BitDepth::Eight);
            let mut writer = encoder.write_header().ok()?;
          writer.write_image_data(rgba).ok()?;
    }
        Some(BASE64.encode(&buf))
    }
}

#[cfg(target_os = "macos")]
mod macos_impl {
    pub fn extract_icon(_path: &str, _use_real_icon: bool) -> Option<String> {
      // Use NSWorkspace to get icon as TIFF, then convert to base64 PNG
        // For MVP, return None and rely on extension fallback
        None
    }
}

#[cfg(target_os = "linux")]
mod linux_impl {
    pub fn extract_icon(_path: &str, _use_real_icon: bool) -> Option<String> {
        // Use `gio info` or fallback
        None
    }
}

#[command]
pub async fn get_file_icon(path: String, use_real_icon: Option<bool>) -> Result<String, String> {
    let use_real = use_real_icon.unwrap_or(true); // 默认使用真实图标
  #[cfg(windows)]
    let icon_data = windows_impl::extract_icon(&path, use_real);
    #[cfg(target_os = "macos")]
    let icon_data = macos_impl::extract_icon(&path, use_real);
  #[cfg(target_os = "linux")]
    let icon_data = linux_impl::extract_icon(&path, use_real);

    match icon_data {
        Some(data) => Ok(data),
        None => Ok(String::new()), // Empty means use extension fallback
    }
}
