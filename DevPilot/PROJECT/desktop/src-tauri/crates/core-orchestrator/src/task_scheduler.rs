//! chunk 级任务编排器（FR-003/032）。
//! 按 `chunk_no` 顺序执行当前 open 轮次的 pending tasks：
//! 构造 prompt → 调用 LLM → 解析文件变更 → runner 执行 → 写回状态与 checkpoint。

use core_exec::{FileChange, MaskedSecret, TaskRequest, TaskResult};
use core_sandbox::policy::{Decision, SandboxPolicy};
use core_state::agent_config::AgentConfigFields;
use core_state::task_event::TaskEvent;
use core_state::{Db, DbResult};
use rusqlite::{Connection, OptionalExtension};
use serde::{Deserialize, Serialize};
use std::path::Path;

const TASK_IMPL_PROMPT: &str = include_str!("prompts/task_impl.txt");

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TaskOutput {
    #[serde(default)]
    pub files: Vec<FileChange>,
    #[serde(default)]
    pub summary: String,
    #[serde(default)]
    pub clarifying_questions: Vec<String>,
}

#[derive(Debug, Clone)]
pub struct LlmMessage {
    pub role: String,
    pub content: String,
}

#[derive(Debug, Clone)]
pub struct LlmResponse {
    pub content: String,
    pub cost_cents: i64,
}

#[async_trait::async_trait]
pub trait LlmClient: Send + Sync {
    async fn complete(&self, messages: Vec<LlmMessage>) -> Result<LlmResponse, SchedulerError>;
}

#[derive(Debug, thiserror::Error)]
pub enum SchedulerError {
    #[error("数据库错误：{0}")]
    Db(#[from] core_state::DbError),
    #[error("LLM 调用失败：{0}")]
    Llm(String),
    #[error("模型返回无法解析：{0}")]
    Parse(String),
    #[error("文件路径越界：{0}")]
    PathEscape(String),
}

#[derive(Debug, Clone)]
pub enum RunResult {
    Done {
        total_cost_cents: i64,
    },
    NeedClarify {
        task_id: i64,
        questions: Vec<String>,
    },
    Failed {
        task_id: i64,
    },
}

/// 一次调度运行上下文。
pub struct Scheduler<'c, 'p> {
    pub db: &'c Db,
    pub project_id: i64,
    pub round_id: i64,
    pub project_path: &'p Path,
    pub llm: &'c dyn LlmClient,
}

/// 内存中的 task 行（仅 scheduler 内部用）。
struct PendingTask {
    id: i64,
    chunk_no: i64,
    title: String,
    instructions: String,
}

impl<'c, 'p> Scheduler<'c, 'p> {
    pub async fn run_round(
        &self,
        mut on_event: impl FnMut(&TaskEvent),
    ) -> Result<RunResult, SchedulerError> {
        let tasks = self.list_pending_tasks()?;
        if tasks.is_empty() {
            return Ok(RunResult::Done {
                total_cost_cents: 0,
            });
        }

        let mut total_cost_cents: i64 = 0;

        for task in tasks {
            self.set_task_running(task.id)?;

            // 构造 prompt 并请求 LLM（此阶段不持 DB 锁，允许并发读状态）。
            let prompt = self.build_prompt(&task)?;
            let response = self.llm.complete(prompt).await?;
            total_cost_cents += response.cost_cents.max(0);

            let output = parse_task_output(&response.content)
                .map_err(|e| SchedulerError::Parse(e.to_string()))?;

            if !output.clarifying_questions.is_empty() {
                self.set_task_status(task.id, "pending")?;
                return Ok(RunResult::NeedClarify {
                    task_id: task.id,
                    questions: output.clarifying_questions,
                });
            }

            // 路径安全：LLM 返回的文件必须在项目目录内。
            self.validate_files(&output.files)?;

            // 执行 runner 闭环。
            let result = self
                .run_task_with_events(&task, &output.files, &mut on_event)
                .await?;

            let status = if result.success { "done" } else { "failed" };
            let git_commit = latest_commit_hash(self.project_path);
            self.finalize_task(
                &task,
                status,
                result.cost_cents,
                &output.summary,
                git_commit.as_deref(),
            )?;

            if !result.success {
                return Ok(RunResult::Failed { task_id: task.id });
            }
        }

        Ok(RunResult::Done { total_cost_cents })
    }

