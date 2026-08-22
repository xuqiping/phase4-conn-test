//! MCP server 进程管理器（FR-026 / AC-029）。
//! 职责：spawn（危险命令过 core-sandbox 审批门）→ initialize 握手（超时 kill）→
//! 长驻读 stdout；异常退出监听 → 状态标 error + 自动重启（超 3 次转 manual_required）。
//! 日志环形缓冲每 server ≤200 行。
//!
//! 退出判定约定：`stop()` 先把句柄移出 map 再杀进程；退出监听发现「进程死了但还在
//! map 里」即为异常退出（AC-029），据此标 error 并触发自动重启。
//! 竞态防护：每个句柄带代次号（gen）。supervise 只看护自己启动那一代；stop→start
//! 换血后旧监听醒来发现 gen 对不上即退场，不会把新进程误判成「异常退出」。

use std::collections::{HashMap, VecDeque};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;
use std::time::Duration;

use core_state::mcp_store::McpServerRow;
use core_state::Db;
use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
use tokio::process::{Child, ChildStdin, Command};
use tokio::sync::{mpsc, Mutex};

use crate::rpc;

/// 每 server 日志环形缓冲上限。
pub const LOG_RING_MAX: usize = 200;
/// 自动重启上限（超过转 manual_required，人工介入）。
pub const MAX_AUTO_RESTARTS: i64 = 3;

type LogRing = Mutex<VecDeque<String>>;
type SharedChild = Arc<Mutex<Child>>;
type SharedStdin = Arc<Mutex<ChildStdin>>;
type SharedResponses = Arc<Mutex<mpsc::Receiver<String>>>;

struct ProcHandle {
    gen: u64,
    child: SharedChild,
    stdin: SharedStdin,
    responses: SharedResponses,
    logs: Arc<LogRing>,
}

/// 进程管理器：可 Clone（内部共享句柄）。
#[derive(Clone)]
pub struct Manager {
    db: Db,
    procs: Arc<Mutex<HashMap<i64, ProcHandle>>>,
    next_gen: Arc<AtomicU64>,
    /// 握手超时（测试可调短）。
    pub init_timeout: Duration,
    /// 异常退出是否自动重启（测试可关，避免后台任务干扰断言）。
    pub auto_restart: bool,
}

pub type MgrResult<T> = Result<T, String>;

impl Manager {
    pub fn new(db: Db) -> Self {
        Self {
            db,
            procs: Arc::new(Mutex::new(HashMap::new())),
            next_gen: Arc::new(AtomicU64::new(1)),
            init_timeout: Duration::from_secs(10),
            auto_restart: true,
        }
    }

    /// 危险命令校验（安全清单：MCP spawn 复用 P03 审批门；后台进程无法弹批，非 Allow 一律拒）。
    fn check_command_safety(command: &str, args: &[String]) -> MgrResult<()> {
        let gate =
            core_sandbox::approval::ApprovalGate::new(core_sandbox::approval::ApprovalPolicy::Auto);
        let arg_refs: Vec<&str> = args.iter().map(String::as_str).collect();
        let reason = match gate.check_command(command, &arg_refs) {
            core_sandbox::approval::Decision::Allow => return Ok(()),
            core_sandbox::approval::Decision::Block(r) => format!("命中黑名单：{r}"),
            core_sandbox::approval::Decision::Ask(p) => format!("需人工确认：{}", p.detail),
        };
        Err(format!(
            "命令「{command} {}」被安全策略拦下（{reason}）。后台 server 无法弹审批，请换安全的启动命令。",
            args.join(" ")
        ))
    }

    fn row(&self, id: i64) -> MgrResult<McpServerRow> {
        self.db
            .read(|c| core_state::mcp_store::by_id(c, id))
            .map_err(|e| format!("读配置失败：{e}"))?
            .ok_or_else(|| "没有这个 server 记录".to_string())
    }

    /// 启动（对外入口）：去重 → 起进程 → 挂异常退出监听。
    pub async fn start(&self, id: i64) -> MgrResult<String> {
        if self.procs.lock().await.contains_key(&id) {
            return Ok("已在运行".into());
        }
        let (msg, gen) = self.start_proc(id).await?;
        let supervisor = Manager {
            db: self.db.clone(),
            procs: self.procs.clone(),
            next_gen: self.next_gen.clone(),
            init_timeout: self.init_timeout,
            auto_restart: self.auto_restart,
        };
        tokio::spawn(supervise(supervisor, id, gen));
        Ok(msg)
    }

