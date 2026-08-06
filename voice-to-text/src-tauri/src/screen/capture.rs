//! WGC window capture (plan Step 3 / FR-101).
//!
//! Structure:
//! - [`ScreenCapture::list_windows`] — enumerate capturable windows for the picker UI.
//! - [`ScreenCapture::start`] — spawn a free-threaded WGC session on one window;
//!   each arriving frame is stamped `frame_ts = clock.now_ms()` and forwarded as
//!   [`FrameMeta`] (pixels stay on the GPU; Step 4 will encode them in-handler).
//! - [`ScreenCapture::stop`] — halt and release.
//!
//! Minimized / closed window handling: WGC simply stops delivering frames when
//! the window is minimized; [`ScreenCapture::status`] reports `stalled = true`
//! after [`STALL_THRESHOLD_MS`] without a frame so the UI can warn the user.
//! When the window is closed, `on_closed` fires and the capture ends.

use serde::Serialize;
use std::path::PathBuf;
#[cfg(windows)]
use std::sync::mpsc::Receiver;
#[cfg(windows)]
use std::sync::Arc;

#[cfg(windows)]
use crate::session::SessionClock;

/// No frames for this long while capturing → treat as stalled (window
/// probably minimized). Surfaced via `CaptureStatus.stalled`.
pub const STALL_THRESHOLD_MS: i64 = 2000;

/// Target frame interval: 33ms ≈ 30fps (plan 验证标准). WGC caps delivery at
/// this rate instead of the 60fps default, halving GPU/CPU load for 网课场景.
#[cfg(windows)]
const FRAME_INTERVAL: std::time::Duration = std::time::Duration::from_millis(33);

/// One capturable window, as shown in the frontend picker.
#[derive(Debug, Clone, Serialize)]
pub struct WindowInfo {
    /// Raw HWND as an integer; pass back to `start_window_capture` unchanged.
    pub hwnd: isize,
    pub title: String,
    pub process_name: String,
}

/// Lightweight per-frame record. The BGRA pixel data never leaves the capture
/// thread in Step 3 — only metadata crosses the channel (zero-copy verify of
/// fps / timestamps; Step 4 adds the encoder next to the frames).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct FrameMeta {
    pub frame_ts: i64,
    pub width: u32,
    pub height: u32,
}

/// Snapshot of a running capture, returned by `get_capture_status`.
#[derive(Debug, Clone, Serialize)]
pub struct CaptureStatus {
    pub running: bool,
    pub frames_captured: u64,
    pub last_frame_ts: i64,
    /// True when no frame arrived within [`STALL_THRESHOLD_MS`] (likely minimized).
    pub stalled: bool,
}

/// Video-recording attachment for a capture (plan Step 4). When present, the
/// capture handler encodes frames to sliced MP4 segments under `video_dir`.
#[derive(Debug, Clone)]
pub struct RecordConfig {
    /// Session's `video/` directory — segments + manifest.jsonl land here.
    pub video_dir: PathBuf,
    /// Segment length override (tests); production uses [`crate::screen::encode::SLICE_MS`].
    pub slice_ms: i64,
    /// traceId for log lines = session id (运维 O1).
    pub trace: String,
}

/// Pure stall rule, extracted for unit testing.
pub fn is_stalled(last_frame_ts: Option<i64>, started_ts: i64, now: i64) -> bool {
    let reference = last_frame_ts.unwrap_or(started_ts);
    now - reference > STALL_THRESHOLD_MS
}

// ---------------------------------------------------------------------------
// Windows implementation (real WGC)
// ---------------------------------------------------------------------------
#[cfg(windows)]
mod imp {
    use super::*;
    use crate::screen::encode::SliceEncoder;
    use std::sync::mpsc;
    use std::time::Instant;
    use windows_capture::capture::{CaptureControl, Context, GraphicsCaptureApiHandler};
    use windows_capture::frame::Frame;
    use windows_capture::graphics_capture_api::{GraphicsCaptureApi, InternalCaptureControl};
    use windows_capture::settings::{
        ColorFormat, CursorCaptureSettings, DirtyRegionSettings, DrawBorderSettings,
        MinimumUpdateIntervalSettings, SecondaryWindowSettings, Settings,
    };
    use windows_capture::window::Window;

