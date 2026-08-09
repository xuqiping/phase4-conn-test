//! Video slice decoding for 网课录屏总结 (plan Step 5 / FR-104).
//!
//! Reads the MP4 segments written by `encode.rs` and samples frames at a fixed
//! interval for the fine page-extraction pipeline. Uses Windows **Media
//! Foundation SourceReader** (H.264 decode + RGB32 convert in-box) — 系统自带，
//! 无 ffmpeg 依赖、包体零增长（design §3.5「纯 Rust / 无 ffmpeg」约束的系统 API 实现）。
//!
//! Frames stream out via callback (never buffered wholesale: 2h @2Hz full-res
//! would be 50+ GB); the caller downsamples / dedups on the fly.
//!
//! 时间轴：回调里的 `ts_ms` 是**片内**时间（每段 MP4 的 PTS 从 0 开始，见
//! encode.rs 说明）；换算 session 时间轴要加 manifest 里该段的 `start_ms`，
//! 由 Step 5b 的 process_frames 完成。

use std::path::Path;

/// One sampled video frame (full resolution, BGRA8 top-down rows).
pub struct SampledFrame {
    /// In-segment timestamp, ms from this MP4's first frame.
    pub ts_ms: i64,
    pub width: u32,
    pub height: u32,
    pub bgra: Vec<u8>,
}

// ---------------------------------------------------------------------------
// Windows implementation (MF SourceReader)
// ---------------------------------------------------------------------------
#[cfg(windows)]
mod imp {
    use super::*;
    use windows::core::{Interface, HSTRING};
    use windows::Win32::Media::MediaFoundation::*;

    /// Stream frames from `path`, invoking `on_frame` at most once per
    /// `step_ms` of in-segment time. Returns the number of frames sampled.
    ///
    /// Sequential decode (no seeking) — reliable across MF H.264 decoder
    /// quirks, and 2–4 Hz sampling keeps the cost dominated by decode alone.
    pub fn sample_video(
        path: &Path,
        step_ms: i64,
        mut on_frame: impl FnMut(SampledFrame),
    ) -> Result<u64, String> {
        unsafe {
            MFStartup(MF_VERSION, MFSTARTUP_FULL).map_err(|e| e.to_string())?;
            let result = sample_video_inner(path, step_ms, &mut on_frame);
            let _ = MFShutdown();
            result
        }
    }

