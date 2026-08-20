//! Playwright 冒烟执行（FR-052 / AC-058）。
//! S8 实现：将 acceptance_items 中 method=auto 的项转成临时 JS 测试脚本并运行。

use core_state::{Db, DbResult};
use std::path::Path;

/// 占位实现，S8 替换为真实 runner。
pub async fn run_smoke(_db: &Db, _project_id: i64, _project_path: &Path) -> DbResult<()> {
    Ok(())
}
