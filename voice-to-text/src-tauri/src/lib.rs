mod audio;
mod screen;
mod session;
mod stt;

use crate::audio::{AudioCapture, AudioCaptureConfig};
use crate::screen::scene_detect::ExtractConfig;
use crate::screen::{encode::SLICE_MS, CaptureStatus, RecordConfig, ScreenCapture, WindowInfo};
use crate::session::{SessionClock, SessionInfo, SessionManager, SessionState};
use crate::stt::{SpeechRecognizer, SpeechRecognizerConfig};
use serde::Serialize;
use std::path::PathBuf;
use std::sync::{Arc, Mutex};
use tauri::Emitter;

struct RecordingState {
    capture: AudioCapture,
    save_audio: bool,
}

type AppState = Arc<Mutex<Option<RecordingState>>>;

/// Resolve the sherpa-onnx model directory:
/// 1. next to the executable (production / distribution)
/// 2. ../models (dev mode, cwd is src-tauri)
/// 3. ./models (fallback)
fn resolve_model_dir() -> Result<String, String> {
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
    Ok(models_dir
        .join("sherpa-onnx-streaming-zipformer-small-bilingual-zh-en-2023-02-16")
        .to_str()
        .unwrap()
        .to_string()
        // canonicalize produces \\?\ UNC prefix which sherpa-onnx C API cannot handle
        .replace("\\\\?\\", ""))
}

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

    let model_dir = resolve_model_dir()?;
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

// ---- Capture session: 三路录制集成 (plan Step 4 / FR-101/102/103) ----
//
// 一条命令同时拉起：屏幕捕获（视频硬编切片 + SAD 预筛）、音频采集、实时转写。
// 三路共用同一个 SessionClock（t0），落盘到同一 session 目录：
//   video/video_NNN.mp4 + manifest.jsonl   frames/change_*.jpg + changes.jsonl
//   audio.wav                              transcript.jsonl
// 运维 O1: 日志 traceId = session_id；O4: 单轨失败降级不拖垮其他轨。
// 安全检查: session_id 经 SessionManager 路径穿越校验；hwnd 由 ScreenCapture
// 校验为有效窗口；「即将录制屏幕」的前端提示在 Step 11（UI）落地。

struct CaptureSession {
    screen: ScreenCapture,
    audio: AudioCapture,
    session_id: String,
    session_dir: PathBuf,
}

type CaptureSessionState = Arc<Mutex<Option<CaptureSession>>>;

/// transcript.jsonl 一行：仅 final 句落盘（partial 只推前端，不落盘防抖）。
#[derive(Serialize)]
struct TranscriptLine {
    start_ms: i64,
    end_ms: i64,
    text: String,
}

#[tauri::command]
async fn start_capture_session(
    state: tauri::State<'_, CaptureSessionState>,
    manager: tauri::State<'_, SessionManager>,
    session_id: String,
    hwnd: isize,
    audio_device: Option<String>,
    app_handle: tauri::AppHandle,
) -> Result<(), String> {
    let mut guard = state.lock().unwrap();
    if guard.is_some() {
        return Err("capture session already started".to_string());
    }

    // 安全 + 状态校验：session 必须存在且不在录制中。
    let dir = manager.session_dir(&session_id).map_err(|e| e.to_string())?;
    if manager.get_session_status(&session_id).map_err(|e| e.to_string())?
        == SessionState::Recording
    {
        return Err(format!("session {session_id} is already recording"));
    }

    // 三路共用的墙钟 t0。
    let clock = Arc::new(SessionClock::new());

    // 音轨 + 转写轨 (FR-102)：clock 注入 → capture_ts / 句级时间戳全在 session 时间轴。
    let mut audio = AudioCapture::new(AudioCaptureConfig {
        sample_rate: 16000,
        channels: 1,
        clock: Some(clock.clone()),
    });
    match audio_device {
        Some(name) => audio.select_device_by_name(&name).map_err(|e| e.to_string())?,
        None => audio.select_default_device().map_err(|e| e.to_string())?,
    }
    let receiver = audio
        .get_receiver()
        .ok_or("audio receiver unavailable")?;

    let recognizer = SpeechRecognizer::new(SpeechRecognizerConfig {
        model_dir: resolve_model_dir()?,
        sample_rate: 16000,
    })
    .map_err(|e| format!("Failed to load speech recognizer: {e}"))?;

    // 转写回调：实时 emit 前端（复用现有 transcription 事件）+ final 句追加 jsonl。
    let transcript = Arc::new(Mutex::new(
        std::fs::OpenOptions::new()
            .create(true)
            .append(true)
            .open(dir.join("transcript.jsonl"))
            .map_err(|e| format!("open transcript.jsonl failed: {e}"))?,
    ));
    let trace = session_id.clone();
    recognizer.process_stream(receiver, move |result| {
        if result.is_final && !result.text.is_empty() {
            match serde_json::to_string(&TranscriptLine {
                start_ms: result.start_ms,
                end_ms: result.end_ms,
                text: result.text.clone(),
            }) {
                Ok(line) => {
                    use std::io::Write;
                    if let Err(e) = writeln!(transcript.lock().unwrap(), "{line}") {
                        log::error!("[session][{trace}] transcript append failed: {e}");
                    }
                }
                Err(e) => log::error!("[session][{trace}] transcript serialize failed: {e}"),
            }
        }
        let _ = app_handle.emit("transcription", &result);
    });
    audio.start().map_err(|e| e.to_string())?;

    // 屏幕轨 (FR-101/103)：视频切片 + SAD 预筛缩略图。失败时已起的音轨要收口。
    let screen = match ScreenCapture::start_with_record(
        hwnd,
        Some(clock),
        Some(RecordConfig {
            video_dir: dir.join("video"),
            frames_dir: Some(dir.join("frames")),
            slice_ms: SLICE_MS,
            trace: session_id.clone(),
        }),
    ) {
        Ok(s) => s,
        Err(e) => {
            audio.stop();
            return Err(e);
        }
    };

    manager
        .set_state(&session_id, SessionState::Recording)
        .map_err(|e| e.to_string())?;
    log::info!("[session][{session_id}] capture session started (screen+audio+stt on one t0)");
    *guard = Some(CaptureSession {
        screen,
        audio,
        session_id,
        session_dir: dir,
    });
    Ok(())
}

