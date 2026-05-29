pub trait OcrEngine: Send + Sync {
    fn recognize(&self, image_path: &str) -> Result<String, String>;
}

pub struct DisabledOcrEngine;

impl OcrEngine for DisabledOcrEngine {
    fn recognize(&self, _image_path: &str) -> Result<String, String> {
        Ok(String::new())
    }
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