    /// 起进程本体：校验 → spawn → 握手 → 入 map → 标 running（不挂监听）。
    /// 返回 (消息, 代次号)。env_json（形如 {"K":"V"}）会注入子进程环境——
    /// 市场 server 的 token/key 全靠它（P4 审查发现此前被静默丢弃）。
    async fn start_proc(&self, id: i64) -> MgrResult<(String, u64)> {
        let row = self.row(id)?;
        let args: Vec<String> = serde_json::from_str(&row.args_json)
            .map_err(|e| format!("args 不是合法 JSON 数组：{e}"))?;
        let envs: std::collections::HashMap<String, String> = if row.env_json.trim().is_empty() {
            Default::default()
        } else {
            serde_json::from_str(&row.env_json)
                .map_err(|e| format!("env 不是合法 JSON 对象（键值都得是字符串）：{e}"))?
        };
        Self::check_command_safety(&row.command, &args)?;

        let mut child = Command::new(&row.command)
            .args(&args)
            .envs(&envs)
            .stdin(std::process::Stdio::piped())
            .stdout(std::process::Stdio::piped())
            .stderr(std::process::Stdio::piped())
            .kill_on_drop(true)
            .spawn()
            .map_err(|e| format!("启动失败：{e}（检查命令是否存在）"))?;
        let pid = child.id();
        let mut stdin = child.stdin.take().expect("stdin piped");
        let stdout = child.stdout.take().expect("stdout piped");
        let stderr = child.stderr.take().expect("stderr piped");

        let (tx, rx) = mpsc::channel::<String>(64);
        tokio::spawn(async move {
            let mut lines = BufReader::new(stdout).lines();
            while let Ok(Some(line)) = lines.next_line().await {
                if tx.send(line).await.is_err() {
                    break;
                }
            }
        });
        let logs: Arc<LogRing> = Arc::new(Mutex::new(VecDeque::new()));
        let log_sink = logs.clone();
        tokio::spawn(async move {
            let mut lines = BufReader::new(stderr).lines();
            while let Ok(Some(line)) = lines.next_line().await {
                let mut ring = log_sink.lock().await;
                if ring.len() >= LOG_RING_MAX {
                    ring.pop_front();
                }
                ring.push_back(line);
            }
        });

        // 握手：initialize → 等 id=1 应答 → initialized 通知
        stdin
            .write_all(rpc::initialize_request(1, "devpilot").as_bytes())
            .await
            .map_err(|e| format!("写握手请求失败：{e}"))?;
        let mut rx = rx;
        let handshake: MgrResult<()> = match tokio::time::timeout(self.init_timeout, async {
            loop {
                let Some(line) = rx.recv().await else {
                    return Err("进程在握手期间就退出了".to_string());
                };
                if let Some((rid, result)) = rpc::parse_response(&line) {
                    if rid == 1 {
                        return if rpc::is_rpc_error(&result) {
                            Err("握手被 server 拒绝".to_string())
                        } else {
                            Ok(())
                        };
                    }
                }
            }
        })
        .await
        {
            Ok(r) => r,
            Err(_) => Err(format!(
                "握手超时（{}s 无应答），已强制终止",
                self.init_timeout.as_secs_f32() as i32
            )),
        };
        if let Err(msg) = handshake {
            child.kill().await.ok();
            self.set_status(id, "error", None, &msg).ok();
            return Err(msg);
        }
        stdin
            .write_all(rpc::initialized_notification().as_bytes())
            .await
            .ok();
        stdin.flush().await.ok();

        let gen = self.next_gen.fetch_add(1, Ordering::SeqCst);
        let mut procs = self.procs.lock().await;
        // 并发 start 兜底：握手期间别人先入 map 了，就杀掉自己这路，不产生双进程。
        if procs.contains_key(&id) {
            drop(procs);
            child.kill().await.ok();
            return Ok(("已在运行".into(), gen));
        }
        self.set_status(id, "running", pid, "").ok();
        procs.insert(
            id,
            ProcHandle {
                gen,
                child: Arc::new(Mutex::new(child)),
                stdin: Arc::new(Mutex::new(stdin)),
                responses: Arc::new(Mutex::new(rx)),
                logs,
            },
        );

        // supervise 由 start（对外入口）挂上，这里只负责进程本身。
        Ok(("已启动".into(), gen))
    }

    /// 停止：先把句柄移出 map（退出监听据此判「非异常」），再杀进程 → 标 stopped。
    pub async fn stop(&self, id: i64) -> MgrResult<String> {
        let handle = self.procs.lock().await.remove(&id);
        if let Some(h) = handle {
            h.child.lock().await.kill().await.ok();
            self.set_status(id, "stopped", None, "").ok();
            Ok("已停止".into())
        } else {
            self.set_status(id, "stopped", None, "").ok();
            Ok("本来就没在运行".into())
        }
    }

    /// 一键重启（AC-029）：stop + 计数 + start + 清零。
    pub async fn restart(&self, id: i64) -> MgrResult<String> {
        self.stop(id).await?;
        self.db
            .write(|c| core_state::mcp_store::bump_restart(c, id))
            .map_err(|e| format!("计数失败：{e}"))?;
        let msg = self.start(id).await?;
        self.db
            .write(|c| core_state::mcp_store::reset_restart_count(c, id))
            .ok();
        Ok(msg)
    }

