use super::protocol::{
    WorkerEventKind, WorkerOperation, WorkerRequest, WorkerResponse, OFFICE_WORKER_LINE_MAX_BYTES,
    OFFICE_WORKER_PROTOCOL_VERSION,
};
use std::fmt::{Debug, Display, Formatter};
use std::io::{BufRead, BufReader, Read, Write};
use std::path::Path;
use std::process::{Child, ChildStdin, Command, Stdio};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{mpsc, Arc};
use std::thread;
use std::time::{Duration, Instant};
use uuid::Uuid;

const GRACEFUL_CANCEL_TIMEOUT: Duration = Duration::from_secs(10);
const DROP_SHUTDOWN_TIMEOUT: Duration = Duration::from_millis(250);

pub struct WorkerSession {
    child: Child,
    stdin: ChildStdin,
    events: mpsc::Receiver<Result<WorkerResponse, WorkerSessionError>>,
    stderr_line_count: Arc<AtomicU64>,
    ready: bool,
}

impl Debug for WorkerSession {
    fn fmt(&self, formatter: &mut Formatter<'_>) -> std::fmt::Result {
        formatter
            .debug_struct("WorkerSession")
            .field("pid", &self.child.id())
            .field("ready", &self.ready)
            .field("stderr_line_count", &self.stderr_line_count())
            .finish()
    }
}

impl WorkerSession {
    pub fn spawn(
        executable: &Path,
        handshake_timeout: Duration,
    ) -> Result<Self, WorkerSessionError> {
        let mut child = Command::new(executable)
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .spawn()
            .map_err(|_| WorkerSessionError::new(WorkerSessionErrorCode::SpawnFailed))?;

        let pipes = (child.stdin.take(), child.stdout.take(), child.stderr.take());
        let (Some(stdin), Some(stdout), Some(stderr)) = pipes else {
            let _ = child.kill();
            let _ = child.wait();
            return Err(WorkerSessionError::new(
                WorkerSessionErrorCode::PipeUnavailable,
            ));
        };

        let (sender, events) = mpsc::channel();
        thread::spawn(move || read_stdout(stdout, sender));

        let stderr_line_count = Arc::new(AtomicU64::new(0));
        let stderr_counter = Arc::clone(&stderr_line_count);
        thread::spawn(move || drain_stderr(stderr, stderr_counter));

        let mut session = Self {
            child,
            stdin,
            events,
            stderr_line_count,
            ready: false,
        };
        let request = WorkerRequest::control(new_request_id(), WorkerOperation::Handshake);
        let response = session.request(request, handshake_timeout)?;
        if response.event != WorkerEventKind::Ready
            || response.protocol_version != Some(OFFICE_WORKER_PROTOCOL_VERSION)
            || response.worker_pid != Some(session.child.id())
        {
            session.kill_owned_child();
            return Err(WorkerSessionError::new(
                WorkerSessionErrorCode::HandshakeRejected,
            ));
        }
        session.ready = true;
        Ok(session)
    }

    pub fn pid(&self) -> u32 {
        self.child.id()
    }

    pub fn stderr_line_count(&self) -> u64 {
        self.stderr_line_count.load(Ordering::Relaxed)
    }

    pub fn request(
        &mut self,
        request: WorkerRequest,
        timeout: Duration,
    ) -> Result<WorkerResponse, WorkerSessionError> {
        self.request_with_progress(request, timeout, |_| {})
    }

    pub fn request_with_progress<F>(
        &mut self,
        request: WorkerRequest,
        timeout: Duration,
        mut on_progress: F,
    ) -> Result<WorkerResponse, WorkerSessionError>
    where
        F: FnMut(&super::protocol::WorkerProgress),
    {
        let request_id = request.request_id.clone();
        self.send(&request)?;
        let deadline = Instant::now() + timeout;
        loop {
            let remaining = deadline.saturating_duration_since(Instant::now());
            if remaining.is_zero() {
                return Err(WorkerSessionError::new(WorkerSessionErrorCode::Timeout));
            }
            let response = self
                .events
                .recv_timeout(remaining)
                .map_err(|error| match error {
                    mpsc::RecvTimeoutError::Timeout => {
                        WorkerSessionError::new(WorkerSessionErrorCode::Timeout)
                    }
                    mpsc::RecvTimeoutError::Disconnected => {
                        WorkerSessionError::new(WorkerSessionErrorCode::WorkerExited)
                    }
                })??;
            if response.request_id.as_deref() == Some(request_id.as_str())
                && response.event == WorkerEventKind::Progress
            {
                if let Some(progress) = response.progress.as_ref() {
                    on_progress(progress);
                }
                continue;
            }
            if response.request_id.as_deref() == Some(request_id.as_str()) {
                return Ok(response);
            }
        }
    }

