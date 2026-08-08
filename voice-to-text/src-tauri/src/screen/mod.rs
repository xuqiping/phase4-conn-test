//! Screen capture module (网课录屏总结, plan Step 3 / FR-101).
//!
//! Wraps `windows-capture` (Windows Graphics Capture, WGC) to capture frames
//! of a user-selected window, stamping every frame with a wall-clock timestamp
//! (ms since session t0) so it can later be aligned with audio + transcript
//! on the same time axis.
//!
//! Security: only the window explicitly chosen by the user is captured —
//! never the full screen / other windows (plan Step 3 安全检查).
//!
//! 运维 (O1): all log lines carry the `[screen]` prefix + hwnd + title so a
//! capture session can be traced; fps is logged periodically for monitoring.

mod capture;
pub mod decode;
pub mod encode;
pub mod scene_detect;

pub use capture::{CaptureStatus, RecordConfig, RegionRect, ScreenCapture, WindowInfo};
