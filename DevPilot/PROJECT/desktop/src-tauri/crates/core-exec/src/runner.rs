// 任务执行器：读文件 → 写文件 → 安装 → 跑测试/lint → 失败自动修复 → 产出 diff → commit 存档点。
// 对应 FR-003/AC-004（P03 Step6 全流程闭环 v1）。

use std::collections::HashMap;
use std::future::Future;
use std::path::Path;
use std::pin::Pin;

use rusqlite::Connection;
use serde::{Deserialize, Serialize};

use crate::exec::{run, ExecRequest, ExecResult};
use crate::install::{plan as install_plan, run_plan as run_install_plan};
use crate::probe::{probe, EnvProfile, Stack};
use crate::profile::probe_and_cache;
use crate::secrets::{inject_env, redact, MaskedSecret};
use core_sandbox::path::PathError;
use core_sandbox::policy::{Decision, SandboxPolicy};
use core_state::task_event::{TaskEvent, TaskEventType};

/// 请求：一次本地执行任务。
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TaskRequest {
    pub project_id: i64,
    pub task_id: i64,
    pub title: String,
    /// 大白话任务描述（给 commit message / 日志用）。
    pub instructions: String,
    /// 初始文件变更（LLM 或人工编排的补丁）。
    pub files: Vec<FileChange>,
    /// 手动指定测试/lint 命令（测试用或高级场景）。
    #[serde(default)]
    pub test_command: Option<Vec<String>>,
    /// 失败自动修复最大轮数（默认 0 表示不重试）。
    #[serde(default)]
    pub max_fix_attempts: usize,
}

impl TaskRequest {
    pub fn new(title: impl Into<String>, instructions: impl Into<String>) -> Self {
        Self {
            project_id: 0,
            task_id: 0,
            title: title.into(),
            instructions: instructions.into(),
            files: Vec::new(),
            test_command: None,
            max_fix_attempts: 0,
        }
    }
}

/// 文件变更项：相对项目根的路径 + 完整内容。
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct FileChange {
    pub path: String,
    pub content: String,
}

impl FileChange {
    pub fn new(path: impl Into<String>, content: impl Into<String>) -> Self {
        Self {
            path: path.into(),
            content: content.into(),
        }
    }
}

/// 任务结果。
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct TaskResult {
    pub success: bool,
    /// 最终到达的 phase：probe/install/run/done/failed。
    pub phase: String,
    /// git diff --stat 风格摘要；无 git 时为空。
    pub diff_summary: String,
    pub test_result: Option<TestResult>,
    pub fix_attempts: usize,
    pub cost_cents: i64,
    pub error: Option<String>,
}

/// 测试/lint 执行结果。
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct TestResult {
    pub command: String,
    pub exit_code: Option<i32>,
    pub stdout: String,
    pub stderr: String,
    pub timed_out: bool,
}

impl From<ExecResult> for TestResult {
    fn from(r: ExecResult) -> Self {
        Self {
            command: String::new(),
            exit_code: r.exit_code,
            stdout: r.stdout,
            stderr: r.stderr,
            timed_out: r.timed_out,
        }
    }
}

