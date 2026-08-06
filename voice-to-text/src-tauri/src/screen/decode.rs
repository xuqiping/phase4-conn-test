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
    use windows::core::HSTRING;
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

            let buffer = sample
                .ConvertToContiguousBuffer()
                .map_err(|e| format!("contiguous buffer: {e}"))?;
            let bgra = copy_frame(&buffer, width, height)?;
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

    /// Copy an RGB32 media buffer into a tightly packed top-down BGRA vec.
    /// MF 对未压缩 RGB32 的默认约定是 **bottom-up**（buffer 第 0 行 = 画面
    /// 底行），所以拷贝时翻成 top-down（合成视频回环测试的顶部亮带断言
    /// 验证了这一点）。Uses the 1D `IMFMediaBuffer::Lock` —
    /// `IMFMediaBuffer2`/Lock2D isn't in the windows 0.61 bindings.
    unsafe fn copy_frame(buffer: &IMFMediaBuffer, width: u32, height: u32) -> Result<Vec<u8>, String> {
        let mut ptr: *mut u8 = std::ptr::null_mut();
        let mut cur_len: u32 = 0;
        buffer
            .Lock(&mut ptr, None, Some(&mut cur_len))
            .map_err(|e| format!("lock: {e}"))?;
        let result = (|| {
            let row_bytes = width as usize * 4;
            let need = row_bytes * height as usize;
            if ptr.is_null() || (cur_len as usize) < need {
                return Err(format!("buffer too small: {cur_len} < {need}"));
            }
            let mut out = vec![0u8; need];
            for row in 0..height as usize {
                // bottom-up source → top-down destination
                let src = ptr.add((height as usize - 1 - row) * row_bytes);
                let dst = &mut out[row * row_bytes..(row + 1) * row_bytes];
                std::ptr::copy_nonoverlapping(src, dst.as_mut_ptr(), row_bytes);
            }
            Ok(out)
        })();
        let _ = buffer.Unlock();
        result
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

    /// AC-104 前置：切片可读回、采样间隔生效、时间戳单调、几何正确。
    #[test]
    fn sample_video_roundtrip() {
        let dir = Path::new(env!("CARGO_MANIFEST_DIR"))
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
        }
        for w in frames.windows(2) {
            assert!(w[1].ts_ms > w[0].ts_ms, "timestamps must increase");
        }
        // 前半暗页、后半亮页可区分（编码有损，给足余量）
        let mean = |f: &SampledFrame| f.bgra.iter().map(|&b| b as u64).sum::<u64>() / f.bgra.len() as u64;
        assert!(mean(frames.first().unwrap()) < 80, "page A should be dark");
        assert!(mean(frames.last().unwrap()) > 160, "page B should be bright");
        // 方向标记：顶部 16 行必须显著亮于底部（H.264 有损会压暗亮带，
        // 用相对差而非绝对阈值）。行序翻错时亮带沉底、此断言反转。
        let band_mean = |f: &SampledFrame, from_row: usize| {
            let rows = &f.bgra[from_row * 320 * 4..(from_row + 16) * 320 * 4];
            rows.iter().map(|&b| b as u64).sum::<u64>() / rows.len() as u64
        };
        let first = frames.first().unwrap();
        assert!(
            band_mean(first, 0) > band_mean(first, 164) + 50,
            "top band lost — row order flipped? top={} bottom={}",
            band_mean(first, 0),
            band_mean(first, 164)
        );
    }
}
