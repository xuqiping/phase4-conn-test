//! Step 6 (FR-105): 课件 OCR —— 对 frames.json 的原图跑本地 OCR，回填 `ocr_text`。
//!
//! 选型（原 plan「需人工介入」点，已拍板）：
//! - 引擎：**oar-ocr 0.9**（PP-OCRv5 ONNX 模型的纯 Rust 推理，ort/ONNX Runtime 后端）。
//!   与 PaddleOCR 同模型族 → 中文课件效果同源最优；纯 Rust 免 FFI。
//!   （crates.io 上不存在 rapidocr 官方 crate，PaddleOCR FFI 需 C++ 链，均排除。）
//! - 模型：**PP-OCRv5 mobile**（det 4.6MiB + rec 15.8MiB + dict），中文优化。
//! - 打包策略：**首用自动下载**（auto-download feature → ModelScope → $OAR_HOME，国内可达，
//!   SHA-256 校验）；exe 旁 `models/ocr/` 存在同名文件则优先用本地（可后续改内置打包）。
//!
//! 降级（O4）：单帧 OCR 失败 → `ocr_text` 保持 null + log warn，不阻塞整课；
//! 图像文件缺失同理计 failed。全部本地推理，内容不出本机（安全检查通过）。

use crate::screen::scene_detect::FrameEntry;
use image::RgbImage;
use std::path::{Path, PathBuf};

/// 注册模型名（oar-ocr auto-download registry；ModelScope 源）。
pub const DET_MODEL: &str = "pp-ocrv5_mobile_det.onnx";
pub const REC_MODEL: &str = "pp-ocrv5_mobile_rec.onnx";
pub const DICT_FILE: &str = "ppocrv5_dict.txt";

/// OCR 可配置阈值（O2 配置面；与 ExtractConfig 同模式）。
#[derive(Debug, Clone)]
pub struct OcrConfig {
    /// 识别置信度下限：低于此值的文本行丢弃（脏框/噪声）。
    pub min_confidence: f32,
    /// 对比度拉伸的低/高百分位（0-1）。课件常见低反差投影字，2%/98% 拉伸。
    pub stretch_low_pct: f64,
    pub stretch_high_pct: f64,
}

impl Default for OcrConfig {
    fn default() -> Self {
        Self {
            min_confidence: 0.5,
            stretch_low_pct: 0.02,
            stretch_high_pct: 0.98,
        }
    }
}

/// 模型路径解析：exe 旁 `models/ocr/` 三件齐 → 用本地（内置打包/离线）；
/// 否则返回裸名 → oar-ocr auto-download 从 ModelScope 拉到 $OAR_HOME。
pub fn resolve_model_paths() -> (String, String, String) {
    let local_dir = std::env::current_exe()
        .ok()
        .and_then(|p| p.parent().map(|d| d.join("models").join("ocr")))
        .or_else(|| std::env::current_dir().ok().map(|d| d.join("models").join("ocr")));
    if let Some(dir) = local_dir {
        let det = dir.join(DET_MODEL);
        let rec = dir.join(REC_MODEL);
        let dict = dir.join(DICT_FILE);
        if det.exists() && rec.exists() && dict.exists() {
            let strip = |p: PathBuf| p.to_string_lossy().replace("\\\\?\\", "");
            log::info!("[ocr] 使用本地模型目录 {}", dir.display());
            return (strip(det), strip(rec), strip(dict));
        }
    }
    log::info!("[ocr] 本地无模型 → 裸名走 auto-download（$OAR_HOME / ModelScope）");
    (DET_MODEL.into(), REC_MODEL.into(), DICT_FILE.into())
}

/// 对比度增强：按亮度百分位做线性拉伸（应对投影/截图低反差小字）。
/// 纯逻辑可测。hi<=lo（纯色图）时原样返回。
pub fn contrast_stretch(img: &RgbImage, low_pct: f64, high_pct: f64) -> RgbImage {
    let (w, h) = img.dimensions();
    let n = (w as usize * h as usize).max(1);
    // 亮度直方图（BT.601 近似：2G+R+B 的整数版即 luma=(r+2g+b)/4 足够排序用）。
    let mut hist = [0u32; 256];
    for p in img.pixels() {
        let luma = ((p[0] as u32 + 2 * p[1] as u32 + p[2] as u32) / 4) as usize;
        hist[luma] += 1;
    }
    let pick = |pct: f64| -> u32 {
        // 累计计数 >= 目标的首个亮度桶；target 至少为 1 使 pct=0 落在最小占用桶，
        // pct=1 落在最大占用桶（acc 恰好在该桶达到 n）。
        let target = ((n as f64 * pct.clamp(0.0, 1.0)) as u32).max(1);
        let mut acc = 0u32;
        for (v, &c) in hist.iter().enumerate() {
            acc += c;
            if acc >= target {
                return v as u32;
            }
        }
        255
    };
    let mut lo = pick(low_pct);
    let mut hi = pick(high_pct);
    if hi <= lo {
        // 百分位退化（如 98% 像素挤同一桶）→ 回退实际占用区间 min/max。
        lo = hist.iter().position(|&c| c > 0).unwrap_or(0) as u32;
        hi = hist.iter().rposition(|&c| c > 0).unwrap_or(0) as u32;
    }
    if hi <= lo {
        return img.clone(); // 纯色图：原样返回
    }
    let scale = 255.0 / (hi - lo) as f32;
    let mut out = RgbImage::new(w, h);
    for (src, dst) in img.pixels().zip(out.pixels_mut()) {
        let map = |v: u8| -> u8 { ((v as f32 - lo as f32) * scale).clamp(0.0, 255.0) as u8 };
        *dst = image::Rgb([map(src[0]), map(src[1]), map(src[2])]);
    }
    out
}