/// 沙箱文件越界 / IO 错误统一类型。
#[derive(Debug, thiserror::Error)]
pub enum FileError {
    #[error("路径越界：{0}")]
    Escape(#[from] PathError),
    #[error("命中沙箱黑名单：{0}")]
    Denied(String),
    #[error("IO 错误：{0}")]
    Io(#[from] std::io::Error),
}

fn project_policy(project_path: &Path) -> SandboxPolicy {
    SandboxPolicy::new(vec![project_path.to_path_buf()])
}

/// 读取项目内文件（受沙箱约束）。
pub fn read_project_file(project_path: &Path, rel: &str) -> Result<String, FileError> {
    let policy = project_policy(project_path);
    let target = policy.join(rel)?;
    match policy.check(&target)? {
        Decision::Allow(p) => Ok(std::fs::read_to_string(p)?),
        Decision::Deny(reason) => Err(FileError::Denied(reason)),
    }
}

/// 写入项目内文件（受沙箱约束）；父目录不存在时自动创建。
pub fn write_project_file(project_path: &Path, rel: &str, content: &str) -> Result<(), FileError> {
    let policy = project_policy(project_path);
    let target = policy.join(rel)?;
    match policy.check(&target)? {
        Decision::Allow(p) => {
            if let Some(parent) = p.parent() {
                std::fs::create_dir_all(parent)?;
            }
            std::fs::write(&p, content)?;
            Ok(())
        }
        Decision::Deny(reason) => Err(FileError::Denied(reason)),
    }
}

/// 失败自动修复策略。MVP 默认无 LLM；未来可接入云端 chat/estimate。
pub trait FixStrategy: Send + Sync {
    fn fix<'a>(
        &'a self,
        req: &'a TaskRequest,
        last: &'a TestResult,
        project_path: &'a Path,
    ) -> Pin<Box<dyn Future<Output = Option<FileChange>> + Send + 'a>>;
}

/// 默认策略：不修复，直接失败。
pub struct NoOpFixStrategy;

impl FixStrategy for NoOpFixStrategy {
    fn fix<'a>(
        &'a self,
        _req: &'a TaskRequest,
        _last: &'a TestResult,
        _project_path: &'a Path,
    ) -> Pin<Box<dyn Future<Output = Option<FileChange>> + Send + 'a>> {
        Box::pin(async { None })
    }
}

