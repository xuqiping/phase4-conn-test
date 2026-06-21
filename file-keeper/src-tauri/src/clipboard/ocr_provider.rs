use serde::{Deserialize, Serialize};
use std::io::Write;
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
#[cfg(test)]
use std::sync::{Mutex, OnceLock};
use std::time::Instant;

#[derive(Debug, Clone, Default, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct OcrResult {
    pub text: String,
    pub engine: String,
    pub elapsed_ms: u128,
    #[serde(default)]
    pub blocks: Vec<OcrBlock>,
}

#[derive(Debug, Clone, Default, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct OcrBlock {
    pub text: String,
    pub confidence: f32,
    pub box_points: Vec<[f32; 2]>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct OcrProviderStatus {
    pub provider: String,
    pub available: bool,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct SidecarRequest<'a> {
    image_path: &'a str,
    language: &'a str,
}

#[cfg(test)]
type TestRecognizer = Box<dyn Fn(&str) -> Result<OcrResult, String> + Send + Sync>;

#[cfg(test)]
static TEST_RECOGNIZER: OnceLock<Mutex<Option<TestRecognizer>>> = OnceLock::new();

#[cfg(test)]
pub fn set_recognizer_for_testing(
    recognizer: impl Fn(&str) -> Result<OcrResult, String> + Send + Sync + 'static,
) {
    *TEST_RECOGNIZER
        .get_or_init(|| Mutex::new(None))
        .lock()
        .unwrap() = Some(Box::new(recognizer));
}

#[cfg(test)]
pub fn clear_recognizer_for_testing() {
    *TEST_RECOGNIZER
        .get_or_init(|| Mutex::new(None))
        .lock()
        .unwrap() = None;
}

pub fn recognize_image(image_path: &str) -> Result<OcrResult, String> {
    #[cfg(test)]
    if let Some(recognizer) = TEST_RECOGNIZER
        .get_or_init(|| Mutex::new(None))
        .lock()
        .unwrap()
        .as_ref()
    {
        return recognizer(image_path);
    }

    let provider = select_provider(install_dir().ok());
    match provider.provider.as_str() {
        "local_sidecar" => recognize_with_sidecar(image_path),
        "windows_system" => recognize_with_windows(image_path),
        _ => Ok(OcrResult::default()),
    }
}

pub fn provider_status() -> Result<OcrProviderStatus, String> {
    Ok(select_provider(install_dir().ok()))
}

fn select_provider(install_dir: Option<PathBuf>) -> OcrProviderStatus {
    if let Some(root) = install_dir {
        if sidecar_path(&root).exists() {
            return OcrProviderStatus {
                provider: "local_sidecar".to_string(),
                available: true,
            };
        }
    }
    if cfg!(target_os = "windows") {
        OcrProviderStatus {
            provider: "windows_system".to_string(),
            available: true,
        }
    } else {
        OcrProviderStatus {
            provider: "disabled".to_string(),
            available: false,
        }
    }
}

fn recognize_with_sidecar(image_path: &str) -> Result<OcrResult, String> {
    let root = install_dir()?;
    let sidecar = sidecar_path(&root);
    let request = serde_json::to_vec(&SidecarRequest {
        image_path,
        language: "zh_en",
    })
    .map_err(|err| err.to_string())?;
    let started = Instant::now();
    let mut child = Command::new(sidecar)
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .map_err(|err| err.to_string())?;
    child
        .stdin
        .as_mut()
        .ok_or_else(|| "OCR sidecar stdin unavailable".to_string())?
        .write_all(&request)
        .map_err(|err| err.to_string())?;
    let output = child.wait_with_output().map_err(|err| err.to_string())?;
    if !output.status.success() {
        return Err(String::from_utf8_lossy(&output.stderr).to_string());
    }
    let mut result: OcrResult =
        serde_json::from_slice(&output.stdout).map_err(|err| err.to_string())?;
    if result.elapsed_ms == 0 {
        result.elapsed_ms = started.elapsed().as_millis();
    }
    if result.engine.is_empty() {
        result.engine = "rapidocr-onnx".to_string();
    }
    Ok(result)
}

fn recognize_with_windows(image_path: &str) -> Result<OcrResult, String> {
    let started = Instant::now();
    let text = crate::clipboard::ocr::recognize_with_windows_system(image_path)?;
    Ok(OcrResult {
        text,
        engine: "windows_system".to_string(),
        elapsed_ms: started.elapsed().as_millis(),
        blocks: Vec::new(),
    })
}

fn install_dir() -> Result<PathBuf, String> {
    let exe = std::env::current_exe().map_err(|err| err.to_string())?;
    exe.parent()
        .map(Path::to_path_buf)
        .ok_or_else(|| "应用安装目录不可用".to_string())
}

fn sidecar_path(install_dir: &Path) -> PathBuf {
    install_dir.join("ocr").join(executable_name())
}

fn executable_name() -> &'static str {
    if cfg!(target_os = "windows") {
        "file-keeper-ocr.exe"
    } else {
        "file-keeper-ocr"
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn provider_prefers_local_sidecar_when_present() {
        let root =
            std::env::temp_dir().join(format!("file-keeper-ocr-provider-{}", uuid::Uuid::new_v4()));
        let sidecar = root.join("ocr").join(executable_name());
        std::fs::create_dir_all(sidecar.parent().unwrap()).unwrap();
        std::fs::write(&sidecar, "stub").unwrap();

        let provider = select_provider(Some(root.clone()));

        assert_eq!(provider.provider, "local_sidecar");
        assert!(provider.available);
        let _ = std::fs::remove_dir_all(root);
    }

    #[test]
    fn provider_falls_back_when_sidecar_missing() {
        let root = PathBuf::from("C:/definitely/missing/file-keeper");
        let provider = select_provider(Some(root));

        if cfg!(target_os = "windows") {
            assert_eq!(provider.provider, "windows_system");
        } else {
            assert_eq!(provider.provider, "disabled");
        }
    }
}