    /// 请求 tools/list（MVP 供外部探测用）。
    /// 只短暂拿 map 锁克隆共享句柄，等待应答期间不阻塞其他 server 的操作。
    pub async fn list_tools(&self, id: i64) -> MgrResult<serde_json::Value> {
        let (stdin, responses) = {
            let procs = self.procs.lock().await;
            let h = procs.get(&id).ok_or("server 没在运行")?;
            (h.stdin.clone(), h.responses.clone())
        };
        {
            let mut w = stdin.lock().await;
            w.write_all(rpc::tools_list_request(2).as_bytes())
                .await
                .map_err(|e| format!("写请求失败：{e}"))?;
            w.flush().await.ok();
        }
        let mut responses = responses.lock().await;
        match tokio::time::timeout(Duration::from_secs(5), async {
            loop {
                let Some(line) = responses.recv().await else {
                    return Err("进程输出通道已关闭".to_string());
                };
                if let Some((rid, result)) = rpc::parse_response(&line) {
                    if rid == 2 {
                        return if rpc::is_rpc_error(&result) {
                            Err("server 拒绝了 tools/list".to_string())
                        } else {
                            Ok(result)
                        };
                    }
                }
            }
        })
        .await
        {
            Ok(r) => r,
            Err(_) => Err("tools/list 超时（5s）".to_string()),
        }
    }

    /// 环形日志快照（管理页日志抽屉）。
    pub async fn logs(&self, id: i64) -> Vec<String> {
        let procs = self.procs.lock().await;
        match procs.get(&id) {
            Some(h) => h.logs.lock().await.iter().cloned().collect(),
            None => Vec::new(),
        }
    }

    fn set_status(
        &self,
        id: i64,
        status: &str,
        pid: Option<u32>,
        msg: &str,
    ) -> core_state::DbResult<()> {
        self.db
            .write(|c| core_state::mcp_store::set_status(c, id, status, pid.map(|p| p as i64), msg))
    }
}

/// 退出等待：轮询 try_wait 而不是 hold 锁调 wait().await——
/// 否则 stop() 里的 kill 拿不到锁，会和这里互相等死（Windows 实测必现）。
/// 返回退出码（拿不到就 None）。
async fn wait_exit(child: &SharedChild) -> Option<i32> {
    loop {
        let status = {
            let mut c = child.lock().await;
            match c.try_wait() {
                Ok(opt) => opt,
                Err(_) => return None,
            }
        };
        let Some(s) = status else {
            tokio::time::sleep(Duration::from_millis(100)).await;
            continue;
        };
        return s.code();
    }
}

/// 异常退出监听循环（AC-029）：等当前进程退出 → 句柄仍在 map **且代次没变** = 异常退出 →
/// 标 error → 自动重启（超 MAX_AUTO_RESTARTS 次转 manual_required）。
/// 只调 `start_proc` 不再 spawn 新监听，重启后的进程由本循环继续看护（带新代次）。
async fn supervise(mgr: Manager, id: i64, mut gen: u64) {
    loop {
        // 当前进程句柄 + 代次（不在 map 里说明已被正常停掉，本监听退场）
        let child = {
            let procs = mgr.procs.lock().await;
            match procs.get(&id) {
                Some(h) if h.gen == gen => h.child.clone(),
                Some(_) => return, // map 里已是新一代：我们看护的那路被 stop→start 换掉了
                None => return,
            }
        };
        let code = wait_exit(&child).await;
        let current = {
            let mut procs = mgr.procs.lock().await;
            match procs.get(&id) {
                Some(h) if h.gen == gen => procs.remove(&id).map(|_| ()),
                _ => None, // 换血或已移除：不算异常
            }
        };
        if current.is_none() {
            return;
        }
        mgr.db
            .write(|c| {
                core_state::mcp_store::set_status(
                    c,
                    id,
                    "error",
                    None,
                    &format!("进程异常退出（退出码 {code:?}）"),
                )
            })
            .ok();
        if !mgr.auto_restart {
            return;
        }
        let count = mgr
            .db
            .write(|c| core_state::mcp_store::bump_restart(c, id))
            .unwrap_or(0);
        if count > MAX_AUTO_RESTARTS {
            mgr.db
                .write(|c| {
                    core_state::mcp_store::set_status(
                        c,
                        id,
                        "manual_required",
                        None,
                        "反复异常退出，已停止自动重启，请检查日志后手动重启",
                    )
                })
                .ok();
            return;
        }
        // 重启失败（起不来/握手失败）会标 error 且不入 map → 下一轮循环头取不到句柄即退场。
        if let Ok((_, new_gen)) = mgr.start_proc(id).await {
            gen = new_gen; // 继续看护新一代（不再 spawn 新监听）
            mgr.db
                .write(|c| core_state::mcp_store::reset_restart_count(c, id))
                .ok();
        }
    }
}
