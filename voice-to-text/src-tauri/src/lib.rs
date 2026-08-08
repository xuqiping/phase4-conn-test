mod align;
mod audio;
mod feature_flags;
mod ocr;
mod screen;
mod session;
mod stt;
mod summary;

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

// ---- Feature flag (plan Step 12 / 运维开关) ----

/// 前端启动时拉取，决定「网课总结」Tab 显隐。每次调用实时读配置，
/// 运维改完 feature_flags.json / 环境变量后重启应用即生效。
#[tauri::command]
fn get_feature_flags(app: tauri::AppHandle) -> feature_flags::FeatureFlags {
    match summary_config_dir(&app) {
        Ok(dir) => feature_flags::effective_flags(&dir),
        Err(_) => feature_flags::env_only_flags(),
    }
}

/// 录制入口的服务端兜底校验：即使前端被绕过，关停状态下也拒绝开新会话。
fn ensure_course_summary_enabled(app: &tauri::AppHandle) -> Result<(), String> {
    let flags = match summary_config_dir(app) {
        Ok(dir) => feature_flags::effective_flags(&dir),
        Err(_) => feature_flags::env_only_flags(),
    };
    if flags.course_summary {
        Ok(())
    } else {
        log::warn!("[feature] start_capture_session 被拒绝：course_summary 开关已关停");
        Err("网课总结功能已被运维开关关闭（feature flag: course_summary=off）".into())
    }
}


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

    // Step 12 运维开关：功能关停时拒绝开新录制（服务端兜底，不依赖前端隐藏）。
    ensure_course_summary_enabled(&app_handle)?;

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

// ---- Post-processing (plan Step 6 / FR-105) ----

/// 课件 OCR：对 frames.json 中 ocr_text 为 null 的页跑本地 OCR（PP-OCRv5 mobile），
/// 回填写回 frames.json。单帧失败降级 null 不阻塞（O4）。
/// 模型首用自动下载（ModelScope → $OAR_HOME）；exe 旁 models/ocr/ 存在则优先本地。
/// 返回 (ok, failed) 供前端提示。
#[tauri::command]
async fn process_ocr(
    session_id: String,
    manager: tauri::State<'_, SessionManager>,
) -> Result<(usize, usize), String> {
    let dir = manager.session_dir(&session_id).map_err(|e| e.to_string())?;
    let trace = session_id.clone();
    // 模型加载秒级 + 全课推理分钟级 —— blocking 线程池，不占 async runtime。
    let stats = tauri::async_runtime::spawn_blocking(move || {
        crate::ocr::process_session_ocr(&dir, &crate::ocr::OcrConfig::default(), &trace)
    })
    .await
    .map_err(|e| format!("ocr task join: {e}"))??;
    log::info!(
        "[session][{session_id}] process_ocr → ok={} failed={}",
        stats.ok, stats.failed
    );
    Ok((stats.ok, stats.failed))
}

// ---- Post-processing (plan Step 7 / FR-106) ----

/// 音字帧对齐：转写句 × 课件帧按时间区间归并 → aligned.json（Step 8 总结的输入）。
/// 降级：无 frames → 5min 固定窗；无转写 → 「无讲解文字」（texts 空）。
/// 注意：要 ocr_text 进 aligned.json 需先跑 process_ocr 再跑本命令（读当下快照）。
#[tauri::command]
async fn align_session(
    session_id: String,
    manager: tauri::State<'_, SessionManager>,
) -> Result<usize, String> {
    let dir = manager.session_dir(&session_id).map_err(|e| e.to_string())?;
    let trace = session_id.clone();
    let n = tauri::async_runtime::spawn_blocking(move || crate::align::align_session(&dir, &trace))
        .await
        .map_err(|e| format!("align task join: {e}"))??;
    log::info!("[session][{session_id}] align_session → {n} units");
    Ok(n)
}

// ---- Cloud summary (plan Step 8 / FR-107/109) ----
//
// 隐私红线（AGENTS.md「例外·网课总结功能」已 review）：仅上传文字（多模态开关开启时
// 才附课件帧图）；API Key 只走 Windows 凭据管理器；配置（非密）落 %APPDATA% 配置目录。

use crate::summary::{SummaryConfig, SummaryDraft};

fn summary_config_dir(app: &tauri::AppHandle) -> Result<PathBuf, String> {
    use tauri::Manager;
    app.path()
        .app_config_dir()
        .map_err(|e| format!("resolve config dir: {e}"))
}

#[tauri::command]
fn get_summary_config(app: tauri::AppHandle) -> SummaryConfig {
    match summary_config_dir(&app) {
        Ok(dir) => summary::load_config(&dir),
        Err(_) => SummaryConfig::default(),
    }
}

