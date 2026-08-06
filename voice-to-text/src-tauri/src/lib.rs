mod audio;
mod screen;
mod session;
mod stt;

use crate::audio::{AudioCapture, AudioCaptureConfig};
use crate::screen::{CaptureStatus, ScreenCapture, WindowInfo};
use crate::session::{SessionInfo, SessionManager, SessionState};
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
        clock: None, // Step 4 (start_capture_session) will inject the session clock
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

// ---- Session management (网课录屏总结, plan Step 1 / FR-103) ----

#[tauri::command]
fn create_session(manager: tauri::State<'_, SessionManager>) -> Result<SessionInfo, String> {
    manager.create_session().map_err(|e| e.to_string())
}

#[tauri::command]
fn list_sessions(manager: tauri::State<'_, SessionManager>) -> Result<Vec<SessionInfo>, String> {
    manager.list_sessions().map_err(|e| e.to_string())
}

#[tauri::command]
fn get_session_status(
    id: String,
    manager: tauri::State<'_, SessionManager>,
) -> Result<SessionState, String> {
    manager.get_session_status(&id).map_err(|e| e.to_string())
}

// ---- Screen capture (网课录屏总结, plan Step 3 / FR-101) ----

type ScreenState = Arc<Mutex<Option<ScreenCapture>>>;

/// List capturable windows for the picker UI (FR-101).
#[tauri::command]
fn list_windows() -> Result<Vec<WindowInfo>, String> {
    ScreenCapture::list_windows()
}

/// Start capturing the selected window. Plain Step 3 usage passes no session
/// clock (capture-local t0); Step 4's start_capture_session injects the
/// session clock so all three pipelines share one time axis.
#[tauri::command]
fn start_window_capture(
    state: tauri::State<'_, ScreenState>,
    hwnd: isize,
) -> Result<(), String> {
    let mut guard = state.lock().unwrap();
    if guard.is_some() {
        return Err("window capture already started".to_string());
    }
    *guard = Some(ScreenCapture::start(hwnd, None)?);
    Ok(())
}

#[tauri::command]
fn stop_window_capture(state: tauri::State<'_, ScreenState>) -> Result<(), String> {
    let mut guard = state.lock().unwrap();
    if let Some(mut cap) = guard.take() {
        cap.stop()?;
    }
    Ok(())
}

/// fps / frame count / stalled (minimized) status for the recording UI.
#[tauri::command]
fn get_capture_status(state: tauri::State<'_, ScreenState>) -> CaptureStatus {
    let mut guard = state.lock().unwrap();
    match guard.as_mut() {
        Some(cap) => cap.status(),
        None => CaptureStatus {
            running: false,
            frames_captured: 0,
            last_frame_ts: 0,
            stalled: false,
        },
    }
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_log::Builder::default().build())
        .manage::<AppState>(Arc::new(Mutex::new(None)))
        .manage::<ScreenState>(Arc::new(Mutex::new(None)))
        .manage(SessionManager::new(SessionManager::default_base_dir()))
        .invoke_handler(tauri::generate_handler![
            list_audio_devices,
            start_recording,
            stop_recording,
            get_recording_status,
            create_session,
            list_sessions,
            get_session_status,
            list_windows,
            start_window_capture,
            stop_window_capture,
            get_capture_status
        ])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
