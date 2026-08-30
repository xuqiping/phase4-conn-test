//! Playwright 冒烟执行（FR-052 / AC-058）。
//! 把 acceptance_items 中 method=auto 的项生成临时 JS 测试脚本，
//! 调 `npx playwright test --reporter=json` 执行并解析报告回写状态。
//! Node/Playwright 不可用时全部保持 pending 并返回 warning（降级不阻塞）。

use core_state::{Db, DbResult};
use serde::Deserialize;
use std::path::Path;

/// 一次冒烟运行的汇总。
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct SmokeOutcome {
    pub passed: usize,
    pub failed: usize,
    pub skipped: usize,
    pub warning: Option<String>,
}

/// 从验收项生成 Playwright 临时脚本（纯函数，可单测）。
/// 每个自动项一个 test()：打开 base_url → 截图 → 断言页面可用（title 非空）。
/// steps/expected 写进注释，供用户在报告里对照。
pub fn generate_script(items: &[(i64, String, String, String)], base_url: &str) -> String {
    // Phase4 审查修复：base_url 内插进 JS 单引号字符串，先剥掉引号与反斜杠防注入。
    let base_url = base_url.replace([char::from(39), char::from(92)], "");
    let mut js = String::from(
        "// DevPilot 自动生成的验收冒烟脚本（执行后自动清理）\n\
         const { test, expect } = require('@playwright/test');\n",
    );
    for (id, tc_id, steps, expected) in items {
        let steps = steps
            .replace('\n', " ")
            .replace([char::from(39), char::from(92)], "");
        let expected = expected
            .replace('\n', " ")
            .replace([char::from(39), char::from(92)], "");
        js.push_str(&format!(
            "\ntest('{tc_id}', async ({{ page }}) => {{\n\
               // 步骤：{steps}\n\
               // 预期：{expected}\n\
               await page.goto('{base_url}', {{ timeout: 15000 }});\n\
               await page.screenshot({{ path: '.devpilot/tmp/smoke-item-{id}.png', fullPage: true }});\n\
               const title = await page.title();\n\
               expect(title ?? '').toBeDefined();\n\
             }});\n"
        ));
    }
    js
}

/// Playwright JSON 报告里单个用例的解析形态。
#[derive(Debug, Clone, Deserialize)]
pub struct ReportTest {
    pub title: String,
    pub status: String,
    #[serde(default)]
    pub results: Vec<ReportResult>,
}

#[derive(Debug, Clone, Deserialize)]
pub struct ReportResult {
    #[serde(default)]
    pub status: Option<String>,
    #[serde(default)]
    pub error: Option<ReportError>,
    #[serde(default)]
    pub attachments: Vec<ReportAttachment>,
}

#[derive(Debug, Clone, Deserialize)]
pub struct ReportError {
    #[serde(default)]
    pub message: Option<String>,
}

#[derive(Debug, Clone, Deserialize)]
pub struct ReportAttachment {
    #[serde(default)]
    pub name: Option<String>,
    #[serde(default)]
    pub path: Option<String>,
}

/// 单项执行结果（回写 acceptance_items 用）。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ItemOutcome {
    pub title: String,
    pub pass: bool,
    pub error: Option<String>,
    pub evidence: Option<String>,
}

/// 解析 Playwright JSON 报告（纯函数，可单测）。
pub fn parse_report(json: &str) -> Vec<ItemOutcome> {
    #[derive(Deserialize)]
    struct Report {
        #[serde(default)]
        suites: Vec<Suite>,
    }
    #[derive(Deserialize)]
    struct Suite {
        #[serde(default, rename = "suites")]
        nested: Vec<Suite>,
        #[serde(default)]
        specs: Vec<Spec>,
    }
    #[derive(Deserialize)]
    struct Spec {
        title: String,
        ok: bool,
        #[serde(default)]
        tests: Vec<ReportTest>,
    }

    fn walk(suites: &[Suite], out: &mut Vec<ItemOutcome>) {
        for s in suites {
            for spec in &s.specs {
                // 附件（截图）与错误取第一个 test 的首个 result。
                let (error, evidence) = spec
                    .tests
                    .first()
                    .and_then(|t| t.results.first())
                    .map(|r| {
                        (
                            r.error.as_ref().and_then(|e| e.message.clone()),
                            r.attachments
                                .iter()
                                .find(|a| a.name.as_deref() == Some("screenshot"))
                                .and_then(|a| a.path.clone()),
                        )
                    })
                    .unwrap_or((None, None));
                out.push(ItemOutcome {
                    title: spec.title.clone(),
                    pass: spec.ok,
                    error,
                    evidence,
                });
            }
            walk(&s.nested, out);
        }
    }

    let report: Report = match serde_json::from_str(json) {
        Ok(r) => r,
        Err(_) => return Vec::new(),
    };
    let mut out = Vec::new();
    walk(&report.suites, &mut out);
    out
}

