//! Scene-change prefilter for 网课录屏总结 (plan Step 4 轻量入口 / FR-101).
//!
//! 录制时只做轻量活（design §3.5：重活全放录后）：每 [`SAMPLE_EVERY`] 帧抽一帧，
//! 降采样成 64×36 灰度栅格，与上一采样帧算 SAD（像素差绝对值均值）；超过阈值
//! 判为"翻页/场景变化"，存一张小缩略图 + 时间戳到 `frames/`。**不阻塞录制**：
//! 采样率 2Hz，缩略图仅 320px 宽，完整精细抽帧（直方图+pHash）在 Step 5。
//!
//! 纯逻辑部分（[`downsample_gray`] / [`downsample_bgra`] / [`sad_mean`] /
//! [`SceneDetector`]）与平台无关，单测覆盖；Windows 部分 [`SceneTap`] 住在
//! 捕获 handler 里，失败只降级自身（log + 停用），不影响录制三路。

use serde::Serialize;
use std::path::PathBuf;

/// Sample 1 in N frames. At the 30fps cap this is ~2Hz — enough to catch PPT
/// page flips, cheap enough to never disturb encoding.
pub const SAMPLE_EVERY: u32 = 15;

/// SAD mean threshold (0-255 gray scale) above which a frame pair is a scene
/// change. PPT 翻页通常 >> 20；讲师摄像头小窗口微动一般 < 5。
/// O2: 后续做成可配置项（抽帧阈值开关）。
pub const SAD_THRESHOLD: f64 = 12.0;

/// Coarse grid the SAD runs on — tiny enough to be free, big enough to see
/// a slide change.
pub const GRID_W: usize = 64;
pub const GRID_H: usize = 36;

/// Thumbnail width for change frames (height follows aspect).
pub const THUMB_W: usize = 320;

/// Nearest-neighbor downsample of a BGRA8 buffer to a gray grid
/// (`out_w * out_h` bytes, luma-ish: simple average of B,G,R).
/// Returns an empty vec on malformed input (caller logs and skips).
pub fn downsample_gray(bgra: &[u8], w: usize, h: usize, out_w: usize, out_h: usize) -> Vec<u8> {
    if w == 0 || h == 0 || bgra.len() < w * h * 4 {
        return Vec::new();
    }
    let mut out = Vec::with_capacity(out_w * out_h);
    for gy in 0..out_h {
        let sy = gy * h / out_h;
        for gx in 0..out_w {
            let sx = gx * w / out_w;
            let i = (sy * w + sx) * 4;
            out.push((bgra[i] as u16 / 3 + bgra[i + 1] as u16 / 3 + bgra[i + 2] as u16 / 3) as u8);
        }
    }
    out
}

/// Nearest-neighbor downsample keeping BGRA (for thumbnails).
pub fn downsample_bgra(bgra: &[u8], w: usize, h: usize, out_w: usize, out_h: usize) -> Vec<u8> {
    if w == 0 || h == 0 || bgra.len() < w * h * 4 {
        return Vec::new();
    }
    let mut out = Vec::with_capacity(out_w * out_h * 4);
    for gy in 0..out_h {
        let sy = gy * h / out_h;
        for gx in 0..out_w {
            let sx = gx * w / out_w;
            let i = (sy * w + sx) * 4;
            out.extend_from_slice(&bgra[i..i + 4]);
        }
    }
    out
}

/// Mean absolute difference of two equal-length gray grids (0.0 = identical).
pub fn sad_mean(a: &[u8], b: &[u8]) -> f64 {
    if a.is_empty() || a.len() != b.len() {
        return 0.0;
    }
    let sum: u64 = a
        .iter()
        .zip(b.iter())
        .map(|(&x, &y)| x.abs_diff(y) as u64)
        .sum();
    sum as f64 / a.len() as f64
}

/// One detected change, appended as a JSON line to `frames/changes.jsonl`.
/// `ts` is on the session wall clock — Step 5 精细抽帧按它定位视频切片。
#[derive(Debug, Clone, Serialize)]
pub struct SceneChange {
    pub ts: i64,
    pub file: String,
}

/// Stateful change detector: remembers the previous sampled grid.
pub struct SceneDetector {
    threshold: f64,
    last: Option<Vec<u8>>,
}

impl SceneDetector {
    pub fn new(threshold: f64) -> Self {
        Self {
            threshold,
            last: None,
        }
    }

    /// Feed one sampled gray grid. The first sample only primes the detector
    /// (a recording start is not a "change"). Returns true on scene change.
    pub fn push(&mut self, gray: Vec<u8>) -> bool {
        let changed = match &self.last {
            None => false,
            Some(prev) => sad_mean(prev, &gray) > self.threshold,
        };
        self.last = Some(gray);
        changed
    }
}

// ---------------------------------------------------------------------------
// Windows implementation (taps real capture frames)
// ---------------------------------------------------------------------------
#[cfg(windows)]
mod imp {
    use super::*;
    use std::fs::{File, OpenOptions};
    use std::io::Write;
    use windows_capture::encoder::{ImageEncoder, ImageEncoderPixelFormat, ImageFormat};
    use windows_capture::frame::Frame;

    /// Lives in the capture handler; samples frames and persists change
    /// thumbnails. All I/O failures degrade to "tap disabled" (O4) — the video/
    /// audio/transcript tracks must never die because a thumbnail failed.
    pub struct SceneTap {
        frames_dir: PathBuf,
        changes_log: File,
        detector: SceneDetector,
        sample_every: u32,
        counter: u32,
        scratch: Vec<u8>,
        disabled: bool,
        trace: String,
    }