    unsafe fn sample_video_inner(
        path: &Path,
        step_ms: i64,
        on_frame: &mut impl FnMut(SampledFrame),
    ) -> Result<u64, String> {
        let url = HSTRING::from(path.as_os_str());
        // ENABLE_VIDEO_PROCESSING: 否则 SourceReader 拒绝 H.264→RGB32 的
        // SetCurrentMediaType（0xC00D36B4 —— 默认不加载格式转换 MFT）。
        let mut attrs: Option<IMFAttributes> = None;
        MFCreateAttributes(&mut attrs, 1).map_err(|e| e.to_string())?;
        let attrs = attrs.ok_or("MFCreateAttributes returned None")?;
        attrs
            .SetUINT32(&MF_SOURCE_READER_ENABLE_VIDEO_PROCESSING, 1)
            .map_err(|e| e.to_string())?;
        let reader: IMFSourceReader = MFCreateSourceReaderFromURL(&url, &attrs)
            .map_err(|e| format!("open {path:?}: {e}"))?;

        // Force BGRA32 output; MF inserts decoder + color converter as needed.
        let out_type: IMFMediaType = MFCreateMediaType().map_err(|e| e.to_string())?;
        out_type
            .SetGUID(&MF_MT_MAJOR_TYPE, &MFMediaType_Video)
            .map_err(|e| e.to_string())?;
        out_type
            .SetGUID(&MF_MT_SUBTYPE, &MFVideoFormat_RGB32)
            .map_err(|e| e.to_string())?;
        reader
            .SetCurrentMediaType(MF_SOURCE_READER_FIRST_VIDEO_STREAM.0 as u32, None, &out_type)
            .map_err(|e| format!("set output type: {e}"))?;

        // Negotiated frame geometry (MF_MT_FRAME_SIZE packs width in the high
        // 32 bits — MFGetAttributeSize isn't in windows 0.61, unpack manually).
        let current: IMFMediaType = reader
            .GetCurrentMediaType(MF_SOURCE_READER_FIRST_VIDEO_STREAM.0 as u32)
            .map_err(|e| e.to_string())?;
        let frame_size = current
            .GetUINT64(&MF_MT_FRAME_SIZE)
            .map_err(|e| e.to_string())?;
        let (width, height) = ((frame_size >> 32) as u32, frame_size as u32);
        if width == 0 || height == 0 {
            return Err("video reports 0x0 frame size".to_string());
        }

        // stride 语义：有符号 int32 按 UINT32 存；MF 约定正 = bottom-up。
        // ⚠ 但此值在「首帧读出前」是协商请求值，不可靠（2026-08-09 实机：
        // 请求值报 6512 紧凑，实际表面 1632x1024 pitch 6528）。真正的
        // pitch/朝向在首个 sample 读出后重查（下方 first-sample 重查块）。
        let mut stride = current
            .GetUINT32(&MF_MT_DEFAULT_STRIDE)
            .map(|v| v as i32)
            .unwrap_or(width as i32 * 4);
        // 朝向 hint：pitch 有填充（对齐表面拷贝）→ top-down；紧凑 DIB →
        // bottom-up 符号约定成立。两个分支各有实机/单测锚点（见下方首帧重查块）。
        let mut top_down_hint = false;

        let step_ticks = step_ms.max(1) * 10_000; // ms → 100ns units
        let mut next_sample_ts = 0i64;
        let mut sampled = 0u64;

        loop {
            let mut flags: u32 = 0;
            let mut timestamp = 0i64;
            let mut sample: Option<IMFSample> = None;
            reader
                .ReadSample(
                    MF_SOURCE_READER_FIRST_VIDEO_STREAM.0 as u32,
                    0,
                    None,
                    Some(&mut flags),
                    Some(&mut timestamp),
                    Some(&mut sample),
                )
                .map_err(|e| format!("read sample: {e}"))?;
            if (flags & MF_SOURCE_READERF_ENDOFSTREAM.0 as u32) != 0 {
                break;
            }
            let Some(sample) = sample else { continue };
            if timestamp < next_sample_ts {
                continue; // between sample points — decode but don't copy out
            }
            next_sample_ts = timestamp + step_ticks;

            // 首个 sample 读出后重查协商媒体类型 —— 此刻才是视频处理器
            // 真实输出：stride 幅值 = 真实 pitch（首帧 copy 也要用，放 copy 前）。
            if sampled == 0 {
                if let Ok(live) =
                    reader.GetCurrentMediaType(MF_SOURCE_READER_FIRST_VIDEO_STREAM.0 as u32)
                {
                    if let Ok(s) = live.GetUINT32(&MF_MT_DEFAULT_STRIDE) {
                        stride = s as i32;
                    }
                }
                // 朝向判定：pitch 有填充（对齐表面拷贝）→ top-down；
                // pitch 紧凑（经典 DIB）→ 符号约定（正 = bottom-up）。
                // 锚点：synth 320x180 pitch 紧凑=bottom-up（单测回环）；
                //       实机 1628x1021 pitch 6528>6512=top-down（2026-08-09）。
                top_down_hint = stride.unsigned_abs() as usize > width as usize * 4;
            }

            let bgra = copy_sample_frame(&sample, width, height, stride, top_down_hint)?;
            on_frame(SampledFrame {
                ts_ms: timestamp / 10_000,
                width,
                height,
                bgra,
            });
            sampled += 1;
        }
        Ok(sampled)
    }

