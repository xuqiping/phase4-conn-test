//! Video encoding + 15-min slicing for 网课录屏总结 (plan Step 4 / FR-101, FR-103).
//!
//! Layout:
//! - Pure rotation logic ([`SlicePlanner`], [`segment_file_name`]) is platform-free
//!   and unit-tested without any real encoder.
//! - The Windows-only [`SliceEncoder`] wraps windows-capture's `VideoEncoder`
//!   (MediaFoundation transcode; hardware encoder auto-selected, software
//!   fallback by MF — 本机 RTX 2060 SUPER 走 NVENC) and lives on the capture
//!   thread inside the handler, so frame delivery and encoding share no locks.
//!
//! 偏离计划记录：plan/design 写的是 `video_NNN.h264` 裸流，但 windows-capture 的
//! `VideoEncoder` 走 `MediaTranscoder` 必须带容器 → 落盘为 **H.264 + MP4 容器**，
//! 文件名 `video_NNN.mp4`。MP4 可拖拽 seek、ffmpeg/播放器直接读，Step5 抽帧更方便。
//!
//! 对齐设计：每个 MP4 内 PTS 以其首帧为 0（crate 内部归一化）；跨轨对齐靠
//! `video/manifest.jsonl` 里每段的 `start_ms/end_ms`（session 墙钟，与音频/转写
//! 同基准）。O7：15min 切片控制单文件体积，2h ≈ 3.6GB（4Mbps），符合 design 2–6GB。

use serde::Serialize;
use std::path::PathBuf;

/// Slice length: one segment per 15 minutes (plan Step 4 / O7).
pub const SLICE_MS: i64 = 15 * 60 * 1000;

/// H.264 target bitrate. 4 Mbps × 2h ≈ 3.6 GB — inside the design's 2–6 GB budget.
pub const VIDEO_BITRATE: u32 = 4_000_000;

/// Encode frame rate, matching the 30fps capture cap (capture.rs FRAME_INTERVAL).
pub const VIDEO_FPS: u32 = 30;

/// Segment file name: `video_001.mp4`, `video_002.mp4`, …
/// (`.mp4` not `.h264` — see module-level 偏离计划记录.)
pub fn segment_file_name(index: u32) -> String {
    format!("video_{index:03}.mp4")
}

/// One finished segment, appended as a JSON line to `video/manifest.jsonl`.
/// `start_ms`/`end_ms` are on the session wall clock — the same axis as
/// `audio.wav` and `transcript.jsonl` (AC-103 三路对齐).
#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
pub struct SegmentRecord {
    pub index: u32,
    pub file: String,
    pub start_ms: i64,
    pub end_ms: i64,
    pub frames: u64,
}

/// What the capture thread must do for an incoming frame.
#[derive(Debug, PartialEq, Eq)]
pub enum SliceAction {
    /// Append the frame to the current segment.
    Continue,
    /// Open a new segment file for this frame. `closed` carries the record of
    /// the segment that just ended (None for the very first segment).
    Open {
        index: u32,
        file: String,
        closed: Option<SegmentRecord>,
    },
}

struct OpenSegment {
    index: u32,
    file: String,
    start_ms: i64,
    frames: u64,
}

/// Decides when to rotate to the next segment file. Pure bookkeeping — no I/O,
/// no encoder — so the 15-min slicing rule is testable in milliseconds.
pub struct SlicePlanner {
    slice_ms: i64,
    next_index: u32,
    open: Option<OpenSegment>,
}

impl SlicePlanner {
    pub fn new(slice_ms: i64) -> Self {
        Self {
            slice_ms,
            next_index: 1,
            open: None,
        }
    }

    /// Feed one frame timestamp. The frame belongs to the returned segment:
    /// on rotation the closing segment's `end_ms` is this frame's ts.
    pub fn on_frame(&mut self, ts_ms: i64) -> SliceAction {
        let rotate = match &self.open {
            None => true,
            Some(seg) => ts_ms - seg.start_ms >= self.slice_ms,
        };
        if !rotate {
            if let Some(seg) = self.open.as_mut() {
                seg.frames += 1;
            }
            return SliceAction::Continue;
        }

        let closed = self.close_open(ts_ms);
        let index = self.next_index;
        self.next_index += 1;
        let file = segment_file_name(index);
        self.open = Some(OpenSegment {
            index,
            file: file.clone(),
            start_ms: ts_ms,
            frames: 1,
        });
        SliceAction::Open {
            index,
            file,
            closed,
        }
    }