    pub fn heartbeat(&mut self, timeout: Duration) -> Result<(), WorkerSessionError> {
        let request = WorkerRequest::control(new_request_id(), WorkerOperation::Heartbeat);
        let result = self.request(request, timeout).and_then(|response| {
            if response.event == WorkerEventKind::Heartbeat {
                Ok(())
            } else {
                Err(WorkerSessionError::new(
                    WorkerSessionErrorCode::UnexpectedEvent,
                ))
            }
        });
        if let Err(error) = result {
            let _ = self.graceful_shutdown(GRACEFUL_CANCEL_TIMEOUT);
            return Err(error);
        }
        Ok(())
    }

    pub fn cancel(&mut self, target_request_id: String) -> Result<(), WorkerSessionError> {
        let mut request = WorkerRequest::control(new_request_id(), WorkerOperation::Cancel);
        request.target_request_id = Some(target_request_id);
        match self.request(request, GRACEFUL_CANCEL_TIMEOUT) {
            Ok(response) if response.event == WorkerEventKind::Cancelled => Ok(()),
            Ok(_) => {
                self.kill_owned_child();
                Err(WorkerSessionError::new(
                    WorkerSessionErrorCode::UnexpectedEvent,
                ))
            }
            Err(error) => {
                self.kill_owned_child();
                Err(error)
            }
        }
    }

    pub fn shutdown(mut self, timeout: Duration) -> Result<(), WorkerSessionError> {
        self.graceful_shutdown(timeout)
    }

    fn send(&mut self, request: &WorkerRequest) -> Result<(), WorkerSessionError> {
        serde_json::to_writer(&mut self.stdin, request)
            .map_err(|_| WorkerSessionError::new(WorkerSessionErrorCode::RequestEncodeFailed))?;
        self.stdin
            .write_all(b"\n")
            .and_then(|_| self.stdin.flush())
            .map_err(|_| WorkerSessionError::new(WorkerSessionErrorCode::WorkerExited))
    }

    fn graceful_shutdown(&mut self, timeout: Duration) -> Result<(), WorkerSessionError> {
        if self.child.try_wait().ok().flatten().is_some() {
            return Ok(());
        }
        let request = WorkerRequest::control(new_request_id(), WorkerOperation::Shutdown);
        let result = self.request(request, timeout);
        match result {
            Ok(response) if response.event == WorkerEventKind::ShuttingDown => {
                let deadline = Instant::now() + timeout;
                while Instant::now() < deadline {
                    if self.child.try_wait().ok().flatten().is_some() {
                        return Ok(());
                    }
                    thread::sleep(Duration::from_millis(10));
                }
                self.kill_owned_child();
                Err(WorkerSessionError::new(WorkerSessionErrorCode::Timeout))
            }
            Ok(_) => {
                self.kill_owned_child();
                Err(WorkerSessionError::new(
                    WorkerSessionErrorCode::UnexpectedEvent,
                ))
            }
            Err(error) => {
                self.kill_owned_child();
                Err(error)
            }
        }
    }

    fn kill_owned_child(&mut self) {
        if self.child.try_wait().ok().flatten().is_none() {
            let _ = self.child.kill();
        }
        let _ = self.child.wait();
    }
}

impl Drop for WorkerSession {
    fn drop(&mut self) {
        let _ = self.graceful_shutdown(DROP_SHUTDOWN_TIMEOUT);
    }
}

fn new_request_id() -> String {
    Uuid::new_v4().to_string()
}

fn read_stdout(
    stdout: impl std::io::Read,
    sender: mpsc::Sender<Result<WorkerResponse, WorkerSessionError>>,
) {
    let mut reader = BufReader::new(stdout);
    loop {
        match read_bounded_line(&mut reader, OFFICE_WORKER_LINE_MAX_BYTES) {
            Ok(Some(line)) => {
                let response = serde_json::from_slice::<WorkerResponse>(&line)
                    .map_err(|_| WorkerSessionError::new(WorkerSessionErrorCode::MalformedResponse))
                    .and_then(validate_response);
                if sender.send(response).is_err() {
                    break;
                }
            }
            Ok(None) => break,
            Err(error) => {
                let _ = sender.send(Err(error));
                break;
            }
        }
    }
}

fn read_bounded_line<R: BufRead>(
    reader: &mut R,
    max_bytes: usize,
) -> Result<Option<Vec<u8>>, WorkerSessionError> {
    let mut line = Vec::new();
    let mut saw_bytes = false;
    loop {
        let buffer = reader
            .fill_buf()
            .map_err(|_| WorkerSessionError::new(WorkerSessionErrorCode::WorkerExited))?;
        if buffer.is_empty() {
            return if saw_bytes { Ok(Some(line)) } else { Ok(None) };
        }
        saw_bytes = true;
        let newline = buffer.iter().position(|byte| *byte == b'\n');
        let content_len = newline.unwrap_or(buffer.len());
        if content_len > max_bytes.saturating_sub(line.len()) {
            return Err(WorkerSessionError::new(
                WorkerSessionErrorCode::ResponseTooLarge,
            ));
        }
        line.extend_from_slice(&buffer[..content_len]);
        let consumed = newline.map_or(buffer.len(), |index| index + 1);
        reader.consume(consumed);
        if newline.is_some() {
            if line.last() == Some(&b'\r') {
                line.pop();
            }
            return Ok(Some(line));
        }
    }
}