    /// Copy an RGB32 sample into a tightly packed top-down BGRA vec.
    ///
    /// pitch 探测（2026-08-09 实机斜纹根因完整记录）：1628x1021 奇数高视频，
    /// 解码器输出 **16 对齐表面**（1632x1024，pitch 6528），协商请求值却报
    /// 紧凑 6512 → 按假 pitch 读 = 整幅斜纹。首帧读出后重查的 live 媒体类型
    /// 给出真实 pitch；拿不到再用 cur_len 反推（`derive_pitch`）。
    ///
    /// 朝向（同为正 stride，两种布局都真实存在过）：
    ///   - pitch 紧凑（== width×4，经典 DIB）：bottom-up 约定成立（单测回环锚点）；
    ///   - pitch 有填充（对齐表面拷贝）：输出 **top-down**
    ///     （实机锚点：1628x1021 pitch 6528，翻转会得到倒置图）。
    ///   调用方传 top_down_hint 区分。
    ///
    /// alpha 通道强制置 255：MF RGB32 实为 BGRX，第 4 字节未定义（常为 0）。
    /// 下游 windows-capture ImageEncoder 按 **Premultiplied alpha** 解释缓冲，
    /// alpha=0 会被当成全透明 → 编码出纯黑图（2026-08-08 用户实机踩坑）。
    unsafe fn copy_sample_frame(
        sample: &IMFSample,
        width: u32,
        height: u32,
        stride: i32,
        top_down_hint: bool,
    ) -> Result<Vec<u8>, String> {
        let row_bytes = width as usize * 4;
        // 1) 原始 buffer 的 2D 锁（真实 pitch；ConvertToContiguousBuffer 摊平后
        //    2D 能力丢失，必须在摊平前试）。Lock2D 语义：scanline0 = 内存首行，
        //    pitch 带符号（正 = bottom-up 需翻，负 = top-down）。
        if let Ok(raw) = sample.GetBufferByIndex(0) {
            if let Ok(buf2d) = raw.cast::<IMF2DBuffer>() {
                let mut scanline0: *mut u8 = std::ptr::null_mut();
                let mut pitch: i32 = 0;
                if buf2d.Lock2D(&mut scanline0, &mut pitch).is_ok() {
                    let result = (|| {
                        if scanline0.is_null() || (pitch.unsigned_abs() as usize) < row_bytes {
                            return Err(format!("lock2d pitch {pitch} invalid for {width}x{height}"));
                        }
                        Ok(copy_rows_2d(scanline0, pitch, width, height, top_down_hint))
                    })();
                    let _ = buf2d.Unlock2D();
                    return result;
                }
            }
        }
        // 2) 摊平 + 1D Lock：pitch 优先 live stride 幅值，对不上用 cur_len 反推
        let buffer = sample
            .ConvertToContiguousBuffer()
            .map_err(|e| format!("contiguous buffer: {e}"))?;
        let mut ptr: *mut u8 = std::ptr::null_mut();
        let mut cur_len: u32 = 0;
        buffer
            .Lock(&mut ptr, None, Some(&mut cur_len))
            .map_err(|e| format!("lock: {e}"))?;
        let result = (|| {
            let derived = derive_pitch(cur_len as usize, row_bytes, height as usize);
            let attr = stride.unsigned_abs() as usize;
            // live stride 放得下就用（首帧时 attr 可能还是请求假值，则靠反推）
            let pitch = if attr >= row_bytes
                && attr * (height as usize - 1) + row_bytes <= cur_len as usize
            {
                attr
            } else {
                derived
            };
            // 最后一行起点 + 一行字节 = 实际需要的缓冲长度（pitch 对齐填充只在行间）
            let need = pitch * (height as usize - 1) + row_bytes;
            if ptr.is_null() || (cur_len as usize) < need {
                return Err(format!(
                    "buffer too small: {cur_len} < {need} (pitch {pitch})"
                ));
            }
            let bottom_up = stride > 0 && !top_down_hint;
            Ok(reorder_bgra(ptr, cur_len as usize, width, height, pitch, bottom_up))
        })();
        let _ = buffer.Unlock();
        result
    }

