// 命令执行器：异步起进程、捕获 stdout/stderr、timeout、杀进程树、返回结构化结果。
// 对应 FR-003 地基：任务需要可靠地跑外部命令（测试/lint/安装）。

use std::collections::HashMap;
use std::path::PathBuf;
use std::time::{Duration, Instant};

use sysinfo::{ProcessRefreshKind, RefreshKind, System};
use tokio::io::{AsyncBufReadExt, BufReader};
use tokio::process::Command;
use tokio::time::timeout;

/// 执行请求。
#[derive(Debug, Clone)]
pub struct ExecRequest {
    pub cmd: String,
    pub args: Vec<String>,
    pub cwd: PathBuf,
    pub env: HashMap<String, String>,
    pub timeout_ms: u64,
    /// stdout/stderr 各自保留的最大字节数（超尾截断）。
    pub max_output_bytes: usize,
}

impl ExecRequest {
    pub fn new(cmd: impl Into<String>, cwd: impl Into<PathBuf>) -> Self {
        Self {
            cmd: cmd.into(),
            args: Vec::new(),
            cwd: cwd.into(),
            env: HashMap::new(),
            timeout_ms: 60_000,
            max_output_bytes: 8 * 1024,
        }
    }

    pub fn arg(mut self, a: impl Into<String>) -> Self {
        self.args.push(a.into());
        self
    }

    pub fn env(mut self, k: impl Into<String>, v: impl Into<String>) -> Self {
        self.env.insert(k.into(), v.into());
        self
    }

    pub fn timeout(mut self, ms: u64) -> Self {
        self.timeout_ms = ms;
        self
    }
}

/// 执行结果。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ExecResult {
    pub exit_code: Option<i32>,
    pub stdout: String,
    pub stderr: String,
    pub duration_ms: u64,
    pub timed_out: bool,
}

/// 执行命令；超时后杀进程树。
pub async fn run(req: ExecRequest) -> ExecResult {
    let start = Instant::now();
    let mut cmd = Command::new(&req.cmd);
    cmd.args(&req.args)
        .current_dir(&req.cwd)
        .envs(&req.env)
        .stdout(std::process::Stdio::piped())
        .stderr(std::process::Stdio::piped());

    let Ok(mut child) = cmd.spawn() else {
        return ExecResult {
            exit_code: None,
            stdout: String::new(),
            stderr: format!("无法启动命令: {}", req.cmd),
            duration_ms: start.elapsed().as_millis() as u64,
            timed_out: false,
        };
    };

    let pid = child.id();

    let stdout = child.stdout.take().expect("piped stdout");
    let stderr = child.stderr.take().expect("piped stderr");

    let mut out_buf = String::new();
    let mut err_buf = String::new();

    let out_handle = read_limited(stdout, req.max_output_bytes, &mut out_buf);
    let err_handle = read_limited(stderr, req.max_output_bytes, &mut err_buf);

    let run_fut = async {
        let (out_res, err_res, status_res) = tokio::join!(out_handle, err_handle, child.wait());
        let _ = out_res;
        let _ = err_res;
        status_res
    };

    let result = timeout(Duration::from_millis(req.timeout_ms), run_fut).await;
    let duration_ms = start.elapsed().as_millis() as u64;

    match result {
        Ok(Ok(status)) => ExecResult {
            exit_code: status.code(),
            stdout: out_buf,
            stderr: err_buf,
            duration_ms,
            timed_out: false,
        },
        Ok(Err(e)) => ExecResult {
            exit_code: None,
            stdout: out_buf,
            stderr: format!("等待子进程失败: {e}"),
            duration_ms,
            timed_out: false,
        },
        Err(_) => {
            // 超时：杀进程树
            if let Some(pid) = pid {
                kill_tree(pid);
            }
            // child.wait() 会因信号终止返回；再 await 一次收尸
            let _ = child.wait().await;
            ExecResult {
                exit_code: None,
                stdout: out_buf,
                stderr: format!(
                    "{err_buf}\n[devpilot] 命令执行超过 {}ms 被强制终止",
                    req.timeout_ms
                ),
                duration_ms,
                timed_out: true,
            }
        }
    }
}

async fn read_limited<R: tokio::io::AsyncRead + Unpin>(
    reader: R,
    max_bytes: usize,
    buf: &mut String,
) -> std::io::Result<()> {
    let mut lines = BufReader::new(reader).lines();
    while let Some(line) = lines.next_line().await? {
        let line = line + "\n";
        if buf.len() + line.len() <= max_bytes {
            buf.push_str(&line);
        } else {
            let remaining = max_bytes.saturating_sub(buf.len());
            if remaining > 0 {
                buf.push_str(&line[..remaining]);
            }
            buf.push_str("\n[devpilot] 输出过长，已截断\n");
            break;
        }
    }
    Ok(())
}

fn kill_tree(root_pid: u32) {
    let mut sys = System::new_with_specifics(
        RefreshKind::default().with_processes(ProcessRefreshKind::default()),
    );
    sys.refresh_processes();

    let mut pids = vec![root_pid];
    // 简单广度遍历子进程
    let mut i = 0;
    while i < pids.len() {
        let parent = pids[i];
        for (pid, proc) in sys.processes() {
            if proc.parent().map(|p| p.as_u32()) == Some(parent) {
                pids.push(pid.as_u32());
            }
        }
        i += 1;
    }

    for pid in pids {
        #[cfg(unix)]
        {
            unsafe { libc::kill(pid as i32, libc::SIGKILL) };
        }
        #[cfg(windows)]
        {
            let _ = std::process::Command::new("taskkill")
                .arg("/PID")
                .arg(pid.to_string())
                .arg("/F")
                .arg("/T")
                .output();
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn echo_captures_stdout() {
        let r = run(ExecRequest::new("echo", std::env::temp_dir()).arg("hello")).await;
        assert_eq!(r.exit_code, Some(0));
        assert!(r.stdout.contains("hello"));
        assert!(!r.timed_out);
    }

    #[tokio::test]
    async fn env_injected() {
        let r = run(ExecRequest::new(
            if cfg!(windows) { "cmd" } else { "sh" },
            std::env::temp_dir(),
        )
        .arg(if cfg!(windows) {
            "/C echo %MYVAR%"
        } else {
            "-c echo $MYVAR"
        })
        .env("MYVAR", "secret123"))
        .await;
        assert!(r.stdout.contains("secret123"));
    }

    #[tokio::test]
    async fn non_zero_exit() {
        let r = run(ExecRequest::new(
            if cfg!(windows) { "cmd" } else { "sh" },
            std::env::temp_dir(),
        )
        .arg(if cfg!(windows) {
            "/C exit 42"
        } else {
            "-c exit 42"
        }))
        .await;
        assert_eq!(r.exit_code, Some(42));
    }

    #[tokio::test]
    async fn timeout_kills() {
        let req = if cfg!(windows) {
            ExecRequest::new("cmd", std::env::temp_dir())
                .arg("/c")
                .arg("ping -n 30 127.0.0.1 > nul")
                .timeout(100)
        } else {
            ExecRequest::new("sleep", std::env::temp_dir())
                .arg("30")
                .timeout(100)
        };
        let r = run(req).await;
        assert!(r.timed_out);
    }
}