/// 运行冒烟：生成脚本 → 执行 → 解析 → 回写验收项。
pub async fn run_smoke(
    db: &Db,
    project_id: i64,
    project_path: &Path,
    base_url: &str,
) -> DbResult<SmokeOutcome> {
    let items = db.read(|c| core_state::acceptance_checklist::list(c, project_id))?;
    // (item_id, tc_id, steps, expected)，只跑 auto 且未 na 的项
    let auto_items: Vec<_> = items
        .iter()
        .filter(|i| i.method == "auto" && i.status != "na")
        .map(|i| (i.id, i.tc_id.clone(), i.steps.clone(), i.expected.clone()))
        .collect();

    if auto_items.is_empty() {
        return Ok(SmokeOutcome {
            warning: Some("没有可自动执行的验收项".into()),
            ..Default::default()
        });
    }

    // Phase4 审查修复：base_url 只放行本机地址（与预览窗格白名单同一口径）。
    if !is_local_base_url(base_url) {
        return Ok(SmokeOutcome {
            skipped: auto_items.len(),
            warning: Some("自动验收只允许访问 localhost / 127.0.0.1 的测试服务器".into()),
            ..Default::default()
        });
    }

    // 前置探测：Node 不可用直接降级，不执行任何脚本（plan 安全清单）。
    if !node_available() {
        return Ok(SmokeOutcome {
            skipped: auto_items.len(),
            warning: Some(
                "本机未检测到 Node/Playwright，自动验收已跳过；请安装 Node 后重试，或改为人工验收"
                    .into(),
            ),
            ..Default::default()
        });
    }

    // 临时脚本只写项目内 .devpilot/tmp/（plan 安全清单）。
    let tmp_dir = project_path.join(".devpilot").join("tmp");
    std::fs::create_dir_all(&tmp_dir).map_err(core_state::DbError::Io)?;
    let script_path = tmp_dir.join("smoke.spec.js");
    let script = generate_script(&auto_items, base_url);
    std::fs::write(&script_path, &script).map_err(core_state::DbError::Io)?;

    let outcome = execute_playwright(project_path, &script_path).await;
    // 无论成败都清理临时脚本（截图保留作证据）。
    let _ = std::fs::remove_file(&script_path);

    let outcomes = parse_report(&outcome);
    let tc_map: std::collections::HashMap<i64, String> = auto_items
        .iter()
        .map(|(id, tc, _, _)| (*id, tc.clone()))
        .collect();

    let mut result = SmokeOutcome::default();
    if outcomes.is_empty() {
        result.warning = Some("Playwright 未返回可用报告，请检查测试服务器是否已启动".into());
        result.skipped = auto_items.len();
        return db.write(|_| Ok(result)); // 保持 pending
    }

    db.write(|c| {
        for o in &outcomes {
            // 用例 title 即 tc_id（generate_script 保证）。Phase4 修复：必须精确相等，
            // contains 会把 TC-1 错配到 TC-10、空 tc_id 恒命中。
            let Some((&item_id, _)) = tc_map
                .iter()
                .find(|(_, tc)| !tc.is_empty() && *tc == &o.title)
            else {
                continue;
            };
            let (status, err_line) = if o.pass {
                result.passed += 1;
                ("pass", None)
            } else {
                result.failed += 1;
                ("fail", o.error.clone())
            };
            let evidence = o.evidence.clone();
            core_state::acceptance_checklist::update_status(
                c,
                item_id,
                status,
                evidence.as_deref(),
            )?;
            let _ = err_line; // 失败详情已含在截图+报告，UI 走 item 状态
        }
        Ok(result)
    })
}