    /// 从摊平缓冲长度反推行距：解码器对齐表面时 cur_len = pitch × 对齐后
    /// 行数（≥ 图像行数）。紧凑（cur_len == row_bytes × height）直接返回；
    /// 否则找最小合法 pitch（4 字节倍数、整除 cur_len、容纳得下 height 行）。
    /// 实机锚点：1628x1021 视频 → cur_len 6684672 = 6528 × 1024，搜出 6528。
    pub(crate) fn derive_pitch(cur_len: usize, row_bytes: usize, height: usize) -> usize {
        if cur_len == row_bytes * height {
            return row_bytes;
        }
        let mut pitch = row_bytes + 4;
        while pitch <= row_bytes + 4096 {
            if cur_len % pitch == 0 && cur_len / pitch >= height {
                return pitch;
            }
            pitch += 4;
        }
        row_bytes // 找不到按紧凑，长度检查会兜底报错
    }

    /// 2D 锁路径重排：scanline0 = 内存首行，pitch 带符号（正 = bottom-up，
    /// 负 = top-down，负 pitch 时行地址向下递减）。top_down_hint（对齐表面）
    /// 覆盖符号约定。输出紧凑 top-down、alpha 恒 255。
    unsafe fn copy_rows_2d(
        scanline0: *const u8,
        pitch: i32,
        width: u32,
        height: u32,
        top_down_hint: bool,
    ) -> Vec<u8> {
        let row_bytes = width as usize * 4;
        let mut out = vec![0u8; row_bytes * height as usize];
        let bottom_up = pitch > 0 && !top_down_hint;
        for row in 0..height as usize {
            let img_row = if bottom_up {
                height as usize - 1 - row
            } else {
                row
            };
            let src = scanline0.offset(img_row as isize * pitch as isize);
            std::ptr::copy_nonoverlapping(src, out[row * row_bytes..].as_mut_ptr(), row_bytes);
        }
        for px in out.chunks_exact_mut(4) {
            px[3] = 255;
        }
        out
    }

    /// 纯像素重排（抽出便于单测）：按 pitch 行距读源行，输出紧凑 top-down，
    /// alpha 恒置 255。bottom_up=true 时源第 0 行是画面底行，翻成 top-down。
    pub(crate) unsafe fn reorder_bgra(
        src: *const u8,
        src_len: usize,
        width: u32,
        height: u32,
        pitch: usize,
        bottom_up: bool,
    ) -> Vec<u8> {
        let row_bytes = width as usize * 4;
        let mut out = vec![0u8; row_bytes * height as usize];
        for row in 0..height as usize {
            let src_row = if bottom_up {
                height as usize - 1 - row
            } else {
                row
            };
            let offset = src_row * pitch;
            if offset + row_bytes > src_len {
                break; // 末尾截断防御（正常路径已被 copy_frame 的长度检查拦住）
            }
            std::ptr::copy_nonoverlapping(
                src.add(offset),
                out[row * row_bytes..].as_mut_ptr(),
                row_bytes,
            );
        }
        // BGRX → BGRA：每像素第 4 字节置 255（见 copy_frame 文档）。
        for px in out.chunks_exact_mut(4) {
            px[3] = 255;
        }
        out
    }
}

#[cfg(windows)]
pub use imp::sample_video;

/// Non-Windows stub: decoding is Windows-only (MF), same as capture.
#[cfg(not(windows))]
pub fn sample_video(
    _path: &Path,
    _step_ms: i64,
    _on_frame: impl FnMut(SampledFrame),
) -> Result<u64, String> {
    Err("video decoding is only supported on Windows".to_string())
}

#[cfg(all(test, windows))]
mod tests {
    use super::*;
    use super::imp::{derive_pitch, reorder_bgra};
    use windows_capture::encoder::{
        AudioSettingsBuilder, ContainerSettingsBuilder, VideoEncoder, VideoSettingsBuilder,
        VideoSettingsSubType,
    };