/// OCR 引擎封装：模型加载耗时（秒级），复用单次构建。
pub struct OcrEngine {
    inner: oar_ocr::prelude::OAROCR,
    cfg: OcrConfig,
}

impl OcrEngine {
    pub fn new(cfg: OcrConfig) -> Result<Self, String> {
        let (det, rec, dict) = resolve_model_paths();
        let inner = oar_ocr::prelude::OAROCRBuilder::new(det, rec, dict)
            .build()
            .map_err(|e| format!("build OAROCR engine: {e}"))?;
        Ok(Self { inner, cfg })
    }

    /// 对内存图像识别：预处理（对比度拉伸）→ det+rec → 按置信度过滤 → 行拼接。
    pub fn recognize_image(&self, img: &RgbImage) -> Result<String, String> {
        let stretched = contrast_stretch(img, self.cfg.stretch_low_pct, self.cfg.stretch_high_pct);
        let results = self
            .inner
            .predict(vec![stretched])
            .map_err(|e| format!("ocr predict: {e}"))?;
        let mut lines = Vec::new();
        for result in &results {
            for region in &result.text_regions {
                if let Some((text, conf)) = region.text_with_confidence() {
                    if conf >= self.cfg.min_confidence && !text.trim().is_empty() {
                        lines.push(text);
                    }
                }
            }
        }
        Ok(lines.join("\n"))
    }

    pub fn recognize_path(&self, path: &Path) -> Result<String, String> {
        let img = image::open(path)
            .map_err(|e| format!("open image {}: {e}", path.display()))?
            .to_rgb8();
        self.recognize_image(&img)
    }
}

/// process_ocr 统计。
#[derive(Debug, Default, PartialEq, Eq)]
pub struct OcrStats {
    pub total: usize,
    pub already: usize,
    pub ok: usize,
    pub failed: usize,
}

/// 编排：读 frames.json → 对 ocr_text 为 null 的条目跑 OCR → 回填写回。
/// 引擎懒构建：没有「文件存在且待识别」的帧时不加载模型（避免无谓下载）。
pub fn process_session_ocr(
    session_dir: &Path,
    cfg: &OcrConfig,
    trace: &str,
) -> Result<OcrStats, String> {
    let frames_path = session_dir.join("frames.json");
    let raw = std::fs::read_to_string(&frames_path).map_err(|e| {
        format!("read frames.json: {e}（先跑 process_frames）")
    })?;
    let mut entries: Vec<FrameEntry> =
        serde_json::from_str(&raw).map_err(|e| format!("parse frames.json: {e}"))?;

    let mut stats = OcrStats { total: entries.len(), ..Default::default() };
    let mut engine: Option<OcrEngine> = None;

    for entry in &mut entries {
        if entry.ocr_text.is_some() {
            stats.already += 1;
            continue;
        }
        let img_path = session_dir.join("frames").join(&entry.orig_path);
        if !img_path.exists() {
            stats.failed += 1;
            log::warn!("[ocr][{trace}] 原图缺失，跳过: {}", entry.orig_path);
            continue;
        }
        if engine.is_none() {
            engine = Some(OcrEngine::new(cfg.clone())?);
        }
        let eng = engine.as_ref().unwrap();
        match eng.recognize_path(&img_path) {
            Ok(text) => {
                stats.ok += 1;
                entry.ocr_text = if text.is_empty() { None } else { Some(text) };
            }
            Err(e) => {
                // O4 降级：单帧失败 → null + warn，不阻塞整课。
                stats.failed += 1;
                log::warn!("[ocr][{trace}] OCR 失败 {}: {e}", entry.orig_path);
            }
        }
    }

    let json = serde_json::to_string_pretty(&entries).map_err(|e| e.to_string())?;
    std::fs::write(&frames_path, json).map_err(|e| format!("write frames.json: {e}"))?;
    log::info!(
        "[ocr][{trace}] process_ocr: total={} already={} ok={} failed={}",
        stats.total, stats.already, stats.ok, stats.failed
    );
    Ok(stats)
}