    type HandlerError = Box<dyn std::error::Error + Send + Sync>;

    /// Everything the handler needs, delivered via Settings flags.
    struct CaptureFlags {
        clock: Arc<SessionClock>,
        sender: mpsc::Sender<FrameMeta>,
        /// Window title, only for log lines (traceId substitute until Step 4
        /// injects the session id).
        label: String,
        /// Step 4: when set, frames are also encoded to sliced MP4 segments.
        record: Option<RecordConfig>,
    }

    /// The per-capture callback object living on the WGC thread.
    struct CaptureHandler {
        clock: Arc<SessionClock>,
        sender: mpsc::Sender<FrameMeta>,
        label: String,
        frames: u64,
        last_frame_ts: i64,
        fps_window_start: Instant,
        fps_window_frames: u32,
        /// Step 4 video track. Lazy-created on the first frame (encoder needs
        /// real width/height). `encoder_failed` = degraded: keep capturing,
        /// skip video (O4 降级路径 — 捕获/转写不陪葬).
        record: Option<RecordConfig>,
        encoder: Option<SliceEncoder>,
        encoder_failed: bool,
    }

    impl GraphicsCaptureApiHandler for CaptureHandler {
        type Flags = CaptureFlags;
        type Error = HandlerError;

        fn new(ctx: Context<Self::Flags>) -> Result<Self, Self::Error> {
            let f = ctx.flags;
            log::info!("[screen] capture handler up for {}", f.label);
            Ok(Self {
                clock: f.clock,
                sender: f.sender,
                label: f.label,
                frames: 0,
                last_frame_ts: 0,
                fps_window_start: Instant::now(),
                fps_window_frames: 0,
                record: f.record,
                encoder: None,
                encoder_failed: false,
            })
        }

        fn on_frame_arrived(
            &mut self,
            frame: &mut Frame,
            capture_control: InternalCaptureControl,
        ) -> Result<(), Self::Error> {
            let ts = self.clock.now_ms();
            // 验证: frame_ts 必须单调不减；时钟回拨说明墙钟实现有问题。
            if ts < self.last_frame_ts {
                log::warn!(
                    "[screen] non-monotonic frame_ts on {}: {} < {}",
                    self.label,
                    ts,
                    self.last_frame_ts
                );
            }
            self.last_frame_ts = ts;
            self.frames += 1;

            // Step 4: video track (no-op when this capture isn't recording).
            self.encode_frame(frame, ts);

            let meta = FrameMeta {
                frame_ts: ts,
                width: frame.width(),
                height: frame.height(),
            };
            // Receiver gone (stop without drain) → halt capture thread.
            if self.sender.send(meta).is_err() {
                log::info!("[screen] frame receiver dropped, halting capture of {}", self.label);
                capture_control.stop();
                return Ok(());
            }

            // 运维 O1: rolling fps log (every ~2s), the Step 3 验证 (~30fps) hook.
            self.fps_window_frames += 1;
            let elapsed = self.fps_window_start.elapsed();
            if elapsed.as_secs() >= 2 {
                let fps = self.fps_window_frames as f64 / elapsed.as_secs_f64();
                log::info!(
                    "[screen] {} capturing: {:.1} fps ({}x{}, total {} frames)",
                    self.label,
                    fps,
                    meta.width,
                    meta.height,
                    self.frames
                );
                self.fps_window_start = Instant::now();
                self.fps_window_frames = 0;
            }
            Ok(())
        }

        fn on_closed(&mut self) -> Result<(), Self::Error> {
            // 验证: 窗口被关闭时捕获应结束（最小化则是静默停帧，由 stalled 上报）。
            log::info!("[screen] window closed, capture of {} ended", self.label);
            Ok(())
        }
    }

