pub trait OcrEngine: Send + Sync {
    fn recognize(&self, image_path: &str) -> Result<String, String>;
}

pub struct DisabledOcrEngine;

impl OcrEngine for DisabledOcrEngine {
    fn recognize(&self, _image_path: &str) -> Result<String, String> {
        Ok(String::new())
    }
}

pub fn recognize_image(image_path: &str) -> Result<String, String> {
    #[cfg(target_os = "windows")]
    {
      windows_ocr(image_path)
    }
    #[cfg(not(target_os = "windows"))]
    {
        let _ = image_path;
        Ok(String::new())
    }
}

pub fn recognize_with_windows_system(image_path: &str) -> Result<String, String> {
    #[cfg(target_os = "windows")]
    {
        windows_ocr(image_path)
    }
    #[cfg(not(target_os = "windows"))]
    {
        let _ = image_path;
        Ok(String::new())
    }
}

#[cfg(target_os = "windows")]
fn windows_ocr(image_path: &str) -> Result<String, String> {
    use windows::Graphics::Imaging::BitmapDecoder;
    use windows::Media::Ocr::OcrEngine;
    use windows::Storage::FileAccessMode;
    use windows::Storage::Streams::FileRandomAccessStream;
    use windows::core::{HSTRING, Interface};

    let stream = FileRandomAccessStream::OpenAsync(&HSTRING::from(image_path), FileAccessMode::Read)
        .map_err(|err| err.to_string())?
        .get()
        .map_err(|err| err.to_string())?;
    let decoder = BitmapDecoder::CreateAsync(&stream)
        .map_err(|err| err.to_string())?
        .get()
        .map_err(|err| err.to_string())?;
    let frame = decoder.cast::<windows::Graphics::Imaging::IBitmapFrameWithSoftwareBitmap>()
        .map_err(|err| err.to_string())?;
    let bitmap = frame.GetSoftwareBitmapAsync()
        .map_err(|err| err.to_string())?
        .get()
        .map_err(|err| err.to_string())?;
    let engine = OcrEngine::TryCreateFromUserProfileLanguages().map_err(|err| err.to_string())?;
    let result = engine.RecognizeAsync(&bitmap)
        .map_err(|err| err.to_string())?
        .get()
        .map_err(|err| err.to_string())?;
    Ok(result.Text().map_err(|err| err.to_string())?.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn disabled_ocr_returns_empty_text() {
        let engine = DisabledOcrEngine;
        assert_eq!(engine.recognize("image.png").unwrap(), "");
    }
}