fn validate_response(response: WorkerResponse) -> Result<WorkerResponse, WorkerSessionError> {
    let valid_identifier = |value: &str| {
        !value.is_empty()
            && value.len() <= 64
            && value
                .bytes()
                .all(|byte| byte.is_ascii_alphanumeric() || byte == b'-' || byte == b'_')
    };
    let valid_code = |value: &str| {
        value.starts_with("OFFICE_")
            && value.len() <= 128
            && value
                .bytes()
                .all(|byte| byte.is_ascii_uppercase() || byte.is_ascii_digit() || byte == b'_')
    };

    if response
        .request_id
        .as_deref()
        .is_some_and(|value| !valid_identifier(value))
        || response
            .task_id
            .as_deref()
            .is_some_and(|value| !valid_identifier(value))
        || response
            .error_code
            .as_deref()
            .is_some_and(|value| !valid_code(value))
        || response.risk_codes.iter().any(|value| !valid_code(value))
        || response.classification.as_deref().is_some_and(|value| {
            !matches!(value, "SAFE_OOXML" | "HIGH_FIDELITY_REQUIRED" | "BLOCKED")
        })
        || response.source_sha256.as_deref().is_some_and(|value| {
            value.len() != 64 || !value.bytes().all(|byte| byte.is_ascii_hexdigit())
        })
        || response
            .progress
            .as_ref()
            .is_some_and(|progress| progress.completed > progress.total)
    {
        return Err(WorkerSessionError::new(
            WorkerSessionErrorCode::MalformedResponse,
        ));
    }
    Ok(response)
}

fn drain_stderr(mut stderr: impl Read, line_count: Arc<AtomicU64>) {
    let mut buffer = [0_u8; 8 * 1024];
    let mut pending_line = false;
    loop {
        match stderr.read(&mut buffer) {
            Ok(0) => {
                if pending_line {
                    line_count.fetch_add(1, Ordering::Relaxed);
                }
                break;
            }
            Ok(count) => {
                for byte in &buffer[..count] {
                    if *byte == b'\n' {
                        line_count.fetch_add(1, Ordering::Relaxed);
                        pending_line = false;
                    } else {
                        pending_line = true;
                    }
                }
            }
            Err(_) => break,
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum WorkerSessionErrorCode {
    SpawnFailed,
    PipeUnavailable,
    HandshakeRejected,
    RequestEncodeFailed,
    MalformedResponse,
    ResponseTooLarge,
    Timeout,
    WorkerExited,
    UnexpectedEvent,
}

impl WorkerSessionErrorCode {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::SpawnFailed => "OFFICE_WORKER_SPAWN_FAILED",
            Self::PipeUnavailable => "OFFICE_WORKER_PIPE_UNAVAILABLE",
            Self::HandshakeRejected => "OFFICE_WORKER_HANDSHAKE_REJECTED",
            Self::RequestEncodeFailed => "OFFICE_WORKER_REQUEST_ENCODE_FAILED",
            Self::MalformedResponse => "OFFICE_WORKER_MALFORMED_RESPONSE",
            Self::ResponseTooLarge => "OFFICE_WORKER_RESPONSE_TOO_LARGE",
            Self::Timeout => "OFFICE_WORKER_TIMEOUT",
            Self::WorkerExited => "OFFICE_WORKER_EXITED",
            Self::UnexpectedEvent => "OFFICE_WORKER_UNEXPECTED_EVENT",
        }
    }
}

#[derive(Clone, Copy, PartialEq, Eq)]
pub struct WorkerSessionError {
    code: WorkerSessionErrorCode,
}

impl WorkerSessionError {
    fn new(code: WorkerSessionErrorCode) -> Self {
        Self { code }
    }

    pub fn code(self) -> WorkerSessionErrorCode {
        self.code
    }
}

impl Debug for WorkerSessionError {
    fn fmt(&self, formatter: &mut Formatter<'_>) -> std::fmt::Result {
        formatter
            .debug_struct("WorkerSessionError")
            .field("code", &self.code.as_str())
            .finish()
    }
}

impl Display for WorkerSessionError {
    fn fmt(&self, formatter: &mut Formatter<'_>) -> std::fmt::Result {
        formatter.write_str(self.code.as_str())
    }
}

impl std::error::Error for WorkerSessionError {}