    /// Synthesize a tiny MP4 in-memory style: 4s @ 30fps, page A (dark) for
    /// 2s then page B (bright) for 2s, via the same encoder the recorder uses
    /// (`send_frame_buffer` accepts raw BGRA — no window needed).
    fn make_test_video(path: &Path) {
        let (w, h) = (320u32, 180u32);
        let mut enc = VideoEncoder::new(
            VideoSettingsBuilder::new(w, h)
                .sub_type(VideoSettingsSubType::H264)
                .bitrate(1_000_000)
                .frame_rate(30),
            AudioSettingsBuilder::new().disabled(true),
            ContainerSettingsBuilder::new(),
            path,
        )
        .expect("create encoder");
        for i in 0..120 {
            let v: u8 = if i < 60 { 20 } else { 220 }; // page flip halfway
            let mut frame = vec![v; (w * h * 4) as usize];
            // 方向标记：顶部 16 行恒亮带。实测（回环诊断）crate 文档说
            // send_frame_buffer 要 bottom-to-top，但实际按 top-down 解释 ——
            // 亮带写 buffer 开头 = 画面顶部。解码端 bottom-up→top-down 翻转
            // 若弄错，亮带会跑到 out 底部，下面的相对断言就会抓到。
            for row in frame.chunks_mut((w * 4) as usize).take(16) {
                row.fill(250);
            }
            let ts_100ns = i as i64 * 1_000_000 / 3; // 33.3ms per frame
            enc.send_frame_buffer(&frame, ts_100ns).expect("send frame");
        }
        enc.finish().expect("finish encoder");
    }

    /// padded stride（2026-08-09 实机斜纹根因）：行间有填充时按 pitch 读，
    /// 输出必须紧凑且不斜。构造 3x2 图、pitch 16（行内容 12 + 4 填充）。
    #[test]
    fn reorder_bgra_honours_padded_pitch() {
        let (w, h, pitch) = (3usize, 2usize, 16usize);
        let row_bytes = w * 4;
        // bottom-up 源：buffer 第 0 行 = 画面底行。底行像素全 1，顶行全 2。
        let mut src = vec![0u8; pitch * h];
        for i in 0..row_bytes {
            src[i] = 1; // buffer 行 0 = 底行
            src[pitch + i] = 2; // buffer 行 1 = 顶行
        }
        let out = unsafe { reorder_bgra(src.as_ptr(), src.len(), w as u32, h as u32, pitch, true) };
        assert_eq!(out.len(), row_bytes * h);
        // top-down 输出：行 0 = 顶行（源值 2，alpha 置 255）
        assert_eq!(&out[0..4], &[2, 2, 2, 255]);
        assert_eq!(&out[row_bytes..row_bytes + 4], &[1, 1, 1, 255]);
        // top-down（负 stride）不翻
        let out = unsafe { reorder_bgra(src.as_ptr(), src.len(), w as u32, h as u32, pitch, false) };
        assert_eq!(&out[0..4], &[1, 1, 1, 255]);
    }

    /// 行距反推：紧凑直返；对齐表面用实机锚点（1628x1021 → 6528×1024）；
    /// 长度对不上按紧凑兜底（交给上层长度检查报错，不出错图）。
    #[test]
    fn derive_pitch_finds_aligned_surface_pitch() {
        // 紧凑
        assert_eq!(derive_pitch(6512 * 1022, 6512, 1022), 6512);
        // 实机斜纹锚点：6684672 = 6528 × 1024（16 对齐表面）
        assert_eq!(derive_pitch(6_684_672, 6512, 1022), 6528);
        // 对不上 → 紧凑兜底
        assert_eq!(derive_pitch(6512 * 1022 + 7, 6512, 1022), 6512);
    }

