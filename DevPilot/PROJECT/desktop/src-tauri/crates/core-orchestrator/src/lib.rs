//! core-orchestrator：chunk→任务编排、subagent 分派、产物生成器（PRD/plan/AGENTS.md）。
//! 对应 FR-003/030~035/013/015/036/037/038。

pub mod acceptance;
pub mod diff_summarizer;
pub mod security_scanner;
pub mod task_scheduler;

/// crate 名常量，供主程序装配自检（冒烟测试用）。
pub const CRATE_NAME: &str = "core-orchestrator";

#[cfg(test)]
mod tests {
    use super::CRATE_NAME;

    /// 冒烟测试：crate 可编译、可互引（P01 Step 2 验收）。
    #[test]
    fn smoke_placeholder() {
        assert_eq!(CRATE_NAME, "core-orchestrator");
    }
}