#[tauri::command]
fn set_summary_config(app: tauri::AppHandle, config: SummaryConfig) -> Result<(), String> {
    let dir = summary_config_dir(&app)?;
    summary::save_config(&dir, &config)
}

/// 保存 API Key 到 Windows 凭据管理器（不落盘、不进日志）。
#[tauri::command]
fn set_summary_api_key(key: String) -> Result<(), String> {
    let trimmed = key.trim();
    if trimmed.is_empty() {
        return Err("API Key 不能为空".into());
    }
    summary::set_api_key(trimmed)
}

/// 前端回显用：只告知「是否已设置」，永不返回 Key 本体。
#[tauri::command]
fn has_summary_api_key() -> Result<bool, String> {
    summary::get_api_key().map(|k| k.is_some())
}

#[tauri::command]
fn clear_summary_api_key() -> Result<(), String> {
    summary::clear_api_key()
}

/// 连通性自检：发一条最小请求验证 Base URL + Key + 模型名可用。
#[tauri::command]
async fn test_summary_connection(app: tauri::AppHandle) -> Result<String, String> {
    let dir = summary_config_dir(&app)?;
    let cfg = summary::load_config(&dir);
    let key = summary::get_api_key()?.ok_or("尚未设置 API Key")?;
    tauri::async_runtime::spawn_blocking(move || {
        let messages = vec![summary::cloud_api::ChatMessage::user(
            "回复 ok 两个字母即可。".into(),
        )];
        summary::cloud_api::chat_blocking(&cfg, &key, &cfg.model, &messages)
            .map(|_| format!("连接成功（{}）", cfg.model))
    })
    .await
    .map_err(|e| format!("test task join: {e}"))?
}

/// 生成/全量重生成总结草稿（map-reduce）。LLM 失败自动本地兜底（fallback=true）。
#[tauri::command]
async fn summarize(
    session_id: String,
    vlm_on: Option<bool>,
    app: tauri::AppHandle,
    manager: tauri::State<'_, SessionManager>,
) -> Result<SummaryDraft, String> {
    let dir = manager.session_dir(&session_id).map_err(|e| e.to_string())?;
    let cfg_dir = summary_config_dir(&app)?;
    let cfg = summary::load_config(&cfg_dir);
    let key = summary::get_api_key()?.ok_or("尚未设置 API Key —— 请先在总结设置里填写")?;
    let trace = session_id.clone();
    // 串行 map + 段间间隔，2h 课可能分钟级 —— blocking 线程池。
    tauri::async_runtime::spawn_blocking(move || {
        summary::map_reduce::run_summary(&dir, &cfg, &key, vlm_on.unwrap_or(false), &trace)
    })
    .await
    .map_err(|e| format!("summarize task join: {e}"))?
}

/// 可纠错：segment_id = Some 只重生成该段，None 全量重生成。
#[tauri::command]
async fn regenerate_summary(
    session_id: String,
    segment_id: Option<usize>,
    vlm_on: Option<bool>,
    app: tauri::AppHandle,
    manager: tauri::State<'_, SessionManager>,
) -> Result<SummaryDraft, String> {
    let dir = manager.session_dir(&session_id).map_err(|e| e.to_string())?;
    let cfg_dir = summary_config_dir(&app)?;
    let cfg = summary::load_config(&cfg_dir);
    let key = summary::get_api_key()?.ok_or("尚未设置 API Key —— 请先在总结设置里填写")?;
    let trace = session_id.clone();
    tauri::async_runtime::spawn_blocking(move || {
        summary::map_reduce::regenerate(&dir, &cfg, &key, segment_id, vlm_on.unwrap_or(false), &trace)
    })
    .await
    .map_err(|e| format!("regenerate task join: {e}"))?
}

// ---- Render & export (plan Step 9 / FR-108) ----
//
// 纯本地读草稿 + 写 exports/，无网络请求，不需要 spawn_blocking。

/// 时间轴章节结构（章节 → 要点[时间戳] + 课件帧引用），供前端 Study.vue 消费。
#[tauri::command]
fn get_timeline(
    session_id: String,
    manager: tauri::State<'_, SessionManager>,
) -> Result<summary::render::Timeline, String> {
    let dir = manager.session_dir(&session_id).map_err(|e| e.to_string())?;
    summary::render::build_timeline(&dir)
}

/// 导出 Markdown 到 session_dir/exports/summary.md，返回文件完整路径。
#[tauri::command]
fn export_markdown(
    session_id: String,
    manager: tauri::State<'_, SessionManager>,
) -> Result<String, String> {
    let dir = manager.session_dir(&session_id).map_err(|e| e.to_string())?;
    summary::render::export_markdown(&dir, &session_id)
        .map(|p| p.to_string_lossy().replace("\\\\?\\", ""))
}