/// 执行一次任务闭环。
/// `conn` 为 Some 时复用环境画像缓存；None 时现场探测（测试用）。
pub async fn run_task<F>(
    conn: Option<&mut Connection>,
    req: &TaskRequest,
    project_path: &Path,
    fixer: &F,
    secrets: &[MaskedSecret],
    mut on_event: impl FnMut(&TaskEvent),
) -> TaskResult
where
    F: FixStrategy,
{
    let mut result = TaskResult {
        success: false,
        phase: "pending".into(),
        diff_summary: String::new(),
        test_result: None,
        fix_attempts: 0,
        cost_cents: 0,
        error: None,
    };

    emit(
        &mut on_event,
        req.task_id,
        TaskEventType::Narrative,
        format!("[runner] 任务开始：{}", req.title),
    );

    // 1. 应用初始文件变更。
    result.phase = "write".into();
    for fc in &req.files {
        if let Err(e) = write_project_file(project_path, &fc.path, &fc.content) {
            result.error = Some(format!("写入 {} 失败：{e}", fc.path));
            return result;
        }
        emit(
            &mut on_event,
            req.task_id,
            TaskEventType::Narrative,
            format!("[runner] 已写文件：{}", fc.path),
        );
    }

    // 2. 环境探测与缓存。
    result.phase = "probe".into();
    let profile = match conn {
        Some(c) => match probe_and_cache(c, project_path) {
            Ok(p) => p,
            Err(e) => {
                result.error = Some(format!("环境探测失败：{e}"));
                return result;
            }
        },
        None => probe(project_path),
    };
    emit(
        &mut on_event,
        req.task_id,
        TaskEventType::Narrative,
        format!("[runner] 识别技术栈：{:?}", profile.stacks),
    );

    // 3. 安装缺失运行时（失败即停）。
    result.phase = "install".into();
    let install_plan = install_plan(&profile);
    if !install_plan.missing.is_empty() {
        emit(
            &mut on_event,
            req.task_id,
            TaskEventType::Narrative,
            format!(
                "[runner] 发现缺失运行时：{}",
                install_plan.missing.join("、")
            ),
        );
        let install_res = run_install_plan(&install_plan, project_path, |line| {
            emit(
                &mut on_event,
                req.task_id,
                TaskEventType::Raw,
                line.trim_end(),
            );
        })
        .await;
        for r in &install_res {
            if !r.success {
                result.error = Some(format!("安装 {} 失败：{}", r.step, r.stderr));
                return result;
            }
        }
    }

    // 4. 探测测试/lint 命令。
    result.phase = "run".into();
    let Some(test_cmd) = pick_test_command(req, &profile, project_path) else {
        result.success = true;
        result.phase = "done".into();
        result.diff_summary = diff_stat(project_path);
        commit_checkpoint(project_path, &req.title, result.success);
        return result;
    };

    emit(
        &mut on_event,
        req.task_id,
        TaskEventType::Narrative,
        format!("[runner] 执行：{}", test_cmd.join(" ")),
    );

    // 5. 跑测试 + 失败修复循环。
    let mut exec_req = build_exec_request(&test_cmd, project_path, secrets);
    let mut last = run(exec_req).await;
    result.test_result = Some(into_test_result(&test_cmd, &last, secrets));

    while last.exit_code != Some(0) && result.fix_attempts < req.max_fix_attempts {
        result.fix_attempts += 1;
        emit(
            &mut on_event,
            req.task_id,
            TaskEventType::Narrative,
            format!("[runner] 第 {} 次自动修复", result.fix_attempts),
        );
        let Some(patch) = fixer
            .fix(req, result.test_result.as_ref().unwrap(), project_path)
            .await
        else {
            break;
        };
        if let Err(e) = write_project_file(project_path, &patch.path, &patch.content) {
            result.error = Some(format!("修复写入 {} 失败：{e}", patch.path));
            return result;
        }
        emit(
            &mut on_event,
            req.task_id,
            TaskEventType::Narrative,
            format!("[runner] 应用修复：{}", patch.path),
        );
        exec_req = build_exec_request(&test_cmd, project_path, secrets);
        last = run(exec_req).await;
        result.test_result = Some(into_test_result(&test_cmd, &last, secrets));
    }

    result.success = last.exit_code == Some(0) && !last.timed_out;
    result.phase = if result.success { "done" } else { "failed" }.into();

    // 6. 产出 diff 摘要。
    result.diff_summary = diff_stat(project_path);

    // 7. commit 存档点（失败也提交，便于排查）。
    let commit = commit_checkpoint(project_path, &req.title, result.success);
    if let Some(ref hash) = commit {
        emit(
            &mut on_event,
            req.task_id,
            TaskEventType::Checkpoint,
            format!("checkpoint {}", hash),
        );
    }

    emit(
        &mut on_event,
        req.task_id,
        TaskEventType::Narrative,
        format!("[runner] 任务结束：{}", result.phase),
    );
    result
}

fn emit(
    on_event: &mut impl FnMut(&TaskEvent),
    task_id: i64,
    event_type: TaskEventType,
    message: impl Into<String>,
) {
    on_event(&TaskEvent {
        id: None,
        task_id,
        event_type,
        message: message.into(),
        created_at: None,
    });
}

fn build_exec_request(cmd: &[String], cwd: &Path, secrets: &[MaskedSecret]) -> ExecRequest {
    let mut env = HashMap::new();
    inject_env(&mut env, secrets);
    ExecRequest::new(&cmd[0], cwd)
        .args(cmd[1..].to_vec())
        .envs(env)
        .timeout(120_000)
}

fn into_test_result(cmd: &[String], last: &ExecResult, secrets: &[MaskedSecret]) -> TestResult {
    TestResult {
        command: cmd.join(" "),
        exit_code: last.exit_code,
        stdout: redact(&last.stdout, secrets),
        stderr: redact(&last.stderr, secrets),
        timed_out: last.timed_out,
    }
}