    impl SceneTap {
        pub fn new(frames_dir: PathBuf, sample_every: u32, trace: String) -> Result<Self, String> {
            let changes_log = OpenOptions::new()
                .create(true)
                .append(true)
                .open(frames_dir.join("changes.jsonl"))
                .map_err(|e| format!("open changes.jsonl failed: {e}"))?;
            Ok(Self {
                frames_dir,
                changes_log,
                detector: SceneDetector::new(SAD_THRESHOLD),
                sample_every: sample_every.max(1),
                counter: 0,
                scratch: Vec::new(),
                disabled: false,
                trace,
            })
        }

        /// Called for every captured frame; internally rate-limits to
        /// 1-in-N. Errors are logged once and the tap disables itself.
        pub fn maybe_sample(&mut self, frame: &mut Frame, ts: i64) {
            if self.disabled {
                return;
            }
            self.counter += 1;
            if self.counter % self.sample_every != 0 {
                return;
            }
            if let Err(e) = self.sample(frame, ts) {
                log::error!("[screen][{}] scene tap disabled: {e}", self.trace);
                self.disabled = true;
            }
        }

        fn sample(&mut self, frame: &mut Frame, ts: i64) -> Result<(), String> {
            let (w, h) = (frame.width() as usize, frame.height() as usize);
            let buffer = frame.buffer().map_err(|e| format!("frame buffer: {e}"))?;
            let pixels = buffer.as_nopadding_buffer(&mut self.scratch);

            let gray = downsample_gray(pixels, w, h, GRID_W, GRID_H);
            if gray.is_empty() {
                return Err(format!("unexpected buffer size {} for {}x{}", pixels.len(), w, h));
            }
            if !self.detector.push(gray) {
                return Ok(());
            }

            // Scene change → persist a small thumbnail + manifest line.
            let thumb_h = (THUMB_W * h / w).max(1);
            let thumb = downsample_bgra(pixels, w, h, THUMB_W, thumb_h);
            let file_name = format!("change_{ts}.jpg");
            let jpg = ImageEncoder::new(ImageFormat::Jpeg, ImageEncoderPixelFormat::Bgra8)
                .and_then(|enc| enc.encode(&thumb, THUMB_W as u32, thumb_h as u32))
                .map_err(|e| format!("thumb encode: {e}"))?;
            std::fs::write(self.frames_dir.join(&file_name), jpg)
                .map_err(|e| format!("thumb write: {e}"))?;

            let line = serde_json::to_string(&SceneChange {
                ts,
                file: file_name.clone(),
            })
            .map_err(|e| e.to_string())?;
            writeln!(self.changes_log, "{line}").map_err(|e| e.to_string())?;
            log::info!("[screen][{}] scene change at {}ms → {}", self.trace, ts, file_name);
            Ok(())
        }
    }
}

#[cfg(windows)]
pub use imp::SceneTap;

#[cfg(test)]
mod tests {
    use super::*;

    fn solid_bgra(w: usize, h: usize, px: [u8; 4]) -> Vec<u8> {
        let mut v = Vec::with_capacity(w * h * 4);
        for _ in 0..w * h {
            v.extend_from_slice(&px);
        }
        v
    }

    #[test]
    fn downsample_gray_uniform_frame() {
        // 全白帧 → 栅格全 255；全黑 → 全 0
        let white = solid_bgra(128, 72, [255, 255, 255, 255]);
        let g = downsample_gray(&white, 128, 72, GRID_W, GRID_H);
        assert_eq!(g.len(), GRID_W * GRID_H);
        assert!(g.iter().all(|&v| v == 255));

        let black = solid_bgra(128, 72, [0, 0, 0, 255]);
        let g = downsample_gray(&black, 128, 72, GRID_W, GRID_H);
        assert!(g.iter().all(|&v| v == 0));
    }

    #[test]
    fn downsample_rejects_malformed_buffer() {
        assert!(downsample_gray(&[0; 10], 128, 72, GRID_W, GRID_H).is_empty());
        assert!(downsample_bgra(&[0; 10], 128, 72, 32, 18).is_empty());
    }

    #[test]
    fn sad_mean_zero_for_identical() {
        let a = vec![7u8; 100];
        assert_eq!(sad_mean(&a, &a), 0.0);
        assert_eq!(sad_mean(&a, &[1, 2]), 0.0, "length mismatch → 0");
    }

    /// AC-101 联动：静态网课画面不应触发变化（避免录后海量无效帧）。
    #[test]
    fn detector_ignores_static_and_noisy_frames() {
        let mut d = SceneDetector::new(SAD_THRESHOLD);
        let base = vec![100u8; GRID_W * GRID_H];
        assert!(!d.push(base.clone()), "first sample primes only");
        assert!(!d.push(base), "identical → no change");

        // 讲师小窗口微动：5% 像素变化 ±20 → SAD 均值 ~1，远低于阈值
        let mut noisy = vec![100u8; GRID_W * GRID_H];
        let touched = noisy.len() / 20;
        for v in noisy.iter_mut().take(touched) {
            *v = 120;
        }
        assert!(!d.push(noisy), "small local motion → no change");
    }

    #[test]
    fn detector_fires_on_page_flip() {
        let mut d = SceneDetector::new(SAD_THRESHOLD);
        d.push(vec![30u8; GRID_W * GRID_H]);
        // PPT 翻页：整帧亮度大改 → SAD 均值 170 >> 阈值
        assert!(d.push(vec![200u8; GRID_W * GRID_H]), "page flip must fire");
    }
}