    fn list_pending_tasks(&self) -> DbResult<Vec<PendingTask>> {
        self.db.read(|c| {
            let mut stmt = c.prepare(
                "SELECT id, chunk_no, title, instructions FROM tasks
                 WHERE round_id = ?1 AND status = 'pending' ORDER BY chunk_no",
            )?;
            let rows = stmt.query_map([self.round_id], |r| {
                Ok(PendingTask {
                    id: r.get(0)?,
                    chunk_no: r.get(1)?,
                    title: r.get(2)?,
                    instructions: r.get(3)?,
                })
            })?;
            Ok(rows.collect::<Result<Vec<_>, _>>()?)
        })
    }

    fn set_task_running(&self, task_id: i64) -> DbResult<()> {
        self.db.write(|c| {
            c.execute(
                "UPDATE tasks SET status = 'running', started_at = datetime('now') WHERE id = ?1",
                [task_id],
            )?;
            Ok(())
        })
    }

    fn set_task_status(&self, task_id: i64, status: &str) -> DbResult<()> {
        self.db.write(|c| {
            c.execute(
                "UPDATE tasks SET status = ?1 WHERE id = ?2",
                (status, task_id),
            )?;
            Ok(())
        })
    }

    fn build_prompt(&self, task: &PendingTask) -> DbResult<Vec<LlmMessage>> {
        let agents = self.db.read(|c| load_agent_text(c, self.project_id))?;
        let specs = self.db.read(|c| load_spec_text(c, self.project_id))?;
        let chunks = self.db.read(|c| load_chunk_text(c, self.project_id))?;
        let upstream = self
            .db
            .read(|c| load_upstream_summary(c, self.round_id, task.chunk_no))?;

        let upstream_block = if upstream.is_empty() {
            "".to_string()
        } else {
            format!("之前 chunk 的完成摘要：\n{}", upstream)
        };

        let user = TASK_IMPL_PROMPT
            .replace("{{AGENTS}}", &agents)
            .replace("{{SPEC_CARDS}}", &specs)
            .replace("{{PLAN_CHUNKS}}", &chunks)
            .replace("{{TASK_TITLE}}", &task.title)
            .replace("{{TASK_INSTRUCTIONS}}", &task.instructions)
            .replace("{{UPSTREAM_SUMMARY}}", &upstream_block);

        Ok(vec![LlmMessage {
            role: "user".into(),
            content: user,
        }])
    }

    fn validate_files(&self, files: &[FileChange]) -> Result<(), SchedulerError> {
        let policy = SandboxPolicy::new(vec![self.project_path.to_path_buf()]);
        for f in files {
            let target = policy
                .join(&f.path)
                .map_err(|e| SchedulerError::PathEscape(e.to_string()))?;
            match policy
                .check(&target)
                .map_err(|e| SchedulerError::PathEscape(e.to_string()))?
            {
                Decision::Allow(_) => {}
                Decision::Deny(reason) => {
                    return Err(SchedulerError::PathEscape(reason));
                }
            }
        }
        Ok(())
    }

    async fn run_task_with_events(
        &self,
        task: &PendingTask,
        files: &[FileChange],
        on_event: &mut impl FnMut(&TaskEvent),
    ) -> Result<TaskResult, SchedulerError> {
        let secrets = self.db.read(|c| load_secrets(c, self.project_id))?;
        let req = TaskRequest {
            project_id: self.project_id,
            task_id: task.id,
            title: task.title.clone(),
            instructions: task.instructions.clone(),
            files: files.to_vec(),
            test_command: None,
            max_fix_attempts: 0,
        };
        let result = core_exec::run_task(
            None,
            &req,
            self.project_path,
            &core_exec::NoOpFixStrategy,
            &secrets,
            |ev| on_event(ev),
        )
        .await;
        Ok(result)
    }

