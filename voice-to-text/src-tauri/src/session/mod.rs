//! Session management + wall-clock zero point for 网课录屏总结 (plan Step 1).
//!
//! Each class session lives in its own directory under `sessions/<id>/`:
//!   video/  audio.wav  transcript.jsonl  frames/  exports/
//! A shared [`SessionClock`] gives the screen / audio / transcript pipelines
//! one monotonic time axis (ms since session start) so they can be aligned.
//!
//! FR-103 (分轨落盘的目录与时间基准).  traceId = session id (运维 O1).

use serde::{Deserialize, Serialize};
use std::fs;
use std::path::{Path, PathBuf};
use std::time::Instant;

/// Session lifecycle states. Persisted to `.status` so they survive restarts.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum SessionState {
    Idle,
    Recording,
    Processing,
    Done,
}

impl SessionState {
    fn as_str(self) -> &'static str {
        match self {
            SessionState::Idle => "idle",
            SessionState::Recording => "recording",
            SessionState::Processing => "processing",
            SessionState::Done => "done",
        }
    }

    fn parse(s: &str) -> Self {
        match s.trim() {
            "recording" => SessionState::Recording,
            "processing" => SessionState::Processing,
            "done" => SessionState::Done,
            _ => SessionState::Idle,
        }
    }
}

/// Serializable session descriptor returned to the frontend over IPC.
#[derive(Debug, Clone, Serialize)]
pub struct SessionInfo {
    pub id: String,
    pub path: String,
    pub state: SessionState,
}

/// Wall-clock relative to session start.
///
/// Uses [`std::time::Instant`], which on Windows wraps `QueryPerformanceCounter`
/// — the same high-resolution monotonic clock the plan asks for — but without a
/// `windows` Cargo feature and portable to macOS/Linux. `Instant` is `Copy`, so
/// the clock is cloned cheaply and all three pipelines share one time axis.
///
/// (Deviation from plan: plan said raw QPC; Instant is the idiomatic QPC wrapper.
/// See 开发进度 / 偏离计划.)
#[derive(Clone, Default)]
pub struct SessionClock {
    t0: Option<Instant>,
}

impl SessionClock {
    pub fn new() -> Self {
        Self { t0: Some(Instant::now()) }
    }

    /// Milliseconds since the clock started. Monotonically non-decreasing.
    /// Returns 0 before [`SessionClock::start`] is called.
    pub fn now_ms(&self) -> i64 {
        match self.t0 {
            Some(t0) => t0.elapsed().as_millis() as i64,
            None => 0,
        }
    }

    /// (Re)start the clock at this moment.
    pub fn start(&mut self) {
        self.t0 = Some(Instant::now());
    }
}

/// Owns the `sessions/` root and creates / queries session directories.
/// Holds no mutable in-memory state — all state lives on disk, so it is
/// `Send + Sync` without a `Mutex`.
pub struct SessionManager {
    base_dir: PathBuf,
}

impl SessionManager {
    pub fn new(base_dir: PathBuf) -> Self {
        Self { base_dir }
    }

    /// Resolve `sessions/` next to the executable (production) or cwd (dev).
    /// Never hard-codes an absolute path (AGENTS.md red line).
    pub fn default_base_dir() -> PathBuf {
        std::env::current_exe()
            .ok()
            .and_then(|p| p.parent().map(|d| d.join("sessions")))
            .or_else(|| std::env::current_dir().ok().map(|cwd| cwd.join("sessions")))
            .unwrap_or_else(|| PathBuf::from("sessions"))
    }

    /// 区域框选 overlay 的屏幕快照路径（sessions 根目录下，asset:// scope 已覆盖）。
    pub fn region_shot_path(&self) -> PathBuf {
        self.base_dir.join(".region_shot.png")
    }