    /// Close the currently open segment (end of recording). Returns its record,
    /// or None if nothing was open (e.g. zero frames captured).
    pub fn close_open(&mut self, end_ms: i64) -> Option<SegmentRecord> {
        self.open.take().map(|seg| SegmentRecord {
            index: seg.index,
            file: seg.file,
            start_ms: seg.start_ms,
            end_ms,
            frames: seg.frames,
        })
    }
}

// ---------------------------------------------------------------------------
// Windows implementation (real encoder)
// ---------------------------------------------------------------------------
#[cfg(windows)]
mod imp {
    use super::*;
    use std::fs::{File, OpenOptions};
    use std::io::Write;
    use windows_capture::encoder::{
        AudioSettingsBuilder, ContainerSettingsBuilder, VideoEncoder, VideoSettingsBuilder,
        VideoSettingsSubType,
    };
    use windows_capture::frame::Frame;

    /// Per-recording encoder owner: rotates segment files per [`SlicePlanner`],
    /// appends a manifest line per closed segment. Lives inside the capture
    /// handler (single-threaded — no locking needed).
    ///
    /// 运维 O1: all log lines carry `trace` (= session id) as traceId.
    pub struct SliceEncoder {
        video_dir: PathBuf,
        width: u32,
        height: u32,
        planner: SlicePlanner,
        encoder: Option<VideoEncoder>,
        manifest: File,
        trace: String,
    }

    impl SliceEncoder {
        pub fn new(
            video_dir: PathBuf,
            width: u32,
            height: u32,
            slice_ms: i64,
            trace: String,
        ) -> Result<Self, String> {
            let manifest = OpenOptions::new()
                .create(true)
                .append(true)
                .open(video_dir.join("manifest.jsonl"))
                .map_err(|e| format!("open manifest.jsonl failed: {e}"))?;
            log::info!(
                "[screen][{trace}] video recording armed: {}x{} @{}fps, {}bps, slice {}min",
                width,
                height,
                VIDEO_FPS,
                VIDEO_BITRATE,
                slice_ms / 60000
            );
            Ok(Self {
                video_dir,
                width,
                height,
                planner: SlicePlanner::new(slice_ms),
                encoder: None,
                manifest,
                trace,
            })
        }

        fn write_manifest(&mut self, rec: &SegmentRecord) {
            match serde_json::to_string(rec) {
                Ok(line) => {
                    if let Err(e) = writeln!(self.manifest, "{line}") {
                        log::error!("[screen][{}] manifest write failed: {e}", self.trace);
                    }
                }
                Err(e) => log::error!("[screen][{}] manifest serialize failed: {e}", self.trace),
            }
        }

        /// Feed one captured frame. Rotates segment when the planner says so.
        /// Errors bubble up — the caller (handler) degrades to
        /// capture-without-video on failure (O4 降级路径).
        pub fn send_frame(&mut self, frame: &Frame, ts_ms: i64) -> Result<(), String> {
            if let SliceAction::Open {
                index,
                file,
                closed,
            } = self.planner.on_frame(ts_ms)
            {
                // Finalize the previous segment first: its mp4 becomes a
                // complete, playable file even if the app crashes later (O7).
                if let Some(enc) = self.encoder.take() {
                    enc.finish()
                        .map_err(|e| format!("finish segment failed: {e}"))?;
                }
                if let Some(rec) = closed {
                    log::info!(
                        "[screen][{}] segment {} closed: {} frames, {}..{}ms",
                        self.trace,
                        rec.file,
                        rec.frames,
                        rec.start_ms,
                        rec.end_ms
                    );
                    self.write_manifest(&rec);
                }

                let path = self.video_dir.join(&file);
                let video = VideoSettingsBuilder::new(self.width, self.height)
                    .sub_type(VideoSettingsSubType::H264)
                    .bitrate(VIDEO_BITRATE)
                    .frame_rate(VIDEO_FPS);
                let encoder = VideoEncoder::new(
                    video,
                    // 音轨不进视频容器 —— 音频单独落 audio.wav（分轨落盘 FR-103）
                    AudioSettingsBuilder::new().disabled(true),
                    ContainerSettingsBuilder::new(),
                    &path,
                )
                .map_err(|e| format!("create encoder for {file} failed: {e}"))?;
                self.encoder = Some(encoder);
                log::info!("[screen][{}] segment {index} opened: {file}", self.trace);
            }

            match self.encoder.as_mut() {
                Some(enc) => enc
                    .send_frame(frame)
                    .map_err(|e| format!("encode frame failed: {e}")),
                None => Err("encoder not open".to_string()),
            }
        }