// ---- Study commands (plan Step 11 / FR-108/109) ----
//
// 帧图与视频走 asset:// 协议（run() 里动态放行 sessions 根目录），不走 base64 IPC；
// 这里只暴露元数据（切片清单 / OCR 原文）与草稿编辑。

/// 视频切片清单（供 Study.vue 点播跳转）：切片时长 + 有序文件名。
#[derive(Serialize)]
struct VideoSlices {
    slice_ms: i64,
    files: Vec<String>,
    /// audio.wav 是否存在 —— 视频分轨落盘无音轨（FR-103），
    /// 学习区靠它在播放切片时同步播放音轨（2026-08-08 Phase4 手测缺陷修复）。
    has_audio: bool,
}

#[tauri::command]
fn get_video_slices(
    session_id: String,
    manager: tauri::State<'_, SessionManager>,
) -> Result<VideoSlices, String> {
    let dir = manager.session_dir(&session_id).map_err(|e| e.to_string())?;
    let mut files: Vec<String> = Vec::new();
    let video_dir = dir.join("video");
    if video_dir.is_dir() {
        for entry in std::fs::read_dir(&video_dir).map_err(|e| e.to_string())? {
            let path = entry.map_err(|e| e.to_string())?.path();
            if path.extension().and_then(|e| e.to_str()) == Some("mp4") {
                if let Some(name) = path.file_name().and_then(|n| n.to_str()) {
                    files.push(name.to_string());
                }
            }
        }
        files.sort();
    }
    Ok(VideoSlices {
        slice_ms: SLICE_MS,
        files,
        has_audio: dir.join("audio.wav").is_file(),
    })
}

/// OCR 原文核对（Study.vue 展开用）：按 frame_ref 查 frames.json 的 ocr_text。
/// frame_ref 形如 "frames/page_123.jpg"；找不到条目返回 Ok(None)，不算错误。
#[tauri::command]
fn get_ocr_text(
    session_id: String,
    frame_ref: String,
    manager: tauri::State<'_, SessionManager>,
) -> Result<Option<String>, String> {
    let dir = manager.session_dir(&session_id).map_err(|e| e.to_string())?;
    // 路径穿越校验：frame_ref 必须是 frames/ 下的纯文件名引用。
    if frame_ref.contains("..") || frame_ref.starts_with('/') || frame_ref.contains(':') {
        return Err("非法的帧引用路径".into());
    }
    let raw = std::fs::read_to_string(dir.join("frames.json"))
        .map_err(|e| format!("read frames.json: {e}"))?;
    let entries: Vec<crate::screen::scene_detect::FrameEntry> =
        serde_json::from_str(&raw).map_err(|e| format!("parse frames.json: {e}"))?;
    Ok(entries
        .into_iter()
        .find(|e| e.orig_path == frame_ref || e.thumb_path == frame_ref)
        .and_then(|e| e.ocr_text))
}

/// 要点局部编辑（SummaryPanel）：原地改文本，不压历史版本。
#[tauri::command]
fn update_summary_point(
    session_id: String,
    segment_id: usize,
    point_index: usize,
    text: String,
    manager: tauri::State<'_, SessionManager>,
) -> Result<(), String> {
    let dir = manager.session_dir(&session_id).map_err(|e| e.to_string())?;
    summary::update_point_text(&dir, segment_id, point_index, &text)
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_log::Builder::default().build())
        .setup(|app| {
            // Step 11: 帧图/视频切片走 asset:// 协议（convertFileSrc）。
            // 安全边界：只放行 sessions 根目录（递归），其他路径 webview 无权读。
            use tauri::Manager;
            let base = SessionManager::default_base_dir();
            std::fs::create_dir_all(&base).map_err(|e| format!("create sessions dir: {e}"))?;
            app.asset_protocol_scope()
                .allow_directory(&base, true)
                .map_err(|e| format!("allow asset scope: {e}"))?;
            Ok(())
        })
        .manage::<AppState>(Arc::new(Mutex::new(None)))
        .manage::<ScreenState>(Arc::new(Mutex::new(None)))
        .manage::<CaptureSessionState>(Arc::new(Mutex::new(None)))
        .manage(SessionManager::new(SessionManager::default_base_dir()))
        .invoke_handler(tauri::generate_handler![
            list_audio_devices,
            start_recording,
            stop_recording,
            get_recording_status,
            get_feature_flags,
            create_session,
            list_sessions,
            get_session_status,
            list_windows,
            start_window_capture,
            stop_window_capture,
            get_capture_status,
            start_capture_session,
            stop_capture_session,
            process_frames,
            process_ocr,
            align_session,
            get_summary_config,
            set_summary_config,
            set_summary_api_key,
            has_summary_api_key,
            clear_summary_api_key,
            test_summary_connection,
            summarize,
            regenerate_summary,
            get_timeline,
            export_markdown,
            get_video_slices,
            get_ocr_text,
            update_summary_point
        ])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
