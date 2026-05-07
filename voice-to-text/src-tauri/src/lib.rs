mod audio;
mod stt;

use crate::audio::{AudioCapture, AudioCaptureConfig};
use crate::stt::{SpeechRecognizer, SpeechRecognizerConfig};
use std::sync::{Arc, Mutex};
use tauri::Emitter;

struct RecordingState {
    capture: AudioCapture,
    save_audio: bool,
}

type AppState = Arc<Mutex<Option<RecordingState>>>;

#[tauri::command]
fn list_audio_devices() -> Vec<String> {
    AudioCapture::list_devices()
}

#[tauri::command]
async fn start_recording(
    state: tauri::State<'_, AppState>,
    save_audio: bool,
    device_name: Option<String>,
    app_handle: tauri::AppHandle,
) -> Result<(), String> {
    let mut guard = state.lock().unwrap();
    if guard.is_some() {
        return Err("recording already started".to_string());
    }

    let mut capture = AudioCapture::new(AudioCaptureConfig {
        sample_rate: 16000,
        channels: 1,
    });

    if let Some(name) = device_name {
        capture.select_device_by_name(&name).map_err(|e| e.to_string())?;
    } else {
        capture.select_default_device().map_err(|e| e.to_string())?;
    }

    let receiver = capture.get_receiver().unwrap();

    // Resolve model dir:
    // 1. First check next to the executable (production / distribution)
    // 2. Then check ../models (dev mode, cwd is src-tauri)
    // 3. Then check ./models (fallback)
    let models_dir = std::env::current_exe()
        .ok()
        .and_then(|p| p.parent().map(|d| d.join("models")))
        .filter(|p| p.exists())
        .or_else(|| {
            std::env::current_dir()
                .ok()
                .and_then(|cwd| {
                    let parent = cwd.join("..").join("models");
                    if parent.exists() { Some(parent) } else { None }
                })
        })
        .or_else(|| {
            std::env::current_dir()
                .ok()
                .map(|cwd| cwd.join("models"))
                .filter(|p| p.exists())
        })
        .ok_or("cannot find models directory")?;
    let model_dir = models_dir
        .join("sherpa-onnx-streaming-zipformer-small-bilingual-zh-en-2023-02-16")
        .to_str()
        .unwrap()
        .to_string()
        // canonicalize produces \\?\ UNC prefix which sherpa-onnx C API cannot handle
        .replace("\\\\?\\", "");

    log::info!("Using model dir: {}", model_dir);

    let recognizer = SpeechRecognizer::new(SpeechRecognizerConfig {
        model_dir,
        sample_rate: 16000,
    }).map_err(|e| format!("Failed to load speech recognizer: {}", e))?;

    let app_handle_clone = app_handle.clone();
    recognizer.process_stream(receiver, move |result| {
        let _ = app_handle_clone.emit("transcription", result);
    });

    capture.start().map_err(|e| e.to_string())?;
    *guard = Some(RecordingState { capture, save_audio });
    Ok(())
}

#[tauri::command]
fn stop_recording(state: tauri::State<'_, AppState>) -> Result<(), String> {
    let mut guard = state.lock().unwrap();
    if let Some(mut rec_state) = guard.take() {
        rec_state.capture.stop();

        if rec_state.save_audio {
            let samples = rec_state.capture.take_samples();
            if !samples.is_empty() {
                let filename = format!("recording_{}.wav", chrono::Local::now().format("%Y%m%d_%H%M%S"));
                let path = std::env::current_dir().unwrap().join(&filename);
                match sherpa_rs::write_audio_file(path.to_str().unwrap(), &samples, 16000) {
                    Ok(_) => log::info!("Audio saved to: {}", path.display()),
                    Err(e) => log::error!("Failed to save audio: {}", e),
                }
            }
        }
    }
    Ok(())
}

#[tauri::command]
fn get_recording_status(state: tauri::State<'_, AppState>) -> bool {
    let guard = state.lock().unwrap();
    guard.is_some()
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_log::Builder::default().build())
        .manage::<AppState>(Arc::new(Mutex::new(None)))
        .invoke_handler(tauri::generate_handler![
            list_audio_devices,
            start_recording,
            stop_recording,
            get_recording_status
        ])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