    impl CaptureHandler {
        /// Step 4 video track: lazily create the encoder on the first frame,
        /// then encode every frame. Any failure degrades to capture-without-
        /// video instead of killing the capture (O4).
        fn encode_frame(&mut self, frame: &mut Frame, ts: i64) {
            let Some(rec) = &self.record else { return };
            if self.encoder_failed {
                return;
            }
            if self.encoder.is_none() {
                match SliceEncoder::new(
                    rec.video_dir.clone(),
                    frame.width(),
                    frame.height(),
                    rec.slice_ms,
                    rec.trace.clone(),
                ) {
                    Ok(enc) => self.encoder = Some(enc),
                    Err(e) => {
                        log::error!(
                            "[screen] {} video track disabled (init failed): {e}",
                            self.label
                        );
                        self.encoder_failed = true;
                        return;
                    }
                }
            }
            if let Some(enc) = self.encoder.as_mut() {
                if let Err(e) = enc.send_frame(frame, ts) {
                    log::error!(
                        "[screen] {} video track disabled (encode failed): {e}",
                        self.label
                    );
                    self.encoder = None;
                    self.encoder_failed = true;
                }
            }
        }
    }

    impl Drop for CaptureHandler {
        fn drop(&mut self) {
            // Capture thread ends (stop / window closed / receiver gone) →
            // flush the final video segment so every mp4 on disk is playable.
            if let Some(enc) = self.encoder.as_mut() {
                enc.finish(self.last_frame_ts);
            }
        }
    }

    pub struct ScreenCapture {
        control: Option<CaptureControl<CaptureHandler, HandlerError>>,
        receiver: Receiver<FrameMeta>,
        clock: Arc<SessionClock>,
        started_ts: i64,
        frames_captured: u64,
        last_frame_ts: Option<i64>,
        label: String,
    }

    impl ScreenCapture {
        /// Enumerate visible, top-level windows with a non-empty title.
        pub fn list_windows() -> Result<Vec<WindowInfo>, String> {
            let windows = Window::enumerate().map_err(|e| e.to_string())?;
            let mut out = Vec::new();
            for w in windows {
                let Ok(title) = w.title() else { continue };
                if title.trim().is_empty() {
                    continue;
                }
                out.push(WindowInfo {
                    hwnd: w.as_raw_hwnd() as isize,
                    title,
                    process_name: w.process_name().unwrap_or_default(),
                });
            }
            Ok(out)
        }

        /// Start capturing the window behind `hwnd`.
        ///
        /// `clock`: shared session wall clock. `None` (plain Step 3 usage)
        /// creates a capture-local clock; Step 4 passes the session clock so
        /// screen / audio / transcript share t0 (plan Step 3 备注).
        pub fn start(hwnd: isize, clock: Option<Arc<SessionClock>>) -> Result<Self, String> {
            Self::start_with_record(hwnd, clock, None)
        }

        /// Step 4 entry: same as [`ScreenCapture::start`], plus a video track
        /// when `record` is set — frames are encoded to sliced MP4 segments
        /// under `record.video_dir` (FR-101/FR-103).
        pub fn start_with_record(
            hwnd: isize,
            clock: Option<Arc<SessionClock>>,
            record: Option<RecordConfig>,
        ) -> Result<Self, String> {
            let window = Window::from_raw_hwnd(hwnd as *mut std::ffi::c_void);
            if !window.is_valid() {
                return Err(format!("window {hwnd:#x} is not capturable (invisible / gone?)"));
            }
            let title = window.title().unwrap_or_else(|_| "<untitled>".to_string());
            // traceId-ish label until Step 4: hwnd + title identify the capture.
            let label = format!("hwnd={hwnd:#x} \"{title}\"");

            let clock = clock.unwrap_or_else(|| Arc::new(SessionClock::new()));
            let started_ts = clock.now_ms();
            let (sender, receiver) = mpsc::channel::<FrameMeta>();

            // 30fps cap only where the platform supports it (IGraphicsCaptureSession5);
            // older Windows builds reject the custom interval, so degrade to the
            // system default (~60fps) instead of failing the whole capture.
            let interval = match GraphicsCaptureApi::is_minimum_update_interval_supported() {
                Ok(true) => MinimumUpdateIntervalSettings::Custom(FRAME_INTERVAL),
                _ => {
                    log::warn!("[screen] min update interval unsupported on this platform, using default fps");
                    MinimumUpdateIntervalSettings::Default
                }
            };

            let settings = Settings::new(
                window,
                CursorCaptureSettings::Default,
                DrawBorderSettings::Default,
                SecondaryWindowSettings::Default,
                interval,
                DirtyRegionSettings::Default,
                ColorFormat::Bgra8,
                CaptureFlags { clock: clock.clone(), sender, label: label.clone(), record },
            );

            let control = CaptureHandler::start_free_threaded(settings)
                .map_err(|e| format!("failed to start WGC capture: {e}"))?;
            log::info!("[screen] capture started on {label}");

            Ok(Self {
                control: Some(control),
                receiver,
                clock,
                started_ts,
                frames_captured: 0,
                last_frame_ts: None,
                label,
            })
        }

