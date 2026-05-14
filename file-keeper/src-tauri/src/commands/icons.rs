use tauri::command;

#[cfg(windows)]
mod windows_impl {
    use windows::Win32::UI::Shell::{SHGetFileInfoW, SHFILEINFOW, SHGFI_ICON, SHGFI_SMALLICON, SHGFI_USEFILEATTRIBUTES};
    use windows::Win32::UI::WindowsAndMessaging::DestroyIcon;
    use windows::core::PWSTR;

    pub fn extract_icon(path: &str) -> Option<String> {
        let wide_path: Vec<u16> = path.encode_utf16().chain(Some(0)).collect();
        unsafe {
          let mut info: SHFILEINFOW = std::mem::zeroed();
            let result = SHGetFileInfoW(
         PWSTR(wide_path.as_ptr() as *mut u16),
                0x80, // FILE_ATTRIBUTE_NORMAL
                Some(&mut info),
                std::mem::size_of::<SHFILEINFOW>() as u32,
                SHGFI_ICON | SHGFI_SMALLICON | SHGFI_USEFILEATTRIBUTES,
            );
        if result != 0 && !info.hIcon.is_invalid() {
                // Get icon data as PNG via HICON -> bitmap -> PNG
                // Simplified: for MVP, just return empty and use extension fallback
                // Full implementation would require bitmap conversion.
                DestroyIcon(info.hIcon);
            }
        }
        None // Fallback to extension-based
    }
}

#[cfg(target_os = "macos")]
mod macos_impl {
    pub fn extract_icon(_path: &str) -> Option<String> {
        // Use NSWorkspace to get icon as TIFF, then convert to base64 PNG
        // For MVP, return None and rely on extension fallback
        None
    }
}

#[cfg(target_os = "linux")]
mod linux_impl {
    pub fn extract_icon(_path: &str) -> Option<String> {
        // Use `gio info` or fallback
        None
    }
}

#[command]
pub async fn get_file_icon(path: String) -> Result<String, String> {
    #[cfg(windows)]
    let icon_data = windows_impl::extract_icon(&path);
    #[cfg(target_os = "macos")]
    let icon_data = macos_impl::extract_icon(&path);
    #[cfg(target_os = "linux")]
    let icon_data = linux_impl::extract_icon(&path);

    match icon_data {
        Some(data) => Ok(data),
        None => Ok(String::new()), // Empty means use extension fallback
    }
}