#[cfg(test)]
mod tests {
    use super::*;
    use image::Rgb;

    fn solid(v: u8, w: u32, h: u32) -> RgbImage {
        RgbImage::from_pixel(w, h, Rgb([v, v, v]))
    }

    #[test]
    fn contrast_stretch_expands_low_contrast() {
        // 低反差图：像素全在 100..110 → 拉伸后应接近 0..255。
        let mut img = solid(100, 100, 100);
        for (i, p) in img.pixels_mut().enumerate() {
            let v = 100 + (i % 11) as u8;
            *p = Rgb([v, v, v]);
        }
        let out = contrast_stretch(&img, 0.0, 1.0);
        let min = out.pixels().map(|p| p[0]).min().unwrap();
        let max = out.pixels().map(|p| p[0]).max().unwrap();
        assert_eq!(min, 0);
        assert!(max >= 250, "max={max}");
    }

    #[test]
    fn contrast_stretch_flat_image_unchanged() {
        let img = solid(128, 32, 32);
        let out = contrast_stretch(&img, 0.02, 0.98);
        assert!(out.pixels().all(|p| p[0] == 128), "纯色图 hi<=lo 应原样返回");
    }

    #[test]
    fn contrast_stretch_percentile_clips_outliers() {
        // 98% 像素在 50，2% 像素 255（高光点）→ p98 退化回退 min/max：暗部压 0、高光 255。
        let mut img = solid(50, 100, 100);
        for i in 0..200 {
            let x = (i % 100) as u32;
            let y = (i / 100) as u32;
            img.put_pixel(x, y, Rgb([255, 255, 255]));
        }
        let out = contrast_stretch(&img, 0.02, 0.98);
        let dark = out.get_pixel(50, 50)[0];
        assert!(dark <= 10, "回退 min/max 后暗部 50 应压到近 0，got {dark}");
        assert_eq!(out.get_pixel(0, 0)[0], 255);
    }

    #[test]
    fn process_ocr_missing_frames_json_errors() {
        let dir = std::env::temp_dir().join(format!("ocr_test_missing_{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let r = process_session_ocr(&dir, &OcrConfig::default(), "test");
        assert!(r.is_err());
        std::fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn process_ocr_skips_done_and_counts_missing_image() {
        // 不触发引擎加载（无真实模型也可跑）：一条已回填 + 一条原图缺失。
        let dir = std::env::temp_dir().join(format!("ocr_test_degrade_{}", std::process::id()));
        std::fs::create_dir_all(dir.join("frames")).unwrap();
        let entries = vec![
            FrameEntry {
                frame_ts: 0,
                orig_path: "page_0.jpg".into(),
                thumb_path: "thumb_0.jpg".into(),
                ocr_text: Some("已有文本".into()),
            },
            FrameEntry {
                frame_ts: 1000,
                orig_path: "page_1000.jpg".into(), // 不存在 → failed，保持 null
                thumb_path: "thumb_1000.jpg".into(),
                ocr_text: None,
            },
        ];
        std::fs::write(
            dir.join("frames.json"),
            serde_json::to_string_pretty(&entries).unwrap(),
        )
        .unwrap();
        let stats = process_session_ocr(&dir, &OcrConfig::default(), "test").unwrap();
        assert_eq!(
            stats,
            OcrStats { total: 2, already: 1, ok: 0, failed: 1 },
            "O4 降级：缺图计 failed 不报错"
        );
        // 写回后已回填文本保留、缺图条目仍 null。
        let back: Vec<FrameEntry> = serde_json::from_str(
            &std::fs::read_to_string(dir.join("frames.json")).unwrap(),
        )
        .unwrap();
        assert_eq!(back[0].ocr_text.as_deref(), Some("已有文本"));
        assert_eq!(back[1].ocr_text, None);
        std::fs::remove_dir_all(&dir).ok();
    }

    /// 真实 OCR 冒烟：需 auto-download 拉模型（ModelScope 直连），手动跑：
    /// `cargo test -- --ignored ocr_real`
    /// 只验证「模型加载 + 推理管线」端到端通；中文识别准确率人工验证留 Phase4。
    #[test]
    #[ignore]
    fn ocr_real_smoke() {
        let engine = OcrEngine::new(OcrConfig::default()).expect("engine build");
        let img = solid(255, 64, 32); // 空白图：应 Ok（可能空文本），不崩即可
        let r = engine.recognize_image(&img);
        assert!(r.is_ok(), "blank image ocr should not crash: {r:?}");
    }
}