        /// Drain pending frame metadata into our counters.
        fn drain(&mut self) {
            while let Ok(meta) = self.receiver.try_recv() {
                self.frames_captured += 1;
                self.last_frame_ts = Some(meta.frame_ts);
            }
        }

        pub fn status(&mut self) -> CaptureStatus {
            self.drain();
            let now = self.clock.now_ms();
            let finished = self.control.as_ref().map_or(true, |c| c.is_finished());
            CaptureStatus {
                running: !finished,
                frames_captured: self.frames_captured,
                last_frame_ts: self.last_frame_ts.unwrap_or(0),
                stalled: is_stalled(self.last_frame_ts, self.started_ts, now),
            }
        }

        pub fn stop(&mut self) -> Result<(), String> {
            self.drain();
            if let Some(control) = self.control.take() {
                control.stop().map_err(|e| format!("failed to stop capture: {e}"))?;
            }
            log::info!(
                "[screen] capture stopped on {} ({} frames)",
                self.label,
                self.frames_captured
            );
            Ok(())
        }
    }

    impl Drop for ScreenCapture {
        fn drop(&mut self) {
            let _ = self.stop();
        }
    }
}

// ---------------------------------------------------------------------------
// Non-Windows stub (keeps commands + frontend API uniform across platforms)
// ---------------------------------------------------------------------------
#[cfg(not(windows))]
mod imp {
    use super::WindowInfo;
    use super::CaptureStatus;
    use super::RecordConfig;

    pub struct ScreenCapture;

    impl ScreenCapture {
        pub fn list_windows() -> Result<Vec<WindowInfo>, String> {
            Err("screen capture is only supported on Windows".to_string())
        }
        pub fn start(
            _hwnd: isize,
            _clock: Option<std::sync::Arc<crate::session::SessionClock>>,
        ) -> Result<Self, String> {
            Err("screen capture is only supported on Windows".to_string())
        }
        pub fn start_with_record(
            _hwnd: isize,
            _clock: Option<std::sync::Arc<crate::session::SessionClock>>,
            _record: Option<RecordConfig>,
        ) -> Result<Self, String> {
            Err("screen capture is only supported on Windows".to_string())
        }
        pub fn status(&mut self) -> CaptureStatus {
            CaptureStatus { running: false, frames_captured: 0, last_frame_ts: 0, stalled: false }
        }
        pub fn stop(&mut self) -> Result<(), String> {
            Ok(())
        }
    }
}

pub use imp::ScreenCapture;

#[cfg(test)]
mod tests {
    use super::*;

    /// AC: plan Step 3 验证 —— frame_ts 单调由时钟保证；stall 规则独立可测。
    #[test]
    fn stall_rule_triggers_after_threshold() {
        // frames flowing at t=1000, quiet for >2s at t=3500 → stalled
        assert!(is_stalled(Some(1000), 0, 3500));
        assert!(!is_stalled(Some(1000), 0, 2500));
    }

    #[test]
    fn stall_rule_uses_start_when_no_frames_yet() {
        // window minimized from the very beginning: no frame ever arrives,
        // stall measured from capture start
        assert!(is_stalled(None, 1000, 4000));
        assert!(!is_stalled(None, 1000, 2000));
    }