#[tauri::command]
fn stop_capture_session(
    state: tauri::State<'_, CaptureSessionState>,
    manager: tauri::State<'_, SessionManager>,
) -> Result<(), String> {
    let mut guard = state.lock().unwrap();
    let Some(mut sess) = guard.take() else {
        return Err("no capture session running".to_string());
    };
    let trace = sess.session_id.clone();

    // 屏幕轨：stop 会 join 捕获线程 → handler Drop finalize 最后一段 mp4 + manifest。
    sess.screen.stop()?;
    sess.audio.stop();

    // 音轨落盘 audio.wav：统一重采样到 16kHz 单声道 —— 与转写时间戳同速率，
    // 否则设备原生 48k 的裸样本按 16k 写头会慢放 3 倍、三路时间轴错位。
    let raw = sess.audio.take_samples();
    if !raw.is_empty() {
        let (rate, channels) = sess.audio.actual_format().unwrap_or((16000, 1));
        let samples = if (rate, channels) == (16000, 1) {
            raw
        } else {
            crate::stt::resample_to_mono_16khz(&raw, rate, channels)
        };
        let wav_path = sess.session_dir.join("audio.wav").to_string_lossy().replace("\\\\?\\", "");
        match sherpa_rs::write_audio_file(&wav_path, &samples, 16000) {
            Ok(_) => log::info!(
                "[session][{trace}] audio.wav written ({} samples @16k, source {}Hz/{}ch)",
                samples.len(),
                rate,
                channels
            ),
            Err(e) => log::error!("[session][{trace}] audio.wav write failed: {e}"),
        }
    }

    // 联动 L1：停止录制 → Processing（录后编排由 Step 5+ 接管）。
    manager
        .set_state(&sess.session_id, SessionState::Processing)
        .map_err(|e| e.to_string())?;
    log::info!("[session][{trace}] capture session stopped → processing");
    Ok(())
}

// ---- Post-processing (plan Step 5 / FR-104) ----

/// 录后精细抽帧：逐视频切片 500ms 采样 → SAD/直方图/pHash 判页去重 →
/// 存 page_*/thumb_* 图 + 写 frames.json。返回检出页数。
#[tauri::command]
async fn process_frames(
    session_id: String,
    manager: tauri::State<'_, SessionManager>,
) -> Result<usize, String> {
    let dir = manager.session_dir(&session_id).map_err(|e| e.to_string())?;
    let trace = session_id.clone();
    // 2h 视频解码可能跑分钟级 —— 放 blocking 线程池，不占 async runtime。
    let work_dir = dir.clone();
    let entries = tauri::async_runtime::spawn_blocking(move || {
        crate::screen::scene_detect::extract_session_frames(
            &work_dir,
            &ExtractConfig::default(),
            &trace,
        )
    })
    .await
    .map_err(|e| format!("extract task join: {e}"))??;

    let json = serde_json::to_string_pretty(&entries).map_err(|e| e.to_string())?;
    std::fs::write(dir.join("frames.json"), json).map_err(|e| e.to_string())?;
    log::info!("[session][{session_id}] process_frames → {} pages", entries.len());
    Ok(entries.len())
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_log::Builder::default().build())
        .manage::<AppState>(Arc::new(Mutex::new(None)))
        .manage::<ScreenState>(Arc::new(Mutex::new(None)))
        .manage::<CaptureSessionState>(Arc::new(Mutex::new(None)))
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
            get_capture_status,
            start_capture_session,
            stop_capture_session,
            process_frames
        ])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
