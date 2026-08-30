//! 验收阶段支持模块（FR-033 / FR-052）。
//! 包含测试方案解析器、Playwright 冒烟 runner（S8 实现）。

pub mod parser;
pub mod smoke;

pub use parser::{parse_project_test_plans, AcceptanceItemDraft, AcceptanceMethod};