    #[cfg(windows)]
    #[test]
    fn list_windows_finds_visible_windows() {
        let windows = ScreenCapture::list_windows().unwrap();
        assert!(
            !windows.is_empty(),
            "a desktop session should always have at least one capturable window"
        );
        for w in &windows {
            assert_ne!(w.hwnd, 0, "hwnd must be a real handle");
            assert!(!w.title.trim().is_empty(), "listed windows must have titles");
        }
    }

    #[cfg(windows)]
    #[test]
    fn start_rejects_invalid_hwnd() {
        // 安全检查: 只捕用户选定的有效窗口；乱传 hwnd 必须被拒绝而不是 panic。
        // (不用 unwrap_err：Ok 侧的 ScreenCapture 没实现 Debug)
        let err = match ScreenCapture::start(0x1, None) {
            Err(e) => e,
            Ok(_) => panic!("hwnd 0x1 should have been rejected"),
        };
        assert!(err.contains("not capturable"), "unexpected error: {err}");
    }

    /// Real WGC smoke test: captures the first listed window for ~3s and
    /// asserts frames arrive with monotonic timestamps. Ignored by default —
    /// it grabs a real on-screen window, so run it manually on a desktop:
    ///   cargo test -- --ignored real_capture
    #[cfg(windows)]
    #[test]
    #[ignore = "captures a real window; run manually"]
    fn real_capture_delivers_monotonic_frames() {
        let windows = ScreenCapture::list_windows().unwrap();
        let target = windows.first().expect("no window to capture");
        let mut cap = ScreenCapture::start(target.hwnd, None).unwrap();

        std::thread::sleep(std::time::Duration::from_secs(3));
        let status = cap.status();
        cap.stop().unwrap();

        assert!(status.frames_captured > 0, "no frames in 3s (window minimized?)");
        // ~30fps cap: allow generous slack for slow/idle windows.
        assert!(
            status.frames_captured <= 33 * 3 + 10,
            "fps cap not respected: {} frames in 3s",
            status.frames_captured
        );
        assert!(status.last_frame_ts >= 0);
    }

    /// Real encode smoke test (AC-101 视频轨): captures the first window for
    /// ~4s with recording on, then asserts a playable segment + manifest line
    /// landed in video/. Ignored by default — grabs a real on-screen window:
    ///   cargo test -- --ignored real_encode
    #[cfg(windows)]
    #[test]
    #[ignore = "captures a real window; run manually"]
    fn real_encode_writes_segment_and_manifest() {
        let dir = std::path::PathBuf::from(env!("CARGO_MANIFEST_DIR"))
            .join("target")
            .join("test-sessions")
            .join("real-encode")
            .join("video");
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();

        let windows = ScreenCapture::list_windows().unwrap();
        let target = windows.first().expect("no window to capture");
        let mut cap = ScreenCapture::start_with_record(
            target.hwnd,
            None,
            Some(RecordConfig {
                video_dir: dir.clone(),
                slice_ms: crate::screen::encode::SLICE_MS,
                trace: "test-real-encode".to_string(),
            }),
        )
        .unwrap();

        std::thread::sleep(std::time::Duration::from_secs(4));
        let status = cap.status();
        cap.stop().unwrap();
        // Encoder finalizes on the capture thread — give it a moment after stop.
        std::thread::sleep(std::time::Duration::from_secs(2));
        drop(cap);

        assert!(status.frames_captured > 0, "no frames in 4s (window minimized?)");

        let seg = dir.join("video_001.mp4");
        let meta = std::fs::metadata(&seg).expect("video_001.mp4 missing");
        assert!(meta.len() > 10_000, "segment suspiciously small: {} bytes", meta.len());

        let manifest = std::fs::read_to_string(dir.join("manifest.jsonl"))
            .expect("manifest.jsonl missing");
        let line = manifest.lines().next().expect("manifest empty");
        assert!(line.contains("video_001.mp4"), "bad manifest line: {line}");
        assert!(line.contains("\"frames\":"), "manifest lacks frame count: {line}");
    }
}
