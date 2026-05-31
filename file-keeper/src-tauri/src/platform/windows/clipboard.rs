use image::codecs::png::PngEncoder;
use image::{ColorType, ImageEncoder};
use windows::core::w;
use windows::Win32::Foundation::{HANDLE, HGLOBAL, HWND};
use windows::Win32::System::DataExchange::{CloseClipboard, EmptyClipboard, GetClipboardData, IsClipboardFormatAvailable, OpenClipboard, RegisterClipboardFormatW, SetClipboardData};
use windows::Win32::System::Memory::{GlobalAlloc, GlobalLock, GlobalSize, GlobalUnlock, GMEM_MOVEABLE, GMEM_ZEROINIT};
use windows::Win32::UI::Shell::{DragQueryFileW, DROPFILES, HDROP};

const CF_DIB: u32 = 8;
const CF_UNICODETEXT: u32 = 13;
const CF_HDROP: u32 = 15;
const CF_DIBV5: u32 = 17;
const BI_RGB: u32 = 0;
const BI_BITFIELDS: u32 = 3;

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

pub fn read_files() -> Result<Option<Vec<String>>, String> {
    unsafe {
        if IsClipboardFormatAvailable(CF_HDROP).is_err() {
            return Ok(None);
        }

        OpenClipboard(HWND::default()).map_err(|err| err.to_string())?;
        let result = read_files_inner();
        let _ = CloseClipboard();
        result
    }
}