/// 根据画像选一个测试/lint 命令；没有识别到则返回 None。
fn pick_test_command(
    req: &TaskRequest,
    profile: &EnvProfile,
    project_path: &Path,
) -> Option<Vec<String>> {
    if let Some(cmd) = &req.test_command {
        return Some(cmd.clone());
    }
    for stack in &profile.stacks {
        match stack {
            Stack::Node => {
                if let Some(cmd) = node_test_command(project_path) {
                    return Some(cmd);
                }
            }
            Stack::Rust => return Some(vec!["cargo".into(), "test".into()]),
            Stack::Python => return Some(vec!["python".into(), "-m".into(), "pytest".into()]),
            Stack::Go => return Some(vec!["go".into(), "test".into(), "./...".into()]),
            _ => {}
        }
    }
    None
}

fn node_test_command(project_path: &Path) -> Option<Vec<String>> {
    let package_json = std::fs::read_to_string(project_path.join("package.json")).ok()?;
    let v: serde_json::Value = serde_json::from_str(&package_json).ok()?;
    let scripts = v.get("scripts")?;
    if scripts.get("test").is_some() {
        let pm = profile_package_manager(project_path);
        Some(vec![pm, "test".into()])
    } else {
        None
    }
}

fn profile_package_manager(project_path: &Path) -> String {
    if project_path.join("pnpm-lock.yaml").exists() {
        "pnpm".into()
    } else if project_path.join("yarn.lock").exists() {
        "yarn".into()
    } else if project_path.join("bun.lockb").exists() || project_path.join("bun.lock").exists() {
        "bun".into()
    } else {
        "npm".into()
    }
}

/// 运行 `git diff --stat` 拿到变更摘要；无 git 时回退到文件计数。
fn diff_stat(project_path: &Path) -> String {
    let out = std::process::Command::new("git")
        .args(["diff", "--stat"])
        .current_dir(project_path)
        .output();
    match out {
        Ok(o) if o.status.success() => String::from_utf8_lossy(&o.stdout).trim().to_string(),
        _ => format!(
            "{} files changed (git unavailable)",
            count_files(project_path)
        ),
    }
}

fn count_files(dir: &Path) -> usize {
    let mut n = 0;
    if let Ok(entries) = std::fs::read_dir(dir) {
        for e in entries.flatten() {
            let p = e.path();
            if p.file_name() == Some(std::ffi::OsStr::new(".git")) {
                continue;
            }
            if p.is_dir() {
                n += count_files(&p);
            } else {
                n += 1;
            }
        }
    }
    n
}