    /// Create a new session directory tree. The id is a local timestamp
    /// generated internally — never taken from caller input (path-traversal guard).
    pub fn create_session(&self) -> std::io::Result<SessionInfo> {
        let now = chrono::Local::now();
        let id = format!(
            "{}_{:03}",
            now.format("%Y%m%d_%H%M%S"),
            now.timestamp_subsec_millis()
        );
        self.validate_id(&id)?;
        let dir = self.base_dir.join(&id);
        fs::create_dir_all(dir.join("video"))?;
        fs::create_dir_all(dir.join("frames"))?;
        fs::create_dir_all(dir.join("exports"))?;
        fs::write(dir.join(".status"), SessionState::Idle.as_str())?;
        log::info!("[session] created {} at {}", id, dir.display());
        Ok(SessionInfo {
            id,
            path: dir.to_string_lossy().into_owned(),
            state: SessionState::Idle,
        })
    }

    /// Enumerate existing session directories.
    pub fn list_sessions(&self) -> std::io::Result<Vec<SessionInfo>> {
        if !self.base_dir.exists() {
            return Ok(Vec::new());
        }
        let mut out = Vec::new();
        for entry in fs::read_dir(&self.base_dir)? {
            let path = entry?.path();
            if !path.is_dir() {
                continue;
            }
            let Some(id) = path.file_name().and_then(|n| n.to_str()) else {
                continue;
            };
            if self.validate_id(id).is_err() {
                continue; // skip non-session dirs (e.g. stray files)
            }
            let state = self.read_status(&path).unwrap_or(SessionState::Idle);
            out.push(SessionInfo {
                id: id.to_string(),
                path: path.to_string_lossy().into_owned(),
                state,
            });
        }
        Ok(out)
    }

    /// Read a session's persisted state.
    /// NotFound if the session directory doesn't exist; Idle if it exists but
    /// has no `.status` (e.g. a hand-created session) — distinguishes the two.
    pub fn get_session_status(&self, id: &str) -> std::io::Result<SessionState> {
        self.validate_id(id)?;
        let dir = self.base_dir.join(id);
        if !dir.exists() {
            return Err(std::io::Error::new(std::io::ErrorKind::NotFound, "session not found"));
        }
        self.read_status(&dir)
    }

    /// Resolve an existing session's directory (validated against path
    /// traversal first). Used by recording start/stop to locate
    /// video/ audio.wav transcript.jsonl (plan Step 4 / FR-103).
    pub fn session_dir(&self, id: &str) -> std::io::Result<PathBuf> {
        self.validate_id(id)?;
        let dir = self.base_dir.join(id);
        if !dir.is_dir() {
            return Err(std::io::Error::new(std::io::ErrorKind::NotFound, "session not found"));
        }
        Ok(dir)
    }

    /// Persist a session state. Used by later steps when entering
    /// Recording / Processing / Done (FR-109 lifecycle, 联动 L1).
    pub fn set_state(&self, id: &str, state: SessionState) -> std::io::Result<()> {
        self.validate_id(id)?;
        let dir = self.base_dir.join(id);
        if !dir.exists() {
            return Err(std::io::Error::new(std::io::ErrorKind::NotFound, "session not found"));
        }
        fs::write(dir.join(".status"), state.as_str())?;
        Ok(())
    }

    fn read_status(&self, dir: &Path) -> std::io::Result<SessionState> {
        match fs::read_to_string(dir.join(".status")) {
            Ok(s) => Ok(SessionState::parse(&s)),
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(SessionState::Idle),
            Err(e) => Err(e),
        }
    }