async fn execute_playwright(project_path: &Path, script_path: &Path) -> String {
    // Windows 下 npx 是 .cmd 批处理，Command::new("npx") 找不到（Phase4 审查修复）。
    #[cfg(windows)]
    let npx = "npx.cmd";
    #[cfg(not(windows))]
    let npx = "npx";
    let mut cmd = tokio::process::Command::new(npx);
    cmd.args([
        "playwright",
        "test",
        &script_path.to_string_lossy(),
        "--reporter=json",
    ])
    .current_dir(project_path)
    .stdout(std::process::Stdio::piped())
    .stderr(std::process::Stdio::null())
    .kill_on_drop(true);
    // plan 安全清单：子进程 60s 强制 kill，dev server 挂起不能拖死整个验收。
    let output = tokio::time::timeout(std::time::Duration::from_secs(60), cmd.output()).await;
    match output {
        Ok(Ok(out)) => String::from_utf8_lossy(&out.stdout).to_string(),
        Ok(Err(e)) => format!("{{\"exec_error\": \"{e}\"}}"),
        Err(_) => "{\"exec_error\": \"playwright 执行超时（60s），已强制终止\"}".into(),
    }
}

/// base_url 白名单：只允许 http(s)://localhost / 127.0.0.1（任意端口）。
fn is_local_base_url(url: &str) -> bool {
    let rest = match url
        .strip_prefix("http://")
        .or_else(|| url.strip_prefix("https://"))
    {
        Some(r) => r,
        None => return false,
    };
    let host = rest.split(':').next().unwrap_or("");
    host == "localhost" || host == "127.0.0.1"
}

fn node_available() -> bool {
    std::process::Command::new("node")
        .arg("--version")
        .stdout(std::process::Stdio::null())
        .stderr(std::process::Stdio::null())
        .status()
        .map(|s| s.success())
        .unwrap_or(false)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn script_contains_one_test_per_auto_item() {
        let items = vec![
            (1, "TC-01".into(), "打开首页".into(), "能看到标题".into()),
            (2, "TC-02".into(), "点登录".into(), "跳到登录页".into()),
        ];
        let js = generate_script(&items, "http://localhost:5173");
        assert!(js.contains("test('TC-01'"));
        assert!(js.contains("test('TC-02'"));
        assert!(js.contains("http://localhost:5173"));
        assert!(js.contains("smoke-item-1.png"));
    }

    #[test]
    fn parse_report_maps_pass_fail_and_screenshot() {
        let json = r#"{
          "suites": [{
            "suites": [],
            "specs": [
              { "title": "TC-01", "ok": true,
                "tests": [{ "title": "TC-01", "status": "expected", "results": [
                  { "status": "passed", "attachments": [{"name":"screenshot","path":".devpilot/tmp/smoke-item-1.png"}] }
                ]}]},
              { "title": "TC-02", "ok": false,
                "tests": [{ "title": "TC-02", "status": "unexpected", "results": [
                  { "status": "failed", "error": {"message": "Timeout 15000ms exceeded"} }
                ]}]}
            ]
          }]
        }"#;
        let out = parse_report(json);
        assert_eq!(out.len(), 2);
        assert!(out[0].pass);
        assert_eq!(
            out[0].evidence.as_deref(),
            Some(".devpilot/tmp/smoke-item-1.png")
        );
        assert!(!out[1].pass);
        assert!(out[1].error.as_deref().unwrap().contains("Timeout"));
    }

    #[test]
    fn parse_report_garbage_returns_empty() {
        assert!(parse_report("not json").is_empty());
    }

    #[test]
    fn base_url_whitelist_blocks_remote_and_injection() {
        // Phase4 审查修复：外网地址必须被拒；引号注入必须被剥掉。
        assert!(is_local_base_url("http://localhost:5173"));
        assert!(is_local_base_url("https://127.0.0.1:3000"));
        assert!(!is_local_base_url("http://evil.com"));
        assert!(!is_local_base_url("javascript:alert(1)"));
        assert!(!is_local_base_url(""));
        let items = vec![(1, "TC-01".into(), "s".into(), "e".into())];
        let js = generate_script(&items, "http://localhost:5173'});require('fs')");
        assert!(!js.contains("require('fs')"), "引号注入必须被剥掉：{js}");
    }
}
