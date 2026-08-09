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

/// 4×4 分块的最大块内 SAD（输入都是 32×32 判页栅格）。局部内容变化的判别器：
/// 整窗录制时翻页只改课件区那几块，整帧均值被静态区域摊薄，块级最大值不会。
pub fn max_block_sad(a: &[u8], b: &[u8]) -> f64 {
    if a.len() != PAGE_GRID * PAGE_GRID || b.len() != PAGE_GRID * PAGE_GRID {
        return 0.0;
    }
    const B: usize = PAGE_GRID / PAGE_BLOCKS; // 8
    let mut max = 0f64;
    for by in 0..PAGE_BLOCKS {
        for bx in 0..PAGE_BLOCKS {
            let mut sum = 0u64;
            for cy in 0..B {
                for cx in 0..B {
                    let i = (by * B + cy) * PAGE_GRID + bx * B + cx;
                    sum += a[i].abs_diff(b[i]) as u64;
                }
            }
            max = max.max(sum as f64 / (B * B) as f64);
        }
    }
    max
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
            let (w, h) = (frame.width() as usize, frame.height() as usize);
            // scratch take 成局部：pixels 借它时 self 仍可整借给 sample_pixels_inner。
            let mut scratch = std::mem::take(&mut self.scratch);
            let result = match frame.buffer() {
                Ok(buf) => {
                    let pixels = buf.as_nopadding_buffer(&mut scratch);
                    self.sample_pixels_inner(pixels, w, h, ts)
                }
                Err(e) => Err(format!("frame buffer: {e}")),
            };
            self.scratch = scratch;
            if let Err(e) = result {
                log::error!("[screen][{}] scene tap disabled: {e}", self.trace);
                self.disabled = true;
            }
        }

        /// 区域框选模式（2026-08-08）：裁剪后是 CPU 像素，无 GPU Frame 可传，
        /// 与 maybe_sample 同一入口限速/降级策略。
        pub fn maybe_sample_pixels(&mut self, pixels: &[u8], w: usize, h: usize, ts: i64) {
            if self.disabled {
                return;
            }
            self.counter += 1;
            if self.counter % self.sample_every != 0 {
                return;
            }
            if let Err(e) = self.sample_pixels_inner(pixels, w, h, ts) {
                log::error!("[screen][{}] scene tap disabled: {e}", self.trace);
                self.disabled = true;
            }
        }

        fn sample_pixels_inner(
            &mut self,
            pixels: &[u8],
            w: usize,
            h: usize,
            ts: i64,
        ) -> Result<(), String> {
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

// ---------------------------------------------------------------------------
// Step 5 精细判页去重 (FR-104)：直方图 BHATTACHARYYA 判页 + pHash 去重 + 去抖。
// 纯 Rust（design §3.5：无 ffmpeg 依赖）；全部在 32×32 灰度栅格上算，与
// 录制时 2Hz 预筛互补：预筛只存变化帧缩略图，本流水线录后从视频切片重新
// 采样 500ms/帧精判，产出课件页序列 frames.json。
// ---------------------------------------------------------------------------

/// 录后采样间隔（design：抽帧召回 >90% 靠 500ms 粒度；比录制的 2Hz 预筛细）。
pub const SAMPLE_STEP_MS: i64 = 500;
/// 去抖窗：2–3s 内多次变化合并为一次翻页（design §3.5，白板书写/动画场景）。
pub const DEBOUNCE_MS: i64 = 2500;
/// 直方图巴氏距离阈值：> 此值才进入 pHash 判页（灰度分布明显不同才算候选新页）。
pub const HIST_DISTANCE_THRESHOLD: f64 = 0.35;
/// pHash 汉明距离 > 8 视为新页（父 plan 默认值；O2 做成可配置）。
pub const PHASH_HAMMING_NEW_PAGE: u32 = 8;
/// 判页栅格边长（32×32 = 1024 采样点：SAD/直方图/pHash 共用）。
pub const PAGE_GRID: usize = 32;
/// 判页分块数（32×32 栅格 → 4×4 块，每块 8×8 cell）。
pub const PAGE_BLOCKS: usize = 4;
/// 块级 SAD 新页阈值：整窗录制时翻页只发生在局部区域，整帧 SAD/直方图/pHash
/// 全被静态区域（浏览器框/章节侧栏）摊薄 —— 实机锚点 20260809_104544_114：
/// 同页+老师红笔标注块级 SAD ≤ 7.7，同模板真翻页（文字页→拓扑图页）≥ 13.4，
/// 而 pHash 汉明仅 4-6（≤8 旧阈值漏判）。取 10 坐中间。
pub const BLOCK_SAD_NEW_PAGE: f64 = 10.0;

/// O2 可配置阈值包（当前命令用 Default；后续接设置界面）。
#[derive(Debug, Clone)]
pub struct ExtractConfig {
    pub sample_step_ms: i64,
    pub debounce_ms: i64,
    pub sad_threshold: f64,
    pub hist_threshold: f64,
    pub hamming_new_page: u32,
    pub block_sad_new_page: f64,
}

impl Default for ExtractConfig {
    fn default() -> Self {
        Self {
            sample_step_ms: SAMPLE_STEP_MS,
            debounce_ms: DEBOUNCE_MS,
            sad_threshold: SAD_THRESHOLD,
            hist_threshold: HIST_DISTANCE_THRESHOLD,
            hamming_new_page: PHASH_HAMMING_NEW_PAGE,
            block_sad_new_page: BLOCK_SAD_NEW_PAGE,
        }
    }
}

/// 32-bin 灰度直方图。
pub fn gray_histogram(gray: &[u8]) -> [u32; 32] {
    let mut hist = [0u32; 32];
    for &v in gray {
        hist[(v >> 3) as usize] += 1;
    }
    hist
}

/// 巴氏距离 0.0（完全相同）~ 1.0（完全不同）。
pub fn bhattacharyya_distance(a: &[u32; 32], b: &[u32; 32]) -> f64 {
    let (sa, sb) = (a.iter().sum::<u32>() as f64, b.iter().sum::<u32>() as f64);
    if sa == 0.0 || sb == 0.0 {
        return 1.0;
    }
    let bc: f64 = a
        .iter()
        .zip(b.iter())
        .map(|(&x, &y)| ((x as f64 / sa) * (y as f64 / sb)).sqrt())
        .sum();
    (1.0 - bc).max(0.0).sqrt()
}

/// 2D DCT-II（分离式：先行后列），输入输出 32×32 行主序。
fn dct_2d_32(input: &[f64; 1024]) -> [f64; 1024] {
    use std::f64::consts::PI;
    // cos 表：cos[(2i+1) k π / 64]
    let mut cos_t = [[0f64; 32]; 32];
    for k in 0..32 {
        for i in 0..32 {
            cos_t[k][i] = (((2 * i + 1) as f64 * k as f64 * PI) / 64.0).cos();
        }
    }
    let c = |k: usize| if k == 0 { 1.0 / 2.0f64.sqrt() } else { 1.0 };
    // 行变换：tmp[x][v] = Σ_y in[x][y]·cos_t[v][y]
    let mut tmp = [0f64; 1024];
    for x in 0..32 {
        for v in 0..32 {
            let mut s = 0.0;
            for y in 0..32 {
                s += input[x * 32 + y] * cos_t[v][y];
            }
            tmp[x * 32 + v] = s;
        }
    }
    // 列变换：out[u][v] = ¼·c(u)c(v)·Σ_x tmp[x][v]·cos_t[u][x]
    let mut out = [0f64; 1024];
    for u in 0..32 {
        for v in 0..32 {
            let mut s = 0.0;
            for x in 0..32 {
                s += tmp[x * 32 + v] * cos_t[u][x];
            }
            out[u * 32 + v] = 0.25 * c(u) * c(v) * s;
        }
    }
    out
}

/// 感知哈希：32×32 灰度 → DCT → 取左上 8×8 低频（去 DC）→ 超**中位数**置 1。
/// （用中位数而非均值：稀疏能量图（如半屏纯色）下均值被少数强系数拉偏，
/// 大量零系数全落到阈值同一侧 → 哈希区分度崩掉；中位数对偏斜稳。）
pub fn phash64(gray_32x32: &[u8]) -> u64 {
    if gray_32x32.len() != 1024 {
        return 0;
    }
    let mut input = [0f64; 1024];
    for (i, &v) in gray_32x32.iter().enumerate() {
        input[i] = v as f64;
    }
    let dct = dct_2d_32(&input);
    // 8×8 低频块，跳过 DC(0,0)，63 个系数与中位数比较
    let mut coeffs = [0f64; 64];
    for u in 0..8 {
        for v in 0..8 {
            coeffs[u * 8 + v] = dct[u * 32 + v];
        }
    }
    let mut sorted = coeffs[1..].to_vec();
    sorted.sort_by(|a, b| a.partial_cmp(b).unwrap_or(std::cmp::Ordering::Equal));
    let median = sorted[sorted.len() / 2];
    let mut hash = 0u64;
    for (i, &c) in coeffs.iter().enumerate() {
        if c > median {
            hash |= 1 << i;
        }
    }
    hash
}

/// 汉明距离。
pub fn hamming(a: u64, b: u64) -> u32 {
    (a ^ b).count_ones()
}

/// 检出的一个课件页（相对 session 时间轴）。
#[derive(Debug, Clone)]
pub struct DetectedPage {
    pub ts_ms: i64,
    pub phash: u64,
}

/// 一页的判页参照（栅格 + 直方图 + pHash）。判"是否不同页"用三路信号 OR：
/// 直方图/pHash 管整帧大改，块级 SAD 管局部翻页（整窗录制被静态区域摊薄的场景）。
#[derive(Debug, Clone)]
struct PageRef {
    grid: Vec<u8>,
    hist: [u32; 32],
    phash: u64,
}

/// 判页流水线状态机：SAD 预筛 → 去抖 → **三路判（OR）** → 去重。
/// 三路语义：直方图距离大 → 明显新页；pHash 距离大 → 同亮度分布的新内容页；
/// 块级 SAD 大 → 局部区域翻页（整窗录制课件区占比小，整帧信号被摊薄，
/// 实机锚点 20260809_104544_114：同模板翻页 hamming 仅 4-6 但块级 SAD ≥13.4）
/// —— 任一成立即候选新页，最后再过 seen 去重（翻回旧页不收）。
///
/// 去抖语义（design「2–3s 内多次变化合并为一次翻页」）：变化先挂 pending，
/// 窗内再变 → 更新 pending（画面还在动）；**静置满窗**（或段末 flush）才提交
/// 判页 —— 这样"翻页动画 1s + 静置"只收 1 页，且不会把真翻页吞掉。
pub struct PageExtractor {
    cfg: ExtractConfig,
    last_grid: Option<Vec<u8>>,
    /// 未提交的变化帧（ts + 栅格）；静置满 debounce 窗后提交判页。
    pending: Option<(i64, Vec<u8>)>,
    current: Option<PageRef>,
    seen: Vec<PageRef>,
    pages: Vec<DetectedPage>,
}

impl PageExtractor {
    pub fn new(cfg: ExtractConfig) -> Self {
        Self {
            cfg,
            last_grid: None,
            pending: None,
            current: None,
            seen: Vec::new(),
            pages: Vec::new(),
        }
    }

    /// 三路判（OR）：page 参照与候选帧是否"不同页"。
    fn page_differs(&self, page: &PageRef, grid: &[u8], hist: &[u32; 32], hash: u64) -> bool {
        bhattacharyya_distance(&page.hist, hist) > self.cfg.hist_threshold
            || hamming(page.phash, hash) > self.cfg.hamming_new_page
            || max_block_sad(&page.grid, grid) > self.cfg.block_sad_new_page
    }

    /// 与当前页参照是否不同页 —— SAD 预筛漏网兜底用，与 commit 同语义。
    /// 当前页未建立（首帧前）时恒 false。
    fn differs_from_current_page(&self, grid: &[u8]) -> bool {
        self.current
            .as_ref()
            .is_some_and(|cur| self.page_differs(cur, grid, &gray_histogram(grid), phash64(grid)))
    }

    /// 三路判 + 去重，提交一个已静置的变化帧。返回 Some = 新页。
    fn commit(&mut self, ts_ms: i64, grid: &[u8]) -> Option<DetectedPage> {
        let hist = gray_histogram(grid);
        let hash = phash64(grid);
        let differs = self
            .current
            .as_ref()
            .map_or(true, |cur| self.page_differs(cur, grid, &hist, hash));
        // 与任何已收页"相同"（三路都判同）= 翻回旧页 → 只更新当前页参照，不收
        let is_dup = self
            .seen
            .iter()
            .any(|s| !self.page_differs(s, grid, &hist, hash));
        self.current = Some(PageRef {
            grid: grid.to_vec(),
            hist,
            phash: hash,
        });
        if !differs || is_dup {
            return None;
        }
        self.seen.push(self.current.clone().unwrap());
        let page = DetectedPage { ts_ms, phash: hash };
        self.pages.push(page.clone());
        Some(page)
    }

    /// 喂一个采样帧的 32×32 灰度栅格；判为新页时返回 Some。
    /// 首帧无条件成为第 1 页（录制开始 = 第一页课件）。
    pub fn push(&mut self, ts_ms: i64, grid: &[u8]) -> Option<DetectedPage> {
        if self.last_grid.is_none() {
            self.last_grid = Some(grid.to_vec());
            return self.commit(ts_ms, grid);
        }
        let sad = sad_mean(self.last_grid.as_ref().unwrap(), grid);
        if sad <= self.cfg.sad_threshold {
            // 静态帧：pending 静置满窗 → 提交（此刻的 frame 就是安定后的画面）
            if let Some((pts, pgrid)) = self.pending.take() {
                if ts_ms - pts >= self.cfg.debounce_ms {
                    return self.commit(pts, &pgrid);
                }
                // 窗还没满。内容相对 pending 帧仍在漂移（低 SAD 缓慢变化，
                // 如嵌入视频播放）→ 重挂计时，防"每 2.5s 刷一页"的垃圾页；
                // 静置中 → 继续挂着。
                if sad_mean(&pgrid, grid) > self.cfg.sad_threshold {
                    self.pending = Some((ts_ms, grid.to_vec()));
                } else {
                    self.pending = Some((pts, pgrid));
                }
                return None;
            }
            // SAD 预筛漏网兜底（2026-08-09 实机锚点 20260809_104544_114）：
            // 整窗录制时课件区只占画面一部分 / 白底幻灯片只换文字，翻页
            // SAD 实测仅 8.24 < 12 阈值，但与当前页 pHash 汉明 12 > 8。
            // 画面静止却与当前页内容不同 = 悄悄翻到了新页 → 挂 pending
            // 走去抖（静置满窗才提交，上文的漂移重挂防视频段刷页）。
            if self.differs_from_current_page(grid) {
                self.pending = Some((ts_ms, grid.to_vec()));
            }
            return None;
        }
        // 变化帧：窗内连变 → 更新 pending；隔窗新变 → 先提交旧的再挂新的
        self.last_grid = Some(grid.to_vec());
        match self.pending.take() {
            Some((pts, pgrid)) if ts_ms - pts >= self.cfg.debounce_ms => {
                let committed = self.commit(pts, &pgrid);
                self.pending = Some((ts_ms, grid.to_vec()));
                committed
            }
            _ => {
                self.pending = Some((ts_ms, grid.to_vec()));
                None
            }
        }
    }

    /// 段末/收尾提交未决变化（否则最后一页会丢）。
    pub fn flush(&mut self) -> Option<DetectedPage> {
        let (pts, pgrid) = self.pending.take()?;
        self.commit(pts, &pgrid)
    }

    pub fn pages(&self) -> &[DetectedPage] {
        &self.pages
    }
}

/// frames.json 一行（plan Step 5 产出契约）：课件页 + 原图/缩略图引用 +
/// ocr_text 占位（Step 6 回填）。
/// Deserialize：Step 6 process_ocr 要读回 frames.json 回填 ocr_text。
#[derive(Debug, Clone, Serialize, serde::Deserialize)]
pub struct FrameEntry {
    pub frame_ts: i64,
    pub orig_path: String,
    pub thumb_path: String,
    pub ocr_text: Option<String>,
}

// ---------------------------------------------------------------------------
// Step 5 编排（Windows）：逐切片采样 → 判页 → 存图 → frames.json 条目
// ---------------------------------------------------------------------------
#[cfg(windows)]
mod pipeline {
    use super::*;
    use crate::screen::decode::{sample_video, SampledFrame};
    use crate::screen::encode::SegmentRecord;
    use std::path::Path;
    use windows_capture::encoder::{ImageEncoder, ImageEncoderPixelFormat, ImageFormat};

    /// 原图最长边（OCR 清晰度与体积平衡；缩略图沿用 THUMB_W）。
    const ORIG_W: usize = 1280;

    fn save_jpeg(
        frames_dir: &Path,
        name: &str,
        frame: &SampledFrame,
        target_w: usize,
    ) -> Result<String, String> {
        let (w, h) = (frame.width as usize, frame.height as usize);
        let tw = target_w.min(w);
        let th = (tw * h / w).max(1);
        let small = downsample_bgra(&frame.bgra, w, h, tw, th);
        let jpg = ImageEncoder::new(ImageFormat::Jpeg, ImageEncoderPixelFormat::Bgra8)
            .and_then(|enc| enc.encode(&small, tw as u32, th as u32))
            .map_err(|e| format!("jpeg encode {name}: {e}"))?;
        std::fs::write(frames_dir.join(name), jpg).map_err(|e| format!("write {name}: {e}"))?;
        Ok(name.to_string())
    }

    /// 读取 video/manifest.jsonl + 逐切片 500ms 采样判页，产出 frames.json 条目。
    /// 时间轴：片内 ts + 段 start_ms = session 墙钟 ts（AC-103 对齐）。
    /// 单图/单段失败只 log + 跳过（O4 降级：一页存图失败不拖垮整课）。
    pub fn extract_session_frames(
        session_dir: &Path,
        cfg: &ExtractConfig,
        trace: &str,
    ) -> Result<Vec<FrameEntry>, String> {
        let video_dir = session_dir.join("video");
        let frames_dir = session_dir.join("frames");
        let manifest = match std::fs::read_to_string(video_dir.join("manifest.jsonl")) {
            Ok(m) => m,
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => {
                log::warn!("[frames][{trace}] no manifest.jsonl — 未录制视频轨，抽帧为空");
                return Ok(Vec::new());
            }
            Err(e) => return Err(format!("read manifest: {e}")),
        };
        let mut segments = Vec::new();
        for (i, line) in manifest.lines().enumerate() {
            if line.trim().is_empty() {
                continue;
            }
            let rec: SegmentRecord = serde_json::from_str(line)
                .map_err(|e| format!("manifest line {}: {e}", i + 1))?;
            segments.push(rec);
        }

        let mut extractor = PageExtractor::new(cfg.clone());
        let mut entries = Vec::new();
        for seg in &segments {
            let path = video_dir.join(&seg.file);
            if !path.exists() {
                log::warn!("[frames][{trace}] segment {} missing, skipped", seg.file);
                continue;
            }
            // 段内最后一帧的像素留给 flush 提交时存图（去抖 pending 的收尾帧）。
            let mut last_frame: Option<SampledFrame> = None;
            let sampled = {
                let mut handle = |f: SampledFrame| {
                    let session_ts = seg.start_ms + f.ts_ms;
                    let grid = downsample_gray(
                        &f.bgra,
                        f.width as usize,
                        f.height as usize,
                        PAGE_GRID,
                        PAGE_GRID,
                    );
                    if extractor.push(session_ts, &grid).is_some() {
                        save_page(&frames_dir, &mut entries, session_ts, &f, trace);
                    }
                    last_frame = Some(f);
                };
                sample_video(&path, cfg.sample_step_ms, &mut handle)?
            };
            // 段末提交未决变化（pending 去抖窗未满段就结束了的情况）
            if let Some(page) = extractor.flush() {
                if let Some(f) = &last_frame {
                    save_page(&frames_dir, &mut entries, page.ts_ms, f, trace);
                }
            }
            log::info!(
                "[frames][{trace}] {} sampled {sampled} frames → {} pages so far",
                seg.file,
                extractor.pages().len()
            );
        }
        Ok(entries)
    }

    /// 存原图 + 缩略图并登记条目；存图失败只 log（O4：一页失败不拖垮整课）。
    fn save_page(
        frames_dir: &Path,
        entries: &mut Vec<FrameEntry>,
        ts_ms: i64,
        frame: &SampledFrame,
        trace: &str,
    ) {
        let orig_name = format!("page_{ts_ms}.jpg");
        let thumb_name = format!("thumb_{ts_ms}.jpg");
        match save_jpeg(frames_dir, &orig_name, frame, ORIG_W)
            .and_then(|_| save_jpeg(frames_dir, &thumb_name, frame, THUMB_W))
        {
            Ok(_) => entries.push(FrameEntry {
                frame_ts: ts_ms,
                orig_path: format!("frames/{orig_name}"),
                thumb_path: format!("frames/{thumb_name}"),
                ocr_text: None,
            }),
            Err(e) => log::error!("[frames][{trace}] page {ts_ms}ms save failed: {e}"),
        }
    }
}

#[cfg(windows)]
pub use pipeline::extract_session_frames;

/// 非 Windows 桩：与 capture/decode 一致的降级面。
#[cfg(not(windows))]
pub fn extract_session_frames(
    _session_dir: &std::path::Path,
    _cfg: &ExtractConfig,
    _trace: &str,
) -> Result<Vec<FrameEntry>, String> {
    Err("frame extraction is only supported on Windows".to_string())
}

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

    // ---- Step 5 判页去重 (AC-104) ----

    /// 左半黑右半白（静态帧/首帧测试用；频谱太稀疏，不做 pHash 判官）。
    fn grid_split() -> Vec<u8> {
        (0..PAGE_GRID * PAGE_GRID)
            .map(|i| if i % PAGE_GRID < PAGE_GRID / 2 { 20 } else { 230 })
            .collect()
    }

    /// 4×4 块伪随机纹理：近似真实课件的丰富频谱，pHash 判官用它。
    /// 不同 seed → 不同"课件页"；同 seed → 哈希稳定。
    fn grid_texture(seed: u64) -> Vec<u8> {
        (0..PAGE_GRID * PAGE_GRID)
            .map(|i| {
                let (x, y) = (i % PAGE_GRID, i / PAGE_GRID);
                let block = (x / 4 + (y / 4) * 8) as u64;
                let h = seed
                    .wrapping_mul(6364136223846793005)
                    .wrapping_add(block.wrapping_mul(1442695040888963407))
                    >> 33;
                (h % 256) as u8
            })
            .collect()
    }

    #[test]
    fn histogram_distance_identical_vs_disjoint() {
        let a = gray_histogram(&vec![100u8; 1024]);
        assert_eq!(bhattacharyya_distance(&a, &a), 0.0);
        let dark = gray_histogram(&vec![0u8; 1024]);
        let bright = gray_histogram(&vec![255u8; 1024]);
        assert!(bhattacharyya_distance(&dark, &bright) > 0.9);
    }

    #[test]
    fn phash_same_pattern_close_different_pattern_far() {
        let a = phash64(&grid_texture(1));
        // 同图案加轻微噪声 → 汉明距离应 ≤ 8（同页）
        let noisy: Vec<u8> = grid_texture(1)
            .iter()
            .enumerate()
            .map(|(i, &v)| if i % 53 == 0 { v.saturating_add(12) } else { v })
            .collect();
        let near = hamming(a, phash64(&noisy));
        assert!(near <= PHASH_HAMMING_NEW_PAGE, "noisy same page: hamming={near}");
        // 不同纹理 → > 8（新页）
        let far = hamming(a, phash64(&grid_texture(2)));
        assert!(far > PHASH_HAMMING_NEW_PAGE, "different page: hamming={far}");
    }

    #[test]
    fn extractor_first_frame_is_first_page_and_static_stays() {
        let mut ex = PageExtractor::new(ExtractConfig::default());
        assert!(ex.push(0, &grid_split()).is_some(), "first frame = page 1");
        // 静态 30s（500ms 一帧）不应再出新页
        for i in 1..60 {
            assert!(ex.push(i * 500, &grid_split()).is_none(), "ts={}", i * 500);
        }
        assert_eq!(ex.pages().len(), 1);
    }

    #[test]
    fn extractor_debounce_merges_rapid_changes() {
        let mut ex = PageExtractor::new(ExtractConfig::default());
        ex.push(0, &grid_texture(1));
        assert!(ex.push(10_000, &grid_texture(2)).is_none(), "change → pending, not yet a page");
        // 1s 后又一次"变化"（翻页动画）→ 去抖合并进同一 pending
        assert!(ex.push(11_000, &grid_texture(3)).is_none(), "within debounce window");
        assert_eq!(ex.pages().len(), 1, "burst not settled → still 1 page");
        // 静置满窗（距末次变化 3s）→ 提交为一页
        assert!(ex.push(14_000, &grid_texture(3)).is_some(), "settled → commit page 2");
        assert_eq!(ex.pages().len(), 2);
    }

    #[test]
    fn extractor_dedup_when_returning_to_old_page() {
        let mut ex = PageExtractor::new(ExtractConfig::default());
        ex.push(0, &grid_texture(1)); // page 1
        ex.push(10_000, &grid_texture(2)); // change → pending
        ex.push(13_000, &grid_texture(2)); // settled → page 2
        // 翻回 page 1 的图案：pHash 命中旧页 → 去重
        ex.push(20_000, &grid_texture(1)); // change → pending
        assert!(ex.push(23_000, &grid_texture(1)).is_none(), "return to page 1 must dedup");
        assert_eq!(ex.pages().len(), 2);
    }

    /// AC-104 回归（2026-08-09 实机锚点 20260809_104544_114）：白底课件只换内容
    /// （或整窗录制课件区占比小）时翻页 SAD 仅 ~8 < 12 预筛阈值，hist/pHash 兜底
    /// 必须兜住 —— 静置满窗照样判新页。此前整段 147s 视频只抽出首帧 1 页。
    #[test]
    fn extractor_catches_low_sad_page_flip_via_content_fallback() {
        // 白底稀疏内容页：横条 vs 竖条 ≈ "白底幻灯片换了一页内容"
        let bar = |vertical: bool| -> Vec<u8> {
            (0..PAGE_GRID * PAGE_GRID)
                .map(|i| {
                    let (x, y) = (i % PAGE_GRID, i / PAGE_GRID);
                    if (vertical && x == 8) || (!vertical && y == 8) { 80 } else { 230 }
                })
                .collect()
        };
        let (a, b) = (bar(false), bar(true));
        let cfg = ExtractConfig::default();
        // 前提锚点：SAD 低于预筛阈值（兜底存在的意义），但 pHash 判为不同页
        assert!(
            sad_mean(&a, &b) <= cfg.sad_threshold,
            "SAD 必须低于预筛阈值（否则测不到兜底）: {}",
            sad_mean(&a, &b)
        );
        assert!(
            hamming(phash64(&a), phash64(&b)) > cfg.hamming_new_page,
            "前提：pHash 应判为不同页"
        );

        let mut ex = PageExtractor::new(cfg);
        assert!(ex.push(0, &a).is_some(), "first frame = page 1");
        for i in 1..20 {
            assert!(ex.push(i * 500, &a).is_none(), "static page 1, ts={}", i * 500);
        }
        assert_eq!(ex.pages().len(), 1);
        // 低 SAD 翻页：预筛不触发，兜底挂 pending；静置满窗 → 提交新页
        assert!(ex.push(10_000, &b).is_none(), "change → pending");
        for i in 1..5 {
            assert!(ex.push(10_000 + i * 500, &b).is_none(), "settling, ts={}", 10_000 + i * 500);
        }
        assert!(ex.push(12_500, &b).is_some(), "settled low-SAD flip must commit");
        assert_eq!(ex.pages().len(), 2);
        // 提交后当前页参照已更新：继续静止不再出页
        assert!(ex.push(15_000, &b).is_none());
        assert_eq!(ex.pages().len(), 2);
    }

    /// 低 SAD 持续漂移（嵌入视频/动画一直动）不得每 2.5s 刷一页：
    /// 内容不停变 → pending 被不断更新/重挂，永不静置满窗 → 永不提交。
    #[test]
    fn extractor_drift_rearms_pending_and_does_not_spam_pages() {
        let cfg = ExtractConfig::default();
        // 2 格宽竖条每 500ms 右移 1 格（模拟画面局部持续运动）：
        // 与上一变化帧的 SAD 在 9~19 间波动（静态/变化分支都会走到），
        // 关键是内容一直在动 → pending 永远挂不到 2.5s 静置。
        let bar_at = |col: usize| -> Vec<u8> {
            (0..PAGE_GRID * PAGE_GRID)
                .map(|i| {
                    let x = i % PAGE_GRID;
                    if x == col || x == col + 1 { 80 } else { 230 }
                })
                .collect()
        };
        let mut ex = PageExtractor::new(cfg);
        assert!(ex.push(0, &bar_at(2)).is_some(), "first frame = page 1");
        let mut s = 0usize;
        for i in 1..24 {
            let col = 2 + i as usize;
            if col + 1 >= PAGE_GRID {
                break;
            }
            s += 1;
            assert!(
                ex.push(i * 500, &bar_at(col)).is_none(),
                "drifting content must not commit, ts={}",
                i * 500
            );
        }
        assert!(s > 10, "漂移序列要足够长才测得到不刷页");
        assert_eq!(ex.pages().len(), 1, "漂移段不得刷出新页");
    }

    /// AC-104 端到端：合成 4s 切片（2 页）+ manifest（段起点 5000ms）→
    /// extract_session_frames 应检出 2 页、ts 带段偏移、图片落盘、ocr_text 占位。
    #[cfg(windows)]
    #[test]
    fn extract_session_frames_end_to_end() {
        use windows_capture::encoder::{
            AudioSettingsBuilder, ContainerSettingsBuilder, VideoEncoder, VideoSettingsBuilder,
            VideoSettingsSubType,
        };
        let dir = std::path::PathBuf::from(env!("CARGO_MANIFEST_DIR"))
            .join("target").join("test-sessions").join("extract-e2e");
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(dir.join("video")).unwrap();
        std::fs::create_dir_all(dir.join("frames")).unwrap();

        // 4s @ 30fps：前 2s 纹理 A，后 2s 纹理 B（编码器输入实测按 top-down）。
        let (w, h) = (256u32, 144u32);
        let mut enc = VideoEncoder::new(
            VideoSettingsBuilder::new(w, h)
                .sub_type(VideoSettingsSubType::H264)
                .bitrate(1_000_000)
                .frame_rate(30),
            AudioSettingsBuilder::new().disabled(true),
            ContainerSettingsBuilder::new(),
            dir.join("video").join("video_001.mp4"),
        )
        .unwrap();
        for i in 0..120 {
            let seed: u64 = if i < 60 { 7 } else { 9 };
            let mut frame = vec![0u8; (w * h * 4) as usize];
            for (px, chunk) in frame.chunks_mut(4).enumerate() {
                let (x, y) = (px % w as usize, px / w as usize);
                let block = (x / 16 + (y / 16) * 16) as u64;
                let val = (seed.wrapping_mul(6364136223846793005)
                    .wrapping_add(block.wrapping_mul(1442695040888963407)) >> 33) as u8;
                chunk[0] = val; chunk[1] = val; chunk[2] = val; chunk[3] = 255;
            }
            enc.send_frame_buffer(&frame, i as i64 * 1_000_000 / 3).unwrap();
        }
        enc.finish().unwrap();

        std::fs::write(
            dir.join("video").join("manifest.jsonl"),
            r#"{"index":1,"file":"video_001.mp4","start_ms":5000,"end_ms":9000,"frames":120}"#,
        )
        .unwrap();

        let entries = extract_session_frames(&dir, &ExtractConfig::default(), "test-e2e").unwrap();
        assert!(entries.len() >= 2, "2-page video should yield ≥2 pages: {:?}", entries.len());
        for e in &entries {
            assert!(e.frame_ts >= 5000, "ts must carry segment offset: {}", e.frame_ts);
            assert!(e.ocr_text.is_none(), "ocr_text 是 Step 6 占位");
            assert!(dir.join(&e.orig_path).exists(), "{} missing", e.orig_path);
            assert!(dir.join(&e.thumb_path).exists(), "{} missing", e.thumb_path);
        }
        // 页间 ts 间隔 ≥ 去抖窗（粗判，H.264 噪声可能引入小幅抖动页）
        for pair in entries.windows(2) {
            assert!(pair[1].frame_ts > pair[0].frame_ts, "page ts must increase");
        }
    }

    /// 真实切片判页诊断探针（2026-08-09「课件翻页多次却只抽 1 帧」）。
    /// 每 500ms 采样打印 SAD/直方图距离/pHash 汉明距离 + PageExtractor 判定结果，
    /// 看翻页到底死在哪一级（SAD 预筛 / 直方图 / pHash / 去抖）。
    /// 用法：`VTT_TEST_VIDEO=<切片路径> cargo test --lib page_diag_probe -- --ignored --nocapture`
    #[cfg(windows)]
    #[test]
    #[ignore = "需要 VTT_TEST_VIDEO 指向真实切片"]
    fn page_diag_probe() {
        use crate::screen::decode::sample_video;
        let path = std::path::PathBuf::from(
            std::env::var("VTT_TEST_VIDEO").expect("set VTT_TEST_VIDEO to a video slice path"),
        );
        let cfg = ExtractConfig::default();
        let mut extractor_holder = PageExtractor::new(cfg.clone());
        let ex = &mut extractor_holder;
        let mut prev: Option<Vec<u8>> = None;
        let mut max_sad = 0f64;
        let mut n = 0u64;
        // 每 15s 存一帧 jpg 目检：验证"没被判页的时刻课件是否真的没变"
        let dump_dir = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("target").join("test-sessions").join("decode").join("diag");
        let _ = std::fs::remove_dir_all(&dump_dir);
        std::fs::create_dir_all(&dump_dir).unwrap();
        let mut anchors: Vec<(i64, Vec<u8>)> = Vec::new();
        sample_video(&path, cfg.sample_step_ms, |f| {
            let grid = downsample_gray(
                &f.bgra,
                f.width as usize,
                f.height as usize,
                PAGE_GRID,
                PAGE_GRID,
            );
            let sad = prev.as_ref().map_or(0.0, |p| sad_mean(p, &grid));
            max_sad = max_sad.max(sad);
            // SAD 过 1/4 阈值就打印（全量 296 行太多，但静音期也要有据可查）
            if sad > cfg.sad_threshold / 4.0 || n < 3 {
                let hist = gray_histogram(&grid);
                let hash = phash64(&grid);
                let (hist_d, ham, blk) = match &ex.current {
                    Some(cur) => (
                        bhattacharyya_distance(&cur.hist, &hist),
                        hamming(cur.phash, hash) as i32,
                        max_block_sad(&cur.grid, &grid),
                    ),
                    None => (-1.0, -1, -1.0),
                };
                println!(
                    "ts={:>6}ms SAD={sad:6.2} hist_d={hist_d:5.3} hamming={ham:>2} block_sad={blk:5.1} (阈值 SAD>{} hist>{} ham>{} block>{})",
                    f.ts_ms, cfg.sad_threshold, cfg.hist_threshold, cfg.hamming_new_page, cfg.block_sad_new_page
                );
            }
            if ex.push(f.ts_ms, &grid).is_some() {
                println!("ts={:>6}ms → ★ 判为新页 #{}", f.ts_ms, ex.pages().len());
            }
            if f.ts_ms % 15_000 == 0 || f.ts_ms <= 5_000 {
                let jpg = windows_capture::encoder::ImageEncoder::new(
                    windows_capture::encoder::ImageFormat::Jpeg,
                    windows_capture::encoder::ImageEncoderPixelFormat::Bgra8,
                )
                .and_then(|enc| enc.encode(&f.bgra, f.width, f.height))
                .expect("encode diag jpg");
                std::fs::write(dump_dir.join(format!("diag_{:06}.jpg", f.ts_ms)), jpg)
                    .expect("write diag jpg");
                anchors.push((f.ts_ms, grid.clone()));
            }
            prev = Some(grid);
            n += 1;
        })
        .expect("sample real video");
        if ex.flush().is_some() {
            println!("flush → ★ 判为新页 #{}", ex.pages().len());
        }
        println!("sampled={n} max_SAD={max_sad:.2} pages={}", ex.pages().len());
        // 锚点帧两两 pHash 汉明 / 直方图距离矩阵：看"同页带标注"与"真翻页"的数值间隔
        let hashes: Vec<u64> = anchors.iter().map(|(_, g)| phash64(g)).collect();
        let hists: Vec<[u32; 32]> = anchors.iter().map(|(_, g)| gray_histogram(g)).collect();
        println!("pairwise (ts_s: hamming / hist_d / max_block_sad):");
        for i in 0..anchors.len() {
            for j in (i + 1)..anchors.len() {
                // 32×32 栅格切成 4×4 块（每块 8×8 cell），取块内 SAD 的最大值：
                // 局部翻页（课件区只占整窗一部分）在块级差异上应该暴露得更明显
                let mut max_block = 0f64;
                for by in 0..4 {
                    for bx in 0..4 {
                        let mut sum = 0u64;
                        for cy in 0..8 {
                            for cx in 0..8 {
                                let idx = (by * 8 + cy) * 32 + bx * 8 + cx;
                                sum += anchors[i].1[idx].abs_diff(anchors[j].1[idx]) as u64;
                            }
                        }
                        max_block = max_block.max(sum as f64 / 64.0);
                    }
                }
                println!(
                    "  {:>3}s vs {:>3}s: ham={:>2} hist={:.3} block_sad={:.1}",
                    anchors[i].0 / 1000,
                    anchors[j].0 / 1000,
                    hamming(hashes[i], hashes[j]),
                    bhattacharyya_distance(&hists[i], &hists[j]),
                    max_block
                );
            }
        }
    }
}