/// 把当前变更提交为存档点；成功用 devpilot: 前缀，失败用 devpilot-failed: 前缀。
/// 返回 commit hash（失败或无 git 时返回 None）。
fn commit_checkpoint(project_path: &Path, title: &str, success: bool) -> Option<String> {
    let msg = if success {
        format!("devpilot: {title}")
    } else {
        format!("devpilot-failed: {title}")
    };
    let _ = std::process::Command::new("git")
        .args(["add", "-A"])
        .current_dir(project_path)
        .output();
    let _ = std::process::Command::new("git")
        .args(["commit", "-m", &msg, "--no-verify"])
        .current_dir(project_path)
        .output();
    std::process::Command::new("git")
        .args(["log", "-1", "--pretty=%H"])
        .current_dir(project_path)
        .output()
        .ok()
        .filter(|o| o.status.success())
        .map(|o| String::from_utf8_lossy(&o.stdout).trim().to_string())
}

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::TempDir;

    fn init_git(dir: &Path) {
        let _ = std::process::Command::new("git")
            .args(["init", "-q"])
            .current_dir(dir)
            .output();
        let _ = std::process::Command::new("git")
            .args(["config", "user.email", "devpilot@local"])
            .current_dir(dir)
            .output();
        let _ = std::process::Command::new("git")
            .args(["config", "user.name", "DevPilot"])
            .current_dir(dir)
            .output();
    }

    #[tokio::test]
    async fn writes_files_inside_project_and_passes_test() {
        let tmp = TempDir::new().unwrap();
        init_git(tmp.path());

        std::fs::write(tmp.path().join("package.json"), "{}").unwrap();

        let mut req = TaskRequest::new("add fn", "write something");
        req.test_command = Some(vec!["node".into(), "-e".into(), "console.log('ok')".into()]);
        let r = run_task(None, &req, tmp.path(), &NoOpFixStrategy, &[], |_| {}).await;
        assert!(r.success, "{:?}", r);
        assert_eq!(r.phase, "done");
        assert!(r.test_result.unwrap().stdout.contains("ok"));
    }

    #[tokio::test]
    async fn fails_without_fixer_and_commits_failed_checkpoint() {
        let tmp = TempDir::new().unwrap();
        init_git(tmp.path());

        std::fs::write(tmp.path().join("package.json"), "{}").unwrap();

        let mut req = TaskRequest::new("broken", "will fail");
        req.test_command = Some(vec!["node".into(), "-e".into(), "process.exit(1)".into()]);
        let r = run_task(None, &req, tmp.path(), &NoOpFixStrategy, &[], |_| {}).await;
        assert!(!r.success);
        assert_eq!(r.phase, "failed");

        // 确认失败存档点存在。
        let log = std::process::Command::new("git")
            .args(["log", "-1", "--pretty=%s"])
            .current_dir(tmp.path())
            .output()
            .unwrap();
        assert!(String::from_utf8_lossy(&log.stdout).contains("devpilot-failed"));
    }

    struct MockFixer;

    impl FixStrategy for MockFixer {
        fn fix<'a>(
            &'a self,
            _req: &'a TaskRequest,
            _last: &'a TestResult,
            _project_path: &'a Path,
        ) -> Pin<Box<dyn Future<Output = Option<FileChange>> + Send + 'a>> {
            Box::pin(async move {
                // 修复：写入一个让测试通过的脚本。
                Some(FileChange::new("check.js", "process.exit(0);"))
            })
        }
    }

    #[tokio::test]
    async fn fixer_can_recover_failed_test() {
        let tmp = TempDir::new().unwrap();
        init_git(tmp.path());

        std::fs::write(tmp.path().join("package.json"), "{}").unwrap();

        let mut req = TaskRequest::new("fixable", "try fix");
        req.test_command = Some(vec!["node".into(), "check.js".into()]);
        req.max_fix_attempts = 2;
        let r = run_task(None, &req, tmp.path(), &MockFixer, &[], |_| {}).await;
        assert!(r.success);
        assert_eq!(r.fix_attempts, 1);
    }

    #[tokio::test]
    async fn secrets_injected_and_redacted_in_output() {
        let tmp = TempDir::new().unwrap();
        init_git(tmp.path());

        std::fs::write(tmp.path().join("package.json"), "{}").unwrap();

        let mut req = TaskRequest::new("secret-task", "use secret");
        req.test_command = Some(vec![
            "node".into(),
            "-e".into(),
            "console.log('token=' + process.env.DEVPILOT_SECRET_API_KEY)".into(),
        ]);
        let secrets = vec![MaskedSecret::new("api_key", "super-secret")];
        let r = run_task(None, &req, tmp.path(), &NoOpFixStrategy, &secrets, |_| {}).await;
        assert!(r.success, "{:?}", r);
        let out = r.test_result.unwrap().stdout;
        assert!(out.contains("token=***"), "{out}");
        assert!(!out.contains("super-secret"));
    }

    #[test]
    fn read_write_inside_project_allowed() {
        let tmp = TempDir::new().unwrap();
        write_project_file(tmp.path(), "src/main.rs", "hello").unwrap();
        assert_eq!(
            read_project_file(tmp.path(), "src/main.rs").unwrap(),
            "hello"
        );
    }

    #[test]
    fn write_outside_project_rejected() {
        let tmp = TempDir::new().unwrap();
        assert!(write_project_file(tmp.path(), "../escape.txt", "x").is_err());
    }
}