    /// Reject anything that isn't a `YYYYMMDD_HHMMSS_mmm`-style id, blocking
    /// `..`, slashes, and absolute paths from reaching the filesystem.
    fn validate_id(&self, id: &str) -> std::io::Result<()> {
        let ok = !id.is_empty()
            && id.bytes().all(|b| b.is_ascii_digit() || b == b'_')
            && !id.contains("__");
        if ok {
            Ok(())
        } else {
            Err(std::io::Error::new(
                std::io::ErrorKind::InvalidInput,
                "invalid session id",
            ))
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Arc;

    /// Tests need a writable base dir on a non-full drive. C: is full on this
    /// machine, so anchor under the crate target dir (on D:).
    fn unique_base(label: &str) -> PathBuf {
        let base = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
            .join("target")
            .join("test-sessions")
            .join(label);
        let _ = fs::remove_dir_all(&base);
        fs::create_dir_all(&base).unwrap();
        base
    }

    #[test]
    fn create_session_makes_full_tree() {
        let mgr = SessionManager::new(unique_base("tree"));
        let info = mgr.create_session().unwrap();
        let dir = PathBuf::from(&info.path);
        assert!(dir.join("video").is_dir(), "video/ missing");
        assert!(dir.join("frames").is_dir(), "frames/ missing");
        assert!(dir.join("exports").is_dir(), "exports/ missing");
        assert!(dir.join(".status").is_file(), ".status missing");
        assert_eq!(info.state, SessionState::Idle);
    }

    #[test]
    fn now_ms_is_monotonic_non_decreasing() {
        let clock = SessionClock::new();
        let a = clock.now_ms();
        // busy-spin so at least 1ms elapses on most clocks
        let mut busy = 0u64;
        for _ in 0..200_000 {
            busy = busy.wrapping_add(1);
        }
        let b = clock.now_ms();
        assert!(b >= a, "now_ms must be non-decreasing ({} >= {})", b, a);
        let _ = busy;
    }

    #[test]
    fn now_ms_thread_safe_under_concurrency() {
        let clock = Arc::new(SessionClock::new());
        let handles: Vec<_> = (0..4)
            .map(|_| {
                let c = clock.clone();
                std::thread::spawn(move || {
                    let mut prev = -1i64;
                    for _ in 0..200 {
                        let t = c.now_ms();
                        assert!(t >= prev, "regression under concurrency: {} < {}", t, prev);
                        prev = t;
                    }
                })
            })
            .collect();
        for h in handles {
            h.join().unwrap();
        }
    }

    #[test]
    fn list_sessions_returns_created() {
        let mgr = SessionManager::new(unique_base("list"));
        let a = mgr.create_session().unwrap();
        let b = mgr.create_session().unwrap();
        let mut ids: Vec<_> = mgr.list_sessions().unwrap().into_iter().map(|s| s.id).collect();
        let mut want = vec![a.id, b.id];
        ids.sort();
        want.sort();
        assert_eq!(ids, want);
    }

    #[test]
    fn set_and_get_state_roundtrip() {
        let mgr = SessionManager::new(unique_base("state"));
        let s = mgr.create_session().unwrap();
        mgr.set_state(&s.id, SessionState::Recording).unwrap();
        assert_eq!(mgr.get_session_status(&s.id).unwrap(), SessionState::Recording);
        mgr.set_state(&s.id, SessionState::Done).unwrap();
        assert_eq!(mgr.get_session_status(&s.id).unwrap(), SessionState::Done);
    }

    #[test]
    fn path_traversal_ids_rejected() {
        let mgr = SessionManager::new(unique_base("traversal"));
        for bad in ["..", "../etc", "a/b", "id\x00", "..\\windows", "ok-but-slash/", "name with space"] {
            assert!(mgr.get_session_status(bad).is_err(), "should reject id {:?}", bad);
        }
        // a valid-looking id that simply doesn't exist yet -> NotFound (not InvalidInput)
        let err = mgr.get_session_status("20240101_120000_000").unwrap_err();
        assert_eq!(err.kind(), std::io::ErrorKind::NotFound);
    }

    /// FR-103: 录制命令靠 session_dir 定位三路落盘目录。
    #[test]
    fn session_dir_resolves_existing_only() {
        let mgr = SessionManager::new(unique_base("dir"));
        let s = mgr.create_session().unwrap();
        let dir = mgr.session_dir(&s.id).unwrap();
        assert!(dir.join("video").is_dir(), "video/ must exist under session dir");
        assert!(mgr.session_dir("..\\..").is_err(), "traversal rejected");
        let err = mgr.session_dir("20240101_120000_000").unwrap_err();
        assert_eq!(err.kind(), std::io::ErrorKind::NotFound);
    }
}