    /// AC-104 前置：切片可读回、采样间隔生效、时间戳单调、几何正确。
    #[test]
    fn sample_video_roundtrip() {        let dir = Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("target")
            .join("test-sessions")
            .join("decode");
        std::fs::create_dir_all(&dir).unwrap();
        let mp4 = dir.join("synth.mp4");
        make_test_video(&mp4);

        let mut frames: Vec<SampledFrame> = Vec::new();
        let n = sample_video(&mp4, 500, |f| frames.push(f)).expect("sample_video");
        assert!(n >= 6, "4s @2Hz should sample ≥6 frames, got {n}");
        assert!(n <= 10, "sampling cap broken: {n}");
        for f in &frames {
            assert_eq!((f.width, f.height), (320, 180));
            assert_eq!(f.bgra.len(), 320 * 180 * 4);
            // copy_frame 必须把 BGRX 的未用字节规范成 alpha=255，
            // 否则下游按 premultiplied alpha 编码会出纯黑图。
            assert!(
                f.bgra.chunks_exact(4).all(|px| px[3] == 255),
                "alpha must be normalized to 255"
            );
        }
        for w in frames.windows(2) {
            assert!(w[1].ts_ms > w[0].ts_ms, "timestamps must increase");
        }
        // 前半暗页、后半亮页可区分（编码有损，给足余量）。
        // 亮度只算 B/G/R 三通道 —— alpha 已被规范成恒 255，算进来会抬高均值。
        let bgr_mean = |buf: &[u8]| {
            let (mut sum, mut n) = (0u64, 0u64);
            for px in buf.chunks_exact(4) {
                sum += px[0] as u64 + px[1] as u64 + px[2] as u64;
                n += 3;
            }
            sum / n.max(1)
        };
        let mean = |f: &SampledFrame| bgr_mean(&f.bgra);
        assert!(mean(frames.first().unwrap()) < 80, "page A should be dark");
        assert!(mean(frames.last().unwrap()) > 160, "page B should be bright");
        // 方向标记：顶部 16 行必须显著亮于底部（H.264 有损会压暗亮带，
        // 用相对差而非绝对阈值）。行序翻错时亮带沉底、此断言反转。
        let band_mean = |f: &SampledFrame, from_row: usize| {
            bgr_mean(&f.bgra[from_row * 320 * 4..(from_row + 16) * 320 * 4])
        };
        let first = frames.first().unwrap();
        assert!(
            band_mean(first, 0) > band_mean(first, 164) + 50,
            "top band lost — row order flipped? top={} bottom={}",
            band_mean(first, 0),
            band_mean(first, 164)
        );
    }

    /// 真实录制切片诊断（手动跑）：把任一 video_NNN.mp4 路径设进
    /// `VTT_TEST_VIDEO` 环境变量再 `cargo test -- --ignored real_video_probe`，
    /// 打印采样帧数/亮度/alpha 统计，并把首帧存成 real_frame.jpg 供目检
    /// —— 复现「视频正常但抽帧全黑」「抽帧斜纹」类问题用。
    #[test]
    #[ignore = "需要 VTT_TEST_VIDEO 指向真实切片"]
    fn real_video_probe() {
        let path = std::path::PathBuf::from(
            std::env::var("VTT_TEST_VIDEO").expect("set VTT_TEST_VIDEO to a video slice path"),
        );
        let out_dir = Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("target")
            .join("test-sessions")
            .join("decode");
        std::fs::create_dir_all(&out_dir).unwrap();
        let mut n = 0u64;
        let mut dark = 0u64;
        sample_video(&path, 500, |f| {
            // 只算 B/G/R（alpha 恒 255，会抬高均值掩盖黑帧）
            let (mut sum, mut cnt) = (0u64, 0u64);
            for px in f.bgra.chunks_exact(4) {
                sum += px[0] as u64 + px[1] as u64 + px[2] as u64;
                cnt += 3;
            }
            let mean = sum / cnt.max(1);
            if mean < 16 {
                dark += 1;
            }
            if n == 0 {
                // 首帧存图目检（斜纹/翻转/黑帧一眼可辨）
                let jpg = windows_capture::encoder::ImageEncoder::new(
                    windows_capture::encoder::ImageFormat::Jpeg,
                    windows_capture::encoder::ImageEncoderPixelFormat::Bgra8,
                )
                .and_then(|enc| enc.encode(&f.bgra, f.width, f.height))
                .expect("encode probe jpg");
                let out = out_dir.join("real_frame.jpg");
                std::fs::write(&out, jpg).expect("write probe jpg");
                println!("first frame saved: {}", out.display());
            }
            n += 1;
            if n <= 3 {
                println!("frame ts={}ms {}x{} mean_luma={mean}", f.ts_ms, f.width, f.height);
            }
        })
        .expect("sample real video");
        println!("sampled={n} dark_frames={dark}");
        assert!(n > 0, "no frames sampled");
    }
}