    fn finalize_task(
        &self,
        task: &PendingTask,
        status: &str,
        cost_cents: i64,
        summary: &str,
        git_commit: Option<&str>,
    ) -> DbResult<()> {
        self.db.write(|c| {
            c.execute(
                "UPDATE tasks SET status = ?1, cost_cents = ?2, finished_at = datetime('now') WHERE id = ?3",
                (status, cost_cents, task.id),
            )?;
            if let Some(commit) = git_commit {
                let checkpoint_status = if status == "done" { "success" } else { "failed" };
                c.execute(
                    "INSERT INTO checkpoints (task_id, git_commit, summary_plain, title, status) VALUES (?1, ?2, ?3, ?4, ?5)",
                    (task.id, commit, summary, &task.title, checkpoint_status),
                )?;
            }
            Ok(())
        })
    }

    /// 在当前轮次末尾追加一个新 task（FR-015 追加指令续跑）。
    pub fn append_task(&self, title: &str, instructions: &str) -> DbResult<i64> {
        self.db.write(|c| {
            let next_chunk: i64 = c.query_row(
                "SELECT COALESCE(MAX(chunk_no), 0) + 1 FROM tasks WHERE round_id = ?1",
                [self.round_id],
                |r| r.get(0),
            )?;
            c.execute(
                "INSERT INTO tasks (round_id, chunk_no, title, instructions, status, source) VALUES (?1, ?2, ?3, ?4, 'pending', 'local')",
                (self.round_id, next_chunk, title, instructions),
            )?;
            Ok(c.last_insert_rowid())
        })
    }
}

fn load_agent_text(c: &Connection, project_id: i64) -> DbResult<String> {
    let fields: Option<String> = c
        .query_row(
            "SELECT fields_json FROM agent_configs WHERE project_id = ?1",
            [project_id],
            |r| r.get(0),
        )
        .optional()?;
    let text = fields.unwrap_or_else(|| "{}".into());
    let parsed: AgentConfigFields = serde_json::from_str(&text).unwrap_or_default();
    Ok(format!(
        "定位：{}\n技术栈：{}\n命名：{}\n提交：{}\n安全：{}\n文档：{}\n测试：{}",
        parsed.positioning,
        parsed.tech_stack,
        parsed.naming_style,
        parsed.commit_style,
        parsed.security_redlines,
        parsed.doc_requirements,
        parsed.testing_redlines
    ))
}

fn load_spec_text(c: &Connection, project_id: i64) -> DbResult<String> {
    let mut stmt = c.prepare(
        "SELECT title, detail, ac_json FROM spec_cards WHERE project_id = ?1 AND status = 'confirmed' ORDER BY sort_order",
    )?;
    let rows = stmt.query_map([project_id], |r| {
        Ok((
            r.get::<_, String>(0)?,
            r.get::<_, String>(1)?,
            r.get::<_, String>(2)?,
        ))
    })?;
    let mut out = String::new();
    for r in rows {
        let (title, detail, ac) = r?;
        out.push_str(&format!(
            "- {}\n  细节：{}\n  验收：{}\n\n",
            title, detail, ac
        ));
    }
    Ok(out)
}

fn load_chunk_text(c: &Connection, project_id: i64) -> DbResult<String> {
    let mut stmt = c.prepare(
        "SELECT title, goal, estimated_tokens FROM plan_chunks WHERE project_id = ?1 AND status = 'approved' ORDER BY sort_order",
    )?;
    let rows = stmt.query_map([project_id], |r| {
        Ok((
            r.get::<_, String>(0)?,
            r.get::<_, String>(1)?,
            r.get::<_, Option<i64>>(2)?,
        ))
    })?;
    let mut out = String::new();
    for r in rows {
        let (title, goal, est) = r?;
        out.push_str(&format!(
            "- {}（目标：{}，预估 {} tokens）\n",
            title,
            goal,
            est.map(|n| n.to_string()).unwrap_or_else(|| "未估".into())
        ));
    }
    Ok(out)
}

fn load_upstream_summary(c: &Connection, round_id: i64, current_chunk: i64) -> DbResult<String> {
    let mut stmt = c.prepare(
        "SELECT t.title, c.summary_plain FROM tasks t
         JOIN checkpoints c ON c.task_id = t.id
         WHERE t.round_id = ?1 AND t.chunk_no < ?2 AND t.status = 'done'
         ORDER BY t.chunk_no",
    )?;
    let rows = stmt.query_map((round_id, current_chunk), |r| {
        Ok((r.get::<_, String>(0)?, r.get::<_, String>(1)?))
    })?;
    let mut out = String::new();
    for r in rows {
        let (title, summary) = r?;
        out.push_str(&format!("- {}：{}\n", title, summary));
    }
    Ok(out)
}

fn load_secrets(c: &Connection, project_id: i64) -> DbResult<Vec<MaskedSecret>> {
    let names = core_state::secrets::list(c, project_id)?;
    let mut out = Vec::new();
    for m in names {
        if let Some(v) = core_state::secrets::load(c, project_id, &m.name)? {
            out.push(MaskedSecret::new(m.name, v));
        }
    }
    Ok(out)
}

fn latest_commit_hash(project_path: &Path) -> Option<String> {
    std::process::Command::new("git")
        .args(["log", "-1", "--pretty=%H"])
        .current_dir(project_path)
        .output()
        .ok()
        .filter(|o| o.status.success())
        .map(|o| String::from_utf8_lossy(&o.stdout).trim().to_string())
}

fn parse_task_output(raw: &str) -> Result<TaskOutput, serde_json::Error> {
    let text = raw.trim();
    // 直接解析
    if let Ok(v) = serde_json::from_str::<TaskOutput>(text) {
        return Ok(v);
    }
    // 尝试 ```json ... ```
    if let Some(start) = text.find("```") {
        let after_fence = &text[start + 3..];
        let code = after_fence.strip_prefix("json").unwrap_or(after_fence);
        if let Some(end) = code.find("```") {
            let inner = code[..end].trim();
            if let Ok(v) = serde_json::from_str::<TaskOutput>(inner) {
                return Ok(v);
            }
        }
    }
    // 尝试第一个 { ... }
    if let Some(start) = text.find('{') {
        if let Some(end) = text.rfind('}') {
            let slice = &text[start..=end];
            if let Ok(v) = serde_json::from_str::<TaskOutput>(slice) {
                return Ok(v);
            }
        }
    }
    serde_json::from_str::<TaskOutput>(text)
}

// ---------- HTTP LLM 客户端 ----------

pub struct HttpLlmClient {
    client: reqwest::Client,
    base_url: String,
    access_token: String,
    model: String,
}

impl HttpLlmClient {
    pub fn new(base_url: impl Into<String>, access_token: impl Into<String>) -> Self {
        Self {
            client: reqwest::Client::new(),
            base_url: base_url.into(),
            access_token: access_token.into(),
            model: "claude-sonnet-4".into(),
        }
    }
}

#[async_trait::async_trait]
impl LlmClient for HttpLlmClient {
    async fn complete(&self, messages: Vec<LlmMessage>) -> Result<LlmResponse, SchedulerError> {
        let nonce = format!(
            "sch-{}",
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .map(|d| d.as_millis())
                .unwrap_or(0)
        );
        let body = serde_json::json!({
            "model": self.model,
            "messages": messages.iter().map(|m| serde_json::json!({
                "role": m.role,
                "content": m.content,
            })).collect::<Vec<_>>(),
            "nonce": nonce,
        });
        let resp = self
            .client
            .post(format!("{}/gateway/complete", self.base_url))
            .header("authorization", format!("Bearer {}", self.access_token))
            .json(&body)
            .send()
            .await
            .map_err(|e| SchedulerError::Llm(e.to_string()))?;

        if !resp.status().is_success() {
            let status = resp.status();
            let text = resp.text().await.unwrap_or_default();
            return Err(SchedulerError::Llm(format!("HTTP {}: {}", status, text)));
        }

        let payload = resp
            .json::<serde_json::Value>()
            .await
            .map_err(|e| SchedulerError::Llm(e.to_string()))?;
        let content = payload["data"]["content"]
            .as_str()
            .unwrap_or("")
            .to_string();
        let cost_cents = payload["data"]["cost_cents"].as_i64().unwrap_or(0);
        Ok(LlmResponse {
            content,
            cost_cents,
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::path::PathBuf;
    use tempfile::TempDir;

    struct MockLlm {
        output: TaskOutput,
    }

    #[async_trait::async_trait]
    impl LlmClient for MockLlm {
        async fn complete(
            &self,
            _messages: Vec<LlmMessage>,
        ) -> Result<LlmResponse, SchedulerError> {
            Ok(LlmResponse {
                content: serde_json::to_string(&self.output).unwrap(),
                cost_cents: 10,
            })
        }
    }

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

    fn fixture() -> (Db, i64, i64, PathBuf) {
        let db = Db::open_in_memory().unwrap();
        let project_id: i64 = db
            .write(|c| {
                c.execute(
                    "INSERT INTO projects (name, path, scale, workflow_version) VALUES ('p', '/tmp/p', 'L1', '1.20')",
                    [],
                )?;
                Ok(c.last_insert_rowid())
            })
            .unwrap();
        let round_id: i64 = db
            .write(|c| {
                c.execute(
                    "INSERT INTO rounds (project_id, seq, title) VALUES (?1, 1, 'r1')",
                    [project_id],
                )?;
                Ok(c.last_insert_rowid())
            })
            .unwrap();
        db.write(|c| {
            c.execute(
                "INSERT INTO tasks (round_id, chunk_no, title, instructions) VALUES (?1, 1, 't1', 'write ok')",
                [round_id],
            )?;
            c.execute(
                "INSERT INTO tasks (round_id, chunk_no, title, instructions) VALUES (?1, 2, 't2', 'fail')",
                [round_id],
            )?;
            Ok(())
        })
        .unwrap();
        (db, project_id, round_id, "/tmp/p".into())
    }

    #[tokio::test]
    async fn scheduler_runs_pending_tasks_in_order() {
        let tmp = TempDir::new().unwrap();
        init_git(tmp.path());
        let (db, project_id, round_id, _) = fixture();

        // 覆盖项目路径到真实临时目录
        db.write(|c| {
            c.execute(
                "UPDATE projects SET path = ?1 WHERE id = ?2",
                (tmp.path().to_string_lossy().to_string(), project_id),
            )?;
            Ok(())
        })
        .unwrap();

        // 写入 Rust 项目让 runner 执行 cargo test
        std::fs::write(
            tmp.path().join("Cargo.toml"),
            "[package]\nname = \"p\"\nversion = \"0.1.0\"\nedition = \"2021\"\n",
        )
        .unwrap();
        std::fs::create_dir(tmp.path().join("src")).unwrap();
        std::fs::write(tmp.path().join("src/lib.rs"), "").unwrap();

        let llm = MockLlm {
            output: TaskOutput {
                files: vec![FileChange::new("hello.txt", "hello")],
                summary: "add hello".into(),
                clarifying_questions: vec![],
            },
        };
        let scheduler = Scheduler {
            db: &db,
            project_id,
            round_id,
            project_path: tmp.path(),
            llm: &llm,
        };
        let result = scheduler.run_round(|_| {}).await.unwrap();
        match result {
            RunResult::Done { total_cost_cents } => {
                assert_eq!(total_cost_cents, 20); // 2 tasks × 10
            }
            other => panic!("unexpected {other:?}"),
        }

        let statuses: Vec<String> = db
            .read(|c| {
                let mut stmt =
                    c.prepare("SELECT status FROM tasks WHERE round_id = ?1 ORDER BY chunk_no")?;
                let rows = stmt.query_map([round_id], |r| r.get::<_, String>(0))?;
                Ok(rows.collect::<Result<Vec<_>, _>>()?)
            })
            .unwrap();
        assert_eq!(statuses, vec!["done", "done"]);
    }

    #[tokio::test]
    async fn scheduler_stops_on_failure() {
        let tmp = TempDir::new().unwrap();
        init_git(tmp.path());
        let (db, project_id, round_id, _) = fixture();
        db.write(|c| {
            c.execute(
                "UPDATE projects SET path = ?1 WHERE id = ?2",
                (tmp.path().to_string_lossy().to_string(), project_id),
            )?;
            Ok(())
        })
        .unwrap();
        // 写入 Rust 项目让 runner 执行 cargo test（会失败）
        std::fs::write(
            tmp.path().join("Cargo.toml"),
            "[package]\nname = \"p\"\nversion = \"0.1.0\"\nedition = \"2021\"\n",
        )
        .unwrap();
        std::fs::create_dir(tmp.path().join("src")).unwrap();
        std::fs::write(
            tmp.path().join("src/lib.rs"),
            "#[test]\nfn fail() { panic!(\"boom\"); }\n",
        )
        .unwrap();

        let llm = MockLlm {
            output: TaskOutput {
                files: vec![FileChange::new("bad.txt", "bad")],
                summary: "add bad".into(),
                clarifying_questions: vec![],
            },
        };
        let scheduler = Scheduler {
            db: &db,
            project_id,
            round_id,
            project_path: tmp.path(),
            llm: &llm,
        };
        let result = scheduler.run_round(|_| {}).await.unwrap();
        assert!(matches!(result, RunResult::Failed { task_id: _ }));

        let statuses: Vec<String> = db
            .read(|c| {
                let mut stmt =
                    c.prepare("SELECT status FROM tasks WHERE round_id = ?1 ORDER BY chunk_no")?;
                let rows = stmt.query_map([round_id], |r| r.get::<_, String>(0))?;
                Ok(rows.collect::<Result<Vec<_>, _>>()?)
            })
            .unwrap();
        assert_eq!(statuses[0], "failed");
        assert_eq!(statuses[1], "pending");
    }

    #[tokio::test]
    async fn scheduler_returns_clarify_questions() {
        let tmp = TempDir::new().unwrap();
        init_git(tmp.path());
        let (db, project_id, round_id, _) = fixture();
        db.write(|c| {
            c.execute(
                "UPDATE projects SET path = ?1 WHERE id = ?2",
                (tmp.path().to_string_lossy().to_string(), project_id),
            )?;
            Ok(())
        })
        .unwrap();

        let llm = MockLlm {
            output: TaskOutput {
                files: vec![],
                summary: "".into(),
                clarifying_questions: vec!["数据库用哪个？".into()],
            },
        };
        let scheduler = Scheduler {
            db: &db,
            project_id,
            round_id,
            project_path: tmp.path(),
            llm: &llm,
        };
        let result = scheduler.run_round(|_| {}).await.unwrap();
        assert!(matches!(result, RunResult::NeedClarify { .. }));
    }

    #[test]
    fn append_task_adds_pending_task_at_end_of_round() {
        let tmp = TempDir::new().unwrap();
        init_git(tmp.path());
        let (db, _project_id, round_id, _) = fixture();
        let scheduler = Scheduler {
            db: &db,
            project_id: 1,
            round_id,
            project_path: tmp.path(),
            llm: &MockLlm {
                output: TaskOutput {
                    files: vec![],
                    summary: "".into(),
                    clarifying_questions: vec![],
                },
            },
        };
        let id = scheduler.append_task("追加", "再加点功能").unwrap();
        let (chunk, status): (i64, String) = db
            .read(|c| {
                c.query_row(
                    "SELECT chunk_no, status FROM tasks WHERE id = ?1",
                    [id],
                    |r| Ok((r.get::<_, i64>(0)?, r.get::<_, String>(1)?)),
                )
                .map_err(Into::into)
            })
            .unwrap();
        assert_eq!(chunk, 3); // fixture 已有 2 个 task
        assert_eq!(status, "pending");
    }

    #[test]
    fn parse_extracts_json_from_markdown() {
        let raw = "```json\n{\"files\":[],\"summary\":\"x\",\"clarifying_questions\":[]}\n```";
        let out = parse_task_output(raw).unwrap();
        assert_eq!(out.summary, "x");
    }
}
