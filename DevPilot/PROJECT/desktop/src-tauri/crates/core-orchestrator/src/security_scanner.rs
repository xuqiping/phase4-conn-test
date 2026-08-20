//! 本地静态安全扫描器（FR-040 / AC-044）。
//! 目前覆盖：密钥硬编码、鉴权缺失（启发式）、数据库/端口暴露、依赖漏洞审计。

use regex::Regex;
use serde::{Deserialize, Serialize};
use std::collections::HashSet;
use std::path::{Path, PathBuf};

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
        let mut findings = Vec::new();
        self.walk_files(&mut |rel, content| {
            if is_likely_example(rel) {
                return;
            }
            check_secrets(content, rel, &mut findings);
            check_auth(content, rel, &mut findings);
            check_db_exposure(content, rel, &mut findings);
        });

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

    fn walk_files(&self, cb: &mut dyn FnMut(&str, &str)) {
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
                let content = match std::fs::read_to_string(&path) {
                    Ok(c) => c,
                    Err(_) => continue,
                };
                cb(&rel, &content);
            }
        }
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

fn check_secrets(content: &str, rel: &str, findings: &mut Vec<Finding>) {
    let patterns: Vec<(&str, &str, &str, &str, &str)> = vec![
        (
            r"\b(sk-[a-zA-Z0-9]{20,})\b",
            "high",
            "secret",
            "疑似 OpenAI/Anthropic API Key 硬编码",
            "将密钥移入环境变量或 DevPilot Secrets；禁止写入源码",
        ),
        (
            r"\b(AK[A-Za-z0-9]{16,})\b",
            "high",
            "secret",
            "疑似云厂商 AccessKey 硬编码",
            "使用 IAM 角色或密钥管理服务，不要硬编码",
        ),
        (
            r"(-----BEGIN (?:RSA |EC |DSA |OPENSSH )?PRIVATE KEY-----)",
            "critical",
            "secret",
            "发现私钥文件内容",
            "立即吊销并重新生成；私钥必须放在密钥管理器",
        ),
        (
            r"\b(ghp_[a-zA-Z0-9]{36}|github_pat_[a-zA-Z0-9_]+)\b",
            "high",
            "secret",
            "疑似 GitHub Token 硬编码",
            "删除 Token 并重新生成，存放于环境变量",
        ),
        (
            r#"(?i)(?:password|passwd|pwd|secret|token|api_key)\s*[:=]\s*["']([^"']{8,})["']"#,
            "medium",
            "secret",
            "疑似密码/令牌硬编码",
            "使用环境变量或密钥管理服务",
        ),
    ];

    for (pat, severity, category, msg, suggestion) in patterns {
        let re = Regex::new(pat).expect("合法正则");
        for m in re.find_iter(content) {
            let line = line_number(content, m.start());
            let snip = snippet(content, m.start());
            // 脱敏：只展示前 8 位 + ...
            let mut masked = snip.clone();
            if let Some(caps) = re.captures(&snip) {
                if let Some(secret) = caps.get(1) {
                    let s = secret.as_str();
                    let masked_secret = if s.len() > 12 {
                        format!("{}...{}", &s[..8], &s[s.len() - 4..])
                    } else {
                        "***".into()
                    };
                    masked = format!("{} → {}", msg, masked_secret);
                }
            }
            push_finding(
                findings,
                severity,
                category,
                masked,
                rel,
                line,
                Some(snip),
                suggestion,
            );
        }
    }
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
    let route_re = Regex::new(r"@(?i:Get|Post|Put|Delete|Patch)\s*\(").expect("合法正则");
    for m in route_re.find_iter(content) {
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

fn check_db_exposure(content: &str, rel: &str, findings: &mut Vec<Finding>) {
    let patterns: Vec<(&str, &str, &str, &str)> = vec![
        (
            r"\b0\.0\.0\.0\b",
            "medium",
            "发现 0.0.0.0 监听地址，可能将服务暴露给公网",
            "生产环境绑定 127.0.0.1 或指定内网接口",
        ),
        (
            r#"(?i)(?:DATABASE_URL|DB_URL|MONGO_URL|MONGODB_URI|REDIS_URL)\s*[:=]\s*["']?[^\s"']+://[^\s"']+"#,
            "high",
            "数据库连接串疑似硬编码或包含密码",
            "使用环境变量注入凭据，不要在配置文件写密码",
        ),
        (
            r"(?i)(?:port|listen)\s*[:=]\s*(5432|3306|27017|6379)",
            "medium",
            "发现常见数据库默认端口暴露配置",
            "数据库端口不直接暴露在公网，使用私有网络或隧道",
        ),
    ];

    for (pat, severity, msg, suggestion) in patterns {
        let re = Regex::new(pat).expect("合法正则");
        for m in re.find_iter(content) {
            let line = line_number(content, m.start());
            push_finding(
                findings,
                severity,
                "database",
                msg,
                rel,
                line,
                Some(snippet(content, m.start())),
                suggestion,
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
    }
}
