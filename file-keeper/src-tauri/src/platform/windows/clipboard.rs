use windows::Win32::Foundation::{HANDLE, HGLOBAL, HWND};
use windows::Win32::System::DataExchange::{CloseClipboard, EmptyClipboard, GetClipboardData, IsClipboardFormatAvailable, OpenClipboard, SetClipboardData};
use windows::Win32::System::Memory::{GlobalAlloc, GlobalLock, GlobalUnlock, GMEM_MOVEABLE};

const CF_UNICODETEXT: u32 = 13;

pub fn read_text() -> Result<Option<String>, String> {
    unsafe {
        if IsClipboardFormatAvailable(CF_UNICODETEXT).is_err() {
            return Ok(None);
        }

        OpenClipboard(HWND::default()).map_err(|err| err.to_string())?;
        let result = read_text_inner();
        let _ = CloseClipboard();
        result
    }
}

pub fn write_text(text: &str) -> Result<(), String> {
    unsafe {
        OpenClipboard(HWND::default()).map_err(|err| err.to_string())?;
        let result = write_text_inner(text);
        let _ = CloseClipboard();
        result
    }
}

unsafe fn read_text_inner() -> Result<Option<String>, String> {
    let handle = GetClipboardData(CF_UNICODETEXT).map_err(|err| err.to_string())?;
    if handle.is_invalid() {
        return Ok(None);
    }
    let global = HGLOBAL(handle.0);

    let ptr = GlobalLock(global);
    if ptr.is_null() {
        return Ok(None);
    }

    let wide_ptr = ptr as *const u16;
    let mut len = 0usize;
    while *wide_ptr.add(len) != 0 {
        len += 1;
    }

    let slice = std::slice::from_raw_parts(wide_ptr, len);
    let text = String::from_utf16_lossy(slice);
    let _ = GlobalUnlock(global);
    Ok(Some(text))
}

unsafe fn write_text_inner(text: &str) -> Result<(), String> {
    EmptyClipboard().map_err(|err| err.to_string())?;

    let mut wide: Vec<u16> = text.encode_utf16().collect();
    wide.push(0);
    let bytes = wide.len() * std::mem::size_of::<u16>();
    let handle = GlobalAlloc(GMEM_MOVEABLE, bytes).map_err(|err| err.to_string())?;
    let ptr = GlobalLock(handle);
    if ptr.is_null() {
        return Err("无法锁定剪贴板内存".to_string());
    }

    std::ptr::copy_nonoverlapping(wide.as_ptr() as *const u8, ptr as *mut u8, bytes);
    let _ = GlobalUnlock(handle);
    SetClipboardData(CF_UNICODETEXT, HANDLE(handle.0)).map_err(|err| err.to_string())?;
    Ok(())
}
