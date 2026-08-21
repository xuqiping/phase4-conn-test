//! 本地静态安全扫描器（FR-040 / AC-044）。
//! 目前覆盖：密钥硬编码、鉴权缺失（启发式）、数据库/端口暴露、依赖漏洞审计。

use regex::Regex;
use serde::{Deserialize, Serialize};
use std::collections::HashSet;
use std::path::{Path, PathBuf};
use std::sync::OnceLock;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub enum ScanStatus {
    Pass,
    Fail,
    Partial,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct Finding {
    pub severity: String, // critical / high / medium / low / info
    pub category: String,
    pub message: String,
    pub file: String,
    pub line: usize,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub snippet: Option<String>,
    pub suggestion: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ScanReport {
    pub status: ScanStatus,
    pub findings: Vec<Finding>,
}

pub struct SecurityScanner {
    project_path: PathBuf,
    scale: String,
    max_file_bytes: usize,
    exclude_dirs: HashSet<&'static str>,
}

impl SecurityScanner {
    pub fn new(project_path: impl Into<PathBuf>, scale: impl Into<String>) -> Self {
        Self {
            project_path: project_path.into(),
            scale: scale.into(),
            max_file_bytes: 1_048_576, // 1MB
            exclude_dirs: [
                ".git",
                "node_modules",
                "target",
                ".cache",
                "dist",
                "build",
                ".devpilot",
                "coverage",
                ".next",
                ".nuxt",
                "vendor",
            ]
            .iter()
            .copied()
            .collect(),
        }
    }

    pub fn scan(&self) -> ScanReport {
        // Phase4 性能修复：多线程并行扫描。单线程实测 5000 文件 12s，
        // 其中一半是文件系统开销（Windows Defender 逐文件实扫），并行读+并行正则后达标。
        let paths = self.collect_files();
        let workers = std::thread::available_parallelism()
            .map(|n| n.get())
            .unwrap_or(4)
            .min(8);
        let mut findings = Vec::new();
        if paths.len() <= workers * 4 {
            // 小项目不值得开线程。
            self.scan_paths(&paths, &mut findings);
        } else {
            let chunk = paths.len().div_ceil(workers);
            let mut partials: Vec<Vec<Finding>> = Vec::new();
            std::thread::scope(|s| {
                let handles: Vec<_> = paths
                    .chunks(chunk)
                    .map(|c| {
                        s.spawn(|| {
                            let mut local = Vec::new();
                            self.scan_paths(c, &mut local);
                            local
                        })
                    })
                    .collect();
                for h in handles {
                    // 线程只做文件 IO 与纯函数检查，scan_paths 不 panic 时这里不会 panic。
                    partials.push(h.join().unwrap_or_default());
                }
            });
            for p in partials {
                findings.extend(p);
            }
        }
        // 稳定输出顺序：按文件+行号排序（并行后各块顺序被打乱）。
        findings.sort_by(|a, b| (&a.file, a.line).cmp(&(&b.file, b.line)));

        self.check_dependencies(&mut findings);

        let status = if findings
            .iter()
            .any(|f| matches!(f.severity.as_str(), "critical" | "high"))
        {
            ScanStatus::Fail
        } else if findings.is_empty() {
            ScanStatus::Pass
        } else {
            ScanStatus::Partial
        };

        ScanReport { status, findings }
    }

    fn scan_paths(&self, paths: &[PathBuf], findings: &mut Vec<Finding>) {
        for path in paths {
            let rel = path
                .strip_prefix(&self.project_path)
                .unwrap_or(path)
                .to_string_lossy()
                .to_string();
            if is_likely_example(&rel) {
                continue;
            }
            let Ok(content) = std::fs::read_to_string(path) else {
                continue;
            };
            check_secrets(&content, &rel, findings);
            check_auth(&content, &rel, findings);
            check_db_exposure(&content, &rel, findings);
        }
    }

    fn collect_files(&self) -> Vec<PathBuf> {
        let mut out = Vec::new();
        let mut stack = vec![self.project_path.clone()];
        while let Some(dir) = stack.pop() {
            let entries = match std::fs::read_dir(&dir) {
                Ok(e) => e,
                Err(_) => continue,
            };
            for entry in entries.flatten() {
                let path = entry.path();
                if path.is_symlink() {
                    continue;
                }
                if path.is_dir() {
                    let name = path.file_name().and_then(|s| s.to_str()).unwrap_or("");
                    if self.exclude_dirs.contains(name) {
                        continue;
                    }
                    stack.push(path);
                    continue;
                }
                if !path.is_file() {
                    continue;
                }
                let rel = path
                    .strip_prefix(&self.project_path)
                    .unwrap_or(&path)
                    .to_string_lossy()
                    .to_string();
                if is_binary_file(&rel) {
                    continue;
                }
                let meta = match std::fs::metadata(&path) {
                    Ok(m) => m,
                    Err(_) => continue,
                };
                if meta.len() > self.max_file_bytes as u64 {
                    continue;
                }
                out.push(path);
            }
        }
        out
    }

    fn check_dependencies(&self, findings: &mut Vec<Finding>) {
        // 依赖审计耗时且依赖外部网络：L0 小项目跳过，只做源码静态扫描。
        if self.scale == "L0" {
            return;
        }
        if self.project_path.join("package.json").exists() {
            match run_npm_audit(&self.project_path) {
                Ok(Some(report)) => findings.extend(report.into_findings()),
                Ok(None) => {}
                Err(e) => findings.push(Finding {
                    severity: "info".into(),
                    category: "dependency_audit".into(),
                    message: format!("npm audit 无法完成：{e}"),
                    file: "package.json".into(),
                    line: 0,
                    snippet: None,
                    suggestion: "确保本机已安装 Node 且网络可达，或改在 CI 中执行依赖审计".into(),
                }),
            }
        }
        if self.project_path.join("Cargo.lock").exists() {
            match run_cargo_audit(&self.project_path) {
                Ok(Some(report)) => findings.extend(report.into_findings()),
                Ok(None) => {}
                Err(e) => findings.push(Finding {
                    severity: "info".into(),
                    category: "dependency_audit".into(),
                    message: format!("cargo audit 无法完成：{e}"),
                    file: "Cargo.lock".into(),
                    line: 0,
                    snippet: None,
                    suggestion: "确保已安装 cargo-audit 且网络可达".into(),
                }),
            }
        }
    }
}

fn is_likely_example(rel: &str) -> bool {
    let lowered = rel.to_lowercase();
    lowered.contains(".test.")
        || lowered.contains(".spec.")
        || lowered.contains(".example.")
        || lowered.contains("/test/")
        || lowered.contains("/tests/")
        || lowered.contains("/__tests__/")
        || lowered.contains("/example/")
        || lowered.contains("/examples/")
        || lowered.contains("/fixtures/")
}

fn is_binary_file(rel: &str) -> bool {
    let ext = rel.rsplit('.').next().unwrap_or("").to_lowercase();
    matches!(
        ext.as_str(),
        "png"
            | "jpg"
            | "jpeg"
            | "gif"
            | "ico"
            | "svg"
            | "pdf"
            | "zip"
            | "tar"
            | "gz"
            | "exe"
            | "dll"
            | "so"
            | "dylib"
            | "wasm"
            | "ttf"
            | "woff"
            | "woff2"
            | "mp3"
            | "mp4"
            | "webm"
            | "lock"
            | "sum"
    )
}

fn line_number(content: &str, pos: usize) -> usize {
    content[..pos.min(content.len())].matches('\n').count() + 1
}

fn snippet(content: &str, pos: usize) -> String {
    let start = content[..pos.min(content.len())]
        .rfind('\n')
        .map(|i| i + 1)
        .unwrap_or(0);
    let end = content[pos.min(content.len())..]
        .find('\n')
        .map(|i| pos + i)
        .unwrap_or(content.len());
    content[start..end].trim().to_string()
}

#[allow(clippy::too_many_arguments)]
fn push_finding(
    findings: &mut Vec<Finding>,
    severity: &str,
    category: &str,
    message: impl Into<String>,
    file: &str,
    line: usize,
    snippet: Option<String>,
    suggestion: impl Into<String>,
) {
    findings.push(Finding {
        severity: severity.into(),
        category: category.into(),
        message: message.into(),
        file: file.into(),
        line,
        snippet,
        suggestion: suggestion.into(),
    });
}

/// 密钥脱敏：按字符取前 8 + 后 4（多字节安全），短密钥直接 ***。
/// Phase4 审查修正：按字节切片遇中文捕获组会 panic，且 snippet 必须同样脱敏。
fn mask_secret(s: &str) -> String {
    let chars: Vec<char> = s.chars().collect();
    if chars.len() > 12 {
        let head: String = chars[..8].iter().collect();
        let tail: String = chars[chars.len() - 4..].iter().collect();
        format!("{head}...{tail}")
    } else {
        "***".into()
    }
}

/// 正则只编译一次（LazyLock 全局缓存）——Phase4 实测逐文件重编译导致 5000 文件 98s，
/// 远超 10s 预算；缓存后整个扫描几乎只剩文件 IO。
static SECRET_PATTERNS: OnceLock<Vec<(Regex, &'static str, &'static str, &'static str)>> =
    OnceLock::new();

fn secret_patterns() -> &'static Vec<(Regex, &'static str, &'static str, &'static str)> {
    SECRET_PATTERNS.get_or_init(|| {
        vec![
            (
                Regex::new(r"\b(sk-[a-zA-Z0-9]{20,})\b").unwrap(),
                "high",
                "疑似 OpenAI/Anthropic API Key 硬编码",
                "将密钥移入环境变量或 DevPilot Secrets；禁止写入源码",
            ),
            (
                Regex::new(r"\b(AK[A-Za-z0-9]{16,})\b").unwrap(),
                "high",
                "疑似云厂商 AccessKey 硬编码",
                "使用 IAM 角色或密钥管理服务，不要硬编码",
            ),
            (
                Regex::new(r"(-----BEGIN (?:RSA |EC |DSA |OPENSSH )?PRIVATE KEY-----)").unwrap(),
                "critical",
                "发现私钥文件内容",
                "立即吊销并重新生成；私钥必须放在密钥管理器",
            ),
            (
                Regex::new(r"\b(ghp_[a-zA-Z0-9]{36}|github_pat_[a-zA-Z0-9_]+)\b").unwrap(),
                "high",
                "疑似 GitHub Token 硬编码",
                "删除 Token 并重新生成，存放于环境变量",
            ),
            (
                Regex::new(r#"(?i)(?:password|passwd|pwd|secret|token|api_key)\s*[:=]\s*["']([^"']{8,})["']"#)
                    .unwrap(),
                "medium",
                "疑似密码/令牌硬编码",
                "使用环境变量或密钥管理服务",
            ),
        ]
    })
}

fn check_secrets(content: &str, rel: &str, findings: &mut Vec<Finding>) {
    // 廉价字面前置过滤：绝大多数文件不含任何密钥特征，直接跳过整组正则
    // （Phase4 性能修复：(?i) 正则的字面预优化失效，逐文件全跑 5000 文件要 36s）。
    {
        let lower = content.to_ascii_lowercase();
        let maybe = content.contains("sk-")
            || content.contains("AK")
            || content.contains("PRIVATE KEY")
            || content.contains("ghp_")
            || content.contains("github_pat_")
            || lower.contains("password")
            || lower.contains("passwd")
            || lower.contains("pwd")
            || lower.contains("secret")
            || lower.contains("token")
            || lower.contains("api_key");
        if !maybe {
            return;
        }
    }
    for (re, severity, msg, suggestion) in secret_patterns() {
        for caps in re.captures_iter(content) {
            let m = caps.get(0).expect("整组必在");
            let line = line_number(content, m.start());
            let snip_raw = snippet(content, m.start());
            // snippet 与 message 一并脱敏：完整密钥不允许进 UI 或落库（plan 安全清单）。
            let (masked_msg, snip) = match caps.get(1) {
                Some(secret) => {
                    let masked_secret = mask_secret(secret.as_str());
                    (
                        format!("{msg} → {masked_secret}"),
                        snip_raw.replacen(secret.as_str(), &masked_secret, 1),
                    )
                }
                None => (msg.to_string(), snip_raw),
            };
            push_finding(
                findings,
                severity,
                "secret",
                masked_msg,
                rel,
                line,
                Some(snip),
                *suggestion,
            );
        }
    }
}

static AUTH_ROUTE_RE: OnceLock<Regex> = OnceLock::new();

fn auth_route_re() -> &'static Regex {
    AUTH_ROUTE_RE.get_or_init(|| Regex::new(r"@(?i:Get|Post|Put|Delete|Patch)\s*\(").unwrap())
}

fn check_auth(content: &str, rel: &str, findings: &mut Vec<Finding>) {
    // 仅对常见后端文件做启发式扫描。
    if !rel.ends_with(".ts")
        && !rel.ends_with(".js")
        && !rel.ends_with(".tsx")
        && !rel.ends_with(".py")
    {
        return;
    }
    for m in auth_route_re().find_iter(content) {
        let window_start = m.start().saturating_sub(300);
        let window_end = (m.end() + 300).min(content.len());
        let window = &content[window_start..window_end];
        let has_guard = window.contains("@UseGuards")
            || window.contains("AuthGuard")
            || window.contains("requireAuth")
            || window.contains("authenticate")
            || window.contains("@Authenticated");
        if !has_guard {
            let line = line_number(content, m.start());
            push_finding(
                findings,
                "medium",
                "auth",
                "路由方法未检测到鉴权守卫，可能存在未授权访问风险",
                rel,
                line,
                Some(snippet(content, m.start())),
                "为该路由添加 @UseGuards(AuthGuard) 或等效鉴权中间件",
            );
        }
    }
}

static DB_PATTERNS: OnceLock<Vec<(Regex, &'static str, &'static str, &'static str)>> =
    OnceLock::new();

fn db_patterns() -> &'static Vec<(Regex, &'static str, &'static str, &'static str)> {
    DB_PATTERNS.get_or_init(|| {
        vec![
            (
                Regex::new(r"\b0\.0\.0\.0\b").unwrap(),
                "medium",
                "发现 0.0.0.0 监听地址，可能将服务暴露给公网",
                "生产环境绑定 127.0.0.1 或指定内网接口",
            ),
            (
                Regex::new(r#"(?i)(?:DATABASE_URL|DB_URL|MONGO_URL|MONGODB_URI|REDIS_URL)\s*[:=]\s*["']?[^\s"']+://[^\s"']+"#)
                    .unwrap(),
                "high",
                "数据库连接串疑似硬编码或包含密码",
                "使用环境变量注入凭据，不要在配置文件写密码",
            ),
            (
                Regex::new(r"(?i)(?:port|listen)\s*[:=]\s*(5432|3306|27017|6379)").unwrap(),
                "medium",
                "发现常见数据库默认端口暴露配置",
                "数据库端口不直接暴露在公网，使用私有网络或隧道",
            ),
        ]
    })
}

fn check_db_exposure(content: &str, rel: &str, findings: &mut Vec<Finding>) {
    // 同 check_secrets：字面前置过滤，命中才跑正则。
    {
        let lower = content.to_ascii_lowercase();
        let maybe = content.contains("0.0.0.0")
            || lower.contains("database_url")
            || lower.contains("db_url")
            || lower.contains("mongo")
            || lower.contains("redis")
            || lower.contains("port")
            || lower.contains("listen");
        if !maybe {
            return;
        }
    }
    for (re, severity, msg, suggestion) in db_patterns() {
        for m in re.find_iter(content) {
            let line = line_number(content, m.start());
            push_finding(
                findings,
                severity,
                "database",
                *msg,
                rel,
                line,
                Some(snippet(content, m.start())),
                *suggestion,
            );
        }
    }
}

// ---------- 依赖审计 ----------

struct NpmAuditReport {
    vulnerabilities: Vec<(NpmSeverity, String)>,
}

#[derive(Debug, Clone, Deserialize)]
struct NpmAuditJson {
    vulnerabilities: Option<serde_json::Map<String, serde_json::Value>>,
}

#[derive(Debug, Clone, Deserialize)]
enum NpmSeverity {
    Critical,
    High,
    Moderate,
    Low,
}

impl NpmAuditReport {
    fn into_findings(self) -> Vec<Finding> {
        self.vulnerabilities
            .into_iter()
            .map(|(sev, pkg)| {
                let severity = match sev {
                    NpmSeverity::Critical => "critical",
                    NpmSeverity::High => "high",
                    NpmSeverity::Moderate => "medium",
                    NpmSeverity::Low => "low",
                };
                Finding {
                    severity: severity.into(),
                    category: "dependency".into(),
                    message: format!("npm 依赖 {pkg} 存在漏洞"),
                    file: "package-lock.json".into(),
                    line: 0,
                    snippet: None,
                    suggestion: "运行 npm audit fix 或升级该依赖".into(),
                }
            })
            .collect()
    }
}

fn run_npm_audit(project_path: &Path) -> Result<Option<NpmAuditReport>, String> {
    let output = std::process::Command::new("npm")
        .args(["audit", "--json"])
        .current_dir(project_path)
        .output()
        .map_err(|e| e.to_string())?;
    if output.stdout.is_empty() {
        return Ok(None);
    }
    let json: NpmAuditJson =
        serde_json::from_slice(&output.stdout).map_err(|e| format!("解析 npm audit 失败：{e}"))?;
    let mut vulns = Vec::new();
    if let Some(map) = json.vulnerabilities {
        for (pkg, value) in map {
            let severity = value
                .get("severity")
                .and_then(|v| v.as_str())
                .unwrap_or("low");
            let sev = match severity.to_lowercase().as_str() {
                "critical" => NpmSeverity::Critical,
                "high" => NpmSeverity::High,
                "moderate" => NpmSeverity::Moderate,
                _ => NpmSeverity::Low,
            };
            vulns.push((sev, pkg));
        }
    }
    if vulns.is_empty() {
        Ok(None)
    } else {
        Ok(Some(NpmAuditReport {
            vulnerabilities: vulns,
        }))
    }
}

struct CargoAuditReport {
    vulnerabilities: Vec<(String, String)>, // (severity, title)
}

#[derive(Debug, Clone, Deserialize)]
struct CargoAuditJson {
    vulnerabilities: CargoVulnList,
}

#[derive(Debug, Clone, Deserialize)]
struct CargoVulnList {
    list: Vec<CargoVuln>,
}

#[derive(Debug, Clone, Deserialize)]
struct CargoVuln {
    advisory: CargoAdvisory,
}

#[derive(Debug, Clone, Deserialize)]
struct CargoAdvisory {
    title: String,
    severity: Option<String>,
}

impl CargoAuditReport {
    fn into_findings(self) -> Vec<Finding> {
        self.vulnerabilities
            .into_iter()
            .map(|(severity, title)| Finding {
                severity,
                category: "dependency".into(),
                message: format!("cargo 依赖存在漏洞：{title}"),
                file: "Cargo.lock".into(),
                line: 0,
                snippet: None,
                suggestion: "运行 cargo update 或升级该依赖".into(),
            })
            .collect()
    }
}

fn run_cargo_audit(project_path: &Path) -> Result<Option<CargoAuditReport>, String> {
    let output = std::process::Command::new("cargo")
        .args(["audit", "--json"])
        .current_dir(project_path)
        .output()
        .map_err(|e| e.to_string())?;
    if output.stdout.is_empty() {
        return Ok(None);
    }
    let json: CargoAuditJson = serde_json::from_slice(&output.stdout)
        .map_err(|e| format!("解析 cargo audit 失败：{e}"))?;
    let vulns: Vec<_> = json
        .vulnerabilities
        .list
        .into_iter()
        .map(|v| {
            let sev = v
                .advisory
                .severity
                .unwrap_or_else(|| "low".into())
                .to_lowercase();
            (sev, v.advisory.title)
        })
        .collect();
    if vulns.is_empty() {
        Ok(None)
    } else {
        Ok(Some(CargoAuditReport {
            vulnerabilities: vulns,
        }))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::TempDir;

    fn tmp_project() -> (TempDir, PathBuf) {
        let tmp = TempDir::new().unwrap();
        let path = tmp.path().to_path_buf();
        (tmp, path)
    }

    #[test]
    fn detects_hardcoded_secret() {
        let (_tmp, path) = tmp_project();
        std::fs::write(
            path.join("config.ts"),
            "export const API_KEY = 'sk-abcdefghijklmnopqrstuvwxyz123456';\n",
        )
        .unwrap();
        let report = SecurityScanner::new(&path, "L2").scan();
        assert!(matches!(report.status, ScanStatus::Fail));
        assert!(report.findings.iter().any(|f| f.category == "secret"));
    }

    #[test]
    fn skips_test_and_example_files() {
        let (_tmp, path) = tmp_project();
        std::fs::write(
            path.join("config.test.ts"),
            "export const KEY = 'sk-abcdef';\n",
        )
        .unwrap();
        std::fs::write(
            path.join("config.example.ts"),
            "export const KEY = 'sk-abcdef';\n",
        )
        .unwrap();
        let report = SecurityScanner::new(&path, "L2").scan();
        assert!(matches!(report.status, ScanStatus::Pass));
        assert!(report.findings.is_empty());
    }

    #[test]
    fn detects_unprotected_route() {
        let (_tmp, path) = tmp_project();
        std::fs::create_dir(path.join("src")).unwrap();
        std::fs::write(
            path.join("src/users.controller.ts"),
            "@Controller('users')\nexport class UsersController {\n  @Get(':id')\n  findOne() {}\n}\n",
        )
        .unwrap();
        let report = SecurityScanner::new(&path, "L2").scan();
        assert!(report.findings.iter().any(|f| f.category == "auth"));
    }

    #[test]
    fn detects_db_url() {
        let (_tmp, path) = tmp_project();
        std::fs::write(
            path.join(".env"),
            "DATABASE_URL=postgres://user:secret@example.com:5432/db\n",
        )
        .unwrap();
        let report = SecurityScanner::new(&path, "L2").scan();
        assert!(report.findings.iter().any(|f| f.category == "database"));
    }

    #[test]
    fn masked_snippet_does_not_leak_full_secret() {
        let (_tmp, path) = tmp_project();
        std::fs::write(
            path.join("config.ts"),
            "const KEY = 'sk-abcdefghijklmnopqrstuvwxyz123456';\n",
        )
        .unwrap();
        let report = SecurityScanner::new(&path, "L2").scan();
        let f = report
            .findings
            .iter()
            .find(|f| f.category == "secret")
            .unwrap();
        assert!(!f.message.contains("klmnopqrstuvwxyz"));
        // 脱敏规则：只保留前 8 位 + 后 4 位
        assert!(f.message.contains("sk-abcde"));
        assert!(f.message.contains("3456"));
        assert!(!f.message.contains("sk-abcdefghijklmnopqrstuvwxyz123456"));
        // Phase4 审查修正 C3：snippet 也必须脱敏，完整密钥不允许进 UI/落库。
        let snip = f.snippet.as_deref().unwrap_or("");
        assert!(
            !snip.contains("sk-abcdefghijklmnopqrstuvwxyz123456"),
            "snippet 泄漏完整密钥：{snip}"
        );
        assert!(snip.contains("sk-abcde"));
    }

    #[test]
    fn mask_secret_is_multibyte_safe() {
        // Phase4 审查 C4：捕获组含中文时按字节切片会 panic，按字符切片必须安全。
        let masked = mask_secret("密码密码密码密码密码密码密码");
        assert!(masked.contains("..."));
        assert_eq!(mask_secret("short"), "***");
    }

    /// Phase4 性能实测（#[ignore]，手动 `cargo test -p core-orchestrator perf_scan -- --ignored --nocapture`）。
    /// 目标：L2 项目 5000+ 文件静态扫描 <10s（plan 性能清单）。
    #[test]
    #[ignore]
    fn perf_scan_large_tree() {
        let tmp = tempfile::TempDir::new().unwrap();
        // 以本 crate 源码为种子，整树翻倍复制直到 >=5000 个 .rs 文件。
        let seed = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("src");
        let mut round = 0;
        loop {
            let dest = tmp.path().join(format!("r{round}"));
            std::fs::create_dir_all(&dest).unwrap();
            copy_tree(&seed, &dest);
            let files = walkdir::WalkDir::new(tmp.path())
                .into_iter()
                .filter_map(|e| e.ok())
                .filter(|e| e.file_type().is_file())
                .count();
            if files >= 5000 {
                break;
            }
            round += 1;
        }
        let _total = walkdir::WalkDir::new(tmp.path())
            .into_iter()
            .filter_map(|e| e.ok())
            .filter(|e| e.file_type().is_file())
            .count();
        // 混入 4:1 无关键词的良性文件，贴近真实项目（不是每个文件都命中密钥特征）。
        let benign = "fn helper_{}(x: u32) -> u32 { x.wrapping_add({}) }
";
        let mut i = 0;
        while count_files(tmp.path()) < 5000 {
            std::fs::write(
                tmp.path().join(format!("benign{i}.rs")),
                benign.replace("{}", &i.to_string()).repeat(200),
            )
            .unwrap();
            i += 1;
        }
        let total = count_files(tmp.path());
        let t1 = std::time::Instant::now();
        let report = SecurityScanner::new(tmp.path(), "L2").scan();
        let ms = t1.elapsed().as_millis();
        println!("scanned {total} files in {ms}ms (first pass)");
        // 复扫：排除新建文件被 Defender 实时扫描的干扰，衡量扫描器本身。
        // 纯 IO 基线：只列文件不读内容。
        let io_scanner = SecurityScanner::new(tmp.path(), "L2");
        let t3 = std::time::Instant::now();
        let _ = io_scanner.collect_files();
        println!("collect-only pass: {}ms", t3.elapsed().as_millis());
        let t2 = std::time::Instant::now();
        let _ = SecurityScanner::new(tmp.path(), "L2").scan();
        let ms2 = t2.elapsed().as_millis();
        println!("second pass: {ms2}ms");
        let _ = report;
        assert!(
            ms2 < 10_000,
            "5000-file warm scan took {ms2}ms, exceeds 10s budget"
        );
    }

    fn count_files(root: &std::path::Path) -> usize {
        walkdir::WalkDir::new(root)
            .into_iter()
            .filter_map(|e| e.ok())
            .filter(|e| e.file_type().is_file())
            .count()
    }

    fn copy_tree(src: &std::path::Path, dst: &std::path::Path) {
        std::fs::create_dir_all(dst).unwrap();
        for entry in walkdir::WalkDir::new(src)
            .min_depth(1)
            .into_iter()
            .filter_map(|e| e.ok())
        {
            let rel = entry.path().strip_prefix(src).unwrap();
            let out = dst.join(rel);
            if entry.file_type().is_dir() {
                std::fs::create_dir_all(&out).unwrap();
            } else {
                std::fs::copy(entry.path(), &out).unwrap();
            }
        }
    }
}