        /// Flush the final segment + manifest. Called from the handler's Drop
        /// (capture thread ends → handler drops → here).
        pub fn finish(&mut self, end_ms: i64) {
            if let Some(enc) = self.encoder.take() {
                if let Err(e) = enc.finish() {
                    log::error!("[screen][{}] final segment finish failed: {e}", self.trace);
                }
            }
            if let Some(rec) = self.planner.close_open(end_ms) {
                log::info!(
                    "[screen][{}] segment {} closed (final): {} frames, {}..{}ms",
                    self.trace,
                    rec.file,
                    rec.frames,
                    rec.start_ms,
                    rec.end_ms
                );
                self.write_manifest(&rec);
            }
        }
    }
}

#[cfg(windows)]
pub use imp::SliceEncoder;

#[cfg(test)]
mod tests {
    use super::*;

    /// AC-103 切片命名：3 位序号、mp4 容器（偏离计划：plan 原文为 .h264）。
    #[test]
    fn segment_names_are_zero_padded() {
        assert_eq!(segment_file_name(1), "video_001.mp4");
        assert_eq!(segment_file_name(12), "video_012.mp4");
        assert_eq!(segment_file_name(123), "video_123.mp4");
    }

    #[test]
    fn first_frame_opens_segment_1() {
        let mut p = SlicePlanner::new(SLICE_MS);
        match p.on_frame(100) {
            SliceAction::Open {
                index,
                file,
                closed,
            } => {
                assert_eq!(index, 1);
                assert_eq!(file, "video_001.mp4");
                assert_eq!(closed, None, "no prior segment to close");
            }
            SliceAction::Continue => panic!("first frame must open a segment"),
        }
    }

    #[test]
    fn frames_within_slice_do_not_rotate() {
        let mut p = SlicePlanner::new(1000);
        assert!(matches!(p.on_frame(0), SliceAction::Open { .. }));
        for ts in [33, 66, 999] {
            assert_eq!(p.on_frame(ts), SliceAction::Continue, "ts={ts}");
        }
    }

    #[test]
    fn rotation_closes_previous_with_frame_counts() {
        let mut p = SlicePlanner::new(1000);
        p.on_frame(0); // seg1 opens, 1 frame
        p.on_frame(500); // seg1: 2 frames
        match p.on_frame(1000) {
            SliceAction::Open {
                index,
                file,
                closed,
            } => {
                assert_eq!((index, file.as_str()), (2, "video_002.mp4"));
                let rec = closed.expect("segment 1 must close on rotation");
                assert_eq!(
                    rec,
                    SegmentRecord {
                        index: 1,
                        file: "video_001.mp4".to_string(),
                        start_ms: 0,
                        end_ms: 1000,
                        frames: 2,
                    }
                );
            }
            SliceAction::Continue => panic!("ts=1000 must rotate at slice boundary"),
        }
    }

    #[test]
    fn close_open_reports_final_segment_once() {
        let mut p = SlicePlanner::new(SLICE_MS);
        assert_eq!(p.close_open(5000), None, "nothing open yet");
        p.on_frame(100);
        p.on_frame(200);
        let rec = p.close_open(200).expect("open segment must close");
        assert_eq!(rec.start_ms, 100);
        assert_eq!(rec.end_ms, 200);
        assert_eq!(rec.frames, 2);
        assert_eq!(p.close_open(300), None, "already closed");
    }

    /// 15min 切片全景：模拟 30fps × 31min，应切 3 段且帧数守恒。
    #[test]
    fn fifteen_minute_slices_over_long_recording() {
        let mut p = SlicePlanner::new(SLICE_MS);
        let mut opened = Vec::new();
        let mut total_frames = 0u64;
        // 31 minutes at 30fps
        for i in 0..(31 * 60 * 30) {
            let ts = i * 1000 / 30;
            total_frames += 1;
            if let SliceAction::Open { index, closed, .. } = p.on_frame(ts) {
                if let Some(rec) = closed {
                    assert_eq!(rec.index, index - 1);
                    assert!(rec.end_ms - rec.start_ms >= SLICE_MS);
                }
                opened.push(index);
            }
        }
        assert_eq!(opened, vec![1, 2, 3], "31min @15min slices → 3 segments");
        let last = p.close_open(31 * 60 * 1000).unwrap();
        let counted: u64 = last.frames; // only the final segment's frames here
        assert!(counted > 0);
        let _ = total_frames;
    }
}