pub fn read_image() -> Result<Option<Vec<u8>>, String> {
    unsafe {
        let png_format = RegisterClipboardFormatW(w!("PNG"));
        if png_format != 0 && IsClipboardFormatAvailable(png_format).is_ok() {
            OpenClipboard(HWND::default()).map_err(|err| err.to_string())?;
            let result = read_global_bytes_inner(png_format);
            let _ = CloseClipboard();
            if let Some(bytes) = result? {
                if !bytes.is_empty() {
                    return Ok(Some(bytes));
                }
            }
        }

        if IsClipboardFormatAvailable(CF_DIBV5).is_ok() {
            OpenClipboard(HWND::default()).map_err(|err| err.to_string())?;
            let result = read_global_bytes_inner(CF_DIBV5);
            let _ = CloseClipboard();
            if let Some(bytes) = result? {
                if let Some(png) = dib_to_png_bytes(&bytes)? {
                    return Ok(Some(png));
                }
            }
        }

        if IsClipboardFormatAvailable(CF_DIB).is_ok() {
            OpenClipboard(HWND::default()).map_err(|err| err.to_string())?;
            let result = read_global_bytes_inner(CF_DIB);
            let _ = CloseClipboard();
            if let Some(bytes) = result? {
                if let Some(png) = dib_to_png_bytes(&bytes)? {
                    return Ok(Some(png));
                }
            }
        }

        Ok(None)
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

pub fn write_files(paths: &[String]) -> Result<(), String> {
    if paths.is_empty() {
        return Err("没有可复制的文件路径".to_string());
    }
    unsafe {
        OpenClipboard(HWND::default()).map_err(|err| err.to_string())?;
        let result = write_files_inner(paths);
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

unsafe fn read_files_inner() -> Result<Option<Vec<String>>, String> {
    let handle = GetClipboardData(CF_HDROP).map_err(|err| err.to_string())?;
    if handle.is_invalid() {
        return Ok(None);
    }

    let hdrop = HDROP(handle.0);
    let count = DragQueryFileW(hdrop, u32::MAX, None);
    if count == 0 {
        return Ok(None);
    }

    let mut files = Vec::new();
    for index in 0..count {
        let len = DragQueryFileW(hdrop, index, None);
        if len == 0 {
            continue;
        }
        let mut buffer = vec![0u16; len as usize + 1];
        let written = DragQueryFileW(hdrop, index, Some(&mut buffer));
        if written == 0 {
            continue;
        }
        files.push(String::from_utf16_lossy(&buffer[..written as usize]));
    }

    if files.is_empty() {
        Ok(None)
    } else {
        Ok(Some(files))
    }
}

unsafe fn read_global_bytes_inner(format: u32) -> Result<Option<Vec<u8>>, String> {
    let handle = GetClipboardData(format).map_err(|err| err.to_string())?;
    if handle.is_invalid() {
        return Ok(None);
    }
    let global = HGLOBAL(handle.0);
    let size = GlobalSize(global);
    if size == 0 {
        return Ok(None);
    }
    let ptr = GlobalLock(global);
    if ptr.is_null() {
        return Ok(None);
    }
    let bytes = std::slice::from_raw_parts(ptr as *const u8, size).to_vec();
    let _ = GlobalUnlock(global);
    Ok(Some(bytes))
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

unsafe fn write_files_inner(paths: &[String]) -> Result<(), String> {
    EmptyClipboard().map_err(|err| err.to_string())?;

    let mut wide_paths = Vec::<u16>::new();
    for path in paths {
        wide_paths.extend(path.encode_utf16());
        wide_paths.push(0);
    }
    wide_paths.push(0);

    let header_bytes = std::mem::size_of::<DROPFILES>();
    let path_bytes = wide_paths.len() * std::mem::size_of::<u16>();
    let total_bytes = header_bytes + path_bytes;
    let handle = GlobalAlloc(GMEM_MOVEABLE | GMEM_ZEROINIT, total_bytes).map_err(|err| err.to_string())?;
    let ptr = GlobalLock(handle);
    if ptr.is_null() {
        return Err("无法锁定剪贴板文件内存".to_string());
    }

    let drop_files = ptr as *mut DROPFILES;
    (*drop_files).pFiles = header_bytes as u32;
    (*drop_files).fWide = true.into();
    std::ptr::copy_nonoverlapping(
        wide_paths.as_ptr() as *const u8,
        (ptr as *mut u8).add(header_bytes),
        path_bytes,
    );
    let _ = GlobalUnlock(handle);
    SetClipboardData(CF_HDROP, HANDLE(handle.0)).map_err(|err| err.to_string())?;
    Ok(())
}

fn dib_to_png_bytes(dib: &[u8]) -> Result<Option<Vec<u8>>, String> {
    if dib.len() < 40 {
        return Ok(None);
    }
    let header_size = read_u32(dib, 0)? as usize;
    if header_size < 40 || dib.len() < header_size {
        return Ok(None);
    }
    let width = read_i32(dib, 4)?;
    let raw_height = read_i32(dib, 8)?;
    let planes = read_u16(dib, 12)?;
    let bit_count = read_u16(dib, 14)?;
    let compression = read_u32(dib, 16)?;
    if width <= 0 || raw_height == 0 || planes != 1 || !matches!(bit_count, 24 | 32) {
        return Ok(None);
    }
    if !matches!(compression, BI_RGB | BI_BITFIELDS) {
        return Ok(None);
    }

    let width = width as usize;
    let height = raw_height.unsigned_abs() as usize;
    let top_down = raw_height < 0;
    let bytes_per_pixel = (bit_count / 8) as usize;
    let row_stride = ((width * bit_count as usize + 31) / 32) * 4;
    let (red_mask, green_mask, blue_mask, alpha_mask, pixel_offset) = dib_masks_and_pixel_offset(dib, header_size, bit_count, compression)?;
    if dib.len() < pixel_offset + row_stride.saturating_mul(height) {
        return Ok(None);
    }

    let mut rgba = vec![0u8; width * height * 4];
    let mut saw_alpha = false;
    for y in 0..height {
        let source_y = if top_down { y } else { height - 1 - y };
        let row = pixel_offset + source_y * row_stride;
        for x in 0..width {
            let source = row + x * bytes_per_pixel;
            let target = (y * width + x) * 4;
            if bit_count == 24 {
                rgba[target] = dib[source + 2];
                rgba[target + 1] = dib[source + 1];
                rgba[target + 2] = dib[source];
                rgba[target + 3] = 255;
            } else {
                let pixel = u32::from_le_bytes([dib[source], dib[source + 1], dib[source + 2], dib[source + 3]]);
                rgba[target] = extract_masked_channel(pixel, red_mask);
                rgba[target + 1] = extract_masked_channel(pixel, green_mask);
                rgba[target + 2] = extract_masked_channel(pixel, blue_mask);
                rgba[target + 3] = if alpha_mask == 0 { 255 } else { extract_masked_channel(pixel, alpha_mask) };
                saw_alpha |= rgba[target + 3] != 0;
            }
        }
    }

    if bit_count == 32 && alpha_mask != 0 && !saw_alpha {
        for pixel in rgba.chunks_exact_mut(4) {
            pixel[3] = 255;
        }
    }

    let mut png = Vec::new();
    let encoder = PngEncoder::new(&mut png);
    encoder
        .write_image(&rgba, width as u32, height as u32, ColorType::Rgba8.into())
        .map_err(|err| err.to_string())?;
    Ok(Some(png))
}

fn dib_masks_and_pixel_offset(dib: &[u8], header_size: usize, bit_count: u16, compression: u32) -> Result<(u32, u32, u32, u32, usize), String> {
    if bit_count == 24 {
        return Ok((0, 0, 0, 0, header_size));
    }
    if compression == BI_BITFIELDS {
        if header_size == 40 {
            if dib.len() < 52 {
                return Ok((0, 0, 0, 0, header_size));
            }
            let red = read_u32(dib, 40)?;
            let green = read_u32(dib, 44)?;
            let blue = read_u32(dib, 48)?;
            let alpha = if dib.len() >= 56 { read_u32(dib, 52)? } else { 0 };
            let offset = if alpha != 0 { 56 } else { 52 };
            return Ok((red, green, blue, alpha, offset));
        }
        let red = read_u32(dib, 40).unwrap_or(0x00ff0000);
        let green = read_u32(dib, 44).unwrap_or(0x0000ff00);
        let blue = read_u32(dib, 48).unwrap_or(0x000000ff);
        let alpha = read_u32(dib, 52).unwrap_or(0xff000000);
        return Ok((red, green, blue, alpha, header_size));
    }
    Ok((0x00ff0000, 0x0000ff00, 0x000000ff, 0xff000000, header_size))
}

fn extract_masked_channel(pixel: u32, mask: u32) -> u8 {
    if mask == 0 {
        return 0;
    }
    let shift = mask.trailing_zeros();
    let bits = mask.count_ones();
    let value = (pixel & mask) >> shift;
    if bits >= 8 {
        (value >> (bits - 8)) as u8
    } else {
        ((value * 255) / ((1u32 << bits) - 1)) as u8
    }
}

fn read_u16(bytes: &[u8], offset: usize) -> Result<u16, String> {
    let value = bytes.get(offset..offset + 2).ok_or_else(|| "剪贴板图片数据不完整".to_string())?;
    Ok(u16::from_le_bytes([value[0], value[1]]))
}

fn read_u32(bytes: &[u8], offset: usize) -> Result<u32, String> {
    let value = bytes.get(offset..offset + 4).ok_or_else(|| "剪贴板图片数据不完整".to_string())?;
    Ok(u32::from_le_bytes([value[0], value[1], value[2], value[3]]))
}

fn read_i32(bytes: &[u8], offset: usize) -> Result<i32, String> {
    let value = bytes.get(offset..offset + 4).ok_or_else(|| "剪贴板图片数据不完整".to_string())?;
    Ok(i32::from_le_bytes([value[0], value[1], value[2], value[3]]))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn converts_24_bit_dib_to_png() {
        let mut dib = Vec::new();
        dib.extend_from_slice(&40u32.to_le_bytes());
        dib.extend_from_slice(&1i32.to_le_bytes());
        dib.extend_from_slice(&1i32.to_le_bytes());
        dib.extend_from_slice(&1u16.to_le_bytes());
        dib.extend_from_slice(&24u16.to_le_bytes());
        dib.extend_from_slice(&BI_RGB.to_le_bytes());
        dib.extend_from_slice(&4u32.to_le_bytes());
        dib.extend_from_slice(&[0u8; 16]);
        dib.extend_from_slice(&[30, 20, 10, 0]);

        let png = dib_to_png_bytes(&dib).unwrap().unwrap();
        let image = image::load_from_memory(&png).unwrap().to_rgba8();

        assert_eq!(image.width(), 1);
        assert_eq!(image.height(), 1);
        assert_eq!(image.get_pixel(0, 0).0, [10, 20, 30, 255]);
    }
}
